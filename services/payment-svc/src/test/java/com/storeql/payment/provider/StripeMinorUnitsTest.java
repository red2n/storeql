package com.storeql.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.payment.provider.PaymentProvider.AuthorizeRequest;
import com.storeql.payment.provider.PaymentProvider.ProviderException;
import com.storeql.payment.provider.PaymentProvider.WebhookEvent;
import com.storeql.test.DriverStub;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code amount} Stripe is sent, and read back, in each currency's own minor units: ISO 4217's
 * (through common-service {@code Fx.minorUnits}) with Stripe's documented exceptions
 * (docs.stripe.com/currencies). A dinar is sent in fils (three places), yen and CFA francs whole,
 * and an amount Stripe cannot charge exactly is refused before Stripe is asked, never rounded.
 */
class StripeMinorUnitsTest {

  /** Stripe's zero-decimal list, as published, less UGX (a special case, below). */
  private static final String[] ZERO_DECIMAL = {
    "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "VND", "VUV", "XAF",
    "XOF", "XPF"
  };

  /** Stripe's three-decimal currencies: sent in thousandths, the last of them always 0. */
  private static final String[] THREE_DECIMAL = {"BHD", "JOD", "KWD", "OMR", "TND"};

  private DriverStub stripe;
  private StripePaymentProvider provider;

  @BeforeEach
  void start() {
    stripe =
        DriverStub.start(
            r -> DriverStub.Reply.json(200, "{\"id\":\"pi_1\",\"status\":\"requires_capture\"}"));
    provider = new StripePaymentProvider();
    // A key made for this run: the stand-in checks nothing about it.
    provider.secretKeyConfig = Optional.of("key-" + Ids.newId());
    provider.webhookSecretConfig = Optional.empty();
    provider.init();
    provider.apiBase = stripe.url();
  }

  @AfterEach
  void stop() {
    stripe.close();
  }

  private DriverStub.Recorded authorised(String amount, String currency) {
    provider.authorize(
        new AuthorizeRequest(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            new BigDecimal(amount),
            currency,
            null,
            Ids.newId().toString()));
    DriverStub.Recorded r = stripe.last();
    assertEquals("POST", r.method());
    assertEquals("/v1/payment_intents", r.route());
    assertEquals(
        currency.toLowerCase(java.util.Locale.ROOT), r.form().get("currency").get(0), currency);
    return r;
  }

  private String sentAmount(String amount, String currency) {
    return authorised(amount, currency).form().get("amount").get(0);
  }

  private void refusedBeforeStripe(String amount, String currency) {
    int before = stripe.count();
    ProviderException e =
        assertThrows(
            ProviderException.class,
            () ->
                provider.authorize(
                    new AuthorizeRequest(
                        Ids.newId(),
                        Ids.newId(),
                        Ids.newId(),
                        new BigDecimal(amount),
                        currency,
                        null,
                        Ids.newId().toString())),
            currency + " " + amount);
    assertFalse(e.retryable(), "asking again cannot make it chargeable");
    assertTrue(e.getMessage().contains(currency), e.getMessage());
    assertEquals(before, stripe.count(), "Stripe was never asked");
  }

  @Test
  @DisplayName("KWD is sent in fils: 1.120 is amount=1120, never 112")
  void kwdIsSentInFils() {
    assertEquals("1120", sentAmount("1.120", "KWD"));
    assertEquals("1120", sentAmount("1.12", "KWD"), "trailing zeros are not precision");
    assertEquals("5000", sentAmount("5", "BHD"));
    assertEquals("250", sentAmount("0.25", "OMR"));
  }

  @Test
  @DisplayName(
      "A dinar amount Stripe cannot charge (its last fils not 0) is refused before Stripe, never"
          + " rounded")
  void aDinarAmountStripeCannotChargeIsRefused() {
    refusedBeforeStripe("1.125", "KWD");
    refusedBeforeStripe("0.005", "JOD");
    refusedBeforeStripe("10.1255", "TND");
  }

  @Test
  @DisplayName(
      "An amount Stripe cannot charge is a pricing fact, said with the nearest amounts it can"
          + " charge, before Stripe is asked — never an outage")
  void anUnchargeableAmountNamesTheNearestItCanCharge() {
    for (String[] c :
        new String[][] {
          {"1.125", "KWD", "1.120", "1.130"},
          {"0.005", "JOD", "none", "0.010"},
          {"10.1255", "TND", "10.120", "10.130"},
          {"2.999", "BHD", "2.990", "3.000"},
          {"7.001", "OMR", "7.000", "7.010"},
          {"1250.5", "JPY", "1250", "1251"},
          {"5.50", "ISK", "5", "6"},
          {"10.505", "GBP", "10.50", "10.51"}
        }) {
      int before = stripe.count();
      PaymentProvider.AmountNotChargeable e =
          assertThrows(
              PaymentProvider.AmountNotChargeable.class,
              () -> provider.requireChargeable(new BigDecimal(c[0]), c[1]),
              c[1] + " " + c[0]);
      assertEquals(c[1], e.currency(), c[1]);
      // Nothing below the smallest chargeable amount is named: a charge of nothing is not one.
      assertEquals(
          c[2], e.below() == null ? "none" : e.below().toPlainString(), c[1] + " below " + c[0]);
      assertEquals(c[3], e.above().toPlainString(), c[1] + " above " + c[0]);
      assertFalse(e.retryable(), "asking again cannot make it chargeable");
      assertEquals(before, stripe.count(), "Stripe was never asked");
    }
    // What Stripe can charge passes, and is not sent anywhere by being checked.
    int before = stripe.count();
    provider.requireChargeable(new BigDecimal("1.120"), "KWD");
    provider.requireChargeable(new BigDecimal("1250.00"), "JPY");
    provider.requireChargeable(new BigDecimal("10.50"), "GBP");
    assertEquals(before, stripe.count());
    // And authorise refuses the same amount the same way, should anything reach it unchecked.
    assertThrows(
        PaymentProvider.AmountNotChargeable.class,
        () ->
            provider.authorize(
                new AuthorizeRequest(
                    Ids.newId(),
                    Ids.newId(),
                    Ids.newId(),
                    new BigDecimal("1.125"),
                    "KWD",
                    null,
                    Ids.newId().toString())));
    assertEquals(before, stripe.count(), "Stripe was never asked");
  }

  @Test
  @DisplayName("JPY is whole: 1250 yen is amount=1250, and half a yen is refused, never rounded")
  void jpyIsWhole() {
    assertEquals("1250", sentAmount("1250", "JPY"));
    assertEquals("1250", sentAmount("1250.00", "JPY"));
    refusedBeforeStripe("1250.5", "JPY");
  }

  @Test
  @DisplayName("XOF is whole: 5000 francs is amount=5000, never 100 times the price")
  void xofIsWhole() {
    assertEquals("5000", sentAmount("5000", "XOF"));
    assertEquals("5000", sentAmount("5000", "XAF"));
    assertEquals("1500", sentAmount("1500", "MGA"), "Stripe quotes MGA whole though ISO gives 2");
    refusedBeforeStripe("1500.50", "MGA");
  }

  @Test
  @DisplayName("ISK and UGX are whole krónur and shillings sent as hundredths: 5 is amount=500")
  void iskAndUgxAreWholeUnitsAsHundredths() {
    assertEquals("500", sentAmount("5", "ISK"));
    assertEquals("500", sentAmount("5", "UGX"));
    refusedBeforeStripe("5.50", "ISK");
    refusedBeforeStripe("5.01", "UGX");
  }

  @Test
  @DisplayName("Two-decimal currencies, HUF and TWD among them for a charge, are hundredths")
  void twoDecimalCurrenciesAreHundredths() {
    assertEquals("1050", sentAmount("10.50", "GBP"));
    assertEquals("1045", sentAmount("10.45", "HUF"));
    assertEquals("80045", sentAmount("800.45", "TWD"));
    refusedBeforeStripe("10.505", "GBP");
  }

  @Test
  @DisplayName("Stripe's published lists are pinned: zero-decimal 0, three-decimal 3, ISK/UGX 2")
  void stripesPublishedListsArePinned() {
    for (String c : ZERO_DECIMAL) assertEquals(0, StripePaymentProvider.exponent(c), c);
    for (String c : THREE_DECIMAL) assertEquals(3, StripePaymentProvider.exponent(c), c);
    assertEquals(2, StripePaymentProvider.exponent("ISK"));
    assertEquals(2, StripePaymentProvider.exponent("UGX"));
    for (String c : new String[] {"GBP", "USD", "EUR", "HUF", "TWD", "INR", "AED"}) {
      assertEquals(2, StripePaymentProvider.exponent(c), c);
    }
    assertEquals(3, StripePaymentProvider.exponent("kwd"), "case does not matter");
  }

  @Test
  @DisplayName("A capture is read back in the currency's own units: 1120 fils is 1.120 dinars")
  void aCaptureIsReadBackInTheCurrencysUnits() {
    for (String[] c :
        new String[][] {
          {"kwd", "1120", "1.120"},
          {"jpy", "1250", "1250"},
          {"xof", "5000", "5000"},
          {"isk", "500", "5"},
          {"gbp", "1050", "10.50"}
        }) {
      stripe.answerWith(
          r ->
              DriverStub.Reply.json(
                  200,
                  "{\"id\":\"pi_1\",\"currency\":\""
                      + c[0]
                      + "\",\"amount_received\":"
                      + c[1]
                      + "}"));
      PaymentProvider.Capture captured =
          provider.capture("pi_1", new BigDecimal(c[2]), Ids.newId().toString());
      assertEquals(new BigDecimal(c[2]), captured.capturedAmount(), c[0]);
    }
  }

  @Test
  @DisplayName("A webhook and a dispute are read back in the currency's own units too")
  void aWebhookAndADisputeAreReadInTheCurrencysUnits() {
    WebhookEvent paid =
        StripePaymentProvider.parseEvent(
            ("{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":"
                    + "{\"id\":\"pi_1\",\"status\":\"succeeded\",\"currency\":\"kwd\","
                    + "\"amount_received\":1120}}}")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(new BigDecimal("1.120"), paid.capturedAmount());
    WebhookEvent francs =
        StripePaymentProvider.parseEvent(
            ("{\"id\":\"evt_2\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":"
                    + "{\"id\":\"pi_2\",\"status\":\"succeeded\",\"currency\":\"xof\","
                    + "\"amount_received\":5000}}}")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(new BigDecimal("5000"), francs.capturedAmount());
    WebhookEvent dispute =
        StripePaymentProvider.parseEvent(
            ("{\"id\":\"evt_3\",\"type\":\"charge.dispute.funds_withdrawn\",\"data\":{\"object\":"
                    + "{\"id\":\"dp_1\",\"amount\":1120,\"currency\":\"kwd\","
                    + "\"payment_intent\":\"pi_1\",\"status\":\"needs_response\","
                    + "\"balance_transactions\":[{\"currency\":\"kwd\",\"fee\":3000}]}}}")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(new BigDecimal("1.120"), dispute.dispute().amount());
    assertEquals(new BigDecimal("3.000"), dispute.dispute().fee());
    assertEquals("KWD", dispute.dispute().feeCurrency());
  }

  @Test
  @DisplayName(
      "An answer that names no currency is never read as pounds: a capture keeps the amount asked,"
          + " a webhook leaves it to the intent's own")
  void noCurrencyIsNeverAssumed() {
    stripe.answerWith(
        r -> DriverStub.Reply.json(200, "{\"id\":\"pi_1\",\"amount_received\":1120}"));
    assertEquals(
        new BigDecimal("1.120"),
        provider.capture("pi_1", new BigDecimal("1.120"), Ids.newId().toString()).capturedAmount());
    WebhookEvent paid =
        StripePaymentProvider.parseEvent(
            ("{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":"
                    + "{\"id\":\"pi_1\",\"status\":\"succeeded\",\"amount_received\":1120}}}")
                .getBytes(StandardCharsets.UTF_8));
    assertNull(paid.capturedAmount());
  }
}
