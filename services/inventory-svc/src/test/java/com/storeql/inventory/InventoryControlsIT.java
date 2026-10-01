package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flow-catalogue controls (30 Sep 2026): who may set the reorder-point cost inputs (management, not
 * the shop floor), and a write-off larger than what is on hand (refused whole, nothing written).
 * Every case also runs as another business's staff and as a store-held caller.
 */
@HelidonTest
class InventoryControlsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = Ids.newId().toString();
  private static final String OTHER_T = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();
  private static final String STRANGER = Ids.newId().toString();

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String role,
      String stores,
      String key) {
    Invocation.Builder b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", T.equals(tenant) ? USER : STRANGER)
            .header("X-Roles", role);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    if (key != null) b = b.header("Idempotency-Key", key);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response as(String method, String path, String json, String role) {
    return call(method, path, json, T, role, null, null);
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private static String plan(String store, String variant, String orderingCost) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"leadTimeDays\":7,\"orderingCost\":"
        + orderingCost
        + ",\"holdingCostPct\":0.2,\"unitCost\":10}";
  }

  private static BigDecimal orderingCostOf(String tenant, String store, String variant) {
    return new BigDecimal(
        Envelopes.scalar(
            PG,
            "SELECT ordering_cost FROM inventory.reorder_point_plans WHERE tenant_id = '"
                + tenant
                + "' AND store_id = '"
                + store
                + "' AND variant_id = '"
                + variant
                + "'"));
  }

  private static int plans(String tenant, String store, String variant) {
    return Integer.parseInt(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.reorder_point_plans WHERE tenant_id = '"
                + tenant
                + "' AND store_id = '"
                + store
                + "' AND variant_id = '"
                + variant
                + "'"));
  }

  // ── reorder-point cost inputs ──────────────────────────────────────────────

  /**
   * Catalogue INV reorder-points gap 1: ordering cost, holding cost % and unit cost are the
   * business's money figures. Management sets them; staff still read the plans. A store-held caller
   * reads and sets plans only at their own stores, and another business's staff neither see nor
   * move ours, even naming our store id.
   */
  @Test
  @DisplayName("Reorder-point cost inputs are management's to set; staff read the plans")
  void ropCostInputsAreManagementsToSetAndStaffReadThePlans() {
    String store = Ids.newId().toString();
    String elsewhere = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String path = "/admin/inventory/rop-plans";

    // The shop floor cannot set them, and a refused attempt writes nothing.
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      assertThat(
          role, code(as("PUT", path, plan(store, variant, "999"), role), 403), is("FORBIDDEN"));
    }
    assertThat(as("PUT", path, plan(store, variant, "999"), "CUSTOMER").getStatus(), is(403));
    assertThat(plans(T, store, variant), is(0));

    // A manager sets them; the owner changes them.
    assertThat(as("PUT", path, plan(store, variant, "50"), "MANAGER").getStatus(), is(200));
    assertThat(orderingCostOf(T, store, variant), comparesEqualTo(new BigDecimal("50")));
    assertThat(as("PUT", path, plan(store, variant, "60"), "OWNER").getStatus(), is(200));
    assertThat(orderingCostOf(T, store, variant), comparesEqualTo(new BigDecimal("60")));

    // A refused attempt after the plan exists leaves it as it was.
    assertThat(as("PUT", path, plan(store, variant, "999"), "STOREKEEPER").getStatus(), is(403));
    assertThat(orderingCostOf(T, store, variant), comparesEqualTo(new BigDecimal("60")));

    // Staff, the shop floor included, read the plan.
    for (String role : new String[] {"MANAGER", "STOREKEEPER", "CASHIER"}) {
      Response list = as("GET", path + "?store=" + store, null, role);
      assertThat(role, Envelopes.okArray(list), hasSize(1));
      Response one =
          as("GET", path + "/by-variant?store=" + store + "&variant=" + variant, null, role);
      assertThat(
          role,
          Envelopes.ok(one).getJsonNumber("orderingCost").bigDecimalValue(),
          comparesEqualTo(new BigDecimal("60")));
    }
    assertThat(as("GET", path + "?store=" + store, null, "CUSTOMER").getStatus(), is(403));

    // A manager held to another store neither sets nor reads this store's plans.
    assertThat(
        code(call("PUT", path, plan(store, variant, "1"), T, "MANAGER", elsewhere, null), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(call("GET", path + "?store=" + store, null, T, "MANAGER", elsewhere, null), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(
            call(
                "GET",
                path + "/by-variant?store=" + store + "&variant=" + variant,
                null,
                T,
                "MANAGER",
                elsewhere,
                null),
            403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(
            call("POST", path + "/compute?store=" + store, "", T, "MANAGER", elsewhere, null), 403),
        is("STORE_ACCESS_DENIED"));
    // ...and one held to this store reads and sets it.
    assertThat(
        call("PUT", path, plan(store, variant, "70"), T, "MANAGER", store, null).getStatus(),
        is(200));
    assertThat(orderingCostOf(T, store, variant), comparesEqualTo(new BigDecimal("70")));

    // Another business's staff, of every role, naming our store id: nothing of ours is seen, and
    // what they set is theirs alone.
    for (String role :
        new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      Response list = call("GET", path + "?store=" + store, null, OTHER_T, role, store, null);
      assertThat(role, Envelopes.okArray(list), hasSize(0));
      assertThat(
          role,
          code(
              call(
                  "GET",
                  path + "/by-variant?store=" + store + "&variant=" + variant,
                  null,
                  OTHER_T,
                  role,
                  store,
                  null),
              404),
          is("ROP_NOT_FOUND"));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      assertThat(
          role,
          call("PUT", path, plan(store, variant, "1"), OTHER_T, role, store, null).getStatus(),
          is(403));
    }
    assertThat(
        call("PUT", path, plan(store, variant, "2"), OTHER_T, "MANAGER", store, null).getStatus(),
        is(200));
    assertThat(orderingCostOf(T, store, variant), comparesEqualTo(new BigDecimal("70")));
    assertThat(orderingCostOf(OTHER_T, store, variant), comparesEqualTo(new BigDecimal("2")));
  }

  // ── over-write-off ─────────────────────────────────────────────────────────

  private static BigDecimal onHand(String tenant, String store, String variant) {
    return new BigDecimal(
        Envelopes.scalar(
            PG,
            "SELECT COALESCE(SUM(remaining_qty), 0) FROM inventory.inventory_batches WHERE"
                + " tenant_id = '"
                + tenant
                + "' AND store_id = '"
                + store
                + "' AND variant_id = '"
                + variant
                + "'"));
  }

  private static int count(String table, String tenant, String variant) {
    return Integer.parseInt(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory."
                + table
                + " WHERE tenant_id = '"
                + tenant
                + "' AND variant_id = '"
                + variant
                + "'"));
  }

  private static String adjustBody(String store, String variant, String delta) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"delta\":"
        + delta
        + ",\"reason\":\"Damaged\",\"reasonCode\":\"DAMAGED\"}";
  }

  /**
   * Catalogue INV adjustments gap 4: a negative adjustment larger than what is on hand is refused
   * (422 INSUFFICIENT_STOCK) and writes nothing: no batch is touched, no ADJUST movement and no
   * StockAdjusted event, and an Idempotency-Key it used is not spent, so the same request can be
   * made once the stock is there. Another business's staff writing off against our store id draw
   * nothing of ours.
   */
  @Test
  @DisplayName("A write-off larger than what is on hand is refused whole, and nothing is written")
  void aWriteOffLargerThanWhatIsOnHandIsRefusedWholeAndNothingIsWritten() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String receive =
        "{\"storeId\":\""
            + store
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":5,\"batchNo\":\"L1\",\"costPrice\":2.00}";
    Envelopes.created(as("POST", "/admin/inventory/receive", receive, "OWNER"));
    assertThat(onHand(T, store, variant), comparesEqualTo(new BigDecimal("5")));

    String key = Ids.newId().toString();
    String tooMany = adjustBody(store, variant, "-6");
    Response over = call("POST", "/admin/inventory/adjust", tooMany, T, "STOREKEEPER", null, key);
    assertThat(code(over, 422), is("INSUFFICIENT_STOCK"));
    assertThat(onHand(T, store, variant), comparesEqualTo(new BigDecimal("5")));
    assertThat(count("stock_movements", T, variant), is(1)); // the receipt alone
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.outbox WHERE event_type = 'StockAdjusted' AND"
                + " payload LIKE '%"
                + variant
                + "%'"),
        is("0"));

    // No level goes negative, and the refusal spent no key: once the stock is there, the same
    // request goes through.
    Envelopes.created(
        as(
            "POST",
            "/admin/inventory/receive",
            receive.replace("\"qty\":5", "\"qty\":1").replace("L1", "L2"),
            "OWNER"));
    assertThat(
        call("POST", "/admin/inventory/adjust", tooMany, T, "STOREKEEPER", null, key).getStatus(),
        is(200));
    assertThat(onHand(T, store, variant), comparesEqualTo(BigDecimal.ZERO));

    // Nothing on hand at all: refused the same way, and the level stays at zero.
    assertThat(
        code(
            as("POST", "/admin/inventory/adjust", adjustBody(store, variant, "-1"), "MANAGER"),
            422),
        is("INSUFFICIENT_STOCK"));
    assertThat(onHand(T, store, variant), comparesEqualTo(BigDecimal.ZERO));

    // Another business, naming our store and variant, has nothing to write off and moves nothing of
    // ours.
    Envelopes.created(as("POST", "/admin/inventory/receive", receive.replace("L1", "L3"), "OWNER"));
    for (String role : new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER"}) {
      assertThat(
          role,
          code(
              call(
                  "POST",
                  "/admin/inventory/adjust",
                  adjustBody(store, variant, "-1"),
                  OTHER_T,
                  role,
                  store,
                  null),
              422),
          is("INSUFFICIENT_STOCK"));
    }
    assertThat(
        call(
                "POST",
                "/admin/inventory/adjust",
                adjustBody(store, variant, "-1"),
                OTHER_T,
                "CASHIER",
                store,
                null)
            .getStatus(),
        is(403));
    assertThat(onHand(T, store, variant), comparesEqualTo(new BigDecimal("5")));
    assertThat(onHand(OTHER_T, store, variant), comparesEqualTo(BigDecimal.ZERO));
  }

  // ── standard cost and order modifiers ──────────────────────────────────────

  private static String costing(String store, String variant, String average) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"method\":\"AVERAGE\",\"averageCost\":"
        + average
        + "}";
  }

  private static BigDecimal averageCostOf(String tenant, String store, String variant) {
    return new BigDecimal(
        Envelopes.scalar(
            PG,
            "SELECT average_cost FROM inventory.costing_methods WHERE tenant_id = '"
                + tenant
                + "' AND store_id = '"
                + store
                + "' AND variant_id = '"
                + variant
                + "'"));
  }

  /**
   * The standard cost values the stock, so it is management's to set, at a store the caller keeps;
   * staff read it. Another business's staff, naming our store, see and move nothing of ours.
   */
  @Test
  @DisplayName("The standard cost is management's to set at a store they keep; staff read it")
  void theStandardCostIsManagementsToSetAtAStoreTheyKeep() {
    String store = Ids.newId().toString();
    String elsewhere = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String path = "/admin/inventory/costing-methods";

    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      assertThat(
          role, code(as("PUT", path, costing(store, variant, "99"), role), 403), is("FORBIDDEN"));
    }
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.costing_methods WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"),
        is("0"));

    assertThat(as("PUT", path, costing(store, variant, "3"), "MANAGER").getStatus(), is(200));
    assertThat(averageCostOf(T, store, variant), comparesEqualTo(new BigDecimal("3")));
    assertThat(as("PUT", path, costing(store, variant, "99"), "STOREKEEPER").getStatus(), is(403));
    assertThat(averageCostOf(T, store, variant), comparesEqualTo(new BigDecimal("3")));

    assertThat(
        code(call("PUT", path, costing(store, variant, "5"), T, "MANAGER", elsewhere, null), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        call("PUT", path, costing(store, variant, "4"), T, "MANAGER", store, null).getStatus(),
        is(200));
    assertThat(averageCostOf(T, store, variant), comparesEqualTo(new BigDecimal("4")));

    for (String role : new String[] {"MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(
          role, Envelopes.okArray(as("GET", path + "?store=" + store, null, role)), hasSize(1));
      assertThat(
          role,
          as("GET", path + "/by-variant?store=" + store + "&variant=" + variant, null, role)
              .getStatus(),
          is(200));
    }

    for (String role :
        new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(
          role,
          Envelopes.okArray(
              call("GET", path + "?store=" + store, null, OTHER_T, role, store, null)),
          hasSize(0));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      assertThat(
          role,
          call("PUT", path, costing(store, variant, "1"), OTHER_T, role, store, null).getStatus(),
          is(403));
    }
    assertThat(
        call("PUT", path, costing(store, variant, "2"), OTHER_T, "MANAGER", store, null)
            .getStatus(),
        is(200));
    assertThat(averageCostOf(T, store, variant), comparesEqualTo(new BigDecimal("4")));
    assertThat(averageCostOf(OTHER_T, store, variant), comparesEqualTo(new BigDecimal("2")));
  }

  private static String modifiers(String min) {
    return "{\"minOrderQty\":" + min + ",\"maxOrderQty\":100,\"lotMultiplier\":5}";
  }

  private static BigDecimal minOrderQtyOf(String tenant, String store, String variant) {
    return new BigDecimal(
        Envelopes.scalar(
            PG,
            "SELECT COALESCE(min_order_qty, 0) FROM inventory.reorder_point_plans WHERE tenant_id = '"
                + tenant
                + "' AND store_id = '"
                + store
                + "' AND variant_id = '"
                + variant
                + "'"));
  }

  /**
   * A plan's order modifiers are management's, at a plan's own store: another store is 403
   * STORE_ACCESS_DENIED, another business's plan is 404, whatever their role.
   */
  @Test
  @DisplayName("Order modifiers are management's, at the plan's store; another business finds none")
  void orderModifiersAreManagementsAtThePlansStore() {
    String store = Ids.newId().toString();
    String elsewhere = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String plans = "/admin/inventory/rop-plans";
    String planId =
        Envelopes.ok(as("PUT", plans, plan(store, variant, "50"), "OWNER")).getString("id");
    String path = plans + "/" + planId + "/order-modifiers";

    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      assertThat(role, code(as("PUT", path, modifiers("7"), role), 403), is("FORBIDDEN"));
    }
    assertThat(minOrderQtyOf(T, store, variant), comparesEqualTo(BigDecimal.ZERO));

    assertThat(
        code(call("PUT", path, modifiers("7"), T, "MANAGER", elsewhere, null), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(minOrderQtyOf(T, store, variant), comparesEqualTo(BigDecimal.ZERO));

    assertThat(as("PUT", path, modifiers("7"), "MANAGER").getStatus(), is(200));
    assertThat(minOrderQtyOf(T, store, variant), comparesEqualTo(new BigDecimal("7")));
    assertThat(call("PUT", path, modifiers("8"), T, "MANAGER", store, null).getStatus(), is(200));
    assertThat(minOrderQtyOf(T, store, variant), comparesEqualTo(new BigDecimal("8")));

    // Another business, any role that reaches the resource, finds no such plan; the rest are
    // refused as not management. Ours is as it was.
    for (String role : new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER"}) {
      assertThat(
          role,
          code(call("PUT", path, modifiers("1"), OTHER_T, role, store, null), 404),
          is("ROP_PLAN_NOT_FOUND"));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      assertThat(
          role, call("PUT", path, modifiers("1"), OTHER_T, role, store, null).getStatus(), is(403));
    }
    assertThat(minOrderQtyOf(T, store, variant), comparesEqualTo(new BigDecimal("8")));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A costing method nobody defined is refused, and one never set is not found")
  void aCostingMethodThatIsWrongOrNeverSetIsRefused() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String path = "/admin/inventory/costing-methods";
    String rows =
        "SELECT count(*) FROM inventory.costing_methods WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'";

    assertThat(
        code(
            as(
                "PUT",
                path,
                costing(store, variant, "3").replace("\"AVERAGE\"", "\"LIFO\""),
                "OWNER"),
            400),
        is("INVALID_COSTING_METHOD"));
    assertThat(Envelopes.scalar(PG, rows), is("0"));

    String read = path + "/by-variant?store=" + store + "&variant=" + variant;
    assertThat(code(as("GET", read, null, "OWNER"), 404), is("COSTING_METHOD_NOT_FOUND"));

    // Ours is set; another business's owner, naming our store and variant, still finds none.
    assertThat(as("PUT", path, costing(store, variant, "3"), "MANAGER").getStatus(), is(200));
    assertThat(as("GET", read, null, "OWNER").getStatus(), is(200));
    assertThat(
        code(call("GET", read, null, OTHER_T, "OWNER", null, null), 404),
        is("COSTING_METHOD_NOT_FOUND"));
    assertThat(averageCostOf(T, store, variant), comparesEqualTo(new BigDecimal("3")));
  }
}
