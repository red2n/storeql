package com.storeql.payment.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.payment.domain.Settlements;
import com.storeql.payment.domain.Settlements.ParsedFile;
import com.storeql.payment.domain.Settlements.ParsedLine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Each layout comes down to the same lines — signed from the business's side, net equal to gross
 * less fee — and a file that does not is refused with the line it fails on, never corrected.
 */
class SettlementParsersTest {

  private static final SettlementFileParser CANONICAL = new CanonicalSettlementParser();
  private static final SettlementFileParser STRIPE = new StripeSettlementParser();
  private static final SettlementFileParser ADYEN = new AdyenSettlementParser();

  private static BigDecimal money(String amount) {
    return new BigDecimal(amount).setScale(4);
  }

  private static SettlementFileException refused(SettlementFileParser parser, String content) {
    return assertThrows(SettlementFileException.class, () -> parser.parse(content));
  }

  // ── the platform's own layout ───────────────────────────────────────────────

  @Test
  void theCanonicalLayoutReadsEveryKindOfLineAndWorksOutWhatIsLeftOut() {
    ParsedFile file =
        CANONICAL.parse(
            "type,reference,original_reference,gross,fee,net,occurred_at\n"
                + "SALE,AUTH-1,,45.99,0.69,45.30,2026-09-15T10:22:31Z\n"
                + "sale,AUTH-2,,10.00,,,2026-09-15 11:00:00\n"
                + "REFUND,RF-1,AUTH-1,-5.00,0,-5.00,2026-09-16\n"
                + "CHARGEBACK,CB-1,AUTH-2,-10.00,15.00,-25.00,\n"
                + "chargeback reversal,CB-0,AUTH-0,20.00,,,\n"
                + "FEE,,,-12.00,,,\n"
                + "FEE,MONTHLY,,0,3.00,-3.00,\n"
                + "ADJUSTMENT,RESERVE,,-100.00,,,\n");

    assertEquals(8, file.lines().size());
    assertNull(file.reference());
    assertNull(file.declaredNet());
    ParsedLine first = file.lines().get(0);
    assertEquals(Settlements.SALE, first.type());
    assertEquals(money("45.99"), first.gross());
    assertEquals(money("45.30"), first.net());
    assertEquals(Instant.parse("2026-09-15T10:22:31Z"), first.occurredAt());
    ParsedLine second = file.lines().get(1);
    assertEquals(money("10.00"), second.net(), "no fee and no net: net is the gross");
    assertEquals(Instant.parse("2026-09-15T11:00:00Z"), second.occurredAt());
    assertEquals(Instant.parse("2026-09-16T00:00:00Z"), file.lines().get(2).occurredAt());
    assertEquals(Settlements.CHARGEBACK_REVERSAL, file.lines().get(4).type());
    ParsedLine charge = file.lines().get(5);
    assertEquals(money("0"), charge.gross(), "a charge written as money out is a fee, said so");
    assertEquals(money("12.00"), charge.fee());
    assertEquals(money("-12.00"), charge.net());
    assertEquals(money("-100.00"), file.lines().get(7).net());
  }

  @Test
  void aLineWithTheWrongSignIsRefusedNotFlipped() {
    String header = "type,reference,gross,fee,net\n";
    assertTrue(
        refused(CANONICAL, header + "REFUND,R1,5.00,0,5.00\n")
            .getMessage()
            .startsWith("Line 2: a refund takes money out"));
    assertTrue(
        refused(CANONICAL, header + "SALE,S1,10.00,0,10.00\nSALE,S2,-1.00,0,-1.00\n")
            .getMessage()
            .startsWith("Line 3: a sale brings money in"));
    assertTrue(
        refused(CANONICAL, header + "CHARGEBACK,C1,40.00,0,40.00\n")
            .getMessage()
            .contains("chargeback takes money out"));
    assertTrue(
        refused(CANONICAL, header + "CHARGEBACK_REVERSAL,C1,-40.00,0,-40.00\n")
            .getMessage()
            .contains("chargeback reversal brings money in"));
    assertTrue(refused(CANONICAL, header + "SALE,S1,0,0,0\n").getMessage().contains("above zero"));
  }

  @Test
  void aLineThatDoesNotAddUpOrCannotBeReadExactlyIsRefused() {
    String header = "type,reference,gross,fee,net\n";
    assertEquals(
        "Line 2: net is not gross less fee",
        refused(CANONICAL, header + "SALE,S1,10.00,0.50,9.00\n").getMessage());
    assertTrue(
        refused(CANONICAL, header + "SALE,S1,\"1.234,56\",0,0\n")
            .getMessage()
            .contains("not an amount written like 1234.56"));
    assertTrue(
        refused(CANONICAL, header + "SALE,S1,10.00001,0,10.00001\n")
            .getMessage()
            .contains("more than four decimal places"));
    assertTrue(
        refused(CANONICAL, header + "SALE,S1,1e30,0,1e30\n").getMessage().contains("too large"));
    assertTrue(
        refused(CANONICAL, header + "SALE,,10.00,0,10.00\n")
            .getMessage()
            .contains("needs the acquirer's reference"));
    assertTrue(
        refused(CANONICAL, header + "GIFT,S1,10.00,0,10.00\n")
            .getMessage()
            .contains("type is not one of"));
    assertTrue(refused(CANONICAL, header + ",S1,10.00,0,10.00\n").getMessage().contains("type is"));
    assertTrue(
        refused(CANONICAL, header + "ADJUSTMENT,A1,0,0,0\n").getMessage().contains("of nothing"));
    assertTrue(
        refused(CANONICAL, header + "FEE,F1,0,0,0\n").getMessage().contains("carries a fee"));
    assertTrue(
        refused(CANONICAL, "type,reference,gross,occurred_at\nSALE,S1,1.00,yesterday\n")
            .getMessage()
            .contains("not a date or a time"));
    assertTrue(
        refused(CANONICAL, header + "SALE," + "R".repeat(256) + ",1.00,0,1.00\n")
            .getMessage()
            .contains("too long a reference"));
  }

  @Test
  void anAmountWithAHugeExponentIsRefusedAtOnce() {
    // Twelve characters a file can carry; rounding them to four places the long way builds a
    // power of ten eighty million digits long. Refused as too fine, at once, as any fifth place.
    String header = "type,reference,gross,fee,net\n";
    SettlementFileException e =
        assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () -> refused(CANONICAL, header + "SALE,S1,1E-80000000,0,1E-80000000\n"));
    assertTrue(e.getMessage().contains("more than four decimal places"), e.getMessage());
    // Trailing zeros past the fourth place are still not precision, however they are written.
    assertEquals(
        money("10.00"),
        CANONICAL.parse(header + "SALE,S1,10.000000,0,1000000E-5\n").lines().get(0).gross());
  }

  @Test
  void aLongAmountCellIsRefusedOnItsLengthBeforeItIsRead() {
    // A cell may be 2,000 characters (the CSV's field limit) and a file 20,000 lines: a one with
    // 1,998 trailing zeros in every amount cell cost a quadratic strip of its zeros, cell after
    // cell. Refused on its length before it is read, and the whole file stays fast.
    String header = "type,reference,gross,fee,net\n";
    String longCell = "1." + "0".repeat(1_997);
    StringBuilder file = new StringBuilder(header);
    for (int i = 0; i < 2_000; i++) {
      file.append("SALE,S").append(i).append(',').append(longCell).append(",0,").append(longCell);
      file.append('\n');
    }
    SettlementFileException e =
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> refused(CANONICAL, file.toString()));
    assertEquals(Fields.INVALID, e.code());
    assertTrue(
        e.getMessage().contains("Line 2: gross is too long to be an amount"), e.getMessage());
    // A long cell is refused by its digits, whatever they are: zeros before the point too.
    assertTrue(
        refused(CANONICAL, header + "SALE,S1," + "0".repeat(60) + "1.00,0,1.00\n")
            .getMessage()
            .contains("too long to be an amount"));
    // And an amount written at the bound is read: forty characters is more than any money needs.
    String atBound = "1234.56" + "0".repeat(33);
    assertEquals(40, atBound.length());
    assertEquals(
        money("1234.56"),
        CANONICAL.parse(header + "SALE,S1," + atBound + ",0,1234.56\n").lines().get(0).gross());
  }

  @Test
  void aFileThatIsNotTheLayoutNamedSaysSoByItsCode() {
    assertEquals(
        "SETTLEMENT_FILE_WRONG_LAYOUT", refused(CANONICAL, "reference,amount\nS1,1.00\n").code());
    assertEquals("SETTLEMENT_FILE_INVALID", refused(CANONICAL, "type,gross\n").code());
    assertEquals("SETTLEMENT_FILE_INVALID", refused(CANONICAL, "").code());
    assertEquals(
        "SETTLEMENT_FILE_WRONG_LAYOUT",
        refused(STRIPE, "type,reference,gross\nSALE,S1,1.00\n").code());
    assertEquals(
        "SETTLEMENT_FILE_WRONG_LAYOUT",
        refused(ADYEN, "type,reference,gross\nSALE,S1,1.00\n").code());
  }

  // ── Stripe ──────────────────────────────────────────────────────────────────

  private static final String STRIPE_HEADER =
      "automatic_payout_id,automatic_payout_effective_at_utc,balance_transaction_id,created_utc,"
          + "currency,gross,fee,net,reporting_category,source_id,payment_intent_id,charge_id\n";

  @Test
  void stripesPayoutReportNamesItsPayoutItsDateAndItsCurrency() {
    ParsedFile file =
        STRIPE.parse(
            STRIPE_HEADER
                + "po_1,2026-09-17 00:00:00,txn_1,2026-09-15 10:22:31,gbp,45.99,0.89,45.10,"
                + "charge,ch_1,pi_1,ch_1\n"
                + "po_1,2026-09-17 00:00:00,txn_2,2026-09-15 12:00:00,gbp,-5.00,0.00,-5.00,"
                + "refund,re_1,pi_1,ch_1\n"
                + "po_1,2026-09-17 00:00:00,txn_3,2026-09-16 09:00:00,gbp,-20.00,15.00,-35.00,"
                + "dispute,dp_1,pi_2,ch_2\n"
                + "po_1,2026-09-17 00:00:00,txn_4,2026-09-16 09:30:00,gbp,20.00,0.00,20.00,"
                + "dispute_reversal,dp_0,pi_0,ch_0\n"
                + "po_1,2026-09-17 00:00:00,txn_5,2026-09-16 10:00:00,gbp,-2.00,0.00,-2.00,"
                + "fee,fee_1,,\n"
                + "po_1,2026-09-17 00:00:00,txn_6,2026-09-16 11:00:00,gbp,-5.00,0.00,-5.00,"
                + "reserve_transaction,rsv_1,,\n"
                + "po_1,2026-09-17 00:00:00,txn_7,2026-09-17 00:00:00,gbp,-18.10,0.00,-18.10,"
                + "payout,po_1,,\n");

    assertEquals("po_1", file.reference());
    assertEquals(LocalDate.of(2026, 9, 17), file.payoutDate());
    assertEquals("GBP", file.currency());
    assertEquals(money("18.10"), file.declaredNet(), "the payout row is what the rest adds up to");
    assertEquals(6, file.lines().size(), "and is not itself a line");
    ParsedLine sale = file.lines().get(0);
    assertEquals("pi_1", sale.reference(), "a payment is known by its payment intent");
    assertEquals("ch_1", sale.originalReference(), "with the charge beside it");
    ParsedLine refund = file.lines().get(1);
    assertEquals(Settlements.REFUND, refund.type());
    assertEquals("re_1", refund.reference());
    assertEquals("pi_1", refund.originalReference());
    ParsedLine dispute = file.lines().get(2);
    assertEquals(Settlements.CHARGEBACK, dispute.type());
    assertEquals("dp_1", dispute.reference(), "a dispute by the id the webhook gave");
    assertEquals(money("-35.00"), dispute.net());
    assertEquals(Settlements.CHARGEBACK_REVERSAL, file.lines().get(3).type());
    ParsedLine fee = file.lines().get(4);
    assertEquals(Settlements.FEE, fee.type());
    assertEquals(money("2.00"), fee.fee());
    assertEquals(Settlements.ADJUSTMENT, file.lines().get(5).type());
    BigDecimal sum =
        file.lines().stream().map(ParsedLine::net).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(file.declaredNet(), sum);
  }

  @Test
  void aStripeFileOfTwoPayoutsOrTwoCurrenciesIsRefused() {
    String one =
        "po_1,2026-09-17 00:00:00,txn_1,2026-09-15 10:22:31,gbp,1.00,0,1.00,charge,c,p,c\n";
    assertEquals(
        "SETTLEMENT_FILE_MANY_PAYOUTS",
        refused(STRIPE, STRIPE_HEADER + one + one.replace("po_1", "po_2")).code());
    assertEquals(
        "SETTLEMENT_FILE_MANY_PAYOUTS",
        refused(STRIPE, STRIPE_HEADER + one + one.replace(",gbp,", ",eur,")).code());
    assertTrue(
        refused(STRIPE, STRIPE_HEADER + one.replace(",gbp,", ",pounds,"))
            .getMessage()
            .contains("three-letter code"));
    assertTrue(
        refused(STRIPE, STRIPE_HEADER + one.replace(",charge,", ",,"))
            .getMessage()
            .contains("reporting_category is missing"));
  }

  // ── Adyen ───────────────────────────────────────────────────────────────────

  private static final String ADYEN_HEADER =
      "Company Account,Merchant Account,Psp Reference,Merchant Reference,Payment Method,"
          + "Creation Date,TimeZone,Type,Modification Reference,Gross Currency,Gross Debit (GC),"
          + "Gross Credit (GC),Exchange Rate,Net Currency,Net Debit (NC),Net Credit (NC),"
          + "Commission (NC),Markup (NC),Scheme Fees (NC),Interchange (NC),Batch Number\n";

  @Test
  void adyensSettlementDetailsAreCreditsLessDebitsAndTheFeeIsTheDifference() {
    ParsedFile file =
        ADYEN.parse(
            ADYEN_HEADER
                + "Co,Shop,PSP1,ORDER-1,visa,2026-09-15 10:22:31,UTC,Settled,,EUR,,45.99,1,EUR,,"
                + "45.10,0.20,0.30,0.09,0.30,118\n"
                + "Co,Shop,PSP1,ORDER-1,visa,2026-09-15 12:00:00,UTC,Refunded,MOD1,EUR,5.00,,1,"
                + "EUR,5.10,,0.10,,,,118\n"
                + "Co,Shop,PSP2,ORDER-2,mc,2026-09-16 09:00:00,UTC,Chargeback,MOD2,EUR,20.00,,1,"
                + "EUR,35.00,,,,,,118\n"
                + "Co,Shop,PSP0,ORDER-0,mc,2026-09-16 09:30:00,UTC,ChargebackReversed,MOD0,EUR,,"
                + "20.00,1,EUR,,20.00,,,,,118\n"
                + "Co,Shop,,,,2026-09-16 10:00:00,UTC,Fee,,EUR,,,,EUR,2.00,,,,,,118\n"
                + "Co,Shop,,Deposit,,2026-09-16 11:00:00,UTC,DepositCorrection,,EUR,,,,EUR,"
                + "5.00,,,,,,118\n"
                + "Co,Shop,,,,2026-09-17 02:00:00,UTC,MerchantPayout,,EUR,,,,EUR,18.00,,,,,,118\n");

    assertEquals("118", file.reference());
    assertEquals("EUR", file.currency());
    assertEquals(6, file.lines().size());
    ParsedLine sale = file.lines().get(0);
    assertEquals("PSP1", sale.reference());
    assertEquals("ORDER-1", sale.originalReference(), "a sale is looked for under either name");
    assertEquals(money("45.99"), sale.gross());
    assertEquals(money("0.89"), sale.fee());
    assertEquals(money("45.10"), sale.net());
    ParsedLine refund = file.lines().get(1);
    assertEquals("MOD1", refund.reference(), "a refund's own reference");
    assertEquals("PSP1", refund.originalReference(), "and the payment it gave back");
    assertEquals(money("-5.00"), refund.gross());
    assertEquals(money("0.10"), refund.fee());
    assertEquals(money("-35.00"), file.lines().get(2).net());
    assertEquals(money("15.00"), file.lines().get(2).fee());
    assertEquals(Settlements.CHARGEBACK_REVERSAL, file.lines().get(3).type());
    assertEquals(money("2.00"), file.lines().get(4).fee());
    assertEquals(Settlements.ADJUSTMENT, file.lines().get(5).type());
    assertEquals(money("-5.00"), file.lines().get(5).net());
    assertEquals(money("18.00"), file.declaredNet());
    assertEquals(
        file.declaredNet(),
        file.lines().stream().map(ParsedLine::net).reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  @Test
  void adyensPayoutRowSaysWhatTheBatchAddsUpToAndWhen() {
    ParsedFile file =
        ADYEN.parse(
            ADYEN_HEADER
                + "Co,Shop,PSP1,ORDER-1,visa,2026-09-15 10:22:31,UTC,Settled,,EUR,,45.99,1,EUR,,"
                + "45.10,,,,,118\n"
                + "Co,Shop,,,,2026-09-17 02:00:00,UTC,MerchantPayout,,EUR,,,,EUR,45.10,,,,,,118\n");

    assertEquals(money("45.10"), file.declaredNet());
    assertEquals(LocalDate.of(2026, 9, 17), file.payoutDate());
    assertEquals(1, file.lines().size());
  }

  @Test
  void anAdyenLineConvertedBetweenCurrenciesOrAFileOfTwoBatchesIsRefused() {
    String converted =
        "Co,Shop,PSP1,ORDER-1,visa,2026-09-15 10:22:31,UTC,Settled,,USD,,50.00,0.86,EUR,,"
            + "42.00,,,,,118\n";
    assertEquals("SETTLEMENT_CURRENCY_MIXED", refused(ADYEN, ADYEN_HEADER + converted).code());
    String one =
        "Co,Shop,PSP1,ORDER-1,visa,2026-09-15 10:22:31,CEST,Settled,,EUR,,1.00,1,EUR,,1.00,,,,,"
            + "118\n";
    assertEquals(
        "SETTLEMENT_FILE_MANY_PAYOUTS",
        refused(ADYEN, ADYEN_HEADER + one + one.replace(",118\n", ",119\n")).code());
    // A zone the JDK has no name for is read as UTC rather than refused: the time only breaks ties.
    assertEquals(
        Instant.parse("2026-09-15T10:22:31Z"),
        ADYEN.parse(ADYEN_HEADER + one).lines().get(0).occurredAt());
  }
}
