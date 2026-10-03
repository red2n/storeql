package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Every line is rounded half up once at its currency's minor units, and a sale is the sum of its
 * rounded lines — the receipt's rule, at a pound's two places, a dinar's three and in whole yen.
 */
class LineMoneyTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aWeighedLineIsRoundedHalfUpAtTheCurrencysOwnUnits() {
    // 0.375 kg at 12.99 is 4.87125: a line of £4.87.
    assertEquals(d("4.87"), LineMoney.of(d("12.99"), d("0.375"), "GBP"));
    // At a dinar's three places: 0.375 × 1.299 is 0.487125, a line of 0.487.
    assertEquals(d("0.487"), LineMoney.of(d("1.299"), d("0.375"), "KWD"));
    // In whole yen: 0.375 × ¥1,299 is 487.125, a line of ¥487.
    assertEquals(d("487"), LineMoney.of(d("1299"), d("0.375"), "JPY"));
    // Half up, as a receipt rounds: 0.5 × 0.05 is 0.025, a line of 0.03.
    assertEquals(d("0.03"), LineMoney.of(d("0.05"), d("0.5"), "GBP"));
  }

  /**
   * 2 × 0.333 kg at 1.99: the lines are 0.66 and 0.66 and the sale 1.32 — not the 1.32534 an
   * unrounded total would make 1.33. The receipt's lines add up to its total.
   */
  @Test
  void aSaleIsTheSumOfItsRoundedLinesNotTheRoundedSumOfExactOnes() {
    BigDecimal line = LineMoney.of(d("1.99"), d("0.333"), "GBP");
    assertEquals(d("0.66"), line);
    assertEquals(d("1.32"), line.add(line));
    assertEquals(
        d("1.328"),
        LineMoney.of(d("1.995"), d("0.333"), "KWD")
            .add(LineMoney.of(d("1.995"), d("0.333"), "KWD")));
    assertEquals(
        d("132"),
        LineMoney.of(d("199"), d("0.333"), "JPY").add(LineMoney.of(d("199"), d("0.333"), "JPY")));
  }

  @Test
  void aQuotedLineIsHeldToTheSameRuleAndUnchangedByIt() {
    assertEquals(d("0.66"), LineMoney.quoted(d("0.66"), "GBP"));
    assertEquals(d("10.00"), LineMoney.quoted(d("10"), "GBP"));
    assertEquals(d("1.328"), LineMoney.quoted(d("1.328"), "KWD"));
    assertEquals(d("132"), LineMoney.quoted(d("132"), "JPY"));
    // A quote finer than the currency — none is, pricing-svc rounds each line — would be held to
    // it half up rather than kept finer than money.
    assertEquals(d("4.87"), LineMoney.quoted(d("4.87125"), "GBP"));
  }
}
