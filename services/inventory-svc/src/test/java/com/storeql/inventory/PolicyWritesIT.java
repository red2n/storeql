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
import java.util.Map;
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

  /** What another business's staff are told when they name one of our records by id. */
  private static final Map<String, String> NOT_FOUND_CODES =
      Map.ofEntries(
          Map.entry("close period", "PERIOD_NOT_FOUND"),
          Map.entry("activate source type", "SOURCE_TYPE_NOT_FOUND"),
          Map.entry("deactivate source type", "SOURCE_TYPE_NOT_FOUND"),
          Map.entry("activate reason code", "REASON_CODE_NOT_FOUND"),
          Map.entry("deactivate reason code", "REASON_CODE_NOT_FOUND"),
          Map.entry("kanban order modifiers", "KANBAN_NOT_FOUND"),
          Map.entry("deactivate picking rule", "PICKING_RULE_NOT_FOUND"),
          Map.entry("picking rule zone priorities", "PICKING_RULE_NOT_FOUND"),
          Map.entry("delete picking rule assignment", "ASSIGNMENT_NOT_FOUND"));

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
      case "GET" -> b.get();
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
            route.name() + " " + role,
            code(call(r, OTHER_T, role, r.store()), 404),
            is(NOT_FOUND_CODES.get(route.name())));
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

  // ── refusals: what a write says when it is wrong, and that nothing moved ────

  private Response owner(String method, String path, String body) {
    return call(new Req(method, path, body, null), T, "OWNER", null);
  }

  private static String count(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  @Test
  @DisplayName("An ABC compile with criteria, thresholds or a class nobody defined is refused")
  void anAbcCompileOrFilterNobodyDefinedIsRefused() {
    String store = Ids.newId().toString();
    String runs =
        "SELECT count(*) FROM inventory.abc_compile_runs WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'";
    assertThat(
        code(
            owner(
                "POST",
                BASE + "/abc/compile",
                "{\"storeId\":\"" + store + "\",\"criteria\":\"COST\"}"),
            400),
        is("INVALID_ABC_CRITERIA"));
    assertThat(
        code(
            owner(
                "POST",
                BASE + "/abc/compile",
                "{\"storeId\":\""
                    + store
                    + "\",\"criteria\":\"VALUE\",\"thresholdA\":90,\"thresholdAB\":70}"),
            400),
        is("INVALID_ABC_THRESHOLDS"));
    assertThat(
        code(
            owner(
                "POST", BASE + "/abc/compile", "{\"storeId\":\"" + store + "\",\"thresholdA\":0}"),
            400),
        is("INVALID_ABC_THRESHOLDS"));
    assertThat(count(runs), is("0"));

    assertThat(
        code(owner("GET", BASE + "/abc/assignments?class=X", null), 400), is("INVALID_ABC_CLASS"));
    assertThat(
        code(owner("GET", BASE + "/abc/assignments/" + store + "/" + Ids.newId(), null), 404),
        is("ABC_ASSIGNMENT_NOT_FOUND"));
    // Another business's owner finds nothing either.
    assertThat(
        code(
            call(
                new Req("GET", BASE + "/abc/assignments/" + store + "/" + Ids.newId(), null, null),
                OTHER_T,
                "OWNER",
                null),
            404),
        is("ABC_ASSIGNMENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("Safety stock with a method nobody defined, or no percentage, is refused")
  void aSafetyStockSettingThatIsWrongIsRefused() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String rows =
        "SELECT count(*) FROM inventory.safety_stock_params WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'";
    assertThat(
        code(
            owner(
                "POST",
                BASE + "/safety-stock",
                "{\"storeId\":\""
                    + store
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"method\":\"INVALID_METHOD\"}"),
            400),
        is("INVALID_SAFETY_STOCK_METHOD"));
    for (String body :
        new String[] {
          "{\"storeId\":\"%s\",\"variantId\":\"%s\",\"method\":\"USER_DEFINED\"}",
          "{\"storeId\":\"%s\",\"variantId\":\"%s\",\"method\":\"USER_DEFINED\","
              + "\"userDefinedPct\":0}"
        }) {
      assertThat(
          code(owner("POST", BASE + "/safety-stock", body.formatted(store, variant)), 400),
          is("USER_DEFINED_PCT_REQUIRED"));
    }
    assertThat(count(rows), is("0"));
    assertThat(
        code(owner("GET", BASE + "/safety-stock/" + store + "/" + variant, null), 404),
        is("SAFETY_STOCK_PARAMS_NOT_FOUND"));
  }

  @Test
  @DisplayName("A second period on the same day, or closing one twice, is refused")
  void aPeriodOpenedTwiceOrClosedTwiceIsRefused() {
    String store = Ids.newId().toString();
    String body =
        "{\"storeId\":\""
            + store
            + "\",\"periodName\":\"P"
            + tail()
            + "\",\"periodDate\":\"1999-03-04\"}";
    Response first =
        call(new Req("POST", BASE + "/accounting-periods", body, store), T, "OWNER", null);
    String id = id(first.readEntity(String.class));
    assertThat(first.getStatus(), is(201));

    assertThat(
        code(
            call(
                new Req(
                    "POST",
                    BASE + "/accounting-periods",
                    body.replaceFirst("\"P[^\"]*\"", "\"Again\""),
                    store),
                T,
                "OWNER",
                null),
            409),
        is("PERIOD_DUPLICATE_DATE"));
    assertThat(
        count(
            "SELECT count(*) FROM inventory.accounting_periods WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"),
        is("1"));

    assertThat(
        owner("POST", BASE + "/accounting-periods/" + id + "/close", "").getStatus(), is(200));
    assertThat(
        code(owner("POST", BASE + "/accounting-periods/" + id + "/close", ""), 409),
        is("PERIOD_NOT_OPEN"));
    assertThat(
        code(owner("POST", BASE + "/accounting-periods/" + Ids.newId() + "/close", ""), 404),
        is("PERIOD_NOT_FOUND"));
    assertThat(
        count(
            "SELECT status FROM inventory.accounting_periods WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + id
                + "'"),
        is("CLOSED"));
  }

  @Test
  @DisplayName("A kanban card of a type nobody defined is refused; triggers and refills keep order")
  void aKanbanCardThatIsWrongOrOutOfTurnIsRefused() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    for (String type : new String[] {"INVALID", "TRANSFER"}) {
      assertThat(
          type,
          code(
              owner(
                  "POST",
                  BASE + "/kanban-cards",
                  "{\"storeId\":\""
                      + store
                      + "\",\"variantId\":\""
                      + variant
                      + "\",\"kanbanType\":\""
                      + type
                      + "\",\"reorderQty\":5}"),
              400),
          is("INVALID_KANBAN_TYPE"));
    }
    assertThat(
        count(
            "SELECT count(*) FROM inventory.kanban_cards WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"),
        is("0"));

    String card = card(store);
    String cardState =
        "SELECT status FROM inventory.kanban_cards WHERE tenant_id = '"
            + T
            + "' AND id = '"
            + card
            + "'";
    // An EMPTY card cannot be replenished.
    assertThat(
        code(owner("POST", BASE + "/kanban-cards/" + card + "/replenish", ""), 409),
        is("KANBAN_NOT_TRIGGERED"));
    assertThat(count(cardState), is("EMPTY"));
    // Triggered once; the second trigger is refused and the card stays triggered.
    assertThat(
        owner("POST", BASE + "/kanban-cards/" + card + "/trigger", "{}").getStatus(), is(200));
    assertThat(
        code(owner("POST", BASE + "/kanban-cards/" + card + "/trigger", "{}"), 409),
        is("KANBAN_NOT_EMPTY"));
    assertThat(count(cardState), is("TRIGGERED"));
    // A card that is not there, and one of another business, are not found, and ours is unmoved.
    assertThat(
        code(owner("POST", BASE + "/kanban-cards/" + Ids.newId() + "/trigger", "{}"), 404),
        is("KANBAN_NOT_FOUND"));
    assertThat(
        code(
            call(
                new Req("POST", BASE + "/kanban-cards/" + card + "/replenish", "", null),
                OTHER_T,
                "OWNER",
                null),
            404),
        is("KANBAN_NOT_FOUND"));
    assertThat(count(cardState), is("TRIGGERED"));
  }

  @Test
  @DisplayName(
      "A picking rule or assignment that is wrong or not there is refused, and none is made")
  void aPickingRuleOrAssignmentThatIsWrongIsRefused() {
    assertThat(
        code(
            owner(
                "POST",
                BASE + "/picking-rules",
                "{\"name\":\"R" + tail() + "\",\"strategy\":\"RANDOM\"}"),
            400),
        is("INVALID_STRATEGY"));
    assertThat(
        code(owner("GET", BASE + "/picking-rules/" + Ids.newId(), null), 404),
        is("PICKING_RULE_NOT_FOUND"));

    String rule = rule();
    String assignments =
        "SELECT count(*) FROM inventory.picking_rule_assignments WHERE tenant_id = '"
            + T
            + "' AND rule_id = '"
            + rule
            + "'";
    assertThat(
        code(
            owner(
                "POST",
                BASE + "/picking-rule-assignments",
                "{\"ruleId\":\""
                    + rule
                    + "\",\"scopeType\":\"CATEGORY\",\"scopeId\":\""
                    + Ids.newId()
                    + "\"}"),
            400),
        is("INVALID_SCOPE_TYPE"));
    assertThat(
        code(
            owner(
                "POST",
                BASE + "/picking-rule-assignments",
                "{\"ruleId\":\"" + rule + "\",\"scopeType\":\"PRODUCT\"}"),
            400),
        is("SCOPE_ID_REQUIRED"));
    assertThat(
        code(
            owner(
                "POST",
                BASE + "/picking-rule-assignments",
                "{\"ruleId\":\"" + Ids.newId() + "\",\"scopeType\":\"GLOBAL\"}"),
            404),
        is("PICKING_RULE_NOT_FOUND"));
    assertThat(count(assignments), is("0"));

    String store = Ids.newId().toString();
    String assignment = assignment(store);
    assertThat(
        owner("DELETE", BASE + "/picking-rule-assignments/" + assignment, null).getStatus(),
        oneOf(200, 204));
    assertThat(
        code(owner("DELETE", BASE + "/picking-rule-assignments/" + assignment, null), 404),
        is("ASSIGNMENT_NOT_FOUND"));
    assertThat(
        code(owner("DELETE", BASE + "/picking-rule-assignments/" + Ids.newId(), null), 404),
        is("ASSIGNMENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("Resolving a picking rule needs both a store and a variant")
  void aPickingRuleResolvedWithoutItsStoreAndVariantIsRefused() {
    assertThat(code(owner("GET", BASE + "/picking-rules/resolve", null), 400), is("MISSING_PARAM"));
    assertThat(
        code(owner("GET", BASE + "/picking-rules/resolve?store=" + Ids.newId(), null), 400),
        is("MISSING_PARAM"));
  }
}
