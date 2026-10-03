package com.storeql.order.domain;

import com.storeql.order.domain.Domain.SpecialOrder;
import com.storeql.order.domain.Domain.SpecialOrderItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Whether a request to make a special order is the very request an order was already made from, so
 * that a retry under the same Idempotency-Key answers the first order (golden rule 11) and the same
 * key for a different request is refused rather than quietly answered with something else.
 *
 * <p>A special order has no target to compare, as a void has its order, so what the key stands for
 * is the request itself: the store, the customer and how to reach them, where and when it goes, the
 * note, and the lines. It is judged as the tables keep it, not as typed: the request is built into
 * the same order and lines the insert would write, and a price is compared at the minor units of
 * the order's currency (a typed price finer than the currency is refused before it gets here), a
 * quantity at the decimal places its column holds — so {@code 5.0} and {@code 5.00} are one
 * sterling price, a dinar price keeps its third decimal, and a yen price is whole yen however a
 * column once wrote it. The order of the lines does not matter; two lines of the same product are
 * two lines whichever way they were sent.
 *
 * <p>What the business's currency is, and the status the order has reached since, are not part of
 * it: the first is the tenant's, never the caller's, and the second is what the retry is told.
 */
public final class SpecialOrderReplay {

  /** Decimal places of {@code special_order_items.qty}, {@code NUMERIC(18,3)}. */
  static final int QTY_SCALE = 3;

  private SpecialOrderReplay() {}

  /** One line as the table keeps it: the same figures compare equal whatever their scale. */
  private record Line(UUID variantId, BigDecimal qty, BigDecimal unitPrice, String notes) {}

  /**
   * @param stored the special order already made under the key
   * @param storedLines its lines
   * @param wanted the order the request would make now, built exactly as it would be inserted
   * @param wantedLines its lines
   * @return whether the request asks for what was already made
   */
  public static boolean isSameRequest(
      SpecialOrder stored,
      List<SpecialOrderItem> storedLines,
      SpecialOrder wanted,
      List<SpecialOrderItem> wantedLines) {
    return stored.storeId().equals(wanted.storeId())
        && Objects.equals(stored.customerId(), wanted.customerId())
        && Objects.equals(stored.customerName(), wanted.customerName())
        && Objects.equals(stored.customerPhone(), wanted.customerPhone())
        && Objects.equals(stored.customerEmail(), wanted.customerEmail())
        && Objects.equals(stored.deliveryAddress(), wanted.deliveryAddress())
        && Objects.equals(stored.requestedDeliveryDate(), wanted.requestedDeliveryDate())
        && Objects.equals(stored.notes(), wanted.notes())
        && linesOf(storedLines, minorUnits(stored.currency()))
            .equals(linesOf(wantedLines, minorUnits(stored.currency())));
  }

  /** The currency's ISO 4217 minor units: 2 for the pound, 0 for the yen, 3 for the dinar. */
  private static int minorUnits(String currency) {
    return java.util.Currency.getInstance(currency).getDefaultFractionDigits();
  }

  /** The lines as a count of each distinct line, so neither their order nor their ids matter. */
  private static Map<Line, Integer> linesOf(List<SpecialOrderItem> items, int moneyScale) {
    Map<Line, Integer> counted = new HashMap<>();
    for (SpecialOrderItem item : items) {
      Line line =
          new Line(
              item.variantId(),
              item.qty().setScale(QTY_SCALE, RoundingMode.HALF_UP),
              item.unitPrice().setScale(moneyScale, RoundingMode.HALF_UP),
              item.notes());
      counted.merge(line, 1, Integer::sum);
    }
    return counted;
  }
}
