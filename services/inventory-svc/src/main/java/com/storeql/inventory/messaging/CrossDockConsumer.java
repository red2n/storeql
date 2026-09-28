package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The Kafka loop for purchase-svc's cross-dock allocation snapshots (cross-docking); what they mean
 * is {@link CrossDockAllocationsHandler}'s.
 */
@ApplicationScoped
class CrossDockConsumer extends BaseKafkaConsumer {

  @Inject CrossDockAllocationsHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.crossdock-allocations-set",
      defaultValue = "storeql.purchase.crossdock-allocations-set")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "inventory-crossdock-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc-crossdock";
  }

  @Override
  protected void handle(String t, String value) {
    handler.handle(value);
  }
}
