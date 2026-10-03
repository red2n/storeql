package com.storeql.tenant;

import static com.storeql.tenant.AdminRig.call;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.tenant.AdminRig.Answer;
import com.storeql.tenant.AdminRig.Biz;
import com.storeql.tenant.AdminRig.Who;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform operator's surface in tenant-svc, which reaches across every business — the list of
 * them, one of them, the lookup by e-invoicing address, the currency replay, the move between
 * plans, the suspension and the security incident — and who is turned away from it.
 *
 * <p>Every role of the business a request names and of another business, a shopper and nobody at
 * all are each refused with the one answer, for every route; what the routes would have moved (a
 * status, a plan, an announcement, an incident) is read back unmoved; and the platform
 * administrator's own call to the same routes is set beside the refusals, so that unmoved is not
 * empty.
 */
@HelidonTest
class PlatformRoutesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static Who admin() {
    return new Who(null, Ids.newId().toString(), "PLATFORM_ADMIN", null, null);
  }

  private record Route(String method, String path, String body) {}

  private static int count(String sql) {
    return Integer.parseInt(scalar(PG, sql));
  }

  /** Every way of not being the platform administrator: each role of two businesses, and nobody. */
  private static List<Who> everyoneElse(Biz ours, Biz theirs) {
    List<Who> out = new ArrayList<>();
    for (Biz b : List.of(ours, theirs)) {
      for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
        out.add(b.as(role));
      }
    }
    // A manager held to one store, naming the other business's store as well.
    out.add(ours.manager(ours.store(), theirs.store()));
    out.add(new Who(null, null, null, null, null));
    return out;
  }

  private static String incident(String tenant) {
    return "{\"kind\":\"SEVERE_INCIDENT\",\"title\":\"Checkout token leak\","
        + "\"summary\":\"Session tokens logged by a proxy\",\"awareAt\":\""
        + Instant.now().minus(Duration.ofHours(1))
        + "\",\"tenantIds\":[\""
        + tenant
        + "\"]}";
  }

  @Test
  @DisplayName(
      "Nobody but the platform administrator reaches the platform's routes, and a refusal moves nothing")
  void onlyThePlatformAdministratorReachesThePlatformRoutes() {
    Biz ours = AdminRig.biz(target, "Surface Ours", "GB", "GBP");
    Biz theirs = AdminRig.biz(target, "Surface Theirs", "GB", "GBP");
    String t = ours.tenant();
    String plan = Ids.newId().toString();

    List<Route> routes =
        List.of(
            new Route("GET", "/platform/tenants?limit=100", null),
            new Route("GET", "/platform/tenants/" + t, null),
            new Route("GET", "/platform/tenants/by-einvoice-address?vatNumber=GB123456789", null),
            new Route("POST", "/platform/tenants/republish-currency", null),
            new Route("POST", "/platform/tenants/republish-currency?tenantId=" + t, null),
            new Route("PUT", "/platform/tenants/" + t + "/plan", "{\"planId\":\"" + plan + "\"}"),
            new Route(
                "PATCH",
                "/platform/tenants/" + t + "/status",
                "{\"status\":\"INACTIVE\",\"reason\":\"take it down\"}"),
            new Route("POST", "/platform/security-incidents", incident(t)));

    String announced =
        "SELECT count(*) FROM tenant.outbox WHERE tenant_id = '"
            + t
            + "'::uuid AND event_type IN ('TenantStatusChanged', 'TenantCurrencyDeclared')";
    String planMoves =
        "SELECT count(*) FROM tenant.tenant_plan_changes WHERE tenant_id = '" + t + "'::uuid";
    String planOf =
        "SELECT coalesce(plan_id::text, '-') FROM tenant.tenants WHERE id = '" + t + "'::uuid";
    String incidents = "SELECT count(*) FROM tenant.security_incidents";
    String statusOf = "SELECT status FROM tenant.tenants WHERE id = '" + t + "'::uuid";
    int announcedBefore = count(announced);
    int movesBefore = count(planMoves);
    String planBefore = scalar(PG, planOf);
    int incidentsBefore = count(incidents);

    for (Route route : routes) {
      for (Who who : everyoneElse(ours, theirs)) {
        Answer refused = call(target, route.method(), route.path(), route.body(), who);
        String label =
            route.method() + " " + route.path() + " as " + who.roles() + " of " + who.tenant();
        assertThat(label + " -> " + refused.text(), refused.status(), is(403));
        assertThat(label, refused.code(), is("FORBIDDEN"));
        assertThat(label + " answers no data", refused.json().containsKey("data"), is(false));
      }
    }
    assertThat("still trading", scalar(PG, statusOf), is("ACTIVE"));
    assertThat("nothing was announced", count(announced), is(announcedBefore));
    assertThat("no plan change was recorded", count(planMoves), is(movesBefore));
    assertThat("and the plan it is on is the one it was", scalar(PG, planOf), is(planBefore));
    assertThat("no incident was opened", count(incidents), is(incidentsBefore));

    // The platform administrator's own calls, to the same routes, reach their handlers.
    Who root = admin();
    Answer list = call(target, "GET", "/platform/tenants?limit=100", null, root);
    assertThat(list.text(), list.status(), is(200));
    assertThat("both businesses are on the platform's list", list.text(), containsString(t));
    assertThat(list.text(), containsString(theirs.tenant()));
    Answer one = call(target, "GET", "/platform/tenants/" + t, null, root);
    assertThat(one.text(), one.status(), is(200));
    assertThat(one.data().getString("id"), is(t));
    assertThat(
        "no business holds that address",
        call(
                target,
                "GET",
                "/platform/tenants/by-einvoice-address?vatNumber=GB123456789",
                null,
                root)
            .status(),
        is(404));
    Answer unknownPlan =
        call(
            target,
            "PUT",
            "/platform/tenants/" + t + "/plan",
            "{\"planId\":\"" + plan + "\"}",
            root);
    assertThat(unknownPlan.text(), unknownPlan.status(), is(404));
    assertThat(unknownPlan.code(), is("PLAN_NOT_FOUND"));
    Answer replay =
        call(target, "POST", "/platform/tenants/republish-currency?tenantId=" + t, null, root);
    assertThat(replay.text(), replay.status(), is(200));
    assertThat(replay.text(), containsString("\"tenantsAnnounced\":1"));
    assertThat("the replay announced one", count(announced), is(announcedBefore + 1));
    Answer off =
        call(
            target,
            "PATCH",
            "/platform/tenants/" + t + "/status",
            "{\"status\":\"INACTIVE\",\"reason\":\"take it down\"}",
            root);
    assertThat(off.text(), off.status(), is(200));
    assertThat("the suspension moved it", scalar(PG, statusOf), is("INACTIVE"));
    assertThat(count(announced), is(announcedBefore + 2));
    assertThat(
        call(target, "PATCH", "/platform/tenants/" + t + "/status", "{\"status\":\"ACTIVE\"}", root)
            .status(),
        is(200));
    Answer opened = call(target, "POST", "/platform/security-incidents", incident(t), root);
    assertThat(opened.text(), opened.status(), is(201));
    assertThat("the incident was opened", count(incidents), is(incidentsBefore + 1));
  }

  @Test
  @DisplayName("The platform's reads and writes name a business that is not there, and find nobody")
  void anUnknownBusinessIsNotFoundOnTheRoutesThatNameOne() {
    Who root = admin();
    String nobody = Ids.newId().toString();
    int incidents = count("SELECT count(*) FROM tenant.security_incidents");
    Answer read = call(target, "GET", "/platform/tenants/" + nobody, null, root);
    assertThat(read.text(), read.status(), is(404));
    assertThat(read.code(), is("TENANT_NOT_FOUND"));
    Answer suspend =
        call(
            target,
            "PATCH",
            "/platform/tenants/" + nobody + "/status",
            "{\"status\":\"INACTIVE\",\"reason\":\"x\"}",
            root);
    assertThat(suspend.text(), suspend.status(), is(404));
    Answer move =
        call(
            target,
            "PUT",
            "/platform/tenants/" + nobody + "/plan",
            "{\"planId\":\"" + Ids.newId() + "\"}",
            root);
    assertThat(move.text(), move.status(), is(404));
    assertThat(move.code(), is("TENANT_NOT_FOUND"));
    Answer notAnId = call(target, "GET", "/platform/tenants/not-an-id", null, root);
    assertThat(notAnId.text(), notAnId.status(), is(400));
    assertThat(notAnId.code(), is("INVALID_UUID"));
    Answer replay =
        call(target, "POST", "/platform/tenants/republish-currency?tenantId=not-an-id", null, root);
    assertThat(replay.text(), replay.status(), is(400));
    assertThat(replay.code(), is("INVALID_UUID"));
    Answer replayNobody =
        call(target, "POST", "/platform/tenants/republish-currency?tenantId=" + nobody, null, root);
    assertThat("a business that is not there is announced as none", replayNobody.status(), is(200));
    assertThat(replayNobody.text(), containsString("\"tenantsAnnounced\":0"));
    assertThat(
        "naming a business that is not there opened no incident",
        count("SELECT count(*) FROM tenant.security_incidents"),
        is(incidents));
  }
}
