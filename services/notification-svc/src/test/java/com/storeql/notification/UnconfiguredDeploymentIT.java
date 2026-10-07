package com.storeql.notification;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.notification.provider.SimulatedPushProvider;
import com.storeql.notification.provider.SimulatedSmsProvider;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deployment that has not been given what a channel or a feature needs: the SMS carrier and the
 * push service are named but have no account behind them, and there is no key to seal a webhook's
 * secret under. Each request that needs what is missing is refused 503 by name, in the order the
 * resource asks (the caller's role first, then what the deployment lacks), and writes, sends and
 * meters nothing; what the deployment does have still works.
 */
@HelidonTest
class UnconfiguredDeploymentIT {

  private static final PostgresSupport PG;

  /** The properties this class changes, put back as they were so the next class finds its own. */
  private static final String[] KEYS = {
    "storeql.webhooks.secrets-key",
    "storeql.notification.sms.provider",
    "storeql.notification.push.provider",
    "storeql.notification.sms.twilio.account-sid",
    "storeql.notification.sms.twilio.auth-token",
    "storeql.notification.sms.twilio.from",
    "storeql.notification.push.fcm.project-id",
    "storeql.notification.push.fcm.service-account"
  };

  private static final Map<String, String> BEFORE = new HashMap<>();

  static {
    PG = PostgresSupport.start().wire("notification");
    for (String key : KEYS) {
      BEFORE.put(key, System.getProperty(key));
      System.clearProperty(key);
    }
    // A real carrier and a real push service, known to this deployment and never ready: no account
    // behind either. And no key to seal a webhook's secret under.
    System.setProperty("storeql.notification.sms.provider", "TWILIO");
    System.setProperty("storeql.notification.push.provider", "FCM");
  }

  private static final String T = "01a090f1-2222-7000-8000-000000000001";
  private static final String OTHER_T = "01a090f1-2222-7000-8000-000000000002";

  /** The business whose endpoint was made while the deployment still had its key. */
  private static final String ROTATING_T = "01a090f1-2222-7000-8000-000000000003";

  @Inject WebTarget target;
  @Inject SimulatedSmsProvider simulatedSms;
  @Inject SimulatedPushProvider simulatedPush;

  @AfterAll
  static void stop() {
    for (String key : KEYS) {
      String was = BEFORE.get(key);
      if (was == null) System.clearProperty(key);
      else System.setProperty(key, was);
    }
    PG.stop();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String tenant, String role, String user) {
    Invocation.Builder b =
        target
            .path(path)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", role);
    return user == null ? b : b.header("X-User-Id", user);
  }

  private Response call(String method, String path, String tenant, String role, String json) {
    Invocation.Builder b = as(path, tenant, role, Ids.newId().toString());
    return "GET".equals(method)
        ? b.get()
        : b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
  }

  private Response send(String json) {
    return call("POST", "/notifications/send", T, "OWNER", json);
  }

  private static String message(String channel, String recipient, String eventId) {
    return "{\"channel\":\""
        + channel
        + "\",\"recipient\":\""
        + recipient
        + "\",\"subject\":\"Your order\",\"body\":\"Ready to collect.\",\"type\":\"ORDER_READY\","
        + "\"eventId\":\""
        + eventId
        + "\"}";
  }

  private Response register(String tenant, String user, String token) {
    return as("/notifications/devices", tenant, "CUSTOMER", user)
        .post(
            Entity.entity(
                "{\"platform\":\"ANDROID\",\"token\":\"" + token + "\"}",
                MediaType.APPLICATION_JSON));
  }

  /** The stable code of a refusal: the problem's own member, or the legacy envelope's. */
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

  private static String loggedFor(String eventId) {
    return scalar(
        PG,
        "SELECT count(*) FROM notification.notification_log WHERE event_id = '" + eventId + "'");
  }

  private static String announced(String eventType) {
    return scalar(
        PG,
        "SELECT count(*) FROM notification.outbox WHERE event_type = '"
            + eventType
            + "' AND tenant_id = '"
            + T
            + "'");
  }

  private static String endpoints(String tenant) {
    return scalar(
        PG,
        "SELECT count(*) FROM notification.webhook_endpoints WHERE tenant_id = '" + tenant + "'");
  }

  private static String endpointBody() {
    return "{\"url\":\"https://hooks.example.com/storeql\",\"description\":\"ERP\","
        + "\"events\":[\"OrderPlaced\"]}";
  }

  // ── a carrier that is not ready ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "A text with no carrier behind it is refused 503 and is not sent, recorded or metered")
  void aTextWithNoCarrierIsRefusedAndNothingIsSentRecordedOrMetered() {
    String event = Ids.newId().toString();
    int before = simulatedSms.sent().size();

    assertRefused(
        "a text", send(message("SMS", "+447700900123", event)), 503, "CHANNEL_UNAVAILABLE");

    assertThat("the simulator is not standing in", simulatedSms.sent().size(), is(before));
    assertThat("not recorded, so a retry is not taken for a repeat", loggedFor(event), is("0"));
    assertThat("not metered", announced("SmsSent"), is("0"));
    // Asked again it is refused again: nothing was half done.
    assertRefused(
        "the same text again",
        send(message("SMS", "+447700900123", event)),
        503,
        "CHANNEL_UNAVAILABLE");
    // What is wrong with the request itself is still said first: the carrier is asked about last.
    assertRefused(
        "a number that is no number",
        send(message("SMS", "07700 900123", Ids.newId().toString())),
        400,
        "SMS_RECIPIENT_INVALID");
  }

  @Test
  @DisplayName(
      "A push with no provider is refused 503 before any device is looked for; a device stays")
  void aPushWithNoProviderIsRefusedBeforeAnyDeviceIsLookedForAndForgetsNothing() {
    String login = Ids.newId().toString();
    String event = Ids.newId().toString();
    int before = simulatedPush.sent().size();

    assertRefused("no device", send(message("PUSH", login, event)), 503, "CHANNEL_UNAVAILABLE");

    // Registering a device is the shopper's own act and needs no provider.
    assertThat(register(T, login, "tok-" + "a".repeat(40)).getStatus(), is(201));
    assertRefused("a device", send(message("PUSH", login, event)), 503, "CHANNEL_UNAVAILABLE");

    assertThat(simulatedPush.sent().size(), is(before));
    assertThat(loggedFor(event), is("0"));
    JsonArray mine =
        Envelopes.parse(as("/notifications/devices", T, "CUSTOMER", login).get(String.class))
            .getJsonArray("data");
    assertThat("a refusal of the channel forgets no device", mine.size(), is(1));
  }

  @Test
  @DisplayName("The channel list says which providers are not ready, and what is ready still sends")
  void theChannelListSaysWhichProvidersAreNotReadyAndWhatIsReadyStillSends() {
    JsonArray rows =
        Envelopes.parse(as("/admin/notifications/channels", T, "OWNER", null).get(String.class))
            .getJsonArray("data");
    JsonObject sms = Envelopes.find(rows, "channel", "SMS");
    assertThat(sms.getString("provider"), is("TWILIO"));
    assertThat(sms.getBoolean("configured"), is(false));
    JsonObject push = Envelopes.find(rows, "channel", "PUSH");
    assertThat(push.getString("provider"), is("FCM"));
    assertThat(push.getBoolean("configured"), is(false));
    assertThat(Envelopes.find(rows, "channel", "EMAIL").getBoolean("configured"), is(true));

    // The deployment's own default channel and the in-app feed are unaffected.
    for (String channel : new String[] {"EMAIL", "APP"}) {
      String event = Ids.newId().toString();
      Response r = send(message(channel, "reader@example.com", event));
      assertThat(channel + ": " + r.readEntity(String.class), r.getStatus(), is(202));
      assertThat(channel, loggedFor(event), is("1"));
    }
  }

  // ── webhooks with no key to seal a secret under ─────────────────────────────

  @Test
  @DisplayName(
      "An endpoint cannot be registered where no key seals its secret: 503, and none is stored")
  void anEndpointCannotBeRegisteredWhereNothingSealsItsSecret() {
    assertRefused(
        "the owner",
        call("POST", "/admin/webhooks/endpoints", T, "OWNER", endpointBody()),
        503,
        "WEBHOOKS_NOT_CONFIGURED");
    // The role is asked first, so a manager is told no, not "not switched on".
    assertRefused(
        "a manager",
        call("POST", "/admin/webhooks/endpoints", T, "MANAGER", endpointBody()),
        403,
        "FORBIDDEN");
    assertThat("nothing was stored", endpoints(T), is("0"));

    // What needs no key still answers: the catalogue and the list.
    Response events = call("GET", "/admin/webhooks/events", T, "OWNER", null);
    assertThat(events.readEntity(String.class), events.getStatus(), is(200));
    Response listed = call("GET", "/admin/webhooks/endpoints", T, "OWNER", null);
    assertThat(Envelopes.parse(listed.readEntity(String.class)).getJsonArray("data").size(), is(0));
  }

  @Test
  @DisplayName("A secret cannot be rotated where no key seals it: 503, and the stored one stands")
  void aSecretCannotBeRotatedWhereNothingSealsIt() {
    // An endpoint made while the deployment had its key; the key has since been lost.
    String endpoint = Ids.newId().toString();
    String sealed = "v1:sealed-while-the-key-was-here";
    exec(
        PG,
        "INSERT INTO notification.webhook_endpoints (id, tenant_id, url, description,"
            + " secret_sealed, events, enabled, consecutive_failures, created_by, created_at,"
            + " updated_at) VALUES ('"
            + endpoint
            + "','"
            + ROTATING_T
            + "','https://hooks.example.com/storeql','ERP','"
            + sealed
            + "', ARRAY['OrderPlaced'], true, 0, '"
            + Ids.newId()
            + "', now(), now())");
    String path = "/admin/webhooks/endpoints/" + endpoint + "/secret";

    assertRefused(
        "the owner", call("POST", path, ROTATING_T, "OWNER", null), 503, "WEBHOOKS_NOT_CONFIGURED");
    assertThat(
        "the stored secret stands",
        scalar(
            PG,
            "SELECT secret_sealed FROM notification.webhook_endpoints WHERE id = '"
                + endpoint
                + "'"),
        is(sealed));
    // Another business finds no such endpoint, key or no key; a manager is told no first.
    assertRefused(
        "another business's owner",
        call("POST", path, OTHER_T, "OWNER", null),
        404,
        "WEBHOOK_ENDPOINT_NOT_FOUND");
    assertRefused("a manager", call("POST", path, ROTATING_T, "MANAGER", null), 403, "FORBIDDEN");
    assertThat(
        scalar(
            PG,
            "SELECT secret_sealed FROM notification.webhook_endpoints WHERE id = '"
                + endpoint
                + "'"),
        is(sealed));
  }
}
