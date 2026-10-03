package com.storeql.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The money of a container deposit (09.16), pure: what a line's containers carry, the VAT inside a
 * deposit the scheme taxes, and nothing at all — each rounded half up to the sale currency's own
 * minor units ({@code Fx.minorUnits}), because a scheme in yen pays back whole yen and one in
 * dinars keeps its fils.
 */
public final class ContainerDeposits {

  private ContainerDeposits() {}

  /**
   * The deposit on {@code qty} containers at {@code each}.
   *
   * @param scale the currency's minor units
   */
  public static BigDecimal amount(BigDecimal each, BigDecimal qty, int scale) {
    return each.multiply(qty).setScale(scale, RoundingMode.HALF_UP);
  }

  /**
   * The VAT inside a gross deposit at {@code rate} (a fraction), or nothing when the scheme leaves
   * it outside the scope of VAT ({@code rate} null).
   *
   * @param scale the currency's minor units
   */
  public static BigDecimal vatInside(BigDecimal gross, BigDecimal rate, int scale) {
    if (rate == null) return zero(scale);
    return gross.subtract(gross.divide(BigDecimal.ONE.add(rate), scale, RoundingMode.HALF_UP));
  }

  /** Nothing, at the currency's scale: £0.00, ¥0, KWD 0.000. */
  public static BigDecimal zero(int scale) {
    return BigDecimal.ZERO.setScale(scale);
  }
}
