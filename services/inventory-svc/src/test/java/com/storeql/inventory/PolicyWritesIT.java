package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Policy writes are management's (intent/inventory-screens.md slice 1): the accounting periods,
 * zone-to-ledger mappings, source types, reason codes, ABC compile, safety stock, par levels,
 * kanban cards and picking rules. One table, every route, every role; floor writes stay staff's.
 */
@HelidonTest
class PolicyWritesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = Ids.newId().toString();
  private static final String OTHER_T = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();
  private static final String STRANGER = Ids.newId().toString();
  private static final String BASE = "/admin/inventory";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /** One request: what it says, the store it names (null when it names none), and a probe. */
  private record Req(String method, String path, String body, String store) {}

  /** A route: builds a fresh request (and any fixture of ours it needs) on every call. */
  private record Route(String name, Supplier<Req> build, boolean byId) {}

  private Response call(Req r, String tenant, String role, String stores) {
    Invocation.Builder b =
        WebTargets.at(target, r.path())
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", T.equals(tenant) ? USER : STRANGER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    if (stores != null) b = b.header("X-Store-Ids", stores);
    Entity<String> body =
        Entity.entity(r.body() == null ? "" : r.body(), MediaType.APPLICATION_JSON);
    return switch (r.method()) {
      case "PUT" -> b.put(body);
      case "DELETE" -> b.delete();
      default -> b.post(body);
    };
  }

  private static String tail() {
    String s = Ids.newId().toString();
    return s.substring(s.length() - 12);
  }

  private String owned(Req r) {
    Response resp = call(r, T, "OWNER", null);
    String text = resp.readEntity(String.class);
    assertThat(text, resp.getStatus(), oneOf(200, 201));
    return text;
  }

  private static String id(String envelope) {
    return Envelopes.parse(envelope).getJsonObject("data").getString("id");
  }

  // ── fixtures of ours ───────────────────────────────────────────────────────

  private String period(String store) {
    String body =
        "{\"storeId\":\""
            + store
            + "\",\"periodName\":\"P"
            + tail()
            + "\",\"periodDate\":\""
            + LocalDate.of(2000, 1, 1).plusDays(SEQ.incrementAndGet())
            + "\"}";
    return id(owned(new Req("POST", BASE + "/accounting-periods", body, store)));
  }

  private String reasonCode() {
    return id(
        owned(new Req("POST", BASE + "/reason-codes", "{\"code\":\"RC" + tail() + "\"}", null)));
  }

  private String sourceType() {
    return id(
        owned(new Req("POST", BASE + "/source-types", "{\"code\":\"ST" + tail() + "\"}", null)));
  }

  private String rule() {
    return id(
        owned(
            new Req(
                "POST",
                BASE + "/picking-rules",
                "{\"name\":\"R" + tail() + "\",\"strategy\":\"FEFO\"}",
                null)));
  }

  private static String assignmentBody(String ruleId, String store) {
    return "{\"ruleId\":\"" + ruleId + "\",\"scopeType\":\"STORE\",\"scopeId\":\"" + store + "\"}";
  }

  private String assignment(String store) {
    return id(
        owned(
            new Req(
                "POST", BASE + "/picking-rule-assignments", assignmentBody(rule(), store), store)));
  }

  private String card(String store) {
    String body =
        "{\"storeId\":\""
            + store
            + "\",\"variantId\":\""
            + Ids.newId()
            + "\",\"kanbanType\":\"PRODUCTION\",\"reorderQty\":5}";
    return id(owned(new Req("POST", BASE + "/kanban-cards", body, store)));
  }

  // ── the table ──────────────────────────────────────────────────────────────

  private List<Route> routes() {
    return List.of(
        new Route(
            "open period",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "POST",
                  BASE + "/accounting-periods",
                  "{\"storeId\":\""
                      + s
                      + "\",\"periodName\":\"P"
                      + tail()
                      + "\",\"periodDate\":\""
                      + LocalDate.of(2000, 1, 1).plusDays(SEQ.incrementAndGet())
                      + "\"}",
                  s);
            },
            false),
        new Route(
            "close period",
            () -> {
              String s = Ids.newId().toString();
              return new Req("POST", BASE + "/accounting-periods/" + period(s) + "/close", "", s);
            },
            true),
        new Route(
            "zone ledger mapping",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "PUT",
                  BASE + "/zone-gl-mappings",
                  "{\"storeId\":\"" + s + "\",\"nominalCode\":\"N" + tail() + "\"}",
                  s);
            },
            false),
        new Route(
            "create source type",
            () -> new Req("POST", BASE + "/source-types", "{\"code\":\"S" + tail() + "\"}", null),
            false),
        new Route(
            "activate source type",
            () -> new Req("POST", BASE + "/source-types/" + sourceType() + "/activate", "", null),
            true),
        new Route(
            "deactivate source type",
            () -> new Req("POST", BASE + "/source-types/" + sourceType() + "/deactivate", "", null),
            true),
        new Route(
            "create reason code",
            () -> new Req("POST", BASE + "/reason-codes", "{\"code\":\"C" + tail() + "\"}", null),
            false),
        new Route(
            "activate reason code",
            () -> new Req("POST", BASE + "/reason-codes/" + reasonCode() + "/activate", "", null),
            true),
        new Route(
            "deactivate reason code",
            () -> new Req("POST", BASE + "/reason-codes/" + reasonCode() + "/deactivate", "", null),
            true),
        new Route(
            "abc compile",
            () -> {
              String s = Ids.newId().toString();
              return new Req("POST", BASE + "/abc/compile", "{\"storeId\":\"" + s + "\"}", s);
            },
            false),
        new Route(
            "set safety stock",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "POST",
                  BASE + "/safety-stock",
                  "{\"storeId\":\""
                      + s
                      + "\",\"variantId\":\""
                      + Ids.newId()
                      + "\",\"method\":\"USER_DEFINED\",\"userDefinedPct\":10}",
                  s);
            },
            false),
        new Route(
            "compute safety stock",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "POST", BASE + "/safety-stock/compute", "{\"storeId\":\"" + s + "\"}", s);
            },
            false),
        new Route(
            "compute rop plans",
            () -> {
              String s = Ids.newId().toString();
              return new Req("POST", BASE + "/rop-plans/compute?store=" + s, "", s);
            },
            false),
        new Route(
            "par level",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "PUT",
                  BASE + "/par-levels",
                  "{\"storeId\":\""
                      + s
                      + "\",\"variantId\":\""
                      + Ids.newId()
                      + "\",\"parQty\":10,\"reviewCycle\":\"WEEKLY\"}",
                  s);
            },
            false),
        new Route(
            "create kanban card",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "POST",
                  BASE + "/kanban-cards",
                  "{\"storeId\":\""
                      + s
                      + "\",\"variantId\":\""
                      + Ids.newId()
                      + "\",\"kanbanType\":\"PRODUCTION\",\"reorderQty\":5}",
                  s);
            },
            false),
        new Route(
            "kanban order modifiers",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "PUT",
                  BASE + "/kanban-cards/" + card(s) + "/order-modifiers",
                  "{\"minOrderQty\":3}",
                  s);
            },
            true),
        new Route(
            "create picking rule",
            () ->
                new Req(
                    "POST",
                    BASE + "/picking-rules",
                    "{\"name\":\"R" + tail() + "\",\"strategy\":\"FIFO\"}",
                    null),
            false),
        new Route(
            "deactivate picking rule",
            () -> new Req("DELETE", BASE + "/picking-rules/" + rule(), null, null),
            true),
        new Route(
            "picking rule zone priorities",
            () ->
                new Req(
                    "PUT",
                    BASE + "/picking-rules/" + rule() + "/zone-priorities",
                    "{\"zonePriorities\":[{\"zoneId\":\"" + Ids.newId() + "\",\"priority\":1}]}",
                    null),
            true),
        new Route(
            "assign picking rule",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "POST", BASE + "/picking-rule-assignments", assignmentBody(rule(), s), s);
            },
            false),
        new Route(
            "delete picking rule assignment",
            () -> {
              String s = Ids.newId().toString();
              return new Req(
                  "DELETE", BASE + "/picking-rule-assignments/" + assignment(s), null, s);
            },
            true));
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  // ── the tests ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Every policy write is management's; the floor is refused, a held manager stays home")
  void policyWritesAreManagements() {
    for (Route route : routes()) {
      String label = route.name();
      // The shop floor and everyone else is refused before anything is looked at.
      for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
        assertThat(
            label + " " + role,
            code(call(route.build().get(), T, role, null), 403),
            is("FORBIDDEN"));
      }
      assertThat(
          label + " CUSTOMER", call(route.build().get(), T, "CUSTOMER", null).getStatus(), is(403));

      // A manager held to another store is refused where the route names a store.
      Req named = route.build().get();
      if (named.store() != null) {
        assertThat(
            label + " held elsewhere",
            code(call(named, T, "MANAGER", Ids.newId().toString()), 403),
            is("STORE_ACCESS_DENIED"));
      }

      // Management writes, a held manager at the store included.
      for (String role : new String[] {"MANAGER", "OWNER", "PLATFORM_ADMIN"}) {
        Req r = route.build().get();
        Response resp = call(r, T, role, null);
        assertThat(
            label + " " + role + " " + resp.readEntity(String.class),
            resp.getStatus(),
            oneOf(200, 201, 204));
      }
      Req own = route.build().get();
      if (own.store() != null) {
        Response resp = call(own, T, "MANAGER", own.store());
        assertThat(
            label + " held here " + resp.readEntity(String.class),
            resp.getStatus(),
            oneOf(200, 201, 204));
      }
    }
  }

  @Test
  @DisplayName(
      "Policy writes stay in their business: another business's staff change nothing of ours")
  void policyWritesStayInTheirBusiness() {
    String period = period(Ids.newId().toString());
    String reason = reasonCode();
    String assignmentStore = Ids.newId().toString();
    String assignment = assignment(assignmentStore);
    for (Route route : routes()) {
      for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
        Req r = route.build().get();
        assertThat(
            route.name() + " " + role, call(r, OTHER_T, role, r.store()).getStatus(), is(403));
      }
      if (!route.byId()) continue;
      for (String role : new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER"}) {
        Req r = route.build().get();
        assertThat(
            route.name() + " " + role, call(r, OTHER_T, role, r.store()).getStatus(), is(404));
      }
    }
    // Ours are as they were.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT status FROM inventory.accounting_periods WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + period
                + "'"),
        is("OPEN"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT active FROM inventory.transaction_reason_codes WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + reason
                + "'"),
        is("true"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.picking_rule_assignments WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + assignment
                + "'"),
        is("1"));
    Response other =
        call(
            new Req("POST", BASE + "/accounting-periods/" + period + "/close", "", null),
            OTHER_T,
            "OWNER",
            null);
    assertThat(other.getStatus(), is(404));
    JsonObject none = Envelopes.parse(other.readEntity(String.class));
    assertThat(none.containsKey("code"), is(true));
  }
}
