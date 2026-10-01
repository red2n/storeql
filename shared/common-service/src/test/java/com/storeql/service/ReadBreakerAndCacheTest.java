package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReadBreakerAndCacheTest {

  @Test
  void theCircuitOpensAfterTheThresholdAndClosesAfterTheOpenPeriod() {
    AtomicLong now = new AtomicLong();
    ReadBreaker b = new ReadBreaker(3, Duration.ofSeconds(5), now::get);
    b.failure();
    b.failure();
    assertFalse(b.isOpen());
    b.failure();
    assertTrue(b.isOpen());
    now.addAndGet(Duration.ofSeconds(6).toNanos());
    assertFalse(b.isOpen(), "half-open: one read is let through");
    b.failure();
    assertTrue(b.isOpen(), "a failed probe opens it again at once");
    now.addAndGet(Duration.ofSeconds(6).toNanos());
    b.success();
    b.failure();
    assertFalse(b.isOpen(), "a success resets the count");
  }

  @Test
  void aSuccessBetweenFailuresKeepsTheCircuitClosed() {
    ReadBreaker b = new ReadBreaker(2, Duration.ofSeconds(5), () -> 0L);
    b.failure();
    b.success();
    b.failure();
    assertFalse(b.isOpen());
  }

  @Test
  void aCacheBeyondItsLimitDropsExpiredEntriesFirstThenAnyUntilItFits() {
    Map<Integer, Boolean> cache = new HashMap<>(); // value true = expired
    for (int i = 0; i < 10; i++) cache.put(i, i < 6);
    CacheSweep.trim(cache, expired -> expired, 8);
    assertEquals(4, cache.size(), "the six expired are swept, which already fits");
    assertTrue(cache.values().stream().noneMatch(v -> v));

    Map<Integer, Boolean> live = new HashMap<>();
    for (int i = 0; i < 20; i++) live.put(i, false);
    CacheSweep.trim(live, expired -> expired, 8);
    assertEquals(8, live.size(), "bounded even when nothing has expired");

    Map<Integer, Boolean> small = new HashMap<>(Map.of(1, true));
    CacheSweep.trim(small, expired -> expired, 8);
    assertEquals(1, small.size(), "under the limit nothing is touched");
  }
}
