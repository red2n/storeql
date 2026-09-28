package com.storeql.inventory.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

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
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bonded and duty-suspended stock (readiness review, Inventory &amp; stock control).
 *
 * <p>Excise goods held in an approved warehouse with the duty suspended: received as such only at a
 * bonded store, counted on hand but never available to sell, valued at cost without the duty and
 * carrying the duty they would crystallise, and released to home use in a movement of their own
 * that computes the duty owed and tells purchase-svc. Written before the code.
 */
@HelidonTest
class BondIT {

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
    // The business trades in pounds: the duty a release owes is in its home currency.
    com.storeql.test.TenantSvcStub.start().with(BondIT.T, "GBP", "GB");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String BOND = "01a090ae-611e-703c-a378-a4972ea461e1";
  private static final String SHOP = "01a090ae-611e-703c-a378-a4972ea461e2";
  private static final String WHISKY = "01a090ae-611e-7037-a4b7-c854f0266ae1";
  private static final String UNRATED = "01a090ae-611e-7037-a4b7-c854f0266ae2";
  private static final String ORDER = "01a090ae-611e-705c-994c-5daee3fbd0e1";

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
          "TRUNCATE TABLE inventory.bond_releases, inventory.excise_duty_rates,"
              + " inventory.bond_approvals, inventory.lot_genealogy, inventory.stock_movements,"
              + " inventory.reservations, inventory.sale_revenue, inventory.inventory_batches,"
              + " inventory.processed_events, inventory.outbox CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String roles) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", "01a090ae-611e-700b-bde4-50df0324c3e1")
            .header("X-Roles", roles);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response post(String path, String json) {
    return call("POST", path, json, "OWNER");
  }

  private Response get(String path) {
    return call("GET", path, null, "OWNER");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private Response receive(String store, String variant, int qty, String dutyStatus) {
    return post(
        "/admin/inventory/receive",
        "{\"storeId\":\""
            + store
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":"
            + qty
            + ",\"batchNo\":\"CASK-"
            + qty
            + "\",\"costPrice\":20.00"
            + (dutyStatus == null ? "" : ",\"dutyStatus\":\"" + dutyStatus + "\"")
            + "}");
  }

  private void bondTheWarehouse() {
    JsonObject approval =
        Envelopes.ok(
            call(
                "PUT",
                "/admin/inventory/bond/approvals/" + BOND,
                "{\"approvalNumber\":\"GBWK123456789\",\"regime\":\"EXCISE\"}",
                "OWNER"));
    assertThat(approval.getString("storeId"), is(BOND));
    assertThat(approval.getString("approvalNumber"), is("GBWK123456789"));
    assertThat(approval.getBoolean("active"), is(true));
  }

  private void rateTheWhisky() {
    JsonObject rate =
        Envelopes.ok(
            call(
                "PUT",
                "/admin/inventory/bond/duty-rates/" + WHISKY,
                "{\"dutyPerUnit\":2.50,\"note\":\"70cl at 40%: 0.28 lpa x GBP 31.64 per lpa\"}",
                "OWNER"));
    assertThat(
        rate.getJsonNumber("dutyPerUnit").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("2.50")));
    assertThat(rate.getString("currency"), is("GBP"));
  }

  private JsonObject levelAt(String store, String variant) {
    JsonArray levels = Envelopes.okArray(get("/admin/inventory/levels?store=" + store));
    return Envelopes.find(levels, "variantId", variant);
  }

  private static String fulfilled(String eventId, String store, String variant, int qty) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + store
        + "\",\"items\":[{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"netAmount\":40.00}]}";
  }

  // ── in bond: on hand, never for sale, valued without the duty ──────────────

  @Test
  void dutySuspendedStockIsHeldOnlyInBondAndNeverSold() {
    bondTheWarehouse();
    rateTheWhisky();

    JsonObject cask = Envelopes.created(receive(BOND, WHISKY, 10, "duty_suspended"));
    assertThat(cask.getString("dutyStatus"), is("DUTY_SUSPENDED"));
    assertThat(
        Envelopes.created(receive(BOND, WHISKY, 2, null)).getString("dutyStatus"), is("DUTY_PAID"));

    // Refused by name: suspended stock at a shop nobody approved, a status nobody defined.
    assertThat(
        code(receive(SHOP, WHISKY, 1, "DUTY_SUSPENDED"), 400), is("INVENTORY_STORE_NOT_BONDED"));
    assertThat(
        code(receive(BOND, WHISKY, 1, "DUTY_FREE"), 400), is("INVENTORY_DUTY_STATUS_INVALID"));

    // On hand, in bond, and only the duty-paid two available.
    JsonObject level = levelAt(BOND, WHISKY);
    assertThat(
        level.getJsonNumber("onHand").bigDecimalValue(), comparesEqualTo(new BigDecimal("12")));
    assertThat(
        level.getJsonNumber("inBond").bigDecimalValue(), comparesEqualTo(new BigDecimal("10")));
    assertThat(
        level.getJsonNumber("available").bigDecimalValue(), comparesEqualTo(new BigDecimal("2")));

    // A hold or a sale reaches the duty-paid two and no further.
    assertThat(
        post(
                "/inventory/reservations",
                "{\"storeId\":\""
                    + BOND
                    + "\",\"variantId\":\""
                    + WHISKY
                    + "\",\"qty\":3,\"orderId\":\""
                    + Ids.newId()
                    + "\"}")
            .getStatus(),
        is(422));
    orders.handle(fulfilled(Ids.newId().toString(), BOND, WHISKY, 2));
    assertThat(levelAt(BOND, WHISKY).getJsonNumber("available").bigDecimalValue().signum(), is(0));
    assertThat(
        levelAt(BOND, WHISKY).getJsonNumber("inBond").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("10")));
    // A sale with nothing duty-paid left draws nothing from bond.
    orders.handle(fulfilled(Ids.newId().toString(), BOND, WHISKY, 1));
    assertThat(
        levelAt(BOND, WHISKY).getJsonNumber("inBond").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("10")));

    // Valued at cost without the duty, and the duty it would crystallise beside it.
    JsonArray rows =
        Envelopes.okArray(get("/admin/inventory/reports/valuation?groupBy=VARIANT&limit=50"));
    JsonObject row = Envelopes.find(rows, "groupKey", WHISKY);
    assertThat(
        row.getJsonNumber("value").bigDecimalValue(), comparesEqualTo(new BigDecimal("200.00")));
    assertThat(
        row.getJsonNumber("dutySuspendedQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("10")));
    assertThat(
        row.getJsonNumber("dutyPotential").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("25.00")));

    // The stock in bond, per variant, with the duty it carries.
    JsonArray inBond = Envelopes.okArray(get("/admin/inventory/bond/stock?storeId=" + BOND));
    JsonObject whisky = Envelopes.find(inBond, "variantId", WHISKY);
    assertThat(
        whisky.getJsonNumber("qty").bigDecimalValue(), comparesEqualTo(new BigDecimal("10")));
    assertThat(
        whisky.getJsonNumber("dutyPotential").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("25.00")));
  }

  // ── released to home use: the duty crystallises ────────────────────────────

  @Test
  void aReleaseFromBondPaysTheDutyOnWhatLeavesAndTellsPurchaseSvcOnce() {
    bondTheWarehouse();
    rateTheWhisky();
    Envelopes.created(receive(BOND, WHISKY, 10, "DUTY_SUSPENDED"));

    JsonObject release =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/bond/releases",
                "{\"storeId\":\""
                    + BOND
                    + "\",\"variantId\":\""
                    + WHISKY
                    + "\",\"qty\":4,\"reference\":\"W5 Sep\"}",
                "STOREKEEPER"));
    assertThat(
        release.getJsonNumber("qty").bigDecimalValue(), comparesEqualTo(new BigDecimal("4")));
    assertThat(
        release.getJsonNumber("dutyPerUnit").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("2.50")));
    assertThat(
        release.getJsonNumber("dutyAmount").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("10.00")));
    assertThat(release.getString("currency"), is("GBP"));
    assertThat(release.getString("reference"), is("W5 Sep"));

    // Six still in bond, four duty-paid and available; the released four are a batch of their own.
    JsonObject level = levelAt(BOND, WHISKY);
    assertThat(
        level.getJsonNumber("inBond").bigDecimalValue(), comparesEqualTo(new BigDecimal("6")));
    assertThat(
        level.getJsonNumber("available").bigDecimalValue(), comparesEqualTo(new BigDecimal("4")));
    JsonArray batches =
        Envelopes.okArray(get("/admin/inventory/batches?store=" + BOND + "&variant=" + WHISKY));
    assertThat(batches.size(), is(2));
    int suspended = 0;
    int paid = 0;
    for (int i = 0; i < batches.size(); i++) {
      JsonObject b = batches.getJsonObject(i);
      if ("DUTY_SUSPENDED".equals(b.getString("dutyStatus"))) suspended++;
      if ("DUTY_PAID".equals(b.getString("dutyStatus"))) paid++;
    }
    assertThat(suspended, is(1));
    assertThat(paid, is(1));
    // The movement of its own.
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.stock_movements WHERE type = 'BOND_RELEASE'"),
        is("2"));
    // purchase-svc is told what duty is now owed.
    String announced =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type = 'DutyReleased'");
    assertThat(announced, containsString("\"dutyAmount\":10.00"));
    assertThat(announced, containsString("\"variantId\":\"" + WHISKY + "\""));
    assertThat(announced, containsString("\"currency\":\"GBP\""));

    // The releases of a period, with the duty they add up to.
    String today = LocalDate.now().toString();
    JsonObject period =
        Envelopes.ok(
            get("/admin/inventory/bond/releases?storeId=" + BOND + "&from=2026-01-01&to=" + today));
    assertThat(period.getJsonArray("releases").size(), is(1));
    assertThat(
        period.getJsonNumber("totalDuty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("10.00")));

    // Refused by name: more than is in bond, a variant with no duty rate, a shop that is not
    // bonded,
    // and by role.
    assertThat(
        code(
            post(
                "/admin/inventory/bond/releases",
                "{\"storeId\":\""
                    + BOND
                    + "\",\"variantId\":\""
                    + WHISKY
                    + "\",\"qty\":7,\"reference\":\"too many\"}"),
            422),
        is("INVENTORY_INSUFFICIENT_BONDED_STOCK"));
    Envelopes.created(receive(BOND, UNRATED, 3, "DUTY_SUSPENDED"));
    assertThat(
        code(
            post(
                "/admin/inventory/bond/releases",
                "{\"storeId\":\""
                    + BOND
                    + "\",\"variantId\":\""
                    + UNRATED
                    + "\",\"qty\":1,\"reference\":\"x\"}"),
            409),
        is("INVENTORY_DUTY_RATE_MISSING"));
    assertThat(
        code(
            post(
                "/admin/inventory/bond/releases",
                "{\"storeId\":\""
                    + SHOP
                    + "\",\"variantId\":\""
                    + WHISKY
                    + "\",\"qty\":1,\"reference\":\"x\"}"),
            409),
        is("INVENTORY_STORE_NOT_BONDED"));
    assertThat(
        call(
                "PUT",
                "/admin/inventory/bond/approvals/" + SHOP,
                "{\"approvalNumber\":\"X\",\"regime\":\"EXCISE\"}",
                "CASHIER")
            .getStatus(),
        is(403));
    assertThat(
        call(
                "PUT",
                "/admin/inventory/bond/duty-rates/" + WHISKY,
                "{\"dutyPerUnit\":1}",
                "STOREKEEPER")
            .getStatus(),
        is(403));
    assertThat(
        code(
            call(
                "PUT",
                "/admin/inventory/bond/approvals/" + SHOP,
                "{\"approvalNumber\":\"X\",\"regime\":\"BONDED\"}",
                "OWNER"),
            400),
        is("INVENTORY_BOND_REGIME_INVALID"));

    // An ended approval takes no more suspended stock.
    assertThat(post("/admin/inventory/bond/approvals/" + BOND + "/end", "{}").getStatus(), is(200));
    assertThat(
        code(receive(BOND, WHISKY, 1, "DUTY_SUSPENDED"), 400), is("INVENTORY_STORE_NOT_BONDED"));
    JsonArray approvals = Envelopes.okArray(get("/admin/inventory/bond/approvals"));
    assertThat(approvals.getJsonObject(0).getBoolean("active"), is(false));
  }

  // ── a consignment or duty-suspended delivery from purchase-svc ─────────────

  @Test
  void aGoodsReceiptUnderBondMakesADutySuspendedBatch() {
    bondTheWarehouse();
    String eventId = Ids.newId().toString();
    String event =
        "{\"eventId\":\""
            + eventId
            + "\",\"tenantId\":\""
            + T
            + "\",\"storeId\":\""
            + BOND
            + "\",\"refId\":\""
            + Ids.newId()
            + "\",\"poId\":\""
            + Ids.newId()
            + "\",\"ownership\":\"OWNED\",\"supplierId\":null,\"dutyStatus\":\"DUTY_SUSPENDED\","
            + "\"lines\":[{\"variantId\":\""
            + WHISKY
            + "\",\"qty\":6,\"costPrice\":20.00}]}";
    goodsReceived.handle(event);
    goodsReceived.handle(event);
    JsonArray batches =
        Envelopes.okArray(get("/admin/inventory/batches?store=" + BOND + "&variant=" + WHISKY));
    assertThat(batches.size(), is(1));
    assertThat(batches.getJsonObject(0).getString("dutyStatus"), is("DUTY_SUSPENDED"));
    assertThat(levelAt(BOND, WHISKY).getJsonNumber("available").bigDecimalValue().signum(), is(0));
  }
}
