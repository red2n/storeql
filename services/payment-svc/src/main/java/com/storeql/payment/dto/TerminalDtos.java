package com.storeql.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** EMV terminals and the card payments taken on them (07.16). */
public final class TerminalDtos {

  private TerminalDtos() {}

  @Schema(name = "CardTerminal")
  public record TerminalResponse(
      String id,
      String storeId,
      @Schema(description = "What the cashier sees, e.g. \"Till 2\".") String label,
      @Schema(description = "SIMULATED, STRIPE_TERMINAL, ADYEN or VERIFONE.") String vendor,
      @Schema(description = "The vendor's identifier for the device, as printed on it.")
          String serial,
      @Schema(description = "ACTIVE or RETIRED. A retired terminal is kept: payments point at it.")
          String status,
      String retiredReason,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "RegisterTerminalRequest")
  public record RegisterRequest(
      @NotBlank String storeId,
      @Schema(description = "What the cashier sees. Unique among a store's active terminals.")
          @NotBlank
          @Size(max = 60)
          String label,
      @Schema(description = "SIMULATED, STRIPE_TERMINAL, ADYEN or VERIFONE.")
          @NotBlank
          @Size(max = 30)
          String vendor,
      @Schema(description = "The device's serial, as printed on it.") @Size(max = 80)
          String serial) {}

  @Schema(name = "RetireTerminalRequest")
  public record RetireRequest(
      @Schema(
              description =
                  "Why, so a device withdrawn after a fault is distinguishable from one replaced on"
                      + " an upgrade.")
          @Size(max = 300)
          String reason) {}

  /**
   * A card payment on a terminal, and what the terminal said.
   *
   * @param state REQUESTED, APPROVED, DECLINED, CANCELLED, FAILED or TIMED_OUT. A TIMED_OUT attempt
   *     is the one to act on: the card may have been charged, so no tender is recorded and it is
   *     reconciled against the acquirer's settlement file
   * @param panLast4 the four digits a receipt may print. No other part of the number exists
   *     anywhere in this platform
   * @param receiptLine what a receipt prints for the card, already assembled
   */
  @Schema(name = "TerminalPayment")
  public record AttemptResponse(
      String id,
      String terminalId,
      String orderId,
      String amount,
      String currency,
      @Schema(description = "SALE or REFUND.") String kind,
      String refundOf,
      String state,
      @Schema(description = "The terminal's own words when it declined or failed.")
          String outcomeDetail,
      String scheme,
      String panLast4,
      String authCode,
      @Schema(description = "The EMV application the card ran, e.g. A0000000031010.") String aid,
      @Schema(description = "What the receipt prints for it, e.g. VISA DEBIT.")
          String applicationLabel,
      @Schema(description = "CHIP, CONTACTLESS, SWIPE or MANUAL.") String entryMode,
      @Schema(description = "PIN, SIGNATURE, NONE or DEVICE.") String verification,
      String providerRef,
      @Schema(description = "The tender this became, once approved.") String paymentId,
      String receiptLine,
      String requestedAt,
      String settledAt,
      @Schema(description = "Who asked; null only for a refund the platform owes.")
          String requestedBy,
      @Schema(description = "Why money went back, for a refund.") String reason,
      @Schema(description = "The money owed back to a card this refund is for, if any.")
          String dueId,
      @Schema(
              description =
                  "Where it stands with the money: SETTLED; AT_MACHINE (the card is at the"
                      + " machine); APPROVED_UNRECORDED (it took money that is neither recorded"
                      + " on its order nor put back: record it with POST /payments naming it, or"
                      + " put it back); UNDECIDED (the machine did not answer: a manager says what"
                      + " it shows). Anything but SETTLED holds the terminal: it takes no new"
                      + " sale until then.")
          String standing,
      @Schema(description = "What a person saw on the machine, for one that did not answer.")
          DecisionResponse decision) {}

  /** What a person saw on a card machine that did not answer. */
  @Schema(name = "TerminalPaymentDecision")
  public record DecisionResponse(
      @Schema(description = "APPROVED or NOT_TAKEN.") String outcome,
      String reason,
      String decidedBy,
      String decidedAt) {}

  /**
   * What a person saw on a card machine that did not answer.
   *
   * @param outcome APPROVED (the machine shows it approved: record it on its sale or put it back)
   *     or NOT_TAKEN (no money was taken)
   */
  @Schema(name = "TerminalPaymentDecisionRequest")
  public record DecisionRequest(
      @Schema(description = "APPROVED or NOT_TAKEN: what the card machine shows.") @NotBlank
          String outcome,
      @Schema(description = "Why: what was seen, and where.") @NotBlank @Size(max = 500)
          String reason) {}

  /** Money owed back to a card a terminal took, until the machine has put it back. */
  @Schema(name = "CardRefundDue")
  public record RefundDueResponse(
      String id,
      String storeId,
      String orderId,
      @Schema(description = "The sale on the terminal it goes back to.") String saleAttemptId,
      @Schema(description = "The recorded tender it is drawn against; null if never recorded.")
          String paymentId,
      String amount,
      String currency,
      String reason,
      @Schema(description = "ORDER_EVENT (an order cancelled, voided or returned) or PERSON.")
          String source,
      @Schema(
              description =
                  "OWED, REFUNDED, NEEDS_ATTENTION (the machine could not be reached, refused, or"
                      + " did not answer: a manager acts), NOT_REFUNDED (a person's refund the"
                      + " machine refused) or REFUNDED_ANOTHER_WAY (no machine could put it back,"
                      + " and a manager recorded how it was given back instead).")
          String state,
      @Schema(description = "Why it waits for a person.") String attention,
      @Schema(description = "The last refund asked of the machine for it.") String refundAttemptId,
      @Schema(description = "The books' refund, once put back or given back another way.")
          String refundId,
      String requestedBy,
      String createdAt,
      String updatedAt,
      @Schema(
              description =
                  "How it was given back when no machine could put it back; on one due read by"
                      + " its id, and on the answer that records it.")
          AnotherWayResponse anotherWay) {}

  /** How money owed back to a card was given back when no machine could put it back. */
  @Schema(name = "CardRefundAnotherWay")
  public record AnotherWayResponse(
      @Schema(description = "CASH, CARD (the acquirer's own refund), UPI or WALLET.") String method,
      @Schema(description = "What finds it again: for CARD, the acquirer's refund reference.")
          String reference,
      String reason,
      String closedBy,
      String closedAt) {}

  /**
   * Says how money owed back to a card was given back instead, when no card machine could put it
   * back.
   *
   * <p>There is no field for a card number: a reference is what the acquirer calls its refund.
   */
  @Schema(name = "CardRefundAnotherWayRequest")
  public record AnotherWayRequest(
      @Schema(
              description =
                  "How the money left: CASH, CARD (the acquirer's own refund of the card, outside"
                      + " any card machine here), UPI or WALLET. Never a gift card, store credit"
                      + " or a voucher: those are issued by their own routes.")
          @NotBlank
          @Size(max = 30)
          String method,
      @Schema(
              description =
                  "What finds it again. Required for CARD: the acquirer's refund reference, as"
                      + " its settlement file will carry it.")
          @Size(max = 255)
          String reference,
      @Schema(description = "Why no card machine could put it back. Kept, with who and when.")
          @NotBlank
          @Size(max = 500)
          String reason,
      @Schema(
              description =
                  "The till session (a UUIDv7) the money was handed over from, so that drawer's"
                      + " report counts the refund and a cash one lowers its expected cash. It must"
                      + " be this business's, open, and at the store the card payment was taken at"
                      + " (404 TILL_SESSION_NOT_FOUND, 409 TILL_SESSION_NOT_OPEN or"
                      + " TILL_SESSION_OTHER_STORE, nothing written); a retry under the same key is"
                      + " answered whatever became of the drawer. Absent, the refund is counted at"
                      + " no drawer, as it always was. Ignored for a card payment never recorded"
                      + " on a sale, which has nothing in the books.")
          String tillSessionId) {}

  /**
   * Takes a card for an order.
   *
   * <p>There is no field for a card number, and there never will be: the terminal reads the card.
   */
  @Schema(name = "TerminalSaleRequest")
  public record SaleRequest(
      @NotBlank String terminalId,
      @NotBlank String orderId,
      @NotNull @Positive BigDecimal amount,
      @NotBlank @Size(min = 3, max = 3) String currency) {}

  /**
   * Puts money back on the card that paid. Linked to the attempt, never to a card presented again.
   */
  @Schema(name = "TerminalRefundRequest")
  public record RefundRequest(
      @NotNull @Positive BigDecimal amount,
      @Schema(description = "Why the money goes back. Kept, with who asked and when.")
          @NotBlank
          @Size(max = 500)
          String reason) {}
}
