package com.storeql.notification.messaging;

import com.storeql.notification.service.WebhookFanout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An event off any catalogue topic (22.6), handed to the webhook fan-out. The consumer owns the
 * poll loop and this the dispatch, as every consumer and handler pair in this package does.
 */
@ApplicationScoped
public class WebhookEventsHandler {

  @Inject WebhookFanout fanout;

  /**
   * @param json the event as published
   * @return how many deliveries were queued
   */
  public int handle(String json) {
    return fanout.accept(json);
  }
}
