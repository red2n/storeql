package com.storeql.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request and response shapes of the chargeback register (11.9). */
public final class DisputeDtos {

  private DisputeDtos() {}

  /**
   * A chargeback the acquirer has told the business about, for a card taken on a terminal the
   * platform does not talk to. A provider's own disputes arrive by webhook and are never recorded
   * this way.
   */
  @Schema(name = "RecordDisputeRequest")
  public record RecordDisputeRequest(
      @Schema(description = "The card tender disputed.") @NotNull java.util.UUID paymentId,
      @Schema(
              description =
                  "What is disputed; the whole tender when absent. No finer than the currency's"
                      + " minor unit (DISPUTE_AMOUNT_INVALID otherwise).")
          @jakarta.validation.constraints.Positive
          @Digits(integer = 14, fraction = 4)
          BigDecimal amount,
      @Schema(
              description =
                  "What the acquirer charges for the dispute itself, no finer than the currency's"
                      + " minor unit.")
          @DecimalMin(value = "0")
          @Digits(integer = 14, fraction = 4)
          BigDecimal feeAmount,
      @Schema(description = "ISO 4217; the business's own currency when absent.")
          @Size(min = 3, max = 3)
          String currency,
      @Schema(
              description =
                  "FRAUDULENT, PRODUCT_NOT_RECEIVED, PRODUCT_UNACCEPTABLE, DUPLICATE,"
                      + " CREDIT_NOT_PROCESSED, SUBSCRIPTION_CANCELLED, UNRECOGNIZED or GENERAL.")
          @NotBlank
          @Size(max = 40)
          String reason,
      @Schema(description = "The card scheme's own reason code, e.g. Visa 10.4.") @Size(max = 20)
          String networkReasonCode,
      @Schema(description = "The acquirer's case number.") @NotBlank @Size(max = 255)
          String caseReference,
      @Schema(description = "When the acquirer must have the business's answer, UTC.") @NotNull
          java.time.Instant evidenceDueBy,
      @Schema(description = "Whether the acquirer has already taken the money. Usually it has.")
          Boolean fundsWithdrawn) {}

  /** What the business answers a dispute with. */
  @Schema(name = "DisputeEvidenceRequest")
  public record EvidenceRequest(
      @Size(max = 2000) String productDescription,
      @Size(max = 200) String customerName,
      @Size(max = 320) String customerEmail,
      @Schema(description = "The receipt or invoice number the customer was given.")
          @Size(max = 200)
          String receiptReference,
      @Schema(description = "Collected in store on a date, delivered to an address, signed for.")
          @Size(max = 4000)
          String fulfilmentProof,
      @Size(max = 4000) String customerCommunication,
      @Size(max = 4000) String refundPolicy,
      @Size(max = 4000) String notes) {}

  /** How a dispute the acquirer told the business about ended. */
  @Schema(name = "ResolveDisputeRequest")
  public record ResolveRequest(
      @Schema(description = "WON or LOST.") @NotBlank @Size(max = 10) String outcome,
      @Size(max = 1000) String note) {}

  @Schema(name = "Dispute")
  public record DisputeResponse(
      String id,
      String paymentId,
      String orderId,
      String storeId,
      String provider,
      String reference,
      BigDecimal amount,
      BigDecimal feeAmount,
      String currency,
      String reason,
      String networkReasonCode,
      String status,
      boolean fundsWithdrawn,
      String evidenceDueBy,
      @Schema(description = "Whether the answer is still owed and its date has passed.")
          boolean overdue,
      String openedAt,
      String closedAt,
      @Schema(
              description =
                  "The currency feeAmount is in: the acquirer's settlement currency, which need"
                      + " not be the disputed charge's (currency).")
          String feeCurrency) {}

  @Schema(name = "DisputeEvent")
  public record EventResponse(String kind, String detail, String actorId, String at) {}

  @Schema(name = "DisputeEvidence")
  public record EvidenceResponse(
      String productDescription,
      String customerName,
      String customerEmail,
      String receiptReference,
      String fulfilmentProof,
      String customerCommunication,
      String refundPolicy,
      String notes,
      String submittedBy,
      String submittedAt) {}

  @Schema(name = "DisputeFile")
  public record FileResponse(
      DisputeResponse dispute, List<EventResponse> history, EvidenceResponse evidence) {
    public FileResponse {
      history = List.copyOf(history);
    }
  }

  /** Disputes over a period against the card payments they came out of. */
  @Schema(name = "DisputeSummary")
  public record SummaryResponse(
      String from,
      String to,
      int opened,
      int needsResponse,
      int underReview,
      int won,
      int lost,
      BigDecimal amountDisputed,
      BigDecimal amountLost,
      BigDecimal feesCharged,
      int cardPayments,
      @Schema(description = "Disputes opened over card payments taken, as a fraction.")
          BigDecimal disputeRatio,
      @Schema(
              description =
                  "Whether the ratio is at or past the level the card schemes start monitoring a"
                      + " merchant at.")
          boolean aboveMonitoringThreshold) {}
}
