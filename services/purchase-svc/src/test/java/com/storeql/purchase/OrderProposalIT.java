package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The automatic order proposal (06.x) through the service: the stock position read from an
 * inventory-svc stub, what is on order read from this service's own orders, a draft raised on the
 * supplier the business last bought from with every line's arithmetic, and the refusals.
 */
@HelidonTest
class OrderProposalIT {

  static final String T = "01a090ae-8a1e-7d2c-a97b-d1b8025478e1";
  static final String OWNER = "01a090ae-8a1e-7d2c-b111-d1b8025478e1";

  private static final PostgresSupport PG;
  private static final JsonStub INVENTORY;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "GBP", "GB");
    INVENTORY = JsonStub.start("inventory-svc");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "purchase");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.purchase.approval.limits", "");
  }

  @Inject WebTarget target;

  private UUID store;
  private UUID otherStore;
  private final UUID v1 = Ids.newId();
  private final UUID v2 = Ids.newId();
  private final UUID v3 = Ids.newId();
  private final UUID v4 = Ids.newId();

  @BeforeEach
  void fresh() throws SQLException {
    PurchaseFixtures.truncateAll(PG);
    INVENTORY.reset();
    // Routes outlive a test; each starts with no network, as a business without a warehouse has.
    INVENTORY.on("GET", "/admin/inventory/network/sourcing", 404, "{}");
    store = Ids.newId();
    otherStore = Ids.newId();
  }

  // ── the door ────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String role, String storeIds) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", OWNER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return storeIds == null ? b : b.header("X-Store-Ids", storeIds);
  }

  private Response post(String path, String json) {
    return as(path, "OWNER", null).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject body(Response r, int expected) {
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(expected));
    return Json.createReader(new StringReader(text)).readObject();
  }

  private static JsonObject data(Response r, int expected) {
    return body(r, expected).getJsonObject("data");
  }

  private String supplier(String name) {
    return data(
            post(
                "/suppliers",
                "{\"name\":\"" + name + "\",\"vatRegistered\":false,\"currency\":\"GBP\"}"),
            201)
        .getString("id");
  }

  /**
   * A submitted order for one line: the supplier we last bought the item from, and stock on order.
   */
  private String orderFrom(String supplierId, UUID variant, String qty, String price) {
    String poId =
        data(
                post(
                    "/purchase-orders",
                    "{\"supplierId\":\"" + supplierId + "\",\"storeId\":\"" + store + "\"}"),
                201)
            .getString("id");
    data(
        post(
            "/purchase-orders/" + poId + "/lines",
            "{\"variantId\":\"" + variant + "\",\"qty\":" + qty + ",\"unitPrice\":" + price + "}"),
        201);
    data(post("/purchase-orders/" + poId + "/submit", ""), 200);
    return poId;
  }

  private static String plan(UUID v, String rop, String eoq, String avgDaily, int lead) {
    return "{\"id\":\""
        + Ids.newId()
        + "\",\"variantId\":\""
        + v
        + "\",\"leadTimeDays\":"
        + lead
        + ",\"avgDailyDemand\":"
        + avgDaily
        + ",\"rop\":"
        + rop
        + ",\"eoq\":"
        + eoq
        + ",\"minOrderQty\":null,\"maxOrderQty\":null,\"lotMultiplier\":null}";
  }

  private static String level(UUID v, String available) {
    return "{\"variantId\":\""
        + v
        + "\",\"onHand\":"
        + available
        + ",\"reserved\":0,\"available\":"
        + available
        + "}";
  }

  private void stockPosition(String plans, String levels, String forecasts) {
    INVENTORY.on("GET", "/admin/inventory/rop-plans", 200, "{\"data\":[" + plans + "]}");
    INVENTORY.on(
        "GET",
        "/admin/inventory/levels",
        200,
        "{\"data\":[" + levels + "],\"meta\":{\"nextCursor\":null}}");
    INVENTORY.on("GET", "/admin/inventory/forecasts", 200, "{\"data\":[" + forecasts + "]}");
  }

  private static JsonObject first(JsonArray arr, String field, String value) {
    return arr.getValuesAs(JsonObject.class).stream()
        .filter(o -> value.equals(o.getString(field)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + field + " = " + value + " in " + arr));
  }

  @Test
  @DisplayName(
      "At the reorder point, a draft is raised on the supplier we last bought from, with the arithmetic on every line; a second run waits for the first draft")
  void proposesFromTheStockPosition() {
    String acme = supplier("Acme Wholesale");
    orderFrom(acme, v1, "10", "2.50"); // ten on order, and Acme is where we buy v1
    stockPosition(
        plan(v1, "28", "45", "4", 7)
            + ","
            + plan(v2, "10", "20", "1", 7)
            + ","
            + plan(v3, "5", "null", "1", 3)
            + ","
            + plan(v4, "null", "null", "0", 7),
        level(v1, "3") + "," + level(v2, "50") + "," + level(v3, "1") + "," + level(v4, "0"),
        "{\"variantId\":\"" + v3 + "\",\"next28\":20.0000}");

    JsonObject run =
        data(post("/purchase-orders/proposals/run", "{\"storeId\":\"" + store + "\"}"), 200);
    assertThat(run.getInt("considered"), is(4));
    assertThat(run.getInt("coverDays"), is(28));
    JsonArray orders = run.getJsonArray("orders");
    assertThat("one supplier, one draft", orders.size(), is(1));
    JsonObject draft = orders.getJsonObject(0);
    assertThat(draft.getString("supplierId"), is(acme));
    assertThat(draft.getString("supplierName"), is("Acme Wholesale"));
    assertThat(draft.getString("currency"), is("GBP"));
    assertThat(draft.getInt("lines"), is(1));
    assertThat(
        "45 at the 2.50 we last paid",
        draft.getJsonNumber("totalNet").bigDecimalValue(),
        is(new BigDecimal("112.50")));
    JsonArray skipped = run.getJsonArray("skipped");
    assertThat(skipped.size(), is(2));
    assertThat(
        first(skipped, "variantId", v3.toString()).getString("reason"),
        containsString("no supplier"));
    assertThat(
        first(skipped, "variantId", v4.toString()).getString("reason"),
        containsString("no reorder point"));

    String poId = draft.getString("poId");
    JsonObject po = data(as("/purchase-orders/" + poId, "OWNER", null).get(), 200);
    assertThat(po.getString("status"), is("DRAFT"));
    assertThat(po.getString("source"), is("PROPOSAL"));
    assertThat(
        "the supplier's lead time from today",
        po.getString("expectedDelivery"),
        is(LocalDate.now(ZoneOffset.UTC).plusDays(7).toString()));
    JsonArray lines =
        body(as("/purchase-orders/" + poId + "/lines", "OWNER", null).get(), 200)
            .getJsonArray("data");
    assertThat(lines.size(), is(1));
    JsonObject line = lines.getJsonObject(0);
    assertThat(line.getString("variantId"), is(v1.toString()));
    assertThat(line.getJsonNumber("qty").bigDecimalValue(), is(new BigDecimal("45.000")));
    assertThat(line.getJsonNumber("unitPrice").bigDecimalValue(), is(new BigDecimal("2.50")));
    assertThat(line.getString("proposalReason"), containsString("on hand 3 + on order 10 = 13"));
    assertThat(line.getString("proposalReason"), containsString("EOQ 45"));
    // The order a person typed carries no reason.
    JsonArray typed = body(as("/purchase-orders", "OWNER", null).get(), 200).getJsonArray("data");
    assertThat(first(typed, "source", "MANUAL").getString("status"), is("SUBMITTED"));

    JsonObject again =
        body(post("/purchase-orders/proposals/run", "{\"storeId\":\"" + store + "\"}"), 409);
    assertThat(again.toString(), containsString("PURCHASE_PROPOSAL_OPEN"));
    data(post("/purchase-orders/" + poId + "/cancel", "{\"reason\":\"not this week\"}"), 200);
    JsonObject third =
        data(post("/purchase-orders/proposals/run", "{\"storeId\":\"" + store + "\"}"), 200);
    assertThat(third.getJsonArray("orders").size(), is(1));

    JsonArray runs =
        body(as("/purchase-orders/proposals?store=" + store, "OWNER", null).get(), 200)
            .getJsonArray("data");
    assertThat(runs.size(), is(2));
    assertThat("latest first", runs.getJsonObject(0).getString("id"), is(third.getString("id")));
    assertThat(runs.getJsonObject(1).getJsonArray("skipped").size(), is(2));
  }

  @Test
  @DisplayName(
      "Without an EOQ the draft covers the period from the forecast, scaled to the cover asked for; a received order names the supplier but is no longer on order")
  void coversFromTheForecast() {
    String bee = supplier("Bee Foods");
    String poId = orderFrom(bee, v3, "5", "1.20");
    data(
        post(
            "/goods-receipts",
            "{\"poId\":\""
                + poId
                + "\",\"storeId\":\""
                + store
                + "\",\"lines\":[{\"variantId\":\""
                + v3
                + "\",\"qtyReceived\":5}]}"),
        201);
    stockPosition(
        plan(v3, "5", "null", "1", 3),
        level(v3, "1"),
        "{\"variantId\":\"" + v3 + "\",\"next28\":20.0000}");

    JsonObject run =
        data(
            post(
                "/purchase-orders/proposals/run",
                "{\"storeId\":\"" + store + "\",\"coverDays\":14}"),
            200);
    JsonObject draft = run.getJsonArray("orders").getJsonObject(0);
    assertThat(draft.getString("supplierId"), is(bee));
    JsonArray lines =
        body(as("/purchase-orders/" + draft.getString("poId") + "/lines", "OWNER", null).get(), 200)
            .getJsonArray("data");
    JsonObject line = lines.getJsonObject(0);
    // back to the reorder point (5 - 1 = 4) plus fourteen days of a twenty-eight-day forecast of 20
    // (10)
    assertThat(line.getJsonNumber("qty").bigDecimalValue(), is(new BigDecimal("14.000")));
    assertThat(line.getJsonNumber("unitPrice").bigDecimalValue(), is(new BigDecimal("1.20")));
    assertThat(line.getString("proposalReason"), containsString("on hand 1 + on order 0 = 1"));
    assertThat(line.getString("proposalReason"), containsString("forecast 10 over 14 days"));
    JsonObject po =
        data(as("/purchase-orders/" + draft.getString("poId"), "OWNER", null).get(), 200);
    assertThat(
        po.getString("expectedDelivery"), is(LocalDate.now(ZoneOffset.UTC).plusDays(3).toString()));
  }

  @Test
  @DisplayName(
      "Refusals: a keeper of another store, a cover outside a day to a year, and no stock position at all")
  void refusals() {
    stockPosition(plan(v1, "28", "45", "4", 7), level(v1, "3"), "");
    try (Response other =
        as("/purchase-orders/proposals/run", "STOREKEEPER", otherStore.toString())
            .post(Entity.entity("{\"storeId\":\"" + store + "\"}", MediaType.APPLICATION_JSON))) {
      assertThat(other.readEntity(String.class), other.getStatus(), is(403));
    }
    assertThat(
        body(
                post(
                    "/purchase-orders/proposals/run",
                    "{\"storeId\":\"" + store + "\",\"coverDays\":0}"),
                400)
            .toString(),
        containsString("PURCHASE_PROPOSAL_COVER_INVALID"));
    assertThat(
        post("/purchase-orders/proposals/run", "{\"storeId\":\"not-a-store\"}").getStatus(),
        is(400));
    assertThat(as("/purchase-orders/proposals", "OWNER", null).get().getStatus(), is(400));

    INVENTORY.on("GET", "/admin/inventory/rop-plans", 503, "{}");
    JsonObject down =
        body(post("/purchase-orders/proposals/run", "{\"storeId\":\"" + store + "\"}"), 503);
    assertThat(down.toString(), containsString("PURCHASE_PROPOSAL_STOCK_UNAVAILABLE"));
    assertThat(
        "nothing was raised",
        body(as("/purchase-orders", "OWNER", null).get(), 200).getJsonArray("data").size(),
        is(0));
    assertThat(
        body(as("/purchase-orders/proposals?store=" + store, "OWNER", null).get(), 200)
            .getJsonArray("data")
            .size(),
        is(0));
  }

  @Test
  @DisplayName(
      "A fresh item's draft covers no more than its shelf life, whatever cover was asked for")
  void freshItemsAreCappedToTheirShelfLife() {
    String dairy = supplier("Dairy Direct");
    String poId = orderFrom(dairy, v3, "5", "0.80");
    data(
        post(
            "/goods-receipts",
            "{\"poId\":\""
                + poId
                + "\",\"storeId\":\""
                + store
                + "\",\"lines\":[{\"variantId\":\""
                + v3
                + "\",\"qtyReceived\":5}]}"),
        201);
    stockPosition(
        plan(v3, "5", "null", "1", 2),
        level(v3, "1"),
        "{\"variantId\":\""
            + v3
            + "\",\"next28\":28.0000,\"fresh\":true,\"shelfLifeDays\":5,\"maxCoverDays\":5}");
    JsonObject run =
        data(
            post(
                "/purchase-orders/proposals/run",
                "{\"storeId\":\"" + store + "\",\"coverDays\":28}"),
            200);
    JsonObject draft = run.getJsonArray("orders").getJsonObject(0);
    JsonArray lines =
        body(as("/purchase-orders/" + draft.getString("poId") + "/lines", "OWNER", null).get(), 200)
            .getJsonArray("data");
    JsonObject line = lines.getJsonObject(0);
    // back to the reorder point (5 - 1 = 4) plus five days of a twenty-eight-day forecast of 28
    // (5), not 28 days
    assertThat(line.getJsonNumber("qty").bigDecimalValue(), is(new BigDecimal("9.000")));
    assertThat(line.getString("proposalReason"), containsString("capped to the 5-day shelf life"));
  }

  // ── depot / DC replenishment ────────────────────────────────────────────────

  private void sourcing(String json) {
    INVENTORY.on("GET", "/admin/inventory/network/sourcing", 200, "{\"data\":" + json + "}");
  }

  @Test
  @DisplayName(
      "A shop a warehouse serves buys only what it buys direct; the rest is the warehouse's to send")
  void aServedShopBuysOnlyWhatItBuysDirect() {
    String acme = supplier("Acme Wholesale");
    orderFrom(acme, v1, "1", "2.00");
    orderFrom(acme, v2, "1", "3.00");
    UUID warehouse = Ids.newId();
    // Both below their reorder points; v2 is bought direct, v1 comes from the warehouse.
    stockPosition(
        plan(v1, "10", "20", "1", 7) + "," + plan(v2, "10", "30", "1", 7),
        level(v1, "0") + "," + level(v2, "0"),
        "");
    sourcing(
        "{\"storeId\":\""
            + store
            + "\",\"warehouse\":false,\"servedBy\":\""
            + warehouse
            + "\",\"leadTimeDays\":2,\"direct\":[\""
            + v2
            + "\"],\"shops\":[],\"demand\":[]}");
    JsonObject run =
        data(post("/purchase-orders/proposals/run", "{\"storeId\":\"" + store + "\"}"), 200);
    assertThat(run.getInt("considered"), is(1));
    JsonArray orders = run.getJsonArray("orders");
    assertThat(orders.size(), is(1));
    String poId = orders.getJsonObject(0).getString("poId");
    JsonArray lines =
        body(as("/purchase-orders/" + poId + "/lines", "OWNER", null).get(), 200)
            .getJsonArray("data");
    assertThat(lines.size(), is(1));
    assertThat(lines.getJsonObject(0).getString("variantId"), is(v2.toString()));
  }

  @Test
  @DisplayName(
      "A warehouse buys for the shops it serves: their demand over its lead time, less what it has promised them")
  void aWarehouseBuysForTheShopsItServes() {
    String acme =
        data(
                post(
                    "/suppliers",
                    "{\"name\":\"Acme Wholesale\",\"vatRegistered\":false,\"currency\":\"GBP\","
                        + "\"leadTimeDays\":3}"),
                201)
            .getString("id");
    orderFrom(acme, v1, "1", "2.00"); // one on order, and Acme is where we buy v1
    // The warehouse has no plan of its own (it sells nothing); 10 on hand, 5 promised to shops.
    stockPosition("", level(v1, "10") + "," + level(v4, "0"), "");
    sourcing(
        "{\"storeId\":\""
            + store
            + "\",\"warehouse\":true,\"servedBy\":null,\"direct\":[],\"shops\":[\""
            + Ids.newId()
            + "\",\""
            + Ids.newId()
            + "\"],\"demand\":[{\"variantId\":\""
            + v1
            + "\",\"next28\":56,\"avgDailyDemand\":2,\"committed\":5,\"shops\":2},"
            + "{\"variantId\":\""
            + v4
            + "\",\"next28\":28,\"avgDailyDemand\":1,\"committed\":0,\"shops\":1}]}");
    JsonObject run =
        data(post("/purchase-orders/proposals/run", "{\"storeId\":\"" + store + "\"}"), 200);
    assertThat(run.getInt("considered"), is(2));
    // v4: nobody sells it to us and no plan says how long it takes.
    assertThat(
        first(run.getJsonArray("skipped"), "variantId", v4.toString()).getString("reason"),
        containsString("no lead time for the warehouse"));
    JsonArray orders = run.getJsonArray("orders");
    assertThat(orders.size(), is(1));
    String poId = orders.getJsonObject(0).getString("poId");
    JsonObject line =
        body(as("/purchase-orders/" + poId + "/lines", "OWNER", null).get(), 200)
            .getJsonArray("data")
            .getJsonObject(0);
    // Reorder point: 2 a day over Acme's 3 days = 6; position: 10 on hand less 5 promised, plus 1
    // on order = 6 ≤ 6; order back to it plus the shops' 56 over 28 days.
    assertThat(line.getJsonNumber("qty").bigDecimalValue(), is(new BigDecimal("56.000")));
    String reason = line.getString("proposalReason");
    assertThat(reason, containsString("for the 2 shops it serves (2/day; 5 committed to them)"));
    assertThat(reason, containsString("on hand 5 + on order 1 = 6 ≤ reorder point 6"));
  }
}
