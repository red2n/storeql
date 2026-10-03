package com.storeql.order.messaging;

import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.repo.OrderRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Gap #50 — SIM→POS direction. Updates the pos_stock_positions projection for each incoming
 * inventory event. Idempotent: the eventId dedupe and the additive upsert commit in one transaction
 * (see {@code upsertStockPositionOnce}). Malformed payloads are skipped; write failures propagate
 * so the consumer loop redelivers instead of losing the event.
 *
 * <p>Delta rules:
 *
 * <ul>
 *   <li>StockReceived → +qty (new stock arrived)
 *   <li>StockDeducted → -qty (stock consumed by sale)
 *   <li>StockAdjusted → +delta (delta is already signed by inventory-svc)
 * </ul>
 */
@ApplicationScoped
class InventoryEventHandler {

  private static final Logger LOG = System.getLogger(InventoryEventHandler.class.getName());
  static final String CONSUMER_NAME = "order-svc/inventory-sync";

  @Inject OrderRepository repo;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID variantId;
    BigDecimal delta;
    try {
      JsonObject obj;
      try (JsonReader reader = Json.createReader(new StringReader(json))) {
        obj = reader.readObject();
      }
      String eventType = stringOrNull(obj, "eventType");
      String eventIdStr = stringOrNull(obj, "eventId");
      String tenantIdStr = stringOrNull(obj, "tenantId");
      String storeIdStr = stringOrNull(obj, "storeId");
      String variantIdStr = stringOrNull(obj, "variantId");
      if (eventIdStr == null || tenantIdStr == null || storeIdStr == null || variantIdStr == null) {
        return;
      }

      eventId = Ids.parse(eventIdStr);
      tenantId = Ids.parse(tenantIdStr);
      storeId = Ids.parse(storeIdStr);
      variantId = Ids.parse(variantIdStr);

      if ("StockReceived".equals(eventType)) {
        if (!obj.containsKey("qty") || obj.isNull("qty")) return;
        delta = obj.getJsonNumber("qty").bigDecimalValue();
      } else if ("StockDeducted".equals(eventType)) {
        if (!obj.containsKey("qty") || obj.isNull("qty")) return;
        delta = obj.getJsonNumber("qty").bigDecimalValue().negate();
      } else if ("StockAdjusted".equals(eventType)) {
        if (!obj.containsKey("delta") || obj.isNull("delta")) return;
        delta = obj.getJsonNumber("delta").bigDecimalValue();
      } else {
        return;
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed inventory event skipped: " + e.getMessage());
      return;
    }

    boolean processed =
        repo.upsertStockPositionOnce(eventId, CONSUMER_NAME, tenantId, storeId, variantId, delta);
    if (processed) {
      LOG.log(Level.DEBUG, "StockPosition updated {0}/{1} delta={2}", storeId, variantId, delta);
    }
  }

  private static String stringOrNull(JsonObject o, String key) {
    return o.containsKey(key) && !o.isNull(key) ? o.getString(key) : null;
  }
}
