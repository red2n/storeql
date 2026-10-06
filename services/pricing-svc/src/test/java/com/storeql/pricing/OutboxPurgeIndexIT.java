package com.storeql.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbox's indexes on the schema pricing-svc migrates. The scheduled purge of delivered outbox
 * rows and old consumer dedupe rows deletes only what is past its cutoff, in bounded batches, and
 * the indexes on outbox (V1__init.sql) and on processed_events (V8__catalogue_projection.sql) are
 * what let a batch find its rows without reading the table. The relay's claim, with its dead-letter
 * and backoff conditions, is served by two indexes: its ordered scan by idx_outbox_claim and its
 * check for an earlier waiting row of the same aggregate by idx_outbox_aggregate_pending. No index
 * of every waiting row by created_at (idx_outbox_unpublished) is kept beside them.
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

  /**
   * The relay's claim, as BaseOutboxRepository.claim sends it: the rows that may publish now, in
   * the order they were written, not behind an earlier row of their aggregate that is dead or
   * backing off. Copied from the shared repository's private method with its one parameter, the
   * limit, as {@code $1}; {@link #theCopyOfTheClaimIsTheClaim} fails when the two stop agreeing.
   */
  private static final String CLAIM =
      "SELECT o.id, o.aggregate_id, o.topic, o.payload FROM outbox o"
          + " WHERE o.published_at IS NULL AND o.dead_at IS NULL"
          + " AND o.next_attempt_at <= now()"
          + " AND NOT EXISTS (SELECT 1 FROM outbox p"
          + "   WHERE p.aggregate_id = o.aggregate_id AND p.published_at IS NULL"
          + "   AND (p.dead_at IS NOT NULL OR p.next_attempt_at > now())"
          + "   AND (p.created_at, p.id) < (o.created_at, o.id))"
          + " ORDER BY o.created_at, o.id LIMIT $1";

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

  /** An unpublished row of one aggregate: live, backing off until {@code retryAt}, or dead. */
  private static UUID pending(UUID aggregate, Instant createdAt, Instant retryAt, boolean dead)
      throws SQLException {
    UUID id = Ids.newId();
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at, next_attempt_at, dead_at, attempts)"
                    + " VALUES (?, 'Probe', 'storeql.test', NULL, ?, '{}', ?, ?,"
                    + " CASE WHEN ? THEN now() END, CASE WHEN ? THEN 10 ELSE 0 END)")) {
      ps.setObject(1, id);
      ps.setObject(2, aggregate);
      ps.setObject(3, createdAt.atOffset(ZoneOffset.UTC));
      ps.setObject(4, retryAt.atOffset(ZoneOffset.UTC));
      ps.setBoolean(5, dead);
      ps.setBoolean(6, dead);
      ps.executeUpdate();
    }
    return id;
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
   * The plan of the relay's claim as the planner chooses it for a backlog this size, prepared as
   * the driver prepares it. A prepared statement is planned for its first executions with the value
   * known and after that without it (a generic plan), so the claim is planned both ways.
   */
  private static String claimPlan(boolean generic) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      try {
        st.execute(
            "SET plan_cache_mode = " + (generic ? "force_generic_plan" : "force_custom_plan"));
        st.execute("PREPARE claim(int) AS " + CLAIM);
        StringBuilder out = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN EXECUTE claim(100)")) {
          while (rs.next()) out.append(rs.getString(1)).append('\n');
        }
        return out.toString();
      } finally {
        st.execute("DEALLOCATE ALL");
        st.execute("RESET plan_cache_mode");
      }
    }
  }

  /** The ids the copied claim returns, in the order it returns them. */
  private static List<UUID> claimedByCopy() throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(CLAIM.replace("$1", "100"))) {
      List<UUID> ids = new ArrayList<>();
      while (rs.next()) ids.add(rs.getObject(1, UUID.class));
      return ids;
    }
  }

  /**
   * The plan of a query with sequential and bitmap scans off: what it does when it can avoid them.
   */
  private static String plan(String query) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
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
        all.contains("(created_at, id) WHERE ((published_at IS NULL) AND (dead_at IS NULL))"),
        "the claim's index holds the rows that may publish, in write order: " + all);
    assertTrue(
        all.contains("idx_outbox_aggregate_pending"), "the per-aggregate check's index: " + all);
    assertFalse(
        all.contains("idx_outbox_unpublished"),
        "no index of every waiting row by created_at beside the claim's, which holds the rows the"
            + " claim reads and not the dead letters: "
            + all);
  }

  @Test
  @DisplayName(
      "The relay's claim, dead-letter and backoff conditions and all, is served by its index")
  void theClaimIsServedByItsIndex() throws SQLException {
    seedBacklog();
    for (boolean generic : new boolean[] {false, true}) {
      String plan = claimPlan(generic);
      String how = (generic ? "generic" : "custom") + " plan:\n" + plan;
      assertTrue(plan.contains("idx_outbox_claim"), how);
      assertFalse(plan.contains("Sort"), "the index already holds them in order: " + how);
      assertTrue(plan.contains("idx_outbox_aggregate_pending"), "the per-aggregate check: " + how);
    }
  }

  /**
   * An index of every unpublished row by created_at, dead letters included, once stood beside the
   * claim's and was dropped. Offered again, the planner still chooses the claim's: nothing was
   * lost.
   */
  @Test
  @DisplayName(
      "The index of every unpublished row that was dropped would not be chosen for the claim")
  void theDroppedIndexWouldNotBeChosen() throws SQLException {
    seedBacklog();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL");
      try {
        for (boolean generic : new boolean[] {false, true}) {
          String plan = claimPlan(generic);
          assertTrue(plan.contains("idx_outbox_claim"), plan);
          assertFalse(plan.contains("idx_outbox_unpublished"), plan);
        }
      } finally {
        st.execute("DROP INDEX idx_outbox_unpublished");
      }
    }
  }

  /**
   * The copy above is only worth its plan if it is the claim. Seeded with every case the claim
   * decides (a healthy earlier row does not hold a later one back; a dead row, or one backing off,
   * holds back its own aggregate and no other; a published one holds nothing), the shared
   * repository and the copy must claim the same rows in the same order.
   */
  @Test
  @DisplayName("The claim copied here claims what the shared repository claims")
  void theCopyOfTheClaimIsTheClaim() throws SQLException {
    Instant now = Instant.now();
    Instant past = now.minusSeconds(60);
    Instant future = now.plus(Duration.ofHours(1));
    UUID healthy = Ids.newId();
    UUID behindDead = Ids.newId();
    UUID behindBackoff = Ids.newId();
    UUID afterPublished = Ids.newId();
    UUID healthy1 = pending(healthy, now.minusSeconds(50), past, false);
    UUID healthy2 = pending(healthy, now.minusSeconds(49), past, false);
    pending(behindDead, now.minusSeconds(48), past, true);
    pending(behindDead, now.minusSeconds(47), past, false);
    pending(behindBackoff, now.minusSeconds(46), future, false);
    pending(behindBackoff, now.minusSeconds(45), past, false);
    outbox(now.minusSeconds(44), now.minusSeconds(43));
    UUID alone = pending(Ids.newId(), now.minusSeconds(42), past, false);
    UUID backingOffAlone = pending(Ids.newId(), now.minusSeconds(41), future, false);
    // a published row of an aggregate, then a live one: the published one holds nothing
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, aggregate_id, payload, created_at,"
                    + " published_at) VALUES (?, 'Probe', 'storeql.test', ?, '{}', ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, afterPublished);
      ps.setObject(3, now.minusSeconds(40).atOffset(ZoneOffset.UTC));
      ps.setObject(4, now.minusSeconds(39).atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
    UUID afterPublished2 = pending(afterPublished, now.minusSeconds(38), past, false);

    List<UUID> expected = List.of(healthy1, healthy2, alone, afterPublished2);
    assertEquals(expected, claimedByCopy());

    Outbox repo = new Outbox(PG.dataSource());
    List<UUID> inOrder = new ArrayList<>();
    try (var lease = repo.tryDrainLock().orElseThrow()) {
      var result =
          repo.drainOnce(
              lease,
              100,
              rows -> {
                rows.forEach(r -> inOrder.add(r.id()));
                // nothing delivered and nothing failed: the drain writes nothing
                return new PublishOutcome(List.of(), Map.of());
              });
      assertEquals(expected.size(), result.claimed());
    }
    assertEquals(expected, inOrder, "the shared claim and the copy agree, in order");
    assertFalse(inOrder.contains(backingOffAlone));
  }

  /**
   * A backlog of every kind of row at a size where the planner has a real choice, as it is after a
   * broker outage: live, dead, backing off and published, over thousands of aggregates, read once
   * so the planner knows it. Set-based, with ids built as UUIDv7 from the row number.
   */
  private static void seedBacklog() throws SQLException {
    String id = v7("i");
    String aggregate = v7("i % 4000 + 1000000");
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload, created_at,"
              + " published_at, attempts, next_attempt_at, dead_at)"
              + " SELECT "
              + id
              + ", 'Probe', 'storeql.test', NULL, "
              + aggregate
              + ", '{}', now() - make_interval(secs => 100000 - i),"
              + " CASE WHEN i % 3 = 0 THEN now() - interval '1 day' END,"
              + " CASE WHEN i % 11 = 0 AND i % 3 <> 0 THEN 10 ELSE 0 END,"
              + " CASE WHEN i % 13 = 0 AND i % 3 <> 0 THEN now() + interval '1 hour'"
              + "      ELSE now() - interval '1 minute' END,"
              + " CASE WHEN i % 11 = 0 AND i % 3 <> 0 THEN now() END"
              + " FROM generate_series(1, 30000) AS i");
      st.execute("ANALYZE outbox");
    }
  }

  /** SQL for a UUIDv7 built from a number: version nibble 7, variant 10, the number in the tail. */
  private static String v7(String number) {
    String hex = "lpad(to_hex(" + number + "), 18, '0')";
    return "('01a090ae-611e-7' || substr("
        + hex
        + ", 1, 3) || '-8' || substr("
        + hex
        + ", 4, 3) || '-' || substr("
        + hex
        + ", 7, 12))::uuid";
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
