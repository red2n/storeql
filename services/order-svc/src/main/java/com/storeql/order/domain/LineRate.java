package com.storeql.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The rate a line was taxed at, as a percentage with two decimals, for the fiscal files that list a
 * sale by rate. The rate the quote applied is the truth and is used when the line kept it; working
 * a rate back from a rounded VAT amount is a fallback for a line placed before it was kept, and is
 * wrong on a small line (1.99 at 20% is 0.33 of VAT on 1.66, which reads as 19.88%).
 */
public final class LineRate {

  private LineRate() {}

  /**
   * @param vatRate the rate the quote applied, as a fraction ({@code 0.20}), or null
   * @param vat the VAT on the line
   * @param lineTotal the line's net value
   * @return the rate in percent, to two decimals
   */
  public static BigDecimal percent(BigDecimal vatRate, BigDecimal vat, BigDecimal lineTotal) {
    if (vatRate != null) {
      return vatRate.movePointRight(2).setScale(2, RoundingMode.HALF_UP);
    }
    if (lineTotal == null || lineTotal.signum() == 0 || vat == null) {
      return BigDecimal.ZERO.setScale(2);
    }
    return vat.multiply(BigDecimal.valueOf(100)).divide(lineTotal, 2, RoundingMode.HALF_UP);
  }
}
