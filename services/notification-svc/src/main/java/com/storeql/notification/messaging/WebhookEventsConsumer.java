package com.storeql.notification.messaging;

import com.storeql.notification.domain.Webhooks;
import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Polls every topic on the webhook catalogue (22.6) and hands each event to {@link
 * WebhookEventsHandler}, which queues a delivery for every endpoint of the event's business that
 * asked for its type. Its own consumer group, so the catalogue's other consumers in this service
 * are not affected by what webhooks subscribe to. Lifecycle inherited from {@link
 * BaseKafkaConsumer}.
 */
@ApplicationScoped
class WebhookEventsConsumer extends BaseKafkaConsumer {

  @Inject WebhookEventsHandler handler;

  @Override
  protected List<String> topics() {
    return Webhooks.topics();
  }

  @Override
  protected String consumerName() {
    return "notification-webhook-events-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-webhooks";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
