package com.storeql.purchase.repo;

import com.storeql.purchase.domain.DeferredRevenue;
import com.storeql.purchase.domain.DeferredRevenue.GiftCardOutcome;
import com.storeql.purchase.domain.DeferredRevenue.GiftCardPool;
import com.storeql.purchase.domain.DeferredRevenue.PointsOutcome;
import com.storeql.purchase.domain.DeferredRevenue.PointsPool;
import com.storeql.purchase.domain.Domain.DeferredRevenueSettings;
import com.storeql.purchase.domain.Domain.DeferredRevenueView;
import com.storeql.purchase.domain.Domain.GiftCardLoad;
import com.storeql.purchase.domain.Domain.LoyaltyEvent;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for deferred revenue (17.11). Every change to a tenant's points or gift cards
 * locks that tenant's pool first, reads the estimates in force after the lock, and writes the
 * journal, the pool and the record of the event in the same transaction: an event redelivered is
 * recorded once, and two events, or an event and a save of the estimates, cannot both release the
 * same income.
 */
@ApplicationScoped
public class DeferredRevenueRepository extends BaseJdbcRepository {

  private static final String SETTINGS_COLUMNS =
      "id, tenant_id, currency, point_value, points_breakage_pct, gift_card_breakage_pct, reason,"
          + " set_by, set_at";
  private static final String EVENT_COLUMNS =
      "tenant_id, event_id, kind, customer_id, order_id, points, order_total, order_tax";

  /** One pool's statements: create at zero, lock and read, read, save. */
  private record PoolSql(String create, String lock, String read, String save) {}

  private static final PoolSql POINTS =
      new PoolSql(
          "INSERT INTO loyalty_point_pools (tenant_id, points_outstanding, deferred_income,"
              + " points_unmatched) VALUES (?,0,0,0) ON CONFLICT (tenant_id) DO NOTHING",
          "SELECT points_outstanding, deferred_income, points_unmatched FROM loyalty_point_pools"
              + " WHERE tenant_id = ? FOR UPDATE",
          "SELECT points_outstanding, deferred_income, points_unmatched FROM loyalty_point_pools"
              + " WHERE tenant_id = ?",
          "UPDATE loyalty_point_pools SET points_outstanding = ?, deferred_income = ?,"
              + " points_unmatched = ?, updated_at = now() WHERE tenant_id = ?");

  private static final PoolSql GIFT_CARDS =
      new PoolSql(
          "INSERT INTO gift_card_pools (tenant_id, loaded, redeemed, breakage) VALUES (?,0,0,0)"
              + " ON CONFLICT (tenant_id) DO NOTHING",
          "SELECT loaded, redeemed, breakage FROM gift_card_pools WHERE tenant_id = ? FOR UPDATE",
          "SELECT loaded, redeemed, breakage FROM gift_card_pools WHERE tenant_id = ?",
          "UPDATE gift_card_pools SET loaded = ?, redeemed = ?, breakage = ?, updated_at = now()"
              + " WHERE tenant_id = ?");

  /** How a loyalty event is posted, given the estimates, the locked pool and the sale's store. */
  @FunctionalInterface
  public interface PointsRule {
    PointsOutcome apply(
        DeferredRevenue.Settings settings, PointsPool pool, LoyaltyEvent event, UUID storeId);
  }

  /** How a gift card spent is posted, given the estimates (null when unset) and the locked pool. */
  @FunctionalInterface
  public interface GiftCardRule {
    GiftCardOutcome apply(DeferredRevenue.Settings settings, GiftCardPool pool);
  }

  /**
   * Records a loyalty event and, when estimates are set, posts it.
   *
   * @return {@code false} when the event was already recorded
   */
  public boolean recordLoyaltyEvent(LoyaltyEvent e, PointsRule rule) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO loyalty_events ("
                      + EVENT_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, event_id) DO NOTHING")) {
            ps.setObject(1, e.tenantId());
            ps.setObject(2, e.eventId());
            ps.setString(3, e.kind());
            ps.setObject(4, e.customerId());
            ps.setObject(5, e.orderId());
            ps.setBigDecimal(6, e.points());
            ps.setBigDecimal(7, e.orderTotal());
            ps.setBigDecimal(8, e.orderTax());
            if (ps.executeUpdate() == 0) return false;
          }
          PointsPool pool = lockPointsPool(c, e.tenantId());
          Optional<DeferredRevenueSettings> settings = currentSettings(c, e.tenantId());
          if (settings.isPresent()) post(c, settings.get().estimates(), pool, e, rule);
          return true;
        },
        "record loyalty event");
  }

  /**
   * Saves new estimates and posts, oldest first, every loyalty event that was waiting for them.
   *
   * @return how many waiting events were posted
   */
  public int saveSettings(DeferredRevenueSettings s, PointsRule rule) {
    return inTx(
        c -> {
          PointsPool pool = lockPointsPool(c, s.tenantId());
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO deferred_revenue_settings (id, tenant_id, currency, point_value,"
                      + " points_breakage_pct, gift_card_breakage_pct, reason, set_by)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setString(3, s.currency());
            ps.setBigDecimal(4, s.pointValue());
            ps.setBigDecimal(5, s.pointsBreakagePct());
            ps.setBigDecimal(6, s.giftCardBreakagePct());
            ps.setString(7, s.reason());
            ps.setObject(8, s.setBy());
            ps.executeUpdate();
          }
          List<LoyaltyEvent> waiting = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + EVENT_COLUMNS
                      + " FROM loyalty_events WHERE tenant_id = ? AND posted_at IS NULL"
                      + " ORDER BY received_at, event_id")) {
            ps.setObject(1, s.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) waiting.add(event(rs));
            }
          }
          for (LoyaltyEvent e : waiting) {
            pool = post(c, s.estimates(), pool, e, rule);
          }
          return waiting.size();
        },
        "save deferred revenue estimates");
  }

  /**
   * Records a gift card load and its journal.
   *
   * @return {@code false} when the card transaction was already posted
   */
  public boolean recordGiftCardLoad(GiftCardLoad load, List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO gift_card_loads (tenant_id, transaction_id, gift_card_id, store_id,"
                      + " kind, paid_by, amount, currency, journal_id) VALUES (?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, transaction_id) DO NOTHING")) {
            ps.setObject(1, load.tenantId());
            ps.setObject(2, load.transactionId());
            ps.setObject(3, load.giftCardId());
            ps.setObject(4, load.storeId());
            ps.setString(5, load.kind());
            ps.setString(6, load.paidBy());
            ps.setBigDecimal(7, load.amount());
            ps.setString(8, load.currency());
            // A load paid for by a return's refund posts nothing: the refund owes the card.
            ps.setObject(9, posting.isEmpty() ? null : posting.get(0).journalId());
            if (ps.executeUpdate() == 0) return false;
          }
          GiftCardPool pool = lockGiftCardPool(c, load.tenantId());
          saveGiftCardPool(c, load.tenantId(), DeferredRevenue.loaded(pool, load.amount()));
          LedgerWriter.insert(c, posting);
          return true;
        },
        "record gift card load");
  }

  /**
   * Records a gift card spent as tender, and the breakage the rule recognises for it, once.
   *
   * @param dedupeId the key the spend is recorded under
   * @return {@code false} when it was already recorded
   */
  public boolean recordGiftCardSpent(
      UUID dedupeId, String consumer, UUID tenantId, GiftCardRule rule) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumer)) return false;
          GiftCardPool pool = lockGiftCardPool(c, tenantId);
          DeferredRevenue.Settings settings =
              currentSettings(c, tenantId).map(DeferredRevenueSettings::estimates).orElse(null);
          GiftCardOutcome out = rule.apply(settings, pool);
          LedgerWriter.insert(c, out.posting());
          saveGiftCardPool(c, tenantId, out.pool());
          return true;
        },
        "record gift card spent");
  }

  /** The estimates, their history, both pools and the events waiting for estimates. */
  public DeferredRevenueView view(UUID tenantId) {
    List<DeferredRevenueSettings> history =
        query(
            "SELECT "
                + SETTINGS_COLUMNS
                + " FROM deferred_revenue_settings WHERE tenant_id = ?"
                + " ORDER BY set_at DESC, id DESC LIMIT 50",
            ps -> ps.setObject(1, tenantId),
            DeferredRevenueRepository::settings,
            "list deferred revenue estimates");
    BigDecimal[] points = readPool(POINTS, tenantId);
    BigDecimal[] cards = readPool(GIFT_CARDS, tenantId);
    long waiting =
        query(
                "SELECT COUNT(*) AS waiting FROM loyalty_events"
                    + " WHERE tenant_id = ? AND posted_at IS NULL",
                ps -> ps.setObject(1, tenantId),
                rs -> rs.getLong("waiting"),
                "count loyalty events waiting")
            .get(0);
    return new DeferredRevenueView(
        history.isEmpty() ? null : history.get(0),
        history,
        new PointsPool(points[0], points[1], points[2]),
        waiting,
        new GiftCardPool(cards[0], cards[1], cards[2]));
  }

  // ── inside a transaction ────────────────────────────────────────────────────

  private static PointsPool post(
      Connection c,
      DeferredRevenue.Settings settings,
      PointsPool pool,
      LoyaltyEvent e,
      PointsRule rule)
      throws SQLException {
    PointsOutcome out = rule.apply(settings, pool, e, saleStore(c, e));
    LedgerWriter.insert(c, out.posting());
    save(
        c,
        POINTS,
        e.tenantId(),
        out.pool().outstanding(),
        out.pool().deferred(),
        out.pool().unmatched());
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE loyalty_events SET posted_at = now(), journal_id = ?"
                + " WHERE tenant_id = ? AND event_id = ?")) {
      ps.setObject(1, out.posting().isEmpty() ? null : out.posting().get(0).journalId());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.eventId());
      ps.executeUpdate();
    }
    return out.pool();
  }

  private static PointsPool lockPointsPool(Connection c, UUID tenantId) throws SQLException {
    BigDecimal[] v = lock(c, POINTS, tenantId);
    return new PointsPool(v[0], v[1], v[2]);
  }

  private static GiftCardPool lockGiftCardPool(Connection c, UUID tenantId) throws SQLException {
    BigDecimal[] v = lock(c, GIFT_CARDS, tenantId);
    return new GiftCardPool(v[0], v[1], v[2]);
  }

  private static void saveGiftCardPool(Connection c, UUID tenantId, GiftCardPool pool)
      throws SQLException {
    save(c, GIFT_CARDS, tenantId, pool.loaded(), pool.redeemed(), pool.breakage());
  }

  /** Creates the tenant's pool at zero if it has none, then locks and reads it. */
  private static BigDecimal[] lock(Connection c, PoolSql sql, UUID tenantId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql.create())) {
      ps.setObject(1, tenantId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps = c.prepareStatement(sql.lock())) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        // The insert above guarantees the row; its absence is a fault, never a zero pool.
        if (!rs.next()) throw new SQLException("no pool row for tenant " + tenantId);
        return values(rs);
      }
    }
  }

  private static void save(Connection c, PoolSql sql, UUID tenantId, BigDecimal... values)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql.save())) {
      for (int i = 0; i < values.length; i++) ps.setBigDecimal(i + 1, values[i]);
      ps.setObject(values.length + 1, tenantId);
      ps.executeUpdate();
    }
  }

  private BigDecimal[] readPool(PoolSql sql, UUID tenantId) {
    var rows =
        query(
            sql.read(),
            ps -> ps.setObject(1, tenantId),
            DeferredRevenueRepository::values,
            "read pool");
    return rows.isEmpty()
        ? new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}
        : rows.get(0);
  }

  private static BigDecimal[] values(ResultSet rs) throws SQLException {
    return new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)};
  }

  private static Optional<DeferredRevenueSettings> currentSettings(Connection c, UUID tenantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + SETTINGS_COLUMNS
                + " FROM deferred_revenue_settings WHERE tenant_id = ?"
                + " ORDER BY set_at DESC, id DESC LIMIT 1")) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(settings(rs)) : Optional.empty();
      }
    }
  }

  /** The store of the sale the points came with, when the ledger has the sale. */
  private static UUID saleStore(Connection c, LoyaltyEvent e) throws SQLException {
    if (e.orderId() == null) return null;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT store_id FROM sales_orders WHERE tenant_id = ? AND order_id = ?")) {
      ps.setObject(1, e.tenantId());
      ps.setObject(2, e.orderId());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject("store_id", UUID.class) : null;
      }
    }
  }

  private static DeferredRevenueSettings settings(ResultSet rs) throws SQLException {
    return new DeferredRevenueSettings(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("currency"),
        rs.getBigDecimal("point_value"),
        rs.getBigDecimal("points_breakage_pct"),
        rs.getBigDecimal("gift_card_breakage_pct"),
        rs.getString("reason"),
        rs.getObject("set_by", UUID.class),
        rs.getTimestamp("set_at").toInstant());
  }

  private static LoyaltyEvent event(ResultSet rs) throws SQLException {
    return new LoyaltyEvent(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("kind"),
        rs.getObject("customer_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getBigDecimal("points"),
        rs.getBigDecimal("order_total"),
        rs.getBigDecimal("order_tax"));
  }
}
