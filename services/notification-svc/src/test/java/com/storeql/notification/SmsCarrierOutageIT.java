package com.storeql.notification;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SMS carrier is down, or says no, while a text is being sent through the route: the carrier is
 * a stub at the end of the real Twilio driver. An outage is {@code 503} with the driver's code and
 * a refusal {@code 409} with the carrier's, and neither is recorded or metered — so a retry of the
 * same send, once the carrier is back, delivers once, and a repeat after that is not a second text.
 */
@HelidonTest
class SmsCarrierOutageIT {

  private static final PostgresSupport PG;
  private static final JsonStub CARRIER;

  /** An account made up for this run: no real one is named in a file. */
  private static final String SID = "AC" + Ids.newId().toString().replace("-", "");

  private static final AtomicInteger STATUS = new AtomicInteger(201);
  private static final AtomicReference<String> REPLY = new AtomicReference<>("{}");

  /** The properties this class changes, put back as they were so the next class finds its own. */
  private static final String[] KEYS = {
    "storeql.notification.sms.provider",
    "storeql.notification.sms.twilio.base-url",
    "storeql.notification.sms.twilio.account-sid",
    "storeql.notification.sms.twilio.auth-token",
    "storeql.notification.sms.twilio.from"
  };

  private static final Map<String, String> BEFORE = new HashMap<>();

  static {
    PG = PostgresSupport.start().wire("notification");
    CARRIER = JsonStub.start();
    CARRIER.on(
        "POST",
        "/2010-04-01/Accounts/" + SID + "/Messages.json",
        call -> new JsonStub.Answer(STATUS.get(), REPLY.get()));
    for (String key : KEYS) {
      BEFORE.put(key, System.getProperty(key));
    }
    System.setProperty("storeql.notification.sms.provider", "TWILIO");
    System.setProperty("storeql.notification.sms.twilio.base-url", CARRIER.baseUrl());
    System.setProperty("storeql.notification.sms.twilio.account-sid", SID);
    System.setProperty(
        "storeql.notification.sms.twilio.auth-token", Ids.newId().toString().replace("-", ""));
    System.setProperty("storeql.notification.sms.twilio.from", "+441234567890");
  }

  private static final String T = "01a090f1-3333-7000-8000-000000000001";

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    CARRIER.close();
    for (String key : KEYS) {
      String was = BEFORE.get(key);
      if (was == null) System.clearProperty(key);
      else System.setProperty(key, was);
    }
    PG.stop();
  }

  private Response send(String json) {
    return target
        .path("/notifications/send")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", T)
        .header("X-User-Id", Ids.newId().toString())
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String text(String eventId) {
    return "{\"channel\":\"SMS\",\"recipient\":\"+447700900123\",\"subject\":\"Your order\","
        + "\"body\":\"Ready to collect.\",\"type\":\"ORDER_READY\",\"eventId\":\""
        + eventId
        + "\"}";
  }

  private static String codeOf(String body) {
    JsonObject problem = Json.createReader(new StringReader(body)).readObject();
    return problem.containsKey("code")
        ? problem.getString("code")
        : problem.getJsonObject("error").getString("code");
  }

  private static void assertRefused(String what, Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(what + ": " + body, r.getStatus(), is(status));
    assertThat(what + ": " + body, codeOf(body), is(code));
  }

  private static long logged(String eventId) {
    return Long.parseLong(
        scalar(
            PG,
            "SELECT count(*) FROM notification.notification_log WHERE event_id = '"
                + eventId
                + "'"));
  }

  private static long metered() {
    return Long.parseLong(
        scalar(
            PG,
            "SELECT count(*) FROM notification.outbox WHERE event_type = 'SmsSent'"
                + " AND tenant_id = '"
                + T
                + "'"));
  }

  @Test
  @DisplayName(
      "A carrier outage is 503 and not recorded or metered, so the retry delivers once and a repeat sends nothing")
  void aCarrierOutageIsRefusedAndNotRecordedSoTheRetryDeliversOnce() {
    String event = Ids.newId().toString();
    String json = text(event);
    long meteredBefore = metered();
    int asked = CARRIER.calls().size();

    STATUS.set(503);
    REPLY.set("{}");
    assertRefused("the carrier is down", send(json), 503, "SMS_PROVIDER_UNAVAILABLE");
    assertThat("the carrier was asked", CARRIER.calls().size(), is(asked + 1));
    assertThat("not recorded", logged(event), is(0L));
    assertThat("not metered", metered(), is(meteredBefore));

    // The carrier is back: the same send, under the same id, now goes, once.
    STATUS.set(201);
    REPLY.set("{\"sid\":\"SM1\"}");
    Response sent = send(json);
    assertThat(sent.readEntity(String.class), sent.getStatus(), is(202));
    assertThat(logged(event), is(1L));
    assertThat(metered(), is(meteredBefore + 1));

    // A repeat is the same text: the carrier is not asked again, and nothing more is written.
    assertThat(send(json).getStatus(), is(202));
    assertThat(CARRIER.calls().size(), is(asked + 2));
    assertThat(logged(event), is(1L));
    assertThat(metered(), is(meteredBefore + 1));
  }

  @Test
  @DisplayName(
      "A carrier's refusal is 409 with the carrier's own code, and is not recorded or metered")
  void aCarrierRefusalIsConflictWithItsCodeAndNothingIsRecordedOrMetered() {
    String event = Ids.newId().toString();
    long meteredBefore = metered();
    STATUS.set(400);
    REPLY.set("{\"code\":21211,\"message\":\"The 'To' number is not a valid phone number.\"}");

    assertRefused("the carrier refuses", send(text(event)), 409, "SMS_REJECTED_21211");

    assertThat("not recorded", logged(event), is(0L));
    assertThat("not metered", metered(), is(meteredBefore));
  }
}
