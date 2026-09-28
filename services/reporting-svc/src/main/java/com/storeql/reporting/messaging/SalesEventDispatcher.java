package com.storeql.reporting.messaging;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.Domain.SaleLine;
import com.storeql.reporting.service.ReportingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Routes order/payment events to the sales projection (N4). {@code OrderConfirmed} records a sale
 * (idempotent on the order PK); {@code PaymentRefunded} accumulates a refund against that sale
 * (deduped on eventId); {@code OrderVoided} marks a till sale voided after the fact, so it leaves
 * every sales report (deduped on eventId). One dispatcher per domain keeps each concern a single
 * private method (SRP).
 *
 * <p>Malformed payloads are skipped and write failures propagate ({@link JsonEventDispatcher}).
 */
@ApplicationScoped
class SalesEventDispatcher extends JsonEventDispatcher {

  private static final String CONSUMER = "reporting-svc/sales-events";

  @Inject ReportingService service;

  SalesEventDispatcher() {
    super("sales");
  }

  @Override
  protected boolean route(String topic, JsonObject obj) {
    switch (topic) {
      case "storeql.order.order-confirmed" -> handleOrderConfirmed(obj);
      case "storeql.payment.payment-refunded" -> handleRefunded(obj);
      case "storeql.order.order-voided" -> handleVoided(obj);
      default -> {
        return false;
      }
    }
    return true;
  }

  private void handleOrderConfirmed(JsonObject obj) {
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    UUID orderId = Ids.parse(obj.getString("orderId"));
    UUID storeId = optUuid(obj, "storeId");
    String channel = obj.getString("channel", null);
    UUID customerId = optUuid(obj, "customerId");
    BigDecimal gross = obj.getJsonNumber("total").bigDecimalValue();
    // A sale without its currency is malformed: recording it as pounds would corrupt revenue.
    String currency = obj.getString("currency");
    // The lines, for sales by category (19.x); an event minted before they were carried has none.
    List<SaleLine> lines =
        obj.containsKey("lines") && !obj.isNull("lines")
            ? obj.getJsonArray("lines").getValuesAs(JsonObject.class).stream()
                .map(SalesEventDispatcher::line)
                .toList()
            : List.of();
    service.recordSale(tenantId, orderId, storeId, channel, customerId, gross, currency, lines);
  }

  private static SaleLine line(JsonObject l) {
    BigDecimal unitPrice =
        l.containsKey("unitPrice") && !l.isNull("unitPrice")
            ? l.getJsonNumber("unitPrice").bigDecimalValue()
            : null;
    return new SaleLine(
        Ids.parse(l.getString("variantId")),
        l.getJsonNumber("qty").bigDecimalValue(),
        unitPrice,
        l.getJsonNumber("lineTotal").bigDecimalValue());
  }

  private void handleRefunded(JsonObject obj) {
    UUID eventId = Ids.parse(obj.getString("eventId"));
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    UUID orderId = Ids.parse(obj.getString("orderId"));
    BigDecimal amount = obj.getJsonNumber("amount").bigDecimalValue();
    service.applySalesRefund(eventId, CONSUMER, tenantId, orderId, amount);
  }

  /**
   * A till sale voided after the fact no longer stands. The event's items are what inventory-svc
   * restocks; the sales projection needs only which sale, whose, and the event to dedupe on.
   */
  private void handleVoided(JsonObject obj) {
    UUID eventId = Ids.parse(obj.getString("eventId"));
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    UUID orderId = Ids.parse(obj.getString("orderId"));
    service.applySaleVoided(eventId, CONSUMER, tenantId, orderId);
  }
}
