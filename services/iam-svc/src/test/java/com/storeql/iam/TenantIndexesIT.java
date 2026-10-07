package com.storeql.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tenant-led indexes of the tables that carry a business's id, on the schema iam-svc migrates.
 * A tenant's export reads its rows a page at a time, {@code WHERE tenant_id = ? AND (key) > cursor
 * ORDER BY key LIMIT n}, and its erasure runs {@code DELETE ... WHERE tenant_id = ?}
 * (common-service TenantDataRepository): each is the shape an index starting with {@code tenant_id}
 * answers, and what the plan tests below run. The first test reads every table that has a {@code
 * tenant_id} column from the catalog and requires each, bar the exempt outbox, to have such an
 * index.
 */
class TenantIndexesIT {

  private static final PostgresSupport PG =
      PostgresSupport.start().migrate("classpath:db/migration");

  @AfterAll
  static void stop() {
    PG.stop();
  }

  /** The plan of a query with sequential and bitmap scans off: what it does if an index can. */
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

  /** Each index of {@code table} as {@code name (key columns)}, the key columns in index order. */
  private static List<String> indexes(String table) throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT i.relname || ' (' || (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)"
                    + " FROM unnest(x.indkey::int2[]) WITH ORDINALITY k(attnum, ord)"
                    + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum) || ')'"
                    + " FROM pg_index x JOIN pg_class t ON t.oid = x.indrelid"
                    + " JOIN pg_class i ON i.oid = x.indexrelid"
                    + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = 'public' AND t.relname = ? ORDER BY 1")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) found.add(rs.getString(1));
      }
    }
    return found;
  }

  private static void exec(String sql, Object... args) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    }
  }

  /**
   * The tables of a schema that carry a business's id, read from the catalog (information_schema),
   * never from a list written here: a table added later is checked without anyone remembering to.
   */
  private static List<String> tenantTables(String schema) throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT c.table_name FROM information_schema.columns c"
                    + " JOIN information_schema.tables t"
                    + " ON t.table_schema = c.table_schema AND t.table_name = c.table_name"
                    + " WHERE c.table_schema = ? AND c.column_name = 'tenant_id'"
                    + " AND t.table_type = 'BASE TABLE' ORDER BY 1")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) found.add(rs.getString(1));
      }
    }
    return found;
  }

  /** Whether some index of the table has {@code tenant_id} as its first key column. */
  private static boolean hasATenantLedIndex(String schema, String table) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_index x"
                    + " JOIN pg_class t ON t.oid = x.indrelid"
                    + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                    + " CROSS JOIN LATERAL unnest(x.indkey::int2[]) WITH ORDINALITY k(attnum, ord)"
                    + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum"
                    + " WHERE n.nspname = ? AND t.relname = ? AND k.ord = 1"
                    + " AND a.attname = 'tenant_id')")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  /** Every table of the schema with a {@code tenant_id} column and no index that starts with it. */
  private static List<String> tenantTablesWithoutATenantLedIndex(String schema)
      throws SQLException {
    List<String> missing = new ArrayList<>();
    for (String table : tenantTables(schema)) {
      if (!hasATenantLedIndex(schema, table)) missing.add(table);
    }
    return missing;
  }

  /**
   * The tables that carry a business's id and are read by no business's id, each with why: the
   * reason is the one V1__init.sql gives for the outbox.
   */
  private static final Map<String, String> NOT_READ_BY_BUSINESS =
      Map.of(
          "outbox",
          "one relay drains every business's rows in the order they were written, so no query on"
              + " it filters by tenant_id, and a business's export and erasure leave it out as"
              + " delivery machinery (TenantDataSpec.INFRASTRUCTURE)");

  @Test
  @DisplayName(
      "Every table that carries a business's id, bar the outbox, has an index that starts with it")
  void everyTenantTableHasATenantLedIndex() throws SQLException {
    List<String> tables = tenantTables("public");
    // The catalog read finds the tables it is meant to find, and nothing in the exemption is stale.
    assertTrue(tables.contains("users"), "the catalog read found no tenant tables: " + tables);
    assertTrue(
        tables.containsAll(NOT_READ_BY_BUSINESS.keySet()),
        "an exempt table is no longer a tenant table: " + tables);
    List<String> missing = new ArrayList<>(tenantTablesWithoutATenantLedIndex("public"));
    for (var exempt : NOT_READ_BY_BUSINESS.entrySet()) {
      // The reason holds only while the table really has no such index: the day one is added the
      // exemption is a lie, and goes.
      assertTrue(
          missing.remove(exempt.getKey()),
          exempt.getKey()
              + " has a tenant-led index now, so it is not exempt: "
              + exempt.getValue());
    }
    assertEquals(
        List.of(),
        missing,
        "tables that carry tenant_id and have no index that starts with it, among " + tables);
  }

  @Test
  @DisplayName("The check names a tenant table with no tenant-led index, and only that one")
  void theCheckFindsATableWithoutOne() throws SQLException {
    exec("CREATE SCHEMA tenant_index_probe");
    try {
      // Integer columns only: nothing here is a business's data, or a uuid the audit would read.
      exec("CREATE TABLE tenant_index_probe.keyless (id integer PRIMARY KEY, tenant_id integer)");
      exec(
          "CREATE TABLE tenant_index_probe.second (id integer PRIMARY KEY, note integer,"
              + " tenant_id integer)");
      exec("CREATE INDEX ON tenant_index_probe.second (note, tenant_id)");
      exec("CREATE TABLE tenant_index_probe.led (id integer PRIMARY KEY, tenant_id integer)");
      exec("CREATE INDEX ON tenant_index_probe.led (tenant_id, id)");
      exec("CREATE TABLE tenant_index_probe.keyed (tenant_id integer PRIMARY KEY)");
      exec("CREATE TABLE tenant_index_probe.nobody (id integer PRIMARY KEY, note integer)");
      assertEquals(
          List.of("keyed", "keyless", "led", "second"),
          tenantTables("tenant_index_probe"),
          "a table with no tenant_id column is not a tenant table");
      assertEquals(
          List.of("keyless", "second"),
          tenantTablesWithoutATenantLedIndex("tenant_index_probe"),
          "tenant_id second in an index does not lead it; first in the primary key does");
    } finally {
      exec("DROP SCHEMA tenant_index_probe CASCADE");
    }
  }

  @Test
  @DisplayName("The forgotten-password tokens' tenant index is on the business alone")
  void theTokensTenantIndexIsOnTheBusinessAlone() throws SQLException {
    List<String> found = indexes("password_reset_tokens");
    // Erasure is its only tenant-keyed reader; a second column nobody reads beside it is dropped.
    assertTrue(found.contains("idx_password_reset_tokens_tenant (tenant_id)"), found.toString());
    assertFalse(
        found.stream().anyMatch(i -> i.contains("tenant_id, user_id")),
        "no tenant index carries a user_id nobody reads with it: " + found);
    // Every reader of a token goes by its hash, its user or its expiry, each with an index.
    assertTrue(found.contains("idx_password_reset_tokens_user (user_id)"), found.toString());
    assertTrue(found.contains("idx_password_reset_tokens_expires (expires_at)"), found.toString());
  }

  @Test
  @DisplayName("A business's erasure of its reset tokens reads them through the tenant index")
  void erasureOfTheTokensIsServedByTheTenantIndex() throws SQLException {
    Instant now = Instant.now();
    UUID erased = null;
    for (int t = 0; t < 10; t++) {
      UUID tenant = Ids.newId();
      erased = tenant;
      for (int i = 0; i < 20; i++) {
        UUID user = Ids.newId();
        exec(
            "INSERT INTO users (id, tenant_id, type, email, status)"
                + " VALUES (?, ?, 'STAFF', ?, 'ACTIVE')",
            user,
            tenant,
            "tok" + t + "-" + i + "@example.com");
        exec(
            "INSERT INTO password_reset_tokens (id, user_id, tenant_id, token_hash, expires_at,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
            Ids.newId(),
            user,
            tenant,
            "hash-" + Ids.newId(),
            now.plusSeconds(1800).atOffset(ZoneOffset.UTC),
            now.atOffset(ZoneOffset.UTC));
      }
    }
    String plan = plan("DELETE FROM password_reset_tokens WHERE tenant_id = '" + erased + "'");
    assertTrue(plan.contains("idx_password_reset_tokens_tenant"), plan);
  }

  @Test
  @DisplayName("A business's export of its store types pages through the tenant index in order")
  void exportOfStoreTypesIsServedByTheTenantIndex() throws SQLException {
    UUID tenant = null;
    UUID firstStore = null;
    for (int t = 0; t < 10; t++) {
      UUID owner = Ids.newId();
      for (int i = 0; i < 100; i++) {
        UUID store = Ids.newId();
        exec(
            "INSERT INTO store_types (store_id, tenant_id, type) VALUES (?, ?, 'STORE')",
            store,
            owner);
        if (t == 3 && i == 0) {
          tenant = owner;
          firstStore = store;
        }
      }
    }
    exec("ANALYZE store_types");
    // A business that has stores: its first page, and the page after a cursor.
    String firstPage =
        plan(
            "SELECT * FROM store_types WHERE tenant_id = '"
                + tenant
                + "' ORDER BY store_id LIMIT 100");
    String nextPage =
        plan(
            "SELECT * FROM store_types WHERE tenant_id = '"
                + tenant
                + "' AND (store_id) > '"
                + firstStore
                + "'::uuid ORDER BY store_id LIMIT 100");
    for (String plan : List.of(firstPage, nextPage)) {
      assertTrue(plan.contains("idx_store_types_tenant_store"), plan);
      assertFalse(plan.contains("Sort"), "the index already holds them in order: " + plan);
    }
    assertEquals(
        List.of(
            "idx_store_types_tenant_store (tenant_id, store_id)", "store_types_pkey (store_id)"),
        indexes("store_types"));
  }
}
