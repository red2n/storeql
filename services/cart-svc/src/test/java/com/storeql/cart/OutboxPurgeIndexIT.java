package com.storeql.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxStore.DrainLease;
import com.storeql.service.OutboxStore.DrainResult;
import com.storeql.service.OutboxStore.PendingOutbox;
import com.storeql.service.OutboxStore.PublishOutcome;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The indexes the relay's drain and the hourly purge depend on, on the schema cart-svc migrates.
 *
 * <p>The relay claims through {@code BaseOutboxRepository.claim}, which reads rows that are neither
 * published nor dead, past their backoff, and not behind a dead or backing-off row of their own
 * aggregate. That statement is planned here exactly as the repository writes it, dead-letter and
 * backoff conditions included: its ordered scan reads {@code idx_outbox_claim} and its check for an
 * earlier waiting row of the same aggregate reads {@code idx_outbox_aggregate_pending}. The shared
 * repository's other statements on the outbox are the insert, the marking of a row published and
 * the recording of a failure (both by primary key) and the purge of published rows (its index is
 * {@code idx_outbox_published}), so the claim is the one statement that reads waiting rows in
 * {@code created_at} order. An index of every waiting row by {@code created_at} ({@code
 * idx_outbox_unpublished}) would also hold the dead letters, which that scan never reads, and
 * V3__outbox.sql creates none. The outbox is read across every business, so it has no tenant-led
 * index.
 */
class OutboxPurgeIndexIT {

  private static final String SCHEMA = "cart";

  private static final PostgresSupport PG = PostgresSupport.start();

  static {
    // The service's own schema, which Flyway creates and makes the default, as the service does.
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

  /**
   * The relay's claim, copied from {@code BaseOutboxRepository.claim} with its limit written out.
   */
  private static final String CLAIM =
      "SELECT o.id, o.aggregate_id, o.topic, o.payload FROM outbox o"
          + " WHERE o.published_at IS NULL AND o.dead_at IS NULL"
          + " AND o.next_attempt_at <= now()"
          + " AND NOT EXISTS (SELECT 1 FROM outbox p"
          + "   WHERE p.aggregate_id = o.aggregate_id AND p.published_at IS NULL"
          + "   AND (p.dead_at IS NOT NULL OR p.next_attempt_at > now())"
          + "   AND (p.created_at, p.id) < (o.created_at, o.id))"
          + " ORDER BY o.created_at, o.id LIMIT 100";

  private static final Duration DAY = Duration.ofDays(1);

  @AfterAll
  static void stop() {
    PG.stop();
  }

  /** A connection source whose tables are the service's own, as the service's is. */
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

  /** How an outbox row stands: when it was made, and what became of it. */
  private record Row(
      Instant createdAt,
      Instant publishedAt,
      Instant deadAt,
      Instant nextAttemptAt,
      UUID aggregate) {

    static Row waiting(Instant createdAt) {
      return new Row(createdAt, null, null, null, Ids.newId());
    }

    static Row published(Instant createdAt, Instant publishedAt) {
      return new Row(createdAt, publishedAt, null, null, Ids.newId());
    }
  }

  private static UUID outbox(Row r) throws SQLException {
    UUID id = Ids.newId();
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at, published_at, dead_at, next_attempt_at)"
                    + " VALUES (?, 'Probe', 'storeql.test', ?, ?, '{}', ?, ?, ?,"
                    + " coalesce(?, now()))")) {
      ps.setObject(1, id);
      ps.setObject(2, Ids.newId());
      ps.setObject(3, r.aggregate());
      ps.setObject(4, r.createdAt().atOffset(ZoneOffset.UTC));
      setInstant(ps, 5, r.publishedAt());
      setInstant(ps, 6, r.deadAt());
      setInstant(ps, 7, r.nextAttemptAt());
      ps.executeUpdate();
    }
    return id;
  }

  private static void setInstant(PreparedStatement ps, int at, Instant value) throws SQLException {
    if (value == null) {
      ps.setNull(at, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      ps.setObject(at, value.atOffset(ZoneOffset.UTC));
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

  /**
   * The plan of a query with sequential and bitmap scans off: what it does when it can avoid them.
   */
  private static String plan(String query) throws SQLException {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      // Nor a bitmap scan, which would need a sort: on a table that was never analysed the planner
      // may well prefer one, and with both off only an ordered index scan is left to serve
      // ORDER BY ... LIMIT, if an index can.
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
      "The claim, the purge and the dedupe purge have indexes; the one nothing used is gone")
  void theIndexesExist() throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexname || ' ' || indexdef FROM pg_indexes"
                    + " WHERE schemaname = '"
                    + SCHEMA
                    + "' AND tablename IN ('outbox', 'processed_events')")) {
      while (rs.next()) found.add(rs.getString(1));
    }
    String all = String.join("\n", found);
    assertTrue(
        all.contains("(created_at, id) WHERE ((published_at IS NULL) AND (dead_at IS NULL))"),
        "the claim's index holds only what may be claimed, in the claim's order: " + all);
    assertTrue(
        all.contains("(published_at) WHERE (published_at IS NOT NULL)"),
        "the purge's index holds only delivered rows: " + all);
    assertTrue(
        all.contains("(aggregate_id, created_at, id) WHERE (published_at IS NULL)"),
        "the per-aggregate check has its own: " + all);
    assertTrue(all.contains("idx_processed_events_processed_at"), all);
    assertFalse(
        all.contains("idx_outbox_unpublished"),
        "it would also hold the dead letters, which the claim's ordered scan never reads: " + all);
  }

  @Test
  @DisplayName("The relay's real claim, with its dead-letter and backoff filters, reads its index")
  void theRealClaimIsServedByTheClaimIndex() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 40; i++) {
      outbox(Row.waiting(now.minus(Duration.ofMinutes(i))));
      outbox(Row.published(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10))));
      outbox(new Row(now.minus(DAY), null, now.minus(Duration.ofHours(1)), null, Ids.newId()));
      outbox(new Row(now.minus(DAY), null, null, now.plus(Duration.ofHours(1)), Ids.newId()));
    }
    String claim = plan(CLAIM);
    assertTrue(claim.contains("idx_outbox_claim"), claim);
    assertFalse(claim.contains("Sort"), "the index already holds them in order: " + claim);
    assertTrue(
        claim.contains("idx_outbox_aggregate_pending"),
        "an aggregate's earlier unpublished rows are found by aggregate: " + claim);
  }

  @Test
  @DisplayName("The relay claims what may publish now, and not a dead, backing-off or sent row")
  void theRealDrainClaimsOnlyWhatMayPublishNow() throws SQLException {
    Instant now = Instant.now();
    UUID older = outbox(Row.waiting(now.minus(Duration.ofMinutes(5))));
    UUID newer = outbox(Row.waiting(now.minus(Duration.ofMinutes(1))));
    outbox(Row.published(now.minus(DAY), now.minus(Duration.ofHours(1))));
    outbox(new Row(now.minus(DAY), null, now.minus(Duration.ofHours(1)), null, Ids.newId()));
    outbox(new Row(now.minus(DAY), null, null, now.plus(Duration.ofHours(1)), Ids.newId()));
    // A dead letter holds back its own aggregate and no other: the row behind it waits.
    UUID held = Ids.newId();
    outbox(new Row(now.minus(DAY), null, now.minus(Duration.ofHours(1)), null, held));
    outbox(new Row(now.minus(Duration.ofHours(2)), null, null, null, held));

    Outbox repo = new Outbox(dataSource());
    Set<UUID> claimed = new HashSet<>();
    DrainResult result;
    try (DrainLease lease = repo.tryDrainLock().orElseThrow()) {
      result =
          repo.drainOnce(
              lease,
              100,
              rows -> {
                rows.stream().map(PendingOutbox::id).forEach(claimed::add);
                return new PublishOutcome(rows.stream().map(PendingOutbox::id).toList(), Map.of());
              });
    }
    assertEquals(Set.of(older, newer), claimed);
    assertEquals(2, result.claimed());
  }

  @Test
  @DisplayName("A batch of the purge finds its oldest rows through the indexes, in their order")
  void aBatchIsServedByTheIndexes() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 40; i++) {
      outbox(Row.published(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10 + i))));
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
}
