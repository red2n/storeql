package com.storeql.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Order is asserted on {@code toString()}: fixed-width lowercase hex sorts exactly as PostgreSQL
 * sorts {@code uuid} (unsigned, byte by byte), which is the order the index-locality argument rests
 * on. {@link UUID#compareTo} compares signed longs, so it is not a safe stand-in.
 */
class UuidV7GeneratorTest {

  private static final long NOW = 1_757_590_000_000L;

  @Test
  void idsCarryVersion7AndTheRfcVariant() {
    UUID id = Ids.newId();

    assertEquals(7, id.version());
    assertEquals(2, id.variant());
  }

  @Test
  void theLeading48BitsAreTheClockMillisecond() {
    UUID id = new UuidV7Generator(() -> NOW, new SplittableRandom(1)::nextLong).next();

    assertEquals(NOW, millisOf(id));
  }

  /**
   * More ids than one millisecond's counter can hold, so the generator has to borrow the next
   * millisecond part-way through — and order must survive that.
   */
  @Test
  void idsMadeInTheSameMillisecondStillSortInCreationOrder() {
    UuidV7Generator generator = new UuidV7Generator(() -> NOW, new SplittableRandom(2)::nextLong);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(generator.next());
    }

    assertStrictlyIncreasing(ids);
    assertTrue(millisOf(ids.get(ids.size() - 1)) > NOW, "counter overflow borrows the next ms");
  }

  /** NTP can step the wall clock back; ids made after that must still sort after earlier ones. */
  @Test
  void aClockThatStepsBackDoesNotBreakTheOrder() {
    PrimitiveIterator.OfLong ticks = LongStream.of(NOW, NOW - 1_000).iterator();
    UuidV7Generator generator =
        new UuidV7Generator(ticks::nextLong, new SplittableRandom(3)::nextLong);

    UUID before = generator.next();
    UUID after = generator.next();

    assertStrictlyIncreasing(List.of(before, after));
    assertEquals(NOW, millisOf(after));
  }

  /** A later millisecond wins even when its fresh counter seed is lower than the last counter. */
  @Test
  void aLaterMillisecondSortsLaterWhateverTheCounter() {
    PrimitiveIterator.OfLong ticks =
        LongStream.concat(LongStream.generate(() -> NOW).limit(2_000), LongStream.of(NOW + 1))
            .iterator();
    UuidV7Generator generator =
        new UuidV7Generator(ticks::nextLong, new SplittableRandom(4)::nextLong);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 2_001; i++) {
      ids.add(generator.next());
    }

    assertStrictlyIncreasing(ids);
    assertEquals(NOW + 1, millisOf(ids.get(ids.size() - 1)));
  }

  /**
   * Threads share nothing but the random source, so two of them often hold the same millisecond and
   * counter at once: every id must still be unique, and each thread's own ids still in order.
   */
  @Test
  void concurrentThreadsNeverShareAnIdAndEachKeepsItsOwnOrder() {
    int threads = 8;
    int perThread = 20_000;
    Set<UUID> seen = ConcurrentHashMap.newKeySet();
    UUID[][] byThread = new UUID[threads][perThread];
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int t = 0; t < threads; t++) {
        UUID[] mine = byThread[t];
        pool.submit(
            () -> {
              for (int i = 0; i < perThread; i++) {
                mine[i] = Ids.newId();
                seen.add(mine[i]);
              }
            });
      }
    }

    assertEquals(threads * perThread, seen.size());
    for (UUID[] ids : byThread) {
      assertStrictlyIncreasing(Arrays.asList(ids));
    }
  }

  /**
   * One virtual thread per id (a request each): no per-thread state is rebuilt, and with one shared
   * sequence even ids made by different threads in one millisecond stay in order.
   */
  @Test
  void idsMadeByAFreshVirtualThreadEachStillSortInOrder() throws Exception {
    UuidV7Generator generator = new UuidV7Generator(() -> NOW, new SplittableRandom(3)::nextLong);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 3000; i++) {
      UUID[] box = new UUID[1];
      Thread.ofVirtual().start(() -> box[0] = generator.next()).join();
      ids.add(box[0]);
    }
    assertStrictlyIncreasing(ids);
  }

  private static long millisOf(UUID id) {
    return id.getMostSignificantBits() >>> 16;
  }

  private static void assertStrictlyIncreasing(List<UUID> ids) {
    for (int i = 1; i < ids.size(); i++) {
      String previous = ids.get(i - 1).toString();
      String current = ids.get(i).toString();
      assertTrue(previous.compareTo(current) < 0, previous + " should sort before " + current);
    }
  }
}
