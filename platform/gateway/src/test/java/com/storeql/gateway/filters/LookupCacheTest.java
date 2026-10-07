package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The gateway's request-path lookups: bounded, one load per key at a time (groups 5, 6). */
class LookupCacheTest {

  private final AtomicLong clock = new AtomicLong(1_000);

  @Test
  void neverHoldsMoreThanTheCapAndDropsTheLeastRecentlyUsed() {
    LookupCache<Integer> cache = new LookupCache<>(3, 10_000, true, clock::get);
    AtomicInteger loads = new AtomicInteger();
    for (int i = 0; i < 10; i++) cache.get("k" + i, k -> loads.incrementAndGet(), v -> true);
    assertEquals(3, cache.size());

    cache.get("k9", k -> loads.incrementAndGet(), v -> true);
    assertEquals(10, loads.get(), "the most recent is still held");
    cache.get("k0", k -> loads.incrementAndGet(), v -> true);
    assertEquals(11, loads.get(), "the oldest was dropped");
  }

  @Test
  void aValueIsLoadedAgainOnlyAfterItsTtl() {
    LookupCache<Integer> cache = new LookupCache<>(10, 1_000, false, clock::get);
    AtomicInteger loads = new AtomicInteger();
    cache.get("a", k -> loads.incrementAndGet(), v -> true);
    cache.get("a", k -> loads.incrementAndGet(), v -> true);
    assertEquals(1, loads.get());
    clock.addAndGet(1_001);
    assertEquals(2, cache.get("a", k -> loads.incrementAndGet(), v -> true));
  }

  @Test
  void anAnswerThatIsNotCacheableIsNotKept() {
    LookupCache<Integer> cache = new LookupCache<>(10, 1_000, false, clock::get);
    AtomicInteger loads = new AtomicInteger();
    cache.get("a", k -> loads.incrementAndGet(), v -> false);
    cache.get("a", k -> loads.incrementAndGet(), v -> false);
    assertEquals(2, loads.get());
    assertEquals(0, cache.size());
  }

  @Test
  void manyCallersForOneKeyShareOneLoad() throws Exception {
    LookupCache<Integer> cache = new LookupCache<>(10, 1_000, false, clock::get);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      results.add(
          pool.submit(
              () ->
                  cache.get(
                      "a",
                      k -> {
                        loads.incrementAndGet();
                        try {
                          release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return 42;
                      },
                      v -> true)));
    }
    Thread.sleep(200);
    release.countDown();
    for (Future<Integer> f : results) assertEquals(42, f.get());
    pool.shutdownNow();
    assertEquals(1, loads.get());
  }

  @Test
  void anExpiredValueIsServedWhileOneCallerRefreshesWhenStaleIsAllowed() throws Exception {
    LookupCache<String> cache = new LookupCache<>(10, 1_000, true, clock::get);
    cache.get("a", k -> "old", v -> true);
    clock.addAndGet(5_000);

    CountDownLatch inLoad = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<String> refresher =
        pool.submit(
            () ->
                cache.get(
                    "a",
                    k -> {
                      inLoad.countDown();
                      try {
                        release.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return "new";
                    },
                    v -> true));
    assertTrue(inLoad.await(5, TimeUnit.SECONDS));

    assertEquals("old", cache.get("a", k -> "never", v -> true), "no wait, no second load");
    release.countDown();
    assertEquals("new", refresher.get());
    assertEquals("new", cache.get("a", k -> "never", v -> true));
    pool.shutdownNow();
  }

  @Test
  void aFailedLoadFailsItsCallersAndLeavesNothingStuck() {
    LookupCache<Integer> cache = new LookupCache<>(10, 1_000, false, clock::get);
    assertThrows(
        IllegalStateException.class,
        () ->
            cache.get(
                "a",
                k -> {
                  throw new IllegalStateException("boom");
                },
                v -> true));
    assertEquals(7, cache.get("a", k -> 7, v -> true), "the next caller loads afresh");
  }
}
