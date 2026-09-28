package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** {@code TrialNoticeIssued} from tenant-svc: a word to a business about its trial (21.13). */
@ApplicationScoped
class TrialNoticeIssuedConsumer extends BaseKafkaConsumer {

  @Inject TrialNoticeIssuedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.trial-notice-issued",
      defaultValue = "storeql.tenant.trial-notice-issued")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-trial-notice-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-trial";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
