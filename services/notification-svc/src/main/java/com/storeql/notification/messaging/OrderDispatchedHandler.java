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
 * Tells a shopper their delivery order is on its way (ship-from-store): on {@code OrderDispatched},
 * naming the carrier and its reference when the store noted one. Once per event, on the buyer the
 * event names; a guest checkout sends nothing; a delivery failure propagates so the loop retries.
 */
@ApplicationScoped
class OrderDispatchedHandler {

  private static final Logger LOG = System.getLogger(OrderDispatchedHandler.class.getName());
  private static final String TYPE = "ORDER_DISPATCHED";

  @Inject Notifier notifier;
  @Inject CustomerClient customers;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    UUID customerId;
    String carrier;
    String reference;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!obj.containsKey("customerId") || obj.isNull("customerId")) {
        return; // guest checkout — no account to write to
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
      customerId = Ids.parse(obj.getString("customerId"));
      carrier = obj.getString("carrier");
      reference =
          obj.containsKey("reference") && !obj.isNull("reference")
              ? obj.getString("reference")
              : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderDispatched payload skipped: " + e.getMessage());
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
            new Messages.Message(
                TYPE,
                form,
                null,
                Values.of()
                    .text("order", orderId.toString())
                    .text("carrier", carrier)
                    .text("reference", reference)));
  }
}
