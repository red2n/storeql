package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.Channels;
import com.storeql.notification.channel.NotificationChannel;
import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.MessagesTestSupport;
import com.storeql.notification.service.NotifierTestSupport;
import com.storeql.notification.service.OnceRepo;
import com.storeql.notification.service.RecordingChannel;
import com.storeql.notification.template.Catalogue;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Delivery and collection slots: the confirmation says the window, in the store's own zone and the
 * reader's language, when the order has one — and reads exactly as before when it does not.
 */
class OrderConfirmedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final class FakeCustomers extends CustomerClient {
    private final String language;

    FakeCustomers(String language) {
      this.language = language;
    }

    @Override
    public Optional<String> languageOf(UUID tenantId, UUID customerId) {
      return Optional.ofNullable(language);
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

  /** A handler wired to a fake customer and channel, plus the channel to read what it sent. */
  private record Wired(OrderConfirmedHandler handler, RecordingChannel channel) {}

  private static Wired wired(String language, String country) {
    return wired(language, country, new MessagesTestSupport.MemoryStore());
  }

  /**
   * As above, with a business's own live template already stored — so a language with no words in
   * the platform's own template (Polish; the default is English) still writes its dates and window
   * out in that language, exactly as a business publishing its own words already can.
   */
  private static Wired wired(
      String language, String country, MessagesTestSupport.MemoryStore store) {
    OrderConfirmedHandler handler = new OrderConfirmedHandler();
    RecordingChannel channel = new RecordingChannel();
    var messages = MessagesTestSupport.with(store, country, "Hollins Grocers");
    handler.notifier =
        NotifierTestSupport.notifierOf(channel, new OnceRepo(), new NoChannels(), messages);
    handler.customers = new FakeCustomers(language);
    return new Wired(handler, channel);
  }

  private static String confirmed(
      UUID eventId,
      String fulfilmentType,
      String slotStartsAt,
      String slotEndsAt,
      String slotTimeZone) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"channel\":\"ONLINE\",\"customerId\":\""
        + CUSTOMER
        + "\",\"total\":24.60,\"taxAmount\":0,\"currency\":\"GBP\",\"fulfilmentType\":"
        + text(fulfilmentType)
        + ",\"slotStartsAt\":"
        + text(slotStartsAt)
        + ",\"slotEndsAt\":"
        + text(slotEndsAt)
        + ",\"slotTimeZone\":"
        + text(slotTimeZone)
        + ",\"lines\":[]}";
  }

  private static String text(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  @Test
  void aDeliveryWindowIsSaidInWarsawsZoneAndPolish() {
    // A live Polish template, as a business publishing its own words already can: the platform's
    // own default is English, so without one "reader's language" would have nothing Polish to show.
    var store = new MessagesTestSupport.MemoryStore();
    store.put(
        "ORDER_CONFIRMED",
        Catalogue.Form.EMAIL,
        "pl",
        "Zamówienie {{order}}",
        "Dziękujemy!\n\nZamówienie {{order}}\nRazem: {{total}}{{#window}}\n{{window}}{{/window}}\n\n— {{shop}}");
    Wired w = wired("pl", "PL", store);
    w.handler()
        .handle(
            confirmed(
                Ids.newId(),
                "DELIVERY",
                "2026-09-27T15:00:00Z",
                "2026-09-27T17:00:00Z",
                "Europe/Warsaw"));
    String body = w.channel().body();
    assertTrue(body.contains("Delivery: niedziela 27 września, 17:00–19:00"), body);
  }

  @Test
  void aCollectionWindowIsSaidInKolkatasZoneAndEnglishIndia() {
    Wired w = wired("en", "IN");
    w.handler()
        .handle(
            confirmed(
                Ids.newId(),
                "PICKUP",
                "2026-09-27T09:30:00Z",
                "2026-09-27T11:30:00Z",
                "Asia/Kolkata"));
    String body = w.channel().body();
    assertTrue(body.contains("Collection: Sunday 27 September, 15:00–17:00"), body);
  }

  @Test
  void aThirdBusinessInNewYorksZoneRendersItsOwnLocalWindow() {
    Wired w = wired("en", "US");
    w.handler()
        .handle(
            confirmed(
                Ids.newId(),
                "DELIVERY",
                "2026-09-27T20:00:00Z",
                "2026-09-27T22:00:00Z",
                "America/New_York"));
    String body = w.channel().body();
    assertTrue(body.contains("Delivery: Sunday 27 September, 16:00–18:00"), body);
  }

  @Test
  void anOrderWithNoWindowReadsExactlyAsBefore() {
    Wired w = wired(null, "GB");
    w.handler().handle(confirmed(Ids.newId(), null, null, null, null));
    String body = w.channel().body();
    assertEquals(
        "Thanks for your order!\n\nOrder " + ORDER + "\nTotal: £24.60\n\n— Hollins Grocers", body);
    assertFalse(body.contains("Delivery"), body);
    assertFalse(body.contains("Collection"), body);
  }

  @Test
  void aRedeliveredConfirmationWithAWindowIsToldOnce() {
    Wired w = wired("pl", "PL");
    String payload =
        confirmed(
            Ids.newId(),
            "DELIVERY",
            "2026-09-27T15:00:00Z",
            "2026-09-27T17:00:00Z",
            "Europe/Warsaw");
    w.handler().handle(payload);
    w.handler().handle(payload);
    assertEquals(1, w.channel().sends());
  }
}
