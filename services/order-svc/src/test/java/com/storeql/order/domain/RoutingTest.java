package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which store fills which line of a delivery order, pure: the delivery-area store first; what it
 * cannot hold from the nearest store that holds all of the rest, else the fewest stores; a line
 * split across stores only when no one store holds it. Written before the code.
 */
class RoutingTest {

  private static final UUID AREA = Ids.newId();
  private static final UUID NEAR = Ids.newId();
  private static final UUID FAR = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();
  private static final UUID PLUMS = Ids.newId();

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static Map<UUID, BigDecimal> wants(Object... variantQty) {
    Map<UUID, BigDecimal> m = new LinkedHashMap<>();
    for (int i = 0; i < variantQty.length; i += 2)
      m.put((UUID) variantQty[i], d((String) variantQty[i + 1]));
    return m;
  }

  private static Routing.Candidate store(UUID id, Double km, Object... variantQty) {
    return new Routing.Candidate(id, km, wants(variantQty));
  }

  @Test
  void anAreaStoreThatHoldsEverythingFillsTheOrderAlone() {
    var r =
        Routing.route(
            wants(APPLES, "3", PEARS, "2"),
            AREA,
            List.of(
                store(AREA, 0.0, APPLES, "5", PEARS, "2"),
                store(NEAR, 5.0, APPLES, "9", PEARS, "9")));
    assertThat(r, instanceOf(Routing.Plan.class));
    var plan = (Routing.Plan) r;
    assertThat(plan.stores(), contains(AREA));
    assertThat(plan.byStore().get(AREA).get(PEARS), comparesEqualTo(d("2")));
  }

  @Test
  void whatTheAreaStoreCannotHoldGoesToTheNearestStoreThatHoldsAllOfTheRest() {
    // The area store has apples, not pears or plums; the near store has pears but no plums; the
    // far store has both: the rest goes, whole, to the far store — one part more, not two.
    var plan =
        (Routing.Plan)
            Routing.route(
                wants(APPLES, "3", PEARS, "2", PLUMS, "1"),
                AREA,
                List.of(
                    store(AREA, 0.0, APPLES, "5"),
                    store(FAR, 30.0, PEARS, "4", PLUMS, "4"),
                    store(NEAR, 5.0, PEARS, "4")));
    assertThat(plan.stores(), contains(AREA, FAR));
    assertThat(plan.byStore().get(FAR).get(PLUMS), comparesEqualTo(d("1")));
    // And when the near store holds all of the rest, the near store wins over the far.
    var nearer =
        (Routing.Plan)
            Routing.route(
                wants(APPLES, "3", PEARS, "2"),
                AREA,
                List.of(
                    store(AREA, 0.0, APPLES, "5"),
                    store(FAR, 30.0, PEARS, "4"),
                    store(NEAR, 5.0, PEARS, "4")));
    assertThat(nearer.stores(), contains(AREA, NEAR));
  }

  @Test
  void withNoOneStoreForTheRestTheFewestStoresAreUsed() {
    // Pears only at the near store, plums only at the far one.
    var plan =
        (Routing.Plan)
            Routing.route(
                wants(APPLES, "1", PEARS, "1", PLUMS, "1"),
                AREA,
                List.of(
                    store(AREA, 0.0, APPLES, "1"),
                    store(NEAR, 5.0, PEARS, "1"),
                    store(FAR, 30.0, PLUMS, "1")));
    assertThat(plan.stores(), contains(AREA, NEAR, FAR));
  }

  @Test
  void aLineIsSplitOnlyWhenNoOneStoreHoldsIt() {
    // Six apples wanted; the area store holds four and the near store three — nobody holds six.
    var plan =
        (Routing.Plan)
            Routing.route(
                wants(APPLES, "6"),
                AREA,
                List.of(store(AREA, 0.0, APPLES, "4"), store(NEAR, 5.0, APPLES, "3")));
    assertThat(plan.stores(), contains(AREA, NEAR));
    assertThat(plan.byStore().get(AREA).get(APPLES), comparesEqualTo(d("4")));
    assertThat(plan.byStore().get(NEAR).get(APPLES), comparesEqualTo(d("2")));
    // But a store that holds all six takes the line whole.
    var whole =
        (Routing.Plan)
            Routing.route(
                wants(APPLES, "6"),
                AREA,
                List.of(store(AREA, 0.0, APPLES, "4"), store(FAR, 30.0, APPLES, "6")));
    assertThat(whole.stores(), contains(FAR));
  }

  @Test
  void nothingFitsIsUnfulfillableAndAStoreWithNoCoordinatesComesLast() {
    var none =
        Routing.route(
            wants(APPLES, "9"),
            AREA,
            List.of(store(AREA, 0.0, APPLES, "4"), store(NEAR, 5.0, APPLES, "3")));
    assertThat(none, instanceOf(Routing.Unfulfillable.class));
    assertThat(((Routing.Unfulfillable) none).shortBy().get(APPLES), comparesEqualTo(d("2")));
    // Two stores hold the rest; the one with no coordinates is not preferred.
    var plan =
        (Routing.Plan)
            Routing.route(
                wants(APPLES, "1", PEARS, "1"),
                AREA,
                List.of(
                    store(AREA, 0.0, APPLES, "1"),
                    store(NEAR, null, PEARS, "1"),
                    store(FAR, 30.0, PEARS, "1")));
    assertThat(plan.stores(), contains(AREA, FAR));
    // The area store's distance is nothing to itself; nothing on hand anywhere is unfulfillable.
    assertThat(
        Routing.route(wants(APPLES, "1"), AREA, List.of()),
        instanceOf(Routing.Unfulfillable.class));
    assertThat(Routing.km(53.8, -1.55, 53.96, -1.08) > 30, is(true));
  }
}
