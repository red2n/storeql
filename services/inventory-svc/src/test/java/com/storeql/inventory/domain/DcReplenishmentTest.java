package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What a shop served by a warehouse needs, and how a short warehouse shares what it has — pure.
 * Written before the code.
 */
class DcReplenishmentTest {

  private static final UUID LEEDS = Ids.newId();
  private static final UUID YORK = Ids.newId();
  private static final UUID HULL = Ids.newId();

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aShopAtOrBelowItsReorderPointNeedsItBackPlusWhatSellsOverLeadTimeAndCover() {
    // On hand 4 + inbound 2 = 6 ≤ reorder point 10: back to 10 (4) plus the forecast 14 over the
    // two days' lead time and the seven days' cover.
    var result =
        DcReplenishment.need(
            new DcReplenishment.Shop(LEEDS, d("10"), d("4"), d("2"), d("14"), d("1.5"), 2, 7));
    assertThat(result, instanceOf(DcReplenishment.Need.class));
    var need = (DcReplenishment.Need) result;
    assertThat(need.qty(), comparesEqualTo(d("18")));
    assertThat(need.position(), comparesEqualTo(d("6")));
    assertThat(need.reason(), containsString("on hand 4 + inbound 2 = 6 ≤ reorder point 10"));
    assertThat(need.reason(), containsString("forecast 14 over 9 days"));
    // No forecast: the plan's average daily demand over the same nine days.
    var byAverage =
        (DcReplenishment.Need)
            DcReplenishment.need(
                new DcReplenishment.Shop(LEEDS, d("10"), d("4"), d("2"), null, d("1.5"), 2, 7));
    assertThat(byAverage.qty(), comparesEqualTo(d("17.5")));
    assertThat(byAverage.reason(), containsString("1.5/day over 9 days (no forecast)"));
  }

  @Test
  void aShopAboveItsReorderPointNeedsNothingAndOneWithoutAPlanIsSkipped() {
    assertThat(
        DcReplenishment.need(
            new DcReplenishment.Shop(LEEDS, d("10"), d("9"), d("2"), d("14"), d("1.5"), 2, 7)),
        instanceOf(DcReplenishment.Nothing.class));
    var skipped =
        DcReplenishment.need(
            new DcReplenishment.Shop(LEEDS, null, d("0"), d("0"), d("14"), d("1.5"), 2, 7));
    assertThat(skipped, instanceOf(DcReplenishment.Skipped.class));
    assertThat(
        ((DcReplenishment.Skipped) skipped).reason(), containsString("no reorder point computed"));
  }

  private static DcReplenishment.Need need(UUID shop, String qty, String position, String avg) {
    return new DcReplenishment.Need(shop, d(qty), d(position), avg == null ? null : d(avg), "r");
  }

  private static Map<UUID, BigDecimal> byShop(List<DcReplenishment.Allocation> allocations) {
    return allocations.stream()
        .collect(Collectors.toMap(DcReplenishment.Allocation::storeId, a -> a.qty()));
  }

  @Test
  void enoughAtTheWarehouseGivesEveryShopItsNeed() {
    var shares =
        DcReplenishment.share(
            d("100"), List.of(need(LEEDS, "18", "6", "1.5"), need(YORK, "7", "3", "1")));
    assertThat(byShop(shares).get(LEEDS), comparesEqualTo(d("18")));
    assertThat(byShop(shares).get(YORK), comparesEqualTo(d("7")));
    assertThat(shares.get(0).reason(), is("r"));
  }

  @Test
  void aShortWarehouseSharesInProportionToNeedAndTheRemainderGoesToTheLeastCover() {
    // Needs 10, 10 and 5 against 12 on hand: 12·10/25 = 4.8 → 4, 4.8 → 4, 12·5/25 = 2.4 → 2;
    // the 2 left over go to the shop with the least cover — York (position 1 at 1/day, one day)
    // before Hull (two days) and Leeds (ten) — never above its need.
    var shares =
        DcReplenishment.share(
            d("12"),
            List.of(
                need(LEEDS, "10", "30", "3"),
                need(YORK, "10", "1", "1"),
                need(HULL, "5", "4", "2")));
    Map<UUID, BigDecimal> got = byShop(shares);
    assertThat(got.get(LEEDS), comparesEqualTo(d("4")));
    assertThat(got.get(YORK), comparesEqualTo(d("6")));
    assertThat(got.get(HULL), comparesEqualTo(d("2")));
    assertThat(
        got.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), comparesEqualTo(d("12")));
    var york = shares.stream().filter(a -> a.storeId().equals(YORK)).findFirst().orElseThrow();
    assertThat(york.reason(), containsString("cut to 6 of 10: the warehouse is short"));
  }

  @Test
  void aWarehouseWithNothingGivesNothingAndAWeighedRemainderIsNotLost() {
    var none = DcReplenishment.share(d("0"), List.of(need(LEEDS, "10", "1", "1")));
    assertThat(byShop(none).get(LEEDS), comparesEqualTo(BigDecimal.ZERO));
    // 2.5 kg against needs of 2 and 2: 1 and 1 whole, the half to the least cover.
    var weighed =
        DcReplenishment.share(
            d("2.5"), List.of(need(LEEDS, "2", "3", "1"), need(YORK, "2", "1", "1")));
    assertThat(byShop(weighed).get(LEEDS), comparesEqualTo(d("1")));
    assertThat(byShop(weighed).get(YORK), comparesEqualTo(d("1.5")));
    // A shop with no demand history has endless cover: the remainder goes to the others first.
    var noHistory =
        DcReplenishment.share(
            d("3"), List.of(need(LEEDS, "2", "0", null), need(YORK, "2", "5", "1")));
    assertThat(byShop(noHistory).get(YORK), comparesEqualTo(d("2")));
    assertThat(byShop(noHistory).get(LEEDS), comparesEqualTo(d("1")));
  }
}
