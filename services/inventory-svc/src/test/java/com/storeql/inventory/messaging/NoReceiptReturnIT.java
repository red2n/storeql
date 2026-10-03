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
 * A no-receipt return has no order: order-svc announces it, and each line is restocked at the
 * return's store as an anonymous batch placed by its condition. Driven through the handler, as the
 * consumer does.
 */
@HelidonTest
class NoReceiptReturnIT {

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
  @Inject NoReceiptReturnHandler handler;

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

  private record Stock(String store, String variant, String returnId) {}

  /** A variant with ten units on sale at a store. */
  private Stock stocked() {
    Stock s = new Stock(Ids.newId().toString(), Ids.newId().toString(), Ids.newId().toString());
    Response r =
        target
            .path("/admin/inventory/receive")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"storeId\":\"%s\",\"variantId\":\"%s\",\"qty\":10,\"batchNo\":\"L1\",\"costPrice\":2.50}"
                        .formatted(s.store, s.variant),
                    MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
    return s;
  }

  private static String event(String eventId, String tenant, Stock s, String condition) {
    return "{\"eventId\":\"%s\",\"eventType\":\"NoReceiptReturnRecorded\",\"tenantId\":\"%s\",\"returnId\":\"%s\",\"storeId\":\"%s\",\"currency\":\"GBP\",\"amount\":6.00,\"taxAmount\":1.00,\"refundMethod\":\"STORE_CREDIT\",\"customerId\":null,\"giftCardId\":null,\"approvedBy\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"qty\":2,\"unitPrice\":3.00,\"taxAmount\":1.00,\"condition\":\"%s\"}]}"
        .formatted(eventId, tenant, s.returnId, s.store, Ids.newId(), s.variant, condition);
  }

  private BigDecimal available(Stock s) {
    Response r =
        com.storeql.test.WebTargets.at(target, "/admin/inventory/levels?store=" + s.store)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    for (JsonObject row : Envelopes.okArray(r).getValuesAs(JsonObject.class)) {
      if (s.variant.equals(row.getString("variantId"))) {
        return row.getJsonNumber("available").bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  private static String returnedField(String tenant, Stock s, String column) {
    return Envelopes.scalar(
        PG,
        "SELECT "
            + column
            + " FROM inventory.inventory_batches WHERE tenant_id = '"
            + tenant
            + "' AND variant_id = '"
            + s.variant
            + "' AND received_qty = 2");
  }

  private static int batches(String tenant, Stock s) {
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
  @DisplayName("A sealed no-receipt line goes on sale at the store and available rises")
  void sealedGoesOnSale() {
    Stock s = stocked();
    handler.handle(event(Ids.newId().toString(), T, s, "SEALED"));
    assertThat(returnedField(T, s, "material_status"), is("AVAILABLE"));
    assertThat(returnedField(T, s, "store_id"), is(s.store));
    assertThat(available(s), comparesEqualTo(new BigDecimal("12")));
    // The movement references the return, and the arrival is announced like any restock.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT ref_id FROM inventory.stock_movements WHERE ref_type = 'NO_RECEIPT_RETURN'"
                + " AND variant_id = '"
                + s.variant
                + "'"),
        is(s.returnId));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.outbox WHERE event_type = 'StockReceived'"),
        is("2"));
  }

  @Test
  @DisplayName("An opened no-receipt line waits in INSPECTION and available does not rise")
  void openedGoesToInspection() {
    Stock s = stocked();
    handler.handle(event(Ids.newId().toString(), T, s, "OPENED"));
    assertThat(returnedField(T, s, "material_status"), is("INSPECTION"));
    assertThat(returnedField(T, s, "material_status_reason"), containsString("opened"));
    assertThat(available(s), comparesEqualTo(new BigDecimal("10")));
  }

  @Test
  @DisplayName("Damaged and faulty no-receipt lines go to DAMAGED and available does not rise")
  void damagedAndFaultyGoToDamaged() {
    Stock damaged = stocked();
    handler.handle(event(Ids.newId().toString(), T, damaged, "DAMAGED"));
    assertThat(returnedField(T, damaged, "material_status"), is("DAMAGED"));
    assertThat(available(damaged), comparesEqualTo(new BigDecimal("10")));

    Stock faulty = stocked();
    handler.handle(event(Ids.newId().toString(), T, faulty, "FAULTY"));
    assertThat(returnedField(T, faulty, "material_status"), is("DAMAGED"));
    assertThat(available(faulty), comparesEqualTo(new BigDecimal("10")));
  }

  @Test
  @DisplayName("The same event twice restocks once")
  void theSameEventTwiceRestocksOnce() {
    Stock s = stocked();
    String event = event(Ids.newId().toString(), T, s, "OPENED");
    handler.handle(event);
    handler.handle(event);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE ref_type = 'NO_RECEIPT_RETURN'"
                + " AND variant_id = '"
                + s.variant
                + "'"),
        is("1"));
    assertThat(batches(T, s), is(2));
  }

  @Test
  @DisplayName("Another business's event never touches our stock")
  void anotherBusinessesEventNeverTouchesOurStock() {
    Stock s = stocked();
    handler.handle(event(Ids.newId().toString(), OTHER_T, s, "SEALED"));
    assertThat(batches(T, s), is(1));
    assertThat(available(s), comparesEqualTo(new BigDecimal("10")));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '"
                + T
                + "' AND ref_type = 'NO_RECEIPT_RETURN'"),
        is("0"));
  }
}
