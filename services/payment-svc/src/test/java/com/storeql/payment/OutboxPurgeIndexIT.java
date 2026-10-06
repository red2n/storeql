package com.storeql.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.test.PostgresSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The relay's and the scheduled purge's statements on the schema payment-svc migrates: the purge
 * deletes only what is past its cutoff, in bounded batches, and the drain claims only what may
 * publish now; the indexes on outbox (V1__init.sql) and processed_events (V4__processed_events.sql)
 * are what let each find its rows without reading the table.
 *
 * <p>Each plan below is of the statement the shared repository ({@link BaseOutboxRepository})
 * really prepares, taken from the connection as it is prepared and never copied here, so a change
 * to that SQL is planned the day it is made.
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

  /** Every statement text prepared on a connection taken from the wrapped data source. */
  private static final class Prepared {
    final List<String> sql = new CopyOnWriteArrayList<>();

    DataSource over(DataSource real) {
      return (DataSource)
          Proxy.newProxyInstance(
              DataSource.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, m, args) -> {
                Object r = call(real, m, args);
                return "getConnection".equals(m.getName()) ? over((Connection) r) : r;
              });
    }

    private Connection over(Connection real) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, m, args) -> {
                if ("prepareStatement".equals(m.getName()) && args[0] instanceof String s) {
                  sql.add(s);
                }
                return call(real, m, args);
              });
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
      try {
        return m.invoke(target, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    /** The one statement prepared that starts with {@code prefix}. */
    String statement(String prefix) {
      List<String> found = sql.stream().filter(q -> q.startsWith(prefix)).toList();
      assertEquals(1, found.size(), prefix + " in " + sql);
      return found.get(0);
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
    outbox(createdAt, publishedAt, null, createdAt);
  }

  /** A row as the relay leaves it: published, waiting out a backoff, or a dead letter. */
  private static void outbox(
      Instant createdAt, Instant publishedAt, Instant deadAt, Instant nextAttemptAt)
      throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at, published_at, dead_at, next_attempt_at)"
                    + " VALUES (?, 'Probe', 'storeql.test', NULL, ?, '{}', ?, ?, ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.newId());
      ps.setObject(3, createdAt.atOffset(ZoneOffset.UTC));
      if (publishedAt == null) {
        ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(4, publishedAt.atOffset(ZoneOffset.UTC));
      }
      if (deadAt == null) {
        ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(5, deadAt.atOffset(ZoneOffset.UTC));
      }
      ps.setObject(6, nextAttemptAt.atOffset(ZoneOffset.UTC));
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
   * The plan of a statement as the repository prepares it ({@code ?} markers and all), with
   * sequential and bitmap scans off: what it does when it can avoid them. The statement is prepared
   * on the server with its markers as parameters, and planned for {@code args}, one for each
   * marker, as SQL expressions.
   */
  private static String plan(String statement, String... args) throws SQLException {
    StringBuilder numbered = new StringBuilder();
    int markers = 0;
    for (char ch : statement.toCharArray()) {
      numbered.append(ch == '?' ? "$" + (++markers) : String.valueOf(ch));
    }
    assertEquals(markers, args.length, "one argument for each marker of " + statement);
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      // Nor a bitmap scan, which would need a sort: on a table that was never analysed the planner
      // may well prefer one, and with both off only an ordered index scan is left to serve
      // ORDER BY ... LIMIT, if an index can.
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      st.execute("PREPARE probe AS " + numbered);
      StringBuilder out = new StringBuilder();
      try (ResultSet rs =
          st.executeQuery(
              "EXPLAIN EXECUTE probe"
                  + (markers == 0 ? "" : "(" + String.join(", ", args) + ")"))) {
        while (rs.next()) out.append(rs.getString(1)).append('\n');
      }
      return out.toString();
    }
  }

  @Test
  @DisplayName(
      "The purge's two lookups each have an index of their own, and the outbox's is partial; the"
          + " drain's claim and its per-aggregate check have theirs")
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
        "the claim's index holds only rows that may publish: " + all);
    assertTrue(all.contains("idx_outbox_aggregate_pending"), all);
    // An index on (created_at) over every unpublished row, dead letters too, is not kept: the claim
    // is the one statement that reads unpublished rows in the order they were written
    // (theClaimIsServedByItsOwnIndex), and its ordered scan never reads a dead letter, which
    // idx_outbox_claim leaves out. Another index on those rows would cost every write and serve no
    // statement the claim's own indexes do not.
    assertFalse(
        all.contains("idx_outbox_unpublished"),
        "no index of every unpublished row by created_at: " + all);
  }

  @Test
  @DisplayName(
      "The purge's own statements, as the shared repository runs them, find their oldest rows"
          + " through the indexes, in their order")
  void aBatchIsServedByTheIndexes() throws SQLException {
    Instant now = Instant.now();
    for (int i = 0; i < 40; i++) {
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10 + i)));
      processed(now.minus(DAY.multipliedBy(40 + i)));
    }
    Prepared spy = new Prepared();
    Outbox repo = new Outbox(spy.over(PG.dataSource()));
    repo.purgePublished(now.minus(DAY.multipliedBy(7)), 5000);
    repo.purgeProcessedEvents(now.minus(DAY.multipliedBy(30)), 5000);

    // The two deletes, exactly as BaseOutboxRepository prepared them.
    String published =
        plan(spy.statement("DELETE FROM outbox"), "now() - interval '7 days'", "5000");
    assertTrue(published.contains("idx_outbox_published"), published);
    assertFalse(published.contains("Sort"), "the index already holds them in order: " + published);
    String dedupe =
        plan(spy.statement("DELETE FROM processed_events"), "now() - interval '30 days'", "5000");
    assertTrue(dedupe.contains("idx_processed_events_processed_at"), dedupe);
    assertFalse(dedupe.contains("Sort"), "the index already holds them in order: " + dedupe);
  }

  @Test
  @DisplayName(
      "The relay's claim, with its dead-letter and backoff conditions, is served by the claim"
          + " index in the order rows were written, and its aggregate check by its own")
  void theClaimIsServedByItsOwnIndex() throws SQLException {
    Instant now = Instant.now();
    // Published, live, backing off and dead: every kind of row the claim has to tell apart.
    for (int i = 0; i < 20; i++) {
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10 + i)));
      outbox(now.minus(Duration.ofMinutes(i)), null, null, now.minus(Duration.ofSeconds(1)));
      outbox(now.minus(Duration.ofMinutes(i)), null, null, now.plus(Duration.ofMinutes(5)));
      outbox(now.minus(DAY.multipliedBy(2)), null, now.minus(DAY), now.minus(DAY));
    }
    Prepared spy = new Prepared();
    Outbox repo = new Outbox(spy.over(PG.dataSource()));
    try (var lease = repo.tryDrainLock().orElseThrow()) {
      // The claim runs, finds live rows and hands them to the publisher; stop it there.
      AssertionError handedOver =
          assertThrows(
              AssertionError.class,
              () ->
                  repo.drainOnce(
                      lease,
                      1000,
                      rows -> {
                        throw new AssertionError("claimed " + rows.size());
                      }));
      assertTrue(handedOver.getMessage().startsWith("claimed "), handedOver.getMessage());
      assertNotEquals("claimed 0", handedOver.getMessage(), "rows that may publish were found");
    }
    String claim = spy.statement("SELECT o.id, o.aggregate_id, o.topic, o.payload FROM outbox o");
    assertTrue(claim.contains("dead_at IS NULL"), "the real claim, dead letters and all: " + claim);
    assertTrue(claim.contains("next_attempt_at <= now()"), "and its backoff: " + claim);

    String plan = plan(claim, "1000");
    assertTrue(plan.contains("idx_outbox_claim"), plan);
    assertTrue(plan.contains("idx_outbox_aggregate_pending"), plan);
    assertFalse(plan.contains("Sort"), "the claim index already holds them in order: " + plan);
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
