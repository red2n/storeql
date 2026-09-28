package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Planning a wave, pure: the orders' lines allocated to batches in the picking rule's order, merged
 * into one pick line per batch that names the orders it serves, walked zone by zone; and a short
 * pick shared out to the earliest order first. Written before the code.
 */
class WavesTest {

  private static final UUID STORE = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();
  private static final UUID ZONE_A = Ids.newId();
  private static final UUID ZONE_B = Ids.newId();
  private static final UUID ORDER_1 = Ids.newId();
  private static final UUID ORDER_2 = Ids.newId();
  private static final Instant T0 = Instant.parse("2026-09-25T08:00:00Z");

  private static Waves.Stock stock(UUID batch, UUID variant, UUID zone, String qty, String lot) {
    return new Waves.Stock(batch, variant, zone, lot, new BigDecimal(qty));
  }

  @Test
  void ordersAreAllocatedToBatchesInRuleOrderAndMergedIntoOnePickLinePerBatch() {
    // Order 1 wants 3 apples and 2 pears; order 2, confirmed later, wants 4 apples.
    List<Waves.Want> wants =
        List.of(
            new Waves.Want(ORDER_1, T0, APPLES, new BigDecimal("3")),
            new Waves.Want(ORDER_1, T0, PEARS, new BigDecimal("2")),
            new Waves.Want(ORDER_2, T0.plusSeconds(60), APPLES, new BigDecimal("4")));
    // Apples: an older batch of 5 in zone B, a newer batch of 10 in zone A (rule order given).
    UUID oldApples = Ids.newId();
    UUID newApples = Ids.newId();
    UUID pears = Ids.newId();
    Map<UUID, List<Waves.Stock>> byVariant =
        Map.of(
            APPLES,
            List.of(
                stock(oldApples, APPLES, ZONE_B, "5", "A-OLD"),
                stock(newApples, APPLES, ZONE_A, "10", "A-NEW")),
            PEARS,
            List.of(stock(pears, PEARS, ZONE_A, "8", "P-1")));
    Waves.Plan plan = Waves.plan(wants, byVariant, List.of(ZONE_A, ZONE_B));
    // Three pick lines: the old apples (5: 3 for order 1, 2 for order 2), the new apples (2 for
    // order 2), the pears (2 for order 1) — walked zone A first, then zone B.
    assertThat(plan.lines().size(), is(3));
    Waves.Line first = plan.lines().get(0);
    assertThat(first.zoneId(), is(ZONE_A));
    Waves.Line second = plan.lines().get(1);
    assertThat(second.zoneId(), is(ZONE_A));
    Waves.Line third = plan.lines().get(2);
    assertThat(third.zoneId(), is(ZONE_B));
    assertThat(third.batchId(), is(oldApples));
    assertThat(third.directedQty(), comparesEqualTo(new BigDecimal("5")));
    assertThat(third.allocations().size(), is(2));
    assertThat(third.allocations().get(0).orderId(), is(ORDER_1));
    assertThat(third.allocations().get(0).qty(), comparesEqualTo(new BigDecimal("3")));
    assertThat(third.allocations().get(1).orderId(), is(ORDER_2));
    assertThat(third.allocations().get(1).qty(), comparesEqualTo(new BigDecimal("2")));
    Waves.Line newLine =
        plan.lines().stream().filter(l -> l.batchId().equals(newApples)).findFirst().get();
    assertThat(newLine.directedQty(), comparesEqualTo(new BigDecimal("2")));
    assertThat(newLine.allocations().get(0).orderId(), is(ORDER_2));
    assertThat(plan.unplaced().isEmpty(), is(true));
    // Walk order is written on the lines, one-based.
    assertThat(first.walkOrder(), is(1));
    assertThat(third.walkOrder(), is(3));
  }

  @Test
  void whatTheShelfCannotCoverIsLeftUnplacedAndZonelessStockWalksLast() {
    List<Waves.Want> wants =
        List.of(
            new Waves.Want(ORDER_1, T0, APPLES, new BigDecimal("6")),
            new Waves.Want(ORDER_2, T0.plusSeconds(1), PEARS, new BigDecimal("1")));
    UUID apples = Ids.newId();
    UUID pears = Ids.newId();
    Map<UUID, List<Waves.Stock>> byVariant =
        Map.of(
            APPLES, List.of(stock(apples, APPLES, null, "4", "A")),
            PEARS, List.of(stock(pears, PEARS, ZONE_A, "3", "P")));
    Waves.Plan plan = Waves.plan(wants, byVariant, List.of());
    assertThat(plan.lines().size(), is(2));
    // Nothing says where the apples sit, so they are walked after the placed stock.
    assertThat(plan.lines().get(0).batchId(), is(pears));
    assertThat(plan.lines().get(1).batchId(), is(apples));
    assertThat(plan.lines().get(1).directedQty(), comparesEqualTo(new BigDecimal("4")));
    assertThat(plan.unplaced().size(), is(1));
    assertThat(plan.unplaced().get(0).orderId(), is(ORDER_1));
    assertThat(plan.unplaced().get(0).qty(), comparesEqualTo(new BigDecimal("2")));
  }

  @Test
  void aShortPickIsSharedOutToTheEarliestOrderFirst() {
    Waves.Allocation a1 = new Waves.Allocation(ORDER_1, new BigDecimal("3"));
    Waves.Allocation a2 = new Waves.Allocation(ORDER_2, new BigDecimal("2"));
    List<Waves.Allocation> picked = Waves.share(List.of(a1, a2), new BigDecimal("4"));
    assertThat(picked.get(0).qty(), comparesEqualTo(new BigDecimal("3")));
    assertThat(picked.get(1).qty(), comparesEqualTo(new BigDecimal("1")));
    List<Waves.Allocation> none = Waves.share(List.of(a1, a2), BigDecimal.ZERO);
    assertThat(none.get(0).qty().signum(), is(0));
    assertThat(none.get(1).qty().signum(), is(0));
    // Never more than was directed.
    List<Waves.Allocation> full = Waves.share(List.of(a1, a2), new BigDecimal("9"));
    assertThat(full.get(0).qty(), comparesEqualTo(new BigDecimal("3")));
    assertThat(full.get(1).qty(), comparesEqualTo(new BigDecimal("2")));
  }
}
