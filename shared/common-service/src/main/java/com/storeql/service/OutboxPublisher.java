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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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
    try {
      long deadline = System.nanoTime() + drainBudgetMillis * 1_000_000L;
      int drained;
      do {
        // The claim (FOR UPDATE SKIP LOCKED) and the published-mark run in the repo's single
        // transaction, so two replicas draining at the same instant never claim the same row.
        drained = store.drainAndPublish(batchSize, this::publishBatch).size();
      } while (drained >= batchSize
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
  List<UUID> publishBatch(List<OutboxStore.PendingOutbox> rows) {
    var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>(rows.size());
    for (var row : rows) {
      futures.add(producer.send(new ProducerRecord<>(row.topic(), row.key(), row.payload())));
    }
    producer.flush();
    var published = new java.util.ArrayList<UUID>(rows.size());
    // Once a row fails, later rows about the SAME aggregate are not marked published, so the
    // retry sends them after it (per-aggregate order); other aggregates are unaffected.
    var failedKeys = new java.util.HashSet<String>();
    for (int i = 0; i < rows.size(); i++) {
      if (failedKeys.contains(rows.get(i).key())) continue;
      try {
        futures.get(i).get();
        published.add(rows.get(i).id());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        failedKeys.add(rows.get(i).key());
        LOG.log(
            Level.WARNING, "Publish failed for outbox {0}: {1}", rows.get(i).id(), e.getMessage());
      }
    }
    return published;
  }

  /** Stops the drain timer and closes the producer, if either was started. */
  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
    if (producer != null) producer.close(Duration.ofSeconds(2));
  }
}
