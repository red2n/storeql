package com.storeql.payment.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for the automatic-refund path. Polls {@code storeql.order.order-returned},
 * {@code storeql.order.order-cancelled} and {@code storeql.order.container-deposit-refunded}
 * (09.16: the deposit paid back at the till leaves the drawer) and dispatches each record to {@link
 * OrderEventHandler}. Consumer lifecycle is inherited from {@link BaseKafkaConsumer}; all business
 * logic lives in the handler (SRP).
 */
@ApplicationScoped
class OrderEventConsumer extends BaseKafkaConsumer {

  @Inject OrderEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-returned",
      defaultValue = "storeql.order.order-returned")
  String returnedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-cancelled",
      defaultValue = "storeql.order.order-cancelled")
  String cancelledTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.container-deposit-refunded",
      defaultValue = "storeql.order.container-deposit-refunded")
  String containerRefundTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-line-short-closed",
      defaultValue = "storeql.order.order-line-short-closed")
  String lineShortClosedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-line-substituted",
      defaultValue = "storeql.order.order-line-substituted")
  String lineSubstitutedTopic;

  @Override
  protected List<String> topics() {
    return List.of(
        returnedTopic,
        cancelledTopic,
        containerRefundTopic,
        lineShortClosedTopic,
        lineSubstitutedTopic);
  }

  @Override
  protected String consumerName() {
    return "payment-order-refund-consumer";
  }

  @Override
  protected String groupId() {
    return "payment-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
