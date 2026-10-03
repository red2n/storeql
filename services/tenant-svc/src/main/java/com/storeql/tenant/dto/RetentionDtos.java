package com.storeql.tenant.dto;

import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for retention schedules (21.16). No tenant_id in requests. */
public final class RetentionDtos {

  private RetentionDtos() {}

  @Schema(name = "SetRetentionRequest", description = "How long a class of data is kept.")
  public record SetPeriodRequest(
      @Schema(description = "Days; 0 means purged as soon as settled. Never under the law's floor.")
          @NotNull
          @Min(0)
          @Max(36500)
          @JsonbTypeDeserializer(WholeNumbers.ExactInt.class)
          Integer periodDays) {}

  @Schema(name = "PlaceRetentionHoldRequest", description = "A matter that stops a purge.")
  public record PlaceHoldRequest(
      @Schema(description = "One class, or none for every class.") @Size(max = 40) String dataClass,
      @Schema(enumeration = {"ALL", "CUSTOMER", "ORDER"})
          @NotBlank
          @Pattern(regexp = "ALL|CUSTOMER|ORDER", message = "subjectKind is not recognised")
          String subjectKind,
      @Schema(description = "The customer or order held; none for ALL.") String subjectId,
      @NotBlank @Size(max = 2000) String reason) {}

  @Schema(name = "ReleaseRetentionHoldRequest")
  public record ReleaseHoldRequest(@NotBlank @Size(max = 2000) String reason) {}

  @Schema(
      name = "RetentionClass",
      description = "A class of data: the law's floor and the period set.")
  public record ClassResponse(
      String code,
      String name,
      @Schema(enumeration = {"DELETE", "ANONYMISE", "KEEP"}) String purgeKind,
      String purgedBy,
      String description,
      @Schema(description = "The least the law of a country traded in requires; absent when none.")
          Integer floorDays,
      String floorScope,
      String floorCitation,
      String floorSummary,
      @Schema(description = "What the business set; absent until it decides.") Integer periodDays,
      String setBy,
      String setAt) {}

  @Schema(name = "RetentionHold")
  public record HoldResponse(
      String id,
      String dataClass,
      String subjectKind,
      String subjectId,
      String reason,
      String placedBy,
      String placedAt,
      boolean active,
      String releasedBy,
      String releasedAt,
      String releaseReason) {}

  @Schema(name = "RetentionSheet", description = "The business's schedule, floors and holds.")
  public record SheetResponse(
      String country,
      List<String> countries,
      List<ClassResponse> classes,
      @Schema(description = "The holds in force.") List<HoldResponse> holds) {}

  @Schema(name = "RetentionRun", description = "One purge a service ran.")
  public record RunResponse(
      String id,
      String service,
      String dataClass,
      String cutoff,
      int rowsAffected,
      int heldSkipped,
      String startedAt,
      String finishedAt,
      String recordedAt) {}
}
