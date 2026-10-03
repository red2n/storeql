package com.storeql.ids;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Generates RFC 9562 version-7 UUIDs: 48 bits of Unix-epoch milliseconds, the version, a 12-bit
 * counter, the variant, then 62 random bits.
 *
 * <p>The millisecond and counter live in one atomic word advanced by compare-and-set: no lock, no
 * per-thread state to rebuild for every virtual thread, and no call parks another. Ids are strictly
 * increasing in byte order — the order PostgreSQL sorts {@code uuid} in — even when many are made
 * in the same millisecond or the clock steps back. The counter (RFC 9562 §6.2, method 1) starts at
 * a random value below 2048 each new millisecond, so at least 2048 ids fit in one; past that the
 * thread borrows the next millisecond rather than break order.
 *
 * <p>Ids from every thread share one sequence, so they are also increasing across threads (the
 * counter is not reset by another thread's claim within a millisecond), which suits index locality
 * even better. Uniqueness still rests on the 62 random bits — which is why those must come from a
 * secure source that never repeats a draw.
 *
 * <p>The random bits keep ids unguessable, but the timestamp is readable by anyone holding the id:
 * never use one as a secret, and never read business time out of it.
 */
final class UuidV7Generator {

  private static final int COUNTER_MAX = 0xFFF;
  private static final long COUNTER_SEED_MASK = 0x7FF;
  private static final long VERSION_7 = 0x7000L;
  private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;
  private static final long RANDOM_62_BITS = 0x3FFF_FFFF_FFFF_FFFFL;

  private final LongSupplier clock;
  private final LongSupplier random;

  /** Last millisecond (high 52 bits) and counter (low 12 bits) handed out, as one CAS-ed word. */
  private final AtomicLong state = new AtomicLong();

  /**
   * @param clock current time in Unix-epoch milliseconds
   * @param random secure random longs; must be thread-safe
   */
  UuidV7Generator(LongSupplier clock, LongSupplier random) {
    this.clock = clock;
    this.random = random;
  }

  /**
   * @return a new version-7 UUID, greater than every id this generator has returned on this thread
   */
  UUID next() {
    long claimed;
    while (true) {
      long current = state.get();
      long lastMillis = current >>> 12;
      int counter = (int) (current & COUNTER_MAX);
      long now = clock.getAsLong();
      long updated;
      if (now > lastMillis) {
        updated = (now << 12) | (random.getAsLong() & COUNTER_SEED_MASK);
      } else if (counter < COUNTER_MAX) {
        updated = current + 1;
      } else {
        updated = ((lastMillis + 1) << 12) | (random.getAsLong() & COUNTER_SEED_MASK);
      }
      if (state.compareAndSet(current, updated)) {
        claimed = updated;
        break;
      }
    }
    return layout(claimed >>> 12, claimed & COUNTER_MAX, random.getAsLong());
  }

  /**
   * The version-7 bit layout, shared by generated and derived ids.
   *
   * @param millis Unix-epoch milliseconds; the low 48 bits are used
   * @param twelveBits the counter or other value for the 12 bits after the version; low 12 used
   * @param sixtyTwoBits the value for the 62 bits after the variant; low 62 used
   */
  static UUID layout(long millis, long twelveBits, long sixtyTwoBits) {
    return new UUID(
        ((millis & 0xFFFF_FFFF_FFFFL) << 16) | VERSION_7 | (twelveBits & COUNTER_MAX),
        (sixtyTwoBits & RANDOM_62_BITS) | VARIANT_RFC);
  }
}
