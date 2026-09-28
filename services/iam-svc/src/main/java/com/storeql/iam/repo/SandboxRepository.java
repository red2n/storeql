package com.storeql.iam.repo;

import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

/**
 * Which tenants are sandboxes, and of what (22.8): kept from tenant-svc's {@code TenantCreated}
 * announcements. The active sandbox of a business is the newest one not switched off in {@code
 * tenant_status}, the projection this service already keeps.
 */
@ApplicationScoped
public class SandboxRepository extends BaseJdbcRepository {

  private static final String RECORD =
      "INSERT INTO tenant_sandboxes (sandbox_tenant_id, live_tenant_id) VALUES (?, ?)"
          + " ON CONFLICT (sandbox_tenant_id) DO NOTHING";
  private static final String ACTIVE_OF =
      "SELECT s.sandbox_tenant_id FROM tenant_sandboxes s"
          + " LEFT JOIN tenant_status ts ON ts.tenant_id = s.sandbox_tenant_id"
          + " WHERE s.live_tenant_id = ? AND COALESCE(ts.status, 'ACTIVE') = 'ACTIVE'"
          + " ORDER BY s.created_at DESC, s.sandbox_tenant_id DESC LIMIT 1";
  private static final String LIVE_OF =
      "SELECT live_tenant_id FROM tenant_sandboxes WHERE sandbox_tenant_id = ?";

  /**
   * Records a sandbox once, under the event's processed mark in the same transaction.
   *
   * @return whether it was recorded now; false when the event was already processed
   */
  public boolean recordOnce(UUID eventId, String consumerName, UUID sandboxId, UUID liveId) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          try (PreparedStatement ps = c.prepareStatement(RECORD)) {
            ps.setObject(1, sandboxId);
            ps.setObject(2, liveId);
            ps.executeUpdate();
          }
          return true;
        },
        "record sandbox");
  }

  /** The business's active sandbox, if it has one. */
  public Optional<UUID> activeSandboxOf(UUID liveTenantId) {
    return query(
            ACTIVE_OF,
            ps -> ps.setObject(1, liveTenantId),
            rs -> rs.getObject("sandbox_tenant_id", UUID.class),
            "active sandbox of tenant")
        .stream()
        .findFirst();
  }

  /** The live business a sandbox stands in for; empty when the tenant is not a sandbox. */
  public Optional<UUID> liveOf(UUID tenantId) {
    return query(
            LIVE_OF,
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getObject("live_tenant_id", UUID.class),
            "live tenant of sandbox")
        .stream()
        .findFirst();
  }
}
