package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * {@code inTx} must leave NOTHING committed when the unit of work fails, whatever it throws. The
 * fake connection models the JDBC rule that bit us: switching auto-commit back on COMMITS an open
 * transaction, so a failure path that skips the rollback commits its half-done writes.
 */
class InTxRollbackTest {

  /** Writes land in {@code pending}; commit (or setAutoCommit(true)) moves them to committed. */
  private static final class FakeDb {
    final List<String> pending = new ArrayList<>();
    final List<String> committed = new ArrayList<>();
    int rollbacks;
    boolean autoCommit = true;

    Connection connection() {
      return (Connection)
          Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "setAutoCommit" -> {
                    boolean on = (Boolean) args[0];
                    if (on && !autoCommit) flush();
                    autoCommit = on;
                    return null;
                  }
                  case "commit" -> {
                    flush();
                    return null;
                  }
                  case "rollback" -> {
                    pending.clear();
                    rollbacks++;
                    return null;
                  }
                  case "prepareStatement" -> {
                    return statement((String) args[0]);
                  }
                  default -> {
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    return null;
                  }
                }
              });
    }

    private void flush() {
      committed.addAll(pending);
      pending.clear();
    }

    private PreparedStatement statement(String sql) {
      return (PreparedStatement)
          Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) -> {
                if ("executeUpdate".equals(method.getName())) {
                  pending.add(sql);
                  return 1;
                }
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                return null;
              });
    }
  }

  private static final class Repo extends BaseOutboxRepository {
    Repo(FakeDb db) throws Exception {
      Field f = BaseJdbcRepository.class.getDeclaredField("dataSource");
      f.setAccessible(true);
      f.set(
          this,
          Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {DataSource.class},
              (p, m, a) -> "getConnection".equals(m.getName()) ? db.connection() : null));
    }

    /** A business write plus its outbox event, then the given failure (null = succeed). */
    String writeThen(Throwable failure) {
      return inTx(
          c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO business_row")) {
              ps.executeUpdate();
            }
            insertOutbox(c, new OutboxRow("T", "topic", Ids.newId(), Ids.newId(), "{}"));
            if (failure instanceof SQLException se) throw se;
            if (failure instanceof RuntimeException re) throw re;
            if (failure instanceof Error er) throw er;
            return "done";
          },
          "write");
    }
  }

  @Test
  void aRuntimeExceptionAfterTheWritesCommitsNeitherTheRowNorItsOutboxEvent() throws Exception {
    FakeDb db = new FakeDb();
    var boom = new IllegalStateException("loyalty account disappeared after update");
    var thrown = assertThrows(IllegalStateException.class, () -> new Repo(db).writeThen(boom));
    assertSame(boom, thrown, "rethrown unchanged");
    assertTrue(db.committed.isEmpty(), "nothing committed, got " + db.committed);
    assertTrue(db.pending.isEmpty());
    assertTrue(db.rollbacks >= 1);
    assertTrue(db.autoCommit, "connection handed back in auto-commit mode");
  }

  @Test
  void anErrorIsRolledBackAndRethrownUnchangedToo() throws Exception {
    FakeDb db = new FakeDb();
    var boom = new AssertionError("an Error, not an Exception");
    var thrown = assertThrows(AssertionError.class, () -> new Repo(db).writeThen(boom));
    assertSame(boom, thrown);
    assertTrue(db.committed.isEmpty(), "nothing committed, got " + db.committed);
  }

  @Test
  void anApiExceptionStillRollsBackAndPropagates() throws Exception {
    FakeDb db = new FakeDb();
    var refusal = new ApiException(409, "X", "no", List.of());
    var thrown = assertThrows(ApiException.class, () -> new Repo(db).writeThen(refusal));
    assertSame(refusal, thrown);
    assertTrue(db.committed.isEmpty());
  }

  @Test
  void aSqlExceptionStillRollsBackAndIsMappedToDbError() throws Exception {
    FakeDb db = new FakeDb();
    var thrown =
        assertThrows(
            ApiException.class, () -> new Repo(db).writeThen(new SQLException("bad", "22000")));
    assertEquals("DB_ERROR", thrown.code());
    assertTrue(db.committed.isEmpty());
  }

  @Test
  void aSuccessfulUnitOfWorkCommitsBothRows() throws Exception {
    FakeDb db = new FakeDb();
    assertEquals("done", new Repo(db).writeThen(null));
    assertEquals(2, db.committed.size());
    assertTrue(db.committed.get(0).contains("business_row"));
    assertTrue(db.committed.get(1).contains("outbox"));
    assertFalse(db.pending.stream().anyMatch(s -> true));
  }
}
