package com.storeql.notification.service;

import com.storeql.ids.Ids;
import jakarta.json.JsonObject;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The id a webhook delivery is deduplicated on (22.6): the event's own {@code eventId}, or — for
 * the three catalogue events whose producers publish none (OrderPlaced, PaymentCaptured,
 * PriceChanged) — one derived from what identifies the fact.
 *
 * <p>Without it those events were dropped by the fan-out and no subscriber ever heard of an order
 * placed or a payment captured. A derived id is stable (the same fact redelivered by Kafka derives
 * the same id, so it is still queued once per endpoint). {@code PriceChanged} has no key of its own
 * — two changes to one price list are indistinguishable — so it has no fallback; its producer must
 * publish an {@code eventId}.
 */
public final class WebhookEventIds {

  private WebhookEventIds() {}

  /** Event type to the payload field that identifies one occurrence of it. */
  private static final Map<String, String> NATURAL_KEY =
      Map.of("OrderPlaced", "orderId", "PaymentCaptured", "paymentId");

  /**
   * @param type the event's type
   * @param obj the event as published
   * @return its {@code eventId}, else the derived id, else empty when it has neither (the producer
   *     must add an {@code eventId}); a malformed id throws {@link IllegalArgumentException}
   */
  public static Optional<UUID> of(String type, JsonObject obj) {
    String own = obj.getString("eventId", null);
    if (own != null) {
      return Optional.of(Ids.parse(own));
    }
    String field = NATURAL_KEY.get(type);
    String key = field == null ? null : obj.getString(field, null);
    if (key == null) {
      return Optional.empty();
    }
    return Optional.of(Ids.derived(Ids.parse(key), "webhook:" + type));
  }
}
