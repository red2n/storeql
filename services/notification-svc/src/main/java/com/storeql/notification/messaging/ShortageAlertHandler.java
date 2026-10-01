package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.json.Jsons;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.NotificationService;
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
 * Handles StockBelowThreshold events published by inventory-svc. The eventId dedupe and the
 * ShortageAlert insert commit in one transaction (see {@code insertAlertOnce}). Separated from
 * {@link ShortageAlertConsumer} (SRP). Malformed payloads are skipped; write failures propagate so
 * the consumer loop redelivers instead of losing the event.
 *
 * <p>Also pushes the alert via {@link Notifier} (recipient = store id) so a device-facing channel —
 * MQTT to POS terminals / kiosk displays / the platform console — gets it in real time instead of
 * relying on staff polling {@code /admin/notifications/shortage-alerts}. The push is called
 * unconditionally (not gated on the alert being newly recorded): {@link Notifier} has its own
 * (eventId, type) dedupe, independent of the shortage_alerts dedupe, so a redelivery after a prior
 * push failure still retries the push even though the alert row is already there.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, variantId, available, threshold}}.
 */
@ApplicationScoped
class ShortageAlertHandler {

  private static final Logger LOG = System.getLogger(ShortageAlertHandler.class.getName());
  static final String CONSUMER_NAME = "notification-svc/shortage-alert";
  static final String NOTIFICATION_TYPE = "SHORTAGE_ALERT";

  @Inject NotificationService service;
  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID variantId;
    BigDecimal available;
    BigDecimal threshold;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      variantId = Ids.parse(obj.getString("variantId"));
      available = new BigDecimal(obj.get("available").toString());
      threshold = new BigDecimal(obj.get("threshold").toString());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StockBelowThreshold payload skipped: " + e.getMessage());
      return;
    }

    boolean recorded =
        service.recordShortageAlertOnce(
            CONSUMER_NAME, tenantId, storeId, variantId, available, threshold, eventId);
    if (recorded) {
      LOG.log(
          Level.WARNING,
          "SHORTAGE_ALERT tenant={0} store={1} variant={2} available={3} threshold={4}",
          tenantId,
          storeId,
          variantId,
          available,
          threshold);
    }

    // Addressed to a store's devices, not to a person, so there is no subject to erase
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId.toString(),
        new Messages.Message(
            "STOCK_BELOW_THRESHOLD",
            Catalogue.Form.ALERT,
            null,
            Values.of()
                .text("variant", variantId.toString())
                .text("store", storeId.toString())
                .number("available", available)
                .number("threshold", threshold)));
  }
}
