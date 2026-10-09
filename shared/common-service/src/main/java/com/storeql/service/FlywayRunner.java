package com.storeql.service;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;

/**
 * Runs Flyway migrations on startup into the service's own schema. Dev/template convenience:
 * docs/ARCHITECTURE.md §17 describes production running migrations in a separate Job instead.
 *
 * <p>Two outcomes stop the service from starting, each as a {@link MigrationFailedException} that
 * is also logged at {@code ERROR}:
 *
 * <ul>
 *   <li>Flyway's validation of the schema fails, for example on an applied migration whose file has
 *       changed, an applied version whose file is gone (Flyway's default lets one newer than every
 *       file through, so an older build still starts on a newer schema), or a migration numbered
 *       below the applied ones. Starting again changes nothing until the database and the files
 *       agree.
 *   <li>No versioned migration was found at the locations, as for a service jar built without its
 *       {@code db/migration} folder.
 * </ul>
 *
 * <p>What happens to any other failure (database unreachable, a migration or callback that fails, a
 * missing privilege, a migration file Flyway cannot recognise by its name) depends on {@code
 * storeql.db.migrate.mode} ({@code STOREQL_DB_MIGRATE_MODE}):
 *
 * <ul>
 *   <li>{@code lenient} (the default, as before): logged at {@code WARNING} and the service keeps
 *       starting, as services start in any order (docs/ARCHITECTURE.md §17). Nothing tries again.
 *       Right for development and for pods that cannot reach Postgres directly.
 *   <li>{@code strict}, for a deployment that migrates itself (the pilot's single VM): a migration
 *       that fails, a missing privilege, a lock timeout or a schema that has tables and no history
 *       throws {@link MigrationFailedException} and the service does not start. A database that
 *       cannot be reached is retried with a growing wait, and {@code /health/ready} is DOWN ({@link
 *       HealthChecks.SchemaReadiness}) until the schema is migrated, so a half-migrated schema is
 *       never served.
 *   <li>{@code off}: nothing is migrated here because a Job does it; the service is not held back.
 * </ul>
 *
 * <p>Any other value stops startup, so a misspelled mode is never quietly lenient. Flyway's own
 * settings are pinned: validation on, no out-of-order migrations, {@code clean} disabled, and
 * (Flyway's default) a schema newer than this build's files still starts, so the previous image
 * runs on the next release's schema.
 */
@ApplicationScoped
public class FlywayRunner {

  private static final Logger LOG = System.getLogger(FlywayRunner.class.getName());

  /** Where a service's own migrations (and this module's afterMigrate callbacks) are found. */
  private static final String[] DEFAULT_LOCATIONS = {"classpath:db/migration"};

  /**
   * The migrations and the database disagree, or the build has no migration to apply. Thrown from
   * startup so the service does not run.
   */
  public static final class MigrationFailedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    MigrationFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  @Inject ServiceSettings settings;

  /** Where migrations are read from; a test points it at files on disk. */
  String[] locations = DEFAULT_LOCATIONS;

  /** The mode, when a test sets it; otherwise it is read from configuration. */
  String mode;

  /** First wait before trying an unreachable database again; it doubles up to the maximum. */
  long retryBackoffMillis = 1_000;

  private static final long MAX_BACKOFF_MILLIS = 30_000;

  /** Whether the schema is known to be at this build's version. */
  private volatile boolean current;

  /** The migration failure a strict start recorded, which keeps readiness DOWN. */
  private volatile MigrationFailedException failure;

  /** The background retry of an unreachable database, ended when the service shuts down. */
  private volatile Thread retry;

  private volatile boolean stopped;

  /** What one strict attempt came to. */
  enum Outcome {
    DONE,
    UNREACHABLE
  }

  /**
   * The migrate mode in force: {@code lenient} unless configured.
   *
   * @return {@code lenient}, {@code strict} or {@code off} (anything else is refused at start)
   */
  public String mode() {
    String m = mode;
    if (m == null) {
      try {
        m =
            ConfigProvider.getConfig()
                .getOptionalValue("storeql.db.migrate.mode", String.class)
                .orElse(null);
      } catch (RuntimeException | LinkageError noConfig) {
        m = null;
      }
      if (m == null) {
        m = System.getProperty("storeql.db.migrate.mode", System.getenv("STOREQL_DB_MIGRATE_MODE"));
      }
    }
    return m == null || m.isBlank() ? "lenient" : m.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Whether this process knows the schema is migrated (strict: it ran the migrations or found them
   * run; off: assumed; lenient: after a successful run).
   *
   * @return {@code true} once the schema is at this build's version as far as this process knows
   */
  public boolean schemaCurrent() {
    return current;
  }

  /**
   * Whether readiness must be DOWN for the schema: a {@code strict} deployment whose schema is not
   * yet migrated. Never in {@code lenient} or {@code off} mode.
   *
   * @return {@code true} while a strict deployment must not be served
   */
  public boolean holdsReadiness() {
    return "strict".equals(mode()) && !current;
  }

  /** Ends the background retry when the service shuts down. */
  @PreDestroy
  void stop() {
    stopped = true;
    Thread t = retry;
    if (t != null) {
      t.interrupt();
    }
  }

  /**
   * The failure a strict start recorded, if any.
   *
   * @return the exception that kept the schema from being migrated, or {@code null}
   */
  MigrationFailedException failure() {
    return failure;
  }

  /**
   * Runs pending migrations from {@code classpath:db/migration} into {@link
   * ServiceSettings#dbSchema()}, creating the schema and baselining if needed. See the class
   * comment for what stops startup and what is only logged.
   *
   * @param event the CDI initialization event payload; unused, only its firing matters
   * @throws MigrationFailedException when validation fails or the build has no versioned migration
   */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    String m = mode();
    switch (m) {
      case "lenient" -> migrate(locations);
      case "strict" -> startStrict();
      case "off" -> {
        current = true;
        LOG.log(Level.INFO, "Flyway is off: this process applies no migrations (a Job does)");
      }
      default ->
          throw new IllegalArgumentException(
              "storeql.db.migrate.mode \""
                  + m
                  + "\" is not one of lenient, strict, off: a mode nobody defined is not guessed");
    }
  }

  /**
   * One attempt against the locations given.
   *
   * @param where the Flyway locations to read migrations from
   * @throws MigrationFailedException when validation fails or none of {@code where} holds a
   *     versioned migration
   */
  void migrate(String... where) {
    String schema = settings.dbSchema();
    try {
      Flyway flyway = flywayFor(false, where);
      if (!foundVersionedMigration(flyway)) {
        throw noMigrations(where);
      }
      var result = flyway.migrate();
      current = true;
      LOG.log(
          Level.INFO,
          "Flyway applied {0} migration(s) to schema {1}",
          result.migrationsExecuted,
          schema);
    } catch (MigrationFailedException refused) {
      throw refused;
    } catch (FlywayValidateException mismatch) {
      throw mismatch(schema, mismatch);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Flyway migration deferred (DB not ready?): " + e.getMessage());
    }
  }

  /** Flyway as configured for this process; strict does not baseline over somebody's tables. */
  private Flyway flywayFor(boolean strict, String... where) {
    String schema = settings.dbSchema();
    return Flyway.configure()
        .dataSource(settings.dbMigrationUrl(), settings.dbUser(), settings.dbPassword())
        .locations(where)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        // A schema with tables and no history is baselined over only when lenient. Strict refuses
        // it: starting version 2 onwards on objects nobody recorded is how data is damaged.
        .baselineOnMigrate(!strict)
        // Pinned, not left to defaults: what is applied is what the files say, in order, and a
        // deployment can never clean the schema.
        .validateOnMigrate(true)
        .outOfOrder(false)
        .cleanDisabled(true)
        // A file whose name Flyway cannot read (V2_b.sql, v3__c.sql) fails the attempt
        // instead of being left out of it.
        .validateMigrationNaming(true)
        .load();
  }

  /**
   * The first strict attempt; a database that cannot be reached is then retried in the background.
   */
  private void startStrict() {
    if (migrateStrict(locations) == Outcome.UNREACHABLE) {
      LOG.log(
          Level.WARNING,
          "Flyway (strict): the database cannot be reached; trying again with a growing wait."
              + " /health/ready is DOWN until the schema is migrated.");
      retry = Thread.ofVirtual().name("flyway-retry").start(this::retryUntilMigrated);
    }
  }

  /**
   * One strict attempt.
   *
   * @return {@code DONE} when the schema is migrated, {@code UNREACHABLE} when the database could
   *     not be reached
   * @throws MigrationFailedException for everything else that goes wrong
   */
  Outcome migrateStrict(String... where) {
    String schema = settings.dbSchema();
    try {
      Flyway flyway = flywayFor(true, where);
      if (!foundVersionedMigration(flyway)) {
        throw noMigrations(where);
      }
      var result = flyway.migrate();
      current = true;
      failure = null;
      LOG.log(
          Level.INFO,
          "Flyway applied {0} migration(s) to schema {1}",
          result.migrationsExecuted,
          schema);
      return Outcome.DONE;
    } catch (MigrationFailedException refused) {
      failure = refused;
      throw refused;
    } catch (FlywayValidateException mismatch) {
      MigrationFailedException refused = mismatch(schema, mismatch);
      failure = refused;
      throw refused;
    } catch (Exception e) {
      if (unreachable(e)) {
        LOG.log(Level.WARNING, "Flyway migration deferred (DB not ready?): " + e.getMessage());
        return Outcome.UNREACHABLE;
      }
      MigrationFailedException failed = migrationFailed(schema, e);
      failure = failed;
      throw failed;
    }
  }

  /** Tries again, waiting longer each time, until migrated or a migration itself fails. */
  private void retryUntilMigrated() {
    long wait = Math.max(1, retryBackoffMillis);
    while (!current && failure == null && !stopped) {
      try {
        Thread.sleep(wait);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        if (migrateStrict(locations) == Outcome.DONE) {
          return;
        }
      } catch (MigrationFailedException refused) {
        return; // recorded and logged at ERROR; readiness stays DOWN, and a retry cannot cure it
      }
      wait = Math.min(wait * 2, MAX_BACKOFF_MILLIS);
    }
  }

  /**
   * Whether the failure is only that the database cannot be reached (connection refused, host not
   * found, a timeout, the server still starting or full), which waiting cures, and not a migration
   * that ran and failed.
   */
  private static boolean unreachable(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof ConnectException
          || t instanceof UnknownHostException
          || t instanceof SocketTimeoutException) {
        return true;
      }
      if (t instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null
            && (state.startsWith("08") || "57P03".equals(state) || "53300".equals(state))) {
          return true;
        }
      }
    }
    return false;
  }

  /** Logs at {@code ERROR} and builds the exception for a migration that ran and failed. */
  private static MigrationFailedException migrationFailed(String schema, Exception failure) {
    String message =
        "Migrating schema \""
            + schema
            + "\" failed, so the service will not serve a half-migrated schema."
            + "\nFlyway says:\n"
            + indent(flywaysReport(failure))
            + "\nThe migration that failed was rolled back; fix it, or restore the database from the"
            + " backup taken before this release, and start again.";
    LOG.log(Level.ERROR, message);
    return new MigrationFailedException(message, failure);
  }

  /**
   * Whether Flyway resolved a versioned migration. An applied version whose file is gone and a
   * baseline row are in the history but are not files, so neither counts.
   */
  private static boolean foundVersionedMigration(Flyway flyway) {
    return Arrays.stream(flyway.info().all())
        .anyMatch(m -> m.isVersioned() && m.getState().isResolved() && !m.getType().isSynthetic());
  }

  /** Logs at {@code ERROR} and builds the exception for a schema that failed validation. */
  private static MigrationFailedException mismatch(String schema, FlywayValidateException failure) {
    String message =
        "Database schema \""
            + schema
            + "\" does not match this build's migrations, so the service will not start."
            + "\nFlyway says:\n"
            + indent(flywaysReport(failure))
            + "\nStarting again changes nothing until the database and the migrations agree."
            + "\nIn development, reset the database (docker compose down -v) and start again."
            + " In a deployment, restore the database from a backup taken before the change, or"
            + " deploy the build whose migrations match it. If the files are right and the history"
            + " is wrong, a person can run flyway repair, which brings the history's checksums into"
            + " line with the files and runs nothing.";
    LOG.log(Level.ERROR, message);
    return new MigrationFailedException(message, failure);
  }

  /** Logs at {@code ERROR} and builds the exception for a build that has no migration. */
  private static MigrationFailedException noMigrations(String... where) {
    String message =
        "No versioned migration (V<n>__name.sql) was found at "
            + String.join(", ", where)
            + ", so the service will not start. A service's migrations are read from its"
            + " db/migration folder: rebuild the service from a tree that has them.";
    LOG.log(Level.ERROR, message);
    return new MigrationFailedException(message, null);
  }

  /**
   * Flyway's own account of what failed, less the advertisement it appends to a validation report.
   */
  private static String flywaysReport(Exception failure) {
    return String.valueOf(failure.getMessage())
        .lines()
        .filter(line -> !line.startsWith("Need more flexibility"))
        .collect(Collectors.joining("\n"));
  }

  private static String indent(String text) {
    return "  " + text.strip().replace("\n", "\n  ");
  }
}
