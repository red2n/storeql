package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One business never sees another's traffic or failures. The other business's staff of every role
 * and a shopper ask for ours — naming our tenant, our store and our cursor in every place a request
 * can carry them — and are given nothing of it.
 */
@HelidonTest
class SystemHealthIsolationIT {

  static {
    GatewayHarness.start();
  }

  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  @Inject WebTarget target;
  @Inject FlowRecorder recorder;

  private final RedisCommands<String, String> redis = TestRedis.commands();

  // One business per test: Helidon keeps one test instance per class, and the tests share a Redis.
  private String ours;
  private String theirs;
  private String ourStore;

  @BeforeEach
  void freshBusinesses() {
    ours = Ids.newId().toString();
    theirs = Ids.newId().toString();
    ourStore = Ids.newId().toString();
  }

  /** Our traffic: successes, a client error and failures, in two groups, from two callers. */
  private void ourTraffic() {
    String token = GatewayHarness.token(ours, "OWNER");
    String other = GatewayHarness.token(ours, "MANAGER");
    for (int i = 0; i < 4; i++) {
      GwCalls.get(target, "/api/v1/order-svc/ok/" + Ids.newId(), token).close();
    }
    GwCalls.get(target, "/api/v1/payment-svc/missing/" + Ids.newId(), token).close();
    GwCalls.get(target, "/api/v1/payment-svc/boom/" + Ids.newId(), other).close();
    GwCalls.get(target, "/api/v1/order-svc/denied/" + Ids.newId(), other).close();
    // A guest browsing our storefront is our traffic too.
    GwCalls.get(
            target,
            "/api/v1/product-svc/catalog/products",
            null,
            Map.of("X-Storefront-Tenant", ours))
        .close();
    recorder.flush();
  }

  private JsonObject read(String token, String endpoint, String query, Map<String, String> naming) {
    recorder.flush();
    Response r = GwCalls.get(target, "/api/v1/system-health/" + endpoint + query, token, naming);
    assertEquals(200, r.getStatus());
    return GwCalls.json(r).getJsonObject("data");
  }

  /** Every way a request can name our business, so that none is believed. */
  private Map<String, String> namingOurs() {
    return Map.of(
        "X-Tenant-Id", ours,
        "X-Storefront-Tenant", ours,
        "X-Store-Ids", ourStore,
        "X-User-Id", Ids.newId().toString(),
        "X-Roles", "OWNER");
  }

  private String namingOursInQuery() {
    return "?tenantId=" + ours + "&tenant=" + ours + "&storeId=" + ourStore;
  }

  @Test
  @DisplayName("Our own owner sees our traffic: every request counted, the failures listed")
  void weSeeOurOwn() {
    ourTraffic();
    JsonObject s = read(GatewayHarness.token(ours, "OWNER"), "summary", "", Map.of());
    JsonObject hour = s.getJsonObject("windows").getJsonObject("lastHour");
    assertEquals(8, hour.getInt("total"), "4 + 1 + 1 + 1 + the guest");
    assertEquals(2, hour.getInt("failed"));
    assertEquals(
        2,
        read(GatewayHarness.token(ours, "OWNER"), "failures", "", Map.of())
            .getJsonArray("items")
            .size(),
        "the 500 and the 403, and nothing else");
  }

  @Test
  @DisplayName(
      "Another business's owner and manager, naming our ids everywhere, see none of our traffic")
  void theirManagementSeesNothingOfOurs() {
    ourTraffic();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      String token = GatewayHarness.token(theirs, role);
      JsonObject s = read(token, "summary", namingOursInQuery(), namingOurs());
      assertTrue(s.getBoolean("available"));
      assertEquals(
          0,
          s.getJsonObject("windows").getJsonObject("last24Hours").getInt("failed"),
          role + " saw our failures in their counts");
      for (var g : s.getJsonArray("byGroup").getValuesAs(JsonObject.class)) {
        assertEquals(
            "system-health",
            g.getString("group"),
            role + " was shown our group " + g.getString("group"));
      }
      JsonObject failures = read(token, "failures", namingOursInQuery(), namingOurs());
      assertTrue(
          failures.getJsonArray("items").isEmpty(),
          role + " was shown " + failures.getJsonArray("items"));
    }
  }

  @Test
  @DisplayName(
      "Another business's storekeeper, cashier and a shopper are refused, whatever they name")
  void theirOtherStaffAndShoppersAreRefused() {
    ourTraffic();
    // The staff below a manager are refused by the permission; a shopper never gets as far: the
    // shared tier gate turns away anyone without a staff role.
    Map<String, String> tokens = new java.util.LinkedHashMap<>();
    tokens.put(GatewayHarness.token(theirs, "STOREKEEPER"), "SYSTEM_HEALTH_NOT_PERMITTED");
    tokens.put(GatewayHarness.token(theirs, "CASHIER"), "SYSTEM_HEALTH_NOT_PERMITTED");
    tokens.put(GatewayHarness.token(theirs, "CUSTOMER"), "FORBIDDEN");
    // a shopper's token carries no business at all
    tokens.put(
        GatewayHarness.token(null, Ids.newId().toString(), List.of("CUSTOMER"), null, null),
        "FORBIDDEN");
    for (Map.Entry<String, String> caller : tokens.entrySet()) {
      for (String endpoint : new String[] {"summary", "failures"}) {
        try (Response r =
            GwCalls.get(
                target,
                "/api/v1/system-health/" + endpoint + namingOursInQuery(),
                caller.getKey(),
                namingOurs())) {
          assertEquals(403, r.getStatus(), endpoint);
          assertEquals(caller.getValue(), GwCalls.codeOf(GwCalls.json(r)), endpoint);
        }
      }
    }
  }

  @Test
  @DisplayName("Our cursor, handed to another business, opens none of our failures")
  void aCursorIsNotAKey() {
    ourTraffic();
    JsonObject first = read(GatewayHarness.token(ours, "OWNER"), "failures", "?limit=1", Map.of());
    String cursor = first.getString("nextCursor", null);
    assertTrue(cursor != null && !cursor.isBlank(), "two failures, a page of one: there is more");

    JsonObject theirPage =
        read(GatewayHarness.token(theirs, "OWNER"), "failures", "?after=" + cursor, namingOurs());
    assertTrue(theirPage.getJsonArray("items").isEmpty());
  }

  @Test
  @DisplayName("A platform administrator has no business to be shown, and is not shown ours")
  void aPlatformAdminIsNotAnyBusiness() {
    ourTraffic();
    String token =
        GatewayHarness.token(null, Ids.newId().toString(), List.of("PLATFORM_ADMIN"), null, null);
    try (Response r =
        GwCalls.get(
            target, "/api/v1/system-health/summary" + namingOursInQuery(), token, namingOurs())) {
      assertEquals(401, r.getStatus());
      assertEquals("NO_TENANT", GwCalls.codeOf(GwCalls.json(r)));
    }
  }

  @Test
  @DisplayName(
      "Traffic that belongs to no business is counted for the operator and never given to a business")
  void unattributedTrafficIsNobodysToRead() {
    String ourToken = GatewayHarness.token(ours, "OWNER");
    String theirToken = GatewayHarness.token(theirs, "OWNER");
    recorder.flush();
    long before = unattributedTotal();
    for (int i = 0; i < 6; i++) {
      GwCalls.get(target, "/api/v1/order-svc/ok/" + i, null, namingOurs()).close(); // 401
    }
    GwCalls.get(target, "/api/v1/order-svc/ok/1", "forged.token.value", namingOurs()).close();
    recorder.flush();
    assertEquals(before + 7, unattributedTotal(), "the operator's bucket holds them");

    for (String token : new String[] {ourToken, theirToken}) {
      JsonObject s = read(token, "summary", "", Map.of());
      assertEquals(0, s.getJsonObject("windows").getJsonObject("last24Hours").getInt("failed"));
      for (var g : s.getJsonArray("byGroup").getValuesAs(JsonObject.class)) {
        assertEquals("system-health", g.getString("group"));
      }
      assertTrue(read(token, "failures", "", Map.of()).getJsonArray("items").isEmpty());
    }
    assertEquals(0, redis.exists("flow:f:-"), "no failure list exists for nobody");
  }

  private long unattributedTotal() {
    long total = 0;
    for (String key : redis.keys("flow:m:-:*")) {
      for (String v : redis.hgetall(key).values()) total += Long.parseLong(v);
    }
    return total;
  }

  @Test
  @DisplayName(
      "A caller cannot be made ours by a header: the business is the one in the verified token")
  void headersDoNotChooseTheBusiness() {
    ourTraffic();
    // Their owner's token, our tenant in every header: still theirs.
    JsonObject s =
        read(GatewayHarness.token(theirs, "OWNER"), "summary", "", Map.of("X-Tenant-Id", ours));
    assertFalse(
        s.getJsonArray("byGroup").getValuesAs(JsonObject.class).stream()
            .anyMatch(g -> g.getString("group").equals("order-svc")));
  }
}
