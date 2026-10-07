package com.storeql.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape of the schema order-svc migrates, where a mistake in a migration is not a failing
 * query: which indexes the outbox carries and that the relay's own claim is served by them, the
 * name of the store-status index every service that projects it shares, a table that is not kept
 * because nothing uses it, and the Flyway description of a renamed migration.
 */
class OrderSchemaIT {

  private static final PostgresSupport PG =
      PostgresSupport.start().migrate("classpath:db/migration");

  @AfterAll
  static void stop() throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("TRUNCATE TABLE outbox");
    }
    PG.stop();
  }

  /** The shared outbox repository over a data source that remembers every statement it prepares. */
  private static final class Recording extends BaseOutboxRepository {
    final List<String> prepared = new CopyOnWriteArrayList<>();

    Recording(DataSource real) {
      this.dataSource = recording(real, prepared);
    }
  }

  private static DataSource recording(DataSource real, List<String> prepared) {
    ClassLoader loader = OrderSchemaIT.class.getClassLoader();
    return (DataSource)
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = call(real, method, args);
              if ("getConnection".equals(method.getName()) && result instanceof Connection c) {
                return Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {Connection.class},
                    (cp, cm, ca) -> {
                      if ("prepareStatement".equals(cm.getName())
                          && ca != null
                          && ca.length > 0
                          && ca[0] instanceof String sql) {
                        prepared.add(sql);
                      }
                      return call(c, cm, ca);
                    });
              }
              return result;
            });
  }

  private static Object call(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private static List<String> indexNames(String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND tablename = ? ORDER BY indexname")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) names.add(rs.getString(1));
      }
    }
    return names;
  }

  private static String indexDef(String index) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = ?")) {
      ps.setString(1, index);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  /**
   * The plan with sequential and bitmap scans off: what the statement does if an index can serve.
   */
  private static String plan(String query) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      StringBuilder out = new StringBuilder();
      try (ResultSet rs = st.executeQuery("EXPLAIN " + query)) {
        while (rs.next()) out.append(rs.getString(1)).append('\n');
      }
      return out.toString();
    }
  }

  private static void waitingRows(int aggregates, int perAggregate) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("TRUNCATE TABLE outbox");
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                  + " created_at) VALUES (?, 'Probe', 'storeql.test', ?, ?, '{}',"
                  + " now() - make_interval(secs => ?))")) {
        int age = aggregates * perAggregate;
        for (int a = 0; a < aggregates; a++) {
          UUID aggregate = Ids.newId();
          for (int i = 0; i < perAggregate; i++) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, Ids.newId());
            ps.setObject(3, aggregate);
            ps.setInt(4, age--);
            ps.addBatch();
          }
        }
        ps.executeBatch();
      }
      st.execute("ANALYZE outbox");
    }
  }

  /** The claim exactly as the relay issues it: the statement the shared repository prepares. */
  private static String theRelaysClaim() throws SQLException {
    Recording outbox = new Recording(PG.dataSource());
    try (OutboxStore.DrainLease lease = outbox.tryDrainLock().orElseThrow()) {
      outbox.drainOnce(lease, 100, rows -> new OutboxStore.PublishOutcome(List.of(), Map.of()));
    }
    String claim =
        outbox.prepared.stream()
            .filter(s -> s.contains("FROM outbox o") && s.contains("ORDER BY o.created_at"))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("the relay prepared no claim: " + outbox.prepared));
    assertTrue(claim.contains("o.dead_at IS NULL"), "the real claim skips dead letters: " + claim);
    assertTrue(claim.contains("o.next_attempt_at <= now()"), "and honours the backoff: " + claim);
    assertTrue(claim.contains("NOT EXISTS"), "and waits behind its own aggregate: " + claim);
    assertTrue(claim.endsWith("LIMIT ?"), claim);
    return claim.substring(0, claim.length() - 1) + "100";
  }

  @Test
  @DisplayName(
      "The outbox has the claim, the per-aggregate check and the purge's index, and no other but"
          + " its primary key")
  void theOutboxCarriesExactlyTheIndexesTheRelayUses() throws SQLException {
    assertEquals(
        List.of(
            "idx_outbox_aggregate_pending",
            "idx_outbox_claim",
            "idx_outbox_published",
            "outbox_pkey"),
        indexNames("outbox"),
        "idx_outbox_unpublished (created_at) WHERE published_at IS NULL is not kept: the one"
            + " statement that reads waiting rows in created_at order is the claim, which excludes"
            + " dead letters and is served by idx_outbox_claim; the others look rows up by id or by"
            + " aggregate (idx_outbox_aggregate_pending), or read published rows");
    assertTrue(
        indexDef("idx_outbox_claim")
            .contains("(created_at, id) WHERE ((published_at IS NULL) AND (dead_at IS NULL))"),
        indexDef("idx_outbox_claim"));
  }

  @Test
  @DisplayName(
      "The relay's own claim, backoff and dead-letter filters included, reads idx_outbox_claim")
  void theRelaysClaimIsServedByTheClaimIndex() throws SQLException {
    waitingRows(60, 40);
    String plan = plan(theRelaysClaim());
    assertTrue(plan.contains("idx_outbox_claim"), plan);
    assertFalse(plan.contains("idx_outbox_unpublished"), plan);
    assertFalse(
        plan.contains("Sort"), "the index holds the rows in the order the claim asks: " + plan);
  }

  @Test
  @DisplayName("The claim's look at an aggregate's earlier rows reads idx_outbox_aggregate_pending")
  void theAggregateCheckIsServedByItsIndex() throws SQLException {
    waitingRows(60, 40);
    String plan = plan(theRelaysClaim());
    assertTrue(plan.contains("idx_outbox_aggregate_pending"), plan);
  }

  @Test
  @DisplayName("The store-status index has the name iam-svc and cart-svc give the same projection")
  void theStoreStatusIndexIsNamedAsInTheOtherServices() throws SQLException {
    assertEquals(
        List.of("idx_store_status_tenant_store", "store_status_pkey"), indexNames("store_status"));
    assertTrue(
        indexDef("idx_store_status_tenant_store").endsWith("(tenant_id, store_id)"),
        indexDef("idx_store_status_tenant_store"));
  }

  @Test
  @DisplayName("There is no idempotency_keys table: nothing reads or writes one")
  void thereIsNoIdempotencyKeysTable() throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT to_regclass('idempotency_keys')::text")) {
      rs.next();
      assertNull(rs.getString(1), "a table nothing uses is not kept");
    }
  }

  @Test
  @DisplayName(
      "The last migration is described by what it creates: the order settings and gift-card loads")
  void theLastMigrationIsNamedForWhatItCreates() throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT description FROM flyway_schema_history WHERE version = '23'")) {
      assertTrue(rs.next(), "version 23 is applied");
      assertEquals("order settings and gift card load lines", rs.getString(1));
    }
  }
}
