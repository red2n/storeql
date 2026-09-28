package com.storeql.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a substitute is charged (substitutions for out-of-stock online lines), pure: the lower of
 * its own price at the store and the original line's price, gross — the shopper never pays more
 * than they did — with the substitute's own VAT within it. A cheaper substitute is charged its own
 * price; a dearer one the original's, its net and VAT scaled to fit.
 */
public final class SubstitutePrice {

  private SubstitutePrice() {}

  /**
   * A substitute line's money.
   *
   * @param lineNet what the line is charged before VAT
   * @param lineVat the VAT within the charge
   * @param unitPrice the net price per unit, rounded, for the line's display
   * @param capped whether the original's price held the substitute's down
   */
  public record Charge(
      BigDecimal lineNet, BigDecimal lineVat, BigDecimal unitPrice, boolean capped) {
    /** The line's charge, gross. */
    public BigDecimal gross() {
      return lineNet.add(lineVat);
    }
  }

  /**
   * Charges {@code qty} of a substitute quoted at {@code quotedNet} + {@code quotedVat} for that
   * quantity, against an original line whose standing units are worth {@code originalGrossUnit}
   * each.
   *
   * @param vatRate the substitute's VAT rate as a fraction, or null when the quote did not say
   * @param scale the currency's minor-unit digits
   */
  public static Charge charge(
      BigDecimal originalGrossUnit,
      BigDecimal quotedNet,
      BigDecimal quotedVat,
      BigDecimal vatRate,
      BigDecimal qty,
      int scale) {
    BigDecimal net = zero(quotedNet);
    BigDecimal vat = zero(quotedVat);
    BigDecimal quotedGross = net.add(vat);
    BigDecimal capGross = originalGrossUnit.multiply(qty).setScale(scale, RoundingMode.HALF_UP);
    if (quotedGross.compareTo(capGross) <= 0) {
      return new Charge(net, vat, unit(net, qty, scale), false);
    }
    BigDecimal cappedNet;
    BigDecimal cappedVat;
    if (vatRate != null && vatRate.signum() > 0) {
      cappedNet = capGross.divide(BigDecimal.ONE.add(vatRate), scale, RoundingMode.HALF_UP);
      cappedVat = capGross.subtract(cappedNet);
    } else if (vat.signum() > 0 && quotedGross.signum() > 0) {
      // The quote's own proportion, when it gave an amount but no rate.
      cappedVat = capGross.multiply(vat).divide(quotedGross, scale, RoundingMode.HALF_UP);
      cappedNet = capGross.subtract(cappedVat);
    } else {
      cappedNet = capGross;
      cappedVat = BigDecimal.ZERO.setScale(scale);
    }
    return new Charge(cappedNet, cappedVat, unit(cappedNet, qty, scale), true);
  }

  /** A share of an amount for what stands of a line: {@code amount × after / before}, rounded. */
  public static BigDecimal share(
      BigDecimal amount, BigDecimal after, BigDecimal before, int scale) {
    if (amount == null) return null;
    if (before.signum() <= 0 || after.signum() <= 0) return BigDecimal.ZERO.setScale(scale);
    return amount.multiply(after).divide(before, scale, RoundingMode.HALF_UP);
  }

  private static BigDecimal unit(BigDecimal net, BigDecimal qty, int scale) {
    return qty.signum() <= 0 ? net : net.divide(qty, scale, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
