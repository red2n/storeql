package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Polls {@code storeql.iam.password-reset-requested} and dispatches to {@link
 * PasswordResetRequestedHandler}, which sends the reset email. Consumer lifecycle inherited from
 * {@link BaseKafkaConsumer} (SRP).
 */
@ApplicationScoped
class PasswordResetRequestedConsumer extends BaseKafkaConsumer {

  @Inject PasswordResetRequestedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.password-reset-requested",
      defaultValue = "storeql.iam.password-reset-requested")
  String topic;

  @Override
  protected List<String> topics() {
    return List.of(topic);
  }

  @Override
  protected String consumerName() {
    return "notification-password-reset-requested-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-password-reset";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
