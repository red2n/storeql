package com.storeql.customer.messaging;

import com.storeql.customer.json.Jsons;
import com.storeql.customer.service.CustomerService;
import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Business handler for {@code storeql.order.order-confirmed} events. Accrues loyalty points for the
 * order's buyer via {@link CustomerService#accrueLoyaltyFromOrder}, idempotently keyed by the
 * event's {@code eventId}. Separated from {@link OrderConfirmedConsumer} so Kafka lifecycle and
 * domain logic each have a single reason to change (SRP).
 *
 * <p>Guest orders (no {@code customerId}) and malformed payloads are logged and skipped — they will
 * never parse/apply on redelivery either. A failed accrual write propagates so the consumer loop
 * redelivers the record; the accrual itself dedupes on {@code eventId} so redelivery is safe.
 */
@ApplicationScoped
class OrderConfirmedHandler {

  private static final Logger LOG = System.getLogger(OrderConfirmedHandler.class.getName());

  @Inject CustomerService service;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    UUID customerId;
    BigDecimal total;
    BigDecimal tax;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      // Guest orders carry customerId:null (or omit it on legacy events) — nobody to award.
      if (!obj.containsKey("customerId") || obj.isNull("customerId")) {
        return;
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
      customerId = Ids.parse(obj.getString("customerId"));
      total = obj.getJsonNumber("total").bigDecimalValue();
      // The VAT inside the total; an event from before 17.7 carries none.
      tax =
          obj.containsKey("taxAmount") && !obj.isNull("taxAmount")
              ? obj.getJsonNumber("taxAmount").bigDecimalValue()
              : BigDecimal.ZERO;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderConfirmed payload skipped: " + e.getMessage());
      return;
    }

    service.accrueLoyaltyFromOrder(eventId, tenantId, customerId, orderId, total, tax);
  }
}
