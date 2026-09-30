package com.storeql.iam.messaging;

import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Business handler for {@code storeql.tenant.staff-assigned} events. Binds the assigned
 * store-scoped role to the staff login, idempotently — only a login already in the business (made
 * there by staff provisioning, or its owner); any other is refused and audited, never stamped in
 * (see {@code bindStaffOnce}). Separated from {@link StaffAssignedConsumer} so Kafka lifecycle and
 * domain logic each have a single reason to change (SRP).
 *
 * <p>The dedupe mark and the bind commit in one transaction (see {@code bindStaffOnce}); a
 * malformed payload is logged and skipped, while a failed write propagates so the consumer loop
 * redelivers the record instead of losing it.
 */
@ApplicationScoped
public class StaffAssignedHandler {

  private static final Logger LOG = System.getLogger(StaffAssignedHandler.class.getName());
  static final String CONSUMER_NAME = "iam-svc/staff-assigned";

  @Inject UserRepository users;

  public void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID userId;
    UUID storeId;
    String role;
    String roleCode;
    java.util.Set<String> permissions;
    java.time.Instant roleUpdatedAt;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      userId = Ids.parse(obj.getString("userId"));
      // A business-wide assignment (head office, MANAGER tier) names no store and says so with
      // "businessWide":true; the row then has a null store, which a token reads as "held to no
      // store". A store assignment is exactly as it was: storeId, no marker.
      boolean businessWide = obj.getBoolean("businessWide", false);
      storeId = businessWide ? null : Ids.parse(obj.getString("storeId"));
      role = obj.getString("role");
      if (businessWide && !"MANAGER".equals(role)) {
        LOG.log(Level.WARNING, "Business-wide StaffAssigned for a non-MANAGER tier skipped");
        return;
      }
      // A custom role (20.10): the tier is in "role" as ever; the code and the permissions it
      // held at assignment ride beside it. Absent for a plain tier assignment.
      roleCode = obj.getString("roleCode", null);
      permissions = roleCode == null ? null : Permissions.parse(obj);
      roleUpdatedAt = roleCode == null ? null : Permissions.instant(obj, "roleUpdatedAt");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StaffAssigned payload skipped: " + e.getMessage());
      return;
    }

    boolean processed =
        users.bindStaffOnce(
            eventId,
            CONSUMER_NAME,
            userId,
            tenantId,
            role,
            storeId,
            roleCode,
            permissions,
            roleUpdatedAt);
    if (processed) {
      LOG.log(
          Level.INFO,
          "StaffAssigned handled (bound, or refused and audited): user {0} as {1}{2} of tenant {3}"
              + " store {4} (null: business-wide)",
          userId,
          role,
          roleCode == null ? "" : " (" + roleCode + ")",
          tenantId,
          storeId);
    }
  }
}
