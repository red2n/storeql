package com.storeql.payment;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.repo.PaymentIntentRepository;
import com.storeql.test.Hmac;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A provider's webhook against real Postgres, signed as Stripe signs it, in the orders it really
 * arrives in (intent/card-payments.md, slice 2): an event that reaches the service before the
 * intent has learnt its provider reference is applied through the intent id this service sent as
 * metadata, and an event for an intent of ours that is not known is answered with a failure and is
 * not recorded, so the provider delivers it again.
 *
 * <p>An event is matched by its provider reference when an intent holds it, and through the
 * business it names when none does. What must not work is read back as nothing moved: another
 * business's intent id, a reference that belongs to another intent than the metadata names, and ids
 * that are absent or malformed on an event whose reference no intent holds.
 */
@HelidonTest
class WebhookOrderingIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  /** The deployment's webhook secret, made for this run. */
  private static final String SECRET = "whsec_" + Ids.newId();

  static {
    System.setProperty("storeql.payment.stripe.webhook-secret", SECRET);
  }

  @Inject WebTarget target;
  @Inject PaymentIntentRepository intents;

  @AfterAll
  static void stop() {
    System.clearProperty("storeql.payment.stripe.webhook-secret");
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private PaymentIntent intent(UUID tenant, UUID order, String ref, String status) {
    Instant now = Instant.now();
    return intents.create(
        new PaymentIntent(
            Ids.newId(),
            tenant,
            order,
            null,
            PaymentIntent.PROVIDER_STRIPE,
            ref,
            new BigDecimal("3.37"),
            BigDecimal.ZERO,
            "GBP",
            status,
            null,
            null,
            null,
            null,
            null,
            now,
            now));
  }

  private static String metadata(UUID tenant, UUID intentId) {
    return "{\"intentId\":\"" + intentId + "\",\"tenantId\":\"" + tenant + "\"}";
  }

  /** A Stripe PaymentIntent event, with the metadata object as given (null for none). */
  private static String event(String eventId, String type, String ref, String metadata) {
    boolean captured = "payment_intent.succeeded".equals(type);
    String stripeStatus =
        switch (type) {
          case "payment_intent.succeeded" -> "succeeded";
          case "payment_intent.amount_capturable_updated" -> "requires_capture";
          default -> "requires_payment_method";
        };
    return "{\"id\":\""
        + eventId
        + "\",\"type\":\""
        + type
        + "\",\"data\":{\"object\":{\"id\":\""
        + ref
        + "\",\"status\":\""
        + stripeStatus
        + "\",\"currency\":\"gbp\",\"amount_received\":"
        + (captured ? 337 : 0)
        + (metadata == null ? "" : ",\"metadata\":" + metadata)
        + "}}}";
  }

  private Answer deliver(String body, String signature) {
    return deliver(body, signature, null);
  }

  /** As {@link #deliver(String, String)}, from a caller who also sends the identity headers. */
  private Answer deliver(String body, String signature, Caller who) {
    Invocation.Builder request =
        WebTargets.at(target, "/payments/webhooks/stripe")
            .request()
            .header("Stripe-Signature", signature);
    if (who != null) {
      request =
          request
              .header("X-Tenant-Id", who.tenantId())
              .header("X-User-Id", who.userId())
              .header("X-Roles", who.roles());
    }
    Response r = request.post(Entity.entity(body, MediaType.APPLICATION_JSON));
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(),
        text == null || text.isBlank()
            ? jakarta.json.JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject());
  }

  /** Delivered the way the provider does: signed over the raw bytes, now. */
  private Answer deliver(String body) {
    return deliver(body, signed(body), null);
  }

  private static String signed(String body) {
    long now = Instant.now().getEpochSecond();
    return "t=" + now + ",v1=" + Hmac.sha256Hex(SECRET, now + "." + body);
  }

  private static String newEventId() {
    return "evt_" + Ids.newId();
  }

  private static String newRef() {
    return "pi_" + Ids.newId();
  }

  private static String statusOf(UUID intentId) {
    return scalar(PG, "SELECT status FROM payment.payment_intents WHERE id = '" + intentId + "'");
  }

  private static String refOf(UUID intentId) {
    return scalar(
        PG,
        "SELECT coalesce(provider_ref, 'none') FROM payment.payment_intents WHERE id = '"
            + intentId
            + "'");
  }

  private static String tendersOf(UUID order) {
    return scalar(
        PG,
        "SELECT count(*) || '/' || coalesce(max(method), '-') || '/' || coalesce(max(reference),"
            + " '-') FROM payment.payment_tenders WHERE order_id = '"
            + order
            + "'");
  }

  private static String capturesOf(UUID order) {
    return scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentCaptured'"
            + " AND payload LIKE '%\"orderId\":\""
            + order
            + "\"%'");
  }

  private static boolean seen(String eventId) {
    return !"0"
        .equals(
            scalar(
                PG,
                "SELECT count(*) FROM payment.payment_webhook_events WHERE provider_event_id = '"
                    + eventId
                    + "'"));
  }

  // ── an event before the intent has its reference ───────────────────────────

  @Test
  @DisplayName(
      "An event that arrives before the intent has its provider reference is applied through the"
          + " intent id sent as metadata: it learns the reference, and the events after it, and a"
          + " redelivery, reach the same end by that reference")
  void eventBeforeReferenceIsApplied() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    PaymentIntent opened = intent(tenant, order, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String ref = newRef();
    assertThat("no reference yet", refOf(opened.id()), is("none"));

    String authorised = newEventId();
    Answer first =
        deliver(
            event(
                authorised,
                "payment_intent.amount_capturable_updated",
                ref,
                metadata(tenant, opened.id())));

    assertThat(first.body().toString(), first.status(), is(200));
    assertThat("applied", statusOf(opened.id()), is("AUTHORIZED"));
    assertThat("and it learnt the reference", refOf(opened.id()), is(ref));
    assertThat("and the event is recorded, now it has been applied", seen(authorised), is(true));
    assertThat("nothing was taken by an authorisation", tendersOf(order), is("0/-/-"));

    // The capture names the same payment intent: found by the reference now, metadata or not.
    String captured = newEventId();
    String body = event(captured, "payment_intent.succeeded", ref, null);
    Answer second = deliver(body);
    assertThat(second.body().toString(), second.status(), is(200));
    assertThat(statusOf(opened.id()), is("CAPTURED"));
    assertThat(
        "one tender, a card's, with the charge as its reference",
        tendersOf(order),
        is("1/CARD/" + ref));
    assertThat(capturesOf(order), is("1"));

    // Delivered again, as every provider does: the same end, and nothing twice.
    Answer again = deliver(body);
    assertThat(again.status(), is(200));
    Answer anotherEvent = deliver(event(newEventId(), "payment_intent.succeeded", ref, null));
    assertThat(anotherEvent.status(), is(200));
    assertThat(tendersOf(order), is("1/CARD/" + ref));
    assertThat(capturesOf(order), is("1"));
  }

  @Test
  @DisplayName(
      "A capture that arrives before the intent has its provider reference is applied, once,"
          + " through the metadata: the money is recorded and the intent holds the charge")
  void aCaptureBeforeTheReferenceIsRecordedOnce() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    PaymentIntent opened = intent(tenant, order, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String ref = newRef();
    String body =
        event(newEventId(), "payment_intent.succeeded", ref, metadata(tenant, opened.id()));

    Answer a = deliver(body);

    assertThat(a.body().toString(), a.status(), is(200));
    assertThat(statusOf(opened.id()), is("CAPTURED"));
    assertThat(refOf(opened.id()), is(ref));
    assertThat(tendersOf(order), is("1/CARD/" + ref));
    assertThat(capturesOf(order), is("1"));
    assertThat(
        "the tender is the business's",
        scalar(
            PG, "SELECT tenant_id FROM payment.payment_tenders WHERE order_id = '" + order + "'"),
        is(tenant.toString()));

    Answer again = deliver(body);
    assertThat(again.status(), is(200));
    assertThat(tendersOf(order), is("1/CARD/" + ref));
    assertThat(capturesOf(order), is("1"));
  }

  @Test
  @DisplayName(
      "An event of a kind that changes nothing still teaches the intent its reference, so the"
          + " capture that follows can name the provider's own object")
  void anEventThatChangesNothingStillTeachesTheReference() {
    UUID tenant = Ids.newId();
    PaymentIntent opened = intent(tenant, Ids.newId(), null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String ref = newRef();
    String created = newEventId();

    Answer a =
        deliver(event(created, "payment_intent.created", ref, metadata(tenant, opened.id())));

    assertThat(a.status(), is(200));
    assertThat(statusOf(opened.id()), is("REQUIRES_ACTION"));
    assertThat(refOf(opened.id()), is(ref));
    assertThat(seen(created), is(true));
  }

  // ── an intent of ours that is not known ────────────────────────────────────

  @Test
  @DisplayName(
      "An event for an intent of ours that is not known yet is answered with a failure and is not"
          + " marked seen, so the provider delivers it again, and applied when it does and the"
          + " intent is there")
  void unknownIntentIsRedelivered() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID id = Ids.newId();
    String ref = newRef();
    String eventId = newEventId();
    String body = event(eventId, "payment_intent.succeeded", ref, metadata(tenant, id));

    Answer first = deliver(body);

    assertThat(first.body().toString(), first.status(), is(404));
    assertThat(first.code(), is("PAYMENT_WEBHOOK_INTENT_UNKNOWN"));
    assertThat(
        "not recorded: the provider's redelivery must be judged afresh", seen(eventId), is(false));
    assertThat(tendersOf(order), is("0/-/-"));

    // The intent exists by the time the provider tries again (same event, same id).
    Instant now = Instant.now();
    intents.create(
        new PaymentIntent(
            id,
            tenant,
            order,
            null,
            PaymentIntent.PROVIDER_STRIPE,
            null,
            new BigDecimal("3.37"),
            BigDecimal.ZERO,
            "GBP",
            PaymentIntent.STATUS_REQUIRES_ACTION,
            null,
            null,
            null,
            null,
            null,
            now,
            now));
    Answer second = deliver(body);

    assertThat(second.body().toString(), second.status(), is(200));
    assertThat(statusOf(id), is("CAPTURED"));
    assertThat(tendersOf(order), is("1/CARD/" + ref));
    assertThat("recorded once it has been applied", seen(eventId), is(true));
  }

  @Test
  @DisplayName(
      "An event naming another business's intent is answered as an unknown intent and moves"
          + " nothing of theirs or ours: the intent id is looked up in the business the event names")
  void anotherBusinessesIntentIsNeverApplied() {
    UUID theirs = Ids.newId();
    UUID mine = Ids.newId();
    UUID theirOrder = Ids.newId();
    PaymentIntent theirIntent =
        intent(theirs, theirOrder, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String ref = newRef();
    String eventId = newEventId();

    // The event says: business `mine`, and the id of an intent that belongs to `theirs`.
    Answer a =
        deliver(event(eventId, "payment_intent.succeeded", ref, metadata(mine, theirIntent.id())));

    assertThat(a.body().toString(), a.status(), is(404));
    assertThat(a.code(), is("PAYMENT_WEBHOOK_INTENT_UNKNOWN"));
    assertThat("theirs is as it was", statusOf(theirIntent.id()), is("REQUIRES_ACTION"));
    assertThat(refOf(theirIntent.id()), is("none"));
    assertThat(tendersOf(theirOrder), is("0/-/-"));
    assertThat(capturesOf(theirOrder), is("0"));
    assertThat(
        "and nothing was written for the business the event named",
        scalar(PG, "SELECT count(*) FROM payment.payment_tenders WHERE tenant_id = '" + mine + "'"),
        is("0"));
    assertThat(seen(eventId), is(false));
  }

  @Test
  @DisplayName(
      "A reference that belongs to one intent, with metadata naming another business's, applies to"
          + " neither: it is recorded, since delivering it again would change nothing")
  void aReferenceAndMetadataThatDisagreeApplyToNeither() {
    UUID mine = Ids.newId();
    UUID theirs = Ids.newId();
    UUID myOrder = Ids.newId();
    UUID theirOrder = Ids.newId();
    String ref = newRef();
    PaymentIntent myIntent = intent(mine, myOrder, ref, PaymentIntent.STATUS_AUTHORIZED);
    PaymentIntent theirIntent =
        intent(theirs, theirOrder, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String eventId = newEventId();

    Answer a =
        deliver(
            event(eventId, "payment_intent.succeeded", ref, metadata(theirs, theirIntent.id())));

    assertThat(a.body().toString(), a.status(), is(200));
    assertThat("mine is as it was", statusOf(myIntent.id()), is("AUTHORIZED"));
    assertThat(tendersOf(myOrder), is("0/-/-"));
    assertThat("theirs is as it was", statusOf(theirIntent.id()), is("REQUIRES_ACTION"));
    assertThat(refOf(theirIntent.id()), is("none"));
    assertThat(tendersOf(theirOrder), is("0/-/-"));
    assertThat(capturesOf(myOrder) + capturesOf(theirOrder), is("00"));
    assertThat(seen(eventId), is(true));
  }

  @Test
  @DisplayName(
      "Metadata for an intent that already holds another reference does not take this event's:"
          + " nothing is applied, and the intent keeps its own")
  void anIntentWithAnotherReferenceIsNotTheOneTheEventIsAbout() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    String held = newRef();
    PaymentIntent opened = intent(tenant, order, held, PaymentIntent.STATUS_AUTHORIZED);
    String eventId = newEventId();

    Answer a =
        deliver(
            event(eventId, "payment_intent.succeeded", newRef(), metadata(tenant, opened.id())));

    assertThat(a.body().toString(), a.status(), is(200));
    assertThat(statusOf(opened.id()), is("AUTHORIZED"));
    assertThat(refOf(opened.id()), is(held));
    assertThat(tendersOf(order), is("0/-/-"));
    assertThat(seen(eventId), is(true));
  }

  // ── an event that is not ours ──────────────────────────────────────────────

  @Test
  @DisplayName(
      "An event with no metadata of ours, or with an id that is malformed or not a UUIDv7, names"
          + " no intent: acknowledged and recorded as before, moving nothing, never a 5xx")
  void anEventNotOursIsAcknowledgedAndMovesNothing() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    PaymentIntent waiting = intent(tenant, order, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String v4 = "9f1c1d3e-8a41-4f6a-9b0e-3c2f5a7d1e44";
    String[] notOurs = {
      null,
      "{}",
      "{\"orderId\":\"" + order + "\"}",
      "{\"intentId\":\"not-a-uuid\",\"tenantId\":\"" + tenant + "\"}",
      "{\"intentId\":\"" + v4 + "\",\"tenantId\":\"" + tenant + "\"}",
      "{\"intentId\":\"" + waiting.id() + "\",\"tenantId\":\"" + v4 + "\"}",
      "{\"intentId\":\"" + waiting.id() + "\",\"tenantId\":\"nonsense\"}",
      "{\"intentId\":\"" + waiting.id() + "\"}",
      "{\"tenantId\":\"" + tenant + "\"}",
      "{\"intentId\":12,\"tenantId\":true}",
    };

    for (String metadata : notOurs) {
      String eventId = newEventId();
      Answer a = deliver(event(eventId, "payment_intent.succeeded", newRef(), metadata));
      assertThat(metadata + " " + a.body(), a.status(), is(200));
      assertThat(metadata, seen(eventId), is(true));
      assertThat(metadata, statusOf(waiting.id()), is("REQUIRES_ACTION"));
      assertThat(metadata, refOf(waiting.id()), is("none"));
      assertThat(metadata, tendersOf(order), is("0/-/-"));
    }
  }

  @Test
  @DisplayName("A malformed id never stops an event the reference already matches")
  void aMalformedIdDoesNotBlockTheReferenceMatch() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    String ref = newRef();
    PaymentIntent opened = intent(tenant, order, ref, PaymentIntent.STATUS_AUTHORIZED);

    Answer a =
        deliver(
            event(
                newEventId(),
                "payment_intent.succeeded",
                ref,
                "{\"intentId\":\"not-a-uuid\",\"tenantId\":\"" + tenant + "\"}"));

    assertThat(a.body().toString(), a.status(), is(200));
    assertThat(statusOf(opened.id()), is("CAPTURED"));
    assertThat(tendersOf(order), is("1/CARD/" + ref));
  }

  @Test
  @DisplayName(
      "An event about a hold for an attempt that failed here is found by its metadata and left as"
          + " the failed attempt it is: it learns the reference, and takes nothing")
  void aHoldForAFailedAttemptIsFoundAndNotRevived() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    PaymentIntent opened = intent(tenant, order, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    assertThat(
        intents.markTerminal(tenant, opened.id(), "FAILED", "PROVIDER_ERROR", "timeout"), is(true));
    String ref = newRef();
    String eventId = newEventId();

    Answer a =
        deliver(
            event(
                eventId,
                "payment_intent.amount_capturable_updated",
                ref,
                metadata(tenant, opened.id())));

    assertThat(a.body().toString(), a.status(), is(200));
    assertThat("not revived", statusOf(opened.id()), is("FAILED"));
    assertThat("but the hold is tied to its record", refOf(opened.id()), is(ref));
    assertThat(tendersOf(order), is("0/-/-"));
    assertThat(seen(eventId), is(true));
  }

  @Test
  @DisplayName("An event that is not signed by the provider is refused, whatever intent it names")
  void anUnsignedEventNamingARealIntentMovesNothing() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    PaymentIntent opened = intent(tenant, order, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String eventId = newEventId();
    String body =
        event(eventId, "payment_intent.succeeded", newRef(), metadata(tenant, opened.id()));
    long now = Instant.now().getEpochSecond();

    Answer forged = deliver(body, "t=" + now + ",v1=" + "0".repeat(64));

    assertThat(forged.status(), is(400));
    assertThat(forged.code(), is("PAYMENT_WEBHOOK_INVALID"));
    assertThat(statusOf(opened.id()), is("REQUIRES_ACTION"));
    assertThat(refOf(opened.id()), is("none"));
    assertThat(tendersOf(order), is("0/-/-"));
    assertThat(seen(eventId), is(false));
    assertThat(forged.body().toString(), containsString("PAYMENT_WEBHOOK_INVALID"));
  }

  @Test
  @DisplayName(
      "Another business's staff of every role, and a shopper, choose nothing by the identity they"
          + " send with a delivery: unsigned it is refused whatever they name, signed it is applied"
          + " to the business the event's metadata names, and theirs is untouched")
  void identityHeadersOnADeliveryChooseNothing() {
    UUID mine = Ids.newId();
    UUID theirs = Ids.newId();
    UUID myOrder = Ids.newId();
    UUID theirOrder = Ids.newId();
    PaymentIntent mineOpen = intent(mine, myOrder, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    PaymentIntent theirsOpen =
        intent(theirs, theirOrder, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    String ref = newRef();
    String body =
        event(newEventId(), "payment_intent.succeeded", ref, metadata(mine, mineOpen.id()));
    long now = Instant.now().getEpochSecond();
    String forged = "t=" + now + ",v1=" + "0".repeat(64);

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "CUSTOMER"}) {
      Caller them = new Caller(theirs, Ids.newId(), role);
      Answer refused = deliver(body, forged, them);
      assertThat(role + " " + refused.body(), refused.status(), is(400));
      assertThat(role, refused.code(), is("PAYMENT_WEBHOOK_INVALID"));
      assertThat(role, statusOf(mineOpen.id()), is("REQUIRES_ACTION"));
      assertThat(role, tendersOf(myOrder), is("0/-/-"));
    }

    // The provider's own delivery, from behind their identity: the event says whose it is.
    Answer genuine = deliver(body, signed(body), new Caller(theirs, Ids.newId(), "OWNER"));
    assertThat(genuine.body().toString(), genuine.status(), is(200));
    assertThat(statusOf(mineOpen.id()), is("CAPTURED"));
    assertThat(tendersOf(myOrder), is("1/CARD/" + ref));
    assertThat("theirs is as it was", statusOf(theirsOpen.id()), is("REQUIRES_ACTION"));
    assertThat(refOf(theirsOpen.id()), is("none"));
    assertThat(tendersOf(theirOrder), is("0/-/-"));
    assertThat(
        "no tender was written for the business whose identity was sent",
        scalar(
            PG, "SELECT count(*) FROM payment.payment_tenders WHERE tenant_id = '" + theirs + "'"),
        is("0"));
  }

  // ── the authorisation's own answer, arriving late ──────────────────────────

  @Test
  @DisplayName(
      "The provider's answer to the authorisation, recorded after an event already moved the"
          + " intent, never moves it back or fails: the reference is kept and the later state wins")
  void aLateAuthorisationAnswerNeverMovesAnIntentBack() {
    UUID tenant = Ids.newId();
    String ref = newRef();

    // Captured through the metadata first; the create call's own write then arrives.
    UUID order = Ids.newId();
    PaymentIntent captured = intent(tenant, order, null, PaymentIntent.STATUS_REQUIRES_ACTION);
    assertThat(
        deliver(
                event(
                    newEventId(), "payment_intent.succeeded", ref, metadata(tenant, captured.id())))
            .status(),
        is(200));
    intents.markAuthorized(
        tenant, captured.id(), ref, PaymentIntent.STATUS_REQUIRES_ACTION, "https://3ds.example/x");
    assertThat(statusOf(captured.id()), is("CAPTURED"));
    assertThat(refOf(captured.id()), is(ref));
    assertThat(tendersOf(order), is("1/CARD/" + ref));

    // Authorised through the metadata first: the answer "still needs the customer" is old news.
    String ref2 = newRef();
    PaymentIntent authorised =
        intent(tenant, Ids.newId(), null, PaymentIntent.STATUS_REQUIRES_ACTION);
    assertThat(
        deliver(
                event(
                    newEventId(),
                    "payment_intent.amount_capturable_updated",
                    ref2,
                    metadata(tenant, authorised.id())))
            .status(),
        is(200));
    intents.markAuthorized(
        tenant,
        authorised.id(),
        ref2,
        PaymentIntent.STATUS_REQUIRES_ACTION,
        "https://3ds.example/y");
    assertThat(statusOf(authorised.id()), is("AUTHORIZED"));
    assertThat(refOf(authorised.id()), is(ref2));
    assertThat(
        "no step for the customer once the hold is placed",
        scalar(
            PG,
            "SELECT coalesce(next_action_url, 'none') FROM payment.payment_intents WHERE id = '"
                + authorised.id()
                + "'"),
        is("none"));

    // The ordinary order: nothing happened first, so the answer is recorded as it always was.
    String ref3 = newRef();
    PaymentIntent plain = intent(tenant, Ids.newId(), null, PaymentIntent.STATUS_REQUIRES_ACTION);
    intents.markAuthorized(
        tenant, plain.id(), ref3, PaymentIntent.STATUS_REQUIRES_ACTION, "https://3ds.example/z");
    assertThat(statusOf(plain.id()), is("REQUIRES_ACTION"));
    assertThat(refOf(plain.id()), is(ref3));
    assertThat(
        scalar(
            PG,
            "SELECT next_action_url FROM payment.payment_intents WHERE id = '" + plain.id() + "'"),
        is("https://3ds.example/z"));
    intents.markAuthorized(tenant, plain.id(), ref3, PaymentIntent.STATUS_AUTHORIZED, null);
    assertThat(statusOf(plain.id()), is("AUTHORIZED"));
  }
}
