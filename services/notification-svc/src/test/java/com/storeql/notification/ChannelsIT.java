package com.storeql.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.notification.provider.SimulatedPushProvider;
import com.storeql.notification.provider.SimulatedSmsProvider;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SMS and push (13.7), through the routes: a text to a number and a push to a login's devices, both
 * recorded on the log with their channel; a device registered, listed and removed by the login that
 * holds it and nobody else — with the wrong number, the wrong channel, marketing where consent
 * cannot stand, a full device list and a hammering caller tried beside the right ones.
 */
@HelidonTest
class ChannelsIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "notification");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a090f1-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a090f1-1111-7000-8000-000000000002";

  @Inject WebTarget target;
  @Inject SimulatedSmsProvider sms;
  @Inject SimulatedPushProvider push;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private Invocation.Builder as(String path, String tenant, String roles, String user) {
    var b =
        target
            .path(path)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles);
    if (user != null) b = b.header("X-User-Id", user);
    return b;
  }

  private Response send(String json) {
    return as("/notifications/send", T, "OWNER", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String text(String to, String body, String extra) {
    return "{\"channel\":\"SMS\",\"recipient\":\""
        + to
        + "\",\"subject\":\"Your order\",\"body\":\""
        + body
        + "\",\"type\":\"ORDER_READY\",\"eventId\":\""
        + Ids.newId()
        + "\""
        + extra
        + "}";
  }

  private Response register(String tenant, String user, String platform, String token) {
    return as("/notifications/devices", tenant, "CUSTOMER", user)
        .post(
            Entity.entity(
                "{\"platform\":\"" + platform + "\",\"token\":\"" + token + "\"}",
                MediaType.APPLICATION_JSON));
  }

  private static JsonObject json(String body) {
    try (var r = Json.createReader(new StringReader(body))) {
      return r.readObject();
    }
  }

  private JsonArray log(String channel) {
    Response r = as("/admin/notifications", T, "OWNER", null).property("channel", channel).get();
    // The property is not a query param; build the query explicitly.
    r.close();
    Response q =
        target
            .path("/admin/notifications")
            .queryParam("channel", channel)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    String body = q.readEntity(String.class);
    assertThat(body, q.getStatus(), is(200));
    return json(body).getJsonArray("data");
  }

  // ── SMS ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A text goes to the number and is logged on the SMS channel")
  void aTextGoesToTheNumberAndIsLogged() {
    int before = sms.sent().size();
    Response r = send(text("+447700900123", "Your order is ready to collect.", ""));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(202));
    assertThat(body, containsString("\"channel\":\"SMS\""));
    assertThat(sms.sent().size(), is(before + 1));
    assertThat(sms.sent().get(0).to(), is("+447700900123"));
    assertThat(sms.sent().get(0).body(), is("Your order is ready to collect."));
    assertThat(log("SMS").toString(), containsString("+447700900123"));
    assertThat(log("SMTP").toString(), not(containsString("+447700900123")));
  }

  /** The parts of every text announced for this business so far, oldest first (21.10). */
  private static java.util.List<Integer> announcedParts() {
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM notification.outbox WHERE event_type = 'SmsSent'"
                    + " AND tenant_id = ?::uuid ORDER BY created_at, id")) {
      ps.setString(1, T);
      java.util.List<Integer> parts = new java.util.ArrayList<>();
      try (var rs = ps.executeQuery()) {
        while (rs.next()) parts.add(json(rs.getString(1)).getInt("parts"));
      }
      return parts;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("Every text sent is announced for the meter, in the parts the carrier bills")
  void everyTextIsMetered() {
    int before = announcedParts().size();
    String longGsm = "A".repeat(200);
    assertThat(send(text("+447700900124", longGsm, "")).getStatus(), is(202));
    // One letter outside the GSM alphabet sends the whole text as UCS-2: 70 to a part.
    assertThat(
        send(text("+447700900124", "Zamówienie gotowe. Dziękujemy!", "")).getStatus(), is(202));
    // Refused before it was sent: nothing to meter.
    assertThat(send(text("07700 900124", "no", "")).getStatus(), is(400));
    java.util.List<Integer> parts = announcedParts();
    assertThat(parts.size(), is(before + 2));
    assertThat("200 GSM characters are two parts of 153", parts.get(before), is(2));
    assertThat(parts.get(before + 1), is(1));
  }

  @Test
  @DisplayName(
      "A number that is not E.164, a body over 1600 characters, an unknown channel: refused")
  void badSmsInputIsRefusedByName() {
    int before = sms.sent().size();
    Response bad = send(text("07700 900123", "hi", ""));
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("SMS_RECIPIENT_INVALID"));
    Response empty = send(text("+", "hi", ""));
    assertThat(empty.getStatus(), is(400));
    Response longBody = send(text("+447700900123", "x".repeat(1601), ""));
    assertThat(longBody.getStatus(), is(400));
    assertThat(longBody.readEntity(String.class), containsString("SMS_BODY_TOO_LONG"));
    Response channel =
        send("{\"channel\":\"PIGEON\",\"recipient\":\"a@b.c\",\"subject\":\"s\",\"body\":\"b\"}");
    assertThat(channel.getStatus(), is(400));
    assertThat(channel.readEntity(String.class), containsString("CHANNEL_UNKNOWN"));
    assertThat("nothing was sent", sms.sent().size(), is(before));
  }

  @Test
  @DisplayName(
      "Marketing by SMS needs consent the shop can show; by push it cannot be lawful at all")
  void marketingIsGatedPerChannel() {
    int before = sms.sent().size();
    // Consent cannot be checked here (no customer-svc): refused, nothing sent.
    Response noConsent =
        send(
            text(
                "+447700900123",
                "20% off",
                ",\"category\":\"MARKETING\",\"customerId\":\"" + Ids.newId() + "\""));
    assertThat(noConsent.getStatus(), is(409));
    assertThat(noConsent.readEntity(String.class), containsString("MARKETING_CONSENT_MISSING"));
    assertThat(sms.sent().size(), is(before));

    String login = Ids.newId().toString();
    assertThat(register(T, login, "ANDROID", "tok-" + "a".repeat(40)).getStatus(), is(201));
    Response byPush =
        send(
            "{\"channel\":\"PUSH\",\"recipient\":\""
                + login
                + "\",\"subject\":\"Offers\",\"body\":\"20% off\",\"category\":\"MARKETING\","
                + "\"customerId\":\""
                + Ids.newId()
                + "\"}");
    assertThat(byPush.getStatus(), is(409));
    assertThat(byPush.readEntity(String.class), containsString("MARKETING_CHANNEL_UNSUPPORTED"));
  }

  // ── push and devices ──────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A login registers its device, is pushed to on it, and removes it; then nothing reaches it")
  void aLoginIsPushedToOnItsOwnDevices() {
    String login = Ids.newId().toString();
    String noDevice =
        "{\"channel\":\"PUSH\",\"recipient\":\""
            + login
            + "\",\"subject\":\"Ready\",\"body\":\"Collect it.\"}";
    Response none = send(noDevice);
    assertThat(none.getStatus(), is(409));
    assertThat(none.readEntity(String.class), containsString("PUSH_NO_DEVICE"));

    Response reg = register(T, login, "ios", "tok-" + "b".repeat(40));
    String regBody = reg.readEntity(String.class);
    assertThat(regBody, reg.getStatus(), is(201));
    assertThat("the whole token is never shown", regBody, not(containsString("b".repeat(40))));
    assertThat(regBody, containsString("\"platform\":\"IOS\""));
    String deviceId = json(regBody).getJsonObject("data").getString("id");

    int before = push.sent().size();
    Response sent = send(noDevice);
    assertThat(sent.readEntity(String.class), sent.getStatus(), is(202));
    assertThat(push.sent().size(), is(before + 1));
    assertThat(push.sent().get(0).title(), is("Ready"));
    assertThat(log("PUSH").toString(), containsString(login));

    // Their own list shows it; another login's list does not; another login cannot remove it.
    String mine = as("/notifications/devices", T, "CUSTOMER", login).get(String.class);
    assertThat(mine, containsString(deviceId));
    String others =
        as("/notifications/devices", T, "CUSTOMER", Ids.newId().toString()).get(String.class);
    assertThat(others, not(containsString(deviceId)));
    Response notTheirs =
        as("/notifications/devices/" + deviceId, T, "CUSTOMER", Ids.newId().toString()).delete();
    assertThat(notTheirs.getStatus(), is(404));
    assertThat(notTheirs.readEntity(String.class), containsString("DEVICE_NOT_FOUND"));
    assertThat(
        as("/notifications/devices/" + deviceId, T, "CUSTOMER", login).delete().getStatus(),
        is(204));
    Response twice = as("/notifications/devices/" + deviceId, T, "CUSTOMER", login).delete();
    assertThat(twice.getStatus(), is(404));
    assertThat(twice.readEntity(String.class), containsString("DEVICE_NOT_FOUND"));
    assertThat(send(noDevice).getStatus(), is(409));
  }

  @Test
  @DisplayName("A device the provider no longer knows is forgotten on the spot")
  void aDeadDeviceIsForgotten() {
    String login = Ids.newId().toString();
    assertThat(register(T, login, "WEB", "gone-" + "c".repeat(40)).getStatus(), is(201));
    Response r =
        send(
            "{\"channel\":\"PUSH\",\"recipient\":\""
                + login
                + "\",\"subject\":\"Ready\",\"body\":\"b\"}");
    assertThat(r.getStatus(), is(409));
    assertThat(
        as("/notifications/devices", T, "CUSTOMER", login).get(String.class),
        containsString("\"data\":[]"));
  }

  @Test
  @DisplayName(
      "Bad device input is refused, the same token twice is one device, and no login means no device")
  void badDeviceInputIsRefused() {
    String login = Ids.newId().toString();
    assertThat(register(T, login, "PALM", "tok-" + "d".repeat(40)).getStatus(), is(400));
    assertThat(register(T, login, "ANDROID", "short").getStatus(), is(400));
    assertThat(register(T, login, "ANDROID", "t".repeat(5000)).getStatus(), is(400));
    assertThat(register(T, login, "ANDROID", "tok-" + "e".repeat(40)).getStatus(), is(201));
    assertThat(register(T, login, "ANDROID", "tok-" + "e".repeat(40)).getStatus(), is(201));
    String mine = as("/notifications/devices", T, "CUSTOMER", login).get(String.class);
    assertThat(json(mine).getJsonArray("data").size(), is(1));
    assertThat(
        register(T, null, "ANDROID", "tok-" + "f".repeat(40)).getStatus(), anyOf(is(401), is(403)));
    // A record is per shop: the same login at another shop has no device there.
    assertThat(
        as("/notifications/devices", OTHER_T, "CUSTOMER", login).get(String.class),
        containsString("\"data\":[]"));
  }

  @Test
  @DisplayName("A login holds at most ten devices at one shop")
  void theDeviceListIsCapped() {
    String login = Ids.newId().toString();
    for (int i = 0; i < 10; i++) {
      assertThat(
          "device " + i,
          register(T, login, "ANDROID", "tok-" + i + "-" + "g".repeat(40)).getStatus(),
          is(201));
    }
    Response eleventh = register(T, login, "ANDROID", "tok-11-" + "g".repeat(40));
    assertThat(eleventh.getStatus(), is(409));
    assertThat(eleventh.readEntity(String.class), containsString("PUSH_DEVICE_LIMIT"));
    // Refreshing one already held is not an eleventh.
    assertThat(register(T, login, "ANDROID", "tok-3-" + "g".repeat(40)).getStatus(), is(201));
  }

  // ── the channels, and abuse ───────────────────────────────────────────────

  @Test
  @DisplayName("The shop can see which channels it has, and a shopper cannot")
  void channelsAreListedForManagement() {
    String body = as("/admin/notifications/channels", T, "OWNER", null).get(String.class);
    assertThat(body, containsString("\"channel\":\"SMS\""));
    assertThat(body, containsString("\"provider\":\"SIMULATED\""));
    assertThat(body, containsString("\"channel\":\"PUSH\""));
    assertThat(body, containsString("\"channel\":\"EMAIL\""));
    assertThat(
        as("/admin/notifications/channels", T, "CUSTOMER", Ids.newId().toString())
            .get()
            .getStatus(),
        is(403));
    assertThat(
        as("/notifications/send", T, "CUSTOMER", Ids.newId().toString())
            .post(Entity.entity(text("+447700900123", "hi", ""), MediaType.APPLICATION_JSON))
            .getStatus(),
        is(403));
  }

  @Test
  @DisplayName("Thirty texts to a bad number are thirty refusals and nothing sent")
  void hammeringWithABadNumberSendsNothing() {
    int before = sms.sent().size();
    for (int i = 0; i < 30; i++) {
      Response r = send(text("+0", "spam " + i, ""));
      assertThat("attempt " + i, r.getStatus(), is(400));
      r.close();
    }
    assertThat(sms.sent().size(), is(before));
  }
}
