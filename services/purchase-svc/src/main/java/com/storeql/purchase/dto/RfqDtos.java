package com.storeql.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire contract for requests for quotation. */
public final class RfqDtos {

  private RfqDtos() {}

  @Schema(name = "RfqLineRequest", description = "One thing asked for.")
  public record RfqLineRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @Size(max = 500) String notes) {}

  @Schema(name = "CreateRfqRequest", description = "A request for quotation to several suppliers.")
  public record CreateRfqRequest(
      @NotBlank @Size(max = 200) String title,
      @Schema(description = "The store the goods are for.") @NotBlank String storeId,
      @Schema(
              description =
                  "The day the goods are needed, as yyyy-MM-dd; becomes the orders' expected delivery.")
          String neededBy,
      @Schema(description = "The day quotes are due, as yyyy-MM-dd; advisory.") String closesOn,
      @Size(max = 2000) String notes,
      @NotNull @Valid List<RfqLineRequest> lines,
      @Schema(description = "The suppliers asked to quote.") @NotNull List<String> supplierIds) {}

  @Schema(
      name = "RfqQuoteLineRequest",
      description = "A supplier's price for one line, in the quote's currency.")
  public record RfqQuoteLineRequest(
      @NotBlank String variantId, @NotNull @DecimalMin("0") BigDecimal unitPrice) {}

  @Schema(
      name = "RfqQuoteRequest",
      description = "What a supplier said, recorded by the buyer; replaces any earlier quote.")
  public record RfqQuoteRequest(
      @Schema(description = "ISO 4217; the supplier's own currency when unsaid.") String currency,
      @Min(0) Integer leadTimeDays,
      @Schema(description = "The day the quote lapses, as yyyy-MM-dd.") String validUntil,
      @Size(max = 2000) String notes,
      @NotNull @Valid List<RfqQuoteLineRequest> lines) {}

  @Schema(name = "RfqAwardLineRequest")
  public record RfqAwardLineRequest(@NotBlank String variantId, @NotBlank String supplierId) {}

  @Schema(
      name = "RfqAwardRequest",
      description = "Which supplier gets which line; lines left out are not awarded.")
  public record RfqAwardRequest(@NotNull @Valid List<RfqAwardLineRequest> awards) {}

  @Schema(name = "RfqSummaryResponse")
  public record RfqSummaryResponse(
      UUID id,
      String reference,
      String title,
      UUID storeId,
      String status,
      LocalDate neededBy,
      LocalDate closesOn,
      int lines,
      int suppliers,
      @Schema(description = "Suppliers that have quoted.") int quotes,
      Instant createdAt) {}

  @Schema(name = "RfqLineResponse")
  public record RfqLineResponse(UUID id, UUID variantId, BigDecimal qty, String notes) {}

  @Schema(name = "RfqPriceResponse")
  public record RfqPriceResponse(UUID variantId, BigDecimal unitPrice) {}

  @Schema(name = "RfqBidResponse", description = "One supplier asked, and what they said.")
  public record RfqBidResponse(
      UUID supplierId,
      String supplierName,
      @Schema(description = "INVITED, QUOTED or DECLINED.") String status,
      String currency,
      Integer leadTimeDays,
      LocalDate validUntil,
      String notes,
      Instant quotedAt,
      @Schema(
              description =
                  "The supplier's scorecard grade over the last 90 days; null with nothing to judge.")
          String grade,
      List<RfqPriceResponse> prices) {}

  @Schema(
      name = "RfqPriceComparisonResponse",
      description = "One supplier's price for one line, as quoted and at home.")
  public record RfqPriceComparisonResponse(
      UUID supplierId,
      BigDecimal unitPrice,
      String currency,
      @Schema(description = "In the business's own currency; null when no rate is kept.")
          BigDecimal homeUnitPrice,
      BigDecimal lineTotal,
      BigDecimal homeLineTotal,
      @Schema(description = "The lowest price at home for this line.") boolean lowest) {}

  @Schema(name = "RfqLineComparisonResponse")
  public record RfqLineComparisonResponse(
      UUID variantId, BigDecimal qty, List<RfqPriceComparisonResponse> prices) {}

  @Schema(name = "RfqBidSummaryResponse", description = "One bid added up and ranked.")
  public record RfqBidSummaryResponse(
      UUID supplierId,
      String supplierName,
      String status,
      @Schema(description = "Whether every line was priced.") boolean complete,
      BigDecimal total,
      String currency,
      BigDecimal homeTotal,
      @Schema(description = "1 for the cheapest complete bid readable at home; null when unranked.")
          Integer rank) {}

  @Schema(name = "RfqComparisonResponse")
  public record RfqComparisonResponse(
      String homeCurrency,
      List<RfqLineComparisonResponse> lines,
      List<RfqBidSummaryResponse> bids) {}

  @Schema(name = "RfqAwardResponse")
  public record RfqAwardResponse(
      UUID variantId, UUID supplierId, UUID poId, BigDecimal unitPrice, String currency) {}

  @Schema(name = "RfqResponse", description = "The request in full.")
  public record RfqResponse(
      UUID id,
      String reference,
      String title,
      UUID storeId,
      String status,
      LocalDate neededBy,
      LocalDate closesOn,
      String notes,
      Instant createdAt,
      Instant issuedAt,
      Instant awardedAt,
      Instant cancelledAt,
      String cancelledReason,
      List<RfqLineResponse> lines,
      List<RfqBidResponse> bids,
      RfqComparisonResponse comparison,
      List<RfqAwardResponse> awards,
      @Schema(description = "The draft orders the award raised, one per supplier.")
          List<UUID> purchaseOrderIds) {}
}
