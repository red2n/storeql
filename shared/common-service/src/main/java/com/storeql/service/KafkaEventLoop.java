package com.storeql.service;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Reliable Kafka poll loop shared by every consumer. Offsets are committed manually: with
 * auto-commit (the previous per-service pattern) a record whose handler failed was still acked, so
 * the event was lost forever (at-least-once silently became at-most-once).
 *
 * <p>Handler contract: return normally when the record is done (processed or deliberately skipped,
 * e.g. malformed payload); throw to mean "retry me". On a throw the partition is rewound to the
 * failed offset before committing, so earlier successes are acked and the failed record is
 * re-polled on the next tick. Combined with the processed_events dedupe inside each handler's
 * transaction this yields effectively-once processing.
 */
public final class KafkaEventLoop implements AutoCloseable {

  /** Processes one record; THROW to have the record redelivered, return normally to ack it. */
  @FunctionalInterface
  public interface Handler {
    void handle(String topic, String value);
  }

  private static final Logger LOG = System.getLogger(KafkaEventLoop.class.getName());

  /**
   * A record still failing after this many deliveries is dead-lettered instead of retried forever.
   */
  private static final int MAX_ATTEMPTS = 5;

  /** How long a dead letter may take to be acknowledged before it counts as not written. */
  static final long DLQ_ACK_TIMEOUT_SECONDS = 10;

  /** Pause after a poll in which a record failed, before it is redelivered. */
  private static final long RETRY_BACKOFF_MS = 2000;

  private final String name;
  private final List<String> topics;
  private final String bootstrap;
  private final Handler handler;
  private final KafkaConsumer<String, String> consumer;
  private final String offsetReset;
  private final ScheduledExecutorService scheduler;
  private volatile boolean running;

  /** When the broker last answered a metadata request; the readiness probe reads it. */
  private volatile long lastHealthyMillis;

  /** When the next broker probe is due. Only the poll thread touches it. */
  private long nextProbeAtMillis;

  /** How often the loop asks the broker for metadata, whatever the traffic. */
  static final long PROBE_EVERY_MILLIS = 10_000;

  /** How long a broker probe may take before it counts as not answering. */
  static final long PROBE_TIMEOUT_SECONDS = 2;

  /** Tracks retry count for the offset currently stuck at the head of each partition. */
  private final Map<TopicPartition, Attempt> attempts = new HashMap<>();

  /** Created lazily — most consumers never produce a dead letter in their lifetime. */
  private KafkaProducer<String, String> dlqProducer;

  private static final class Attempt {
    long offset = -1;
    int count;
  }

  /**
   * @param name identifies this loop in thread names and log lines, e.g. {@code
   *     "store-status-changed"}
   * @param bootstrap Kafka bootstrap servers, e.g. {@code "kafka:9092"}
   * @param groupId Kafka consumer-group id; must be unique per logical consumer across the cluster
   * @param topics the topics to subscribe to
   * @param handler processes each record; see {@link Handler}'s contract for retry semantics
   */
  public KafkaEventLoop(
      String name, String bootstrap, String groupId, List<String> topics, Handler handler) {
    this(name, bootstrap, groupId, topics, "earliest", handler);
  }

  /**
   * As above, saying where the group starts when it has no committed offset: {@code "earliest"}
   * (the default — a new consumer must not miss what was published before it existed) or {@code
   * "latest"} for a projection of live events that must not replay retained history when it is
   * first deployed.
   *
   * @param offsetReset {@code "earliest"}, {@code "latest"} or {@code "none"}; anything else is
   *     refused by the Kafka client at construction
   */
  public KafkaEventLoop(
      String name,
      String bootstrap,
      String groupId,
      List<String> topics,
      String offsetReset,
      Handler handler) {
    this.name = name;
    this.topics = List.copyOf(topics);
    this.offsetReset = offsetReset;
    this.bootstrap = bootstrap;
    this.handler = handler;
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
    // Manual commit: auto-commit would ack records whose handler failed (lost events).
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    // A poll's batch must finish well inside max.poll.interval.ms: bounded, configurable.
    props.put(
        ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
        String.valueOf(Cfg.getLong("storeql.kafka.max-poll-records", 100L)));
    this.consumer = new KafkaConsumer<>(props);
    this.consumer.subscribe(topics);
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, name);
              t.setDaemon(true);
              return t;
            });
  }

  /** Where this loop's group starts with no committed offset: what it was built with. */
  String offsetReset() {
    return offsetReset;
  }

  private static long pollDelayMillis() {
    return Math.max(1, Cfg.getLong("storeql.kafka.poll-delay-ms", 50L));
  }

  /**
   * Starts polling on a dedicated daemon thread, ticking every {@code storeql.kafka.poll-delay-ms}
   * (default 50) milliseconds after the previous poll finished. Idempotent to call only once per
   * instance — call {@link #close()} and construct a new loop to restart.
   */
  public void start() {
    running = true;
    markHealthy(System.currentTimeMillis());
    KafkaConsumerRegistry.track(this);
    // poll() already waits up to 500 ms for records, so the pause between ticks can be tiny: a
    // backlog is drained back to back instead of one batch per 2 s. A failed record is the one
    // case that must wait (see RETRY_BACKOFF_MS) so its 5 attempts are not burnt in a blink.
    long delayMs = pollDelayMillis();
    scheduler.scheduleWithFixedDelay(this::pollQuietly, 2000, delayMs, TimeUnit.MILLISECONDS);
    LOG.log(Level.INFO, "{0} started", name);
  }

  /**
   * One poll tick: fetch records, dispatch each to {@link #handler}, and commit offsets up to (but
   * not including) the first failure per partition. Never throws — {@link WakeupException} (from
   * {@link #close()}) and any other exception are caught and logged, so a bad tick never kills the
   * scheduler.
   */
  // Throwable on purpose: an Error escaping this tick would kill the scheduled task silently.
  @SuppressWarnings("PMD.AvoidCatchingThrowable")
  private void pollQuietly() {
    if (!running) {
      return;
    }
    try {
      probeBrokerIfDue();
      var records = consumer.poll(Duration.ofMillis(500));
      if (records.isEmpty()) {
        return;
      }
      Map<TopicPartition, Long> rewind = dispatch(records);
      // Seek resets the position, so commitSync() acks exactly up to (not including) failures.
      rewind.forEach(consumer::seek);
      consumer.commitSync();
      if (!rewind.isEmpty()) {
        Thread.sleep(RETRY_BACKOFF_MS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (WakeupException e) {
      LOG.log(Level.DEBUG, "{0} woken for shutdown", name);
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable e) {
      LOG.log(Level.WARNING, "{0} poll deferred: {1}", name, e.getMessage());
    }
  }

  /**
   * Hands each record to the handler, in order, and returns for each partition the offset to seek
   * back to: the first record that must be redelivered. Any failure of the handler, checked or not,
   * is a failed record: it is retried, or dead-lettered once its attempts run out. Only a {@link
   * VirtualMachineError} leaves this method, so one bad record cannot kill the poll loop.
   *
   * @param records the records of one poll, in the order the broker gave them
   * @return the partitions that must be rewound, with the offset to rewind each to
   */
  // Throwable on purpose: the handler contract is "throw to mean retry", and a handler's failure
  // of any kind must be a failed record. Only VirtualMachineError is let through.
  @SuppressWarnings("PMD.AvoidCatchingThrowable")
  Map<TopicPartition, Long> dispatch(Iterable<ConsumerRecord<String, String>> records) {
    // First failed offset per partition; later records of that partition are left unprocessed
    // to preserve per-partition ordering — unless that offset has exhausted its retries and was
    // dead-lettered, in which case processing of the partition resumes with the next record.
    Map<TopicPartition, Long> rewind = new HashMap<>();
    for (var rec : records) {
      var tp = new TopicPartition(rec.topic(), rec.partition());
      if (rewind.containsKey(tp)) {
        continue;
      }
      try {
        handler.handle(rec.topic(), rec.value());
        attempts.remove(tp);
      } catch (VirtualMachineError fatal) {
        throw fatal;
      } catch (Throwable e) {
        int count = recordAttempt(tp, rec.offset());
        if (count >= MAX_ATTEMPTS && deadLetter(rec, e)) {
          LOG.log(
              Level.ERROR,
              "{0}: record {1}@{2} failed {3} times, dead-lettered and skipped: {4}",
              name,
              tp,
              rec.offset(),
              count,
              e.getMessage());
          attempts.remove(tp);
        } else {
          rewind.put(tp, rec.offset());
          LOG.log(
              Level.WARNING,
              "{0}: record {1}@{2} failed (attempt {3}/{4}), will retry: {5}",
              name,
              tp,
              rec.offset(),
              count,
              MAX_ATTEMPTS,
              e.getMessage());
        }
      }
    }
    return rewind;
  }

  String name() {
    return name;
  }

  /**
   * Records that the broker answered at {@code atMillis}.
   *
   * @param atMillis the time of the answer, in epoch milliseconds
   */
  void markHealthy(long atMillis) {
    lastHealthyMillis = atMillis;
  }

  /**
   * Whether the broker has answered within {@code windowMillis} of {@code nowMillis}. A poll that
   * returns empty proves nothing about the broker, since the client returns empty while it cannot
   * connect; the answer that counts is a metadata request that succeeds.
   *
   * @param nowMillis the current time, in epoch milliseconds
   * @param windowMillis how long an answer stays good
   * @return {@code true} while the broker's last answer is within the window
   */
  boolean isHealthy(long nowMillis, long windowMillis) {
    return nowMillis - lastHealthyMillis <= windowMillis;
  }

  /**
   * Asks the broker for the first topic's metadata, at most once per {@link #PROBE_EVERY_MILLIS}.
   * Any answer, even an empty topic, counts; a timeout or an error does not.
   */
  private void probeBrokerIfDue() {
    long now = System.currentTimeMillis();
    if (now < nextProbeAtMillis || topics.isEmpty()) {
      return;
    }
    nextProbeAtMillis = now + PROBE_EVERY_MILLIS;
    try {
      consumer.partitionsFor(topics.get(0), Duration.ofSeconds(PROBE_TIMEOUT_SECONDS));
      markHealthy(System.currentTimeMillis());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "{0}: broker not answering: {1}", name, e.getMessage());
    }
  }

  /**
   * Returns the attempt count for this (partition, offset), resetting it if the offset moved on.
   *
   * @param tp the partition the failing record belongs to
   * @param offset the failing record's offset
   * @return the number of consecutive failed attempts at {@code offset} on {@code tp}, including
   *     this one
   */
  private int recordAttempt(TopicPartition tp, long offset) {
    Attempt a = attempts.computeIfAbsent(tp, k -> new Attempt());
    if (a.offset != offset) {
      a.offset = offset;
      a.count = 0;
    }
    a.count++;
    return a.count;
  }

  /**
   * Publishes a record that exhausted {@link #MAX_ATTEMPTS} to {@code <topic>.DLT}, creating the
   * producer on first use. The record is skipped only once the broker has acknowledged the dead
   * letter; otherwise it stays unacknowledged and is tried again on a later poll, so a dead letter
   * that never reaches Kafka can never lose the record.
   *
   * @param rec the record that exhausted its retries
   * @param cause the last handler failure for this record, kept for the log line
   * @return {@code true} when the dead letter was acknowledged and the record may be skipped
   */
  private boolean deadLetter(ConsumerRecord<String, String> rec, Throwable cause) {
    if (dlqProducer == null) {
      Properties props = new Properties();
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      dlqProducer = new KafkaProducer<>(props);
    }
    boolean acknowledged = publishDeadLetter(dlqProducer, rec);
    if (!acknowledged) {
      LOG.log(
          Level.ERROR,
          "{0}: dead letter for {1}@{2} not acknowledged, will retry (original cause: {3})",
          name,
          rec.topic(),
          rec.offset(),
          cause.getMessage());
    }
    return acknowledged;
  }

  /**
   * Sends one dead letter and waits, bounded, for the broker's acknowledgement. A send that fails
   * or times out is reported as not written: the caller must not treat the record as handled.
   *
   * @param producer the producer to send with
   * @param rec the record to dead-letter
   * @return {@code true} only when the broker acknowledged the write
   */
  static boolean publishDeadLetter(
      Producer<String, String> producer, ConsumerRecord<String, String> rec) {
    try {
      producer
          .send(new ProducerRecord<>(rec.topic() + ".DLT", rec.key(), rec.value()))
          .get(DLQ_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      LOG.log(
          Level.ERROR,
          "failed to publish dead letter for {0}@{1}: {2}",
          rec.topic(),
          rec.offset(),
          e.getMessage());
      return false;
    }
  }

  // Try-with-resources doesn't fit: the consumer must be closed with a bounded timeout only
  // *after* the scheduler has been asked to stop and given a chance to terminate, and the
  // producer is closed afterwards too — there's no single resource a TWR clause can own here.
  /**
   * Stops polling, closes the consumer (leaving its consumer group) and the DLQ producer if one was
   * created. Waits up to 3 seconds for the poll thread to terminate before forcing consumer
   * closure, so an in-flight {@code handler.handle(...)} call isn't cut off mid-transaction.
   */
  @SuppressWarnings("PMD.UseTryWithResources")
  @Override
  public void close() {
    running = false;
    KafkaConsumerRegistry.untrack(name);
    consumer.wakeup();
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      // Always close the consumer (releasing its sockets and leaving the consumer group) even if
      // the poll thread didn't terminate within the await window — otherwise a slow/blocked
      // poll() leaks the consumer for the life of the JVM.
      consumer.close(Duration.ofSeconds(2));
    }
    if (dlqProducer != null) {
      dlqProducer.close(Duration.ofSeconds(2));
    }
  }
}
