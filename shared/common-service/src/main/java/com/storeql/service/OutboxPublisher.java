package com.storeql.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Drains a service's transactional outbox to Kafka on a timer (at-least-once delivery; consumers
 * must be idempotent). Shared across all services — each provides a {@link ServiceSettings} (for
 * Kafka config) and an {@link OutboxStore} (its repo). Resilient: if Kafka is down, rows stay
 * pending and retry on the next tick.
 *
 * <p>Eager startup ({@code @Observes @Initialized}) because CDI instantiates
 * {@code @ApplicationScoped} lazily — a {@code @PostConstruct}-only bean would never run.
 */
@ApplicationScoped
public class OutboxPublisher {

  private static final Logger LOG = System.getLogger(OutboxPublisher.class.getName());

  @Inject ServiceSettings settings;

  /** Optional: a service without an outbox provides no OutboxStore bean; publisher no-ops. */
  @Inject Instance<OutboxStore> storeInstance;

  private OutboxStore store;
  private Producer<String, String> producer;
  private ScheduledExecutorService scheduler;
  private int batchSize = 100;
  private long drainBudgetMillis = 5000L;

  /**
   * CDI observer — makes this {@code @ApplicationScoped} bean eager so {@link #start()} runs at
   * application startup instead of never (lazy beans are only instantiated on first injection, and
   * nothing injects {@code OutboxPublisher} directly).
   *
   * @param event the CDI initialization event payload; unused, only its firing matters
   */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* makes the bean eager */
  }

  /**
   * Builds the Kafka producer and starts the drain-timer, or no-ops if Kafka is disabled or this
   * service has no {@link OutboxStore} bean (no outbox table).
   */
  @PostConstruct
  void start() {
    if (!settings.kafkaEnabled()) {
      LOG.log(Level.INFO, "Outbox publisher disabled (kafka disabled)");
      return;
    }
    if (storeInstance.isUnsatisfied()) {
      LOG.log(Level.INFO, "Outbox publisher disabled (no OutboxStore — service has no outbox)");
      return;
    }
    // A service may have several repositories extending BaseOutboxRepository (= several
    // OutboxStore beans), but they all drain the same schema-level outbox table — any one
    // suffices. Instance.get() would throw AmbiguousResolutionException here.
    this.store = storeInstance.iterator().next();
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.kafkaBootstrap());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
    this.producer = new KafkaProducer<>(props);

    // storeql.outbox.poll-millis (when > 0) overrides the whole-second interval for a tighter idle
    // tick; a drain that returns a full batch does not wait for the tick at all (see drainQuietly).
    long pollMillis = cfgLong("storeql.outbox.poll-millis", 0L);
    if (pollMillis <= 0) pollMillis = settings.outboxPollSeconds() * 1000L;
    this.batchSize = (int) Math.max(1, cfgLong("storeql.outbox.batch-size", 100L));
    this.drainBudgetMillis = Math.max(0, cfgLong("storeql.outbox.drain-budget-ms", 5000L));
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, settings.serviceName() + "-outbox-publisher");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::drainQuietly, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    long purgeMinutes = Math.max(1, cfgLong("storeql.outbox.purge.interval-minutes", 60L));
    if (cfgLong("storeql.outbox.purge.enabled", 1L) > 0) {
      scheduler.scheduleWithFixedDelay(
          this::purgeQuietly, Math.min(5, purgeMinutes), purgeMinutes, TimeUnit.MINUTES);
    }
    LOG.log(Level.INFO, "Outbox publisher started (bootstrap={0})", settings.kafkaBootstrap());
  }

  /**
   * One drain tick: claims up to {@code storeql.outbox.batch-size} (default 100) pending rows via
   * {@link #store} and publishes them, and keeps going while batches come back full, within {@code
   * storeql.outbox.drain-budget-ms} (default 5000), so a backlog drains at the speed of Kafka and
   * not at one batch per tick. Never throws: any failure (claim query error, Kafka unreachable) is
   * logged and deferred to the next tick, since rows that aren't confirmed published stay pending.
   */
  void drainQuietly() {
    try (var lease = store.tryDrainLock().orElse(null)) {
      if (lease == null) return; // another replica is draining this schema
      long deadline = System.nanoTime() + drainBudgetMillis * 1_000_000L;
      int claimed;
      do {
        // Keep going while batches come back full: a full claim may leave rows behind it. The
        // condition is on rows claimed, not rows published: a batch that failed to publish is still
        // a full batch, and its failed rows are backed off, so the next claim reaches past them.
        claimed = store.drainOnce(lease, batchSize, this::publishBatch).claimed();
      } while (claimed >= batchSize
          && System.nanoTime() < deadline
          && !Thread.currentThread().isInterrupted());
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Outbox drain deferred: " + e.getMessage());
    }
  }

  /**
   * Housekeeping tick: deletes PUBLISHED outbox rows older than {@code
   * storeql.outbox.retention-days} (default 7) and consumer dedupe rows older than {@code
   * storeql.processed-events.retention-days} (default 30; must exceed the longest Kafka topic
   * retention plus consumer lag, Kafka's own default being 7 days), in batches of {@code
   * storeql.outbox.purge.batch-size} (default 1000), at most {@code
   * storeql.outbox.purge.max-batches} (default 50) per kind per tick. Never throws.
   */
  void purgeQuietly() {
    try {
      long outboxDays = Math.max(1, cfgLong("storeql.outbox.retention-days", 7L));
      long dedupeDays = Math.max(1, cfgLong("storeql.processed-events.retention-days", 30L));
      int batch = (int) Math.max(1, cfgLong("storeql.outbox.purge.batch-size", 1000L));
      int maxBatches = (int) Math.max(1, cfgLong("storeql.outbox.purge.max-batches", 50L));
      java.time.Instant now = java.time.Instant.now();
      int outbox = 0;
      for (int i = 0; i < maxBatches; i++) {
        int n = store.purgePublished(now.minus(Duration.ofDays(outboxDays)), batch);
        outbox += n;
        if (n < batch) break;
      }
      int dedupe = 0;
      for (int i = 0; i < maxBatches; i++) {
        int n = store.purgeProcessedEvents(now.minus(Duration.ofDays(dedupeDays)), batch);
        dedupe += n;
        if (n < batch) break;
      }
      if (outbox + dedupe > 0) {
        LOG.log(
            Level.INFO, "Purged {0} published outbox rows, {1} processed_events", outbox, dedupe);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Outbox purge deferred: " + e.getMessage());
    }
  }

  private static long cfgLong(String key, long fallback) {
    return Cfg.getLong(key, fallback);
  }

  /**
   * Pipelines the whole batch (one flush) instead of awaiting each send, returning exactly the ids
   * that were confirmed delivered — N Kafka roundtrips become ~1. A row that fails to send isn't
   * returned, so it stays unpublished and retries next tick (at-least-once).
   *
   * @param rows the pending rows claimed by {@link #drainQuietly()}
   * @return the ids of {@code rows} whose send was confirmed by the broker; a subset when some
   *     sends failed or timed out
   */
  OutboxStore.PublishOutcome publishBatch(List<OutboxStore.PendingOutbox> rows) {
    // Per-aggregate order: an aggregate's Nth row is sent only after its N-1 predecessors are
    // acknowledged, and a row whose predecessor failed is not sent at all, so Kafka sees an
    // aggregate's events in the order they were written and no row is sent twice. Rows are sent in
    // waves (the Nth row of every aggregate together), so one batch still pipelines across
    // aggregates.
    Map<String, Integer> seen = new HashMap<>();
    List<List<Integer>> waves = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      int n = seen.merge(rows.get(i).key(), 1, Integer::sum) - 1;
      if (waves.size() <= n) waves.add(new ArrayList<>());
      waves.get(n).add(i);
    }
    Set<String> failedKeys = new HashSet<>();
    List<UUID> published = new ArrayList<>(rows.size());
    Map<UUID, String> failed = new LinkedHashMap<>();
    for (List<Integer> wave : waves) {
      List<Integer> sent = new ArrayList<>(wave.size());
      List<Future<RecordMetadata>> futures = new ArrayList<>(wave.size());
      for (int i : wave) {
        OutboxStore.PendingOutbox row = rows.get(i);
        if (failedKeys.contains(row.key())) continue;
        sent.add(i);
        futures.add(producer.send(new ProducerRecord<>(row.topic(), row.key(), row.payload())));
      }
      producer.flush();
      for (int k = 0; k < sent.size(); k++) {
        OutboxStore.PendingOutbox row = rows.get(sent.get(k));
        try {
          futures.get(k).get();
          published.add(row.id());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return new OutboxStore.PublishOutcome(published, failed);
        } catch (Exception e) {
          // Exception, not Throwable: an Error is not a failed send and must not be recorded as
          // one.
          failedKeys.add(row.key());
          String reason = String.valueOf(e.getMessage());
          failed.put(row.id(), reason);
          LOG.log(Level.WARNING, "Publish failed for outbox {0}: {1}", row.id(), reason);
        }
      }
    }
    return new OutboxStore.PublishOutcome(published, failed);
  }

  /** Stops the drain timer and closes the producer, if either was started. */
  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
    if (producer != null) producer.close(Duration.ofSeconds(2));
  }
}
