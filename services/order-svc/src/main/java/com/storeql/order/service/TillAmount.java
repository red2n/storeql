package com.storeql.order.service;

import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.service.Fx;
import com.storeql.web.ApiException;
import com.storeql.web.ErrorCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Money the till works out rather than types, taken at its currency's own minor units (ISO 4217,
 * through common-service {@link Fx#minorUnits}): whole yen, pence, fils for dinars.
 *
 * <p>The till adds a sale up in binary floating point and posts the double it has. A gift-card
 * tender of what is left to pay on three items at 1.10 is {@code 3.3000000000000003}; a full-basket
 * discount clamped to goods of 0.70 and 0.10 is {@code 0.7999999999999999}; a weighed 0.375 kg at
 * 12.99 is {@code 4.87125}, a line the till never rounds. What the cashier was shown, and what this
 * service totals each line to, is that figure rounded half up at the currency's units ({@code
 * 3.30}, {@code 0.80}, {@code 4.87}). Half up because a sale's lines are rounded so, which keeps a
 * tender that covers the total covering it — the same rule payment-svc applies to the same till's
 * tenders ({@code Amounts.tendered}), so the two services never disagree about one sale.
 *
 * <p>Refusing such a figure instead would refuse every sale the till rang up with that arithmetic,
 * and an offline-queued sale would replay the same body and be refused on every attempt. Money a
 * person types is still checked, never rounded ({@link TypedMoney}).
 */
final class TillAmount {

  private TillAmount() {}

  /**
   * The amount at the currency's minor units, half up. Null in, null out.
   *
   * <p>An amount below a tenth of the currency's smallest unit is nothing, said at once: rounding
   * it the long way builds a power of ten as long as its exponent, and {@code 1E-80000000} is
   * twelve characters on the wire.
   *
   * @param amount the figure as the till sent it
   * @param currency the ISO 4217 currency it is in
   * @return the figure at the currency's own scale
   * @throws ApiException 400 {@code VALIDATION_FAILED} for more than {@link
   *     TypedMoney#MOST_WHOLE_DIGITS} whole digits, before any rounding
   */
  static BigDecimal rounded(BigDecimal amount, String currency) {
    if (amount == null) return null;
    if (TypedMoney.tooLarge(amount)) {
      throw ApiException.badRequest(
          ErrorCodes.VALIDATION_FAILED,
          "an amount has more than " + TypedMoney.MOST_WHOLE_DIGITS + " whole digits");
    }
    int units = Fx.minorUnits(currency);
    // |amount| < 10^(precision - scale): at -units - 1 or below it is under a tenth of the
    // smallest unit, and half up makes it zero.
    if ((long) amount.precision() - amount.scale() < -units) {
      return BigDecimal.ZERO.setScale(units);
    }
    return amount.setScale(units, RoundingMode.HALF_UP);
  }

  /**
   * What a card is charged at a till: the figure the till worked out — what is left to pay, or the
   * card's balance when that is less — {@link #rounded rounded} to the currency's units. Only a
   * charge of nothing is refused.
   *
   * @param amount the amount as the till sent it
   * @param currency the order's ISO 4217 currency
   * @return the charge at the currency's scale: {@code 3.30} for pounds, {@code 999} for yen
   * @throws ApiException 400 {@code GIFT_CARD_AMOUNT_INVALID} for an amount that is missing, too
   *     large to be money, or not positive once rounded to the currency's units
   */
  static BigDecimal giftCardCharge(BigDecimal amount, String currency) {
    BigDecimal charge =
        amount == null || TypedMoney.tooLarge(amount) ? null : rounded(amount, currency);
    if (charge == null || charge.signum() <= 0) {
      throw ApiException.badRequest(
          "GIFT_CARD_AMOUNT_INVALID",
          "a gift card charge is at least the smallest unit " + currency + " has");
    }
    return charge;
  }

  /**
   * The most decimal places a figure the till sends can have. A till's double for any real amount
   * is written with at most seventeen significant digits, so twenty places hold every one from the
   * fourth place, the finest minor unit ISO 4217 has (payment-svc bounds a tender the same way).
   */
  private static final int MOST_PLACES = 20;

  /**
   * The goods as the till rang them up, which is what the till caps a discount at: each line's
   * quantity times the unit price the till sent, added up unrounded as the till adds them (2 ×
   * 0.333 kg at 1.99 is 1.32534), then {@link #rounded rounded} once at the currency's units (1.33)
   * — while this service rounds each line first (0.66 + 0.66 = 1.32), and its prices may be newer
   * than the till's. A line the till sent no price for counts at this service's own line, as does
   * one no till's double could be (more than {@value #MOST_PLACES} places, or more whole digits
   * than money has), so nothing here is longer than the figures a till sends.
   *
   * @param asSent the lines as the till sent them
   * @param lines this service's value of each line, in the same order
   * @param currency the order's ISO 4217 currency
   * @return the till's goods at the currency's own scale
   */
  static BigDecimal goodsAsRung(
      List<OrderItemRequest> asSent, List<BigDecimal> lines, String currency) {
    BigDecimal goods = BigDecimal.ZERO;
    for (int i = 0; i < asSent.size(); i++) {
      OrderItemRequest line = asSent.get(i);
      goods =
          goods.add(
              asATillSends(line.qty()) && asATillSends(line.unitPrice())
                  ? line.qty().multiply(line.unitPrice())
                  : lines.get(i));
    }
    return rounded(goods, currency);
  }

  private static boolean asATillSends(BigDecimal figure) {
    return figure != null && figure.scale() <= MOST_PLACES && !TypedMoney.tooLarge(figure);
  }
}
