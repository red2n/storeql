package com.storeql.reporting.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.Domain.LabourDayStat;
import com.storeql.reporting.domain.Domain.SalesCategoryStat;
import com.storeql.reporting.domain.Domain.SalesDayStat;
import com.storeql.reporting.domain.Domain.SalesSummary;
import com.storeql.reporting.dto.Dtos.LabourDayRow;
import com.storeql.reporting.dto.Dtos.SalesCategoryRow;
import com.storeql.reporting.dto.Dtos.SalesDayRow;
import com.storeql.reporting.dto.Dtos.SalesSummaryRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A report shows money at its currency's own minor units — whole yen, a dinar's three places, a
 * pound's two — though the columns hold four places for any currency. Percentages and hours keep
 * their fixed two places: they are not money.
 */
class MoneyAtCurrencyUnitsTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  private static String text(BigDecimal v) {
    return v == null ? null : v.toPlainString();
  }

  @Test
  @DisplayName("Sales summary: KWD keeps three places, JPY none, GBP two")
  void summary() {
    List<SalesSummaryRow> rows =
        Mappers.toSalesSummaryReport(
                List.of(
                    new SalesSummary("KWD", 2, d("10.1250"), d("0.0000")),
                    new SalesSummary("JPY", 1, d("1500.0000"), d("200.0000")),
                    new SalesSummary("GBP", 3, d("12.5000"), d("2.5000"))))
            .rows();
    assertEquals("10.125", text(rows.get(0).gross()));
    assertEquals("0.000", text(rows.get(0).refunded()));
    assertEquals("10.125", text(rows.get(0).net()));
    assertEquals("1500", text(rows.get(1).gross()));
    assertEquals("1300", text(rows.get(1).net()));
    assertEquals("12.50", text(rows.get(2).gross()));
    assertEquals("10.00", text(rows.get(2).net()));
  }

  @Test
  @DisplayName("Sales by day and by category: money at the currency's units, the share at two")
  void dayAndCategory() {
    List<SalesDayRow> days =
        Mappers.toSalesByDayReport(
                List.of(
                    new SalesDayStat("2026-10-01", "KWD", 1, d("1.1250"), d("0.1250")),
                    new SalesDayStat("2026-10-01", "JPY", 1, d("980.0000"), d("0"))))
            .rows();
    assertEquals("1.125", text(days.get(0).gross()));
    assertEquals("1.000", text(days.get(0).net()));
    assertEquals("980", text(days.get(1).gross()));
    assertEquals("0", text(days.get(1).refunded()));

    List<SalesCategoryRow> cats =
        Mappers.toSalesByCategoryReport(
                "TOP",
                List.of(
                    new SalesCategoryStat(Ids.newId(), "JPY", 1, d("3"), d("1000.0000")),
                    new SalesCategoryStat(Ids.newId(), "JPY", 1, d("1"), d("2000.0000"))))
            .rows();
    assertEquals("1000", text(cats.get(0).gross()));
    assertEquals("33.33", text(cats.get(0).share()), "a percentage keeps its two places");
  }

  @Test
  @DisplayName("Labour: takings, cost and takings per hour at the currency's units")
  void labour() {
    List<LabourDayRow> rows =
        Mappers.toLabourReport(
                List.of(
                    new LabourDayStat(
                        "2026-10-01", "JPY", d("10000.0000"), d("0.0000"), 180, 0, d("3000.0000")),
                    new LabourDayStat(
                        "2026-10-01", "KWD", d("10.0000"), d("0.0000"), 180, 0, d("3.1250")),
                    new LabourDayStat(
                        "2026-10-01", "GBP", d("100.0000"), d("0.0000"), 180, 60, null)))
            .rows();
    LabourDayRow yen = rows.get(0);
    assertEquals("10000", text(yen.net()));
    assertEquals("3000", text(yen.labourCost()));
    assertEquals("3333", text(yen.salesPerHour()), "10000 yen over 3 hours, whole yen");
    assertEquals("30.00", text(yen.labourPercent()));
    assertEquals("3.00", text(yen.hours()));

    LabourDayRow dinar = rows.get(1);
    assertEquals("3.125", text(dinar.labourCost()));
    assertEquals("3.333", text(dinar.salesPerHour()), "a dinar's three places");

    LabourDayRow pound = rows.get(2);
    assertEquals("100.00", text(pound.net()));
    assertEquals("33.33", text(pound.salesPerHour()));
    assertEquals(null, pound.labourCost(), "unknown stays unknown");
    assertEquals("1.00", text(pound.uncostedHours()));
  }
}
