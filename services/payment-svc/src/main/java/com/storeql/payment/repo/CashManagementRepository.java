package com.storeql.payment.repo;

import com.storeql.payment.domain.Domain.CashDrop;
import com.storeql.payment.domain.Domain.TillSession;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
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
            + " (id, tenant_id, store_id, opened_by, float_amount, status, opened_at, money_basis)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, session.id());
          ps.setObject(2, session.tenantId());
          ps.setObject(3, session.storeId());
          ps.setObject(4, session.openedBy());
          ps.setBigDecimal(5, session.floatAmount());
          ps.setString(6, TillSession.STATUS_OPEN);
          ps.setObject(
              7, java.time.OffsetDateTime.ofInstant(session.openedAt(), java.time.ZoneOffset.UTC));
          ps.setString(8, session.moneyBasis());
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
                + " counted_cash, over_short, opened_at, closed_at, money_basis"
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
                + " counted_cash, over_short, opened_at, closed_at, money_basis"
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
   * Records a mid-shift cash drop against a session, judged on this transaction: the session's row
   * is held shared while the drop is written, so a close waits for it (and counts it) or finds the
   * session closed and refuses the drop.
   *
   * @param drop the drop to persist; its {@code id} must already be a UUIDv7
   * @return the row read back after insert
   * @throws ApiException 400 {@code TILL_CLOSED} when the session is closed
   */
  public CashDrop recordDrop(CashDrop drop) {
    return inTx(
        c -> {
          TillSessionLocks.requireOpenForCashTx(c, drop.tenantId(), drop.tillSessionId());
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO cash_drops (id, tenant_id, till_session_id, amount, recorded_by,"
                      + " notes, created_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, drop.id());
            ps.setObject(2, drop.tenantId());
            ps.setObject(3, drop.tillSessionId());
            ps.setBigDecimal(4, drop.amount());
            ps.setObject(5, drop.recordedBy());
            ps.setString(6, drop.notes());
            ps.setObject(
                7, java.time.OffsetDateTime.ofInstant(drop.createdAt(), java.time.ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return drop;
        },
        "record cash drop");
  }

  /**
   * The money a till session's report counts, read together: the tenders and refunds by method (the
   * session's own on the SESSION basis, the store's in the window on WINDOW), what the store took
   * naming no session (SESSION only, shown apart and in no drawer), and the drawer's own drops and
   * pay-ins and pay-outs.
   */
  public record Figures(
      List<Object[]> tenders,
      List<Object[]> refunds,
      List<Object[]> tendersNotAtATill,
      List<Object[]> refundsNotAtATill,
      BigDecimal drops,
      BigDecimal payIns,
      BigDecimal payOuts) {

    public Figures {
      tenders = List.copyOf(tenders);
      refunds = List.copyOf(refunds);
      tendersNotAtATill = List.copyOf(tendersNotAtATill);
      refundsNotAtATill = List.copyOf(refundsNotAtATill);
    }

    /** A drawer that has taken and given nothing. */
    public static Figures none() {
      return new Figures(
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);
    }
  }

  /**
   * The session's figures over {@code [openedAt, to)}, all read on one connection from one
   * snapshot, so the sums of an X report cannot disagree with one another because a sale landed
   * between two of them.
   */
  public Figures figures(TillSession session, java.time.Instant to) {
    return inTx(
        c -> {
          try (java.sql.Statement st = c.createStatement()) {
            st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          }
          return figuresTx(c, session, to);
        },
        "read till figures");
  }

  private static Figures figuresTx(Connection c, TillSession session, java.time.Instant to)
      throws SQLException {
    UUID tenantId = session.tenantId();
    UUID storeId = session.storeId();
    java.time.Instant from = session.openedAt();
    boolean bySession = TillSession.BASIS_SESSION.equals(session.moneyBasis());
    // The drawer's money: exactly what names this session (SESSION), or everything at the store
    // while it was open (WINDOW). The first does not move because another till is open at the
    // store.
    List<Object[]> tenders =
        bySession
            ? sumsTx(
                c,
                "SELECT method, COALESCE(SUM(amount),0) AS total FROM payment_tenders"
                    + " WHERE tenant_id = ? AND till_session_id = ? AND status = 'CAPTURED'"
                    + " GROUP BY method",
                ps -> {
                  ps.setObject(1, tenantId);
                  ps.setObject(2, session.id());
                })
            : sumsTx(
                c,
                "SELECT method, COALESCE(SUM(amount),0) AS total FROM payment_tenders"
                    + " WHERE tenant_id = ? AND store_id = ? AND status = 'CAPTURED'"
                    + " AND created_at >= ? AND created_at < ? GROUP BY method",
                ps -> windowed(ps, tenantId, storeId, from, to));
    List<Object[]> refunds =
        bySession
            ? sumsTx(
                c,
                "SELECT method, COALESCE(SUM(amount),0) AS total FROM refund_tenders"
                    + " WHERE tenant_id = ? AND till_session_id = ? GROUP BY method",
                ps -> {
                  ps.setObject(1, tenantId);
                  ps.setObject(2, session.id());
                })
            : sumsTx(
                c,
                "SELECT method, COALESCE(SUM(amount),0) AS total FROM refund_tenders"
                    + " WHERE tenant_id = ? AND store_id = ? AND created_at >= ?"
                    + " AND created_at < ? GROUP BY method",
                ps -> windowed(ps, tenantId, storeId, from, to));
    List<Object[]> looseTenders = List.of();
    List<Object[]> looseRefunds = List.of();
    if (bySession) {
      // What the store took in the window naming no session (online, back-office, a client that
      // sends none): shown apart as "not at a till", never in a drawer.
      looseTenders =
          sumsTx(
              c,
              "SELECT method, COALESCE(SUM(amount),0) AS total FROM payment_tenders"
                  + " WHERE tenant_id = ? AND store_id = ? AND status = 'CAPTURED'"
                  + " AND till_session_id IS NULL AND created_at >= ? AND created_at < ?"
                  + " GROUP BY method",
              ps -> windowed(ps, tenantId, storeId, from, to));
      looseRefunds =
          sumsTx(
              c,
              "SELECT method, COALESCE(SUM(amount),0) AS total FROM refund_tenders"
                  + " WHERE tenant_id = ? AND store_id = ? AND till_session_id IS NULL"
                  + " AND created_at >= ? AND created_at < ? GROUP BY method",
              ps -> windowed(ps, tenantId, storeId, from, to));
    }
    return new Figures(
        tenders,
        refunds,
        looseTenders,
        looseRefunds,
        totalTx(
            c,
            "SELECT COALESCE(SUM(amount), 0) AS total FROM cash_drops"
                + " WHERE tenant_id = ? AND till_session_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, session.id());
            }),
        movementsTx(c, tenantId, session.id(), "PAY_IN"),
        movementsTx(c, tenantId, session.id(), "PAY_OUT"));
  }

  private static void windowed(
      PreparedStatement ps,
      UUID tenantId,
      UUID storeId,
      java.time.Instant from,
      java.time.Instant to)
      throws SQLException {
    ps.setObject(1, tenantId);
    ps.setObject(2, storeId);
    ps.setObject(3, OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC));
    ps.setObject(4, OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
  }

  private static BigDecimal movementsTx(
      Connection c, UUID tenantId, UUID tillSessionId, String direction) throws SQLException {
    return totalTx(
        c,
        "SELECT COALESCE(SUM(amount), 0) AS total FROM cash_movements"
            + " WHERE tenant_id = ? AND till_session_id = ? AND direction = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, tillSessionId);
          ps.setString(3, direction);
        });
  }

  /** Rows of {@code (method, total)} read on the caller's connection. */
  private static List<Object[]> sumsTx(Connection c, String sql, Binder binder)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        List<Object[]> out = new java.util.ArrayList<>();
        while (rs.next()) {
          out.add(new Object[] {rs.getString("method"), rs.getBigDecimal("total")});
        }
        return out;
      }
    }
  }

  /** One sum read on the caller's connection. */
  private static BigDecimal totalTx(Connection c, String sql, Binder binder) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal("total") : BigDecimal.ZERO;
      }
    }
  }

  /**
   * What the close decides from the session as the lock found it and the figures read under that
   * lock: the count, how far it differed, who closed it and their note, and the {@code
   * TillSessionClosed} that announces it.
   */
  public record Closing(
      BigDecimal countedCash,
      BigDecimal overShort,
      UUID closedBy,
      String note,
      com.storeql.service.OutboxRow event) {}

  /** The formula of the close, run on the figures the closing transaction read. */
  @FunctionalInterface
  public interface Closer {

    /**
     * @param locked the session as the close found it, still open
     * @param figures everything the report counts, read after the session was locked
     * @param closedAt the instant of the close, taken after the lock
     */
    Closing close(TillSession locked, Figures figures, java.time.Instant closedAt);
  }

  /** A closed session with the figures it was closed on. */
  public record Closed(TillSession session, Figures figures, java.time.Instant closedAt) {}

  /**
   * Closes a till session as one unit of work. The session's row is taken {@code FOR UPDATE} first,
   * which waits for every write naming the drawer that is in flight (each holds the row shared) and
   * keeps out the ones that come after; only then are the figures read, on this connection, and the
   * over/short worked from them, so the count, what is stored and what is announced are one answer.
   * The close happens once: a second attempt finds the session no longer open and writes nothing.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param sessionId the session to close
   * @param closer works the over/short and the announcement from the figures read under the lock
   * @return the closed session, the figures it closed on and the instant of the close
   * @throws ApiException 404 {@code TILL_SESSION_NOT_FOUND}; 409 {@code TILL_ALREADY_CLOSED}
   */
  public Closed closeTill(UUID tenantId, UUID sessionId, Closer closer) {
    return inTx(
        c -> {
          TillSession locked = lockForCloseTx(c, tenantId, sessionId);
          if (locked == null) {
            throw ApiException.notFound("TILL_SESSION_NOT_FOUND", "Till session not found");
          }
          if (!TillSession.STATUS_OPEN.equals(locked.status())) {
            throw ApiException.conflict("TILL_ALREADY_CLOSED", "Till session is already closed");
          }
          // Taken after the lock: every write naming the drawer that was counted committed before
          // it, so none is later than the instant the figures are read up to.
          java.time.Instant closedAt = java.time.Instant.now();
          Figures figures = figuresTx(c, locked, closedAt);
          Closing verdict = closer.close(locked, figures, closedAt);
          int rows;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE till_sessions SET status = 'CLOSED', counted_cash = ?, over_short = ?,"
                      + " closed_at = ?, closed_by = ?, note = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN'")) {
            ps.setBigDecimal(1, verdict.countedCash());
            ps.setBigDecimal(2, verdict.overShort());
            ps.setObject(3, OffsetDateTime.ofInstant(closedAt, java.time.ZoneOffset.UTC));
            ps.setObject(4, verdict.closedBy());
            ps.setString(5, verdict.note());
            ps.setObject(6, tenantId);
            ps.setObject(7, sessionId);
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            throw ApiException.conflict("TILL_ALREADY_CLOSED", "Till session is already closed");
          }
          insertOutbox(c, verdict.event());
          return new Closed(
              new TillSession(
                  locked.id(),
                  locked.tenantId(),
                  locked.storeId(),
                  locked.openedBy(),
                  locked.floatAmount(),
                  TillSession.STATUS_CLOSED,
                  verdict.countedCash(),
                  verdict.overShort(),
                  locked.openedAt(),
                  closedAt,
                  locked.moneyBasis()),
              figures,
              closedAt);
        },
        "close till session");
  }

  private static TillSession lockForCloseTx(Connection c, UUID tenantId, UUID sessionId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, opened_by, float_amount, status,"
                + " counted_cash, over_short, opened_at, closed_at, money_basis"
                + " FROM till_sessions WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapSession(rs) : null;
      }
    }
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
        closedAt == null ? null : closedAt.toInstant(),
        rs.getString("money_basis"));
  }
}
