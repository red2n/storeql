package com.storeql.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for inventory-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(name = "ReceiveRequest", description = "Manual receipt of stock into a new batch.")
  public record ReceiveRequest(
      @Schema(description = "UUID of the store receiving stock.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant being received.") @NotBlank
          String variantId,
      @Schema(description = "Quantity received.") @NotNull @Positive BigDecimal qty,
      String batchNo,
      @Schema(description = "Unit cost of this batch.") BigDecimal costPrice,
      @Schema(description = "ISO expiry date, if perishable.") String expiryDate,
      String grade,
      @Schema(description = "UUID of the zone the batch is placed in.") String zoneId,
      @Schema(
              description =
                  "OWNED (the default) or CONSIGNMENT: stock the supplier still owns until it"
                      + " sells, valued apart and owed to the supplier as it sells.")
          String ownership,
      @Schema(description = "The supplier that owns a CONSIGNMENT batch; required for one.")
          String supplierId,
      @Schema(
              description =
                  "DUTY_PAID (the default) or DUTY_SUSPENDED: excise goods received into bond at an"
                      + " approved store — on hand, never available until released.")
          String dutyStatus) {}

  @Schema(name = "BatchReceiveItem", description = "One line of a bulk receive request.")
  public record BatchReceiveItem(
      @Schema(description = "UUID of the store receiving stock.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant being received.") @NotBlank
          String variantId,
      @Schema(description = "Quantity received.") @NotNull @Positive BigDecimal qty) {}

  @Schema(name = "BatchReceiveRequest", description = "Bulk receive of multiple items in one call.")
  public record BatchReceiveRequest(@NotNull @Valid List<BatchReceiveItem> items) {}

  @Schema(
      name = "BatchReceiveResult",
      description = "Outcome of a bulk receive: count succeeded plus per-line error messages.")
  public record BatchReceiveResult(int received, List<String> errors) {}

  @Schema(name = "AdjustRequest", description = "Manual stock correction by a signed delta.")
  public record AdjustRequest(
      @Schema(description = "UUID of the store being adjusted.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant being adjusted.") @NotBlank
          String variantId,
      @Schema(description = "Signed adjustment quantity; positive adds, negative removes stock.")
          @NotNull
          BigDecimal delta,
      String reason,
      String reasonCode) {}

  @Schema(name = "ReserveRequest", description = "Hold stock for an order.")
  public record ReserveRequest(
      @Schema(description = "UUID of the store to reserve from.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant to reserve.") @NotBlank String variantId,
      @Schema(description = "Quantity to hold.") @NotNull @Positive BigDecimal qty,
      @Schema(description = "UUID of the order this reservation is for.") String orderId,
      @Schema(description = "Hold duration in seconds; defaults to the service's configured TTL.")
          Long ttlSeconds) {}

  @Schema(
      name = "ThresholdRequest",
      description = "Reorder threshold that triggers a low-stock replenishment suggestion.")
  public record ThresholdRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Available qty at or below which a suggestion is raised.")
          @NotNull
          @Positive
          BigDecimal threshold,
      @Schema(description = "Optional cap on suggested replenishment qty.") BigDecimal maxQty) {}

  @Schema(name = "MaterialStatusRequest", description = "Hold/release-style batch material status.")
  public record MaterialStatusRequest(
      @Schema(description = "e.g. AVAILABLE, QUARANTINE, HOLD, REJECTED.") @NotBlank
          String materialStatus,
      String reason) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "LevelResponse", description = "On-hand/reserved/available stock for a variant.")
  public record LevelResponse(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Total physical quantity in stock.") BigDecimal onHand,
      @Schema(description = "Quantity currently held by open reservations.") BigDecimal reserved,
      @Schema(description = "onHand minus what is in bond minus reserved; the sellable quantity.")
          BigDecimal available,
      @Schema(description = "How much of onHand sits in bond with its duty suspended.")
          BigDecimal inBond) {}

  @Schema(name = "LevelSummaryResponse", description = "Aggregate stock-level KPI counts.")
  public record LevelSummaryResponse(
      @Schema(description = "Distinct SKUs with any stock level recorded.") long skuCount,
      @Schema(description = "SKUs at or below their reorder threshold.") long lowStockCount) {}

  @Schema(name = "BatchResponse", description = "A received lot/batch of stock.")
  public record BatchResponse(
      String id,
      @Schema(description = "UUID of the store the batch is held at.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String batchNo,
      @Schema(description = "Quantity originally received.") BigDecimal receivedQty,
      @Schema(description = "Quantity still remaining in this batch.") BigDecimal remainingQty,
      @Schema(description = "Unit cost of this batch.") BigDecimal costPrice,
      @Schema(description = "ISO expiry date, if perishable.") String expiryDate,
      String createdAt,
      @Schema(description = "e.g. ACTIVE, DEPLETED, CANCELLED.") String status,
      @Schema(description = "e.g. AVAILABLE, QUARANTINE, HOLD, REJECTED.") String materialStatus,
      String materialStatusReason,
      String grade,
      @Schema(description = "UUID of the zone the batch is placed in.") String zoneId,
      @Schema(description = "OWNED, or CONSIGNMENT when the supplier still owns it.")
          String ownership,
      @Schema(description = "The owning supplier of a CONSIGNMENT batch.") String ownerSupplierId,
      @Schema(description = "DUTY_PAID, or DUTY_SUSPENDED while the batch sits in bond.")
          String dutyStatus) {}

  @Schema(name = "ReservationResponse", description = "A hold placed against available stock.")
  public record ReservationResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Quantity held.") BigDecimal qty,
      @Schema(description = "UUID of the order this reservation is for.") String orderId,
      @Schema(description = "HELD, CONSUMED, or RELEASED.") String status,
      @Schema(description = "Instant after which the hold auto-expires.") String expiresAt,
      String createdAt,
      @Schema(description = "STOCK, or DROPSHIP when the supplier fulfils it and nothing is held.")
          String fulfilment) {}

  @Schema(
      name = "LowStockRowResponse",
      description = "One item currently below a configured reorder level.")
  public record LowStockRowResponse(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(
              description =
                  "Which configured level bound this row: THRESHOLD (manual min/max),"
                      + " SAFETY_STOCK, or REORDER_POINT. The highest configured level wins.")
          String signal,
      @Schema(description = "The binding reorder level.") BigDecimal reorderLevel,
      @Schema(description = "On hand minus held reservations, as the levels list reports it.")
          BigDecimal availableQty,
      @Schema(description = "How far below the level this item is.") BigDecimal shortfall) {}

  @Schema(name = "ValuationRowResponse", description = "One line of the stock valuation report.")
  public record ValuationRowResponse(
      @Schema(description = "The store id or variant id this line values.") String groupKey,
      @Schema(
              description =
                  "Costing basis used: FIFO (each batch at its own cost), AVERAGE (the configured"
                      + " standard cost), or MIXED where a store rollup spans both.")
          String method,
      @Schema(description = "Total remaining quantity.") BigDecimal onHandQty,
      @Schema(
              description =
                  "How much of onHandQty carries no cost and is excluded from value. Reported"
                      + " rather than valued at zero, which would understate the holding.")
          BigDecimal unvaluedQty,
      @Schema(
              description =
                  "Money value of the quantity that could be costed and that the business owns."
                      + " Consignment stock is not its asset and is reported apart.")
          BigDecimal value,
      @Schema(description = "How much of onHandQty the supplier still owns (consignment).")
          BigDecimal consignmentQty,
      @Schema(
              description =
                  "What the consignment holding is worth at the cost the supplier will be owed.")
          BigDecimal consignmentValue,
      @Schema(description = "How much of onHandQty is held in bond with its duty suspended.")
          BigDecimal dutySuspendedQty,
      @Schema(
              description =
                  "The duty that stock would crystallise on release, at the variants' rates.")
          BigDecimal dutyPotential) {}

  // ── Bonded and duty-suspended stock ─────────────────────────────────────────

  @Schema(name = "BondApprovalRequest")
  public record BondApprovalRequest(
      @Schema(description = "The revenue's approval number for the warehouse.") @NotBlank
          String approvalNumber,
      @Schema(description = "EXCISE or CUSTOMS.") @NotBlank String regime) {}

  @Schema(name = "BondApprovalResponse")
  public record BondApprovalResponse(
      String storeId,
      String approvalNumber,
      String regime,
      boolean active,
      String createdAt,
      String endedAt) {}

  @Schema(name = "DutyRateRequest")
  public record DutyRateRequest(
      @Schema(description = "The duty one unit crystallises on release, in the home currency.")
          @NotNull
          @PositiveOrZero
          BigDecimal dutyPerUnit,
      @Schema(description = "How the figure was arrived at.") String note) {}

  @Schema(name = "DutyRateResponse")
  public record DutyRateResponse(
      String variantId, BigDecimal dutyPerUnit, String currency, String note, String updatedAt) {}

  @Schema(name = "BondReleaseRequest")
  public record BondReleaseRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @Schema(description = "The return or warrant this release belongs to.") String reference) {}

  @Schema(name = "BondReleaseResponse")
  public record BondReleaseResponse(
      String id,
      String storeId,
      String variantId,
      BigDecimal qty,
      BigDecimal dutyPerUnit,
      BigDecimal dutyAmount,
      String currency,
      String reference,
      String releasedAt) {}

  @Schema(name = "BondReleasesResponse", description = "The releases of a period and their duty.")
  public record BondReleasesResponse(
      List<BondReleaseResponse> releases, BigDecimal totalDuty, String currency) {}

  // ── Fresh yield, preparation and butchery loss ─────────────────────────────

  @Schema(name = "YieldOutputSpecRequest", description = "One cut a primal is expected to yield.")
  public record YieldOutputSpecRequest(
      @NotBlank String variantId,
      @Schema(description = "The cut's expected share of the input quantity, in percent.")
          @NotNull
          @Positive
          BigDecimal expectedPct,
      @Schema(
              description =
                  "The relative share of the primal's cost this cut carries; the expected share"
                      + " when unsaid, so cost follows weight.")
          BigDecimal costShare,
      @Schema(
              description =
                  "The cut's own shelf life from the day it is made; unsaid keeps the primal's date.")
          Integer shelfLifeDays) {}

  @Schema(name = "YieldTemplateRequest", description = "What a primal should break into.")
  public record YieldTemplateRequest(
      @NotBlank String name,
      @NotBlank String inputVariantId,
      @Schema(description = "The unit the shares are read in (kg, each); a label for people.")
          String unit,
      String notes,
      @NotNull @Valid List<YieldOutputSpecRequest> outputs) {}

  @Schema(name = "YieldOutputSpecResponse")
  public record YieldOutputSpecResponse(
      String variantId, BigDecimal expectedPct, BigDecimal costShare, Integer shelfLifeDays) {}

  @Schema(name = "YieldTemplateResponse")
  public record YieldTemplateResponse(
      String id,
      String name,
      String inputVariantId,
      String unit,
      String notes,
      boolean active,
      @Schema(description = "What the cuts' shares leave: the loss expected, in percent.")
          BigDecimal expectedLossPct,
      List<YieldOutputSpecResponse> outputs,
      String createdAt) {}

  @Schema(name = "YieldRunOutputRequest", description = "What came out of one cut.")
  public record YieldRunOutputRequest(
      @NotBlank String variantId, @NotNull @DecimalMin("0") BigDecimal qty) {}

  @Schema(name = "YieldRunRequest", description = "A breakdown made at a store.")
  public record YieldRunRequest(
      @NotBlank String storeId,
      @NotBlank String templateId,
      @Schema(description = "How much of the primal went in.") @NotNull @Positive
          BigDecimal inputQty,
      @Schema(description = "What came out, per cut; a cut left out came to nothing.")
          @NotNull
          @Valid
          List<YieldRunOutputRequest> outputs,
      @Schema(description = "The docket or the day's sheet this breakdown belongs to.")
          String reference,
      String notes) {}

  @Schema(name = "YieldRunOutputResponse")
  public record YieldRunOutputResponse(
      String variantId,
      BigDecimal qty,
      BigDecimal expectedQty,
      @Schema(
              description =
                  "The cut's cost per unit, the primal's cost apportioned; null when the primal had none.")
          BigDecimal unitCost,
      @Schema(description = "The batch the cut became; null when nothing came out.")
          String batchId) {}

  @Schema(name = "YieldRunResponse")
  public record YieldRunResponse(
      String id,
      String storeId,
      String templateId,
      String templateName,
      String inputVariantId,
      BigDecimal inputQty,
      BigDecimal inputCost,
      BigDecimal outputQty,
      BigDecimal lossQty,
      BigDecimal lossPct,
      BigDecimal expectedLossQty,
      @Schema(description = "Loss less expected loss: positive when more was lost than expected.")
          BigDecimal lossVariance,
      @Schema(description = "The loss at the primal's unit cost: what the bin took.")
          BigDecimal lossAtCost,
      String reference,
      String notes,
      String recordedAt,
      List<YieldRunOutputResponse> outputs) {}

  @Schema(name = "YieldTotalsResponse", description = "A period's breakdowns added up.")
  public record YieldTotalsResponse(
      int runs,
      BigDecimal inputQty,
      BigDecimal outputQty,
      BigDecimal lossQty,
      BigDecimal expectedLossQty,
      BigDecimal lossAtCost) {}

  @Schema(name = "YieldRunsResponse", description = "The butchery-loss report.")
  public record YieldRunsResponse(List<YieldRunResponse> runs, YieldTotalsResponse totals) {}

  @Schema(name = "BondStockResponse", description = "What sits in bond and the duty it carries.")
  public record BondStockResponse(
      String storeId,
      String variantId,
      BigDecimal qty,
      @Schema(description = "The variant's duty per unit; null when none is set.")
          BigDecimal dutyPerUnit,
      BigDecimal dutyPotential) {}

  @Schema(
      name = "ShrinkageRowResponse",
      description = "One aggregated line of the stock write-off report.")
  public record ShrinkageRowResponse(
      @Schema(
              description =
                  "What this line sums: a reason code, an actor id, a store id or a variant id,"
                      + " depending on the grouping. UNSPECIFIED covers adjustments made with no"
                      + " reason code; SYSTEM covers those with no human actor.")
          String groupKey,
      @Schema(description = "Total quantity written off, as a positive number.")
          BigDecimal qtyWrittenOff,
      @Schema(description = "Total quantity added back, e.g. stock found during a count.")
          BigDecimal qtyFound,
      @Schema(description = "Signed net of write-offs and finds.") BigDecimal netQty,
      @Schema(description = "How many adjustment movements this line covers.") long movements) {}

  @Schema(
      name = "StockTurnRowResponse",
      description = "One line of the stock-turn report: what sold, against what was held.")
  public record StockTurnRowResponse(
      @Schema(description = "The store id or variant id this line covers.") String groupKey,
      @Schema(
              description =
                  "Cost of the stock sold in the window, taken from the cost price of the batches"
                      + " the sales actually drew down.")
          BigDecimal cogs,
      @Schema(
              description =
                  "Quantity sold out of batches carrying no cost price, and therefore excluded"
                      + " from cogs. Reported rather than costed at zero, which would overstate"
                      + " margin and understate turns.")
          BigDecimal uncostedSaleQty,
      @Schema(description = "Value of the holding at the start of the window.")
          BigDecimal openingValue,
      @Schema(description = "Value of the holding at the end of the window.")
          BigDecimal closingValue,
      @Schema(description = "Mean of opening and closing — the denominator of turnoverRatio.")
          BigDecimal averageValue,
      @Schema(
              description =
                  "cogs / averageValue. Null when there was no stock to turn, which is not the"
                      + " same as turning it zero times.")
          BigDecimal turnoverRatio,
      @Schema(
              description =
                  "How many days the average holding would last at this rate of sale. Null"
                      + " whenever turnoverRatio is.")
          BigDecimal daysOnHand) {}

  @Schema(
      name = "StockTurnReportResponse",
      description = "The stock-turn report, plus whether its opening figures can be trusted.")
  public record StockTurnReportResponse(
      @Schema(description = "One line per store or variant, slowest-turning first.")
          List<StockTurnRowResponse> rows,
      @Schema(
              description =
                  "False when the movement ledger has been purged past the start of the window, so"
                      + " opening value is a floor rather than a figure. Rows are still returned.")
          boolean historyComplete,
      @Schema(description = "Length of the window in days — the numerator of daysOnHand.")
          int windowDays) {}

  @Schema(name = "DeadStockRowResponse", description = "One line of the dead-stock ageing report.")
  public record DeadStockRowResponse(
      @Schema(
              description =
                  "What this line covers: an ageing bucket (0-30, 31-60, 61-90, 91-180, 180+), a"
                      + " store id, or a variant id, depending on the grouping.")
          String groupKey,
      @Schema(description = "Quantity still on hand.") BigDecimal onHandQty,
      @Schema(description = "Value of that quantity at batch cost.") BigDecimal value,
      @Schema(description = "How much of onHandQty carries no cost and is excluded from value.")
          BigDecimal uncostedQty,
      @Schema(
              description =
                  "Days since this item last sold — for a group, the largest such age in it. Where"
                      + " nothing has ever sold, days since the oldest remaining batch arrived.")
          Integer daysSinceLastSale,
      @Schema(
              description =
                  "True when nothing in this line has ever sold, so its age is measured from"
                      + " receipt rather than from a sale.")
          boolean neverSold) {}

  @Schema(name = "MovementResponse", description = "One append-only stock movement ledger entry.")
  public record MovementResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the batch this movement affected, if applicable.")
          String batchId,
      @Schema(
              description =
                  "RECEIVE, SALE, ADJUST, TRANSFER_OUT or TRANSFER_IN (a manual adjustment is ADJUST with refType ADJUSTMENT).")
          String type,
      @Schema(description = "Signed movement quantity.") BigDecimal qty,
      String refType,
      String refId,
      @Schema(
              description =
                  "Reason code for the movement, e.g. THEFT or DAMAGED. Set on adjustments; null"
                      + " for system-caused movements, which cite refType/refId instead.")
          String reasonCode,
      @Schema(
              description =
                  "UUID of the user who made this adjustment. Null for system-caused movements --"
                      + " trace those through refType/refId to the record that names its actor.")
          String actorId,
      String createdAt) {}

  @Schema(name = "ThresholdResponse", description = "A configured reorder threshold.")
  public record ThresholdResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Available qty at or below which a suggestion is raised.")
          BigDecimal threshold,
      @Schema(description = "Optional cap on suggested replenishment qty.") BigDecimal maxQty) {}

  @Schema(name = "SuggestionResponse", description = "A min-max replenishment suggestion.")
  public record SuggestionResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Available quantity at the time the suggestion was raised.")
          BigDecimal availableQty,
      BigDecimal minQty,
      BigDecimal maxQty,
      @Schema(description = "Recommended quantity to reorder.") BigDecimal suggestedQty,
      @Schema(description = "OPEN, ACCEPTED, or DISMISSED.") String status,
      String createdAt,
      String resolvedAt) {}

  @Schema(
      name = "ResolveSuggestionRequest",
      description = "Set a replenishment suggestion's status.")
  public record ResolveSuggestionRequest(
      @Schema(description = "ACCEPTED or DISMISSED.") @NotBlank String status) {}

  @Schema(name = "RegisterSerialsRequest", description = "Register serial numbers for a batch.")
  public record RegisterSerialsRequest(
      @Schema(description = "UUID of the batch the serials belong to.") @NotBlank String batchId,
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Explicit serial codes to register.") List<String> serials,
      @Schema(description = "Number of serials to auto-generate (max 200) if serials is omitted.")
          Integer autoQty,
      @Schema(description = "Prefix used when auto-generating serial codes.") String prefix) {}

  @Schema(name = "SerialStatusRequest", description = "Update a serial number's status.")
  public record SerialStatusRequest(
      @Schema(description = "e.g. IN_STOCK, SOLD, RETURNED, DAMAGED.") @NotBlank String status) {}

  @Schema(name = "SerialNumberResponse", description = "A tracked serialized unit.")
  public record SerialNumberResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the batch this serial was received in.") String batchId,
      String serialNo,
      @Schema(description = "e.g. IN_STOCK, SOLD, RETURNED, DAMAGED.") String status,
      String receivedAt,
      String soldAt) {}

  @Schema(
      name = "SerialMovementResponse",
      description = "A status transition for one serial number.")
  public record SerialMovementResponse(
      String id,
      @Schema(description = "UUID of the serial number.") String serialId,
      String fromStatus,
      String toStatus,
      String refType,
      String refId,
      String createdAt) {}

  // ── Move Orders (Gap #5) ─────────────────────────────────────────────────────

  @Schema(name = "MoveOrderLineRequest", description = "One requested variant/qty on a move order.")
  public record MoveOrderLineRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Quantity requested to move.") @NotNull @Positive
          BigDecimal requestedQty) {}

  @Schema(
      name = "CreateMoveOrderRequest",
      description = "Intra-store zone-to-zone stock move request.")
  public record CreateMoveOrderRequest(
      @Schema(description = "UUID of the store both zones belong to.") @NotBlank String fromStoreId,
      @Schema(
              description =
                  "UUID of the destination store (same as fromStoreId for intra-store" + " moves).")
          @NotBlank
          String toStoreId,
      String fromZone,
      String toZone,
      String notes,
      @NotNull @Valid List<MoveOrderLineRequest> lines) {}

  @Schema(name = "MoveOrderLineResponse", description = "One line of a move order.")
  public record MoveOrderLineResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal requestedQty,
      @Schema(description = "Quantity actually picked when the order was executed.")
          BigDecimal pickedQty) {}

  @Schema(
      name = "MoveOrderResponse",
      description = "A zone-to-zone stock move order with its lines.")
  public record MoveOrderResponse(
      String id,
      @Schema(description = "UUID of the source store.") String fromStoreId,
      @Schema(description = "UUID of the destination store.") String toStoreId,
      String fromZone,
      String toZone,
      String notes,
      @Schema(description = "PENDING, PICKED, or CANCELLED.") String status,
      String createdAt,
      String pickedAt,
      List<MoveOrderLineResponse> lines) {}

  // ── Transfer Orders (Gap #6) ─────────────────────────────────────────────────

  @Schema(
      name = "TransferOrderLineRequest",
      description = "One requested variant/qty on a transfer order.")
  public record TransferOrderLineRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Quantity requested to transfer.") @NotNull @Positive
          BigDecimal requestedQty) {}

  @Schema(name = "CreateTransferOrderRequest", description = "Inter-store stock transfer request.")
  public record CreateTransferOrderRequest(
      @Schema(description = "UUID of the sending store.") @NotBlank String fromStoreId,
      @Schema(description = "UUID of the receiving store.") @NotBlank String toStoreId,
      String transferType,
      String notes,
      @NotNull @Valid List<TransferOrderLineRequest> lines) {}

  @Schema(name = "TransferOrderLineResponse", description = "One line of a transfer order.")
  public record TransferOrderLineResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal requestedQty,
      @Schema(description = "Quantity actually shipped.") BigDecimal shippedQty,
      @Schema(description = "Quantity actually received at the destination store.")
          BigDecimal receivedQty,
      @Schema(
              description =
                  "Why a replenishment proposal asked for this quantity; null when a person did.")
          String reason) {}

  @Schema(
      name = "TransferOrderResponse",
      description = "An inter-store stock transfer order with its lines.")
  public record TransferOrderResponse(
      String id,
      @Schema(description = "UUID of the sending store.") String fromStoreId,
      @Schema(description = "UUID of the receiving store.") String toStoreId,
      String transferType,
      @Schema(
              description =
                  "DRAFT (proposed, to be released), PENDING, SHIPPED, RECEIVED, or CANCELLED.")
          String status,
      String notes,
      String createdAt,
      String shippedAt,
      String receivedAt,
      @Schema(description = "MANUAL, or PROPOSAL when a warehouse's replenishment run raised it.")
          String source,
      @Schema(description = "The replenishment run that proposed it, or null.")
          String proposalRunId,
      @Schema(description = "A cross-dock transfer's purchase order, or null.")
          String purchaseOrderId,
      @Schema(description = "A cross-dock transfer's goods receipt, or null.")
          String goodsReceiptId,
      List<TransferOrderLineResponse> lines) {}

  // ── Lot Genealogy (Gap #11) ──────────────────────────────────────────────

  @Schema(
      name = "CreateLotLinkRequest",
      description = "Link a parent and child batch for genealogy traceability.")
  public record CreateLotLinkRequest(
      @Schema(description = "UUID of the parent (source) batch.") @NotBlank String parentBatchId,
      @Schema(description = "UUID of the child (resulting) batch.") @NotBlank String childBatchId,
      @Schema(description = "Quantity attributed to this link.") @NotNull @Positive BigDecimal qty,
      @Schema(description = "e.g. SPLIT, MERGE, REPACK.") String relationType,
      String notes) {}

  @Schema(name = "LotGenealogyLinkResponse", description = "One parent-child genealogy link.")
  public record LotGenealogyLinkResponse(
      String id,
      @Schema(description = "UUID of the parent batch.") String parentBatchId,
      @Schema(description = "UUID of the child batch.") String childBatchId,
      BigDecimal qty,
      String relationType,
      String notes,
      String createdAt) {}

  @Schema(
      name = "LotGenealogyTreeResponse",
      description = "A batch's ancestor and/or descendant genealogy links.")
  public record LotGenealogyTreeResponse(
      @Schema(description = "UUID of the batch this tree is rooted at.") String batchId,
      List<LotGenealogyLinkResponse> ancestors,
      List<LotGenealogyLinkResponse> descendants) {}

  // ── Cycle Counting (Gap #10) ─────────────────────────────────────────────

  @Schema(name = "CreateCycleCountRequest", description = "Start a cycle count for a store.")
  public record CreateCycleCountRequest(
      @Schema(description = "UUID of the store being counted.") @NotBlank String storeId,
      @NotBlank String name,
      @Schema(description = "Comma-separated ABC classes included, e.g. \"A,B,C\".")
          String abcClasses,
      @Schema(description = "Variance percentage within which a line auto-approves.")
          BigDecimal tolerancePct) {}

  @Schema(name = "EnterCountRequest", description = "Record a counted quantity for a count line.")
  public record EnterCountRequest(
      @Schema(description = "Physically counted quantity.") @NotNull BigDecimal countedQty) {}

  @Schema(name = "CycleCountLineResponse", description = "One variant's line within a cycle count.")
  public record CycleCountLineResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Quantity per system records at count time.") BigDecimal systemQty,
      @Schema(description = "Physically counted quantity.") BigDecimal countedQty,
      @Schema(description = "countedQty minus systemQty.") BigDecimal variance,
      BigDecimal variancePct,
      @Schema(description = "PENDING, COUNTED, APPROVED, or FLAGGED.") String status,
      String countedAt) {}

  @Schema(name = "CycleCountHeaderResponse", description = "A cycle count with summary counts.")
  public record CycleCountHeaderResponse(
      String id,
      @Schema(description = "UUID of the store being counted.") String storeId,
      String name,
      String abcClasses,
      BigDecimal tolerancePct,
      @Schema(description = "OPEN, COUNTING, APPROVED, ADJUSTED, or COMPLETED.") String status,
      int totalLines,
      int countedLines,
      int approvedLines,
      String createdAt,
      String completedAt) {}

  @Schema(
      name = "CycleCountApproveResult",
      description = "Outcome of tolerance-based cycle-count approval.")
  public record CycleCountApproveResult(
      @Schema(description = "Lines auto-approved because variance was within tolerance.")
          int autoApproved,
      @Schema(description = "Lines flagged for manual review.") int flagged) {}

  @Schema(
      name = "CycleCountAdjustResult",
      description = "Outcome of posting cycle-count adjustments.")
  public record CycleCountAdjustResult(
      @Schema(description = "Number of StockAdjusted movements posted.") int adjusted) {}

  // ── ABC Analysis (Gap #9) ────────────────────────────────────────────────

  @Schema(name = "RunAbcRequest", description = "Parameters for an ABC classification compile run.")
  public record RunAbcRequest(
      @Schema(description = "UUID of the store to scope the run to; null for all stores.")
          String storeId,
      @Schema(description = "VALUE or VELOCITY.") String criteria,
      @Schema(description = "Cumulative percentage cutoff for class A (0-100).")
          BigDecimal thresholdA,
      @Schema(description = "Cumulative percentage cutoff for class B (thresholdA-100).")
          BigDecimal thresholdAB) {}

  @Schema(name = "AbcCompileRunResponse", description = "A completed ABC classification run.")
  public record AbcCompileRunResponse(
      String id,
      String storeId,
      String criteria,
      BigDecimal thresholdA,
      BigDecimal thresholdAB,
      @Schema(description = "Number of variants classified in this run.") int itemsCompiled,
      String compiledAt) {}

  @Schema(name = "AbcAssignmentResponse", description = "A variant's ABC classification.")
  public record AbcAssignmentResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the compile run this assignment came from.") String runId,
      @Schema(description = "A, B, or C.") String abcClass,
      @Schema(description = "Value or velocity score used to rank the variant.") BigDecimal score,
      @Schema(description = "Rank position within the run, 1 = highest score.") int rank,
      String assignedAt) {}

  // ── Safety Stock (Gap #8) ────────────────────────────────────────────────

  @Schema(name = "SetSafetyStockRequest", description = "Configure safety-stock parameters.")
  public record SetSafetyStockRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "MAD or USER_DEFINED.") @NotBlank String method,
      Integer leadTimeDays,
      @Schema(description = "Target service level percentage, e.g. 95.") BigDecimal serviceLevelPct,
      @Schema(
              description =
                  "Required when method is USER_DEFINED; a manual safety-stock" + " percentage.")
          BigDecimal userDefinedPct) {}

  @Schema(
      name = "ComputeSafetyStockRequest",
      description = "Scope for a safety-stock recompute; both fields optional.")
  public record ComputeSafetyStockRequest(String storeId, String variantId) {}

  @Schema(name = "SafetyStockParamsResponse", description = "Computed safety-stock parameters.")
  public record SafetyStockParamsResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "MAD or USER_DEFINED.") String method,
      int leadTimeDays,
      BigDecimal serviceLevelPct,
      BigDecimal userDefinedPct,
      @Schema(description = "Computed safety-stock quantity to hold as a buffer.")
          BigDecimal safetyStockQty,
      String computedAt,
      String createdAt) {}

  @Schema(name = "ComputeSafetyStockResult", description = "Outcome of a safety-stock recompute.")
  public record ComputeSafetyStockResult(
      @Schema(description = "Number of safety-stock rows updated.") int computed,
      String bucketType) {}

  @Schema(
      name = "AggregateRequest",
      description = "Scope and grain for a demand-history aggregation run.")
  public record AggregateRequest(
      String storeId,
      @Schema(description = "DAY, WEEK, or MONTH.") String bucketType,
      @Schema(description = "ISO date; only movements on/after this date are aggregated.")
          String since) {}

  @Schema(name = "AggregateResult", description = "Outcome of a demand-history aggregation run.")
  public record AggregateResult(
      @Schema(description = "Number of demand buckets upserted.") int bucketsUpserted,
      String bucketType) {}

  @Schema(name = "DemandBucketResponse", description = "Aggregated demand for one time bucket.")
  public record DemandBucketResponse(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String bucketDate,
      @Schema(description = "DAY, WEEK, or MONTH.") String bucketType,
      @Schema(description = "Total SALE-movement quantity in this bucket.") BigDecimal demandQty,
      int movementCount,
      String computedAt) {}

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────

  @Schema(
      name = "UpsertRopPlanRequest",
      description = "Reorder-point/EOQ plan inputs for a variant at a store.")
  public record UpsertRopPlanRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Supplier lead time in days.") @NotNull @Positive Integer leadTimeDays,
      @Schema(description = "Fixed cost per purchase order, used in the EOQ formula.")
          @NotNull
          @Positive
          BigDecimal orderingCost,
      @Schema(description = "Annual holding cost as a percentage of unit cost.") @NotNull @Positive
          BigDecimal holdingCostPct,
      @Schema(description = "Unit cost used in the EOQ formula.") @NotNull @Positive
          BigDecimal unitCost) {}

  @Schema(name = "RopPlanResponse", description = "A computed reorder-point/EOQ plan.")
  public record RopPlanResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      int leadTimeDays,
      BigDecimal orderingCost,
      BigDecimal holdingCostPct,
      BigDecimal unitCost,
      @Schema(description = "Average daily demand derived from demand history.")
          BigDecimal avgDailyDemand,
      @Schema(description = "Computed reorder point.") BigDecimal rop,
      @Schema(description = "Computed economic order quantity.") BigDecimal eoq,
      @Schema(description = "Order-modifier floor applied to the computed EOQ.")
          BigDecimal minOrderQty,
      @Schema(description = "Order-modifier ceiling applied to the computed EOQ.")
          BigDecimal maxOrderQty,
      @Schema(description = "Rounds the order qty up to a multiple of this lot size.")
          BigDecimal lotMultiplier,
      String computedAt,
      String createdAt) {}

  @Schema(name = "ComputeRopResult", description = "Outcome of a ROP/EOQ recompute.")
  public record ComputeRopResult(
      @Schema(description = "Number of plans recomputed.") int computed) {}

  // ── Gap #18: Kanban Replenishment ────────────────────────────────────────

  @Schema(name = "CreateKanbanCardRequest", description = "Create a kanban replenishment card.")
  public record CreateKanbanCardRequest(
      @Schema(description = "UUID of the store the card replenishes.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "e.g. PRODUCTION or TRANSFER.") @NotBlank String kanbanType,
      @Schema(description = "Fixed quantity ordered each time the card triggers.")
          @NotNull
          @Positive
          BigDecimal reorderQty,
      @Schema(description = "UUID of the store this card is replenished from, for TRANSFER cards.")
          String sourceStoreId,
      String supplierRef,
      String notes) {}

  @Schema(
      name = "TriggerKanbanRequest",
      description = "Optional notes when triggering a kanban card.")
  public record TriggerKanbanRequest(String notes) {}

  @Schema(name = "KanbanCardResponse", description = "A kanban replenishment card.")
  public record KanbanCardResponse(
      String id,
      @Schema(description = "UUID of the store the card replenishes.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String kanbanType,
      @Schema(description = "READY, TRIGGERED, or REPLENISHED.") String status,
      BigDecimal reorderQty,
      @Schema(description = "UUID of the source store for TRANSFER cards.") String sourceStoreId,
      String supplierRef,
      String notes,
      @Schema(description = "Order-modifier floor applied to reorderQty.") BigDecimal minOrderQty,
      @Schema(description = "Order-modifier ceiling applied to reorderQty.") BigDecimal maxOrderQty,
      @Schema(description = "Rounds the order qty up to a multiple of this lot size.")
          BigDecimal lotMultiplier,
      String createdAt,
      String triggeredAt,
      String replenishedAt) {}

  // ── Gap #17: Costing ─────────────────────────────────────────────────────

  @Schema(
      name = "UpsertCostingMethodRequest",
      description = "Set the costing method for a variant at a store.")
  public record UpsertCostingMethodRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "FIFO or AVERAGE.") @NotBlank String method,
      @Schema(
              description =
                  "Standard unit cost used when method is AVERAGE. Omit to leave the stored value"
                      + " unchanged. Ignored for FIFO, which values each batch at its own cost.")
          @PositiveOrZero
          BigDecimal averageCost) {}

  @Schema(name = "CostingMethodResponse", description = "A variant's assigned costing method.")
  public record CostingMethodResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "FIFO or AVERAGE.") String method,
      @Schema(
              description =
                  "Standard unit cost used when method is AVERAGE. Operator-set, not recomputed"
                      + " from receipts.")
          BigDecimal averageCost,
      String updatedAt) {}

  @Schema(name = "OpenPeriodRequest", description = "Open a new accounting period for a store.")
  public record OpenPeriodRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @NotBlank String periodName,
      @Schema(description = "ISO date representing the period.") @NotBlank String periodDate) {}

  @Schema(name = "AccountingPeriodResponse", description = "An accounting period.")
  public record AccountingPeriodResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      String periodName,
      String periodDate,
      @Schema(description = "OPEN or CLOSED.") String status,
      String openedAt,
      String closedAt) {}

  // ── Tier-1 Gap #21: Transaction reason codes ─────────────────────────────

  @Schema(name = "CreateReasonCodeRequest", description = "Create a transaction reason code.")
  public record CreateReasonCodeRequest(@NotBlank String code, String description) {}

  @Schema(name = "ReasonCodeResponse", description = "A transaction reason code.")
  public record ReasonCodeResponse(
      String id,
      @Schema(description = "UUID of the owning tenant.") String tenantId,
      String code,
      String description,
      boolean active,
      String createdAt) {}

  // ── Tier-1 Gap #22: Transaction source types ──────────────────────────────

  @Schema(name = "CreateSourceTypeRequest", description = "Create a transaction source type.")
  public record CreateSourceTypeRequest(@NotBlank String code, String description) {}

  @Schema(name = "SourceTypeResponse", description = "A transaction source type.")
  public record SourceTypeResponse(
      String id,
      @Schema(description = "UUID of the owning tenant.") String tenantId,
      String code,
      String description,
      boolean active,
      String createdAt) {}

  // ── Tier-1 Gap #23: Lot actions (split / merge) ───────────────────────────

  @Schema(name = "LotSplitRequest", description = "Split a batch into a new child batch.")
  public record LotSplitRequest(
      @Schema(description = "UUID of the batch to split.") @NotBlank String sourceBatchId,
      @Schema(description = "Quantity to move into the new batch.") @NotNull @Positive
          BigDecimal qty,
      String batchNo,
      String notes) {}

  @Schema(
      name = "LotMergeRequest",
      description = "Merge qty from a source batch into a target batch.")
  public record LotMergeRequest(
      @Schema(description = "UUID of the batch to merge from.") @NotBlank String sourceBatchId,
      @Schema(description = "UUID of the batch to merge into.") @NotBlank String targetBatchId,
      @Schema(description = "Quantity to move.") @NotNull @Positive BigDecimal qty,
      String notes) {}

  @Schema(name = "LotActionResponse", description = "A recorded split or merge action.")
  public record LotActionResponse(
      String id,
      @Schema(description = "SPLIT or MERGE.") String actionType,
      @Schema(description = "UUID of the batch the qty was taken from.") String sourceBatchId,
      @Schema(description = "UUID of the batch the qty resulted in or was merged into.")
          String resultBatchId,
      BigDecimal qty,
      String notes,
      String createdAt) {}

  // ── Tier-1 Gap #24: Expiry alert query ────────────────────────────────────

  @Schema(name = "ExpiringBatchResponse", description = "A batch nearing its expiry date.")
  public record ExpiringBatchResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String batchNo,
      BigDecimal remainingQty,
      String expiryDate,
      @Schema(description = "Days remaining until expiryDate, as of the query time.")
          long daysUntilExpiry) {}

  // ── Tier-1 Gap #25: Grade control ─────────────────────────────────────────

  @Schema(name = "UpdateGradeRequest", description = "Update a batch's quality grade.")
  public record UpdateGradeRequest(@NotBlank String grade) {}

  // ── Tier-1 Gap #26: Lot UOM conversions ───────────────────────────────────

  @Schema(
      name = "UpsertLotUomConversionRequest",
      description = "Define a unit-of-measure conversion for a specific batch.")
  public record UpsertLotUomConversionRequest(
      @Schema(description = "UUID of the batch this conversion applies to.") @NotBlank
          String batchId,
      @NotBlank String fromUom,
      @NotBlank String toUom,
      @Schema(description = "Multiplier: 1 fromUom = factor toUom.") @NotNull @Positive
          BigDecimal factor,
      String notes) {}

  @Schema(name = "LotUomConversionResponse", description = "A batch's unit-of-measure conversion.")
  public record LotUomConversionResponse(
      String id,
      @Schema(description = "UUID of the batch.") String batchId,
      String fromUom,
      String toUom,
      BigDecimal factor,
      String notes,
      String createdAt) {}

  // ── Tier-1 Gap #27: PAR levels ────────────────────────────────────────────

  @Schema(
      name = "UpsertParLevelRequest",
      description = "Set a periodic-automatic-replenishment (PAR) target quantity.")
  public record UpsertParLevelRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Target quantity to be topped up to on each review cycle.")
          @NotNull
          @Positive
          BigDecimal parQty,
      String uom,
      @Schema(description = "e.g. DAILY, WEEKLY.") String reviewCycle) {}

  @Schema(name = "ParLevelResponse", description = "A configured PAR level.")
  public record ParLevelResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal parQty,
      String uom,
      String reviewCycle,
      String createdAt,
      String updatedAt) {}

  // ── Tier-1 Gap #28: Order modifiers on ROP plans ──────────────────────────

  @Schema(
      name = "UpdateOrderModifiersRequest",
      description = "Order-quantity modifiers applied on top of a computed reorder quantity.")
  public record UpdateOrderModifiersRequest(
      @Schema(description = "Floor applied to the computed order quantity.") BigDecimal minOrderQty,
      @Schema(description = "Ceiling applied to the computed order quantity.")
          BigDecimal maxOrderQty,
      @Schema(description = "Rounds the order qty up to a multiple of this lot size.")
          BigDecimal lotMultiplier) {}

  // ── Tier-1 Gap #29: Batch reservations ────────────────────────────────────

  @Schema(
      name = "BatchReserveRequest",
      description = "Reserve stock for multiple lines in one call.")
  public record BatchReserveRequest(@NotNull @Valid List<ReserveRequest> reservations) {}

  @Schema(name = "BatchReserveResponse", description = "Outcome of a bulk reservation request.")
  public record BatchReserveResponse(
      int succeeded, int failed, List<ReservationResponse> results) {}

  // ── Tier-1 Gap #30: Purge transaction history ─────────────────────────────

  @Schema(
      name = "PurgeMovementsRequest",
      description = "Delete movement history older than a given instant (data retention).")
  public record PurgeMovementsRequest(
      @Schema(description = "ISO-8601 instant; movements before this are permanently deleted.")
          @NotBlank
          String before) {}

  @Schema(name = "PurgeResult", description = "Outcome of a movement purge.")
  public record PurgeResult(@Schema(description = "Number of movements deleted.") int purged) {}

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  @Schema(
      name = "UpsertZoneGlMappingRequest",
      description = "Map a store/zone to a nominal ledger (GL) account.")
  public record UpsertZoneGlMappingRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the zone; null maps the whole store.") String zoneId,
      @Schema(description = "Nominal ledger account code.") @NotBlank String nominalCode,
      String description) {}

  @Schema(name = "ZoneGlMappingResponse", description = "A zone-to-GL-account mapping.")
  public record ZoneGlMappingResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the zone; null if mapped at store level.") String zoneId,
      String nominalCode,
      String description,
      String createdAt,
      String updatedAt) {}

  // ── Gap #38: Picking Rules ────────────────────────────────────────────────

  @Schema(name = "CreatePickingRuleRequest", description = "Create a named picking strategy rule.")
  public record CreatePickingRuleRequest(
      @NotBlank String name,
      @Schema(description = "e.g. FIFO, FEFO.") @NotBlank String strategy,
      String gradePreference) {}

  @Schema(name = "PickingRuleResponse", description = "A picking strategy rule.")
  public record PickingRuleResponse(
      String id,
      String name,
      String strategy,
      String gradePreference,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      String createdAt,
      String updatedAt) {}

  @Schema(
      name = "SetZonePrioritiesRequest",
      description = "Ordered zone pick priorities for a picking rule.")
  public record SetZonePrioritiesRequest(@NotNull @Valid List<ZonePriorityEntry> zonePriorities) {
    @Schema(
        name = "ZonePriorityEntry",
        description = "One zone's pick priority (lower picks first).")
    public record ZonePriorityEntry(
        @Schema(description = "UUID of the zone.") @NotBlank String zoneId, int priority) {}
  }

  @Schema(
      name = "PickingRuleZonePriorityResponse",
      description = "A zone's priority within a rule.")
  public record PickingRuleZonePriorityResponse(
      String id, @Schema(description = "UUID of the zone.") String zoneId, int priority) {}

  @Schema(
      name = "CreatePickingRuleAssignmentRequest",
      description = "Bind a picking rule to a scope (e.g. tenant/store/category).")
  public record CreatePickingRuleAssignmentRequest(
      @Schema(description = "UUID of the picking rule.") @NotBlank String ruleId,
      @Schema(description = "e.g. TENANT, STORE, CATEGORY.") @NotBlank String scopeType,
      @Schema(description = "UUID of the scoped entity; null for a tenant-wide assignment.")
          String scopeId) {}

  @Schema(name = "PickingRuleAssignmentResponse", description = "A picking rule scope assignment.")
  public record PickingRuleAssignmentResponse(
      String id,
      @Schema(description = "UUID of the picking rule.") String ruleId,
      String scopeType,
      String scopeId,
      String createdAt) {}

  @Schema(
      name = "PickingRuleResolveResponse",
      description =
          "The applicable picking rule and previewed pick order for a variant at a store.")
  public record PickingRuleResolveResponse(
      @Schema(description = "UUID of the resolved picking rule.") String appliedRuleId,
      String appliedRuleName,
      String strategy,
      String gradePreference,
      List<PickBatchPreview> pickOrder) {
    @Schema(
        name = "PickBatchPreview",
        description = "One batch's position in the previewed pick order.")
    public record PickBatchPreview(
        @Schema(description = "UUID of the batch.") String batchId,
        String batchNo,
        @Schema(description = "UUID of the zone the batch is in.") String zoneId,
        java.math.BigDecimal remainingQty,
        String expiryDate,
        String grade,
        String createdAt) {}
  }

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  @Schema(
      name = "CreatePhysicalInventoryRequest",
      description = "Start a full physical inventory count for a store.")
  public record CreatePhysicalInventoryRequest(
      @Schema(description = "UUID of the store being counted.") @NotBlank String storeId,
      String notes) {}

  @Schema(name = "AddTagRequest", description = "Register a variant to be counted.")
  public record AddTagRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "UUID of the zone the variant is expected in.") String zoneId,
      @Schema(description = "Quantity per system records at the time the tag is added.") @NotNull
          BigDecimal systemQty) {}

  @Schema(name = "CountTagRequest", description = "Record a counted quantity for a tag.")
  public record CountTagRequest(
      @Schema(description = "Physically counted quantity.") @NotNull BigDecimal countedQty) {}

  @Schema(
      name = "PhysicalInventoryTagResponse",
      description = "One variant/zone tag being counted.")
  public record PhysicalInventoryTagResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the zone.") String zoneId,
      BigDecimal systemQty,
      BigDecimal countedQty,
      @Schema(description = "countedQty minus systemQty.") BigDecimal adjustmentQty,
      @Schema(description = "PENDING or COUNTED.") String status,
      String countedAt) {}

  @Schema(
      name = "PhysicalInventoryResponse",
      description = "A full physical inventory count with its tags.")
  public record PhysicalInventoryResponse(
      String id,
      @Schema(description = "UUID of the store being counted.") String storeId,
      @Schema(description = "IN_PROGRESS or COMPLETED.") String status,
      String notes,
      String startedAt,
      String completedAt,
      List<PhysicalInventoryTagResponse> tags) {}

  /**
   * Public storefront stock signal: whether a variant is buyable at a store. No quantities are
   * exposed above a business's own configured threshold — {@code onlyLeft} is the one deliberate
   * exception, and only ever at or below it.
   */
  @Schema(
      name = "AvailabilityResponse",
      description =
          "Public in-stock/out-of-stock signal for a variant; no quantities exposed above a"
              + " business's own low-stock threshold.")
  public record AvailabilityResponse(
      @Schema(description = "UUID of the product variant.") String variantId,
      boolean inStock,
      @Schema(
              description =
                  "True when the supplier ships it per order: available with none on the shelf.")
          boolean dropship,
      @Schema(
              description =
                  "The whole units left at the store named on the read, when a business has set a"
                      + " threshold and 0 < available <= threshold; null otherwise — no threshold"
                      + " set, above the threshold, out of stock, dropship, no store named, or a"
                      + " fractional (weighed) quantity. Never a count above the threshold.")
          Integer onlyLeft) {}

  // ── "Only N left" on the storefront ────────────────────────────────────

  @Schema(
      name = "StorefrontStockSettingsRequest",
      description =
          "The business-wide low-stock threshold shown to shoppers as \"only N left\"; null"
              + " switches the feature off.")
  public record StorefrontStockSettingsRequest(
      @Schema(description = "1..1000, or null to switch the feature off.")
          Integer lowStockThreshold) {}

  @Schema(
      name = "StorefrontStockSettingsResponse",
      description = "The business's current \"only N left\" setting.")
  public record StorefrontStockSettingsResponse(
      @Schema(description = "1..1000, or null while the feature is off.") Integer lowStockThreshold,
      @Schema(description = "When it was last changed; null if never.") String updatedAt,
      @Schema(description = "UUID of the staff member who last changed it; null if never.")
          String updatedBy) {}

  // ── Gross margin and GMROI (19.7) ─────────────────────────────────────────────

  @Schema(name = "GrossMarginRowResponse")
  public record GrossMarginRowResponse(
      @Schema(description = "The store id or variant id this line covers.") String groupKey,
      @Schema(description = "Net revenue in the window, after VAT, discounts and returns.")
          BigDecimal revenue,
      @Schema(description = "Cost of the batches the sales drew down, less what came back.")
          BigDecimal cogs,
      BigDecimal grossMargin,
      @Schema(
              description =
                  "Gross margin as a percentage of revenue; null when nothing was earned.")
          BigDecimal marginPercent,
      @Schema(description = "Average value of the holding at cost over the window.")
          BigDecimal averageValue,
      @Schema(description = "Gross margin / average inventory at cost; null when nothing was held.")
          BigDecimal gmroi,
      @Schema(description = "GMROI scaled to a year.") BigDecimal annualisedGmroi,
      @Schema(description = "Quantity sold out of batches with no cost price.")
          BigDecimal uncostedSaleQty,
      @Schema(description = "Quantity sold with no revenue recorded; its cost is still in cogs.")
          BigDecimal unpricedSaleQty) {}

  @Schema(name = "GrossMarginReportResponse")
  public record GrossMarginReportResponse(
      @Schema(description = "One line per store or variant, lowest margin first.")
          List<GrossMarginRowResponse> rows,
      @Schema(description = "False when archived movements make the average holding a floor.")
          boolean historyComplete,
      int windowDays) {}

  // ── Demand forecast (06.x) ───────────────────────────────────────────────────

  @Schema(name = "ForecastRunRequest")
  public record ForecastRunRequest(
      @NotBlank String storeId,
      @Schema(description = "One variant, or omitted for every variant with history at the store.")
          String variantId,
      @Schema(description = "Days to forecast, 1 to 365; 28 when omitted.") Integer horizonDays) {}

  @Schema(name = "ForecastRunResponse")
  public record ForecastRunResponse(
      String storeId,
      @Schema(description = "Variants forecast in this run.") int variants,
      @Schema(description = "How many took each method: MEAN, SES, CROSTON_SBA.")
          Map<String, Integer> byMethod,
      @Schema(description = "Mean MAPE over the forecasts that could compute one; null when none.")
          BigDecimal meanMape,
      int horizonDays,
      String computedAt,
      @Schema(description = "Variants that live fourteen days or fewer, by their batches.")
          int fresh,
      @Schema(description = "Variants with a year's shape: thirteen months seen, twelve indices.")
          int seasonal,
      @Schema(description = "Variants a promotion will run on within the horizon.") int promoted) {}

  @Schema(name = "ForecastPoint")
  public record ForecastPointResponse(String day, BigDecimal qty) {}

  @Schema(name = "Forecast")
  public record ForecastResponse(
      String id,
      String storeId,
      String variantId,
      @Schema(description = "MEAN, SES (smoothing with a weekday profile) or CROSTON_SBA.")
          String method,
      @Schema(description = "Demand on fewer than three days in four: Croston's case.")
          boolean intermittent,
      @Schema(description = "The smoothing constant the hold-out chose; null for MEAN.")
          BigDecimal alpha,
      @Schema(description = "Expected demand per day before the weekday profile.") BigDecimal level,
      @Schema(description = "Seven multipliers, Monday first; empty when none.")
          List<BigDecimal> weekdayProfile,
      String historyFrom,
      String historyTo,
      int historyDays,
      int horizonDays,
      @Schema(description = "The first forecast day.") String fromDay,
      @Schema(description = "Expected demand over the next seven days.") BigDecimal next7,
      @Schema(description = "Expected demand over the next twenty-eight days.") BigDecimal next28,
      @Schema(description = "Days of history the forecast was tested against.") int holdoutDays,
      @Schema(
              description =
                  "Mean absolute percentage error over hold-out days with demand; null when none had any.")
          BigDecimal mape,
      @Schema(
              description =
                  "Forecast minus actual over the hold-out, as a percentage of actual; negative means under-forecast.")
          BigDecimal bias,
      @Schema(
              description =
                  "Mean absolute scaled error against a naive one-day-back forecast; under 1 beats it.")
          BigDecimal mase,
      @Schema(
              description =
                  "One expected quantity per day from fromDay; only on the single forecast.")
          List<ForecastPointResponse> points,
      String computedAt,
      @Schema(description = "Lives fourteen days or fewer, by the median of its dated batches.")
          boolean fresh,
      @Schema(
              description =
                  "Median days from receipt to expiry over the dated batches; null when the item keeps.")
          Integer shelfLifeDays,
      @Schema(
              description =
                  "Percent of what was received that went out of date unsold (past-date stock plus"
                      + " EXPIRY write-offs); null when nothing sold or wasted.")
          BigDecimal wasteRatePct,
      @Schema(
              description =
                  "The shelf life, the longest cover an order should be given; null when the item keeps.")
          Integer maxCoverDays,
      @Schema(
              description =
                  "Twelve monthly indices, January first, from the days no promotion ran; empty under"
                      + " thirteen months of history.")
          List<BigDecimal> seasonalIndices,
      @Schema(
              description =
                  "What a promotion does to the item: promoted-day demand over ordinary, 1 to 10;"
                      + " null when none could be measured.")
          BigDecimal uplift,
      @Schema(
              description =
                  "ITEM from its own promotions, STORE pooled across the store's; null with no uplift.")
          String upliftSource,
      @Schema(description = "History days a promotion ran on.") int promotedHistoryDays,
      @Schema(description = "Horizon days a promotion will run on, each forecast at the uplift.")
          int promotedAheadDays) {}
}
