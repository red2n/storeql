package com.storeql.order.service;

import com.storeql.service.Fx;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one line of a sale is worth on the receipt — the one rule every line in this service is
 * valued by, and pricing-svc's quote by the same rule.
 *
 * <p><b>Per line, then added up.</b> A line is its quantity times its unit price, rounded once,
 * half up, to the currency's own minor units (ISO 4217 through {@link Fx#minorUnits}: pence, whole
 * yen, a dinar's three places); the order's subtotal is the sum of those rounded lines, and VAT,
 * discounts, refunds and the fiscal record are worked from them. A weighed 0.375 kg at 12.99 is a
 * line of 4.87 and 2 × 0.333 kg at 1.99 are lines of 0.66 + 0.66 = 1.32 — not the 1.32534 an
 * unrounded total would make 1.33. That is how a receipt is printed and read: each line shows money
 * that can be paid, and the total is what the lines add up to, so a customer, an auditor and a
 * return of one line all see the same figures (EN 16931 BR-CO-10 says the same of an invoice: the
 * sum of the line net amounts). Totalling the exact products and rounding once would leave lines on
 * the receipt that do not add up to its total.
 */
final class LineMoney {

  private LineMoney() {}

  /**
   * A line's value: {@code qty × unitPrice}, half up at the currency's minor units.
   *
   * @param unitPrice the net unit price, in {@code currency}
   * @param qty the quantity, as counted
   * @param currency the ISO 4217 currency of the sale
   */
  static BigDecimal of(BigDecimal unitPrice, BigDecimal qty, String currency) {
    return unitPrice.multiply(qty).setScale(Fx.minorUnits(currency), RoundingMode.HALF_UP);
  }

  /**
   * A line valued elsewhere — pricing-svc's quote, which rounds each line by this same rule — held
   * to it: half up at the currency's minor units, which leaves a quoted line exactly as quoted.
   *
   * @param line the line's value as quoted
   * @param currency the ISO 4217 currency of the sale
   */
  static BigDecimal quoted(BigDecimal line, String currency) {
    return line.setScale(Fx.minorUnits(currency), RoundingMode.HALF_UP);
  }
}
