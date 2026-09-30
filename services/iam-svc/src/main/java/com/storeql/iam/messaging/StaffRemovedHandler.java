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
 * Takes a staff role away when tenant-svc removes the assignment (SJ-D51). Idempotent on the event
 * id; a malformed payload is logged and skipped.
 */
@ApplicationScoped
public class StaffRemovedHandler {

  private static final Logger LOG = System.getLogger(StaffRemovedHandler.class.getName());
  static final String CONSUMER_NAME = "iam-svc/staff-removed";

  @Inject UserRepository users;

  /**
   * @param json the {@code StaffRemoved} payload
   */
  public void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID userId;
    UUID storeId;
    String role;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"StaffRemoved".equals(obj.getString("eventType", ""))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      userId = Ids.parse(obj.getString("userId"));
      // "businessWide":true and no storeId: the business-wide assignment (null store row) goes.
      storeId = obj.getBoolean("businessWide", false) ? null : Ids.parse(obj.getString("storeId"));
      role = obj.getString("role");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StaffRemoved payload skipped: " + e.getMessage());
      return;
    }
    if (users.unbindStaffOnce(eventId, CONSUMER_NAME, userId, tenantId, role, storeId)) {
      LOG.log(
          Level.INFO,
          "Unbound user {0} as {1} of tenant {2} store {3}",
          userId,
          role,
          tenantId,
          storeId);
    }
  }
}
