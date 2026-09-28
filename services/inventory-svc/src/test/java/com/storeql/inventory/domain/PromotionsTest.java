package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Promotions.PromotionWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The days a promotion ran, or will run, for one item at one store — read off pricing-svc's
 * windows.
 */
class PromotionsTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 9, 1);
  private static final UUID SHOP = Ids.newId();
  private static final UUID OTHER_SHOP = Ids.newId();
  private static final UUID BEANS = Ids.newId();
  private static final UUID RICE = Ids.newId();

  private static Instant at(int day, int hour) {
    return DAY0.plusDays(day).atTime(hour, 0).toInstant(ZoneOffset.UTC);
  }

  private static String days(boolean[] marks) {
    StringBuilder b = new StringBuilder();
    for (boolean m : marks) b.append(m ? 'P' : '.');
    return b.toString();
  }

  @Test
  @DisplayName(
      "A window marks the days it touches for its items at its store; one for everything everywhere marks them all from its start")
  void windowsMarkTheirDays() {
    var beansAtTheShop =
        new PromotionWindow(Ids.newId(), SHOP, at(3, 9), at(5, 14), Set.of(BEANS), false);
    var everythingFromDayEight =
        new PromotionWindow(Ids.newId(), null, at(8, 0), null, Set.of(), true);
    List<PromotionWindow> windows = List.of(beansAtTheShop, everythingFromDayEight);

    assertThat(
        days(Promotions.days(windows, BEANS, SHOP, DAY0, DAY0.plusDays(9))), is("...PPP..PP"));
    assertThat(
        days(Promotions.days(windows, RICE, SHOP, DAY0, DAY0.plusDays(9))), is("........PP"));
    assertThat(
        days(Promotions.days(windows, BEANS, OTHER_SHOP, DAY0, DAY0.plusDays(9))),
        is("........PP"));
  }

  @Test
  @DisplayName(
      "No windows mark nothing, and a window that ended before the range marks nothing in it")
  void nothingWhereNothingRan() {
    assertThat(days(Promotions.days(List.of(), BEANS, SHOP, DAY0, DAY0.plusDays(3))), is("...."));
    var lastMonth =
        new PromotionWindow(Ids.newId(), SHOP, at(-30, 0), at(-20, 0), Set.of(BEANS), false);
    assertThat(
        days(Promotions.days(List.of(lastMonth), BEANS, SHOP, DAY0, DAY0.plusDays(3))), is("...."));
  }
}
