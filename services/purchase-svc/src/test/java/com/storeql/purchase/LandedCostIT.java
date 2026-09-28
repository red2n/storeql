package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Landed cost (07.x), end to end inside the service: a charge spread exactly over a receipt's
 * lines, posted Dr Stock / Cr Landed Costs Accrued, announced for inventory, idempotent on replay;
 * reversed with a reason and the mirror posted; every refusal named; another business kept out.
 */
@HelidonTest
class LandedCostIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(LandedCostIT.T, "GBP", "GB").with(LandedCostIT.OTHER_T, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "purchase");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.purchase.approval.limits", "");
  }

  private static final String T = "01a0be6a-0000-7000-8000-000000000001";
  private static final String OTHER_T = "01a0be6a-0000-7000-8000-000000000002";
  private static final String STORE = "01a0be6a-0000-7000-8000-000000000010";
  private static final String APPLES = "01a0be6a-0000-7000-8000-000000000020";
  private static final String PEARS = "01a0be6a-0000-7000-8000-000000000021";
  private static final String USER = "01a0be6a-0000-7000-8000-000000000030";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── harness ──────────────────────────────────────────────────────────────────

  private Response call(
      String method, String path, String json, String tenant, String roles, String key) {
    int q = path.indexOf('?');
    WebTarget t = target.path(q < 0 ? path : path.substring(0, q));
    if (q >= 0) {
      for (String param : path.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    var b =
        t.request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("X-User-Id", USER)
            .header("Idempotency-Key", key);
    return "POST".equals(method)
        ? b.post(Entity.entity(json, MediaType.APPLICATION_JSON))
        : b.get();
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "OWNER", Ids.newId().toString());
  }

  private Response get(String path) {
    return call("GET", path, null, T, "OWNER", Ids.newId().toString());
  }

  private static JsonObject data(Response r) {
    try (JsonReader reader = Json.createReader(new StringReader(r.readEntity(String.class)))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private static JsonArray dataArray(Response r) {
    try (JsonReader reader = Json.createReader(new StringReader(r.readEntity(String.class)))) {
      return reader.readObject().getJsonArray("data");
    }
  }

  private static String extractId(String json) {
    var m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static String errorCode(Response r) {
    String body = r.readEntity(String.class);
    var m = Pattern.compile("\"code\":\"([A-Z_]+)\"").matcher(body);
    return m.find() ? m.group(1) : body;
  }

  private static String json(String template, Object... args) {
    return String.format(template, args);
  }

  /** A submitted order for apples and pears at the given unit prices, received in full. */
  private String[] receivedOrder(String applePrice, String pearPrice) {
    Response sup =
        post(
            "/suppliers",
            json(
                "{\"name\":\"Landed %s\",\"vatRegistered\":true,\"currency\":\"GBP\"}",
                Ids.newId()));
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));
    Response po =
        post(
            "/purchase-orders",
            json("{\"supplierId\":\"%s\",\"storeId\":\"%s\",\"currency\":\"GBP\"}", supId, STORE));
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));
    assertThat(
        post(
                "/purchase-orders/" + poId + "/lines",
                json("{\"variantId\":\"%s\",\"qty\":10,\"unitPrice\":%s}", APPLES, applePrice))
            .getStatus(),
        is(201));
    assertThat(
        post(
                "/purchase-orders/" + poId + "/lines",
                json("{\"variantId\":\"%s\",\"qty\":5,\"unitPrice\":%s}", PEARS, pearPrice))
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}").getStatus(), is(200));
    Response gr =
        post(
            "/goods-receipts",
            json(
                "{\"poId\":\"%s\",\"storeId\":\"%s\",\"lines\":[{\"variantId\":\"%s\",\"qtyReceived\":10},{\"variantId\":\"%s\",\"qtyReceived\":5}]}",
                poId, STORE, APPLES, PEARS));
    assertThat(gr.getStatus(), is(201));
    return new String[] {poId, extractId(gr.readEntity(String.class)), supId};
  }

  private static String charge(String grId, String type, String basis, String amount) {
    return json(
        "{\"grId\":\"%s\",\"chargeType\":\"%s\",\"basis\":\"%s\",\"amount\":%s,\"reference\":\"CN-1\"}",
        grId, type, basis, amount);
  }

  private JsonObject line(JsonObject charge, String variantId) {
    for (JsonValue v : charge.getJsonArray("lines")) {
      if (variantId.equals(v.asJsonObject().getString("variantId"))) return v.asJsonObject();
    }
    throw new AssertionError("no line for " + variantId + " in " + charge);
  }

  /**
   * The ledger lines one source wrote about one charge, today; the tests share a ledger. Matched on
   * the source reference, which carries the id: the description names things by handle only.
   */
  private JsonArray journal(String sourceType, String chargeId) {
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    var out = Json.createArrayBuilder();
    for (JsonValue v : dataArray(get("/nominal-ledger?limit=100&from=" + today + "&to=" + today))) {
      JsonObject e = v.asJsonObject();
      if (sourceType.equals(e.getString("sourceType", null))
          && chargeId.equals(e.getString("sourceRef", null))) {
        out.add(v);
      }
    }
    return out.build();
  }

  /** How people see an id: its last eight characters, as every screen and journal shows it. */
  private static String handle(String id) {
    return "#" + id.substring(id.length() - 8);
  }

  /** Every line of a journal carries this description, and none carries a whole id. */
  private static void describedAs(JsonArray lines, String description, String... ids) {
    assertThat(lines.isEmpty(), is(false));
    for (JsonValue v : lines) {
      String said = v.asJsonObject().getString("description");
      assertThat(said, is(description));
      for (String id : ids) assertThat(said, not(containsString(id)));
    }
  }

  private static BigDecimal sum(JsonArray entries, String code, String side) {
    BigDecimal total = BigDecimal.ZERO;
    for (JsonValue v : entries) {
      JsonObject e = v.asJsonObject();
      if (code.equals(e.getString("nominalCode"))) {
        total = total.add(e.getJsonNumber(side).bigDecimalValue());
      }
    }
    return total;
  }

  private String outboxTypes(String aggregateId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT event_type FROM purchase.outbox WHERE aggregate_id = ? ORDER BY created_at")) {
      ps.setObject(1, Ids.parse(aggregateId));
      try (var rs = ps.executeQuery()) {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) sb.append(rs.getString(1)).append(',');
        return sb.toString();
      }
    }
  }

  // ── tests ────────────────────────────────────────────────────────────────────

  @Test
  void freightByValueIsSpreadExactlyPostedAndAnnounced() throws Exception {
    String[] ids = receivedOrder("2.50", "3.00"); // 25.00 of apples, 15.00 of pears
    Response r = post("/landed-costs", charge(ids[1], "freight", "by_value", "10.00"));
    assertThat(r.getStatus(), is(201));
    JsonObject c = data(r);
    assertThat(c.getString("status"), is("APPLIED"));
    assertThat(c.getString("chargeType"), is("FREIGHT"));
    assertThat(c.getString("currency"), is("GBP"));
    assertThat(
        line(c, APPLES).getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("6.25")));
    assertThat(
        line(c, APPLES).getJsonNumber("perUnit").bigDecimalValue(), is(new BigDecimal("0.6250")));
    assertThat(
        line(c, PEARS).getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("3.75")));
    assertThat(
        line(c, PEARS).getJsonNumber("lineValue").bigDecimalValue(), is(new BigDecimal("15.00")));

    JsonArray posted = journal("LANDED_COST", c.getString("id"));
    assertThat(sum(posted, "1001", "debit"), is(new BigDecimal("10.00")));
    assertThat(sum(posted, "2110", "credit"), is(new BigDecimal("10.00")));
    // Read in the accounting package and on the Integrations screen: the receipt and its order
    // named as people see them (the procurement screen shows "PO #…"), never by a whole id.
    describedAs(
        posted,
        "Freight landed on receipt " + handle(ids[1]) + " against PO " + handle(ids[0]),
        ids[1],
        ids[0]);
    assertThat(outboxTypes(c.getString("id")), is("LandedCostApplied,"));

    // The charge is read back whole, and listed by receipt and by order.
    JsonObject back = data(get("/landed-costs/" + c.getString("id")));
    assertThat(back.getJsonArray("lines").size(), is(2));
    assertThat(dataArray(get("/landed-costs?grId=" + ids[1])).size(), is(1));
    assertThat(dataArray(get("/landed-costs?poId=" + ids[0])).size(), is(1));
  }

  @Test
  void dutyByQuantityIgnoresPriceAndTheChargeIsIdempotentOnReplay() throws Exception {
    String[] ids = receivedOrder("100.00", "0.10");
    String key = Ids.newId().toString();
    String body = charge(ids[1], "DUTY", "BY_QUANTITY", "3.00");
    Response first = call("POST", "/landed-costs", body, T, "STOREKEEPER", key);
    assertThat(first.getStatus(), is(201));
    JsonObject c = data(first);
    assertThat(
        line(c, APPLES).getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("2.00")));
    assertThat(
        line(c, PEARS).getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("1.00")));
    assertThat(
        line(c, PEARS).getJsonNumber("perUnit").bigDecimalValue(), is(new BigDecimal("0.2000")));

    Response again = call("POST", "/landed-costs", body, T, "STOREKEEPER", key);
    assertThat(again.getStatus(), is(201));
    assertThat(data(again).getString("id"), is(c.getString("id")));
    assertThat(
        "one posting, not two", dataArray(get("/landed-costs?grId=" + ids[1])).size(), is(1));
    assertThat(outboxTypes(c.getString("id")), is("LandedCostApplied,"));
  }

  @Test
  void aReversalNeedsAReasonMirrorsThePostingAndHappensOnce() throws Exception {
    String[] ids = receivedOrder("2.00", "2.00");
    JsonObject c = data(post("/landed-costs", charge(ids[1], "INSURANCE", "BY_VALUE", "6.00")));
    String id = c.getString("id");

    Response blank = post("/landed-costs/" + id + "/reversal", "{\"reason\":\"   \"}");
    assertThat(blank.getStatus(), is(400));

    Response r =
        post("/landed-costs/" + id + "/reversal", "{\"reason\":\"insurer credited the premium\"}");
    assertThat(r.getStatus(), is(200));
    JsonObject reversed = data(r);
    assertThat(reversed.getString("status"), is("REVERSED"));
    assertThat(reversed.getString("reversedReason"), is("insurer credited the premium"));
    assertThat(
        "the lines stay with the reversed charge", reversed.getJsonArray("lines").size(), is(2));

    JsonArray mirror = journal("LANDED_COST_REVERSAL", id);
    assertThat(sum(mirror, "2110", "debit"), is(new BigDecimal("6.00")));
    assertThat(sum(mirror, "1001", "credit"), is(new BigDecimal("6.00")));
    describedAs(
        mirror,
        "Insurance on receipt "
            + handle(ids[1])
            + " against PO "
            + handle(ids[0])
            + " reversed: insurer credited the premium",
        ids[1],
        ids[0]);
    assertThat(outboxTypes(id), is("LandedCostApplied,LandedCostReversed,"));

    Response twice = post("/landed-costs/" + id + "/reversal", "{\"reason\":\"again\"}");
    assertThat(twice.getStatus(), is(409));
    assertThat(errorCode(twice), is("PURCHASE_LANDED_REVERSED"));
  }

  @Test
  void everyRefusalIsNamedAndAnotherBusinessIsKeptOut() {
    String[] ids = receivedOrder("2.50", "3.00");
    String grId = ids[1];

    Response type = post("/landed-costs", charge(grId, "POSTAGE", "BY_VALUE", "1.00"));
    assertThat(type.getStatus(), is(400));
    assertThat(errorCode(type), is("PURCHASE_LANDED_INVALID"));

    Response basis = post("/landed-costs", charge(grId, "FREIGHT", "EVENLY", "1.00"));
    assertThat(basis.getStatus(), is(400));
    assertThat(errorCode(basis), is("PURCHASE_LANDED_INVALID"));

    Response zero = post("/landed-costs", charge(grId, "FREIGHT", "BY_VALUE", "0.00"));
    assertThat("a zero charge is refused at the boundary", zero.getStatus(), is(400));

    Response currency =
        post(
            "/landed-costs",
            json(
                "{\"grId\":\"%s\",\"chargeType\":\"FREIGHT\",\"basis\":\"BY_VALUE\",\"amount\":1.00,\"currency\":\"EUR\"}",
                grId));
    assertThat(currency.getStatus(), is(400));
    assertThat(errorCode(currency), is("PURCHASE_LANDED_CURRENCY_MISMATCH"));

    Response carrier =
        post(
            "/landed-costs",
            json(
                "{\"grId\":\"%s\",\"chargeType\":\"FREIGHT\",\"basis\":\"BY_VALUE\",\"amount\":1.00,\"chargedBy\":\"%s\"}",
                grId, Ids.newId()));
    assertThat(carrier.getStatus(), is(404));
    assertThat(errorCode(carrier), is("PURCHASE_SUPPLIER_NOT_FOUND"));

    Response unknown =
        post("/landed-costs", charge(Ids.newId().toString(), "FREIGHT", "BY_VALUE", "1.00"));
    assertThat(unknown.getStatus(), is(404));
    assertThat(errorCode(unknown), is("PURCHASE_GRN_NOT_FOUND"));

    Response cashier =
        call(
            "POST",
            "/landed-costs",
            charge(grId, "FREIGHT", "BY_VALUE", "1.00"),
            T,
            "CASHIER",
            Ids.newId().toString());
    assertThat(cashier.getStatus(), is(403));

    // Another business: the receipt does not exist for it, and nor does a charge on it.
    Response rival =
        call(
            "POST",
            "/landed-costs",
            charge(grId, "FREIGHT", "BY_VALUE", "1.00"),
            OTHER_T,
            "OWNER",
            Ids.newId().toString());
    assertThat(rival.getStatus(), is(404));
    JsonObject c = data(post("/landed-costs", charge(grId, "FREIGHT", "BY_VALUE", "1.00")));
    assertThat(
        call("GET", "/landed-costs/" + c.getString("id"), null, OTHER_T, "OWNER", null).getStatus(),
        is(404));
    assertThat(
        call(
                "POST",
                "/landed-costs/" + c.getString("id") + "/reversal",
                "{\"reason\":\"mine\"}",
                OTHER_T,
                "OWNER",
                Ids.newId().toString())
            .getStatus(),
        is(404));
    assertThat(
        call("GET", "/landed-costs?grId=" + grId, null, OTHER_T, "OWNER", Ids.newId().toString())
            .getStatus(),
        is(404));
  }
}
