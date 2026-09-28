package com.storeql.reporting.messaging;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.util.UUID;

/**
 * Routes product-svc's catalogue events to the projection sales by category reads against (19.x):
 * {@code ProductCategorised} says where a product sits (its category path, leaf first) and which
 * variants are its; {@code VariantCreated} ties a variant created later to its product. Both are
 * idempotent upserts, and a categorisation is applied only if it is at least as new as the word
 * already held, so the order events arrive in cannot move a product back.
 */
@ApplicationScoped
class CatalogueEventDispatcher extends JsonEventDispatcher {

  @Inject ReportingService service;

  CatalogueEventDispatcher() {
    super("catalogue");
  }

  @Override
  protected boolean route(String topic, JsonObject obj) {
    switch (topic) {
      case "storeql.catalog.product-categorised" -> handleCategorised(obj);
      case "storeql.catalog.variant-created" -> handleVariantCreated(obj);
      default -> {
        return false;
      }
    }
    return true;
  }

  private void handleCategorised(JsonObject obj) {
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    UUID productId =
        obj.containsKey("productId")
            ? Ids.parse(obj.getString("productId"))
            : Ids.parse(obj.getString("aggregateId"));
    service.applyProductCategorised(
        tenantId, productId, uuids(obj, "categoryPath"), uuids(obj, "variantIds"), occurredAt(obj));
  }

  private void handleVariantCreated(JsonObject obj) {
    UUID tenantId = Ids.parse(obj.getString("tenantId"));
    // product-svc names the variant as the event's aggregate; there is no variantId field.
    UUID variantId = Ids.parse(obj.getString("aggregateId"));
    UUID productId = Ids.parse(obj.getString("productId"));
    service.applyVariantCreated(tenantId, variantId, productId);
  }
}
