package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.domain.SalesAttribution.SellerDay;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A commission statement's net sales are kept to the statement currency's own minor units: an empty
 * period is £0.00, ¥0 or KWD 0.000, and a sum never gains decimals the currency does not have.
 */
class SalesAttributionNetSalesTest {

  private static final UUID SELLER = Ids.newId();

  private static SellerDay day(String net) {
    return new SellerDay(SELLER, LocalDate.of(2026, 9, 1), new BigDecimal(net), BigDecimal.ONE);
  }

  @Test
  void anEmptyPeriodIsNothingInTheCurrencysOwnUnits() {
    assertThat(SalesAttribution.netSales(List.of(), 2), is(new BigDecimal("0.00")));
    assertThat(SalesAttribution.netSales(List.of(), 0), is(new BigDecimal("0")));
    assertThat(SalesAttribution.netSales(List.of(), 3), is(new BigDecimal("0.000")));
  }

  @Test
  void yenSalesAddUpToWholeYenAndDinarsKeepTheirFils() {
    assertThat(
        SalesAttribution.netSales(List.of(day("1200"), day("800")), 0), is(new BigDecimal("2000")));
    assertThat(
        SalesAttribution.netSales(List.of(day("1.235"), day("2.001")), 3),
        is(new BigDecimal("3.236")));
    assertThat(
        SalesAttribution.netSales(List.of(day("10.00"), day("20.00")), 2),
        is(new BigDecimal("30.00")));
  }
}
