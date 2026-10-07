package com.storeql.reporting.messaging;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Routes incoming inventory events to the correct projection update. One dispatcher handles all
 * stock-related topics so each handler concern is a single private method (SRP).
 *
 * <p>Idempotency: the stock-delta projections and a shipment's supply lines dedupe on eventId
 * atomically with their writes (golden rule #7). A receipt does not dedupe on its own eventId:
 * deleting a transfer's lines twice is harmless. What it does write is a note that the transfer
 * landed, a {@code processed_events} row under a key derived from the business and the transfer
 * (not an event id), taken under the transfer's own lock. That note and that lock are what make a
 * shipment and its receipt independent of the order they are read in (they travel on two topics,
 * which Kafka does not order against each other): a shipment read after its receipt finds the note
 * and opens nothing. Do not remove them as a redundant dedupe mark. Malformed payloads are skipped;
 * write failures propagate so the consumer loop redelivers instead of losing the event.
 */
@ApplicationScoped
class StockEventDispatcher extends JsonEventDispatcher {

  private static final Logger LOG = System.getLogger(StockEventDispatcher.class.getName());
  private static final String CONSUMER = "reporting-svc/stock-events";
  private static final String IN_TRANSIT = "INTRANSIT";

  @Inject ReportingService service;

  StockEventDispatcher() {
    super("stock");
  }

  @Override
  protected boolean route(String topic, JsonObject obj) {
    UUID eventId = Ids.parse(obj.getString("eventId"));
    switch (topic) {
      case "storeql.inventory.stock-received" ->
          applyDelta(eventId, obj, qty(obj), "StockReceived");
      case "storeql.inventory.stock-deducted" -> handleDeducted(eventId, obj);
      case "storeql.inventory.stock-adjusted" ->
          applyDelta(eventId, obj, new BigDecimal(obj.get("delta").toString()), "StockAdjusted");
      case "storeql.inventory.transfer-order-shipped" -> handleTransferShipped(eventId, obj);
      case "storeql.inventory.transfer-order-received" -> handleTransferReceived(obj);
      default -> {
        return false;
      }
    }
    return true;
  }

  private void applyDelta(UUID eventId, JsonObject obj, BigDecimal delta, String eventType) {
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    UUID storeId = Ids.parse(obj.getString("storeId"));
    UUID variantId = Ids.parse(obj.getString("variantId"));
    service.applyStockDeltaOnce(eventId, CONSUMER, tenantId, storeId, variantId, delta, eventType);
  }

  private void handleDeducted(UUID eventId, JsonObject obj) {
    // StockDeducted now carries storeId/variantId/qty (enriched in inventory-svc)
    if (!obj.containsKey("storeId") || !obj.containsKey("variantId")) {
      LOG.log(Level.WARNING, "StockDeducted missing storeId/variantId — skipped");
      return;
    }
    applyDelta(eventId, obj, qty(obj).negate(), "StockDeducted");
  }

  /**
   * A transfer left its store. Only an INTRANSIT one is stock in transit: a DIRECT transfer is
   * booked into the receiving store as it ships and no receipt follows, so a line opened for it
   * would never be retired. An event that does not say which kind it is cannot be told apart, so it
   * opens nothing either.
   *
   * <p>The dedupe mark and the supply lines commit together (see applyTransferShippedOnce), so a
   * redelivery adds nothing twice and a failed write is retried rather than swallowed. A line that
   * cannot be read makes the whole event malformed, so it is skipped before anything is written.
   */
  private void handleTransferShipped(UUID eventId, JsonObject obj) {
    UUID transferOrderId = Ids.parse(obj.getString("aggregateId"));
    if (!IN_TRANSIT.equals(obj.getString("transferType", null))) {
      LOG.log(Level.DEBUG, "Transfer {0} is not in transit as it ships: no line", transferOrderId);
      return;
    }
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    UUID fromStoreId = Ids.parse(obj.getString("fromStoreId"));
    UUID toStoreId = Ids.parse(obj.getString("toStoreId"));
    List<UUID> variantIds = new ArrayList<>();
    List<BigDecimal> qtys = new ArrayList<>();
    for (JsonObject line : obj.getJsonArray("lines").getValuesAs(JsonObject.class)) {
      BigDecimal qty = line.getJsonNumber("qty").bigDecimalValue();
      if (qty.signum() <= 0) {
        throw new IllegalArgumentException("a transfer line's qty must be positive");
      }
      variantIds.add(Ids.parse(line.getString("variantId")));
      qtys.add(qty);
    }
    service.applyTransferShippedOnce(
        tenantId, transferOrderId, eventId, CONSUMER, fromStoreId, toStoreId, variantIds, qtys);
  }

  /**
   * A transfer landed: its lines are no longer in transit. The receipt's event id is its own, so
   * the lines are found by the transfer order the shipment and the receipt both name as their
   * aggregate, and only within the receipt's own business. The receipt takes no dedupe mark on its
   * event id (deleting again finds nothing), but it does leave the transfer's "landed" note, which
   * a shipment read afterwards checks; see {@code ReportingRepository#retireSupplyLines}.
   */
  private void handleTransferReceived(JsonObject obj) {
    service.applyTransferReceived(
        Ids.parse(obj.getString("tenantId")), Ids.parse(obj.getString("aggregateId")));
  }

  private static BigDecimal qty(JsonObject obj) {
    return new BigDecimal(obj.get("qty").toString());
  }
}
