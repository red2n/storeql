package com.storeql.gateway.flow;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A warning that says it once an interval, with a count of the times it was skipped: when Redis is
 * away every request and every drain lands here, and a line each would bury the log. The same shape
 * as {@code RateCounter}'s, so the two read alike.
 */
final class WarnOnce {

  private static final long INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();

  private final LongSupplier nanos;
  private final AtomicLong skipped = new AtomicLong();
  private final AtomicLong lastNanos;

  WarnOnce(LongSupplier nanos) {
    this.nanos = nanos;
    this.lastNanos = new AtomicLong(nanos.getAsLong() - INTERVAL_NANOS);
  }

  /**
   * @param log where the line goes
   * @param what what is wrong, in words
   * @param cause the failure, shown on the line that is written
   */
  void warn(Logger log, String what, Throwable cause) {
    long count = skipped.incrementAndGet();
    long now = nanos.getAsLong();
    long last = lastNanos.get();
    if (now - last >= INTERVAL_NANOS && lastNanos.compareAndSet(last, now)) {
      long since = skipped.getAndSet(0);
      log.log(
          Level.WARNING,
          "{0} ({1} time(s) since the last notice): {2}",
          what,
          Math.max(since, count),
          String.valueOf(cause));
    }
  }
}
