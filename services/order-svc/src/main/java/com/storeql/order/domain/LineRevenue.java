package com.storeql.order.domain;

import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each line of an order earned, net of VAT and of the order's discounts (19.7).
 *
 * <p>Revenue lives here and cost lives in inventory-svc, and neither may read the other's tables,
 * so a gross margin needs the revenue to travel with the stock it paid for. {@code OrderFulfilled}
 * carries it per line. An order's total is its subtotal less the staff and promotion discounts plus
 * VAT, and the lines add up to the subtotal; a line therefore earned its share of the subtotal
 * after the discounts, which is what this returns — per unit of the variant, so a line handed over
 * in parts earns in proportion to what was handed over each time.
 */
public final class LineRevenue {

  private LineRevenue() {}

  /**
   * The net revenue per unit of each variant on the order.
   *
   * @return variant id to net price per unit, unrounded; empty when the order has no value
   */
  public static Map<UUID, BigDecimal> unitNet(Order order, List<OrderItem> lines) {
    Map<UUID, BigDecimal> value = new HashMap<>();
    Map<UUID, BigDecimal> qty = new HashMap<>();
    BigDecimal subtotal = BigDecimal.ZERO;
    for (OrderItem l : lines) {
      BigDecimal lineTotal = l.lineTotal() == null ? BigDecimal.ZERO : l.lineTotal();
      value.merge(l.variantId(), lineTotal, BigDecimal::add);
      qty.merge(l.variantId(), l.qty(), BigDecimal::add);
      subtotal = subtotal.add(lineTotal);
    }
    Map<UUID, BigDecimal> out = new HashMap<>();
    if (subtotal.signum() <= 0) return out;
    // At shelf prices a line's value is already its net after every discount (the order keeps the
    // discounts only as what was given), so none is taken off again.
    BigDecimal discounts =
        order.taxInclusive()
            ? BigDecimal.ZERO
            : nz(order.discountAmount()).add(nz(order.promotionDiscount()));
    BigDecimal netOfDiscounts = subtotal.subtract(discounts).max(BigDecimal.ZERO);
    BigDecimal factor = netOfDiscounts.divide(subtotal, 10, RoundingMode.HALF_UP);
    for (var e : value.entrySet()) {
      BigDecimal q = qty.get(e.getKey());
      if (q == null || q.signum() <= 0) continue;
      out.put(e.getKey(), e.getValue().multiply(factor).divide(q, 10, RoundingMode.HALF_UP));
    }
    return out;
  }

  /** The net revenue for {@code qty} of a variant, rounded to the currency's minor unit. */
  public static BigDecimal forQty(
      Map<UUID, BigDecimal> unitNet, UUID variantId, BigDecimal qty, int scale) {
    BigDecimal unit = unitNet.get(variantId);
    if (unit == null || qty == null) return null;
    return unit.multiply(qty).setScale(scale, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
