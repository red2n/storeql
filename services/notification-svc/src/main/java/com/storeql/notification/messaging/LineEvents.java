package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Values;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;

/**
 * How a line event of an online order reaches its shopper (substitutions for out-of-stock online
 * lines): the buyer the event names — a guest checkout sends nothing — told once per event through
 * {@link OrderMessages}, the order's reference and the refund (only when something goes back, in
 * the order's currency) said the same way in both messages, the rest in the words each handler
 * gives. A malformed payload is skipped, not thrown; a delivery failure propagates so the loop
 * retries.
 */
final class LineEvents {

  private static final Logger LOG = System.getLogger(LineEvents.class.getName());

  private LineEvents() {}

  /**
   * @param type the message type sent
   * @param words the message's own values from the payload: the item, the substitute, the quantity
   */
  static void tell(
      Notifier notifier,
      CustomerClient customers,
      String json,
      String type,
      Function<JsonObject, Values> words) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    UUID customerId;
    JsonObject obj;
    BigDecimal refund;
    String currency;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      if (!obj.containsKey("customerId") || obj.isNull("customerId")) {
        return; // guest checkout — no account to write to
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
      customerId = Ids.parse(obj.getString("customerId"));
      refund = refund(obj);
      currency = obj.getString("currency", null);
      words.apply(obj); // a payload the words cannot be read from is malformed too
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed " + type + " payload skipped: " + e.getMessage());
      return;
    }
    OrderMessages.tell(
        notifier,
        customers,
        eventId,
        type,
        tenantId,
        customerId,
        orderId,
        form -> {
          Values values = words.apply(obj).text("order", orderId.toString());
          if (refund != null && currency != null) values.money("refund", refund, currency);
          return new Messages.Message(type, form, null, values);
        });
  }

  /** The product's name, or "an item" when order-svc could not name it. */
  static String item(JsonObject obj, String key) {
    String name = obj.containsKey(key) && !obj.isNull(key) ? obj.getString(key) : null;
    return name == null || name.isBlank() ? "an item" : name;
  }

  /** The quantity as a person writes it: 2, not 2.000. */
  static String qty(JsonObject obj) {
    return new BigDecimal(obj.get("qty").toString()).stripTrailingZeros().toPlainString();
  }

  /** The refund, or null when the event carries none or nothing goes back. */
  private static BigDecimal refund(JsonObject obj) {
    if (!obj.containsKey("refundAmount") || obj.isNull("refundAmount")) return null;
    BigDecimal refund = obj.getJsonNumber("refundAmount").bigDecimalValue();
    return refund.signum() > 0 ? refund : null;
  }
}
