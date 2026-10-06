package com.storeql.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.stream.Collectors;
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
 * <p>Any other exception (database unreachable, a migration or callback that fails, a missing
 * privilege, a migration file Flyway cannot recognise by its name) is logged at {@code WARNING} and
 * the service keeps starting, as services start in any order (docs/ARCHITECTURE.md §17). This class
 * does not try again.
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

  /**
   * Runs pending migrations from {@code classpath:db/migration} into {@link
   * ServiceSettings#dbSchema()}, creating the schema and baselining if needed. See the class
   * comment for what stops startup and what is only logged.
   *
   * @param event the CDI initialization event payload; unused, only its firing matters
   * @throws MigrationFailedException when validation fails or the build has no versioned migration
   */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    migrate(locations);
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
      Flyway flyway =
          Flyway.configure()
              .dataSource(settings.dbMigrationUrl(), settings.dbUser(), settings.dbPassword())
              .locations(where)
              .schemas(schema)
              .defaultSchema(schema)
              .createSchemas(true)
              .baselineOnMigrate(true)
              // A file whose name Flyway cannot read (V2_b.sql, v3__c.sql) fails the attempt
              // instead of being left out of it.
              .validateMigrationNaming(true)
              .load();
      if (!foundVersionedMigration(flyway)) {
        throw noMigrations(where);
      }
      var result = flyway.migrate();
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
