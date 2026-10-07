package com.storeql.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.storeql.ids.Ids;
import com.storeql.payment.provider.PaymentProvider.WebhookEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a Stripe event names the intent of ours it is about (intent/card-payments.md, slice 2): the
 * ids this service sent as metadata when it opened the PaymentIntent are part of the object a
 * {@code payment_intent.*} event carries, and are what matches an event whose provider reference no
 * intent holds yet. An id that is absent, or is not a canonical UUIDv7, names nothing: the event is
 * then not one of ours to correlate, never a failure to parse.
 */
class StripeWebhookCorrelationTest {

  private static WebhookEvent parse(String type, String objectJson) {
    String body =
        "{\"id\":\"evt_1\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson + "}}";
    return StripePaymentProvider.parseEvent(body.getBytes(StandardCharsets.UTF_8));
  }

  private static String intentObject(String metadata) {
    return "{\"id\":\"pi_1\",\"status\":\"requires_capture\",\"currency\":\"gbp\""
        + (metadata == null ? "" : ",\"metadata\":" + metadata)
        + "}";
  }

  @Test
  @DisplayName("A payment_intent event names the business and the intent it was opened for")
  void anEventNamesOurIntentAndItsBusiness() {
    UUID tenant = Ids.newId();
    UUID intent = Ids.newId();

    WebhookEvent e =
        parse(
            "payment_intent.amount_capturable_updated",
            intentObject(
                "{\"intentId\":\""
                    + intent
                    + "\",\"orderId\":\""
                    + Ids.newId()
                    + "\",\"tenantId\":\""
                    + tenant
                    + "\"}"));

    assertNotNull(e.ours());
    assertEquals(intent, e.ours().intentId());
    assertEquals(tenant, e.ours().tenantId());
    assertEquals("pi_1", e.providerRef());
  }

  @Test
  @DisplayName("An id is read as StoreQL reads every id: upper case is the same id")
  void anIdInUpperCaseIsTheSameId() {
    UUID tenant = Ids.newId();
    UUID intent = Ids.newId();

    WebhookEvent e =
        parse(
            "payment_intent.succeeded",
            intentObject(
                "{\"intentId\":\""
                    + intent.toString().toUpperCase(java.util.Locale.ROOT)
                    + "\",\"tenantId\":\""
                    + tenant
                    + "\"}"));

    assertNotNull(e.ours());
    assertEquals(intent, e.ours().intentId());
  }

  @Test
  @DisplayName("An event with no metadata, or none of ours in it, names no intent of ours")
  void anEventWithNoMetadataNamesNothing() {
    assertNull(parse("payment_intent.succeeded", intentObject(null)).ours());
    assertNull(parse("payment_intent.succeeded", intentObject("{}")).ours());
    assertNull(parse("payment_intent.succeeded", intentObject("{\"orderId\":\"x\"}")).ours());
    assertNull(
        parse("payment_intent.succeeded", intentObject("{\"intentId\":null,\"tenantId\":null}"))
            .ours());
  }

  @Test
  @DisplayName(
      "An id that is malformed, of another version, or not a string names nothing, and does not"
          + " fail the parse")
  void aBadIdNamesNothing() {
    UUID good = Ids.newId();
    String v4 = "9f1c1d3e-8a41-4f6a-9b0e-3c2f5a7d1e44";
    for (String bad :
        new String[] {
          "\"not-a-uuid\"", "\"\"", "\"1-1-1-1-1\"", "\"" + v4 + "\"", "12", "true", "{}", "[]"
        }) {
      String asIntent = "{\"intentId\":" + bad + ",\"tenantId\":\"" + good + "\"}";
      String asTenant = "{\"intentId\":\"" + good + "\",\"tenantId\":" + bad + "}";
      assertNull(parse("payment_intent.succeeded", intentObject(asIntent)).ours(), asIntent);
      assertNull(parse("payment_intent.succeeded", intentObject(asTenant)).ours(), asTenant);
    }
    // The rest of the event is read all the same: a bad id costs the correlation, nothing else.
    WebhookEvent e =
        parse(
            "payment_intent.succeeded",
            intentObject("{\"intentId\":\"not-a-uuid\",\"tenantId\":\"" + good + "\"}"));
    assertEquals("pi_1", e.providerRef());
    assertEquals("evt_1", e.providerEventId());
  }

  @Test
  @DisplayName(
      "Only an id pair is a correlation: an intent with no business, or the reverse, is not")
  void halfAPairNamesNothing() {
    UUID id = Ids.newId();
    assertNull(
        parse("payment_intent.succeeded", intentObject("{\"intentId\":\"" + id + "\"}")).ours());
    assertNull(
        parse("payment_intent.succeeded", intentObject("{\"tenantId\":\"" + id + "\"}")).ours());
  }

  @Test
  @DisplayName(
      "An object that is not a PaymentIntent names no intent, whatever metadata it carries: its id"
          + " is not the intent's reference")
  void anEventAboutAnotherObjectNamesNothing() {
    String metadata = "{\"intentId\":\"" + Ids.newId() + "\",\"tenantId\":\"" + Ids.newId() + "\"}";
    // A charge may carry the intent's metadata, and its id is ch_…, not the intent's pi_…
    assertNull(
        parse(
                "charge.succeeded",
                "{\"id\":\"ch_1\",\"status\":\"succeeded\",\"currency\":\"gbp\",\"metadata\":"
                    + metadata
                    + "}")
            .ours());
    assertNull(
        parse(
                "charge.dispute.created",
                "{\"id\":\"dp_1\",\"amount\":100,\"currency\":\"gbp\",\"payment_intent\":\"pi_1\","
                    + "\"reason\":\"fraudulent\",\"status\":\"needs_response\",\"metadata\":"
                    + metadata
                    + "}")
            .ours());
  }
}
