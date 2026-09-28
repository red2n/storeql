package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.OrderProposal.Nothing;
import com.storeql.purchase.domain.OrderProposal.Order;
import com.storeql.purchase.domain.OrderProposal.Plan;
import com.storeql.purchase.domain.OrderProposal.Position;
import com.storeql.purchase.domain.OrderProposal.Result;
import com.storeql.purchase.domain.OrderProposal.Skipped;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order proposal's policy (06.x) as pure arithmetic: a reorder-point (s, Q) rule — order when
 * the stock position (on hand plus on order) has fallen to the reorder point, by the economic order
 * quantity when there is one and otherwise up to cover, then bent to the supplier's order modifiers
 * — and the words it gives a buyer for each line.
 */
class OrderProposalTest {

  private static final UUID V = Ids.newId();

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  private static Plan plan(String rop, String eoq, String min, String max, String lot) {
    return new Plan(
        V,
        rop == null ? null : d(rop),
        eoq == null ? null : d(eoq),
        min == null ? null : d(min),
        max == null ? null : d(max),
        lot == null ? null : d(lot),
        d("4"),
        7);
  }

  @Test
  @DisplayName("Stock above the reorder point wants nothing")
  void aboveTheReorderPointNothing() {
    Result r =
        OrderProposal.propose(
            plan("28", "45", null, null, null), new Position(d("30"), d("0"), null, null), 28);
    assertThat(r, instanceOf(Nothing.class));
  }

  @Test
  @DisplayName(
      "At or below the reorder point, the economic order quantity is ordered, and the line says why")
  void atTheReorderPointOrderTheEoq() {
    Result r =
        OrderProposal.propose(
            plan("28", "45", null, null, null), new Position(d("3"), d("10"), null, null), 28);
    Order o = (Order) r;
    assertThat(o.qty(), is(d("45.000")));
    assertThat(o.reason(), containsString("on hand 3 + on order 10 = 13"));
    assertThat(o.reason(), containsString("reorder point 28"));
    assertThat(o.reason(), containsString("EOQ 45"));
  }

  @Test
  @DisplayName(
      "With no EOQ the order covers the forecast: back to the reorder point plus what the cover period expects")
  void withoutAnEoqOrderUpToCover() {
    Result r =
        OrderProposal.propose(
            plan("28", null, null, null, null), new Position(d("3"), d("0"), d("40"), null), 28);
    Order o = (Order) r;
    assertThat(o.qty(), is(d("65.000")));
    assertThat(o.reason(), containsString("forecast 40 over 28 days"));
  }

  @Test
  @DisplayName("With no forecast either, the average daily demand over the cover period stands in")
  void withoutAForecastTheAverageStandsIn() {
    Result r =
        OrderProposal.propose(
            plan("28", null, null, null, null), new Position(d("3"), d("0"), null, null), 28);
    Order o = (Order) r;
    assertThat(o.qty(), is(d("137.000")));
    assertThat(o.reason(), containsString("4/day over 28 days"));
  }

  @Test
  @DisplayName(
      "The supplier's order modifiers bend the quantity: a minimum raises it, a lot rounds it up, a maximum caps it to the largest lot that fits")
  void orderModifiersBendTheQuantity() {
    Order raised =
        (Order)
            OrderProposal.propose(
                plan("28", "45", "50", null, null), new Position(d("3"), d("0"), null, null), 28);
    assertThat(raised.qty(), is(d("50.000")));
    assertThat(raised.reason(), containsString("minimum order 50"));

    Order rounded =
        (Order)
            OrderProposal.propose(
                plan("28", "45", "50", null, "12"), new Position(d("3"), d("0"), null, null), 28);
    assertThat(rounded.qty(), is(d("60.000")));
    assertThat(rounded.reason(), containsString("lots of 12"));

    Order capped =
        (Order)
            OrderProposal.propose(
                plan("28", "45", "50", "55", "12"), new Position(d("3"), d("0"), null, null), 28);
    assertThat(capped.qty(), is(d("48.000")));
    assertThat(capped.reason(), containsString("maximum order 55"));
  }

  @Test
  @DisplayName("An order never leaves the position below the reorder point, however small the EOQ")
  void anOrderAtLeastRestoresTheReorderPoint() {
    Order o =
        (Order)
            OrderProposal.propose(
                plan("100", "5", null, null, null), new Position(d("10"), d("0"), null, null), 28);
    assertThat(o.qty(), is(d("90.000")));
  }

  @Test
  @DisplayName("An item with no reorder point computed is skipped, and says so")
  void noReorderPointIsSkipped() {
    Result r =
        OrderProposal.propose(
            plan(null, "45", null, null, null), new Position(d("0"), d("0"), null, null), 28);
    assertThat(r, instanceOf(Skipped.class));
    assertThat(((Skipped) r).reason(), containsString("no reorder point"));
  }

  @Test
  @DisplayName(
      "A fresh item is ordered for no longer than it lives: the cover is capped to the shelf life, and the line says so")
  void aShortShelfLifeCapsTheCover() {
    // Five days of forecast (the caller scaled it to the five-day shelf life): 5 - 1 + 4 = 8.
    Order o =
        (Order)
            OrderProposal.propose(
                plan("5", null, null, null, null), new Position(d("1"), d("0"), d("4"), 5), 28);
    assertThat(o.qty(), is(d("8.000")));
    assertThat(o.reason(), containsString("forecast 4 over 5 days"));
    assertThat(o.reason(), containsString("capped to the 5-day shelf life"));
  }

  @Test
  @DisplayName("An EOQ that would outlive the item is cut to what sells within its shelf life")
  void anEoqIsCutToWhatSellsWithinTheShelfLife() {
    Order o =
        (Order)
            OrderProposal.propose(
                plan("5", "60", null, null, null), new Position(d("1"), d("0"), d("4"), 5), 28);
    assertThat(
        "back to the reorder point plus five days of sales, not sixty", o.qty(), is(d("8.000")));
    assertThat(o.reason(), containsString("EOQ 60 cut to what sells within the 5-day shelf life"));
  }

  @Test
  @DisplayName("A shelf life longer than the cover changes nothing")
  void aLongShelfLifeChangesNothing() {
    Order o =
        (Order)
            OrderProposal.propose(
                plan("28", "45", null, null, null), new Position(d("3"), d("0"), null, 90), 28);
    assertThat(o.qty(), is(d("45.000")));
    assertThat(o.reason(), org.hamcrest.Matchers.not(containsString("shelf life")));
  }
}
