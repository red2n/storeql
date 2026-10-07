package com.storeql.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The arithmetic of what a return is worth (intent/return-controls.md), pure so the rules can be
 * tested without a database: what a returned line is worth to the customer, how an exchange
 * settles, and what a no-receipt line costs at today's price.
 */
public final class ReturnValue {

  private ReturnValue() {}

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
   * @param scale the currency's minor units ({@code Fx.minorUnits}): 2 for the pound, 0 for the
   *     yen, 3 for the dinar
   * @return the value, rounded half up to the currency's minor unit
   */
  public static BigDecimal grossOf(
      BigDecimal unitNet,
      BigDecimal returnedQty,
      BigDecimal lineVat,
      BigDecimal soldQty,
      int scale) {
    BigDecimal net = unitNet.multiply(returnedQty);
    BigDecimal vat = BigDecimal.ZERO;
    if (lineVat != null && soldQty != null && soldQty.signum() > 0) {
      vat = lineVat.multiply(returnedQty).divide(soldQty, 10, RoundingMode.HALF_UP);
    }
    return net.add(vat).setScale(scale, RoundingMode.HALF_UP);
  }

  /**
   * Shares an order-level discount (the staff discount and the promotion engine's whole-basket
   * reduction) across the lines it was taken off, pro rata to their net value, in whole minor
   * units, so that the shares add up to exactly the discount: each line takes its share rounded
   * down, and the units left over go one each to the lines with the largest remainders (the later
   * line first when two are equal). A line's VAT is not part of the weight, because the order's VAT
   * is charged in full whatever the discount, as the sale's revenue is shared ({@link
   * LineRevenue}).
   *
   * @param discount the order's discount, never negative
   * @param lineNets the lines' net values in a stable order, never negative
   * @param scale the currency's minor units (2 for most, 0 for the yen)
   * @return each line's share, in the order given; all zero when there is no discount or nothing to
   *     share it across; never more than the discount in all
   */
  public static List<BigDecimal> discountShares(
      BigDecimal discount, List<BigDecimal> lineNets, int scale) {
    int n = lineNets.size();
    List<BigDecimal> out = new ArrayList<>(n);
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal net : lineNets) total = total.add(net == null ? BigDecimal.ZERO : net);
    BigDecimal d = discount == null ? BigDecimal.ZERO : discount.setScale(scale, RoundingMode.DOWN);
    if (d.signum() <= 0 || total.signum() <= 0) {
      for (int i = 0; i < n; i++) out.add(BigDecimal.ZERO.setScale(scale));
      return out;
    }
    if (d.compareTo(total) > 0) d = total.setScale(scale, RoundingMode.DOWN);
    BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
    BigDecimal[] exact = new BigDecimal[n];
    BigDecimal[] rest = new BigDecimal[n];
    BigDecimal given = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      BigDecimal net = lineNets.get(i) == null ? BigDecimal.ZERO : lineNets.get(i);
      BigDecimal share = d.multiply(net).divide(total, 12, RoundingMode.HALF_EVEN);
      exact[i] = share.setScale(scale, RoundingMode.DOWN);
      rest[i] = share.subtract(exact[i]);
      given = given.add(exact[i]);
    }
    int left = d.subtract(given).divide(unit, 0, RoundingMode.HALF_UP).intValue();
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    java.util.Arrays.sort(
        order,
        (a, b) -> {
          int c = rest[b].compareTo(rest[a]);
          return c != 0 ? c : Integer.compare(b, a);
        });
    for (int k = 0; k < left && k < n; k++) exact[order[k]] = exact[order[k]].add(unit);
    for (int i = 0; i < n; i++) out.add(exact[i]);
    return out;
  }

  /**
   * What a return of part of a line is worth.
   *
   * @param value what the customer is refunded: the line's net and VAT for the quantity, less the
   *     quantity's share of the order's discount
   * @param net the net figure the fiscal and commission reports read: the net price for the
   *     quantity less that same share, which is what the sale recognised as revenue for it
   */
  public record Worth(BigDecimal value, BigDecimal net) {}

  /**
   * What returning {@code qty} of a line is worth, given what already came back from it. Every
   * figure is read from the line's running total, {@code amount(k) = round(amount x k / sold)}, and
   * a return is the difference between the running totals after and before it. Returns in any
   * number of parts therefore add up to exactly what the line was worth in one, and the last part
   * takes the rounding remainder, so no refund adds up to more than was paid.
   *
   * @param lineNet the whole line's net price as sold
   * @param lineVat the VAT on the whole line, or null when none was recorded (worth its net price)
   * @param soldQty the quantity the line was sold with
   * @param discountShare the line's share of the order's discount ({@link #discountShares})
   * @param alreadyReturned what has come back from the line before
   * @param qty what comes back now
   * @param scale the currency's minor units
   * @return the worth of this return, never negative
   */
  public static Worth worth(
      BigDecimal lineNet,
      BigDecimal lineVat,
      BigDecimal soldQty,
      BigDecimal discountShare,
      BigDecimal alreadyReturned,
      BigDecimal qty,
      int scale) {
    if (soldQty == null || soldQty.signum() <= 0)
      return new Worth(BigDecimal.ZERO.setScale(scale), BigDecimal.ZERO.setScale(scale));
    BigDecimal before = alreadyReturned.max(BigDecimal.ZERO).min(soldQty);
    BigDecimal after = before.add(qty).min(soldQty);
    BigDecimal vat = lineVat == null ? BigDecimal.ZERO : lineVat;
    BigDecimal share = discountShare == null ? BigDecimal.ZERO : discountShare;
    BigDecimal value =
        running(lineNet.add(vat), soldQty, after, scale)
            .subtract(running(share, soldQty, after, scale))
            .subtract(
                running(lineNet.add(vat), soldQty, before, scale)
                    .subtract(running(share, soldQty, before, scale)));
    BigDecimal net =
        running(lineNet, soldQty, after, scale)
            .subtract(running(share, soldQty, after, scale))
            .subtract(
                running(lineNet, soldQty, before, scale)
                    .subtract(running(share, soldQty, before, scale)));
    return new Worth(value.max(BigDecimal.ZERO), net.max(BigDecimal.ZERO));
  }

  private static BigDecimal running(
      BigDecimal amount, BigDecimal soldQty, BigDecimal k, int scale) {
    return amount.multiply(k).divide(soldQty, scale, RoundingMode.HALF_UP);
  }

  /**
   * A no-receipt line at the current price.
   *
   * @param unitNet the current price of one, net of VAT
   * @param unitVat the VAT on one
   * @param qty how many come back
   * @param scale the currency's minor units ({@code Fx.minorUnits}); the line's VAT and value are
   *     rounded to it, half up. The unit price keeps four decimals, as a unit price may
   *     legitimately be finer than the currency (SJ-D25)
   * @return the line: unit price with VAT, the VAT in the line, and the line's value
   */
  public static Line priceLine(BigDecimal unitNet, BigDecimal unitVat, BigDecimal qty, int scale) {
    BigDecimal vatEach = unitVat == null ? BigDecimal.ZERO : unitVat;
    BigDecimal grossEach = unitNet.add(vatEach);
    return new Line(
        grossEach.setScale(4, RoundingMode.HALF_UP),
        vatEach.multiply(qty).setScale(scale, RoundingMode.HALF_UP),
        grossEach.multiply(qty).setScale(scale, RoundingMode.HALF_UP));
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
