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
 * An item the store could not include tells its shopper, once, by name when order-svc could name
 * it, with what goes back when anything does (substitutions for out-of-stock online lines).
 */
class OrderLineShortClosedHandlerTest {

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

  private static String head(String type, UUID eventId, UUID customer) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\""
        + type
        + "\",\"occurredAt\":\"2026-09-25T10:00:00Z\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"customerId\":"
        + (customer == null ? "null" : "\"" + customer + "\"")
        + ",\"loginId\":null,\"currency\":\"GBP\",\"orderTotal\":17.60,\"channel\":\"ONLINE\","
        + "\"fulfilmentType\":\"DELIVERY\",";
  }

  private RecordingChannel channel;
  private OrderLineShortClosedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    handler = new OrderLineShortClosedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, new OnceRepo(), new NoChannels());
    handler.customers = new FakeCustomers();
  }

  private static String closed(
      UUID eventId, UUID customer, String name, String qty, String refund) {
    return head("OrderLineShortClosed", eventId, customer)
        + "\"variantId\":\""
        + Ids.newId()
        + "\",\"variantName\":"
        + (name == null ? "null" : "\"" + name + "\"")
        + ",\"qty\":"
        + qty
        + ",\"refundAmount\":"
        + refund
        + "}";
  }

  @Test
  void theShopperIsToldOnceWhatTheyWillNotGetAndWhatGoesBack() {
    String payload = closed(Ids.newId(), CUSTOMER, "Braeburn apples 1kg", "2.000", "3.80");
    handler.handle(payload);
    handler.handle(payload);
    assertEquals(List.of("sam@example.com"), channel.recipients);
    assertEquals("An item in your order was unavailable", channel.subjects.get(0));
    String body = channel.bodies.get(0);
    assertTrue(body.contains("2 × Braeburn apples 1kg in your order " + ORDER), body);
    assertTrue(body.contains("3.80"), body);
    assertTrue(body.contains("goes back to the way you paid"), body);
  }

  @Test
  void anUnnamedItemWithNothingBackIsStillToldAndAGuestHearsNothing() {
    handler.handle(closed(Ids.newId(), CUSTOMER, null, "1", "0.00"));
    String body = channel.bodies.get(0);
    assertTrue(body.contains("1 × an item"), body);
    assertFalse(body.contains("goes back"), body);
    handler.handle(closed(Ids.newId(), null, "Apples", "1", "2.00"));
    handler.handle("{\"variantId\":\"x\"}");
    assertEquals(1, channel.sends());
  }
}
