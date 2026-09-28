package com.storeql.reporting.messaging;

import com.storeql.ids.Ids;
import com.storeql.reporting.repo.ReportingRepository;
import com.storeql.reporting.service.ReportingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Routes incoming inventory events to the correct projection update. One dispatcher handles all
 * stock-related topics so each handler concern is a single private method (SRP).
 *
 * <p>Idempotency: the stock-delta projections dedupe on eventId atomically with their writes
 * (golden rule #7); transfer events are naturally idempotent. Malformed payloads are skipped; write
 * failures propagate so the consumer loop redelivers instead of losing the event.
 */
@ApplicationScoped
class StockEventDispatcher extends JsonEventDispatcher {

  private static final Logger LOG = System.getLogger(StockEventDispatcher.class.getName());
  private static final String CONSUMER = "reporting-svc/stock-events";

  @Inject ReportingService service;
  @Inject ReportingRepository repo;

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
      case "storeql.inventory.transfer-order-received" ->
          // delete-by-event is naturally idempotent — no dedupe mark needed
          service.applyTransferReceived(eventId);
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

  private void handleTransferShipped(UUID eventId, JsonObject obj) {
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    UUID fromStoreId = Ids.parse(obj.getString("fromStoreId"));
    UUID toStoreId = Ids.parse(obj.getString("toStoreId"));
    // Dedupe BEFORE inserting: supply-line ids are random, so a redelivered event would
    // otherwise add duplicate rows. (Currently the event carries no line details and the
    // lists are empty placeholders — see applyTransferShipped.)
    if (!repo.markProcessedIfNew(eventId, CONSUMER)) {
      return;
    }
    service.applyTransferShipped(tenantId, eventId, fromStoreId, toStoreId, List.of(), List.of());
  }

  private static BigDecimal qty(JsonObject obj) {
    return new BigDecimal(obj.get("qty").toString());
  }
}
