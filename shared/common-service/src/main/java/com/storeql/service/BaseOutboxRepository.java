package com.storeql.service;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Extends {@link BaseJdbcRepository} with the three outbox operations every repo that emits domain
 * events needs: write a row inside a transaction, drain unpublished rows, and mark them published.
 * Repos without an outbox (e.g. RefreshTokenRepository) extend {@link BaseJdbcRepository} directly.
 */
public abstract class BaseOutboxRepository extends BaseJdbcRepository implements OutboxStore {

  /**
   * Insert one outbox row into the already-open connection {@code c}. No-op if {@code o} is null.
   *
   * @param c the caller's open transaction connection (typically from within {@link #inTx}) — the
   *     insert commits atomically with whatever else runs on {@code c}, satisfying golden rule #6
   * @param o the row to insert, or {@code null} to no-op (a repo method that doesn't always emit an
   *     event can pass a possibly-null row unconditionally)
   * @throws SQLException on any JDBC failure; propagates to the caller's transaction, which will be
   *     rolled back
   */
  protected void insertOutbox(Connection c, OutboxRow o) throws SQLException {
    if (o == null) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload)"
                + " VALUES (?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setString(2, o.eventType());
      ps.setString(3, o.topic());
      ps.setObject(4, o.tenantId());
      ps.setObject(5, o.aggregateId());
      ps.setString(6, o.payload());
      ps.executeUpdate();
    }
  }

  /**
   * Takes this schema's drain right on a connection of its own. A session-level lock, so it spans
   * the whole drain and not a transaction: nothing here holds row locks while Kafka is written to.
   */
  // not try-with-resources: the connection is handed to the lease on success, and closed here only
  // when the lock is not taken or the attempt fails
  @SuppressWarnings("PMD.UseTryWithResources")
  @Override
  public Optional<DrainLease> tryDrainLock() {
    Connection c;
    try {
      c = dataSource.getConnection();
    } catch (SQLException e) {
      throw dbError("take the outbox drain lock", e);
    }
    boolean held = false;
    try {
      try (PreparedStatement ps =
              c.prepareStatement("SELECT pg_try_advisory_lock(" + DRAIN_KEY + ")");
          ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          held = rs.getBoolean(1);
        }
      }
      return held ? Optional.of(new Lease(c)) : Optional.empty();
    } catch (SQLException e) {
      throw dbError("take the outbox drain lock", e);
    } finally {
      if (!held) closeQuietly(c);
    }
  }

  /** The schema-scoped key of the drain lock: one drainer per schema, whichever service it is. */
  private static final String DRAIN_KEY = "hashtext(current_schema()), hashtext('outbox')";

  /** Holds the drain connection, and gives the lock back before the connection goes to the pool. */
  private final class Lease implements DrainLease {
    private final Connection c;

    Lease(Connection c) {
      this.c = c;
    }

    // not try-with-resources: the connection must close after the lock is released, on every path
    @SuppressWarnings("PMD.UseTryWithResources")
    @Override
    public void close() {
      try (PreparedStatement ps =
          c.prepareStatement("SELECT pg_advisory_unlock(" + DRAIN_KEY + ")")) {
        ps.executeQuery().close();
      } catch (SQLException e) {
        // A connection that cannot release its lock must not go back to the pool holding it.
        LOG.log(
            Level.WARNING,
            "outbox drain lock not released, discarding its connection: {0}",
            e.getMessage());
        try {
          c.abort(Runnable::run);
          return;
        } catch (SQLException ignored) {
          // fall through to a plain close
        }
      } finally {
        closeQuietly(c);
      }
    }
  }

  private static final System.Logger LOG = System.getLogger(BaseOutboxRepository.class.getName());

  private static void closeQuietly(Connection c) {
    try {
      c.close();
    } catch (SQLException ignored) {
      // nothing more can be done with a connection that will not close
    }
  }

  /**
   * Claims, publishes, and records. Claims without a transaction, publishes with none open, and
   * then marks the outcome in one short transaction; a crash between publish and that transaction
   * re-publishes the batch on the next drain, which is at-least-once and what consumers dedupe on.
   */
  @Override
  public DrainResult drainOnce(
      DrainLease lease, int limit, Function<List<PendingOutbox>, PublishOutcome> publish) {
    List<PendingOutbox> rows = claim(limit);
    if (rows.isEmpty()) {
      return new DrainResult(0, List.of());
    }
    PublishOutcome outcome =
        Objects.requireNonNull(publish.apply(rows), "publish returned no outcome");
    List<UUID> published = outcome.published() == null ? List.of() : outcome.published();
    Map<UUID, String> failed = outcome.failed() == null ? Map.of() : outcome.failed();
    // an id the publisher was not given would update nothing and go unnoticed: refuse it instead
    Set<UUID> claimed = new HashSet<>(rows.stream().map(PendingOutbox::id).toList());
    for (UUID id : published) {
      if (!claimed.contains(id))
        throw new IllegalStateException("published a row that was not claimed: " + id);
    }
    for (UUID id : failed.keySet()) {
      if (!claimed.contains(id))
        throw new IllegalStateException("failed a row that was not claimed: " + id);
    }
    if (!published.isEmpty() || !failed.isEmpty()) {
      inTx(
          c -> {
            markPublished(c, published);
            recordFailures(c, failed);
            return null;
          },
          "record outbox outcome");
    }
    return new DrainResult(rows.size(), published);
  }

  /**
   * The rows that may publish now: not published, not dead, past their backoff, and not behind a
   * row of their aggregate that is dead or backing off. A healthy earlier row does not block: the
   * batch is sent in per-aggregate order, so a claimed row's earlier rows are claimed with it. A
   * dead or backing-off row therefore holds back its own aggregate, and no other.
   */
  private List<PendingOutbox> claim(int limit) {
    List<PendingOutbox> rows = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT o.id, o.aggregate_id, o.topic, o.payload FROM outbox o"
                    + " WHERE o.published_at IS NULL AND o.dead_at IS NULL"
                    + " AND o.next_attempt_at <= now()"
                    + " AND NOT EXISTS (SELECT 1 FROM outbox p"
                    + "   WHERE p.aggregate_id = o.aggregate_id AND p.published_at IS NULL"
                    + "   AND (p.dead_at IS NOT NULL OR p.next_attempt_at > now())"
                    + "   AND (p.created_at, p.id) < (o.created_at, o.id))"
                    + " ORDER BY o.created_at, o.id LIMIT ?")) {
      ps.setInt(1, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new PendingOutbox(
                  rs.getObject("id", UUID.class),
                  rs.getObject("aggregate_id", UUID.class),
                  rs.getString("topic"),
                  rs.getString("payload")));
        }
      }
    } catch (SQLException e) {
      throw dbError("claim outbox rows", e);
    }
    return rows;
  }

  private static void markPublished(Connection c, List<UUID> published) throws SQLException {
    if (published.isEmpty()) return;
    try (PreparedStatement ps =
        c.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ANY(?)")) {
      java.sql.Array ids = c.createArrayOf("uuid", published.toArray());
      try {
        ps.setArray(1, ids);
        ps.executeUpdate();
      } finally {
        ids.free();
      }
    }
  }

  /**
   * One more attempt for each failed row: its backoff doubles from {@code
   * storeql.outbox.backoff-base-seconds} up to {@code storeql.outbox.backoff-cap-seconds}, and at
   * {@code storeql.outbox.max-attempts} the row becomes a dead letter. A dead letter is never
   * claimed again; it stays for an operator to see.
   */
  private static void recordFailures(Connection c, Map<UUID, String> failed) throws SQLException {
    if (failed.isEmpty()) return;
    long base = Math.max(1, Cfg.getLong("storeql.outbox.backoff-base-seconds", 1L));
    long cap = Math.max(base, Cfg.getLong("storeql.outbox.backoff-cap-seconds", 900L));
    long maxAttempts = Math.max(1, Cfg.getLong("storeql.outbox.max-attempts", 10L));
    List<UUID> ids = new ArrayList<>(failed.keySet());
    List<String> reasons = new ArrayList<>(ids.size());
    for (UUID id : ids) {
      reasons.add(failed.get(id) == null ? "not acknowledged" : failed.get(id));
    }
    java.sql.Array idArray = c.createArrayOf("uuid", ids.toArray());
    java.sql.Array reasonArray = c.createArrayOf("text", reasons.toArray());
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE outbox o SET attempts = o.attempts + 1,"
                + " last_error = left(f.err, 500),"
                + " next_attempt_at = now() + make_interval(secs => LEAST(?::double precision,"
                + "   ?::double precision * power(2, o.attempts))),"
                + " dead_at = CASE WHEN o.attempts + 1 >= ? THEN now() END"
                + " FROM unnest(?::uuid[], ?::text[]) AS f(id, err)"
                + " WHERE o.id = f.id AND o.published_at IS NULL"
                + " RETURNING o.id, o.event_type, o.aggregate_id, o.tenant_id, o.attempts,"
                + " o.dead_at IS NOT NULL AS dead, o.last_error")) {
      ps.setLong(1, cap);
      ps.setLong(2, base);
      ps.setLong(3, maxAttempts);
      ps.setArray(4, idArray);
      ps.setArray(5, reasonArray);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          if (rs.getBoolean("dead")) {
            LOG.log(
                Level.WARNING,
                "outbox row {0} ({1}) is a dead letter: aggregate {2}, tenant {3}, {4} attempts, last error: {5}",
                rs.getObject("id"),
                rs.getString("event_type"),
                rs.getObject("aggregate_id"),
                rs.getObject("tenant_id"),
                rs.getInt("attempts"),
                rs.getString("last_error"));
          }
        }
      }
    } finally {
      idArray.free();
      reasonArray.free();
    }
  }

  /** Which timestamp column this schema's {@code processed_events} uses; null until detected. */
  private volatile String processedAtColumn;

  /** Set once the schema turns out to have no {@code processed_events} table at all. */
  private volatile boolean noProcessedEvents;

  @Override
  public int purgePublished(Instant cutoff, int batch) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM outbox WHERE id IN (SELECT id FROM outbox"
                      + " WHERE published_at IS NOT NULL AND published_at < ?"
                      + " ORDER BY published_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)")) {
            ps.setObject(1, cutoff.atOffset(ZoneOffset.UTC));
            ps.setInt(2, batch);
            return ps.executeUpdate();
          }
        },
        "purge published outbox");
  }

  @Override
  public int purgeProcessedEvents(Instant cutoff, int batch) {
    if (noProcessedEvents) return 0;
    String col = processedAtColumn;
    if (col != null) return deleteProcessed(col, cutoff, batch);
    // Most schemas call it processed_at, order-svc created_at: try one, fall back to the other.
    for (String candidate : new String[] {"processed_at", "created_at"}) {
      try {
        int n = deleteProcessed(candidate, cutoff, batch);
        processedAtColumn = candidate;
        return n;
      } catch (ApiException e) {
        String state = e.getCause() instanceof SQLException se ? se.getSQLState() : null;
        if ("42P01".equals(state)) {
          noProcessedEvents = true;
          return 0;
        }
        if (!"42703".equals(state)) throw e;
      }
    }
    return 0;
  }

  private int deleteProcessed(String column, Instant cutoff, int batch) {
    // column is one of two literals above, never caller input
    // The key is (event_id, consumer), so the batch is chosen by that pair.
    String sql =
        "DELETE FROM processed_events WHERE (event_id, consumer) IN"
            + " (SELECT event_id, consumer FROM processed_events WHERE "
            + column
            + " < ? ORDER BY "
            + column
            + " ASC LIMIT ? FOR UPDATE SKIP LOCKED)";
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, cutoff.atOffset(ZoneOffset.UTC));
            ps.setInt(2, batch);
            return ps.executeUpdate();
          }
        },
        "purge processed events");
  }
}
