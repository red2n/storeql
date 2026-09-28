package com.storeql.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Webhooks to a business's own systems (22.6): the endpoints, the deliveries, the attempts. */
public final class Webhooks {

  private Webhooks() {}

  public static final String PENDING = "PENDING";
  public static final String DELIVERED = "DELIVERED";
  public static final String DEAD = "DEAD";
  public static final List<String> STATUSES = List.of(PENDING, DELIVERED, DEAD);

  /** A delivery made by hand to prove the wiring; never on the catalogue, always delivered. */
  public static final String PING = "Ping";

  /** Every secret starts with this, so a receiver's configuration is legible at a glance. */
  public static final String SECRET_PREFIX = "whsec_";

  /**
   * An event type a business may ask to be told of, the topic it arrives on, and a line about it.
   */
  public record EventType(String type, String topic, String description) {}

  /** What a business may subscribe to. Each is a fact its own systems act on. */
  public static final List<EventType> CATALOGUE =
      List.of(
          new EventType(
              "OrderPlaced",
              "storeql.order.order-placed",
              "An order was placed, online or at a till"),
          new EventType(
              "OrderConfirmed",
              "storeql.order.order-confirmed",
              "An order was paid for and confirmed"),
          new EventType(
              "OrderFulfilled",
              "storeql.order.order-fulfilled",
              "An order was picked and packed (a till sale: handed over)"),
          new EventType(
              "OrderDispatched",
              "storeql.order.order-dispatched",
              "A picked delivery order left the store with a carrier"),
          new EventType(
              "OrderCollected",
              "storeql.order.order-collected",
              "A picked pickup order was collected by its shopper"),
          new EventType(
              "OrderLineShortClosed",
              "storeql.order.order-line-short-closed",
              "A line of an online order was closed short: the store could not fill it"),
          new EventType(
              "OrderLineSubstituted",
              "storeql.order.order-line-substituted",
              "A line of an online order was replaced by a substitute the store put in the bag"),
          new EventType(
              "OrderCancelled", "storeql.order.order-cancelled", "An order was cancelled"),
          new EventType(
              "OrderReturned", "storeql.order.order-returned", "Goods came back on an order"),
          new EventType(
              "PaymentCaptured",
              "storeql.payment.payment-captured",
              "Money was taken for an order"),
          new EventType(
              "PaymentRefunded", "storeql.payment.payment-refunded", "Money was given back"),
          new EventType(
              "StockReceived",
              "storeql.inventory.stock-received",
              "Stock was booked in at a store"),
          new EventType(
              "StockAdjusted",
              "storeql.inventory.stock-adjusted",
              "A stock level was corrected by hand"),
          new EventType(
              "StockBelowThreshold",
              "storeql.inventory.stock-below-threshold",
              "A line fell below its reorder point"),
          new EventType(
              "RecallOpened", "storeql.inventory.recall-opened", "A product recall was opened"),
          new EventType(
              "GoodsReceived",
              "storeql.purchase.goods-received",
              "A delivery from a supplier arrived"),
          new EventType(
              "ProductCreated",
              "storeql.catalog.product-created",
              "A product was added to the catalogue"),
          new EventType(
              "ProductUpdated", "storeql.catalog.product-updated", "A product was changed"),
          new EventType(
              "ProductDelisted",
              "storeql.catalog.product-delisted",
              "A product was taken off sale"),
          new EventType("PriceChanged", "storeql.pricing.price-changed", "A price was changed"),
          new EventType(
              "CustomerRegistered",
              "storeql.customer.customer-registered",
              "A customer signed up"));

  public static Optional<EventType> eventType(String type) {
    return CATALOGUE.stream().filter(e -> e.type().equals(type)).findFirst();
  }

  /** The topics the consumer listens on: one per event on the catalogue. */
  public static List<String> topics() {
    return CATALOGUE.stream().map(EventType::topic).distinct().toList();
  }

  /**
   * How long to wait before trying again, after this many tries have failed: a minute, then five,
   * half an hour, two hours, eight — a receiver that is down for a deploy is reached in minutes,
   * one down for a weekend is not hammered.
   */
  public static Duration backoff(int attemptsMade) {
    return switch (Math.max(1, attemptsMade)) {
      case 1 -> Duration.ofMinutes(1);
      case 2 -> Duration.ofMinutes(5);
      case 3 -> Duration.ofMinutes(30);
      case 4 -> Duration.ofHours(2);
      default -> Duration.ofHours(8);
    };
  }

  /**
   * Where a business's events are sent.
   *
   * @param secretSealed the signing secret, sealed under the deployment's key
   * @param events the event types it asked for
   * @param disabledReason why this service switched it off, or null
   * @param consecutiveFailures deliveries that failed since the last that landed
   */
  public record Endpoint(
      UUID id,
      UUID tenantId,
      String url,
      String description,
      String secretSealed,
      List<String> events,
      boolean enabled,
      String disabledReason,
      int consecutiveFailures,
      Instant lastDeliveredAt,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt) {
    public Endpoint {
      events = List.copyOf(events);
    }
  }

  /** One event to one endpoint: what is to be sent, and how it has gone so far. */
  public record Delivery(
      UUID id,
      UUID tenantId,
      UUID endpointId,
      UUID eventId,
      String eventType,
      String payload,
      String status,
      int attempts,
      Instant nextAttemptAt,
      Instant deliveredAt,
      Integer lastStatus,
      String lastError,
      Instant createdAt) {}

  /** One try: what came back, or what went wrong. Never rewritten. */
  public record Attempt(
      UUID id,
      UUID tenantId,
      UUID deliveryId,
      int attempt,
      Instant attemptedAt,
      Integer statusCode,
      String error,
      String responseSnippet,
      int durationMs) {}

  /** A delivery due now, with the endpoint it goes to. */
  public record Due(Delivery delivery, Endpoint endpoint) {}
}
