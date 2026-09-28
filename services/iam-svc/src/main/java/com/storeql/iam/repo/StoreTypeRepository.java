package com.storeql.iam.repo;

import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * iam-svc's projection of each store's type (STORE, WAREHOUSE, DARK_STORE), fed by tenant-svc's
 * {@code StoreStatusChanged}. Read at clock-in so no till session opens at a dark store
 * (ship-from-store and dark-store picking). A store with no row is taken for a shop.
 */
@ApplicationScoped
public class StoreTypeRepository extends BaseJdbcRepository {

  /**
   * Records a store's type as of the event's time; an older event never overwrites a newer one.
   *
   * @return whether the row was written
   */
  public boolean upsert(UUID storeId, UUID tenantId, String type, Instant changedAt) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO store_types (store_id, tenant_id, type, changed_at)"
                      + " VALUES (?,?,?,?) ON CONFLICT (store_id) DO UPDATE"
                      + " SET type = EXCLUDED.type, changed_at = EXCLUDED.changed_at"
                      + " WHERE EXCLUDED.changed_at >= store_types.changed_at")) {
            ps.setObject(1, storeId);
            ps.setObject(2, tenantId);
            ps.setString(3, type);
            ps.setObject(4, changedAt.atOffset(ZoneOffset.UTC));
            return ps.executeUpdate() > 0;
          }
        },
        "record store type");
  }

  /** The store's type as last announced, when the store is the tenant's and the type is known. */
  public Optional<String> typeOf(UUID tenantId, UUID storeId) {
    return query(
            "SELECT type FROM store_types WHERE tenant_id = ? AND store_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs -> rs.getString("type"),
            "read store type")
        .stream()
        .findFirst();
  }
}
