package com.storeql.notification.mapper;

import com.storeql.notification.domain.Webhooks;
import com.storeql.notification.domain.Webhooks.Attempt;
import com.storeql.notification.domain.Webhooks.Delivery;
import com.storeql.notification.domain.Webhooks.Endpoint;
import com.storeql.notification.dto.WebhookDtos;
import java.util.List;

/** Webhook rows to what goes over the wire (22.6): never the sealed secret. */
public final class WebhookMappers {

  private WebhookMappers() {}

  public static WebhookDtos.EventTypeResponse toDto(Webhooks.EventType t) {
    return new WebhookDtos.EventTypeResponse(t.type(), t.description());
  }

  public static WebhookDtos.EndpointResponse toDto(Endpoint e) {
    return new WebhookDtos.EndpointResponse(
        e.id().toString(),
        e.url(),
        e.description(),
        e.events(),
        e.enabled(),
        e.disabledReason(),
        e.consecutiveFailures(),
        e.lastDeliveredAt(),
        e.createdAt(),
        e.updatedAt());
  }

  public static WebhookDtos.DeliveryResponse toDto(Delivery d) {
    return new WebhookDtos.DeliveryResponse(
        d.id().toString(),
        d.endpointId().toString(),
        d.eventId().toString(),
        d.eventType(),
        d.status(),
        d.attempts(),
        d.nextAttemptAt(),
        d.deliveredAt(),
        d.lastStatus(),
        d.lastError(),
        d.createdAt());
  }

  public static WebhookDtos.AttemptResponse toDto(Attempt a) {
    return new WebhookDtos.AttemptResponse(
        a.attempt(),
        a.attemptedAt(),
        a.statusCode(),
        a.error(),
        a.responseSnippet(),
        a.durationMs());
  }

  public static WebhookDtos.DeliveryDetail toDto(Delivery d, List<Attempt> attempts) {
    return WebhookDtos.DeliveryDetail.of(
        toDto(d), d.payload(), attempts.stream().map(WebhookMappers::toDto).toList());
  }
}
