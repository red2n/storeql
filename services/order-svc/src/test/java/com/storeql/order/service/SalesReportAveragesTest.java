package com.storeql.order.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.order.domain.Domain.SalesByStaffRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A cashier's average basket is money, and is kept to the business currency's own minor units: a
 * yen business sees whole yen, a dinar business keeps its fils. The discount rate is a percentage
 * and keeps its one decimal whatever the currency.
 */
class SalesReportAveragesTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void thePoundsAverageBasketKeepsItsPence() {
    var row =
        OrderService.withStaffRatios(
            new SalesByStaffRow("c", 3, d("10.00"), d("0.00"), null, null), 2);
    assertThat(row.averageBasket(), is(d("3.33")));
  }

  @Test
  void aDinarAverageBasketKeepsItsThirdDecimal() {
    var row =
        OrderService.withStaffRatios(
            new SalesByStaffRow("c", 3, d("10.000"), d("2.000"), null, null), 3);
    assertThat(row.averageBasket(), is(d("3.333")));
    assertThat(row.discountRate(), is(d("16.7")));
  }

  @Test
  void aYenAverageBasketIsWholeYen() {
    var row =
        OrderService.withStaffRatios(
            new SalesByStaffRow("c", 3, d("1000.00"), d("0.00"), null, null), 0);
    assertThat(row.averageBasket(), is(d("333")));
    // A sum read back from a column that kept two places is still whole yen on the report.
    assertThat(row.grossAmount(), is(d("1000")));
    assertThat(row.discountAmount(), is(d("0")));
  }
}
