package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Return to vendor and debit notes (07.8): the reverse SJ-D3 named. Goods go back against a
 * received order at the order's prices, no more than was received, once per key; the debit note is
 * numbered; the supplier's credit note closes it, once; the wrong caller and the wrong input are
 * refused by name.
 */
@HelidonTest
class VendorReturnIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start()
        .with(VendorReturnIT.T, "GBP", "GB")
        .with(VendorReturnIT.OTHER_T, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "purchase");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.purchase.approval.limits", "");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String OTHER_T = "01a090ae-611e-7037-a4b7-c854f0266ace";
  private static final String STORE_A = "01a090ae-611e-703c-a378-a4972ea461c8";
  private static final String VARIANT = "01a090ae-611e-705c-994c-5daee3fbd033";
  private static final String OTHER_VARIANT = "01a090ae-611e-705c-994c-5daee3fbd034";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant, String roles) {
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
            .header("X-User-Id", "01a090ae-611e-7055-9838-5de027ce9e0c")
            .header("Idempotency-Key", Ids.newId().toString());
    return "POST".equals(method)
        ? b.post(Entity.entity(json, MediaType.APPLICATION_JSON))
        : b.get();
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "OWNER");
  }

  private static JsonObject data(Response r) {
    try (JsonReader reader = Json.createReader(new StringReader(r.readEntity(String.class)))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private static String extractId(String json) {
    var m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  /** A supplier, an order of {@code ordered} at 2.50, submitted, with {@code received} received. */
  private String receivedOrder(String ordered, String received) {
    Response sup =
        post(
            "/suppliers",
            "{\"name\":\"RTV Supplier "
                + Ids.newId()
                + "\",\"vatRegistered\":true,\"currency\":\"GBP\"}");
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));
    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supId
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"currency\":\"GBP\"}");
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));
    assertThat(
        post(
                "/purchase-orders/" + poId + "/lines",
                "{\"variantId\":\"" + VARIANT + "\",\"qty\":" + ordered + ",\"unitPrice\":2.50}")
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}").getStatus(), is(200));
    if (received != null) {
      assertThat(
          post(
                  "/goods-receipts",
                  "{\"poId\":\""
                      + poId
                      + "\",\"storeId\":\""
                      + STORE_A
                      + "\",\"lines\":[{\"variantId\":\""
                      + VARIANT
                      + "\",\"qtyReceived\":"
                      + received
                      + "}]}")
              .getStatus(),
          is(201));
    }
    return poId;
  }

  private static String rtv(String poId, String reason, String qty) {
    return "{\"poId\":\""
        + poId
        + "\",\"reason\":\""
        + reason
        + "\",\"notes\":\"crushed cases\",\"lines\":[{\"variantId\":\""
        + VARIANT
        + "\",\"qty\":"
        + qty
        + "}]}";
  }

  private String outboxTypes(String returnId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement("SELECT event_type FROM purchase.outbox WHERE aggregate_id = ?")) {
      ps.setObject(1, Ids.parse(returnId));
      try (var rs = ps.executeQuery()) {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) sb.append(rs.getString(1)).append(',');
        return sb.toString();
      }
    }
  }

  // ── tests ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Goods go back at the order's prices, numbered, and the event that moves the stock is written with them")
  void goodsGoBackAtTheOrdersPrices() throws Exception {
    String po = receivedOrder("10", "10");
    Response r = call("POST", "/vendor-returns", rtv(po, "damaged", "3"), T, "STOREKEEPER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    JsonObject ret = Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
    assertThat(ret.getString("status"), is("RAISED"));
    assertThat(ret.getString("reason"), is("DAMAGED"));
    assertThat(ret.getString("storeId"), is(STORE_A));
    assertThat(ret.getString("currency"), is("GBP"));
    // 3 × 2.50; no VAT rates reachable here, so gross equals net — the same path a tenant with no
    // VAT configured takes.
    assertThat(ret.getJsonNumber("netAmount").bigDecimalValue().toPlainString(), is("7.50"));
    assertThat(ret.getJsonNumber("vatAmount").bigDecimalValue().toPlainString(), is("0.00"));
    assertThat(ret.getJsonNumber("grossAmount").bigDecimalValue().toPlainString(), is("7.50"));
    assertThat(ret.getString("debitNoteNumber"), containsString("DN-"));
    assertThat(ret.getJsonArray("lines").size(), is(1));
    JsonObject line = ret.getJsonArray("lines").getJsonObject(0);
    assertThat(line.getJsonNumber("unitPrice").bigDecimalValue().toPlainString(), is("2.50"));
    assertThat(line.getJsonNumber("lineNet").bigDecimalValue().toPlainString(), is("7.50"));
    assertThat(outboxTypes(ret.getString("id")), containsString("ReturnedToVendor"));

    // The order is untouched — what was received was received — and the progress says what went
    // back.
    assertThat(
        data(call("GET", "/purchase-orders/" + po, null, T, "OWNER")).getString("status"),
        is("RECEIVED"));
    var progress =
        Json.createReader(
                new StringReader(
                    call("GET", "/purchase-orders/" + po + "/progress", null, T, "OWNER")
                        .readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(
        progress.getJsonObject(0).getJsonNumber("qtyReturned").bigDecimalValue().intValue(), is(3));
    assertThat(
        progress.getJsonObject(0).getJsonNumber("qtyReceived").bigDecimalValue().intValue(),
        is(10));

    // The next return takes the next number; more than is left to return is refused.
    Response second = call("POST", "/vendor-returns", rtv(po, "QUALITY", "7"), T, "OWNER");
    assertThat(second.getStatus(), is(201));
    String first = ret.getString("debitNoteNumber");
    String next = data(second).getString("debitNoteNumber");
    assertThat(next, is(not(first)));
    Response over = call("POST", "/vendor-returns", rtv(po, "QUALITY", "1"), T, "OWNER");
    assertThat(over.getStatus(), is(422));
    assertThat(over.readEntity(String.class), containsString("PURCHASE_RTV_OVER_RETURN"));
  }

  private static org.hamcrest.Matcher<String> not(String s) {
    return org.hamcrest.Matchers.not(s);
  }

  @Test
  @DisplayName("A retried raise returns the return already recorded, not a second one")
  void aRetriedRaiseIsTheSameReturn() {
    String po = receivedOrder("10", "10");
    String key = Ids.newId().toString();
    java.util.function.Supplier<jakarta.ws.rs.client.Invocation.Builder> b =
        () ->
            target
                .path("/vendor-returns")
                .request()
                .header("X-Tenant-Id", T)
                .header("X-Roles", "OWNER")
                .header("Idempotency-Key", key);
    Response one = b.get().post(Entity.entity(rtv(po, "DAMAGED", "2"), MediaType.APPLICATION_JSON));
    Response two = b.get().post(Entity.entity(rtv(po, "DAMAGED", "2"), MediaType.APPLICATION_JSON));
    assertThat(one.getStatus(), is(201));
    assertThat(two.getStatus(), is(201));
    assertThat(data(one).getString("id"), is(data(two).getString("id")));
    var progress =
        Json.createReader(
                new StringReader(
                    call("GET", "/purchase-orders/" + po + "/progress", null, T, "OWNER")
                        .readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(
        progress.getJsonObject(0).getJsonNumber("qtyReturned").bigDecimalValue().intValue(), is(2));
  }

  @Test
  @DisplayName("What a return refuses, each by name")
  void whatAReturnRefuses() {
    String po = receivedOrder("10", "6");
    Response r = call("POST", "/vendor-returns", rtv(po, "FELT_LIKE_IT", "1"), T, "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_RTV_REASON_UNKNOWN"));
    r =
        call(
            "POST",
            "/vendor-returns",
            "{\"poId\":\"" + po + "\",\"reason\":\"DAMAGED\",\"lines\":[]}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_RTV_EMPTY"));
    r =
        call(
            "POST",
            "/vendor-returns",
            "{\"poId\":\""
                + po
                + "\",\"reason\":\"DAMAGED\",\"lines\":[{\"variantId\":\""
                + OTHER_VARIANT
                + "\",\"qty\":1}]}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(422));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_RTV_NOT_ON_ORDER"));
    r = call("POST", "/vendor-returns", rtv(po, "DAMAGED", "7"), T, "OWNER");
    assertThat(r.getStatus(), is(422));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_RTV_OVER_RETURN"));
    r = call("POST", "/vendor-returns", rtv(po, "DAMAGED", "0"), T, "OWNER");
    assertThat(r.getStatus(), is(400));
    // A partly received order takes a return up to what arrived.
    assertThat(
        call("POST", "/vendor-returns", rtv(po, "OVER_DELIVERED", "6"), T, "OWNER").getStatus(),
        is(201));

    String submitted = receivedOrder("10", null);
    r = call("POST", "/vendor-returns", rtv(submitted, "DAMAGED", "1"), T, "OWNER");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_RTV_NOTHING_RECEIVED"));
    r = call("POST", "/vendor-returns", rtv(Ids.newId().toString(), "DAMAGED", "1"), T, "OWNER");
    assertThat(r.getStatus(), is(404));
  }

  @Test
  @DisplayName("Warehouse and management send goods back; the till does not; a rival finds nothing")
  void whoMaySendGoodsBack() {
    String po = receivedOrder("10", "10");
    assertThat(
        call("POST", "/vendor-returns", rtv(po, "DAMAGED", "1"), T, "CASHIER").getStatus(),
        is(403));
    assertThat(
        call("POST", "/vendor-returns", rtv(po, "DAMAGED", "1"), T, "CUSTOMER").getStatus(),
        is(403));
    assertThat(
        call("POST", "/vendor-returns", rtv(po, "DAMAGED", "1"), OTHER_T, "OWNER").getStatus(),
        is(404));
    Response ok = call("POST", "/vendor-returns", rtv(po, "DAMAGED", "1"), T, "STOREKEEPER");
    assertThat(ok.getStatus(), is(201));
    String id = data(ok).getString("id");
    assertThat(call("GET", "/vendor-returns/" + id, null, T, "CASHIER").getStatus(), is(200));
    assertThat(call("GET", "/vendor-returns/" + id, null, OTHER_T, "OWNER").getStatus(), is(404));
    assertThat(call("GET", "/vendor-returns/" + id, null, T, "CUSTOMER").getStatus(), is(403));
    var mine =
        Json.createReader(
                new StringReader(
                    call("GET", "/vendor-returns?poId=" + po, null, T, "OWNER")
                        .readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(mine.size(), is(1));
    assertThat(
        call("GET", "/vendor-returns?poId=" + po, null, OTHER_T, "OWNER").getStatus(), is(404));
  }

  @Test
  @DisplayName("A return offsets the invoice; it does not reopen or move the order")
  void aReturnLeavesTheOrdersOwnStatusAlone() {
    String po = receivedOrder("10", "10");
    assertThat(
        data(call("GET", "/purchase-orders/" + po, null, T, "OWNER")).getString("status"),
        is("RECEIVED"));
    assertThat(
        call("POST", "/vendor-returns", rtv(po, "DAMAGED", "4"), T, "OWNER").getStatus(), is(201));
    assertThat(
        data(call("GET", "/purchase-orders/" + po, null, T, "OWNER")).getString("status"),
        is("RECEIVED"));
    // A partly received order stays as it was too, and its return needs no answer from
    // inventory-svc (none is running here): the order's own ceiling decides.
    String part = receivedOrder("10", "6");
    assertThat(
        call("POST", "/vendor-returns", rtv(part, "DAMAGED", "2"), T, "STOREKEEPER").getStatus(),
        is(201));
    assertThat(
        data(call("GET", "/purchase-orders/" + part, null, T, "OWNER")).getString("status"),
        is("PARTIALLY_RECEIVED"));
  }

  @Test
  @DisplayName("The supplier's credit note closes the return, once, and only management records it")
  void theCreditNoteClosesTheReturnOnce() {
    String po = receivedOrder("10", "10");
    String id =
        data(call("POST", "/vendor-returns", rtv(po, "DAMAGED", "4"), T, "OWNER")).getString("id");
    String credit = "{\"creditNoteNumber\":\"CN-77\",\"creditNoteDate\":\"2026-09-20\"}";
    assertThat(
        call("POST", "/vendor-returns/" + id + "/credit", credit, T, "STOREKEEPER").getStatus(),
        is(403));
    assertThat(
        call("POST", "/vendor-returns/" + id + "/credit", credit, T, "CASHIER").getStatus(),
        is(403));
    assertThat(
        call("POST", "/vendor-returns/" + id + "/credit", credit, OTHER_T, "OWNER").getStatus(),
        is(404));
    Response bad =
        call(
            "POST",
            "/vendor-returns/" + id + "/credit",
            "{\"creditNoteNumber\":\"CN-77\",\"creditNoteDate\":\"next week\"}",
            T,
            "MANAGER");
    assertThat(bad.getStatus(), is(400));
    assertThat(
        call(
                "POST",
                "/vendor-returns/" + id + "/credit",
                "{\"creditNoteDate\":\"2026-09-20\"}",
                T,
                "MANAGER")
            .getStatus(),
        is(400));

    Response ok = call("POST", "/vendor-returns/" + id + "/credit", credit, T, "MANAGER");
    assertThat(ok.getStatus(), is(200));
    JsonObject credited = data(ok);
    assertThat(credited.getString("status"), is("CREDITED"));
    assertThat(credited.getString("creditNoteNumber"), is("CN-77"));
    assertThat(credited.getString("creditNoteDate"), is("2026-09-20"));
    // The credit defaults to the debit note's gross: 4 × 2.50.
    assertThat(
        credited.getJsonNumber("creditAmount").bigDecimalValue().toPlainString(), is("10.00"));
    assertThat(credited.getString("creditedAt"), notNullValue());

    Response again =
        call(
            "POST",
            "/vendor-returns/" + id + "/credit",
            "{\"creditNoteNumber\":\"CN-78\",\"creditNoteDate\":\"2026-09-21\"}",
            T,
            "OWNER");
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("CN-77"));
    assertThat(
        data(call("GET", "/vendor-returns/" + id, null, T, "OWNER")).getString("creditNoteNumber"),
        is("CN-77"));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
