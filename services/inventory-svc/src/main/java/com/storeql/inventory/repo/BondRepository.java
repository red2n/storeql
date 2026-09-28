package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.BondApproval;
import com.storeql.inventory.domain.Domain.BondRelease;
import com.storeql.inventory.domain.Domain.BondStock;
import com.storeql.inventory.domain.Domain.ExciseDutyRate;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bonded warehouses, the duty a variant crystallises, the releases made and what sits in bond.
 * Every statement filters by {@code tenant_id} first; the store is tenant-svc's, referenced never
 * joined.
 */
@ApplicationScoped
public class BondRepository extends BaseJdbcRepository {

  // ── Approvals ──────────────────────────────────────────────────────────────

  public BondApproval approve(BondApproval a) {
    exec(
        "INSERT INTO bond_approvals (tenant_id, store_id, approval_number, regime, active,"
            + " created_by, created_at, ended_at) VALUES (?,?,?,?,TRUE,?,?,NULL)"
            + " ON CONFLICT (tenant_id, store_id) DO UPDATE SET approval_number ="
            + " EXCLUDED.approval_number, regime = EXCLUDED.regime, active = TRUE,"
            + " created_by = EXCLUDED.created_by, created_at = EXCLUDED.created_at, ended_at = NULL",
        ps -> {
          ps.setObject(1, a.tenantId());
          ps.setObject(2, a.storeId());
          ps.setString(3, a.approvalNumber());
          ps.setString(4, a.regime());
          ps.setObject(5, a.createdBy());
          ps.setObject(6, a.createdAt().atOffset(ZoneOffset.UTC));
        },
        "approve bonded warehouse");
    return a;
  }

  /** Ends an approval; returns false when the store had none live. */
  public boolean end(UUID tenantId, UUID storeId) {
    int[] rows = new int[1];
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE bond_approvals SET active = FALSE, ended_at = now()"
                      + " WHERE tenant_id = ? AND store_id = ? AND active")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            rows[0] = ps.executeUpdate();
          }
          return null;
        },
        "end bond approval");
    return rows[0] > 0;
  }

  public boolean isBonded(UUID tenantId, UUID storeId) {
    return !query(
            "SELECT 1 AS live FROM bond_approvals WHERE tenant_id = ? AND store_id = ? AND active",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs -> rs.getInt("live"),
            "is store bonded")
        .isEmpty();
  }

  public List<BondApproval> findApprovals(UUID tenantId) {
    return query(
        "SELECT tenant_id, store_id, approval_number, regime, active, created_by, created_at,"
            + " ended_at FROM bond_approvals WHERE tenant_id = ? ORDER BY active DESC, created_at",
        ps -> ps.setObject(1, tenantId),
        BondRepository::mapApproval,
        "list bond approvals");
  }

  // ── Duty rates ─────────────────────────────────────────────────────────────

  public ExciseDutyRate setRate(ExciseDutyRate r) {
    exec(
        "INSERT INTO excise_duty_rates (tenant_id, variant_id, duty_per_unit, currency, note,"
            + " updated_by, updated_at) VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id, variant_id) DO UPDATE SET duty_per_unit ="
            + " EXCLUDED.duty_per_unit, currency = EXCLUDED.currency, note = EXCLUDED.note,"
            + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
        ps -> {
          ps.setObject(1, r.tenantId());
          ps.setObject(2, r.variantId());
          ps.setBigDecimal(3, r.dutyPerUnit());
          ps.setString(4, r.currency());
          ps.setString(5, r.note());
          ps.setObject(6, r.updatedBy());
          ps.setObject(7, r.updatedAt().atOffset(ZoneOffset.UTC));
        },
        "set duty rate");
    return r;
  }

  public Optional<ExciseDutyRate> findRate(UUID tenantId, UUID variantId) {
    var rows =
        query(
            "SELECT tenant_id, variant_id, duty_per_unit, currency, note, updated_by, updated_at"
                + " FROM excise_duty_rates WHERE tenant_id = ? AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            BondRepository::mapRate,
            "find duty rate");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<ExciseDutyRate> findRates(UUID tenantId) {
    return query(
        "SELECT tenant_id, variant_id, duty_per_unit, currency, note, updated_by, updated_at"
            + " FROM excise_duty_rates WHERE tenant_id = ? ORDER BY variant_id",
        ps -> ps.setObject(1, tenantId),
        BondRepository::mapRate,
        "list duty rates");
  }

  // ── Releases and the stock in bond ─────────────────────────────────────────

  public List<BondRelease> findReleases(UUID tenantId, UUID storeId, LocalDate from, LocalDate to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, qty, duty_per_unit, duty_amount, currency,"
                + " reference, released_by, released_at FROM bond_releases WHERE tenant_id = ?"
                + " AND released_at >= ?::date AND released_at < (?::date + 1)");
    if (storeId != null) sql.append(" AND store_id = ?");
    sql.append(" ORDER BY released_at DESC, id DESC");
    return query(
        sql.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, from);
          ps.setObject(3, to);
          if (storeId != null) ps.setObject(4, storeId);
        },
        BondRepository::mapRelease,
        "list bond releases");
  }

  /** What sits in bond per store and variant, with the duty it would crystallise. */
  public List<BondStock> stockInBond(UUID tenantId, UUID storeId) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT b.store_id, b.variant_id, SUM(b.remaining_qty)::numeric(18,3) AS qty,"
                + " MAX(edr.duty_per_unit) AS duty_per_unit,"
                + " (SUM(b.remaining_qty) * COALESCE(MAX(edr.duty_per_unit), 0))::numeric(18,2)"
                + " AS duty_potential"
                + " FROM inventory_batches b LEFT JOIN excise_duty_rates edr"
                + " ON edr.tenant_id = b.tenant_id AND edr.variant_id = b.variant_id"
                + " WHERE b.tenant_id = ? AND b.duty_status = 'DUTY_SUSPENDED'"
                + " AND b.remaining_qty > 0");
    if (storeId != null) sql.append(" AND b.store_id = ?");
    sql.append(" GROUP BY b.store_id, b.variant_id ORDER BY b.store_id, b.variant_id");
    return query(
        sql.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        rs ->
            new BondStock(
                rs.getObject("store_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("duty_per_unit"),
                rs.getBigDecimal("duty_potential")),
        "stock in bond");
  }

  private static BondApproval mapApproval(ResultSet rs) throws SQLException {
    OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
    return new BondApproval(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("approval_number"),
        rs.getString("regime"),
        rs.getBoolean("active"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        ended == null ? null : ended.toInstant());
  }

  private static ExciseDutyRate mapRate(ResultSet rs) throws SQLException {
    return new ExciseDutyRate(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("duty_per_unit"),
        rs.getString("currency"),
        rs.getString("note"),
        rs.getObject("updated_by", UUID.class),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static BondRelease mapRelease(ResultSet rs) throws SQLException {
    return new BondRelease(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("duty_per_unit"),
        rs.getBigDecimal("duty_amount"),
        rs.getString("currency"),
        rs.getString("reference"),
        rs.getObject("released_by", UUID.class),
        rs.getObject("released_at", OffsetDateTime.class).toInstant());
  }

  static Instant now() {
    return Instant.now();
  }
}
