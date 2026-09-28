package com.storeql.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A priced order divided into its parts (order orchestration), pure. Each part takes its own lines
 * — a line split across stores shared by quantity — with their amounts and VAT; the order's tax,
 * discount and whole-basket promotion are shared by the parts (the tax from the lines' own VAT when
 * they carry it, else by subtotal); the last part takes the rounding, so the parts add up to
 * exactly what the shopper was quoted.
 */
public final class OrderSplit {

  private static final int MONEY = 2;

  private OrderSplit() {}

  /**
   * A priced line of the whole order.
   *
   * @param vat the line's VAT as quoted, or null when the order was placed without it
   */
  public record Line(UUID variantId, BigDecimal qty, BigDecimal lineTotal, BigDecimal vat) {}

  /** The part of a line a store fills. */
  public record LinePart(
      int lineIndex, UUID variantId, BigDecimal qty, BigDecimal lineTotal, BigDecimal vat) {}

  /** One store's part of the order. */
  public record Part(
      UUID storeId,
      List<LinePart> lines,
      BigDecimal subtotal,
      BigDecimal tax,
      BigDecimal discount,
      BigDecimal promotion) {
    public Part {
      lines = List.copyOf(lines);
    }
  }

  /**
   * Divides the order.
   *
   * @param plan store → product → quantity, in the order the parts are to be read
   * @param tax the order's tax as quoted
   * @param discount the order's discount
   * @param promotion the whole-basket promotion
   */
  public static List<Part> split(
      List<Line> lines,
      Map<UUID, Map<UUID, BigDecimal>> plan,
      BigDecimal tax,
      BigDecimal discount,
      BigDecimal promotion) {
    // What of each line each store takes, the last taker of a line getting what is left of it.
    Map<Integer, BigDecimal> qtyLeft = new HashMap<>();
    Map<Integer, BigDecimal> totalLeft = new HashMap<>();
    Map<Integer, BigDecimal> vatLeft = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      qtyLeft.put(i, lines.get(i).qty());
      totalLeft.put(i, lines.get(i).lineTotal());
      vatLeft.put(i, lines.get(i).vat());
    }
    List<UUID> stores = new ArrayList<>(plan.keySet());
    List<List<LinePart>> byPart = new ArrayList<>();
    for (UUID store : stores) {
      List<LinePart> parts = new ArrayList<>();
      // In the order's own line order, so a part lists its lines as the shopper added them.
      Map<UUID, BigDecimal> wants = new HashMap<>(plan.get(store));
      for (int i = 0; i < lines.size(); i++) {
        Line line = lines.get(i);
        BigDecimal need = wants.getOrDefault(line.variantId(), BigDecimal.ZERO);
        BigDecimal has = qtyLeft.get(i);
        if (need.signum() > 0 && has.signum() > 0) {
          BigDecimal take = has.min(need);
          boolean last = take.compareTo(has) == 0;
          BigDecimal total =
              last ? totalLeft.get(i) : share(line.lineTotal(), take, line.qty(), MONEY);
          BigDecimal vat =
              line.vat() == null
                  ? null
                  : last ? vatLeft.get(i) : share(line.vat(), take, line.qty(), MONEY);
          parts.add(new LinePart(i, line.variantId(), take, total, vat));
          qtyLeft.put(i, has.subtract(take));
          totalLeft.put(i, totalLeft.get(i).subtract(total));
          if (vat != null) vatLeft.put(i, vatLeft.get(i).subtract(vat));
          wants.put(line.variantId(), need.subtract(take));
        }
      }
      byPart.add(parts);
    }
    BigDecimal whole = lines.stream().map(Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    boolean lineVat = lines.stream().allMatch(l -> l.vat() != null);
    List<Part> out = new ArrayList<>();
    BigDecimal taxGiven = BigDecimal.ZERO;
    BigDecimal discGiven = BigDecimal.ZERO;
    BigDecimal promoGiven = BigDecimal.ZERO;
    for (int p = 0; p < stores.size(); p++) {
      List<LinePart> parts = byPart.get(p);
      BigDecimal subtotal =
          parts.stream().map(LinePart::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
      boolean last = p == stores.size() - 1;
      BigDecimal t;
      BigDecimal dsc;
      BigDecimal prm;
      if (last) {
        t = zero(tax).subtract(taxGiven);
        dsc = zero(discount).subtract(discGiven);
        prm = zero(promotion).subtract(promoGiven);
      } else {
        t =
            lineVat
                ? parts.stream()
                    .map(LinePart::vat)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(MONEY, RoundingMode.HALF_UP)
                : share(zero(tax), subtotal, whole, MONEY);
        dsc = share(zero(discount), subtotal, whole, MONEY);
        prm = share(zero(promotion), subtotal, whole, MONEY);
      }
      taxGiven = taxGiven.add(t);
      discGiven = discGiven.add(dsc);
      promoGiven = promoGiven.add(prm);
      out.add(new Part(stores.get(p), parts, subtotal, t, dsc, prm));
    }
    return out;
  }

  /**
   * An amount shared by weights, to the cent, the last part with any weight taking what is left: a
   * line promotion shared by the quantities of a line split across parts.
   */
  public static List<BigDecimal> share(BigDecimal amount, List<BigDecimal> weights) {
    BigDecimal whole = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    int last = -1;
    for (int i = 0; i < weights.size(); i++) if (weights.get(i).signum() > 0) last = i;
    List<BigDecimal> out = new ArrayList<>();
    BigDecimal given = BigDecimal.ZERO;
    for (int i = 0; i < weights.size(); i++) {
      BigDecimal s;
      if (weights.get(i).signum() <= 0) s = BigDecimal.ZERO;
      else if (i == last) s = amount.subtract(given);
      else s = share(amount, weights.get(i), whole, MONEY);
      given = given.add(s);
      out.add(s);
    }
    return out;
  }

  private static BigDecimal share(BigDecimal amount, BigDecimal part, BigDecimal whole, int scale) {
    if (whole.signum() == 0) return BigDecimal.ZERO.setScale(scale);
    return amount.multiply(part).divide(whole, scale, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
