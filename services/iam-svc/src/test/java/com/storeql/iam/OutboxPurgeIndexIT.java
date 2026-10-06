package com.storeql.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxStore.PendingOutbox;
import com.storeql.service.OutboxStore.PublishOutcome;
import com.storeql.test.PostgresSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbox relay and its housekeeping, on the schema iam-svc migrates. The scheduled purge of
 * delivered outbox rows and old consumer dedupe rows deletes only what is past its cutoff, in
 * bounded batches, and the indexes of V1__init.sql (outbox) and V2__processed_events.sql are what
 * let a batch find its rows without reading the table. The relay's own claim, the statement
 * BaseOutboxRepository really runs, is read through the indexes of V1__init.sql too:
 * idx_outbox_claim serves its ordered scan and idx_outbox_aggregate_pending its per-aggregate
 * check, both on the rows still waiting, and no index of every waiting row by created_at
 * (idx_outbox_unpublished) is kept.
 */
class OutboxPurgeIndexIT {

  private static final PostgresSupport PG =
      PostgresSupport.start().migrate("classpath:db/migration");

  /** The shared purge, over this test's database. */
  private static final class Outbox extends BaseOutboxRepository {
    Outbox(DataSource ds) {
      this.dataSource = ds;
    }
  }

  private static final Duration DAY = Duration.ofDays(1);

  @AfterAll
  static void stop() {
    PG.stop();
  }

  @BeforeEach
  void empty() throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("TRUNCATE TABLE outbox, processed_events");
    }
  }

  private static void outbox(Instant createdAt, Instant publishedAt) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at, published_at) VALUES (?, 'Probe', 'storeql.test', NULL, ?, '{}',"
                    + " ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.newId());
      ps.setObject(3, createdAt.atOffset(ZoneOffset.UTC));
      if (publishedAt == null) {
        ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(4, publishedAt.atOffset(ZoneOffset.UTC));
      }
      ps.executeUpdate();
    }
  }

  /** A row still waiting, written at {@code createdAt}, for one aggregate. */
  private static void waiting(
      java.util.UUID aggregate, Instant createdAt, boolean dead, Instant nextAttemptAt)
      throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at, next_attempt_at, dead_at)"
                    + " VALUES (?, 'Probe', 'storeql.test', NULL, ?, '{}', ?, ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, aggregate);
      ps.setObject(3, createdAt.atOffset(ZoneOffset.UTC));
      ps.setObject(4, nextAttemptAt.atOffset(ZoneOffset.UTC));
      if (dead) {
        ps.setObject(5, createdAt.atOffset(ZoneOffset.UTC));
      } else {
        ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
      }
      ps.executeUpdate();
    }
  }

  /** Every statement prepared on a connection of {@code real} is added to {@code sql}. */
  private static DataSource recording(DataSource real, List<String> sql) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              try {
                Object result = method.invoke(real, args);
                if ("getConnection".equals(method.getName()) && result instanceof Connection c) {
                  return (Connection)
                      Proxy.newProxyInstance(
                          Connection.class.getClassLoader(),
                          new Class<?>[] {Connection.class},
                          (cp, cm, cargs) -> {
                            if ("prepareStatement".equals(cm.getName())
                                && cargs != null
                                && cargs.length > 0
                                && cargs[0] instanceof String text) {
                              sql.add(text);
                            }
                            try {
                              return cm.invoke(c, cargs);
                            } catch (InvocationTargetException e) {
                              throw e.getCause();
                            }
                          });
                }
                return result;
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static void processed(Instant at) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO processed_events (event_id, consumer, processed_at)"
                    + " VALUES (?, 'probe', ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, at.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private static int count(String table, String where) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table + " WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /**
   * The plan of a query with sequential and bitmap scans off: what it does when it can avoid them.
   */
  private static String plan(String query) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      // Nor a bitmap scan, which would need a sort: with both off only an ordered index scan is
      // left to serve ORDER BY ... LIMIT, if an index can.
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      StringBuilder out = new StringBuilder();
      try (ResultSet rs = st.executeQuery("EXPLAIN " + query)) {
        while (rs.next()) out.append(rs.getString(1)).append('\n');
      }
      return out.toString();
    }
  }

  @Test
  @DisplayName(
      "The purge's two lookups each have an index of their own, and the outbox's is partial")
  void theIndexesExist() throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexname || ' ' || indexdef FROM pg_indexes"
                    + " WHERE schemaname = 'public' AND tablename IN ('outbox', 'processed_events')")) {
      while (rs.next()) found.add(rs.getString(1));
    }
    String all = String.join("\n", found);
    assertTrue(all.contains("idx_outbox_published"), all);
    assertTrue(
        all.contains("(published_at) WHERE (published_at IS NOT NULL)"),
        "holds only delivered rows: " + all);
    assertTrue(all.contains("idx_processed_events_processed_at"), all);
    assertTrue(
        all.contains("idx_outbox_claim")
            && all.contains(
                "(created_at, id) WHERE ((published_at IS NULL) AND (dead_at IS NULL))"),
        "the claim's index holds the waiting rows that are not dead, in the claim's order: " + all);
    assertTrue(
        all.contains("idx_outbox_aggregate_pending")
            && all.contains("(aggregate_id, created_at, id) WHERE (published_at IS NULL)"),
        "the claim's per-aggregate check has its own index on the waiting rows: " + all);
    assertFalse(
        all.contains("idx_outbox_unpublished"),
        "idx_outbox_unpublished is not kept: idx_outbox_claim holds every row the claim can"
            + " return, in its order, and idx_outbox_aggregate_pending serves the per-aggregate"
            + " check: "
            + all);
  }

  /**
   * Runs the relay's real drain once, takes the claim statement it prepared (so the test cannot
   * drift from BaseOutboxRepository), and plans that exact statement: its dead_at and backoff
   * conditions and its per-aggregate NOT EXISTS included. Sequential and bitmap scans are off, so
   * whatever index serves the claim shows in the plan.
   */
  @Test
  @DisplayName(
      "The relay's real claim scans idx_outbox_claim in order and checks each aggregate through"
          + " idx_outbox_aggregate_pending")
  void theRealClaimIsServedByTheClaimIndex() throws SQLException {
    Instant now = Instant.now();
    java.util.UUID blocked = Ids.newId();
    // Waiting rows of many aggregates, a dead letter and a row backing off with a row behind each,
    // and delivered rows: the shapes the claim has to tell apart.
    for (int i = 0; i < 60; i++) {
      waiting(Ids.newId(), now.minus(Duration.ofMinutes(60 - i)), false, now.minus(DAY));
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10)));
    }
    waiting(blocked, now.minus(Duration.ofHours(3)), true, now.minus(DAY));
    waiting(blocked, now.minus(Duration.ofHours(2)), false, now.minus(DAY));
    java.util.UUID backing = Ids.newId();
    waiting(backing, now.minus(Duration.ofHours(3)), false, now.plus(DAY));
    waiting(backing, now.minus(Duration.ofHours(2)), false, now.minus(DAY));

    List<String> sql = new ArrayList<>();
    Outbox repo = new Outbox(recording(PG.dataSource(), sql));
    List<PendingOutbox> claimed = new ArrayList<>();
    try (var lease = repo.tryDrainLock().orElseThrow()) {
      repo.drainOnce(
          lease,
          100,
          rows -> {
            claimed.addAll(rows);
            return new PublishOutcome(rows.stream().map(PendingOutbox::id).toList(), Map.of());
          });
    }
    assertEquals(60, claimed.size(), "the dead letter, the backoff and the rows behind them wait");
    assertFalse(
        claimed.stream().anyMatch(r -> blocked.equals(r.aggregateId())),
        "a dead letter holds back its own aggregate");
    assertFalse(
        claimed.stream().anyMatch(r -> backing.equals(r.aggregateId())),
        "a row backing off holds back its own aggregate");

    String claim =
        sql.stream()
            .filter(
                s -> s.startsWith("SELECT o.id, o.aggregate_id, o.topic, o.payload FROM outbox"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the drain prepared no claim: " + sql));
    assertTrue(claim.contains("o.dead_at IS NULL"), claim);
    assertTrue(claim.contains("o.next_attempt_at <= now()"), claim);
    assertTrue(claim.contains("NOT EXISTS"), claim);
    String plan = plan(claim.replace("LIMIT ?", "LIMIT 100"));
    assertTrue(plan.contains("idx_outbox_claim"), "the ordered scan is served: " + plan);
    assertTrue(
        plan.contains("idx_outbox_aggregate_pending"),
        "the per-aggregate check, inside the claim, is served: " + plan);
    assertFalse(plan.contains("Sort"), "the index already holds them in order: " + plan);
    assertFalse(plan.contains("idx_outbox_unpublished"), plan);
    assertEquals(
        0,
        count("pg_indexes", "tablename = 'outbox' AND indexname = 'idx_outbox_unpublished'"),
        "idx_outbox_unpublished is not kept, and the claim's plan above reads idx_outbox_claim"
            + " and idx_outbox_aggregate_pending");
  }

  @Test
  @DisplayName("A batch of the purge finds its oldest rows through the indexes, in their order")
  void aBatchIsServedByTheIndexes() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 40; i++) {
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10 + i)));
      processed(now.minus(DAY.multipliedBy(40 + i)));
    }
    String published =
        plan(
            "SELECT id FROM outbox WHERE published_at IS NOT NULL"
                + " AND published_at < now() - interval '7 days' ORDER BY published_at ASC LIMIT 5000");
    assertTrue(published.contains("idx_outbox_published"), published);
    assertFalse(published.contains("Sort"), "the index already holds them in order: " + published);
    String dedupe =
        plan(
            "SELECT event_id FROM processed_events WHERE processed_at < now() - interval '30 days'"
                + " ORDER BY processed_at ASC LIMIT 5000");
    assertTrue(dedupe.contains("idx_processed_events_processed_at"), dedupe);
    assertFalse(dedupe.contains("Sort"), "the index already holds them in order: " + dedupe);
  }

  @Test
  @DisplayName("The purge takes delivered rows past the cutoff in bounded batches, and no others")
  void thePurgeTakesOnlyWhatIsPastItsCutoff() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 5; i++)
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10)));
    for (int i = 0; i < 3; i++) outbox(now.minus(DAY), now.minus(Duration.ofHours(1)));
    // Never delivered, however old: it still has to reach Kafka.
    for (int i = 0; i < 4; i++) outbox(now.minus(DAY.multipliedBy(60)), null);

    Outbox repo = new Outbox(PG.dataSource());
    Instant cutoff = now.minus(DAY.multipliedBy(7));
    assertEquals(2, repo.purgePublished(cutoff, 2));
    assertEquals(2, repo.purgePublished(cutoff, 2));
    assertEquals(1, repo.purgePublished(cutoff, 2), "the last, short batch ends the sweep");
    assertEquals(0, repo.purgePublished(cutoff, 2));

    assertEquals(3, count("outbox", "published_at IS NOT NULL"), "delivered this week stay");
    assertEquals(4, count("outbox", "published_at IS NULL"), "undelivered rows are never purged");
  }

  @Test
  @DisplayName("Consumer dedupe rows go once older than the cutoff, in bounded batches")
  void dedupeRowsGoWhenOldEnough() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 6; i++) processed(now.minus(DAY.multipliedBy(40)));
    for (int i = 0; i < 2; i++) processed(now.minus(DAY));

    Outbox repo = new Outbox(PG.dataSource());
    Instant cutoff = now.minus(DAY.multipliedBy(30));
    assertEquals(4, repo.purgeProcessedEvents(cutoff, 4));
    assertEquals(2, repo.purgeProcessedEvents(cutoff, 4));
    assertEquals(0, repo.purgeProcessedEvents(cutoff, 4));
    assertEquals(2, count("processed_events", "true"), "yesterday's are still there");
  }
}
