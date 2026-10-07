package com.storeql.product.mapper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment.Line;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A review line's money is written at its currency's own minor units (ISO 4217), whatever scale the
 * column keeps it at: the column holds four decimal places so that every currency fits, and a yen
 * figure read back as {@code 1200.0000} is not how anyone writes yen.
 */
class ReviewLineMoneyTest {

  private static Line line(String revenue, String margin, String currency) {
    return new Line(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        new BigDecimal("120.500"),
        revenue == null ? null : new BigDecimal(revenue),
        margin == null ? null : new BigDecimal(margin),
        currency,
        1,
        null,
        null,
        false);
  }

  @Test
  @DisplayName("Revenue and margin are written at their currency's minor units")
  void moneyIsWrittenAtItsCurrencysMinorUnits() {
    var yen = AssortmentMappers.toDto(line("1200.0000", "-300.0000", "JPY"));
    assertThat(yen.revenue(), is("1200"));
    assertThat(yen.margin(), is("-300"));

    var pounds = AssortmentMappers.toDto(line("340.0000", "70.5000", "GBP"));
    assertThat(pounds.revenue(), is("340.00"));
    assertThat(pounds.margin(), is("70.50"));

    var dinars = AssortmentMappers.toDto(line("12.3450", "1.0000", "KWD"));
    assertThat(dinars.revenue(), is("12.345"));
    assertThat(dinars.margin(), is("1.000"));

    // Units sold is a quantity, not money: written as kept.
    assertThat(yen.unitsSold(), is("120.500"));
  }

  @Test
  @DisplayName("A line with no money says none")
  void aLineWithNoMoneySaysNone() {
    var none = AssortmentMappers.toDto(line(null, null, null));
    assertThat(none.revenue(), is(nullValue()));
    assertThat(none.margin(), is(nullValue()));
    assertThat(none.currency(), is(nullValue()));
  }
}
