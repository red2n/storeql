package com.storeql.gateway.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The records waiting to be written: bounded, lock-free, and never waiting for anybody. Two lines
 * share one capacity — failures and everything else — so that when the queue is full a success is
 * given up (counted) and a failure takes the place of the oldest success. A failure is lost only
 * when the queue holds nothing but failures, because memory is the bound that wins.
 *
 * <p>The size is a counter kept beside the lines, not their length: it can run a few ahead of the
 * truth while threads are between taking a place and filling it, never past the capacity.
 */
final class FlowQueue {

  private final int capacity;
  private final Queue<FlowRecord> failures = new ConcurrentLinkedQueue<>();
  private final Queue<FlowRecord> others = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();
  private final LongAdder dropped = new LongAdder();

  FlowQueue(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  /**
   * @param record a finished request
   * @return true when the record was queued, false when it was given up (and counted)
   */
  boolean offer(FlowRecord record) {
    boolean failure = record.outcome() == FlowOutcome.FAILED;
    if (size.incrementAndGet() <= capacity) {
      (failure ? failures : others).add(record);
      return true;
    }
    if (failure && others.poll() != null) {
      // The place just taken is the evicted success's: the size stays at capacity.
      size.decrementAndGet();
      dropped.increment();
      failures.add(record);
      return true;
    }
    size.decrementAndGet();
    dropped.increment();
    return false;
  }

  /**
   * @param max the most to take
   * @return up to {@code max} records, failures first
   */
  List<FlowRecord> drain(int max) {
    List<FlowRecord> out = new ArrayList<>(Math.min(max, Math.max(size.get(), 0)));
    FlowRecord next;
    while (out.size() < max && (next = failures.poll()) != null) out.add(next);
    while (out.size() < max && (next = others.poll()) != null) out.add(next);
    size.addAndGet(-out.size());
    return out;
  }

  /**
   * @return how many records were thrown away unwritten
   */
  int discardAll() {
    int count = 0;
    while (failures.poll() != null) count++;
    while (others.poll() != null) count++;
    size.addAndGet(-count);
    return count;
  }

  /**
   * @return records given up because the queue was full, since it was made
   */
  long dropped() {
    return dropped.sum();
  }
}
