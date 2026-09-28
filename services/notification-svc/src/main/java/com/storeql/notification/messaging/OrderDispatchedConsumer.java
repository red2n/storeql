package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code storeql.order.order-dispatched} and dispatches to {@link OrderDispatchedHandler},
 * which tells a shopper their delivery is on its way. Its own consumer group; lifecycle inherited
 * from {@link BaseKafkaConsumer} (SRP).
 */
@ApplicationScoped
class OrderDispatchedConsumer extends BaseKafkaConsumer {

  @Inject OrderDispatchedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-dispatched",
      defaultValue = "storeql.order.order-dispatched")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-order-dispatched-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-order-dispatched";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
