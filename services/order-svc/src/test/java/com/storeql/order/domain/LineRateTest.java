package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LineRateTest {

  @Test
  @DisplayName("the rate the quote applied is used as it was, whatever the rounding of the VAT")
  void theQuotedRateIsTheTruth() {
    // 1.99 at 20% inside: 0.33 of VAT on a net of 1.66 reads as 19.88% if worked back.
    assertEquals(
        new BigDecimal("20.00"),
        LineRate.percent(new BigDecimal("0.2000"), new BigDecimal("0.33"), new BigDecimal("1.66")));
    assertEquals(
        new BigDecimal("5.00"),
        LineRate.percent(new BigDecimal("0.05"), new BigDecimal("0.09"), new BigDecimal("1.90")));
  }

  @Test
  @DisplayName("a line that kept no rate has it worked back from its VAT, as before")
  void aLineWithoutARateIsWorkedBack() {
    assertEquals(
        new BigDecimal("19.88"),
        LineRate.percent(null, new BigDecimal("0.33"), new BigDecimal("1.66")));
    assertEquals(
        new BigDecimal("0.00"), LineRate.percent(null, new BigDecimal("0.00"), BigDecimal.ZERO));
  }
}
