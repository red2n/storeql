package com.storeql.inventory.repo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Stock-turn money is kept to the business currency's own minor units, never two decimals assumed:
 * a yen business's cost of sales and holdings are whole yen, a dinar business keeps its fils. The
 * turnover ratio is a ratio and keeps its four decimals whatever the currency.
 */
class StockTurnMinorUnitsTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aYenTurnIsWholeYen() {
    // Sums of quantity × four-decimal unit cost: 1234.5678 of cost sold, holdings 1001 and 2000.
    var row = StockTurnRepository.turnRow("v", d("1234.5678"), d("0"), d("1001.4"), d("2000"), 0);
    assertThat(row.cogs(), is(d("1235")));
    assertThat(row.openingValue(), is(d("1001")));
    // The average of 1001 and 2000 is 1500.5: ¥1,501, never ¥1,500.50.
    assertThat(row.averageValue(), is(d("1501")));
    assertThat(row.turnoverRatio(), is(d("0.8228")));
  }

  @Test
  void aDinarTurnKeepsItsFils() {
    var row = StockTurnRepository.turnRow("v", d("1.2345"), d("0"), d("1.0004"), d("2.0011"), 3);
    assertThat(row.cogs(), is(d("1.235")));
    assertThat(row.closingValue(), is(d("2.001")));
    assertThat(row.averageValue(), is(d("1.501")));
  }

  @Test
  void aPoundTurnKeepsItsPenceAsBefore() {
    var row = StockTurnRepository.turnRow("v", d("10.004"), d("0"), d("5.00"), d("10.00"), 2);
    assertThat(row.cogs(), is(d("10.00")));
    assertThat(row.averageValue(), is(d("7.50")));
  }
}
