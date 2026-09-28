package com.storeql.inventory.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Consignment stock ownership (readiness review: "Consignment and dropship stock ownership").
 *
 * <p>Stock on the shelf that the supplier still owns: received as such (by hand or from a
 * consignment purchase order), valued apart from the business's own holding, sold like any other
 * stock, and each sale announced to purchase-svc, which owes the supplier for it. Written before
 * the code.
 */
@HelidonTest
class ConsignmentIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478c1";
  private static final String S = "01a090ae-611e-703c-a378-a4972ea461c1";
  private static final String OWNED_V = "01a090ae-611e-7037-a4b7-c854f0266ac1";
  private static final String CONSIGNED_V = "01a090ae-611e-7037-a4b7-c854f0266ac2";
  private static final String SUPPLIER = "01a090ae-611e-7056-8f30-ecdbb48160c1";
  private static final String ORDER = "01a090ae-611e-705c-994c-5daee3fbd0c1";

  @Inject WebTarget target;
  @Inject GoodsReceivedHandler goodsReceived;
  @Inject OrderEventHandler orders;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE inventory.stock_movements, inventory.reservations,"
              + " inventory.sale_revenue, inventory.inventory_batches, inventory.processed_events,"
              + " inventory.outbox CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response post(String path, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String pathAndQuery) {
    return com.storeql.test.WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .get();
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private Response receive(String variant, int qty, String cost, String ownershipJson) {
    return post(
        "/admin/inventory/receive",
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":"
            + qty
            + ",\"batchNo\":\"B-"
            + variant.substring(30)
            + "\",\"costPrice\":"
            + cost
            + ownershipJson
            + "}");
  }

  private static int consignmentSales() {
    return Integer.parseInt(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.outbox WHERE event_type = 'ConsignmentStockSold'"));
  }

  private static String consignmentSalePayloads() {
    return Envelopes.scalar(
        PG,
        "SELECT string_agg(payload, '|') FROM inventory.outbox"
            + " WHERE event_type = 'ConsignmentStockSold'");
  }

  private static String fulfilled(String eventId, String variant, int qty) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + S
        + "\",\"items\":[{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"netAmount\":"
        + (qty * 5)
        + ".00}]}";
  }

  // ── received and valued apart ──────────────────────────────────────────────

  @Test
  void consignmentStockIsReceivedAsTheSuppliersAndValuedApart() {
    JsonObject consigned =
        Envelopes.created(
            receive(
                CONSIGNED_V,
                5,
                "3.00",
                ",\"ownership\":\"consignment\",\"supplierId\":\"" + SUPPLIER + "\""));
    assertThat(consigned.getString("ownership"), is("CONSIGNMENT"));
    assertThat(consigned.getString("ownerSupplierId"), is(SUPPLIER));
    JsonObject owned = Envelopes.created(receive(OWNED_V, 10, "2.00", ""));
    assertThat(owned.getString("ownership"), is("OWNED"));
    assertThat(owned.containsKey("ownerSupplierId") && !owned.isNull("ownerSupplierId"), is(false));

    // The batch list says whose it is.
    JsonArray batches =
        Envelopes.okArray(get("/admin/inventory/batches?store=" + S + "&variant=" + CONSIGNED_V));
    assertThat(batches.size(), is(1));
    assertThat(batches.getJsonObject(0).getString("ownership"), is("CONSIGNMENT"));

    // Valued apart: the business's own value excludes what it does not own; the consignment
    // holding is reported beside it, at the cost the supplier will be owed.
    JsonArray rows =
        Envelopes.okArray(get("/admin/inventory/reports/valuation?groupBy=VARIANT&limit=50"));
    JsonObject ownedRow = Envelopes.find(rows, "groupKey", OWNED_V);
    JsonObject consignedRow = Envelopes.find(rows, "groupKey", CONSIGNED_V);
    assertThat(
        ownedRow.getJsonNumber("value").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("20.00")));
    assertThat(ownedRow.getJsonNumber("consignmentQty").bigDecimalValue().signum(), is(0));
    assertThat(
        consignedRow.getJsonNumber("onHandQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("5")));
    assertThat(
        consignedRow.getJsonNumber("consignmentQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("5")));
    assertThat(
        consignedRow.getJsonNumber("consignmentValue").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("15.00")));
    assertThat(consignedRow.getJsonNumber("value").bigDecimalValue().signum(), is(0));
    JsonArray byStore = Envelopes.okArray(get("/admin/inventory/reports/valuation?groupBy=STORE"));
    JsonObject store = Envelopes.find(byStore, "groupKey", S);
    assertThat(
        store.getJsonNumber("value").bigDecimalValue(), comparesEqualTo(new BigDecimal("20.00")));
    assertThat(
        store.getJsonNumber("consignmentValue").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("15.00")));

    // Refused by name: consignment stock without the supplier it belongs to, or an ownership
    // nobody defined.
    assertThat(
        code(receive(CONSIGNED_V, 1, "3.00", ",\"ownership\":\"CONSIGNMENT\""), 400),
        is("INVENTORY_CONSIGNMENT_SUPPLIER_REQUIRED"));
    assertThat(
        code(receive(CONSIGNED_V, 1, "3.00", ",\"ownership\":\"BORROWED\""), 400),
        is("INVENTORY_OWNERSHIP_INVALID"));
  }

  @Test
  void aConsignmentGoodsReceiptMakesAConsignmentBatchOnce() {
    String eventId = Ids.newId().toString();
    String event =
        "{\"eventId\":\""
            + eventId
            + "\",\"tenantId\":\""
            + T
            + "\",\"storeId\":\""
            + S
            + "\",\"refId\":\""
            + Ids.newId()
            + "\",\"poId\":\""
            + Ids.newId()
            + "\",\"ownership\":\"CONSIGNMENT\",\"supplierId\":\""
            + SUPPLIER
            + "\",\"lines\":[{\"variantId\":\""
            + CONSIGNED_V
            + "\",\"qty\":4,\"costPrice\":3.00}]}";
    goodsReceived.handle(event);
    goodsReceived.handle(event);
    JsonArray batches =
        Envelopes.okArray(get("/admin/inventory/batches?store=" + S + "&variant=" + CONSIGNED_V));
    assertThat(batches.size(), is(1));
    JsonObject b = batches.getJsonObject(0);
    assertThat(b.getString("ownership"), is("CONSIGNMENT"));
    assertThat(b.getString("ownerSupplierId"), is(SUPPLIER));
    assertThat(
        b.getJsonNumber("costPrice").bigDecimalValue(), comparesEqualTo(new BigDecimal("3")));
    assertThat(
        b.getJsonNumber("remainingQty").bigDecimalValue(), comparesEqualTo(new BigDecimal("4")));
  }

  // ── sold, and the supplier told ────────────────────────────────────────────

  @Test
  void sellingConsignmentStockTellsTheSupplierOnceForEachDraw() {
    Envelopes.created(
        receive(
            CONSIGNED_V,
            5,
            "3.00",
            ",\"ownership\":\"CONSIGNMENT\",\"supplierId\":\"" + SUPPLIER + "\""));
    Envelopes.created(receive(OWNED_V, 10, "2.00", ""));

    // A till sale of three, fulfilled with no prior hold: drawn from the consignment batch.
    String eventId = Ids.newId().toString();
    orders.handle(fulfilled(eventId, CONSIGNED_V, 3));
    assertThat(consignmentSales(), is(1));
    String payload = consignmentSalePayloads();
    assertThat(payload, containsString("\"eventType\":\"ConsignmentStockSold\""));
    assertThat(payload, containsString("\"supplierId\":\"" + SUPPLIER + "\""));
    assertThat(payload, containsString("\"variantId\":\"" + CONSIGNED_V + "\""));
    assertThat(payload, containsString("\"orderId\":\"" + ORDER + "\""));
    assertThat(payload, containsString("\"qty\":3"));
    assertThat(payload, containsString("\"unitCost\":3.00"));
    assertThat(payload, containsString("\"storeId\":\"" + S + "\""));
    // Redelivered: nothing twice.
    orders.handle(fulfilled(eventId, CONSIGNED_V, 3));
    assertThat(consignmentSales(), is(1));

    // The business's own stock sells without telling anyone.
    orders.handle(fulfilled(Ids.newId().toString(), OWNED_V, 2));
    assertThat(consignmentSales(), is(1));

    // The checkout path — a hold, then its consumption — announces the draw too.
    JsonObject hold =
        Envelopes.created(
            post(
                "/inventory/reservations",
                "{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + CONSIGNED_V
                    + "\",\"qty\":1,\"orderId\":\""
                    + Ids.newId()
                    + "\"}"));
    assertThat(
        post("/inventory/reservations/" + hold.getString("id") + "/consume", "{}").getStatus(),
        is(200));
    assertThat(consignmentSales(), is(2));
    assertThat(consignmentSalePayloads(), containsString("\"qty\":1"));

    // What is left is still the supplier's.
    JsonArray rows =
        Envelopes.okArray(get("/admin/inventory/reports/valuation?groupBy=VARIANT&limit=50"));
    JsonObject consignedRow = Envelopes.find(rows, "groupKey", CONSIGNED_V);
    assertThat(
        consignedRow.getJsonNumber("consignmentQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("1")));
    assertThat(consignedRow.get("value"), org.hamcrest.Matchers.notNullValue());
    assertThat(consignedRow.getJsonNumber("value").bigDecimalValue().signum(), is(0));
    assertThat(
        Envelopes.find(rows, "groupKey", OWNED_V)
            .getJsonNumber("consignmentValue")
            .bigDecimalValue()
            .signum(),
        is(0));
    assertThat(consignedRow.get("nothing"), nullValue());
  }
}
