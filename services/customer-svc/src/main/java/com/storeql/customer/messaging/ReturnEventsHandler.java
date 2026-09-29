package com.storeql.customer.messaging;

import com.storeql.customer.service.CustomerService;
import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Business handler for what a returned or voided sale does to a customer (return controls).
 *
 * <ul>
 *   <li>{@code OrderReturned}: takes back the points the refunded part of the sale earned.
 *   <li>{@code OrderVoided}: takes back the rest of the points the sale earned.
 *   <li>{@code PaymentRefunded} with {@code refundMethod} STORE_CREDIT: credits the customer's
 *       store credit by the refunded amount.
 * </ul>
 *
 * Every write dedupes on the event's {@code eventId}, so a redelivery does nothing. Malformed
 * payloads are logged and skipped (they will never parse on redelivery either); a failed write
 * propagates so the consumer loop redelivers the record.
 */
@ApplicationScoped
class ReturnEventsHandler {

  private static final Logger LOG = System.getLogger(ReturnEventsHandler.class.getName());

  @Inject CustomerService service;

  void handleReturned(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    BigDecimal refund;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
      refund = decimal(obj, "refundAmount");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderReturned payload skipped: " + e.getMessage());
      return;
    }
    service.reverseLoyaltyForReturn(eventId, tenantId, orderId, refund);
  }

  void handleVoided(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderVoided payload skipped: " + e.getMessage());
      return;
    }
    service.reverseLoyaltyForVoid(eventId, tenantId, orderId);
  }

  void handleRefunded(String json) {
    UUID eventId;
    UUID tenantId;
    UUID customerId;
    UUID orderId;
    UUID refundId;
    UUID returnId;
    BigDecimal amount;
    String currency;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"STORE_CREDIT".equals(text(obj, "refundMethod"))) {
        return; // a refund to the original tender or a gift card credits no store credit
      }
      String customer = text(obj, "customerId");
      if (customer == null) {
        LOG.log(
            Level.WARNING,
            "STORE_CREDIT refund "
                + text(obj, "refundId")
                + " names no customer; nothing credited");
        return;
      }
      String refund = text(obj, "refundId");
      String ret = text(obj, "returnId");
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      customerId = Ids.parse(customer);
      orderId = Ids.parse(obj.getString("orderId"));
      refundId = refund == null ? null : Ids.parse(refund);
      returnId = ret == null ? null : Ids.parse(ret);
      amount = decimal(obj, "amount");
      currency = text(obj, "currency");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed PaymentRefunded payload skipped: " + e.getMessage());
      return;
    }
    service.creditStoreCreditFromRefund(
        eventId, tenantId, customerId, orderId, refundId, returnId, amount, currency);
  }

  private static String text(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key) ? obj.getString(key) : null;
  }

  private static BigDecimal decimal(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key)
        ? obj.getJsonNumber(key).bigDecimalValue()
        : null;
  }
}
