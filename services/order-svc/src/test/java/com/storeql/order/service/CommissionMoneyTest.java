package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.client.TenantClient.SchemeTerms;
import com.storeql.service.Fx;
import com.storeql.service.FxRates;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A per-unit commission in another currency is stated in the statement's at the business's own
 * rate, rounded once to the statement currency's minor units — a pound's two places, a dinar's
 * three, whole yen — or not at all.
 */
class CommissionMoneyTest {

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** A business at home in {@code home}, with home units per one unit of each other currency. */
  private static FxRates.Table rates(String home, String... pairs) {
    java.util.Map<String, Fx.Rate> rates = new java.util.HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      rates.put(pairs[i], new Fx.Rate(pairs[i], d(pairs[i + 1]), FROM));
    }
    return new FxRates.Table(home, rates);
  }

  @Test
  void aForeignPerUnitCommissionIsTranslatedIntoTheHomeStatement() {
    // 12.34 EUR at 0.85 is 10.489: £10.49.
    assertEquals(
        d("10.49"),
        CommissionMoney.translate(d("12.34"), "EUR", "GBP", rates("GBP", "EUR", "0.85")).get());
    // A dinar statement keeps its third place: £10.00 at 0.3851 is KWD 3.851.
    assertEquals(
        d("3.851"),
        CommissionMoney.translate(d("10.00"), "GBP", "KWD", rates("KWD", "GBP", "0.3851")).get());
    // A yen statement is whole yen: $12.34 at 150.25 is 1854.085, ¥1,854.
    assertEquals(
        d("1854"),
        CommissionMoney.translate(d("12.34"), "USD", "JPY", rates("JPY", "USD", "150.25")).get());
  }

  @Test
  void aStatementInAnotherCurrencyThanHomeIsReachedThroughTheBusinesssRates() {
    FxRates.Table gbp = rates("GBP", "EUR", "0.85", "USD", "0.79", "JPY", "0.0052", "KWD", "2.60");
    // From home: £10.00 at 0.79 a dollar is 12.6582..., $12.66.
    assertEquals(d("12.66"), CommissionMoney.translate(d("10.00"), "GBP", "USD", gbp).get());
    // Across, rounded once: €10.00 is £8.50 unrounded, $10.7594..., $10.76.
    assertEquals(d("10.76"), CommissionMoney.translate(d("10.00"), "EUR", "USD", gbp).get());
    // Into whole yen: €1.00 is 0.85 / 0.0052 = 163.46..., ¥163.
    assertEquals(d("163"), CommissionMoney.translate(d("1.00"), "EUR", "JPY", gbp).get());
    // Into dinars: €1.00 is 0.85 / 2.60 = 0.32692..., KWD 0.327.
    assertEquals(d("0.327"), CommissionMoney.translate(d("1.00"), "EUR", "KWD", gbp).get());
  }

  @Test
  void theSameCurrencyIsHeldToItsOwnUnitsAndNeedsNoRate() {
    FxRates.Table none = rates("GBP");
    assertEquals(d("1854"), CommissionMoney.translate(d("1854"), "JPY", "jpy", none).get());
    assertEquals(d("3.851"), CommissionMoney.translate(d("3.851"), "KWD", "KWD", none).get());
  }

  @Test
  void withoutARateNothingIsGuessed() {
    FxRates.Table gbp = rates("GBP", "EUR", "0.85");
    // The arrangement's currency has no rate.
    assertTrue(CommissionMoney.translate(d("1.00"), "CHF", "GBP", gbp).isEmpty());
    // The statement's currency has none.
    assertTrue(CommissionMoney.translate(d("1.00"), "EUR", "USD", gbp).isEmpty());
    assertTrue(CommissionMoney.translate(d("1.00"), "GBP", "JPY", gbp).isEmpty());
  }

  @Test
  void whichCurrencyAStretchPaysInComesFromItsArrangement() {
    UUID percent = Ids.newId();
    UUID perUnit = Ids.newId();
    Map<UUID, SchemeTerms> schemes =
        Map.of(
            percent, new SchemeTerms("PERCENT_OF_NET", null),
            perUnit, new SchemeTerms("PER_UNIT", "eur"));
    assertNull(CommissionMoney.rateCurrency(null, schemes));
    assertNull(CommissionMoney.rateCurrency(percent, schemes));
    assertEquals("EUR", CommissionMoney.rateCurrency(perUnit, schemes));
    // A stretch under an arrangement the list does not hold is not guessed at either.
    assertThrows(
        IllegalStateException.class, () -> CommissionMoney.rateCurrency(Ids.newId(), schemes));
  }
}
