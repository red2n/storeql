package com.storeql.test;

import com.storeql.ids.Ids;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Drives one service's permission gates (20.10) the same way in every service: a manager narrowed
 * to nothing is refused by name; one holding every other permission is refused; one holding the
 * permission is past the gate; one carrying no claim is judged by the tier and admitted; an owner
 * narrowed on paper is not narrowed in fact.
 *
 * <p>Lives here rather than in each service's test tree because the gate's contract is the shared
 * filter's, not the service's: the same five outcomes must hold at every gated route, and one copy
 * of the assertion is how that stays true.
 */
public final class PermissionGate {

  /** The catalogue as the tests know it, so "every other permission" means every other one. */
  private static final java.util.List<String> ALL =
      java.util.List.of(
          "sales.void",
          "sales.refund",
          "till.no_sale",
          "till.manage",
          "stock.adjust",
          "purchasing.approve",
          "purchasing.invoices.decide",
          "finance.journal",
          "pricing.write",
          "customers.privacy",
          "staff.manage",
          "finance.payments");

  private final WebTarget target;
  private final String tenantId;
  private final String userId;

  /**
   * @param target the test's injected target
   * @param tenantId the tenant every request is made in
   * @param userId the caller
   */
  public PermissionGate(WebTarget target, String tenantId, String userId) {
    this.target = target;
    this.tenantId = tenantId;
    this.userId = userId;
  }

  /**
   * Sends one request as a member of staff.
   *
   * @param method GET, POST or DELETE
   * @param pathAndQuery the route, optionally with a query string
   * @param json the body for a POST, ignored otherwise
   * @param roles the {@code X-Roles} header
   * @param permissions the {@code X-Permissions} header, or {@code null} for a token carrying no
   *     claim
   * @return the response
   */
  public Response send(
      String method, String pathAndQuery, String json, String roles, String permissions) {
    var req =
        WebTargets.at(target, pathAndQuery)
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-User-Id", userId)
            .header("X-Roles", roles)
            .header("Idempotency-Key", Ids.newId().toString());
    if (permissions != null) req = req.header("X-Permissions", permissions);
    return switch (method) {
      case "GET" -> req.get();
      case "DELETE" -> req.delete();
      default -> req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  /**
   * Asserts the five outcomes at one gated route.
   *
   * @param method GET, POST or DELETE
   * @param pathAndQuery the route
   * @param json the body for a POST
   * @param permission the permission the route is gated on
   * @throws AssertionError describing which outcome failed
   */
  public void assertGated(String method, String pathAndQuery, String json, String permission) {
    try (Response none = send(method, pathAndQuery, json, "MANAGER", "-")) {
      String body = none.readEntity(String.class);
      check(
          none.getStatus() == 403
              && body.contains("PERMISSION_DENIED")
              && body.contains(permission),
          pathAndQuery
              + ": narrowed to nothing should be 403 PERMISSION_DENIED naming "
              + permission,
          none.getStatus() + " " + body);
    }
    StringBuilder others = new StringBuilder();
    for (String p : ALL) {
      if (!p.equals(permission)) others.append(others.length() == 0 ? "" : ",").append(p);
    }
    try (Response other = send(method, pathAndQuery, json, "MANAGER", others.toString())) {
      check(
          other.getStatus() == 403,
          pathAndQuery + ": holding every other permission should still be 403",
          other.getStatus() + " " + other.readEntity(String.class));
    }
    notDenied(method, pathAndQuery, json, "MANAGER", permission, "holding it");
    notDenied(method, pathAndQuery, json, "MANAGER", null, "no claim");
    notDenied(method, pathAndQuery, json, "OWNER", "-", "an owner");
  }

  /**
   * Asserts that a tier narrowed to nothing is refused at a route its plain form is admitted to.
   *
   * @param method GET, POST or DELETE
   * @param pathAndQuery the route
   * @param json the body for a POST
   * @param tier STOREKEEPER or CASHIER
   */
  public void assertTierNarrows(String method, String pathAndQuery, String json, String tier) {
    try (Response narrowed = send(method, pathAndQuery, json, tier, "-")) {
      String body = narrowed.readEntity(String.class);
      check(
          narrowed.getStatus() == 403 && body.contains("PERMISSION_DENIED"),
          pathAndQuery + ": a " + tier + " narrowed to nothing should be 403 PERMISSION_DENIED",
          narrowed.getStatus() + " " + body);
    }
    notDenied(method, pathAndQuery, json, tier, null, "a plain " + tier);
  }

  /**
   * A tier whose defaults never included the permission is refused on its defaults alone: the route
   * asks, and the tier has no answer.
   */
  public void assertTierRefused(String method, String pathAndQuery, String json, String tier) {
    try (Response r = send(method, pathAndQuery, json, tier, null)) {
      String body = r.readEntity(String.class);
      check(
          r.getStatus() == 403 && body.contains("PERMISSION_DENIED"),
          pathAndQuery + ": a plain " + tier + " should be 403 PERMISSION_DENIED",
          r.getStatus() + " " + body);
    }
  }

  private void notDenied(
      String method, String path, String json, String roles, String permissions, String who) {
    try (Response r = send(method, path, json, roles, permissions)) {
      String body = r.readEntity(String.class);
      check(
          !body.contains("PERMISSION_DENIED"),
          path + ": " + who + " should be past the gate, whatever the route says next",
          r.getStatus() + " " + body);
    }
  }

  private static void check(boolean ok, String what, String got) {
    if (!ok) throw new AssertionError(what + " — got " + got);
  }
}
