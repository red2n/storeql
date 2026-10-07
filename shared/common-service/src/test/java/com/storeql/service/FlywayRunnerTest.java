package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner against a real Postgres, with migration files on disk. A schema that fails validation
 * and a build with no migration stop the service; the other failures tried here (an unreachable
 * database, a failing migration, a role without DDL rights, a misspelled file name) are logged at
 * {@code WARNING} and the service starts. Each case gets a schema of its own, so the container is
 * started once.
 */
class FlywayRunnerTest {

  private static PostgresSupport pg;

  @TempDir Path migrations;

  @BeforeAll
  static void startDatabase() {
    pg = PostgresSupport.start();
  }

  @AfterAll
  static void stopDatabase() {
    pg.stop();
  }

  // ── a clean database migrates ────────────────────────────────────────────────

  @Test
  @DisplayName("a clean database migrates")
  void aCleanDatabaseMigrates() throws SQLException {
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY, total numeric NOT NULL);");
    write("V2__lines.sql", "CREATE TABLE lines (id int PRIMARY KEY, order_id int NOT NULL);");
    String schema = newSchema();

    runner(schema).migrate(locations());

    assertTrue(tableExists(schema, "orders"));
    assertTrue(tableExists(schema, "lines"));
    assertEquals(2, appliedVersions(schema));
  }

  @Test
  @DisplayName("starting again on an unchanged set applies nothing and does not refuse")
  void anUnchangedSetIsAQuietSecondStart() throws SQLException {
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());

    List<LogRecord> logged = logged(() -> runner(schema).migrate(locations()));

    assertEquals(List.of(), levels(logged, Level.WARNING, Level.SEVERE));
    assertEquals(1, appliedVersions(schema));
  }

  @Test
  @DisplayName("a migration added after the first start is applied on the next")
  void aNewMigrationIsApplied() throws SQLException {
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());

    write("V2__lines.sql", "CREATE TABLE lines (id int PRIMARY KEY);");
    runner(schema).migrate(locations());

    assertTrue(tableExists(schema, "lines"));
    assertEquals(2, appliedVersions(schema));
  }

  // ── a schema the migrations do not match stops the service ───────────────────

  @Test
  @DisplayName("an applied migration whose file has changed stops startup, naming the remedy")
  void aChangedAppliedFileStopsStartup() throws SQLException {
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    write("V2__lines.sql", "CREATE TABLE lines (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());

    // The file V2 was applied from is edited, and a new V3 arrives behind it.
    write("V2__lines.sql", "CREATE TABLE lines (id int PRIMARY KEY, note text);");
    write("V3__later.sql", "CREATE TABLE later (id int PRIMARY KEY);");
    FlywayRunner restart = runner(schema);

    FlywayRunner.MigrationFailedException refused =
        assertThrows(FlywayRunner.MigrationFailedException.class, () -> restart.onStart(null));

    String message = refused.getMessage();
    assertTrue(message.contains(schema), "names the schema: " + message);
    assertTrue(message.toLowerCase().contains("checksum"), "says what differs: " + message);
    assertTrue(message.contains("docker compose down -v"), "names the dev remedy: " + message);
    assertTrue(message.contains("restore"), "names the deployment remedy: " + message);
    assertTrue(message.contains("flyway repair"), "names the deliberate remedy: " + message);
    assertInstanceOf(FlywayValidateException.class, refused.getCause());
    assertFalse(tableExists(schema, "later"), "nothing is applied on the mismatched schema");
    assertEquals(2, appliedVersions(schema));
  }

  @Test
  @DisplayName("the refusal is logged at ERROR with the words of the exception")
  void aRefusalIsLoggedAtError() {
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY, extra text);");

    FlywayRunner restart = runner(schema);
    List<LogRecord> logged = new ArrayList<>();
    FlywayRunner.MigrationFailedException refused =
        assertThrows(
            FlywayRunner.MigrationFailedException.class,
            () -> capture(logged, () -> restart.migrate(locations())));

    List<LogRecord> errors = levels(logged, Level.SEVERE);
    assertEquals(1, errors.size(), String.valueOf(logged));
    assertEquals(refused.getMessage(), errors.get(0).getMessage());
    assertEquals(List.of(), levels(logged, Level.WARNING), "a refusal is not also a deferral");
  }

  @Test
  @DisplayName("an applied version whose file is gone, with later ones present, stops startup")
  void anAppliedVersionWhoseFileIsGoneStopsStartup() throws IOException {
    write("V1__a.sql", "CREATE TABLE a (id int PRIMARY KEY);");
    write("V2__b.sql", "CREATE TABLE b (id int PRIMARY KEY);");
    write("V3__c.sql", "CREATE TABLE c (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());

    Files.delete(migrations.resolve("V2__b.sql"));
    FlywayRunner restart = runner(schema);

    FlywayRunner.MigrationFailedException refused =
        assertThrows(
            FlywayRunner.MigrationFailedException.class, () -> restart.migrate(locations()));

    assertTrue(refused.getMessage().contains("docker compose down -v"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("not resolved locally: 2"),
        "names version 2: " + refused.getMessage());
    assertInstanceOf(FlywayValidateException.class, refused.getCause());
  }

  @Test
  @DisplayName("a migration slipped in below the applied ones stops startup and is not applied")
  void aMigrationBelowTheAppliedOnesStopsStartup() throws SQLException {
    write("V1__a.sql", "CREATE TABLE a (id int PRIMARY KEY);");
    write("V3__c.sql", "CREATE TABLE c (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());

    write("V2__b.sql", "CREATE TABLE b (id int PRIMARY KEY);");
    FlywayRunner restart = runner(schema);

    FlywayRunner.MigrationFailedException refused =
        assertThrows(
            FlywayRunner.MigrationFailedException.class, () -> restart.migrate(locations()));

    assertInstanceOf(FlywayValidateException.class, refused.getCause());
    assertFalse(tableExists(schema, "b"));
  }

  @Test
  @DisplayName("an applied version newer than every file starts, as Flyway's defaults allow")
  void anAppliedVersionNewerThanEveryFileStillStarts() throws IOException {
    // An older build on a schema a newer build migrated: Flyway reports the unknown newest version
    // as "future" and its default ignores it, so a rolled-back build still starts.
    write("V1__a.sql", "CREATE TABLE a (id int PRIMARY KEY);");
    write("V2__b.sql", "CREATE TABLE b (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());

    Files.delete(migrations.resolve("V2__b.sql"));
    List<LogRecord> logged = logged(() -> runner(schema).migrate(locations()));

    assertEquals(List.of(), levels(logged, Level.WARNING, Level.SEVERE));
  }

  // ── a build with no migration at all stops the service ───────────────────────

  @Test
  @DisplayName("a build with no versioned migration stops startup before anything is created")
  void noVersionedMigrationStopsStartupOnAFreshDatabase() throws SQLException {
    String schema = newSchema();
    FlywayRunner runner = runner(schema);
    // Only this module's afterMigrate callbacks are on the classpath: what a service jar built
    // without its db/migration folder sees.
    runner.locations = new String[] {"classpath:db/migration"};

    FlywayRunner.MigrationFailedException refused =
        assertThrows(FlywayRunner.MigrationFailedException.class, () -> runner.onStart(null));

    assertTrue(refused.getMessage().contains("No versioned migration"), refused.getMessage());
    assertTrue(refused.getMessage().contains("classpath:db/migration"), refused.getMessage());
    assertFalse(schemaExists(schema), "the schema was not created for a build that has no tables");
  }

  @Test
  @DisplayName("a build with no versioned migration is logged at ERROR")
  void noVersionedMigrationIsLoggedAtError() {
    FlywayRunner runner = runner(newSchema());
    List<LogRecord> logged = new ArrayList<>();

    FlywayRunner.MigrationFailedException refused =
        assertThrows(
            FlywayRunner.MigrationFailedException.class,
            () -> capture(logged, () -> runner.migrate("classpath:db/migration")));

    List<LogRecord> errors = levels(logged, Level.SEVERE);
    assertEquals(1, errors.size(), String.valueOf(logged));
    assertEquals(refused.getMessage(), errors.get(0).getMessage());
  }

  @Test
  @DisplayName("a build with no versioned migration stops startup on an already migrated schema")
  void noVersionedMigrationStopsStartupOnAMigratedSchema() throws SQLException {
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());

    FlywayRunner jarWithoutMigrations = runner(schema);

    FlywayRunner.MigrationFailedException refused =
        assertThrows(
            FlywayRunner.MigrationFailedException.class,
            () -> jarWithoutMigrations.migrate("classpath:db/migration"));

    assertTrue(refused.getMessage().contains("No versioned migration"), refused.getMessage());
    assertEquals(1, appliedVersions(schema));
  }

  @Test
  @DisplayName("a baseline row is not a migration: a baselined schema and no files stops startup")
  void noVersionedMigrationStopsStartupOnABaselinedSchema() throws SQLException {
    // A schema that holds a table but no history is baselined at version 1 on the first start.
    String schema = newSchema();
    try (Connection c = pg.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA \"" + schema + "\"");
      st.execute("CREATE TABLE \"" + schema + "\".stray (id int)");
    }
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    runner(schema).migrate(locations());
    assertEquals(1, appliedVersions(schema), "the baseline row is the one version recorded");
    assertFalse(tableExists(schema, "orders"), "V1 was baselined over, not run");

    FlywayRunner jarWithoutMigrations = runner(schema);

    assertThrows(
        FlywayRunner.MigrationFailedException.class,
        () -> jarWithoutMigrations.migrate("classpath:db/migration"));
  }

  // ── a misspelled file name is not skipped quietly ────────────────────────────

  @Test
  @DisplayName("a misspelled migration name is logged by name and nothing is applied")
  void aMisspelledMigrationNameIsNotSkippedQuietly() throws SQLException {
    write("V1__a.sql", "CREATE TABLE a (id int PRIMARY KEY);");
    write("V2_b.sql", "CREATE TABLE b (id int PRIMARY KEY);"); // one underscore
    write("v3__c.sql", "CREATE TABLE c (id int PRIMARY KEY);"); // lower-case prefix
    String schema = newSchema();

    List<LogRecord> logged = logged(() -> runner(schema).migrate(locations()));

    List<LogRecord> warnings = levels(logged, Level.WARNING);
    assertTrue(
        warnings.stream().anyMatch(r -> r.getMessage().contains("V2_b.sql")),
        "the warning names the misspelled file: "
            + warnings.stream().map(LogRecord::getMessage).toList());
    assertFalse(tableExists(schema, "a"), "a set with a misspelled name is applied by no part");
    assertFalse(tableExists(schema, "b"));
  }

  @Test
  @DisplayName("PostgresSupport.migrate throws on a misspelled migration name")
  void postgresSupportRefusesAMisspelledName() {
    write("V1__a.sql", "CREATE TABLE a (id int PRIMARY KEY);");
    write("V2_b.sql", "CREATE TABLE b (id int PRIMARY KEY);");

    FlywayException failed =
        assertThrows(FlywayException.class, () -> pg.migrate("filesystem:" + migrations));

    assertTrue(failed.getMessage().contains("V2_b.sql"), failed.getMessage());
  }

  // ── everything else is deferred, as it was before ────────────────────────────

  @Test
  @DisplayName("a database that cannot be reached is logged at WARNING and startup goes on")
  void anUnreachableDatabaseIsDeferred() {
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    FlywayRunner runner = runner(newSchema(), closedPortUrl());
    List<LogRecord> logged = new ArrayList<>();

    assertDoesNotThrow(() -> capture(logged, () -> runner.onStart(null)));

    assertEquals(List.of(), levels(logged, Level.SEVERE));
    List<LogRecord> warnings = levels(logged, Level.WARNING);
    assertEquals(1, warnings.size(), String.valueOf(logged));
    assertTrue(
        warnings.get(0).getMessage().startsWith("Flyway migration deferred"),
        warnings.get(0).getMessage());
  }

  @Test
  @DisplayName("a migration whose SQL fails is logged at WARNING and startup goes on")
  void aFailingMigrationIsDeferred() throws SQLException {
    write("V1__a.sql", "CREATE TABLE a (id int PRIMARY KEY);");
    write("V2__broken.sql", "CREATE TABEL b (id int PRIMARY KEY);");
    String schema = newSchema();
    FlywayRunner runner = runner(schema);
    List<LogRecord> logged = new ArrayList<>();

    assertDoesNotThrow(() -> capture(logged, () -> runner.onStart(null)));

    assertEquals(List.of(), levels(logged, Level.SEVERE));
    List<LogRecord> warnings = levels(logged, Level.WARNING);
    assertEquals(1, warnings.size(), String.valueOf(logged));
    assertTrue(
        warnings.get(0).getMessage().contains("V2__broken.sql"), warnings.get(0).getMessage());
    assertTrue(tableExists(schema, "a"), "the migration before the broken one was applied");
  }

  @Test
  @DisplayName(
      "a role with no DDL rights on a migrated schema is logged at WARNING and not refused")
  void aRoleWithoutDdlRightsIsDeferred() throws SQLException {
    // The schema is migrated by its owner. The role the runner then uses can read and write the
    // tables but cannot create objects in the schema, so the afterMigrate callback that runs
    // CREATE OR REPLACE FUNCTION is refused (SQL state 42501).
    write("V1__orders.sql", "CREATE TABLE orders (id int PRIMARY KEY);");
    String schema = newSchema();
    runner(schema).migrate(locations());
    String role = "dml_" + Ids.shortRef(Ids.newId());
    try (Connection c = pg.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE ROLE \"" + role + "\" LOGIN PASSWORD 'dml'");
      st.execute("GRANT USAGE ON SCHEMA \"" + schema + "\" TO \"" + role + "\"");
      st.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA \""
              + schema
              + "\" TO \""
              + role
              + "\"");
    }
    FlywayRunner dmlOnly = runner(new Settings(pg.jdbcUrl(), schema, role, "dml"));
    List<LogRecord> logged = new ArrayList<>();

    assertDoesNotThrow(() -> capture(logged, () -> dmlOnly.migrate(locations())));

    assertEquals(List.of(), levels(logged, Level.SEVERE));
    List<LogRecord> warnings = levels(logged, Level.WARNING);
    assertEquals(1, warnings.size(), String.valueOf(logged));
    assertTrue(warnings.get(0).getMessage().contains("42501"), warnings.get(0).getMessage());
  }

  // ── wiring ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("onStart observes the container's initialisation")
  void onStartIsTheContainerInitialisedObserver() throws NoSuchMethodException {
    Method onStart = FlywayRunner.class.getDeclaredMethod("onStart", Object.class);
    Parameter event = onStart.getParameters()[0];

    assertTrue(event.isAnnotationPresent(Observes.class));
    assertEquals(ApplicationScoped.class, event.getAnnotation(Initialized.class).value());
    assertTrue(FlywayRunner.class.isAnnotationPresent(ApplicationScoped.class));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** What the service's own {@code ServiceConfig} would say, with the database and role chosen. */
  private static final class Settings implements ServiceSettings {
    final String url;
    final String schema;
    final String user;
    final String password;

    Settings(String url, String schema, String user, String password) {
      this.url = url;
      this.schema = schema;
      this.user = user;
      this.password = password;
    }

    @Override
    public String serviceName() {
      return "flyway-runner-test";
    }

    @Override
    public int servicePort() {
      return 0;
    }

    @Override
    public String dbUrl() {
      return url;
    }

    @Override
    public String dbMigrationUrl() {
      return url;
    }

    @Override
    public String dbUser() {
      return user;
    }

    @Override
    public String dbPassword() {
      return password;
    }

    @Override
    public String dbSchema() {
      return schema;
    }

    @Override
    public boolean consulEnabled() {
      return false;
    }

    @Override
    public String consulHost() {
      return "localhost";
    }

    @Override
    public int consulPort() {
      return 8500;
    }

    @Override
    public boolean kafkaEnabled() {
      return false;
    }

    @Override
    public String kafkaBootstrap() {
      return "localhost:9092";
    }

    @Override
    public long outboxPollSeconds() {
      return 5;
    }
  }

  private FlywayRunner runner(String schema) {
    return runner(schema, pg.jdbcUrl());
  }

  private FlywayRunner runner(String schema, String url) {
    return runner(new Settings(url, schema, pg.username(), pg.password()));
  }

  private FlywayRunner runner(Settings settings) {
    FlywayRunner runner = new FlywayRunner();
    runner.settings = settings;
    runner.locations = locations();
    return runner;
  }

  /** The files under test plus the callbacks every service gets from this module. */
  private String[] locations() {
    return new String[] {"filesystem:" + migrations, "classpath:db/migration"};
  }

  private void write(String name, String sql) {
    try {
      Files.writeString(migrations.resolve(name), sql);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String newSchema() {
    return "fw_" + Ids.shortRef(Ids.newId());
  }

  /** A JDBC URL for a port nothing listens on: connection refused, at once. */
  private static String closedPortUrl() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "jdbc:postgresql://127.0.0.1:" + socket.getLocalPort() + "/storeql_test";
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs {@code action} and returns what the runner logged meanwhile. */
  private static List<LogRecord> logged(Runnable action) {
    List<LogRecord> records = new ArrayList<>();
    capture(records, action);
    return records;
  }

  /**
   * Runs {@code action}, adding what the runner logs meanwhile to {@code into}, even on a throw.
   */
  private static void capture(List<LogRecord> into, Runnable action) {
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            into.add(record);
          }

          @Override
          public void flush() {
            // nothing buffered
          }

          @Override
          public void close() {
            // nothing held
          }
        };
    Logger logger = Logger.getLogger(FlywayRunner.class.getName());
    logger.addHandler(capture);
    try {
      action.run();
    } finally {
      logger.removeHandler(capture);
    }
  }

  private static List<LogRecord> levels(List<LogRecord> records, Level... wanted) {
    List<Level> accepted = List.of(wanted);
    return records.stream().filter(r -> accepted.contains(r.getLevel())).toList();
  }

  private static boolean schemaExists(String schema) throws SQLException {
    try (Connection c = pg.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1) > 0;
      }
    }
  }

  private static boolean tableExists(String schema, String table) throws SQLException {
    try (Connection c = pg.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      ps.setString(1, "\"" + schema + "\"." + table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private static int appliedVersions(String schema) throws SQLException {
    try (Connection c = pg.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM \""
                    + schema
                    + "\".flyway_schema_history"
                    + " WHERE success AND version IS NOT NULL")) {
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
