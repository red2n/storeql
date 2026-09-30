package com.storeql.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The arithmetic of what a return is worth (intent/return-controls.md), pure so the rules can be
 * tested without a database: what a returned line is worth to the customer, how an exchange
 * settles, and what a no-receipt line costs at today's price.
 */
public final class ReturnValue {

  private ReturnValue() {}

  /** Money is kept to the minor unit of the columns it lands in. */
  private static final int SCALE = 2;

  /**
   * How an exchange settles between what comes back and what is bought.
   *
   * @param exchangeAmount the part of the new basket the returned value pays: the lesser of the two
   * @param dueFromCustomer what the customer still pays when the new basket is dearer
   * @param refundToCustomer what goes back to the customer when the new basket is cheaper
   */
  public record Settlement(
      BigDecimal exchangeAmount, BigDecimal dueFromCustomer, BigDecimal refundToCustomer) {}

  /**
   * Settles an exchange: the returned value pays the new basket directly, and only the difference
   * is paid or refunded.
   *
   * @param returned what the return is worth (R), never negative
   * @param bought what the new basket costs (B), never negative
   * @return the exchange amount min(R, B), what is due max(B - R, 0) and what goes back max(R - B,
   *     0); one of the last two is always zero
   */
  public static Settlement settle(BigDecimal returned, BigDecimal bought) {
    if (returned == null || bought == null || returned.signum() < 0 || bought.signum() < 0)
      throw new IllegalArgumentException("an exchange settles two amounts that are not negative");
    BigDecimal exchange = returned.min(bought);
    return new Settlement(
        exchange,
        bought.subtract(returned).max(BigDecimal.ZERO),
        returned.subtract(bought).max(BigDecimal.ZERO));
  }

  /**
   * What a returned line is worth to the customer against the sale: the line's net price for the
   * quantity, plus that quantity's share of the VAT the line was sold with. The sale's own VAT is
   * used, never a rate assumed here; a line sold with no VAT recorded is worth its net price.
   *
   * @param unitNet the price of one, net of VAT
   * @param returnedQty how many come back
   * @param lineVat the VAT on the whole sold line, or null when none was recorded
   * @param soldQty how many the line was sold with
   * @return the value, rounded half up to the minor unit
   */
  public static BigDecimal grossOf(
      BigDecimal unitNet, BigDecimal returnedQty, BigDecimal lineVat, BigDecimal soldQty) {
    BigDecimal net = unitNet.multiply(returnedQty);
    BigDecimal vat = BigDecimal.ZERO;
    if (lineVat != null && soldQty != null && soldQty.signum() > 0) {
      vat = lineVat.multiply(returnedQty).divide(soldQty, 10, RoundingMode.HALF_UP);
    }
    return net.add(vat).setScale(SCALE, RoundingMode.HALF_UP);
  }

  /**
   * A no-receipt line at the current price.
   *
   * @param unitNet the current price of one, net of VAT
   * @param unitVat the VAT on one
   * @param qty how many come back
   * @return the line: unit price with VAT, the VAT in the line, and the line's value
   */
  public static Line priceLine(BigDecimal unitNet, BigDecimal unitVat, BigDecimal qty) {
    BigDecimal vatEach = unitVat == null ? BigDecimal.ZERO : unitVat;
    BigDecimal grossEach = unitNet.add(vatEach);
    return new Line(
        grossEach.setScale(4, RoundingMode.HALF_UP),
        vatEach.multiply(qty).setScale(SCALE, RoundingMode.HALF_UP),
        grossEach.multiply(qty).setScale(SCALE, RoundingMode.HALF_UP));
  }

  /**
   * One priced no-receipt line.
   *
   * @param unitPrice one unit, VAT included
   * @param taxAmount the VAT in the whole line
   * @param value the whole line, VAT included: what the customer is credited
   */
  public record Line(BigDecimal unitPrice, BigDecimal taxAmount, BigDecimal value) {}
}
