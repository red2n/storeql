package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LineRevenueTest {

  private static final UUID A = Ids.parse("01a090ae-611e-7a00-8000-0000000000a1");
  private static final UUID B = Ids.parse("01a090ae-611e-7a00-8000-0000000000b1");

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** An order with only the figures this rule reads; everything else left null or false. */
  private static Order order(String discount, String promotion) throws Exception {
    return order(discount, promotion, false);
  }

  private static Order order(String discount, String promotion, boolean inclusive)
      throws Exception {
    RecordComponent[] parts = Order.class.getRecordComponents();
    Object[] args = new Object[parts.length];
    Class<?>[] types = new Class<?>[parts.length];
    for (int i = 0; i < parts.length; i++) {
      types[i] = parts[i].getType();
      String name = parts[i].getName();
      if (name.equals("discountAmount")) args[i] = d(discount);
      else if (name.equals("promotionDiscount")) args[i] = d(promotion);
      else if (name.equals("taxInclusive")) args[i] = inclusive;
      else if (types[i] == boolean.class) args[i] = false;
      else if (types[i] == int.class) args[i] = 0;
      else if (types[i] == long.class) args[i] = 0L;
    }
    return Order.class.getDeclaredConstructor(types).newInstance(args);
  }

  private static OrderItem line(UUID variant, String qty, String lineTotal) throws Exception {
    RecordComponent[] parts = OrderItem.class.getRecordComponents();
    Object[] args = new Object[parts.length];
    Class<?>[] types = new Class<?>[parts.length];
    for (int i = 0; i < parts.length; i++) {
      types[i] = parts[i].getType();
      switch (parts[i].getName()) {
        case "variantId" -> args[i] = variant;
        case "qty" -> args[i] = d(qty);
        case "lineTotal" -> args[i] = d(lineTotal);
        default -> {
          if (types[i] == boolean.class) args[i] = false;
        }
      }
    }
    return OrderItem.class.getDeclaredConstructor(types).newInstance(args);
  }

  @Test
  @DisplayName("Without discounts each unit earns its own price")
  void undiscountedLinesEarnTheirPrice() throws Exception {
    var unit =
        LineRevenue.unitNet(order("0", "0"), List.of(line(A, "3", "36.00"), line(B, "1", "4.00")));
    assertEquals(0, LineRevenue.forQty(unit, A, d("3"), 2).compareTo(d("36.00")));
    assertEquals(0, LineRevenue.forQty(unit, A, d("1"), 2).compareTo(d("12.00")));
    assertEquals(0, LineRevenue.forQty(unit, B, d("1"), 2).compareTo(d("4.00")));
  }

  @Test
  @DisplayName("Staff and promotion discounts are shared across the lines by value")
  void discountsAreSharedByValue() throws Exception {
    // 40.00 of goods with 4.00 staff and 6.00 promotion discount: every line earns three quarters.
    var unit =
        LineRevenue.unitNet(
            order("4.00", "6.00"), List.of(line(A, "3", "36.00"), line(B, "1", "4.00")));
    assertEquals(0, LineRevenue.forQty(unit, A, d("3"), 2).compareTo(d("27.00")));
    assertEquals(0, LineRevenue.forQty(unit, B, d("1"), 2).compareTo(d("3.00")));
    // A variant on two lines at two prices earns its average.
    var twice =
        LineRevenue.unitNet(order("0", "0"), List.of(line(A, "1", "10.00"), line(A, "1", "20.00")));
    assertEquals(0, LineRevenue.forQty(twice, A, d("2"), 2).compareTo(d("30.00")));
    // Yen has no minor unit; a dinar has three.
    assertEquals(0, LineRevenue.forQty(twice, A, d("1"), 0).compareTo(d("15")));
    assertEquals(0, LineRevenue.forQty(twice, A, d("0.333"), 3).compareTo(d("4.995")));
  }

  @Test
  @DisplayName(
      "An order of no value, an unknown variant or a discount beyond the goods earn nothing")
  void edgesEarnNothingRatherThanGuess() throws Exception {
    assertTrue(LineRevenue.unitNet(order("0", "0"), List.of(line(A, "1", "0"))).isEmpty());
    assertTrue(LineRevenue.unitNet(order("0", "0"), List.of()).isEmpty());
    var unit = LineRevenue.unitNet(order("0", "0"), List.of(line(A, "2", "10.00")));
    assertNull(LineRevenue.forQty(unit, B, d("1"), 2));
    assertNull(LineRevenue.forQty(Map.of(), A, d("1"), 2));
    var overDiscounted = LineRevenue.unitNet(order("50.00", "0"), List.of(line(A, "1", "10.00")));
    assertEquals(0, LineRevenue.forQty(overDiscounted, A, d("1"), 2).compareTo(d("0.00")));
  }

  @Test
  @DisplayName(
      "at shelf prices the discounts are already inside the lines, so revenue is the line's net")
  void shelfPriceRevenueTakesNoDiscountOffAgain() throws Exception {
    // Two lines whose net is already after a 1.00 staff discount and a 1.33 basket offer.
    Order o = order("1.00", "1.33", true);
    List<OrderItem> lines = List.of(line(A, "1", "1.00"), line(B, "1", "9.00"));

    Map<UUID, BigDecimal> unit = LineRevenue.unitNet(o, lines);

    assertEquals(0, unit.get(A).compareTo(d("1.00")));
    assertEquals(0, unit.get(B).compareTo(d("9.00")));
  }
}
