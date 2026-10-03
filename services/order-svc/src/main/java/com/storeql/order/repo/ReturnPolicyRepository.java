package com.storeql.order.repo;

import com.storeql.order.domain.ReturnPolicy;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

/**
 * A business's return policy: one row per business, replaced whole when management changes it. The
 * row is a setting, not a ledger; who changed it last and when are kept beside it.
 */
@ApplicationScoped
public class ReturnPolicyRepository extends BaseJdbcRepository {

  /**
   * The policy the business set.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the policy, or empty when the business never set one (the default applies)
   */
  public Optional<ReturnPolicy> find(UUID tenantId) {
    return query(
            "SELECT window_days, cashier_ceiling, no_receipt_allowed, no_receipt_ceiling"
                + " FROM return_policies WHERE tenant_id=?",
            ps -> ps.setObject(1, tenantId),
            rs ->
                new ReturnPolicy(
                    rs.getInt("window_days"),
                    rs.getBigDecimal("cashier_ceiling"),
                    rs.getBoolean("no_receipt_allowed"),
                    rs.getBigDecimal("no_receipt_ceiling")),
            "find return policy")
        .stream()
        .findFirst();
  }

  /**
   * Sets the policy, replacing any earlier one.
   *
   * @param tenantId owning tenant
   * @param policy the new policy
   * @param updatedBy the member of management who set it
   */
  public void save(UUID tenantId, ReturnPolicy policy, UUID updatedBy) {
    exec(
        "INSERT INTO return_policies (tenant_id, window_days, cashier_ceiling,"
            + " no_receipt_allowed, no_receipt_ceiling, updated_by, updated_at)"
            + " VALUES (?,?,?,?,?,?,now())"
            + " ON CONFLICT (tenant_id) DO UPDATE SET window_days=EXCLUDED.window_days,"
            + " cashier_ceiling=EXCLUDED.cashier_ceiling,"
            + " no_receipt_allowed=EXCLUDED.no_receipt_allowed,"
            + " no_receipt_ceiling=EXCLUDED.no_receipt_ceiling,"
            + " updated_by=EXCLUDED.updated_by, updated_at=now()",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, policy.windowDays());
          ps.setBigDecimal(3, policy.cashierCeiling());
          ps.setBoolean(4, policy.noReceiptAllowed());
          ps.setBigDecimal(5, policy.noReceiptCeiling());
          if (updatedBy != null) ps.setObject(6, updatedBy);
          else ps.setNull(6, java.sql.Types.OTHER);
        },
        "save return policy");
  }
}
