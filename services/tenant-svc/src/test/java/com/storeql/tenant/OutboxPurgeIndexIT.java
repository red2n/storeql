package com.storeql.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxStore;
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
 * The outbox's indexes, on the schema tenant-svc migrates.
 *
 * <p>The scheduled purge of delivered rows deletes only what is past its cutoff, in bounded
 * batches, and the idx_outbox_published index of V1__init.sql is what lets a batch find its rows
 * without reading the table.
 *
 * <p>The relay's claim is served by two indexes: its ordered scan (ORDER BY created_at, id LIMIT n)
 * by idx_outbox_claim, and its per-aggregate check (an aggregate's earlier unpublished rows) by
 * idx_outbox_aggregate_pending. The test plans the claim SQL as the shared repository really
 * prepares it (recorded off a real drain, never a copy that could drift). V1 carries no
 * idx_outbox_unpublished, an index of every waiting row by created_at: it would also hold the dead
 * letters, which the claim's ordered scan never reads, and the claim is the one statement that
 * reads waiting rows in that order.
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
      st.execute("TRUNCATE TABLE outbox");
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

  private static int count(String where) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM outbox WHERE " + where)) {
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
  @DisplayName("The purge's lookup has an index of its own, and it is partial")
  void theIndexExists() throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexname || ' ' || indexdef FROM pg_indexes"
                    + " WHERE schemaname = 'public' AND tablename = 'outbox'")) {
      while (rs.next()) found.add(rs.getString(1));
    }
    String all = String.join("\n", found);
    assertTrue(all.contains("idx_outbox_published"), all);
    assertTrue(
        all.contains("(published_at) WHERE (published_at IS NOT NULL)"),
        "holds only delivered rows: " + all);
    assertTrue(all.contains("idx_outbox_claim"), "the drain's index is there: " + all);
    assertTrue(all.contains("idx_outbox_aggregate_pending"), "and the per-aggregate one: " + all);
    // That index would hold every waiting row, dead letters too, ordered by created_at only: a
    // superset of the claim index's rows, without its id tie-break. The claim's ordered scan never
    // reads the dead letters: idx_outbox_claim serves it.
    assertFalse(
        all.contains("idx_outbox_unpublished"),
        "no index of every waiting row by created_at: " + all);
  }

  /** The columns the relay's rows are told apart by. */
  private record Row(
      Instant createdAt,
      Instant publishedAt,
      Instant deadAt,
      Instant nextAttemptAt,
      java.util.UUID aggregate) {}

  private static void outbox(Row r) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at, published_at, dead_at, next_attempt_at)"
                    + " VALUES (?, 'Probe', 'storeql.test', NULL, ?, '{}', ?, ?, ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, r.aggregate());
      ps.setObject(3, r.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(4, r.publishedAt() == null ? null : r.publishedAt().atOffset(ZoneOffset.UTC));
      ps.setObject(5, r.deadAt() == null ? null : r.deadAt().atOffset(ZoneOffset.UTC));
      ps.setObject(6, r.nextAttemptAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /** A data source that remembers every statement prepared on its connections. */
  private static DataSource recording(DataSource real, List<String> prepared) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(method, real, args);
              if (!"getConnection".equals(method.getName())) return result;
              Connection connection = (Connection) result;
              return Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (p, m, a) -> {
                    if ("prepareStatement".equals(m.getName())
                        && a != null
                        && a.length > 0
                        && a[0] instanceof String sql) {
                      prepared.add(sql);
                    }
                    return invoke(m, connection, a);
                  });
            });
  }

  private static Object invoke(java.lang.reflect.Method m, Object target, Object[] args)
      throws Throwable {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  @Test
  @DisplayName(
      "The relay's real claim scans idx_outbox_claim in order and checks each aggregate through"
          + " idx_outbox_aggregate_pending")
  void theClaimIsServedByItsTwoIndexes() throws SQLException {
    Instant now = Instant.now();
    // The mix a relay meets: waiting rows, dead letters, rows backing off, and delivered ones.
    for (int i = 0; i < 400; i++) {
      outbox(new Row(now.minus(Duration.ofSeconds(600 - i)), null, null, now, Ids.newId()));
    }
    for (int i = 0; i < 40; i++) {
      outbox(
          new Row(
              now.minus(Duration.ofSeconds(900 + i)),
              null,
              now.minusSeconds(60),
              now,
              Ids.newId()));
    }
    for (int i = 0; i < 40; i++) {
      outbox(
          new Row(
              now.minus(Duration.ofSeconds(1000 + i)),
              null,
              null,
              now.plus(Duration.ofMinutes(5)),
              Ids.newId()));
    }
    for (int i = 0; i < 400; i++) {
      outbox(new Row(now.minus(DAY), now.minus(Duration.ofHours(2)), null, now, Ids.newId()));
    }
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("ANALYZE outbox");
    }

    // One real drain over the shared repository, through a data source that records what it
    // prepares: the claim is the SELECT from outbox, exactly as BaseOutboxRepository writes it.
    List<String> prepared = new ArrayList<>();
    Outbox repo = new Outbox(recording(PG.dataSource(), prepared));
    try (OutboxStore.DrainLease lease = repo.tryDrainLock().orElseThrow()) {
      OutboxStore.DrainResult drained =
          repo.drainOnce(lease, 100, rows -> new OutboxStore.PublishOutcome(List.of(), Map.of()));
      assertEquals(100, drained.claimed(), "the claim found a full batch of the waiting rows");
    }
    List<String> claims = prepared.stream().filter(sql -> sql.contains("FROM outbox o")).toList();
    assertEquals(1, claims.size(), "one claim statement: " + prepared);
    String claim = claims.get(0);
    assertTrue(
        claim.contains("o.dead_at IS NULL"), "the real claim, dead letters excluded: " + claim);
    assertTrue(claim.contains("o.next_attempt_at <= now()"), "and backoff honoured: " + claim);
    assertTrue(claim.contains("ORDER BY o.created_at, o.id LIMIT ?"), claim);

    String plan = plan(claim.replace("LIMIT ?", "LIMIT 100"));
    assertTrue(plan.contains("idx_outbox_claim"), plan);
    assertTrue(
        plan.contains("idx_outbox_aggregate_pending"),
        "and the check of an aggregate's earlier rows reads its own index: " + plan);
    assertFalse(
        plan.contains("Sort"), "the index already holds them in the claim's order: " + plan);
    assertFalse(plan.contains("idx_outbox_unpublished"), plan);
  }

  @Test
  @DisplayName("A batch of the purge finds its oldest rows through the index, in their order")
  void aBatchIsServedByTheIndex() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 40; i++) {
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10 + i)));
    }
    String published =
        plan(
            "SELECT id FROM outbox WHERE published_at IS NOT NULL"
                + " AND published_at < now() - interval '7 days' ORDER BY published_at ASC LIMIT 5000");
    assertTrue(published.contains("idx_outbox_published"), published);
    assertFalse(published.contains("Sort"), "the index already holds them in order: " + published);
  }

  @Test
  @DisplayName("The purge takes delivered rows past the cutoff in bounded batches, and no others")
  void thePurgeTakesOnlyWhatIsPastItsCutoff() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 5; i++) {
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10)));
    }
    for (int i = 0; i < 3; i++) outbox(now.minus(DAY), now.minus(Duration.ofHours(1)));
    // Never delivered, however old: it still has to reach Kafka.
    for (int i = 0; i < 4; i++) outbox(now.minus(DAY.multipliedBy(60)), null);

    Outbox repo = new Outbox(PG.dataSource());
    Instant cutoff = now.minus(DAY.multipliedBy(7));
    assertEquals(2, repo.purgePublished(cutoff, 2));
    assertEquals(2, repo.purgePublished(cutoff, 2));
    assertEquals(1, repo.purgePublished(cutoff, 2), "the last, short batch ends the sweep");
    assertEquals(0, repo.purgePublished(cutoff, 2));

    assertEquals(3, count("published_at IS NOT NULL"), "delivered this week stay");
    assertEquals(4, count("published_at IS NULL"), "undelivered rows are never purged");
  }

  @Test
  @DisplayName(
      "tenant-svc keeps no processed_events table, so its dedupe purge finds nothing to do")
  void thereIsNoDedupeTableToPurge() {
    Outbox repo = new Outbox(PG.dataSource());
    assertEquals(0, repo.purgeProcessedEvents(Instant.now(), 100));
    assertEquals(0, repo.purgeProcessedEvents(Instant.now(), 100), "and it stops asking");
  }
}
