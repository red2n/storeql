package com.storeql.order.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls inventory-svc's WavePicked events and hands each to {@link WavePickedHandler}. */
@ApplicationScoped
class WavePickedConsumer extends BaseKafkaConsumer {

  @Inject WavePickedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.wave-picked",
      defaultValue = "storeql.inventory.wave-picked")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "order-wave-picked-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc-wave-picked";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
