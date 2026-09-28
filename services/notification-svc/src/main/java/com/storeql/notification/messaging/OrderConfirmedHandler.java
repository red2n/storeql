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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emails the buyer an order confirmation when an order is confirmed. Guest orders (no customerId)
 * are skipped; for a real customer the email is resolved from customer-svc (best-effort — a missing
 * email just skips the send). Malformed payloads are skipped; a delivery failure propagates so the
 * consumer loop retries (idempotent per event in {@link Notifier}).
 *
 * <p>Delivery and collection slots: when the order carries a window ({@code slotStartsAt} / {@code
 * slotEndsAt} / {@code slotTimeZone}), the confirmation says it in the store's own zone and the
 * reader's language; an order with no window reads exactly as before.
 */
@ApplicationScoped
class OrderConfirmedHandler {

  private static final Logger LOG = System.getLogger(OrderConfirmedHandler.class.getName());
  private static final String DELIVERY = "DELIVERY";

  @Inject Notifier notifier;
  @Inject CustomerClient customers;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    UUID customerId;
    BigDecimal total;
    String currency;
    String fulfilmentType;
    Instant slotStartsAt;
    Instant slotEndsAt;
    String slotTimeZone;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!obj.containsKey("customerId") || obj.isNull("customerId")) {
        return; // guest checkout — no account to email
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
      customerId = Ids.parse(obj.getString("customerId"));
      total = obj.getJsonNumber("total").bigDecimalValue();
      // order-svc always sends the order's currency; one without is malformed, not pounds (SJ-D53).
      currency = obj.getString("currency");
      fulfilmentType = obj.getString("fulfilmentType", null);
      slotStartsAt = Payloads.instant(obj, "slotStartsAt");
      slotEndsAt = Payloads.instant(obj, "slotEndsAt");
      slotTimeZone = obj.getString("slotTimeZone", null);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderConfirmed payload skipped: " + e.getMessage());
      return;
    }

    // How it reaches them — email, the shopper's own language, a push to a registered phone, each
    // once — is the way every order message does (ship-from-store added two more).
    boolean delivery = DELIVERY.equals(fulfilmentType);
    OrderMessages.tell(
        notifier,
        customers,
        eventId,
        "ORDER_CONFIRMATION",
        tenantId,
        customerId,
        orderId,
        form ->
            new Messages.Message(
                "ORDER_CONFIRMED",
                form,
                null,
                Values.of()
                    .text("order", orderId.toString())
                    .money("total", total, currency)
                    .window("window", delivery, slotStartsAt, slotEndsAt, slotTimeZone)));
  }
}
