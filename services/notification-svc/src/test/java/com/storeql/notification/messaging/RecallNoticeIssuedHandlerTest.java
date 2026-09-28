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
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A recall notice reaches the buyer once: by email to the shop's record, by text to the number a
 * guest left when there is no record, and never twice for a redelivered event. What it says is what
 * GPSR art.36 lists, in that order, without a word that plays the risk down.
 */
class RecallNoticeIssuedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID LOGIN = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final class FakeCustomers extends CustomerClient {
    String email;
    String phone;
    String language;

    @Override
    public Optional<String> languageOf(UUID tenantId, UUID customerId) {
      return Optional.ofNullable(language);
    }

    @Override
    public Optional<String> emailOf(UUID tenantId, UUID customerId) {
      return Optional.ofNullable(CUSTOMER.equals(customerId) ? email : null);
    }

    @Override
    public Optional<String> phoneOf(UUID tenantId, UUID customerId) {
      return Optional.ofNullable(CUSTOMER.equals(customerId) ? phone : null);
    }

    @Override
    public Optional<UUID> loginIdOf(UUID tenantId, UUID customerId) {
      return Optional.empty();
    }
  }

  /** Email on the default channel; SMS and PUSH each on their own fake. */
  private static final class RecordingChannels extends Channels {
    final RecordingChannel sms = new RecordingChannel();
    final RecordingChannel push = new RecordingChannel();

    @Override
    public NotificationChannel forName(String name) {
      return switch (name) {
        case "SMS" -> sms;
        case "PUSH" -> push;
        default -> null;
      };
    }
  }

  private RecordingChannel channel;
  private RecordingChannels channels;
  private FakeCustomers customers;
  private RecallNoticeIssuedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    channels = new RecordingChannels();
    customers = new FakeCustomers();
    handler = new RecallNoticeIssuedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, new OnceRepo(), channels);
    handler.customers = customers;
  }

  @Test
  void aBuyerWithARecordIsEmailedOnceWhatArt36Asks() {
    customers.email = "chris@example.com";
    String payload = payload(CUSTOMER, null, null, "REFUND", "REPLACEMENT");

    handler.handle(payload);
    handler.handle(payload);

    assertEquals(List.of("chris@example.com"), channel.recipients);
    assertEquals("Product safety recall — FSA-PRIN-42", channel.subjects.get(0));
    String body = channel.bodies.get(0);
    assertTrue(body.startsWith("PRODUCT SAFETY RECALL\nReference FSA-PRIN-42\n"), body);
    assertTrue(
        body.contains(
            "What: Crunchy peanut butter (PB-340), lot L1, best before 1 October 2026, 2 bought"),
        body);
    assertTrue(body.contains("Bought on 12 September 2026, order " + ORDER), body);
    assertTrue(body.contains("Hazard: undeclared allergen. Peanut not on the label"), body);
    assertTrue(
        body.contains("What to do: Stop using this product immediately. Do not eat it."), body);
    assertTrue(body.contains("Your remedy — you choose: a refund or a replacement."), body);
    assertTrue(body.contains("Contact: 0800 100 200 or https://recall.example.com"), body);
    assertTrue(body.contains("Please pass this notice to anyone you have shared"), body);
    assertFalse(body.toLowerCase().contains("precautionary"), body);
  }

  @Test
  void aGuestWithOnlyANumberIsTextedOnceAndANumberThatIsNotOneIsNot() {
    String payload = payload(null, null, "+447700900123", "REFUND");
    handler.handle(payload);
    handler.handle(payload);
    assertEquals(List.of(), channel.recipients);
    assertEquals(List.of("+447700900123"), channels.sms.recipients);
    String text = channels.sms.bodies.get(0);
    assertTrue(text.startsWith("PRODUCT SAFETY RECALL FSA-PRIN-42: Crunchy peanut butter"), text);
    assertTrue(
        text.contains("Stop using it now. undeclared allergen. You may choose a refund."), text);
    assertTrue(text.endsWith("Contact 0800 100 200 or https://recall.example.com"), text);
    assertEquals(List.of(), channels.push.recipients);

    // A local number is not an E.164 one: nothing is sent to a number that may not be theirs.
    handler.handle(payload(null, null, "07700 900123", "REFUND"));
    assertEquals(1, channels.sms.recipients.size());
  }

  @Test
  void anOrderNumberThatCannotBeTextedFallsBackToTheCustomersOwn() {
    // A phone at the till: an order placed before its number was kept in international form still
    // carries it as typed. That cannot be texted, but the buyer's own record has a number that can.
    customers.phone = "+447400123456";
    handler.handle(payload(CUSTOMER, null, "07400 123456", "REFUND"));
    assertEquals(List.of("+447400123456"), channels.sms.recipients);
  }

  @Test
  void aRecordWithNoEmailFallsBackToItsPhoneALoginGetsAPushAndAMalformedPayloadIsSkipped() {
    customers.phone = "+447700900999";
    handler.handle(payload(CUSTOMER, LOGIN, null, "REFUND"));
    assertEquals(List.of(), channel.recipients);
    assertEquals(List.of("+447700900999"), channels.sms.recipients);
    assertEquals(List.of(LOGIN.toString()), channels.push.recipients);
    assertTrue(
        channels.push.bodies.get(0).startsWith("Stop using Crunchy peanut butter (PB-340)"),
        channels.push.bodies.get(0));

    handler.handle("{\"eventId\":\"nope\"}");
    handler.handle("not json");
    assertEquals(1, channels.sms.recipients.size());
  }

  @Test
  void aSingleRemedyCarriesItsReasonAndAnUnnamedProductStillReads() {
    customers.email = "chris@example.com";
    String payload =
        payload(CUSTOMER, LOGIN, null, "REFUND")
            .replace(
                "\"singleRemedyReason\":null", "\"singleRemedyReason\":\"Food cannot be repaired\"")
            .replace("\"productName\":\"Crunchy peanut butter\",\"sku\":\"PB-340\",", "")
            .replace("\"contactUrl\":\"https://recall.example.com\"", "\"contactUrl\":null");
    handler.handle(payload);
    String body = channel.bodies.get(0);
    assertTrue(
        body.contains("What: the product, lot L1, best before 1 October 2026, 2 bought"), body);
    assertTrue(body.contains("you choose: a refund. Food cannot be repaired"), body);
    assertTrue(body.contains("Contact: 0800 100 200\n"), body);
  }

  private static String payload(
      UUID customerId, UUID loginId, String buyerPhone, String... remedies) {
    var remedyArray = Json.createArrayBuilder();
    for (String r : remedies) {
      remedyArray.add(r);
    }
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "RecallNoticeIssued")
            .add("tenantId", TENANT.toString())
            .add("noticeId", Ids.newId().toString())
            .add("recallId", Ids.newId().toString())
            .add("reference", "FSA-PRIN-42")
            .add("hazard", "ALLERGEN")
            .add("reason", "Peanut not on the label")
            .add("customerNotice", "Do not eat it.")
            .add("remedies", remedyArray)
            .add("orderId", ORDER.toString())
            .add("storeId", Ids.newId().toString())
            .add("channel", "ONLINE")
            .add("soldAt", "2026-09-12T10:15:00Z")
            .add(
                "lines",
                Json.createArrayBuilder()
                    .add(
                        Json.createObjectBuilder()
                            .add("variantId", Ids.newId().toString())
                            .add("productName", "Crunchy peanut butter")
                            .add("sku", "PB-340")
                            .add("batchNo", "L1")
                            .add("expiryDate", "2026-10-01")
                            .add("qty", 2)));
    b.addNull("singleRemedyReason");
    b.add("contactPhone", "0800 100 200");
    b.add("contactUrl", "https://recall.example.com");
    if (customerId == null) b.addNull("customerId");
    else b.add("customerId", customerId.toString());
    if (loginId == null) b.addNull("loginId");
    else b.add("loginId", loginId.toString());
    if (buyerPhone == null) b.addNull("buyerPhone");
    else b.add("buyerPhone", buyerPhone);
    return b.build().toString();
  }
}
