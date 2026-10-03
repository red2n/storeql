package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import org.junit.jupiter.api.Test;

class OrderSettingsTest {

  @Test
  void everyLimitIsOffUntilSet() {
    assertNull(OrderSettings.NONE.pendingLimitHours());
    assertNull(OrderSettings.NONE.priceWaitFlagMinutes());
    assertNull(OrderSettings.NONE.priceWaitCancelMinutes());
    assertEquals(24, OrderSettings.NONE.pendingHours(24));
  }

  @Test
  void ownPendingLimitWinsOverThePlatformDefault() {
    assertEquals(6, OrderSettings.NONE.withPendingLimit(6).pendingHours(24));
    assertEquals(
        24, OrderSettings.NONE.withPendingLimit(6).withPendingLimit(null).pendingHours(24));
  }

  @Test
  void aPendingLimitBelowOneHourIsRefused() {
    for (int bad : new int[] {0, -3}) {
      var e = assertThrows(ApiException.class, () -> OrderSettings.NONE.withPendingLimit(bad));
      assertEquals("ORDER_PENDING_LIMIT_INVALID", e.code());
      assertEquals(400, e.status());
    }
  }

  @Test
  void theTwoLimitsShareOneRowAndOneDoesNotDisturbTheOther() {
    var s = OrderSettings.NONE.withPendingLimit(12).withPriceWait(30, 90);
    assertEquals(12, s.pendingLimitHours());
    assertEquals(30, s.priceWaitFlagMinutes());
    assertEquals(90, s.priceWaitCancelMinutes());
    var t = s.withPendingLimit(null);
    assertEquals(30, t.priceWaitFlagMinutes());
    assertEquals(90, t.priceWaitCancelMinutes());
  }

  @Test
  void aCancelLimitBeforeTheFlagLimitOrBelowOneMinuteIsRefused() {
    int[][] bad = {{60, 30}, {0, 10}, {10, 0}, {-1, 5}};
    for (int[] b : bad) {
      var e = assertThrows(ApiException.class, () -> OrderSettings.NONE.withPriceWait(b[0], b[1]));
      assertEquals("ORDER_PRICE_WAIT_INVALID", e.code());
    }
  }

  @Test
  void eitherPriceLimitMayBeUnsetAndEqualLimitsAreAllowed() {
    OrderSettings.NONE.withPriceWait(30, null);
    OrderSettings.NONE.withPriceWait(null, 30);
    OrderSettings.NONE.withPriceWait(null, null);
    OrderSettings.NONE.withPriceWait(30, 30);
  }
}
