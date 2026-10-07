package com.storeql.pricing.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.pricing.domain.Domain.TaxSummaryRow;
import com.storeql.pricing.domain.Domain.VatRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * pricing-svc's money in the currency's own minor units, never two decimals assumed: the VAT on a
 * price, and the VAT report's rows and totals. A yen business sees whole yen, a dinar business
 * keeps its fils; the rate itself is never rounded.
 */
class CurrencyMinorUnitsTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static VatRate rate(String r) {
    return new VatRate(
        com.storeql.ids.Ids.newId(),
        com.storeql.ids.Ids.newId(),
        "T1",
        "Standard",
        d(r),
        false,
        null,
        Instant.parse("2024-01-01T00:00:00Z"),
        null,
        Instant.parse("2024-01-01T00:00:00Z"));
  }

  @Test
  void vatOnAPriceIsInTheCurrencysOwnUnits() {
    // 10% of ¥1,234 is ¥123 (123.4), never ¥123.40.
    assertThat(PricingService.vatOn(d("1234"), rate("0.10"), 0), is(d("123")));
    // 5% of KWD 1.235 is 0.062 (0.06175), never 0.06.
    assertThat(PricingService.vatOn(d("1.235"), rate("0.05"), 3), is(d("0.062")));
    // 20% of £9.99 is £2.00 (1.998), as before.
    assertThat(PricingService.vatOn(d("9.99"), rate("0.20"), 2), is(d("2.00")));
  }

  @Test
  void theVatReportIsInTheBusinessCurrencysOwnUnits() {
    var yen =
        PricingService.taxSummaryOf(
            List.of(
                new TaxSummaryRow("T1", false, d("1000.00"), d("100.00"), d("1100.00"), 2),
                new TaxSummaryRow("T0", true, d("500.4"), d("0"), d("500.4"), 1)),
            0,
            "a",
            "b");
    assertThat(yen.rows().get(1).netAmount(), is(d("500")));
    assertThat(yen.totals().netAmount(), is(d("1500")));
    assertThat(yen.totals().outputVat(), is(d("100")));
    assertThat(yen.totals().grossAmount(), is(d("1600")));

    var dinar =
        PricingService.taxSummaryOf(
            List.of(new TaxSummaryRow("T1", false, d("1.235"), d("0.062"), d("1.297"), 1)),
            3,
            "a",
            "b");
    assertThat(dinar.rows().get(0).vatAmount(), is(d("0.062")));
    assertThat(dinar.totals().grossAmount(), is(d("1.297")));
    assertThat(dinar.totals().transactions(), is(1L));
  }

  @Test
  void aListPriceIsNoFinerThanItsCurrencyAndKeptAtItsScale() {
    assertThat(PricingService.priceIn(d("1.235"), "KWD"), is(d("1.235")));
    assertThat(PricingService.priceIn(d("1.2"), "KWD"), is(d("1.200")));
    assertThat(PricingService.priceIn(d("1234.00"), "JPY"), is(d("1234")));
    assertThat(PricingService.priceIn(d("10"), "GBP"), is(d("10.00")));
    for (String[] bad : new String[][] {{"1.2345", "KWD"}, {"1234.5", "JPY"}, {"9.999", "GBP"}}) {
      var e =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.storeql.web.ApiException.class, () -> PricingService.priceIn(d(bad[0]), bad[1]));
      assertThat(e.code(), is("VALIDATION_FAILED"));
      assertThat(e.details().get(0).startsWith("price: "), is(true));
    }
  }

  /**
   * A repricing proposal is kept in a four-place column (NUMERIC(19,4)) and read back as {@code
   * 7.9900}; applying it writes the list price at the list currency's own scale, as every other
   * price write does, never the column's four places: pence, whole yen, fils for dinars.
   */
  @Test
  void anAppliedProposalIsAtItsListCurrencysOwnScale() {
    assertThat(RepricingService.appliedPrice(d("7.9900"), "GBP"), is(d("7.99")));
    assertThat(RepricingService.appliedPrice(d("1234.0000"), "JPY"), is(d("1234")));
    assertThat(RepricingService.appliedPrice(d("8.9900"), "KWD"), is(d("8.990")));
    assertThat(RepricingService.appliedPrice(d("1.2350"), "KWD"), is(d("1.235")));
    // Finer than the currency is no proposal this service makes: said so, never rounded away.
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> RepricingService.appliedPrice(d("1234.5000"), "JPY"));
  }

  /**
   * A typed price far past any money is refused at once, whichever way its exponent points: neither
   * scaling {@code 1E+80000000} nor writing {@code 1E-80000000} out in full for the refusal's
   * message is work an eleven-character body may cause.
   */
  @Test
  void anExtremeExponentIsRefusedAtOnce() {
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2),
        () -> {
          for (String bad : new String[] {"1E+80000000", "1E-80000000"}) {
            var e =
                org.junit.jupiter.api.Assertions.assertThrows(
                    com.storeql.web.ApiException.class,
                    () -> PricingService.priceIn(d(bad), "GBP"));
            assertThat(e.code(), is("VALIDATION_FAILED"));
            assertThat(e.details().get(0).startsWith("price: "), is(true));
          }
        });
    // Eighteen whole digits is still a price.
    assertThat(PricingService.priceIn(d("999999999999999999"), "JPY"), is(d("999999999999999999")));
  }

  /**
   * A promotion's value is money for FLAT, BASKET_FLAT, SPEND_THRESHOLD and MIX_MATCH (an amount, a
   * bundle price) and is refused finer than the business's currency, as minOrderAmount already is;
   * for the percentage types and the BOGO (whose own fields describe it) it is no money at all, so
   * a yen business may give 12.5% and a dinar business 12.3456%.
   */
  @Test
  void anAmountPromotionsValueIsNoFinerThanItsCurrencyAndAPercentageIsNoMoney() {
    for (String type : new String[] {"FLAT", "BASKET_FLAT", "SPEND_THRESHOLD", "MIX_MATCH"}) {
      assertThat(type, PricingService.promotionValueIn(type, d("1.235"), "KWD"), is(d("1.235")));
      assertThat(type, PricingService.promotionValueIn(type, d("1.2"), "KWD"), is(d("1.200")));
      assertThat(type, PricingService.promotionValueIn(type, d("100.00"), "JPY"), is(d("100")));
      assertThat(type, PricingService.promotionValueIn(type, d("5"), "GBP"), is(d("5.00")));
      for (String[] bad :
          new String[][] {
            {"1.2345", "KWD"}, {"100.5", "JPY"}, {"9.999", "GBP"}, {"0.0001", "GBP"}
          }) {
        var e =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.storeql.web.ApiException.class,
                () -> PricingService.promotionValueIn(type, d(bad[0]), bad[1]),
                type + " " + bad[0] + " " + bad[1]);
        assertThat(e.status(), is(400));
        assertThat(e.code(), is("VALIDATION_FAILED"));
        assertThat(e.details().get(0).startsWith("value: "), is(true));
      }
    }
    // A percentage is not money: it keeps the scale it was typed at, in any currency.
    for (String type : new String[] {"PERCENT", "BASKET_PERCENT", "BOGO"}) {
      BigDecimal typed = d("12.3456");
      assertThat(type, PricingService.promotionValueIn(type, typed, "KWD"), is(typed));
      assertThat(type, PricingService.promotionValueIn(type, d("12.5"), "JPY"), is(d("12.5")));
    }
  }

  /**
   * A repricing rule's value is a percentage for UNDERCUT_PERCENT (no money) and an amount taken
   * off the rival's price for UNDERCUT_AMOUNT, which must be payable in the business's currency;
   * MATCH_LOWEST has no value to speak of.
   */
  @Test
  void aRepricingRulesAmountIsNoFinerThanItsCurrencyAndAPercentageIsNoMoney() {
    var amount = com.storeql.pricing.domain.Repricing.Strategy.UNDERCUT_AMOUNT;
    assertThat(RepricingService.ruleValueIn(amount, d("0.005"), "KWD"), is(d("0.005")));
    assertThat(RepricingService.ruleValueIn(amount, d("5"), "JPY"), is(d("5")));
    assertThat(RepricingService.ruleValueIn(amount, d("0.5"), "GBP"), is(d("0.50")));
    for (String[] bad : new String[][] {{"0.0005", "KWD"}, {"0.5", "JPY"}, {"0.505", "GBP"}}) {
      var e =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.storeql.web.ApiException.class,
              () -> RepricingService.ruleValueIn(amount, d(bad[0]), bad[1]),
              bad[0] + " " + bad[1]);
      assertThat(e.status(), is(400));
      assertThat(e.code(), is("VALIDATION_FAILED"));
      assertThat(e.details().get(0).startsWith("value: "), is(true));
    }
    var percent = com.storeql.pricing.domain.Repricing.Strategy.UNDERCUT_PERCENT;
    assertThat(RepricingService.ruleValueIn(percent, d("12.3456"), "KWD"), is(d("12.3456")));
    assertThat(RepricingService.ruleValueIn(percent, d("12.5"), "JPY"), is(d("12.5")));
    var match = com.storeql.pricing.domain.Repricing.Strategy.MATCH_LOWEST;
    assertThat(RepricingService.ruleValueIn(match, d("0"), "JPY"), is(d("0")));
  }

  /**
   * A rival's price is an observation, not a price the business sets: what a competitor charges is
   * kept as seen, at the column's four places ({@code competitor_prices.price} is NUMERIC(19,4)),
   * so it may be finer than the business's own currency (forecourt fuel to a tenth of a penny,
   * intent/repricing-automation.md). It is never rounded; one finer than the column holds is
   * refused, because the database would round it without a word.
   */
  @Test
  void aRivalsPriceIsKeptAsSeenAtTheColumnsFourPlaces() {
    // Finer than the currency's units, within the column's four places: accepted, never rounded.
    assertThat(RepricingService.rivalPriceIn(d("1.4599"), "GBP"), is(d("1.4599")));
    assertThat(RepricingService.rivalPriceIn(d("9.0005"), "KWD"), is(d("9.0005")));
    assertThat(RepricingService.rivalPriceIn(d("1250.5"), "JPY"), is(d("1250.5")));
    assertThat(RepricingService.rivalPriceIn(d("8.505"), "GBP"), is(d("8.505")));
    // Within the currency's units: returned at them.
    assertThat(RepricingService.rivalPriceIn(d("8.5"), "GBP"), is(d("8.50")));
    assertThat(RepricingService.rivalPriceIn(d("1250"), "JPY"), is(d("1250")));
    assertThat(RepricingService.rivalPriceIn(d("9.005"), "KWD"), is(d("9.005")));
    // Zeros past the currency's units say nothing finer, and zeros past the column's four are no
    // fifth place.
    assertThat(RepricingService.rivalPriceIn(d("1.4500"), "GBP"), is(d("1.45")));
    assertThat(RepricingService.rivalPriceIn(d("1.459900"), "GBP"), is(d("1.4599")));
    assertThat(RepricingService.rivalPriceIn(d("1250.0000"), "JPY"), is(d("1250")));
    // A fifth place is refused, because the column holds four.
    for (String[] bad :
        new String[][] {{"1.45999", "GBP"}, {"9.00005", "KWD"}, {"1250.12345", "JPY"}}) {
      var e =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.storeql.web.ApiException.class,
              () -> RepricingService.rivalPriceIn(d(bad[0]), bad[1]),
              bad[0] + " " + bad[1]);
      assertThat(e.status(), is(400));
      assertThat(e.code(), is("VALIDATION_FAILED"));
      assertThat(e.details().get(0).startsWith("price: "), is(true));
      assertThat(e.getMessage(), org.hamcrest.Matchers.containsString("four places"));
    }
  }

  /**
   * A typed figure that cannot fit its column is a 400 at the boundary, not the database's overflow
   * (a 500): promotions.value is NUMERIC(18,4), fourteen whole digits, and a rival's price and a
   * repricing rule's value NUMERIC(19,4), fifteen.
   */
  @Test
  void aFigureTooBigForItsColumnIsRefusedBeforeTheDatabaseSeesIt() {
    String fourteen = "99999999999999";
    String fifteen = "999999999999999";
    for (String type : new String[] {"FLAT", "BASKET_FLAT", "SPEND_THRESHOLD", "MIX_MATCH"}) {
      assertThat(PricingService.promotionValueIn(type, d(fourteen), "JPY"), is(d(fourteen)));
      assertRefused(() -> PricingService.promotionValueIn(type, d(fifteen), "JPY"), "value: ");
    }
    // Not money, and not any smaller: a BOGO's unused value, or a percentage typed past its column.
    assertRefused(() -> PricingService.promotionValueIn("BOGO", d(fifteen), null), "value: ");
    assertRefused(() -> PricingService.promotionValueIn("PERCENT", d(fifteen), null), "value: ");
    var amount = com.storeql.pricing.domain.Repricing.Strategy.UNDERCUT_AMOUNT;
    var percent = com.storeql.pricing.domain.Repricing.Strategy.UNDERCUT_PERCENT;
    var match = com.storeql.pricing.domain.Repricing.Strategy.MATCH_LOWEST;
    assertThat(RepricingService.ruleValueIn(amount, d(fifteen), "JPY"), is(d(fifteen)));
    assertRefused(() -> RepricingService.ruleValueIn(amount, d("9" + fifteen), "JPY"), "value: ");
    assertRefused(() -> RepricingService.ruleValueIn(percent, d("9" + fifteen), null), "value: ");
    assertRefused(() -> RepricingService.ruleValueIn(match, d("9" + fifteen), null), "value: ");
    assertThat(RepricingService.rivalPriceIn(d(fifteen), "JPY"), is(d(fifteen)));
    assertRefused(() -> RepricingService.rivalPriceIn(d("9" + fifteen), "JPY"), "price: ");
  }

  private static void assertRefused(
      org.junit.jupiter.api.function.Executable call, String detailPrefix) {
    var e = org.junit.jupiter.api.Assertions.assertThrows(com.storeql.web.ApiException.class, call);
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("VALIDATION_FAILED"));
    assertThat(e.details().get(0), org.hamcrest.Matchers.startsWith(detailPrefix));
  }

  @Test
  void typedMoneyIsRefusedByItsOwnName() {
    assertThat(PricingService.amountIn(d("1.98"), "KWD", "markdownPrice"), is(d("1.980")));
    assertThat(PricingService.amountIn(null, "KWD", "originalPrice"), is((BigDecimal) null));
    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.storeql.web.ApiException.class,
            () -> PricingService.amountIn(d("1980.5"), "JPY", "markdownPrice"));
    assertThat(e.details().get(0).startsWith("markdownPrice: "), is(true));
  }
}
