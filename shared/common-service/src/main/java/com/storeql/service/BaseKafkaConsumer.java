package com.storeql.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Template base for all Kafka consumers. Owns the shared lifecycle — start, stop, eager
 * initialisation, logging — so concrete subclasses only provide what varies per topic:
 *
 * <ul>
 *   <li>{@link #topics()} — one or more topics to subscribe to. For config-driven topics, inject
 *       the value with {@code @ConfigProperty} in the subclass and return it here. For fixed topic
 *       sets, return a {@code List.of()} constant.
 *   <li>{@link #consumerName()} — unique name used in thread labels and log lines.
 *   <li>{@link #groupId()} — Kafka consumer-group id. Each logically distinct consumer must use a
 *       different group so every consumer receives every message independently.
 *   <li>{@link #handle(String, String)} — dispatch one record to the business handler. Return
 *       normally to ack; throw to trigger seek-back and retry.
 * </ul>
 *
 * <p>The {@code storeql.kafka.enabled} and {@code storeql.kafka.bootstrap} properties are shared
 * across every consumer and are injected here; subclasses need not redeclare them.
 *
 * <p>CDI injects superclass fields before calling {@link PostConstruct}, so subclass
 * {@code @ConfigProperty} topic fields are available when {@link #start()} runs.
 */
public abstract class BaseKafkaConsumer {

  @Inject
  @ConfigProperty(name = "storeql.kafka.enabled", defaultValue = "true")
  private boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "storeql.kafka.bootstrap", defaultValue = "localhost:9092")
  private String bootstrap;

  private final Logger log = System.getLogger(getClass().getName());
  private KafkaEventLoop loop;

  /** Topics to subscribe to. Inject per-topic config in the subclass and return it here. */
  protected abstract List<String> topics();

  /** Unique consumer loop name; used in thread names and log lines. */
  protected abstract String consumerName();

  /** Kafka consumer-group id. Must be distinct per logical consumer across the whole cluster. */
  protected abstract String groupId();

  /**
   * Handle one Kafka record. Return normally to ack the offset; throw any exception to rewind the
   * partition and retry (the {@link KafkaEventLoop} seek-back mechanism).
   */
  protected abstract void handle(String topic, String value);

  /**
   * Where this consumer's group starts when it has no committed offset: {@code earliest} by
   * default, so a new consumer misses nothing published before it existed. A projection of live
   * events that must not replay retained history on its first deployment answers {@code latest}.
   */
  protected String offsetReset() {
    return "earliest";
  }

  /**
   * CDI observer — makes the bean eager so polling starts at application startup.
   * {@code @ApplicationScoped} beans are otherwise lazy and would never be instantiated (so {@link
   * #start()} would never run) without something observing them.
   *
   * @param e the CDI initialization event payload; unused, only its firing matters
   */
  void onStart(
      @Observes @Initialized(jakarta.enterprise.context.ApplicationScoped.class) Object e) {
    /* eager init trigger only */
  }

  /**
   * Builds and starts this consumer's {@link KafkaEventLoop}, or no-ops if {@code
   * storeql.kafka.enabled=false}. A failure to start (bad bootstrap config, broker unreachable at
   * boot, ...) is caught and does not fail the deployment — instead it's recorded in {@link
   * KafkaConsumerRegistry#markFailed} so {@link HealthChecks.KafkaConsumerReadiness} reports the
   * service as not-ready rather than silently never processing events.
   */
  @PostConstruct
  final void start() {
    if (!kafkaEnabled) {
      log.log(Level.INFO, "{0} disabled (storeql.kafka.enabled=false)", consumerName());
      return;
    }
    try {
      loop =
          new KafkaEventLoop(
              consumerName(), bootstrap, groupId(), topics(), offsetReset(), this::handle);
      loop.start();
      KafkaConsumerRegistry.clear(consumerName());
    } catch (Exception e) {
      KafkaConsumerRegistry.markFailed(consumerName());
      log.log(
          Level.ERROR,
          "{0} failed to start, readiness will report DOWN: {1}",
          consumerName(),
          e.getMessage());
    }
  }

  /** Clears this consumer's registry entry and closes its {@link KafkaEventLoop}, if started. */
  @PreDestroy
  final void stop() {
    KafkaConsumerRegistry.clear(consumerName());
    if (loop != null) loop.close();
  }
}
