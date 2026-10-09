package com.storeql.payment.repo;

import com.storeql.web.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * How a write that names a till session (a drawer) is judged: on the transaction that writes it,
 * with the session's row held {@code FOR SHARE}.
 *
 * <p>The close takes the same row {@code FOR UPDATE} before it reads a single figure, so every
 * write naming the drawer is either committed before the close reads (and is counted in it) or
 * waits for the close and finds the drawer closed. There is no third case: a tender cannot be
 * checked against an open drawer and committed after that drawer's count. Share locks do not
 * conflict with each other, so tills writing to one drawer never wait for one another; only the
 * close, once, waits for them.
 *
 * <p>Lock order: a writer takes this row and then whatever else it writes (a tender, an attempt);
 * the close takes this row and nothing else, so no cycle can form through it.
 */
final class TillSessionLocks {

  private TillSessionLocks() {}

  /** The status a session open for money has. */
  static final String OPEN = "OPEN";

  /**
   * The drawer a till named for money it takes or gives: this business's, and open. Held shared to
   * the end of the caller's transaction.
   *
   * @return the store the drawer is at
   * @throws ApiException 404 {@code TILL_SESSION_NOT_FOUND} when the business has no such session;
   *     409 {@code TILL_SESSION_NOT_OPEN} when it is closed
   */
  static UUID requireOpenTx(Connection c, UUID tenantId, UUID sessionId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT store_id, status FROM till_sessions WHERE tenant_id = ? AND id = ? FOR SHARE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.notFound("TILL_SESSION_NOT_FOUND", "Till session not found");
        }
        if (!OPEN.equals(rs.getString("status"))) {
          throw ApiException.conflict("TILL_SESSION_NOT_OPEN", "That till session is closed");
        }
        return rs.getObject("store_id", UUID.class);
      }
    }
  }

  /**
   * Holds a session's row shared for the rest of the caller's transaction and refuses it when it is
   * closed: the drawer's own cash (a drop, a pay-in, a pay-out) is written only to an open one.
   * Nothing is refused for a session that does not exist, as before.
   *
   * @throws ApiException 400 {@code TILL_CLOSED}
   */
  static void requireOpenForCashTx(Connection c, UUID tenantId, UUID sessionId)
      throws SQLException {
    String status = sharedStatusTx(c, tenantId, sessionId);
    if (status != null && !OPEN.equals(status)) {
      throw ApiException.badRequest("TILL_CLOSED", "Till session is already closed");
    }
  }

  /**
   * Holds a session's row shared for the rest of the caller's transaction, whatever its status, and
   * says what it is: the one write that must be made even to a drawer that has closed (a deposit
   * refund an event announces) is still ordered against the close.
   *
   * @return the status, or null when the business has no such session
   */
  static String sharedStatusTx(Connection c, UUID tenantId, UUID sessionId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT status FROM till_sessions WHERE tenant_id = ? AND id = ? FOR SHARE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("status") : null;
      }
    }
  }

  /**
   * The store of a drawer that is this business's and still open, held shared to the end of the
   * caller's transaction, or null when it is not (unknown, another business's, closed). A closing
   * drawer is closed by the time the lock is granted, so it reads as null.
   */
  static UUID openStoreTx(Connection c, UUID tenantId, UUID sessionId) throws SQLException {
    if (sessionId == null) return null;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT store_id FROM till_sessions"
                + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN' FOR SHARE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject("store_id", UUID.class) : null;
      }
    }
  }

  /**
   * The drawer to keep for money an event says a till moved at {@code storeId}: the session itself
   * when it is this business's open session at that very store, else null. Money an event announces
   * is never refused over where it is counted -- a drawer that cannot be this money's only means it
   * is counted at no drawer.
   */
  static UUID keptAtTx(Connection c, UUID tenantId, UUID sessionId, UUID storeId)
      throws SQLException {
    UUID sessionStore = openStoreTx(c, tenantId, sessionId);
    return sessionStore != null && sessionStore.equals(storeId) ? sessionId : null;
  }
}
