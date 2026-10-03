package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the connection-acquire retry logic in BaseJdbcRepository.acquireConnection().
 * acquireConnection() is private, so we exercise it through the protected inTx() method. Follows
 * the same reflection-based approach as KafkaEventLoopTest — no DI seam, no Mockito.
 */
class BaseJdbcRepositoryTest {

  private static class TestRepo extends BaseJdbcRepository {
    @Override
    protected RuntimeException handleTxSqlException(String what, SQLException e) {
      return new RuntimeException(e);
    }
  }

  private static void injectDataSource(BaseJdbcRepository repo, DataSource ds) throws Exception {
    Field f = BaseJdbcRepository.class.getDeclaredField("dataSource");
    f.setAccessible(true);
    f.set(repo, ds);
  }

  /** A no-op Connection proxy — setAutoCommit/commit/rollback/close are all silent. */
  private static Connection noopConnection() {
    return (Connection)
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Class<?> ret = method.getReturnType();
              if (ret == boolean.class) return false;
              if (ret == int.class) return 0;
              return null;
            });
  }

  /**
   * DataSource that throws a transient SQLException for the first {@code failCount} calls to
   * getConnection(), then returns a no-op connection. Used to drive acquireConnection()'s retry
   * loop without a real database.
   */
  private static DataSource stubbedDataSource(int failCount) {
    AtomicInteger calls = new AtomicInteger(0);
    return (DataSource)
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if ("getConnection".equals(method.getName())) {
                if (calls.incrementAndGet() <= failCount) {
                  throw new SQLException("transient-pool-contention-" + calls.get());
                }
                return noopConnection();
              }
              return null;
            });
  }

  @Test
  void inTxSucceedsWhenConnectionAvailableImmediately() throws Exception {
    var repo = new TestRepo();
    injectDataSource(repo, stubbedDataSource(0));
    assertDoesNotThrow(() -> repo.inTx(c -> "ok", "test"));
  }

  @Test
  void inTxRetriesAndSucceedsAfterTransientConnectionFailures() throws Exception {
    // Two consecutive failures then success — mirrors the pgbouncer contention scenario described
    // in the pgbouncer-gotchas memory: same call retried 1-2x always succeeded.
    var repo = new TestRepo();
    injectDataSource(repo, stubbedDataSource(2));
    assertDoesNotThrow(() -> repo.inTx(c -> "ok", "test"));
  }

  @Test
  void inTxThrowsAfterAllAttemptsExhausted() throws Exception {
    // maxAttempts = 3 in acquireConnection(); 3 failures must surface as an exception.
    var repo = new TestRepo();
    injectDataSource(repo, stubbedDataSource(3));
    var ex = assertThrows(Exception.class, () -> repo.inTx(c -> "ok", "test"));
    assertNotNull(ex);
  }

  @Test
  void inTxExecutesTxWorkExactlyOnceEvenWhenAcquireIsRetried() throws Exception {
    // Prove retry scope: only the acquire step is retried; work.run(c) must execute exactly once.
    var repo = new TestRepo();
    AtomicInteger workCount = new AtomicInteger(0);
    injectDataSource(repo, stubbedDataSource(1));
    repo.inTx(
        c -> {
          workCount.incrementAndGet();
          return null;
        },
        "test");
    assertEquals(1, workCount.get());
  }

  @Test
  void aPoolTimeoutIsNotRetried() throws Exception {
    // Retrying an exhausted pool triples the caller's wait: one attempt, then the failure.
    AtomicInteger calls = new AtomicInteger();
    DataSource ds =
        (DataSource)
            Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  calls.incrementAndGet();
                  throw new java.sql.SQLTransientConnectionException(
                      "pool - Connection is not available, request timed out after 30000ms.");
                });
    var repo = new TestRepo();
    injectDataSource(repo, ds);
    assertThrows(Exception.class, () -> repo.inTx(c -> "ok", "test"));
    assertEquals(1, calls.get());
  }

  @Test
  void aSlowFailedAcquireIsNotRetried() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    DataSource ds =
        (DataSource)
            Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  calls.incrementAndGet();
                  Thread.sleep(30);
                  throw new SQLException("slow failure");
                });
    var repo =
        new TestRepo() {
          @Override
          protected long acquireRetryMaxAttemptMillis() {
            return 10; // an attempt that took longer than this is the pool waiting, not a blip
          }
        };
    injectDataSource(repo, ds);
    assertThrows(Exception.class, () -> repo.inTx(c -> "ok", "test"));
    assertEquals(1, calls.get());
  }
}
