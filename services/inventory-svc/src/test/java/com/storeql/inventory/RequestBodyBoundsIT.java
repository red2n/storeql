package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every request body goes through common-web {@code Validations.validate} (02 Oct 2026): the order
 * modifiers of a reorder-point plan and of a kanban card, an ABC compile, a safety-stock recompute
 * and a kanban trigger. A number no quantity or threshold could be ({@code 1E+80000000}, twelve
 * characters on the wire) is {@code 400 VALIDATION_FAILED "<field>: is out of range"} in the
 * platform's problem, where it was a 500 from the database or a number eighty million digits long,
 * and nothing is written.
 */
@HelidonTest
class RequestBodyBoundsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();
  private static final String BASE = "/admin/inventory";

  /** Too many whole digits, too many places, and the one that wraps @Digits' int arithmetic. */
  private static final List<String> ABSURD = List.of("1E+80000000", "1E-80000000", "1E+2147483647");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private record Answer(int status, JsonObject body, String text) {
    String code() {
      return body.getString("code", null);
    }

    List<String> details() {
      return body.containsKey("details")
          ? body.getJsonArray("details").getValuesAs(JsonString.class).stream()
              .map(JsonString::getString)
              .toList()
          : List.of();
    }
  }

  private Answer call(String method, String path, String json, String role) {
    Invocation.Builder b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", USER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    Entity<String> body = Entity.entity(json, MediaType.APPLICATION_JSON);
    Response r = "PUT".equals(method) ? b.put(body) : b.post(body);
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(), text.isBlank() ? JsonObject.EMPTY_JSON_OBJECT : Envelopes.parse(text), text);
  }

  private Answer owner(String method, String path, String json) {
    return call(method, path, json, "OWNER");
  }

  private static void outOfRange(Answer a, String field) {
    assertThat(a.text(), a.status(), is(400));
    assertThat(a.text(), a.code(), is("VALIDATION_FAILED"));
    assertThat(a.text(), a.details(), is(List.of(field + ": is out of range")));
    assertThat(a.text(), a.body().getString("type"), is("urn:storeql:problem:VALIDATION_FAILED"));
  }

  private static void fieldRefused(Answer a, String field) {
    assertThat(a.text(), a.status(), is(400));
    assertThat(a.text(), a.code(), is("VALIDATION_FAILED"));
    assertThat(a.text(), a.details().size(), is(1));
    assertThat(a.text(), a.details().get(0), startsWith(field + ": "));
  }

  private static String scalar(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  private String plan(String store) {
    Answer made =
        owner(
            "PUT",
            BASE + "/rop-plans",
            "{\"storeId\":\""
                + store
                + "\",\"variantId\":\""
                + Ids.newId()
                + "\",\"leadTimeDays\":7,\"orderingCost\":50,\"holdingCostPct\":0.2,"
                + "\"unitCost\":10}");
    assertThat(made.text(), made.status(), is(200));
    return made.body().getJsonObject("data").getString("id");
  }

  private String card(String store) {
    Answer made =
        owner(
            "POST",
            BASE + "/kanban-cards",
            "{\"storeId\":\""
                + store
                + "\",\"variantId\":\""
                + Ids.newId()
                + "\",\"kanbanType\":\"PRODUCTION\",\"reorderQty\":5}");
    assertThat(made.text(), made.status(), oneOf(200, 201));
    return made.body().getJsonObject("data").getString("id");
  }

  private static String modifiersOfPlan(String id) {
    return scalar(
        "SELECT coalesce(min_order_qty::text, '-') || '/' || coalesce(max_order_qty::text, '-')"
            + " || '/' || coalesce(lot_multiplier::text, '-') FROM inventory.reorder_point_plans"
            + " WHERE tenant_id = '"
            + T
            + "' AND id = '"
            + id
            + "'");
  }

  private static String cardState(String id) {
    return scalar(
        "SELECT status || '/' || coalesce(min_order_qty::text, '-') || '/'"
            + " || coalesce(max_order_qty::text, '-') || '/' || coalesce(lot_multiplier::text, '-')"
            + " || '/' || coalesce(notes, '-') FROM inventory.kanban_cards WHERE tenant_id = '"
            + T
            + "' AND id = '"
            + id
            + "'");
  }

  // ── order modifiers ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A plan's order modifiers refuse a number no quantity could be, field by field, and the plan"
          + " is as it was")
  void aPlansOrderModifiersRefuseAbsurdNumbers() {
    String id = plan(Ids.newId().toString());
    String path = BASE + "/rop-plans/" + id + "/order-modifiers";
    String before = modifiersOfPlan(id);
    for (String absurd : ABSURD) {
      for (String field : List.of("minOrderQty", "maxOrderQty", "lotMultiplier")) {
        outOfRange(owner("PUT", path, "{\"" + field + "\":" + absurd + "}"), field);
      }
    }
    // What the column cannot hold was a 500 (numeric field overflow); what it would round, rounded.
    fieldRefused(owner("PUT", path, "{\"minOrderQty\":1000000000000000}"), "minOrderQty");
    fieldRefused(owner("PUT", path, "{\"minOrderQty\":1.0005}"), "minOrderQty");
    fieldRefused(owner("PUT", path, "{\"minOrderQty\":-1}"), "minOrderQty");
    fieldRefused(owner("PUT", path, "{\"lotMultiplier\":0}"), "lotMultiplier");
    Answer none = owner("PUT", path, "null");
    assertThat(none.text(), none.status(), is(400));
    assertThat(none.code(), oneOf("BODY_REQUIRED", "REQUEST_BODY_INVALID"));
    assertThat("nothing was written", modifiersOfPlan(id), is(before));

    // A wrong body is refused before the plan is looked for: 400, not 404.
    outOfRange(
        owner(
            "PUT",
            BASE + "/rop-plans/" + Ids.newId() + "/order-modifiers",
            "{\"minOrderQty\":1E+80000000}"),
        "minOrderQty");
    // Not management: refused before the body is read.
    Answer cashier = call("PUT", path, "{\"minOrderQty\":1E+80000000}", "CASHIER");
    assertThat(cashier.text(), cashier.status(), is(403));

    Answer set =
        owner("PUT", path, "{\"minOrderQty\":5,\"maxOrderQty\":100,\"lotMultiplier\":0.5}");
    assertThat(set.text(), set.status(), is(200));
    assertThat(modifiersOfPlan(id), is("5.000/100.000/0.500"));
  }

  @Test
  @DisplayName(
      "A kanban card's order modifiers refuse a number no quantity could be, and the card is as it"
          + " was")
  void aKanbanCardsOrderModifiersRefuseAbsurdNumbers() {
    String id = card(Ids.newId().toString());
    String path = BASE + "/kanban-cards/" + id + "/order-modifiers";
    String before = cardState(id);
    for (String absurd : ABSURD) {
      for (String field : List.of("minOrderQty", "maxOrderQty", "lotMultiplier")) {
        outOfRange(owner("PUT", path, "{\"" + field + "\":" + absurd + "}"), field);
      }
    }
    fieldRefused(owner("PUT", path, "{\"maxOrderQty\":1000000000000000}"), "maxOrderQty");
    assertThat("nothing was written", cardState(id), is(before));

    Answer set = owner("PUT", path, "{\"minOrderQty\":3,\"lotMultiplier\":3}");
    assertThat(set.text(), set.status(), is(200));
    assertThat(cardState(id), is("EMPTY/3.000/-/3.000/-"));
  }

  // ── ABC compile, safety stock, kanban trigger ──────────────────────────────

  @Test
  @DisplayName(
      "An ABC compile refuses a threshold no percentage could be, or finer than the run keeps, and"
          + " no run is written")
  void anAbcCompileRefusesAbsurdThresholds() {
    String store = Ids.newId().toString();
    String runs =
        "SELECT count(*) FROM inventory.abc_compile_runs WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'";
    for (String absurd : ABSURD) {
      outOfRange(
          owner(
              "POST",
              BASE + "/abc/compile",
              "{\"storeId\":\"" + store + "\",\"thresholdA\":" + absurd + "}"),
          "thresholdA");
      outOfRange(
          owner(
              "POST",
              BASE + "/abc/compile",
              "{\"storeId\":\"" + store + "\",\"thresholdA\":70,\"thresholdAB\":" + absurd + "}"),
          "thresholdAB");
    }
    // 99.98 < 99.999 < 100 passes the service, and 99.999 was kept as 100.00, which the run's own
    // check refuses: a 500.
    fieldRefused(
        owner(
            "POST",
            BASE + "/abc/compile",
            "{\"storeId\":\"" + store + "\",\"thresholdA\":99.98,\"thresholdAB\":99.999}"),
        "thresholdAB");
    assertThat("no run was written", scalar(runs), is("0"));

    Answer ok =
        owner(
            "POST",
            BASE + "/abc/compile",
            "{\"storeId\":\"" + store + "\",\"thresholdA\":70.5,\"thresholdAB\":90}");
    assertThat(ok.text(), ok.status(), is(201));
    assertThat(scalar(runs), is("1"));
  }

  @Test
  @DisplayName(
      "A safety-stock recompute is checked when a body is sent, and still takes none; a store that"
          + " is no id computes nothing")
  void aSafetyStockRecomputeIsChecked() {
    String rows =
        "SELECT count(*) FROM inventory.safety_stock_params WHERE tenant_id = '" + T + "'";
    String before = scalar(rows);
    Answer notAnId = owner("POST", BASE + "/safety-stock/compute", "{\"storeId\":\"1E+80000000\"}");
    assertThat(notAnId.text(), notAnId.status(), is(400));
    assertThat(notAnId.code(), is("INVALID_UUID"));
    assertThat(scalar(rows), is(before));

    Answer whole = owner("POST", BASE + "/safety-stock/compute", "{}");
    assertThat(whole.text(), whole.status(), is(200));
  }

  @Test
  @DisplayName(
      "A kanban trigger's note is held to 2000 characters and the card stays empty; one within it"
          + " triggers")
  void aKanbanTriggerChecksItsNote() {
    String id = card(Ids.newId().toString());
    String path = BASE + "/kanban-cards/" + id + "/trigger";
    fieldRefused(owner("POST", path, "{\"notes\":\"" + "x".repeat(2001) + "\"}"), "notes");
    assertThat(cardState(id), startsWith("EMPTY/"));

    Answer ok = owner("POST", path, "{\"notes\":\"bin empty\"}");
    assertThat(ok.text(), ok.status(), is(200));
    assertThat(cardState(id), is("TRIGGERED/-/-/-/bin empty"));
  }

  // ── whole numbers read as sent, decimals as kept (2 Oct 2026, second pass) ─────

  private static void notTheShape(Answer a) {
    assertThat(a.text(), a.status(), is(400));
    assertThat(a.text(), a.code(), is("REQUEST_BODY_INVALID"));
  }

  private static String plans(String store) {
    return scalar(
        "SELECT count(*) FROM inventory.reorder_point_plans WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'");
  }

  private static String ropBody(
      String store, String variant, String lead, String ordering, String holding, String unit) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"leadTimeDays\":"
        + lead
        + ",\"orderingCost\":"
        + ordering
        + ",\"holdingCostPct\":"
        + holding
        + ",\"unitCost\":"
        + unit
        + "}";
  }

  @Test
  @DisplayName(
      "A plan's lead time is the days sent: 4294967303 was bound as 7 and written with a 200; now"
          + " it, 7.5 and 1E+80000000 are refused at binding and no plan is written")
  void aPlansLeadTimeIsNotCutDown() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    for (String cut : List.of("4294967303", "7.5", "1E+80000000", "-4294967289")) {
      notTheShape(
          owner("PUT", BASE + "/rop-plans", ropBody(store, variant, cut, "50", "20", "10")));
    }
    assertThat("no plan was written", plans(store), is("0"));

    Answer ok = owner("PUT", BASE + "/rop-plans", ropBody(store, variant, "7", "50", "20", "10"));
    assertThat(ok.text(), ok.status(), is(200));
    assertThat(
        scalar(
            "SELECT lead_time_days FROM inventory.reorder_point_plans WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"),
        is("7"));
  }

  @Test
  @DisplayName(
      "A plan's costs are held to the plan: a holding cost of 1000 was a 500 (NUMERIC(7,4)) and an"
          + " ordering cost of 50.005 was kept as 50.01; both are 400 and nothing is written")
  void aPlansCostsAreHeldToThePlan() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    fieldRefused(
        owner("PUT", BASE + "/rop-plans", ropBody(store, variant, "7", "50", "1000", "10")),
        "holdingCostPct");
    fieldRefused(
        owner("PUT", BASE + "/rop-plans", ropBody(store, variant, "7", "50.005", "20", "10")),
        "orderingCost");
    fieldRefused(
        owner("PUT", BASE + "/rop-plans", ropBody(store, variant, "7", "50", "20", "10.0000001")),
        "unitCost");
    fieldRefused(
        owner("PUT", BASE + "/rop-plans", ropBody(store, variant, "366", "50", "20", "10")),
        "leadTimeDays");
    assertThat("no plan was written", plans(store), is("0"));

    Answer ok =
        owner("PUT", BASE + "/rop-plans", ropBody(store, variant, "7", "50.01", "999.9999", "10"));
    assertThat(ok.text(), ok.status(), is(200));
    assertThat(
        scalar(
            "SELECT ordering_cost || '/' || holding_cost_pct FROM inventory.reorder_point_plans"
                + " WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"),
        is("50.01/999.9999"));
  }

  @Test
  @DisplayName(
      "A receipt is held to the batch: 1E+20 overflowed received_qty as a 500 and 0.0004 became a"
          + " batch of nothing; both are 400 and no batch is written")
  void aReceiptIsHeldToTheBatch() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String batches =
        "SELECT count(*) FROM inventory.inventory_batches WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'";
    String receive = "{\"storeId\":\"" + store + "\",\"variantId\":\"" + variant + "\",";
    fieldRefused(owner("POST", BASE + "/receive", receive + "\"qty\":1E+20}"), "qty");
    fieldRefused(owner("POST", BASE + "/receive", receive + "\"qty\":0.0004}"), "qty");
    fieldRefused(
        owner("POST", BASE + "/receive", receive + "\"qty\":1,\"costPrice\":1.00005}"),
        "costPrice");
    assertThat("no batch was written", scalar(batches), is("0"));

    Answer ok = owner("POST", BASE + "/receive", receive + "\"qty\":2.5,\"costPrice\":1.2345}");
    assertThat(ok.text(), ok.status(), oneOf(200, 201));
    assertThat(
        scalar(
            "SELECT received_qty || '/' || cost_price FROM inventory.inventory_batches"
                + " WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"),
        is("2.500/1.2345"));
  }

  @Test
  @DisplayName(
      "A hold's lifetime is the seconds sent and at most a year: past a long is refused at"
          + " binding, Long.MAX_VALUE (past any instant, a 500) and none are 400, nothing is held")
  void aHoldsLifetimeIsHeld() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String holds =
        "SELECT count(*) FROM inventory.reservations WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'";
    String reserve =
        "{\"storeId\":\""
            + store
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":1,\"ttlSeconds\":";
    notTheShape(owner("POST", "/inventory/reservations", reserve + "9223372036854775808}"));
    notTheShape(owner("POST", "/inventory/reservations", reserve + "900.5}"));
    fieldRefused(
        owner("POST", "/inventory/reservations", reserve + "9223372036854775807}"), "ttlSeconds");
    fieldRefused(owner("POST", "/inventory/reservations", reserve + "0}"), "ttlSeconds");
    assertThat("nothing was held", scalar(holds), is("0"));
  }

  @Test
  @DisplayName(
      "A figure written with trailing zeros is the figure: order-svc's hold of 1.0000 was a 400"
          + " (a 503 at its checkout) and product-svc's import of 2.5000 lost its whole chunk;"
          + " both are taken and kept as the column keeps them")
  void trailingZerosAreTheFigure() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String other = Ids.newId().toString();
    String line = "{\"storeId\":\"" + store + "\",\"variantId\":\"";

    // product-svc's CSV import posts a sheet's figures as written, a chunk to a call.
    Answer chunk =
        owner(
            "POST",
            BASE + "/receive/batch",
            "{\"items\":["
                + line
                + variant
                + "\",\"qty\":2.5000},"
                + line
                + other
                + "\",\"qty\":1.0000}]}");
    assertThat(chunk.text(), chunk.status(), is(200));
    JsonObject result = chunk.body().getJsonObject("data");
    assertThat(chunk.text(), result.getInt("received"), is(2));
    assertThat(chunk.text(), result.getJsonArray("errors").size(), is(0));

    Answer receipt =
        owner(
            "POST",
            BASE + "/receive",
            line + variant + "\",\"qty\":2.5000,\"costPrice\":1.50000000}");
    assertThat(receipt.text(), receipt.status(), oneOf(200, 201));
    String ofTheStore =
        " FROM inventory.inventory_batches WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'";
    assertThat(
        scalar("SELECT string_agg(received_qty::text, ',' ORDER BY received_qty)" + ofTheStore),
        is("1.000,2.500,2.500"));
    assertThat(scalar("SELECT max(cost_price)::text" + ofTheStore), is("1.5000"));

    // order-svc's InventoryClient posts Quantities.typed's 1.0000 as written.
    Answer hold =
        owner(
            "POST",
            "/inventory/reservations",
            line + variant + "\",\"qty\":1.0000,\"ttlSeconds\":172800}");
    assertThat(hold.text(), hold.status(), is(201));
    assertThat(
        scalar(
            "SELECT string_agg(qty::text, ',') FROM inventory.reservations WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"),
        is("1.000"));

    // Still held: a place the column would round away, written with zeros after it.
    fieldRefused(
        owner("POST", "/inventory/reservations", line + variant + "\",\"qty\":1.00010}"), "qty");
    fieldRefused(
        owner(
            "POST",
            BASE + "/receive/batch",
            "{\"items\":[" + line + variant + "\",\"qty\":2.50050}]}"),
        "qty");
  }
}
