package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls OrderFulfilled, OrderReturned and OrderCancelled events from order-svc and dispatches each
 * to {@link OrderEventHandler} to update inventory positions and checkout stock holds. Consumer
 * lifecycle is inherited from {@link BaseKafkaConsumer}; all business logic lives in the handler
 * (SRP).
 */
@ApplicationScoped
class OrderEventConsumer extends BaseKafkaConsumer {

  @Inject OrderEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-fulfilled",
      defaultValue = "storeql.order.order-fulfilled")
  String fulfilledTopic;

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
      name = "storeql.kafka.topics.order-voided",
      defaultValue = "storeql.order.order-voided")
  String voidedTopic;

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
        fulfilledTopic,
        returnedTopic,
        cancelledTopic,
        voidedTopic,
        lineShortClosedTopic,
        lineSubstitutedTopic);
  }

  @Override
  protected String consumerName() {
    return "inventory-order-sync-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc-order-sync";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
