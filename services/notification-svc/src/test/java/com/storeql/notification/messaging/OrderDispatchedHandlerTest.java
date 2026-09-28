package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.Channels;
import com.storeql.notification.channel.NotificationChannel;
import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.NotifierTestSupport;
import com.storeql.notification.service.OnceRepo;
import com.storeql.notification.service.RecordingChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A dispatched delivery tells its shopper it is on its way, once, naming the carrier and the
 * reference when the store noted one (ship-from-store).
 */
class OrderDispatchedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final class FakeCustomers extends CustomerClient {
    @Override
    public Optional<String> languageOf(UUID tenantId, UUID customerId) {
      return Optional.empty();
    }

    @Override
    public Optional<String> emailOf(UUID tenantId, UUID customerId) {
      return Optional.ofNullable(CUSTOMER.equals(customerId) ? "sam@example.com" : null);
    }

    @Override
    public Optional<String> phoneOf(UUID tenantId, UUID customerId) {
      return Optional.empty();
    }

    @Override
    public Optional<UUID> loginIdOf(UUID tenantId, UUID customerId) {
      return Optional.empty();
    }
  }

  private static final class NoChannels extends Channels {
    @Override
    public NotificationChannel forName(String name) {
      return null;
    }
  }

  private RecordingChannel channel;
  private OrderDispatchedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    handler = new OrderDispatchedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, new OnceRepo(), new NoChannels());
    handler.customers = new FakeCustomers();
  }

  private static String dispatched(UUID eventId, UUID customer, String carrier, String reference) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderDispatched\",\"occurredAt\":\"2026-09-25T10:00:00Z\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"customerId\":"
        + (customer == null ? "null" : "\"" + customer + "\"")
        + ",\"loginId\":null,\"carrier\":\""
        + carrier
        + "\",\"reference\":"
        + (reference == null ? "null" : "\"" + reference + "\"")
        + ",\"parcels\":null}";
  }

  @Test
  void theShopperIsToldOnceWithTheCarrierAndReference() {
    String payload = dispatched(Ids.newId(), CUSTOMER, "DPD", "15501234567890");
    handler.handle(payload);
    handler.handle(payload);
    assertEquals(List.of("sam@example.com"), channel.recipients);
    assertEquals("Your order is on its way", channel.subjects.get(0));
    String body = channel.bodies.get(0);
    assertTrue(body.contains("Your order " + ORDER + " left with DPD."), body);
    assertTrue(body.contains("Reference: 15501234567890"), body);
  }

  @Test
  void aDispatchWithNoReferenceSaysNoneAndAGuestHearsNothing() {
    handler.handle(dispatched(Ids.newId(), CUSTOMER, "Evri", null));
    String body = channel.bodies.get(0);
    assertTrue(body.contains("left with Evri."), body);
    assertFalse(body.contains("Reference"), body);
    handler.handle(dispatched(Ids.newId(), null, "DPD", "x"));
    handler.handle("{\"carrier\":\"DPD\"}");
    assertEquals(1, channel.sends());
  }
}
