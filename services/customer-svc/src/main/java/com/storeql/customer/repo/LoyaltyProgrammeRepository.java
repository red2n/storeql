package com.storeql.customer.repo;

import com.storeql.customer.domain.Domain.Expired;
import com.storeql.customer.domain.Domain.ExpiringSoon;
import com.storeql.customer.domain.Domain.ExpiryRun;
import com.storeql.customer.domain.Domain.LoyaltyAccount;
import com.storeql.customer.domain.Domain.LoyaltyLedgerEntry;
import com.storeql.customer.domain.Domain.TierChange;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.customer.domain.LoyaltyProgramme.Tier;
import com.storeql.customer.domain.PointLots.PointLot;
import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The loyalty programme a business set (13.x) and the sweeps that keep every account true to it:
 * points dying on their day, tiers falling when the earning that reached them rolls out of the
 * qualifying window. Tenant first in every query.
 */
@ApplicationScoped
public class LoyaltyProgrammeRepository extends BaseOutboxRepository {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

  /** The business's programme, or the platform's default when it never set one. */
  public LoyaltyProgramme programme(UUID tenantId) {
    return inTx(c -> programme(c, tenantId), "read loyalty programme");
  }

  static LoyaltyProgramme programme(Connection c, UUID tenantId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT expiry_months, qualifying_months, reason, set_by, set_at"
                + " FROM loyalty_programmes WHERE tenant_id = ?")) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return LoyaltyProgramme.defaults(tenantId);
        }
        Integer expiry = rs.getObject("expiry_months", Integer.class);
        Integer qualifying = rs.getObject("qualifying_months", Integer.class);
        String reason = rs.getString("reason");
        UUID setBy = rs.getObject("set_by", UUID.class);
        Instant setAt = rs.getObject("set_at", OffsetDateTime.class).toInstant();
        return new LoyaltyProgramme(
            tenantId, expiry, qualifying, tiers(c, tenantId), reason, setBy, setAt);
      }
    }
  }

  private static List<Tier> tiers(Connection c, UUID tenantId) throws SQLException {
    List<Tier> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT name, threshold, multiplier FROM loyalty_tiers WHERE tenant_id = ?"
                + " ORDER BY rank")) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new Tier(
                  rs.getString("name"),
                  rs.getBigDecimal("threshold"),
                  rs.getBigDecimal("multiplier")));
        }
      }
    }
    return out;
  }

  /**
   * Saves the programme and brings every account and lot into line with it: open lots get the
   * rule's expiry (never sooner than the notice period), or none when the rule is lifted; every
   * account is re-tiered quietly — a business changing its ladder is not a customer's achievement.
   */
  public void save(LoyaltyProgramme p, Instant now) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO loyalty_programmes (tenant_id, expiry_months, qualifying_months,"
                      + " reason, set_by, set_at) VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id) DO UPDATE SET expiry_months = EXCLUDED.expiry_months,"
                      + " qualifying_months = EXCLUDED.qualifying_months, reason = EXCLUDED.reason,"
                      + " set_by = EXCLUDED.set_by, set_at = EXCLUDED.set_at")) {
            ps.setObject(1, p.tenantId());
            ps.setObject(2, p.expiryMonths());
            ps.setObject(3, p.qualifyingMonths());
            ps.setString(4, p.reason());
            ps.setObject(5, p.setBy());
            ps.setObject(6, LoyaltyLots.odt(now));
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM loyalty_tiers WHERE tenant_id = ?")) {
            ps.setObject(1, p.tenantId());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO loyalty_tiers (id, tenant_id, rank, name, threshold, multiplier)"
                      + " VALUES (?,?,?,?,?,?)")) {
            int rank = 0;
            for (Tier t : p.tiers()) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, p.tenantId());
              ps.setInt(3, rank++);
              ps.setString(4, t.name());
              ps.setBigDecimal(5, t.threshold());
              ps.setBigDecimal(6, t.multiplier());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          reexpire(c, p, now);
          retier(c, p, now, null);
          return null;
        },
        "save loyalty programme");
  }

  /**
   * Every open lot of the tenant takes the rule's expiry, with notice; or none when there is no
   * rule.
   */
  private void reexpire(Connection c, LoyaltyProgramme p, Instant now) throws SQLException {
    if (!p.expires()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE loyalty_point_lots SET expires_at = NULL"
                  + " WHERE tenant_id = ? AND remaining > 0")) {
        ps.setObject(1, p.tenantId());
        ps.executeUpdate();
      }
      return;
    }
    Instant notice = now.plusSeconds(LoyaltyProgramme.NOTICE_DAYS * 86_400L);
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE loyalty_point_lots SET expires_at ="
                + " GREATEST(earned_at + make_interval(months => ?), ?)"
                + " WHERE tenant_id = ? AND remaining > 0")) {
      ps.setInt(1, p.expiryMonths());
      ps.setObject(2, LoyaltyLots.odt(notice));
      ps.setObject(3, p.tenantId());
      ps.executeUpdate();
    }
  }

  /**
   * Re-tiers every account of the tenant from what qualifies now. With {@code eventFor}, a tier
   * that changes is announced; without, it changes quietly.
   *
   * @return how many accounts changed tier
   */
  private int retier(
      Connection c, LoyaltyProgramme p, Instant now, Function<TierChange, OutboxRow> eventFor)
      throws SQLException {
    List<LoyaltyAccount> accounts = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier, created_at,"
                + " updated_at, qualifying_points, tier_since"
                + " FROM loyalty_accounts WHERE tenant_id = ? FOR UPDATE")) {
      ps.setObject(1, p.tenantId());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) accounts.add(CustomerRepository.mapLoyaltyAccount(rs));
      }
    }
    int changed = 0;
    for (LoyaltyAccount a : accounts) {
      BigDecimal qualifying = LoyaltyLots.qualifyingPoints(c, a, p, now);
      String tier = p.tierFor(qualifying).name();
      boolean moved = !tier.equals(a.tier());
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE loyalty_accounts SET qualifying_points = ?, tier = ?,"
                  + " tier_since = CASE WHEN tier = ? THEN COALESCE(tier_since, ?) ELSE ? END"
                  + " WHERE tenant_id = ? AND customer_id = ?")) {
        ps.setBigDecimal(1, qualifying);
        ps.setString(2, tier);
        ps.setString(3, tier);
        ps.setObject(4, LoyaltyLots.odt(now));
        ps.setObject(5, LoyaltyLots.odt(now));
        ps.setObject(6, p.tenantId());
        ps.setObject(7, a.customerId());
        ps.executeUpdate();
      }
      if (moved) {
        changed++;
        if (eventFor != null) {
          insertOutboxRow(
              c,
              eventFor.apply(
                  new TierChange(p.tenantId(), a.customerId(), a.tier(), tier, qualifying)));
        }
      }
    }
    return changed;
  }

  /** Businesses whose points can die, or whose tiers can fall: the ones a sweep has work for. */
  public List<UUID> tenantsWithRules() {
    return query(
        "SELECT tenant_id FROM loyalty_programmes"
            + " WHERE expiry_months IS NOT NULL OR qualifying_months IS NOT NULL ORDER BY tenant_id",
        ps -> {},
        rs -> rs.getObject("tenant_id", UUID.class),
        "tenants with loyalty rules");
  }

  /**
   * One tenant's sweep: every lot that has died is written off in one EXPIRE entry per customer and
   * announced, then every account is re-tiered from what qualifies today, each fall or rise
   * announced.
   */
  public ExpiryRun sweep(
      UUID tenantId,
      Instant now,
      Function<Expired, OutboxRow> expiredEvent,
      Function<TierChange, OutboxRow> tierEvent) {
    return inTx(
        c -> {
          LoyaltyProgramme p = programme(c, tenantId);
          List<UUID> customers = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT customer_id FROM loyalty_point_lots"
                      + " WHERE tenant_id = ? AND remaining > 0 AND expires_at IS NOT NULL"
                      + " AND expires_at <= ? GROUP BY customer_id ORDER BY customer_id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, LoyaltyLots.odt(now));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) customers.add(rs.getObject("customer_id", UUID.class));
            }
          }
          BigDecimal total = BigDecimal.ZERO;
          int expiredCustomers = 0;
          for (UUID customerId : customers) {
            Optional<LoyaltyAccount> locked = lockAccount(c, tenantId, customerId);
            if (locked.isEmpty()) continue;
            LoyaltyAccount account = locked.get();
            List<PointLot> due = LoyaltyLots.dueLots(c, tenantId, customerId, now);
            BigDecimal points =
                due.stream().map(PointLot::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (points.signum() <= 0) continue;
            BigDecimal newBalance = account.pointsBalance().subtract(points).max(BigDecimal.ZERO);
            UUID entryId = Ids.newId();
            Instant earliest =
                due.stream().map(PointLot::earnedAt).min(Instant::compareTo).orElse(now);
            String reason =
                points.stripTrailingZeros().toPlainString()
                    + " points earned from "
                    + DAY.format(earliest.atOffset(java.time.ZoneOffset.UTC))
                    + " expired under the "
                    + p.expiryMonths()
                    + "-month rule";
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO loyalty_ledger (id, tenant_id, customer_id, type, points,"
                        + " balance_after, order_id, reason, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
              ps.setObject(1, entryId);
              ps.setObject(2, tenantId);
              ps.setObject(3, customerId);
              ps.setString(4, LoyaltyLedgerEntry.TYPE_EXPIRE);
              ps.setBigDecimal(5, points.negate());
              ps.setBigDecimal(6, newBalance);
              ps.setObject(7, null);
              ps.setString(8, reason);
              ps.setObject(9, LoyaltyLots.odt(now));
              ps.executeUpdate();
            }
            LoyaltyLots.close(c, tenantId, due, entryId);
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE loyalty_accounts SET points_balance = ?, updated_at = ?"
                        + " WHERE tenant_id = ? AND customer_id = ?")) {
              ps.setBigDecimal(1, newBalance);
              ps.setObject(2, LoyaltyLots.odt(now));
              ps.setObject(3, tenantId);
              ps.setObject(4, customerId);
              ps.executeUpdate();
            }
            insertOutboxRow(c, expiredEvent.apply(new Expired(tenantId, customerId, points, now)));
            total = total.add(points);
            expiredCustomers++;
          }
          int retiered = p.qualifyingMonths() == null ? 0 : retier(c, p, now, tierEvent);
          return new ExpiryRun(expiredCustomers, total, retiered);
        },
        "sweep loyalty expiry");
  }

  /** The points that die within the window, and the first day any do. */
  public Optional<ExpiringSoon> expiringSoon(UUID tenantId, UUID customerId, Instant within) {
    List<ExpiringSoon> rows =
        query(
            "SELECT MIN(expires_at) AS on_day, COALESCE(SUM(remaining), 0) AS points"
                + " FROM loyalty_point_lots WHERE tenant_id = ? AND customer_id = ?"
                + " AND remaining > 0 AND expires_at IS NOT NULL AND expires_at <= ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
              ps.setObject(3, LoyaltyLots.odt(within));
            },
            rs -> {
              OffsetDateTime on = rs.getObject("on_day", OffsetDateTime.class);
              return on == null
                  ? null
                  : new ExpiringSoon(rs.getBigDecimal("points"), on.toInstant());
            },
            "expiring loyalty points");
    return rows.stream().filter(r -> r != null).findFirst();
  }

  private static Optional<LoyaltyAccount> lockAccount(Connection c, UUID tenantId, UUID customerId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier, created_at,"
                + " updated_at, qualifying_points, tier_since"
                + " FROM loyalty_accounts WHERE tenant_id = ? AND customer_id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(CustomerRepository.mapLoyaltyAccount(rs)) : Optional.empty();
      }
    }
  }

  private void insertOutboxRow(Connection c, OutboxRow row) throws SQLException {
    insertOutbox(c, row);
  }
}
