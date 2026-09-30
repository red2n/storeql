package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An order-level discount is taken back out of what a return refunds (intent/return-controls.md):
 * shared across the lines pro rata to their net value in whole minor units, then across the parts a
 * line comes back in, so the refunds of a whole order never add up to more than was paid for its
 * goods, and the last part takes the rounding.
 */
class ReturnDiscountShareTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  private static BigDecimal sum(List<BigDecimal> xs) {
    return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void aDiscountIsSharedProRataToTheLinesNetValue() {
    var shares = ReturnValue.discountShares(d("10.00"), List.of(d("60.00"), d("40.00")), 2);
    assertThat(shares.get(0), is(d("6.00")));
    assertThat(shares.get(1), is(d("4.00")));
  }

  @Test
  void threeWaysThatDoNotDivideAddUpToExactlyTheDiscount() {
    var shares =
        ReturnValue.discountShares(d("1.00"), List.of(d("10.00"), d("10.00"), d("10.00")), 2);
    assertThat(sum(shares), is(d("1.00")));
    // 0.33 each, and the spare unit goes to the later line when the remainders tie.
    assertThat(shares.get(0), is(d("0.33")));
    assertThat(shares.get(1), is(d("0.33")));
    assertThat(shares.get(2), is(d("0.34")));
  }

  @Test
  void theLargestRemainderTakesTheSpareUnit() {
    // 5.00 over 3.00 : 7.00 : 10.00 is 0.75, 1.75, 2.50: the units left go by remainder.
    var shares =
        ReturnValue.discountShares(d("5.00"), List.of(d("3.00"), d("7.00"), d("10.00")), 2);
    assertThat(sum(shares), is(d("5.00")));
    assertThat(shares.get(2), is(d("2.50")));
  }

  @Test
  void aCurrencyWithNoMinorUnitSharesInWholeUnits() {
    var shares = ReturnValue.discountShares(d("100"), List.of(d("1000"), d("1000"), d("1000")), 0);
    assertThat(sum(shares), is(d("100")));
    assertThat(shares.get(2), is(d("34")));
  }

  @Test
  void noDiscountOrNothingToShareItAcrossSharesNothing() {
    assertThat(
        sum(ReturnValue.discountShares(BigDecimal.ZERO, List.of(d("5.00")), 2)).signum(), is(0));
    assertThat(sum(ReturnValue.discountShares(null, List.of(d("5.00")), 2)).signum(), is(0));
    assertThat(
        sum(ReturnValue.discountShares(d("2.00"), List.of(BigDecimal.ZERO), 2)).signum(), is(0));
    assertThat(ReturnValue.discountShares(d("2.00"), List.of(), 2).size(), is(0));
  }

  @Test
  void aFullyDiscountedOrderSharesEveryLineInFull() {
    var shares = ReturnValue.discountShares(d("30.00"), List.of(d("10.00"), d("20.00")), 2);
    assertThat(shares.get(0), is(d("10.00")));
    assertThat(shares.get(1), is(d("20.00")));
  }

  @Test
  void aDiscountAboveTheGoodsIsCappedAtTheGoods() {
    var shares = ReturnValue.discountShares(d("99.00"), List.of(d("10.00"), d("20.00")), 2);
    assertThat(sum(shares), is(d("30.00")));
  }

  @Test
  void aLineIsWorthItsNetAndVatLessItsShare() {
    // 100.00 net + 20.00 VAT, 12.00 of the order's discount: 108.00 paid, 88.00 net revenue.
    var w =
        ReturnValue.worth(d("100.00"), d("20.00"), d("4"), d("12.00"), BigDecimal.ZERO, d("4"), 2);
    assertThat(w.value(), is(d("108.00")));
    assertThat(w.net(), is(d("88.00")));
  }

  @Test
  void aPartOfALineIsWorthItsProportion() {
    var w =
        ReturnValue.worth(d("100.00"), d("20.00"), d("4"), d("12.00"), BigDecimal.ZERO, d("1"), 2);
    assertThat(w.value(), is(d("27.00")));
    assertThat(w.net(), is(d("22.00")));
  }

  @Test
  void aLineReturnedInThreePartsAddsUpToExactlyWhatItWasPaid() {
    // 10.00 net, 2.00 VAT and a 0.01 share over three units: each part rounds, the last takes it
    // up.
    BigDecimal paid = d("11.99");
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal back = BigDecimal.ZERO;
    for (int i = 0; i < 3; i++) {
      var w = ReturnValue.worth(d("10.00"), d("2.00"), d("3"), d("0.01"), back, BigDecimal.ONE, 2);
      total = total.add(w.value());
      back = back.add(BigDecimal.ONE);
    }
    assertThat(total, is(paid));
  }

  @Test
  void theThreePartsOfAThirdPennyLineNeverExceedTheWhole() {
    // 3 units sold for 100.00 in all (33.33 each is not 100.00): three parts still make 100.00.
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal back = BigDecimal.ZERO;
    for (int i = 0; i < 3; i++) {
      var w =
          ReturnValue.worth(d("100.00"), null, d("3"), BigDecimal.ZERO, back, BigDecimal.ONE, 2);
      total = total.add(w.value());
      back = back.add(BigDecimal.ONE);
    }
    assertThat(total, is(d("100.00")));
  }

  @Test
  void aZeroVatLineIsWorthItsNetLessItsShare() {
    var w =
        ReturnValue.worth(
            d("50.00"), BigDecimal.ZERO, d("5"), d("5.00"), BigDecimal.ZERO, d("2"), 2);
    assertThat(w.value(), is(d("18.00")));
    assertThat(w.net(), is(d("18.00")));
  }

  @Test
  void aFullyDiscountedLineRefundsNothingButItsVat() {
    var w =
        ReturnValue.worth(d("10.00"), d("2.00"), d("1"), d("10.00"), BigDecimal.ZERO, d("1"), 2);
    assertThat(w.value(), is(d("2.00")));
    assertThat(w.net().signum(), is(0));
  }

  @Test
  void withNoDiscountAReturnIsWhatItWasBefore() {
    assertThat(
        ReturnValue.worth(d("3.00"), d("1.00"), d("3"), BigDecimal.ZERO, BigDecimal.ZERO, d("1"), 2)
            .value(),
        is(ReturnValue.grossOf(d("1.00"), d("1"), d("1.00"), d("3"))));
    assertThat(
        ReturnValue.worth(d("30.00"), null, d("3"), null, BigDecimal.ZERO, d("3"), 2).value(),
        is(d("30.00")));
  }

  @Test
  void anOrderReturnedLineByLineInPartsRefundsExactlyWhatWasPaidForTheGoods() {
    // Two lines, 7.00% off a 45.00 basket in three-way rounding, returned a unit at a time.
    List<BigDecimal> nets = List.of(d("15.00"), d("15.00"), d("15.00"));
    List<BigDecimal> vats = List.of(d("3.00"), d("3.00"), d("3.00"));
    BigDecimal discount = d("3.15");
    var shares = ReturnValue.discountShares(discount, nets, 2);
    BigDecimal refunded = BigDecimal.ZERO;
    for (int line = 0; line < 3; line++) {
      BigDecimal back = BigDecimal.ZERO;
      for (int part = 0; part < 5; part++) {
        var w =
            ReturnValue.worth(
                nets.get(line), vats.get(line), d("5"), shares.get(line), back, BigDecimal.ONE, 2);
        refunded = refunded.add(w.value());
        back = back.add(BigDecimal.ONE);
      }
    }
    // Paid for the goods: 45.00 + 9.00 VAT - 3.15.
    assertThat(refunded, is(d("50.85")));
  }
}
