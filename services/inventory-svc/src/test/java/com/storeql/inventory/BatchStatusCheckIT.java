package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.test.PostgresSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * A batch's lifecycle status is held to the values the code writes. Only ACTIVE is ever written (a
 * batch that runs out keeps its status and its {@code remaining_qty} says so; one past its date is
 * judged by {@code Expiry}, never by a status), so the database refuses any other word, and the
 * constants of {@code Batch} are exactly what the constraint allows.
 */
class BatchStatusCheckIT {

  private static final String SCHEMA = "inventory";

  private static final PostgresSupport PG = PostgresSupport.start();

  static {
    Flyway.configure()
        .dataSource(PG.jdbcUrl(), PG.username(), PG.password())
        .locations("classpath:db/migration")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .load()
        .migrate();
  }

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private static PGSimpleDataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(PG.jdbcUrl());
    ds.setUser(PG.username());
    ds.setPassword(PG.password());
    ds.setCurrentSchema(SCHEMA);
    return ds;
  }

  /** Inserts a batch with this status; the other columns are whatever they must be. */
  private static void insertWithStatus(String status) throws SQLException {
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO inventory_batches (id, tenant_id, store_id, variant_id, received_qty,"
                    + " remaining_qty, status) VALUES (?, ?, ?, ?, 1, 1, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.newId());
      ps.setObject(3, Ids.newId());
      ps.setObject(4, Ids.newId());
      ps.setString(5, status);
      ps.executeUpdate();
    }
  }

  private static List<String> statusConstants() {
    return java.util.Arrays.stream(Batch.class.getFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("STATUS_"))
        .map(BatchStatusCheckIT::valueOf)
        .toList();
  }

  private static String valueOf(Field f) {
    try {
      return (String) f.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("A batch status the code never writes is refused by the database")
  void aStatusNobodyWritesIsRefused() throws SQLException {
    insertWithStatus("ACTIVE");
    for (String word : new String[] {"DEPLETED", "EXPIRED", "active", "", "CANCELLED"}) {
      SQLException refused =
          org.junit.jupiter.api.Assertions.assertThrows(
              SQLException.class, () -> insertWithStatus(word), "'" + word + "' must be refused");
      assertThat(refused.getSQLState(), is("23514"));
      assertThat(refused.getMessage(), containsString("chk_batch_status"));
    }
  }

  @Test
  @DisplayName("The statuses Batch defines are exactly the ones the constraint allows")
  void theConstantsAreTheConstraint() throws SQLException {
    String definition;
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conname = 'chk_batch_status'")) {
      assertThat("the constraint exists", rs.next(), is(true));
      definition = rs.getString(1);
    }
    TreeSet<String> allowed = new TreeSet<>();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(definition);
    while (m.find()) allowed.add(m.group(1));
    assertThat(definition, new TreeSet<>(statusConstants()), is(allowed));
    for (String status : statusConstants()) {
      insertWithStatus(status);
    }
  }
}
