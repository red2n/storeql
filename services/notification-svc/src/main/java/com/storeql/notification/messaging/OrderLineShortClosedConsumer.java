package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code storeql.order.order-line-short-closed} and dispatches to {@link
 * OrderLineShortClosedHandler}, which tells a shopper an item of their order was unavailable. Its
 * own consumer group; lifecycle inherited from {@link BaseKafkaConsumer} (SRP).
 */
@ApplicationScoped
class OrderLineShortClosedConsumer extends BaseKafkaConsumer {

  @Inject OrderLineShortClosedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-line-short-closed",
      defaultValue = "storeql.order.order-line-short-closed")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-order-line-short-closed-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-order-line-short-closed";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
