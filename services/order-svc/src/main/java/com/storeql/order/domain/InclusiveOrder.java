package com.storeql.order.domain;

import com.storeql.money.TaxInclusive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The money of an order sold at shelf prices, VAT inside (intent/vat-inclusive-pricing.md), pure.
 *
 * <p>The quote prices each line to what it finally costs once every automatic offer has been taken
 * off (the line's gross). The only discount left for the order to apply is a member of staff's, and
 * it comes off the gross: it is shared across the lines by their gross value to the minor unit (the
 * shares add up to the discount exactly), each line's VAT is worked again from what it finally
 * cost, and the net is what is left. So a staff discount lowers the VAT, as it does in a shop that
 * sells at shelf prices; the identities then hold on every order:
 *
 * <pre>
 *   paidGross = net + vat                    (per line)
 *   total     = Σ paidGross + deposits + cards
 *   subtotal  = Σ net,  tax = Σ vat,  so  subtotal + tax = Σ paidGross
 * </pre>
 *
 * <p>The staff and promotion discounts stay on the order as what was given, for the audit and the
 * discount reports; they are already inside the lines and are never subtracted again.
 */
public final class InclusiveOrder {

  private InclusiveOrder() {}

  /**
   * A line as quoted.
   *
   * @param gross what the line costs after every automatic offer, VAT included
   * @param vatRate the rate as a fraction ({@code 0.20}); null or zero when none applies
   */
  public record LineIn(BigDecimal gross, BigDecimal vatRate) {}

  /**
   * A line as the order keeps it.
   *
   * @param paidGross what the customer pays for the line, after the staff discount
   * @param vat the VAT inside {@code paidGross}
   * @param net what is left of it: the line's value, the revenue it earns
   */
  public record LineOut(BigDecimal paidGross, BigDecimal vat, BigDecimal net) {}

  /** The priced order. */
  public record Priced(
      List<LineOut> lines, BigDecimal subtotal, BigDecimal tax, BigDecimal paidGross) {
    public Priced {
      lines = List.copyOf(lines);
    }
  }

  /**
   * Prices the order's lines.
   *
   * @param lines the lines in order, none negative
   * @param staffDiscount the staff discount, from zero to the lines' gross together
   * @param scale the currency's minor units ({@code Fx.minorUnits})
   * @return each line's paid gross, VAT and net, and their sums
   * @throws IllegalArgumentException for a discount larger than the lines together or negative
   */
  public static Priced price(List<LineIn> lines, BigDecimal staffDiscount, int scale) {
    List<BigDecimal> grosses = lines.stream().map(LineIn::gross).toList();
    BigDecimal discount = staffDiscount == null ? BigDecimal.ZERO.setScale(scale) : staffDiscount;
    List<BigDecimal> shares = TaxInclusive.shareByGross(discount, grosses, scale);
    List<LineOut> out = new ArrayList<>(lines.size());
    BigDecimal subtotal = BigDecimal.ZERO.setScale(scale);
    BigDecimal tax = BigDecimal.ZERO.setScale(scale);
    BigDecimal paid = BigDecimal.ZERO.setScale(scale);
    for (int i = 0; i < lines.size(); i++) {
      LineIn in = lines.get(i);
      BigDecimal paidGross = in.gross().subtract(shares.get(i)).setScale(scale);
      BigDecimal rate = in.vatRate() == null ? BigDecimal.ZERO : in.vatRate();
      BigDecimal vat = TaxInclusive.vatInside(paidGross, rate, scale);
      BigDecimal net = paidGross.subtract(vat);
      out.add(new LineOut(paidGross, vat, net));
      subtotal = subtotal.add(net);
      tax = tax.add(vat);
      paid = paid.add(paidGross);
    }
    return new Priced(out, subtotal, tax, paid);
  }
}
