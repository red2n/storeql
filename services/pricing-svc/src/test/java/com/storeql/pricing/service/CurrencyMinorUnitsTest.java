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
