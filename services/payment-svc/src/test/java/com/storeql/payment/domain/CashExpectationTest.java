package com.storeql.payment.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one formula every cash report uses: float + cash sales - cash refunds + pay-ins - pay-outs -
 * drops.
 */
class CashExpectationTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  @DisplayName(
      "Every term is in: float and sales and pay-ins add; refunds, pay-outs and drops take")
  void everyTermCounts() {
    var e = new CashExpectation(d("100"), d("50"), d("10"), d("15"), d("8"), d("20"));

    assertThat(e.expected(), comparesEqualTo(d("127")));
  }

  @Test
  @DisplayName("Each term moves the answer by exactly its own amount, in its own direction")
  void eachTermAlone() {
    BigDecimal zero = BigDecimal.ZERO;
    assertThat(
        new CashExpectation(d("7"), zero, zero, zero, zero, zero).expected(),
        comparesEqualTo(d("7")));
    assertThat(
        new CashExpectation(zero, d("7"), zero, zero, zero, zero).expected(),
        comparesEqualTo(d("7")));
    assertThat(
        new CashExpectation(zero, zero, d("7"), zero, zero, zero).expected(),
        comparesEqualTo(d("-7")));
    assertThat(
        new CashExpectation(zero, zero, zero, d("7"), zero, zero).expected(),
        comparesEqualTo(d("7")));
    assertThat(
        new CashExpectation(zero, zero, zero, zero, d("7"), zero).expected(),
        comparesEqualTo(d("-7")));
    assertThat(
        new CashExpectation(zero, zero, zero, zero, zero, d("7")).expected(),
        comparesEqualTo(d("-7")));
  }

  @Test
  @DisplayName("A missing term is none, so a drawer with nothing of a kind still adds up")
  void nullIsNone() {
    var e = new CashExpectation(d("80.5000"), null, null, null, null, null);

    assertThat(e.expected(), comparesEqualTo(d("80.5")));
  }

  @Test
  @DisplayName("Over/short is counted less expected: over is positive, short negative, right zero")
  void overShort() {
    var e = new CashExpectation(d("100"), d("50"), d("10"), d("15"), d("8"), d("20"));

    assertThat(e.overShort(d("130")), comparesEqualTo(d("3")));
    assertThat(e.overShort(d("120.25")), comparesEqualTo(d("-6.75")));
    assertThat(e.overShort(d("127")), comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("Money keeps four decimals without rounding: no floating point anywhere")
  void exactMoney() {
    var e = new CashExpectation(d("0.1000"), d("0.2000"), d("0.0001"), d("0"), d("0"), d("0"));

    assertThat(e.expected(), comparesEqualTo(d("0.2999")));
  }

  @Test
  @DisplayName("Nothing counted is not a count of zero: over/short refuses null")
  void countedIsRequired() {
    var e = new CashExpectation(d("1"), null, null, null, null, null);

    assertThrows(NullPointerException.class, () -> e.overShort(null));
  }
}
