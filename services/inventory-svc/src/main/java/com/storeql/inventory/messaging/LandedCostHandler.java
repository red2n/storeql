package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.config.Jsons;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Business handler for {@code storeql.purchase.landed-cost-applied} (07.x): a charge landed on a
 * receipt lifts the unit cost of the batches that receipt created, line by line, by the per-unit
 * share purchase-svc apportioned; a {@code LandedCostReversed} lowers it by the same. Separated
 * from {@link LandedCostConsumer} so Kafka lifecycle and domain logic each have one reason to
 * change.
 *
 * <p>Each line is deduped on an id derived from the event, inside the line's transaction. A receipt
 * inventory has not booked yet is not a skipped line: the service answers with a 5xx, which is
 * rethrown so the consumer redelivers the event until the receipt has landed. A 4xx is logged and
 * skipped, as it would be on any redelivery.
 *
 * <p>Expected payload: {@code {eventId, eventType, tenantId, storeId, refId (the receipt),
 * landedCostId, lines:[{variantId, qty, amount, perUnit}]}}.
 */
@ApplicationScoped
class LandedCostHandler {

  private static final Logger LOG = System.getLogger(LandedCostHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/landed-cost";
  static final String REVERSED = "LandedCostReversed";

  @Inject InventoryService service;

  void handle(String json) {
    UUID eventId;
    String eventType;
    UUID tenantId;
    UUID storeId;
    UUID grId;
    UUID landedCostId;
    JsonArray lines;
    try (var reader = Jsons.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      eventType = obj.getString("eventType", "LandedCostApplied");
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      grId = Ids.parse(obj.getString("refId"));
      landedCostId = Ids.parse(obj.getString("landedCostId"));
      lines = obj.getJsonArray("lines");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed LandedCost payload skipped: " + e.getMessage());
      return;
    }
    if (lines == null) {
      return;
    }
    boolean reversal = REVERSED.equals(eventType);
    String sourceType = reversal ? "LANDED_COST_REVERSAL" : "LANDED_COST";
    int applied = 0;
    for (int i = 0; i < lines.size(); i++) {
      JsonObject line = lines.getJsonObject(i);
      UUID variantId = Ids.parse(line.getString("variantId"));
      BigDecimal perUnit = new BigDecimal(line.get("perUnit").toString());
      BigDecimal amount = new BigDecimal(line.get("amount").toString());
      if (reversal) {
        perUnit = perUnit.negate();
        amount = amount.negate();
      }
      try {
        if (service.revalueReceiptOnce(
            lineDedupeId(eventId, i),
            CONSUMER_NAME,
            tenantId,
            storeId,
            variantId,
            grId,
            perUnit,
            amount,
            sourceType,
            landedCostId)) {
          applied++;
        }
      } catch (ApiException e) {
        if (e.status() >= 500) {
          throw e;
        }
        LOG.log(
            Level.WARNING,
            "{0} {1} line {2} ({3}) skipped: {4}",
            eventType,
            eventId,
            i,
            variantId,
            e.getMessage());
      }
    }
    if (applied > 0) {
      LOG.log(Level.INFO, "{0} {1}: {2} line(s) revalued", eventType, eventId, applied);
    }
  }

  static UUID lineDedupeId(UUID eventId, int lineIndex) {
    return Ids.derived(eventId, CONSUMER_NAME + ":" + lineIndex);
  }
}
