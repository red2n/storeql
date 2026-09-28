package com.storeql.purchase.messaging;

import com.storeql.ids.Ids;
import com.storeql.purchase.service.ConsignmentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * {@code ConsignmentStockSold} from inventory-svc: a sale drawn from a batch the supplier still
 * owns, which this service now owes the supplier for. Idempotent on the event id.
 */
@ApplicationScoped
public class ConsignmentEventHandler {

  private static final Logger LOG = System.getLogger(ConsignmentEventHandler.class.getName());

  @Inject ConsignmentService service;

  public void stockSold(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      if (!"ConsignmentStockSold".equals(o.getString("eventType", ""))) return;
      String occurredAt = o.getString("occurredAt", null);
      LocalDate soldOn =
          occurredAt == null || occurredAt.isBlank()
              ? LocalDate.now(ZoneOffset.UTC)
              : Instant.parse(occurredAt).atZone(ZoneOffset.UTC).toLocalDate();
      service.recordSale(
          Ids.parse(o.getString("eventId")),
          Ids.parse(o.getString("tenantId")),
          EventJson.optUuid(o, "storeId"),
          Ids.parse(o.getString("supplierId")),
          Ids.parse(o.getString("variantId")),
          EventJson.optUuid(o, "batchId"),
          EventJson.optUuid(o, "orderId"),
          o.getJsonNumber("qty").bigDecimalValue(),
          EventJson.optNumber(o, "unitCost"),
          soldOn);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "ConsignmentStockSold not recorded, malformed: " + e.getMessage());
    }
  }
}
