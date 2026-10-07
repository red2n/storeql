package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may read the screen's figures: the permission first, then business-wide — the order in which
 * a refused person learns the least. The rule is common-web's {@code SystemHealthAccess}; this
 * proves it holds at the gateway's door with real tokens, on both routes.
 */
@HelidonTest
class SystemHealthAccessIT {

  static {
    GatewayHarness.start();
  }

  private static final String[] ROUTES = {"summary", "failures"};

  @Inject WebTarget target;

  private final String tenant = Ids.newId().toString();
  private final String store = Ids.newId().toString();

  private String token(List<String> roles, List<String> stores, List<String> perms) {
    return GatewayHarness.token(tenant, Ids.newId().toString(), roles, stores, perms);
  }

  private Response call(String route, String token) {
    return GwCalls.get(target, "/api/v1/system-health/" + route, token);
  }

  private void assertAllowed(String token, String who) {
    for (String route : ROUTES) {
      try (Response r = call(route, token)) {
        assertEquals(200, r.getStatus(), who + " on " + route);
      }
    }
  }

  private void assertRefused(String token, String code, String who) {
    for (String route : ROUTES) {
      try (Response r = call(route, token)) {
        assertEquals(403, r.getStatus(), who + " on " + route);
        assertEquals(code, GwCalls.codeOf(GwCalls.json(r)), who + " on " + route);
      }
    }
  }

  @Test
  @DisplayName(
      "An owner, a manager and a custom manager-tier role holding the permission are let in")
  void permittedCallers() {
    assertAllowed(token(List.of("OWNER"), null, null), "owner");
    assertAllowed(token(List.of("OWNER"), null, List.of()), "owner, claim narrowed to nothing");
    assertAllowed(token(List.of("MANAGER"), null, null), "manager with no permission claim");
    assertAllowed(
        token(List.of("MANAGER"), null, List.of("system.health")), "the IT person's custom role");
    assertAllowed(
        token(List.of("MANAGER"), null, List.of("stock.adjust", "system.health")),
        "a wider custom role");
    assertAllowed(token(List.of("PLATFORM_ADMIN"), null, null), "platform admin");
  }

  @Test
  @DisplayName(
      "SYSTEM_HEALTH_NOT_PERMITTED: a role narrowed away from the permission, and staff below a"
          + " manager")
  void withoutThePermission() {
    assertRefused(
        token(List.of("MANAGER"), null, List.of()),
        "SYSTEM_HEALTH_NOT_PERMITTED",
        "manager, claim naming nothing");
    assertRefused(
        token(List.of("MANAGER"), null, List.of("stock.adjust")),
        "SYSTEM_HEALTH_NOT_PERMITTED",
        "manager, custom role without it");
    assertRefused(
        token(List.of("STOREKEEPER"), null, null), "SYSTEM_HEALTH_NOT_PERMITTED", "storekeeper");
    assertRefused(token(List.of("CASHIER"), null, null), "SYSTEM_HEALTH_NOT_PERMITTED", "cashier");
  }

  @Test
  @DisplayName(
      "A shopper, or a token with no staff role, is refused 403 FORBIDDEN by the shared tier gate"
          + " before this route is reached")
  void notStaff() {
    assertRefused(token(List.of("CUSTOMER"), null, null), "FORBIDDEN", "shopper");
    assertRefused(token(List.of(), null, null), "FORBIDDEN", "a token with no role");
    assertRefused(token(null, null, null), "FORBIDDEN", "a token with no roles claim");
    assertRefused(
        token(List.of("SOMETHING_ELSE"), null, null), "FORBIDDEN", "a role nobody defined");
    assertRefused(
        token(List.of("CUSTOMER"), null, List.of("system.health")),
        "FORBIDDEN",
        "a shopper whose token claims the permission");
  }

  @Test
  @DisplayName("BUSINESS_WIDE_ONLY: a caller held to stores, who would otherwise be let in")
  void heldToStores() {
    assertRefused(
        token(List.of("MANAGER"), List.of(store), null),
        "BUSINESS_WIDE_ONLY",
        "store-held manager");
    assertRefused(
        token(List.of("MANAGER"), List.of(store), List.of("system.health")),
        "BUSINESS_WIDE_ONLY",
        "store-held custom role");
    assertRefused(
        token(List.of("OWNER"), List.of(store), null), "BUSINESS_WIDE_ONLY", "store-held owner");
  }

  @Test
  @DisplayName(
      "The permission is judged first: a store-held cashier is told about the permission, not the stores")
  void permissionBeforeBusinessWide() {
    assertRefused(
        token(List.of("CASHIER"), List.of(store), null),
        "SYSTEM_HEALTH_NOT_PERMITTED",
        "store-held cashier");
    assertRefused(
        token(List.of("MANAGER"), List.of(store), List.of()),
        "SYSTEM_HEALTH_NOT_PERMITTED",
        "store-held manager with the permission taken away");
  }

  @Test
  @DisplayName(
      "No token is 401, and a token naming no business is 401 NO_TENANT, not another business's figures")
  void notSignedIn() {
    for (String route : ROUTES) {
      try (Response r = call(route, null)) {
        assertEquals(401, r.getStatus());
      }
      try (Response r =
          call(
              route,
              GatewayHarness.token(null, Ids.newId().toString(), List.of("OWNER"), null, null))) {
        assertEquals(401, r.getStatus());
        assertEquals("NO_TENANT", GwCalls.codeOf(GwCalls.json(r)));
      }
    }
  }

  @Test
  @DisplayName("A refusal says no more than its code, answers with an id, and is never cached")
  void refusalShape() {
    try (Response r = call("summary", token(List.of("CASHIER"), null, null))) {
      assertEquals(403, r.getStatus());
      assertTrue(r.getHeaderString("Cache-Control").contains("no-store"));
      assertTrue(Ids.isV7(Ids.parse(GwCalls.requestId(r))));
      JsonObject body = GwCalls.json(r);
      assertEquals("SYSTEM_HEALTH_NOT_PERMITTED", GwCalls.codeOf(body));
      assertTrue(body.toString().contains("system.health"));
    }
  }

  @Test
  @DisplayName("The failures list takes a limit and a cursor and refuses ones that are not")
  void pagingParameters() {
    String owner = token(List.of("OWNER"), null, null);
    try (Response r = GwCalls.get(target, "/api/v1/system-health/failures?limit=abc", owner)) {
      assertEquals(400, r.getStatus());
      assertEquals("INVALID_LIMIT", GwCalls.codeOf(GwCalls.json(r)));
    }
    try (Response r = GwCalls.get(target, "/api/v1/system-health/failures?after=***", owner)) {
      assertEquals(400, r.getStatus());
      assertEquals("INVALID_CURSOR", GwCalls.codeOf(GwCalls.json(r)));
    }
    String notOurs = com.storeql.web.Cursor.encode("not-a-number");
    try (Response r =
        GwCalls.get(target, "/api/v1/system-health/failures?after=" + notOurs, owner)) {
      assertEquals(400, r.getStatus());
      assertEquals("INVALID_CURSOR", GwCalls.codeOf(GwCalls.json(r)));
    }
    for (String limit : new String[] {"0", "-5", "1", "100", "100000"}) {
      try (Response r =
          GwCalls.get(target, "/api/v1/system-health/failures?limit=" + limit, owner)) {
        assertEquals(200, r.getStatus(), "limit " + limit + " is clamped, not refused");
      }
    }
  }

  @Test
  @DisplayName(
      "Reading only: a write to either route is not served, and not forwarded to any service")
  void readOnly() {
    String owner = token(List.of("OWNER"), null, null);
    for (String route : ROUTES) {
      try (Response r =
          GwCalls.request(target, "/api/v1/system-health/" + route, owner, java.util.Map.of())
              .post(Entity.json("{}"))) {
        assertEquals(405, r.getStatus(), "POST " + route);
      }
      try (Response r =
          GwCalls.request(target, "/api/v1/system-health/" + route, owner, java.util.Map.of())
              .delete()) {
        assertEquals(405, r.getStatus(), "DELETE " + route);
      }
    }
  }

  @Test
  @DisplayName("Only the versioned form is served: the unversioned alias answers no figures")
  void onlyVersionedForm() {
    String owner = token(List.of("OWNER"), null, null);
    try (Response r = GwCalls.get(target, "/api/system-health/summary", owner)) {
      assertNotEquals(200, r.getStatus());
    }
  }
}
