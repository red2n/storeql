package com.storeql.inventory.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The confirmations that feed the waiting list of orders to be picked (wave picking), on a consumer
 * group of their own that starts at the <em>latest</em> offset: a projection of live confirmations
 * must not, on its first deployment, replay every retained confirmation into the list — an order
 * confirmed before this existed is handed over as it always was — and the deductions' group keeps
 * its own offsets untouched. The handler is the order handler's, which knows the event.
 */
@ApplicationScoped
class AwaitingOrdersConsumer extends BaseKafkaConsumer {

  @Inject OrderEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-confirmed",
      defaultValue = "storeql.order.order-confirmed")
  String confirmedTopic;

  @Override
  protected List<String> topics() {
    return List.of(confirmedTopic);
  }

  @Override
  protected String consumerName() {
    return "inventory-awaiting-orders-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc-awaiting-orders";
  }

  @Override
  protected String offsetReset() {
    return "latest";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
