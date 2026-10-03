package com.storeql.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Attributed sales and the statements made of them (store operations & workforce). */
public final class CommissionDtos {

  private CommissionDtos() {}

  @Schema(
      name = "CommissionStatementLine",
      description =
          "One person, one stretch of days under one arrangement, one rate band. A line with no"
              + " scheme carries sales that earned nothing, so a statement's sales add up to the"
              + " period's takings.")
  public record LineResponse(
      String sellerUserId,
      String schemeId,
      String schemeName,
      String segmentFrom,
      String segmentTo,
      @Schema(
              description =
                  "Where the band starts, as the arrangement rated it: an amount of the sales"
                      + " under a percentage, a count of units under a per-unit arrangement —"
                      + " never rounded to the statement currency's minor units.")
          String thresholdFrom,
      @Schema(description = "A percentage, or an amount per unit in rateCurrency.") String rate,
      @Schema(description = "Net sales, or units under a per-unit arrangement.") String amount,
      @Schema(description = "In the statement's currency, at its own minor units.")
          String commission,
      @Schema(
              description =
                  "The currency a per-unit rate, and what it earned, is in. Absent for a"
                      + " percentage and for sales under no arrangement.")
          String rateCurrency,
      @Schema(
              description =
                  "What the band earned in rateCurrency, when that is not the statement's"
                      + " currency and commission is its translation at the business's own rate."
                      + " Absent when nothing was translated.")
          String ratedCommission) {}

  @Schema(name = "CommissionStatement")
  public record StatementResponse(
      String id,
      @Schema(description = "Absent for a statement covering every store.") String storeId,
      String periodStart,
      String periodEnd,
      String currency,
      @Schema(description = "DRAFT, APPROVED or SUPERSEDED.") String status,
      String netSales,
      String commission,
      String note,
      @Schema(description = "The statement this one replaced, when it is a restatement.")
          String supersedes,
      String supersededBy,
      String createdAt,
      String createdBy,
      String approvedAt,
      String approvedBy,
      @Schema(description = "Left out of a list; present when one statement is read.")
          List<LineResponse> lines) {

    public StatementResponse {
      lines = lines == null ? null : List.copyOf(lines);
    }
  }

  @Schema(
      name = "CommissionStatementRequest",
      description =
          "A statement for a finished period. Name supersedes to restate a period already approved;"
              + " nothing else can change an approved statement, because somebody was paid on it.")
  public record StatementRequest(
      @NotBlank @Size(max = 10) String from,
      @NotBlank @Size(max = 10) String to,
      @Schema(description = "One store, or omit for every store.") String storeId,
      @Schema(description = "The currency counted; the business's own when omitted.") @Size(max = 3)
          String currency,
      @Size(max = 500) String note,
      String supersedes) {}

  @Schema(
      name = "SaleSellerRequest",
      description =
          "Credits a sale to somebody, or to nobody by leaving sellerUserId out. The reason is"
              + " required and kept: commission follows attribution.")
  public record SellerRequest(String sellerUserId, @NotBlank @Size(max = 500) String reason) {}

  @Schema(name = "SaleSellerChange", description = "Append-only record of an attribution change.")
  public record SellerChangeResponse(
      String id,
      String orderId,
      String fromUserId,
      String toUserId,
      String reason,
      String changedAt,
      String changedBy) {}
}
