package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Listens for purchase-svc's word on how a variant is fulfilled and hands it to the handler. */
@ApplicationScoped
class VariantSourcingConsumer extends BaseKafkaConsumer {

  @Inject VariantSourcingHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.variant-sourcing-changed",
      defaultValue = "storeql.purchase.variant-sourcing-changed")
  String sourcingTopic;

  @Override
  protected List<String> topics() {
    return List.of(sourcingTopic);
  }

  @Override
  protected String consumerName() {
    return "inventory-variant-sourcing-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc-sourcing";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
