package com.storeql.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Chargebacks and disputes (11.9): a cardholder's bank taking a card payment back. */
public final class Disputes {

  private Disputes() {}

  /** The business has until the due date to answer. */
  public static final String NEEDS_RESPONSE = "NEEDS_RESPONSE";

  /** Evidence is in; the scheme is deciding. */
  public static final String UNDER_REVIEW = "UNDER_REVIEW";

  public static final String WON = "WON";
  public static final String LOST = "LOST";

  /** The business chose not to contest: lost, by its own decision. */
  public static final String ACCEPTED = "ACCEPTED";

  public static final Set<String> OPEN = Set.of(NEEDS_RESPONSE, UNDER_REVIEW);

  /** Why a cardholder disputes, in the categories the providers share. */
  public static final Set<String> REASONS =
      Set.of(
          "FRAUDULENT",
          "PRODUCT_NOT_RECEIVED",
          "PRODUCT_UNACCEPTABLE",
          "DUPLICATE",
          "CREDIT_NOT_PROCESSED",
          "SUBSCRIPTION_CANCELLED",
          "UNRECOGNIZED",
          "GENERAL");

  public static final String EVENT_OPENED = "OPENED";
  public static final String EVENT_FUNDS_WITHDRAWN = "FUNDS_WITHDRAWN";
  public static final String EVENT_FUNDS_REINSTATED = "FUNDS_REINSTATED";
  public static final String EVENT_EVIDENCE_SUBMITTED = "EVIDENCE_SUBMITTED";
  public static final String EVENT_ACCEPTED = "ACCEPTED";
  public static final String EVENT_WON = "WON";
  public static final String EVENT_LOST = "LOST";

  /**
   * A dispute.
   *
   * @param provider who told us: a payment provider, or MANUAL when staff recorded the acquirer's
   *     letter
   * @param providerDisputeRef the provider's id for it, or the acquirer's case number
   * @param feeAmount what the acquirer charges for the dispute itself, in {@code feeCurrency}
   * @param fundsWithdrawn whether the acquirer has taken the disputed amount
   * @param feeCurrency the currency the fee was charged in: the acquirer's settlement currency,
   *     which need not be {@code currency}, the disputed charge's
   */
  public record Dispute(
      UUID id,
      UUID tenantId,
      UUID paymentId,
      UUID orderId,
      UUID storeId,
      String provider,
      String providerDisputeRef,
      BigDecimal amount,
      BigDecimal feeAmount,
      String currency,
      String reason,
      String networkReasonCode,
      String status,
      boolean fundsWithdrawn,
      Instant evidenceDueBy,
      Instant openedAt,
      Instant closedAt,
      String idempotencyKey,
      UUID createdBy,
      String feeCurrency) {

    public boolean open() {
      return OPEN.contains(status);
    }
  }

  /** One thing that happened to a dispute. */
  public record DisputeEvent(
      UUID id, String kind, String detail, UUID actorId, Instant createdAt) {}

  /** What the business answered with. */
  public record Evidence(
      String productDescription,
      String customerName,
      String customerEmail,
      String receiptReference,
      String fulfilmentProof,
      String customerCommunication,
      String refundPolicy,
      String notes,
      UUID submittedBy,
      Instant submittedAt) {}

  /** A dispute with its history and its answer, as the register's detail shows it. */
  public record DisputeFile(Dispute dispute, List<DisputeEvent> events, Evidence evidence) {
    public DisputeFile {
      events = List.copyOf(events);
    }
  }

  /**
   * Disputes over a period, against the card payments they came out of: the ratio the card schemes
   * watch.
   *
   * @param cardPayments card, wallet and UPI tenders captured in the period
   * @param ratio disputes opened over card payments, as a fraction; null when there were none
   */
  public record Summary(
      int opened,
      int needsResponse,
      int underReview,
      int won,
      int lost,
      BigDecimal amountDisputed,
      BigDecimal amountLost,
      BigDecimal feesCharged,
      int cardPayments,
      BigDecimal ratio) {}
}
