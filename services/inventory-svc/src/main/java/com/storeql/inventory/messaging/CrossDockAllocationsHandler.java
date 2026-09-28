package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.CrossDock.Expected;
import com.storeql.inventory.service.CrossDockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * purchase-svc's cross-dock allocation snapshots (cross-docking): what each shop is owed on a
 * warehouse's purchase order, replaced whole per order, once per event. A malformed snapshot is
 * skipped; a failure to write it propagates so the loop redelivers.
 */
@ApplicationScoped
class CrossDockAllocationsHandler {

  private static final Logger LOG = System.getLogger(CrossDockAllocationsHandler.class.getName());
  static final String CONSUMER = "inventory-svc/crossdock-allocations";

  @Inject CrossDockService crossDock;

  void handle(String value) {
    UUID eventId;
    UUID tenantId;
    UUID poId;
    UUID warehouseId;
    List<Expected> rows = new ArrayList<>();
    try (var reader = Json.createReader(new StringReader(value))) {
      JsonObject o = reader.readObject();
      eventId = Ids.parse(o.getString("eventId"));
      tenantId = Ids.parse(o.getString("tenantId"));
      poId = Ids.parse(o.getString("poId"));
      warehouseId = Ids.parse(o.getString("warehouseId"));
      JsonArray allocations = o.getJsonArray("allocations");
      for (int i = 0; i < allocations.size(); i++) {
        JsonObject a = allocations.getJsonObject(i);
        rows.add(
            new Expected(
                Ids.parse(a.getString("storeId")),
                Ids.parse(a.getString("variantId")),
                new BigDecimal(a.get("qty").toString())));
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed CrossDockAllocationsSet skipped: " + e.getMessage());
      return;
    }
    if (crossDock.allocationsSet(eventId, CONSUMER, tenantId, poId, warehouseId, rows)) {
      LOG.log(
          Level.INFO, "CrossDockAllocationsSet {0}: {1} allocation(s) now owed", poId, rows.size());
    }
  }
}
