package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * A pickup order picked and packed in full tells its shopper it is ready to collect, once — not a
 * part handover, not a delivery, not a till sale, not a guest checkout (ship-from-store and
 * dark-store picking).
 */
class OrderFulfilledHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID LOGIN = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final class FakeCustomers extends CustomerClient {
    String email = "sam@example.com";
    UUID login;

    @Override
    public Optional<String> languageOf(UUID tenantId, UUID customerId) {
      return Optional.empty();
    }

    @Override
    public Optional<String> emailOf(UUID tenantId, UUID customerId) {
      return Optional.ofNullable(CUSTOMER.equals(customerId) ? email : null);
    }

    @Override
    public Optional<String> phoneOf(UUID tenantId, UUID customerId) {
      return Optional.empty();
    }

    @Override
    public Optional<UUID> loginIdOf(UUID tenantId, UUID customerId) {
      return Optional.ofNullable(login);
    }
  }

  private static final class RecordingChannels extends Channels {
    final RecordingChannel push = new RecordingChannel();

    @Override
    public NotificationChannel forName(String name) {
      return "PUSH".equals(name) ? push : null;
    }
  }

  private RecordingChannel channel;
  private RecordingChannels channels;
  private FakeCustomers customers;
  private OrderFulfilledHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    channels = new RecordingChannels();
    customers = new FakeCustomers();
    handler = new OrderFulfilledHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, new OnceRepo(), channels);
    handler.customers = customers;
  }

  private static String fulfilled(
      UUID eventId, String status, String channel, String fulfilment, UUID customer) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"status\":\""
        + status
        + "\",\"channel\":\""
        + channel
        + "\",\"fulfilmentType\":\""
        + fulfilment
        + "\",\"customerId\":"
        + (customer == null ? "null" : "\"" + customer + "\"")
        + ",\"loginId\":null,\"items\":[]}";
  }

  @Test
  void aPickupPickedInFullIsToldReadyOnceByEmailAndPush() {
    customers.login = LOGIN;
    String payload = fulfilled(Ids.newId(), "FULFILLED", "ONLINE", "PICKUP", CUSTOMER);
    handler.handle(payload);
    handler.handle(payload);
    assertEquals(List.of("sam@example.com"), channel.recipients);
    assertEquals("Your order is ready to collect", channel.subjects.get(0));
    String body = channel.bodies.get(0);
    assertTrue(body.contains("Your order " + ORDER + " is ready to collect."), body);
    assertTrue(body.contains("Hollins Grocers"), body);
    assertEquals(List.of(LOGIN.toString()), channels.push.recipients);
    assertEquals("Ready to collect", channels.push.subjects.get(0));
  }

  @Test
  void nothingIsSaidForAPartPickADeliveryATillSaleOrAGuest() {
    handler.handle(fulfilled(Ids.newId(), "PARTIALLY_FULFILLED", "ONLINE", "PICKUP", CUSTOMER));
    handler.handle(fulfilled(Ids.newId(), "FULFILLED", "ONLINE", "DELIVERY", CUSTOMER));
    handler.handle(fulfilled(Ids.newId(), "FULFILLED", "POS", "INSTORE", CUSTOMER));
    handler.handle(fulfilled(Ids.newId(), "FULFILLED", "ONLINE", "PICKUP", null));
    // An event from before status and kind were said, and a malformed one, say nothing either.
    handler.handle(
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"items\":[]}");
    handler.handle(
        "{\"status\":\"FULFILLED\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\",\"customerId\":\"x\"}");
    assertEquals(List.of(), channel.recipients);
    assertEquals(List.of(), channels.push.recipients);
  }

  @Test
  void aShopperWithNoEmailOnRecordIsNotWrittenTo() {
    customers.email = null;
    handler.handle(fulfilled(Ids.newId(), "FULFILLED", "ONLINE", "PICKUP", CUSTOMER));
    assertEquals(0, channel.sends());
  }
}
