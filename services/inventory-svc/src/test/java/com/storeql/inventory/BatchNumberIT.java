package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The batch numbers inventory writes itself — {@code RET-}, {@code MO-}, {@code TO-}, {@code CC-} —
 * end in the short handle of the document behind them. Ids are UUIDv7, so documents created back to
 * back share the first eight characters of their ids; while the handle was cut from the front, all
 * of them got one batch number. Real Postgres; Kafka and Consul disabled.
 */
@HelidonTest
class BatchNumberIT {

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
    System.setProperty("storeql.inventory.food-safety.overdue-sweeper.enabled", "false");
  }

  private static final String T = "01a090ae-611e-7011-ae7d-1bd68c966ff6";
  private static final String USER = "01a090ae-611e-7033-93f1-01903ac69340";

  @Inject WebTarget target;
  @Inject InventoryService inventory;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── returns ──────────────────────────────────────────────────────────────────

  /**
   * The worst case, built exactly: two orders whose ids agree on everything but the random tail —
   * same millisecond, same counter. Only the tail can tell their returns apart.
   */
  @Test
  void returnsForOrdersFromTheSameMillisecondGetDifferentBatchNumbers() {
    UUID first = Ids.parse("01a0905d-7082-7518-9ec6-00000000a001");
    UUID second = Ids.parse("01a0905d-7082-7518-9ec6-00000000a002");
    UUID tenant = Ids.parse(T);
    UUID store = Ids.newId();
    UUID variant = Ids.newId();

    assertTrue(returnOnce(Ids.newId(), tenant, store, variant, first));
    assertTrue(returnOnce(Ids.newId(), tenant, store, variant, second));

    assertEquals(List.of("RET-0000a001", "RET-0000a002"), batchNumbers(variant, "RET-%"));
  }

  @Test
  void aRedeliveredReturnEventMintsNoSecondBatch() {
    UUID tenant = Ids.parse(T);
    UUID store = Ids.newId();
    UUID variant = Ids.newId();
    UUID order = Ids.newId();
    UUID event = Ids.newId();

    assertTrue(returnOnce(event, tenant, store, variant, order));
    assertFalse(returnOnce(event, tenant, store, variant, order));

    assertEquals(List.of("RET-" + Ids.shortRef(order)), batchNumbers(variant, "RET-%"));
  }

  // ── move orders ──────────────────────────────────────────────────────────────

  @Test
  void aBurstOfMoveOrdersGetsOneDistinctBatchNumberEach() {
    String store = uuid();
    UUID variant = Ids.newId();
    receive(store, variant, "100");

    List<String> orders = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      orders.add(createMoveOrder(store, variant, "1"));
    }
    Set<String> expected = new HashSet<>();
    Set<String> heads = new HashSet<>();
    for (String order : orders) {
      data(post("/admin/inventory/move-orders/" + order + "/pick", null));
      expected.add("MO-" + tail(order));
      heads.add(order.substring(0, 8));
    }

    assertTrue(heads.size() < orders.size(), "the burst shares id heads: " + heads);
    List<String> numbers = batchNumbers(variant, "MO-%");
    assertEquals(orders.size(), numbers.size());
    assertEquals(expected, new HashSet<>(numbers));
  }

  @Test
  void aPickedMoveOrderCannotBePickedAgainOrMintAnotherBatch() {
    String store = uuid();
    UUID variant = Ids.newId();
    receive(store, variant, "5");
    String order = createMoveOrder(store, variant, "2");
    data(post("/admin/inventory/move-orders/" + order + "/pick", null));

    assertError(
        post("/admin/inventory/move-orders/" + order + "/pick", null),
        422,
        "MOVE_ORDER_NOT_PICKABLE");
    assertEquals(List.of("MO-" + tail(order)), batchNumbers(variant, "MO-%"));
  }

  // ── transfers ────────────────────────────────────────────────────────────────

  @Test
  void directTransfersShippedBackToBackGetDifferentBatchNumbers() {
    String from = uuid();
    String to = uuid();
    UUID variant = Ids.newId();
    receive(from, variant, "10");
    String first = createTransfer(from, to, variant, "DIRECT");
    String second = createTransfer(from, to, variant, "DIRECT");

    data(post("/admin/inventory/transfers/" + first + "/ship", null));
    data(post("/admin/inventory/transfers/" + second + "/ship", null));

    assertEquals(
        Set.of("TO-" + tail(first), "TO-" + tail(second)),
        new HashSet<>(batchNumbers(variant, "TO-%")));
    assertEquals(2, batchNumbers(variant, "TO-%").size());
  }

  @Test
  void inTransitTransfersGetTheirBatchNumberOnReceiptNotBefore() {
    String from = uuid();
    String to = uuid();
    UUID variant = Ids.newId();
    receive(from, variant, "10");
    String first = createTransfer(from, to, variant, "INTRANSIT");
    String second = createTransfer(from, to, variant, "INTRANSIT");

    data(post("/admin/inventory/transfers/" + first + "/ship", null));
    data(post("/admin/inventory/transfers/" + second + "/ship", null));
    assertEquals(List.of(), batchNumbers(variant, "TO-%"), "nothing arrives while in transit");

    data(post("/admin/inventory/transfers/" + first + "/receive", null));
    data(post("/admin/inventory/transfers/" + second + "/receive", null));

    assertEquals(
        Set.of("TO-" + tail(first), "TO-" + tail(second)),
        new HashSet<>(batchNumbers(variant, "TO-%")));
    assertEquals(2, batchNumbers(variant, "TO-%").size());
  }

  @Test
  void aTransferShippedTwiceOrReceivedOutOfTurnMintsNothingExtra() {
    String from = uuid();
    String to = uuid();
    UUID variant = Ids.newId();
    receive(from, variant, "10");
    String direct = createTransfer(from, to, variant, "DIRECT");
    String inTransit = createTransfer(from, to, variant, "INTRANSIT");

    assertError(
        post("/admin/inventory/transfers/" + inTransit + "/receive", null),
        422,
        "TRANSFER_ORDER_NOT_RECEIVABLE");
    data(post("/admin/inventory/transfers/" + direct + "/ship", null));
    assertError(
        post("/admin/inventory/transfers/" + direct + "/ship", null),
        422,
        "TRANSFER_ORDER_NOT_SHIPPABLE");
    assertError(
        post("/admin/inventory/transfers/" + direct + "/receive", null),
        422,
        "TRANSFER_ORDER_NOT_RECEIVABLE");

    assertEquals(List.of("TO-" + tail(direct)), batchNumbers(variant, "TO-%"));
  }

  // ── cycle counts ─────────────────────────────────────────────────────────────

  /** Same construction as the returns case: two counts whose ids differ only in the random tail. */
  @Test
  void cycleCountsFromTheSameMillisecondGetDifferentAdjustmentBatchNumbers() {
    UUID first = Ids.parse("01a0905d-7082-7518-9ec6-00000000c001");
    UUID second = Ids.parse("01a0905d-7082-7518-9ec6-00000000c002");
    String store = uuid();
    UUID variant = Ids.newId();
    seedApprovedCount(first, store, variant, "4", "6");
    seedApprovedCount(second, store, variant, "6", "9");

    data(post("/admin/inventory/cycle-counts/" + first + "/adjust", null));
    data(post("/admin/inventory/cycle-counts/" + second + "/adjust", null));

    assertEquals(List.of("CC-0000c001", "CC-0000c002"), batchNumbers(variant, "CC-%"));
  }

  @Test
  void anAdjustedCycleCountCannotBeAdjustedAgainOrMintAnotherBatch() {
    UUID count = Ids.newId();
    String store = uuid();
    UUID variant = Ids.newId();
    seedApprovedCount(count, store, variant, "1", "3");
    data(post("/admin/inventory/cycle-counts/" + count + "/adjust", null));

    assertError(
        post("/admin/inventory/cycle-counts/" + count + "/adjust", null),
        422,
        "CYCLE_COUNT_CLOSED");
    assertEquals(List.of("CC-" + Ids.shortRef(count)), batchNumbers(variant, "CC-%"));
  }

  // ── refusals: cycle counts, move orders and transfers ────────────────────────

  @Test
  @DisplayName("A count line that is not there is not found, and the count is left as it was")
  void aCountLineThatDoesNotExistIsNotFound() {
    String store = uuid();
    String count =
        created(
                post(
                    "/admin/inventory/cycle-counts",
                    "{\"storeId\":\"%s\",\"name\":\"Aisle 4\"}".formatted(store)))
            .getString("id");

    assertError(
        post(
            "/admin/inventory/cycle-counts/" + count + "/lines/" + Ids.newId() + "/count",
            "{\"countedQty\":1}"),
        404,
        "COUNT_LINE_NOT_FOUND");
    assertEquals(
        "OPEN",
        scalar(
            "SELECT status FROM inventory.cycle_count_headers WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + count
                + "'"));
  }

  @Test
  @DisplayName("A count entered under a count that is not there is not found")
  void aCountEnteredUnderAnUnknownCountIsNotFound() {
    assertError(get("/admin/inventory/cycle-counts/" + Ids.newId()), 404, "CYCLE_COUNT_NOT_FOUND");
    assertError(
        post(
            "/admin/inventory/cycle-counts/" + Ids.newId() + "/lines/" + Ids.newId() + "/count",
            "{\"countedQty\":1}"),
        404,
        "CYCLE_COUNT_NOT_FOUND");
  }

  @Test
  @DisplayName("An approved count line cannot be counted again; its counted quantity stays")
  void anApprovedCountLineCannotBeRecounted() {
    UUID count = Ids.newId();
    UUID variant = Ids.newId();
    UUID line = seedApprovedCount(count, uuid(), variant, "4", "6");

    assertError(
        post(
            "/admin/inventory/cycle-counts/" + count + "/lines/" + line + "/count",
            "{\"countedQty\":5}"),
        422,
        "COUNT_LINE_NOT_UPDATABLE");
    assertEquals(
        0,
        new BigDecimal(
                lineOf(
                    "SELECT counted_qty FROM inventory.cycle_count_lines WHERE tenant_id = '"
                        + T
                        + "' AND id = '"
                        + line
                        + "'"))
            .compareTo(new BigDecimal("6")));
  }

  @Test
  @DisplayName("A line of another count is refused under this one, and the line is untouched")
  void aLineOfAnotherCountIsRefused() {
    UUID first = Ids.newId();
    UUID second = Ids.newId();
    String store = uuid();
    UUID lineOfFirst = seedApprovedCount(first, store, Ids.newId(), "4", "6");
    seedApprovedCount(second, store, Ids.newId(), "1", "1");

    assertError(
        post(
            "/admin/inventory/cycle-counts/" + second + "/lines/" + lineOfFirst + "/count",
            "{\"countedQty\":1}"),
        400,
        "LINE_HEADER_MISMATCH");
    assertEquals(
        0,
        new BigDecimal(
                lineOf(
                    "SELECT counted_qty FROM inventory.cycle_count_lines WHERE tenant_id = '"
                        + T
                        + "' AND id = '"
                        + lineOfFirst
                        + "'"))
            .compareTo(new BigDecimal("6")));
  }

  @Test
  @DisplayName("A count with a tolerance outside 0 to 100 is refused and none is made")
  void aCountWithAToleranceOutsideNoughtToAHundredIsRefused() {
    String store = uuid();
    for (String tolerance : new String[] {"150", "-1", "100.5"}) {
      assertError(
          post(
              "/admin/inventory/cycle-counts",
              ("{\"storeId\":\"%s\",\"name\":\"Aisle 4\",\"tolerancePct\":%s}")
                  .formatted(store, tolerance)),
          400,
          "INVALID_TOLERANCE");
    }
    assertEquals(
        "0",
        scalar(
            "SELECT count(*) FROM inventory.cycle_count_headers WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'"));
  }

  @Test
  @DisplayName("A move order or transfer with no lines is refused and none is made")
  void aMoveOrTransferWithNoLinesIsRefused() {
    String store = uuid();
    String other = uuid();
    assertError(
        post(
            "/admin/inventory/move-orders",
            ("{\"fromStoreId\":\"%s\",\"toStoreId\":\"%s\",\"fromZone\":\"BACK\","
                    + "\"toZone\":\"FLOOR\",\"lines\":[]}")
                .formatted(store, store)),
        400,
        "NO_LINES");
    assertError(
        post(
            "/admin/inventory/transfers",
            ("{\"fromStoreId\":\"%s\",\"toStoreId\":\"%s\",\"transferType\":\"DIRECT\","
                    + "\"lines\":[]}")
                .formatted(store, other)),
        400,
        "NO_LINES");
    assertEquals(
        "0",
        scalar(
            "SELECT count(*) FROM inventory.move_orders WHERE tenant_id = '"
                + T
                + "' AND from_store_id = '"
                + store
                + "'"));
    assertEquals(
        "0",
        scalar(
            "SELECT count(*) FROM inventory.transfer_orders WHERE tenant_id = '"
                + T
                + "' AND from_store_id = '"
                + store
                + "'"));
  }

  @Test
  @DisplayName("A transfer of a type nobody defined is refused and none is made")
  void aTransferOfATypeNobodyDefinedIsRefused() {
    String from = uuid();
    String to = uuid();
    UUID variant = Ids.newId();
    receive(from, variant, "5");

    assertError(
        post(
            "/admin/inventory/transfers",
            ("{\"fromStoreId\":\"%s\",\"toStoreId\":\"%s\",\"transferType\":\"COURIER\","
                    + "\"lines\":[{\"variantId\":\"%s\",\"requestedQty\":1}]}")
                .formatted(from, to, variant)),
        400,
        "INVALID_TRANSFER_TYPE");
    assertEquals(
        "0",
        scalar(
            "SELECT count(*) FROM inventory.transfer_orders WHERE tenant_id = '"
                + T
                + "' AND from_store_id = '"
                + from
                + "'"));
  }

  @Test
  @DisplayName("A picked move order cannot be cancelled, and it stays completed")
  void aPickedMoveOrderCannotBeCancelled() {
    String store = uuid();
    UUID variant = Ids.newId();
    receive(store, variant, "5");
    String order = createMoveOrder(store, variant, "2");
    data(post("/admin/inventory/move-orders/" + order + "/pick", null));

    assertError(
        post("/admin/inventory/move-orders/" + order + "/cancel", null),
        422,
        "MOVE_ORDER_NOT_CANCELLABLE");
    assertEquals(
        "COMPLETED",
        scalar(
            "SELECT status FROM inventory.move_orders WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + order
                + "'"));
  }

  @Test
  @DisplayName("A shipped transfer cannot be cancelled, and it stays shipped")
  void aShippedTransferCannotBeCancelled() {
    String from = uuid();
    String to = uuid();
    UUID variant = Ids.newId();
    receive(from, variant, "10");
    String transfer = createTransfer(from, to, variant, "INTRANSIT");
    data(post("/admin/inventory/transfers/" + transfer + "/ship", null));

    assertError(
        post("/admin/inventory/transfers/" + transfer + "/cancel", null),
        422,
        "TRANSFER_ORDER_NOT_CANCELLABLE");
    assertEquals(
        "SHIPPED",
        scalar(
            "SELECT status FROM inventory.transfer_orders WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + transfer
                + "'"));
  }

  @Test
  @DisplayName("A move order or transfer that is not there is not found")
  void aMoveOrderOrTransferThatIsNotThereIsNotFound() {
    assertError(get("/admin/inventory/move-orders/" + Ids.newId()), 404, "MOVE_ORDER_NOT_FOUND");
    assertError(
        post("/admin/inventory/move-orders/" + Ids.newId() + "/pick", null),
        404,
        "MOVE_ORDER_NOT_FOUND");
    assertError(
        post("/admin/inventory/move-orders/" + Ids.newId() + "/cancel", null),
        404,
        "MOVE_ORDER_NOT_FOUND");
    assertError(get("/admin/inventory/transfers/" + Ids.newId()), 404, "TRANSFER_ORDER_NOT_FOUND");
    assertError(
        post("/admin/inventory/transfers/" + Ids.newId() + "/ship", null),
        404,
        "TRANSFER_ORDER_NOT_FOUND");
    assertError(
        post("/admin/inventory/transfers/" + Ids.newId() + "/cancel", null),
        404,
        "TRANSFER_ORDER_NOT_FOUND");
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private boolean returnOnce(UUID event, UUID tenant, UUID store, UUID variant, UUID order) {
    return inventory.receiveReturnFromOrderOnce(
        event, "batch-number-it", tenant, store, variant, BigDecimal.ONE, order);
  }

  private void receive(String store, UUID variant, String qty) {
    created(
        post(
            "/admin/inventory/receive",
            // No lot: stock that has one carries it when it moves (SJ-D71, LotProvenanceIT), and
            // the system number is for stock that has none.
            "{\"storeId\":\"%s\",\"variantId\":\"%s\",\"qty\":%s}".formatted(store, variant, qty)));
  }

  private String createMoveOrder(String store, UUID variant, String qty) {
    return created(
            post(
                "/admin/inventory/move-orders",
                ("{\"fromStoreId\":\"%s\",\"toStoreId\":\"%s\",\"fromZone\":\"BACK\","
                        + "\"toZone\":\"FLOOR\",\"lines\":[{\"variantId\":\"%s\","
                        + "\"requestedQty\":%s}]}")
                    .formatted(store, store, variant, qty)))
        .getString("id");
  }

  private String createTransfer(String from, String to, UUID variant, String type) {
    return created(
            post(
                "/admin/inventory/transfers",
                ("{\"fromStoreId\":\"%s\",\"toStoreId\":\"%s\",\"transferType\":\"%s\","
                        + "\"lines\":[{\"variantId\":\"%s\",\"requestedQty\":2}]}")
                    .formatted(from, to, type, variant)))
        .getString("id");
  }

  private static UUID seedApprovedCount(
      UUID headerId, String store, UUID variant, String systemQty, String countedQty) {
    UUID lineId = Ids.newId();
    BigDecimal system = new BigDecimal(systemQty);
    BigDecimal counted = new BigDecimal(countedQty);
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      try (var ps =
          c.prepareStatement(
              "INSERT INTO inventory.cycle_count_headers (id, tenant_id, store_id, name, status)"
                  + " VALUES (?, ?, ?, 'IT count', 'PENDING_APPROVAL')")) {
        ps.setObject(1, headerId);
        ps.setObject(2, Ids.parse(T));
        ps.setObject(3, Ids.parse(store));
        ps.executeUpdate();
      }
      try (var ps =
          c.prepareStatement(
              "INSERT INTO inventory.cycle_count_lines (id, tenant_id, header_id, store_id,"
                  + " variant_id, system_qty, counted_qty, variance, status, counted_at)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', now())")) {
        ps.setObject(1, lineId);
        ps.setObject(2, Ids.parse(T));
        ps.setObject(3, headerId);
        ps.setObject(4, Ids.parse(store));
        ps.setObject(5, variant);
        ps.setBigDecimal(6, system);
        ps.setBigDecimal(7, counted);
        ps.setBigDecimal(8, counted.subtract(system));
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("seeding cycle count " + headerId, e);
    }
    return lineId;
  }

  /** One number read from this test's database (the caller scopes the SQL to its own rows). */
  private static String scalar(String sql) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      if (!rs.next()) {
        return null;
      }
      return rs.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException("reading " + sql, e);
    }
  }

  private Response get(String path) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .header("X-User-Id", USER)
        .get();
  }

  private static String lineOf(String sql) {
    return scalar(sql);
  }

  /** This tenant's batch numbers for one variant matching {@code like}, sorted. */
  private static List<String> batchNumbers(UUID variant, String like) {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT batch_no FROM inventory.inventory_batches"
                    + " WHERE tenant_id = ? AND variant_id = ? AND batch_no LIKE ?"
                    + " ORDER BY batch_no")) {
      ps.setObject(1, Ids.parse(T));
      ps.setObject(2, variant);
      ps.setString(3, like);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("reading batch numbers", e);
    }
    return out;
  }

  private Response post(String path, String json) {
    var request =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .header("X-User-Id", USER);
    return request.post(Entity.entity(json == null ? "" : json, MediaType.APPLICATION_JSON));
  }

  private static String tail(String id) {
    return Ids.shortRef(Ids.parse(id));
  }

  private static String uuid() {
    return Ids.newId().toString();
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

  private static void assertError(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, parse(body).getJsonObject("error").getString("code"), is(code));
  }

  private static JsonObject parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }
}
