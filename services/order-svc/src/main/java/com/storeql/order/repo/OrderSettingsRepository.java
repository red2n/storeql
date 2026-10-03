package com.storeql.order.repo;

import com.storeql.order.domain.OrderSettings;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

/**
 * The waits a business sets on an order: one row per business, the one place for the unpaid-order
 * limit and the price-wait limits. A setting, not a ledger; who changed it last and when are kept
 * beside it.
 */
@ApplicationScoped
public class OrderSettingsRepository extends BaseJdbcRepository {

  /**
   * The business's settings.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the row, or empty when the business never set anything
   */
  public Optional<OrderSettings> find(UUID tenantId) {
    return query(
            "SELECT pending_limit_hours, price_wait_flag_minutes, price_wait_cancel_minutes"
                + " FROM order_settings WHERE tenant_id=?",
            ps -> ps.setObject(1, tenantId),
            rs ->
                new OrderSettings(
                    (Integer) rs.getObject("pending_limit_hours"),
                    (Integer) rs.getObject("price_wait_flag_minutes"),
                    (Integer) rs.getObject("price_wait_cancel_minutes")),
            "find order settings")
        .stream()
        .findFirst();
  }

  /**
   * Replaces the business's settings.
   *
   * @param tenantId owning tenant
   * @param s the settings as they should now stand
   * @param updatedBy who changed them
   */
  public void save(UUID tenantId, OrderSettings s, UUID updatedBy) {
    exec(
        "INSERT INTO order_settings (tenant_id, pending_limit_hours, price_wait_flag_minutes,"
            + " price_wait_cancel_minutes, updated_by, updated_at) VALUES (?,?,?,?,?,now())"
            + " ON CONFLICT (tenant_id) DO UPDATE SET"
            + " pending_limit_hours=EXCLUDED.pending_limit_hours,"
            + " price_wait_flag_minutes=EXCLUDED.price_wait_flag_minutes,"
            + " price_wait_cancel_minutes=EXCLUDED.price_wait_cancel_minutes,"
            + " updated_by=EXCLUDED.updated_by, updated_at=now()",
        ps -> {
          ps.setObject(1, tenantId);
          setInt(ps, 2, s.pendingLimitHours());
          setInt(ps, 3, s.priceWaitFlagMinutes());
          setInt(ps, 4, s.priceWaitCancelMinutes());
          if (updatedBy != null) ps.setObject(5, updatedBy);
          else ps.setNull(5, Types.OTHER);
        },
        "save order settings");
  }

  private static void setInt(java.sql.PreparedStatement ps, int i, Integer v)
      throws java.sql.SQLException {
    if (v == null) ps.setNull(i, Types.INTEGER);
    else ps.setInt(i, v);
  }
}
