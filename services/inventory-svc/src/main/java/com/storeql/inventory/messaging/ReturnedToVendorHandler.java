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
 * Business handler for {@code storeql.purchase.returned-to-vendor} (07.8): the goods a return sent
 * back leave the store's stock, line by line, FIFO from its available batches, as RTV movements
 * against the return.
 *
 * <p>Each line is deduped on a deterministic per-line id inside its own transaction, so a
 * redelivered event skips the lines that already committed and retries only the rest. A line the
 * store cannot cover — the goods were sold before the return was raised, or purchase-svc could not
 * check — is skipped with a warning naming the shortfall, the same way a sale line is; the return
 * and its debit note stand, and the discrepancy is what a cycle count then finds.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, refId, poId, supplierId,
 * lines:[{variantId, qty}]}}.
 */
@ApplicationScoped
class ReturnedToVendorHandler {

  private static final Logger LOG = System.getLogger(ReturnedToVendorHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/returned-to-vendor";

  @Inject InventoryService service;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID returnId;
    JsonArray lines;
    try (var reader = Jsons.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      returnId =
          obj.containsKey("refId") && !obj.isNull("refId")
              ? Ids.parse(obj.getString("refId"))
              : eventId;
      lines = obj.getJsonArray("lines");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed ReturnedToVendor payload skipped: " + e.getMessage());
      return;
    }
    if (lines == null) {
      return;
    }
    int applied = 0;
    for (int i = 0; i < lines.size(); i++) {
      JsonObject line = lines.getJsonObject(i);
      UUID variantId = Ids.parse(line.getString("variantId"));
      BigDecimal qty = new BigDecimal(line.get("qty").toString());
      try {
        if (service.returnToVendorOnce(
            lineDedupeId(eventId, i), CONSUMER_NAME, tenantId, storeId, variantId, qty, returnId)) {
          applied++;
        }
      } catch (ApiException e) {
        if (e.status() >= 500) {
          throw e;
        }
        LOG.log(
            Level.WARNING,
            "ReturnedToVendor {0} line {1} ({2} x {3}) skipped: {4}",
            eventId,
            i,
            qty,
            variantId,
            e.getMessage());
      }
    }
    if (applied > 0) {
      LOG.log(Level.INFO, "ReturnedToVendor {0}: {1} line(s) left stock", eventId, applied);
    }
  }

  /** Deterministic per-line dedupe id: stable across redeliveries of the same event. */
  static UUID lineDedupeId(UUID eventId, int lineIndex) {
    return Ids.derived(eventId, CONSUMER_NAME + ":" + lineIndex);
  }
}
