package com.storeql.payment.repo;

import com.storeql.payment.domain.Domain.CashDrop;
import com.storeql.payment.domain.Domain.TillSession;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC access to till sessions, cash drops, and the tender/refund sums the X/Z reports total. */
@ApplicationScoped
public class CashManagementRepository extends BaseOutboxRepository {

  /**
   * Opens a till session and returns it as stored.
   *
   * @param session the session to persist; its {@code id} must already be a UUIDv7
   * @return the row read back after insert
   */
  public TillSession openTill(TillSession session) {
    exec(
        "INSERT INTO till_sessions"
            + " (id, tenant_id, store_id, opened_by, float_amount, status, opened_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, session.id());
          ps.setObject(2, session.tenantId());
          ps.setObject(3, session.storeId());
          ps.setObject(4, session.openedBy());
          ps.setBigDecimal(5, session.floatAmount());
          ps.setString(6, TillSession.STATUS_OPEN);
          ps.setObject(
              7, java.time.OffsetDateTime.ofInstant(session.openedAt(), java.time.ZoneOffset.UTC));
        },
        "open till session");
    return session;
  }

  /**
   * Looks a till session up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param sessionId the session to fetch
   * @return the session, or empty when no such session exists in this tenant
   */
  public Optional<TillSession> findSession(UUID tenantId, UUID sessionId) {
    return query(
            "SELECT id, tenant_id, store_id, opened_by, float_amount, status,"
                + " counted_cash, over_short, opened_at, closed_at"
                + " FROM till_sessions WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, sessionId);
            },
            CashManagementRepository::mapSession,
            "find till session")
        .stream()
        .findFirst();
  }

  /**
   * The open session one person opened at one store — the latest, should there be more than one.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store the till is at
   * @param openedBy the person who opened it
   * @return the open session, or empty when that person has none open there in this tenant
   */
  public Optional<TillSession> findOpenSession(UUID tenantId, UUID storeId, UUID openedBy) {
    return query(
            "SELECT id, tenant_id, store_id, opened_by, float_amount, status,"
                + " counted_cash, over_short, opened_at, closed_at"
                + " FROM till_sessions"
                + " WHERE tenant_id = ? AND store_id = ? AND status = ? AND opened_by = ?"
                + " ORDER BY opened_at DESC, id DESC LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setString(3, TillSession.STATUS_OPEN);
              ps.setObject(4, openedBy);
            },
            CashManagementRepository::mapSession,
            "find open till session")
        .stream()
        .findFirst();
  }

  /**
   * Records a mid-shift cash drop against a session.
   *
   * @param drop the drop to persist; its {@code id} must already be a UUIDv7
   * @return the row read back after insert
   */
  public CashDrop recordDrop(CashDrop drop) {
    exec(
        "INSERT INTO cash_drops (id, tenant_id, till_session_id, amount, recorded_by, notes, created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, drop.id());
          ps.setObject(2, drop.tenantId());
          ps.setObject(3, drop.tillSessionId());
          ps.setBigDecimal(4, drop.amount());
          ps.setObject(5, drop.recordedBy());
          ps.setString(6, drop.notes());
          ps.setObject(
              7, java.time.OffsetDateTime.ofInstant(drop.createdAt(), java.time.ZoneOffset.UTC));
        },
        "record cash drop");
    return drop;
  }

  /**
   * Total cash dropped to the safe during one session, which the expected-drawer figure subtracts.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param tillSessionId the session to total
   * @return the summed amount, zero when nothing was dropped
   */
  public BigDecimal sumCashDrops(UUID tenantId, UUID tillSessionId) {
    return query(
            "SELECT COALESCE(SUM(amount), 0) AS total FROM cash_drops"
                + " WHERE tenant_id = ? AND till_session_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, tillSessionId);
            },
            rs -> rs.getBigDecimal("total"),
            "sum cash drops")
        .stream()
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  /** Sum of CAPTURED tenders grouped by method, for a set of order_ids in this session's store. */
  public List<Object[]> sumTendersByMethod(
      UUID tenantId, UUID storeId, java.time.Instant from, java.time.Instant to) {
    return query(
        "SELECT method, COALESCE(SUM(amount),0) AS total"
            + " FROM payment_tenders"
            + " WHERE tenant_id = ? AND status = 'CAPTURED'"
            + " AND created_at >= ? AND created_at < ?"
            + " GROUP BY method",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC));
          ps.setObject(3, java.time.OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
        },
        rs -> new Object[] {rs.getString("method"), rs.getBigDecimal("total")},
        "sum tenders by method");
  }

  /** Sum of refunds grouped by method, matching same time window. */
  public List<Object[]> sumRefundsByMethod(
      UUID tenantId, java.time.Instant from, java.time.Instant to) {
    return query(
        "SELECT method, COALESCE(SUM(amount),0) AS total"
            + " FROM refund_tenders"
            + " WHERE tenant_id = ? AND created_at >= ? AND created_at < ?"
            + " GROUP BY method",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC));
          ps.setObject(3, java.time.OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
        },
        rs -> new Object[] {rs.getString("method"), rs.getBigDecimal("total")},
        "sum refunds by method");
  }

  /**
   * Closes a till session, recording what was counted and how far it differed from expectation.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param sessionId the session to close
   * @param countedCash the cash actually counted in the drawer
   * @param overShort counted less expected — positive over, negative short
   * @return the closed session as stored
   */
  public TillSession closeTill(
      UUID tenantId, UUID sessionId, BigDecimal countedCash, BigDecimal overShort) {
    return inTx(
        c -> {
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE till_sessions SET status = 'CLOSED', counted_cash = ?, over_short = ?,"
                      + " closed_at = now() WHERE tenant_id = ? AND id = ? AND status = 'OPEN'")) {
            ps.setBigDecimal(1, countedCash);
            ps.setBigDecimal(2, overShort);
            ps.setObject(3, tenantId);
            ps.setObject(4, sessionId);
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            throw ApiException.conflict("TILL_ALREADY_CLOSED", "Till session is already closed");
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id, store_id, opened_by, float_amount, status,"
                      + " counted_cash, over_short, opened_at, closed_at"
                      + " FROM till_sessions WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next()) return mapSession(rs);
              throw ApiException.conflict("TILL_ALREADY_CLOSED", "Till session is already closed");
            }
          }
        },
        "close till session");
  }

  private static TillSession mapSession(ResultSet rs) throws SQLException {
    var closedAt = rs.getObject("closed_at", OffsetDateTime.class);
    return new TillSession(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("opened_by", UUID.class),
        rs.getBigDecimal("float_amount"),
        rs.getString("status"),
        rs.getBigDecimal("counted_cash"),
        rs.getBigDecimal("over_short"),
        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
        closedAt == null ? null : closedAt.toInstant());
  }
}
