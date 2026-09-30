package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The id a webhook delivery is deduplicated on, when a producer publishes none. */
class WebhookEventIdsTest {

  private static JsonObject json(String s) {
    return Json.createReader(new StringReader(s)).readObject();
  }

  @Test
  void anEventsOwnIdWins() {
    UUID id = Ids.newId();
    Optional<UUID> got =
        WebhookEventIds.of(
            "OrderPlaced",
            json("{\"eventId\":\"" + id + "\",\"orderId\":\"" + Ids.newId() + "\"}"));
    assertEquals(Optional.of(id), got);
  }

  @Test
  void anOrderPlacedAndAPaymentCapturedDeriveTheirIdFromTheirKeyStably() {
    UUID order = Ids.newId();
    UUID payment = Ids.newId();
    JsonObject placed = json("{\"orderId\":\"" + order + "\"}");
    JsonObject captured = json("{\"paymentId\":\"" + payment + "\"}");
    UUID a = WebhookEventIds.of("OrderPlaced", placed).orElseThrow();
    assertEquals(a, WebhookEventIds.of("OrderPlaced", placed).orElseThrow());
    assertTrue(Ids.isV7(a));
    assertEquals(
        Ids.derived(payment, "webhook:PaymentCaptured"),
        WebhookEventIds.of("PaymentCaptured", captured).orElseThrow());
  }

  @Test
  void oneOrderAndOneKeyNeverCollideAcrossTypes() {
    UUID key = Ids.newId();
    UUID placed =
        WebhookEventIds.of("OrderPlaced", json("{\"orderId\":\"" + key + "\"}")).orElseThrow();
    UUID captured =
        WebhookEventIds.of("PaymentCaptured", json("{\"paymentId\":\"" + key + "\"}"))
            .orElseThrow();
    assertTrue(!placed.equals(captured));
  }

  @Test
  void aTypeWithNoKeyOfItsOwnHasNoFallback() {
    assertEquals(
        Optional.empty(),
        WebhookEventIds.of("PriceChanged", json("{\"priceListId\":\"" + Ids.newId() + "\"}")));
    assertEquals(Optional.empty(), WebhookEventIds.of("OrderPlaced", json("{}")));
  }

  @Test
  void aMalformedIdIsRefusedNotIgnored() {
    assertThrows(
        RuntimeException.class,
        () -> WebhookEventIds.of("OrderPlaced", json("{\"eventId\":\"not-an-id\"}")));
    assertThrows(
        RuntimeException.class,
        () -> WebhookEventIds.of("OrderPlaced", json("{\"orderId\":\"1-1-1-1-1\"}")));
  }
}
