package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Tells a shopper their pickup order is ready to collect (ship-from-store and dark-store picking):
 * on the {@code OrderFulfilled} that makes an online pickup order FULFILLED — picked and packed in
 * full — and on no other. A part handover, a delivery (told at dispatch), a till sale and a guest
 * checkout send nothing. Once per event, on the buyer the event names; a delivery failure
 * propagates so the loop retries.
 */
@ApplicationScoped
class OrderFulfilledHandler {

  private static final Logger LOG = System.getLogger(OrderFulfilledHandler.class.getName());
  private static final String TYPE = "ORDER_READY_FOR_COLLECTION";

  @Inject Notifier notifier;
  @Inject CustomerClient customers;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    UUID customerId;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"FULFILLED".equals(obj.getString("status", null))
          || !"PICKUP".equals(obj.getString("fulfilmentType", null))
          || !"ONLINE".equals(obj.getString("channel", null))) {
        return; // part-picked, a delivery, a till sale, or an event from before these were said
      }
      if (!obj.containsKey("customerId") || obj.isNull("customerId")) {
        return; // guest checkout — no account to write to
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
      customerId = Ids.parse(obj.getString("customerId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderFulfilled payload skipped: " + e.getMessage());
      return;
    }
    OrderMessages.tell(
        notifier,
        customers,
        eventId,
        TYPE,
        tenantId,
        customerId,
        orderId,
        form ->
            new Messages.Message(TYPE, form, null, Values.of().text("order", orderId.toString())));
  }
}
