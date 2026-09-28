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

  private final String name;
  private final String bootstrap;
  private final Handler handler;
  private final KafkaConsumer<String, String> consumer;
  private final String offsetReset;
  private final ScheduledExecutorService scheduler;
  private volatile boolean running;

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

  /**
   * Starts polling on a dedicated daemon thread, ticking every 2 seconds. Idempotent to call only
   * once per instance — call {@link #close()} and construct a new loop to restart.
   */
  /** Where this loop's group starts with no committed offset: what it was built with. */
  String offsetReset() {
    return offsetReset;
  }

  public void start() {
    running = true;
    scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 2, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "{0} started", name);
  }

  /**
   * One poll tick: fetch records, dispatch each to {@link #handler}, and commit offsets up to (but
   * not including) the first failure per partition. Never throws — {@link WakeupException} (from
   * {@link #close()}) and any other exception are caught and logged, so a bad tick never kills the
   * scheduler.
   */
  private void pollQuietly() {
    if (!running) {
      return;
    }
    try {
      var records = consumer.poll(Duration.ofMillis(500));
      if (records.isEmpty()) {
        return;
      }
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
        } catch (RuntimeException e) {
          int count = recordAttempt(tp, rec.offset());
          if (count >= MAX_ATTEMPTS) {
            LOG.log(
                Level.ERROR,
                "{0}: record {1}@{2} failed {3} times, dead-lettering and skipping: {4}",
                name,
                tp,
                rec.offset(),
                count,
                e.getMessage());
            deadLetter(rec, e);
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
      // Seek resets the position, so commitSync() acks exactly up to (not including) failures.
      rewind.forEach(consumer::seek);
      consumer.commitSync();
    } catch (WakeupException e) {
      LOG.log(Level.DEBUG, "{0} woken for shutdown", name);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "{0} poll deferred: {1}", name, e.getMessage());
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
   * Publishes a record that exhausted {@link #MAX_ATTEMPTS} to {@code <topic>.DLT}, lazily creating
   * the producer on first use. A failure to publish the dead letter itself is logged (with the
   * original cause) and swallowed — the record is skipped either way so the partition isn't stuck
   * forever.
   *
   * @param rec the record that exhausted its retries
   * @param cause the last handler failure for this record
   */
  private void deadLetter(ConsumerRecord<String, String> rec, RuntimeException cause) {
    if (dlqProducer == null) {
      Properties props = new Properties();
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      dlqProducer = new KafkaProducer<>(props);
    }
    try {
      dlqProducer.send(new ProducerRecord<>(rec.topic() + ".DLT", rec.key(), rec.value()));
    } catch (RuntimeException e) {
      LOG.log(
          Level.ERROR,
          "{0}: failed to publish dead letter for {1}@{2} (original cause: {3}): {4}",
          name,
          rec.topic(),
          rec.offset(),
          cause.getMessage(),
          e.getMessage());
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
