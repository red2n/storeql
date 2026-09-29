package com.storeql.customer.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for what a returned or voided sale does to a customer (return controls):
 * {@code storeql.order.order-returned} and {@code storeql.order.order-voided} take loyalty points
 * back, {@code storeql.payment.payment-refunded} credits store credit. Each record goes to {@link
 * ReturnEventsHandler}; the consumer lifecycle is inherited from {@link BaseKafkaConsumer}.
 */
@ApplicationScoped
class ReturnEventsConsumer extends BaseKafkaConsumer {

  @Inject ReturnEventsHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-returned",
      defaultValue = "storeql.order.order-returned")
  String returnedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-voided",
      defaultValue = "storeql.order.order-voided")
  String voidedTopic;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.payment-refunded",
      defaultValue = "storeql.payment.payment-refunded")
  String refundedTopic;

  @Override
  protected List<String> topics() {
    return List.of(returnedTopic, voidedTopic, refundedTopic);
  }

  @Override
  protected String consumerName() {
    return "customer-return-events-consumer";
  }

  @Override
  protected String groupId() {
    return "customer-svc-returns";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(returnedTopic)) {
      handler.handleReturned(value);
    } else if (topic.equals(voidedTopic)) {
      handler.handleVoided(value);
    } else if (topic.equals(refundedTopic)) {
      handler.handleRefunded(value);
    }
  }
}
