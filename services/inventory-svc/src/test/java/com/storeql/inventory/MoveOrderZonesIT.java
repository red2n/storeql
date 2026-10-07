package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A move order says what it does (inventory-screens slice 2): it names the zones by id, the pick
 * draws only what sits in the from-zone and puts it down in the to-zone, and its status is the
 * table's (DRAFT, then COMPLETED or CANCELLED).
 */
@HelidonTest
class MoveOrderZonesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = Ids.newId().toString();
  private static final String OTHER_T = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response call(String method, String path, String json, String tenant, String stores) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", "OWNER")
            .header("Idempotency-Key", Ids.newId().toString());
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return "GET".equals(method)
        ? b.get()
        : b.post(Entity.entity(json == null ? "" : json, MediaType.APPLICATION_JSON));
  }

  private void receive(String store, String variant, String zone, int qty) {
    Response r =
        call(
            "POST",
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + store
                + "\",\"variantId\":\""
                + variant
                + "\",\"qty\":"
                + qty
                + ",\"batchNo\":\"L"
                + zone.substring(zone.length() - 6)
                + "\",\"costPrice\":1.00,\"zoneId\":\""
                + zone
                + "\"}",
            T,
            null);
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private String moveBody(String store, String variant, String from, String to, int qty) {
    return "{\"fromStoreId\":\""
        + store
        + "\",\"toStoreId\":\""
        + store
        + "\","
        + (from == null ? "" : "\"fromZoneId\":\"" + from + "\",")
        + (to == null ? "" : "\"toZoneId\":\"" + to + "\",")
        + "\"lines\":[{\"variantId\":\""
        + variant
        + "\",\"requestedQty\":"
        + qty
        + "}]}";
  }

  private BigDecimal inZone(String store, String variant, String zone) {
    return new BigDecimal(
        Envelopes.scalar(
            PG,
            "SELECT coalesce(sum(remaining_qty),0) FROM inventory.inventory_batches WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "' AND variant_id = '"
                + variant
                + "' AND zone_id = '"
                + zone
                + "'"));
  }

  @Test
  @DisplayName(
      "A move order names its zones by id; the pick draws the from-zone and fills the to-zone")
  void theMoveDrawsFromOneZoneAndPutsDownInTheOther() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String back = Ids.newId().toString();
    String floor = Ids.newId().toString();
    String other = Ids.newId().toString();
    receive(store, variant, back, 6);
    receive(store, variant, other, 9);

    Response created =
        call(
            "POST",
            "/admin/inventory/move-orders",
            moveBody(store, variant, back, floor, 4),
            T,
            null);
    JsonObject order = Envelopes.created(created);
    assertThat(order.getString("status"), is("DRAFT"));
    assertThat(order.getString("fromZoneId"), is(back));
    assertThat(order.getString("toZoneId"), is(floor));

    Response picked =
        call(
            "POST",
            "/admin/inventory/move-orders/" + order.getString("id") + "/pick",
            null,
            T,
            null);
    assertThat(Envelopes.ok(picked).getString("status"), is("COMPLETED"));

    // Four left the back zone and none of the other zone's; four arrived on the floor.
    assertThat(inZone(store, variant, back).compareTo(new BigDecimal("2")), is(0));
    assertThat(inZone(store, variant, other).compareTo(new BigDecimal("9")), is(0));
    assertThat(inZone(store, variant, floor).compareTo(new BigDecimal("4")), is(0));
  }

  @Test
  @DisplayName(
      "A from-zone that holds too little is refused and nothing moves, whatever the rest of the store holds")
  void aShortFromZoneMovesNothing() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String back = Ids.newId().toString();
    String other = Ids.newId().toString();
    String floor = Ids.newId().toString();
    receive(store, variant, back, 2);
    receive(store, variant, other, 50);
    JsonObject order =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/move-orders",
                moveBody(store, variant, back, floor, 5),
                T,
                null));
    Response r =
        call(
            "POST",
            "/admin/inventory/move-orders/" + order.getString("id") + "/pick",
            null,
            T,
            null);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(422));
    assertThat(body, containsString("INSUFFICIENT_STOCK"));
    assertThat(inZone(store, variant, back).compareTo(new BigDecimal("2")), is(0));
    assertThat(inZone(store, variant, other).compareTo(new BigDecimal("50")), is(0));
    assertThat(inZone(store, variant, floor).compareTo(BigDecimal.ZERO), is(0));
  }

  @Test
  @DisplayName(
      "A zone that is not an id is 400, the same zone both ends is 400, no zone is the old move")
  void badZonesAreRefusedAndNoZonesStillWorks() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String zone = Ids.newId().toString();
    Response bad =
        call(
            "POST",
            "/admin/inventory/move-orders",
            moveBody(store, variant, "not-an-id", zone, 1),
            T,
            null);
    assertThat(bad.readEntity(String.class), bad.getStatus(), is(400));
    Response same =
        call(
            "POST",
            "/admin/inventory/move-orders",
            moveBody(store, variant, zone, zone, 1),
            T,
            null);
    String body = same.readEntity(String.class);
    assertThat(body, same.getStatus(), is(400));
    assertThat(body, containsString("MOVE_ORDER_SAME_ZONE"));
    Response none =
        call(
            "POST",
            "/admin/inventory/move-orders",
            moveBody(store, variant, null, null, 1),
            T,
            null);
    assertThat(none.readEntity(String.class), none.getStatus(), is(201));
  }

  @Test
  @DisplayName(
      "Another business, and a keeper of another store, cannot pick or read our move order")
  void aMoveOrderStaysInItsBusinessAndStore() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String back = Ids.newId().toString();
    String floor = Ids.newId().toString();
    receive(store, variant, back, 6);
    String id =
        Envelopes.created(
                call(
                    "POST",
                    "/admin/inventory/move-orders",
                    moveBody(store, variant, back, floor, 4),
                    T,
                    null))
            .getString("id");
    assertThat(
        call("GET", "/admin/inventory/move-orders/" + id, null, OTHER_T, null).getStatus(),
        is(404));
    assertThat(
        call("POST", "/admin/inventory/move-orders/" + id + "/pick", null, OTHER_T, null)
            .getStatus(),
        is(404));
    assertThat(
        call(
                "POST",
                "/admin/inventory/move-orders/" + id + "/pick",
                null,
                T,
                Ids.newId().toString())
            .getStatus(),
        is(403));
    assertThat(inZone(store, variant, back).compareTo(new BigDecimal("6")), is(0));
    assertThat(inZone(store, variant, floor).compareTo(BigDecimal.ZERO), is(0));
  }
}
