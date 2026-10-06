package com.storeql.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxStore;
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
import java.util.Map;
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
 * The outbox's drain and the scheduled purge of delivered outbox rows and old consumer dedupe rows,
 * on the schema purchase-svc migrates: the purge deletes only what is past its cutoff, in bounded
 * batches, and the outbox indexes of V1__init.sql and the processed_events index of
 * V11__sales_postings.sql are what let a batch find its rows without reading the table. The relay's
 * claim is planned here as BaseOutboxRepository prepares it, with its dead-letter and backoff
 * conditions: the test reads the statement off the repository's own connection, so there is no copy
 * of it to drift, and asserts that idx_outbox_claim serves its ordered scan; the claim's check for
 * an earlier waiting row of the same aggregate is served by idx_outbox_aggregate_pending, which the
 * test asserts exists. No index of every waiting row by created_at (idx_outbox_unpublished) is
 * kept.
 */
class OutboxPurgeIndexIT {

  private static final String SCHEMA = "purchase";

  private static final PostgresSupport PG = PostgresSupport.start();

  static {
    // The first migration selects the service's own schema, so it is migrated as the service does:
    // into a schema of that name, which Flyway creates and makes the default.
    Flyway.configure()
        .dataSource(PG.jdbcUrl(), PG.username(), PG.password())
        .locations("classpath:db/migration")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .load()
        .migrate();
  }

  /** The shared claim and purge, over this test's database. */
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

  private static void outbox(Instant createdAt, Instant publishedAt) throws SQLException {
    outbox(Ids.newId(), createdAt, publishedAt, null, null);
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

  /**
   * The plan of a statement with one int parameter (its LIMIT), with sequential and bitmap scans
   * off, as {@link #plan(String)}.
   */
  private static String plan(String query, int limit) throws SQLException {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      StringBuilder out = new StringBuilder();
      try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + query)) {
        ps.setInt(1, limit);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.append(rs.getString(1)).append('\n');
        }
      }
      return out.toString();
    }
  }

  /**
   * A data source that hands out the real connections and writes down every SQL string prepared on
   * them, so a test can read the statements a repository really runs instead of copying them.
   */
  private static DataSource recording(DataSource real, List<String> prepared) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(method, real, args);
              if ("getConnection".equals(method.getName())) {
                return recording((Connection) result, prepared);
              }
              return result;
            });
  }

  private static Connection recording(Connection real, List<String> prepared) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if ("prepareStatement".equals(method.getName())
                  && args != null
                  && args.length > 0
                  && args[0] instanceof String sql) {
                prepared.add(sql);
              }
              return invoke(method, real, args);
            });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  @Test
  @DisplayName(
      "The claim, its per-aggregate check and the purge each have an index of their own, the"
          + " outbox's partial, and the index of every waiting row by created_at is gone")
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
    assertTrue(all.contains("idx_outbox_published"), all);
    assertTrue(
        all.contains("(published_at) WHERE (published_at IS NOT NULL)"),
        "holds only delivered rows: " + all);
    assertTrue(all.contains("idx_processed_events_processed_at"), all);
    assertTrue(
        all.contains(
            "idx_outbox_claim ON purchase.outbox USING btree (created_at, id)"
                + " WHERE ((published_at IS NULL) AND (dead_at IS NULL))"),
        "the claim's own index holds only the rows still in play, in the claim's order: " + all);
    assertTrue(
        all.contains("idx_outbox_aggregate_pending ON purchase.outbox USING btree (aggregate_id,"),
        "the claim's per-aggregate check has its own index: " + all);
    assertEquals(
        0,
        count(
            "pg_indexes", "schemaname = '" + SCHEMA + "' AND indexname = 'idx_outbox_unpublished'"),
        "no index of every waiting row by created_at: it would also hold the dead letters, which"
            + " the claim's ordered scan never reads: "
            + all);
    assertEquals(
        0,
        count("pg_indexes", "schemaname = '" + SCHEMA + "' AND indexname = 'outbox_unpublished'"),
        "the index on the boolean nothing writes is dropped: " + all);
  }

  /**
   * Plans the relay's claim exactly as BaseOutboxRepository prepares it: the dead-letter and
   * backoff conditions and the per-aggregate NOT EXISTS included. The statement is read off the
   * repository's own connection while it claims, and the table holds rows of every kind the claim
   * has to look past: published, dead, backing off, and due.
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
    Outbox repo = new Outbox(recording(dataSource(), prepared));
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
      outbox(now.minus(DAY.multipliedBy(30)), now.minus(DAY.multipliedBy(10)));
    }
    for (int i = 0; i < 3; i++) {
      outbox(now.minus(DAY), now.minus(Duration.ofHours(1)));
    }
    // Never delivered, however old: it still has to reach Kafka.
    for (int i = 0; i < 4; i++) {
      outbox(now.minus(DAY.multipliedBy(60)), null);
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
