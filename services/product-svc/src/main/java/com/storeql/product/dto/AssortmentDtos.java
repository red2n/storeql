package com.storeql.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Store clusters, dated range changes and range reviews (07.18). */
public final class AssortmentDtos {

  private AssortmentDtos() {}

  // ── clusters ────────────────────────────────────────────────────────────────

  @Schema(name = "StoreCluster")
  public record ClusterResponse(
      String id,
      String code,
      String name,
      String note,
      @Schema(description = "ACTIVE or RETIRED.") String status,
      @Schema(description = "The stores in it. A store may be in several clusters.")
          List<String> storeIds,
      String createdAt) {}

  @Schema(name = "AddClusterRequest")
  public record AddClusterRequest(
      @Schema(description = "Short handle, upper-cased. Unique among active clusters.")
          @NotBlank
          @Size(max = 40)
          String code,
      @NotBlank @Size(max = 120) String name,
      @Size(max = 500) String note) {}

  @Schema(name = "AddClusterStoresRequest")
  public record AddStoresRequest(@NotEmpty List<String> storeIds) {}

  // ── changes ─────────────────────────────────────────────────────────────────

  @Schema(name = "AssortmentChange")
  public record ChangeResponse(
      String id,
      String productId,
      @Schema(description = "Set when the change is aimed at one store.") String storeId,
      @Schema(description = "Set when it is aimed at a cluster of them.") String clusterId,
      @Schema(description = "LIST or DELIST.") String action,
      @Schema(description = "The day it takes effect. Applying is a separate step.")
          String effectiveFrom,
      String reason,
      String decidedBy,
      String createdAt,
      @Schema(
              description =
                  "When it was pushed into the live range. Null while it is still intent.")
          String appliedAt,
      @Schema(description = "The review that produced it, when it came from one.") String reviewId,
      @Schema(
              description =
                  "When the sweep closed it as refused for good — a de-list of the last stores the"
                      + " line is sold at, which no range can make apply. Null while it is open or"
                      + " once applied; a closed change is never due again.")
          String refusedAt,
      @Schema(description = "The refusal it was closed with, e.g. ASSORTMENT_LAST_STORE.")
          String refusalCode,
      @Schema(description = "The sentence it was closed with.") String refusal) {}

  @Schema(name = "RecordAssortmentChangeRequest")
  public record RecordChangeRequest(
      @NotBlank String productId,
      @Schema(description = "One store — or give a cluster instead, never both.") String storeId,
      @Schema(description = "A cluster of stores — or give a store instead, never both.")
          String clusterId,
      @Schema(description = "LIST or DELIST.") @NotBlank String action,
      @Schema(description = "The day it takes effect; today when left out.") String effectiveFrom,
      @Schema(
              description =
                  "Why. Required, and the reason this log exists: a de-list with no reason is the"
                      + " thing it is here to stop.")
          @NotBlank
          @Size(max = 500)
          String reason) {}

  /**
   * A change a sweep could not apply, and whether it waits: most stay due, so fixing the cause is
   * enough and the next sweep applies it; a de-list that would leave its line at no store ({@code
   * ASSORTMENT_LAST_STORE}) is closed as refused for good instead ({@code stillDue} false).
   */
  @Schema(name = "AssortmentChangeNotApplied")
  public record NotAppliedResponse(
      String changeId,
      String productId,
      String code,
      String detail,
      @Schema(
              description =
                  "True when the change stays due and the next sweep tries it again (fix the"
                      + " cause; nothing is re-entered). False when it was closed as refused for"
                      + " good (ASSORTMENT_LAST_STORE): reported this once, never due again.")
          boolean stillDue) {}

  @Schema(name = "AssortmentSweepResult")
  public record SweepResponse(
      @Schema(description = "How many changes reached the live range.") int applied,
      List<NotAppliedResponse> notApplied) {}

  // ── range review ────────────────────────────────────────────────────────────

  @Schema(name = "RangeReviewLine")
  public record LineResponse(
      String variantId,
      @Schema(description = "As recorded at review time, not a live read.") String unitsSold,
      String revenue,
      String margin,
      String currency,
      @Schema(description = "Where it came in the category. 1 is best.") Integer rankInCategory,
      @Schema(description = "KEEP, DELIST or INTRODUCE — null while the review is being read.")
          String decision,
      String decisionNote,
      @Schema(description = "Own-brand as it stood on the day, kept on the row.")
          boolean ownBrand) {}

  @Schema(name = "RangeReview")
  public record ReviewResponse(
      String id,
      String categoryId,
      String name,
      String periodFrom,
      String periodTo,
      @Schema(description = "OPEN, DECIDED or ABANDONED.") String status,
      String note,
      @Schema(description = "How many lines still have no decision.") long undecided,
      String createdAt,
      String decidedAt,
      List<LineResponse> lines) {}

  @Schema(name = "OpenRangeReviewRequest")
  public record OpenReviewRequest(
      @NotBlank String categoryId,
      @NotBlank @Size(max = 120) String name,
      @Schema(description = "Inclusive.") @NotBlank String periodFrom,
      @Schema(description = "Exclusive.") @NotBlank String periodTo,
      @Size(max = 500) String note) {}

  @Schema(name = "AddRangeReviewLineRequest")
  public record AddLineRequest(
      @NotBlank String variantId,
      @Schema(description = "What it sold over the period.") BigDecimal unitsSold,
      BigDecimal revenue,
      BigDecimal margin,
      @Schema(description = "Required exactly when revenue or margin is given.") @Size(max = 3)
          String currency,
      @Min(1) Integer rankInCategory,
      @Schema(description = "True when the business owns the brand.") boolean ownBrand) {}

  @Schema(name = "AddRangeReviewLinesRequest")
  public record AddLinesRequest(@NotEmpty @Valid List<AddLineRequest> lines) {}

  @Schema(name = "DecideRangeReviewLineRequest")
  public record DecideRequest(
      @NotBlank String variantId,
      @Schema(description = "KEEP, DELIST or INTRODUCE.") @NotBlank String decision,
      @Schema(description = "Required when dropping an own-brand line.") @Size(max = 500)
          String note) {}

  /** A product a closing review did not touch, and why. */
  @Schema(name = "RangeReviewLeftAlone")
  public record LeftAloneResponse(String productId, String reason) {}

  @Schema(name = "CloseRangeReviewRequest")
  public record CloseReviewRequest(
      @Schema(description = "The store the decisions apply to — or a cluster, never both.")
          String storeId,
      String clusterId,
      @Schema(description = "The day the changes take effect, usually the next reset.")
          String effectiveFrom) {}

  @Schema(name = "RangeReviewResult")
  public record ReviewResultResponse(
      ReviewResponse review,
      @Schema(description = "The range changes the decisions produced. Recorded, not yet applied.")
          List<ChangeResponse> changes,
      @Schema(
              description =
                  "Products the review deliberately left ranged, with the reason — a sibling variant"
                      + " kept, or a variant the review never covered.")
          List<LeftAloneResponse> leftAlone) {}
}
