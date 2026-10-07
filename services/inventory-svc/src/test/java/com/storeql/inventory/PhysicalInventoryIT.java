package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A physical inventory (Gap #16) is the stocktake: each tag records what the books hold when it is
 * added, a person counts it, and completing the count moves the stock to what was found. A tag's
 * system quantity used to be whatever the caller wrote, and completion wrote ledger rows that no
 * level reads, so a count could neither be trusted nor change anything.
 */
@HelidonTest
class PhysicalInventoryIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = "01a090ae-8d1e-7d2c-a97b-d1b8025478e1";
  private static final String S = "01a090ae-8d1e-7a01-a378-a4972ea461c8";
  private static final String MANAGER = "01a090ae-8d1e-7b01-bde4-50df0324c37c";
  private static final String Z1 = "01a090ae-8d1e-7f01-a378-a4972ea461c8";
  private static final String Z2 = "01a090ae-8d1e-7f02-a378-a4972ea461c8";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response send(String role, String method, String path, String json) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", MANAGER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return "GET".equals(method) ? b.get() : b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus() < 300, is(true));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private void receive(String variant, int qty, String zone) {
    String body =
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":"
            + qty
            + ",\"batchNo\":\"PI-IT\",\"costPrice\":\"2.00\""
            + (zone == null ? "" : ",\"zoneId\":\"" + zone + "\"")
            + "}";
    Response r = send("MANAGER", "POST", "/admin/inventory/receive", body);
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private String start() {
    return data(send(
            "MANAGER",
            "POST",
            "/admin/inventory/physical-inventories",
            "{\"storeId\":\"" + S + "\",\"notes\":\"Year end\"}"))
        .getString("id");
  }

  private JsonObject tag(String pi, String variant, String zone, String claimed) {
    return data(
        send(
            "MANAGER",
            "POST",
            "/admin/inventory/physical-inventories/" + pi + "/tags",
            "{\"variantId\":\""
                + variant
                + "\""
                + (zone == null ? "" : ",\"zoneId\":\"" + zone + "\"")
                + (claimed == null ? "" : ",\"systemQty\":" + claimed)
                + "}"));
  }

  private void count(String role, String pi, String tagId, int counted) {
    data(
        send(
            role,
            "POST",
            "/admin/inventory/physical-inventories/" + pi + "/tags/" + tagId + "/count",
            "{\"countedQty\":" + counted + "}"));
  }

  private Response complete(String pi) {
    return send("MANAGER", "POST", "/admin/inventory/physical-inventories/" + pi + "/complete", "");
  }

  /** On hand as the levels report it — what a manager and every other screen read. */
  private BigDecimal onHand(String variant) {
    Response r = send("MANAGER", "GET", "/admin/inventory/levels?store=" + S + "&limit=100", null);
    String body = r.readEntity(String.class);
    for (JsonValue v :
        Json.createReader(new StringReader(body)).readObject().getJsonArray("data")) {
      JsonObject level = v.asJsonObject();
      if (variant.equals(level.getString("variantId"))) {
        return level.getJsonNumber("onHand").bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  private static BigDecimal inZone(String variant, String zone) throws SQLException {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT COALESCE(SUM(remaining_qty), 0) FROM inventory.inventory_batches"
                    + " WHERE tenant_id = ? AND store_id = ? AND variant_id = ? AND zone_id = ?")) {
      ps.setObject(1, Ids.parse(T));
      ps.setObject(2, Ids.parse(S));
      ps.setObject(3, Ids.parse(variant));
      ps.setObject(4, Ids.parse(zone));
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getBigDecimal(1);
      }
    }
  }

  /** Every movement the count wrote for a variant: "type qty actor reason". */
  private static List<String> movements(String pi, String variant) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT type, qty, actor_id, reason_code, batch_id FROM inventory.stock_movements"
                    + " WHERE tenant_id = ? AND ref_type = 'PHYSICAL_INVENTORY' AND ref_id = ?"
                    + " AND variant_id = ? ORDER BY created_at")) {
      ps.setObject(1, Ids.parse(T));
      ps.setObject(2, Ids.parse(pi));
      ps.setObject(3, Ids.parse(variant));
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              rs.getString("type")
                  + " "
                  + rs.getBigDecimal("qty").stripTrailingZeros().toPlainString()
                  + " "
                  + rs.getObject("actor_id")
                  + " "
                  + rs.getString("reason_code")
                  + (rs.getObject("batch_id") == null ? " no-batch" : ""));
        }
      }
    }
    return rows;
  }

  @Test
  @DisplayName("A tag records what the books hold at the store, not what the caller claims")
  void aTagSnapshotsTheBooks() {
    String variant = Ids.newId().toString();
    receive(variant, 10, null);
    String pi = start();
    JsonObject claimedHigh = tag(pi, variant, null, "100");
    assertThat(
        claimedHigh.getJsonNumber("systemQty").bigDecimalValue(), comparesEqualTo(BigDecimal.TEN));

    String unstocked = Ids.newId().toString();
    JsonObject nothingHere = tag(pi, unstocked, null, null);
    assertThat(
        nothingHere.getJsonNumber("systemQty").bigDecimalValue(), comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("Completing a count moves the level to what was found, against who completed it")
  void completingMovesTheLevels() throws SQLException {
    String short3 = Ids.newId().toString();
    String over3 = Ids.newId().toString();
    receive(short3, 10, null);
    receive(over3, 5, null);
    String pi = start();
    String lossTag = tag(pi, short3, null, null).getString("id");
    String gainTag = tag(pi, over3, null, null).getString("id");
    // Counting is anyone's work at the store; a cashier may enter what they found.
    count("CASHIER", pi, lossTag, 7);
    count("STOREKEEPER", pi, gainTag, 8);
    assertThat(onHand(short3), comparesEqualTo(BigDecimal.TEN));

    Response done = complete(pi);
    assertThat(done.readEntity(String.class), done.getStatus(), is(200));

    assertThat(onHand(short3), comparesEqualTo(new BigDecimal("7")));
    assertThat(onHand(over3), comparesEqualTo(new BigDecimal("8")));
    assertThat(
        movements(pi, short3),
        is(List.of("ADJUST -3 " + MANAGER + " PHYSICAL_INVENTORY_VARIANCE")));
    assertThat(
        movements(pi, over3), is(List.of("ADJUST 3 " + MANAGER + " PHYSICAL_INVENTORY_VARIANCE")));
  }

  @Test
  @DisplayName("A tag in a zone counts and corrects that zone only")
  void aZoneTagCorrectsItsZone() throws SQLException {
    String variant = Ids.newId().toString();
    receive(variant, 4, Z1);
    receive(variant, 6, Z2);
    String pi = start();
    JsonObject tag = tag(pi, variant, Z1, null);
    assertThat(
        tag.getJsonNumber("systemQty").bigDecimalValue(), comparesEqualTo(new BigDecimal("4")));
    count("MANAGER", pi, tag.getString("id"), 3);
    assertThat(complete(pi).getStatus(), is(200));
    assertThat(inZone(variant, Z1), comparesEqualTo(new BigDecimal("3")));
    assertThat(inZone(variant, Z2), comparesEqualTo(new BigDecimal("6")));
    assertThat(onHand(variant), comparesEqualTo(new BigDecimal("9")));
  }

  /**
   * The draw prefers the tag's zone but would carry on into others; the check before it is what
   * keeps a zone's loss in that zone. Stock that left the shelf after it was tagged leaves less
   * there than the count found missing, and the completion is refused rather than taken from
   * elsewhere.
   */
  @Test
  @DisplayName("A zone's loss is never drawn from another zone; short there, the count is refused")
  void aZoneLossNeverSpillsIntoAnotherZone() throws SQLException {
    String variant = Ids.newId().toString();
    receive(variant, 4, Z1);
    receive(variant, 6, Z2);
    String pi = start();
    JsonObject tag = tag(pi, variant, Z1, null);
    count("MANAGER", pi, tag.getString("id"), 1);
    // Three of zone 1's four leave the shelf after it was tagged.
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "UPDATE inventory.inventory_batches SET remaining_qty = 1"
                    + " WHERE tenant_id = ? AND variant_id = ? AND zone_id = ?")) {
      ps.setObject(1, Ids.parse(T));
      ps.setObject(2, Ids.parse(variant));
      ps.setObject(3, Ids.parse(Z1));
      assertThat(ps.executeUpdate(), is(1));
    }
    Response done = complete(pi);
    String body = done.readEntity(String.class);
    assertThat(body, done.getStatus(), is(422));
    assertThat(body, containsString("INSUFFICIENT_STOCK"));
    assertThat(inZone(variant, Z1), comparesEqualTo(BigDecimal.ONE));
    assertThat(inZone(variant, Z2), comparesEqualTo(new BigDecimal("6")));
    assertThat(movements(pi, variant).size(), is(0));
  }

  @Test
  @DisplayName("A completed count takes no more tags and posts nothing twice")
  void aCompletedCountIsClosed() throws SQLException {
    String variant = Ids.newId().toString();
    receive(variant, 10, null);
    String pi = start();
    String tagId = tag(pi, variant, null, null).getString("id");
    count("MANAGER", pi, tagId, 9);
    assertThat(complete(pi).getStatus(), is(200));

    Response again = complete(pi);
    assertThat(again.getStatus(), is(409));
    Response late =
        send(
            "MANAGER",
            "POST",
            "/admin/inventory/physical-inventories/" + pi + "/tags",
            "{\"variantId\":\"" + Ids.newId() + "\"}");
    String body = late.readEntity(String.class);
    assertThat(body, late.getStatus(), is(409));
    assertThat(body, containsString("PI_ALREADY_COMPLETED"));
    Response recount =
        send(
            "MANAGER",
            "POST",
            "/admin/inventory/physical-inventories/" + pi + "/tags/" + tagId + "/count",
            "{\"countedQty\":4}");
    String recounted = recount.readEntity(String.class);
    assertThat(recounted, recount.getStatus(), is(409));
    assertThat(recounted, containsString("PI_ALREADY_COMPLETED"));
    assertThat(movements(pi, variant).size(), is(1));
    assertThat(onHand(variant), comparesEqualTo(new BigDecimal("9")));
  }

  @Test
  @DisplayName("A variant is tagged once per count, and a count below zero is refused")
  void noUnitIsCountedTwice() {
    String variant = Ids.newId().toString();
    receive(variant, 4, Z1);
    String pi = start();
    String path = "/admin/inventory/physical-inventories/" + pi + "/tags";
    String zoneTag = tag(pi, variant, Z1, null).getString("id");
    // The same zone again, or the whole store over a zone already tagged: counted twice.
    for (String body :
        new String[] {
          "{\"variantId\":\"" + variant + "\",\"zoneId\":\"" + Z1 + "\"}",
          "{\"variantId\":\"" + variant + "\"}"
        }) {
      Response twice = send("MANAGER", "POST", path, body);
      String answer = twice.readEntity(String.class);
      assertThat(answer, twice.getStatus(), is(409));
      assertThat(answer, containsString("PI_TAG_EXISTS"));
    }
    // Another zone is its own shelf.
    assertThat(tag(pi, variant, Z2, null).getString("zoneId"), is(Z2));

    Response negative =
        send("MANAGER", "POST", path + "/" + zoneTag + "/count", "{\"countedQty\":-1}");
    assertThat(negative.getStatus(), is(400));
    negative.close();
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  private static String codeOf(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return com.storeql.test.Envelopes.parse(body).getString("code");
  }

  @Test
  @DisplayName("A count that is not there is not found, whatever is asked of it")
  void aCountThatIsNotThereIsNotFound() {
    String unknown = Ids.newId().toString();
    String base = "/admin/inventory/physical-inventories/" + unknown;
    assertThat(codeOf(send("OWNER", "GET", base, null), 404), is("PI_NOT_FOUND"));
    assertThat(
        codeOf(
            send("MANAGER", "POST", base + "/tags", "{\"variantId\":\"" + Ids.newId() + "\"}"),
            404),
        is("PI_NOT_FOUND"));
    assertThat(codeOf(send("MANAGER", "POST", base + "/complete", ""), 404), is("PI_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "A tag that is not on the count, or is on another count, is not found; none is counted")
  void aTagThatIsNotOnTheCountIsNotFound() {
    String variant = Ids.newId().toString();
    receive(variant, 4, null);
    String pi = start();
    String other = start();
    String foreignTag = tag(other, variant, null, null).getString("id");

    for (String tagId : new String[] {Ids.newId().toString(), foreignTag}) {
      assertThat(
          codeOf(
              send(
                  "MANAGER",
                  "POST",
                  "/admin/inventory/physical-inventories/" + pi + "/tags/" + tagId + "/count",
                  "{\"countedQty\":1}"),
              404),
          is("TAG_NOT_FOUND"));
    }
    // The other count's tag is untouched.
    JsonObject untouched =
        data(send("OWNER", "GET", "/admin/inventory/physical-inventories/" + other, null));
    JsonObject theTag =
        untouched.getJsonArray("tags").stream()
            .map(JsonValue::asJsonObject)
            .filter(t -> foreignTag.equals(t.getString("id")))
            .findFirst()
            .orElseThrow();
    // JSON-B omits a null member, so an uncounted tag has no countedQty key (or a JSON null).
    assertThat(!theTag.containsKey("countedQty") || theTag.isNull("countedQty"), is(true));
  }
}
