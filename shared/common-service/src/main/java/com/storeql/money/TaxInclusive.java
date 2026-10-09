package com.storeql.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Money arithmetic for a tax-inclusive price (intent/vat-inclusive-pricing.md).
 *
 * <p>In a shop that sells at VAT-inclusive shelf prices the price on the shelf is the truth: it is
 * what is charged, shown, receipted and refunded. The VAT in it is derived from it, never the other
 * way round, because deriving a gross from a two-decimal net cannot reach about one price in six
 * (1.29 at 20% is one). VAT is then carried as an amount from the quote to the ledger and is never
 * recomputed from a net.
 *
 * <p>Every method is pure, works in {@link BigDecimal} only, rounds once and half up (ties away
 * from zero, so a refund is the mirror image of the sale), and takes the currency's minor-unit
 * {@code scale} from {@link com.storeql.service.Fx#minorUnits(String)}: never a literal 2. A rate
 * is a fraction ({@code 0.20} for 20%).
 */
public final class TaxInclusive {

  private TaxInclusive() {}

  /**
   * The VAT inside a gross amount: {@code gross × rate / (1 + rate)} as one division, rounded half
   * up to the currency's minor units. A zero rate or a zero amount has no VAT.
   *
   * @param gross what was charged, VAT included; at most {@code scale} decimals
   * @param rate the VAT rate as a fraction
   * @param scale the currency's minor units
   * @return the VAT, at {@code scale}
   * @throws IllegalArgumentException when {@code gross} is finer than the currency allows: money
   *     typed finer than its minor unit is refused, never rounded
   */
  public static BigDecimal vatInside(BigDecimal gross, BigDecimal rate, int scale) {
    requireWithinScale(gross, scale);
    if (rate.signum() == 0 || gross.signum() == 0) {
      return BigDecimal.ZERO.setScale(scale);
    }
    return gross.multiply(rate).divide(BigDecimal.ONE.add(rate), scale, RoundingMode.HALF_UP);
  }

  /**
   * What is left of a gross amount once its VAT is taken out.
   *
   * @return {@code gross − vatInside(gross, rate, scale)}, at {@code scale}
   */
  public static BigDecimal netOf(BigDecimal gross, BigDecimal rate, int scale) {
    return gross.setScale(scale, RoundingMode.UNNECESSARY).subtract(vatInside(gross, rate, scale));
  }

  /**
   * Shares an amount (a basket discount, a staff discount) across lines in proportion to their
   * gross value, to the minor unit, so the shares add up to the amount exactly: each line gets the
   * floor of its exact share and the leftover minor units go one each to the lines with the largest
   * remainders (a larger gross, then an earlier line, breaks a tie). No line is given more than it
   * costs.
   *
   * @param amount what is to be shared, from zero to the sum of the grosses
   * @param grosses each line's gross value, none negative
   * @param scale the currency's minor units
   * @return each line's share, at {@code scale}
   * @throws IllegalArgumentException for a negative amount or one larger than the lines together
   */
  public static List<BigDecimal> shareByGross(
      BigDecimal amount, List<BigDecimal> grosses, int scale) {
    requireWithinScale(amount, scale);
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal g : grosses) {
      requireWithinScale(g, scale);
      if (g.signum() < 0) {
        throw new IllegalArgumentException("a line's gross value is not negative: " + g);
      }
      total = total.add(g);
    }
    if (amount.signum() < 0 || amount.compareTo(total) > 0) {
      throw new IllegalArgumentException(
          "a share of " + amount + " cannot be taken from lines worth " + total);
    }
    List<BigDecimal> floors = new ArrayList<>();
    List<BigDecimal> remainders = new ArrayList<>();
    BigDecimal given = BigDecimal.ZERO;
    for (BigDecimal g : grosses) {
      if (total.signum() == 0) {
        floors.add(BigDecimal.ZERO.setScale(scale));
        remainders.add(BigDecimal.ZERO);
        continue;
      }
      BigDecimal exact = amount.multiply(g).divide(total, scale + 12, RoundingMode.HALF_UP);
      BigDecimal floor = exact.setScale(scale, RoundingMode.DOWN);
      floors.add(floor);
      remainders.add(exact.subtract(floor));
      given = given.add(floor);
    }
    BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
    long left = amount.subtract(given).movePointRight(scale).longValueExact();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < grosses.size(); i++) {
      order.add(i);
    }
    order.sort(
        Comparator.<Integer, BigDecimal>comparing(remainders::get)
            .reversed()
            .thenComparing(Comparator.<Integer, BigDecimal>comparing(grosses::get).reversed())
            .thenComparing(Comparator.naturalOrder()));
    List<BigDecimal> shares = new ArrayList<>(floors);
    for (int k = 0; k < left; k++) {
      int i = order.get(k);
      shares.set(i, shares.get(i).add(unit));
    }
    return shares;
  }

  /**
   * A running total for pro-rata amounts: {@code amount × k / n}, rounded half up. The part for the
   * k-th of n units is {@code running(k) − running(k − 1)}, so the parts of a refund add up to the
   * whole whatever the split and the last one takes the rounding.
   *
   * @param amount the whole (what was paid for n units)
   * @param k how many units so far, from 0 to n
   * @param n how many units there were
   * @param scale the currency's minor units
   * @return the amount for the first k units, at {@code scale}
   */
  public static BigDecimal running(BigDecimal amount, long k, long n, int scale) {
    if (n <= 0 || k <= 0) {
      return BigDecimal.ZERO.setScale(scale);
    }
    return amount
        .multiply(BigDecimal.valueOf(k))
        .divide(BigDecimal.valueOf(n), scale, RoundingMode.HALF_UP);
  }

  private static void requireWithinScale(BigDecimal amount, int scale) {
    if (amount.stripTrailingZeros().scale() > scale) {
      throw new IllegalArgumentException(
          "an amount of " + amount.toPlainString() + " is finer than the currency's minor unit");
    }
  }
}
