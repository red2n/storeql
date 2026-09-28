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
                new Yield.Share(new BigDecimal("44"), new BigDecimal("40"))));
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
                new Yield.Share(BigDecimal.ZERO, new BigDecimal("40"))));
    // Nothing came out of the second cut: its share goes to what did come out.
    assertThat(unit.get(0), comparesEqualTo(new BigDecimal("10.00")));
    assertThat(unit.get(1), is(nullValue()));
    List<BigDecimal> unvalued =
        Yield.apportion(null, List.of(new Yield.Share(new BigDecimal("50"), new BigDecimal("60"))));
    assertThat(unvalued.get(0), is(nullValue()));
  }
}
