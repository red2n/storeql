package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@code DunningNoticeIssued} from tenant-svc: the notice a business is emailed when it is late.
 */
@ApplicationScoped
class DunningNoticeIssuedConsumer extends BaseKafkaConsumer {

  @Inject DunningNoticeIssuedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.dunning-notice-issued",
      defaultValue = "storeql.tenant.dunning-notice-issued")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-dunning-notice-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-dunning";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
