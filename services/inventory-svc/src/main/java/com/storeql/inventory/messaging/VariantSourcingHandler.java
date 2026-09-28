package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * {@code VariantSourcingChanged} from purchase-svc (consignment and dropship stock ownership): a
 * variant is now fulfilled from a supplier per order (DROPSHIP), or from the shelf again (STOCK).
 * Idempotent on the event id; the latest word wins.
 */
@ApplicationScoped
public class VariantSourcingHandler {

  private static final Logger LOG = System.getLogger(VariantSourcingHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/variant-sourcing";

  @Inject InventoryService service;

  public void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID variantId;
    String fulfilment;
    UUID supplierId;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"VariantSourcingChanged".equals(obj.getString("eventType", ""))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      variantId = Ids.parse(obj.getString("variantId"));
      fulfilment = obj.getString("fulfilment");
      supplierId =
          obj.containsKey("supplierId") && !obj.isNull("supplierId")
              ? Ids.parse(obj.getString("supplierId"))
              : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed VariantSourcingChanged payload skipped: " + e.getMessage());
      return;
    }
    if (service.recordSourcingOnce(
        eventId, CONSUMER_NAME, tenantId, variantId, fulfilment, supplierId)) {
      LOG.log(
          Level.INFO,
          "variant {0} of tenant {1} is now sourced {2}",
          variantId,
          tenantId,
          fulfilment);
    }
  }
}
