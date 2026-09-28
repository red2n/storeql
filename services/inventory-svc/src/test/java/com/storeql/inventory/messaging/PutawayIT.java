package com.storeql.inventory.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Directed putaway (readiness review, Supply chain, warehouse &amp; logistics).
 *
 * <p>Stock arriving with no zone — a purchase receipt, a manual receipt that named none — is placed
 * by the store's putaway rule for the product, else the store's default; with neither it lands on
 * the putaway list for a person to place with one tap. Written before the code.
 */
@HelidonTest
class PutawayIT {

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

  private static final String T = "01a093ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a093ae-611e-702c-a97b-d1b8025478e2";
  private static final String STORE = "01a093ae-611e-703c-a378-a4972ea461e1";
  private static final String CHILLED = "01a093ae-611e-7037-a4b7-c854f0266ae1";
  private static final String AMBIENT = "01a093ae-611e-7037-a4b7-c854f0266ae2";
  private static final String ODDITY = "01a093ae-611e-7037-a4b7-c854f0266ae3";
  private static final String COLD_ROOM = "01a093ae-611e-7041-a4b7-c854f0266aa1";
  private static final String BACK_STORE = "01a093ae-611e-7041-a4b7-c854f0266aa2";
  private static final String AISLE_3 = "01a093ae-611e-7041-a4b7-c854f0266aa3";
  private static final String PO = "01a093ae-611e-705c-994c-5daee3fbd0e1";
  private static final String USER = "01a093ae-611e-700b-bde4-50df0324c3e1";

  @Inject WebTarget target;
  @Inject GoodsReceivedHandler goodsReceived;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE inventory.putaway_tasks, inventory.putaway_rules, inventory.lot_genealogy,"
              + " inventory.stock_movements, inventory.reservations, inventory.inventory_batches,"
              + " inventory.processed_events, inventory.outbox CASCADE");
    }
  }

  private Response call(String method, String path, String json, String tenant, String roles) {
    return call(method, path, json, tenant, roles, null);
  }

  private Response call(
      String method, String path, String json, String tenant, String roles, String storeIds) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles)
            .header("Idempotency-Key", Ids.newId().toString());
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      case "DELETE" -> b.delete();
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private JsonObject rule(String variant, String zone) {
    return Envelopes.ok(
        call(
            "PUT",
            "/admin/inventory/putaway/rules",
            "{\"storeId\":\""
                + STORE
                + "\""
                + (variant == null ? "" : ",\"variantId\":\"" + variant + "\"")
                + ",\"zoneId\":\""
                + zone
                + "\"}",
            T,
            "MANAGER"));
  }

  private String batchZone(String batchId) {
    return Envelopes.scalar(
        PG, "SELECT zone_id::text FROM inventory.inventory_batches WHERE id = '" + batchId + "'");
  }

  private static String goodsReceivedEvent(String variant, int qty) {
    return goodsReceivedEvent(variant, qty, null);
  }

  private static String goodsReceivedEvent(String variant, int qty, String batchNo) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"tenantId\":\""
        + T
        + "\",\"storeId\":\""
        + STORE
        + "\",\"refId\":\""
        + Ids.newId()
        + "\",\"poId\":\""
        + PO
        + "\",\"ownership\":\"OWNED\",\"supplierId\":null,\"dutyStatus\":\"DUTY_PAID\","
        + "\"lines\":[{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + (batchNo == null ? "" : ",\"batchNo\":\"" + batchNo + "\"")
        + ",\"costPrice\":2.00}]}";
  }

  @Test
  void stockArrivingWithNoZoneIsDirectedByTheRuleOrWaitsToBePlaced() {
    // Chilled goods go to the cold room; anything else the store did not name goes to the back
    // store.
    JsonObject chilledRule = rule(CHILLED, COLD_ROOM);
    assertThat(chilledRule.getString("variantId"), is(CHILLED));
    assertThat(chilledRule.getString("zoneId"), is(COLD_ROOM));
    rule(null, BACK_STORE);
    // A rule is one per product per store: setting it again replaces the zone.
    assertThat(rule(CHILLED, COLD_ROOM).getString("zoneId"), is(COLD_ROOM));
    JsonArray rules =
        Envelopes.okArray(
            call("GET", "/admin/inventory/putaway/rules?storeId=" + STORE, null, T, "OWNER"));
    assertThat(rules.size(), is(2));

    // A purchase receipt names no zone: the chilled batch lands in the cold room, the ambient one
    // in the back store, and nothing waits on the list.
    goodsReceived.handle(goodsReceivedEvent(CHILLED, 6));
    goodsReceived.handle(goodsReceivedEvent(AMBIENT, 4));
    String chilledBatch =
        Envelopes.scalar(
            PG,
            "SELECT id::text FROM inventory.inventory_batches WHERE variant_id = '"
                + CHILLED
                + "'");
    String ambientBatch =
        Envelopes.scalar(
            PG,
            "SELECT id::text FROM inventory.inventory_batches WHERE variant_id = '"
                + AMBIENT
                + "'");
    assertThat(batchZone(chilledBatch), is(COLD_ROOM));
    assertThat(batchZone(ambientBatch), is(BACK_STORE));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/putaway/tasks?storeId=" + STORE, null, T, "OWNER"))
            .size(),
        is(0));
    // A manual receipt that names a zone keeps it.
    JsonObject placed =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"variantId\":\""
                    + CHILLED
                    + "\",\"qty\":2,\"zoneId\":\""
                    + AISLE_3
                    + "\",\"costPrice\":2.00}",
                T,
                "OWNER"));
    assertThat(batchZone(placed.getString("id")), is(AISLE_3));

    // With the default gone, an oddity nobody has a rule for waits on the list.
    String defaultId = null;
    for (int i = 0; i < rules.size(); i++) {
      if (!rules.getJsonObject(i).containsKey("variantId"))
        defaultId = rules.getJsonObject(i).getString("id");
    }
    // A manager of another store removes no rule of this one: the rule's own store is checked.
    assertThat(
        call(
                "DELETE",
                "/admin/inventory/putaway/rules/" + defaultId,
                null,
                T,
                "MANAGER",
                Ids.newId().toString())
            .getStatus(),
        is(403));
    assertThat(
        call("DELETE", "/admin/inventory/putaway/rules/" + defaultId, null, T, "MANAGER")
            .getStatus(),
        is(204));
    goodsReceived.handle(goodsReceivedEvent(ODDITY, 3));
    String oddBatch =
        Envelopes.scalar(
            PG,
            "SELECT id::text FROM inventory.inventory_batches WHERE variant_id = '" + ODDITY + "'");
    assertThat(batchZone(oddBatch), is(nullValue()));
    JsonArray tasks =
        Envelopes.okArray(
            call("GET", "/admin/inventory/putaway/tasks?storeId=" + STORE, null, T, "OWNER"));
    assertThat(tasks.size(), is(1));
    JsonObject task = tasks.getJsonObject(0);
    assertThat(task.getString("batchId"), is(oddBatch));
    assertThat(task.getString("variantId"), is(ODDITY));
    assertThat(task.getString("status"), is("OPEN"));
    // Placing needs a zone; a storekeeper places it; placed once; another business sees no task.
    assertThat(
        code(
            call(
                "POST",
                "/admin/inventory/putaway/tasks/" + task.getString("id") + "/place",
                "{}",
                T,
                "STOREKEEPER"),
            400),
        is("INVENTORY_PUTAWAY_ZONE_REQUIRED"));
    assertThat(
        call(
                "POST",
                "/admin/inventory/putaway/tasks/" + task.getString("id") + "/place",
                "{\"zoneId\":\"" + AISLE_3 + "\"}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));
    JsonObject done =
        Envelopes.ok(
            call(
                "POST",
                "/admin/inventory/putaway/tasks/" + task.getString("id") + "/place",
                "{\"zoneId\":\"" + AISLE_3 + "\"}",
                T,
                "STOREKEEPER"));
    assertThat(done.getString("status"), is("PLACED"));
    assertThat(done.getString("placedZoneId"), is(AISLE_3));
    assertThat(batchZone(oddBatch), is(AISLE_3));
    assertThat(
        code(
            call(
                "POST",
                "/admin/inventory/putaway/tasks/" + task.getString("id") + "/place",
                "{\"zoneId\":\"" + AISLE_3 + "\"}",
                T,
                "STOREKEEPER"),
            409),
        is("INVENTORY_PUTAWAY_TASK_PLACED"));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/putaway/tasks?storeId=" + STORE, null, T, "OWNER"))
            .size(),
        is(0));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/putaway/rules?storeId=" + STORE, null, T2, "OWNER"))
            .size(),
        is(0));
    assertThat(
        call(
                "PUT",
                "/admin/inventory/putaway/rules",
                "{\"storeId\":\"" + STORE + "\",\"zoneId\":\"" + BACK_STORE + "\"}",
                T,
                "STOREKEEPER")
            .getStatus(),
        is(403));
  }

  @Test
  void aTaskNamesItsBatchByNumberAndNoOtherBusinessSeesIt() {
    // No rule at all: both arrivals wait on the list. The supplier's lot number rides with one; the
    // other came with none, and a purchase receipt makes none up.
    goodsReceived.handle(goodsReceivedEvent(ODDITY, 3, "LOT-ODD-7"));
    goodsReceived.handle(goodsReceivedEvent(AMBIENT, 5));
    String oddBatch =
        Envelopes.scalar(
            PG,
            "SELECT id::text FROM inventory.inventory_batches WHERE variant_id = '" + ODDITY + "'");
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT batch_no FROM inventory.inventory_batches WHERE variant_id = '" + ODDITY + "'"),
        is("LOT-ODD-7"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT batch_no FROM inventory.inventory_batches WHERE variant_id = '"
                + AMBIENT
                + "'"),
        is(nullValue()));

    JsonArray tasks =
        Envelopes.okArray(
            call("GET", "/admin/inventory/putaway/tasks?storeId=" + STORE, null, T, "STOREKEEPER"));
    assertThat(tasks.size(), is(2));
    JsonObject odd = null;
    JsonObject ambient = null;
    for (int i = 0; i < tasks.size(); i++) {
      JsonObject t = tasks.getJsonObject(i);
      if (ODDITY.equals(t.getString("variantId"))) odd = t;
      if (AMBIENT.equals(t.getString("variantId"))) ambient = t;
    }
    assertThat(odd.getString("batchId"), is(oddBatch));
    assertThat(odd.getString("batchNo"), is("LOT-ODD-7"));
    assertThat(ambient.getString("batchId"), is(notNullValue()));
    assertThat(ambient.containsKey("batchNo"), is(false));

    // Another business's staff of every role, even naming our store, see no task and place none;
    // our own shopper is refused.
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(
          Envelopes.okArray(
                  call(
                      "GET",
                      "/admin/inventory/putaway/tasks?storeId=" + STORE,
                      null,
                      T2,
                      role,
                      STORE))
              .size(),
          is(0));
      assertThat(
          Envelopes.okArray(call("GET", "/admin/inventory/putaway/tasks", null, T2, role, STORE))
              .size(),
          is(0));
    }
    assertThat(
        code(
            call(
                "POST",
                "/admin/inventory/putaway/tasks/" + odd.getString("id") + "/place",
                "{\"zoneId\":\"" + AISLE_3 + "\"}",
                T2,
                "OWNER",
                STORE),
            404),
        is("INVENTORY_PUTAWAY_TASK_NOT_FOUND"));
    assertThat(batchZone(oddBatch), is(nullValue()));
    assertThat(
        call("GET", "/admin/inventory/putaway/tasks?storeId=" + STORE, null, T, "CUSTOMER")
            .getStatus(),
        is(403));

    // The placement answers with the batch's number too.
    JsonObject done =
        Envelopes.ok(
            call(
                "POST",
                "/admin/inventory/putaway/tasks/" + odd.getString("id") + "/place",
                "{\"zoneId\":\"" + AISLE_3 + "\"}",
                T,
                "STOREKEEPER"));
    assertThat(done.getString("batchNo"), is("LOT-ODD-7"));
    assertThat(batchZone(oddBatch), is(AISLE_3));
  }
}
