package com.storeql.inventory.dto;

import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response DTOs for food-safety checks. No tenant_id in requests — it comes from context.
 */
public final class FoodSafetyDtos {

  private FoodSafetyDtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(
      name = "RecordFoodSafetyCheckRequest",
      description =
          "One check at a monitoring point: a reading for a temperature check, or passed for a"
              + " pass/fail check. The result is decided by the server, never sent.")
  public record RecordCheckRequest(
      @Schema(description = "UUID of the monitoring point checked.") @NotBlank String pointId,
      @Schema(description = "The reading, in degrees Celsius. Temperature checks only.")
          BigDecimal value,
      @Schema(description = "Whether the check passed. Pass/fail checks only.") Boolean passed,
      @Schema(description = "What was seen.") @Size(max = 2000) String notes,
      @Schema(description = "What the check refers to, e.g. GRN for a delivery.")
          @Pattern(regexp = "^[A-Z][A-Z_]{1,39}$", message = "refType must be an upper-case code")
          String refType,
      @Schema(description = "UUID of the referenced record.") String refId) {}

  @Schema(
      name = "FoodSafetyCorrectiveActionRequest",
      description = "What was done about a failed check.")
  public record CorrectiveActionRequest(
      @Schema(description = "The action taken.") @NotBlank @Size(max = 2000) String action,
      @Schema(
              description = "What happened to the food.",
              enumeration = {"NONE", "DISCARDED", "MOVED", "REHEATED", "RECOOKED", "OTHER"})
          @NotBlank
          @Pattern(
              regexp = "NONE|DISCARDED|MOVED|REHEATED|RECOOKED|OTHER",
              message = "foodDisposition is not recognised")
          String foodDisposition) {}

  @Schema(name = "CreateFoodSafetyCheckTypeRequest", description = "A tenant's own check type.")
  public record CreateCheckTypeRequest(
      @Schema(description = "Stable upper-case code, unique within the tenant.")
          @NotBlank
          @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$", message = "code must be an upper-case code")
          String code,
      @NotBlank @Size(max = 120) String name,
      @Schema(enumeration = {"TEMPERATURE", "PASS_FAIL"})
          @NotBlank
          @Pattern(
              regexp = "TEMPERATURE|PASS_FAIL",
              message = "kind must be TEMPERATURE or PASS_FAIL")
          String kind,
      @Schema(description = "Lowest passing reading, °C. Temperature types only.")
          BigDecimal minValue,
      @Schema(description = "Highest passing reading, °C. Temperature types only.")
          BigDecimal maxValue,
      @Schema(description = "Always C for a temperature type; omit for pass/fail.") String unit,
      @Schema(description = "Where the limit comes from.") @Size(max = 500) String basis) {}

  @Schema(name = "UpdateFoodSafetyCheckTypeRequest")
  public record UpdateCheckTypeRequest(
      @NotBlank @Size(max = 120) String name,
      BigDecimal minValue,
      BigDecimal maxValue,
      @Size(max = 500) String basis,
      @NotNull Boolean active) {}

  @Schema(
      name = "CreateFoodSafetyPointRequest",
      description =
          "A chiller, freezer, hot cabinet, goods-in bay or checklist. Limits default to the"
              + " check type's and may only be stricter.")
  public record CreatePointRequest(
      @NotBlank String storeId,
      String zoneId,
      @NotBlank @Size(max = 120) String name,
      @NotBlank String checkTypeId,
      BigDecimal minValue,
      BigDecimal maxValue,
      @Schema(description = "How often it must be checked, in hours (1 to 744).")
          @NotNull
          @Min(1)
          @Max(744)
          @JsonbTypeDeserializer(WholeNumbers.ExactInt.class)
          Integer frequencyHours) {}

  @Schema(name = "UpdateFoodSafetyPointRequest")
  public record UpdatePointRequest(
      String zoneId,
      @NotBlank @Size(max = 120) String name,
      BigDecimal minValue,
      BigDecimal maxValue,
      @NotNull @Min(1) @Max(744) @JsonbTypeDeserializer(WholeNumbers.ExactInt.class)
          Integer frequencyHours) {}

  @Schema(name = "FoodSafetyPointStatusRequest", description = "Why a point is switched on or off.")
  public record PointStatusRequest(@NotBlank @Size(max = 500) String reason) {}

  @Schema(
      name = "CreateFoodSafetyReviewRequest",
      description = "A manager signing off a store's records for a period.")
  public record CreateReviewRequest(
      @NotBlank String storeId,
      @Schema(description = "ISO-8601 instant, inclusive, e.g. 2026-09-01T00:00:00Z.") @NotBlank
          String from,
      @Schema(description = "ISO-8601 instant, exclusive.") @NotBlank String to,
      @Size(max = 2000) String notes) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "FoodSafetyCheckType")
  public record CheckTypeResponse(
      String id,
      String code,
      String name,
      String kind,
      BigDecimal minValue,
      BigDecimal maxValue,
      String unit,
      String basis,
      @Schema(description = "True only where the limit is law rather than guidance.")
          boolean statutory,
      @Schema(description = "A platform reference type, which no tenant can edit.")
          boolean platform,
      boolean active) {}

  @Schema(name = "FoodSafetyPoint")
  public record PointResponse(
      String id,
      String storeId,
      String zoneId,
      String name,
      CheckTypeResponse checkType,
      BigDecimal minValue,
      BigDecimal maxValue,
      int frequencyHours,
      boolean active,
      String lastRecordedAt,
      String lastResult,
      BigDecimal lastValue,
      String nextDueAt,
      @Schema(enumeration = {"OK", "DUE", "OVERDUE"}) String dueStatus,
      long openFailures) {}

  @Schema(name = "FoodSafetyCorrectiveAction")
  public record CorrectiveActionResponse(
      String id,
      String recordId,
      String action,
      String foodDisposition,
      String recordedBy,
      String recordedAt) {}

  @Schema(name = "FoodSafetyCheckRecord")
  public record CheckRecordResponse(
      String id,
      String storeId,
      String pointId,
      String pointName,
      String checkTypeCode,
      String checkTypeName,
      String kind,
      BigDecimal value,
      String unit,
      @Schema(description = "The limits as they stood when the check was judged.")
          BigDecimal minValue,
      BigDecimal maxValue,
      @Schema(enumeration = {"PASS", "FAIL"}) String result,
      String notes,
      String refType,
      String refId,
      String recordedBy,
      String recordedAt,
      int correctiveActionCount,
      @Schema(description = "A failure with no corrective action recorded yet.")
          boolean openFailure,
      @Schema(description = "Present on a single record; omitted from lists.")
          List<CorrectiveActionResponse> correctiveActions) {}

  @Schema(name = "FoodSafetyReview")
  public record ReviewResponse(
      String id,
      String storeId,
      String periodFrom,
      String periodTo,
      int recordsCount,
      int failuresCount,
      int openFailuresCount,
      String notes,
      String reviewedBy,
      String reviewedAt) {}
}
