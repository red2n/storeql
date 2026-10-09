package com.storeql.pricing.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Output VAT arrives from order-svc as the events of a sale and of what comes back from it. */
@ApplicationScoped
class SalesTaxEventConsumer extends BaseKafkaConsumer {

  @Inject SalesTaxEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.sales-tax",
      defaultValue =
          "storeql.order.order-confirmed,storeql.order.order-returned,storeql.order.order-voided,"
              + "storeql.order.order-cancelled,storeql.order.no-receipt-return-recorded")
  List<String> sourceTopics;

  @Override
  protected List<String> topics() {
    return sourceTopics;
  }

  @Override
  protected String consumerName() {
    return "pricing-output-vat-consumer";
  }

  @Override
  protected String groupId() {
    return "pricing-svc-output-vat";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
