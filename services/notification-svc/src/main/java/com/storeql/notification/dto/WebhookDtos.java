package com.storeql.notification.dto;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Webhooks (22.6) on the wire. */
public final class WebhookDtos {

  private WebhookDtos() {}

  @Schema(name = "WebhookEventType")
  public record EventTypeResponse(String type, String description) {}

  @Schema(
      name = "CreateWebhookEndpointRequest",
      description =
          "Where to send which events. The URL is HTTPS on a public address; the events are from"
              + " GET /admin/webhooks/events.")
  public record CreateRequest(String url, String description, List<String> events) {}

  @Schema(
      name = "UpdateWebhookEndpointRequest",
      description =
          "Any of the fields; the rest stay as they are. enabled: true also clears a switch-off.")
  public record UpdateRequest(
      String url, String description, List<String> events, Boolean enabled) {}

  @Schema(name = "WebhookEndpoint", description = "An endpoint as listed: never the secret.")
  public record EndpointResponse(
      String id,
      String url,
      String description,
      List<String> events,
      boolean enabled,
      @Schema(description = "Why this service switched it off, when it did.") String disabledReason,
      int consecutiveFailures,
      Instant lastDeliveredAt,
      Instant createdAt,
      Instant updatedAt) {}

  @Schema(
      name = "WebhookEndpointCreated",
      description = "The endpoint as made, with its secret — shown here and never again.")
  public record CreatedResponse(
      String id,
      String url,
      String description,
      List<String> events,
      boolean enabled,
      String disabledReason,
      int consecutiveFailures,
      Instant lastDeliveredAt,
      Instant createdAt,
      Instant updatedAt,
      @Schema(description = "The signing secret. Copy it now: it is not shown again.")
          String secret) {

    public static CreatedResponse of(EndpointResponse r, String secret) {
      return new CreatedResponse(
          r.id(),
          r.url(),
          r.description(),
          r.events(),
          r.enabled(),
          r.disabledReason(),
          r.consecutiveFailures(),
          r.lastDeliveredAt(),
          r.createdAt(),
          r.updatedAt(),
          secret);
    }
  }

  @Schema(name = "WebhookSecret", description = "A rotated secret, shown once.")
  public record SecretResponse(String secret) {}

  @Schema(name = "WebhookPing", description = "The delivery the ping was queued as.")
  public record PingResponse(String deliveryId) {}

  @Schema(name = "WebhookDelivery")
  public record DeliveryResponse(
      String id,
      String endpointId,
      String eventId,
      String eventType,
      @Schema(description = "PENDING, DELIVERED or DEAD") String status,
      int attempts,
      Instant nextAttemptAt,
      Instant deliveredAt,
      Integer lastStatus,
      String lastError,
      Instant createdAt) {}

  @Schema(name = "WebhookAttempt", description = "One try, as it went.")
  public record AttemptResponse(
      int attempt,
      Instant attemptedAt,
      Integer statusCode,
      String error,
      String responseSnippet,
      int durationMs) {}

  @Schema(
      name = "WebhookDeliveryDetail",
      description = "A delivery with what was sent and every try.")
  public record DeliveryDetail(
      String id,
      String endpointId,
      String eventId,
      String eventType,
      String status,
      int attempts,
      Instant nextAttemptAt,
      Instant deliveredAt,
      Integer lastStatus,
      String lastError,
      Instant createdAt,
      @Schema(description = "The event as it was sent, inside the envelope's data.") String payload,
      List<AttemptResponse> attemptLog) {

    public static DeliveryDetail of(DeliveryResponse d, String payload, List<AttemptResponse> log) {
      return new DeliveryDetail(
          d.id(),
          d.endpointId(),
          d.eventId(),
          d.eventType(),
          d.status(),
          d.attempts(),
          d.nextAttemptAt(),
          d.deliveredAt(),
          d.lastStatus(),
          d.lastError(),
          d.createdAt(),
          payload,
          log);
    }
  }

  @Schema(name = "WebhookDeliveryPage")
  public record DeliveryPage(List<DeliveryResponse> items, String nextCursor) {}
}
