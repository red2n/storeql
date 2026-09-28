package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Business handler for {@code storeql.purchase.goods-received} events. Creates inventory batches
 * from each line in the GRN payload, idempotently. Separated from {@link GoodsReceivedConsumer} so
 * Kafka lifecycle and domain logic each have a single reason to change (SRP).
 *
 * <p>Each line is deduped on a deterministic per-line id derived from the eventId, INSIDE the
 * line's transaction: a redelivered event skips lines that already committed and retries only the
 * rest. Malformed payloads are skipped; write failures propagate so the consumer loop redelivers.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, refId?, lines:[{variantId, qty,
 * batchNo?, costPrice?, expiryDate?}]}}.
 */
@ApplicationScoped
class GoodsReceivedHandler {

  private static final Logger LOG = System.getLogger(GoodsReceivedHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/goods-received";

  @Inject InventoryService service;
  @Inject com.storeql.inventory.service.CrossDockService crossDock;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID refId;
    UUID poId;
    JsonArray lines;
    String ownership;
    UUID supplierId;
    String dutyStatus;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      refId =
          obj.containsKey("refId") && !obj.isNull("refId")
              ? Ids.parse(obj.getString("refId"))
              : null;
      lines = obj.getJsonArray("lines");
      poId =
          obj.containsKey("poId") && !obj.isNull("poId") ? Ids.parse(obj.getString("poId")) : null;
      // Consignment stock ownership: a delivery on consignment stays the supplier's.
      ownership = nullableString(obj, "ownership");
      // Bonded stock: a delivery under bond arrives with its duty suspended.
      dutyStatus = nullableString(obj, "dutyStatus");
      supplierId =
          obj.containsKey("supplierId") && !obj.isNull("supplierId")
              ? Ids.parse(obj.getString("supplierId"))
              : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed GoodsReceived payload skipped: " + e.getMessage());
      return;
    }
    if (lines == null) {
      return;
    }

    // Cross-docking: the lines a warehouse's order still owes its shops go straight across the
    // dock on one transaction; the rest are received as always. The business's own duty-paid
    // stock only — purchase-svc refuses to allocate anything else.
    boolean ownStock =
        (ownership == null || "OWNED".equalsIgnoreCase(ownership))
            && (dutyStatus == null || "DUTY_PAID".equalsIgnoreCase(dutyStatus));
    java.util.Set<UUID> crossing =
        poId != null && ownStock
            ? crossDock.crossingVariants(tenantId, poId, storeId)
            : java.util.Set.of();
    java.util.List<com.storeql.inventory.service.CrossDockService.Delivered> dock =
        new java.util.ArrayList<>();
    int created = 0;
    for (int i = 0; i < lines.size(); i++) {
      JsonObject line = lines.getJsonObject(i);
      UUID variantId = Ids.parse(line.getString("variantId"));
      if (crossing.contains(variantId)) {
        dock.add(
            new com.storeql.inventory.service.CrossDockService.Delivered(
                variantId,
                new BigDecimal(line.get("qty").toString()),
                nullableString(line, "batchNo"),
                line.containsKey("costPrice") && !line.isNull("costPrice")
                    ? new BigDecimal(line.get("costPrice").toString())
                    : null,
                line.containsKey("expiryDate") && !line.isNull("expiryDate")
                    ? LocalDate.parse(line.getString("expiryDate"))
                    : null));
        continue;
      }
      BigDecimal qty = new BigDecimal(line.get("qty").toString());
      String batchNo = nullableString(line, "batchNo");
      BigDecimal cost =
          line.containsKey("costPrice") && !line.isNull("costPrice")
              ? new BigDecimal(line.get("costPrice").toString())
              : null;
      LocalDate expiry =
          line.containsKey("expiryDate") && !line.isNull("expiryDate")
              ? LocalDate.parse(line.getString("expiryDate"))
              : null;
      if (service.receiveOnce(
          lineDedupeId(eventId, i),
          CONSUMER_NAME,
          tenantId,
          storeId,
          variantId,
          qty,
          batchNo,
          cost,
          expiry,
          "GRN",
          refId,
          ownership,
          supplierId,
          dutyStatus)) {
        created++;
      }
    }
    if (!dock.isEmpty()) {
      java.util.List<UUID> transfers =
          crossDock.receive(
              Ids.derived(eventId, CONSUMER_NAME + ":crossdock"),
              CONSUMER_NAME,
              tenantId,
              storeId,
              poId,
              refId,
              dock);
      LOG.log(
          Level.INFO,
          "GoodsReceived {0}: {1} line(s) cross-docked in {2} transfer(s)",
          eventId,
          dock.size(),
          transfers.size());
    }
    if (created > 0) {
      LOG.log(Level.INFO, "GoodsReceived {0}: created {1} batch(es)", eventId, created);
    }
  }

  /** Deterministic per-line dedupe id: stable across redeliveries of the same event. */
  static UUID lineDedupeId(UUID eventId, int lineIndex) {
    return Ids.derived(eventId, CONSUMER_NAME + ":" + lineIndex);
  }

  private static String nullableString(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key) ? obj.getString(key) : null;
  }
}
