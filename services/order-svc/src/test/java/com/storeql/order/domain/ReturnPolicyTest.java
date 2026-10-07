package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which rules a return falls outside of. The cases worth writing are the quiet ones: a window
 * counted in the wrong zone, a faulty item refused when the law says it must be taken, and a
 * ceiling that cannot be measured being treated as no ceiling.
 */
class ReturnPolicyTest {

  private static final ZoneId UTC = ZoneOffset.UTC;
  private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
  private static final BigDecimal TEN = BigDecimal.TEN;

  private static Instant daysAgo(int days) {
    return NOW.minusSeconds(days * 86_400L);
  }

  @Test
  @DisplayName("A business that set nothing gets thirty days, no ceiling and no no-receipt returns")
  void theDefault() {
    assertEquals(30, ReturnPolicy.DEFAULT.windowDays());
    assertNull(ReturnPolicy.DEFAULT.cashierCeiling());
    assertFalse(ReturnPolicy.DEFAULT.noReceiptAllowed());
    assertNull(ReturnPolicy.DEFAULT.noReceiptCeiling());
  }

  @Test
  @DisplayName("The last day of the window is inside it, the next is not")
  void windowBoundary() {
    ReturnPolicy p = ReturnPolicy.DEFAULT;
    assertEquals(List.of(), p.reasons(daysAgo(30), NOW, UTC, TEN, false));
    assertEquals(List.of(ReturnPolicy.WINDOW), p.reasons(daysAgo(31), NOW, UTC, TEN, false));
    assertEquals(List.of(), p.reasons(NOW, NOW, UTC, TEN, false));
  }

  @Test
  @DisplayName("Days are calendar days in the store's own zone, not 24-hour spans")
  void windowIsCountedInTheStoresZone() {
    ReturnPolicy oneDay = new ReturnPolicy(1, null, false, null);
    // Sold 23:30 Monday in Auckland (UTC+13 in January); returned 00:30 Wednesday there.
    ZoneId auckland = ZoneId.of("Pacific/Auckland");
    Instant sold = Instant.parse("2026-01-12T10:30:00Z"); // Mon 23:30 NZDT (UTC+13)
    Instant returned = Instant.parse("2026-01-13T11:30:00Z"); // Wed 00:30 NZDT
    // Only 25 hours apart, but two calendar days there: outside a one-day window.
    assertEquals(
        List.of(ReturnPolicy.WINDOW), oneDay.reasons(sold, returned, auckland, TEN, false));
    // The same two moments read in UTC are the same day and the next: inside it.
    assertEquals(List.of(), oneDay.reasons(sold, returned, UTC, TEN, false));
    assertTrue(oneDay.pastWindow(sold, returned, auckland));
    assertFalse(oneDay.pastWindow(sold, returned, UTC));
  }

  @Test
  @DisplayName("Faulty goods past the window need a manager under their own reason")
  void faultyGoodsReplaceTheWindowReason() {
    ReturnPolicy p = ReturnPolicy.DEFAULT;
    assertEquals(
        List.of(ReturnPolicy.FAULTY_PAST_WINDOW), p.reasons(daysAgo(400), NOW, UTC, TEN, true));
    // Inside the window a faulty item is an ordinary return.
    assertEquals(List.of(), p.reasons(daysAgo(3), NOW, UTC, TEN, true));
  }

  @Test
  @DisplayName("Over the cashier's ceiling is a reason; at it is not; none set is never one")
  void ceiling() {
    ReturnPolicy p = new ReturnPolicy(30, new BigDecimal("50.00"), false, null);
    assertEquals(List.of(), p.reasons(NOW, NOW, UTC, new BigDecimal("50.00"), false));
    assertEquals(
        List.of(ReturnPolicy.CEILING), p.reasons(NOW, NOW, UTC, new BigDecimal("50.01"), false));
    assertEquals(
        List.of(), ReturnPolicy.DEFAULT.reasons(NOW, NOW, UTC, new BigDecimal("1e9"), false));
  }

  @Test
  @DisplayName("A refund that cannot be put in the home currency fails closed against a ceiling")
  void untranslatableRefundFailsClosed() {
    ReturnPolicy p = new ReturnPolicy(30, new BigDecimal("50.00"), false, null);
    assertEquals(List.of(ReturnPolicy.CEILING), p.reasons(NOW, NOW, UTC, null, false));
    // With no ceiling there is nothing to measure it against.
    assertEquals(List.of(), ReturnPolicy.DEFAULT.reasons(NOW, NOW, UTC, null, false));
  }

  @Test
  @DisplayName("Late and dear is both reasons, in a stable order")
  void bothReasons() {
    ReturnPolicy p = new ReturnPolicy(7, new BigDecimal("5"), false, null);
    assertEquals(
        List.of(ReturnPolicy.WINDOW, ReturnPolicy.CEILING),
        p.reasons(daysAgo(10), NOW, UTC, TEN, false));
    assertEquals(
        List.of(ReturnPolicy.FAULTY_PAST_WINDOW, ReturnPolicy.CEILING),
        p.reasons(daysAgo(10), NOW, UTC, TEN, true));
  }
}
