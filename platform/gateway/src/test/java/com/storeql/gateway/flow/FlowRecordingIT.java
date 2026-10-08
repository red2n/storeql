package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole path, through the running gateway: a request in, an answer out with its id, a record on
 * the queue, counters and a failure in a real Redis, and the figures read back over HTTP.
 */
@HelidonTest
class FlowRecordingIT {

  static {
    GatewayHarness.start();
  }

  @Inject WebTarget target;
  @Inject FlowRecorder recorder;
  @Inject FlowStore store;

  private final RedisCommands<String, String> redis = TestRedis.commands();

  private static String owner(String tenant) {
    return GatewayHarness.token(tenant, "OWNER");
  }

  private JsonObject summary(String token) {
    recorder.flush();
    Response r = GwCalls.get(target, "/api/v1/system-health/summary", token);
    assertEquals(200, r.getStatus());
    return GwCalls.json(r).getJsonObject("data");
  }

  private JsonObject failures(String token, String query) {
    recorder.flush();
    Response r = GwCalls.get(target, "/api/v1/system-health/failures" + query, token);
    assertEquals(200, r.getStatus());
    return GwCalls.json(r).getJsonObject("data");
  }

  private static void assertIsAGatewayId(String id) {
    assertTrue(id != null && Ids.isV7(Ids.parse(id)), "a UUIDv7 minted by the gateway: " + id);
  }

  private static void await(String what, Duration within, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + within.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("gave up waiting for " + what);
      Thread.sleep(100);
    }
  }

  @Test
  @DisplayName("The screen's own reads are not counted: they are the watching, not the traffic")
  void theScreensOwnReadsAreNotCounted() {
    String tenant = Ids.newId().toString();
    String token = owner(tenant);
    for (int i = 0; i < 4; i++) summary(token);
    failures(token, "");

    JsonObject s = summary(token);

    assertEquals(0, s.getJsonObject("windows").getJsonObject("last24Hours").getInt("total"));
    assertTrue(s.getJsonArray("byGroup").isEmpty());
  }

  @Test
  @DisplayName(
      "The waiting-work read the screen polls is not counted either, on both forms; another reporting-svc route is")
  void theWaitingWorkReadIsNotCounted() {
    String tenant = Ids.newId().toString();
    String token = owner(tenant);
    String waiting = "/reporting-svc" + GatewayHarness.WAITING_WORK;
    List<String> polled = new ArrayList<>();
    for (String path :
        new String[] {
          "/api/v1" + waiting,
          "/api" + waiting,
          "/api/v1" + waiting + "/",
          "/api/v1" + waiting + "?refresh=1",
          "/api" + waiting + "?refresh=1"
        }) {
      for (int i = 0; i < 3; i++) {
        try (Response r = GwCalls.get(target, path, token)) {
          assertEquals(200, r.getStatus(), path);
          polled.add(GwCalls.requestId(r));
        }
      }
    }
    for (String path :
        new String[] {
          "/api/v1/reporting-svc/admin/reports/dashboard",
          "/api/reporting-svc/admin/reports/system-health"
        }) {
      try (Response r = GwCalls.get(target, path, token)) {
        assertEquals(200, r.getStatus(), path);
      }
    }

    JsonObject s = summary(token);

    JsonArray groups = s.getJsonArray("byGroup");
    assertEquals(1, groups.size(), "only the other reports: " + groups);
    assertEquals("reporting-svc", groups.getJsonObject(0).getString("group"));
    assertEquals(2, groups.getJsonObject(0).getInt("total"), "the two that are not the screen's");
    assertEquals(0, groups.getJsonObject(0).getInt("failed"));
    assertEquals(2, s.getJsonObject("windows").getJsonObject("last5Minutes").getInt("total"));
    assertEquals(2, s.getJsonObject("windows").getJsonObject("last24Hours").getInt("total"));
    long reachedTheService;
    synchronized (GatewayHarness.CALLS) {
      reachedTheService =
          GatewayHarness.CALLS.stream()
              .filter(c -> polled.contains(c.headers().get("X-Request-Id")))
              .count();
    }
    assertEquals(polled.size(), reachedTheService, "every poll was answered by the service");
  }

  @Test
  @DisplayName(
      "A waiting-work poll the service refuses is never listed as a failure; the same refusal on another route is")
  void aRefusedWaitingWorkPollIsNotListed() {
    String tenant = Ids.newId().toString();
    String token = owner(tenant);
    GatewayHarness.WAITING_WORK_STATUS.put(tenant, 403);
    for (String base : new String[] {"/api/v1/reporting-svc", "/api/reporting-svc"}) {
      for (int i = 0; i < 3; i++) {
        try (Response r = GwCalls.get(target, base + GatewayHarness.WAITING_WORK, token)) {
          assertEquals(403, r.getStatus());
        }
      }
    }
    try (Response r = GwCalls.get(target, "/api/v1/reporting-svc/boom/" + Ids.newId(), token)) {
      assertEquals(500, r.getStatus());
    }

    List<JsonObject> items =
        failures(token, "").getJsonArray("items").getValuesAs(JsonObject.class);
    JsonObject s = summary(token);

    assertEquals(1, items.size(), "the 500 only: " + items);
    assertEquals("/api/v1/reporting-svc/boom/{id}", items.get(0).getString("routePattern"));
    JsonObject five = s.getJsonObject("windows").getJsonObject("last5Minutes");
    assertEquals(1, five.getInt("total"), "and only the 500 is counted");
    assertEquals(1, five.getInt("failed"));
  }

  // ── counted, grouped, with an id ──────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Every request is counted by route group and outcome, and every answer carries an id")
  void everyRequestIsCounted() {
    String tenant = Ids.newId().toString();
    String token = owner(tenant);
    String id = Ids.newId().toString();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      try (Response r = GwCalls.get(target, "/api/v1/order-svc/ok/" + id, token)) {
        assertEquals(200, r.getStatus());
        ids.add(GwCalls.requestId(r));
      }
    }
    for (String path : new String[] {"missing", "conflict", "boom", "denied"}) {
      try (Response r = GwCalls.get(target, "/api/v1/order-svc/" + path + "/" + id, token)) {
        ids.add(GwCalls.requestId(r));
      }
    }
    ids.forEach(FlowRecordingIT::assertIsAGatewayId);
    assertEquals(7, ids.stream().distinct().count(), "a request each");

    JsonObject s = summary(token);

    assertTrue(s.getBoolean("available"));
    assertEquals(
        Set.of(
            "generatedAt",
            "available",
            "droppedSinceStart",
            "windows",
            "byGroup",
            "perMinute",
            "perHour"),
        s.keySet(),
        "the summary's members are the contract the screen is built to");
    assertEquals(
        Set.of("last5Minutes", "lastHour", "last24Hours"), s.getJsonObject("windows").keySet());
    assertEquals(
        Set.of("total", "succeeded", "failed", "clientErrors", "failureRate"),
        s.getJsonObject("windows").getJsonObject("lastHour").keySet());
    assertEquals(
        Set.of("group", "total", "failed"), s.getJsonArray("byGroup").getJsonObject(0).keySet());
    assertEquals(
        Set.of("at", "total", "failed"), s.getJsonArray("perMinute").getJsonObject(0).keySet());
    assertEquals(
        Set.of("at", "total", "failed"), s.getJsonArray("perHour").getJsonObject(0).keySet());
    JsonObject five = s.getJsonObject("windows").getJsonObject("last5Minutes");
    assertEquals(7, five.getInt("total"));
    assertEquals(3, five.getInt("succeeded"));
    assertEquals(2, five.getInt("clientErrors"), "the 404 and the 409 are answers, not failures");
    assertEquals(2, five.getInt("failed"), "the 500 and the 403");
    assertEquals(2.0 / 7.0, five.getJsonNumber("failureRate").doubleValue(), 1e-9);
    assertEquals(7, s.getJsonObject("windows").getJsonObject("lastHour").getInt("total"));
    assertEquals(7, s.getJsonObject("windows").getJsonObject("last24Hours").getInt("total"));
    JsonArray groups = s.getJsonArray("byGroup");
    assertEquals(1, groups.size());
    assertEquals("order-svc", groups.getJsonObject(0).getString("group"));
    assertEquals(7, groups.getJsonObject(0).getInt("total"));
    assertEquals(2, groups.getJsonObject(0).getInt("failed"));
    assertEquals(60, s.getJsonArray("perMinute").size());
    assertEquals(24, s.getJsonArray("perHour").size());
    assertEquals(
        7,
        s.getJsonArray("perMinute").getValuesAs(JsonObject.class).stream()
            .mapToInt(p -> p.getInt("total"))
            .sum());
  }

  @Test
  @DisplayName(
      "Failures are listed with when, request id, route pattern, status, code and who; successes and client errors are not")
  void failuresAreListed() {
    String tenant = Ids.newId().toString();
    String user = Ids.newId().toString();
    String token = GatewayHarness.token(tenant, user, List.of("OWNER"), null, null);
    String rid;
    String denied;
    try (Response r = GwCalls.get(target, "/api/v1/order-svc/boom/" + Ids.newId(), token)) {
      assertEquals(500, r.getStatus());
      rid = GwCalls.requestId(r);
    }
    try (Response r = GwCalls.get(target, "/api/v1/order-svc/denied/" + Ids.newId(), token)) {
      assertEquals("PERMISSION_DENIED", r.getHeaderString("X-Error-Code"));
      denied = GwCalls.requestId(r);
    }
    GwCalls.get(target, "/api/v1/order-svc/ok/" + Ids.newId(), token).close();
    GwCalls.get(target, "/api/v1/order-svc/missing/" + Ids.newId(), token).close();

    JsonObject page = failures(token, "");

    assertTrue(page.getBoolean("available"));
    assertEquals(Set.of("items", "nextCursor", "available"), page.keySet());
    assertTrue(page.isNull("nextCursor"));
    List<JsonObject> items = page.getJsonArray("items").getValuesAs(JsonObject.class);
    assertEquals(2, items.size());
    JsonObject newest = items.get(0);
    assertEquals(denied, newest.getString("requestId"), "newest first");
    assertEquals(403, newest.getInt("status"));
    assertEquals(
        "PERMISSION_DENIED", newest.getString("code"), "a relayed answer's code from its header");
    assertEquals("GET", newest.getString("method"));
    assertEquals("/api/v1/order-svc/denied/{id}", newest.getString("routePattern"));
    assertEquals("order-svc", newest.getString("group"));
    assertEquals(user, newest.getString("userId"));
    assertTrue(newest.getInt("ms") >= 0);
    assertTrue(Instant.parse(newest.getString("at")).isAfter(Instant.now().minusSeconds(60)));
    JsonObject older = items.get(1);
    assertEquals(rid, older.getString("requestId"));
    assertEquals(500, older.getInt("status"));
    assertTrue(older.isNull("code"), "a relayed body is never read, and this one named no header");
  }

  // ── refused requests have ids too ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A request refused by the first filter that sees it still answers with the gateway's own id")
  void refusalsCarryAnId() {
    String clientChosen = "client-chosen-id";
    Map<String, String> theirs = Map.of("X-Request-Id", clientChosen);

    // JwtAuthFilter: no token
    try (Response r = GwCalls.get(target, "/api/v1/order-svc/ok/1", null, theirs)) {
      assertEquals(401, r.getStatus());
      String id = GwCalls.requestId(r);
      assertIsAGatewayId(id);
      assertNotEquals(clientChosen, id);
      JsonObject problem = GwCalls.json(r);
      assertEquals(id, problem.getString("requestId", id), "the problem body quotes the same id");
    }
    // JwtAuthFilter: a token it cannot verify
    try (Response r = GwCalls.get(target, "/api/v1/order-svc/ok/1", "not.a.token", theirs)) {
      assertEquals(401, r.getStatus());
      assertIsAGatewayId(GwCalls.requestId(r));
    }
    // ApiVersionFilter: a version nobody published
    try (Response r = GwCalls.get(target, "/api/v9/order-svc/ok/1", null, theirs)) {
      assertEquals(404, r.getStatus());
      assertIsAGatewayId(GwCalls.requestId(r));
    }
    // CorsFilter: a preflight
    try (Response r =
        GwCalls.request(
                target,
                "/api/v1/order-svc/ok/1",
                null,
                Map.of(
                    "X-Request-Id", clientChosen,
                    "Origin", "https://app.example.com",
                    "Access-Control-Request-Method", "GET"))
            .options()) {
      assertEquals(204, r.getStatus(), "answered by CorsFilter, before routing");
      assertIsAGatewayId(GwCalls.requestId(r));
    }
  }

  @Test
  @DisplayName(
      "The IP rate limit, which answers before anyone is identified, answers with an id too")
  void theIpRateLimitCarriesAnId() {
    GwCalls.get(target, "/api/v1/order-svc/ok/1", null).close(); // learns the caller's key
    List<String> keys = redis.keys("ratelimit:*");
    List<String> ipKeys = keys.stream().filter(k -> !k.startsWith("ratelimit:tenant:")).toList();
    assertFalse(ipKeys.isEmpty());
    try {
      for (String k : ipKeys) redis.setex(k, 30, "999999999");
      try (Response r = GwCalls.get(target, "/api/v1/order-svc/ok/1", null)) {
        assertEquals(429, r.getStatus());
        assertIsAGatewayId(GwCalls.requestId(r));
        JsonObject body = GwCalls.json(r);
        assertEquals("RATE_LIMITED", GwCalls.codeOf(body));
      }
    } finally {
      for (String k : ipKeys) redis.del(k);
    }
  }

  @Test
  @DisplayName(
      "A business refused by its plan's rate sees each refusal, with the id the client was given")
  void aPlanRefusalIsListedForTheBusiness() {
    String tenant = Ids.newId().toString();
    GatewayHarness.PLAN_RATE.put(tenant, 2L);
    String token = owner(tenant);
    List<String> refused = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      try (Response r = GwCalls.get(target, "/api/v1/order-svc/ok/" + i, token)) {
        if (r.getStatus() == 429) {
          refused.add(GwCalls.requestId(r));
          assertEquals("PLAN_RATE_LIMIT_REACHED", GwCalls.codeOf(GwCalls.json(r)));
        } else {
          assertEquals(200, r.getStatus());
        }
      }
    }
    assertEquals(3, refused.size());
    recorder.flush();

    FlowStore.FailureSlice list = store.failures(tenant, Instant.now(), 20, null);

    assertEquals(
        refused.reversed(),
        list.items().stream().map(SystemHealthDtos.FailureItem::requestId).toList());
    assertTrue(list.items().stream().allMatch(f -> f.status() == 429));
    assertTrue(list.items().stream().allMatch(f -> "PLAN_RATE_LIMIT_REACHED".equals(f.code())));
  }

  @Test
  @DisplayName("A body over the limit is refused 413 and listed with its code")
  void aTooLargeBodyIsAFailure() {
    String tenant = Ids.newId().toString();
    String token = owner(tenant);
    try (Response r =
        GwCalls.post(target, "/api/v1/order-svc/ok/1", token, "x".repeat(1_100_000))) {
      assertEquals(413, r.getStatus());
      assertIsAGatewayId(GwCalls.requestId(r));
    }
    JsonObject page = failures(token, "");
    JsonObject f = page.getJsonArray("items").getJsonObject(0);
    assertEquals(413, f.getInt("status"));
    assertEquals("PAYLOAD_TOO_LARGE", f.getString("code"));
    assertEquals("POST", f.getString("method"));
  }

  @Test
  @DisplayName(
      "The id on the answer is the id the service was sent: a client's own X-Request-Id is overwritten")
  void theProxySendsTheSameIdUpstream() {
    String tenant = Ids.newId().toString();
    String path = "/ok/" + Ids.newId();
    String id;
    try (Response r =
        GwCalls.get(
            target,
            "/api/v1/order-svc" + path,
            owner(tenant),
            Map.of("X-Request-Id", "client-chosen-id"))) {
      assertEquals(200, r.getStatus());
      id = GwCalls.requestId(r);
    }
    assertIsAGatewayId(id);
    List<GatewayHarness.Call> calls = GatewayHarness.callsTo(path);
    assertEquals(1, calls.size());
    assertEquals(id, calls.get(0).headers().get("X-Request-Id"));
  }

  // ── nothing but the shape is kept ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "recordsNoPayload: no body, query string or raw address reaches Redis, whoever the request was")
  void recordsNoPayload() {
    String tenant = Ids.newId().toString();
    String token = owner(tenant);
    String rawId = Ids.newId().toString();
    String body = "{\"note\":\"BODY_SENTINEL_hunter2\",\"email\":\"body.person@example.org\"}";
    String path = "/api/v1/order-svc/boom/" + rawId + "/ann.lee@example.com";

    try (Response r =
        GwCalls.post(
            target,
            path + "?email=query.person@example.net&token=QUERY_SENTINEL_9f8e",
            token,
            body)) {
      assertEquals(500, r.getStatus());
    }
    try (Response r =
        GwCalls.get(target, "/api/v1/order-svc/ok/" + rawId + "?q=OTHER_QUERY_SENTINEL", token)) {
      assertEquals(200, r.getStatus());
    }
    recorder.flush();

    List<String> keys = redis.keys("flow:*" + tenant + "*");
    assertTrue(keys.size() >= 3, "minute, hour and failure keys: " + keys);
    for (String key : keys) {
      String dump =
          key
              + " "
              + switch (redis.type(key)) {
                case "hash" -> redis.hgetall(key).toString();
                case "zset" -> redis.zrange(key, 0, -1).toString();
                default -> "";
              };
      for (String secret :
          new String[] {
            "BODY_SENTINEL",
            "hunter2",
            "body.person",
            "QUERY_SENTINEL",
            "OTHER_QUERY_SENTINEL",
            "query.person",
            "ann.lee",
            "example.com",
            "example.net",
            "example.org",
            rawId
          }) {
        assertFalse(dump.contains(secret), secret + " found in " + dump);
      }
    }
    JsonObject f = failures(token, "").getJsonArray("items").getJsonObject(0);
    assertEquals("/api/v1/order-svc/boom/{id}/{id}", f.getString("routePattern"));
    assertEquals(
        List.of(
            "at", "code", "group", "method", "ms", "requestId", "routePattern", "status", "userId"),
        f.keySet().stream().sorted().toList());
  }

  // ── Redis away ───────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "redisDown: requests still pass, the screen says the figures are unavailable, and it recovers")
  void redisDown() throws InterruptedException {
    String tenant = Ids.newId().toString();
    String token = owner(tenant);
    String id = Ids.newId().toString();
    GwCalls.get(target, "/api/v1/order-svc/ok/" + id, token).close();
    assertEquals(
        1, summary(token).getJsonObject("windows").getJsonObject("lastHour").getInt("total"));

    GatewayHarness.REDIS.stop();
    try {
      await(
          "the gateway to notice Redis is gone",
          Duration.ofSeconds(20),
          () -> {
            try (Response r = GwCalls.get(target, "/api/v1/system-health/summary", token)) {
              return !GwCalls.json(r).getJsonObject("data").getBoolean("available");
            }
          });

      long droppedBefore = recorder.droppedSinceStart();
      for (int i = 0; i < 5; i++) {
        long started = System.nanoTime();
        try (Response r = GwCalls.get(target, "/api/v1/order-svc/ok/" + id, token)) {
          assertEquals(200, r.getStatus(), "the request is not Redis's to refuse");
          assertIsAGatewayId(GwCalls.requestId(r));
        }
        assertTrue(
            Duration.ofNanos(System.nanoTime() - started).toSeconds() < 3, "and not slowed by it");
      }
      recorder.flush(); // does not throw, and lets the records go
      assertTrue(
          recorder.droppedSinceStart() > droppedBefore, "the records were counted as dropped");

      try (Response r = GwCalls.get(target, "/api/v1/system-health/summary", token)) {
        assertEquals(200, r.getStatus(), "unavailable is an answer, not an error");
        JsonObject s = GwCalls.json(r).getJsonObject("data");
        assertFalse(s.getBoolean("available"));
        assertEquals(0, s.getJsonObject("windows").getJsonObject("lastHour").getInt("total"));
        assertTrue(s.getJsonObject("windows").getJsonObject("lastHour").isNull("failureRate"));
        assertTrue(s.getJsonArray("byGroup").isEmpty());
        assertTrue(s.getJsonArray("perMinute").isEmpty());
        assertTrue(s.getJsonArray("perHour").isEmpty());
        assertTrue(s.getJsonNumber("droppedSinceStart").longValue() > 0);
      }
      try (Response r = GwCalls.get(target, "/api/v1/system-health/failures", token)) {
        assertEquals(200, r.getStatus());
        JsonObject page = GwCalls.json(r).getJsonObject("data");
        assertFalse(page.getBoolean("available"), "an empty list must not read as 'no failures'");
        assertTrue(page.getJsonArray("items").isEmpty());
      }
    } finally {
      GatewayHarness.REDIS.start();
    }

    await(
        "recording to resume once Redis is back",
        Duration.ofSeconds(90),
        () -> {
          GwCalls.get(target, "/api/v1/order-svc/ok/" + id, token).close();
          recorder.flush();
          try (Response r = GwCalls.get(target, "/api/v1/system-health/summary", token)) {
            JsonObject s = GwCalls.json(r).getJsonObject("data");
            return s.getBoolean("available")
                && s.getJsonObject("windows").getJsonObject("lastHour").getInt("total") >= 3;
          }
        });
    assertTrue(summary(token).getBoolean("available"));
  }
}
