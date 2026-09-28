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
 * A substitute in the bag tells its shopper, once, what replaced what, that they pay no more, and
 * the difference going back when the substitute cost less (substitutions for out-of-stock online
 * lines).
 */
class OrderLineSubstitutedHandlerTest {

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
  private OrderLineSubstitutedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    handler = new OrderLineSubstitutedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, new OnceRepo(), new NoChannels());
    handler.customers = new FakeCustomers();
  }

  private static String swapped(
      UUID eventId, UUID customer, String from, String to, String qty, String refund) {
    return head("OrderLineSubstituted", eventId, customer)
        + "\"fromVariantId\":\""
        + Ids.newId()
        + "\",\"fromName\":"
        + (from == null ? "null" : "\"" + from + "\"")
        + ",\"toVariantId\":\""
        + Ids.newId()
        + "\",\"toName\":"
        + (to == null ? "null" : "\"" + to + "\"")
        + ",\"qty\":"
        + qty
        + ",\"chargedAmount\":4.40,\"refundAmount\":"
        + refund
        + "}";
  }

  @Test
  void theShopperIsToldOnceWhatReplacedWhatAndTheDifferenceBack() {
    String payload =
        swapped(Ids.newId(), CUSTOMER, "Braeburn apples 1kg", "Gala apples 1kg", "2", "0.40");
    handler.handle(payload);
    handler.handle(payload);
    assertEquals(List.of("sam@example.com"), channel.recipients);
    assertEquals("We substituted an item in your order", channel.subjects.get(0));
    String body = channel.bodies.get(0);
    assertTrue(body.contains("Braeburn apples 1kg was unavailable"), body);
    assertTrue(body.contains("2 × Gala apples 1kg instead"), body);
    assertTrue(body.contains("You pay no more than you did, and"), body);
    assertTrue(body.contains("0.40"), body);
    assertTrue(body.contains("hand it back"), body);
  }

  @Test
  void aSubstituteChargedTheSameSaysNothingOfARefundAndAGuestHearsNothing() {
    handler.handle(swapped(Ids.newId(), CUSTOMER, null, "Gala apples 1kg", "1.000", "0"));
    String body = channel.bodies.get(0);
    assertTrue(body.contains("an item was unavailable"), body);
    assertTrue(body.contains("1 × Gala apples 1kg"), body);
    assertTrue(body.contains("You pay no more than you did."), body);
    assertFalse(body.contains("goes back"), body);
    handler.handle(swapped(Ids.newId(), null, "A", "B", "1", "1.00"));
    handler.handle("{\"fromName\":\"x\"}");
    assertEquals(1, channel.sends());
  }
}
