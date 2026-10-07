package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.config.Jsons;
import com.storeql.inventory.domain.ReturnDisposition;
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
 * Return controls, no-receipt returns. order-svc announces {@code NoReceiptReturnRecorded} (there
 * is no sale for payment-svc to refund against); each line is restocked at the return's store as an
 * anonymous return, placed by its condition (SEALED back on sale, OPENED to INSPECTION, DAMAGED and
 * FAULTY to DAMAGED), a batch of its own, once per line per event.
 *
 * <p>Payload: {@code {eventId, eventType, tenantId, returnId, storeId, items: [{variantId, qty,
 * condition}]}}.
 */
@ApplicationScoped
class NoReceiptReturnHandler {

  private static final Logger LOG = System.getLogger(NoReceiptReturnHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/no-receipt-return";

  @Inject InventoryService service;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID returnId;
    UUID storeId;
    JsonArray items;
    try (var reader = Jsons.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      returnId = Ids.parse(obj.getString("returnId"));
      storeId = Ids.parse(obj.getString("storeId"));
      items = obj.getJsonArray("items");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed no-receipt return event skipped: " + e.getMessage());
      return;
    }
    if (items == null || items.isEmpty()) {
      return;
    }
    for (int i = 0; i < items.size(); i++) {
      try {
        JsonObject line = items.getJsonObject(i);
        UUID variantId = Ids.parse(line.getString("variantId"));
        BigDecimal qty = new BigDecimal(line.get("qty").toString());
        if (qty.signum() <= 0) {
          continue;
        }
        String condition =
            line.containsKey("condition") && !line.isNull("condition")
                ? line.getString("condition")
                : null;
        // No sale to trace, so no cost to carry: the batch is costless, like any anonymous return.
        service.receiveNoReceiptReturnOnce(
            OrderEventHandler.lineDedupeId(eventId, i, CONSUMER_NAME),
            CONSUMER_NAME,
            tenantId,
            storeId,
            variantId,
            qty,
            returnId,
            ReturnDisposition.of(condition, false));
      } catch (ApiException e) {
        if (e.status() >= 500) {
          throw e; // transient — redeliver; completed lines are deduped
        }
        LOG.log(Level.WARNING, "No-receipt return {0} line {1} skipped: {2}", returnId, i, e);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "No-receipt return {0} line {1} malformed: {2}", returnId, i, e);
      }
    }
    LOG.log(
        Level.INFO, "NoReceiptReturnRecorded {0}: processed {1} line(s)", returnId, items.size());
  }
}
