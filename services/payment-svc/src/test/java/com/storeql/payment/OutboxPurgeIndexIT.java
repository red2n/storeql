package com.storeql.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled purge of delivered outbox rows and old consumer dedupe rows, on the schema
 * payment-svc migrates: it deletes only what is past its cutoff, in bounded batches, and the purge
 * indexes on outbox (V1__init.sql) and processed_events (V6__processed_events.sql) are what let a
 * batch find its rows without reading the table.
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
    assertTrue(all.contains("idx_outbox_unpublished"), "the drain's index is still there: " + all);
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
