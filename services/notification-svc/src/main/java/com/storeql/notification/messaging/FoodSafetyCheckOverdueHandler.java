package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.json.Jsons;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.UUID;

/**
 * Alerts a store that a food-safety check was missed. inventory-svc raises one event per missed due
 * time, and {@link Notifier} dedupes redeliveries, so a store is told once per miss rather than on
 * every sweep. A malformed payload is skipped.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, pointName, checkTypeCode, dueSince}}.
 */
@ApplicationScoped
class FoodSafetyCheckOverdueHandler {

  private static final Logger LOG = System.getLogger(FoodSafetyCheckOverdueHandler.class.getName());
  static final String NOTIFICATION_TYPE = "FOOD_SAFETY_CHECK_OVERDUE";

  /** The event carries UTC and a message has no viewer's zone to convert to, so it says so. */
  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    String pointName;
    Instant dueSince;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      pointName = obj.getString("pointName");
      dueSince = Instant.parse(obj.getString("dueSince"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed FoodSafetyCheckOverdue payload skipped: " + e.getMessage());
      return;
    }
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId.toString(),
        new Messages.Message(
            "FOOD_SAFETY_CHECK_OVERDUE",
            Catalogue.Form.ALERT,
            null,
            Values.of().text("point", pointName).moment("due_since", dueSince)));
  }
}
