import org.flywaydb.core.Flyway;

/**
 * One Flyway step as the services run it, for scripts/upgrade-selftest.sh (same Flyway as the services,
 * the settings FlywayRunner pins). Usage: FlywayStep <jdbc-url> <user> <password> <schema> <dir> migrate|validate
 * Exits 0 on success; on a Flyway refusal prints its message and exits 1.
 */
public final class FlywayStep {
  private FlywayStep() {}

  public static void main(String[] a) {
    try {
      Flyway f =
          Flyway.configure()
              .dataSource(a[0], a[1], a[2])
              .schemas(a[3])
              .defaultSchema(a[3])
              .locations("filesystem:" + a[4])
              .validateOnMigrate(true)
              .outOfOrder(false)
              .cleanDisabled(true)
              .validateMigrationNaming(true)
              // an image older than the schema still starts: Flyway's own default, kept
              .ignoreMigrationPatterns("*:future")
              .load();
      if ("migrate".equals(a[5])) {
        f.migrate();
      } else {
        f.validate();
      }
    } catch (RuntimeException e) {
      System.out.println(String.valueOf(e.getMessage()).lines().findFirst().orElse(e.toString()));
      System.exit(1);
    }
  }
}
