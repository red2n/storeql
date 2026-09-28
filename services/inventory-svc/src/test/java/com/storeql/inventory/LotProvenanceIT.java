package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stock that moves keeps what it is (SJ-D71): a transfer, a move order and a return draw from
 * batches with a lot, a use-by date and a cost, and what arrives must carry each of those across,
 * one batch per source batch, with the genealogy link that ties them — so the expiring-batches view
 * still sees moved stock, a recall on the lot holds it as in scope rather than as unknown, and the
 * margin report still knows what it cost. Stock with no lot moves under the system number it always
 * did. Real Postgres; Kafka and Consul disabled.
 */
@HelidonTest
class LotProvenanceIT {
  private static final PostgresSupport PG;

  /** The recall asks tenant-svc where the business trades; a British one, so GPSR does not bind. */
  private static final TenantSvcStub TENANTS;

  static {
    PG = PostgresSupport.start();
    TENANTS = TenantSvcStub.start().with("01a090ae-611e-7011-ae7d-1bd68c966ff6", "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.inventory.food-safety.overdue-sweeper.enabled", "false");
  }

  private static final String T = "01a090ae-611e-7011-ae7d-1bd68c966ff6";
  private static final String USER = "01a090ae-611e-7033-93f1-01903ac69340";

  @Inject WebTarget target;
  @Inject InventoryService inventory;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  // ── transfers ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A direct transfer carries each source lot, its use-by date and its cost across")
  void aDirectTransferCarriesEachSourceLotItsDateAndItsCost() {
    String a = uuid();
    String b = uuid();
    String variant = uuid();
    String l1 = receive(a, variant, "10", "L1", "2026-10-01", "2.50");
    String l2 = receive(a, variant, "5", "L2", "2026-12-01", "3.00");

    String order = createTransfer(a, b, variant, "DIRECT", "12");
    ok(post("/admin/inventory/transfers/" + order + "/ship", null));

    // Drawn soonest-expiry first: all of L1, then two of L2 — and that is what arrives.
    Map<String, JsonObject> arrived = batchesByLot(b, variant);
    assertThat(arrived.keySet().toString(), arrived.size(), is(2));
    JsonObject bL1 = arrived.get("L1");
    assertThat(bL1.getJsonNumber("remainingQty").bigDecimalValue(), is(new BigDecimal("10.000")));
    assertThat(bL1.getString("expiryDate"), is("2026-10-01"));
    assertThat(
        bL1.getJsonNumber("costPrice").bigDecimalValue(), comparesEqualTo(new BigDecimal("2.50")));
    JsonObject bL2 = arrived.get("L2");
    assertThat(bL2.getJsonNumber("remainingQty").bigDecimalValue(), is(new BigDecimal("2.000")));
    assertThat(bL2.getString("expiryDate"), is("2026-12-01"));
    assertThat(
        bL2.getJsonNumber("costPrice").bigDecimalValue(), comparesEqualTo(new BigDecimal("3.00")));

    // The source is what it was, less what left.
    Map<String, JsonObject> left = batchesByLot(a, variant);
    assertThat(
        left.get("L1").getJsonNumber("remainingQty").bigDecimalValue(),
        is(new BigDecimal("0.000")));
    assertThat(
        left.get("L2").getJsonNumber("remainingQty").bigDecimalValue(),
        is(new BigDecimal("3.000")));

    // The expiring view at the receiving store sees the moved stock — the harm SJ-D71 named.
    List<String> expiring = expiringLots(b, 30);
    assertThat(expiring, is(List.of("L1")));

    // Each arrival is the child of the batch it came from.
    JsonArray children = descendants(l1);
    assertThat(children, hasSize(1));
    assertThat(children.getJsonObject(0).getString("childBatchId"), is(bL1.getString("id")));
    assertThat(
        children.getJsonObject(0).getJsonNumber("qty").bigDecimalValue(),
        is(new BigDecimal("10.000")));
    assertThat(children.getJsonObject(0).getString("relationType"), is("SPLIT"));
    assertThat(descendants(l2).getJsonObject(0).getString("childBatchId"), is(bL2.getString("id")));

    // A recall on the lot holds the moved stock as in scope, not as unknown.
    JsonObject recall = created(post("/admin/recalls", recallOf(variant, "L1")));
    Map<String, String> match = new java.util.HashMap<>();
    for (var v : recall.getJsonArray("batches")) {
      match.put(v.asJsonObject().getString("batchId"), v.asJsonObject().getString("match"));
    }
    assertThat(match.get(bL1.getString("id")), is("IN_SCOPE"));
    assertThat(match.containsKey(bL2.getString("id")), is(false));
  }

  @Test
  @DisplayName("An in-transit transfer carries the lots when it arrives, from what left")
  void anInTransitTransferCarriesTheLotsWhenItArrives() {
    String a = uuid();
    String b = uuid();
    String variant = uuid();
    receive(a, variant, "4", "L1", "2026-10-01", "2.50");
    receive(a, variant, "4", "L2", "2026-11-01", "2.75");

    String order = createTransfer(a, b, variant, "INTRANSIT", "6");
    ok(post("/admin/inventory/transfers/" + order + "/ship", null));
    assertThat("nothing arrives while in transit", batchesByLot(b, variant).size(), is(0));

    ok(post("/admin/inventory/transfers/" + order + "/receive", null));
    Map<String, JsonObject> arrived = batchesByLot(b, variant);
    assertThat(arrived.keySet().toString(), arrived.size(), is(2));
    assertThat(
        arrived.get("L1").getJsonNumber("remainingQty").bigDecimalValue(),
        is(new BigDecimal("4.000")));
    assertThat(arrived.get("L1").getString("expiryDate"), is("2026-10-01"));
    assertThat(
        arrived.get("L2").getJsonNumber("remainingQty").bigDecimalValue(),
        is(new BigDecimal("2.000")));
    assertThat(
        arrived.get("L2").getJsonNumber("costPrice").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("2.75")));
  }

  @Test
  @DisplayName("Stock with no lot moves under the system number, and still keeps its date")
  void stockWithNoLotMovesUnderTheSystemNumberAndKeepsItsDate() {
    String a = uuid();
    String b = uuid();
    String variant = uuid();
    receive(a, variant, "3", null, "2026-10-15", null);

    String order = createTransfer(a, b, variant, "DIRECT", "3");
    ok(post("/admin/inventory/transfers/" + order + "/ship", null));

    List<JsonObject> arrived = batches(b, variant);
    assertThat(arrived, hasSize(1));
    assertThat(arrived.get(0).getString("batchNo"), is("TO-" + Ids.shortRef(Ids.parse(order))));
    assertThat(arrived.get(0).getString("expiryDate"), is("2026-10-15"));
    assertThat(
        arrived.get(0).containsKey("costPrice") && !arrived.get(0).isNull("costPrice"), is(false));
    assertThat(expiringLots(b, 30), is(List.of("TO-" + Ids.shortRef(Ids.parse(order)))));
  }

  @Test
  @DisplayName("A transfer short of stock takes nothing and delivers nothing")
  void aTransferShortOfStockTakesNothingAndDeliversNothing() {
    String a = uuid();
    String b = uuid();
    String variant = uuid();
    receive(a, variant, "2", "L1", "2026-10-01", "2.50");

    String order = createTransfer(a, b, variant, "DIRECT", "5");
    Response r = post("/admin/inventory/transfers/" + order + "/ship", null);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(422));
    assertThat(body, containsString("INSUFFICIENT_STOCK"));
    assertThat(batches(b, variant), hasSize(0));
    assertThat(
        batchesByLot(a, variant).get("L1").getJsonNumber("remainingQty").bigDecimalValue(),
        is(new BigDecimal("2.000")));
  }

  // ── move orders ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A move order carries the lot it picked")
  void aMoveOrderCarriesTheLotItPicked() {
    String a = uuid();
    String variant = uuid();
    String l1 = receive(a, variant, "6", "L1", "2026-10-01", "1.20");

    String order = createMoveOrder(a, variant, "4");
    ok(post("/admin/inventory/move-orders/" + order + "/pick", null));

    // The same store: the source, drawn down, and the picked child beside it under the same lot.
    List<JsonObject> all = batches(a, variant);
    assertThat(all, hasSize(2));
    JsonObject child =
        all.stream().filter(x -> !x.getString("id").equals(l1)).findFirst().orElseThrow();
    assertThat(child.getString("batchNo"), is("L1"));
    assertThat(child.getString("expiryDate"), is("2026-10-01"));
    assertThat(
        child.getJsonNumber("costPrice").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("1.20")));
    assertThat(child.getJsonNumber("remainingQty").bigDecimalValue(), is(new BigDecimal("4.000")));
    assertThat(
        descendants(l1).getJsonObject(0).getString("childBatchId"), is(child.getString("id")));
  }

  // ── returns ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A return comes back under the lot it was sold from, and never twice")
  void aReturnComesBackUnderTheLotItWasSoldFromAndNeverTwice() {
    String a = uuid();
    String variant = uuid();
    String l1 = receive(a, variant, "10", "L1", "2026-10-01", "2.50");
    UUID order = Ids.newId();
    inventory.deductSaleFromOrderOnce(
        Ids.newId(),
        "it",
        Ids.parse(T),
        Ids.parse(a),
        Ids.parse(variant),
        new BigDecimal("3"),
        order);

    // Two of the three come back: a child of L1, with its date and cost.
    assertThat(returnOnce(a, variant, "2", order), is(true));
    List<JsonObject> after = batches(a, variant);
    assertThat(after, hasSize(2));
    JsonObject back =
        after.stream().filter(x -> !x.getString("id").equals(l1)).findFirst().orElseThrow();
    assertThat(back.getString("batchNo"), is("L1"));
    assertThat(back.getString("expiryDate"), is("2026-10-01"));
    assertThat(
        back.getJsonNumber("costPrice").bigDecimalValue(), comparesEqualTo(new BigDecimal("2.50")));
    assertThat(back.getJsonNumber("remainingQty").bigDecimalValue(), is(new BigDecimal("2.000")));
    JsonArray links = descendants(l1);
    assertThat(links, hasSize(1));
    assertThat(links.getJsonObject(0).getString("notes"), startsWith("RETURN "));

    // The third comes back under the lot too; a fourth was never sold from it, so it comes back
    // as a return with no provenance — under the system number, as before.
    assertThat(returnOnce(a, variant, "1", order), is(true));
    assertThat(returnOnce(a, variant, "1", order), is(true));
    Map<String, BigDecimal> byNumber =
        batches(a, variant).stream()
            .filter(x -> !x.getString("id").equals(l1))
            .collect(
                Collectors.toMap(
                    x -> x.getString("batchNo"),
                    x -> x.getJsonNumber("remainingQty").bigDecimalValue(),
                    BigDecimal::add));
    assertThat(byNumber.get("L1"), is(new BigDecimal("3.000")));
    assertThat(byNumber.get("RET-" + Ids.shortRef(order)), is(new BigDecimal("1.000")));
    assertThat(expiringLots(a, 30).stream().filter("L1"::equals).count(), is(3L));
  }

  @Test
  @DisplayName("A return with no sale behind it is the anonymous return it always was")
  void aReturnWithNoSaleBehindItIsTheAnonymousReturnItAlwaysWas() {
    String a = uuid();
    String variant = uuid();
    UUID order = Ids.newId();
    assertThat(returnOnce(a, variant, "1", order), is(true));
    List<JsonObject> all = batches(a, variant);
    assertThat(all, hasSize(1));
    assertThat(all.get(0).getString("batchNo"), is("RET-" + Ids.shortRef(order)));
    assertThat(all.get(0).containsKey("expiryDate") && !all.get(0).isNull("expiryDate"), is(false));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private boolean returnOnce(String store, String variant, String qty, UUID order) {
    return inventory.receiveReturnFromOrderOnce(
        Ids.newId(),
        "it",
        Ids.parse(T),
        Ids.parse(store),
        Ids.parse(variant),
        new BigDecimal(qty),
        order);
  }

  private String receive(
      String store, String variant, String qty, String lot, String expiry, String cost) {
    StringBuilder json =
        new StringBuilder("{\"storeId\":\"")
            .append(store)
            .append("\",\"variantId\":\"")
            .append(variant)
            .append("\",\"qty\":")
            .append(qty);
    if (lot != null) json.append(",\"batchNo\":\"").append(lot).append('"');
    if (expiry != null) json.append(",\"expiryDate\":\"").append(expiry).append('"');
    if (cost != null) json.append(",\"costPrice\":").append(cost);
    return created(post("/admin/inventory/receive", json.append('}').toString())).getString("id");
  }

  private String createTransfer(String from, String to, String variant, String type, String qty) {
    return created(
            post(
                "/admin/inventory/transfers",
                ("{\"fromStoreId\":\"%s\",\"toStoreId\":\"%s\",\"transferType\":\"%s\","
                        + "\"lines\":[{\"variantId\":\"%s\",\"requestedQty\":%s}]}")
                    .formatted(from, to, type, variant, qty)))
        .getString("id");
  }

  private String createMoveOrder(String store, String variant, String qty) {
    return created(
            post(
                "/admin/inventory/move-orders",
                ("{\"fromStoreId\":\"%s\",\"toStoreId\":\"%s\",\"fromZone\":\"BACK\","
                        + "\"toZone\":\"FLOOR\",\"lines\":[{\"variantId\":\"%s\","
                        + "\"requestedQty\":%s}]}")
                    .formatted(store, store, variant, qty)))
        .getString("id");
  }

  private static String recallOf(String variant, String lot) {
    return "{\"reference\":\"FSA-"
        + Ids.shortRef(Ids.newId())
        + "\",\"kind\":\"RECALL\",\"hazard\":\"ALLERGEN\",\"reason\":\"Undeclared peanut\","
        + "\"source\":\"FSA\",\"customerNotice\":\"Do not eat. Return it for a refund.\","
        + "\"remedies\":[\"REFUND\",\"REPLACEMENT\"],\"contactPhone\":\"0800 100 200\","
        + "\"items\":[{\"variantId\":\""
        + variant
        + "\",\"batchNo\":\""
        + lot
        + "\"}]}";
  }

  private List<JsonObject> batches(String store, String variant) {
    return list(get("/admin/inventory/batches?store=" + store + "&variant=" + variant));
  }

  private Map<String, JsonObject> batchesByLot(String store, String variant) {
    return batches(store, variant).stream()
        .collect(Collectors.toMap(x -> x.getString("batchNo"), Function.identity()));
  }

  private List<String> expiringLots(String store, int withinDays) {
    return list(
            get("/admin/inventory/batches/expiring?store=" + store + "&withinDays=" + withinDays))
        .stream()
        .map(x -> x.getString("batchNo"))
        .toList();
  }

  private JsonArray descendants(String batchId) {
    return data(get("/admin/inventory/lot-genealogy/batch/" + batchId + "/descendants"))
        .getJsonArray("descendants");
  }

  private Response post(String path, String json) {
    return request(path).post(Entity.entity(json == null ? "" : json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path) {
    int q = path.indexOf('?');
    WebTarget t = target.path(q < 0 ? path : path.substring(0, q));
    if (q >= 0) {
      for (String param : path.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    return t.request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .header("X-User-Id", USER)
        .get();
  }

  private jakarta.ws.rs.client.Invocation.Builder request(String path) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .header("X-User-Id", USER);
  }

  private static String uuid() {
    return Ids.newId().toString();
  }

  private static JsonObject parse(String body) {
    try (var r = Json.createReader(new StringReader(body))) {
      return r.readObject();
    }
  }

  private static JsonObject created(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return parse(body).getJsonObject("data");
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonObject("data");
  }

  private static void ok(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
  }

  private static List<JsonObject> list(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonArray("data").getValuesAs(JsonObject.class);
  }
}
