package com.storeql.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.PendingWork.Customer;
import com.storeql.reporting.domain.PendingWork.Kind;
import com.storeql.reporting.domain.PendingWork.Payment;
import com.storeql.reporting.domain.PendingWork.Purchase;
import com.storeql.reporting.domain.PendingWork.Readings;
import com.storeql.reporting.domain.PendingWork.Report;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The few seconds a business's waiting-work report is kept, so any number of open dashboards cause
 * one fan-out (one set of counts in the owning services) per period. Time is a clock the test moves
 * by hand; nothing here sleeps.
 */
class WaitingWorkCacheTest {

  private static final Instant START = Instant.parse("2026-10-07T09:00:00Z");
  private static final long TTL_MILLIS = 10_000;

  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();
  private static final UUID C = Ids.newId();

  /** Time that moves only when told to: one moment for a report's age and for its stamp. */
  private static final class Time extends Clock {
    private final AtomicLong nanos = new AtomicLong();

    void advance(Duration by) {
      nanos.addAndGet(by.toNanos());
    }

    long nanos() {
      return nanos.get();
    }

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
      return START.plusNanos(nanos.get());
    }
  }

  private final Time time = new Time();

  /** Makes a report, counting how often it is asked to; may take time, wait, or fail. */
  private final class Maker implements Supplier<Report> {
    private final AtomicInteger calls = new AtomicInteger();
    private boolean complete = true;
    private Duration takes = Duration.ZERO;
    private CountDownLatch holdUntil;
    private RuntimeException failure;

    Maker incomplete() {
      complete = false;
      return this;
    }

    Maker taking(Duration by) {
      takes = by;
      return this;
    }

    Maker holding(CountDownLatch release) {
      holdUntil = release;
      return this;
    }

    Maker failing(RuntimeException e) {
      failure = e;
      return this;
    }

    int calls() {
      return calls.get();
    }

    @Override
    public Report get() {
      calls.incrementAndGet();
      Instant madeAt = time.instant();
      if (holdUntil != null) {
        try {
          holdUntil.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      time.advance(takes);
      if (failure != null) throw failure;
      return new Report(madeAt, List.of(), complete ? List.of() : List.of(Kind.CARD_REFUND));
    }
  }

  private WaitingWorkCache cache(long ttlMillis, int maxBusinesses) {
    return new WaitingWorkCache(ttlMillis, maxBusinesses, time::nanos);
  }

  private static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("the condition never held");
      Thread.yield();
    }
  }

  private static <T> List<Future<T>> inParallel(ExecutorService pool, int n, Callable<T> call) {
    List<Future<T>> started = new ArrayList<>();
    for (int i = 0; i < n; i++) started.add(pool.submit(call));
    return started;
  }

  // ── the period ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Inside the period a business's report is made once, however many ask")
  void hitInsideThePeriod() {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    Maker maker = new Maker();

    Report first = cache.get(A, maker);
    time.advance(Duration.ofMillis(TTL_MILLIS - 1));
    for (int i = 0; i < 20; i++) assertSame(first, cache.get(A, maker));

    assertEquals(1, maker.calls());
  }

  @Test
  @DisplayName("At the period's end the report is made again, and that one is kept in its turn")
  void missAfterThePeriod() {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    Maker maker = new Maker();

    Report first = cache.get(A, maker);
    time.advance(Duration.ofMillis(TTL_MILLIS));
    Report second = cache.get(A, maker);
    Report third = cache.get(A, maker);

    assertNotSame(first, second);
    assertSame(second, third);
    assertEquals(2, maker.calls());
  }

  @Test
  @DisplayName("The period counts from when the reading began, not from when it ended")
  void periodCountsFromTheStartOfTheReading() {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    Maker slow = new Maker().taking(Duration.ofSeconds(2));

    Report first = cache.get(A, slow);
    time.advance(Duration.ofMillis(TTL_MILLIS - 2_000 - 1));
    assertSame(first, cache.get(A, slow));
    time.advance(Duration.ofMillis(1));
    assertNotSame(first, cache.get(A, slow));

    assertEquals(2, slow.calls());
  }

  @ParameterizedTest(name = "period {0} ms, {1} businesses: off")
  @CsvSource({"0,100", "-1,100", "10000,0", "10000,-5"})
  @DisplayName("A period or a size of nothing turns the cache off: every ask is made fresh")
  void offMeansEveryAskReads(long ttlMillis, int maxBusinesses) {
    WaitingWorkCache cache = cache(ttlMillis, maxBusinesses);
    Maker maker = new Maker();

    Report first = cache.get(A, maker);
    Report second = cache.get(A, maker);

    assertNotSame(first, second);
    assertEquals(2, maker.calls());
  }

  // ── whose report ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A business is never given another's report; its first ask makes its own")
  void businessesAreKeptApart() {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    Maker makesA = new Maker();
    Maker makesB = new Maker();

    Report a = cache.get(A, makesA);
    time.advance(Duration.ofSeconds(1));
    Report b = cache.get(B, makesB);

    assertNotSame(a, b);
    assertEquals(1, makesB.calls());
    assertEquals(START, a.generatedAt());
    assertEquals(START.plusSeconds(1), b.generatedAt());
    assertSame(a, cache.get(A, makesA));
    assertSame(b, cache.get(B, makesB));
    assertEquals(1, makesA.calls());
    assertEquals(1, makesB.calls());
  }

  // ── what is kept ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A report with a source that did not answer is not kept: the next ask tries again")
  void anUnreachableSourceIsNeverFrozenIn() {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    Maker unreachable = new Maker().incomplete();
    Maker healthy = new Maker();

    Report gap = cache.get(A, unreachable);
    Report again = cache.get(A, unreachable);
    Report whole = cache.get(A, healthy);
    Report kept = cache.get(A, healthy);

    assertNotSame(gap, again);
    assertEquals(2, unreachable.calls());
    assertTrue(whole.unreachable().isEmpty());
    assertSame(whole, kept);
    assertEquals(1, healthy.calls());
  }

  @Test
  @DisplayName("At most the set number of businesses are kept; the least recently asked goes first")
  void theLeastRecentlyUsedGoesFirst() {
    WaitingWorkCache cache = cache(TTL_MILLIS, 2);
    Maker makesA = new Maker();
    Maker makesB = new Maker();
    Maker makesC = new Maker();

    cache.get(A, makesA);
    cache.get(B, makesB);
    cache.get(A, makesA); // A is now the more recent of the two
    cache.get(C, makesC); // makes room: B, the least recently asked, goes
    cache.get(A, makesA);
    cache.get(C, makesC);
    assertEquals(1, makesA.calls());
    assertEquals(1, makesC.calls());

    cache.get(B, makesB);
    assertEquals(2, makesB.calls());
  }

  @Test
  @DisplayName("A cache of one business keeps only the latest to be asked about")
  void aSizeOfOneKeepsOnlyTheLatest() {
    WaitingWorkCache cache = cache(TTL_MILLIS, 1);
    Maker makesA = new Maker();
    Maker makesB = new Maker();

    cache.get(A, makesA);
    cache.get(B, makesB);
    cache.get(A, makesA);

    assertEquals(2, makesA.calls());
    assertEquals(1, makesB.calls());
  }

  // ── one reading at a time for a business ───────────────────────────────────

  @Test
  @Timeout(60)
  @DisplayName("Fifty asks together for one business make one report, not fifty (and share it)")
  void oneFlightPerBusiness() throws Exception {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    CountDownLatch release = new CountDownLatch(1);
    // Not keepable, so only sharing the flight can explain a single reading.
    Maker maker = new Maker().incomplete().holding(release);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Report>> asked = inParallel(pool, 50, () -> cache.get(A, maker));
      awaitTrue(() -> maker.calls() == 1 && cache.followersWaiting() == 49);
      release.countDown();

      Report shared = asked.get(0).get(20, TimeUnit.SECONDS);
      for (Future<Report> f : asked) assertSame(shared, f.get(20, TimeUnit.SECONDS));
    }

    assertEquals(1, maker.calls());
    cache.get(A, maker); // the flight is over: a later ask is a new reading
    assertEquals(2, maker.calls());
  }

  @Test
  @Timeout(60)
  @DisplayName("One business's reading does not hold up another's")
  void businessesDoNotWaitForEachOther() throws Exception {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    CountDownLatch release = new CountDownLatch(1);
    Maker slowForA = new Maker().holding(release);
    Maker makesB = new Maker();

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Report> a = pool.submit(() -> cache.get(A, slowForA));
      awaitTrue(() -> slowForA.calls() == 1);

      Report b = cache.get(B, makesB); // returns while A's reading is still going
      assertEquals(1, makesB.calls());
      assertEquals(START, b.generatedAt());

      release.countDown();
      a.get(20, TimeUnit.SECONDS);
    }
  }

  @Test
  @Timeout(60)
  @DisplayName("A reading that fails fails those waiting on it, and leaves nothing stuck behind")
  void aFailedFlightIsOver() throws Exception {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    CountDownLatch release = new CountDownLatch(1);
    Maker failing = new Maker().holding(release).failing(new IllegalStateException("boom"));

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Report>> asked = inParallel(pool, 5, () -> cache.get(A, failing));
      awaitTrue(() -> failing.calls() == 1 && cache.followersWaiting() == 4);
      release.countDown();

      for (Future<Report> f : asked) {
        ExecutionException e =
            assertThrows(ExecutionException.class, () -> f.get(20, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
      }
    }

    assertEquals(1, failing.calls());
    Maker healthy = new Maker();
    assertTrue(cache.get(A, healthy).unreachable().isEmpty());
    assertEquals(1, healthy.calls());
  }

  // ── through the service: what the screen is given ──────────────────────────

  private static Readings readings(long purchaseOrders, long refundDues, long privacy) {
    return new Readings(
        Optional.of(new Purchase(purchaseOrders, 0, 0, 0, true)),
        Optional.of(new Payment(refundDues)),
        Optional.of(new Customer(privacy)));
  }

  private static Readings noPayment() {
    return new Readings(
        Optional.of(new Purchase(1, 0, 0, 0, true)),
        Optional.empty(),
        Optional.of(new Customer(1)));
  }

  private WaitingWorkService service(Function<UUID, Readings> reader) {
    return WaitingWorkService.forTest(reader, time, cache(TTL_MILLIS, 100));
  }

  @Test
  @DisplayName("Ten asks inside the period read the three services once")
  void servicesAreAskedOncePerPeriod() {
    AtomicInteger asked = new AtomicInteger();
    WaitingWorkService service =
        service(
            id -> {
              asked.incrementAndGet();
              return readings(3, 4, 7);
            });

    for (int i = 0; i < 10; i++) {
      time.advance(Duration.ofMillis(500));
      assertEquals(3L, service.waitingWork(A).items().get(0).count());
    }
    assertEquals(1, asked.get());

    time.advance(Duration.ofMillis(TTL_MILLIS));
    service.waitingWork(A);
    assertEquals(2, asked.get());
  }

  @Test
  @DisplayName("A kept answer says when it was really made, not when it was handed out")
  void generatedAtIsWhenItWasMade() {
    WaitingWorkService service = service(id -> readings(3, 4, 7));

    Report first = service.waitingWork(A);
    time.advance(Duration.ofSeconds(4));
    Report kept = service.waitingWork(A);
    time.advance(Duration.ofSeconds(7));
    Report fresh = service.waitingWork(A);

    assertEquals(START, first.generatedAt());
    assertEquals(START, kept.generatedAt());
    assertEquals(START.plusSeconds(11), fresh.generatedAt());
  }

  @Test
  @DisplayName("Each business is asked about, and answered with, its own figures")
  void serviceKeepsBusinessesApart() {
    Map<UUID, Readings> says = Map.of(A, readings(3, 4, 7), B, readings(9, 8, 5));
    Map<UUID, AtomicInteger> asked =
        Map.of(A, new AtomicInteger(), B, new AtomicInteger(), C, new AtomicInteger());
    WaitingWorkService service =
        service(
            id -> {
              asked.get(id).incrementAndGet();
              return says.getOrDefault(id, readings(0, 0, 0));
            });

    assertEquals(3L, service.waitingWork(A).items().get(0).count());
    assertEquals(9L, service.waitingWork(B).items().get(0).count());
    assertEquals(0L, service.waitingWork(C).items().get(0).count());
    assertEquals(3L, service.waitingWork(A).items().get(0).count());
    assertEquals(9L, service.waitingWork(B).items().get(0).count());

    assertEquals(1, asked.get(A).get());
    assertEquals(1, asked.get(B).get());
    assertEquals(1, asked.get(C).get());
  }

  @Test
  @DisplayName(
      "A source that did not answer is tried again by the next ask, then the whole is kept")
  void serviceDoesNotKeepAGap() {
    AtomicInteger asked = new AtomicInteger();
    WaitingWorkService service =
        service(
            id -> {
              int n = asked.incrementAndGet();
              return n == 1 ? noPayment() : readings(1, 6, 1);
            });

    Report gap = service.waitingWork(A);
    Report whole = service.waitingWork(A);
    Report kept = service.waitingWork(A);

    assertEquals(List.of(Kind.CARD_REFUND), gap.unreachable());
    assertEquals(List.of(), whole.unreachable());
    assertEquals(6L, whole.items().get(4).count());
    assertSame(whole, kept);
    assertEquals(2, asked.get());
  }

  @Test
  @Timeout(60)
  @DisplayName("Fifty dashboards of one business opening together cause one fan-out")
  void coldCacheFiftyAtOnceReadsOnce() throws Exception {
    WaitingWorkCache cache = cache(TTL_MILLIS, 100);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger asked = new AtomicInteger();
    WaitingWorkService service =
        WaitingWorkService.forTest(
            id -> {
              asked.incrementAndGet();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return readings(3, 4, 7);
            },
            time,
            cache);

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Report>> asking = inParallel(pool, 50, () -> service.waitingWork(A));
      awaitTrue(() -> asked.get() == 1 && cache.followersWaiting() == 49);
      release.countDown();

      Report shared = asking.get(0).get(20, TimeUnit.SECONDS);
      for (Future<Report> f : asking) assertSame(shared, f.get(20, TimeUnit.SECONDS));
    }
    assertEquals(1, asked.get());
  }
}
