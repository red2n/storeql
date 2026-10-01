package com.storeql.iam.repo;

import com.storeql.iam.domain.PosSession;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Gap #45 — POS session persistence. */
@ApplicationScoped
public class PosSessionRepository extends BaseJdbcRepository {

  /**
   * Opens a POS session and returns it as stored.
   *
   * @param s the session to persist; its {@code id} must already be a {@code Ids.newId()} UUIDv7
   * @return the row read back after insert, with the server-side timestamps applied
   */
  public PosSession insert(PosSession s) {
    exec(
        "INSERT INTO pos_sessions (id,tenant_id,user_id,store_id,idle_timeout_seconds,status)"
            + " VALUES (?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, s.id());
          ps.setObject(2, s.tenantId());
          ps.setObject(3, s.userId());
          ps.setObject(4, s.storeId());
          ps.setInt(5, s.idleTimeoutSeconds());
          ps.setString(6, s.status());
        },
        "insert pos session");
    return find(s.id()).orElseThrow();
  }

  /**
   * Resets a session's idle clock.
   *
   * <p>Matches on {@code status = 'ACTIVE'}, so touching an ended or expired session is a silent
   * no-op and cannot resurrect it.
   *
   * @param id the session to keep alive
   */
  public void touch(UUID id) {
    exec(
        "UPDATE pos_sessions SET last_activity_at = now() WHERE id = ? AND status = 'ACTIVE'",
        ps -> ps.setObject(1, id),
        "touch pos session");
  }

  /**
   * Closes a session.
   *
   * <p>Matches on {@code status = 'ACTIVE'}, so ending an already-closed session is a silent no-op
   * and leaves the original {@code ended_at} intact.
   *
   * @param id the session to close
   */
  public void end(UUID id) {
    exec(
        "UPDATE pos_sessions SET status = 'ENDED', ended_at = now() WHERE id = ? AND status = 'ACTIVE'",
        ps -> ps.setObject(1, id),
        "end pos session");
  }

  /**
   * Closes a session on a supervisor's word, recording who and why, and revokes the cashier's
   * refresh tokens on the same transaction.
   *
   * @param id the session to close
   * @param endedBy the supervisor
   * @param reason why
   * @return true when this call closed it, false when it was already closed
   */
  public boolean endBySupervisor(UUID id, UUID endedBy, String reason) {
    return inTx(
        c -> {
          UUID owner = null;
          try (var ps =
              c.prepareStatement(
                  "UPDATE pos_sessions SET status = 'ENDED', ended_at = now(), ended_by = ?,"
                      + " end_reason = ? WHERE id = ? AND status = 'ACTIVE' RETURNING user_id")) {
            ps.setObject(1, endedBy);
            ps.setString(2, reason);
            ps.setObject(3, id);
            try (var rs = ps.executeQuery()) {
              if (rs.next()) owner = rs.getObject("user_id", UUID.class);
            }
          }
          if (owner == null) return false;
          try (var rev =
              c.prepareStatement(
                  "UPDATE refresh_tokens SET revoked = true WHERE user_id = ? AND revoked = false")) {
            rev.setObject(1, owner);
            rev.executeUpdate();
          }
          return true;
        },
        "end pos session by supervisor");
  }

  /**
   * End all active POS sessions for a tenant and revoke the refresh tokens of the affected cashiers
   * in the same transaction (called when the tenant is suspended/blocked).
   */
  public int endAllForTenant(UUID tenantId) {
    return inTx(
        c -> {
          int count = 0;
          try (var ps =
              c.prepareStatement(
                  "UPDATE pos_sessions SET status = 'ENDED', ended_at = now()"
                      + " WHERE tenant_id = ? AND status = 'ACTIVE'"
                      + " RETURNING user_id")) {
            ps.setObject(1, tenantId);
            try (var rs = ps.executeQuery()) {
              while (rs.next()) {
                count++;
                UUID userId = rs.getObject("user_id", UUID.class);
                try (var rev =
                    c.prepareStatement(
                        "UPDATE refresh_tokens SET revoked = true"
                            + " WHERE user_id = ? AND revoked = false")) {
                  rev.setObject(1, userId);
                  rev.executeUpdate();
                }
              }
            }
          }
          return count;
        },
        "end all pos sessions for tenant");
  }

  /**
   * End all active POS sessions for a specific store and revoke the refresh tokens of the affected
   * cashiers in the same transaction (called when a store is closed or suspended). Token revocation
   * is scoped to affected users only — other stores' sessions are not disturbed.
   */
  public int endAllForStore(UUID storeId) {
    return inTx(
        c -> {
          int count = 0;
          try (var ps =
              c.prepareStatement(
                  "UPDATE pos_sessions SET status = 'ENDED', ended_at = now()"
                      + " WHERE store_id = ? AND status = 'ACTIVE'"
                      + " RETURNING user_id")) {
            ps.setObject(1, storeId);
            try (var rs = ps.executeQuery()) {
              while (rs.next()) {
                count++;
                UUID userId = rs.getObject("user_id", UUID.class);
                try (var rev =
                    c.prepareStatement(
                        "UPDATE refresh_tokens SET revoked = true"
                            + " WHERE user_id = ? AND revoked = false")) {
                  rev.setObject(1, userId);
                  rev.executeUpdate();
                }
              }
            }
          }
          return count;
        },
        "end all pos sessions for store");
  }

  /**
   * Looks a POS session up by id.
   *
   * <p>Not tenant-scoped: callers must compare {@link PosSession#tenantId()} against the caller's
   * tenant before acting on the result, as {@code PosSessionService} does.
   *
   * @param id the session to fetch
   * @return the session, or empty when no such session exists
   */
  public Optional<PosSession> find(UUID id) {
    return query(
            "SELECT id, tenant_id, user_id, store_id, started_at, last_activity_at,"
                + " ended_at, idle_timeout_seconds, status"
                + " FROM pos_sessions WHERE id = ?",
            ps -> ps.setObject(1, id),
            this::map,
            "find pos session")
        .stream()
        .findFirst();
  }

  /**
   * Lists a tenant's open POS sessions across every store, most recently started first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the active sessions
   */
  public List<PosSession> listActive(UUID tenantId) {
    return listActive(tenantId, null);
  }

  /**
   * Lists open sessions, optionally only at some stores.
   *
   * @param tenantId owning tenant; the first condition
   * @param stores the stores to read, or null for every store of the business
   * @return the active sessions, newest first
   */
  public List<PosSession> listActive(UUID tenantId, java.util.Set<UUID> stores) {
    if (stores != null) {
      return query(
          "SELECT id, tenant_id, user_id, store_id, started_at, last_activity_at,"
              + " ended_at, idle_timeout_seconds, status"
              + " FROM pos_sessions WHERE tenant_id = ? AND status = 'ACTIVE'"
              + " AND store_id = ANY(?) ORDER BY started_at DESC",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          },
          this::map,
          "list active pos sessions at stores");
    }
    return query(
        "SELECT id, tenant_id, user_id, store_id, started_at, last_activity_at,"
            + " ended_at, idle_timeout_seconds, status"
            + " FROM pos_sessions WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY started_at DESC",
        ps -> ps.setObject(1, tenantId),
        this::map,
        "list active pos sessions");
  }

  /**
   * The caller's own open sessions.
   *
   * @param tenantId owning tenant; the first condition
   * @param userId the cashier
   * @return their active sessions, newest first
   */
  public List<PosSession> listActiveOf(UUID tenantId, UUID userId) {
    return query(
        "SELECT id, tenant_id, user_id, store_id, started_at, last_activity_at,"
            + " ended_at, idle_timeout_seconds, status"
            + " FROM pos_sessions WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'"
            + " ORDER BY started_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, userId);
        },
        this::map,
        "list own active pos sessions");
  }

  /**
   * Expires every session idle past its own timeout and revokes the affected cashiers' refresh
   * tokens in the same transaction.
   *
   * <p>Runs across all tenants — the scheduled sweeper calls it with no tenant in context, and each
   * session is judged against the {@code idle_timeout_seconds} it was opened with.
   *
   * @return how many sessions were expired
   */
  public int expireIdle() {
    return inTx(
        c -> {
          int count = 0;
          try (var ps =
              c.prepareStatement(
                  "UPDATE pos_sessions"
                      + " SET status = 'EXPIRED', ended_at = now()"
                      + " WHERE status = 'ACTIVE'"
                      + "   AND last_activity_at + (idle_timeout_seconds || ' seconds')::interval < now()"
                      + " RETURNING user_id")) {
            try (var rs = ps.executeQuery()) {
              while (rs.next()) {
                count++;
                UUID userId = rs.getObject("user_id", UUID.class);
                try (var rev =
                    c.prepareStatement(
                        "UPDATE refresh_tokens SET revoked = true WHERE user_id = ? AND revoked = false")) {
                  rev.setObject(1, userId);
                  rev.executeUpdate();
                }
              }
            }
          }
          return count;
        },
        "expire idle pos sessions");
  }

  private PosSession map(ResultSet rs) throws SQLException {
    OffsetDateTime endedOdt = rs.getObject("ended_at", OffsetDateTime.class);
    return new PosSession(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
        rs.getObject("last_activity_at", OffsetDateTime.class).toInstant(),
        endedOdt != null ? endedOdt.toInstant() : null,
        rs.getInt("idle_timeout_seconds"),
        rs.getString("status"));
  }
}
