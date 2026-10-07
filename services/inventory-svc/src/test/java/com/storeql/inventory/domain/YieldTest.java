package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of a breakdown, pure: what a primal is expected to yield, what its cost becomes on
 * the cuts, and what the loss was against what was expected. Written before the code.
 */
class YieldTest {

  @Test
  void expectedQuantitiesAreSharesOfTheInputAndTheRestIsLoss() {
    assertThat(
        Yield.expectedQty(new BigDecimal("100"), new BigDecimal("35")),
        comparesEqualTo(new BigDecimal("35.000")));
    assertThat(
        Yield.expectedQty(new BigDecimal("12.5"), new BigDecimal("33.333")),
        comparesEqualTo(new BigDecimal("4.167")));
    // Two outputs at 35 % and 45 % leave a fifth of the input as expected loss.
    assertThat(
        Yield.expectedLossPct(List.of(new BigDecimal("35"), new BigDecimal("45"))),
        comparesEqualTo(new BigDecimal("20.000")));
    assertThat(
        Yield.lossPct(new BigDecimal("100"), new BigDecimal("78")),
        comparesEqualTo(new BigDecimal("22.000")));
    assertThat(Yield.lossPct(BigDecimal.ZERO, BigDecimal.ZERO), comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  void theInputCostIsApportionedByCostShareAndSpreadOverWhatCameOut() {
    // A side at 500.00 broken into 34 of sirloin (share 60) and 44 of mince (share 40): the
    // sirloin carries 300.00 over 34 units, the mince 200.00 over 44; the loss carries nothing,
    // which is why the cuts cost more per unit than the side did.
    List<BigDecimal> unit =
        Yield.apportion(
            new BigDecimal("500.00"),
            List.of(
                new Yield.Share(new BigDecimal("34"), new BigDecimal("60")),
                new Yield.Share(new BigDecimal("44"), new BigDecimal("40"))),
            2);
    assertThat(unit.get(0), comparesEqualTo(new BigDecimal("8.82")));
    assertThat(unit.get(1), comparesEqualTo(new BigDecimal("4.55")));
  }

  @Test
  void anOutputThatCameToNothingOrAnInputWithNoCostCarriesNoCost() {
    List<BigDecimal> unit =
        Yield.apportion(
            new BigDecimal("500.00"),
            List.of(
                new Yield.Share(new BigDecimal("50"), new BigDecimal("60")),
                new Yield.Share(BigDecimal.ZERO, new BigDecimal("40"))),
            2);
    // Nothing came out of the second cut: its share goes to what did come out.
    assertThat(unit.get(0), comparesEqualTo(new BigDecimal("10.00")));
    assertThat(unit.get(1), is(nullValue()));
    List<BigDecimal> unvalued =
        Yield.apportion(
            null, List.of(new Yield.Share(new BigDecimal("50"), new BigDecimal("60"))), 2);
    assertThat(unvalued.get(0), is(nullValue()));
  }

  /**
   * The cost of a cut is kept to the business currency's own minor units, never two decimals
   * assumed: a dinar side keeps its fils, a yen side is whole yen; so are the primal's cost and the
   * loss at cost.
   */
  @Test
  void costsAreInTheCurrencysOwnMinorUnits() {
    List<Yield.Share> shares =
        List.of(
            new Yield.Share(new BigDecimal("34"), new BigDecimal("60")),
            new Yield.Share(new BigDecimal("44"), new BigDecimal("40")));
    // KWD 500.000: 300 over 34 is 8.824 (8.8235…), 200 over 44 is 4.545 (4.5454…).
    List<BigDecimal> dinar = Yield.apportion(new BigDecimal("500.000"), shares, 3);
    assertThat(dinar.get(0), is(new BigDecimal("8.824")));
    assertThat(dinar.get(1), is(new BigDecimal("4.545")));
    // ¥50,000: 30,000 over 34 is ¥882 (882.35…), 20,000 over 44 is ¥455 (454.54…).
    List<BigDecimal> yen = Yield.apportion(new BigDecimal("50000"), shares, 0);
    assertThat(yen.get(0), is(new BigDecimal("882")));
    assertThat(yen.get(1), is(new BigDecimal("455")));

    assertThat(Yield.amount(new BigDecimal("499.9996"), 3), is(new BigDecimal("500.000")));
    assertThat(Yield.amount(new BigDecimal("49999.6"), 0), is(new BigDecimal("50000")));
    // The loss at cost: 22 of 100 lost from a KWD 500.000 side is 110.000; from ¥50,001, ¥11,000.
    assertThat(
        Yield.lossAtCost(new BigDecimal("500.000"), new BigDecimal("22"), new BigDecimal("100"), 3),
        is(new BigDecimal("110.000")));
    assertThat(
        Yield.lossAtCost(new BigDecimal("50001"), new BigDecimal("22"), new BigDecimal("100"), 0),
        is(new BigDecimal("11000")));
    assertThat(
        Yield.lossAtCost(null, new BigDecimal("22"), new BigDecimal("100"), 2), is(nullValue()));
  }
}
