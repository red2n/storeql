package com.storeql.inventory.mapper;

import com.storeql.inventory.domain.Domain;
import com.storeql.inventory.domain.Domain.AbcAssignment;
import com.storeql.inventory.domain.Domain.AbcCompileRun;
import com.storeql.inventory.domain.Domain.AccountingPeriod;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.CostingMethod;
import com.storeql.inventory.domain.Domain.CycleCountHeader;
import com.storeql.inventory.domain.Domain.CycleCountLine;
import com.storeql.inventory.domain.Domain.DeadStockRow;
import com.storeql.inventory.domain.Domain.DemandBucket;
import com.storeql.inventory.domain.Domain.DemandForecast;
import com.storeql.inventory.domain.Domain.FreshProfile;
import com.storeql.inventory.domain.Domain.KanbanCard;
import com.storeql.inventory.domain.Domain.Level;
import com.storeql.inventory.domain.Domain.LevelSummary;
import com.storeql.inventory.domain.Domain.LotAction;
import com.storeql.inventory.domain.Domain.LotGenealogyLink;
import com.storeql.inventory.domain.Domain.LotUomConversion;
import com.storeql.inventory.domain.Domain.LowStockRow;
import com.storeql.inventory.domain.Domain.MoveOrder;
import com.storeql.inventory.domain.Domain.MoveOrderLine;
import com.storeql.inventory.domain.Domain.Movement;
import com.storeql.inventory.domain.Domain.ParLevelConfig;
import com.storeql.inventory.domain.Domain.PhysicalInventory;
import com.storeql.inventory.domain.Domain.PhysicalInventoryTag;
import com.storeql.inventory.domain.Domain.PickingRule;
import com.storeql.inventory.domain.Domain.PickingRuleAssignment;
import com.storeql.inventory.domain.Domain.PickingRuleZonePriority;
import com.storeql.inventory.domain.Domain.ReasonCode;
import com.storeql.inventory.domain.Domain.ReorderPointPlan;
import com.storeql.inventory.domain.Domain.Reservation;
import com.storeql.inventory.domain.Domain.SafetyStockParams;
import com.storeql.inventory.domain.Domain.SerialMovement;
import com.storeql.inventory.domain.Domain.SerialNumber;
import com.storeql.inventory.domain.Domain.ShrinkageRow;
import com.storeql.inventory.domain.Domain.StockTurnReport;
import com.storeql.inventory.domain.Domain.StockTurnRow;
import com.storeql.inventory.domain.Domain.Suggestion;
import com.storeql.inventory.domain.Domain.Threshold;
import com.storeql.inventory.domain.Domain.TransactionSourceType;
import com.storeql.inventory.domain.Domain.TransferOrder;
import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.inventory.domain.Domain.ValuationRow;
import com.storeql.inventory.domain.Domain.ZoneGlMapping;
import com.storeql.inventory.domain.Forecasting;
import com.storeql.inventory.dto.Dtos;
import com.storeql.inventory.dto.Dtos.AbcAssignmentResponse;
import com.storeql.inventory.dto.Dtos.AbcCompileRunResponse;
import com.storeql.inventory.dto.Dtos.AccountingPeriodResponse;
import com.storeql.inventory.dto.Dtos.BatchResponse;
import com.storeql.inventory.dto.Dtos.CostingMethodResponse;
import com.storeql.inventory.dto.Dtos.CycleCountHeaderResponse;
import com.storeql.inventory.dto.Dtos.CycleCountLineResponse;
import com.storeql.inventory.dto.Dtos.DeadStockRowResponse;
import com.storeql.inventory.dto.Dtos.DemandBucketResponse;
import com.storeql.inventory.dto.Dtos.ExpiringBatchResponse;
import com.storeql.inventory.dto.Dtos.ForecastPointResponse;
import com.storeql.inventory.dto.Dtos.ForecastResponse;
import com.storeql.inventory.dto.Dtos.ForecastRunResponse;
import com.storeql.inventory.dto.Dtos.KanbanCardResponse;
import com.storeql.inventory.dto.Dtos.LevelResponse;
import com.storeql.inventory.dto.Dtos.LevelSummaryResponse;
import com.storeql.inventory.dto.Dtos.LotActionResponse;
import com.storeql.inventory.dto.Dtos.LotGenealogyLinkResponse;
import com.storeql.inventory.dto.Dtos.LotUomConversionResponse;
import com.storeql.inventory.dto.Dtos.LowStockRowResponse;
import com.storeql.inventory.dto.Dtos.MoveOrderLineResponse;
import com.storeql.inventory.dto.Dtos.MoveOrderResponse;
import com.storeql.inventory.dto.Dtos.MovementResponse;
import com.storeql.inventory.dto.Dtos.ParLevelResponse;
import com.storeql.inventory.dto.Dtos.PhysicalInventoryResponse;
import com.storeql.inventory.dto.Dtos.PhysicalInventoryTagResponse;
import com.storeql.inventory.dto.Dtos.PickingRuleAssignmentResponse;
import com.storeql.inventory.dto.Dtos.PickingRuleResponse;
import com.storeql.inventory.dto.Dtos.PickingRuleZonePriorityResponse;
import com.storeql.inventory.dto.Dtos.ReasonCodeResponse;
import com.storeql.inventory.dto.Dtos.ReservationResponse;
import com.storeql.inventory.dto.Dtos.RopPlanResponse;
import com.storeql.inventory.dto.Dtos.SafetyStockParamsResponse;
import com.storeql.inventory.dto.Dtos.SerialMovementResponse;
import com.storeql.inventory.dto.Dtos.SerialNumberResponse;
import com.storeql.inventory.dto.Dtos.ShrinkageRowResponse;
import com.storeql.inventory.dto.Dtos.SourceTypeResponse;
import com.storeql.inventory.dto.Dtos.StockTurnReportResponse;
import com.storeql.inventory.dto.Dtos.StockTurnRowResponse;
import com.storeql.inventory.dto.Dtos.SuggestionResponse;
import com.storeql.inventory.dto.Dtos.ThresholdResponse;
import com.storeql.inventory.dto.Dtos.TransferOrderLineResponse;
import com.storeql.inventory.dto.Dtos.TransferOrderResponse;
import com.storeql.inventory.dto.Dtos.ValuationRowResponse;
import com.storeql.inventory.dto.Dtos.ZoneGlMappingResponse;
import com.storeql.inventory.service.ForecastService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Maps inventory-svc domain records to the DTOs served over HTTP.
 *
 * <p>Domain records never cross the HTTP boundary; quantities and money stay {@code BigDecimal}
 * throughout, and ids are rendered as strings so the JSON contract stays stable.
 */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a level summary to its wire form.
   *
   * @param s the level summary to convert
   * @return its API representation
   */
  public static LevelSummaryResponse toLevelSummary(LevelSummary s) {
    return new LevelSummaryResponse(s.skuCount(), s.lowStockCount());
  }

  /**
   * Converts a level to its wire form.
   *
   * @param l the level to convert
   * @return its API representation
   */
  public static LevelResponse toLevel(Level l) {
    return new LevelResponse(
        l.storeId().toString(),
        l.variantId().toString(),
        l.onHand(),
        l.reserved(),
        l.available(),
        l.inBond());
  }

  /**
   * Converts a batch to its wire form.
   *
   * @param b the batch to convert
   * @return its API representation
   */
  public static BatchResponse toBatch(Batch b) {
    return new BatchResponse(
        b.id().toString(),
        b.storeId().toString(),
        b.variantId().toString(),
        b.batchNo(),
        b.receivedQty(),
        b.remainingQty(),
        b.costPrice(),
        b.expiryDate() == null ? null : b.expiryDate().toString(),
        ts(b.createdAt()),
        b.status(),
        b.materialStatus(),
        b.materialStatusReason(),
        b.grade(),
        b.zoneId() == null ? null : b.zoneId().toString(),
        b.ownership(),
        b.ownerSupplierId() == null ? null : b.ownerSupplierId().toString(),
        b.dutyStatus());
  }

  public static Dtos.BondApprovalResponse toDto(Domain.BondApproval a) {
    return new Dtos.BondApprovalResponse(
        a.storeId().toString(),
        a.approvalNumber(),
        a.regime(),
        a.active(),
        ts(a.createdAt()),
        a.endedAt() == null ? null : a.endedAt().toString());
  }

  public static Dtos.DutyRateResponse toDto(Domain.ExciseDutyRate r) {
    return new Dtos.DutyRateResponse(
        r.variantId().toString(), r.dutyPerUnit(), r.currency(), r.note(), ts(r.updatedAt()));
  }

  public static Dtos.BondReleaseResponse toDto(Domain.BondRelease r) {
    return new Dtos.BondReleaseResponse(
        r.id().toString(),
        r.storeId().toString(),
        r.variantId().toString(),
        r.qty(),
        r.dutyPerUnit(),
        r.dutyAmount(),
        r.currency(),
        r.reference(),
        ts(r.releasedAt()));
  }

  // ── Fresh yield, preparation and butchery loss ─────────────────────────────

  public static Dtos.YieldOutputSpecResponse toDto(Domain.YieldOutputSpec o) {
    return new Dtos.YieldOutputSpecResponse(
        o.variantId().toString(), o.expectedPct(), o.costShare(), o.shelfLifeDays());
  }

  public static Dtos.YieldTemplateResponse toDto(Domain.YieldTemplate t) {
    return new Dtos.YieldTemplateResponse(
        t.id().toString(),
        t.name(),
        t.inputVariantId().toString(),
        t.unit(),
        t.notes(),
        t.active(),
        t.expectedLossPct(),
        t.outputs().stream().map(Mappers::toDto).toList(),
        t.createdAt().toString());
  }

  public static Dtos.YieldRunOutputResponse toDto(Domain.YieldRunOutput o) {
    return new Dtos.YieldRunOutputResponse(
        o.variantId().toString(),
        o.qty(),
        o.expectedQty(),
        o.unitCost(),
        o.batchId() == null ? null : o.batchId().toString());
  }

  public static Dtos.YieldRunResponse toDto(Domain.YieldRun r) {
    return new Dtos.YieldRunResponse(
        r.id().toString(),
        r.storeId().toString(),
        r.templateId().toString(),
        r.templateName(),
        r.inputVariantId().toString(),
        r.inputQty(),
        r.inputCost(),
        r.outputQty(),
        r.lossQty(),
        r.lossPct(),
        r.expectedLossQty(),
        r.lossVariance(),
        r.lossAtCost(),
        r.reference(),
        r.notes(),
        r.recordedAt().toString(),
        r.outputs().stream().map(Mappers::toDto).toList());
  }

  public static Dtos.YieldTotalsResponse toDto(Domain.YieldTotals t) {
    return new Dtos.YieldTotalsResponse(
        t.runs(), t.inputQty(), t.outputQty(), t.lossQty(), t.expectedLossQty(), t.lossAtCost());
  }

  public static Dtos.BondStockResponse toDto(Domain.BondStock s) {
    return new Dtos.BondStockResponse(
        s.storeId().toString(),
        s.variantId().toString(),
        s.qty(),
        s.dutyPerUnit(),
        s.dutyPotential());
  }

  /**
   * Converts a reservation to its wire form.
   *
   * @param r the reservation to convert
   * @return its API representation
   */
  public static ReservationResponse toReservation(Reservation r) {
    return new ReservationResponse(
        r.id().toString(),
        r.storeId().toString(),
        r.variantId().toString(),
        r.qty(),
        r.orderId() == null ? null : r.orderId().toString(),
        r.status(),
        r.expiresAt() == null ? null : r.expiresAt().toString(),
        ts(r.createdAt()),
        r.fulfilment());
  }

  /**
   * Converts a low stock row to its wire form.
   *
   * @param r the low stock row to convert
   * @return its API representation
   */
  public static LowStockRowResponse toLowStockRow(LowStockRow r) {
    return new LowStockRowResponse(
        r.storeId(), r.variantId(), r.signal(), r.reorderLevel(), r.availableQty(), r.shortfall());
  }

  /**
   * Converts a valuation row to its wire form.
   *
   * @param r the valuation row to convert
   * @return its API representation
   */
  public static ValuationRowResponse toValuationRow(ValuationRow r) {
    return new ValuationRowResponse(
        r.groupKey(),
        r.method(),
        r.onHandQty(),
        r.unvaluedQty(),
        r.value(),
        r.consignmentQty(),
        r.consignmentValue(),
        r.dutySuspendedQty(),
        r.dutyPotential());
  }

  /**
   * Converts a shrinkage row to its wire form.
   *
   * @param r the shrinkage row to convert
   * @return its API representation
   */
  public static ShrinkageRowResponse toShrinkageRow(ShrinkageRow r) {
    return new ShrinkageRowResponse(
        r.groupKey(), r.qtyWrittenOff(), r.qtyFound(), r.netQty(), r.movements());
  }

  /**
   * Converts a stock turn row to its wire form.
   *
   * @param r the stock turn row to convert
   * @return its API representation
   */
  public static StockTurnRowResponse toStockTurnRow(StockTurnRow r) {
    return new StockTurnRowResponse(
        r.groupKey(),
        r.cogs(),
        r.uncostedSaleQty(),
        r.openingValue(),
        r.closingValue(),
        r.averageValue(),
        r.turnoverRatio(),
        r.daysOnHand());
  }

  /**
   * Converts a stock turn report to its wire form.
   *
   * @param report the stock turn report to convert
   * @return its API representation
   */
  public static StockTurnReportResponse toStockTurnReport(StockTurnReport report) {
    return new StockTurnReportResponse(
        report.rows().stream().map(Mappers::toStockTurnRow).toList(),
        report.historyComplete(),
        report.windowDays());
  }

  /**
   * Converts a dead stock row to its wire form.
   *
   * @param r the dead stock row to convert
   * @return its API representation
   */
  public static DeadStockRowResponse toDeadStockRow(DeadStockRow r) {
    return new DeadStockRowResponse(
        r.groupKey(),
        r.onHandQty(),
        r.value(),
        r.uncostedQty(),
        r.daysSinceLastSale(),
        r.neverSold());
  }

  /**
   * Converts a movement to its wire form.
   *
   * @param m the movement to convert
   * @return its API representation
   */
  public static MovementResponse toMovement(Movement m) {
    return new MovementResponse(
        m.id().toString(),
        m.storeId().toString(),
        m.variantId().toString(),
        m.batchId() == null ? null : m.batchId().toString(),
        m.type(),
        m.qty(),
        m.refType(),
        m.refId() == null ? null : m.refId().toString(),
        m.reasonCode(),
        m.actorId() == null ? null : m.actorId().toString(),
        ts(m.createdAt()));
  }

  /**
   * Converts a threshold to its wire form.
   *
   * @param t the threshold to convert
   * @return its API representation
   */
  public static ThresholdResponse toThreshold(Threshold t) {
    return new ThresholdResponse(
        t.id().toString(),
        t.storeId().toString(),
        t.variantId().toString(),
        t.threshold(),
        t.maxQty());
  }

  /**
   * Converts a suggestion to its wire form.
   *
   * @param s the suggestion to convert
   * @return its API representation
   */
  public static SuggestionResponse toSuggestion(Suggestion s) {
    return new SuggestionResponse(
        s.id().toString(),
        s.storeId().toString(),
        s.variantId().toString(),
        s.availableQty(),
        s.minQty(),
        s.maxQty(),
        s.suggestedQty(),
        s.status(),
        ts(s.createdAt()),
        ts(s.resolvedAt()));
  }

  /**
   * Converts a serial to its wire form.
   *
   * @param s the serial to convert
   * @return its API representation
   */
  public static SerialNumberResponse toSerial(SerialNumber s) {
    return new SerialNumberResponse(
        s.id().toString(),
        s.storeId().toString(),
        s.variantId().toString(),
        s.batchId() == null ? null : s.batchId().toString(),
        s.serialNo(),
        s.status(),
        ts(s.receivedAt()),
        ts(s.soldAt()));
  }

  /**
   * Converts a serial movement to its wire form.
   *
   * @param m the serial movement to convert
   * @return its API representation
   */
  public static SerialMovementResponse toSerialMovement(SerialMovement m) {
    return new SerialMovementResponse(
        m.id().toString(),
        m.serialId().toString(),
        m.fromStatus(),
        m.toStatus(),
        m.refType(),
        m.refId() == null ? null : m.refId().toString(),
        ts(m.createdAt()));
  }

  /**
   * Converts a demand bucket to its wire form.
   *
   * @param b the demand bucket to convert
   * @return its API representation
   */
  public static DemandBucketResponse toDemandBucket(DemandBucket b) {
    return new DemandBucketResponse(
        b.storeId().toString(),
        b.variantId().toString(),
        b.bucketDate().toString(),
        b.bucketType(),
        b.demandQty(),
        b.movementCount(),
        ts(b.computedAt()));
  }

  /**
   * Converts a transfer order line to its wire form.
   *
   * @param l the transfer order line to convert
   * @return its API representation
   */
  public static TransferOrderLineResponse toTransferOrderLine(TransferOrderLine l) {
    return new TransferOrderLineResponse(
        l.id().toString(),
        l.variantId().toString(),
        l.requestedQty(),
        l.shippedQty(),
        l.receivedQty(),
        l.reason());
  }

  /**
   * Converts a transfer order to its wire form.
   *
   * @param o the record to persist
   * @param lines the lines to store
   * @return its API representation
   */
  public static TransferOrderResponse toTransferOrder(
      TransferOrder o, List<TransferOrderLine> lines) {
    return new TransferOrderResponse(
        o.id().toString(),
        o.fromStoreId().toString(),
        o.toStoreId().toString(),
        o.transferType(),
        o.status(),
        o.notes(),
        ts(o.createdAt()),
        ts(o.shippedAt()),
        ts(o.receivedAt()),
        o.source(),
        o.proposalRunId() == null ? null : o.proposalRunId().toString(),
        o.purchaseOrderId() == null ? null : o.purchaseOrderId().toString(),
        o.goodsReceiptId() == null ? null : o.goodsReceiptId().toString(),
        lines.stream().map(Mappers::toTransferOrderLine).toList());
  }

  /**
   * Converts a move order line to its wire form.
   *
   * @param l the move order line to convert
   * @return its API representation
   */
  public static MoveOrderLineResponse toMoveOrderLine(MoveOrderLine l) {
    return new MoveOrderLineResponse(
        l.id().toString(), l.variantId().toString(), l.requestedQty(), l.pickedQty());
  }

  /**
   * Converts a move order to its wire form.
   *
   * @param o the record to persist
   * @param lines the lines to store
   * @return its API representation
   */
  public static MoveOrderResponse toMoveOrder(MoveOrder o, List<MoveOrderLine> lines) {
    return new MoveOrderResponse(
        o.id().toString(),
        o.fromStoreId().toString(),
        o.toStoreId().toString(),
        o.fromZone(),
        o.toZone(),
        o.notes(),
        o.status(),
        ts(o.createdAt()),
        ts(o.pickedAt()),
        lines.stream().map(Mappers::toMoveOrderLine).toList());
  }

  /**
   * Converts a lot link to its wire form.
   *
   * @param l the lot link to convert
   * @return its API representation
   */
  public static LotGenealogyLinkResponse toLotLink(LotGenealogyLink l) {
    return new LotGenealogyLinkResponse(
        l.id().toString(),
        l.parentBatchId().toString(),
        l.childBatchId().toString(),
        l.qty(),
        l.relationType(),
        l.notes(),
        ts(l.createdAt()));
  }

  /**
   * Converts a cycle count line to its wire form.
   *
   * @param l the cycle count line to convert
   * @return its API representation
   */
  public static CycleCountLineResponse toCycleCountLine(CycleCountLine l) {
    return new CycleCountLineResponse(
        l.id().toString(),
        l.variantId().toString(),
        l.systemQty(),
        l.countedQty(),
        l.variance(),
        l.variancePct(),
        l.status(),
        ts(l.countedAt()));
  }

  /**
   * Converts a cycle count header to its wire form.
   *
   * @param h the record to persist
   * @param lines the lines to store
   * @return its API representation
   */
  public static CycleCountHeaderResponse toCycleCountHeader(
      CycleCountHeader h, List<CycleCountLine> lines) {
    int counted = (int) lines.stream().filter(l -> !CycleCountLine.OPEN.equals(l.status())).count();
    int approved =
        (int)
            lines.stream()
                .filter(
                    l ->
                        CycleCountLine.APPROVED.equals(l.status())
                            || CycleCountLine.ADJUSTED.equals(l.status()))
                .count();
    return new CycleCountHeaderResponse(
        h.id().toString(),
        h.storeId().toString(),
        h.name(),
        h.abcClasses(),
        h.tolerancePct(),
        h.status(),
        lines.size(),
        counted,
        approved,
        ts(h.createdAt()),
        ts(h.completedAt()));
  }

  /**
   * Converts an abc compile run to its wire form.
   *
   * @param r the abc compile run to convert
   * @return its API representation
   */
  public static AbcCompileRunResponse toAbcCompileRun(AbcCompileRun r) {
    return new AbcCompileRunResponse(
        r.id().toString(),
        r.storeId() == null ? null : r.storeId().toString(),
        r.criteria(),
        r.thresholdA(),
        r.thresholdAB(),
        r.itemsCompiled(),
        ts(r.compiledAt()));
  }

  /**
   * Converts an abc assignment to its wire form.
   *
   * @param a the abc assignment to convert
   * @return its API representation
   */
  public static AbcAssignmentResponse toAbcAssignment(AbcAssignment a) {
    return new AbcAssignmentResponse(
        a.id().toString(),
        a.storeId().toString(),
        a.variantId().toString(),
        a.runId().toString(),
        a.abcClass(),
        a.score(),
        a.rank(),
        ts(a.assignedAt()));
  }

  /**
   * Converts a safety stock params to its wire form.
   *
   * @param p the safety stock params to convert
   * @return its API representation
   */
  public static SafetyStockParamsResponse toSafetyStockParams(SafetyStockParams p) {
    return new SafetyStockParamsResponse(
        p.id().toString(),
        p.storeId().toString(),
        p.variantId().toString(),
        p.method(),
        p.leadTimeDays(),
        p.serviceLevelPct(),
        p.userDefinedPct(),
        p.safetyStockQty(),
        ts(p.computedAt()),
        ts(p.createdAt()));
  }

  /**
   * Converts a tag to its wire form.
   *
   * @param t the tag to convert
   * @return its API representation
   */
  public static PhysicalInventoryTagResponse toTag(PhysicalInventoryTag t) {
    return new PhysicalInventoryTagResponse(
        t.id().toString(),
        t.variantId().toString(),
        t.zoneId() == null ? null : t.zoneId().toString(),
        t.systemQty(),
        t.countedQty(),
        t.adjustmentQty(),
        t.status(),
        ts(t.countedAt()));
  }

  /**
   * Converts a physical inventory to its wire form.
   *
   * @param pi the record to persist
   * @param tags the count tags
   * @return its API representation
   */
  public static PhysicalInventoryResponse toPhysicalInventory(
      PhysicalInventory pi, List<PhysicalInventoryTag> tags) {
    return new PhysicalInventoryResponse(
        pi.id().toString(),
        pi.storeId().toString(),
        pi.status(),
        pi.notes(),
        ts(pi.startedAt()),
        ts(pi.completedAt()),
        tags.stream().map(Mappers::toTag).toList());
  }

  /**
   * Converts a rop plan to its wire form.
   *
   * @param p the rop plan to convert
   * @return its API representation
   */
  public static RopPlanResponse toRopPlan(ReorderPointPlan p) {
    return new RopPlanResponse(
        p.id().toString(),
        p.storeId().toString(),
        p.variantId().toString(),
        p.leadTimeDays(),
        p.orderingCost(),
        p.holdingCostPct(),
        p.unitCost(),
        p.avgDailyDemand(),
        p.rop(),
        p.eoq(),
        p.minOrderQty(),
        p.maxOrderQty(),
        p.lotMultiplier(),
        ts(p.computedAt()),
        ts(p.createdAt()));
  }

  /**
   * Converts a kanban card to its wire form.
   *
   * @param k the kanban card to convert
   * @return its API representation
   */
  public static KanbanCardResponse toKanbanCard(KanbanCard k) {
    return new KanbanCardResponse(
        k.id().toString(),
        k.storeId().toString(),
        k.variantId().toString(),
        k.kanbanType(),
        k.status(),
        k.reorderQty(),
        k.sourceStoreId() == null ? null : k.sourceStoreId().toString(),
        k.supplierRef(),
        k.notes(),
        k.minOrderQty(),
        k.maxOrderQty(),
        k.lotMultiplier(),
        ts(k.createdAt()),
        ts(k.triggeredAt()),
        ts(k.replenishedAt()));
  }

  /**
   * Converts a costing method to its wire form.
   *
   * @param cm the costing method to convert
   * @return its API representation
   */
  public static CostingMethodResponse toCostingMethod(CostingMethod cm) {
    return new CostingMethodResponse(
        cm.id().toString(),
        cm.storeId().toString(),
        cm.variantId().toString(),
        cm.method(),
        cm.averageCost(),
        cm.updatedAt().toString());
  }

  /**
   * Converts a period to its wire form.
   *
   * @param ap the period to convert
   * @return its API representation
   */
  public static AccountingPeriodResponse toPeriod(AccountingPeriod ap) {
    return new AccountingPeriodResponse(
        ap.id().toString(),
        ap.storeId().toString(),
        ap.periodName(),
        ap.periodDate().toString(),
        ap.status(),
        ts(ap.openedAt()),
        ts(ap.closedAt()));
  }

  // ── Tier-1 mappers ────────────────────────────────────────────────────────

  /**
   * Converts a reason code to its wire form.
   *
   * @param r the reason code to convert
   * @return its API representation
   */
  public static ReasonCodeResponse toReasonCode(ReasonCode r) {
    return new ReasonCodeResponse(
        r.id().toString(),
        r.tenantId() == null ? null : r.tenantId().toString(),
        r.code(),
        r.description(),
        r.active(),
        ts(r.createdAt()));
  }

  /**
   * Converts a source type to its wire form.
   *
   * @param t the source type to convert
   * @return its API representation
   */
  public static SourceTypeResponse toSourceType(TransactionSourceType t) {
    return new SourceTypeResponse(
        t.id().toString(),
        t.tenantId() == null ? null : t.tenantId().toString(),
        t.code(),
        t.description(),
        t.active(),
        ts(t.createdAt()));
  }

  /**
   * Converts a lot action to its wire form.
   *
   * @param a the lot action to convert
   * @return its API representation
   */
  public static LotActionResponse toLotAction(LotAction a) {
    return new LotActionResponse(
        a.id().toString(),
        a.actionType(),
        a.sourceBatchId().toString(),
        a.resultBatchId().toString(),
        a.qty(),
        a.notes(),
        ts(a.createdAt()));
  }

  /**
   * Converts an expiring batch to its wire form.
   *
   * @param b the expiring batch to convert
   * @return its API representation
   */
  public static ExpiringBatchResponse toExpiringBatch(Batch b) {
    long daysUntil =
        b.expiryDate() == null
            ? Long.MAX_VALUE
            : ChronoUnit.DAYS.between(LocalDate.now(), b.expiryDate());
    return new ExpiringBatchResponse(
        b.id().toString(),
        b.storeId().toString(),
        b.variantId().toString(),
        b.batchNo(),
        b.remainingQty(),
        b.expiryDate() == null ? null : b.expiryDate().toString(),
        daysUntil);
  }

  /**
   * Converts a lot uom conversion to its wire form.
   *
   * @param c the lot uom conversion to convert
   * @return its API representation
   */
  public static LotUomConversionResponse toLotUomConversion(LotUomConversion c) {
    return new LotUomConversionResponse(
        c.id().toString(),
        c.batchId().toString(),
        c.fromUom(),
        c.toUom(),
        c.factor(),
        c.notes(),
        ts(c.createdAt()));
  }

  /**
   * Converts a par level to its wire form.
   *
   * @param p the par level to convert
   * @return its API representation
   */
  public static ParLevelResponse toParLevel(ParLevelConfig p) {
    return new ParLevelResponse(
        p.id().toString(),
        p.storeId().toString(),
        p.variantId().toString(),
        p.parQty(),
        p.uom(),
        p.reviewCycle(),
        ts(p.createdAt()),
        ts(p.updatedAt()));
  }

  /**
   * Converts a zone gl mapping to its wire form.
   *
   * @param z the zone gl mapping to convert
   * @return its API representation
   */
  public static ZoneGlMappingResponse toZoneGlMapping(ZoneGlMapping z) {
    return new ZoneGlMappingResponse(
        z.id().toString(),
        z.storeId().toString(),
        z.zoneId() == null ? null : z.zoneId().toString(),
        z.nominalCode(),
        z.description(),
        ts(z.createdAt()),
        ts(z.updatedAt()));
  }

  /**
   * Converts a picking rule to its wire form.
   *
   * @param r the picking rule to convert
   * @return its API representation
   */
  public static PickingRuleResponse toPickingRule(PickingRule r) {
    return new PickingRuleResponse(
        r.id().toString(),
        r.name(),
        r.strategy(),
        r.gradePreference(),
        r.status(),
        ts(r.createdAt()),
        ts(r.updatedAt()));
  }

  /**
   * Converts a zone priority to its wire form.
   *
   * @param p the zone priority to convert
   * @return its API representation
   */
  public static PickingRuleZonePriorityResponse toZonePriority(PickingRuleZonePriority p) {
    return new PickingRuleZonePriorityResponse(
        p.id().toString(), p.zoneId().toString(), p.priority());
  }

  /**
   * Converts a picking rule assignment to its wire form.
   *
   * @param a the picking rule assignment to convert
   * @return its API representation
   */
  public static PickingRuleAssignmentResponse toPickingRuleAssignment(PickingRuleAssignment a) {
    return new PickingRuleAssignmentResponse(
        a.id().toString(),
        a.ruleId().toString(),
        a.scopeType(),
        a.scopeId() == null ? null : a.scopeId().toString(),
        ts(a.createdAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }

  /** The gross-margin report in wire form (19.7). */
  public static com.storeql.inventory.dto.Dtos.GrossMarginReportResponse toGrossMarginReport(
      com.storeql.inventory.domain.Domain.GrossMarginReport report) {
    return new com.storeql.inventory.dto.Dtos.GrossMarginReportResponse(
        report.rows().stream()
            .map(
                r ->
                    new com.storeql.inventory.dto.Dtos.GrossMarginRowResponse(
                        r.groupKey(),
                        r.revenue(),
                        r.cogs(),
                        r.grossMargin(),
                        r.marginPercent(),
                        r.averageValue(),
                        r.gmroi(),
                        r.annualisedGmroi(),
                        r.uncostedSaleQty(),
                        r.unpricedSaleQty()))
            .toList(),
        report.historyComplete(),
        report.windowDays());
  }

  // ── Demand forecast (06.x) ───────────────────────────────────────────────────

  public static ForecastRunResponse toForecastRun(ForecastService.RunResult r) {
    return new ForecastRunResponse(
        r.storeId().toString(),
        r.variants(),
        r.byMethod(),
        r.meanMape(),
        r.horizonDays(),
        r.computedAt().toString(),
        r.fresh(),
        r.seasonal(),
        r.promoted());
  }

  /**
   * A stored forecast on the wire; the daily points only when asked, since a list of a store's
   * forecasts would otherwise carry a month of numbers per row.
   */
  public static ForecastResponse toForecast(DemandForecast d, boolean withPoints) {
    Forecasting.Forecast f = d.forecast();
    FreshProfile fresh = d.fresh() == null ? FreshProfile.KEEPS : d.fresh();
    List<ForecastPointResponse> points = List.of();
    if (withPoints) {
      List<ForecastPointResponse> out = new java.util.ArrayList<>(f.points().size());
      for (int i = 0; i < f.points().size(); i++) {
        out.add(new ForecastPointResponse(f.fromDay().plusDays(i).toString(), f.points().get(i)));
      }
      points = out;
    }
    return new ForecastResponse(
        d.id().toString(),
        d.storeId().toString(),
        d.variantId().toString(),
        f.method(),
        f.intermittent(),
        f.alpha(),
        f.level(),
        f.weekdayProfile(),
        d.historyFrom().toString(),
        d.historyTo().toString(),
        f.historyDays(),
        d.horizonDays(),
        f.fromDay().toString(),
        f.expectedOver(7),
        f.expectedOver(28),
        f.accuracy().holdoutDays(),
        f.accuracy().mape(),
        f.accuracy().bias(),
        f.accuracy().mase(),
        points,
        d.computedAt().toString(),
        fresh.fresh(),
        fresh.shelfLifeDays(),
        fresh.wasteRate() == null
            ? null
            : fresh.wasteRate().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
        fresh.maxCoverDays(),
        f.seasonalIndices(),
        f.uplift(),
        f.uplift() == null ? null : f.upliftSource(),
        f.promotedHistoryDays(),
        f.promotedAheadDays());
  }
}
