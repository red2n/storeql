package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.config.ServiceConfig;
import com.storeql.inventory.domain.Domain.AbcAssignment;
import com.storeql.inventory.domain.Domain.AbcCompileRun;
import com.storeql.inventory.domain.Domain.AccountingPeriod;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.CostingMethod;
import com.storeql.inventory.domain.Domain.CycleCountHeader;
import com.storeql.inventory.domain.Domain.CycleCountLine;
import com.storeql.inventory.domain.Domain.DeadStockGrouping;
import com.storeql.inventory.domain.Domain.DeadStockRow;
import com.storeql.inventory.domain.Domain.DemandBucket;
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
import com.storeql.inventory.domain.Domain.MovementAttribution;
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
import com.storeql.inventory.domain.Domain.ShrinkageGrouping;
import com.storeql.inventory.domain.Domain.ShrinkageRow;
import com.storeql.inventory.domain.Domain.StockTurnGrouping;
import com.storeql.inventory.domain.Domain.StockTurnReport;
import com.storeql.inventory.domain.Domain.StockTurnRow;
import com.storeql.inventory.domain.Domain.Suggestion;
import com.storeql.inventory.domain.Domain.Threshold;
import com.storeql.inventory.domain.Domain.TransactionSourceType;
import com.storeql.inventory.domain.Domain.TransferOrder;
import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.inventory.domain.Domain.ValuationGrouping;
import com.storeql.inventory.domain.Domain.ValuationRow;
import com.storeql.inventory.domain.Domain.ZoneGlMapping;
import com.storeql.inventory.repo.AbcAnalysisRepository;
import com.storeql.inventory.repo.CostingRepository;
import com.storeql.inventory.repo.CycleCountRepository;
import com.storeql.inventory.repo.DemandHistoryRepository;
import com.storeql.inventory.repo.InventoryRepository;
import com.storeql.inventory.repo.KanbanRepository;
import com.storeql.inventory.repo.LotActionRepository;
import com.storeql.inventory.repo.LotGenealogyRepository;
import com.storeql.inventory.repo.MovementArchiveRepository;
import com.storeql.inventory.repo.MovementRepository;
import com.storeql.inventory.repo.PhysicalInventoryRepository;
import com.storeql.inventory.repo.PickingRuleRepository;
import com.storeql.inventory.repo.PlanningConfigRepository;
import com.storeql.inventory.repo.ReferenceDataRepository;
import com.storeql.inventory.repo.ReorderPointRepository;
import com.storeql.inventory.repo.SafetyStockRepository;
import com.storeql.inventory.repo.SerialRepository;
import com.storeql.inventory.repo.SuggestionRepository;
import com.storeql.inventory.repo.ThresholdRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stock business logic. All mutations emit a stock event via the outbox (golden rule #6). */
@ApplicationScoped
public class InventoryService {

  private static final System.Logger LOG = System.getLogger(InventoryService.class.getName());

  @Inject ServiceConfig config;
  @Inject InventoryRepository repo;
  @Inject com.storeql.inventory.repo.BondRepository bonds;
  @Inject com.storeql.inventory.repo.ShrinkageRepository shrinkageRepo;
  @Inject com.storeql.inventory.repo.ValuationRepository valuationRepo;
  @Inject com.storeql.inventory.repo.LowStockRepository lowStockRepo;
  @Inject com.storeql.inventory.repo.StockTurnRepository stockTurnRepo;
  @Inject LotGenealogyRepository lotGenealogyRepo;
  @Inject ThresholdRepository thresholdRepo;
  @Inject SuggestionRepository suggestionRepo;
  @Inject com.storeql.inventory.repo.CatalogLinesOutRepository linesOut;
  @Inject DemandHistoryRepository demandHistoryRepo;
  @Inject CycleCountRepository cycleCountRepo;
  @Inject AbcAnalysisRepository abcRepo;
  @Inject SafetyStockRepository safetyStockRepo;
  @Inject MovementRepository movementRepo;
  @Inject PhysicalInventoryRepository physicalInventoryRepo;
  @Inject ReorderPointRepository ropRepo;
  @Inject KanbanRepository kanbanRepo;
  @Inject CostingRepository costingRepo;
  @Inject LotActionRepository lotActionRepo;
  @Inject MovementArchiveRepository movementArchiveRepo;
  @Inject PickingRuleRepository pickingRuleRepo;
  @Inject SerialRepository serialRepo;
  @Inject ReferenceDataRepository refData;
  @Inject PlanningConfigRepository planningConfig;

  // ---- receive (manual GRN entry; event-driven receives go through receiveOnce instead) ----

  /**
   * Books stock into a store as a new batch — the manual goods-receipt entry.
   *
   * <p>Event-driven receipts go through {@code receiveOnce} instead, which dedupes on the event id;
   * this path is for a human entering a delivery, so it is guarded by an idempotency key.
   *
   * @param tenantId owning tenant
   * @param storeId the store receiving the stock
   * @param variantId the variant received
   * @param qty the quantity received
   * @param batchNo the supplier's batch/lot number, or {@code null} to mint one
   * @param costPrice the unit cost this batch landed at, which drives valuation
   * @param expiry the batch's expiry date, or {@code null} for a non-perishable
   * @param refType what the receipt is against, e.g. a goods receipt
   * @param refId the referenced document's id
   * @param zoneId the zone the stock physically sits in
   * @param idempotencyKey the caller's key, so a retried entry does not book the delivery twice
   * @return the created batch
   */
  public Batch receive(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId,
      UUID zoneId,
      String idempotencyKey) {
    return receive(
        tenantId,
        storeId,
        variantId,
        qty,
        batchNo,
        costPrice,
        expiry,
        refType,
        refId,
        zoneId,
        idempotencyKey,
        Batch.OWNERSHIP_OWNED,
        null);
  }

  /**
   * Receives stock that may be the supplier's: {@code ownership} OWNED (the default) or
   * CONSIGNMENT, in which case the owning supplier is named (consignment stock ownership).
   *
   * @throws ApiException 400 {@code INVENTORY_OWNERSHIP_INVALID} for an ownership nobody defined;
   *     400 {@code INVENTORY_CONSIGNMENT_SUPPLIER_REQUIRED} for consignment stock with no supplier
   */
  public Batch receive(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId,
      UUID zoneId,
      String idempotencyKey,
      String ownership,
      UUID ownerSupplierId) {
    return receive(
        tenantId,
        storeId,
        variantId,
        qty,
        batchNo,
        costPrice,
        expiry,
        refType,
        refId,
        zoneId,
        idempotencyKey,
        ownership,
        ownerSupplierId,
        Batch.DUTY_PAID);
  }

  /**
   * As above, for excise goods that may arrive into bond: {@code dutyStatus} DUTY_PAID (the
   * default) or DUTY_SUSPENDED, the latter only at a store approved as a bonded warehouse.
   *
   * @throws ApiException 400 {@code INVENTORY_DUTY_STATUS_INVALID}; 400 {@code
   *     INVENTORY_STORE_NOT_BONDED} for suspended stock at a store nobody approved
   */
  public Batch receive(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId,
      UUID zoneId,
      String idempotencyKey,
      String ownership,
      UUID ownerSupplierId,
      String dutyStatus) {
    String owned = ownershipOf(ownership, ownerSupplierId);
    String duty = dutyStatusOf(dutyStatus);
    if (Batch.DUTY_SUSPENDED.equals(duty) && !bonds.isBonded(tenantId, storeId)) {
      throw ApiException.badRequest(
          "INVENTORY_STORE_NOT_BONDED",
          "duty-suspended stock may be held only at a store approved as a bonded warehouse");
    }
    UUID batchId = Ids.newId();
    var batch =
        new Batch(
            batchId,
            tenantId,
            storeId,
            variantId,
            batchNo,
            qty,
            qty,
            costPrice,
            expiry,
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            null,
            zoneId,
            owned,
            Batch.OWNERSHIP_CONSIGNMENT.equals(owned) ? ownerSupplierId : null,
            duty);
    var event =
        new OutboxRow(
            "StockReceived",
            "storeql.inventory.stock-received",
            tenantId,
            batchId,
            Events.stockReceived(tenantId, storeId, variantId, batchId, qty));
    try {
      return repo.receive(batch, refType, refId, event, idempotencyKey);
    } catch (ApiException e) {
      // Idempotent replay: a retried receipt with the same key gets the original batch back
      // instead of double-counting stock (golden rule #11).
      if ("BATCH_DUPLICATE_KEY".equals(e.code()) && idempotencyKey != null) {
        return repo.findBatchByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> e);
      }
      throw e;
    }
  }

  // ---- Gap #50: POS→SIM deduction (order fulfilled) ----

  /**
   * Deducts sold stock when an order is fulfilled, publishing {@code StockDeducted}.
   *
   * <p>Not deduped — the event-driven path {@code deductSaleFromOrderOnce} is the one that carries
   * a dedupe id. Calling this twice deducts twice.
   *
   * @param tenantId owning tenant
   * @param storeId the store the goods left
   * @param variantId the variant sold
   * @param qty the quantity sold
   * @param orderId the order the deduction is attributed to
   */
  public void deductSaleFromOrder(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId) {
    repo.deductSale(
        tenantId,
        storeId,
        variantId,
        qty,
        orderId,
        stockDeductedEvent(tenantId, storeId, variantId, qty, orderId));
  }

  /**
   * {@link #deductSaleFromOrder} deduped on {@code dedupeId} — used by event consumers so the
   * dedupe mark and the deduction commit atomically (a redelivered event line is skipped, a crashed
   * one retried).
   */
  public boolean deductSaleFromOrderOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId) {
    return deductSaleFromOrderOnce(
        dedupeId, consumerName, tenantId, storeId, variantId, qty, orderId, null);
  }

  /**
   * {@link #deductSaleFromOrderOnce} recording the line's net revenue beside the stock it drew, for
   * gross margin (19.7).
   *
   * @param netAmount revenue net of VAT and discounts, or null when the sale carried none
   */
  public boolean deductSaleFromOrderOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      BigDecimal netAmount) {
    return repo.deductSaleOnce(
        dedupeId,
        consumerName,
        tenantId,
        storeId,
        variantId,
        qty,
        orderId,
        netAmount,
        stockDeductedEvent(tenantId, storeId, variantId, qty, orderId));
  }

  private static OutboxRow stockDeductedEvent(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId) {
    return new OutboxRow(
        "StockDeducted",
        "storeql.inventory.stock-deducted",
        tenantId,
        orderId,
        Events.stockDeducted(tenantId, storeId, variantId, orderId, qty));
  }

  // ---- Gap #50: POS→SIM receipt (order returned) ----

  /**
   * Books returned goods back into stock under the lot they were sold from (SJ-D71).
   *
   * <p>Returns land in their own batch rather than rejoining the one they were sold from, but as
   * its child: the sale's draws say which batches the goods came from, and each share comes back
   * carrying that batch's lot, use-by date and cost, so the expiring view, a recall and the margin
   * report see returned stock as what it is. Goods the sale's draws cannot account for come back as
   * the anonymous return they always were.
   *
   * @param tenantId owning tenant
   * @param storeId the store taking the goods back
   * @param variantId the variant returned
   * @param qty the quantity returned
   * @param orderId the order being returned against
   */
  public void receiveReturnFromOrder(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId) {
    repo.receiveBackOnce(
        null,
        null,
        tenantId,
        storeId,
        variantId,
        qty,
        orderId,
        "RETURN",
        "RET-" + Ids.shortRef(orderId),
        InventoryService::stockReceivedEvent,
        true);
  }

  /** {@link #receiveReturnFromOrder} deduped on {@code dedupeId} (see deductSaleFromOrderOnce). */
  public boolean receiveReturnFromOrderOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId) {
    return repo.receiveBackOnce(
        dedupeId,
        consumerName,
        tenantId,
        storeId,
        variantId,
        qty,
        orderId,
        "RETURN",
        "RET-" + Ids.shortRef(orderId),
        InventoryService::stockReceivedEvent,
        true);
  }

  /**
   * Puts back the stock a voided till sale took, deduped on {@code dedupeId} (SJ-D40).
   *
   * <p>Recorded as a RECEIVE movement with reference type {@code VOID}: distinguishable from a
   * customer return ({@code RETURN}), which is a different loss-prevention signal, while every
   * report that sums receipts keeps working unchanged.
   *
   * <p>Received rather than reversed: the void and the fulfil arrive on different topics and may be
   * processed in either order. A receipt and a deduction net to the same stock whichever lands
   * first; reversing SALE movements that have not arrived yet would reverse nothing.
   */
  public boolean receiveVoidFromOrderOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId) {
    return repo.receiveBackOnce(
        dedupeId,
        consumerName,
        tenantId,
        storeId,
        variantId,
        qty,
        orderId,
        "VOID",
        "RET-" + Ids.shortRef(orderId),
        InventoryService::stockReceivedEvent,
        false);
  }

  /**
   * {@link #receive} deduped on {@code dedupeId} — used by the GoodsReceived consumer per GRN line.
   */
  public boolean receiveOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId) {
    return receiveOnce(
        dedupeId,
        consumerName,
        tenantId,
        storeId,
        variantId,
        qty,
        batchNo,
        costPrice,
        expiry,
        refType,
        refId,
        Batch.OWNERSHIP_OWNED,
        null);
  }

  /** As {@link #receiveOnce}, for stock that may be the supplier's (consignment). */
  public boolean receiveOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId,
      String ownership,
      UUID ownerSupplierId) {
    return receiveOnce(
        dedupeId,
        consumerName,
        tenantId,
        storeId,
        variantId,
        qty,
        batchNo,
        costPrice,
        expiry,
        refType,
        refId,
        ownership,
        ownerSupplierId,
        Batch.DUTY_PAID);
  }

  /**
   * As {@link #receiveOnce}, for a delivery that may arrive into bond. A supplier's word that the
   * goods are under bond is kept even at a store nobody approved — misstating the duty would be
   * worse — and the approval gap is logged for a person.
   */
  public boolean receiveOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId,
      String ownership,
      UUID ownerSupplierId,
      String dutyStatus) {
    String owned = ownershipOf(ownership, ownerSupplierId);
    String duty = dutyStatusOf(dutyStatus);
    if (Batch.DUTY_SUSPENDED.equals(duty) && !bonds.isBonded(tenantId, storeId)) {
      LOG.log(
          System.Logger.Level.WARNING,
          "duty-suspended delivery {0} received at store {1} of tenant {2}, which is not approved"
              + " as a bonded warehouse",
          refId,
          storeId,
          tenantId);
    }
    var batch =
        new Batch(
            Ids.newId(),
            tenantId,
            storeId,
            variantId,
            batchNo,
            qty,
            qty,
            costPrice,
            expiry,
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            null,
            null,
            owned,
            Batch.OWNERSHIP_CONSIGNMENT.equals(owned) ? ownerSupplierId : null,
            duty);
    return repo.receiveOnce(
        dedupeId, consumerName, batch, refType, refId, stockReceivedEvent(batch));
  }

  /** DUTY_PAID when unsaid; DUTY_SUSPENDED for goods held in bond. */
  static String dutyStatusOf(String dutyStatus) {
    if (dutyStatus == null || dutyStatus.isBlank()) return Batch.DUTY_PAID;
    String code = dutyStatus.trim().toUpperCase(java.util.Locale.ROOT);
    if (!Batch.DUTY_PAID.equals(code) && !Batch.DUTY_SUSPENDED.equals(code)) {
      throw ApiException.badRequest(
          "INVENTORY_DUTY_STATUS_INVALID",
          "dutyStatus must be DUTY_PAID or DUTY_SUSPENDED; got " + dutyStatus);
    }
    return code;
  }

  /** OWNED when unsaid; CONSIGNMENT only with the supplier it belongs to. */
  static String ownershipOf(String ownership, UUID ownerSupplierId) {
    if (ownership == null || ownership.isBlank()) return Batch.OWNERSHIP_OWNED;
    String code = ownership.trim().toUpperCase(java.util.Locale.ROOT);
    if (!Batch.OWNERSHIP_OWNED.equals(code) && !Batch.OWNERSHIP_CONSIGNMENT.equals(code)) {
      throw ApiException.badRequest(
          "INVENTORY_OWNERSHIP_INVALID",
          "ownership must be OWNED or CONSIGNMENT; got " + ownership);
    }
    if (Batch.OWNERSHIP_CONSIGNMENT.equals(code) && ownerSupplierId == null) {
      throw ApiException.badRequest(
          "INVENTORY_CONSIGNMENT_SUPPLIER_REQUIRED",
          "consignment stock belongs to a supplier: give supplierId");
    }
    return code;
  }

  /**
   * Sends goods back to the supplier (07.8): what a {@code ReturnedToVendor} line does to stock.
   * Deduped on the line's id; publishes {@code StockAdjusted} with a negative delta so every
   * projection of on-hand follows.
   *
   * @return whether the line was applied now
   * @throws ApiException {@code INSUFFICIENT_STOCK} (422) when the store has less on hand than is
   *     going back — the consumer skips that line and says so
   */
  public boolean returnToVendorOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID returnId) {
    var event =
        new OutboxRow(
            "StockAdjusted",
            "storeql.inventory.stock-adjusted",
            tenantId,
            variantId,
            Events.stockAdjusted(tenantId, storeId, variantId, qty.negate()));
    return repo.deductReturnToVendorOnce(
        dedupeId, consumerName, tenantId, storeId, variantId, qty, returnId, event);
  }

  /**
   * Lifts, or on a reversal lowers, the unit cost of the batches one receipt created by a landed
   * charge's per-unit share (07.x), once per event line. No stock moves, so nothing is announced;
   * valuation reads the batch. A receipt inventory has not booked yet answers 503 so the consumer
   * redelivers rather than losing the charge.
   *
   * @param perUnit what a unit's cost changes by, signed
   * @param amount the line's whole share, signed — what an AVERAGE pool takes in over what is on
   *     hand
   * @param sourceType {@code LANDED_COST} or {@code LANDED_COST_REVERSAL}
   * @param sourceId the charge
   */
  public boolean revalueReceiptOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID grId,
      BigDecimal perUnit,
      BigDecimal amount,
      String sourceType,
      UUID sourceId) {
    return repo.revalueReceiptOnce(
        dedupeId,
        consumerName,
        tenantId,
        storeId,
        variantId,
        grId,
        perUnit,
        amount,
        sourceType,
        sourceId);
  }

  static OutboxRow stockReceivedEvent(Batch batch) {
    return new OutboxRow(
        "StockReceived",
        "storeql.inventory.stock-received",
        batch.tenantId(),
        batch.id(),
        Events.stockReceived(
            batch.tenantId(), batch.storeId(), batch.variantId(), batch.id(), batch.receivedQty()));
  }

  // ---- low stock report ----

  /**
   * Items currently below a configured reorder level, live.
   *
   * <p>Distinct from the planning suggestions, which return the min/max engine's persisted output
   * from the last {@code POST /planning/run} and ignore safety stock and reorder points; and from
   * {@code levelsSummary}, which counts SKUs under one flat number for a dashboard tile.
   */
  public List<LowStockRow> lowStockReport(UUID tenantId, UUID storeId, int limit) {
    return lowStockRepo.lowStock(tenantId, storeId, limit);
  }

  // ---- valuation report ----

  /**
   * Values stock on hand, grouped by store or by variant.
   *
   * <p>Named in the reporting gap analysis as designed but unbuilt. Stock with no cost is returned
   * as {@code unvaluedQty} rather than valued at zero — this figure ends up on a balance sheet, and
   * silently costing unknown stock at nothing understates it.
   */
  public List<ValuationRow> valuationReport(
      UUID tenantId, UUID storeId, ValuationGrouping grouping, int limit) {
    return valuationRepo.value(tenantId, storeId, grouping, limit);
  }

  // ---- shrinkage report ----

  /**
   * Write-offs over a period, grouped by reason code, by member of staff, or by store.
   *
   * <p>This is the report SJ-D4's attribution work exists to feed: until adjustments carried a
   * reason and an actor there was nothing to group by, and the reason-code reference table had no
   * reader. Grouping by ACTOR is the loss-prevention view — a cashier whose write-offs sit well
   * above their peers is the pattern this surfaces.
   *
   * @param grouping validated by the resource against {@link ShrinkageGrouping}
   */
  public List<ShrinkageRow> shrinkageReport(
      UUID tenantId, UUID storeId, Instant from, Instant to, ShrinkageGrouping grouping) {
    if (from != null && to != null && !from.isBefore(to))
      throw ApiException.badRequest(
          "INVENTORY_INVALID_PERIOD", "from must be before to — got " + from + " and " + to);
    return shrinkageRepo.aggregate(tenantId, storeId, from, to, grouping);
  }

  /**
   * The variants behind a summary line, so an investigation can go from "this member of staff wrote
   * off 400 units" to what they actually wrote off.
   */
  public List<ShrinkageRow> shrinkageByVariant(
      UUID tenantId,
      UUID storeId,
      Instant from,
      Instant to,
      String reasonCode,
      UUID actorId,
      int limit) {
    if (from != null && to != null && !from.isBefore(to))
      throw ApiException.badRequest(
          "INVENTORY_INVALID_PERIOD", "from must be before to — got " + from + " and " + to);
    return shrinkageRepo.topVariants(tenantId, storeId, from, to, reasonCode, actorId, limit);
  }

  // ---- stock turn & dead stock ----

  /**
   * How many times the holding turned over during a window, and how long the stock on hand would
   * last at that rate.
   *
   * <p>Both bounds are required, unlike the shrinkage report's. Shrinkage over "all time" is a
   * meaningful total; turns over all time is not — the ratio's denominator is an average holding
   * over a period, and its {@code daysOnHand} divides by the period's length.
   *
   * <p>{@code daysOnHand} is completed here rather than in SQL because it needs the window length,
   * which is a property of the request rather than of any row.
   *
   * @param grouping validated by the resource against {@link StockTurnGrouping}
   */
  public StockTurnReport stockTurnReport(
      UUID tenantId,
      UUID storeId,
      Instant from,
      Instant to,
      StockTurnGrouping grouping,
      int limit) {
    if (!from.isBefore(to))
      throw ApiException.badRequest(
          "INVENTORY_INVALID_PERIOD", "from must be before to — got " + from + " and " + to);

    long days = Duration.between(from, to).toDays();
    // A window shorter than a day still has a length; rounding it to zero would divide by it.
    int windowDays = (int) Math.max(1, days);

    List<StockTurnRow> rows =
        stockTurnRepo.stockTurn(tenantId, storeId, from, to, grouping, limit).stream()
            .map(r -> withDaysOnHand(r, windowDays))
            .toList();

    // The replay behind these figures can only see movements still in stock_movements, so the
    // archive is asked whether the purge took any the window needed. Asking the archive rather
    // than inferring from the oldest retained movement is the difference between "history is
    // missing" and "there is no history yet", which a young tenant has plenty of.
    boolean historyComplete = stockTurnRepo.historyComplete(tenantId, storeId, to);
    return new StockTurnReport(rows, historyComplete, windowDays);
  }

  /**
   * Days of cover implied by a turnover ratio. Null propagates: a group with nothing to turn has no
   * rate of sale, and reporting "0 days on hand" for it would read as an emergency.
   */
  private static StockTurnRow withDaysOnHand(StockTurnRow r, int windowDays) {
    BigDecimal ratio = r.turnoverRatio();
    BigDecimal daysOnHand =
        ratio == null || ratio.signum() == 0
            ? null
            : BigDecimal.valueOf(windowDays).divide(ratio, 1, RoundingMode.HALF_UP);
    return new StockTurnRow(
        r.groupKey(),
        r.cogs(),
        r.uncostedSaleQty(),
        r.openingValue(),
        r.closingValue(),
        r.averageValue(),
        ratio,
        daysOnHand);
  }

  /**
   * Stock on hand aged by time since its last sale, for the dead-stock ladder.
   *
   * <p>No date range: dead stock is a question about now, not about a period. The caller may pin
   * {@code asOf} so a report re-run tomorrow against the same date gives the same answer.
   *
   * @param asOf the instant to measure ages back from; defaults to now when null
   * @param grouping validated by the resource against {@link DeadStockGrouping}
   */
  public List<DeadStockRow> deadStockReport(
      UUID tenantId, UUID storeId, Instant asOf, DeadStockGrouping grouping, int limit) {
    return stockTurnRepo.deadStock(
        tenantId, storeId, asOf == null ? Instant.now() : asOf, grouping, limit);
  }

  // ---- adjust ----
  /**
   * Manual stock correction. {@code reasonCode} and {@code actorId} land on the movement row so a
   * shrinkage investigation can ask who wrote off what, and why (SJ-D4). Both were previously
   * dropped -- {@code reasonCode} was accepted and documented on the request DTO but never read,
   * and there was no actor column at all.
   *
   * @param reasonCode a {@code transaction_reason_codes} code, e.g. THEFT or DAMAGED; may be null
   * @param actorId the authenticated user making the correction; null only for a system caller
   */
  public void adjust(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      String reasonCode,
      UUID actorId,
      String idempotencyKey) {
    var event =
        new OutboxRow(
            "StockAdjusted",
            "storeql.inventory.stock-adjusted",
            tenantId,
            variantId,
            Events.stockAdjusted(tenantId, storeId, variantId, delta));
    repo.adjust(
        tenantId,
        storeId,
        variantId,
        delta,
        reason,
        event,
        idempotencyKey,
        MovementAttribution.by(actorId, reasonCode));
  }

  // ---- reserve ----

  /**
   * Holds stock for a checkout, publishing {@code StockReserved}.
   *
   * <p>A hold is not a deduction: it expires on its own if the order never completes, and the
   * reservation sweeper reclaims it. Retrying with the same idempotency key returns the original
   * hold rather than holding the stock twice for one checkout attempt.
   *
   * @param tenantId owning tenant
   * @param storeId the store holding the stock
   * @param variantId the variant to hold
   * @param qty the quantity to hold
   * @param orderId the order the hold is placed for
   * @param ttlSeconds how long the hold lives, or {@code null} for the configured default
   * @param idempotencyKey the caller's key, so a retried checkout reuses the existing hold
   * @return the hold, whether newly placed or the replayed original
   * @throws ApiException a conflict when there is not enough free stock to hold
   */
  public Reservation reserve(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      Long ttlSeconds,
      String idempotencyKey) {
    long ttl = ttlSeconds == null ? config.reservationTtlSeconds() : ttlSeconds;
    UUID id = Ids.newId();
    var reservation =
        new Reservation(
            id,
            tenantId,
            storeId,
            variantId,
            qty,
            orderId,
            Reservation.HELD,
            Instant.now().plusSeconds(ttl),
            Instant.now());
    var event =
        new OutboxRow(
            "StockReserved",
            "storeql.inventory.stock-reserved",
            tenantId,
            id,
            Events.stockReserved(tenantId, storeId, variantId, id, qty));
    try {
      return repo.reserve(reservation, event, idempotencyKey);
    } catch (ApiException e) {
      // Idempotent replay: a retried reservation with the same key gets the original hold back
      // instead of holding stock twice for one checkout attempt (golden rule #11).
      if ("RESERVATION_DUPLICATE_KEY".equals(e.code()) && idempotencyKey != null) {
        return repo.findReservationByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> e);
      }
      throw e;
    }
  }

  // ---- consume (FIFO deduct) ----

  /**
   * Turns a hold into a real deduction, taking stock FIFO across batches.
   *
   * <p>Not deduped — {@link #consumeOnce} is the event-driven path that carries a dedupe id.
   *
   * @param tenantId owning tenant
   * @param reservationId the hold to consume
   */
  public void consume(UUID tenantId, UUID reservationId) {
    repo.consume(tenantId, reservationId);
  }

  /**
   * {@link #consume} deduped on {@code dedupeId} — used by the OrderFulfilled consumer so the
   * dedupe mark and the consumption commit atomically (a redelivered event line is skipped, a
   * crashed one retried).
   */
  public boolean consumeOnce(
      UUID dedupeId, String consumerName, UUID tenantId, UUID reservationId) {
    return consumeOnce(dedupeId, consumerName, tenantId, reservationId, null);
  }

  /** {@link #consumeOnce} recording the line's net revenue beside the draw-down (19.7). */
  public boolean consumeOnce(
      UUID dedupeId, String consumerName, UUID tenantId, UUID reservationId, BigDecimal netAmount) {
    return repo.consumeOnce(dedupeId, consumerName, tenantId, reservationId, netAmount);
  }

  /** The HELD reservations placed for one order at checkout. */
  public List<Reservation> heldReservationsByOrder(UUID tenantId, UUID orderId) {
    return repo.heldReservationsByOrder(tenantId, orderId);
  }

  // ---- release ----

  /**
   * Gives a hold back without deducting, publishing {@code StockReleased}.
   *
   * <p>What a cancelled or abandoned checkout triggers, so the stock becomes available again rather
   * than waiting out its TTL.
   *
   * @param tenantId owning tenant
   * @param reservationId the hold to release
   * @return {@code true} when a held reservation was released, {@code false} when there was nothing
   *     left to release
   */
  public boolean release(UUID tenantId, UUID reservationId) {
    var event =
        new OutboxRow(
            "StockReleased",
            "storeql.inventory.stock-released",
            tenantId,
            reservationId,
            Events.reservationEvent("StockReleased", tenantId, reservationId));
    return repo.release(tenantId, reservationId, event);
  }

  /**
   * A line of an online order closed short or substituted (substitutions for out-of-stock online
   * lines): {@code qty} of the order's hold on the variant goes back to the shelf and the order
   * waits for that much less, once per event; a hold released in full publishes {@code
   * StockReleased} as a cancellation's would.
   *
   * @return false when the event was already applied
   */
  public boolean lineClosedOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      BigDecimal qty) {
    return repo.lineClosedOnce(
        eventId,
        consumerName,
        tenantId,
        orderId,
        variantId,
        qty,
        reservationId ->
            new OutboxRow(
                "StockReleased",
                "storeql.inventory.stock-released",
                tenantId,
                reservationId,
                Events.reservationEvent("StockReleased", tenantId, reservationId)));
  }

  // ---- reads ----
  /**
   * On-hand levels per variant for a store.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose levels to read
   * @return one level per variant holding stock at that store
   */
  public List<Level> levels(UUID tenantId, UUID storeId) {
    return repo.levels(tenantId, storeId);
  }

  /** What every store holds of the products named (order orchestration's read). */
  public List<Level> levelsForVariants(UUID tenantId, List<UUID> variantIds) {
    return repo.levelsForVariants(tenantId, variantIds);
  }

  /** The products the supplier fulfils per order (dropship). */
  public List<UUID> dropshipVariants(UUID tenantId) {
    return repo.dropshipVariants(tenantId);
  }

  /**
   * What a shopper can buy at a store: each variant with stock available on the shelf, and each
   * variant the supplier fulfils per order (dropship) — available with none on the shelf.
   *
   * <p>Carries each level's raw available quantity too, so a caller with a store and a threshold in
   * hand can turn it into "only N left" via {@link com.storeql.inventory.domain.OnlyLeft#compute}
   * without a second read of the levels this method already loaded.
   */
  public List<com.storeql.inventory.domain.Domain.Availability> availability(
      UUID tenantId, UUID storeId) {
    List<com.storeql.inventory.domain.Domain.Availability> out = new java.util.ArrayList<>();
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    for (Level l : repo.levels(tenantId, storeId)) {
      seen.add(l.variantId());
      out.add(
          new com.storeql.inventory.domain.Domain.Availability(
              l.variantId(),
              l.available() != null && l.available().signum() > 0,
              false,
              l.available()));
    }
    for (UUID variantId : repo.dropshipVariants(tenantId)) {
      if (seen.add(variantId)) {
        out.add(new com.storeql.inventory.domain.Domain.Availability(variantId, true, true, null));
      }
    }
    return out;
  }

  /** Records purchase-svc's word on how a variant is fulfilled, once per event. */
  public boolean recordSourcingOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID variantId,
      String fulfilment,
      UUID supplierId) {
    String code = fulfilment == null ? "" : fulfilment.trim().toUpperCase(java.util.Locale.ROOT);
    if (!"STOCK".equals(code) && !"DROPSHIP".equals(code)) {
      throw ApiException.badRequest(
          "INVENTORY_SOURCING_INVALID", "fulfilment must be STOCK or DROPSHIP; got " + fulfilment);
    }
    return repo.upsertSourcingOnce(
        eventId,
        consumerName,
        tenantId,
        variantId,
        code,
        "DROPSHIP".equals(code) ? supplierId : null);
  }

  /** One page of stock levels plus the opaque cursor for the next page (null when exhausted). */
  public record LevelPage(List<Level> levels, String nextCursor) {}

  /**
   * Cursor-paginated levels. The cursor wraps the last row's {@code storeId|variantId} keyset; a
   * fresh call (null cursor) starts at the first row. Fetches one extra row to learn whether a
   * further page exists without a second query.
   */
  public LevelPage levelsPage(UUID tenantId, UUID storeId, String afterCursor, int limit) {
    UUID afterStoreId = null;
    UUID afterVariantId = null;
    String rawKey = com.storeql.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) {
          throw new IllegalArgumentException("missing separator");
        }
        afterStoreId = Ids.parse(rawKey.substring(0, sep));
        afterVariantId = Ids.parse(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    List<Level> rows = repo.levelsPage(tenantId, storeId, afterStoreId, afterVariantId, limit + 1);
    if (rows.size() <= limit) {
      return new LevelPage(rows, null);
    }
    List<Level> page = rows.subList(0, limit);
    Level last = page.get(page.size() - 1);
    return new LevelPage(
        page, com.storeql.web.Cursor.encode(last.storeId() + "|" + last.variantId()));
  }

  /**
   * SKUs with available quantity at or below this count as "low stock" for the dashboard summary,
   * mirroring the client's {@code InventoryLevel.isLow} heuristic (available &lt;= 5).
   */
  private static final BigDecimal LOW_STOCK_THRESHOLD = new BigDecimal("5");

  /** Aggregate SKU / low-stock counts for the dashboard, without materializing the full list. */
  public LevelSummary levelsSummary(UUID tenantId, UUID storeId) {
    return repo.levelsSummary(tenantId, storeId, LOW_STOCK_THRESHOLD);
  }

  /**
   * Lists the tenant's batches.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param materialStatus the material status
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<Batch> listBatches(
      UUID tenantId, UUID storeId, UUID variantId, String materialStatus, int limit) {
    return repo.listBatches(tenantId, storeId, variantId, materialStatus, limit);
  }

  /**
   * Updates a material status.
   *
   * @param tenantId owning tenant
   * @param batchId the batch id
   * @param materialStatus the material status
   * @param reason the reason recorded against the change
   * @return the updated material status
   */
  public Batch updateMaterialStatus(
      UUID tenantId, UUID batchId, String materialStatus, String reason) {
    if (!List.of(
            Batch.MATERIAL_AVAILABLE,
            Batch.MATERIAL_QUARANTINE,
            Batch.MATERIAL_INSPECTION,
            Batch.MATERIAL_DAMAGED,
            Batch.MATERIAL_RECALLED)
        .contains(materialStatus)) {
      throw new ApiException(
          400,
          "INVALID_MATERIAL_STATUS",
          "materialStatus must be AVAILABLE|QUARANTINE|INSPECTION|DAMAGED|RECALLED",
          List.of(),
          null);
    }
    var event =
        new OutboxRow(
            "MaterialStatusChanged",
            "storeql.inventory.material-status-changed",
            tenantId,
            batchId,
            Events.materialStatusChanged(tenantId, batchId, materialStatus, reason));
    return repo.updateMaterialStatus(tenantId, batchId, materialStatus, reason, event);
  }

  /**
   * Reads a batch.
   *
   * @param tenantId owning tenant
   * @param batchId the batch id
   * @return the batch
   * @throws ApiException a 404 when no such batch exists in this tenant
   */
  public Batch getBatch(UUID tenantId, UUID batchId) {
    return repo.getBatch(tenantId, batchId)
        .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "No such batch"));
  }

  /**
   * Lists the tenant's movements.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param type the type to filter on
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<Movement> listMovements(
      UUID tenantId, UUID storeId, UUID variantId, String type, int limit) {
    return movementRepo.listMovements(tenantId, storeId, variantId, type, limit);
  }

  /**
   * Lists the tenant's reservations.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<Reservation> listReservations(UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listReservations(tenantId, storeId, status, limit);
  }

  /**
   * Reads a reservation.
   *
   * @param tenantId owning tenant
   * @param reservationId the reservation id
   * @return the reservation
   * @throws ApiException a 404 when no such reservation exists in this tenant
   */
  public Reservation getReservation(UUID tenantId, UUID reservationId) {
    return repo.findReservation(tenantId, reservationId)
        .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "No such reservation"));
  }

  /**
   * Sets the reorder threshold and target maximum for a variant at a store.
   *
   * <p>Crossing the threshold is what raises a {@code StockBelowThreshold} alert and a
   * replenishment suggestion; the maximum is what the suggested quantity tops up to.
   *
   * @param tenantId owning tenant
   * @param storeId the store the threshold applies at
   * @param variantId the variant concerned
   * @param threshold the level at or below which stock is considered short
   * @param maxQty the level replenishment should restore stock to
   * @return the stored threshold
   */
  public Threshold setThreshold(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal threshold, BigDecimal maxQty) {
    return thresholdRepo.upsertThreshold(
        new Threshold(Ids.newId(), tenantId, storeId, variantId, threshold, maxQty));
  }

  /**
   * Lists the tenant's thresholds.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @return the matching rows
   */
  public List<Threshold> listThresholds(UUID tenantId, UUID storeId) {
    return thresholdRepo.listThresholds(tenantId, storeId);
  }

  // ---- min-max planning engine ----

  /**
   * Scan every threshold for the tenant (optionally filtered by store), compare against current
   * available stock, and create OPEN replenishment suggestions for any under-stocked SKU. Skips
   * SKUs that already have an OPEN suggestion (idempotent).
   */
  public List<Suggestion> runMinMaxPlan(UUID tenantId, UUID storeId) {
    List<Level> levels = repo.levels(tenantId, storeId);
    Map<String, BigDecimal> avail = new java.util.HashMap<>();
    for (Level l : levels) {
      avail.put(l.storeId() + ":" + l.variantId(), l.available());
    }

    List<Threshold> thresholds = thresholdRepo.listThresholds(tenantId, storeId);
    // Item lifecycle: a line discontinued or delisted is sold while stock lasts and never
    // reordered, so no suggestion is raised for it however low it runs.
    java.util.Set<UUID> out = linesOut.variantsOut(tenantId);
    List<Suggestion> created = new ArrayList<>();
    for (Threshold t : thresholds) {
      if (out.contains(t.variantId())) continue;
      BigDecimal available = avail.getOrDefault(t.storeId() + ":" + t.variantId(), BigDecimal.ZERO);
      if (available.compareTo(t.threshold()) >= 0) continue;

      BigDecimal target =
          t.maxQty() != null ? t.maxQty() : t.threshold().multiply(BigDecimal.valueOf(2));
      BigDecimal suggestedQty = target.subtract(available).max(BigDecimal.ONE);
      UUID suggId = Ids.newId();
      var sugg =
          new Suggestion(
              suggId,
              tenantId,
              t.storeId(),
              t.variantId(),
              available,
              t.threshold(),
              t.maxQty(),
              suggestedQty,
              Suggestion.STATUS_OPEN,
              Instant.now(),
              null);
      var event =
          new OutboxRow(
              "ReplenishmentSuggested",
              "storeql.inventory.replenishment-suggested",
              tenantId,
              suggId,
              Events.replenishmentSuggested(
                  tenantId, suggId, t.storeId(), t.variantId(), suggestedQty));
      suggestionRepo.insertSuggestionIfAbsent(sugg, event).ifPresent(created::add);
    }
    return created;
  }

  /**
   * Lists the tenant's suggestions.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<Suggestion> listSuggestions(UUID tenantId, UUID storeId, String status, int limit) {
    return suggestionRepo.listSuggestions(tenantId, storeId, status, limit);
  }

  /**
   * Closes a replenishment suggestion as ordered or cancelled, publishing {@code
   * ReplenishmentResolved}.
   *
   * @param tenantId owning tenant
   * @param suggId the suggestion to resolve
   * @param newStatus {@code ORDERED} or {@code CANCELLED}
   * @return the resolved suggestion
   * @throws ApiException {@code INVALID_SUGGESTION_STATUS} (400) when the status is neither; a 404
   *     when no such suggestion exists in this tenant
   */
  public Suggestion resolveSuggestion(UUID tenantId, UUID suggId, String newStatus) {
    if (!List.of(Suggestion.STATUS_ORDERED, Suggestion.STATUS_CANCELLED).contains(newStatus)) {
      throw new ApiException(
          400, "INVALID_SUGGESTION_STATUS", "status must be ORDERED or CANCELLED", List.of(), null);
    }
    var event =
        new OutboxRow(
            "ReplenishmentResolved",
            "storeql.inventory.replenishment-resolved",
            tenantId,
            suggId,
            Events.replenishmentResolved(tenantId, suggId, newStatus));
    return suggestionRepo
        .resolveSuggestion(tenantId, suggId, newStatus, event)
        .orElseThrow(
            () -> ApiException.notFound("SUGGESTION_NOT_FOUND", "No open suggestion with that id"));
  }

  // ---- serial number control (Gap #3) ----

  /**
   * Registers serial numbers against a batch, either supplied explicitly or generated.
   *
   * <p>Supply {@code serials} to record the manufacturer's own numbers, or {@code autoQty} with a
   * {@code prefix} to mint a run. An explicit list is capped at 200 per call.
   *
   * @param tenantId owning tenant
   * @param storeId the store holding the serialised stock
   * @param variantId the variant concerned
   * @param batchId the batch the serials belong to
   * @param serials the serial numbers to record, or {@code null} to generate them
   * @param autoQty how many to generate when {@code serials} is absent
   * @param prefix the prefix for generated numbers
   * @return the registered serial numbers
   * @throws ApiException a 400 when more than 200 serials are supplied at once, or when neither a
   *     list nor a quantity is given
   */
  public List<SerialNumber> registerSerials(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      List<String> serials,
      Integer autoQty,
      String prefix) {
    List<String> serialNos;
    if (serials != null && !serials.isEmpty()) {
      if (serials.size() > 200)
        throw new ApiException(
            400, "TOO_MANY_SERIALS", "max 200 serials per call", List.of(), null);
      serialNos = serials;
    } else if (autoQty != null && autoQty > 0) {
      if (autoQty > 200)
        throw new ApiException(400, "TOO_MANY_SERIALS", "autoQty max 200", List.of(), null);
      String pfx = prefix == null || prefix.isBlank() ? "SN" : prefix;
      serialNos = new ArrayList<>();
      for (int i = 0; i < autoQty; i++) {
        serialNos.add(generateSerialNo(pfx, i));
      }
    } else {
      throw new ApiException(
          400, "SERIALS_REQUIRED", "provide serials list or autoQty > 0", List.of(), null);
    }
    Instant now = Instant.now();
    var domainSerials =
        serialNos.stream()
            .map(
                sno ->
                    new SerialNumber(
                        Ids.newId(),
                        tenantId,
                        storeId,
                        variantId,
                        batchId,
                        sno,
                        SerialNumber.IN_STOCK,
                        now,
                        null))
            .toList();
    var event =
        new OutboxRow(
            "SerialsRegistered",
            "storeql.inventory.serials-registered",
            tenantId,
            batchId,
            Events.serialsRegistered(tenantId, batchId, domainSerials.size()));
    return serialRepo.registerSerials(domainSerials, event);
  }

  /**
   * Lists the tenant's serials.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<SerialNumber> listSerials(
      UUID tenantId, UUID storeId, UUID variantId, String status, int limit) {
    return serialRepo.listSerials(tenantId, storeId, variantId, status, limit);
  }

  /**
   * Reads a serial.
   *
   * @param tenantId owning tenant
   * @param serialId the serial id
   * @return the serial
   * @throws ApiException a 404 when no such serial exists in this tenant
   */
  public SerialNumber getSerial(UUID tenantId, UUID serialId) {
    return serialRepo
        .findSerial(tenantId, serialId)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  /**
   * Resolves a scanned serial number to its record.
   *
   * @param tenantId owning tenant
   * @param serialNo the serial number as scanned
   * @return the serial's record, including the batch and status it carries
   * @throws ApiException a 404 when no such serial exists in this tenant
   */
  public SerialNumber lookupSerialByNo(UUID tenantId, String serialNo) {
    return serialRepo
        .findSerialByNo(tenantId, serialNo)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  /**
   * Updates a serial status.
   *
   * @param tenantId owning tenant
   * @param serialId the serial id
   * @param newStatus the new status
   * @return the updated serial status
   * @throws ApiException a 404 when no such serial status exists in this tenant
   */
  public SerialNumber updateSerialStatus(UUID tenantId, UUID serialId, String newStatus) {
    if (!List.of(
            SerialNumber.IN_STOCK,
            SerialNumber.RESERVED,
            SerialNumber.SOLD,
            SerialNumber.RETURNED,
            SerialNumber.LOST,
            SerialNumber.DAMAGED)
        .contains(newStatus)) {
      throw new ApiException(
          400,
          "INVALID_SERIAL_STATUS",
          "status must be IN_STOCK|RESERVED|SOLD|RETURNED|LOST|DAMAGED",
          List.of(),
          null);
    }
    var event =
        new OutboxRow(
            "SerialStatusChanged",
            "storeql.inventory.serial-status-changed",
            tenantId,
            serialId,
            Events.serialStatusChanged(tenantId, serialId, newStatus));
    return serialRepo
        .updateSerialStatus(tenantId, serialId, newStatus, event)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  /**
   * Lists the tenant's serial histories.
   *
   * @param tenantId owning tenant
   * @param serialId the serial id
   * @return the matching rows
   */
  public List<SerialMovement> listSerialHistory(UUID tenantId, UUID serialId) {
    return serialRepo.listSerialHistory(tenantId, serialId);
  }

  private static String generateSerialNo(String prefix, int index) {
    String rand =
        Long.toHexString(System.nanoTime() ^ ((long) index * 0x9E3779B97F4A7C15L))
            .toUpperCase(Locale.ROOT)
            .substring(0, 8);
    return prefix + "-" + rand;
  }

  // ---- demand history (Gap #7) ----

  /**
   * Rolls stock movements up into demand buckets, which the planning maths reads from.
   *
   * @param tenantId owning tenant
   * @param storeId the store to aggregate for
   * @param bucketType {@code DAY}, {@code WEEK} or {@code MONTH}; defaults to {@code WEEK}
   * @param since the earliest date to aggregate from
   * @return how many buckets were written
   * @throws ApiException {@code INVALID_BUCKET_TYPE} (400) when the bucket type is not one of the
   *     three
   */
  public int aggregateDemand(UUID tenantId, UUID storeId, String bucketType, LocalDate since) {
    String bt = bucketType == null ? DemandBucket.BUCKET_WEEK : bucketType.toUpperCase(Locale.ROOT);
    if (!List.of(DemandBucket.BUCKET_DAY, DemandBucket.BUCKET_WEEK, DemandBucket.BUCKET_MONTH)
        .contains(bt)) {
      throw new ApiException(
          400, "INVALID_BUCKET_TYPE", "bucketType must be DAY, WEEK, or MONTH", List.of(), null);
    }
    return demandHistoryRepo.aggregateDemand(tenantId, storeId, bt, since);
  }

  /**
   * Lists the tenant's demand histories.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param bucketType the bucket type
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<DemandBucket> listDemandHistory(
      UUID tenantId, UUID storeId, UUID variantId, String bucketType, int limit) {
    String bt = bucketType == null ? null : bucketType.toUpperCase(Locale.ROOT);
    return demandHistoryRepo.listDemandHistory(tenantId, storeId, variantId, bt, limit);
  }

  // ---- move orders (Gap #5) ----

  /**
   * Creates a move order.
   *
   * @param tenantId owning tenant
   * @param fromStoreId the from store id
   * @param toStoreId the to store id
   * @param fromZone the from zone
   * @param toZone the to zone
   * @param notes free-text notes
   * @param lines the lines to store
   * @return the created move order
   */
  public MoveOrder createMoveOrder(
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      String fromZone,
      String toZone,
      String notes,
      List<MoveOrderLine> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new ApiException(
          400, "NO_LINES", "Move order must have at least one line", List.of(), null);
    }
    UUID orderId = Ids.newId();
    Instant now = Instant.now();
    MoveOrder order =
        new MoveOrder(
            orderId,
            tenantId,
            fromStoreId,
            toStoreId,
            fromZone,
            toZone,
            notes,
            MoveOrder.DRAFT,
            now,
            null);
    List<MoveOrderLine> withIds =
        lines.stream()
            .map(
                l ->
                    new MoveOrderLine(
                        Ids.newId(), tenantId, orderId, l.variantId(), l.requestedQty(), null))
            .toList();
    return repo.createMoveOrder(order, withIds);
  }

  /**
   * Lists the tenant's move orders.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<MoveOrder> listMoveOrders(UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listMoveOrders(tenantId, storeId, status, limit);
  }

  public record MoveOrderWithLines(MoveOrder order, List<MoveOrderLine> lines) {}

  /**
   * Reads a move order.
   *
   * @param tenantId owning tenant
   * @param id the move order to act on
   * @return the move order
   * @throws ApiException a 404 when no such move order exists in this tenant
   */
  public MoveOrderWithLines getMoveOrder(UUID tenantId, UUID id) {
    MoveOrder order =
        repo.findMoveOrder(tenantId, id)
            .orElseThrow(() -> ApiException.notFound("MOVE_ORDER_NOT_FOUND", "No such move order"));
    return new MoveOrderWithLines(order, repo.listMoveOrderLines(id));
  }

  /**
   * Marks a move order picked, so the stock is recorded as moved between zones.
   *
   * @param tenantId owning tenant
   * @param id the move order to pick
   * @return the picked move order with its lines
   * @throws ApiException {@code MOVE_ORDER_NOT_FOUND} (404) when no such order exists; a conflict
   *     when it is not in a pickable state
   */
  public MoveOrderWithLines pickMoveOrder(UUID tenantId, UUID id) {
    MoveOrder existing =
        repo.findMoveOrder(tenantId, id)
            .orElseThrow(() -> ApiException.notFound("MOVE_ORDER_NOT_FOUND", "No such move order"));
    MoveOrder picked =
        repo.pickMoveOrder(
            tenantId,
            id,
            new OutboxRow(
                "MoveOrderCompleted",
                "storeql.inventory.move-order-completed",
                tenantId,
                id,
                Events.moveOrderCompleted(
                    tenantId, id, existing.fromStoreId(), existing.toStoreId())));
    return new MoveOrderWithLines(picked, repo.listMoveOrderLines(id));
  }

  /**
   * Cancels a move order, publishing {@code MoveOrderCancelled}.
   *
   * @param tenantId owning tenant
   * @param id the move order to cancel
   * @return the cancelled move order
   * @throws ApiException {@code MOVE_ORDER_NOT_FOUND} (404) when no such order exists; a conflict
   *     when it has already been picked
   */
  public MoveOrder cancelMoveOrder(UUID tenantId, UUID id) {
    return repo.cancelMoveOrder(
            tenantId,
            id,
            new OutboxRow(
                "MoveOrderCancelled",
                "storeql.inventory.move-order-cancelled",
                tenantId,
                id,
                Events.moveOrderCancelled(tenantId, id)))
        .orElseThrow(
            () ->
                ApiException.unprocessable(
                    "MOVE_ORDER_NOT_CANCELLABLE",
                    "Move order cannot be cancelled in its current state"));
  }

  // ---- transfer orders (Gap #6) ----

  public record TransferOrderWithLines(TransferOrder order, List<TransferOrderLine> lines) {}

  /**
   * Creates a transfer order.
   *
   * @param tenantId owning tenant
   * @param fromStoreId the from store id
   * @param toStoreId the to store id
   * @param transferType the transfer type
   * @param notes free-text notes
   * @param lines the lines to store
   * @return the created transfer order
   */
  public TransferOrderWithLines createTransferOrder(
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      String transferType,
      String notes,
      List<TransferOrderLine> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new ApiException(
          400, "NO_LINES", "Transfer order must have at least one line", List.of(), null);
    }
    String type =
        transferType == null ? TransferOrder.TYPE_DIRECT : transferType.toUpperCase(Locale.ROOT);
    if (!List.of(TransferOrder.TYPE_DIRECT, TransferOrder.TYPE_INTRANSIT).contains(type)) {
      throw new ApiException(
          400,
          "INVALID_TRANSFER_TYPE",
          "transferType must be DIRECT or INTRANSIT",
          List.of(),
          null);
    }
    UUID orderId = Ids.newId();
    Instant now = Instant.now();
    TransferOrder order =
        new TransferOrder(
            orderId,
            tenantId,
            fromStoreId,
            toStoreId,
            type,
            TransferOrder.PENDING,
            notes,
            now,
            null,
            null,
            TransferOrder.SOURCE_MANUAL,
            null);
    List<TransferOrderLine> withIds =
        lines.stream()
            .map(
                l ->
                    new TransferOrderLine(
                        Ids.newId(),
                        tenantId,
                        orderId,
                        l.variantId(),
                        l.requestedQty(),
                        null,
                        null))
            .toList();
    repo.createTransferOrder(order, withIds);
    return new TransferOrderWithLines(order, repo.listTransferOrderLines(tenantId, orderId));
  }

  /**
   * Lists the tenant's transfer orders.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<TransferOrder> listTransferOrders(
      UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listTransferOrders(tenantId, storeId, status, limit);
  }

  /**
   * Reads a transfer order.
   *
   * @param tenantId owning tenant
   * @param id the transfer order to act on
   * @return the transfer order
   * @throws ApiException a 404 when no such transfer order exists in this tenant
   */
  public TransferOrderWithLines getTransferOrder(UUID tenantId, UUID id) {
    TransferOrder order =
        repo.findTransferOrder(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order"));
    return new TransferOrderWithLines(order, repo.listTransferOrderLines(tenantId, id));
  }

  /**
   * Ships a transfer order, deducting from the sending store and putting the stock in transit.
   *
   * <p>The goods belong to neither store until {@link #receiveTransferOrder} lands them, which is
   * why reporting tracks them as an open supply line in between.
   *
   * @param tenantId owning tenant
   * @param id the transfer order to ship
   * @return the shipped transfer order with its lines
   * @throws ApiException {@code TRANSFER_ORDER_NOT_FOUND} (404) when no such order exists; a
   *     conflict when it is not in a shippable state
   */
  public TransferOrderWithLines shipTransferOrder(UUID tenantId, UUID id) {
    TransferOrder existing =
        repo.findTransferOrder(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order"));
    TransferOrder shipped =
        repo.shipTransferOrder(
            tenantId,
            id,
            new OutboxRow(
                "TransferOrderShipped",
                "storeql.inventory.transfer-order-shipped",
                tenantId,
                id,
                Events.transferOrderShipped(
                    tenantId,
                    id,
                    existing.fromStoreId(),
                    existing.toStoreId(),
                    repo.listTransferOrderLines(tenantId, id))));
    return new TransferOrderWithLines(shipped, repo.listTransferOrderLines(tenantId, id));
  }

  /**
   * Receives a shipped transfer, booking the stock into the destination store.
   *
   * <p>Closes the in-transit position the shipment opened.
   *
   * @param tenantId owning tenant
   * @param id the transfer order to receive
   * @return the received transfer order with its lines
   * @throws ApiException {@code TRANSFER_ORDER_NOT_FOUND} (404) when no such order exists; a
   *     conflict when it has not been shipped
   */
  public TransferOrderWithLines receiveTransferOrder(UUID tenantId, UUID id) {
    TransferOrder existing =
        repo.findTransferOrder(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order"));
    TransferOrder received =
        repo.receiveTransferOrder(
            tenantId,
            id,
            new OutboxRow(
                "TransferOrderReceived",
                "storeql.inventory.transfer-order-received",
                tenantId,
                id,
                Events.transferOrderReceived(
                    tenantId,
                    id,
                    existing.fromStoreId(),
                    existing.toStoreId(),
                    repo.listTransferOrderLines(tenantId, id))));
    return new TransferOrderWithLines(received, repo.listTransferOrderLines(tenantId, id));
  }

  /**
   * Cancels a transfer order, publishing {@code TransferOrderCancelled}.
   *
   * @param tenantId owning tenant
   * @param id the transfer order to cancel
   * @return the cancelled transfer order
   * @throws ApiException {@code TRANSFER_ORDER_NOT_FOUND} (404) when no such order exists; a
   *     conflict when it has already shipped
   */
  public TransferOrder cancelTransferOrder(UUID tenantId, UUID id) {
    return repo.cancelTransferOrder(
            tenantId,
            id,
            new OutboxRow(
                "TransferOrderCancelled",
                "storeql.inventory.transfer-order-cancelled",
                tenantId,
                id,
                Events.transferOrderCancelled(tenantId, id)))
        .orElseThrow(
            () ->
                ApiException.unprocessable(
                    "TRANSFER_ORDER_NOT_CANCELLABLE",
                    "Only DRAFT or PENDING transfer orders can be cancelled"));
  }

  // ---- cycle counting (Gap #10) ----

  public record CycleCountWithLines(CycleCountHeader header, List<CycleCountLine> lines) {}

  /**
   * Create a cycle count for a store. Lines are generated from ABC assignments matching abcClasses
   * (defaults to all). Each line captures the current system on-hand qty as a snapshot. If no ABC
   * assignments exist, no lines are generated (count proceeds as manual).
   */
  public CycleCountWithLines createCycleCount(
      UUID tenantId, UUID storeId, String name, String abcClasses, BigDecimal tolerancePct) {

    String classes =
        abcClasses == null || abcClasses.isBlank() ? "A,B,C" : abcClasses.toUpperCase(Locale.ROOT);
    BigDecimal tol = tolerancePct == null ? BigDecimal.valueOf(5) : tolerancePct;
    if (tol.compareTo(BigDecimal.ZERO) < 0 || tol.compareTo(BigDecimal.valueOf(100)) > 0) {
      throw new ApiException(
          400, "INVALID_TOLERANCE", "tolerancePct must be 0–100", List.of(), null);
    }

    UUID headerId = Ids.newId();
    Instant now = Instant.now();
    CycleCountHeader header =
        new CycleCountHeader(
            headerId, tenantId, storeId, name, classes, tol, CycleCountHeader.OPEN, now, null);

    // Generate lines from ABC assignments that match requested classes
    List<String> requestedClasses = List.of(classes.split(","));
    List<AbcAssignment> assignments = abcRepo.listAbcAssignments(tenantId, storeId, null, 1000);
    // Fetch all on-hand quantities in one query instead of one per variant (avoids N+1).
    List<UUID> matchingVariantIds =
        assignments.stream()
            .filter(a -> requestedClasses.contains(a.abcClass()))
            .map(AbcAssignment::variantId)
            .toList();
    Map<UUID, BigDecimal> onHandMap =
        cycleCountRepo.onHandQtyBatch(tenantId, storeId, matchingVariantIds);
    List<CycleCountLine> lines = new ArrayList<>();
    for (AbcAssignment a : assignments) {
      if (!requestedClasses.contains(a.abcClass())) continue;
      BigDecimal onHand = onHandMap.getOrDefault(a.variantId(), BigDecimal.ZERO);
      lines.add(
          new CycleCountLine(
              Ids.newId(),
              tenantId,
              headerId,
              storeId,
              a.variantId(),
              onHand,
              null,
              null,
              null,
              CycleCountLine.OPEN,
              null));
    }

    cycleCountRepo.createCycleCountHeader(header, lines);
    return new CycleCountWithLines(header, lines);
  }

  /**
   * Lists the tenant's cycle counts.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<CycleCountWithLines> listCycleCounts(
      UUID tenantId, UUID storeId, String status, int limit) {
    List<CycleCountHeader> headers =
        cycleCountRepo.listCycleCountHeaders(tenantId, storeId, status, limit);
    List<UUID> headerIds = headers.stream().map(CycleCountHeader::id).toList();
    Map<UUID, List<CycleCountLine>> linesByHeader =
        cycleCountRepo.listCycleCountLinesByHeaders(headerIds);
    return headers.stream()
        .map(h -> new CycleCountWithLines(h, linesByHeader.getOrDefault(h.id(), List.of())))
        .toList();
  }

  /**
   * Reads a cycle count.
   *
   * @param tenantId owning tenant
   * @param headerId the header id
   * @return the cycle count
   * @throws ApiException a 404 when no such cycle count exists in this tenant
   */
  public CycleCountWithLines getCycleCount(UUID tenantId, UUID headerId) {
    CycleCountHeader header =
        cycleCountRepo
            .findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    return new CycleCountWithLines(header, cycleCountRepo.listCycleCountLines(headerId));
  }

  /** Record the physically counted qty for one line; computes variance. */
  public CycleCountLine enterCount(
      UUID tenantId, UUID headerId, UUID lineId, BigDecimal countedQty) {
    if (countedQty.signum() < 0) {
      throw new ApiException(400, "INVALID_COUNT", "countedQty must be >= 0", List.of(), null);
    }
    // Verify line belongs to this header + tenant
    CycleCountLine existing =
        cycleCountRepo
            .findCycleCountLine(tenantId, lineId)
            .orElseThrow(() -> ApiException.notFound("COUNT_LINE_NOT_FOUND", "No such count line"));
    if (!existing.headerId().equals(headerId)) {
      throw new ApiException(
          400, "LINE_HEADER_MISMATCH", "Line does not belong to this count", List.of(), null);
    }
    BigDecimal variance = countedQty.subtract(existing.systemQty());
    BigDecimal variancePct =
        existing.systemQty().compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.valueOf(100)
            : variance
                .abs()
                .divide(existing.systemQty(), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

    // Advance header to IN_PROGRESS if still OPEN
    cycleCountRepo
        .findCycleCountHeader(tenantId, headerId)
        .ifPresent(
            h -> {
              if (CycleCountHeader.OPEN.equals(h.status())) {
                cycleCountRepo.updateHeaderStatus(tenantId, headerId, CycleCountHeader.IN_PROGRESS);
              }
            });

    return cycleCountRepo
        .enterCount(tenantId, lineId, countedQty, variance, variancePct)
        .orElseThrow(
            () ->
                ApiException.unprocessable(
                    "COUNT_LINE_NOT_UPDATABLE", "Line cannot be updated in its current state"));
  }

  public record ApproveResult(int autoApproved, int flagged) {}

  /**
   * For all COUNTED lines: if |variancePct| <= tolerancePct → APPROVED; else REJECTED (awaits
   * manual override or re-count). Advances header to PENDING_APPROVAL if any are flagged, or
   * directly to ADJUSTED-ready state.
   */
  public ApproveResult approveWithTolerance(UUID tenantId, UUID headerId) {
    CycleCountHeader header =
        cycleCountRepo
            .findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    if (CycleCountHeader.ADJUSTED.equals(header.status())
        || CycleCountHeader.CLOSED.equals(header.status())) {
      throw new ApiException(
          422, "CYCLE_COUNT_CLOSED", "Cycle count is already " + header.status(), List.of(), null);
    }

    List<CycleCountLine> lines = cycleCountRepo.listCycleCountLines(headerId);
    List<UUID> toApprove = new ArrayList<>();
    List<UUID> toFlag = new ArrayList<>();
    for (CycleCountLine l : lines) {
      if (!CycleCountLine.COUNTED.equals(l.status())) continue;
      BigDecimal absPct = l.variancePct() == null ? BigDecimal.ZERO : l.variancePct().abs();
      if (absPct.compareTo(header.tolerancePct()) <= 0) toApprove.add(l.id());
      else toFlag.add(l.id());
    }
    cycleCountRepo.bulkUpdateLineStatus(headerId, toApprove, CycleCountLine.APPROVED);
    cycleCountRepo.bulkUpdateLineStatus(headerId, toFlag, CycleCountLine.REJECTED);

    String newHeaderStatus =
        toFlag.isEmpty() ? CycleCountHeader.IN_PROGRESS : CycleCountHeader.PENDING_APPROVAL;
    cycleCountRepo.updateHeaderStatus(tenantId, headerId, newHeaderStatus);
    return new ApproveResult(toApprove.size(), toFlag.size());
  }

  /** Apply stock adjustments for all APPROVED lines, then close the count header. */
  public int adjustCycleCount(UUID tenantId, UUID headerId, UUID actorId) {
    CycleCountHeader header =
        cycleCountRepo
            .findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    if (CycleCountHeader.ADJUSTED.equals(header.status())
        || CycleCountHeader.CLOSED.equals(header.status())) {
      throw new ApiException(
          422, "CYCLE_COUNT_CLOSED", "Cycle count is already closed", List.of(), null);
    }
    var event =
        new OutboxRow(
            "CycleCountAdjusted",
            "storeql.inventory.cycle-count-adjusted",
            tenantId,
            headerId,
            Events.cycleCountAdjusted(tenantId, headerId));
    return repo.applyAdjustments(tenantId, headerId, event, actorId);
  }

  // ---- Lot Genealogy (Gap #11) ----

  /**
   * Creates a lot link.
   *
   * @param tenantId owning tenant
   * @param parentBatchId the parent batch id
   * @param childBatchId the child batch id
   * @param qty the quantity
   * @param relationType the relation type
   * @param notes free-text notes
   * @return the created lot link
   */
  public LotGenealogyLink createLotLink(
      UUID tenantId,
      UUID parentBatchId,
      UUID childBatchId,
      BigDecimal qty,
      String relationType,
      String notes) {
    String type =
        relationType != null && !relationType.isBlank() ? relationType : LotGenealogyLink.SPLIT;
    if (!List.of(LotGenealogyLink.SPLIT, LotGenealogyLink.MERGE, LotGenealogyLink.TRANSFORM)
        .contains(type)) {
      throw new ApiException(
          400,
          "INVALID_RELATION_TYPE",
          "relationType must be SPLIT, MERGE, or TRANSFORM",
          List.of(),
          null);
    }
    var link =
        new LotGenealogyLink(
            Ids.newId(), tenantId, parentBatchId, childBatchId, qty, type, notes, Instant.now());
    return lotGenealogyRepo.createLotLink(link);
  }

  /**
   * Every batch this one came from, transitively — the "where did it come from" half of a recall.
   *
   * @param tenantId owning tenant
   * @param batchId the batch to trace back from
   * @return the ancestor links, empty when the batch was received rather than derived
   */
  public List<LotGenealogyLink> findAncestors(UUID tenantId, UUID batchId) {
    return lotGenealogyRepo.findAncestors(tenantId, batchId);
  }

  /**
   * Every batch derived from this one, transitively — the "where did it go" half of a recall.
   *
   * @param tenantId owning tenant
   * @param batchId the batch to trace forward from
   * @return the descendant links, empty when nothing was split or merged from it
   */
  public List<LotGenealogyLink> findDescendants(UUID tenantId, UUID batchId) {
    return lotGenealogyRepo.findDescendants(tenantId, batchId);
  }

  /**
   * The immediate parent and child links of one batch, without walking the tree.
   *
   * @param tenantId owning tenant
   * @param batchId the batch whose direct links to read
   * @return the one-hop genealogy links
   */
  public List<LotGenealogyLink> findDirectLinks(UUID tenantId, UUID batchId) {
    return lotGenealogyRepo.findDirectLinks(tenantId, batchId);
  }

  // ---- ABC analysis (Gap #9) ----

  /**
   * Run the ABC compile: score all variants in the tenant (optionally one store), rank descending,
   * and assign A/B/C using cumulative-value thresholds. Persists one AbcCompileRun + N
   * AbcAssignment rows (upserted — re-running overwrites previous).
   *
   * <p>Criteria VALUE: score = total_demand_qty * avg_cost_price (annual usage value). Criteria
   * VELOCITY: score = total_demand_qty (movement frequency only). Thresholds are cumulative % of
   * total score: A = 0..thresholdA, B = thresholdA..thresholdAB, C = rest.
   */
  public record AbcCompileResult(AbcCompileRun run, List<AbcAssignment> assignments) {}

  /**
   * Recomputes ABC classes for a store, ranking variants and cutting them into A, B and C bands.
   *
   * <p>Ranked by annual value or by movement velocity, depending on {@code criteria}: the two
   * answer different questions — what the money is tied up in, versus what moves most often.
   *
   * @param tenantId owning tenant
   * @param storeId the store to classify
   * @param criteria {@code VALUE} or {@code VELOCITY}; defaults to {@code VALUE}
   * @param thresholdA the cumulative share at which the A band ends
   * @param thresholdAB the cumulative share at which the B band ends
   * @return the run's outcome, including how many variants landed in each band
   * @throws ApiException a 400 when the criteria is not one of the two
   */
  public AbcCompileResult runAbcCompile(
      UUID tenantId, UUID storeId, String criteria, BigDecimal thresholdA, BigDecimal thresholdAB) {

    String crit =
        criteria == null ? AbcCompileRun.CRITERIA_VALUE : criteria.toUpperCase(Locale.ROOT);
    if (!List.of(AbcCompileRun.CRITERIA_VALUE, AbcCompileRun.CRITERIA_VELOCITY).contains(crit)) {
      throw new ApiException(
          400, "INVALID_ABC_CRITERIA", "criteria must be VALUE or VELOCITY", List.of(), null);
    }
    BigDecimal tA = thresholdA == null ? BigDecimal.valueOf(70) : thresholdA;
    BigDecimal tAB = thresholdAB == null ? BigDecimal.valueOf(90) : thresholdAB;
    if (tA.compareTo(BigDecimal.ZERO) <= 0
        || tA.compareTo(BigDecimal.valueOf(100)) >= 0
        || tAB.compareTo(tA) <= 0
        || tAB.compareTo(BigDecimal.valueOf(100)) >= 0) {
      throw new ApiException(
          400, "INVALID_ABC_THRESHOLDS", "0 < thresholdA < thresholdAB < 100", List.of(), null);
    }

    List<Object[]> raw = abcRepo.abcScoringData(tenantId, storeId);
    if (raw.isEmpty()) {
      UUID runId = Ids.newId();
      AbcCompileRun emptyRun =
          new AbcCompileRun(runId, tenantId, storeId, crit, tA, tAB, 0, Instant.now());
      abcRepo.persistAbcRun(emptyRun, List.of());
      return new AbcCompileResult(emptyRun, List.of());
    }

    // Compute score per row
    record Scored(UUID storeId, UUID variantId, BigDecimal score) {}
    List<Scored> scored = new ArrayList<>();
    for (Object[] row : raw) {
      UUID sid = (UUID) row[0];
      UUID vid = (UUID) row[1];
      BigDecimal demand = (BigDecimal) row[2];
      BigDecimal cost = (BigDecimal) row[3];
      BigDecimal s = AbcCompileRun.CRITERIA_VALUE.equals(crit) ? demand.multiply(cost) : demand;
      scored.add(new Scored(sid, vid, s));
    }
    // Sort descending by score
    scored.sort((a, b) -> b.score().compareTo(a.score()));

    BigDecimal totalScore =
        scored.stream().map(Scored::score).reduce(BigDecimal.ZERO, BigDecimal::add);

    UUID runId = Ids.newId();
    Instant now = Instant.now();
    List<AbcAssignment> assignments = new ArrayList<>();
    BigDecimal cumulative = BigDecimal.ZERO;

    for (int i = 0; i < scored.size(); i++) {
      Scored s = scored.get(i);
      cumulative = cumulative.add(s.score());
      BigDecimal cumulativePct =
          totalScore.compareTo(BigDecimal.ZERO) == 0
              ? BigDecimal.valueOf(100)
              : cumulative
                  .divide(totalScore, 4, java.math.RoundingMode.HALF_UP)
                  .multiply(BigDecimal.valueOf(100));

      String abcClass;
      if (cumulativePct.compareTo(tA) <= 0) abcClass = "A";
      else if (cumulativePct.compareTo(tAB) <= 0) abcClass = "B";
      else abcClass = "C";

      assignments.add(
          new AbcAssignment(
              Ids.newId(),
              tenantId,
              s.storeId(),
              s.variantId(),
              runId,
              abcClass,
              s.score(),
              i + 1,
              now));
    }

    AbcCompileRun run =
        new AbcCompileRun(runId, tenantId, storeId, crit, tA, tAB, assignments.size(), now);
    abcRepo.persistAbcRun(run, assignments);
    return new AbcCompileResult(run, assignments);
  }

  /**
   * Lists the tenant's abc assignments.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param abcClass the abc class
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<AbcAssignment> listAbcAssignments(
      UUID tenantId, UUID storeId, String abcClass, int limit) {
    String cls = abcClass == null ? null : abcClass.toUpperCase(Locale.ROOT);
    if (cls != null && !List.of("A", "B", "C").contains(cls)) {
      throw new ApiException(400, "INVALID_ABC_CLASS", "class must be A, B, or C", List.of(), null);
    }
    return abcRepo.listAbcAssignments(tenantId, storeId, cls, limit);
  }

  /**
   * Reads an abc assignment.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @return the abc assignment
   * @throws ApiException a 404 when no such abc assignment exists in this tenant
   */
  public AbcAssignment getAbcAssignment(UUID tenantId, UUID storeId, UUID variantId) {
    return abcRepo
        .findAbcAssignment(tenantId, storeId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ABC_ASSIGNMENT_NOT_FOUND", "No ABC assignment for this variant"));
  }

  // ---- safety stock (Gap #8) ----

  /**
   * Sets how safety stock is calculated for a variant at a store.
   *
   * @param tenantId owning tenant
   * @param storeId the store the parameters apply at
   * @param variantId the variant concerned
   * @param method the calculation method to use
   * @param leadTimeDays how long replenishment takes, which the buffer has to cover
   * @param serviceLevelPct the target availability, which sets how much buffer that implies
   * @return the stored parameters
   */
  public SafetyStockParams setSafetyStockParams(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String method,
      Integer leadTimeDays,
      BigDecimal serviceLevelPct,
      BigDecimal userDefinedPct) {

    String m = method == null ? SafetyStockParams.METHOD_MAD : method.toUpperCase(Locale.ROOT);
    if (!List.of(SafetyStockParams.METHOD_MAD, SafetyStockParams.METHOD_USER_DEFINED).contains(m)) {
      throw new ApiException(
          400,
          "INVALID_SAFETY_STOCK_METHOD",
          "method must be MAD or USER_DEFINED",
          List.of(),
          null);
    }
    if (SafetyStockParams.METHOD_USER_DEFINED.equals(m)
        && (userDefinedPct == null || userDefinedPct.signum() <= 0)) {
      throw new ApiException(
          400,
          "USER_DEFINED_PCT_REQUIRED",
          "userDefinedPct > 0 is required when method is USER_DEFINED",
          List.of(),
          null);
    }
    int ltd = leadTimeDays == null || leadTimeDays < 1 ? 7 : leadTimeDays;
    BigDecimal slp = serviceLevelPct == null ? BigDecimal.valueOf(95) : serviceLevelPct;

    var params =
        new SafetyStockParams(
            Ids.newId(),
            tenantId,
            storeId,
            variantId,
            m,
            ltd,
            slp,
            userDefinedPct,
            null,
            null,
            Instant.now());
    return safetyStockRepo.upsertSafetyStockParams(params);
  }

  /**
   * Reads a safety stock params.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @return the safety stock params
   * @throws ApiException a 404 when no such safety stock params exists in this tenant
   */
  public SafetyStockParams getSafetyStockParams(UUID tenantId, UUID storeId, UUID variantId) {
    return safetyStockRepo
        .findSafetyStockParams(tenantId, storeId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "SAFETY_STOCK_PARAMS_NOT_FOUND", "No safety stock params for this variant"));
  }

  /**
   * Lists the tenant's safety stock params.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<SafetyStockParams> listSafetyStockParams(UUID tenantId, UUID storeId, int limit) {
    return safetyStockRepo.listSafetyStockParams(tenantId, storeId, limit);
  }

  /**
   * Re-compute safety_stock_qty for every (store, variant) row that has params. Returns the count
   * of rows updated. Rows with no demand history get safety_stock_qty = 0. Optional storeId/
   * variantId narrow the run to one row.
   */
  public int computeSafetyStock(UUID tenantId, UUID storeId, UUID variantId) {
    List<SafetyStockParams> targets;
    if (variantId != null && storeId != null) {
      targets =
          safetyStockRepo
              .findSafetyStockParams(tenantId, storeId, variantId)
              .map(List::of)
              .orElse(List.of());
    } else {
      targets = safetyStockRepo.listSafetyStockParamsAll(tenantId, storeId);
    }
    if (targets.isEmpty()) return 0;

    // One batched read for every target's demand history, instead of one query per row.
    var bucketsByStoreThenVariant = demandHistoryRepo.demandBucketsBatch(tenantId, targets, 30);
    Instant now = Instant.now();
    var qtyByStoreThenVariant = new java.util.HashMap<UUID, java.util.Map<UUID, BigDecimal>>();
    for (SafetyStockParams p : targets) {
      List<DemandBucket> buckets =
          bucketsByStoreThenVariant
              .getOrDefault(p.storeId(), java.util.Map.of())
              .getOrDefault(p.variantId(), List.of());
      BigDecimal qty = computeForOne(p, buckets);
      qtyByStoreThenVariant
          .computeIfAbsent(p.storeId(), k -> new java.util.HashMap<>())
          .put(p.variantId(), qty);
    }
    // One batched write for every target, instead of one connection checkout per row.
    return safetyStockRepo.updateSafetyStockQtyBatch(tenantId, qtyByStoreThenVariant, now);
  }

  /** {@code buckets} is the last 30 daily buckets (enough for meaningful MAD), oldest-first. */
  private BigDecimal computeForOne(SafetyStockParams p, List<DemandBucket> buckets) {
    if (buckets.isEmpty()) return BigDecimal.ZERO;

    int n = buckets.size();
    BigDecimal sum =
        buckets.stream().map(DemandBucket::demandQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal mean = sum.divide(BigDecimal.valueOf(n), 6, java.math.RoundingMode.HALF_UP);

    if (SafetyStockParams.METHOD_USER_DEFINED.equals(p.method())) {
      // safety_stock = (mean * lead_time_days) * (userDefinedPct / 100)
      BigDecimal avgOverLead = mean.multiply(BigDecimal.valueOf(p.leadTimeDays()));
      BigDecimal pct =
          p.userDefinedPct().divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
      return avgOverLead.multiply(pct).setScale(3, java.math.RoundingMode.HALF_UP);
    }

    // MAD = mean of |demand_i − mean|
    BigDecimal madSum =
        buckets.stream()
            .map(b -> b.demandQty().subtract(mean).abs())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal mad = madSum.divide(BigDecimal.valueOf(n), 6, java.math.RoundingMode.HALF_UP);

    // z-score lookup for common service levels; linear interpolation not needed — standard table
    double sl = p.serviceLevelPct().doubleValue();
    double z;
    if (sl >= 99.0) z = 2.326;
    else if (sl >= 98.0) z = 2.054;
    else if (sl >= 97.0) z = 1.881;
    else if (sl >= 95.0) z = 1.645;
    else if (sl >= 90.0) z = 1.282;
    else if (sl >= 85.0) z = 1.036;
    else z = 0.842;

    // safety_stock = z * MAD * sqrt(lead_time_days)
    double sqrtLt = Math.sqrt(p.leadTimeDays());
    BigDecimal ss =
        mad.multiply(BigDecimal.valueOf(z))
            .multiply(BigDecimal.valueOf(sqrtLt))
            .setScale(3, java.math.RoundingMode.HALF_UP);
    return ss.max(BigDecimal.ZERO);
  }

  // ---- sweeper support ----
  public List<com.storeql.inventory.repo.InventoryRepository.ReservationRef>
      expiredReservationsWithTenant(int limit) {
    return repo.expiredHeldReservationsWithTenant(limit);
  }

  static UUID parseUuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  /**
   * Creates a physical inventory.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param notes free-text notes
   * @return the created physical inventory
   */
  public PhysicalInventory createPhysicalInventory(UUID tenantId, UUID storeId, String notes) {
    UUID id = Ids.newId();
    var pi =
        new PhysicalInventory(
            id, tenantId, storeId, PhysicalInventory.OPEN, notes, Instant.now(), null);
    var event =
        new OutboxRow(
            "PhysicalInventoryCreated",
            "storeql.inventory.physical-inventory-created",
            tenantId,
            id,
            Events.physicalInventoryCreated(tenantId, id, storeId));
    return physicalInventoryRepo.createPhysicalInventory(pi, event);
  }

  /**
   * Reads a physical inventory.
   *
   * @param tenantId owning tenant
   * @param id the physical inventory to act on
   * @return the physical inventory
   * @throws ApiException a 404 when no such physical inventory exists in this tenant
   */
  public PhysicalInventory getPhysicalInventory(UUID tenantId, UUID id) {
    return physicalInventoryRepo
        .findPhysicalInventory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("PI_NOT_FOUND", "Physical inventory not found"));
  }

  /**
   * Lists the tenant's physical inventories.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @return the matching rows
   */
  public List<PhysicalInventory> listPhysicalInventories(UUID tenantId, String storeId) {
    UUID storeUuid = storeId != null ? parseUuid(storeId, "storeId") : null;
    return physicalInventoryRepo.listPhysicalInventories(tenantId, storeUuid);
  }

  /**
   * Adds a tag.
   *
   * @param tenantId owning tenant
   * @param piId the pi id
   * @param variantId the product variant concerned
   * @param zoneId the zone id
   * @param systemQty the system qty
   * @return the added tag
   * @throws ApiException a 404 when no such tag exists in this tenant
   */
  public PhysicalInventoryTag addTag(
      UUID tenantId, UUID piId, UUID variantId, UUID zoneId, BigDecimal systemQty) {
    getPhysicalInventory(tenantId, piId);
    var tag =
        new PhysicalInventoryTag(
            Ids.newId(),
            tenantId,
            piId,
            variantId,
            zoneId,
            systemQty,
            null,
            null,
            PhysicalInventoryTag.OPEN,
            null);
    return physicalInventoryRepo.addTag(tag);
  }

  /**
   * Records the counted quantity on one physical-inventory tag.
   *
   * <p>Recording a count does not adjust stock — the variance is only posted when the count is
   * completed, so a part-finished count leaves the books alone.
   *
   * @param tenantId owning tenant
   * @param piId the physical inventory the tag belongs to
   * @param tagId the tag being counted
   * @param countedQty the quantity actually found
   * @return the tag with its recorded count
   */
  public PhysicalInventoryTag countTag(
      UUID tenantId, UUID piId, UUID tagId, BigDecimal countedQty) {
    return physicalInventoryRepo.countTag(tenantId, piId, tagId, countedQty);
  }

  /**
   * Completes a physical inventory, posting the counted variances and publishing {@code
   * PhysicalInventoryCompleted}.
   *
   * <p>This is the point stock actually moves to match the count, so it is terminal.
   *
   * @param tenantId owning tenant
   * @param piId the physical inventory to complete
   * @return the completed physical inventory
   * @throws ApiException a 404 when no such physical inventory exists in this tenant
   */
  public PhysicalInventory completePhysicalInventory(UUID tenantId, UUID piId) {
    getPhysicalInventory(tenantId, piId);
    var event =
        new OutboxRow(
            "PhysicalInventoryCompleted",
            "storeql.inventory.physical-inventory-completed",
            tenantId,
            piId,
            Events.physicalInventoryCompleted(tenantId, piId));
    return physicalInventoryRepo.completePhysicalInventory(tenantId, piId, event);
  }

  /**
   * Lists the tenant's tags.
   *
   * @param tenantId owning tenant
   * @param piId the pi id
   * @return the matching rows
   */
  public List<PhysicalInventoryTag> listTags(UUID tenantId, UUID piId) {
    return physicalInventoryRepo.listTags(tenantId, piId);
  }

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────────

  /**
   * Creates or replaces a rop plan.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param leadTimeDays the lead time days
   * @param orderingCost the ordering cost
   * @param holdingCostPct the holding cost pct
   * @param unitCost the unit cost
   * @return the stored rop plan
   */
  public ReorderPointPlan upsertRopPlan(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      int leadTimeDays,
      java.math.BigDecimal orderingCost,
      java.math.BigDecimal holdingCostPct,
      java.math.BigDecimal unitCost) {
    var plan =
        new ReorderPointPlan(
            null,
            tenantId,
            storeId,
            variantId,
            leadTimeDays,
            orderingCost,
            holdingCostPct,
            unitCost,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var event =
        new OutboxRow(
            "RopPlanUpdated",
            "storeql.inventory.rop-plan-updated",
            tenantId,
            variantId,
            Events.ropPlanUpdated(tenantId, storeId, variantId));
    return ropRepo.upsertRopPlan(plan, event);
  }

  /**
   * Reads a rop plan.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @return the rop plan
   * @throws ApiException a 404 when no such rop plan exists in this tenant
   */
  public ReorderPointPlan getRopPlan(UUID tenantId, UUID storeId, UUID variantId) {
    return ropRepo
        .findRopPlan(tenantId, storeId, variantId)
        .orElseThrow(() -> ApiException.notFound("ROP_NOT_FOUND", "ROP plan not found"));
  }

  /**
   * Lists the tenant's rop plans.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @return the matching rows
   */
  public List<ReorderPointPlan> listRopPlans(UUID tenantId, UUID storeId) {
    return ropRepo.listRopPlans(tenantId, storeId);
  }

  /**
   * Recomputes reorder-point plans for a store from its demand history and lead times.
   *
   * @param tenantId owning tenant
   * @param storeId the store to plan for
   * @return how many plans were written
   */
  public int computeRopPlans(UUID tenantId, UUID storeId) {
    return ropRepo.computeRopPlans(tenantId, storeId);
  }

  // ── Gap #18: Kanban Replenishment ────────────────────────────────────────────

  private static final java.util.Set<String> KANBAN_TYPES =
      java.util.Set.of("SUPPLIER", "INTER_ORG", "INTRA_ORG", "PRODUCTION");

  /**
   * Creates a kanban card.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param kanbanType the kanban type
   * @param reorderQty the reorder qty
   * @param sourceStoreId the source store id
   * @param supplierRef the supplier ref
   * @param notes free-text notes
   * @return the created kanban card
   */
  public KanbanCard createKanbanCard(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String kanbanType,
      java.math.BigDecimal reorderQty,
      UUID sourceStoreId,
      String supplierRef,
      String notes) {
    if (!KANBAN_TYPES.contains(kanbanType)) {
      throw ApiException.badRequest(
          "INVALID_KANBAN_TYPE", "kanban type must be one of " + KANBAN_TYPES);
    }
    UUID cardId = Ids.newId();
    KanbanCard card =
        new KanbanCard(
            cardId,
            tenantId,
            storeId,
            variantId,
            kanbanType,
            KanbanCard.EMPTY,
            reorderQty,
            sourceStoreId,
            supplierRef,
            notes,
            null,
            null,
            null,
            Instant.now(),
            null,
            null);
    var event =
        new OutboxRow(
            "KanbanCreated",
            "storeql.inventory.kanban-created",
            tenantId,
            cardId,
            Events.kanbanCreated(tenantId, cardId, storeId, variantId, kanbanType));
    return kanbanRepo.createKanbanCard(card, event);
  }

  /**
   * Triggers a kanban card, signalling that its bin has run down and needs refilling.
   *
   * @param tenantId owning tenant
   * @param cardId the card to trigger
   * @param notes free-text note recorded against the trigger
   * @return the card in its triggered state
   * @throws ApiException {@code KANBAN_NOT_FOUND} (404) when no such card exists in this tenant
   */
  public KanbanCard triggerKanbanCard(UUID tenantId, UUID cardId, String notes) {
    KanbanCard card =
        kanbanRepo
            .findKanbanCard(tenantId, cardId)
            .orElseThrow(() -> ApiException.notFound("KANBAN_NOT_FOUND", "kanban card not found"));
    var event =
        new OutboxRow(
            "KanbanTriggered",
            "storeql.inventory.kanban-triggered",
            tenantId,
            cardId,
            Events.kanbanTriggered(tenantId, cardId, card.storeId(), card.variantId()));
    return kanbanRepo.triggerKanbanCard(tenantId, cardId, notes, event);
  }

  /**
   * Marks a triggered kanban card refilled, returning it to circulation.
   *
   * @param tenantId owning tenant
   * @param cardId the card to replenish
   * @return the card back in its filled state
   * @throws ApiException {@code KANBAN_NOT_FOUND} (404) when no such card exists in this tenant
   */
  public KanbanCard replenishKanbanCard(UUID tenantId, UUID cardId) {
    KanbanCard card =
        kanbanRepo
            .findKanbanCard(tenantId, cardId)
            .orElseThrow(() -> ApiException.notFound("KANBAN_NOT_FOUND", "kanban card not found"));
    var event =
        new OutboxRow(
            "KanbanReplenished",
            "storeql.inventory.kanban-replenished",
            tenantId,
            cardId,
            Events.kanbanReplenished(tenantId, cardId, card.storeId(), card.variantId()));
    return kanbanRepo.replenishKanbanCard(tenantId, cardId, event);
  }

  /**
   * Reads a kanban card.
   *
   * @param tenantId owning tenant
   * @param cardId the card id
   * @return the kanban card
   * @throws ApiException a 404 when no such kanban card exists in this tenant
   */
  public KanbanCard getKanbanCard(UUID tenantId, UUID cardId) {
    return kanbanRepo
        .findKanbanCard(tenantId, cardId)
        .orElseThrow(() -> ApiException.notFound("KANBAN_NOT_FOUND", "kanban card not found"));
  }

  /**
   * Lists the tenant's kanban cards.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param status the status to set
   * @return the matching rows
   */
  public List<KanbanCard> listKanbanCards(UUID tenantId, UUID storeId, String status) {
    return kanbanRepo.listKanbanCards(tenantId, storeId, status);
  }

  // ── Gap #17: Costing Methods ────────────────────────────────────────────────

  /**
   * Creates or replaces a costing method.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param method the calculation method
   * @param averageCost the average cost
   * @return the stored costing method
   */
  public CostingMethod upsertCostingMethod(
      UUID tenantId, UUID storeId, UUID variantId, String method, BigDecimal averageCost) {
    if (!"FIFO".equals(method) && !"AVERAGE".equals(method)) {
      throw ApiException.badRequest("INVALID_COSTING_METHOD", "method must be FIFO or AVERAGE");
    }
    if (averageCost != null && averageCost.signum() < 0) {
      throw ApiException.badRequest(
          "INVALID_AVERAGE_COST", "averageCost cannot be negative — got " + averageCost);
    }
    var event =
        new OutboxRow(
            "CostingMethodUpdated",
            "storeql.inventory.costing-method-updated",
            tenantId,
            variantId,
            Events.costingMethodUpdated(tenantId, storeId, variantId, method));
    return costingRepo.upsertCostingMethod(
        tenantId, storeId, variantId, method, averageCost, event);
  }

  /**
   * Reads a costing method.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @return the costing method
   * @throws ApiException a 404 when no such costing method exists in this tenant
   */
  public CostingMethod getCostingMethod(UUID tenantId, UUID storeId, UUID variantId) {
    return costingRepo
        .findCostingMethod(tenantId, storeId, variantId)
        .orElseThrow(
            () -> ApiException.notFound("COSTING_METHOD_NOT_FOUND", "costing method not found"));
  }

  /**
   * Lists the tenant's costing methods.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @return the matching rows
   */
  public List<CostingMethod> listCostingMethods(UUID tenantId, UUID storeId) {
    return costingRepo.listCostingMethods(tenantId, storeId);
  }

  /**
   * Opens an inventory accounting period for a store.
   *
   * @param tenantId owning tenant
   * @param storeId the store the period belongs to
   * @param periodName the period's display name
   * @param periodDate the period's date, as an ISO date string
   * @return the opened period
   * @throws ApiException a 400 when {@code periodDate} is not a valid date
   */
  public AccountingPeriod openPeriod(
      UUID tenantId, UUID storeId, String periodName, String periodDate) {
    LocalDate date = com.storeql.web.Parsing.date(periodDate, "periodDate");
    var event =
        new OutboxRow(
            "AccountingPeriodOpened",
            "storeql.inventory.accounting-period-opened",
            tenantId,
            storeId,
            Events.accountingPeriodOpened(tenantId, storeId, periodName, periodDate));
    return costingRepo.openPeriod(tenantId, storeId, periodName, date, event);
  }

  /**
   * Closes an accounting period, freezing its valuation and publishing {@code
   * AccountingPeriodClosed}.
   *
   * @param tenantId owning tenant
   * @param periodId the period to close
   * @return the closed period
   * @throws ApiException a 404 when no such period exists in this tenant
   */
  public AccountingPeriod closePeriod(UUID tenantId, UUID periodId) {
    var event =
        new OutboxRow(
            "AccountingPeriodClosed",
            "storeql.inventory.accounting-period-closed",
            tenantId,
            periodId,
            Events.accountingPeriodClosed(tenantId, periodId));
    return costingRepo.closePeriod(tenantId, periodId, event);
  }

  /**
   * Reads a period.
   *
   * @param tenantId owning tenant
   * @param periodId the period id
   * @return the period
   * @throws ApiException a 404 when no such period exists in this tenant
   */
  public AccountingPeriod getPeriod(UUID tenantId, UUID periodId) {
    return costingRepo
        .findPeriod(tenantId, periodId)
        .orElseThrow(
            () -> ApiException.notFound("PERIOD_NOT_FOUND", "accounting period not found"));
  }

  /**
   * Lists the tenant's periods.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @return the matching rows
   */
  public List<AccountingPeriod> listPeriods(UUID tenantId, UUID storeId) {
    return costingRepo.listPeriods(tenantId, storeId);
  }

  // ── Tier-1 Gap #21: Transaction reason codes ─────────────────────────────

  /**
   * Creates a reason code.
   *
   * @param tenantId owning tenant
   * @param code the code to match
   * @param description the free-text description
   * @return the created reason code
   */
  public ReasonCode createReasonCode(UUID tenantId, String code, String description) {
    return refData.insertReasonCode(tenantId, code.toUpperCase(Locale.ROOT), description);
  }

  /**
   * Lists the tenant's reason codes.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<ReasonCode> listReasonCodes(UUID tenantId) {
    return refData.listReasonCodes(tenantId);
  }

  /**
   * Enables or disables a movement reason code.
   *
   * <p>Disabling rather than deleting, so historical movements keep resolving the code they were
   * recorded against.
   *
   * @param tenantId owning tenant
   * @param id the reason code to switch
   * @param active {@code true} to enable it, {@code false} to retire it
   * @return the reason code in its new state
   */
  public ReasonCode setReasonCodeActive(UUID tenantId, UUID id, boolean active) {
    return refData.setReasonCodeActive(tenantId, id, active);
  }

  // ── Tier-1 Gap #22: Transaction source types ──────────────────────────────

  /**
   * Creates a source type.
   *
   * @param tenantId owning tenant
   * @param code the code to match
   * @param description the free-text description
   * @return the created source type
   */
  public TransactionSourceType createSourceType(UUID tenantId, String code, String description) {
    return refData.insertSourceType(tenantId, code.toUpperCase(Locale.ROOT), description);
  }

  /**
   * Lists the tenant's source types.
   *
   * @param tenantId owning tenant
   * @return the matching rows
   */
  public List<TransactionSourceType> listSourceTypes(UUID tenantId) {
    return refData.listSourceTypes(tenantId);
  }

  /**
   * Enables or disables a transaction source type.
   *
   * <p>Disabling rather than deleting, so historical movements keep resolving the type they were
   * recorded against.
   *
   * @param tenantId owning tenant
   * @param id the source type to switch
   * @param active {@code true} to enable it, {@code false} to retire it
   * @return the source type in its new state
   */
  public TransactionSourceType setSourceTypeActive(UUID tenantId, UUID id, boolean active) {
    return refData.setSourceTypeActive(tenantId, id, active);
  }

  // ── Tier-1 Gap #23: Lot actions (split / merge) ───────────────────────────

  public record LotSplitResult(Batch newBatch, LotAction action) {}

  /**
   * Splits part of a batch into a new one, recording the genealogy link.
   *
   * <p>The child inherits the parent's cost and expiry, and the link is what lets a recall trace
   * from either end.
   *
   * @param tenantId owning tenant
   * @param sourceBatchId the batch to split from
   * @param qty the quantity to move into the new batch
   * @param batchNo the new batch's number, or {@code null} to mint one
   * @param notes free-text note recorded against the split
   * @return the source and new batches as they now stand
   * @throws ApiException {@code BATCH_NOT_FOUND} (404) when the source does not exist; a conflict
   *     when the quantity exceeds what the source holds
   */
  public LotSplitResult splitLot(
      UUID tenantId, UUID sourceBatchId, BigDecimal qty, String batchNo, String notes) {
    Batch source =
        repo.getBatch(tenantId, sourceBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Source batch not found"));
    if (source.remainingQty().compareTo(qty) < 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_QTY", "Split qty exceeds remaining qty on source batch");
    }
    String newBatchNo =
        batchNo != null ? batchNo : source.batchNo() + "-SPLIT-" + Ids.shortRef(Ids.newId());
    UUID newBatchId = Ids.newId();
    Batch splitBatch =
        new Batch(
            newBatchId,
            tenantId,
            source.storeId(),
            source.variantId(),
            newBatchNo,
            qty,
            qty,
            source.costPrice(),
            source.expiryDate(),
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            source.grade(),
            source.zoneId(),
            source.ownership(),
            source.ownerSupplierId());
    OutboxRow splitEvent =
        new OutboxRow(
            "LotSplit",
            "storeql.inventory.lot-split",
            tenantId,
            sourceBatchId,
            Events.lotSplit(tenantId, sourceBatchId, newBatchId, qty));
    Batch newBatch = repo.receive(splitBatch, "LOT_SPLIT", sourceBatchId, splitEvent, null);
    LotAction action =
        lotActionRepo.insertLotAction(
            tenantId, LotAction.SPLIT, sourceBatchId, newBatch.id(), qty, notes);
    return new LotSplitResult(newBatch, action);
  }

  public record LotMergeResult(Batch targetBatch, LotAction action) {}

  /**
   * Merges quantity from one batch into another, recording the genealogy link.
   *
   * <p>Merging mixes provenance, so the link matters: after this, a recall on either source has to
   * reach the merged batch.
   *
   * @param tenantId owning tenant
   * @param sourceBatchId the batch to take stock from
   * @param targetBatchId the batch to merge it into
   * @param qty the quantity to move
   * @param notes free-text note recorded against the merge
   * @param actorId the user performing the merge
   * @return both batches as they now stand
   * @throws ApiException {@code BATCH_NOT_FOUND} (404) when either batch does not exist; a conflict
   *     when the quantity exceeds what the source holds
   */
  public LotMergeResult mergeLot(
      UUID tenantId,
      UUID sourceBatchId,
      UUID targetBatchId,
      BigDecimal qty,
      String notes,
      UUID actorId) {
    Batch source =
        repo.getBatch(tenantId, sourceBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Source batch not found"));
    Batch target =
        repo.getBatch(tenantId, targetBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Target batch not found"));
    if (source.remainingQty().compareTo(qty) < 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_QTY", "Merge qty exceeds remaining qty on source batch");
    }
    OutboxRow mergeEvent =
        new OutboxRow(
            "LotMerge",
            "storeql.inventory.lot-merge",
            tenantId,
            sourceBatchId,
            Events.lotMerge(tenantId, sourceBatchId, targetBatchId, qty));
    OutboxRow addEvent =
        new OutboxRow(
            "LotMergeIn",
            "storeql.inventory.lot-merge-in",
            tenantId,
            targetBatchId,
            Events.lotMerge(tenantId, sourceBatchId, targetBatchId, qty));
    // Deduct from source and add to target atomically — see mergeLotAdjust's Javadoc.
    repo.mergeLotAdjust(
        tenantId,
        source.storeId(),
        source.variantId(),
        mergeEvent,
        target.storeId(),
        target.variantId(),
        addEvent,
        qty,
        MovementAttribution.by(actorId, null));
    Batch updated =
        repo.getBatch(tenantId, targetBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Target batch not found"));
    LotAction action =
        lotActionRepo.insertLotAction(
            tenantId, LotAction.MERGE, sourceBatchId, targetBatchId, qty, notes);
    return new LotMergeResult(updated, action);
  }

  /**
   * Lists the tenant's lot actions.
   *
   * @param tenantId owning tenant
   * @param batchId the batch id
   * @return the matching rows
   */
  public List<LotAction> listLotActions(UUID tenantId, UUID batchId) {
    return lotActionRepo.listLotActions(tenantId, batchId);
  }

  // ── Tier-1 Gap #24: Expiry alert query ────────────────────────────────────

  /**
   * Lists the tenant's expiring batches.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param withinDays the within days
   * @return the matching rows
   */
  public List<Batch> listExpiringBatches(UUID tenantId, UUID storeId, int withinDays) {
    if (withinDays < 1 || withinDays > 3650) {
      throw ApiException.badRequest("INVALID_DAYS", "withinDays must be 1–3650");
    }
    return repo.listExpiringBatches(tenantId, storeId, withinDays);
  }

  // ── Tier-1 Gap #25: Grade control ─────────────────────────────────────────

  /**
   * Updates a batch grade.
   *
   * @param tenantId owning tenant
   * @param batchId the batch id
   * @param grade the stock grade
   * @return the updated batch grade
   */
  public Batch updateBatchGrade(UUID tenantId, UUID batchId, String grade) {
    if (grade == null || grade.isBlank()) {
      throw ApiException.badRequest("INVALID_GRADE", "grade must not be blank");
    }
    return repo.updateBatchGrade(tenantId, batchId, grade.toUpperCase(Locale.ROOT));
  }

  // ── Tier-1 Gap #26: Lot UOM conversions ──────────────────────────────────

  /**
   * Creates or replaces a lot uom conversion.
   *
   * @param tenantId owning tenant
   * @param batchId the batch id
   * @param fromUom the from uom
   * @param toUom the to uom
   * @param factor the conversion factor
   * @param notes free-text notes
   * @return the stored lot uom conversion
   */
  public LotUomConversion upsertLotUomConversion(
      UUID tenantId, UUID batchId, String fromUom, String toUom, BigDecimal factor, String notes) {
    if (factor.compareTo(BigDecimal.ZERO) <= 0) {
      throw ApiException.badRequest("INVALID_FACTOR", "UOM conversion factor must be positive");
    }
    return planningConfig.upsertLotUomConversion(tenantId, batchId, fromUom, toUom, factor, notes);
  }

  /**
   * Lists the tenant's lot uom conversions.
   *
   * @param tenantId owning tenant
   * @param batchId the batch id
   * @return the matching rows
   */
  public List<LotUomConversion> listLotUomConversions(UUID tenantId, UUID batchId) {
    return planningConfig.listLotUomConversions(tenantId, batchId);
  }

  // ── Tier-1 Gap #27: PAR levels ────────────────────────────────────────────

  /**
   * Creates or replaces a par level.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param parQty the par qty
   * @param uom the par to persist
   * @param reviewCycle the review cycle
   * @return the stored par level
   */
  public ParLevelConfig upsertParLevel(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal parQty,
      String uom,
      String reviewCycle) {
    if (parQty.compareTo(BigDecimal.ZERO) <= 0) {
      throw ApiException.badRequest("INVALID_PAR_QTY", "parQty must be positive");
    }
    String cycle =
        reviewCycle == null ? ParLevelConfig.DAILY : reviewCycle.toUpperCase(Locale.ROOT);
    if (!Set.of(ParLevelConfig.DAILY, ParLevelConfig.WEEKLY, ParLevelConfig.MONTHLY)
        .contains(cycle)) {
      throw ApiException.badRequest(
          "INVALID_REVIEW_CYCLE", "reviewCycle must be DAILY, WEEKLY, or MONTHLY");
    }
    return planningConfig.upsertParLevel(tenantId, storeId, variantId, parQty, uom, cycle);
  }

  /**
   * Lists the tenant's par levels.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @return the matching rows
   */
  public List<ParLevelConfig> listParLevels(UUID tenantId, UUID storeId) {
    return planningConfig.listParLevels(tenantId, storeId);
  }

  /**
   * Reads a par level.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @return the par level
   * @throws ApiException a 404 when no such par level exists in this tenant
   */
  public ParLevelConfig getParLevel(UUID tenantId, UUID storeId, UUID variantId) {
    return planningConfig
        .findParLevel(tenantId, storeId, variantId)
        .orElseThrow(() -> ApiException.notFound("PAR_LEVEL_NOT_FOUND", "No PAR level configured"));
  }

  // ── Tier-1 Gap #28: Order modifiers ──────────────────────────────────────

  /**
   * Updates a rop order modifiers.
   *
   * @param tenantId owning tenant
   * @param ropId the rop id
   * @param min the record to persist
   * @param max the record to persist
   * @param lotMult the lot mult
   * @return the updated rop order modifiers
   */
  public ReorderPointPlan updateRopOrderModifiers(
      UUID tenantId, UUID ropId, BigDecimal min, BigDecimal max, BigDecimal lotMult) {
    return ropRepo.updateRopOrderModifiers(tenantId, ropId, min, max, lotMult);
  }

  /**
   * Updates a kanban order modifiers.
   *
   * @param tenantId owning tenant
   * @param cardId the card id
   * @param min the record to persist
   * @param max the record to persist
   * @param lotMult the lot mult
   * @return the updated kanban order modifiers
   */
  public KanbanCard updateKanbanOrderModifiers(
      UUID tenantId, UUID cardId, BigDecimal min, BigDecimal max, BigDecimal lotMult) {
    return kanbanRepo.updateKanbanOrderModifiers(tenantId, cardId, min, max, lotMult);
  }

  // ── Tier-1 Gap #29: Batch (bulk) reservations ─────────────────────────────

  public record BulkReserveResult(int succeeded, int failed, List<Reservation> results) {}

  /**
   * Holds stock for many lines in one call, so a multi-line checkout costs one round trip.
   *
   * <p>Lines are attempted together; the result reports which succeeded and which could not be
   * held, rather than failing the whole basket on one short line.
   *
   * @param tenantId owning tenant
   * @param requests the lines to hold
   * @return the holds placed and the lines that failed, with why
   */
  public BulkReserveResult bulkReserve(
      UUID tenantId, List<com.storeql.inventory.dto.Dtos.ReserveRequest> requests) {
    List<InventoryRepository.ReserveBatchItem> items = new ArrayList<>(requests.size());
    int parseFailed = 0;
    for (var req : requests) {
      try {
        UUID storeId = Ids.parse(req.storeId());
        UUID variantId = Ids.parse(req.variantId());
        UUID orderId = req.orderId() != null ? Ids.parse(req.orderId()) : null;
        long ttl = req.ttlSeconds() == null ? config.reservationTtlSeconds() : req.ttlSeconds();
        UUID id = Ids.newId();
        var reservation =
            new Reservation(
                id,
                tenantId,
                storeId,
                variantId,
                req.qty(),
                orderId,
                Reservation.HELD,
                Instant.now().plusSeconds(ttl),
                Instant.now());
        var event =
            new OutboxRow(
                "StockReserved",
                "storeql.inventory.stock-reserved",
                tenantId,
                id,
                Events.stockReserved(tenantId, storeId, variantId, id, req.qty()));
        items.add(new InventoryRepository.ReserveBatchItem(reservation, event, null));
      } catch (Exception e) {
        parseFailed++;
      }
    }

    List<Reservation> succeeded = new ArrayList<>();
    int dbFailed = 0;
    if (!items.isEmpty()) {
      for (var outcome : repo.reserveBatch(items)) {
        if (outcome.succeeded()) {
          succeeded.add(outcome.reservation());
        } else {
          dbFailed++;
        }
      }
    }
    return new BulkReserveResult(succeeded.size(), parseFailed + dbFailed, succeeded);
  }

  // ── Tier-1 Gap #30: Purge transaction history ─────────────────────────────

  /**
   * Archives and removes stock movements older than a cutoff.
   *
   * <p>Refuses a cutoff inside the last 90 days: {@code stock_movements} is append-only audit, and
   * a too-recent purge would destroy the trail behind current stock rather than trimming history.
   *
   * @param tenantId owning tenant
   * @param before purge movements recorded strictly before this instant
   * @return how many movements were purged
   * @throws ApiException {@code PURGE_TOO_RECENT} (400) when the cutoff is less than 90 days ago
   */
  public int purgeMovementsBefore(UUID tenantId, Instant before) {
    Instant cutoff = Instant.now().minusSeconds(90L * 24 * 3600);
    if (before.isAfter(cutoff)) {
      throw ApiException.badRequest(
          "PURGE_TOO_RECENT", "Cannot purge movements less than 90 days old");
    }
    return movementArchiveRepo.purgeMovementsBefore(tenantId, before);
  }

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  /**
   * Creates or replaces a zone gl mapping.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @param zoneId the zone id
   * @param nominalCode the nominal code
   * @param description the free-text description
   * @return the stored zone gl mapping
   */
  public ZoneGlMapping upsertZoneGlMapping(
      UUID tenantId, UUID storeId, UUID zoneId, String nominalCode, String description) {
    return refData.upsertZoneGlMapping(tenantId, storeId, zoneId, nominalCode, description);
  }

  /**
   * Lists the tenant's zone gl mappings.
   *
   * @param tenantId owning tenant
   * @param storeId the store id
   * @return the matching rows
   */
  public List<ZoneGlMapping> listZoneGlMappings(UUID tenantId, UUID storeId) {
    return refData.listZoneGlMappings(tenantId, storeId);
  }

  // ── Picking Rules (Gap #38) ──────────────────────────────────────────────

  /**
   * Creates a picking rule.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created picking rule
   */
  public PickingRule createPickingRule(
      UUID tenantId, com.storeql.inventory.dto.Dtos.CreatePickingRuleRequest req) {
    String strategy = req.strategy().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("FIFO", "FEFO", "LIFO", "FEFO_GRADE", "ZONE_PRIORITY")
        .contains(strategy)) {
      throw new ApiException(
          400,
          "INVALID_STRATEGY",
          "strategy must be FIFO, FEFO, LIFO, FEFO_GRADE, or ZONE_PRIORITY",
          List.of(),
          null);
    }
    return pickingRuleRepo.createPickingRule(
        tenantId, req.name().trim(), strategy, req.gradePreference());
  }

  /**
   * Reads a picking rule.
   *
   * @param tenantId owning tenant
   * @param id the picking rule to act on
   * @return the picking rule
   * @throws ApiException a 404 when no such picking rule exists in this tenant
   */
  public PickingRule getPickingRule(UUID tenantId, UUID id) {
    return pickingRuleRepo
        .findPickingRule(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PICKING_RULE_NOT_FOUND", "Picking rule not found"));
  }

  /**
   * Lists the tenant's picking rules.
   *
   * @param tenantId owning tenant
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<PickingRule> listPickingRules(UUID tenantId, int limit) {
    return pickingRuleRepo.listPickingRules(tenantId, limit);
  }

  /**
   * Deactivates a picking rule, leaving the row in place.
   *
   * @param tenantId owning tenant
   * @param id the picking rule to act on
   * @return the picking rule in its deactivated state
   * @throws ApiException a 404 when no such picking rule exists in this tenant
   */
  public PickingRule deactivatePickingRule(UUID tenantId, UUID id) {
    getPickingRule(tenantId, id);
    return pickingRuleRepo.deactivatePickingRule(tenantId, id);
  }

  /**
   * Replaces a picking rule's zone priority order in full.
   *
   * <p>The order decides which zone a picker is sent to first when stock sits in several.
   *
   * @param tenantId owning tenant
   * @param ruleId the picking rule to configure
   * @param req the zones in the order they should be picked from
   * @return the stored priorities
   * @throws ApiException a 404 when no such picking rule exists in this tenant
   */
  public List<PickingRuleZonePriority> setZonePriorities(
      UUID tenantId, UUID ruleId, com.storeql.inventory.dto.Dtos.SetZonePrioritiesRequest req) {
    getPickingRule(tenantId, ruleId);
    List<PickingRuleZonePriority> items =
        req.zonePriorities().stream()
            .map(
                e ->
                    new PickingRuleZonePriority(
                        null, tenantId, ruleId, Ids.parse(e.zoneId()), e.priority()))
            .toList();
    pickingRuleRepo.replaceZonePriorities(tenantId, ruleId, items);
    return pickingRuleRepo.listZonePriorities(tenantId, ruleId);
  }

  /**
   * Lists the tenant's zone priorities.
   *
   * @param tenantId owning tenant
   * @param ruleId the rule id
   * @return the matching rows
   * @throws ApiException a 404 when no such zone prioritie exists in this tenant
   */
  public List<PickingRuleZonePriority> listZonePriorities(UUID tenantId, UUID ruleId) {
    getPickingRule(tenantId, ruleId);
    return pickingRuleRepo.listZonePriorities(tenantId, ruleId);
  }

  /**
   * Creates a picking rule assignment.
   *
   * @param tenantId owning tenant
   * @param req the request body carrying the new values
   * @return the created picking rule assignment
   * @throws ApiException a 404 when no such picking rule assignment exists in this tenant
   */
  public PickingRuleAssignment createPickingRuleAssignment(
      UUID tenantId, com.storeql.inventory.dto.Dtos.CreatePickingRuleAssignmentRequest req) {
    UUID ruleId = Ids.parse(req.ruleId());
    getPickingRule(tenantId, ruleId);
    String scopeType = req.scopeType().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("GLOBAL", "STORE", "PRODUCT").contains(scopeType)) {
      throw new ApiException(
          400,
          "INVALID_SCOPE_TYPE",
          "scopeType must be GLOBAL, STORE, or PRODUCT",
          List.of(),
          null);
    }
    UUID scopeId =
        (req.scopeId() != null && !req.scopeId().isBlank()) ? Ids.parse(req.scopeId()) : null;
    if (!"GLOBAL".equals(scopeType) && scopeId == null) {
      throw new ApiException(
          400,
          "SCOPE_ID_REQUIRED",
          "scopeId is required for scope type " + scopeType,
          List.of(),
          null);
    }
    return pickingRuleRepo.createPickingRuleAssignment(tenantId, ruleId, scopeType, scopeId);
  }

  /**
   * Lists the tenant's picking rule assignments.
   *
   * @param tenantId owning tenant
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<PickingRuleAssignment> listPickingRuleAssignments(UUID tenantId, int limit) {
    return pickingRuleRepo.listPickingRuleAssignments(tenantId, limit);
  }

  /**
   * Deletes a picking rule assignment.
   *
   * @param tenantId owning tenant
   * @param id the picking rule assignment to act on
   * @throws ApiException a 404 when no such picking rule assignment exists in this tenant
   */
  public void deletePickingRuleAssignment(UUID tenantId, UUID id) {
    if (!pickingRuleRepo.deletePickingRuleAssignment(tenantId, id)) {
      throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "Picking rule assignment not found");
    }
  }

  /**
   * The picking rule in force for a variant at a store.
   *
   * <p>Falls back to FEFO when nothing is configured, so a perishable is picked shortest-expiry
   * first by default rather than arbitrarily.
   *
   * @param tenantId owning tenant
   * @param storeId the store being picked in
   * @param variantId the variant being picked
   * @return the resolved strategy, grade preference and zone order
   */
  public com.storeql.inventory.dto.Dtos.PickingRuleResolveResponse resolvePickingRule(
      UUID tenantId, UUID storeId, UUID variantId) {
    var rule = pickingRuleRepo.resolvePickingRule(tenantId, storeId, variantId).orElse(null);
    String strategy = rule != null ? rule.strategy() : PickingRule.FEFO;
    String gradePreference = rule != null ? rule.gradePreference() : null;
    List<UUID> zonePriorityOrder =
        (rule != null && PickingRule.ZONE_PRIORITY.equals(strategy))
            ? pickingRuleRepo.listZonePriorities(tenantId, rule.id()).stream()
                .map(PickingRuleZonePriority::zoneId)
                .toList()
            : null;
    var batches =
        repo.previewPickOrder(
            tenantId, storeId, variantId, strategy, gradePreference, zonePriorityOrder);
    var pickOrder =
        batches.stream()
            .map(
                b ->
                    new com.storeql.inventory.dto.Dtos.PickingRuleResolveResponse.PickBatchPreview(
                        b.id().toString(),
                        b.batchNo(),
                        null,
                        b.remainingQty(),
                        b.expiryDate() != null ? b.expiryDate().toString() : null,
                        b.grade(),
                        b.createdAt().toString()))
            .toList();
    return new com.storeql.inventory.dto.Dtos.PickingRuleResolveResponse(
        rule != null ? rule.id().toString() : null,
        rule != null ? rule.name() : null,
        strategy,
        gradePreference,
        pickOrder);
  }
}
