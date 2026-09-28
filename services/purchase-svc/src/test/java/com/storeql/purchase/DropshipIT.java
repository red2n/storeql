package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.purchase.messaging.SalesEventHandler;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dropship (readiness review: "Consignment and dropship stock ownership"), the buyer's side: a
 * variant sourced from a supplier per order — stock the business never holds. An arrangement says
 * which supplier at what cost and tells inventory-svc; a confirmed order with such a line raises
 * one draft purchase order per supplier, shipped to the customer; it is never received into stock,
 * and is delivered instead. Written before the code.
 */
@HelidonTest
class DropshipIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB");
  }

  private static final String T = PurchaseFixtures.T;
  private static final String T2 = PurchaseFixtures.T2;
  private static final String STORE = PurchaseFixtures.STORE_A;
  private static final String VARIANT = PurchaseFixtures.VARIANT;
  private static final String STOCKED = "01a090ae-611e-705c-994c-5daee3fbd0e2";
  private static final String USER = PurchaseFixtures.USER;

  @Inject WebTarget target;
  @Inject SalesEventHandler sales;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  private Response call(String method, String path, String json, String tenant, String roles) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles);
    return "GET".equals(method) ? b.get() : b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "OWNER");
  }

  private Response get(String path) {
    return call("GET", path, null, T, "OWNER");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private String supplier(String name) {
    return Envelopes.created(
            post(
                "/suppliers",
                "{\"name\":\"" + name + "\",\"vatRegistered\":true,\"currency\":\"GBP\"}"))
        .getString("id");
  }

  private static String arrangement(String variant, String supplierId, String cost) {
    return "{\"variantId\":\""
        + variant
        + "\",\"supplierId\":\""
        + supplierId
        + "\",\"unitCost\":"
        + cost
        + "}";
  }

  private static String confirmed(String eventId, String tenant, String orderId, String lines) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
        + tenant
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + STORE
        + "\",\"channel\":\"ONLINE\",\"customerId\":null,\"total\":27.00,\"taxAmount\":4.50,"
        + "\"currency\":\"GBP\",\"lines\":["
        + lines
        + "],\"fulfilmentType\":\"DELIVERY\",\"deliveryAddress\":\"12 High Street, Leeds, LS1 1AA\","
        + "\"deliveryRecipientName\":\"Chris Carter\",\"deliveryRecipientPhone\":\"07700900123\"}";
  }

  private static String line(String variant, int qty, String price) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"unitPrice\":"
        + price
        + ",\"lineTotal\":"
        + price
        + "}";
  }

  private List<JsonObject> dropshipOrders() {
    List<JsonObject> out = new ArrayList<>();
    for (JsonValue v : Envelopes.okArray(get("/purchase-orders?limit=100"))) {
      if ("DROPSHIP".equals(v.asJsonObject().getString("source", ""))) out.add(v.asJsonObject());
    }
    return out;
  }

  /** How people see an id: "#" and its last eight characters, as every screen shows it. */
  private static String handle(String id) {
    return "#" + id.substring(id.length() - 8);
  }

  private static JsonObject trialBalanceRow(JsonObject tb, String code) {
    for (JsonValue v : tb.getJsonArray("rows")) {
      if (code.equals(v.asJsonObject().getString("nominalCode"))) return v.asJsonObject();
    }
    return null;
  }

  @Test
  void anArrangementSourcesAVariantAndAConfirmedOrderRaisesTheSuppliersDraftOnce() {
    String supplierId = supplier("Drop & Ship Ltd");
    JsonObject made =
        Envelopes.created(
            post("/admin/dropship/arrangements", arrangement(VARIANT, supplierId, "4.00")));
    assertThat(made.getString("variantId"), is(VARIANT));
    assertThat(made.getString("supplierId"), is(supplierId));
    assertThat(
        made.getJsonNumber("unitCost").bigDecimalValue(), comparesEqualTo(new BigDecimal("4.00")));
    assertThat(made.getBoolean("active"), is(true));
    // inventory-svc is told the variant is now the supplier's to fulfil.
    String announced =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM purchase.outbox"
                + " WHERE event_type = 'VariantSourcingChanged'");
    assertThat(announced, containsString("\"fulfilment\":\"DROPSHIP\""));
    assertThat(announced, containsString("\"supplierId\":\"" + supplierId + "\""));
    assertThat(announced, containsString("\"variantId\":\"" + VARIANT + "\""));
    // One live arrangement per variant; a supplier nobody has; refused by role.
    assertThat(
        code(post("/admin/dropship/arrangements", arrangement(VARIANT, supplierId, "3.50")), 409),
        is("PURCHASE_DROPSHIP_ARRANGEMENT_EXISTS"));
    assertThat(
        code(
            post(
                "/admin/dropship/arrangements",
                arrangement(STOCKED, Ids.newId().toString(), "3.50")),
            404),
        is("PURCHASE_SUPPLIER_NOT_FOUND"));
    assertThat(
        call(
                "POST",
                "/admin/dropship/arrangements",
                arrangement(STOCKED, supplierId, "1"),
                T,
                "CASHIER")
            .getStatus(),
        is(403));
    assertThat(Envelopes.okArray(get("/admin/dropship/arrangements")).size(), is(1));
    assertThat(
        Envelopes.okArray(call("GET", "/admin/dropship/arrangements", null, T2, "OWNER")).size(),
        is(0));

    // A confirmed order with a dropship line and a stocked one: one draft, for the supplier,
    // shipped to the customer, at the arrangement's cost — and once, however often it is told.
    String orderId = Ids.newId().toString();
    String eventId = Ids.newId().toString();
    String event =
        confirmed(eventId, T, orderId, line(VARIANT, 2, "9.00") + "," + line(STOCKED, 1, "9.00"));
    sales.orderConfirmed(event);
    sales.orderConfirmed(event);
    List<JsonObject> drafts = dropshipOrders();
    assertThat(drafts.size(), is(1));
    JsonObject po = drafts.get(0);
    assertThat(po.getString("status"), is("DRAFT"));
    assertThat(po.getString("supplierId"), is(supplierId));
    assertThat(po.getString("salesOrderId"), is(orderId));
    assertThat(po.getString("shipTo"), containsString("Chris Carter"));
    assertThat(po.getString("shipTo"), containsString("12 High Street, Leeds, LS1 1AA"));
    assertThat(po.getString("shipTo"), containsString("07700900123"));
    assertThat(
        po.getJsonNumber("totalNet").bigDecimalValue(), comparesEqualTo(new BigDecimal("8.00")));
    JsonArray lines = Envelopes.okArray(get("/purchase-orders/" + po.getString("id") + "/lines"));
    assertThat(lines.size(), is(1));
    assertThat(lines.getJsonObject(0).getString("variantId"), is(VARIANT));
    assertThat(
        lines.getJsonObject(0).getJsonNumber("qty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("2")));
    assertThat(
        lines.getJsonObject(0).getJsonNumber("unitPrice").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("4.00")));
    assertThat(
        lines.getJsonObject(0).getString("proposalReason"),
        is("dropship for sale " + handle(orderId) + ", shipped to the customer"));
    // An order with no dropship line raises nothing.
    sales.orderConfirmed(
        confirmed(Ids.newId().toString(), T, Ids.newId().toString(), line(STOCKED, 3, "9.00")));
    assertThat(dropshipOrders().size(), is(1));

    // Submitted to the supplier; never received into stock; delivered to the customer instead.
    String poId = po.getString("id");
    assertThat(
        Envelopes.ok(post("/purchase-orders/" + poId + "/submit", "{}")).getString("status"),
        is("SUBMITTED"));
    assertThat(
        code(post("/goods-receipts", PurchaseFixtures.receiptJson(poId, 2)), 409),
        is("PURCHASE_DROPSHIP_NOT_RECEIVED"));
    JsonObject delivered =
        Envelopes.ok(post("/purchase-orders/" + poId + "/dropship-delivered", "{}"));
    assertThat(delivered.getString("status"), is("RECEIVED"));
    // The cost of goods the business never held, against what the supplier will invoice.
    JsonObject tb = Envelopes.ok(get("/nominal-ledger/trial-balance"));
    assertThat(
        trialBalanceRow(tb, "5020").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("8.00")));
    assertThat(
        trialBalanceRow(tb, "2109").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("-8.00")));
    assertThat(trialBalanceRow(tb, "1001") == null, is(true));
    // Read in the accounting package and on the Integrations screen: the order and the sale it
    // filled named as people see them ("PO #…", "sale #…"), never by a whole id.
    JsonArray delivery = Envelopes.okArray(get("/nominal-ledger?code=5020"));
    assertThat(delivery.size(), is(1));
    String described = delivery.getJsonObject(0).getString("description");
    assertThat(
        described,
        is(
            "Dropship PO "
                + handle(poId)
                + " for sale "
                + handle(orderId)
                + " delivered to the customer"));
    assertThat(described, not(containsString(poId)));
    assertThat(described, not(containsString(orderId)));
    assertThat(delivery.getJsonObject(0).getString("sourceRef"), is(poId));
    assertThat(
        code(post("/purchase-orders/" + poId + "/dropship-delivered", "{}"), 409),
        is("PURCHASE_PO_NOT_DELIVERABLE"));

    // Ended: inventory-svc is told the variant is stocked again, and the next order raises nothing.
    JsonObject ended =
        Envelopes.ok(post("/admin/dropship/arrangements/" + made.getString("id") + "/end", "{}"));
    assertThat(ended.getBoolean("active"), is(false));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM purchase.outbox"
                + " WHERE event_type = 'VariantSourcingChanged'"),
        containsString("\"fulfilment\":\"STOCK\""));
    sales.orderConfirmed(
        confirmed(Ids.newId().toString(), T, Ids.newId().toString(), line(VARIANT, 1, "9.00")));
    assertThat(dropshipOrders().size(), is(1));
  }

  @Test
  void anOrdinaryOrderIsNotDeliverableAsDropship() {
    String supplierId = supplier("Bricks Ltd");
    String poId =
        Envelopes.created(post("/purchase-orders", PurchaseFixtures.orderJson(supplierId)))
            .getString("id");
    assertThat(
        post("/purchase-orders/" + poId + "/lines", PurchaseFixtures.lineJson(1, "2.00"))
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}").getStatus(), is(200));
    assertThat(
        code(post("/purchase-orders/" + poId + "/dropship-delivered", "{}"), 409),
        is("PURCHASE_PO_NOT_DELIVERABLE"));
    assertThat(
        code(post("/purchase-orders/" + Ids.newId() + "/dropship-delivered", "{}"), 404),
        is("PURCHASE_PO_NOT_FOUND"));
  }
}
