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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a shopper is told about their order is told on the word of the business the event names and
 * of nobody else (flow: notifications to the shopper). customer-svc finds a customer in the one
 * business that holds them, so an event of another business that names the same customer id finds
 * nobody: nothing is emailed, pushed or logged; every question put to customer-svc names the
 * event's own business; and an event that names no business, or names one that is not an id, tells
 * nobody at all. The five kinds of message are driven through their real handlers.
 */
class ShopperMessagesIsolationTest {

  private static final UUID SHOP_A = Ids.newId();
  private static final UUID SHOP_B = Ids.newId();
  private static final UUID CUSTOMER_A = Ids.newId();
  private static final UUID CUSTOMER_B = Ids.newId();
  private static final UUID LOGIN_A = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final String NO_BUSINESS = "";
  private static final String NOT_AN_ID_BUSINESS = "\"tenantId\":\"not-an-id\",";

  /**
   * customer-svc as it answers a caller who names a business: a customer is found in the one
   * business that holds them and in no other. Every question is noted with the business it named.
   */
  private static final class Registry extends CustomerClient {
    final List<UUID> askedAbout = new ArrayList<>();

    private boolean holds(UUID tenantId, UUID customerId) {
      askedAbout.add(tenantId);
      return (SHOP_A.equals(tenantId) && CUSTOMER_A.equals(customerId))
          || (SHOP_B.equals(tenantId) && CUSTOMER_B.equals(customerId));
    }

    @Override
    public Optional<String> emailOf(UUID tenantId, UUID customerId) {
      if (!holds(tenantId, customerId)) return Optional.empty();
      return Optional.of(SHOP_A.equals(tenantId) ? "sam@shop-a.example" : "kim@shop-b.example");
    }

    @Override
    public Optional<String> languageOf(UUID tenantId, UUID customerId) {
      holds(tenantId, customerId);
      return Optional.empty();
    }

    @Override
    public Optional<String> phoneOf(UUID tenantId, UUID customerId) {
      holds(tenantId, customerId);
      return Optional.empty();
    }

    @Override
    public Optional<UUID> loginIdOf(UUID tenantId, UUID customerId) {
      boolean held = holds(tenantId, customerId);
      return held && SHOP_A.equals(tenantId) ? Optional.of(LOGIN_A) : Optional.empty();
    }
  }

  private static final class RecordingChannels extends Channels {
    final RecordingChannel push = new RecordingChannel();

    @Override
    public NotificationChannel forName(String name) {
      return "PUSH".equals(name) ? push : null;
    }
  }

  /** One kind of order message: told about an order of a business (as the event's own member). */
  private record Kind(String name, BiConsumer<String, UUID> tell) {}

  /** Every handler wired to one set of fakes, so what any of them sends or logs is counted once. */
  private static final class Rig {
    final RecordingChannel email = new RecordingChannel();
    final RecordingChannels channels = new RecordingChannels();
    final OnceRepo log = new OnceRepo();
    final Registry customers = new Registry();
    final List<Kind> kinds;

    Rig() {
      var notifier = NotifierTestSupport.notifierOf(email, log, channels);
      OrderConfirmedHandler confirmed = new OrderConfirmedHandler();
      confirmed.notifier = notifier;
      confirmed.customers = customers;
      OrderFulfilledHandler fulfilled = new OrderFulfilledHandler();
      fulfilled.notifier = notifier;
      fulfilled.customers = customers;
      OrderDispatchedHandler dispatched = new OrderDispatchedHandler();
      dispatched.notifier = notifier;
      dispatched.customers = customers;
      OrderLineShortClosedHandler shortClosed = new OrderLineShortClosedHandler();
      shortClosed.notifier = notifier;
      shortClosed.customers = customers;
      OrderLineSubstitutedHandler substituted = new OrderLineSubstitutedHandler();
      substituted.notifier = notifier;
      substituted.customers = customers;
      kinds =
          List.of(
              new Kind(
                  "OrderConfirmed",
                  (business, customer) ->
                      confirmed.handle(
                          event(
                              "OrderConfirmed",
                              business,
                              customer,
                              "\"channel\":\"ONLINE\",\"total\":24.60"))),
              new Kind(
                  "OrderFulfilled",
                  (business, customer) ->
                      fulfilled.handle(
                          event(
                              "OrderFulfilled",
                              business,
                              customer,
                              "\"status\":\"FULFILLED\",\"channel\":\"ONLINE\","
                                  + "\"fulfilmentType\":\"PICKUP\""))),
              new Kind(
                  "OrderDispatched",
                  (business, customer) ->
                      dispatched.handle(
                          event(
                              "OrderDispatched",
                              business,
                              customer,
                              "\"carrier\":\"DPD\",\"reference\":\"15501234567890\""))),
              new Kind(
                  "OrderLineShortClosed",
                  (business, customer) ->
                      shortClosed.handle(
                          event(
                              "OrderLineShortClosed",
                              business,
                              customer,
                              "\"variantName\":\"Braeburn apples 1kg\",\"qty\":2,"
                                  + "\"refundAmount\":3.80"))),
              new Kind(
                  "OrderLineSubstituted",
                  (business, customer) ->
                      substituted.handle(
                          event(
                              "OrderLineSubstituted",
                              business,
                              customer,
                              "\"fromName\":\"Braeburn apples 1kg\","
                                  + "\"toName\":\"Gala apples 1kg\",\"qty\":1,"
                                  + "\"refundAmount\":0.40"))));
    }
  }

  /** The business's own member of an event, as order-svc writes it. */
  private static String business(UUID tenant) {
    return "\"tenantId\":\"" + tenant + "\",";
  }

  private static String event(String type, String businessMember, UUID customer, String rest) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + type
        + "\","
        + businessMember
        + "\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"customerId\":\""
        + customer
        + "\",\"currency\":\"GBP\","
        + rest
        + "}";
  }

  @Test
  @DisplayName(
      "Each business's order messages reach only its own customers, whatever customer id an event names")
  void eachBusinessesOrderMessagesReachOnlyItsOwnCustomers() {
    Rig rig = new Rig();

    // A's events about A's customer: told once each, to A's address, logged as A's.
    for (Kind kind : rig.kinds) {
      int sent = rig.email.sends();
      kind.tell().accept(business(SHOP_A), CUSTOMER_A);
      assertEquals(sent + 1, rig.email.sends(), kind.name());
      assertEquals("sam@shop-a.example", rig.email.recipient(), kind.name());
      assertEquals(
          SHOP_A, rig.email.lastTenantId, "the channel is handed the business: " + kind.name());
      assertEquals(SHOP_A, rig.log.tenantId, kind.name());
    }
    assertEquals(rig.kinds.size(), rig.channels.push.sends(), "and pushed to the login A knows");

    // B's events naming A's customer: B's customer-svc finds no such customer, so nobody is told,
    // and every question asked named B, never A.
    rig.customers.askedAbout.clear();
    int emails = rig.email.sends();
    int records = rig.log.records;
    int pushes = rig.channels.push.sends();
    for (Kind kind : rig.kinds) {
      kind.tell().accept(business(SHOP_B), CUSTOMER_A);
    }
    assertEquals(emails, rig.email.sends(), "no email");
    assertEquals(records, rig.log.records, "nothing logged");
    assertEquals(pushes, rig.channels.push.sends(), "no push");
    assertFalse(rig.customers.askedAbout.isEmpty(), "customer-svc was asked");
    assertEquals(
        Set.of(SHOP_B),
        Set.copyOf(rig.customers.askedAbout),
        "every question named the business of the event");

    // And the other way about: A's events naming B's customer tell nobody either.
    for (Kind kind : rig.kinds) {
      kind.tell().accept(business(SHOP_A), CUSTOMER_B);
    }
    assertEquals(emails, rig.email.sends(), "no email");
    assertEquals(records, rig.log.records, "nothing logged");

    // B's own customer is told, as B's, by B's address.
    for (Kind kind : rig.kinds) {
      int sent = rig.email.sends();
      kind.tell().accept(business(SHOP_B), CUSTOMER_B);
      assertEquals(sent + 1, rig.email.sends(), kind.name());
      assertEquals("kim@shop-b.example", rig.email.recipient(), kind.name());
      assertEquals(
          SHOP_B, rig.email.lastTenantId, "the channel is handed the business: " + kind.name());
      assertEquals(SHOP_B, rig.log.tenantId, kind.name());
    }
  }

  @Test
  @DisplayName("An event that names no business, or one that is not an id, tells nobody")
  void anEventThatNamesNoBusinessTellsNobody() {
    Rig rig = new Rig();
    for (Kind kind : rig.kinds) {
      kind.tell().accept(NO_BUSINESS, CUSTOMER_A);
      kind.tell().accept(NOT_AN_ID_BUSINESS, CUSTOMER_A);
    }
    assertEquals(0, rig.email.sends(), "no email");
    assertEquals(0, rig.log.records, "nothing logged");
    assertEquals(0, rig.channels.push.sends(), "no push");
    assertTrue(rig.customers.askedAbout.isEmpty(), "customer-svc was not asked about nobody");
  }
}
