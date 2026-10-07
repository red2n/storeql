package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Kafka infrastructure for {@code storeql.order.no-receipt-return-recorded}. Dispatches each record
 * to {@link NoReceiptReturnHandler}; the business logic is the handler's (SRP).
 */
@ApplicationScoped
class NoReceiptReturnConsumer extends BaseKafkaConsumer {

  @Inject NoReceiptReturnHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.no-receipt-return-recorded",
      defaultValue = "storeql.order.no-receipt-return-recorded")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "inventory-no-receipt-return-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
