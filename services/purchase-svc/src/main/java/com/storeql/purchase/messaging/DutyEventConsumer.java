package com.storeql.purchase.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Listens for the releases from bond inventory-svc announces and hands them to the handler. */
@ApplicationScoped
class DutyEventConsumer extends BaseKafkaConsumer {

  @Inject DutyEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.duty-released",
      defaultValue = "storeql.inventory.duty-released")
  String dutyReleased;

  @Override
  protected List<String> topics() {
    return List.of(dutyReleased);
  }

  @Override
  protected String consumerName() {
    return "purchase-duty-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-duty";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(dutyReleased)) {
      handler.dutyReleased(value);
    }
  }
}
