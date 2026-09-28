package com.storeql.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Reusable Postgres Testcontainer support for service integration tests (docs/ARCHITECTURE.md §16).
 *
 * <p>Spins up a real Postgres in a container, optionally runs the service's Flyway migrations
 * against it, and hands back a {@link DataSource}. Keeps integration tests honest (real DB, real
 * SQL) without a shared instance.
 *
 * <pre>{@code
 * var pg = PostgresSupport.start();
 * pg.migrate("classpath:db/migration");
 * DataSource ds = pg.dataSource();
 * ... run repo tests ...
 * pg.stop();
 * }</pre>
 */
public final class PostgresSupport implements AutoCloseable {

  /**
   * Counts values that are not RFC 9562 v7 (version nibble 7, variant 10) in every uuid and uuid[]
   * column of every table, column by column in one statement: query_to_xml runs each count inside
   * Postgres, so no table name is ever concatenated into SQL here. Character 15 of a uuid's text is
   * its version digit and character 20 its variant digit, which is 8, 9, a or b for variant 10.
   */
  private static final String NON_V7_UUIDS =
      "SELECT column_name, rows FROM ("
          + " SELECT c.table_schema || '.' || c.table_name || '.' || c.column_name AS column_name,"
          + "  (xpath('/row/n/text()', query_to_xml(format("
          + "   CASE WHEN c.udt_name = '_uuid'"
          + "    THEN 'SELECT count(*) AS n FROM %I.%I, unnest(%I) AS u"
          + " WHERE substring(u::text, 15, 1) <> ''7'' OR substring(u::text, 20, 1) NOT IN"
          + " (''8'', ''9'', ''a'', ''b'')'"
          + "    ELSE 'SELECT count(*) AS n FROM %I.%I WHERE %I IS NOT NULL AND"
          + " (substring(%I::text, 15, 1) <> ''7'' OR substring(%I::text, 20, 1) NOT IN"
          + " (''8'', ''9'', ''a'', ''b''))' END,"
          + "   c.table_schema, c.table_name, c.column_name, c.column_name, c.column_name),"
          + "   false, true, '')))[1]::text::bigint AS rows"
          + " FROM information_schema.columns c"
          + " JOIN information_schema.tables t"
          + "  ON t.table_schema = c.table_schema AND t.table_name = c.table_name"
          + " WHERE c.udt_name IN ('uuid', '_uuid') AND t.table_type = 'BASE TABLE'"
          + "  AND c.table_schema NOT IN ('pg_catalog', 'information_schema')"
          + ") counted WHERE rows > 0 ORDER BY column_name";

  /**
   * Every uuid and uuid[] column — and every idempotency_key column — of a schema Flyway migrated
   * that does not carry the v7 CHECK common-service's afterMigrate__uuid_v7_everywhere.sql adds —
   * proof the database, and not only the code, refuses another version. A schema Flyway never ran
   * on (a test's own scratch tables) is not asked.
   */
  private static final String UNGUARDED_UUID_COLUMNS =
      "SELECT c.table_schema || '.' || c.table_name || '.' || c.column_name"
          + " FROM information_schema.columns c"
          + " JOIN information_schema.tables t"
          + "  ON t.table_schema = c.table_schema AND t.table_name = c.table_name"
          + " WHERE (c.udt_name IN ('uuid', '_uuid')"
          + "   OR (c.column_name = 'idempotency_key' AND c.udt_name IN ('text', 'varchar')))"
          + "  AND t.table_type = 'BASE TABLE'"
          + "  AND c.table_schema NOT IN ('pg_catalog', 'information_schema')"
          + "  AND EXISTS (SELECT 1 FROM information_schema.tables h"
          + "   WHERE h.table_schema = c.table_schema AND h.table_name = 'flyway_schema_history')"
          + "  AND NOT EXISTS (SELECT 1 FROM pg_constraint k"
          + "   JOIN pg_class r ON r.oid = k.conrelid JOIN pg_namespace n ON n.oid = r.relnamespace"
          + "   JOIN pg_attribute a ON a.attrelid = r.oid AND a.attname = c.column_name"
          + "   WHERE n.nspname = c.table_schema AND r.relname = c.table_name AND k.contype = 'c'"
          + "    AND k.conname LIKE 'v7\\_%' AND a.attnum = ANY (k.conkey))"
          + " ORDER BY 1";

  @SuppressWarnings("PMD.NoDatabaseMintedIds") // names the generators to find them, never calls one
  private static final String ID_GENERATING_DEFAULTS =
      "SELECT table_schema || '.' || table_name || '.' || column_name || ' DEFAULT ' || column_default"
          + " FROM information_schema.columns"
          + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
          + " AND (column_default ILIKE '%gen_random_uuid%' OR column_default ILIKE '%uuid_generate_v%'"
          + "  OR column_default ILIKE '%uuid_v7%')"
          + " ORDER BY 1";

  /**
   * SJ-D54: a column holding a currency, country, time zone or locale must not default to a
   * literal. SJ-D53 took those literals out of the code and left 'GBP', 'USD' and 'GB' in thirteen
   * column defaults, where an insert that forgot the column was filled in with the wrong one
   * silently.
   */
  private static final String LITERAL_TENANT_DEFAULTS =
      "SELECT table_schema || '.' || table_name || '.' || column_name || ' DEFAULT ' || column_default"
          + " FROM information_schema.columns"
          + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
          + " AND column_name ~ '(^|_)(currency|country|country_code|timezone|time_zone|locale)$'"
          + " AND column_default ~ '^''[^'']+''(::[a-z ]+)?$'"
          + " ORDER BY 1";

  private final PostgreSQLContainer<?> container;

  private PostgresSupport(PostgreSQLContainer<?> container) {
    this.container = container;
  }

  /**
   * Start a Postgres 16 container.
   *
   * @return a started {@code PostgresSupport} with database {@code storeql_test}, user/password
   *     {@code storeql}/{@code storeql}; call {@link #close()} (or {@link #stop()}) when done
   * @throws org.testcontainers.containers.ContainerLaunchException if the container fails to start
   */
  public static PostgresSupport start() {
    @SuppressWarnings("resource")
    PostgreSQLContainer<?> c =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("storeql_test")
            .withUsername("storeql")
            .withPassword("storeql");
    c.start();
    return new PostgresSupport(c);
  }

  /**
   * Run Flyway migrations from the given location (e.g. {@code "classpath:db/migration"}).
   *
   * @param location the Flyway migration location to apply
   * @return this, for chaining after {@link #start()}
   * @throws org.flywaydb.core.api.FlywayException if a migration fails to apply
   */
  /**
   * Points a service's Helidon test at this database and switches discovery and Kafka off: the
   * static block every integration test opens with.
   *
   * @param schema the service's schema, e.g. {@code "purchase"}
   * @return this, for chaining
   */
  public PostgresSupport wire(String schema) {
    System.setProperty("storeql.db.url", jdbcUrl());
    System.setProperty("storeql.db.migration-url", jdbcUrl());
    System.setProperty("storeql.db.user", username());
    System.setProperty("storeql.db.password", password());
    System.setProperty("storeql.db.schema", schema);
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    return this;
  }

  public PostgresSupport migrate(String location) {
    Flyway.configure()
        .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
        .locations(location)
        .load()
        .migrate();
    return this;
  }

  /**
   * @return a fresh unpooled {@link DataSource} pointing at this container
   */
  public DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(container.getJdbcUrl());
    ds.setUser(container.getUsername());
    ds.setPassword(container.getPassword());
    return ds;
  }

  /**
   * @return the JDBC URL of the running container
   */
  public String jdbcUrl() {
    return container.getJdbcUrl();
  }

  /**
   * @return the database user ({@code storeql})
   */
  public String username() {
    return container.getUsername();
  }

  /**
   * @return the database password ({@code storeql})
   */
  public String password() {
    return container.getPassword();
  }

  /**
   * Checks that every row the test left behind has a version-7 id, then stops and removes the
   * container — even when the check fails.
   *
   * <p>StoreQL mints only UUIDv7 ids. Checking here, at the end of every integration test class,
   * turns any path that still stores another version — a column default, SQL that makes its own
   * uuid, a fixture — into a failure that names the table.
   *
   * @throws AssertionError if any uuid column holds a value that is not an RFC 9562 UUIDv7, or a
   *     migrated uuid column lacks the database's own v7 check
   */
  public void stop() {
    try {
      List<String> defaults = idGeneratingDefaults();
      if (!defaults.isEmpty()) {
        throw new AssertionError(
            "columns that generate their own uuids: "
                + defaults
                + ". Drop the DEFAULT and bind Ids.newId(); see docs/coding-standards.md §3.");
      }
      List<String> literals = literalTenantDefaults();
      if (!literals.isEmpty()) {
        throw new AssertionError(
            "currency, country or time zone columns defaulting to a literal: "
                + literals
                + ". Drop the DEFAULT and bind the tenant's own (TenantProfiles); see SJ-D53/SJ-D54.");
      }
      Map<String, Long> offenders = nonV7Ids();
      if (!offenders.isEmpty()) {
        throw new AssertionError(
            "uuids that are not RFC 9562 UUIDv7 (column=rows): "
                + offenders
                + ". Mint ids with Ids.newId() and read them with Ids.parse(); see"
                + " docs/coding-standards.md §3.");
      }
      List<String> unguarded = unguardedUuidColumns();
      if (!unguarded.isEmpty()) {
        throw new AssertionError(
            "uuid columns the database does not hold to v7: "
                + unguarded
                + ". common-service's afterMigrate__uuid_v7_everywhere.sql adds the check after every"
                + " migrate; did the migration fail?");
      }
      List<String> unbound = guardsWithUnboundBodies();
      if (!unbound.isEmpty()) {
        throw new AssertionError(
            "v7 guard functions whose bodies are parsed at call time: "
                + unbound
                + ". pg_restore runs with an empty search_path, so a guard that names another"
                + " function unqualified fails every COPY into a table it checks and the backup"
                + " cannot be restored (the backup drill found this). Define guards with BEGIN"
                + " ATOMIC so their references bind at creation.");
      }
    } finally {
      container.stop();
    }
  }

  /**
   * @return every column, in any schema, whose default calls a uuid generator — the same check as
   *     common-service's afterMigrate.sql, made here because a service that fails to migrate only
   *     logs a warning and keeps running
   */
  public List<String> idGeneratingDefaults() {
    return columnsMatching(ID_GENERATING_DEFAULTS);
  }

  /**
   * @return every currency, country, time zone or locale column, in any schema, whose default is a
   *     literal (SJ-D54)
   */
  public List<String> literalTenantDefaults() {
    return columnsMatching(LITERAL_TENANT_DEFAULTS);
  }

  private List<String> columnsMatching(String sql) {
    List<String> offenders = new ArrayList<>();
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        offenders.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not audit column defaults", e);
    }
    return offenders;
  }

  /**
   * @return every uuid, uuid[] or idempotency_key column of a Flyway-migrated schema that lacks the
   *     database's own v7 check; empty when the database refuses another version everywhere
   */
  public List<String> unguardedUuidColumns() {
    return columnsMatching(UNGUARDED_UUID_COLUMNS);
  }

  /**
   * @return every v7 guard function ({@code uuid_*_v7}) in any schema whose SQL body is a string
   *     parsed at call time rather than a body parsed at creation ({@code pg_proc.prosqlbody}); a
   *     restore runs with an empty search_path and such a body cannot resolve what it names
   */
  public List<String> guardsWithUnboundBodies() {
    List<String> out = new java.util.ArrayList<>();
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(UNBOUND_GUARDS);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not audit the v7 guard functions", e);
    }
    return out;
  }

  private static final String UNBOUND_GUARDS =
      "SELECT n.nspname || '.' || p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
          + " WHERE p.proname LIKE 'uuid\\_%\\_v7' AND p.prolang = (SELECT oid FROM pg_language WHERE lanname = 'sql')"
          + " AND p.prosqlbody IS NULL AND n.nspname NOT IN ('pg_catalog', 'information_schema') ORDER BY 1";

  /**
   * @return every uuid or uuid[] column, in any table, holding values that are not RFC 9562 v7,
   *     with how many; empty when every uuid is v7
   */
  public Map<String, Long> nonV7Ids() {
    Map<String, Long> offenders = new TreeMap<>();
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(NON_V7_UUIDS);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        offenders.put(rs.getString("column_name"), rs.getLong("rows"));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not audit id versions", e);
    }
    return offenders;
  }

  @Override
  public void close() {
    stop();
  }
}
