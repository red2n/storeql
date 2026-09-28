package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SalesPostingTest {

  private static final UUID TENANT = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478e1");
  private static final UUID ORDER = Ids.parse("01a090ae-611e-7a00-8000-000000000001");
  private static final UUID STORE = Ids.parse("01a090ae-611e-703c-a378-a4972ea461c8");
  private static final LocalDate DAY = LocalDate.of(2026, 9, 13);

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** Debit less credit on one code across postings. */
  private static BigDecimal balance(List<NominalLedgerEntry> lines, String code) {
    BigDecimal b = BigDecimal.ZERO;
    for (var l : lines) {
      if (l.nominalCode().equals(code)) b = b.add(l.debit()).subtract(l.credit());
    }
    return b;
  }

  private static void same(BigDecimal actual, String expected) {
    assertThat(actual + " vs " + expected, actual.compareTo(d(expected)), is(0));
  }

  @Test
  @DisplayName("Each tender method is held in its own account, and an unmapped one is not guessed")
  void tendersMapToTheirControlAccounts() {
    assertThat(SalesPosting.controlFor("CASH"), is(SalesPosting.CASH_IN_TILLS));
    assertThat(SalesPosting.controlFor(" card "), is(SalesPosting.CARD_CLEARING));
    assertThat(SalesPosting.controlFor("upi"), is(SalesPosting.CARD_CLEARING));
    assertThat(SalesPosting.controlFor("WALLET"), is(SalesPosting.CARD_CLEARING));
    assertThat(SalesPosting.controlFor("GIFT_CARD"), is(SalesPosting.GIFT_CARD_LIABILITY));
    assertThat(SalesPosting.controlFor("VOUCHER"), is(SalesPosting.GIFT_CARD_LIABILITY));
    assertThat(SalesPosting.controlFor("STORE_CREDIT"), is(SalesPosting.STORE_CREDIT_LIABILITY));
    assertThat(SalesPosting.controlFor(null), is(SalesPosting.UNALLOCATED_RECEIPTS));
    assertThat(SalesPosting.controlFor("BARTER"), is(SalesPosting.UNALLOCATED_RECEIPTS));
  }

  @Test
  @DisplayName("A sale paid in cash and card nets clearing to zero and posts revenue net of VAT")
  void aSplitTenderSaleNetsClearing() {
    List<NominalLedgerEntry> lines = new ArrayList<>();
    lines.addAll(SalesPosting.tender(TENANT, ORDER, STORE, "CASH", d("70.00"), DAY));
    lines.addAll(SalesPosting.tender(TENANT, ORDER, STORE, "CARD", d("50.00"), DAY));
    var sale = SalesPosting.sale(TENANT, ORDER, STORE, d("120.00"), d("20.00"), DAY);
    assertThat(sale.size(), is(3));
    lines.addAll(sale);

    same(balance(lines, Domain.CODE_SALES_CLEARING), "0");
    same(balance(lines, Domain.CODE_CASH_IN_TILLS), "70.00");
    same(balance(lines, Domain.CODE_CARD_CLEARING), "50.00");
    same(balance(lines, Domain.CODE_SALES), "-100.00");
    same(balance(lines, Domain.CODE_VAT_OUTPUT), "-20.00");
    for (var l : sale) {
      assertThat(l.sourceType(), is(Domain.SOURCE_SALE));
      assertThat(l.sourceRef(), is(ORDER));
      assertThat(l.storeId(), is(STORE));
      assertThat(l.entryDate(), is(DAY));
    }
  }

  @Test
  @DisplayName("Every sales journal names the sale by '#' and its handle, never its whole id")
  void everySalesJournalNamesTheSaleByItsHandle() {
    // Read in the accounting package and on the Integrations screen, beside the order screens'
    // "Order #00000001": a whole id can be neither read nor matched by eye. The id stays in
    // sourceRef, which is what anything matches on.
    var sale = SalesPosting.sale(TENANT, ORDER, STORE, d("12.00"), d("2.00"), DAY);
    var tender = SalesPosting.tender(TENANT, ORDER, STORE, "CARD", d("12.00"), DAY);
    var unrecorded = SalesPosting.tender(TENANT, ORDER, STORE, " ", d("1.00"), DAY);
    var refund =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(new SalesPosting.Allocation("CARD", d("6.00"))),
            d("12.00"),
            d("2.00"),
            true,
            DAY);
    var taken = SalesPosting.chargebackWithdrawn(TENANT, ORDER, STORE, d("12.00"), d("15"), DAY);
    var won = SalesPosting.chargebackClosed(TENANT, ORDER, STORE, d("12.00"), true, true, DAY);
    var lost = SalesPosting.chargebackClosed(TENANT, ORDER, STORE, d("12.00"), false, true, DAY);

    assertThat(sale.get(0).description(), is("Sale #00000001"));
    assertThat(tender.get(0).description(), is("Tender (CARD) for sale #00000001"));
    assertThat(
        unrecorded.get(0).description(), is("Tender (an unrecorded method) for sale #00000001"));
    assertThat(refund.get(0).description(), is("Refund for sale #00000001"));
    assertThat(taken.get(0).description(), is("Chargeback on sale #00000001"));
    assertThat(won.get(0).description(), is("Chargeback won on sale #00000001"));
    assertThat(lost.get(0).description(), is("Chargeback lost on sale #00000001"));
    for (var posting : List.of(sale, tender, unrecorded, refund, taken, won, lost)) {
      for (var l : posting) {
        assertThat(l.description(), not(containsString(ORDER.toString())));
        assertThat(l.sourceRef(), is(ORDER));
      }
    }
  }

  @Test
  @DisplayName("Nothing to post posts nothing; VAT is held between zero and the total")
  void edgesPostNothingOrClamp() {
    assertThat(SalesPosting.sale(TENANT, ORDER, STORE, d("0"), d("0"), DAY).isEmpty(), is(true));
    assertThat(SalesPosting.sale(TENANT, ORDER, STORE, null, null, DAY).isEmpty(), is(true));
    assertThat(SalesPosting.tender(TENANT, ORDER, STORE, "CASH", d("0"), DAY).isEmpty(), is(true));
    assertThat(SalesPosting.tender(TENANT, ORDER, STORE, "CASH", d("-5"), DAY).isEmpty(), is(true));
    var noVat = SalesPosting.sale(TENANT, ORDER, STORE, d("10.00"), null, DAY);
    assertThat(noVat.size(), is(2));
    same(balance(noVat, Domain.CODE_SALES), "-10.00");
    var negativeVat = SalesPosting.sale(TENANT, ORDER, STORE, d("10.00"), d("-3"), DAY);
    same(balance(negativeVat, Domain.CODE_SALES), "-10.00");
    var tooMuchVat = SalesPosting.sale(TENANT, ORDER, STORE, d("10.00"), d("15"), DAY);
    same(balance(tooMuchVat, Domain.CODE_VAT_OUTPUT), "-10.00");
    assertThat(
        SalesPosting.refund(TENANT, ORDER, STORE, List.of(), d("10"), d("0"), true, DAY).isEmpty(),
        is(true));
  }

  @Test
  @DisplayName("A refund takes revenue and VAT back in the sale's ratio and credits the tender")
  void aRefundReversesInTheSalesRatio() {
    var lines =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(new SalesPosting.Allocation("CARD", d("30.00"))),
            d("120.00"),
            d("20.00"),
            true,
            DAY);
    same(balance(lines, Domain.CODE_SALES), "25.00");
    same(balance(lines, Domain.CODE_VAT_OUTPUT), "5.00");
    same(balance(lines, Domain.CODE_CARD_CLEARING), "-30.00");

    // A third of a 30.00 sale with 5.00 VAT: VAT 1.67 rounded, sales takes the rest.
    var rounded =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(new SalesPosting.Allocation("CASH", d("10.00"))),
            d("30.00"),
            d("5.00"),
            true,
            DAY);
    same(balance(rounded, Domain.CODE_VAT_OUTPUT), "1.67");
    same(balance(rounded, Domain.CODE_SALES), "8.33");
  }

  @Test
  @DisplayName("A split refund credits each account once; one against an unseen sale uses clearing")
  void splitAndUnconfirmedRefunds() {
    var split =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(
                new SalesPosting.Allocation("CASH", d("5.00")),
                new SalesPosting.Allocation("CARD", d("4.00")),
                new SalesPosting.Allocation("CASH", d("1.00")),
                new SalesPosting.Allocation("STORE_CREDIT", d("0"))),
            d("100.00"),
            d("0"),
            true,
            DAY);
    same(balance(split, Domain.CODE_CASH_IN_TILLS), "-6.00");
    same(balance(split, Domain.CODE_CARD_CLEARING), "-4.00");
    assertThat(split.stream().filter(l -> l.credit().signum() > 0).count(), is(2L));

    var unseen =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(new SalesPosting.Allocation("GIFT_CARD", d("12.00"))),
            null,
            null,
            false,
            DAY);
    same(balance(unseen, Domain.CODE_SALES_CLEARING), "12.00");
    same(balance(unseen, Domain.CODE_GIFT_CARD_LIABILITY), "-12.00");
    same(balance(unseen, Domain.CODE_SALES), "0");
  }

  // ── chargebacks (11.9) ──────────────────────────────────────────────────────

  @Test
  void aChargebackMovesTheMoneyOutOfCardClearingAndBooksTheFee() {
    var taken = SalesPosting.chargebackWithdrawn(TENANT, ORDER, STORE, d("45.99"), d("15.00"), DAY);
    same(balance(taken, Domain.CODE_CARD_RECEIPTS_IN_DISPUTE), "45.99");
    same(balance(taken, Domain.CODE_CHARGEBACK_FEES), "15.00");
    same(balance(taken, Domain.CODE_CARD_CLEARING), "-60.99");
    same(balance(taken, Domain.CODE_SALES), "0");
    assertThat(
        "the sale stands: a bank's decision is not a refund",
        taken.stream().noneMatch(l -> Domain.CODE_VAT_OUTPUT.equals(l.nominalCode())),
        is(true));
  }

  @Test
  void aChargebackWonComesBackAndOneLostIsWrittenOff() {
    var won = SalesPosting.chargebackClosed(TENANT, ORDER, STORE, d("45.99"), true, true, DAY);
    same(balance(won, Domain.CODE_CARD_CLEARING), "45.99");
    same(balance(won, Domain.CODE_CARD_RECEIPTS_IN_DISPUTE), "-45.99");

    var lost = SalesPosting.chargebackClosed(TENANT, ORDER, STORE, d("45.99"), false, true, DAY);
    same(balance(lost, Domain.CODE_CHARGEBACK_LOSSES), "45.99");
    same(balance(lost, Domain.CODE_CARD_RECEIPTS_IN_DISPUTE), "-45.99");
    same(balance(lost, Domain.CODE_CARD_CLEARING), "0");

    // Taken then won nets disputed receipts to nothing and clearing back to where it was, less
    // the fee, which nobody gives back.
    var all = new java.util.ArrayList<NominalLedgerEntry>();
    all.addAll(SalesPosting.chargebackWithdrawn(TENANT, ORDER, STORE, d("45.99"), d("15.00"), DAY));
    all.addAll(won);
    same(balance(all, Domain.CODE_CARD_RECEIPTS_IN_DISPUTE), "0");
    same(balance(all, Domain.CODE_CARD_CLEARING), "-15.00");
  }

  @Test
  void aDisputeWhoseMoneyNeverMovedPostsNothing() {
    assertThat(
        SalesPosting.chargebackClosed(TENANT, ORDER, STORE, d("45.99"), true, false, DAY).isEmpty(),
        is(true));
    assertThat(
        SalesPosting.chargebackClosed(TENANT, ORDER, STORE, d("45.99"), false, false, DAY)
            .isEmpty(),
        is(true));
    assertThat(
        SalesPosting.chargebackClosed(TENANT, ORDER, STORE, d("0"), false, true, DAY).isEmpty(),
        is(true));
    assertThat(
        SalesPosting.chargebackClosed(TENANT, ORDER, STORE, null, false, true, DAY).isEmpty(),
        is(true));
    assertThat(
        SalesPosting.chargebackWithdrawn(TENANT, ORDER, STORE, d("0"), d("0"), DAY).isEmpty(),
        is(true));
    assertThat(
        SalesPosting.chargebackWithdrawn(TENANT, ORDER, STORE, null, null, DAY).isEmpty(),
        is(true));
    // A fee with no amount — an inquiry the acquirer charged for — is still a fee.
    var feeOnly = SalesPosting.chargebackWithdrawn(TENANT, ORDER, STORE, d("0"), d("15.00"), DAY);
    same(balance(feeOnly, Domain.CODE_CHARGEBACK_FEES), "15.00");
    same(balance(feeOnly, Domain.CODE_CARD_CLEARING), "-15.00");
    // A negative amount from a confused sender moves nothing the wrong way.
    assertThat(
        SalesPosting.chargebackWithdrawn(TENANT, ORDER, STORE, d("-5"), d("-1"), DAY).isEmpty(),
        is(true));
  }

  // ── settlement against the acquirer's file (11.10) ──────────────────────────

  @Test
  void aSettledPayoutEmptiesCardClearingIntoTheBankLessTheAcquirersFees() {
    // The day's card takings, 165.99, posted as they were captured …
    var all = new java.util.ArrayList<NominalLedgerEntry>();
    all.addAll(SalesPosting.tender(TENANT, ORDER, STORE, "CARD", d("165.99"), DAY));
    // … and the payout that covers them: 163.50 in the bank, 2.49 kept.
    var paid =
        SalesPosting.cardSettlement(
            TENANT,
            ORDER,
            STORE,
            "WP-1 paid 2026-09-15",
            d("163.50"),
            d("2.49"),
            d("165.99"),
            d("0"),
            DAY);
    all.addAll(paid);

    same(balance(paid, Domain.CODE_BANK), "163.50");
    same(balance(paid, Domain.CODE_CARD_PROCESSING_FEES), "2.49");
    same(balance(all, Domain.CODE_CARD_CLEARING), "0");
    assertThat(paid.get(0).sourceType(), is(Domain.SOURCE_CARD_SETTLEMENT));
    assertThat(paid.get(0).sourceRef(), is(ORDER));
    assertThat(paid.get(0).description(), is("Card settlement WP-1 paid 2026-09-15"));
    assertThat(paid.stream().allMatch(l -> STORE.equals(l.storeId())), is(true));
  }

  @Test
  void moneyThatAnswersToNothingGoesToUnallocatedAndANegativeFigureChangesSides() {
    var unknown =
        SalesPosting.cardSettlement(
            TENANT, ORDER, null, "WP-2", d("29.55"), d("0.45"), d("0"), d("30.00"), DAY);
    same(balance(unknown, Domain.CODE_UNALLOCATED_RECEIPTS), "-30.00");
    same(balance(unknown, Domain.CODE_BANK), "29.55");

    // More refunds and chargebacks than sales: the acquirer takes money from the bank.
    var owed =
        SalesPosting.cardSettlement(
            TENANT, ORDER, STORE, "WP-3", d("-35.90"), d("0.90"), d("-30.00"), d("-5.00"), DAY);
    same(balance(owed, Domain.CODE_BANK), "-35.90");
    same(balance(owed, Domain.CODE_CARD_CLEARING), "30.00");
    same(balance(owed, Domain.CODE_UNALLOCATED_RECEIPTS), "5.00");
    same(balance(owed, Domain.CODE_CARD_PROCESSING_FEES), "0.90");
    assertThat(
        "no line carries a negative amount",
        owed.stream().allMatch(l -> l.debit().signum() >= 0 && l.credit().signum() >= 0),
        is(true));
  }

  @Test
  void figuresThatDoNotBalanceAreRefusedAndNothingIsNothing() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            SalesPosting.cardSettlement(
                TENANT, ORDER, STORE, "WP-4", d("100.00"), d("1.00"), d("100.00"), d("0"), DAY));
    assertThat(
        SalesPosting.cardSettlement(TENANT, ORDER, STORE, "WP-5", null, null, null, null, DAY)
            .isEmpty(),
        is(true));
  }
}
