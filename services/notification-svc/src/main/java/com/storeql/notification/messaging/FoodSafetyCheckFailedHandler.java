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
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Alerts a store that a food-safety check failed: a chiller read warm, a hot cabinet read cold, or
 * a checklist was marked failed. Addressed to the store's devices, like a shortage alert, because
 * whoever is on shift has to act on it rather than whoever took the reading. Idempotent through
 * {@link Notifier}'s (eventId, type) dedupe; a malformed payload is skipped.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, pointName, checkTypeCode, kind, value,
 * unit, minValue, maxValue}}.
 */
@ApplicationScoped
class FoodSafetyCheckFailedHandler {

  private static final Logger LOG = System.getLogger(FoodSafetyCheckFailedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "FOOD_SAFETY_CHECK_FAILED";

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    String pointName;
    BigDecimal value;
    BigDecimal min;
    BigDecimal max;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      pointName = obj.getString("pointName");
      value = decimal(obj, "value");
      min = decimal(obj, "minValue");
      max = decimal(obj, "maxValue");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed FoodSafetyCheckFailed payload skipped: " + e.getMessage());
      return;
    }
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId.toString(),
        new Messages.Message(
            "FOOD_SAFETY_CHECK_FAILED",
            Catalogue.Form.ALERT,
            null,
            Values.of()
                .text("point", pointName)
                .number("reading", value)
                .number("min", min)
                .number("max", max)));
  }

  /**
   * A null field is written as null in the payload, but an absent one must read the same (SJ-D14).
   */
  private static BigDecimal decimal(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key)
        ? obj.getJsonNumber(key).bigDecimalValue()
        : null;
  }
}
