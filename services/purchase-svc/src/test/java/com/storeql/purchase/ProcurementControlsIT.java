package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Procurement controls found by the flow catalogue: who may add a supplier, a draft order's lines
 * changed and removed, the refusals of a goods receipt and a supplier invoice by role and tenant.
 * Cross-dock allocations on amended lines are proved in {@code CrossDockIT}.
 */
@HelidonTest
class ProcurementControlsIT {

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
  private static final String VARIANT_B = "01a090ae-611e-705c-994c-5daee3fbd034";
  private static final String USER = PurchaseFixtures.USER;
  private static final String BANK =
      "\"bankAccountName\":\"Acme Ltd\",\"bankSortCode\":\"12-34-56\",\"bankAccountNumber\":\"31415926\"";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant, String roles) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles)
            .header("Idempotency-Key", Ids.newId().toString());
    return switch (method) {
      case "GET" -> b.get();
      case "DELETE" -> b.delete();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
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

  private static BigDecimal num(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue();
  }

  private String supplier(String name) {
    return Envelopes.created(post("/suppliers", "{\"name\":\"" + name + "\",\"currency\":\"GBP\"}"))
        .getString("id");
  }

  private String draftOrder(String supplierId) {
    return Envelopes.created(post("/purchase-orders", PurchaseFixtures.orderJson(supplierId)))
        .getString("id");
  }

  private String addLine(String po, String variant, int qty, String price) {
    return Envelopes.created(
            post(
                "/purchase-orders/" + po + "/lines",
                "{\"variantId\":\""
                    + variant
                    + "\",\"qty\":"
                    + qty
                    + ",\"unitPrice\":"
                    + price
                    + ",\"vatCode\":\"T1\"}"))
        .getString("id");
  }

  private BigDecimal totalNet(String po) {
    return num(Envelopes.ok(get("/purchase-orders/" + po)), "totalNet");
  }

  private int suppliers() {
    return Envelopes.okArray(get("/suppliers")).size();
  }

  // ── supplier master data: who may add one ──────────────────────────────────

  @Test
  @DisplayName("Adding a supplier is warehouse and management work; the till is refused")
  void aTillRoleCannotAddASupplier() {
    // rules: supplier-master-data gap 3; case PO-05
    String body = "{\"name\":\"Fictitious Ltd\"}";
    assertThat(call("POST", "/suppliers", body, T, "CASHIER").getStatus(), is(403));
    assertThat(call("POST", "/suppliers", body, T, "CUSTOMER").getStatus(), is(403));
    assertThat(call("POST", "/suppliers", body, T2, "CASHIER").getStatus(), is(403));
    assertThat(suppliers(), is(0));

    assertThat(
        call("POST", "/suppliers", "{\"name\":\"By Store\"}", T, "STOREKEEPER").getStatus(),
        is(201));
    assertThat(
        call("POST", "/suppliers", "{\"name\":\"By Manager\"}", T, "MANAGER").getStatus(), is(201));
    assertThat(
        call("POST", "/suppliers", "{\"name\":\"By Owner\"}", T, "OWNER").getStatus(), is(201));
    assertThat(suppliers(), is(3));

    // Where the money goes stays a finance decision, whoever may add the supplier.
    assertThat(
        code(
            call("POST", "/suppliers", "{\"name\":\"Banked\"," + BANK + "}", T, "STOREKEEPER"),
            403),
        is("PERMISSION_DENIED"));
    assertThat(suppliers(), is(3));
    // Correcting one stays with management (SJ-D34).
    String id = supplier("To Correct");
    assertThat(
        call("PUT", "/suppliers/" + id, "{\"name\":\"To Correct 2\"}", T, "STOREKEEPER")
            .getStatus(),
        is(403));
    // Another business sees none of them.
    assertThat(Envelopes.okArray(call("GET", "/suppliers", null, T2, "OWNER")).size(), is(0));
  }

  @Test
  @DisplayName("A till role cannot capture or resolve a supplier invoice")
  void aTillRoleCannotCaptureOrResolveASupplierInvoice() {
    // case INVC-16
    String po = draftOrder(supplier("Invoiced Ltd"));
    String capture =
        PurchaseFixtures.invoiceJson(po, "INV-9", java.time.LocalDate.now(), 1, "1.00", "0.20");
    assertThat(call("POST", "/supplier-invoices", capture, T, "CASHIER").getStatus(), is(403));
    assertThat(call("POST", "/supplier-invoices", capture, T, "CUSTOMER").getStatus(), is(403));
    assertThat(
        call(
                "POST",
                "/supplier-invoices/" + Ids.newId() + "/resolve",
                "{\"action\":\"APPROVE\",\"reason\":\"fine\"}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));
    assertThat(Envelopes.okArray(get("/supplier-invoices")).size(), is(0));
  }

  // ── a draft order's lines can be changed and removed ───────────────────────

  @Test
  void aDraftOrdersLinesAreChangedAndRemovedAndTheTotalsFollow() {
    String po = draftOrder(supplier("Lines Ltd"));
    String first = addLine(po, VARIANT, 10, "20.00");
    String second = addLine(po, VARIANT_B, 5, "4.00");
    assertThat(totalNet(po), comparesEqualTo(new BigDecimal("220.00")));

    // Quantity and price change together; the order restates itself from its lines.
    JsonObject amended =
        Envelopes.ok(
            call(
                "PUT",
                "/purchase-orders/" + po + "/lines/" + first,
                "{\"qty\":8,\"unitPrice\":30.00}",
                T,
                "STOREKEEPER"));
    assertThat(num(amended, "qty"), comparesEqualTo(new BigDecimal("8")));
    assertThat(num(amended, "unitPrice"), comparesEqualTo(new BigDecimal("30.00")));
    assertThat(amended.getString("vatCode"), is("T1"));
    assertThat(totalNet(po), comparesEqualTo(new BigDecimal("260.00")));

    // Refused by name: no quantity, a line of another order, a line nobody has.
    assertThat(
        call(
                "PUT",
                "/purchase-orders/" + po + "/lines/" + first,
                "{\"qty\":0,\"unitPrice\":1}",
                T,
                "OWNER")
            .getStatus(),
        is(400));
    String other = draftOrder(supplier("Other Ltd"));
    String otherLine = addLine(other, VARIANT, 1, "1.00");
    assertThat(
        code(
            call(
                "PUT",
                "/purchase-orders/" + po + "/lines/" + otherLine,
                "{\"qty\":99,\"unitPrice\":99.00}",
                T,
                "OWNER"),
            404),
        is("PURCHASE_LINE_NOT_FOUND"));
    assertThat(
        code(
            call("DELETE", "/purchase-orders/" + po + "/lines/" + otherLine, null, T, "OWNER"),
            404),
        is("PURCHASE_LINE_NOT_FOUND"));
    assertThat(
        code(
            call(
                "PUT",
                "/purchase-orders/" + po + "/lines/" + Ids.newId(),
                "{\"qty\":1,\"unitPrice\":1}",
                T,
                "OWNER"),
            404),
        is("PURCHASE_LINE_NOT_FOUND"));
    // The other order's line is untouched.
    assertThat(totalNet(other), comparesEqualTo(new BigDecimal("1.00")));

    // A line goes; the total follows; going twice is a 404.
    assertThat(
        call("DELETE", "/purchase-orders/" + po + "/lines/" + second, null, T, "MANAGER")
            .getStatus(),
        is(204));
    assertThat(totalNet(po), comparesEqualTo(new BigDecimal("240.00")));
    JsonArray left = Envelopes.okArray(get("/purchase-orders/" + po + "/lines"));
    assertThat(left.size(), is(1));
    assertThat(left.getJsonObject(0).getString("id"), is(first));
    assertThat(
        code(call("DELETE", "/purchase-orders/" + po + "/lines/" + second, null, T, "OWNER"), 404),
        is("PURCHASE_LINE_NOT_FOUND"));
    assertThat(
        code(
            call("DELETE", "/purchase-orders/" + Ids.newId() + "/lines/" + first, null, T, "OWNER"),
            404),
        is("PURCHASE_PO_NOT_FOUND"));

    // Another business's staff of every role move nothing; a shopper is refused.
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(
          role,
          code(
              call(
                  "PUT",
                  "/purchase-orders/" + po + "/lines/" + first,
                  "{\"qty\":1,\"unitPrice\":1.00}",
                  T2,
                  role),
              404),
          is("PURCHASE_PO_NOT_FOUND"));
      assertThat(
          role,
          code(call("DELETE", "/purchase-orders/" + po + "/lines/" + first, null, T2, role), 404),
          is("PURCHASE_PO_NOT_FOUND"));
    }
    assertThat(
        call("DELETE", "/purchase-orders/" + po + "/lines/" + first, null, T, "CUSTOMER")
            .getStatus(),
        is(403));
    assertThat(totalNet(po), comparesEqualTo(new BigDecimal("240.00")));

    // Once submitted the order is the supplier's: adding, changing and removing are all refused
    // (case PO-22 for adding), and the figures stay.
    assertThat(post("/purchase-orders/" + po + "/submit", "{}").getStatus(), is(200));
    assertThat(
        code(post("/purchase-orders/" + po + "/lines", PurchaseFixtures.lineJson(1, "1.00")), 400),
        is("PURCHASE_PO_NOT_DRAFT"));
    assertThat(
        code(
            call(
                "PUT",
                "/purchase-orders/" + po + "/lines/" + first,
                "{\"qty\":1,\"unitPrice\":1.00}",
                T,
                "OWNER"),
            400),
        is("PURCHASE_PO_NOT_DRAFT"));
    assertThat(
        code(call("DELETE", "/purchase-orders/" + po + "/lines/" + first, null, T, "OWNER"), 400),
        is("PURCHASE_PO_NOT_DRAFT"));
    assertThat(totalNet(po), comparesEqualTo(new BigDecimal("240.00")));
    assertThat(Envelopes.okArray(get("/purchase-orders/" + po + "/lines")).size(), is(1));
  }

  // ── goods receipts: refusals by name, and another business ─────────────────

  @Test
  void aReceiptIsRefusedByNameAndAnotherBusinessesOrderIsNotThere() {
    String po = draftOrder(supplier("Receiving Ltd"));
    addLine(po, VARIANT, 10, "2.00");
    // GRN-06: a draft order cannot be received.
    assertThat(
        code(post("/goods-receipts", PurchaseFixtures.receiptJson(po, 1)), 400),
        is("PURCHASE_PO_NOT_RECEIVABLE"));
    assertThat(post("/purchase-orders/" + po + "/submit", "{}").getStatus(), is(200));
    // GRN-08: a receipt with no lines.
    assertThat(
        code(
            post(
                "/goods-receipts",
                "{\"poId\":\"" + po + "\",\"storeId\":\"" + STORE + "\",\"lines\":[]}"),
            400),
        is("PURCHASE_GRN_EMPTY"));
    // GRN-12: listing needs the order.
    assertThat(code(get("/goods-receipts"), 400), is("PURCHASE_MISSING_PO_ID"));
    // GRN-11: another business's staff of every role receive nothing against our order.
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER"}) {
      assertThat(
          role,
          code(
              call("POST", "/goods-receipts", PurchaseFixtures.receiptJson(po, 10), T2, role), 404),
          is("PURCHASE_PO_NOT_FOUND"));
      assertThat(
          role, call("GET", "/goods-receipts?poId=" + po, null, T2, role).getStatus(), is(404));
    }
    assertThat(Envelopes.okArray(get("/goods-receipts?poId=" + po)).size(), is(0));
    assertThat(Envelopes.ok(get("/purchase-orders/" + po)).getString("status"), is("SUBMITTED"));
  }
}
