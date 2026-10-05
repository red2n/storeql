package com.storeql.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.payment.provider.PaymentProvider.DisputeNotice;
import com.storeql.payment.provider.PaymentProvider.WebhookEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Stripe's {@code charge.dispute.*} events, read into the provider-neutral shape (11.9). */
class StripeDisputeParsingTest {

  private static WebhookEvent parse(String type, String status, String extra) {
    String body =
        "{\"id\":\"evt_1\",\"type\":\""
            + type
            + "\",\"data\":{\"object\":{\"id\":\"dp_1\",\"object\":\"dispute\",\"amount\":4599,"
            + "\"currency\":\"gbp\",\"charge\":\"ch_1\",\"payment_intent\":\"pi_1\",\"reason\":"
            + "\"product_not_received\",\"status\":\""
            + status
            + "\",\"network_reason_code\":\"13.1\",\"evidence_details\":{\"due_by\":1790000000}"
            + extra
            + "}}}";
    return StripePaymentProvider.parseEvent(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aDisputeCreatedNamesThePaymentTheAmountTheReasonAndTheDate() {
    WebhookEvent e = parse("charge.dispute.created", "needs_response", "");
    assertEquals("evt_1", e.providerEventId());
    assertEquals("pi_1", e.providerRef(), "the intent the disputed charge belongs to");
    assertNull(e.status(), "not an event about the intent's own state");
    DisputeNotice d = e.dispute();
    assertNotNull(d);
    assertEquals("dp_1", d.disputeRef());
    assertEquals(DisputeNotice.PHASE_OPENED, d.phase());
    assertNull(d.outcome());
    assertEquals(0, new BigDecimal("45.99").compareTo(d.amount()), "minor units to major");
    assertEquals("GBP", d.currency());
    assertEquals("PRODUCT_NOT_RECEIVED", d.reason());
    assertEquals("13.1", d.networkReasonCode());
    assertEquals(Instant.ofEpochSecond(1790000000L), d.evidenceDueBy());
    assertEquals(0, BigDecimal.ZERO.compareTo(d.fee()), "no fee said yet");
  }

  @Test
  void fundsWithdrawnCarriesTheFeeStripeCharged() {
    WebhookEvent e =
        parse(
            "charge.dispute.funds_withdrawn",
            "needs_response",
            ",\"balance_transactions\":[{\"amount\":-4599,\"currency\":\"gbp\",\"fee\":1500,"
                + "\"net\":-6099},{\"amount\":0,\"currency\":\"gbp\",\"fee\":500,\"net\":-500}]");
    assertEquals(DisputeNotice.PHASE_FUNDS_WITHDRAWN, e.dispute().phase());
    assertEquals(0, new BigDecimal("20.00").compareTo(e.dispute().fee()), "the fees, summed");
    assertEquals("GBP", e.dispute().feeCurrency());
  }

  // ── the fee, in the currency Stripe charged it in ───────────────────────────

  private static final String SECRET = "whsec_" + com.storeql.ids.Ids.newId();

  /** A delivery signed as Stripe signs one, read through the public webhook's own check. */
  private static WebhookEvent delivered(String payload) {
    StripePaymentProvider p = new StripePaymentProvider();
    p.webhookSecret = SECRET;
    p.secretKey = "sk_" + com.storeql.ids.Ids.newId();
    p.apiBase = "https://api.stripe.example";
    long now = Instant.now().getEpochSecond();
    String signed = StripePaymentProvider.hmacSha256Hex(SECRET, now + "." + payload);
    return p.verifyWebhook(payload.getBytes(StandardCharsets.UTF_8), "t=" + now + ",v1=" + signed);
  }

  /**
   * charge.dispute.funds_withdrawn as Stripe sends it for a yen charge on an account that settles
   * in pounds: the dispute is in the charge's currency, each balance transaction in the account's
   * own (with the exchange rate it used), and its fee in that currency's minor units.
   */
  private static String yenDisputeSettledInPounds(String balanceTransactions) {
    return "{\"id\":\"evt_fx\",\"object\":\"event\",\"type\":\"charge.dispute.funds_withdrawn\","
        + "\"data\":{\"object\":{\"id\":\"dp_fx\",\"object\":\"dispute\",\"amount\":5000,"
        + "\"currency\":\"jpy\",\"charge\":\"ch_fx\",\"payment_intent\":\"pi_fx\","
        + "\"reason\":\"fraudulent\",\"status\":\"needs_response\",\"is_charge_refundable\":false,"
        + "\"evidence_details\":{\"due_by\":1790000000,\"has_evidence\":false,"
        + "\"past_due\":false,\"submission_count\":0},\"balance_transactions\":"
        + balanceTransactions
        + "}}}";
  }

  @Test
  void aDisputeFeeIsReadInTheCurrencyStripeChargedIt() {
    WebhookEvent e =
        delivered(
            yenDisputeSettledInPounds(
                "[{\"id\":\"txn_1\",\"object\":\"balance_transaction\",\"amount\":-2700,"
                    + "\"currency\":\"gbp\",\"exchange_rate\":0.0054,\"fee\":1500,"
                    + "\"fee_details\":[{\"amount\":1500,\"currency\":\"gbp\","
                    + "\"description\":\"Dispute fee\",\"type\":\"stripe_fee\"}],"
                    + "\"net\":-4200,\"type\":\"adjustment\"}]"));
    DisputeNotice d = e.dispute();
    // The disputed amount is the charge's: 5000 yen, yen having no minor unit.
    assertEquals(new BigDecimal("5000"), d.amount());
    assertEquals("JPY", d.currency());
    // The fee is the account's: 1500 pence is £15.00 — never 1500 yen.
    assertEquals(new BigDecimal("15.00"), d.fee());
    assertEquals("GBP", d.feeCurrency());
  }

  @Test
  void aFeeInNoCurrencyOrInTwoIsNotGuessedAt() {
    // A balance transaction that names no currency: its fee is not read as the dispute's.
    DisputeNotice none =
        delivered(yenDisputeSettledInPounds("[{\"amount\":-2700,\"fee\":1500,\"net\":-4200}]"))
            .dispute();
    assertNull(none.fee(), "not said, so not known");
    assertNull(none.feeCurrency());
    // Two currencies are never summed into one figure.
    DisputeNotice two =
        delivered(
                yenDisputeSettledInPounds(
                    "[{\"amount\":-2700,\"currency\":\"gbp\",\"fee\":1500,\"net\":-4200},"
                        + "{\"amount\":0,\"currency\":\"eur\",\"fee\":500,\"net\":-500}]"))
            .dispute();
    assertNull(two.fee());
    assertNull(two.feeCurrency());
    // No balance transaction yet: no fee charged yet, in the dispute's own currency.
    DisputeNotice opened = parse("charge.dispute.created", "needs_response", "").dispute();
    assertEquals(0, BigDecimal.ZERO.compareTo(opened.fee()));
    assertEquals("GBP", opened.feeCurrency());
  }

  @Test
  void aClosedDisputeSaysWhoWon() {
    assertEquals("LOST", parse("charge.dispute.closed", "lost", "").dispute().outcome());
    assertEquals("WON", parse("charge.dispute.closed", "won", "").dispute().outcome());
    assertEquals(
        "WON",
        parse("charge.dispute.closed", "warning_closed", "").dispute().outcome(),
        "an inquiry that never became a chargeback: the money never left");
    assertEquals(
        DisputeNotice.PHASE_CLOSED, parse("charge.dispute.closed", "won", "").dispute().phase());
    assertEquals(
        DisputeNotice.PHASE_UPDATED,
        parse("charge.dispute.updated", "under_review", "").dispute().phase());
    assertEquals(
        DisputeNotice.PHASE_FUNDS_REINSTATED,
        parse("charge.dispute.funds_reinstated", "won", "").dispute().phase());
  }

  @Test
  void stripesReasonsFallIntoTheCategoriesKept() {
    assertEquals("FRAUDULENT", StripePaymentProvider.disputeReason("fraudulent"));
    assertEquals("DUPLICATE", StripePaymentProvider.disputeReason("duplicate"));
    assertEquals(
        "CREDIT_NOT_PROCESSED", StripePaymentProvider.disputeReason("credit_not_processed"));
    assertEquals(
        "SUBSCRIPTION_CANCELLED", StripePaymentProvider.disputeReason("subscription_canceled"));
    assertEquals(
        "PRODUCT_UNACCEPTABLE", StripePaymentProvider.disputeReason("product_unacceptable"));
    assertEquals("UNRECOGNIZED", StripePaymentProvider.disputeReason("unrecognized"));
    assertEquals("GENERAL", StripePaymentProvider.disputeReason("bank_cannot_process"));
    assertEquals("GENERAL", StripePaymentProvider.disputeReason(""));
  }

  @Test
  void aDisputeWithNoAmountOrCurrencyIsRefusedNotGuessedAt() {
    byte[] noCurrency =
        "{\"id\":\"evt_2\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_2\",\"amount\":100}}}"
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PaymentProvider.ProviderException.class,
        () -> StripePaymentProvider.parseEvent(noCurrency));
    byte[] noAmount =
        "{\"id\":\"evt_3\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_3\",\"currency\":\"eur\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PaymentProvider.ProviderException.class, () -> StripePaymentProvider.parseEvent(noAmount));
  }

  @Test
  void anEventAboutAnIntentIsStillReadAsOne() {
    byte[] body =
        "{\"id\":\"evt_4\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_9\",\"currency\":\"gbp\",\"status\":\"succeeded\",\"amount_received\":1000}}}"
            .getBytes(StandardCharsets.UTF_8);
    WebhookEvent e = StripePaymentProvider.parseEvent(body);
    assertNull(e.dispute());
    assertEquals("pi_9", e.providerRef());
    assertEquals("CAPTURED", e.status());
  }
}
