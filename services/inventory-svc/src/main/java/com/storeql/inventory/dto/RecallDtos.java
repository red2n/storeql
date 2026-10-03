package com.storeql.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for withdrawals and recalls. No tenant_id in requests. */
public final class RecallDtos {

  private RecallDtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(
      name = "OpenRecallRequest",
      description =
          "A withdrawal or recall notice. Every batch in scope is taken off sale as it is opened.")
  public record OpenRecallRequest(
      @Schema(description = "The notice's reference — the supplier's, or the regulator's. Unique.")
          @NotBlank
          @Size(max = 80)
          String reference,
      @Schema(enumeration = {"WITHDRAWAL", "RECALL"})
          @NotBlank
          @Pattern(regexp = "WITHDRAWAL|RECALL", message = "kind must be WITHDRAWAL or RECALL")
          String kind,
      @Schema(
              enumeration = {
                "MICROBIOLOGICAL",
                "ALLERGEN",
                "FOREIGN_BODY",
                "CHEMICAL",
                "LABELLING",
                "QUALITY",
                "OTHER"
              })
          @NotBlank
          @Pattern(
              regexp = "MICROBIOLOGICAL|ALLERGEN|FOREIGN_BODY|CHEMICAL|LABELLING|QUALITY|OTHER",
              message = "hazard is not recognised")
          String hazard,
      @Schema(description = "What is wrong, as the notice says it.") @NotBlank @Size(max = 2000)
          String reason,
      @Schema(description = "The point-of-sale notice. Required for a RECALL.") @Size(max = 2000)
          String customerNotice,
      @Schema(
              description =
                  "Who raised the notice. REGULATOR is whichever authority the business answers to;"
                      + " FSA and FSS remain valid for the UK regulators.",
              enumeration = {
                "REGULATOR",
                "MANUFACTURER",
                "SUPPLIER",
                "INTERNAL",
                "FSA",
                "FSS",
                "OTHER"
              })
          @NotBlank
          @Pattern(
              regexp = "REGULATOR|MANUFACTURER|SUPPLIER|INTERNAL|FSA|FSS|OTHER",
              message = "source is not recognised")
          String source,
      @Schema(description = "The source's own reference, e.g. a regulator's alert number.")
          @Size(max = 120)
          String sourceReference,
      @Schema(description = "What is affected. At most 100 lines.") @NotEmpty @Valid
          List<ScopeLineRequest> items,
      @Schema(
              description =
                  "What a buyer may choose from (GPSR art.37). Required for a RECALL; at least two"
                      + " where the EU's rule binds, or one with singleRemedyReason.",
              enumeration = {"REPAIR", "REPLACEMENT", "REFUND"})
          @Size(max = 3)
          List<
                  @Pattern(
                      regexp = "REPAIR|REPLACEMENT|REFUND",
                      message = "remedy is not recognised")
                  String>
              remedies,
      @Schema(description = "Why only one remedy is offered, where the law asks for two.")
          @Size(max = 500)
          String singleRemedyReason,
      @Schema(description = "A free number a buyer can call (GPSR art.36(2)(f)).")
          @Size(max = 40)
          @Pattern(
              regexp = "[+0-9 ()-]*",
              message = "contactPhone may hold digits, spaces, +, - and brackets")
          String contactPhone,
      @Schema(description = "An online service where a buyer gets more; https or http.")
          @Size(max = 500)
          String contactUrl,
      @Schema(
              description =
                  "ISO date. Buyers of sales on or after this day are told; none means every sale"
                      + " of the packs in scope.")
          String soldFrom) {}

  @Schema(
      name = "RecallScopeLineRequest",
      description =
          "A variant, optionally narrowed to a lot and a best-before or use-by range. A line"
              + " naming neither covers every pack.")
  public record ScopeLineRequest(
      @NotBlank String variantId,
      @Schema(description = "The lot or batch code printed on the pack.") @Size(max = 80)
          String batchNo,
      @Schema(description = "ISO date, inclusive.") String expiryFrom,
      @Schema(description = "ISO date, inclusive.") String expiryTo) {}

  @Schema(
      name = "RecallStoreActionRequest",
      description = "What a store found on its shelves and what became of it.")
  public record StoreActionRequest(
      @Schema(description = "How much was found and taken off the shelf.") @NotNull
          BigDecimal qtyFound,
      @Schema(enumeration = {"HELD_FOR_COLLECTION", "RETURNED_TO_SUPPLIER", "DESTROYED"})
          @NotBlank
          @Pattern(
              regexp = "HELD_FOR_COLLECTION|RETURNED_TO_SUPPLIER|DESTROYED",
              message = "disposition is not recognised")
          String disposition,
      @Schema(description = "Whether the recall notice is displayed at the point of sale.")
          boolean noticeDisplayed,
      @Size(max = 2000) String notes) {}

  @Schema(name = "RecallReasonRequest", description = "Why, kept with the record.")
  public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {}

  @Schema(name = "CloseRecallRequest")
  public record CloseRequest(
      @Schema(description = "The close-out: how much was recovered, and anything outstanding.")
          @Size(max = 2000)
          String notes) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "RecallScopeLine")
  public record ScopeLineResponse(
      String id,
      String variantId,
      String batchNo,
      String expiryFrom,
      String expiryTo,
      @Schema(description = "No lot and no dates: every pack is affected.")
          boolean coversEveryPack) {}

  @Schema(name = "RecallHeldBatch")
  public record HeldBatchResponse(
      String batchId,
      String storeId,
      String variantId,
      String batchNo,
      String expiryDate,
      @Schema(
              description =
                  "IN_SCOPE: certainly affected. LOT_UNKNOWN / DATE_UNKNOWN: held because the"
                      + " batch's lot or date is not known, and releasable once the pack is"
                      + " checked.",
              enumeration = {"IN_SCOPE", "LOT_UNKNOWN", "DATE_UNKNOWN"})
          String match,
      BigDecimal qtyAtQuarantine,
      BigDecimal remainingQty,
      @Schema(enumeration = {"OPEN", "ARRIVAL"}) String quarantinedOn,
      String quarantinedAt,
      boolean released,
      String releaseReason,
      String releasedBy,
      String releasedAt) {}

  @Schema(name = "RecallStoreAction")
  public record StoreActionResponse(
      String id,
      String storeId,
      BigDecimal qtyFound,
      @Schema(description = "What the system held off sale at the store when this was recorded.")
          BigDecimal systemQty,
      String disposition,
      boolean noticeDisplayed,
      String notes,
      String recordedBy,
      String recordedAt) {}

  @Schema(name = "RecallStoreProgress", description = "One store's part in a recall.")
  public record StoreProgressResponse(
      String storeId,
      BigDecimal qtyHeld,
      BigDecimal qtyFound,
      @Schema(description = "Still holding recalled stock with no final disposition.")
          boolean outstanding) {}

  @Schema(name = "RecallSummary")
  public record RecallSummaryResponse(
      String id,
      String reference,
      String kind,
      String hazard,
      String source,
      @Schema(enumeration = {"OPEN", "CLOSED", "CANCELLED"}) String status,
      String openedAt,
      String endedAt,
      int scopeLines,
      int storesAffected,
      int storesOutstanding,
      BigDecimal qtyHeld,
      @Schema(description = "Orders that drew on the packs in scope, found as the recall opened.")
          int ordersAffected,
      BigDecimal qtySold) {}

  @Schema(name = "Recall")
  public record RecallResponse(
      String id,
      String reference,
      String kind,
      String hazard,
      String reason,
      String customerNotice,
      String source,
      String sourceReference,
      String status,
      String openedBy,
      String openedAt,
      String endedBy,
      String endedAt,
      String endNotes,
      List<ScopeLineResponse> items,
      List<HeldBatchResponse> batches,
      List<StoreActionResponse> storeActions,
      List<StoreProgressResponse> stores,
      @Schema(enumeration = {"REPAIR", "REPLACEMENT", "REFUND"}) List<String> remedies,
      String singleRemedyReason,
      String contactPhone,
      String contactUrl,
      String soldFrom,
      @Schema(description = "Orders that drew on the packs in scope, found as the recall opened.")
          int ordersAffected,
      BigDecimal qtySold) {}

  @Schema(
      name = "ActiveRecallItem",
      description =
          "One scope line of an open recall, as the till checks an item against it — or, asked"
              + " with ?endedSince=, of one ended since then.")
  public record ActiveRecallItemResponse(
      String recallId,
      String reference,
      String kind,
      String hazard,
      String customerNotice,
      @Schema(
              description =
                  "When the recall was opened (ISO instant). order-svc judges a till sale replayed"
                      + " from an offline queue by the recalls open when it was rung up.")
          String openedAt,
      String variantId,
      String batchNo,
      String expiryFrom,
      String expiryTo,
      @Schema(
              description =
                  "When the recall was closed or cancelled (ISO instant). Only on a line asked"
                      + " for with ?endedSince=; absent while the recall is open.")
          String endedAt,
      @Schema(
              description =
                  "CLOSED or CANCELLED (raised in error), with endedAt; absent while the recall"
                      + " is open.")
          String endedAs) {}
}
