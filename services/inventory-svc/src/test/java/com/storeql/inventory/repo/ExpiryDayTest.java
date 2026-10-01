package com.storeql.inventory.repo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The tenant-svc read behind the expiry day: one read at a time, and no waiting once read. */
class ExpiryDayTest {

  /** A clock the test moves. */
  private static final class Moving extends Clock {
    final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-10-01T10:00:00Z"));

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @Test
  void concurrentFirstCallsMakeOneRead() throws Exception {
    UUID tenant = Ids.newId();
    AtomicInteger reads = new AtomicInteger();
    ExpiryDay day =
        ExpiryDay.forTest(
            t -> {
              reads.incrementAndGet();
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return Map.of();
            },
            new Moving(),
            Runnable::run);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<java.util.concurrent.Future<?>> done = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        done.add(
            pool.submit(
                () -> {
                  go.await();
                  return day.of(tenant);
                }));
      }
      go.countDown();
      for (var f : done) f.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(reads.get(), is(1));
  }

  @Test
  void aStaleAnswerIsServedAtOnceAndRefreshedOnce() {
    UUID tenant = Ids.newId();
    Moving clock = new Moving();
    AtomicInteger reads = new AtomicInteger();
    List<Runnable> queued = new ArrayList<>();
    ExpiryDay day =
        ExpiryDay.forTest(
            t -> {
              reads.incrementAndGet();
              return Map.of();
            },
            clock,
            queued::add);
    day.of(tenant);
    assertThat(reads.get(), is(1));

    clock.now.set(clock.now.get().plus(Duration.ofMinutes(5)));
    day.of(tenant);
    day.of(tenant);
    day.of(tenant);
    // Nobody waited on tenant-svc: the stale zones were served and one refresh is queued.
    assertThat(reads.get(), is(1));
    assertThat(queued.size(), is(1));

    queued.get(0).run();
    assertThat(reads.get(), is(2));
    day.of(tenant); // fresh again: no new refresh
    assertThat(queued.size(), is(1));
  }
}
