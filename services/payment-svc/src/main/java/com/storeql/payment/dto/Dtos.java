package com.storeql.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for payment-svc — the wire contract for the tender, refund, intent and
 * cash-management endpoints.
 *
 * <p>Money is {@code BigDecimal} throughout, never a floating-point type.
 */
public final class Dtos {

  private Dtos() {}

  @Schema(
      name = "CreatePaymentIntentRequest",
      description =
          "Opens a payment intent with the configured provider for an ONLINE order. The response"
              + " carries the SCA redirect the customer must complete, when the provider requires"
              + " one.")
  public record CreatePaymentIntentRequest(
      @Schema(description = "UUID of the order to be paid for.") @NotBlank String orderId,
      @Schema(
              description =
                  "Amount to authorise, in the order's currency. Must equal the order total"
                      + " exactly; it is checked against order-svc, not trusted.")
          @NotNull
          @Positive
          @Digits(integer = 14, fraction = 4)
          BigDecimal amount,
      @Schema(
              description =
                  "Where the provider should return the customer after SCA. Must be one of the"
                      + " configured allowed return URLs.")
          String returnUrl,
      @Schema(
              description =
                  "Client-supplied idempotency key; the Idempotency-Key header takes precedence"
                      + " when both are present. A replay returns the original intent rather than"
                      + " placing a second hold on the customer's card.")
          String idempotencyKey) {}

  @Schema(
      name = "PaymentIntentResponse",
      description = "State of a payment intent, including any customer action still outstanding.")
  public record PaymentIntentResponse(
      String id,
      String orderId,
      @Schema(description = "MANUAL, STRIPE or RAZORPAY.") String provider,
      @Schema(description = "REQUIRES_ACTION, AUTHORIZED, CAPTURED, FAILED or CANCELLED.")
          String status,
      BigDecimal amount,
      BigDecimal capturedAmount,
      @Schema(description = "ISO-4217 code, resolved from the order.") String currency,
      @Schema(
              description =
                  "Send the customer here to complete SCA / 3-D Secure. Null when no customer"
                      + " action is outstanding.")
          String nextActionUrl,
      @Schema(description = "Provider failure code, when the intent failed.") String failureCode,
      @Schema(description = "Provider failure reason, when the intent failed.")
          String failureMessage,
      @Schema(description = "UUID of the tender written on capture; null until then.")
          String paymentId,
      String createdAt) {}

  @Schema(
      name = "RecordTenderRequest",
      description =
          "Payment tender to capture for an order, either staff-recorded (POS) or online.")
  public record RecordTenderRequest(
      @Schema(
              description =
                  "UUID of the order this tender is captured against. Online, name the order or"
                      + " the split checkout (groupId), not both.")
          String orderId,
      @Schema(
              description =
                  "Amount tendered, in the order's currency. Staff-recorded, it is taken at the"
                      + " currency's own minor unit (whole yen, a dinar's three places), rounded"
                      + " half up as the order's lines are: a till sums a sale in binary floating"
                      + " point, so 3.3000000000000003 is 3.30. One that comes to nothing there is"
                      + " PAYMENT_AMOUNT_INVALID. Online it must equal the order's total. At most"
                      + " ten whole digits and twenty decimal places (VALIDATION_FAILED).")
          @NotNull
          @Positive
          // Ten whole digits, the columns' NUMERIC(14,4). Twenty places: a till's double for any
          // real amount is written with at most seventeen significant digits from the fourth
          // place (the finest minor unit ISO 4217 has), so twenty takes every one and it is
          // rounded, not refused. The bound is what keeps 1E-80000000 (twelve characters, a
          // scale of eighty million) from reaching the rounding, which would build a power of ten
          // that long.
          @Digits(integer = 10, fraction = 20)
          BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, VOUCHER, or STORE_CREDIT.")
          @NotBlank
          String method,
      String reference,
      @Schema(
              description =
                  "Client-supplied idempotency key; the Idempotency-Key header takes"
                      + " precedence when both are present.")
          String idempotencyKey,
      String notes,
      @Schema(description = "UUID of the store the tender is attributed to.") String storeId,
      // Required only for STORE_CREDIT tenders: the customer whose balance is redeemed, and the
      // currency of that balance (the tenant's own when omitted).
      @Schema(
              description =
                  "Required for STORE_CREDIT tenders: the customer whose balance is" + " redeemed.")
          String customerId,
      @Schema(
              description =
                  "ISO currency code of the store-credit balance; the tenant's own when omitted.")
          String currency,
      @Schema(
              description =
                  "Online only: the split checkout paid once for all its parts (order"
                      + " orchestration), instead of orderId. The amount is the checkout's total;"
                      + " one tender is captured per part.")
          // One constructor only: JSON-B binds a request body to a record through its single
          // constructor, and a second one makes every body unreadable.
          String groupId,
      @Schema(
              description =
                  "CARD at a till: the card machine's approved payment this tender records (the"
                      + " `id` POST /payments/terminal answered). It is recorded once, on its own"
                      + " order, at exactly the amount the machine took, and settles the machine"
                      + " for the next sale. Without it, the oldest approval on the order at this"
                      + " amount that no tender records yet is the one recorded.")
          String terminalPaymentId,
      @Schema(
              description =
                  "At a till: the till session (the drawer) this money is taken in. It must be an"
                      + " open session of this business at the tender's store (404"
                      + " TILL_SESSION_NOT_FOUND, 409 TILL_SESSION_NOT_OPEN / TILL_SESSION_OTHER_STORE);"
                      + " open is judged on the transaction that writes the tender, after a retry"
                      + " under the same Idempotency-Key has been answered, so a retry is answered"
                      + " whatever became of the drawer. A drawer opened on the SESSION basis counts"
                      + " only the tenders that name it, whatever the method (a store credit or a"
                      + " card too); without one the tender is shown apart as 'not at a till'.")
          String tillSessionId) {}

  @Schema(
      name = "GroupPaymentResponse",
      description =
          "One payment for a split checkout: a captured tender per part, each confirming its own"
              + " order; together they are the checkout's total.")
  public record GroupPaymentResponse(
      UUID groupId, BigDecimal total, List<TenderResponse> tenders) {}

  @Schema(
      name = "RecordRefundRequest",
      description = "Refund against a previously captured tender.")
  public record RecordRefundRequest(
      @Schema(description = "UUID of the payment tender being refunded.") @NotBlank
          String paymentId,
      @Schema(
              description =
                  "Amount to refund, in the business's currency and no finer than its minor unit;"
                      + " capped at the tender's remaining captured total.")
          @NotNull
          @Positive
          @Digits(integer = 10, fraction = 4)
          BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, or VOUCHER.") @NotBlank
          String method,
      String reference,
      String idempotencyKey,
      @Schema(description = "Reason for the refund.") String reason,
      @Schema(
              description =
                  "At a till: the till session a cash refund is paid out of, so that drawer's report"
                      + " counts it. An open session at the refunded tender's store (404"
                      + " TILL_SESSION_NOT_FOUND, 409 TILL_SESSION_NOT_OPEN / TILL_SESSION_OTHER_STORE),"
                      + " judged on the transaction that writes the refund after a retry is answered."
                      + " The refund is then that drawer's store's, also for a tender taken at no"
                      + " store.")
          String tillSessionId) {}

  @Schema(name = "TenderResponse", description = "A captured (append-only) payment tender.")
  public record TenderResponse(
      UUID id,
      UUID orderId,
      @Schema(description = "Amount tendered, in the order's currency.") BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, VOUCHER, or STORE_CREDIT.")
          String method,
      String reference,
      @Schema(description = "Tender status, e.g. CAPTURED.") String status,
      String notes,
      Instant createdAt) {}

  @Schema(name = "RefundResponse", description = "A recorded (append-only) refund.")
  public record RefundResponse(
      UUID id,
      UUID orderId,
      @Schema(description = "UUID of the tender this refund is drawn against.") UUID paymentId,
      @Schema(description = "Amount refunded, in the order's currency.") BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, or VOUCHER.") String method,
      String reference,
      String reason,
      Instant createdAt) {}

  // ── Cash management ────────────────────────────────────────────────────────

  @Schema(name = "OpenTillRequest", description = "Open a till session with an opening cash float.")
  public record OpenTillRequest(
      @Schema(description = "UUID of the store the till session is opened at.") @NotBlank
          String storeId,
      @Schema(
              description =
                  "Opening cash float, no finer than the business's currency's minor unit"
                      + " (CASH_AMOUNT_INVALID otherwise).")
          @NotNull
          @PositiveOrZero
          @Digits(integer = 10, fraction = 4)
          BigDecimal floatAmount,
      @Schema(
              description =
                  "SESSION: this drawer's report counts only the tenders and refunds that name it"
                      + " (the till sends its session id); money naming none is shown apart as 'not"
                      + " at a till'. WINDOW (the default): everything at the store while it was"
                      + " open, for a client that sends no session. Fixed at open.")
          // One constructor only: JSON-B binds a request body to a record through its single
          // constructor, and a second one makes every body unreadable.
          String basis) {}

  @Schema(name = "TillSessionResponse", description = "A cashier till session.")
  public record TillSessionResponse(
      UUID id,
      UUID storeId,
      @Schema(description = "UUID of the user who opened the session.") UUID openedBy,
      BigDecimal floatAmount,
      @Schema(description = "OPEN or CLOSED.") String status,
      @Schema(description = "Physically counted cash at close time; null while open.")
          BigDecimal countedCash,
      @Schema(description = "countedCash minus expected cash; null while open.")
          BigDecimal overShort,
      Instant openedAt,
      @Schema(description = "Null while the session is still open.") Instant closedAt,
      @Schema(description = "SESSION or WINDOW: whose money the drawer's report counts.")
          String basis) {}

  @Schema(name = "RecordCashDropRequest", description = "Mid-shift safe drop from the till.")
  public record RecordCashDropRequest(
      @Schema(
              description =
                  "Amount removed from the till, no finer than the business's currency's minor unit"
                      + " (INVALID_DROP_AMOUNT otherwise).")
          @NotNull
          @Positive
          @Digits(integer = 10, fraction = 4)
          BigDecimal amount,
      String notes) {}

  @Schema(name = "CashDropResponse", description = "A recorded (append-only) cash drop.")
  public record CashDropResponse(
      UUID id, UUID tillSessionId, BigDecimal amount, Instant createdAt) {}

  @Schema(name = "CloseTillRequest", description = "Close a till session (Z-report).")
  public record CloseTillRequest(
      @Schema(
              description =
                  "Physically counted cash in the till at close time, no finer than the business's"
                      + " currency's minor unit (CASH_AMOUNT_INVALID otherwise).")
          @NotNull
          @PositiveOrZero
          @Digits(integer = 10, fraction = 4)
          BigDecimal countedCash,
      @Schema(
              description =
                  "Why the count differs from expected (optional, up to 500 characters). Kept with"
                      + " the closed session and announced in TillSessionClosed.")
          @jakarta.validation.constraints.Size(max = 500)
          String note) {}

  /** Breakdown of sales and refunds per tender method (used in X/Z reports). */
  @Schema(
      name = "TenderSummary",
      description = "Breakdown of sales and refunds for one tender method.")
  public record TenderSummary(BigDecimal sales, BigDecimal refunds, BigDecimal net) {}

  /** X-report (read-only snapshot) and Z-report (close) share this structure. */
  @Schema(
      name = "TillReportResponse",
      description =
          "Till totals report; shared shape for the read-only X-report and the closing Z-report.")
  public record TillReportResponse(
      UUID tillSessionId,
      UUID storeId,
      UUID openedBy,
      Instant openedAt,
      @Schema(description = "Null for an X-report (till still open).") Instant closedAt,
      BigDecimal floatAmount,
      @Schema(description = "Sales/refunds/net breakdown keyed by tender method.")
          Map<String, TenderSummary> tenderSummary,
      BigDecimal cashDropsTotal,
      @Schema(
              description =
                  "Float plus cash sales minus cash refunds, plus pay-ins, minus pay-outs and"
                      + " cash drops.")
          BigDecimal expectedCashInTill,
      @Schema(description = "Physically counted cash; null for an X-report.")
          BigDecimal countedCash,
      @Schema(description = "countedCash minus expectedCashInTill; null for an X-report.")
          BigDecimal overShort,
      BigDecimal grossSales,
      BigDecimal totalRefunds,
      BigDecimal netSales,
      @Schema(
              description =
                  "Cash tenders in this drawer: on the SESSION basis exactly those that name the"
                      + " session; on WINDOW every one taken at the session's store while it was"
                      + " open, whichever drawer it names.")
          BigDecimal cashSales,
      @Schema(
              description =
                  "Cash refunds paid out of this drawer: on the SESSION basis exactly those that"
                      + " name the session (a return, a void, a cancellation, an exchange's cash"
                      + " back or a manager's refund naming it); on WINDOW every one made at the"
                      + " session's store while it was open.")
          BigDecimal cashRefunds,
      @Schema(description = "Cash put in during the session (pay-ins).") BigDecimal payIns,
      @Schema(description = "Cash taken out during the session (pay-outs).") BigDecimal payOuts,
      @Schema(
              description =
                  "How the money was attributed: SESSION (only what names this drawer) or WINDOW"
                      + " (everything at the session's store while it was open).")
          String basis,
      @Schema(description = "The closer's note on a difference; null on an X-report.") String note,
      @Schema(
              description =
                  "SESSION basis only: money taken or refunded at the store in the window that"
                      + " names no till session (online, back-office, an older client, a refund an"
                      + " event gave back naming a drawer that was not the business's open one at"
                      + " the store), by tender method. Counted in no drawer; null on the WINDOW"
                      + " basis.")
          Map<String, TenderSummary> notAtTill) {}

  // ── Pay-in / Pay-out (petty cash) ─────────────────────────────────────────

  @Schema(name = "CashMovementRequest", description = "Petty cash pay-in or pay-out.")
  public record CashMovementRequest(
      @Schema(description = "UUID of the open till session this movement applies to.") @NotBlank
          String tillSessionId,
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "PAY_IN or PAY_OUT.") @NotBlank String direction,
      @Schema(
              description =
                  "Amount moved, no finer than the business's currency's minor unit"
                      + " (CASH_AMOUNT_INVALID otherwise).")
          @NotNull
          @Positive
          @Digits(integer = 10, fraction = 4)
          BigDecimal amount,
      @Schema(description = "Reason for the movement.") @NotBlank String reason,
      @Schema(description = "UUID of the user who authorised the movement, if applicable.")
          String authorisedBy) {}

  @Schema(name = "CashMovementResponse", description = "A recorded (append-only) cash movement.")
  public record CashMovementResponse(
      UUID id,
      UUID storeId,
      UUID tillSessionId,
      @Schema(description = "PAY_IN or PAY_OUT.") String direction,
      BigDecimal amount,
      String reason,
      Instant createdAt) {}

  // ── Daily Z-report ─────────────────────────────────────────────────────────

  @Schema(name = "GenerateZReportRequest", description = "Generate (or retrieve) a daily Z-report.")
  public record GenerateZReportRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(
              description =
                  "Business date the report covers, ISO-8601 yyyy-MM-dd; today in the store's own"
                      + " time zone when omitted.")
          String businessDate,
      @Schema(
              description =
                  "Physically counted cash for the day, no finer than the report's currency's"
                      + " minor unit (CASH_AMOUNT_INVALID otherwise).")
          @NotNull
          @PositiveOrZero
          @Digits(integer = 10, fraction = 4)
          BigDecimal countedCash,
      @Schema(description = "ISO currency code; the tenant's own when omitted.") String currency,
      @Schema(
              description =
                  "To correct a settled day: the id of the stored report being replaced (it must be"
                      + " the latest version). The correction is a new version; nothing is"
                      + " overwritten.")
          String correctionOf,
      @Schema(description = "Why the day is corrected; required with correctionOf.")
          String reason) {}

  @Schema(
      name = "ZReportResponse",
      description = "Daily end-of-day settlement report (append-only).")
  public record ZReportResponse(
      UUID id,
      UUID storeId,
      LocalDate businessDate,
      BigDecimal totalSales,
      BigDecimal totalRefunds,
      BigDecimal totalDiscounts,
      BigDecimal totalTax,
      BigDecimal netSales,
      BigDecimal cashSales,
      BigDecimal cardSales,
      BigDecimal giftCardSales,
      @Schema(description = "Opening cash float for the till session(s) covered.")
          BigDecimal openingFloat,
      BigDecimal cashDrops,
      BigDecimal payIns,
      BigDecimal payOuts,
      @Schema(
              description =
                  "Float plus cash sales minus cash refunds, plus pay-ins, minus pay-outs and"
                      + " cash drops.")
          BigDecimal expectedCash,
      @Schema(description = "Physically counted cash for the day.") BigDecimal countedCash,
      @Schema(description = "countedCash minus expectedCash.") BigDecimal overShort,
      int transactionCount,
      @Schema(description = "ISO currency code.") String currency,
      Instant generatedAt,
      @Schema(description = "Cash refunded at the store during the day (a term of expectedCash).")
          BigDecimal cashRefunds,
      @Schema(description = "1 for the first report of a day; a correction is the next number.")
          int version,
      @Schema(description = "The report this version corrects; null for version 1.")
          UUID replacesId,
      @Schema(description = "Why the day was corrected; null for version 1.")
          String correctionReason,
      @Schema(description = "IANA zone the day was counted in (the store's own).") String timeZone,
      @Schema(
              description =
                  "True when the store's zone could not be read and UTC was used instead; the day"
                      + " then runs midnight to midnight UTC.")
          boolean zoneAssumed,
      @Schema(
              description =
                  "False when this is a stored report answered again; true when this request wrote"
                      + " it.")
          boolean regenerated) {}

  @Schema(
      name = "TenderMixRowResponse",
      description = "One payment method's share of the take over a window.")
  public record TenderMixRowResponse(
      @Schema(description = "CASH, CARD, GIFT_CARD, VOUCHER — whatever was actually taken.")
          String method,
      @Schema(description = "Money taken through this method in the window.")
          BigDecimal capturedAmount,
      @Schema(description = "How many tenders that was.") long capturedCount,
      @Schema(description = "Money given back through this method.") BigDecimal refundedAmount,
      @Schema(description = "How many refunds that was.") long refundedCount,
      @Schema(
              description =
                  "Tenders recorded against this method that did not capture. A climbing figure"
                      + " against healthy volume is a terminal or acquirer problem, not a sales"
                      + " one.")
          long failedCount,
      @Schema(description = "capturedAmount minus refundedAmount.") BigDecimal netAmount,
      @Schema(
              description =
                  "This method's percentage of the window's total net take. Null when the total is"
                      + " zero or negative, where a share has no meaning.")
          BigDecimal shareOfNet) {}
}
