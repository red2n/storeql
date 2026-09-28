package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.StorefrontStockSettings;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * "Only N left" on the storefront: one row per tenant, present only once a threshold has ever been
 * set. Absent means off — {@link #find} answers empty rather than a row of nulls, and the service
 * turns an empty read into {@link StorefrontStockSettings#off}.
 */
@ApplicationScoped
public class StorefrontSettingsRepository extends BaseJdbcRepository {

  private static final String COLUMNS = "tenant_id, low_stock_threshold, updated_by, updated_at";

  /**
   * Reads the tenant's setting.
   *
   * @param tenantId owning tenant; the first (and only) condition of the query
   * @return the setting, or empty when this business has never set one (the feature is off)
   */
  public Optional<StorefrontStockSettings> find(UUID tenantId) {
    return query(
            "SELECT " + COLUMNS + " FROM storefront_stock_settings WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            StorefrontSettingsRepository::map,
            "read storefront stock settings")
        .stream()
        .findFirst();
  }

  /**
   * Upserts the tenant's threshold. Keyed by {@code tenant_id} alone — the setting is
   * business-wide, so there is exactly one row to hold or replace.
   *
   * @param tenantId owning tenant
   * @param lowStockThreshold 1..1000 (already range-checked by the caller), or null to switch the
   *     feature off
   * @param updatedBy the caller applying the change
   * @return the setting as stored
   */
  public StorefrontStockSettings upsert(UUID tenantId, Integer lowStockThreshold, UUID updatedBy) {
    Instant now = Instant.now();
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO storefront_stock_settings"
                      + " (tenant_id, low_stock_threshold, updated_by, updated_at)"
                      + " VALUES (?,?,?,?)"
                      + " ON CONFLICT (tenant_id) DO UPDATE SET"
                      + " low_stock_threshold = EXCLUDED.low_stock_threshold,"
                      + " updated_by = EXCLUDED.updated_by,"
                      + " updated_at = EXCLUDED.updated_at"
                      + " RETURNING "
                      + COLUMNS)) {
            ps.setObject(1, tenantId);
            if (lowStockThreshold == null) {
              ps.setNull(2, Types.INTEGER);
            } else {
              ps.setInt(2, lowStockThreshold);
            }
            ps.setObject(3, updatedBy);
            ps.setObject(4, now.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return map(rs);
            }
          }
        },
        "upsert storefront stock settings");
  }

  private static StorefrontStockSettings map(ResultSet rs) throws SQLException {
    int threshold = rs.getInt("low_stock_threshold");
    // wasNull speaks of the last column read: ask it right after the one that may be null.
    Integer lowStockThreshold = rs.wasNull() ? null : threshold;
    return new StorefrontStockSettings(
        rs.getObject("tenant_id", UUID.class),
        lowStockThreshold,
        rs.getObject("updated_by", UUID.class),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
