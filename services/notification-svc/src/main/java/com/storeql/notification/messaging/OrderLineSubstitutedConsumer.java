package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code storeql.order.order-line-substituted} and dispatches to {@link
 * OrderLineSubstitutedHandler}, which tells a shopper the store substituted an item of their order.
 * Its own consumer group; lifecycle inherited from {@link BaseKafkaConsumer} (SRP).
 */
@ApplicationScoped
class OrderLineSubstitutedConsumer extends BaseKafkaConsumer {

  @Inject OrderLineSubstitutedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-line-substituted",
      defaultValue = "storeql.order.order-line-substituted")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-order-line-substituted-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-order-line-substituted";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
