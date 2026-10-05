package com.storeql.service;

import com.storeql.web.ApiException;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC utility base for all service repositories. Provides the DataSource, transaction management,
 * one-shot helpers, and shared functional interfaces — once, so repos don't each carry them.
 *
 * <p>CDI injects the superclass {@code dataSource} field when the concrete repo bean is resolved.
 *
 * <p>Typical subclass:
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class StoreRepository extends BaseOutboxRepository {
 *     public Store findById(UUID tenantId, UUID id) {
 *         return query(
 *                 "SELECT * FROM stores WHERE tenant_id = ? AND id = ?",
 *                 ps -> { ps.setObject(1, tenantId); ps.setObject(2, id); },
 *                 this::mapRow,
 *                 "find store")
 *             .stream().findFirst().orElseThrow(() -> ApiException.notFound(...));
 *     }
 * }
 * }</pre>
 */
public abstract class BaseJdbcRepository {

  /** PostgreSQL SQLSTATE code for unique-constraint violations (23505). */
  protected static final String UNIQUE_VIOLATION = "23505";

  @Inject protected DataSource dataSource;

  // ── Functional interfaces ─────────────────────────────────────────────────

  /** A unit of work run inside {@link #inTx}, given the transaction's open connection. */
  @FunctionalInterface
  protected interface TxWork<R> {
    /**
     * @param c the open, auto-commit-disabled connection for this transaction
     * @return the result to hand back from {@link #inTx}
     * @throws SQLException on any JDBC failure; triggers rollback and is mapped via {@link
     *     #handleTxSqlException}
     */
    R run(Connection c) throws SQLException;
  }

  /** Binds parameters onto a prepared statement before it executes. */
  @FunctionalInterface
  protected interface Binder {
    /**
     * @param ps the prepared statement to bind parameters onto
     * @throws SQLException if a {@code setXxx} call fails (e.g. wrong parameter index/type)
     */
    void bind(PreparedStatement ps) throws SQLException;
  }

  /** Maps one JDBC result-set row to a domain object. */
  @FunctionalInterface
  protected interface RowMapper<T> {
    /**
     * @param rs the result set, positioned on the row to map (do not call {@code next()})
     * @return the mapped row
     * @throws SQLException if a column read fails (e.g. wrong column name/type)
     */
    T map(ResultSet rs) throws SQLException;
  }

  // ── Transaction ───────────────────────────────────────────────────────────

  /**
   * Run {@code work} inside a single JDBC transaction. Any other throwable (RuntimeException or
   * Error) also rolls back and is rethrown unchanged: nothing is ever left committed by a failed
   * unit of work. Re-throws {@link ApiException} after rollback (so domain-level 4xx/5xx exceptions
   * propagate cleanly). Routes SQL exceptions through {@link #handleTxSqlException} so subclasses
   * can map unique-violation codes to service-specific error responses.
   *
   * @param work the transactional unit of work
   * @param what a short present-tense description used in error messages, e.g. {@code "create
   *     store"}
   * @return whatever {@code work} returns
   * @throws ApiException the exact exception thrown by {@code work} (after rollback), the result of
   *     {@link #handleTxSqlException} for a {@link SQLException}, or a generic {@code 500 DB_ERROR}
   *     if the connection itself could not be acquired
   */
  @SuppressWarnings("PMD.AvoidCatchingThrowable") // rolls back on every Throwable, then rethrows it
  protected <R> R inTx(TxWork<R> work, String what) {
    try (Connection c = acquireConnection()) {
      c.setAutoCommit(false);
      boolean open = true; // true until committed or rolled back
      try {
        R r = work.run(c);
        c.commit();
        open = false;
        return r;
      } catch (ApiException ae) {
        open = !rollbackQuietly(c, ae);
        throw ae;
      } catch (SQLException e) {
        open = !rollbackQuietly(c, e);
        throw handleTxSqlException(what, e);
      } catch (Throwable t) {
        // Any other RuntimeException or Error: leave nothing half-committed (setAutoCommit(true)
        // below would COMMIT an open transaction), and rethrow exactly what was thrown.
        open = !rollbackQuietly(c, t);
        throw t;
      } finally {
        if (open) open = !rollbackQuietly(c, null);
        // Never switch auto-commit on over a transaction that could not be rolled back: that would
        // COMMIT it. The connection is closed by the try-with-resources and the pool discards it.
        if (!open) c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw dbError(what + " (connection)", e);
    }
  }

  /** Roll back, never letting a failed rollback mask the failure that caused it. */
  private static boolean rollbackQuietly(Connection c, Throwable primary) {
    try {
      c.rollback();
      return true;
    } catch (SQLException re) {
      if (primary != null) primary.addSuppressed(re);
      return false;
    }
  }

  /**
   * Acquires a pooled connection, retrying briefly on transient failures (observed in practice as
   * pgbouncer transaction-pooling contention under concurrent writes: same call retried 1-2x always
   * succeeded). This only retries the acquire step itself, never {@code work.run(c)}, so a retry
   * can never double-execute business logic.
   *
   * <p>A failure that took a long time (the pool's own wait expiring) or that is the pool's
   * "request timed out" is NOT retried: retrying an exhausted pool triples the caller's wait while
   * the pool is already the bottleneck. The threshold is the MicroProfile Config key {@code
   * storeql.db.acquire.retry-max-attempt-ms} (default 1000).
   *
   * @return a pooled connection, ready for {@code setAutoCommit(false)}
   * @throws SQLException the last acquisition failure if all 3 attempts (with 100/200/300ms
   *     backoff) fail, or immediately if the failure was a pool timeout or the wait is interrupted
   */
  private Connection acquireConnection() throws SQLException {
    final int maxAttempts = 3;
    SQLException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      long started = System.nanoTime();
      try {
        return dataSource.getConnection();
      } catch (SQLException e) {
        last = e;
        if (attempt == maxAttempts || isPoolTimeout(e)) break;
        long tookMs = (System.nanoTime() - started) / 1_000_000L;
        if (tookMs > acquireRetryMaxAttemptMillis()) break;
        try {
          Thread.sleep(100L * attempt);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    throw last;
  }

  /** True for the pool's own "no connection became free in time" failure. */
  static boolean isPoolTimeout(SQLException e) {
    String m = e.getMessage();
    return e instanceof java.sql.SQLTransientConnectionException
        && m != null
        && m.contains("request timed out");
  }

  /** Longest a single failed acquire may have taken and still be retried. */
  protected long acquireRetryMaxAttemptMillis() {
    return Cfg.getLong("storeql.db.acquire.retry-max-attempt-ms", 1000L);
  }

  /**
   * Override to map a transaction-level {@link SQLException} to a service-specific exception (e.g.
   * check {@link #UNIQUE_VIOLATION} and return a domain 409). Default: generic DB_ERROR 500.
   *
   * @param what the description passed to the failing {@link #inTx} call
   * @param e the SQL exception that aborted the transaction (already rolled back)
   * @return the exception {@link #inTx} should throw to the caller
   */
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    return dbError(what, e);
  }

  // ── One-shot helpers ──────────────────────────────────────────────────────

  /**
   * Execute a single DML statement outside any caller-managed transaction (opens and commits its
   * own connection). Converts unique-constraint violations to a generic 409.
   *
   * @param sql the DML statement to execute
   * @param binder binds the statement's parameters
   * @param what a short present-tense description used in error messages
   * @throws ApiException 409 {@code DUPLICATE} on a unique-constraint violation ({@link
   *     #UNIQUE_VIOLATION}); 500 {@code DB_ERROR} on any other {@link SQLException}
   */
  protected void exec(String sql, Binder binder, String what) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      ps.executeUpdate();
    } catch (SQLException e) {
      if (UNIQUE_VIOLATION.equals(e.getSQLState()))
        throw new ApiException(409, "DUPLICATE", "Already exists", List.of(), e);
      throw dbError(what, e);
    }
  }

  /**
   * Execute a SELECT and map each row, outside any caller-managed transaction.
   *
   * @param sql the query to execute
   * @param binder binds the query's parameters
   * @param mapper maps each result row to a {@code T}
   * @param what a short present-tense description used in error messages
   * @return the mapped rows, in result-set order; empty (never {@code null}) when nothing matches
   * @throws ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  protected <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper, String what) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> out = new ArrayList<>();
        while (rs.next()) out.add(mapper.map(rs));
        return out;
      }
    } catch (SQLException e) {
      throw dbError(what, e);
    }
  }

  // ── Idempotency ───────────────────────────────────────────────────────────

  /**
   * Insert an event-id + consumer pair into {@code processed_events}. Returns {@code true} if the
   * row was inserted (first time seen); {@code false} if it was already present (duplicate). Common
   * to every service that consumes Kafka events — defined once here so it doesn't need to be copied
   * into each repo.
   *
   * @param eventId the event's {@link com.storeql.events.DomainEvent#eventId()}
   * @param consumer a name identifying this consumer (so the same event can be independently
   *     processed by different consumers)
   * @return {@code true} if this is the first time this (eventId, consumer) pair was seen; {@code
   *     false} if it was already marked processed
   * @throws ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean markProcessedIfNew(UUID eventId, String consumer) {
    try (Connection c = dataSource.getConnection()) {
      return markProcessedIfNewTx(c, eventId, consumer);
    } catch (SQLException e) {
      throw dbError("mark processed event", e);
    }
  }

  /**
   * Transaction-scoped variant of {@link #markProcessedIfNew}: runs on the caller's connection so
   * the dedupe mark commits (or rolls back) atomically WITH the business write. Marking in a
   * separate transaction first would permanently swallow the event if the write then failed.
   *
   * @param c the caller's open transaction connection (typically from within {@link #inTx})
   * @param eventId the event's {@link com.storeql.events.DomainEvent#eventId()}
   * @param consumer a name identifying this consumer
   * @return {@code true} if this is the first time this (eventId, consumer) pair was seen
   * @throws SQLException on any JDBC failure; propagates to the caller's transaction, which will be
   *     rolled back
   */
  protected static boolean markProcessedIfNewTx(Connection c, UUID eventId, String consumer)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO processed_events (event_id, consumer) VALUES (?,?)"
                + " ON CONFLICT (event_id, consumer) DO NOTHING")) {
      ps.setObject(1, eventId);
      ps.setString(2, consumer);
      return ps.executeUpdate() > 0;
    }
  }

  // ── Error ─────────────────────────────────────────────────────────────────

  /**
   * @param what a short present-tense description of the failed operation, e.g. {@code "create
   *     store"}
   * @param cause the underlying JDBC failure
   * @return a {@code 500 DB_ERROR} {@link ApiException} wrapping {@code cause}, with a message that
   *     never leaks {@code cause}'s SQL/driver detail to the client (golden rule: never leak
   *     stack/SQL) — the full detail is only visible server-side via {@code cause}'s stack trace
   */
  protected static ApiException dbError(String what, Throwable cause) {
    return new ApiException(500, "DB_ERROR", "Failed to " + what, List.of(), cause);
  }
}
