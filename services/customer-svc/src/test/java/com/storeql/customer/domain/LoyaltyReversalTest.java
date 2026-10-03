package com.storeql.customer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The pro-rata arithmetic of taking points back when a sale comes back. */
class LoyaltyReversalTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static void assertPoints(String expected, BigDecimal actual) {
    assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }

  @Test
  void aReturnTakesBackTheShareOfWhatTheSaleEarned() {
    // 150 points on a 100.00 sale (a x1.5 tier); a 40.00 refund takes back 60.
    assertPoints(
        "60.00", LoyaltyReversal.forReturn(d("150"), d("0"), d("100.00"), d("40.00"), d("1")));
  }

  @Test
  void theShareRoundsDownAsEarningDoes() {
    // 10 points on 30.00; a 10.00 refund is 3.333... points.
    assertPoints(
        "3.33", LoyaltyReversal.forReturn(d("10"), d("0"), d("30.00"), d("10.00"), d("1")));
  }

  @Test
  void neverMoreThanEarnedLessWhatWasAlreadyTakenBack() {
    assertPoints(
        "20.00", LoyaltyReversal.forReturn(d("100"), d("80"), d("100.00"), d("50.00"), d("1")));
    assertPoints("0", LoyaltyReversal.forReturn(d("100"), d("100"), d("100.00"), d("5"), d("1")));
  }

  @Test
  void aRefundOfTheWholeSaleTakesEverythingLeft() {
    assertPoints(
        "70.00", LoyaltyReversal.forReturn(d("100"), d("30"), d("100.00"), d("100.00"), d("1")));
  }

  @Test
  void anEarningThatDidNotRecordItsTotalIsReversedAtTheProgrammesRate() {
    assertPoints("25.00", LoyaltyReversal.forReturn(d("100"), d("0"), null, d("12.50"), d("2")));
  }

  @Test
  void nothingForNoRefund() {
    assertPoints("0", LoyaltyReversal.forReturn(d("100"), d("0"), d("100"), d("0"), d("1")));
    assertPoints("0", LoyaltyReversal.forReturn(d("100"), d("0"), d("100"), null, d("1")));
  }

  @Test
  void aVoidTakesWhatIsStillNotTakenBack() {
    assertPoints("70", LoyaltyReversal.remaining(d("100"), d("30")));
    assertPoints("0", LoyaltyReversal.remaining(d("100"), d("130")));
  }
}
