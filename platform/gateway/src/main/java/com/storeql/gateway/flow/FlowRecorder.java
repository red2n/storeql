package com.storeql.gateway.flow;

import com.storeql.gateway.GatewayConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Takes a record from the request that made it and gets it to Redis without that request ever
 * waiting. {@link #record} puts it on a bounded queue ({@link FlowQueue}) and returns; one virtual
 * thread wakes every flush interval and writes what is queued in batches.
 *
 * <p>Redis away is not the request's problem. A batch that cannot be written is thrown away and
 * counted, the drainer backs off (the flush interval doubled for each failure in a row, up to a
 * ceiling), and while it waits whatever arrives is thrown away too rather than kept to be written
 * late: the counters would be wrong for a minute that has passed, and the memory is better left to
 * the requests. One line in the log per interval says so.
 */
@ApplicationScoped
public class FlowRecorder {

  private static final Logger LOG = System.getLogger(FlowRecorder.class.getName());

  /** Where batches go. */
  @FunctionalInterface
  public interface Sink {
    /**
     * @param batch the records to count
     * @throws RuntimeException when they could not be written
     */
    void write(List<FlowRecord> batch);
  }

  /**
   * @param queueSize records that may wait
   * @param batchSize records written in one go
   * @param flushMillis how long the drainer sleeps between writes
   * @param backoffMaxMillis the longest it waits after failing to write
   */
  public record Settings(int queueSize, int batchSize, long flushMillis, long backoffMaxMillis) {
    public Settings {
      queueSize = Math.max(1, queueSize);
      batchSize = Math.max(1, batchSize);
      flushMillis = Math.max(1, flushMillis);
      backoffMaxMillis = Math.max(flushMillis, backoffMaxMillis);
    }
  }

  @Inject GatewayConfig config;
  @Inject FlowStore store;

  private Sink sink;
  private Settings settings;
  private LongSupplier nanos = System::nanoTime;
  private FlowQueue queue;
  private WarnOnce warn;

  /** Records thrown away because Redis was away, as opposed to the queue's own drops. */
  private final LongAdder discarded = new LongAdder();

  /**
   * One drain at a time, and {@link #flush} waits for one under way. Not a monitor: it spans I/O.
   */
  private final ReentrantLock drainLock = new ReentrantLock();

  // Guarded by drainLock.
  private int failuresInARow;
  private boolean backingOff;
  private long retryAtNanos;

  private volatile Thread drainer;
  private volatile boolean running;

  public FlowRecorder() {}

  /** For tests: a recorder writing to the given sink, not started. */
  FlowRecorder(Sink sink, Settings settings, LongSupplier nanos) {
    this.sink = sink;
    this.settings = settings;
    this.nanos = nanos;
    this.queue = new FlowQueue(settings.queueSize());
    this.warn = new WarnOnce(nanos);
  }

  @PostConstruct
  void startUp() {
    sink = store;
    settings =
        new Settings(
            config.flowQueueSize(),
            config.flowBatchSize(),
            config.flowFlushIntervalMs(),
            config.flowBackoffMaxMs());
    queue = new FlowQueue(settings.queueSize());
    warn = new WarnOnce(nanos);
    if (config.flowEnabled()) start();
  }

  /**
   * Forces CDI to make the recorder, and so start the drainer, at boot instead of at the first
   * request.
   */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    // nothing to do: being observed is enough to have the bean built
  }

  @PreDestroy
  void shutDown() {
    stop();
  }

  /**
   * Queues a finished request. Never waits, never throws for a full queue.
   *
   * @param record the request, as {@link FlowRecordingFilter} described it
   */
  public void record(FlowRecord record) {
    queue.offer(record);
  }

  /**
   * @return records given up since start: a full queue, or Redis away
   */
  public long droppedSinceStart() {
    return queue.dropped() + discarded.sum();
  }

  /** Starts the drainer, once. */
  void start() {
    if (drainer != null) return;
    running = true;
    Thread thread = Thread.ofVirtual().name("flow-recorder").unstarted(this::loop);
    drainer = thread;
    thread.start();
  }

  /** Stops the drainer and writes what is left, once, if Redis will have it. */
  void stop() {
    running = false;
    Thread thread = drainer;
    drainer = null;
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(TimeUnit.SECONDS.toMillis(2));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    flush();
  }

  boolean running() {
    return running;
  }

  private void loop() {
    while (running) {
      try {
        Thread.sleep(settings.flushMillis());
      } catch (InterruptedException e) {
        return;
      }
      try {
        flush();
      } catch (RuntimeException e) {
        warn.warn(LOG, "System-health recording failed", e);
      }
    }
  }

  /**
   * Writes everything queued now, in batches, and waits for a write already under way, so that when
   * it returns every record queued before the call has been written or given up. The drainer calls
   * it every interval; shutdown and tests call it to be sure.
   */
  public void flush() {
    drainLock.lock();
    try {
      long now = nanos.getAsLong();
      if (backingOff && now - retryAtNanos < 0) {
        discarded.add(queue.discardAll());
        return;
      }
      // At most a queue's worth a time, so that traffic which never lets the queue empty cannot
      // keep one flush from ever returning.
      int turns = settings.queueSize() / settings.batchSize() + 1;
      for (int turn = 0; turn < turns; turn++) {
        List<FlowRecord> batch = queue.drain(settings.batchSize());
        if (batch.isEmpty()) return;
        try {
          sink.write(batch);
          failuresInARow = 0;
          backingOff = false;
        } catch (RuntimeException e) {
          int lost = batch.size() + queue.discardAll();
          discarded.add(lost);
          failuresInARow = Math.min(failuresInARow + 1, 30);
          backingOff = true;
          retryAtNanos = now + TimeUnit.MILLISECONDS.toNanos(backoffMillis());
          warn.warn(
              LOG, "System-health recording is unavailable; discarding " + lost + " record(s)", e);
          return;
        }
      }
    } finally {
      drainLock.unlock();
    }
  }

  private long backoffMillis() {
    return Math.min(settings.flushMillis() << failuresInARow, settings.backoffMaxMillis());
  }
}
