package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code storeql.iam.password-changed} and dispatches to {@link PasswordChangedHandler},
 * which sends the "your password was changed" email. Consumer lifecycle inherited from {@link
 * BaseKafkaConsumer}.
 */
@ApplicationScoped
class PasswordChangedConsumer extends BaseKafkaConsumer {

  @Inject PasswordChangedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.password-changed",
      defaultValue = "storeql.iam.password-changed")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-password-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-password-changed";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
