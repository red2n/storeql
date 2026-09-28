package com.storeql.purchase.domain;

import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The journals a sale writes (17.7): the pure part, so every rule can be tested without a database.
 *
 * <p>A sale is posted through a receipts clearing account. Each tender debits the account the money
 * is held in — cash in the tills, card and wallet clearing until the acquirer settles, or the gift
 * card or store credit liability it reduces — and credits clearing. The confirmed sale debits
 * clearing with the total and credits sales net of VAT and VAT output. A sale paid in full nets
 * clearing to zero for its order; what does not is the reconciliation exception the clearing report
 * lists. A refund credits the control account each refunded tender came from and debits sales and
 * VAT in the sale's own VAT ratio — or clearing, when the ledger never saw the sale confirmed.
 *
 * <p>A chargeback (11.9) is the acquirer taking a card payment back while the argument about it
 * runs. The amount leaves card clearing — the acquirer nets it off what it settles — for card
 * receipts in dispute, where it waits; the acquirer's fee is an expense the day it is charged. A
 * dispute won brings the amount back into clearing; one lost, or accepted, writes it off to
 * chargeback losses. The sale itself stands: the goods left, and a bank's decision is not a refund.
 */
public final class SalesPosting {

  private SalesPosting() {}

  /** The account a tender's money sits in. */
  public record Control(String code, String name) {}

  public static final Control CASH_IN_TILLS =
      new Control(Domain.CODE_CASH_IN_TILLS, Domain.NAME_CASH_IN_TILLS);
  public static final Control CARD_CLEARING =
      new Control(Domain.CODE_CARD_CLEARING, Domain.NAME_CARD_CLEARING);
  public static final Control GIFT_CARD_LIABILITY =
      new Control(Domain.CODE_GIFT_CARD_LIABILITY, Domain.NAME_GIFT_CARD_LIABILITY);
  public static final Control STORE_CREDIT_LIABILITY =
      new Control(Domain.CODE_STORE_CREDIT_LIABILITY, Domain.NAME_STORE_CREDIT_LIABILITY);
  public static final Control UNALLOCATED_RECEIPTS =
      new Control(Domain.CODE_UNALLOCATED_RECEIPTS, Domain.NAME_UNALLOCATED_RECEIPTS);

  /** One tender's part of a refund. */
  public record Allocation(String method, BigDecimal amount) {}

  /**
   * Where a tender's money is held. A method nobody mapped goes to unallocated receipts rather than
   * to a guessed account, so it shows on the trial balance as something to look at.
   */
  public static Control controlFor(String method) {
    String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    return switch (m) {
      case "CASH" -> CASH_IN_TILLS;
      case "CARD", "UPI", "WALLET" -> CARD_CLEARING;
      case "GIFT_CARD", "VOUCHER" -> GIFT_CARD_LIABILITY;
      case "STORE_CREDIT" -> STORE_CREDIT_LIABILITY;
      default -> UNALLOCATED_RECEIPTS;
    };
  }

  /**
   * The confirmed sale: Dr clearing with the total, Cr sales with the total less VAT, Cr VAT
   * output. A sale worth nothing posts nothing; VAT is held between zero and the total.
   */
  public static List<NominalLedgerEntry> sale(
      UUID tenantId, UUID orderId, UUID storeId, BigDecimal total, BigDecimal tax, LocalDate date) {
    if (total == null || total.signum() <= 0) return List.of();
    BigDecimal vat = tax == null ? BigDecimal.ZERO : tax.max(BigDecimal.ZERO).min(total);
    return LedgerPosting.of(
            tenantId, date, "Sale " + Handle.of(orderId), Domain.SOURCE_SALE, orderId, storeId)
        .debit(Domain.CODE_SALES_CLEARING, Domain.NAME_SALES_CLEARING, total)
        .credit(Domain.CODE_SALES, Domain.NAME_SALES, total.subtract(vat))
        .credit(Domain.CODE_VAT_OUTPUT, Domain.NAME_VAT_OUTPUT, vat)
        .build();
  }

  /** One tender: Dr its control account, Cr clearing. A tender of nothing posts nothing. */
  public static List<NominalLedgerEntry> tender(
      UUID tenantId, UUID orderId, UUID storeId, String method, BigDecimal amount, LocalDate date) {
    if (amount == null || amount.signum() <= 0) return List.of();
    Control control = controlFor(method);
    String how = method == null || method.isBlank() ? "an unrecorded method" : method;
    return LedgerPosting.of(
            tenantId,
            date,
            "Tender (" + how + ") for sale " + Handle.of(orderId),
            Domain.SOURCE_SALE_TENDER,
            orderId,
            storeId)
        .debit(control.code(), control.name(), amount)
        .credit(Domain.CODE_SALES_CLEARING, Domain.NAME_SALES_CLEARING, amount)
        .build();
  }

  /**
   * A refund: Cr each tender's control account with its share; Dr sales and VAT output in the
   * sale's own ratio of VAT to total, the VAT rounded to the minor unit and sales taking the
   * remainder — or Dr clearing when the sale was never confirmed. Shares of nothing are ignored.
   *
   * @param saleTotal the confirmed sale's total, when there is one
   * @param saleTax the VAT inside it
   * @param confirmed whether the ledger has the sale
   */
  public static List<NominalLedgerEntry> refund(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<Allocation> allocations,
      BigDecimal saleTotal,
      BigDecimal saleTax,
      boolean confirmed,
      LocalDate date) {
    Map<Control, BigDecimal> byControl = new LinkedHashMap<>();
    BigDecimal refunded = BigDecimal.ZERO;
    for (Allocation a : allocations) {
      if (a.amount() == null || a.amount().signum() <= 0) continue;
      byControl.merge(controlFor(a.method()), a.amount(), BigDecimal::add);
      refunded = refunded.add(a.amount());
    }
    if (refunded.signum() <= 0) return List.of();
    LedgerPosting p =
        LedgerPosting.of(
            tenantId,
            date,
            "Refund for sale " + Handle.of(orderId),
            Domain.SOURCE_SALE_REFUND,
            orderId,
            storeId);
    if (confirmed && saleTotal != null && saleTotal.signum() > 0) {
      BigDecimal tax = saleTax == null ? BigDecimal.ZERO : saleTax.max(BigDecimal.ZERO);
      BigDecimal vat =
          refunded.multiply(tax).divide(saleTotal, 2, RoundingMode.HALF_UP).min(refunded);
      p.debit(Domain.CODE_SALES, Domain.NAME_SALES, refunded.subtract(vat))
          .debit(Domain.CODE_VAT_OUTPUT, Domain.NAME_VAT_OUTPUT, vat);
    } else {
      p.debit(Domain.CODE_SALES_CLEARING, Domain.NAME_SALES_CLEARING, refunded);
    }
    byControl.forEach((control, amount) -> p.credit(control.code(), control.name(), amount));
    return p.build();
  }

  /**
   * The acquirer has taken a disputed card payment, and charged for it: Dr card receipts in dispute
   * with the amount, Dr chargeback fees with the fee, Cr card clearing with both.
   */
  public static List<NominalLedgerEntry> chargebackWithdrawn(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      BigDecimal amount,
      BigDecimal fee,
      LocalDate date) {
    BigDecimal taken = amount == null ? BigDecimal.ZERO : amount.max(BigDecimal.ZERO);
    BigDecimal charged = fee == null ? BigDecimal.ZERO : fee.max(BigDecimal.ZERO);
    if (taken.signum() == 0 && charged.signum() == 0) return List.of();
    LedgerPosting p =
        LedgerPosting.of(
            tenantId,
            date,
            "Chargeback on sale " + Handle.of(orderId),
            Domain.SOURCE_CHARGEBACK,
            orderId,
            storeId);
    if (taken.signum() > 0) {
      p.debit(Domain.CODE_CARD_RECEIPTS_IN_DISPUTE, Domain.NAME_CARD_RECEIPTS_IN_DISPUTE, taken);
    }
    if (charged.signum() > 0) {
      p.debit(Domain.CODE_CHARGEBACK_FEES, Domain.NAME_CHARGEBACK_FEES, charged);
    }
    return p.credit(Domain.CODE_CARD_CLEARING, Domain.NAME_CARD_CLEARING, taken.add(charged))
        .build();
  }

  /**
   * A dispute is over. Won: Dr card clearing, Cr card receipts in dispute — the money comes back.
   * Lost or accepted: Dr chargeback losses, Cr card receipts in dispute. Nothing is posted for a
   * dispute whose money the acquirer never took: there is nothing in dispute to move.
   *
   * @param won whether the business won
   * @param fundsWithdrawn whether the acquirer had taken the amount
   */
  public static List<NominalLedgerEntry> chargebackClosed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      BigDecimal amount,
      boolean won,
      boolean fundsWithdrawn,
      LocalDate date) {
    if (!fundsWithdrawn || amount == null || amount.signum() <= 0) return List.of();
    LedgerPosting p =
        LedgerPosting.of(
            tenantId,
            date,
            "Chargeback " + (won ? "won" : "lost") + " on sale " + Handle.of(orderId),
            Domain.SOURCE_CHARGEBACK,
            orderId,
            storeId);
    if (won) {
      p.debit(Domain.CODE_CARD_CLEARING, Domain.NAME_CARD_CLEARING, amount);
    } else {
      p.debit(Domain.CODE_CHARGEBACK_LOSSES, Domain.NAME_CHARGEBACK_LOSSES, amount);
    }
    return p.credit(
            Domain.CODE_CARD_RECEIPTS_IN_DISPUTE, Domain.NAME_CARD_RECEIPTS_IN_DISPUTE, amount)
        .build();
  }

  /**
   * What a reconciled payout moves in one store's books, each signed so the usual case is positive.
   *
   * @param storeId null for what belongs to no store
   */
  public record StoreSettlement(
      UUID storeId,
      BigDecimal bank,
      BigDecimal fees,
      BigDecimal clearing,
      BigDecimal unallocated) {}

  /**
   * A reconciled payout in one store's books (11.10): Dr bank for what the acquirer paid, Dr card
   * processing fees for what it kept / Cr card clearing for the payments it covers, Cr unallocated
   * receipts for money that answered to nothing here. Each figure is signed so that the usual case
   * is positive, and a negative one changes sides — a payout of more refunds than sales takes money
   * out of the bank. {@code bank = clearing + unallocated - fees}, or nothing is posted.
   *
   * @param batchId the settlement batch, the journal's source
   */
  public static List<NominalLedgerEntry> cardSettlement(
      UUID tenantId,
      UUID batchId,
      UUID storeId,
      String payout,
      BigDecimal bank,
      BigDecimal fees,
      BigDecimal clearing,
      BigDecimal unallocated,
      LocalDate date) {
    BigDecimal b = orZero(bank);
    BigDecimal f = orZero(fees);
    BigDecimal cl = orZero(clearing);
    BigDecimal u = orZero(unallocated);
    if (b.compareTo(cl.add(u).subtract(f)) != 0) {
      throw new IllegalArgumentException("a settlement's figures do not balance");
    }
    if (b.signum() == 0 && f.signum() == 0 && cl.signum() == 0 && u.signum() == 0) {
      return List.of();
    }
    LedgerPosting p =
        LedgerPosting.of(
            tenantId,
            date,
            "Card settlement " + payout,
            Domain.SOURCE_CARD_SETTLEMENT,
            batchId,
            storeId);
    side(p, Domain.CODE_BANK, Domain.NAME_BANK, b, true);
    side(p, Domain.CODE_CARD_PROCESSING_FEES, Domain.NAME_CARD_PROCESSING_FEES, f, true);
    side(p, Domain.CODE_CARD_CLEARING, Domain.NAME_CARD_CLEARING, cl, false);
    side(p, Domain.CODE_UNALLOCATED_RECEIPTS, Domain.NAME_UNALLOCATED_RECEIPTS, u, false);
    return p.build();
  }

  /** A signed figure on its usual side, or on the other when it is negative. */
  private static void side(
      LedgerPosting p, String code, String name, BigDecimal amount, boolean usuallyDebit) {
    if (amount.signum() == 0) return;
    if ((amount.signum() > 0) == usuallyDebit) {
      p.debit(code, name, amount.abs());
    } else {
      p.credit(code, name, amount.abs());
    }
  }

  private static BigDecimal orZero(BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO : amount;
  }
}
