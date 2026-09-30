package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for inventory against real Postgres (Testcontainers): receive two batches,
 * reserve, over-reserve (422), consume with FIFO deduction, tenant isolation. Kafka/Consul
 * disabled.
 */
@HelidonTest
class InventoryIT {

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

  private static final String T = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String OTHER = "01a090ae-611e-701d-9d60-a9d7516ed03b";
  private static final String S = "01a090ae-611e-700f-b645-a14095230b77";
  private static final String V = "01a090ae-611e-7011-ae7d-1bd68c966ff6";

  /** Dedicated variant for the FIFO test so tier-1 stock doesn't pollute its level assertions. */
  private static final String V_FIFO = "01a090ae-611e-7014-8cd5-baf0862fa319";

  /** Dedicated store + variants (id-ordered) for the levels pagination / summary test. */
  private static final String S_PAGE = "01a090ae-611e-7019-ba7e-5901486ca70a";

  private static final String VP1 = "01a090ae-611e-701f-9044-30556c2f6f4e";
  private static final String VP2 = "01a090ae-611e-7020-8485-80b3d7bb3c80";
  private static final String VP3 = "01a090ae-611e-7021-84be-14854f0a8dfe";

  @Inject WebTarget target;
  @Inject com.storeql.inventory.service.InventoryService inventoryService;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  @Test
  void receiveReserveConsumeFifoAndIsolation() {
    // Use V_FIFO so tier-1 tests receiving into V don't pollute level assertions here.
    // two batches: A (earlier expiry, 10) then B (later, 5)
    assertThat(
        post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + V_FIFO
                    + "\",\"qty\":10,\"batchNo\":\"A\",\"expiryDate\":\"2098-01-01\"}",
                T)
            .getStatus(),
        is(201));
    assertThat(
        post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + V_FIFO
                    + "\",\"qty\":5,\"batchNo\":\"B\",\"expiryDate\":\"2099-01-01\"}",
                T)
            .getStatus(),
        is(201));

    // levels for store S — V_FIFO row has onHand 15, available 15
    assertThat(
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class),
        containsString("\"available\":15"));

    // reserve 12 → available 3
    Response resResp =
        post(
            "/inventory/reservations",
            "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V_FIFO + "\",\"qty\":12}",
            T);
    assertThat(resResp.getStatus(), is(201));
    String reservationId = field(resResp.readEntity(String.class), "id");
    assertThat(
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class),
        containsString("\"available\":3"));

    // over-reserve (5 > 3) → 422
    Response over =
        post(
            "/inventory/reservations",
            "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V_FIFO + "\",\"qty\":5}",
            T);
    assertThat(over.getStatus(), is(422));
    assertThat(over.readEntity(String.class), containsString("INSUFFICIENT_STOCK"));

    // consume (FIFO: A drains, B reduced) → onHand 3
    Response consume = post("/inventory/reservations/" + reservationId + "/consume", "", T);
    assertThat(consume.getStatus(), is(200));
    assertThat(
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class),
        containsString("\"onHand\":3"));

    // tenant isolation
    assertThat(get("/admin/inventory/levels", OTHER), not(containsString(V_FIFO)));
  }

  @Test
  void levelsPaginateAndSummarize() {
    // Three SKUs in a dedicated store: two low (available <= 5), one healthy.
    receive(S_PAGE, VP1, 3);
    receive(S_PAGE, VP2, 10);
    receive(S_PAGE, VP3, 2);

    // Summary is a server-side aggregate — 3 distinct SKUs, 2 of them low.
    String summary =
        target
            .path("/admin/inventory/levels/summary")
            .queryParam("store", S_PAGE)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(summary, containsString("\"skuCount\":3"));
    assertThat(summary, containsString("\"lowStockCount\":2"));

    // Page 1 (limit 2): the first two SKUs by (store, variant) order, plus a cursor.
    String page1 = levelsPage(S_PAGE, 2, null);
    assertThat(page1, containsString(VP1));
    assertThat(page1, containsString(VP2));
    assertThat(page1, not(containsString(VP3)));
    String cursor = field(page1, "nextCursor");

    // Page 2: the remaining SKU only — the cursor advances past page 1 with no overlap.
    String page2 = levelsPage(S_PAGE, 2, cursor);
    assertThat(page2, containsString(VP3));
    assertThat(page2, not(containsString(VP1)));
    assertThat(page2, not(containsString(VP2)));
    // Exhausted: no further (non-null) cursor — a null nextCursor is omitted by JSON-B.
    assertThat(page2, not(containsString("\"nextCursor\":\"")));
  }

  private void receive(String store, String variant, int qty) {
    Response r =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\"" + store + "\",\"variantId\":\"" + variant + "\",\"qty\":" + qty + "}",
            T);
    assertThat(r.getStatus(), is(201));
  }

  private String levelsPage(String store, int limit, String after) {
    var t =
        target
            .path("/admin/inventory/levels")
            .queryParam("store", store)
            .queryParam("limit", limit);
    if (after != null) {
      t = t.queryParam("after", after);
    }
    return t.request().header("X-Tenant-Id", T).header("X-Roles", "OWNER").get(String.class);
  }

  // ── Tier-1 Gap #21: Reason codes ─────────────────────────────────────────

  @Test
  void reasonCode_createAndList() {
    Response r =
        post(
            "/admin/inventory/reason-codes",
            "{\"code\":\"TEST_DMG\",\"description\":\"Test damage\"}",
            T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("TEST_DMG"));

    String list = get("/admin/inventory/reason-codes", T);
    assertThat(list, containsString("TEST_DMG"));
    // System seeded codes also visible
    assertThat(list, containsString("DAMAGED"));
  }

  @Test
  void reasonCode_missingCode_returns400() {
    Response r = post("/admin/inventory/reason-codes", "{\"description\":\"no code\"}", T);
    assertThat(r.getStatus(), is(400));
  }

  @Test
  void reasonCode_deactivate() {
    post("/admin/inventory/reason-codes", "{\"code\":\"DEACT_ME\"}", T);
    String list = get("/admin/inventory/reason-codes", T);
    String id = fieldNear(list, "\"DEACT_ME\"", "id");
    Response r = post("/admin/inventory/reason-codes/" + id + "/deactivate", "", T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"active\":false"));
  }

  // ── Tier-1 Gap #22: Source types ─────────────────────────────────────────

  @Test
  void sourceType_createAndList() {
    Response r =
        post("/admin/inventory/source-types", "{\"code\":\"POS_SALE\",\"description\":\"POS\"}", T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("POS_SALE"));

    String list = get("/admin/inventory/source-types", T);
    assertThat(list, containsString("POS_SALE"));
    assertThat(list, containsString("RECEIVE")); // system seed
  }

  @Test
  void sourceType_missingCode_returns400() {
    Response r = post("/admin/inventory/source-types", "{\"description\":\"no code\"}", T);
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #23: Lot split / merge ────────────────────────────────────

  @Test
  void lotSplit_positive() {
    // receive a batch first
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":20,\"batchNo\":\"SPLIT-SRC\"}",
            T);
    assertThat(rcv.getStatus(), is(201));
    String srcBatchId = field(rcv.readEntity(String.class), "id");

    Response r =
        post(
            "/admin/inventory/lots/split",
            "{\"sourceBatchId\":\"" + srcBatchId + "\",\"qty\":8,\"batchNo\":\"SPLIT-CHILD\"}",
            T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("SPLIT"));
  }

  @Test
  void lotSplit_excessQty_returns422() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":5,\"batchNo\":\"SPLIT-SMALL\"}",
            T);
    String srcId = field(rcv.readEntity(String.class), "id");

    Response r =
        post("/admin/inventory/lots/split", "{\"sourceBatchId\":\"" + srcId + "\",\"qty\":999}", T);
    assertThat(r.getStatus(), is(422));
    assertThat(r.readEntity(String.class), containsString("INSUFFICIENT_QTY"));
  }

  @Test
  void lotSplit_unknownBatch_returns404() {
    Response r =
        post(
            "/admin/inventory/lots/split",
            "{\"sourceBatchId\":\"01a090ae-611e-7007-b85c-1fbac22cb87b\",\"qty\":1}",
            T);
    assertThat(r.getStatus(), is(404));
  }

  // ── Tier-1 Gap #24: Expiry alert query ───────────────────────────────────

  @Test
  void expiringBatches_withinWindow() {
    post(
        "/admin/inventory/receive",
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + V
            + "\",\"qty\":3,\"batchNo\":\"EXP-NEAR\",\"expiryDate\":\""
            + java.time.LocalDate.now().plusDays(5)
            + "\"}",
        T);

    String resp =
        target
            .path("/admin/inventory/batches/expiring")
            .queryParam("store", S)
            .queryParam("withinDays", 3650)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(resp, containsString("EXP-NEAR"));
  }

  @Test
  void expiringBatches_invalidDays_returns400() {
    Response r =
        target
            .path("/admin/inventory/batches/expiring")
            .queryParam("store", S)
            .queryParam("withinDays", 9999)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #25: Grade control ────────────────────────────────────────

  @Test
  void gradeUpdate_positive() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":5,\"batchNo\":\"GRADE-B1\",\"grade\":\"A\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/batches/" + batchId + "/grade")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"grade\":\"B\"}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"grade\":\"B\""));
  }

  @Test
  void gradeUpdate_blankGrade_returns400() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":1,\"batchNo\":\"GRADE-B2\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/batches/" + batchId + "/grade")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"grade\":\"\"}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #26: Lot UOM conversions ──────────────────────────────────

  @Test
  void uomConversion_upsertAndList() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":10,\"batchNo\":\"UOM-B1\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/lots/" + batchId + "/uom-conversions")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"batchId\":\""
                        + batchId
                        + "\",\"fromUom\":\"KG\",\"toUom\":\"G\",\"factor\":1000}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"factor\":1000"));

    String list = get("/admin/inventory/lots/" + batchId + "/uom-conversions", T);
    assertThat(list, containsString("KG"));
  }

  @Test
  void uomConversion_negFactor_returns400() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":2,\"batchNo\":\"UOM-NEG\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/lots/" + batchId + "/uom-conversions")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"batchId\":\""
                        + batchId
                        + "\",\"fromUom\":\"KG\",\"toUom\":\"G\",\"factor\":-1}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #27: PAR levels ───────────────────────────────────────────

  @Test
  void parLevel_upsertAndList() {
    Response r =
        target
            .path("/admin/inventory/par-levels")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + V
                        + "\",\"parQty\":50,\"reviewCycle\":\"WEEKLY\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"parQty\":50"));

    String list =
        target
            .path("/admin/inventory/par-levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(list, containsString("WEEKLY"));
  }

  @Test
  void parLevel_invalidCycle_returns400() {
    Response r =
        target
            .path("/admin/inventory/par-levels")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + V
                        + "\",\"parQty\":10,\"reviewCycle\":\"YEARLY\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #28: Order modifiers ──────────────────────────────────────

  @Test
  void ropOrderModifiers_update() {
    // create a ROP plan first
    Response rop =
        target
            .path("/admin/inventory/rop-plans")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + V
                        + "\",\"leadTimeDays\":7,\"orderingCost\":50,\"holdingCostPct\":0.2,\"unitCost\":10}",
                    MediaType.APPLICATION_JSON));
    assertThat(rop.getStatus(), is(200));
    String ropId = field(rop.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/rop-plans/" + ropId + "/order-modifiers")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"minOrderQty\":5,\"maxOrderQty\":100,\"lotMultiplier\":5}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"minOrderQty\":5"));
  }

  @Test
  void ropOrderModifiers_unknownPlan_returns404() {
    Response r =
        target
            .path("/admin/inventory/rop-plans/01a090ae-611e-7007-b85c-1fbac22cb87b/order-modifiers")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"minOrderQty\":1}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(404));
  }

  // ── Tier-1 Gap #29: Bulk reservations ────────────────────────────────────

  @Test
  void bulkReserve_positive() {
    // ensure stock
    post(
        "/admin/inventory/receive",
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + V
            + "\",\"qty\":100,\"batchNo\":\"BULK-SRC\"}",
        T);

    Response r =
        post(
            "/inventory/reservations/batch",
            "{\"reservations\":[{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":2},"
                + "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":3}]}",
            T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"succeeded\":2"));
  }

  @Test
  void bulkReserve_emptyList_returns400() {
    // Missing "reservations" key → 400 (Bean Validation: @NotNull)
    Response bad = post("/inventory/reservations/batch", "{}", T);
    assertThat(bad.getStatus(), is(400));
  }

  // ── Tier-1 Gap #30: Purge movements ──────────────────────────────────────

  @Test
  void purgeMovements_tooRecent_returns400() {
    // Trying to purge within 90 days must be rejected. Computed relative to now (not hardcoded)
    // so this test doesn't silently start passing for the wrong reason once real time moves the
    // fixed date past the 90-day window.
    String tooRecent = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(30).toString();
    Response r = post("/admin/inventory/movements/purge", "{\"before\":\"" + tooRecent + "\"}", T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURGE_TOO_RECENT"));
  }

  @Test
  void purgeMovements_oldDate_succeeds() {
    Response r =
        post("/admin/inventory/movements/purge", "{\"before\":\"2020-01-01T00:00:00Z\"}", T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"purged\""));
  }

  @Test
  void purgeMovements_archivesRatherThanDeletes() throws Exception {
    // Golden rule #8: stock_movements is append-only. Purge must relocate rows to
    // stock_movements_archive, never destroy them. Seed a pre-dated row directly
    // (no API backdates created_at), then verify it survives in the archive table.
    UUID movementId = Ids.newId();
    OffsetDateTime oldDate = OffsetDateTime.parse("2019-01-01T00:00:00Z");
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO inventory.stock_movements (id, tenant_id, store_id, variant_id,"
                    + " type, qty, created_at) VALUES (?,?,?,?,'ADJUST',1,?)")) {
      ps.setObject(1, movementId);
      ps.setObject(2, Ids.parse(T));
      ps.setObject(3, Ids.parse(S));
      ps.setObject(4, Ids.parse(V));
      ps.setObject(5, oldDate);
      ps.executeUpdate();
    }

    Response r =
        post("/admin/inventory/movements/purge", "{\"before\":\"2020-01-01T00:00:00Z\"}", T);
    assertThat(r.getStatus(), is(200));

    try (var c = PG.dataSource().getConnection()) {
      try (var ps = c.prepareStatement("SELECT 1 FROM inventory.stock_movements WHERE id=?")) {
        ps.setObject(1, movementId);
        try (var rs = ps.executeQuery()) {
          assertThat("row must leave the hot table", rs.next(), is(false));
        }
      }
      try (var ps =
          c.prepareStatement("SELECT 1 FROM inventory.stock_movements_archive WHERE id=?")) {
        ps.setObject(1, movementId);
        try (var rs = ps.executeQuery()) {
          assertThat("row must survive in the archive", rs.next(), is(true));
        }
      }
    }
  }

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  @Test
  void zoneGlMapping_upsertAndList() {
    Response r =
        target
            .path("/admin/inventory/zone-gl-mappings")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"nominalCode\":\"1200\",\"description\":\"Stock account\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"nominalCode\":\"1200\""));

    String list =
        target
            .path("/admin/inventory/zone-gl-mappings")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(list, containsString("1200"));
  }

  @Test
  void zoneGlMapping_missingNominalCode_returns400() {
    Response r =
        target
            .path("/admin/inventory/zone-gl-mappings")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"storeId\":\"" + S + "\"}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  @Test
  void receiveWithSameIdempotencyKeyIsNotDoubleCounted() {
    String variant = Ids.newId().toString();
    String key = Ids.newId().toString();
    String body =
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":10,\"batchNo\":\"R\"}";

    Response first = postWithIdempotencyKey("/admin/inventory/receive", body, T, key);
    assertThat(first.getStatus(), is(201));
    String firstBatchId = field(first.readEntity(String.class), "id");

    // a client-timeout retry with the same key replays the original batch, not a second one
    Response retried = postWithIdempotencyKey("/admin/inventory/receive", body, T, key);
    assertThat(retried.getStatus(), is(201));
    assertThat(field(retried.readEntity(String.class), "id"), is(firstBatchId));

    String levels =
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    int marker = levels.indexOf("\"variantId\":\"" + variant + "\"");
    assertThat(marker, not(-1));
    String row = levels.substring(levels.lastIndexOf('{', marker), levels.indexOf('}', marker) + 1);
    assertThat(row, containsString("\"onHand\":10.000"));
  }

  @Test
  void reserveWithSameIdempotencyKeyIsNotDoubleHeld() {
    String variant = Ids.newId().toString();
    String key = Ids.newId().toString();
    String receiveBody =
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":10,\"batchNo\":\"RV\"}";
    assertThat(post("/admin/inventory/receive", receiveBody, T).getStatus(), is(201));

    String reserveBody = "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variant + "\",\"qty\":7}";
    Response first = postWithIdempotencyKey("/inventory/reservations", reserveBody, T, key);
    assertThat(first.getStatus(), is(201));
    String firstReservationId = field(first.readEntity(String.class), "id");

    // a client-timeout retry with the same key replays the original hold, not a second one — if
    // it held stock twice, only 10-7-7=-4 would remain and a third reserve of 4 would fail
    Response retried = postWithIdempotencyKey("/inventory/reservations", reserveBody, T, key);
    assertThat(retried.getStatus(), is(201));
    assertThat(field(retried.readEntity(String.class), "id"), is(firstReservationId));

    String remainder = "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variant + "\",\"qty\":3}";
    Response third = post("/inventory/reservations", remainder, T);
    assertThat(third.getStatus(), is(201));
  }

  @Test
  void adjustWithSameIdempotencyKeyIsNotDoubleApplied() {
    String variant = Ids.newId().toString();
    String key = Ids.newId().toString();
    String receiveBody =
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":10,\"batchNo\":\"ADJ\"}";
    assertThat(post("/admin/inventory/receive", receiveBody, T).getStatus(), is(201));

    // a -4 adjustment, retried with the same key — applied once leaves onHand 6, not 2
    String adjustBody =
        "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variant + "\",\"delta\":-4}";
    Response first = postWithIdempotencyKey("/admin/inventory/adjust", adjustBody, T, key);
    assertThat(first.getStatus(), is(200));
    Response retried = postWithIdempotencyKey("/admin/inventory/adjust", adjustBody, T, key);
    assertThat(retried.getStatus(), is(200));

    String levels =
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    int marker = levels.indexOf("\"variantId\":\"" + variant + "\"");
    assertThat(marker, not(-1));
    String row = levels.substring(levels.lastIndexOf('{', marker), levels.indexOf('}', marker) + 1);
    assertThat(row, containsString("\"onHand\":6.000"));
  }

  @Test
  void batchReceiveRejectsNonPositiveItemQty() {
    // BatchReceiveRequest.items is @NotNull @Valid — a zero qty item must be rejected by
    // cascading Bean Validation instead of silently receiving zero stock.
    String body = "{\"items\":[{\"storeId\":\"" + S + "\",\"variantId\":\"" + V + "\",\"qty\":0}]}";
    assertThat(post("/admin/inventory/receive/batch", body, T).getStatus(), is(400));
  }

  private Response postWithIdempotencyKey(String path, String json, String tenant, String key) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY, key)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /**
   * Like {@link #field} but for unquoted JSON values (numbers, booleans) — the report DTO's
   * quantities and counts are numeric, so the string-only helper cannot read them.
   */
  private static String numericField(String json, String name) {
    String key = "\"" + name + "\":";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    int end = start;
    while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) end++;
    return json.substring(start, end).trim();
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  // ── SJ-D4: who adjusted stock, and why ───────────────────────────────────────

  /** A manual adjustment records the acting user and the reason code on the movement row. */
  @Test
  void manualAdjustmentRecordsActorAndReasonCode() {
    String variant = "01a090ae-611e-702f-81ae-ad56c2b4e6d4";
    String actor = "01a090ae-611e-7038-a477-3cf7ad54f5d6";

    // Stock in, then written off as theft by a named user.
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(variant, "20"), T, actor).getStatus(),
        is(201));
    assertThat(
        postAs(
                "/admin/inventory/adjust",
                "{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"delta\":-5,\"reason\":\"missing from shelf\","
                    + "\"reasonCode\":\"THEFT\"}",
                T,
                actor)
            .getStatus(),
        is(200));

    String movements = movements(variant, "ADJUST");
    assertThat(movements, containsString("THEFT"));
    assertThat(movements, containsString(actor));
  }

  /**
   * System-caused movements stay unattributed on purpose: they already cite the record that caused
   * them. Asserting this pins the distinction, so a later change cannot quietly start stamping the
   * requesting user onto a sale and make "who adjusted this" ambiguous again.
   */
  @Test
  void systemCausedMovementsCarryNoActor() {
    String variant = "01a090ae-611e-7030-aa2a-8c96cf019044";
    String actor = "01a090ae-611e-7039-b9af-f03ae3b76991";
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(variant, "7"), T, actor).getStatus(),
        is(201));

    String movements = movements(variant, "RECEIVE");
    assertThat(movements, containsString("RECEIVE"));
    // The requesting user must not be stamped onto a system-caused movement: attribution here
    // would be misleading, since the receipt is explained by its refType/refId, not by whoever
    // happened to call the endpoint.
    assertThat(movements, not(containsString(actor)));
  }

  /** Like {@link #post} but with an authenticated user id, as the gateway would stamp it. */
  private Response postAs(String path, String json, String tenant, String userId) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", userId)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  // ── Low stock report ─────────────────────────────────────────────────────────

  /**
   * The case a naive query drops: an item that has run out has no batch rows at all, so joining
   * from the batches would silently omit the most urgent line in the report.
   */
  @Test
  void lowStockIncludesItemsThatHaveRunOutEntirely() {
    String stocked = "01a090ae-611e-7047-b89c-88468add6606";
    String soldOut = "01a090ae-611e-7048-84e3-5ae846fd8f84";
    String tenant = "01a090ae-611e-7064-9447-1afc2c3b7774";

    setThreshold(tenant, stocked, "10");
    setThreshold(tenant, soldOut, "10");
    // Only one of them is ever received, so soldOut has no inventory_batches row.
    assertThat(
        post("/admin/inventory/receive", receiveJson(stocked, "4"), tenant).getStatus(), is(201));

    String body = lowStock(tenant);
    assertThat(body, containsString(soldOut));
    assertThat(numericFieldNear(body, soldOut, "availableQty"), is("0.000"));
    assertThat(numericFieldNear(body, soldOut, "shortfall"), is("10.000"));
    // 4 on hand against a threshold of 10 is a shortfall of 6.
    assertThat(numericFieldNear(body, stocked, "shortfall"), is("6.000"));
    // Deepest shortfall first.
    assertThat(body.indexOf(soldOut) < body.indexOf(stocked), is(true));
  }

  /** Stock at or above its level is not low, and reserved stock does not count as available. */
  @Test
  void lowStockExcludesHealthyItemsAndDiscountsHeldReservations() {
    String healthy = "01a090ae-611e-7049-9380-d10f40149ad1";
    String tenant = "01a090ae-611e-7065-8b98-32e8ccecd52e";

    setThreshold(tenant, healthy, "10");
    assertThat(
        post("/admin/inventory/receive", receiveJson(healthy, "12"), tenant).getStatus(), is(201));
    // 12 available against a level of 10 — not low.
    assertThat(lowStock(tenant), not(containsString(healthy)));

    // Hold 5, leaving 7 available: reserved stock is spoken for, so this now IS low.
    Response held =
        post(
            "/inventory/reservations",
            "{\"storeId\":\"" + S + "\",\"variantId\":\"" + healthy + "\",\"qty\":5}",
            tenant);
    assertThat(held.getStatus(), is(201));
    String body = lowStock(tenant);
    assertThat(numericFieldNear(body, healthy, "availableQty"), is("7.000"));
    assertThat(numericFieldNear(body, healthy, "shortfall"), is("3.000"));
  }

  /**
   * Where several reorder signals are configured the highest binds, and the response names which
   * one — quietly picking a lower level would under-order against a level a planner had set.
   */
  @Test
  void lowStockTakesTheHighestConfiguredSignalAndNamesIt() {
    String v = "01a090ae-611e-704a-8c8c-01642201efb5";
    String tenant = "01a090ae-611e-7066-af8e-8ded2d05535c";

    setThreshold(tenant, v, "10");
    // safety_stock_qty is only ever written by the compute job, which needs 30 days of demand
    // history to produce a non-zero figure. Seeding it directly is the same approach the archive
    // test above takes for a value the API cannot produce on demand.
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO inventory.safety_stock_params (id, tenant_id, store_id, variant_id,"
                    + " method, safety_stock_qty, computed_at)"
                    + " VALUES (?,?,?,?,'USER_DEFINED',25,now())")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.parse(tenant));
      ps.setObject(3, Ids.parse(S));
      ps.setObject(4, Ids.parse(v));
      ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new AssertionError("could not seed safety stock", e);
    }

    assertThat(post("/admin/inventory/receive", receiveJson(v, "15"), tenant).getStatus(), is(201));

    // 15 on hand clears the threshold of 10 but not the safety stock of 25.
    String body = lowStock(tenant);
    assertThat(fieldNear(body, v, "signal"), is("SAFETY_STOCK"));
    assertThat(numericFieldNear(body, v, "reorderLevel"), is("25.000"));
    assertThat(numericFieldNear(body, v, "shortfall"), is("10.000"));
  }

  /** An item with no configured level anywhere is not low, however little of it there is. */
  @Test
  void lowStockIgnoresItemsWithNoConfiguredLevelAndIsTenantScoped() {
    String unmanaged = "01a090ae-611e-704b-b623-667cf931d0f4";
    String managed = "01a090ae-611e-704c-93c3-4f3fa41b98a8";
    String tenant = "01a090ae-611e-7067-b259-fcbfdf9689fb";

    assertThat(
        post("/admin/inventory/receive", receiveJson(unmanaged, "1"), tenant).getStatus(), is(201));
    setThreshold(tenant, managed, "5");

    String body = lowStock(tenant);
    assertThat(body, containsString(managed));
    assertThat(body, not(containsString(unmanaged)));
    // Another tenant sees none of it.
    assertThat(lowStock(OTHER), not(containsString(managed)));
  }

  private void setThreshold(String tenant, String variantId, String threshold) {
    Response r =
        post(
            "/admin/inventory/thresholds",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + variantId
                + "\",\"threshold\":"
                + threshold
                + "}",
            tenant);
    assertThat(r.getStatus(), anyOf(is(200), is(201)));
  }

  private String lowStock(String tenant) {
    return target
        .path("/admin/inventory/reports/low-stock")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  // ── Valuation report ─────────────────────────────────────────────────────────

  /** FIFO values each batch at its own cost; the store rollup is the sum of its variants. */
  @Test
  void valuationCostsEachBatchAtItsOwnPriceUnderFifo() {
    String v1 = "01a090ae-611e-7042-b5d8-966b034ca0a8";
    String v2 = "01a090ae-611e-7043-b41b-15029ea1bc19";
    String tenant = "01a090ae-611e-7060-bdbf-27e1145c8a70";

    // Two batches of v1 bought at different prices — FIFO must value each at its own, not at an
    // average: 10 × 2.00 + 5 × 3.00 = 35.00.
    receiveCosted(tenant, v1, "10", "2.00");
    receiveCosted(tenant, v1, "5", "3.00");
    receiveCosted(tenant, v2, "4", "1.50"); // 6.00

    String byVariant = valuation(tenant, "VARIANT", null);
    assertThat(numericFieldNear(byVariant, v1, "value"), is("35.00"));
    assertThat(numericFieldNear(byVariant, v1, "onHandQty"), is("15.000"));
    assertThat(numericFieldNear(byVariant, v2, "value"), is("6.00"));
    // Largest holding first.
    assertThat(byVariant.indexOf(v1) < byVariant.indexOf(v2), is(true));
    assertThat(fieldNear(byVariant, v1, "method"), is("FIFO"));

    // The store rollup is the sum of both variants.
    String byStore = valuation(tenant, "STORE", null);
    assertThat(numericFieldNear(byStore, S, "value"), is("41.00"));
    assertThat(numericFieldNear(byStore, S, "onHandQty"), is("19.000"));
  }

  /**
   * The case worth getting right: cost_price is optional on both receipt paths, so stock can have
   * no cost. Valuing it at zero would silently understate a balance-sheet figure, so it has to come
   * back as unvaluedQty instead.
   */
  @Test
  void stockWithNoCostIsReportedAsUnvaluedNotValuedAtZero() {
    String v = "01a090ae-611e-7044-876b-416df27442bb";
    String tenant = "01a090ae-611e-7061-862d-3f4576aca46d";

    receiveCosted(tenant, v, "10", "4.00"); // 40.00, valued
    // Same variant, no cost supplied — 6 units that cannot be costed.
    assertThat(post("/admin/inventory/receive", receiveJson(v, "6"), tenant).getStatus(), is(201));

    String body = valuation(tenant, "VARIANT", null);
    assertThat(numericFieldNear(body, v, "onHandQty"), is("16.000"));
    assertThat(numericFieldNear(body, v, "value"), is("40.00"));
    // The 6 uncosted units are declared, not folded into the value as zero.
    assertThat(numericFieldNear(body, v, "unvaluedQty"), is("6.000"));
  }

  /** An AVERAGE row values the whole holding at the configured standard cost. */
  @Test
  void averageCostingValuesTheWholeHoldingAtTheConfiguredCost() {
    String v = "01a090ae-611e-7045-9e55-e2f1f905060e";
    String tenant = "01a090ae-611e-7062-9304-9dcfba8f11af";

    receiveCosted(tenant, v, "10", "2.00");
    receiveCosted(tenant, v, "10", "8.00"); // FIFO would say 100.00

    Response cm =
        target
            .path("/admin/inventory/costing-methods")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + v
                        + "\",\"method\":\"AVERAGE\",\"averageCost\":3.50}",
                    MediaType.APPLICATION_JSON));
    assertThat(cm.getStatus(), is(200));

    // 20 units × 3.50 = 70.00, not the 100.00 FIFO would give.
    String body = valuation(tenant, "VARIANT", null);
    assertThat(fieldNear(body, v, "method"), is("AVERAGE"));
    assertThat(numericFieldNear(body, v, "value"), is("70.00"));
    assertThat(numericFieldNear(body, v, "unvaluedQty"), is("0.000"));
  }

  /** Tenant scoping, and a bad grouping is a 400 rather than a silently different report. */
  @Test
  void valuationIsTenantScopedAndValidatesItsInputs() {
    String v = "01a090ae-611e-7046-8d67-1fecec1bc1bf";
    String tenant = "01a090ae-611e-7063-b003-d1fad399e4cb";
    receiveCosted(tenant, v, "3", "5.00");

    assertThat(valuation(tenant, "VARIANT", null), containsString(v));
    assertThat(valuation(OTHER, "VARIANT", null), not(containsString(v)));

    assertThat(
        target
            .path("/admin/inventory/reports/valuation")
            .queryParam("groupBy", "SUPPLIER")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get()
            .getStatus(),
        is(400));
  }

  private void receiveCosted(String tenant, String variantId, String qty, String costPrice) {
    Response r =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + variantId
                + "\",\"qty\":"
                + qty
                + ",\"costPrice\":"
                + costPrice
                + "}",
            tenant);
    assertThat(r.getStatus(), is(201));
  }

  private String valuation(String tenant, String groupBy, String storeId) {
    var t = target.path("/admin/inventory/reports/valuation").queryParam("groupBy", groupBy);
    if (storeId != null) t = t.queryParam("storeId", storeId);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get(String.class);
  }

  // ── Shrinkage report ─────────────────────────────────────────────────────────

  /**
   * The report SJ-D4's attribution work exists to feed. Two members of staff write off stock for
   * different reasons, one also finds some, and the report has to separate all of that correctly.
   */
  @Test
  void shrinkageReportGroupsWriteOffsByReasonActorAndStore() {
    String v1 = "01a090ae-611e-703d-b04b-1f8ae88c0f60";
    String v2 = "01a090ae-611e-703e-8503-f1320f6ae86c";
    String alice = "01a090ae-611e-7057-94a6-3d076e7cb45e";
    String bob = "01a090ae-611e-7058-85bf-88ab81890f4f";
    String tenant = "01a090ae-611e-705d-9baf-57c42ffc2fb6";

    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v1, "100"), tenant, alice).getStatus(),
        is(201));
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v2, "100"), tenant, alice).getStatus(),
        is(201));

    adjust(tenant, alice, v1, "-30", "THEFT");
    adjust(tenant, bob, v1, "-5", "DAMAGED");
    adjust(tenant, bob, v2, "-10", "THEFT");
    // A find is a gain, not a negative loss — it must not cancel out the THEFT total.
    adjust(tenant, bob, v2, "8", "FOUND");

    String byReason = shrinkage(tenant, "REASON");
    // THEFT: 30 + 10 = 40 across two movements, and it outweighs DAMAGED so it sorts first.
    assertThat(byReason.indexOf("THEFT") < byReason.indexOf("DAMAGED"), is(true));
    assertThat(numericFieldNear(byReason, "THEFT", "qtyWrittenOff"), is("40.000"));
    assertThat(numericFieldNear(byReason, "DAMAGED", "qtyWrittenOff"), is("5.000"));
    // FOUND is a gain: it appears with nothing written off and 8 found.
    assertThat(numericFieldNear(byReason, "FOUND", "qtyWrittenOff"), is("0.000"));
    assertThat(numericFieldNear(byReason, "FOUND", "qtyFound"), is("8.000"));

    // Grouped by actor: bob wrote off 15 across three movements, alice 30 across one.
    String byActor = shrinkage(tenant, "ACTOR");
    assertThat(numericFieldNear(byActor, alice, "qtyWrittenOff"), is("30.000"));
    assertThat(numericFieldNear(byActor, bob, "qtyWrittenOff"), is("15.000"));
    assertThat(numericFieldNear(byActor, bob, "movements"), is("3"));

    // Grouped by store, everything lands on the one store used here: 45 off, 8 found, net -37.
    String byStore = shrinkage(tenant, "STORE");
    assertThat(numericFieldNear(byStore, S, "qtyWrittenOff"), is("45.000"));
    assertThat(numericFieldNear(byStore, S, "netQty"), is("-37.000"));

    // Receipts are not adjustments and must never appear as shrinkage.
    assertThat(byReason, not(containsString("\"groupKey\":\"RECEIVE\"")));
  }

  /** Drill-down answers "what did this person actually write off?". */
  @Test
  void shrinkageDrillsDownToVariantsForOneActor() {
    String v1 = "01a090ae-611e-703f-bbf0-259f9ebf19ef";
    String v2 = "01a090ae-611e-7040-b89f-292726780a3f";
    String carol = "01a090ae-611e-7059-9671-db63e8725245";
    String tenant = "01a090ae-611e-705e-9448-d8d984956c9f";

    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v1, "50"), tenant, carol).getStatus(),
        is(201));
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v2, "50"), tenant, carol).getStatus(),
        is(201));
    adjust(tenant, carol, v1, "-20", "THEFT");
    adjust(tenant, carol, v2, "-3", "THEFT");

    String body =
        target
            .path("/admin/inventory/reports/shrinkage/by-variant")
            .queryParam("actorId", carol)
            .queryParam("reasonCode", "theft")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get(String.class);
    // Heaviest first, and reasonCode is matched case-insensitively.
    assertThat(body.indexOf(v1) < body.indexOf(v2), is(true));
    assertThat(numericFieldNear(body, v1, "qtyWrittenOff"), is("20.000"));
  }

  /**
   * A report is only as trustworthy as its isolation, and a bad period is a 400 not a silent empty.
   */
  @Test
  void shrinkageIsTenantScopedAndValidatesItsInputs() {
    String v = "01a090ae-611e-7041-8ffc-2de67716e1ea";
    String dave = "01a090ae-611e-705a-80a8-62cf06d4f55f";
    String tenant = "01a090ae-611e-705f-98e4-e88c73e95223";
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v, "40"), tenant, dave).getStatus(),
        is(201));
    adjust(tenant, dave, v, "-9", "EXPIRY");

    assertThat(shrinkage(tenant, "REASON"), containsString("EXPIRY"));
    // Another tenant sees none of it.
    assertThat(shrinkage(OTHER, "REASON"), not(containsString("EXPIRY")));

    assertThat(shrinkageStatus(tenant, "groupBy", "SUPPLIER"), is(400));
    assertThat(shrinkageStatus(tenant, "from", "last-tuesday"), is(400));
    assertThat(shrinkageStatus(tenant, "storeId", "not-a-uuid"), is(400));

    // The code a caller switches on: groupBy is this service's own vocabulary and keeps a
    // service-scoped code, but a malformed uuid or timestamp means the same thing on every
    // endpoint in the platform, so it answers with the shared one rather than an inventory-
    // specific variant of it.
    assertThat(
        shrinkageError(tenant, "groupBy", "SUPPLIER"),
        containsString("INVENTORY_INVALID_GROUPING"));
    assertThat(shrinkageError(tenant, "from", "last-tuesday"), containsString("\"INVALID_DATE\""));
    assertThat(shrinkageError(tenant, "storeId", "not-a-uuid"), containsString("\"INVALID_UUID\""));
  }

  private void adjust(
      String tenant, String actor, String variantId, String delta, String reasonCode) {
    Response r =
        postAs(
            "/admin/inventory/adjust",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + variantId
                + "\",\"delta\":"
                + delta
                + ",\"reasonCode\":\""
                + reasonCode
                + "\"}",
            tenant,
            actor);
    assertThat(r.getStatus(), is(200));
  }

  private String shrinkage(String tenant, String groupBy) {
    return target
        .path("/admin/inventory/reports/shrinkage")
        .queryParam("groupBy", groupBy)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  private String shrinkageError(String tenant, String param, String value) {
    return target
        .path("/admin/inventory/reports/shrinkage")
        .queryParam(param, value)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get()
        .readEntity(String.class);
  }

  private int shrinkageStatus(String tenant, String param, String value) {
    return target
        .path("/admin/inventory/reports/shrinkage")
        .queryParam(param, value)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get()
        .getStatus();
  }

  /** Movements for one variant, filtered by type. */
  private String movements(String variantId, String type) {
    return target
        .path("/admin/inventory/movements")
        .queryParam("variantId", variantId)
        .queryParam("type", type)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  private static String receiveJson(String variantId, String qty) {
    return "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variantId + "\",\"qty\":" + qty + "}";
  }

  /** Find the value of {@code name} in the JSON object that contains {@code marker}. */
  /** {@link #fieldNear} for an unquoted numeric value. */
  private static String numericFieldNear(String json, String marker, String name) {
    return numericField(objectNear(json, marker), name);
  }

  /** The flat JSON object that mentions {@code marker}. */
  private static String objectNear(String json, String marker) {
    int m = json.indexOf(marker);
    if (m < 0) throw new AssertionError(marker + " not found in " + json);
    int objStart = json.lastIndexOf('{', m);
    int objEnd = json.indexOf('}', m);
    return json.substring(objStart, objEnd + 1);
  }

  private static String fieldNear(String json, String marker, String name) {
    int m = json.indexOf(marker);
    if (m < 0) throw new AssertionError(marker + " not found in " + json);
    // scan backward to find the start of the enclosing object
    int objStart = json.lastIndexOf('{', m);
    // find the end of the object (next '}' after the marker position)
    int objEnd = json.indexOf('}', m);
    String obj = json.substring(objStart, objEnd + 1);
    return field(obj, name);
  }

  // ── Stock turn ───────────────────────────────────────────────────────────────

  /**
   * The report's central claim: cost of goods sold comes from the batches the sale actually drew
   * down, not from an average or from today's price. Two batches at different costs, FIFO takes the
   * cheaper one first, and the COGS figure has to reflect exactly that.
   */
  @Test
  void stockTurnCostsSalesAtTheBatchesFifoActuallyDrewDown() {
    String v = "01a090ae-611e-704d-bb45-bfd8df4039a7";
    String tenant = "01a090ae-611e-7068-a227-6af9ac576fee";

    receiveCosted(tenant, v, "10", "2.00"); // 20.00
    receiveCosted(tenant, v, "10", "5.00"); // 50.00

    // Sell 15: FIFO takes all 10 at 2.00 and 5 at 5.00 = 20 + 25 = 45.00, not 15 x 3.50.
    sell(tenant, v, 15);

    String body = stockTurn(tenant, "VARIANT", null);
    assertThat(numericFieldNear(body, v, "cogs"), is("45.00"));
    // The window opens a day before this tenant existed, so it held nothing then. Zero here is
    // the replay working: a report that read remaining_qty live would have called both ends
    // 25.00 and reported the holding as though it had always been there.
    assertThat(numericFieldNear(body, v, "openingValue"), is("0.00"));
    // Closing is the 5 units FIFO left behind, still at 5.00 each.
    assertThat(numericFieldNear(body, v, "closingValue"), is("25.00"));
    assertThat(numericFieldNear(body, v, "averageValue"), is("12.50"));
    // 45.00 / 12.50 = 3.6 turns in the window.
    assertThat(numericFieldNear(body, v, "turnoverRatio"), is("3.6000"));
    // The whole ledger is inside the window, so nothing was purged from under it.
    assertThat(body, containsString("\"historyComplete\":true"));
  }

  /**
   * A sale out of a batch with no cost price contributes nothing to COGS. Costing it at zero would
   * flatter the margin and understate the turns, so it is declared instead — the same rule the
   * valuation report applies to stock it cannot value.
   */
  @Test
  void stockTurnDeclaresSalesItCannotCostRatherThanCostingThemAtZero() {
    String v = "01a090ae-611e-704e-8496-3244583788b7";
    String tenant = "01a090ae-611e-7069-99e7-75fe68768684";

    receiveCosted(tenant, v, "10", "3.00");
    // Second receipt with no costPrice at all.
    assertThat(post("/admin/inventory/receive", receiveJson(v, "10"), tenant).getStatus(), is(201));

    // Sell 14: 10 costed units at 3.00 = 30.00, plus 4 that cannot be costed.
    sell(tenant, v, 14);

    String body = stockTurn(tenant, "VARIANT", null);
    assertThat(numericFieldNear(body, v, "cogs"), is("30.00"));
    assertThat(numericFieldNear(body, v, "uncostedSaleQty"), is("4.000"));
  }

  /**
   * A window that closes before the sales happened must not see them. This is what separates a
   * replayed report from one that reads {@code remaining_qty} live: the latter would answer a
   * question about last month with this month's stock level.
   */
  @Test
  void stockTurnIsBoundedByItsWindowAndValidatesIt() {
    String v = "01a090ae-611e-704f-a30e-7b1d5d8a67a7";
    String tenant = "01a090ae-611e-706a-9c72-8281fbf89ec0";

    receiveCosted(tenant, v, "10", "4.00");
    sell(tenant, v, 6);

    // A window entirely in the past: nothing had been received or sold yet, so the variant is
    // not a row at all rather than a row of zeroes.
    String past =
        target
            .path("/admin/inventory/reports/stock-turn")
            .queryParam("groupBy", "VARIANT")
            .queryParam("from", "2000-01-01T00:00:00Z")
            .queryParam("to", "2000-02-01T00:00:00Z")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(past, not(containsString(v)));

    // from and to are required, and a bare date is not an instant (SJ-D9).
    Response missing =
        target
            .path("/admin/inventory/reports/stock-turn")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(missing.getStatus(), is(400));
    assertThat(missing.readEntity(String.class), containsString("from"));

    Response backwards =
        target
            .path("/admin/inventory/reports/stock-turn")
            .queryParam("from", "2026-02-01T00:00:00Z")
            .queryParam("to", "2026-01-01T00:00:00Z")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(backwards.getStatus(), is(400));
    assertThat(backwards.readEntity(String.class), containsString("INVENTORY_INVALID_PERIOD"));

    Response badGrouping =
        target
            .path("/admin/inventory/reports/stock-turn")
            .queryParam("groupBy", "REASON")
            .queryParam("from", "2026-01-01T00:00:00Z")
            .queryParam("to", "2026-02-01T00:00:00Z")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(badGrouping.getStatus(), is(400));
    assertThat(badGrouping.readEntity(String.class), containsString("INVENTORY_INVALID_GROUPING"));

    // Another tenant's stock never appears in this one's turns.
    assertThat(stockTurn(OTHER, "VARIANT", null), not(containsString(v)));
  }

  // ── SJ-D40: a voided till sale is not a sale ─────────────────────────────────

  /**
   * What inventory-svc does on OrderFulfilled and then OrderVoided for one till sale, driven
   * through the real service. A unit test with a fake service passed while the reports keyed on a
   * movement type the void never writes; only the real write path shows what lands in
   * stock_movements.
   */
  private void sellByOrderThenVoid(String tenant, String variantId, String qty) {
    UUID order = Ids.newId();
    UUID t = Ids.parse(tenant);
    UUID s = Ids.parse(S);
    UUID v = Ids.parse(variantId);
    var q = new java.math.BigDecimal(qty);
    inventoryService.deductSaleFromOrderOnce(Ids.newId(), "it", t, s, v, q, order);
    inventoryService.receiveVoidFromOrderOnce(Ids.newId(), "it", t, s, v, q, order);
  }

  @Test
  void stockTurnDoesNotCountAVoidedSale() {
    String v = "01a090ae-611e-7050-9ee6-9048d44dc0c1";
    String tenant = "01a090ae-611e-706b-87c6-ddc1c5bf86a6";

    receiveCosted(tenant, v, "10", "2.00");
    sellByOrderThenVoid(tenant, v, "4");

    String body = stockTurn(tenant, "VARIANT", null);
    // Netting by sign cannot work here: the void's receipt goes into a new return batch and this
    // report sums per batch, so the sale would keep its 8.00 of cost in one group and the receipt
    // would show as a negative sale in another. A voided sale has to be excluded outright.
    assertThat(numericFieldNear(body, v, "cogs"), is("0.00"));
    assertThat(numericFieldNear(body, v, "uncostedSaleQty"), is("0.000"));
  }

  @Test
  void deadStockDoesNotTreatAVoidedSaleAsTheLastSale() {
    String v = "01a090ae-611e-7054-9161-1d5069fb5a2c";
    String tenant = "01a090ae-611e-706e-af2f-75352a2c0594";

    receiveCosted(tenant, v, "10", "1.00");
    sellByOrderThenVoid(tenant, v, "3");

    String byVariant = deadStock(tenant, "VARIANT", null);
    // A voided sale did not happen. Counting it would make stock that has never sold look as
    // though it moved today, and hide it from the report whose job is to find it.
    assertThat(numericFieldNear(byVariant, v, "neverSold"), is("true"));
  }

  // ── Dead stock ───────────────────────────────────────────────────────────────

  /**
   * The distinction the report exists for: stock that sold recently is not dead however long ago it
   * arrived, and stock that has never sold is aged from its receipt and says so.
   */
  @Test
  void deadStockAgesFromTheLastSaleNotFromReceipt() {
    String moving = "01a090ae-611e-7051-882f-8d6123511877";
    String idle = "01a090ae-611e-7052-a27b-e65d45fee74f";
    String tenant = "01a090ae-611e-706c-8f84-715053a03395";

    receiveCosted(tenant, moving, "10", "1.00");
    receiveCosted(tenant, idle, "10", "9.00");
    sell(tenant, moving, 2); // sold just now

    String byVariant = deadStock(tenant, "VARIANT", null);
    // Both are freshly created here, so both sit in the first band -- what differs is why.
    assertThat(numericFieldNear(byVariant, moving, "daysSinceLastSale"), is("0"));
    assertThat(numericFieldNear(byVariant, moving, "neverSold"), is("false"));
    // The idle line has never sold: its age is measured from receipt, and it says so.
    assertThat(numericFieldNear(byVariant, idle, "neverSold"), is("true"));
    // 8 units left at 1.00 against 10 at 9.00 -- ordered by value at risk, so idle comes first.
    assertThat(numericFieldNear(byVariant, idle, "value"), is("90.00"));
    assertThat(numericFieldNear(byVariant, moving, "value"), is("8.00"));
    assertThat(byVariant.indexOf(idle) < byVariant.indexOf(moving), is(true));

    // The default grouping is the ageing ladder, and everything here is under 30 days old.
    String ladder = deadStock(tenant, null, null);
    assertThat(numericFieldNear(ladder, "0-30", "value"), is("98.00"));
    assertThat(numericFieldNear(ladder, "0-30", "onHandQty"), is("18.000"));
    // A mixed bucket is not "never sold" just because one line in it never has.
    assertThat(numericFieldNear(ladder, "0-30", "neverSold"), is("false"));
  }

  /**
   * Ageing is measured from a caller-supplied instant so the report is reproducible, and the ladder
   * puts stock in the band that instant implies rather than the band today implies.
   */
  @Test
  void deadStockLaddersAgainstTheSuppliedAsOfInstant() {
    String v = "01a090ae-611e-7053-8723-d0f48f967cec";
    String tenant = "01a090ae-611e-706d-a888-e253c80fa001";

    receiveCosted(tenant, v, "4", "2.50"); // never sold, received today

    // Asked about a year from now, today's untouched receipt is deep in the last band.
    String future =
        deadStock(tenant, null, OffsetDateTime.now().plusDays(200).toInstant().toString());
    assertThat(future, containsString("\"groupKey\":\"180+\""));
    assertThat(numericFieldNear(future, "180+", "value"), is("10.00"));

    // Stock that is entirely sold out has nothing at risk and leaves the report.
    sell(tenant, v, 4);
    assertThat(deadStock(tenant, "VARIANT", null), not(containsString(v)));

    Response badGrouping =
        target
            .path("/admin/inventory/reports/dead-stock")
            .queryParam("groupBy", "ACTOR")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(badGrouping.getStatus(), is(400));
    assertThat(badGrouping.readEntity(String.class), containsString("INVENTORY_INVALID_GROUPING"));
  }

  /** Reserve then consume — the only path that writes SALE movements against real batches. */
  private void sell(String tenant, String variantId, int qty) {
    Response reserved =
        post(
            "/inventory/reservations",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + variantId
                + "\",\"qty\":"
                + qty
                + ",\"orderId\":\""
                + Ids.newId()
                + "\"}",
            tenant);
    assertThat(reserved.getStatus(), is(201));
    String id = field(reserved.readEntity(String.class), "id");
    assertThat(post("/inventory/reservations/" + id + "/consume", "", tenant).getStatus(), is(200));
  }

  /** A window wide enough to contain everything a test just did. */
  private String stockTurn(String tenant, String groupBy, String storeId) {
    return windowReport("stock-turn", tenant, groupBy, storeId);
  }

  /** A management report over the day either side of now, as the owner. */
  private String windowReport(String report, String tenant, String groupBy, String storeId) {
    var t =
        target
            .path("/admin/inventory/reports/" + report)
            .queryParam("from", OffsetDateTime.now().minusDays(1).toInstant().toString())
            .queryParam("to", OffsetDateTime.now().plusDays(1).toInstant().toString());
    if (groupBy != null) t = t.queryParam("groupBy", groupBy);
    if (storeId != null) t = t.queryParam("storeId", storeId);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get(String.class);
  }

  private String deadStock(String tenant, String groupBy, String asOf) {
    var t = target.path("/admin/inventory/reports/dead-stock");
    if (groupBy != null) t = t.queryParam("groupBy", groupBy);
    if (asOf != null) t = t.queryParam("asOf", asOf);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get(String.class);
  }

  // ── 19.7: gross margin and GMROI ─────────────────────────────────────────────

  private static java.math.BigDecimal money(String v) {
    return new java.math.BigDecimal(v);
  }

  /** Revenue set against the cost of the batches the sale drew, by both fulfilment paths. */
  @Test
  void grossMarginSetsWhatTheSalesEarnedAgainstWhatTheyCost() {
    UUID t = Ids.newId();
    UUID s = Ids.parse(S);
    UUID v = Ids.newId();
    String tenant = t.toString();
    receiveCosted(tenant, v.toString(), "10", "3.00");

    // A till sale, deducted straight from the order: 4 at 3.00 cost, 20.00 earned.
    inventoryService.deductSaleFromOrderOnce(
        Ids.newId(), "it", t, s, v, money("4"), Ids.newId(), money("20.00"));
    // An online sale, consuming its checkout hold: 2 at 3.00 cost, 11.00 earned.
    Response held =
        post(
            "/inventory/reservations",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + v
                + "\",\"qty\":2,\"orderId\":\""
                + Ids.newId()
                + "\"}",
            tenant);
    assertThat(held.getStatus(), is(201));
    UUID hold = Ids.parse(field(held.readEntity(String.class), "id"));
    inventoryService.consumeOnce(Ids.newId(), "it", t, hold, money("11.00"));

    String body = windowReport("gross-margin", tenant, "VARIANT", null);
    assertThat(numericFieldNear(body, v.toString(), "revenue"), is("31.00"));
    assertThat(money(numericFieldNear(body, v.toString(), "cogs")).compareTo(money("18")), is(0));
    assertThat(
        money(numericFieldNear(body, v.toString(), "grossMargin")).compareTo(money("13")), is(0));
    assertThat(numericFieldNear(body, v.toString(), "marginPercent"), is("41.9"));
    assertThat(numericFieldNear(body, v.toString(), "unpricedSaleQty"), is("0"));

    // GMROI is the margin over the same average holding stock turn reports, not a second replay.
    String average =
        numericFieldNear(
            windowReport("stock-turn", tenant, "VARIANT", null), v.toString(), "averageValue");
    assertThat(numericFieldNear(body, v.toString(), "averageValue"), is(average));
    assertThat(
        money(numericFieldNear(body, v.toString(), "gmroi")),
        is(money("13").divide(money(average), 2, java.math.RoundingMode.HALF_UP)));

    // By store, the same sales roll up; another tenant sees none of it.
    String byStore = windowReport("gross-margin", tenant, "STORE", null);
    assertThat(numericFieldNear(byStore, S, "revenue"), is("31.00"));
    assertThat(
        windowReport("gross-margin", OTHER, "VARIANT", null), not(containsString(v.toString())));
  }

  /**
   * A return takes back its share of the revenue and the cost, once however often it is delivered,
   * and never more than the line has left to return.
   */
  @Test
  void aReturnTakesBackRevenueAndCostOnceAndNeverMoreThanWasSold() {
    UUID t = Ids.newId();
    UUID s = Ids.parse(S);
    UUID v = Ids.newId();
    UUID order = Ids.newId();
    String tenant = t.toString();
    receiveCosted(tenant, v.toString(), "10", "2.00");

    UUID sale = Ids.newId();
    assertThat(
        inventoryService.deductSaleFromOrderOnce(
            sale, "it", t, s, v, money("5"), order, money("25.00")),
        is(true));
    // Redelivered: the dedupe mark that stops a second deduction stops a second revenue row.
    assertThat(
        inventoryService.deductSaleFromOrderOnce(
            sale, "it", t, s, v, money("5"), order, money("25.00")),
        is(false));

    UUID ret = Ids.newId();
    assertThat(
        inventoryService.receiveReturnFromOrderOnce(ret, "it", t, s, v, money("2"), order),
        is(true));
    assertThat(
        inventoryService.receiveReturnFromOrderOnce(ret, "it", t, s, v, money("2"), order),
        is(false));

    String body = windowReport("gross-margin", tenant, "VARIANT", null);
    assertThat(numericFieldNear(body, v.toString(), "revenue"), is("15.00"));
    assertThat(money(numericFieldNear(body, v.toString(), "cogs")).compareTo(money("6")), is(0));
    assertThat(numericFieldNear(body, v.toString(), "marginPercent"), is("60.0"));

    // Returning ten against three unreturned takes back three: the line nets to nothing sold, so it
    // is not a margin row at all rather than a row with a negative revenue.
    inventoryService.receiveReturnFromOrderOnce(Ids.newId(), "it", t, s, v, money("10"), order);
    assertThat(
        windowReport("gross-margin", tenant, "VARIANT", null), not(containsString(v.toString())));
  }

  /**
   * A sale with no revenue is declared rather than priced at zero; a voided sale earns nothing; the
   * report is management's, and its window and grouping are validated.
   */
  @Test
  void grossMarginDeclaresUnpricedSalesExcludesVoidsAndIsGuarded() {
    UUID t = Ids.newId();
    UUID s = Ids.parse(S);
    UUID v = Ids.newId();
    String tenant = t.toString();
    receiveCosted(tenant, v.toString(), "10", "1.00");

    sell(tenant, v.toString(), 3); // consumed with no revenue: a sale from before 19.7
    UUID voided = Ids.newId();
    inventoryService.deductSaleFromOrderOnce(
        Ids.newId(), "it", t, s, v, money("2"), voided, money("50.00"));
    inventoryService.receiveVoidFromOrderOnce(Ids.newId(), "it", t, s, v, money("2"), voided);

    String row = objectNear(windowReport("gross-margin", tenant, "VARIANT", null), v.toString());
    assertThat(numericField(row, "unpricedSaleQty"), is("3.000"));
    assertThat(money(numericField(row, "revenue")).signum(), is(0));
    assertThat(money(numericField(row, "cogs")).compareTo(money("3")), is(0));
    // Nothing earned has no margin percentage, whether it is serialised as null or left out.
    assertThat(row.matches("(?s).*\"marginPercent\":\\s*-?\\d.*"), is(false));

    String[][] rejected = {
      {null, null, null, "from"},
      {"2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z", null, "INVENTORY_INVALID_PERIOD"},
      {"2026-01-01", "2026-02-01T00:00:00Z", null, "from"},
      {"2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", "REASON", "INVENTORY_INVALID_GROUPING"},
      {
        "2026-01-01T00:00:00Z",
        "2026-02-01T00:00:00Z",
        "store_id;DROP TABLE sale_revenue",
        "INVENTORY_INVALID_GROUPING"
      },
    };
    for (String[] bad : rejected) {
      var q = target.path("/admin/inventory/reports/gross-margin");
      if (bad[0] != null) q = q.queryParam("from", bad[0]);
      if (bad[1] != null) q = q.queryParam("to", bad[1]);
      if (bad[2] != null) q = q.queryParam("groupBy", bad[2]);
      Response r = q.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
      assertThat(String.join(",", java.util.Arrays.asList(bad)), r.getStatus(), is(400));
      assertThat(r.readEntity(String.class), containsString(bad[3]));
    }

    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Response r =
          target
              .path("/admin/inventory/reports/gross-margin")
              .queryParam("from", "2026-01-01T00:00:00Z")
              .queryParam("to", "2026-02-01T00:00:00Z")
              .request()
              .header("X-Tenant-Id", tenant)
              .header("X-Roles", role)
              .get();
      assertThat(role, r.getStatus(), is(403));
    }

    // An absurd limit is clamped, not obeyed and not an error.
    for (String limit : new String[] {"-5", "0", "1000000"}) {
      Response r =
          target
              .path("/admin/inventory/reports/gross-margin")
              .queryParam("from", OffsetDateTime.now().minusDays(1).toInstant().toString())
              .queryParam("to", OffsetDateTime.now().plusDays(1).toInstant().toString())
              .queryParam("limit", limit)
              .request()
              .header("X-Tenant-Id", tenant)
              .header("X-Roles", "OWNER")
              .get();
      assertThat(limit, r.getStatus(), is(200));
    }
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
