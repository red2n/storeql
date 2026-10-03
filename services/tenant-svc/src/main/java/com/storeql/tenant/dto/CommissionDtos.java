package com.storeql.tenant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Commission arrangements and the rating of sales under them (store operations & workforce). */
public final class CommissionDtos {

  private CommissionDtos() {}

  @Schema(name = "CommissionBand")
  public record BandResponse(
      @Schema(description = "The period-to-date figure this band starts at; the first is 0.")
          String thresholdFrom,
      @Schema(description = "A percentage for PERCENT_OF_NET, an amount per unit for PER_UNIT.")
          String rate) {}

  @Schema(name = "CommissionScheme")
  public record SchemeResponse(
      String id,
      String name,
      @Schema(description = "PERCENT_OF_NET or PER_UNIT.") String basis,
      @Schema(description = "The currency a PER_UNIT amount is in; absent for a percentage.")
          String currency,
      @Schema(description = "ACTIVE or WITHDRAWN.") String status,
      String note,
      @Schema(description = "The version this one replaced, when it was corrected.")
          String supersedes,
      @Schema(
              description =
                  "The version that replaced this one; set means it is no longer in force.")
          String supersededBy,
      @Schema(description = "Marginal rate bands, lowest threshold first.")
          List<BandResponse> bands,
      String createdAt) {

    public SchemeResponse {
      bands = bands == null ? List.of() : List.copyOf(bands);
    }
  }

  @Schema(name = "CommissionBandRequest")
  public record BandRequest(
      @Schema(
              description =
                  "Where the band starts: an amount of the business's currency for PERCENT_OF_NET,"
                      + " held to that currency's minor units (none for JPY, three for KWD); a"
                      + " whole number of units for PER_UNIT.")
          @NotNull
          @DecimalMin("0")
          @Fits(integer = 16, fraction = 4)
          BigDecimal thresholdFrom,
      @Schema(
              description =
                  "A percentage, or an amount per unit: a rate, kept to four decimal places.")
          @NotNull
          @DecimalMin("0")
          @Fits(integer = 8, fraction = 4)
          BigDecimal rate) {}

  @Schema(
      name = "CommissionSchemeRequest",
      description =
          "A commission arrangement and its marginal rate bands. The first band starts at 0, or the"
              + " first sales of every period would earn nothing.")
  public record SchemeRequest(
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 20) String basis,
      @Size(max = 3) String currency,
      @NotNull @Valid @Size(min = 1, max = 20) List<BandRequest> bands,
      @Size(max = 500) String note,
      @Schema(
              description =
                  "When correcting a scheme: the day the new rates apply from, and the day whoever is"
                      + " on the old version moves across. Defaults to today.")
          @Size(max = 10)
          String effectiveFrom) {}

  @Schema(name = "CommissionAssignment")
  public record AssignmentResponse(
      String id,
      String userId,
      @Schema(description = "Absent when this row ended the arrangement.") String schemeId,
      String effectiveFrom,
      String note,
      String createdAt) {}

  @Schema(
      name = "CommissionAssignmentRequest",
      description = "Puts somebody on a scheme from a day. Leave schemeId out to end it.")
  public record AssignmentRequest(
      String schemeId, @Size(max = 10) String effectiveFrom, @Size(max = 500) String note) {}

  @Schema(name = "CommissionDayRequest", description = "What one person sold on one day.")
  public record DayRequest(
      @NotBlank @Size(max = 10) String day,
      // A day's figures as the selling service sends them: its net at its currency's minor units
      // (never more than four), its units at a quantity's three places. Either may be below
      // zero, where returns outweigh sales.
      @Schema(description = "Net of VAT and after discounts.")
          @NotNull
          @Fits(integer = 16, fraction = 4)
          BigDecimal net,
      @Schema(description = "Units sold, for a per-unit arrangement.")
          @Fits(integer = 16, fraction = 3)
          BigDecimal units) {}

  @Schema(name = "CommissionSellerRequest")
  public record SellerRequest(
      @NotBlank String userId, @NotNull @Valid @Size(max = 400) List<DayRequest> days) {}

  @Schema(
      name = "CommissionRateRequest",
      description =
          "What each person sold, day by day, for the service that holds the sales. Figures only:"
              + " no sale ever leaves the service that owns it.")
  public record RateRequest(
      @NotBlank @Size(max = 10) String from,
      @NotBlank @Size(max = 10) String to,
      @NotNull @Valid @Size(max = 500) List<SellerRequest> sellers) {}

  @Schema(name = "CommissionEarnedBand")
  public record EarnedResponse(
      String thresholdFrom, String rate, String amountInBand, String commission) {}

  @Schema(
      name = "CommissionSegment",
      description =
          "A stretch of days under one arrangement. A new arrangement starts its own band"
              + " progression, so a scheme change mid-period is two segments.")
  public record SegmentResponse(
      @Schema(description = "Absent when the person was on no arrangement for those days.")
          String schemeId,
      String schemeName,
      String from,
      String to,
      @Schema(description = "Net sales, or units under a per-unit arrangement.") String amount,
      List<EarnedResponse> bands,
      String commission) {

    public SegmentResponse {
      bands = bands == null ? List.of() : List.copyOf(bands);
    }
  }

  @Schema(name = "CommissionRated")
  public record RatedResponse(
      String userId,
      List<SegmentResponse> segments,
      String commission,
      @Schema(description = "The currency of a per-unit arrangement; absent for a percentage.")
          String currency) {

    public RatedResponse {
      segments = segments == null ? List.of() : List.copyOf(segments);
    }
  }
}
