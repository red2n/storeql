package com.storeql.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain records for payment-svc — tenders, refunds, intents and till state.
 *
 * <p>Money is {@code BigDecimal} throughout and every amount carries its currency explicitly. These
 * never cross the HTTP boundary: {@link com.storeql.payment.mapper.Mappers} converts them to the
 * DTOs in {@link com.storeql.payment.dto.Dtos} first.
 */
public final class Domain {

  private Domain() {}

  public record PaymentTender(
      UUID id,
      UUID tenantId,
      UUID orderId,
      BigDecimal amount,
      String method,
      String reference,
      String idempotencyKey,
      String status,
      String notes,
      Instant createdAt,
      UUID storeId) {

    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_CARD = "CARD";
    public static final String METHOD_UPI = "UPI";
    public static final String METHOD_WALLET = "WALLET";
    public static final String METHOD_GIFT_CARD = "GIFT_CARD";
    public static final String METHOD_VOUCHER = "VOUCHER";
    // Redeems the customer's store-credit balance (customer-svc) as tender toward the order.
    public static final String METHOD_STORE_CREDIT = "STORE_CREDIT";

    public static final String STATUS_CAPTURED = "CAPTURED";
    public static final String STATUS_FAILED = "FAILED";
  }

  /**
   * A request to a payment service provider to take money for an order.
   *
   * <p>Distinct from {@link PaymentTender} on purpose: a tender is an append-only statement that
   * money <em>was</em> taken (golden rule #8), while an intent is mutable and moves through {@code
   * REQUIRES_ACTION → AUTHORIZED → CAPTURED} as the customer completes SCA and the provider
   * confirms. Capturing writes exactly one tender and records it in {@link #paymentId()}.
   */
  public record PaymentIntent(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String provider,
      String providerRef,
      BigDecimal amount,
      BigDecimal capturedAmount,
      String currency,
      String status,
      String nextActionUrl,
      String failureCode,
      String failureMessage,
      UUID paymentId,
      String idempotencyKey,
      Instant createdAt,
      Instant updatedAt) {

    /**
     * Created with the provider; the customer must complete SCA / 3-D Secure before it can move.
     */
    public static final String STATUS_REQUIRES_ACTION = "REQUIRES_ACTION";

    /** Funds are held on the customer's card but not yet taken. */
    public static final String STATUS_AUTHORIZED = "AUTHORIZED";

    /** Money has been taken; {@link #paymentId()} names the tender that records it. */
    public static final String STATUS_CAPTURED = "CAPTURED";

    /** The provider declined, or the customer failed SCA. Terminal. */
    public static final String STATUS_FAILED = "FAILED";

    /** Abandoned before capture: the provider cancelled it (its cancel event). Terminal. */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * No real provider: the intent authorises and captures in one step, which is exactly what
     * {@code POST /payments/online} did before this existed. Keeps local dev and cash-only tenants
     * working without credentials, and is the reason nothing in this service assumes a PSP is
     * configured.
     */
    public static final String PROVIDER_MANUAL = "MANUAL";

    public static final String PROVIDER_STRIPE = "STRIPE";
    public static final String PROVIDER_RAZORPAY = "RAZORPAY";

    /**
     * @return {@code true} if this intent can no longer change state.
     */
    public boolean isTerminal() {
      return STATUS_CAPTURED.equals(status)
          || STATUS_FAILED.equals(status)
          || STATUS_CANCELLED.equals(status);
    }
  }

  public record RefundTender(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID paymentId,
      BigDecimal amount,
      String method,
      String reference,
      String idempotencyKey,
      String reason,
      Instant createdAt) {}

  public record TillSession(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID openedBy,
      BigDecimal floatAmount,
      String status,
      BigDecimal countedCash,
      BigDecimal overShort,
      Instant openedAt,
      Instant closedAt,
      String moneyBasis) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    /** Everything at the store between open and close: the basis before sessions carried money. */
    public static final String BASIS_WINDOW = "WINDOW";

    /** Only the tenders and refunds that name this session. */
    public static final String BASIS_SESSION = "SESSION";

    /** A session on the window basis, as every one was before sessions carried their money. */
    public TillSession(
        UUID id,
        UUID tenantId,
        UUID storeId,
        UUID openedBy,
        BigDecimal floatAmount,
        String status,
        BigDecimal countedCash,
        BigDecimal overShort,
        Instant openedAt,
        Instant closedAt) {
      this(
          id,
          tenantId,
          storeId,
          openedBy,
          floatAmount,
          status,
          countedCash,
          overShort,
          openedAt,
          closedAt,
          BASIS_WINDOW);
    }
  }

  public record CashDrop(
      UUID id,
      UUID tenantId,
      UUID tillSessionId,
      BigDecimal amount,
      UUID recordedBy,
      String notes,
      Instant createdAt) {}

  /**
   * One line of the tender-mix report: how much of the take came in through one payment method.
   *
   * <p>Captures and refunds stay on separate columns. A card sale refunded to store credit moves
   * money between two methods rather than cancelling out, and a report that only showed the net
   * could not be reconciled against a merchant statement.
   *
   * @param method CASH, CARD, GIFT_CARD, VOUCHER — whatever the tenant actually took
   * @param capturedAmount money taken through this method in the window
   * @param capturedCount how many tenders that was
   * @param refundedAmount money given back through this method
   * @param refundedCount how many refunds that was
   * @param failedCount tenders recorded against this method that did not capture — a climbing
   *     figure against healthy volume is a terminal or acquirer problem, not a sales one
   * @param netAmount captured minus refunded
   * @param shareOfNet this method's percentage of the window's total net take, to one decimal; null
   *     when the total is zero or negative, where a share has no meaning
   */
  public record TenderMixRow(
      String method,
      BigDecimal capturedAmount,
      long capturedCount,
      BigDecimal refundedAmount,
      long refundedCount,
      long failedCount,
      BigDecimal netAmount,
      BigDecimal shareOfNet) {}

  /**
   * One tender's share of a refund (17.7): the tender refunded, how it was paid, how much of the
   * refund it carries and the store it was taken at, so the ledger credits the right control
   * account.
   */
  public record RefundAllocation(
      java.util.UUID paymentId,
      String method,
      java.math.BigDecimal amount,
      java.util.UUID storeId) {}
}
