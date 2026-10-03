package com.storeql.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A small circuit breaker for {@link ServiceReader}: after {@code threshold} consecutive failed
 * reads the circuit opens for {@code openFor} and reads are refused at once (the caller sees the
 * same "unreachable" answer a failed read gives), so a service that is down is not hammered by
 * every request, each waiting out its own retries. After the open period one read is let through
 * (half-open); a success closes the circuit, a failure opens it again.
 *
 * <p>Lock-free (atomics only), so safe on virtual threads.
 */
final class ReadBreaker {

  private final int threshold;
  private final long openNanos;
  private final LongSupplier nanoClock;
  private final AtomicInteger failures = new AtomicInteger();
  private final AtomicLong openUntil = new AtomicLong();
  private volatile boolean everOpened;

  ReadBreaker(int threshold, Duration openFor, LongSupplier nanoClock) {
    this.threshold = Math.max(1, threshold);
    this.openNanos = openFor.toNanos();
    this.nanoClock = nanoClock;
  }

  /** True while the circuit is open: do not attempt the read. */
  boolean isOpen() {
    return everOpened && nanoClock.getAsLong() - openUntil.get() < 0;
  }

  void success() {
    failures.set(0);
  }

  void failure() {
    if (failures.incrementAndGet() >= threshold) {
      openUntil.set(nanoClock.getAsLong() + openNanos);
      everOpened = true;
    }
  }
}
