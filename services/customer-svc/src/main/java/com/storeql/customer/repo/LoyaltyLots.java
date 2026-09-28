package com.storeql.customer.repo;

import com.storeql.customer.domain.Domain.LoyaltyAccount;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.customer.domain.PointLots;
import com.storeql.customer.domain.PointLots.PointLot;
import com.storeql.customer.domain.PointLots.Take;
import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The SQL of point lots (13.x), on a connection the caller already holds: every earning is a lot
 * with its own expiry, points are spent from the lot that dies first, and a balance from before
 * lots existed becomes one opening lot the first time it is touched.
 */
final class LoyaltyLots {

  private LoyaltyLots() {}

  static void insertLot(
      Connection c,
      UUID tenantId,
      UUID customerId,
      UUID ledgerEntryId,
      BigDecimal points,
      Instant earnedAt,
      Instant expiresAt)
      throws SQLException {
    if (points.signum() <= 0) {
      return;
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO loyalty_point_lots (id, tenant_id, customer_id, ledger_entry_id, points,"
                + " remaining, earned_at, expires_at) VALUES (?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, customerId);
      ps.setObject(4, ledgerEntryId);
      ps.setBigDecimal(5, points);
      ps.setBigDecimal(6, points);
      ps.setObject(7, odt(earnedAt));
      ps.setObject(8, odt(expiresAt));
      ps.executeUpdate();
    }
  }

  /**
   * The account's open lots, locked, after making good any balance that predates lots: the
   * difference becomes an opening lot dated from the account's last movement, expiring under the
   * programme's rule with the notice a rule change gives.
   */
  static List<PointLot> openLots(
      Connection c, LoyaltyAccount account, LoyaltyProgramme programme, Instant now)
      throws SQLException {
    List<PointLot> lots = select(c, account.tenantId(), account.customerId(), null, true);
    BigDecimal held =
        lots.stream().map(PointLot::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal missing = account.pointsBalance().subtract(held);
    if (missing.signum() > 0) {
      Instant earned = account.updatedAt() == null ? now : account.updatedAt();
      Instant expires =
          programme.expires()
              ? LoyaltyProgramme.reexpiry(earned, programme.expiryMonths(), now)
              : null;
      insertLot(c, account.tenantId(), account.customerId(), null, missing, earned, expires);
      lots = select(c, account.tenantId(), account.customerId(), null, true);
    }
    return lots;
  }

  /** Open lots that die on or before {@code dueBy}, locked. */
  static List<PointLot> dueLots(Connection c, UUID tenantId, UUID customerId, Instant dueBy)
      throws SQLException {
    return select(c, tenantId, customerId, dueBy, true);
  }

  private static List<PointLot> select(
      Connection c, UUID tenantId, UUID customerId, Instant dueBy, boolean lock)
      throws SQLException {
    String sql =
        "SELECT id, remaining, earned_at, expires_at FROM loyalty_point_lots"
            + " WHERE tenant_id = ? AND customer_id = ? AND remaining > 0"
            + (dueBy == null ? "" : " AND expires_at IS NOT NULL AND expires_at <= ?")
            + " ORDER BY expires_at NULLS LAST, earned_at"
            + (lock ? " FOR UPDATE" : "");
    List<PointLot> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      if (dueBy != null) {
        ps.setObject(3, odt(dueBy));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
          out.add(
              new PointLot(
                  rs.getObject("id", UUID.class),
                  rs.getBigDecimal("remaining"),
                  rs.getObject("earned_at", OffsetDateTime.class).toInstant(),
                  expires == null ? null : expires.toInstant()));
        }
      }
    }
    return out;
  }

  /** Spends {@code points} from the lots in spending order. */
  static void consume(Connection c, UUID tenantId, List<PointLot> lots, BigDecimal points)
      throws SQLException {
    for (Take take : PointLots.consume(lots, points)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE loyalty_point_lots SET remaining = remaining - ?"
                  + " WHERE tenant_id = ? AND id = ?")) {
        ps.setBigDecimal(1, take.points());
        ps.setObject(2, tenantId);
        ps.setObject(3, take.lotId());
        ps.executeUpdate();
      }
    }
  }

  /** Closes the lots that died: nothing left, and the EXPIRE entry that took it. */
  static void close(Connection c, UUID tenantId, List<PointLot> lots, UUID expireEntryId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE loyalty_point_lots SET remaining = 0, expired_entry_id = ?"
                + " WHERE tenant_id = ? AND id = ?")) {
      for (PointLot lot : lots) {
        ps.setObject(1, expireEntryId);
        ps.setObject(2, tenantId);
        ps.setObject(3, lot.id());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /**
   * What counts towards the tier: everything ever earned when the programme qualifies over a
   * lifetime, otherwise the points earned or awarded within the qualifying window.
   */
  static BigDecimal qualifyingPoints(
      Connection c, LoyaltyAccount account, LoyaltyProgramme programme, Instant now)
      throws SQLException {
    if (programme.qualifyingMonths() == null) {
      return account.lifetimePoints();
    }
    Instant since =
        now.atOffset(ZoneOffset.UTC).minusMonths(programme.qualifyingMonths()).toInstant();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(points), 0) AS qualifying FROM loyalty_ledger"
                + " WHERE tenant_id = ? AND customer_id = ? AND points > 0"
                + " AND type IN ('EARN', 'ADJUST') AND created_at >= ?")) {
      ps.setObject(1, account.tenantId());
      ps.setObject(2, account.customerId());
      ps.setObject(3, odt(since));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal("qualifying") : BigDecimal.ZERO;
      }
    }
  }

  static OffsetDateTime odt(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }
}
