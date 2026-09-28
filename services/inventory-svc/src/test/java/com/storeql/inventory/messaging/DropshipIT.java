package com.storeql.inventory.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
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
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dropship (readiness review: "Consignment and dropship stock ownership"), inventory's side: a
 * variant purchase-svc sources from a supplier per order is stock the business never holds. It is
 * available with none on the shelf, a checkout hold on it draws nothing, and a fulfilled sale of it
 * deducts nothing. Written before the code.
 */
@HelidonTest
class DropshipIT {

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

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478d1";
  private static final String S = "01a090ae-611e-703c-a378-a4972ea461d1";
  private static final String DROP_V = "01a090ae-611e-7037-a4b7-c854f0266ad1";
  private static final String STOCKED_V = "01a090ae-611e-7037-a4b7-c854f0266ad2";
  private static final String SUPPLIER = "01a090ae-611e-7056-8f30-ecdbb48160d1";
  private static final String ORDER = "01a090ae-611e-705c-994c-5daee3fbd0d1";

  @Inject WebTarget target;
  @Inject VariantSourcingHandler sourcing;
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
              + " inventory.sale_revenue, inventory.inventory_batches, inventory.variant_sourcing,"
              + " inventory.processed_events, inventory.outbox CASCADE");
    }
  }

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

  private static String sourced(String eventId, String variant, String fulfilment) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"VariantSourcingChanged\",\"tenantId\":\""
        + T
        + "\",\"aggregateId\":\""
        + variant
        + "\",\"occurredAt\":\"2026-09-24T10:00:00Z\",\"variantId\":\""
        + variant
        + "\",\"fulfilment\":\""
        + fulfilment
        + "\",\"supplierId\":"
        + ("DROPSHIP".equals(fulfilment) ? "\"" + SUPPLIER + "\"" : "null")
        + "}";
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
        + ",\"netAmount\":9.00}]}";
  }

  private static int movements() {
    return Integer.parseInt(Envelopes.scalar(PG, "SELECT count(*) FROM inventory.stock_movements"));
  }

  private static boolean lists(JsonArray availability, String variantId) {
    return availability.getValuesAs(JsonObject.class).stream()
        .anyMatch(a -> variantId.equals(a.getString("variantId", null)));
  }

  private Response reserve(String variant, int qty) {
    return post(
        "/inventory/reservations",
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":"
            + qty
            + ",\"orderId\":\""
            + Ids.newId()
            + "\"}");
  }

  @Test
  void aDropshipVariantIsAvailableWithNoStockAndItsSaleDrawsNothing() {
    // Told twice, kept once: the variant is now sourced from the supplier per order.
    String eventId = Ids.newId().toString();
    sourcing.handle(sourced(eventId, DROP_V, "DROPSHIP"));
    sourcing.handle(sourced(eventId, DROP_V, "DROPSHIP"));

    // Available with nothing on the shelf — and the storefront is told why.
    JsonArray availability = Envelopes.okArray(get("/inventory/availability?store=" + S));
    JsonObject drop = Envelopes.find(availability, "variantId", DROP_V);
    assertThat(drop.getBoolean("inStock"), is(true));
    assertThat(drop.getBoolean("dropship"), is(true));

    // A checkout hold on it is placed without stock and draws none.
    JsonObject hold = Envelopes.created(reserve(DROP_V, 2));
    assertThat(hold.getString("status"), is("HELD"));
    assertThat(hold.getString("fulfilment"), is("DROPSHIP"));
    assertThat(movements(), is(0));
    assertThat(
        post("/inventory/reservations/" + hold.getString("id") + "/consume", "{}").getStatus(),
        is(200));
    assertThat(movements(), is(0));

    // A till sale of it, fulfilled with no hold, deducts nothing and complains of nothing.
    String sale = Ids.newId().toString();
    orders.handle(fulfilled(sale, DROP_V, 1));
    orders.handle(fulfilled(sale, DROP_V, 1));
    assertThat(movements(), is(0));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE variant_id = '" + DROP_V + "'"),
        is("0"));

    // A stocked variant with nothing on the shelf is what it always was: not available, no hold.
    assertThat(lists(availability, STOCKED_V), is(false));
    assertThat(reserve(STOCKED_V, 1).getStatus(), is(422));

    // Sourced from stock again: the variant is no longer available on the supplier's say-so.
    sourcing.handle(sourced(Ids.newId().toString(), DROP_V, "STOCK"));
    JsonArray after = Envelopes.okArray(get("/inventory/availability?store=" + S));
    assertThat(lists(after, DROP_V), is(false));
    assertThat(reserve(DROP_V, 1).getStatus(), is(422));
  }
}
