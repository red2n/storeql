package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.purchase.messaging.ConsignmentEventHandler;
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
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Consignment stock (readiness review: "Consignment and dropship stock ownership"), the buyer's
 * side: a purchase order on consignment is received without the goods becoming the business's stock
 * or a liability; each sale inventory-svc announces is owed to the supplier, once, at the order's
 * price; a settlement gathers the unsettled sales of a period into a statement, once. Written
 * before the code.
 */
@HelidonTest
class ConsignmentIT {

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
  private static final String USER = PurchaseFixtures.USER;

  @Inject WebTarget target;
  @Inject ConsignmentEventHandler handler;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
    Envelopes.exec(
        PG, "TRUNCATE TABLE purchase.consignment_sales, purchase.consignment_settlements CASCADE");
  }

  // ── harness ────────────────────────────────────────────────────────────────

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

  private JsonObject order(String supplierId, String ownershipJson) {
    return Envelopes.created(
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + supplierId
                + "\",\"storeId\":\""
                + STORE
                + "\",\"currency\":\"GBP\""
                + ownershipJson
                + "}"));
  }

  private String submittedOrder(String supplierId, String ownershipJson, int qty, String price) {
    String poId = order(supplierId, ownershipJson).getString("id");
    assertThat(
        post("/purchase-orders/" + poId + "/lines", PurchaseFixtures.lineJson(qty, price))
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}").getStatus(), is(200));
    return poId;
  }

  private static JsonObject trialBalanceRow(JsonObject tb, String code) {
    for (JsonValue v : tb.getJsonArray("rows")) {
      if (code.equals(v.asJsonObject().getString("nominalCode"))) return v.asJsonObject();
    }
    return null;
  }

  private JsonObject trialBalance() {
    return Envelopes.ok(get("/nominal-ledger/trial-balance"));
  }

  private static String goodsReceivedPayload() {
    return Envelopes.scalar(
        PG,
        "SELECT string_agg(payload, '|') FROM purchase.outbox WHERE event_type = 'GoodsReceived'");
  }

  private static String sold(
      String eventId, String tenant, String supplierId, int qty, String unitCost) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"ConsignmentStockSold\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + Ids.newId()
        + "\",\"occurredAt\":\"2026-09-24T10:00:00Z\",\"storeId\":\""
        + STORE
        + "\",\"variantId\":\""
        + VARIANT
        + "\",\"batchId\":\""
        + Ids.newId()
        + "\",\"supplierId\":\""
        + supplierId
        + "\",\"orderId\":\""
        + Ids.newId()
        + "\",\"qty\":"
        + qty
        + ",\"unitCost\":"
        + unitCost
        + "}";
  }

  // ── received without becoming ours ─────────────────────────────────────────

  @Test
  void aConsignmentOrderIsReceivedWithoutBecomingOurStockOrOurDebt() {
    String supplierId = supplier("Sale or Return Ltd");
    JsonObject po = order(supplierId, ",\"ownership\":\"consignment\"");
    assertThat(po.getString("ownership"), is("CONSIGNMENT"));
    assertThat(order(supplierId, "").getString("ownership"), is("OWNED"));

    String consigned = submittedOrder(supplierId, ",\"ownership\":\"CONSIGNMENT\"", 5, "3.00");
    assertThat(
        post("/goods-receipts", PurchaseFixtures.receiptJson(consigned, 5)).getStatus(), is(201));
    // inventory-svc is told whose the stock is.
    String payload = goodsReceivedPayload();
    assertThat(payload, containsString("\"ownership\":\"CONSIGNMENT\""));
    assertThat(payload, containsString("\"supplierId\":\"" + supplierId + "\""));
    assertThat(payload, containsString("\"costPrice\":3.00"));
    // Nothing on the books: no stock asset, nothing owed until it sells.
    JsonObject tb = trialBalance();
    assertThat(tb.getJsonNumber("totalDebit").bigDecimalValue().signum(), is(0));

    // The business's own order, for contrast, is an asset and a liability the moment it lands.
    String owned = submittedOrder(supplierId, "", 4, "2.50");
    assertThat(
        post("/goods-receipts", PurchaseFixtures.receiptJson(owned, 4)).getStatus(), is(201));
    tb = trialBalance();
    assertThat(
        trialBalanceRow(tb, "1001").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("10.00")));
    assertThat(
        trialBalanceRow(tb, "2109").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("-10.00")));

    // A consignment order is settled on its sales, not invoiced on receipt.
    assertThat(
        code(
            post(
                "/supplier-invoices",
                PurchaseFixtures.invoiceJson(consigned, "INV-C1", LocalDate.now(), 5, "3.00", "0")),
            409),
        is("PURCHASE_CONSIGNMENT_NOT_INVOICED"));
    // An ownership nobody defined.
    assertThat(
        code(
            post(
                "/purchase-orders",
                "{\"supplierId\":\""
                    + supplierId
                    + "\",\"storeId\":\""
                    + STORE
                    + "\",\"currency\":\"GBP\",\"ownership\":\"BORROWED\"}"),
            400),
        is("PURCHASE_OWNERSHIP_INVALID"));
  }

  // ── owed as it sells, settled once ─────────────────────────────────────────

  @Test
  void aConsignmentSaleIsOwedToTheSupplierOnceAndSettledOnce() {
    String supplierId = supplier("Sale or Return Ltd");
    String eventId = Ids.newId().toString();
    handler.stockSold(sold(eventId, T, supplierId, 3, "3.00"));
    handler.stockSold(sold(eventId, T, supplierId, 3, "3.00"));
    // A rival business's supplier is not ours; a sale for a supplier nobody has is not recorded.
    handler.stockSold(sold(Ids.newId().toString(), T2, supplierId, 1, "3.00"));

    JsonArray sales = Envelopes.okArray(get("/admin/consignment/sales?supplierId=" + supplierId));
    assertThat(sales.size(), is(1));
    JsonObject sale = sales.getJsonObject(0);
    assertThat(sale.getJsonNumber("qty").bigDecimalValue(), comparesEqualTo(new BigDecimal("3")));
    assertThat(
        sale.getJsonNumber("unitCost").bigDecimalValue(), comparesEqualTo(new BigDecimal("3.00")));
    assertThat(
        sale.getJsonNumber("amount").bigDecimalValue(), comparesEqualTo(new BigDecimal("9.00")));
    assertThat(sale.getString("currency"), is("GBP"));
    assertThat(sale.getBoolean("settled"), is(false));
    assertThat(sale.getString("variantId"), is(VARIANT));
    // The liability arises at the sale: cost of sales against the supplier, nothing through stock.
    JsonObject tb = trialBalance();
    assertThat(
        trialBalanceRow(tb, "5010").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("9.00")));
    assertThat(
        trialBalanceRow(tb, "2100").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("-9.00")));
    assertThat(trialBalanceRow(tb, "1001"), is((JsonObject) null));

    // A second sale, then the statement for the period.
    handler.stockSold(sold(Ids.newId().toString(), T, supplierId, 2, "3.00"));
    JsonObject settlement =
        Envelopes.created(
            post(
                "/admin/consignment/settlements",
                "{\"supplierId\":\""
                    + supplierId
                    + "\",\"from\":\"2026-01-01\",\"to\":\""
                    + LocalDate.now()
                    + "\"}"));
    assertThat(
        settlement.getJsonNumber("total").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("15.00")));
    assertThat(settlement.getInt("salesCount"), is(2));
    assertThat(settlement.getString("currency"), is("GBP"));
    assertThat(settlement.getString("reference"), containsString("CS-"));
    JsonObject read =
        Envelopes.ok(get("/admin/consignment/settlements/" + settlement.getString("id")));
    assertThat(read.getJsonArray("sales").size(), is(2));
    assertThat(
        Envelopes.okArray(
                get("/admin/consignment/sales?supplierId=" + supplierId + "&settled=false"))
            .size(),
        is(0));
    assertThat(
        Envelopes.okArray(get("/admin/consignment/settlements?supplierId=" + supplierId)).size(),
        is(1));
    // Settled once: the same period again has nothing left.
    assertThat(
        code(
            post(
                "/admin/consignment/settlements",
                "{\"supplierId\":\""
                    + supplierId
                    + "\",\"from\":\"2026-01-01\",\"to\":\""
                    + LocalDate.now()
                    + "\"}"),
            409),
        is("PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE"));
    // Refused by name and by role.
    assertThat(
        code(
            post(
                "/admin/consignment/settlements",
                "{\"supplierId\":\""
                    + supplierId
                    + "\",\"from\":\"2026-02-01\",\"to\":\"2026-01-01\"}"),
            400),
        is("PURCHASE_CONSIGNMENT_PERIOD_INVALID"));
    assertThat(
        code(
            post(
                "/admin/consignment/settlements",
                "{\"supplierId\":\""
                    + Ids.newId()
                    + "\",\"from\":\"2026-01-01\",\"to\":\"2026-01-31\"}"),
            404),
        is("PURCHASE_SUPPLIER_NOT_FOUND"));
    assertThat(
        call("POST", "/admin/consignment/settlements", "{}", T, "CASHIER").getStatus(), is(403));
    assertThat(
        Envelopes.okArray(call("GET", "/admin/consignment/sales", null, T2, "OWNER")).size(),
        is(0));
  }
}
