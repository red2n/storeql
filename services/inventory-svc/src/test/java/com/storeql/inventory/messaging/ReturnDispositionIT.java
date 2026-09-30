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
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A return puts the goods where its condition says (return controls): sealed goods back on sale,
 * opened goods to INSPECTION, damaged and faulty goods to DAMAGED, a recall return to RECALLED —
 * and off-sale stock counts as neither on hand nor available and is never drawn by a sale. Driven
 * through the order-event handler, as the consumer does.
 */
@HelidonTest
class ReturnDispositionIT {

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

  private static final String T = "01a090ae-611e-7a2c-a97b-d1b8025478d1";
  private static final String OTHER_T = "01a090ae-611e-7a2d-a97b-d1b8025478d2";

  @Inject WebTarget target;
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
              + " inventory.sale_revenue, inventory.lot_genealogy, inventory.inventory_batches,"
              + " inventory.processed_events, inventory.outbox CASCADE");
    }
  }

  /** A stocked variant at a store, and an order that sold three of its ten units. */
  private record Sale(String store, String variant, String order) {}

  private Sale sold() {
    Sale s = new Sale(Ids.newId().toString(), Ids.newId().toString(), Ids.newId().toString());
    Response r =
        target
            .path("/admin/inventory/receive")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"storeId\":\"%s\",\"variantId\":\"%s\",\"qty\":10,\"batchNo\":\"L1\",\"expiryDate\":\"2027-06-01\",\"costPrice\":2.50}"
                        .formatted(s.store, s.variant),
                    MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
    orders.handle(fulfilled(T, s, 3));
    assertThat(onHand(s), comparesEqualTo(new BigDecimal("7")));
    return s;
  }

  private static String fulfilled(String tenant, Sale s, int qty) {
    return "{\"eventId\":\"%s\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\"%s\",\"orderId\":\"%s\",\"storeId\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"qty\":%d,\"netAmount\":9.00}]}"
        .formatted(Ids.newId(), tenant, s.order, s.store, s.variant, qty);
  }

  /** An OrderReturned event of two units; {@code condition} null leaves the member out. */
  private static String returned(
      String eventId, String tenant, Sale s, String condition, boolean recall) {
    return "{\"eventId\":\"%s\",\"eventType\":\"OrderReturned\",\"tenantId\":\"%s\",\"orderId\":\"%s\",\"storeId\":\"%s\"%s,\"items\":[{\"variantId\":\"%s\",\"qty\":2,\"netAmount\":6.00%s}]}"
        .formatted(
            eventId,
            tenant,
            s.order,
            s.store,
            recall ? ",\"recall\":true" : "",
            s.variant,
            condition == null ? "" : ",\"condition\":\"" + condition + "\"");
  }

  private String levelField(Sale s, String field) {
    Response r =
        com.storeql.test.WebTargets.at(target, "/admin/inventory/levels?store=" + s.store)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    var rows = Envelopes.okArray(r);
    for (JsonObject row : rows.getValuesAs(JsonObject.class)) {
      if (s.variant.equals(row.getString("variantId"))) {
        return row.getJsonNumber(field).bigDecimalValue().toPlainString();
      }
    }
    return "0";
  }

  private BigDecimal onHand(Sale s) {
    return new BigDecimal(levelField(s, "onHand"));
  }

  private BigDecimal available(Sale s) {
    return new BigDecimal(levelField(s, "available"));
  }

  /** The material status of the batch a return made (the one that is not the original lot). */
  private static String returnedStatus(Sale s) {
    return Envelopes.scalar(
        PG,
        "SELECT material_status FROM inventory.inventory_batches WHERE tenant_id = '"
            + T
            + "' AND variant_id = '"
            + s.variant
            + "' AND received_qty = 2");
  }

  private static String returnedReason(Sale s) {
    return Envelopes.scalar(
        PG,
        "SELECT material_status_reason FROM inventory.inventory_batches WHERE tenant_id = '"
            + T
            + "' AND variant_id = '"
            + s.variant
            + "' AND received_qty = 2");
  }

  private static int batches(String tenant, Sale s) {
    return Integer.parseInt(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.inventory_batches WHERE tenant_id = '"
                + tenant
                + "' AND variant_id = '"
                + s.variant
                + "'"));
  }

  @Test
  @DisplayName("A sealed return goes back on sale and available rises")
  void sealedGoesBackOnSale() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, "SEALED", false));
    assertThat(returnedStatus(s), is("AVAILABLE"));
    assertThat(available(s), comparesEqualTo(new BigDecimal("9")));
    assertThat(onHand(s), comparesEqualTo(new BigDecimal("9")));
  }

  @Test
  @DisplayName("A legacy return event with no condition goes back on sale")
  void aLegacyEventWithNoConditionGoesBackOnSale() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, null, false));
    assertThat(returnedStatus(s), is("AVAILABLE"));
    assertThat(available(s), comparesEqualTo(new BigDecimal("9")));
  }

  @Test
  @DisplayName(
      "An opened return waits in INSPECTION: not available, and not on hand as levels count")
  void openedGoesToInspection() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, "OPENED", false));
    assertThat(returnedStatus(s), is("INSPECTION"));
    assertThat(returnedReason(s), containsString("opened"));
    // Levels count sellable stock only — as they do for a QUARANTINE, INSPECTION or DAMAGED batch.
    assertThat(available(s), comparesEqualTo(new BigDecimal("7")));
    assertThat(onHand(s), comparesEqualTo(new BigDecimal("7")));
    // It kept the sale's lot and cost, as a batch of its own.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT batch_no || '/' || cost_price::numeric(18,2) FROM inventory.inventory_batches WHERE"
                + " variant_id = '"
                + s.variant
                + "' AND received_qty = 2"),
        is("L1/2.50"));
  }

  @Test
  @DisplayName("Damaged and faulty returns go to DAMAGED and say which")
  void damagedAndFaultyGoToDamaged() {
    Sale damaged = sold();
    orders.handle(returned(Ids.newId().toString(), T, damaged, "DAMAGED", false));
    assertThat(returnedStatus(damaged), is("DAMAGED"));
    assertThat(returnedReason(damaged), containsString("damaged"));
    assertThat(available(damaged), comparesEqualTo(new BigDecimal("7")));

    Sale faulty = sold();
    orders.handle(returned(Ids.newId().toString(), T, faulty, "FAULTY", false));
    assertThat(returnedStatus(faulty), is("DAMAGED"));
    assertThat(returnedReason(faulty), containsString("faulty"));
    assertThat(available(faulty), comparesEqualTo(new BigDecimal("7")));
  }

  @Test
  @DisplayName("A return that settles a recall goes to RECALLED whatever its condition")
  void aRecallReturnIsRecalled() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, "SEALED", true));
    assertThat(returnedStatus(s), is("RECALLED"));
    assertThat(available(s), comparesEqualTo(new BigDecimal("7")));
  }

  @Test
  @DisplayName("An off-sale return announces the status change like any other")
  void anOffSaleReturnAnnouncesItsStatus() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, "OPENED", false));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.outbox WHERE event_type = 'MaterialStatusChanged'"),
        is("1"));
  }

  // ── what a return leaves behind, and who may touch it (RET-26, RET-27, RET-31) ─────────

  private Response asTenant(String method, String path, String json, String tenant, String role) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  /** The batch of the given remaining quantity among a variant's batches, as the API lists them. */
  private JsonObject batchOf(Sale s, int remaining) {
    var rows =
        Envelopes.okArray(
            asTenant(
                "GET",
                "/admin/inventory/batches?store=" + s.store + "&variant=" + s.variant,
                null,
                T,
                "OWNER"));
    for (JsonObject row : rows.getValuesAs(JsonObject.class)) {
      if (row.getJsonNumber("remainingQty").bigDecimalValue().compareTo(new BigDecimal(remaining))
          == 0) {
        return row;
      }
    }
    throw new AssertionError("no batch with " + remaining + " remaining in " + rows);
  }

  /**
   * RET-26: a returned unit is restocked AVAILABLE at the store that took it back, as a batch of
   * its own carrying the sale's lot, use-by date and cost.
   */
  @Test
  @DisplayName("A return is restocked available at the store, under the sale's lot, date and cost")
  void aReturnIsRestockedAvailableUnderTheSalesLotDateAndCost() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, "SEALED", false));
    JsonObject back = batchOf(s, 2);
    assertThat(back.getString("materialStatus"), is("AVAILABLE"));
    assertThat(back.getString("storeId"), is(s.store));
    assertThat(back.getString("batchNo"), is("L1"));
    assertThat(back.getString("expiryDate"), is("2027-06-01"));
    assertThat(
        back.getJsonNumber("costPrice").bigDecimalValue(), comparesEqualTo(new BigDecimal("2.50")));
    // It is a batch of its own: the original lot still holds the seven that were never sold.
    assertThat(batchOf(s, 7).getString("id").equals(back.getString("id")), is(false));
    assertThat(available(s), comparesEqualTo(new BigDecimal("9")));
  }

  /**
   * RET-27: a return whose sale cannot be traced still adds the stock, as an anonymous batch with
   * no lot, use-by date or cost, and one RETURN-referenced movement.
   */
  @Test
  @DisplayName("A return with no traceable sale is an anonymous, costless restock")
  void aReturnWithNoTraceableSaleIsAnAnonymousCostlessRestock() {
    Sale s = new Sale(Ids.newId().toString(), Ids.newId().toString(), Ids.newId().toString());
    orders.handle(returned(Ids.newId().toString(), T, s, "SEALED", false));
    JsonObject back = batchOf(s, 2);
    assertThat(back.getString("batchNo"), is("RET-" + Ids.shortRef(Ids.parse(s.order))));
    assertThat(back.getString("materialStatus"), is("AVAILABLE"));
    assertThat(back.containsKey("expiryDate") && !back.isNull("expiryDate"), is(false));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT cost_price IS NULL FROM inventory.inventory_batches WHERE id = '"
                + back.getString("id")
                + "'"),
        is("true"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE ref_type = 'RETURN' AND"
                + " variant_id = '"
                + s.variant
                + "'"),
        is("1"));
    assertThat(onHand(s), comparesEqualTo(new BigDecimal("2")));
    assertThat(available(s), comparesEqualTo(new BigDecimal("2")));
  }

  /**
   * RET-31: another business's staff, of every role, can neither flip the material status of the
   * batch a return made nor write stock off against our store, even naming its ids; nothing of ours
   * moves.
   */
  @Test
  @DisplayName("Another business's staff cannot flip a returned batch's status or write it off")
  void anotherBusinessCannotFlipOrWriteOffAReturnedBatch() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, "SEALED", false));
    String batchId = batchOf(s, 2).getString("id");
    String status = "{\"materialStatus\":\"QUARANTINE\",\"reason\":\"not ours\"}";
    String writeOff =
        "{\"storeId\":\""
            + s.store
            + "\",\"variantId\":\""
            + s.variant
            + "\",\"delta\":-1,\"reason\":\"Damaged\"}";
    int movements =
        Integer.parseInt(Envelopes.scalar(PG, "SELECT count(*) FROM inventory.stock_movements"));

    for (String role :
        new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(
          role,
          asTenant(
                  "PUT",
                  "/admin/inventory/batches/" + batchId + "/material-status",
                  status,
                  OTHER_T,
                  role)
              .getStatus(),
          is(404));
      assertThat(
          role,
          asTenant("GET", "/admin/inventory/batches/" + batchId, null, OTHER_T, role).getStatus(),
          is(404));
      int adjusted =
          asTenant("POST", "/admin/inventory/adjust", writeOff, OTHER_T, role).getStatus();
      // The till is refused the write-off outright; the others find nothing of theirs to write off.
      assertThat(role, adjusted, is("CASHIER".equals(role) ? 403 : 422));
    }

    assertThat(returnedStatus(s), is("AVAILABLE"));
    assertThat(onHand(s), comparesEqualTo(new BigDecimal("9")));
    assertThat(
        Envelopes.scalar(PG, "SELECT count(*) FROM inventory.stock_movements"),
        is(String.valueOf(movements)));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.outbox WHERE event_type = 'MaterialStatusChanged'"),
        is("0"));
  }

  @Test
  @DisplayName("The same return event twice restocks once")
  void theSameEventTwiceRestocksOnce() {
    Sale s = sold();
    String event = returned(Ids.newId().toString(), T, s, "OPENED", false);
    orders.handle(event);
    orders.handle(event);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE ref_type = 'RETURN' AND"
                + " variant_id = '"
                + s.variant
                + "'"),
        is("1"));
    assertThat(batches(T, s), is(2));
  }

  @Test
  @DisplayName("Another business's return event never touches our stock")
  void anotherBusinessesEventNeverTouchesOurStock() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), OTHER_T, s, "SEALED", false));
    // Ours is as the sale left it: the original batch only, seven on sale.
    assertThat(batches(T, s), is(1));
    assertThat(available(s), comparesEqualTo(new BigDecimal("7")));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '"
                + T
                + "' AND ref_type = 'RETURN'"),
        is("0"));
  }

  @Test
  @DisplayName("A later sale never draws the off-sale returned stock")
  void aLaterSaleNeverDrawsOffSaleStock() {
    Sale s = sold();
    orders.handle(returned(Ids.newId().toString(), T, s, "OPENED", false));
    // Seven are on sale; the sale of seven takes them all, and a further one finds nothing to draw
    // — the two in INSPECTION are not there for it.
    orders.handle(fulfilled(T, s, 7));
    orders.handle(fulfilled(T, s, 1));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT remaining_qty FROM inventory.inventory_batches WHERE variant_id = '"
                + s.variant
                + "' AND received_qty = 2"),
        is("2.000"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT coalesce(sum(remaining_qty),0) FROM inventory.inventory_batches WHERE"
                + " variant_id = '"
                + s.variant
                + "' AND material_status = 'AVAILABLE'"),
        is("0.000"));
  }
}
