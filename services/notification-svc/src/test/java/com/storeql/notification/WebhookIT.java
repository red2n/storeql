package com.storeql.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.storeql.ids.Ids;
import com.storeql.notification.service.WebhookDeliverer;
import com.storeql.notification.service.WebhookFanout;
import com.storeql.notification.service.WebhookSigner;
import com.storeql.test.PostgresSupport;
import com.sun.net.httpserver.HttpServer;
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
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Webhooks to a business's own systems (22.6): an owner registers an endpoint for the events it
 * wants and is shown its secret once; an event of a subscribed type reaches the endpoint signed,
 * once, and only the business's own; a receiver that fails is tried again with a growing wait and
 * given up on after enough tries; an endpoint that fails on and on is switched off; a delivery can
 * be sent again by hand; a ping proves the wiring; a rotated secret signs from then on.
 *
 * <p>Kafka is off: events are handed to the fan-out as the consumer would; the deliverer's clock is
 * not running, so each tick is called here. The receiver is an HTTP server in this process, which
 * the deployment names as its own so plain HTTP on localhost is allowed.
 */
@HelidonTest
class WebhookIT {

  private static final PostgresSupport PG;
  private static final HttpServer RECEIVER;
  private static final String BASE;
  private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
  private static final AtomicInteger ANSWER = new AtomicInteger(200);

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "notification");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.webhooks.enabled", "false");
    System.setProperty("storeql.webhooks.insecure-hosts", "localhost");
    System.setProperty(
        "storeql.webhooks.secrets-key", "gT8/vWxNNv3W8kHaM/HNGPKahXfWsLCFkFZHlKqyeI4=");
    System.setProperty("storeql.webhooks.max-attempts", "5");
    System.setProperty("storeql.webhooks.disable-after-failures", "3");
    try {
      RECEIVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    RECEIVER.createContext(
        "/hook",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          RECEIVED.add(
              new Received(
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().entrySet().stream()
                      .collect(
                          java.util.stream.Collectors.toMap(
                              e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                              e -> e.getValue().get(0))),
                  new String(body, StandardCharsets.UTF_8)));
          int status = ANSWER.get();
          byte[] answer = ("{\"got\":" + body.length + "}").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    RECEIVER.start();
    BASE = "http://localhost:" + RECEIVER.getAddress().getPort();
  }

  /** What the receiver saw. */
  private record Received(String path, Map<String, String> headers, String body) {}

  @Inject WebTarget target;
  @Inject WebhookFanout fanout;
  @Inject WebhookDeliverer deliverer;

  @AfterAll
  static void stop() {
    RECEIVER.stop(0);
    PG.stop();
  }

  @BeforeEach
  void reset() {
    RECEIVED.clear();
    ANSWER.set(200);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private record Answer(int status, JsonObject body) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    JsonArray items() {
      return body.getJsonArray("data");
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  private record Caller(UUID tenant, String roles) {}

  private static Caller owner(UUID tenant) {
    return new Caller(tenant, "OWNER");
  }

  private Answer call(String method, String path, Caller who, String json) {
    WebTarget t = target;
    String[] parts = path.split("\\?", 2);
    t = t.path(parts[0]);
    if (parts.length == 2) {
      for (String pair : parts[1].split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv.length == 2 ? kv[1] : "");
      }
    }
    Invocation.Builder b =
        t.request()
            .header("X-Tenant-Id", who.tenant())
            .header("X-User-Id", Ids.newId())
            .header("X-Roles", who.roles());
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "DELETE" -> b.delete();
          case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
          default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
        };
    String text = r.readEntity(String.class);
    JsonObject body =
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject();
    return new Answer(r.getStatus(), body);
  }

  private static String endpointJson(String url, String description, String... events) {
    StringBuilder sb = new StringBuilder("{\"url\":\"").append(url).append("\"");
    if (description != null) sb.append(",\"description\":\"").append(description).append("\"");
    sb.append(",\"events\":[");
    for (int i = 0; i < events.length; i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(events[i]).append('"');
    }
    return sb.append("]}").toString();
  }

  private Answer register(Caller who, String url, String... events) {
    return call("POST", "/admin/webhooks/endpoints", who, endpointJson(url, "ERP", events));
  }

  private static String orderPlaced(UUID tenant, UUID eventId) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderPlaced\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + Ids.newId()
        + "\",\"occurredAt\":\"2026-09-23T10:00:00Z\",\"orderId\":\""
        + Ids.newId()
        + "\",\"total\":42.50}";
  }

  private static String event(String type, UUID tenant) {
    return orderPlaced(tenant, Ids.newId()).replace("\"OrderPlaced\"", "\"" + type + "\"");
  }

  private static void sql(String statement, Object... args) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps = c.prepareStatement(statement)) {
      c.setSchema("notification");
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean absent(JsonObject obj, String name) {
    return !obj.containsKey(name) || obj.isNull(name);
  }

  private JsonArray deliveries(Caller who, String query) {
    Answer a = call("GET", "/admin/webhooks/deliveries" + (query == null ? "" : query), who, null);
    assertThat(a.body().toString(), a.status(), is(200));
    return a.data().getJsonArray("items");
  }

  private JsonObject delivery(Caller who, String id) {
    Answer a = call("GET", "/admin/webhooks/deliveries/" + id, who, null);
    assertThat(a.body().toString(), a.status(), is(200));
    return a.data();
  }

  /** Signed to the Standard Webhooks specification: id, timestamp, and v1 over all three. */
  private static boolean verifies(Received r, String secret) {
    return WebhookSigner.verify(
        secret,
        r.headers().get("webhook-id"),
        r.headers().get("webhook-timestamp"),
        r.headers().get("webhook-signature"),
        r.body());
  }

  private static void assertSigned(Received r, String secret, String type) {
    assertThat(r.headers().get("content-type"), startsWith("application/json"));
    assertThat(r.headers().get("x-storeql-event"), is(type));
    assertThat(r.headers().get("webhook-id"), not(nullValue()));
    assertThat(r.headers().get("webhook-timestamp"), not(nullValue()));
    assertThat(r.headers().get("user-agent"), startsWith("StoreQL-Webhooks/"));
    assertThat(
        "the signature verifies with the endpoint's secret over the body as sent",
        verifies(r, secret),
        is(true));
  }

  // ── registering ────────────────────────────────────────────────────────────

  @Test
  void anOwnerRegistersAnEndpointAndIsShownItsSecretOnce() {
    UUID tenant = Ids.newId();
    Caller own = owner(tenant);

    Answer catalogue = call("GET", "/admin/webhooks/events", own, null);
    assertThat(catalogue.body().toString(), catalogue.status(), is(200));
    List<String> types = new ArrayList<>();
    for (var v : catalogue.items()) types.add(v.asJsonObject().getString("type"));
    assertThat(types.size(), greaterThan(10));
    assertThat(types.contains("OrderPlaced"), is(true));
    assertThat(types.contains("StockBelowThreshold"), is(true));
    assertThat(catalogue.items().getJsonObject(0).getString("description").isBlank(), is(false));

    Answer made = register(own, BASE + "/hook", "OrderPlaced", "StockReceived");
    assertThat(made.body().toString(), made.status(), is(201));
    JsonObject ep = made.data();
    String secret = ep.getString("secret");
    assertThat(secret, startsWith("whsec_"));
    assertThat("32 bytes in base64, as the specification writes it", secret.length(), is(50));
    assertThat(ep.getString("url"), is(BASE + "/hook"));
    assertThat(ep.getBoolean("enabled"), is(true));
    assertThat(ep.getJsonArray("events"), hasSize(2));

    Answer listed = call("GET", "/admin/webhooks/endpoints", own, null);
    assertThat(listed.status(), is(200));
    assertThat(listed.items(), hasSize(1));
    assertThat(
        "the secret is shown once",
        listed.items().getJsonObject(0).containsKey("secret"),
        is(false));
    Answer one = call("GET", "/admin/webhooks/endpoints/" + ep.getString("id"), own, null);
    assertThat(one.status(), is(200));
    assertThat(one.data().containsKey("secret"), is(false));

    // A manager reads; only the owner writes; another business sees nothing.
    Caller manager = new Caller(tenant, "MANAGER");
    assertThat(call("GET", "/admin/webhooks/endpoints", manager, null).items(), hasSize(1));
    assertThat(register(manager, BASE + "/hook", "OrderPlaced").status(), is(403));
    assertThat(
        register(new Caller(tenant, "CASHIER"), BASE + "/hook", "OrderPlaced").status(), is(403));
    Caller other = owner(Ids.newId());
    assertThat(call("GET", "/admin/webhooks/endpoints", other, null).items(), hasSize(0));
    assertThat(
        call("GET", "/admin/webhooks/endpoints/" + ep.getString("id"), other, null).status(),
        is(404));

    // What is refused before anything is kept.
    Answer unknown = register(own, BASE + "/hook", "OrderPlaced", "SomethingElse");
    assertThat(unknown.status(), is(400));
    assertThat(unknown.code(), is("WEBHOOK_EVENT_UNKNOWN"));
    Answer none = register(own, BASE + "/hook");
    assertThat(none.status(), is(400));
    assertThat(none.code(), is("WEBHOOK_EVENTS_EMPTY"));
    for (String bad :
        List.of(
            "ftp://example.com/hook",
            "https://user:pw@example.com/hook",
            "https://192.168.1.10/hook",
            "https://10.0.0.5/hook",
            "https://169.254.169.254/latest",
            "http://example.com/hook",
            "not a url")) {
      Answer refused = register(own, bad, "OrderPlaced");
      assertThat(bad, refused.status(), is(400));
      assertThat(bad, refused.code(), is("WEBHOOK_URL_INVALID"));
    }
    assertThat(
        call(
                "POST",
                "/admin/webhooks/endpoints",
                own,
                "{\"url\":\"" + BASE + "/hook\",\"events\":[\"OrderPlaced\"]}")
            .status(),
        is(400));
    assertThat(call("GET", "/admin/webhooks/endpoints", own, null).items(), hasSize(1));

    // Changing it: the events, the description, switched off and on; the url held to the rule.
    Answer changed =
        call(
            "PUT",
            "/admin/webhooks/endpoints/" + ep.getString("id"),
            own,
            "{\"description\":\"Warehouse ERP\",\"events\":[\"OrderPlaced\"],\"enabled\":false}");
    assertThat(changed.body().toString(), changed.status(), is(200));
    assertThat(changed.data().getString("description"), is("Warehouse ERP"));
    assertThat(changed.data().getJsonArray("events"), hasSize(1));
    assertThat(changed.data().getBoolean("enabled"), is(false));
    assertThat(
        call(
                "PUT",
                "/admin/webhooks/endpoints/" + ep.getString("id"),
                own,
                "{\"url\":\"https://10.1.1.1/x\"}")
            .code(),
        is("WEBHOOK_URL_INVALID"));
    assertThat(
        call(
                "PUT",
                "/admin/webhooks/endpoints/" + ep.getString("id"),
                manager,
                "{\"enabled\":true}")
            .status(),
        is(403));

    // Gone: and its deliveries with it.
    assertThat(
        call("DELETE", "/admin/webhooks/endpoints/" + ep.getString("id"), other, null).status(),
        is(404));
    assertThat(
        call("DELETE", "/admin/webhooks/endpoints/" + ep.getString("id"), own, null).status(),
        is(200));
    assertThat(call("GET", "/admin/webhooks/endpoints", own, null).items(), hasSize(0));
    assertThat(
        call("DELETE", "/admin/webhooks/endpoints/" + ep.getString("id"), own, null).status(),
        is(404));
  }

  // ── delivering ─────────────────────────────────────────────────────────────

  @Test
  void anEventOfASubscribedTypeIsDeliveredSignedOnceAndOnlyToItsBusiness() {
    UUID tenant = Ids.newId();
    Caller own = owner(tenant);
    Answer made = register(own, BASE + "/hook", "OrderPlaced");
    assertThat(made.status(), is(201));
    String endpoint = made.data().getString("id");
    String secret = made.data().getString("secret");

    UUID eventId = Ids.newId();
    fanout.accept(orderPlaced(tenant, eventId));
    fanout.accept(orderPlaced(tenant, eventId)); // redelivered by Kafka: the same event twice
    fanout.accept(orderPlaced(Ids.newId(), Ids.newId())); // another business's order
    fanout.accept(event("OrderCancelled", tenant)); // a type this endpoint did not ask for
    fanout.accept("{not json");

    JsonArray pending = deliveries(own, "?endpointId=" + endpoint);
    assertThat(pending.toString(), pending, hasSize(1));
    JsonObject d = pending.getJsonObject(0);
    assertThat(d.getString("status"), is("PENDING"));
    assertThat(d.getString("eventType"), is("OrderPlaced"));
    assertThat(d.getString("eventId"), is(eventId.toString()));
    assertThat(d.getInt("attempts"), is(0));

    int sent = deliverer.tick();
    assertThat(sent, is(1));
    assertThat(RECEIVED, hasSize(1));
    Received r = RECEIVED.get(0);
    assertThat(r.path(), is("/hook"));
    assertSigned(r, secret, "OrderPlaced");
    assertThat(r.headers().get("webhook-id"), is(d.getString("id")));
    assertThat(r.headers().get("x-storeql-attempt"), is("1"));
    JsonObject body = Json.createReader(new StringReader(r.body())).readObject();
    assertThat(body.getString("id"), is(d.getString("id")));
    assertThat(body.getString("type"), is("OrderPlaced"));
    assertThat(body.getString("eventId"), is(eventId.toString()));
    assertThat(body.getString("tenantId"), is(tenant.toString()));
    assertThat(body.getInt("attempt"), is(1));
    assertThat(
        "the event travels whole",
        body.getJsonObject("data").getString("eventType"),
        is("OrderPlaced"));
    assertThat(body.getJsonObject("data").getJsonNumber("total").doubleValue(), is(42.5));

    JsonObject delivered = delivery(own, d.getString("id"));
    assertThat(delivered.getString("status"), is("DELIVERED"));
    assertThat(delivered.getInt("attempts"), is(1));
    assertThat(delivered.getInt("lastStatus"), is(200));
    assertThat(absent(delivered, "deliveredAt"), is(false));
    JsonArray attempts = delivered.getJsonArray("attemptLog");
    assertThat(attempts, hasSize(1));
    assertThat(attempts.getJsonObject(0).getInt("statusCode"), is(200));
    assertThat(attempts.getJsonObject(0).getString("responseSnippet"), startsWith("{\"got\":"));
    assertThat(attempts.getJsonObject(0).getInt("durationMs") >= 0, is(true));
    assertThat("nothing left to send", deliverer.tick(), is(0));
    assertThat(RECEIVED, hasSize(1));

    // The endpoint remembers its last success; another business cannot read the delivery.
    JsonObject ep = call("GET", "/admin/webhooks/endpoints/" + endpoint, own, null).data();
    assertThat(absent(ep, "lastDeliveredAt"), is(false));
    assertThat(
        call("GET", "/admin/webhooks/deliveries/" + d.getString("id"), owner(Ids.newId()), null)
            .status(),
        is(404));

    // Switched off: a matching event is not even queued.
    assertThat(
        call("PUT", "/admin/webhooks/endpoints/" + endpoint, own, "{\"enabled\":false}").status(),
        is(200));
    fanout.accept(orderPlaced(tenant, Ids.newId()));
    assertThat(deliveries(own, "?endpointId=" + endpoint), hasSize(1));
  }

  @Test
  void aFailingReceiverIsTriedAgainWithAGrowingWaitThenGivenUpOnAndCanBeSentAgainByHand() {
    UUID tenant = Ids.newId();
    Caller own = owner(tenant);
    Answer made = register(own, BASE + "/hook", "OrderPlaced");
    assertThat(made.status(), is(201));
    String endpoint = made.data().getString("id");

    ANSWER.set(500);
    fanout.accept(orderPlaced(tenant, Ids.newId()));
    assertThat(deliverer.tick(), is(1));
    String id = deliveries(own, "?endpointId=" + endpoint).getJsonObject(0).getString("id");
    JsonObject after1 = delivery(own, id);
    assertThat(after1.getString("status"), is("PENDING"));
    assertThat(after1.getInt("attempts"), is(1));
    assertThat(after1.getInt("lastStatus"), is(500));
    Instant next = Instant.parse(after1.getString("nextAttemptAt"));
    assertThat(
        "the next try waits about a minute", next.isAfter(Instant.now().plusSeconds(45)), is(true));
    assertThat("not due yet, so not tried", deliverer.tick(), is(0));
    assertThat(RECEIVED, hasSize(1));

    // The minute passes; the receiver is back.
    sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE id = ?", Ids.parse(id));
    ANSWER.set(200);
    assertThat(deliverer.tick(), is(1));
    JsonObject after2 = delivery(own, id);
    assertThat(after2.getString("status"), is("DELIVERED"));
    assertThat(after2.getInt("attempts"), is(2));
    assertThat(after2.getJsonArray("attemptLog"), hasSize(2));
    assertThat(after2.getJsonArray("attemptLog").getJsonObject(0).getInt("statusCode"), is(500));
    assertThat(after2.getJsonArray("attemptLog").getJsonObject(1).getInt("statusCode"), is(200));
    assertThat(RECEIVED.get(1).headers().get("x-storeql-attempt"), is("2"));

    // A receiver that never comes back: each try waits longer than the last, and after three
    // failures in a row the endpoint is switched off, with the reason, so nothing more is tried.
    ANSWER.set(503);
    fanout.accept(orderPlaced(tenant, Ids.newId()));
    String dead = null;
    for (var v : deliveries(own, "?endpointId=" + endpoint + "&status=PENDING"))
      dead = v.asJsonObject().getString("id");
    assertThat(dead, not(nullValue()));
    long lastWait = 0;
    for (int i = 1; i <= 3; i++) {
      assertThat("try " + i, deliverer.tick(), is(1));
      JsonObject d = delivery(own, dead);
      assertThat(d.getInt("attempts"), is(i));
      assertThat(d.getString("status"), is("PENDING"));
      long wait =
          Instant.parse(d.getString("nextAttemptAt")).getEpochSecond()
              - Instant.now().getEpochSecond();
      assertThat("try " + i + " waits longer than the last", wait > lastWait, is(true));
      lastWait = wait;
      sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE id = ?", Ids.parse(dead));
    }
    JsonObject ep = call("GET", "/admin/webhooks/endpoints/" + endpoint, own, null).data();
    assertThat("switched off after three failures in a row", ep.getBoolean("enabled"), is(false));
    assertThat(ep.getString("disabledReason"), not(nullValue()));
    assertThat(ep.getInt("consecutiveFailures"), is(3));
    assertThat("nothing goes to an endpoint switched off", deliverer.tick(), is(0));

    // Switched on again by hand: the run of failures is over; the tries go on to the last allowed.
    assertThat(
        call("PUT", "/admin/webhooks/endpoints/" + endpoint, own, "{\"enabled\":true}").status(),
        is(200));
    ep = call("GET", "/admin/webhooks/endpoints/" + endpoint, own, null).data();
    assertThat(ep.getInt("consecutiveFailures"), is(0));
    assertThat(absent(ep, "disabledReason"), is(true));
    for (int i = 4; i <= 5; i++) {
      assertThat("try " + i, deliverer.tick(), is(1));
      JsonObject d = delivery(own, dead);
      assertThat(d.getInt("attempts"), is(i));
      if (i < 5) {
        assertThat(d.getString("status"), is("PENDING"));
        sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE id = ?", Ids.parse(dead));
      } else {
        assertThat(d.getString("status"), is("DEAD"));
        assertThat(absent(d, "nextAttemptAt"), is(true));
      }
    }
    assertThat("given up on, so no sixth try", deliverer.tick(), is(0));

    // Sent again by hand, to the receiver now back.
    ANSWER.set(200);
    Answer again = call("POST", "/admin/webhooks/deliveries/" + dead + "/redeliver", own, null);
    assertThat(again.body().toString(), again.status(), is(200));
    assertThat(again.data().getString("status"), is("PENDING"));
    assertThat(deliverer.tick(), is(1));
    JsonObject revived = delivery(own, dead);
    assertThat(revived.getString("status"), is("DELIVERED"));
    assertThat(revived.getInt("attempts"), is(6));
    ep = call("GET", "/admin/webhooks/endpoints/" + endpoint, own, null).data();
    assertThat(
        "a delivery that lands clears the run of failures",
        ep.getInt("consecutiveFailures"),
        is(0));
    assertThat(
        call("POST", "/admin/webhooks/deliveries/" + dead + "/redeliver", owner(Ids.newId()), null)
            .status(),
        is(404));

    // The log, newest first, filtered, paged.
    JsonArray all = deliveries(own, "?endpointId=" + endpoint);
    assertThat(all, hasSize(2));
    assertThat(all.getJsonObject(0).getString("id"), is(dead));
    assertThat(deliveries(own, "?endpointId=" + endpoint + "&status=DELIVERED"), hasSize(2));
    assertThat(deliveries(own, "?endpointId=" + endpoint + "&status=DEAD"), hasSize(0));
    Answer page =
        call("GET", "/admin/webhooks/deliveries?endpointId=" + endpoint + "&limit=1", own, null);
    assertThat(page.data().getJsonArray("items"), hasSize(1));
    String cursor = page.data().getString("nextCursor");
    Answer rest =
        call(
            "GET",
            "/admin/webhooks/deliveries?endpointId=" + endpoint + "&limit=1&after=" + cursor,
            own,
            null);
    assertThat(rest.data().getJsonArray("items"), hasSize(1));
    assertThat(rest.data().getJsonArray("items").getJsonObject(0).getString("id"), is(id));
    assertThat(absent(rest.data(), "nextCursor"), is(true));
    assertThat(
        call("GET", "/admin/webhooks/deliveries?status=LOST", own, null).status(),
        anyOf(is(400), is(200)));
  }

  @Test
  void aPingProvesTheWiringAndARotatedSecretSignsFromThenOn() {
    UUID tenant = Ids.newId();
    Caller own = owner(tenant);
    Answer made = register(own, BASE + "/hook", "OrderPlaced");
    assertThat(made.status(), is(201));
    String endpoint = made.data().getString("id");
    String first = made.data().getString("secret");

    Answer ping =
        call(
            "POST",
            "/admin/webhooks/endpoints/" + endpoint + "/ping",
            new Caller(tenant, "MANAGER"),
            null);
    assertThat(ping.body().toString(), ping.status(), is(202));
    String pingId = ping.data().getString("deliveryId");
    assertThat(deliverer.tick(), is(1));
    assertThat(RECEIVED, hasSize(1));
    assertSigned(RECEIVED.get(0), first, "Ping");
    assertThat(RECEIVED.get(0).headers().get("webhook-id"), is(pingId));
    assertThat(delivery(own, pingId).getString("status"), is("DELIVERED"));

    Answer rotated = call("POST", "/admin/webhooks/endpoints/" + endpoint + "/secret", own, null);
    assertThat(rotated.body().toString(), rotated.status(), is(200));
    String second = rotated.data().getString("secret");
    assertThat(second, startsWith("whsec_"));
    assertThat(second, not(is(first)));
    assertThat(
        call(
                "POST",
                "/admin/webhooks/endpoints/" + endpoint + "/secret",
                new Caller(tenant, "MANAGER"),
                null)
            .status(),
        is(403));

    assertThat(
        call("POST", "/admin/webhooks/endpoints/" + endpoint + "/ping", own, null).status(),
        is(202));
    assertThat(deliverer.tick(), is(1));
    Received r = RECEIVED.get(1);
    assertThat("signed with the new secret", verifies(r, second), is(true));
    assertThat("and no longer with the old", verifies(r, first), is(false));
    assertThat(
        call("POST", "/admin/webhooks/endpoints/" + Ids.newId() + "/ping", own, null).status(),
        is(404));
  }
}
