package com.storeql.inventory.service;

import com.storeql.events.EventPayload;
import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.BondRelease;
import com.storeql.inventory.domain.Domain.PickWave;
import com.storeql.inventory.domain.Domain.YieldRun;
import com.storeql.inventory.domain.Domain.YieldRunOutput;
import com.storeql.inventory.domain.FoodSafety.CheckRecord;
import com.storeql.inventory.domain.FoodSafety.OverduePoint;
import com.storeql.inventory.domain.Recall.AffectedOrder;
import com.storeql.inventory.domain.Recall.AffectedSale;
import com.storeql.inventory.domain.Recall.Header;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** JSON event payloads for the outbox. Past-tense; topic storeql.inventory.<event>. */
public final class Events {

  private Events() {}

  static String stockReceived(
      UUID tenantId, UUID storeId, UUID variantId, UUID batchId, BigDecimal qty) {
    return EventPayload.base("StockReceived", tenantId, batchId)
        + storeVariant(storeId, variantId)
        + ",\"qty\":"
        + qty.toPlainString()
        + "}";
  }

  static String stockReserved(
      UUID tenantId, UUID storeId, UUID variantId, UUID reservationId, BigDecimal qty) {
    return EventPayload.base("StockReserved", tenantId, reservationId)
        + storeVariant(storeId, variantId)
        + ",\"qty\":"
        + qty.toPlainString()
        + "}";
  }

  /**
   * For consume/release the store/variant aren't known at the service layer (resolved in the repo).
   */
  public static String reservationEvent(String type, UUID tenantId, UUID reservationId) {
    return EventPayload.base(type, tenantId, reservationId)
        + ",\"reservationId\":\""
        + reservationId
        + "\"}";
  }

  /**
   * The {@code StockDeducted} payload.
   *
   * @param tenantId owning tenant
   * @param storeId the store the stock left
   * @param variantId the variant deducted
   * @param reservationId the hold consumed, or the order the deduction is attributed to
   * @param qty the quantity deducted
   * @return the event payload as JSON
   */
  public static String stockDeducted(
      UUID tenantId, UUID storeId, UUID variantId, UUID reservationId, BigDecimal qty) {
    return EventPayload.base("StockDeducted", tenantId, reservationId)
        + storeVariant(storeId, variantId)
        + ",\"qty\":"
        + qty.toPlainString()
        + ",\"reservationId\":\""
        + reservationId
        + "\"}";
  }

  /**
   * A sale drawn from a batch the supplier still owns: purchase-svc owes the supplier {@code qty}
   * at {@code unitCost} (the order's price) for it. One per consignment batch drawn.
   */
  public static String consignmentStockSold(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      UUID supplierId,
      UUID orderId,
      BigDecimal qty,
      BigDecimal unitCost) {
    return EventPayload.base("ConsignmentStockSold", tenantId, batchId)
        + storeVariant(storeId, variantId)
        + ",\"batchId\":\""
        + batchId
        + "\",\"supplierId\":\""
        + supplierId
        + "\",\"orderId\":"
        + (orderId == null ? "null" : "\"" + orderId + "\"")
        + ",\"qty\":"
        + qty.toPlainString()
        + ",\"unitCost\":"
        + (unitCost == null ? "null" : unitCost.toPlainString())
        + "}";
  }

  /**
   * Duty-suspended stock released to home use: the duty on it is now owed to the revenue, and
   * purchase-svc posts it.
   */
  public static String dutyReleased(BondRelease r) {
    return EventPayload.base("DutyReleased", r.tenantId(), r.id())
        + storeVariant(r.storeId(), r.variantId())
        + ",\"releaseId\":\""
        + r.id()
        + "\",\"qty\":"
        + r.qty().toPlainString()
        + ",\"dutyPerUnit\":"
        + r.dutyPerUnit().toPlainString()
        + ",\"dutyAmount\":"
        + r.dutyAmount().toPlainString()
        + ",\"currency\":\""
        + r.currency()
        + "\",\"reference\":"
        + (r.reference() == null ? "null" : "\"" + EventPayload.esc(r.reference()) + "\"")
        + "}";
  }

  /**
   * A breakdown recorded: the primal consumed, each cut made as a batch at its apportioned cost,
   * and the loss against what was expected — for a report that wants yield by store and template.
   */
  public static String yieldRecorded(YieldRun r) {
    StringBuilder sb = new StringBuilder(EventPayload.base("YieldRecorded", r.tenantId(), r.id()));
    sb.append(",\"runId\":\"")
        .append(r.id())
        .append("\",\"storeId\":\"")
        .append(r.storeId())
        .append("\",\"templateId\":\"")
        .append(r.templateId())
        .append("\",\"inputVariantId\":\"")
        .append(r.inputVariantId())
        .append("\",\"inputQty\":")
        .append(r.inputQty().toPlainString())
        .append(",\"inputCost\":")
        .append(r.inputCost() == null ? "null" : r.inputCost().toPlainString())
        .append(",\"outputQty\":")
        .append(r.outputQty().toPlainString())
        .append(",\"lossQty\":")
        .append(r.lossQty().toPlainString())
        .append(",\"expectedLossQty\":")
        .append(r.expectedLossQty().toPlainString())
        .append(",\"lossAtCost\":")
        .append(r.lossAtCost() == null ? "null" : r.lossAtCost().toPlainString())
        .append(",\"outputs\":[");
    boolean first = true;
    for (YieldRunOutput o : r.outputs()) {
      if (!first) sb.append(',');
      first = false;
      sb.append("{\"variantId\":\"")
          .append(o.variantId())
          .append("\",\"qty\":")
          .append(o.qty().toPlainString())
          .append(",\"unitCost\":")
          .append(o.unitCost() == null ? "null" : o.unitCost().toPlainString())
          .append(",\"batchId\":")
          .append(o.batchId() == null ? "null" : "\"" + o.batchId() + "\"")
          .append('}');
    }
    sb.append("]}");
    return sb.toString();
  }

  /**
   * A wave picked at a store: which orders to fulfil for what, as picked. order-svc fulfils each
   * order it names for exactly these quantities, once per event.
   */
  public static String wavePicked(PickWave wave, Map<UUID, Map<UUID, BigDecimal>> picked) {
    StringBuilder sb =
        new StringBuilder(EventPayload.base("WavePicked", wave.tenantId(), wave.id()));
    sb.append(",\"waveId\":\"")
        .append(wave.id())
        .append("\",\"storeId\":\"")
        .append(wave.storeId())
        .append("\",\"orders\":[");
    boolean firstOrder = true;
    for (Map.Entry<UUID, Map<UUID, BigDecimal>> o : picked.entrySet()) {
      if (!firstOrder) sb.append(',');
      firstOrder = false;
      sb.append("{\"orderId\":\"").append(o.getKey()).append("\",\"lines\":[");
      boolean firstLine = true;
      for (Map.Entry<UUID, BigDecimal> l : o.getValue().entrySet()) {
        if (!firstLine) sb.append(',');
        firstLine = false;
        sb.append("{\"variantId\":\"")
            .append(l.getKey())
            .append("\",\"qty\":")
            .append(l.getValue().stripTrailingZeros().toPlainString())
            .append('}');
      }
      sb.append("]}");
    }
    sb.append("]}");
    return sb.toString();
  }

  static String stockAdjusted(UUID tenantId, UUID storeId, UUID variantId, BigDecimal delta) {
    return EventPayload.base("StockAdjusted", tenantId, variantId)
        + storeVariant(storeId, variantId)
        + ",\"delta\":"
        + delta.toPlainString()
        + "}";
  }

  /**
   * The {@code StockBelowThreshold} payload, which notification-svc turns into a shortage alert.
   *
   * @param tenantId owning tenant
   * @param storeId the store that is short
   * @param variantId the variant that is short
   * @param available what is actually on hand
   * @param threshold the level it fell below
   * @return the event payload as JSON
   */
  public static String stockBelowThreshold(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal available, BigDecimal threshold) {
    return EventPayload.base("StockBelowThreshold", tenantId, variantId)
        + storeVariant(storeId, variantId)
        + ",\"available\":"
        + available.toPlainString()
        + ",\"threshold\":"
        + threshold.toPlainString()
        + "}";
  }

  static String replenishmentSuggested(
      UUID tenantId, UUID suggId, UUID storeId, UUID variantId, BigDecimal suggestedQty) {
    return EventPayload.base("ReplenishmentSuggested", tenantId, suggId)
        + storeVariant(storeId, variantId)
        + ",\"suggestedQty\":"
        + suggestedQty.toPlainString()
        + "}";
  }

  static String replenishmentResolved(UUID tenantId, UUID suggId, String status) {
    return EventPayload.base("ReplenishmentResolved", tenantId, suggId)
        + ",\"status\":\""
        + status
        + "\"}";
  }

  static String serialsRegistered(UUID tenantId, UUID batchId, int count) {
    return EventPayload.base("SerialsRegistered", tenantId, batchId) + ",\"count\":" + count + "}";
  }

  static String serialStatusChanged(UUID tenantId, UUID serialId, String status) {
    return EventPayload.base("SerialStatusChanged", tenantId, serialId)
        + ",\"status\":\""
        + status
        + "\"}";
  }

  static String materialStatusChanged(
      UUID tenantId, UUID batchId, String materialStatus, String reason) {
    String r = reason == null ? "null" : "\"" + EventPayload.esc(reason) + "\"";
    return EventPayload.base("MaterialStatusChanged", tenantId, batchId)
        + ",\"materialStatus\":\""
        + materialStatus
        + "\",\"reason\":"
        + r
        + "}";
  }

  /**
   * A transfer left its store: what went, line by line, so a consumer can hold it as stock in
   * transit (depot / DC replenishment). A transfer ships all it asked for, so the line's quantity
   * is the requested one.
   */
  static String transferOrderShipped(
      UUID tenantId,
      UUID orderId,
      UUID fromStoreId,
      UUID toStoreId,
      java.util.List<com.storeql.inventory.domain.Domain.TransferOrderLine> lines) {
    return EventPayload.base("TransferOrderShipped", tenantId, orderId)
        + ",\"fromStoreId\":\""
        + fromStoreId
        + "\",\"toStoreId\":\""
        + toStoreId
        + "\","
        + transferLines(lines, true)
        + "}";
  }

  /** A transfer arrived: what arrived, line by line, closing what was in transit. */
  static String transferOrderReceived(
      UUID tenantId,
      UUID orderId,
      UUID fromStoreId,
      UUID toStoreId,
      java.util.List<com.storeql.inventory.domain.Domain.TransferOrderLine> lines) {
    return EventPayload.base("TransferOrderReceived", tenantId, orderId)
        + ",\"fromStoreId\":\""
        + fromStoreId
        + "\",\"toStoreId\":\""
        + toStoreId
        + "\","
        + transferLines(lines, false)
        + "}";
  }

  /** The {@code lines} member: each line's variant and quantity (shipped, or else requested). */
  private static String transferLines(
      java.util.List<com.storeql.inventory.domain.Domain.TransferOrderLine> lines,
      boolean requested) {
    StringBuilder sb = new StringBuilder("\"lines\":[");
    for (int i = 0; i < lines.size(); i++) {
      var l = lines.get(i);
      java.math.BigDecimal qty =
          requested || l.shippedQty() == null ? l.requestedQty() : l.shippedQty();
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(l.variantId())
          .append("\",\"qty\":")
          .append(qty.stripTrailingZeros().toPlainString())
          .append('}');
    }
    return sb.append(']').toString();
  }

  static String transferOrderCancelled(UUID tenantId, UUID orderId) {
    return EventPayload.base("TransferOrderCancelled", tenantId, orderId) + "}";
  }

  static String moveOrderCompleted(UUID tenantId, UUID orderId, UUID fromStoreId, UUID toStoreId) {
    return EventPayload.base("MoveOrderCompleted", tenantId, orderId)
        + ",\"fromStoreId\":\""
        + fromStoreId
        + "\",\"toStoreId\":\""
        + toStoreId
        + "\"}";
  }

  static String moveOrderCancelled(UUID tenantId, UUID orderId) {
    return EventPayload.base("MoveOrderCancelled", tenantId, orderId) + "}";
  }

  static String cycleCountAdjusted(UUID tenantId, UUID headerId) {
    return EventPayload.base("CycleCountAdjusted", tenantId, headerId) + "}";
  }

  static String physicalInventoryCreated(UUID tenantId, UUID piId, UUID storeId) {
    return EventPayload.base("PhysicalInventoryCreated", tenantId, piId)
        + ",\"storeId\":\""
        + storeId
        + "\"}";
  }

  static String physicalInventoryCompleted(UUID tenantId, UUID piId) {
    return EventPayload.base("PhysicalInventoryCompleted", tenantId, piId) + "}";
  }

  static String costingMethodUpdated(UUID tenantId, UUID storeId, UUID variantId, String method) {
    return EventPayload.base("CostingMethodUpdated", tenantId, null)
        + storeVariant(storeId, variantId)
        + ",\"method\":\""
        + method
        + "\"}";
  }

  static String accountingPeriodOpened(
      UUID tenantId, UUID storeId, String periodName, String periodDate) {
    return EventPayload.base("AccountingPeriodOpened", tenantId, null)
        + ",\"storeId\":\""
        + storeId
        + "\",\"periodName\":\""
        + periodName
        + "\",\"periodDate\":\""
        + periodDate
        + "\"}";
  }

  static String accountingPeriodClosed(UUID tenantId, UUID periodId) {
    return EventPayload.base("AccountingPeriodClosed", tenantId, periodId) + "}";
  }

  static String kanbanTriggered(UUID tenantId, UUID cardId, UUID storeId, UUID variantId) {
    return EventPayload.base("KanbanTriggered", tenantId, cardId)
        + storeVariant(storeId, variantId)
        + "}";
  }

  static String kanbanReplenished(UUID tenantId, UUID cardId, UUID storeId, UUID variantId) {
    return EventPayload.base("KanbanReplenished", tenantId, cardId)
        + storeVariant(storeId, variantId)
        + "}";
  }

  static String ropPlanUpdated(UUID tenantId, UUID storeId, UUID variantId) {
    return EventPayload.base("RopPlanUpdated", tenantId, variantId)
        + storeVariant(storeId, variantId)
        + "}";
  }

  static String ropComputed(UUID tenantId, UUID storeId, int count) {
    return EventPayload.base("RopComputed", tenantId, storeId)
        + ",\"storeId\":\""
        + storeId
        + "\",\"count\":"
        + count
        + "}";
  }

  static String kanbanCreated(
      UUID tenantId, UUID cardId, UUID storeId, UUID variantId, String kanbanType) {
    return EventPayload.base("KanbanCreated", tenantId, cardId)
        + storeVariant(storeId, variantId)
        + ",\"kanbanType\":\""
        + kanbanType
        + "\"}";
  }

  static String lotSplit(UUID tenantId, UUID sourceBatchId, UUID newBatchId, BigDecimal qty) {
    return EventPayload.base("LotSplit", tenantId, sourceBatchId)
        + ",\"newBatchId\":\""
        + newBatchId
        + "\",\"qty\":"
        + qty.toPlainString()
        + "}";
  }

  static String lotMerge(UUID tenantId, UUID sourceBatchId, UUID targetBatchId, BigDecimal qty) {
    return EventPayload.base("LotMerge", tenantId, sourceBatchId)
        + ",\"targetBatchId\":\""
        + targetBatchId
        + "\",\"qty\":"
        + qty.toPlainString()
        + "}";
  }

  /** A failed food-safety check, carrying what the store alert needs to say without a lookup. */
  static String foodSafetyCheckFailed(CheckRecord record, String pointName, String checkTypeCode) {
    return EventPayload.base("FoodSafetyCheckFailed", record.tenantId(), record.id())
        + ",\"storeId\":\""
        + record.storeId()
        + "\",\"pointId\":\""
        + record.pointId()
        + "\",\"pointName\":\""
        + EventPayload.esc(pointName)
        + "\",\"checkTypeCode\":\""
        + checkTypeCode
        + "\",\"kind\":\""
        + record.kind().name()
        + "\",\"value\":"
        + jsonNumber(record.value())
        + ",\"unit\":"
        + (record.unit() == null ? "null" : "\"" + record.unit() + "\"")
        + ",\"minValue\":"
        + jsonNumber(record.limits().min())
        + ",\"maxValue\":"
        + jsonNumber(record.limits().max())
        + "}";
  }

  static String foodSafetyCheckOverdue(OverduePoint point) {
    return EventPayload.base("FoodSafetyCheckOverdue", point.tenantId(), point.pointId())
        + ",\"storeId\":\""
        + point.storeId()
        + "\",\"pointId\":\""
        + point.pointId()
        + "\",\"pointName\":\""
        + EventPayload.esc(point.pointName())
        + "\",\"checkTypeCode\":\""
        + point.checkTypeCode()
        + "\",\"dueSince\":\""
        + point.dueSince()
        + "\"}";
  }

  /**
   * A recall opened, naming the stores whose stock it took off sale so each can be told. Carries
   * the reference and hazard, never the reason or customer notice: those are free text a store
   * reads on the screen, not in an alert.
   */
  static String recallOpened(Header header, Set<UUID> storeIds) {
    return EventPayload.base("RecallOpened", header.tenantId(), header.id())
        + ",\"reference\":\""
        + EventPayload.esc(header.reference())
        + "\",\"kind\":\""
        + header.kind().name()
        + "\",\"hazard\":\""
        + header.hazard().name()
        + "\",\"storeIds\":["
        + storeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","))
        + "]}";
  }

  /**
   * One order that drew on packs a recall covers, with everything order-svc needs to tell the buyer
   * (GPSR arts.35–37): the notice, the remedies, where to turn, and the lines with their lot and
   * date. Built with the JSON API rather than by hand because the notice is free text with line
   * breaks in it, and keyed by the order so a buyer's notices stay in order.
   */
  static String recallSaleAffected(Header h, AffectedOrder order) {
    JsonArrayBuilder remedies = Json.createArrayBuilder();
    h.remedies().stream().map(Enum::name).sorted().forEach(remedies::add);
    JsonArrayBuilder lines = Json.createArrayBuilder();
    for (AffectedSale s : order.lines()) {
      JsonObjectBuilder line =
          Json.createObjectBuilder()
              .add("variantId", s.variantId().toString())
              .add("batchId", s.batchId().toString())
              .add("qty", s.qty())
              .add("match", s.match().name());
      nullable(line, "batchNo", s.batchNo());
      nullable(line, "expiryDate", s.expiryDate() == null ? null : s.expiryDate().toString());
      lines.add(line);
    }
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "RecallSaleAffected")
            .add("tenantId", h.tenantId().toString())
            .add("aggregateId", order.orderId().toString())
            .add("occurredAt", Instant.now().toString())
            .add("recallId", h.id().toString())
            .add("reference", h.reference())
            .add("kind", h.kind().name())
            .add("hazard", h.hazard().name())
            .add("reason", h.reason())
            .add("customerNotice", h.customerNotice())
            .add("remedies", remedies)
            .add("orderId", order.orderId().toString())
            .add("storeId", order.storeId().toString())
            .add("soldAt", order.soldAt().toString())
            .add("lines", lines);
    nullable(b, "singleRemedyReason", h.singleRemedyReason());
    nullable(b, "contactPhone", h.contactPhone());
    nullable(b, "contactUrl", h.contactUrl());
    return b.build().toString();
  }

  private static void nullable(JsonObjectBuilder b, String name, String value) {
    if (value == null) {
      b.addNull(name);
    } else {
      b.add(name, value);
    }
  }

  private static String jsonNumber(BigDecimal value) {
    return value == null ? "null" : value.toPlainString();
  }

  private static String storeVariant(UUID storeId, UUID variantId) {
    return ",\"storeId\":\"" + storeId + "\",\"variantId\":\"" + variantId + "\"";
  }
}
