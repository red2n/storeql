package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Another business's staff, of every role, cannot read, pick, ship, receive or cancel our move
 * order or our transfer order, even naming their ids: 404 (or the 403 a role without the permission
 * meets first), and nothing of ours moves. The lines of both are read and written by their own
 * tenant only.
 */
@HelidonTest
class OrderLineTenantScopeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final List<String> ROLES =
      List.of("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response call(String method, String path, String json, String tenant, String role) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return "GET".equals(method)
        ? b.get()
        : b.post(Entity.entity(json == null ? "" : json, MediaType.APPLICATION_JSON));
  }

  private static String scalar(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  @Test
  @DisplayName(
      "Another business's staff of every role cannot read or move our move order or transfer"
          + " order, and nothing of ours moves")
  void anotherBusinessCannotReadOrMoveOurOrdersOrTheirLines() {
    String t = Ids.newId().toString();
    String other = Ids.newId().toString();
    String a = Ids.newId().toString();
    String b = Ids.newId().toString();
    String v = Ids.newId().toString();

    Response received =
        call(
            "POST",
            "/admin/inventory/receive",
            "{\"storeId\":\"" + a + "\",\"variantId\":\"" + v + "\",\"qty\":20,\"costPrice\":1}",
            t,
            "OWNER");
    assertThat(received.readEntity(String.class), received.getStatus(), is(201));

    String moveId =
        Envelopes.created(
                call(
                    "POST",
                    "/admin/inventory/move-orders",
                    "{\"fromStoreId\":\""
                        + a
                        + "\",\"toStoreId\":\""
                        + b
                        + "\",\"lines\":[{\"variantId\":\""
                        + v
                        + "\",\"requestedQty\":4}]}",
                    t,
                    "OWNER"))
            .getString("id");
    String transferBody =
        "{\"fromStoreId\":\""
            + a
            + "\",\"toStoreId\":\""
            + b
            + "\",\"transferType\":\"INTRANSIT\",\"lines\":[{\"variantId\":\""
            + v
            + "\",\"requestedQty\":3}]}";
    String pendingId =
        Envelopes.created(call("POST", "/admin/inventory/transfers", transferBody, t, "OWNER"))
            .getString("id");
    String shippedId =
        Envelopes.created(call("POST", "/admin/inventory/transfers", transferBody, t, "OWNER"))
            .getString("id");
    assertThat(
        Envelopes.ok(
                call("POST", "/admin/inventory/transfers/" + shippedId + "/ship", null, t, "OWNER"))
            .getString("status"),
        is("SHIPPED"));
    String onHand =
        scalar(
            "SELECT sum(remaining_qty) FROM inventory.inventory_batches WHERE tenant_id = '"
                + t
                + "' AND variant_id = '"
                + v
                + "'");
    String movements =
        scalar("SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '" + t + "'");

    String[][] routes = {
      {"GET", "/admin/inventory/move-orders/" + moveId},
      {"POST", "/admin/inventory/move-orders/" + moveId + "/pick"},
      {"POST", "/admin/inventory/move-orders/" + moveId + "/cancel"},
      {"GET", "/admin/inventory/transfers/" + pendingId},
      {"POST", "/admin/inventory/transfers/" + pendingId + "/ship"},
      {"POST", "/admin/inventory/transfers/" + pendingId + "/cancel"},
      {"GET", "/admin/inventory/transfers/" + shippedId},
      {"POST", "/admin/inventory/transfers/" + shippedId + "/receive"},
    };
    for (String role : ROLES) {
      for (String[] route : routes) {
        Response r = call(route[0], route[1], null, other, role);
        String label = role + " " + route[0] + " " + route[1];
        int status = r.getStatus();
        r.readEntity(String.class);
        if ("OWNER".equals(role) || "MANAGER".equals(role)) {
          assertThat(label, status, is(404));
        } else {
          assertThat(label, status, anyOf(is(404), is(403)));
        }
      }
    }

    assertThat(
        scalar("SELECT status FROM inventory.move_orders WHERE id = '" + moveId + "'"),
        is("DRAFT"));
    assertThat(
        scalar(
            "SELECT picked_qty FROM inventory.move_order_lines WHERE move_order_id = '"
                + moveId
                + "'"),
        is(nullValue()));
    assertThat(
        scalar("SELECT status FROM inventory.transfer_orders WHERE id = '" + pendingId + "'"),
        is("PENDING"));
    assertThat(
        scalar(
            "SELECT coalesce(shipped_qty::text, 'none') FROM inventory.transfer_order_lines"
                + " WHERE transfer_order_id = '"
                + pendingId
                + "'"),
        is("none"));
    assertThat(
        scalar("SELECT status FROM inventory.transfer_orders WHERE id = '" + shippedId + "'"),
        is("SHIPPED"));
    assertThat(
        scalar(
            "SELECT coalesce(received_qty::text, 'none') FROM inventory.transfer_order_lines"
                + " WHERE transfer_order_id = '"
                + shippedId
                + "'"),
        is("none"));
    assertThat(
        scalar(
            "SELECT sum(remaining_qty) FROM inventory.inventory_batches WHERE tenant_id = '"
                + t
                + "' AND variant_id = '"
                + v
                + "'"),
        is(onHand));
    assertThat(
        scalar("SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '" + t + "'"),
        is(movements));

    // The same calls from the owner of the business do go through: the refusals above were the
    // other business's, not a broken route.
    JsonObject picked =
        Envelopes.ok(
            call("POST", "/admin/inventory/move-orders/" + moveId + "/pick", null, t, "OWNER"));
    assertThat(picked.getString("status"), is("COMPLETED"));
    assertThat(
        picked.getJsonArray("lines").getJsonObject(0).getJsonNumber("pickedQty").intValue(), is(4));
    JsonObject receivedTransfer =
        Envelopes.ok(
            call("POST", "/admin/inventory/transfers/" + shippedId + "/receive", null, t, "OWNER"));
    assertThat(receivedTransfer.getString("status"), is("RECEIVED"));
  }
}
