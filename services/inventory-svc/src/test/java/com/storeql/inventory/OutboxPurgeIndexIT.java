package com.storeql.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxStore;
import com.storeql.test.PostgresSupport;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The outbox of the schema inventory-svc migrates: what drains it and what purges it find their
 * rows through an index, and the indexes the table keeps are exactly those that serve a statement:
 * the claim's ordered scan reads idx_outbox_claim, its check for an earlier waiting row of the same
 * aggregate reads idx_outbox_aggregate_pending, marking a row published and recording a failure go
 * by primary key, and the purge reads idx_outbox_published. The relay's claim is planned as
 * BaseOutboxRepository really prepares it, with its dead-letter and backoff conditions and its
 * per-aggregate check: the statement is read off the repository's own connection while it claims,
 * so there is no copy of it here to drift.
 *
 * <p>The outbox is deliberately cross-tenant: the relay drains every business's rows in the order
 * they were written, and no statement on the table filters by {@code tenant_id}, so no outbox index
 * leads with it.
 */
class OutboxPurgeIndexIT {

  private static final String SCHEMA = "inventory";

  private static final PostgresSupport PG = PostgresSupport.start();

  static {
    Flyway.configure()
        .dataSource(PG.jdbcUrl(), PG.username(), PG.password())
        .locations("classpath:db/migration")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .load()
        .migrate();
  }

  /** The shared outbox repository, over this test's database. */
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

  private static DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(PG.jdbcUrl());
    ds.setUser(PG.username());
    ds.setPassword(PG.password());
    ds.setCurrentSchema(SCHEMA);
    return ds;
  }

  @BeforeEach
  void empty() throws SQLException {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("TRUNCATE TABLE outbox, processed_events");
    }
  }

  /**
   * One outbox row. A null {@code deadAt} is a row still in play; a null {@code nextAttemptAt} is
   * due at once (the column's default, now).
   */
  private static void outbox(
      UUID aggregateId,
      Instant createdAt,
      Instant publishedAt,
      Instant deadAt,
      Instant nextAttemptAt)
      throws SQLException {
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at, published_at, dead_at, next_attempt_at)"
                    + " VALUES (?, 'Probe', 'storeql.test', ?, ?, '{}', ?, ?, ?,"
                    + " coalesce(?, now()))")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.newId());
      ps.setObject(3, aggregateId);
      ps.setObject(4, createdAt.atOffset(ZoneOffset.UTC));
      setInstant(ps, 5, publishedAt);
      setInstant(ps, 6, deadAt);
      setInstant(ps, 7, nextAttemptAt);
      ps.executeUpdate();
    }
  }

  private static void setInstant(PreparedStatement ps, int index, Instant at) throws SQLException {
    if (at == null) {
      ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      ps.setObject(index, at.atOffset(ZoneOffset.UTC));
    }
  }

  private static void processed(Instant at) throws SQLException {
    try (Connection c = dataSource().getConnection();
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
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table + " WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /**
   * The plan of a statement, bound as it is bound to run, with sequential and bitmap scans off:
   * what the planner does when it can avoid them. A bitmap scan would need a sort, and on a table
   * that was never analysed the planner may well prefer one; with both off, only an ordered index
   * scan is left to serve an {@code ORDER BY ... LIMIT}, if an index can.
   */
  private static String plan(String query, Object... params) throws SQLException {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      StringBuilder out = new StringBuilder();
      try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + query)) {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.append(rs.getString(1)).append('\n');
        }
      }
      return out.toString();
    }
  }

  @Test
  @DisplayName(
      "The outbox keeps one index for the claim, one for the per-aggregate check and one for the"
          + " purge, and none of every waiting row by created_at")
  void theIndexesExist() throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexname || ' ' || indexdef FROM pg_indexes WHERE schemaname = '"
                    + SCHEMA
                    + "' AND tablename IN ('outbox', 'processed_events')")) {
      while (rs.next()) found.add(rs.getString(1));
    }
    String all = String.join("\n", found);
    assertTrue(
        all.contains(
            "idx_outbox_claim ON inventory.outbox USING btree (created_at, id)"
                + " WHERE ((published_at IS NULL) AND (dead_at IS NULL))"),
        "the claim's own index holds only the rows still in play, in the claim's order: " + all);
    assertTrue(
        all.contains("idx_outbox_aggregate_pending ON inventory.outbox USING btree (aggregate_id,"),
        "the claim's per-aggregate check has its own index: " + all);
    assertTrue(
        all.contains("(published_at) WHERE (published_at IS NOT NULL)"),
        "the purge's index holds only delivered rows: " + all);
    assertTrue(all.contains("idx_processed_events_processed_at"), all);
    assertEquals(
        0,
        count(
            "pg_indexes", "schemaname = '" + SCHEMA + "' AND indexname = 'idx_outbox_unpublished'"),
        "no index of every waiting row by created_at: it would also hold the dead letters, which"
            + " the claim's ordered scan never reads: "
            + all);
    // The outbox is cross-tenant on purpose: the relay drains every business's rows by age, so
    // there is no tenant-led index to find.
    for (String index : found) {
      if (index.contains(" ON inventory.outbox ")) {
        assertFalse(index.contains("(tenant_id"), "the outbox has no tenant-led index: " + index);
      }
    }
    assertEquals(
        new TreeSet<>(
            List.of(
                "idx_outbox_aggregate_pending",
                "idx_outbox_claim",
                "idx_outbox_published",
                "outbox_pkey")),
        new TreeSet<>(
            found.stream()
                .filter(i -> i.contains(" ON inventory.outbox "))
                .map(i -> i.substring(0, i.indexOf(' ')))
                .toList()),
        "the outbox's indexes are these and no others: " + all);
  }

  /**
   * Plans the relay's claim exactly as BaseOutboxRepository prepares it: the dead-letter and
   * backoff conditions and the per-aggregate NOT EXISTS included. The table holds rows of every
   * kind the claim has to look past: published, dead, backing off, and due.
   */
  @Test
  @DisplayName(
      "The relay's real claim, dead-letter and backoff conditions and all, reads the rows still in"
          + " play through idx_outbox_claim, already in order")
  void theRelaysClaimIsServedByItsIndex() throws SQLException {
    Instant now = Instant.now();
    int due = 40;
    for (int i = 0; i < due; i++) {
      Instant at = now.minus(Duration.ofMinutes(i + 10));
      outbox(Ids.newId(), at, null, null, null);
      outbox(Ids.newId(), at, now.minus(DAY.multipliedBy(10)), null, null);
      outbox(Ids.newId(), at, null, now.minus(Duration.ofMinutes(5)), now.minus(DAY));
      outbox(Ids.newId(), at, null, null, now.plus(Duration.ofMinutes(30)));
    }

    List<String> prepared = new CopyOnWriteArrayList<>();
    Outbox repo = new Outbox(RecordingDataSource.of(dataSource(), prepared));
    List<OutboxStore.PendingOutbox> claimed = new ArrayList<>();
    try (OutboxStore.DrainLease lease = repo.tryDrainLock().orElseThrow()) {
      repo.drainOnce(
          lease,
          500,
          rows -> {
            claimed.addAll(rows);
            return new OutboxStore.PublishOutcome(List.of(), Map.of());
          });
    }
    assertEquals(
        due, claimed.size(), "the claim skips the published, the dead and the backing-off rows");

    List<String> claims = prepared.stream().filter(sql -> sql.contains("FROM outbox o")).toList();
    assertEquals(1, claims.size(), "one statement is the claim: " + prepared);
    String claim = claims.get(0);
    assertTrue(claim.contains("dead_at IS NULL"), claim);
    assertTrue(claim.contains("next_attempt_at <= now()"), claim);
    assertTrue(claim.contains("NOT EXISTS"), claim);

    String plan = plan(claim, 100);
    assertTrue(plan.contains("idx_outbox_claim"), plan);
    assertFalse(plan.contains("Sort"), "the index already holds them in order: " + plan);
    assertTrue(
        plan.contains("idx_outbox_aggregate_pending"),
        "the per-aggregate check is served by its own index: " + plan);
  }

  @Test
  @DisplayName("A batch of the purge finds its oldest rows through the indexes, in their order")
  void aBatchIsServedByTheIndexes() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 40; i++) {
      outbox(
          Ids.newId(),
          now.minus(DAY.multipliedBy(30)),
          now.minus(DAY.multipliedBy(10 + i)),
          null,
          null);
      processed(now.minus(DAY.multipliedBy(40 + i)));
    }
    String published =
        plan(
            "SELECT id FROM outbox WHERE published_at IS NOT NULL"
                + " AND published_at < now() - interval '7 days' ORDER BY published_at ASC"
                + " LIMIT 5000");
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
    for (int i = 0; i < 5; i++) {
      outbox(
          Ids.newId(),
          now.minus(DAY.multipliedBy(30)),
          now.minus(DAY.multipliedBy(10)),
          null,
          null);
    }
    for (int i = 0; i < 3; i++) {
      outbox(Ids.newId(), now.minus(DAY), now.minus(Duration.ofHours(1)), null, null);
    }
    // Never delivered, however old: it still has to reach Kafka.
    for (int i = 0; i < 4; i++) {
      outbox(Ids.newId(), now.minus(DAY.multipliedBy(60)), null, null, null);
    }

    Outbox repo = new Outbox(dataSource());
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
    for (int i = 0; i < 6; i++) {
      processed(now.minus(DAY.multipliedBy(40)));
    }
    for (int i = 0; i < 2; i++) {
      processed(now.minus(DAY));
    }

    Outbox repo = new Outbox(dataSource());
    Instant cutoff = now.minus(DAY.multipliedBy(30));
    assertEquals(4, repo.purgeProcessedEvents(cutoff, 4));
    assertEquals(2, repo.purgeProcessedEvents(cutoff, 4));
    assertEquals(0, repo.purgeProcessedEvents(cutoff, 4));
    assertEquals(2, count("processed_events", "true"), "yesterday's are still there");
  }
}
