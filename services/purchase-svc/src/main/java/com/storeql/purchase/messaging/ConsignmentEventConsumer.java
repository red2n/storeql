package com.storeql.purchase.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Listens for the consignment sales inventory-svc announces and hands them to the handler. */
@ApplicationScoped
class ConsignmentEventConsumer extends BaseKafkaConsumer {

  @Inject ConsignmentEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.consignment-stock-sold",
      defaultValue = "storeql.inventory.consignment-stock-sold")
  String consignmentStockSold;

  @Override
  protected List<String> topics() {
    return List.of(consignmentStockSold);
  }

  @Override
  protected String consumerName() {
    return "purchase-consignment-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-consignment";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(consignmentStockSold)) {
      handler.stockSold(value);
    }
  }
}
