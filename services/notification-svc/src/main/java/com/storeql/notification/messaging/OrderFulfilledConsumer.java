package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code storeql.order.order-fulfilled} and dispatches to {@link OrderFulfilledHandler},
 * which tells a shopper their pickup order is ready to collect. Its own consumer group; lifecycle
 * inherited from {@link BaseKafkaConsumer} (SRP).
 */
@ApplicationScoped
class OrderFulfilledConsumer extends BaseKafkaConsumer {

  @Inject OrderFulfilledHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-fulfilled",
      defaultValue = "storeql.order.order-fulfilled")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-order-fulfilled-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-order-fulfilled";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
