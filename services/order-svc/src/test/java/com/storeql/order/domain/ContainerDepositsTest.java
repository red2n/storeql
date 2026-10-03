package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The money of a container deposit (09.16), pure: the deposit on a line, the VAT inside a taxed
 * deposit, and an empty total — each in the sale currency's own minor units, never two decimals
 * assumed. A scheme in pounds keeps pence; one in yen keeps whole yen; one in dinars keeps fils.
 */
class ContainerDepositsTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aPoundDepositKeepsItsPence() {
    assertThat(ContainerDeposits.amount(d("0.20"), d("3"), 2), is(d("0.60")));
    assertThat(ContainerDeposits.vatInside(d("0.60"), d("0.20"), 2), is(d("0.10")));
    assertThat(ContainerDeposits.vatInside(d("0.60"), null, 2), is(d("0.00")));
    assertThat(ContainerDeposits.zero(2), is(d("0.00")));
  }

  @Test
  void aDinarDepositKeepsItsThirdDecimal() {
    // 3 × 0.025 KWD is 0.075, never 0.08; the VAT inside 0.075 at 5% is 0.004 (0.075 − 0.071).
    assertThat(ContainerDeposits.amount(d("0.025"), d("3"), 3), is(d("0.075")));
    assertThat(ContainerDeposits.vatInside(d("0.075"), d("0.05"), 3), is(d("0.004")));
    assertThat(ContainerDeposits.zero(3), is(d("0.000")));
  }

  @Test
  void aYenDepositIsWholeYen() {
    // 3 × ¥10.5 (a scheme's per-container figure) is ¥32 (31.5 half up), never ¥31.50; the VAT
    // inside ¥32 at 10% is ¥3 (32 − 29), and nothing is ¥0, not ¥0.00.
    assertThat(ContainerDeposits.amount(d("10.5"), d("3"), 0), is(d("32")));
    assertThat(ContainerDeposits.vatInside(d("32"), d("0.10"), 0), is(d("3")));
    assertThat(ContainerDeposits.zero(0), is(d("0")));
  }
}
