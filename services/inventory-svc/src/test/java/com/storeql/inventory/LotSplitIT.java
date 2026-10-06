package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import com.storeql.test.Concurrency;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A lot split moves stock, it does not make any. The quantity leaves the source batch and arrives
 * in the child, on one transaction with the movements that say so, the lot action, the genealogy
 * link and the {@code LotSplit} event; what the business holds of the variant is the same after as
 * before, and the ledger nets to zero without ever reading as a receipt or a sale. Real Postgres;
 * Kafka and Consul disabled.
 */
@HelidonTest
class LotSplitIT {

  private static final PostgresSupport PG;

  /** A recall asks tenant-svc where the business trades; {@code lotOf} registers a British one. */
  private static final TenantSvcStub TENANTS;

  static {
    TENANTS = TenantSvcStub.start();
    PG = PostgresSupport.start().wire("inventory");
  }

  @Inject WebTarget target;
  @Inject InventoryService inventory;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  /** One business's batch of one variant at one store, and the person who received it. */
  private record Lot(String tenant, String staff, String store, String variant, String batch) {}

  private Invocation.Builder as(String path, String tenant, String user, String role) {
    Invocation.Builder b =
        WebTargets.at(target, path).request().header("X-Tenant-Id", tenant).header("X-Roles", role);
    return user == null ? b : b.header("X-User-Id", user);
  }

  private Response post(String path, String json, String tenant, String user, String role) {
    return as(path, tenant, user, role).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json, String tenant, String role) {
    return as(path, tenant, null, role).put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Receives {@code qty} into a new batch of a new variant, at a new store, for a new business. */
  private Lot lot(int qty, String batchNo, String cost, String expiry) {
    return lotOf(Ids.newId().toString(), Ids.newId().toString(), qty, batchNo, cost, expiry, "");
  }

  private Lot lotOf(
      String tenant,
      String staff,
      int qty,
      String batchNo,
      String cost,
      String expiry,
      String more) {
    TENANTS.with(tenant, "GBP", "GB");
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    JsonObject received =
        Envelopes.created(
            post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + store
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"qty\":"
                    + qty
                    + (batchNo == null ? "" : ",\"batchNo\":\"" + batchNo + "\"")
                    + ",\"costPrice\":"
                    + cost
                    + ",\"expiryDate\":\""
                    + expiry
                    + "\""
                    + more
                    + "}",
                tenant,
                staff,
                "OWNER"));
    return new Lot(tenant, staff, store, variant, received.getString("id"));
  }

  /** A split of {@code batch}, asked as {@code user} of {@code role} in the business {@code as}. */
  private Response splitAs(
      String as, String user, String role, String batch, String qty, String no) {
    return post(
        "/admin/inventory/lots/split",
        "{\"sourceBatchId\":\""
            + batch
            + "\",\"qty\":"
            + qty
            + (no == null ? "" : ",\"batchNo\":\"" + no + "\"")
            + "}",
        as,
        user,
        role);
  }

  /** A split of the lot's own batch (or another batch of the same business) by its own owner. */
  private Response split(Lot f, String batch, String qty, String no) {
    return splitAs(f.tenant(), f.staff(), "OWNER", batch, qty, no);
  }

  private JsonObject splitOk(Lot f, String qty, String no) {
    return Envelopes.ok(split(f, f.batch(), qty, no));
  }

  private static String sql(String query) {
    return Envelopes.scalar(PG, query);
  }

  private static BigDecimal number(String query) {
    return new BigDecimal(sql(query));
  }

  private static BigDecimal remaining(String batch) {
    return number(
        "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  private static BigDecimal received(String batch) {
    return number(
        "SELECT received_qty FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  /** What the business holds of the variant: the sum over its batches. */
  private static BigDecimal held(Lot f) {
    return number(
        "SELECT coalesce(sum(remaining_qty), 0) FROM inventory.inventory_batches WHERE tenant_id ="
            + " '"
            + f.tenant()
            + "' AND variant_id = '"
            + f.variant()
            + "'");
  }

  /**
   * What the business's batches of the variant are worth at their own cost: what valuation sums.
   */
  private static BigDecimal worth(Lot f) {
    return number(
        "SELECT coalesce(sum(remaining_qty * cost_price), 0) FROM inventory.inventory_batches"
            + " WHERE tenant_id = '"
            + f.tenant()
            + "' AND variant_id = '"
            + f.variant()
            + "'");
  }

  private static long count(String table, Lot f) {
    return Long.parseLong(
        sql("SELECT count(*) FROM inventory." + table + " WHERE tenant_id = '" + f.tenant() + "'"));
  }

  private static long movements(Lot f, String type) {
    return Long.parseLong(
        sql(
            "SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '"
                + f.tenant()
                + "' AND variant_id = '"
                + f.variant()
                + "' AND type = '"
                + type
                + "'"));
  }

  private static long events(Lot f, String eventType) {
    return Long.parseLong(
        sql(
            "SELECT count(*) FROM inventory.outbox WHERE tenant_id = '"
                + f.tenant()
                + "' AND event_type = '"
                + eventType
                + "'"));
  }

  private static long batches(Lot f) {
    return count("inventory_batches", f);
  }

  /** Everything a split writes, as one string: it must read the same before and after a refusal. */
  private static String footprint(Lot f) {
    return batches(f)
        + "|"
        + held(f)
        + "|"
        + remaining(f.batch())
        + "|"
        + count("stock_movements", f)
        + "|"
        + count("lot_actions", f)
        + "|"
        + count("lot_genealogy", f)
        + "|"
        + count("outbox", f);
  }

  private BigDecimal onHand(Lot f) {
    JsonArray levels =
        Envelopes.okArray(
            as("/admin/inventory/levels?store=" + f.store(), f.tenant(), f.staff(), "OWNER").get());
    JsonObject row = Envelopes.find(levels, "variantId", f.variant());
    return row.getJsonNumber("onHand").bigDecimalValue();
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  // ── the quantity moves ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A split takes the quantity out of the source: what the business holds is unchanged")
  void aSplitTakesTheQuantityOutOfTheSource() {
    Lot f = lot(20, "SRC", "2.5000", "2098-01-01");
    assertThat(onHand(f), comparesEqualTo(d("20")));
    assertThat(worth(f), comparesEqualTo(d("50")));

    JsonObject action = splitOk(f, "8", "CHILD");
    String child = action.getString("resultBatchId");

    assertThat(action.getString("actionType"), is("SPLIT"));
    assertThat(action.getString("sourceBatchId"), is(f.batch()));
    assertThat(remaining(f.batch()), comparesEqualTo(d("12")));
    assertThat(remaining(child), comparesEqualTo(d("8")));
    assertThat(received(child), comparesEqualTo(d("8")));
    assertThat(
        "the sum over source and child is what was received", held(f), comparesEqualTo(d("20")));
    assertThat("the level is the same after as before", onHand(f), comparesEqualTo(d("20")));
    assertThat("so is what the stock is worth", worth(f), comparesEqualTo(d("50")));

    // The child is the same stock at the same place: store, variant, cost and date come across.
    assertThat(
        sql(
            "SELECT store_id || '|' || variant_id || '|' || batch_no || '|' || cost_price || '|'"
                + " || expiry_date FROM inventory.inventory_batches WHERE id = '"
                + child
                + "'"),
        is(f.store() + "|" + f.variant() + "|CHILD|2.5000|2098-01-01"));
  }

  @Test
  @DisplayName(
      "Splitting the whole of a batch leaves the source empty and the child holding it all")
  void splittingTheWholeBatchLeavesTheSourceEmpty() {
    Lot f = lot(6, "WHOLE", "1.0000", "2098-01-01");
    String child = splitOk(f, "6", null).getString("resultBatchId");

    assertThat(remaining(f.batch()), comparesEqualTo(d("0")));
    assertThat(remaining(child), comparesEqualTo(d("6")));
    assertThat(held(f), comparesEqualTo(d("6")));
    assertThat(onHand(f), comparesEqualTo(d("6")));
    assertThat(
        "the child carries its source's lot number: it is the same stock",
        sql("SELECT batch_no FROM inventory.inventory_batches WHERE id = '" + child + "'"),
        is("WHOLE"));
  }

  @Test
  @DisplayName(
      "The ledger nets to zero: out of the source, into the child, never a receipt or a sale")
  void theLedgerNetsToZero() {
    Lot f = lot(20, "LEDGER", "2.5000", "2098-01-01");
    JsonObject action = splitOk(f, "8", "LEDGER-CHILD");
    String child = action.getString("resultBatchId");

    assertThat("only the original receipt is a receipt", movements(f, "RECEIVE"), is(1L));
    assertThat(movements(f, "SALE"), is(0L));
    assertThat(movements(f, "LOT_SPLIT"), is(2L));
    assertThat(
        number(
            "SELECT sum(qty) FROM inventory.stock_movements WHERE tenant_id = '"
                + f.tenant()
                + "' AND type = 'LOT_SPLIT'"),
        comparesEqualTo(d("0")));
    assertThat(
        "out of the source",
        number(
            "SELECT qty FROM inventory.stock_movements WHERE batch_id = '"
                + f.batch()
                + "' AND type = 'LOT_SPLIT'"),
        comparesEqualTo(d("-8")));
    assertThat(
        "into the child",
        number(
            "SELECT qty FROM inventory.stock_movements WHERE batch_id = '"
                + child
                + "' AND type = 'LOT_SPLIT'"),
        comparesEqualTo(d("8")));

    // Both legs cite the lot action, and name the person who split.
    assertThat(
        sql(
            "SELECT string_agg(DISTINCT ref_type || '|' || ref_id || '|' || actor_id, ',')"
                + " FROM inventory.stock_movements WHERE tenant_id = '"
                + f.tenant()
                + "' AND type = 'LOT_SPLIT'"),
        is("LOT_SPLIT|" + action.getString("id") + "|" + f.staff()));

    // Every batch's movements sum to what it holds: the replay the stock-turn report relies on.
    for (String batch : List.of(f.batch(), child)) {
      assertThat(
          batch,
          number(
              "SELECT sum(qty) FROM inventory.stock_movements WHERE tenant_id = '"
                  + f.tenant()
                  + "' AND batch_id = '"
                  + batch
                  + "'"),
          comparesEqualTo(remaining(batch)));
    }
    // And what the business holds is what the ledger says it holds.
    assertThat(
        number(
            "SELECT sum(qty) FROM inventory.stock_movements WHERE tenant_id = '"
                + f.tenant()
                + "' AND variant_id = '"
                + f.variant()
                + "'"),
        comparesEqualTo(held(f)));

    // The movements list reads the same: a receipt filter finds one, a sale filter none.
    String base = "/admin/inventory/movements?variant=" + f.variant();
    assertThat(
        Envelopes.okArray(as(base + "&type=RECEIVE", f.tenant(), f.staff(), "OWNER").get()).size(),
        is(1));
    assertThat(
        Envelopes.okArray(as(base + "&type=SALE", f.tenant(), f.staff(), "OWNER").get()).size(),
        is(0));
    JsonArray split =
        Envelopes.okArray(as(base + "&type=LOT_SPLIT", f.tenant(), f.staff(), "OWNER").get());
    assertThat(split.size(), is(2));
    assertThat(split.getJsonObject(0).getString("actorId"), is(f.staff()));
  }

  @Test
  @DisplayName("A split is announced once as a LotSplit, and creates no stock event")
  void aSplitIsAnnouncedOnceAndCreatesNoStockEvent() {
    Lot f = lot(10, "EVT", "1.0000", "2098-01-01");
    String child = splitOk(f, "4", null).getString("resultBatchId");

    assertThat(events(f, "LotSplit"), is(1L));
    assertThat(
        sql(
            "SELECT aggregate_id || '|' || (payload::json ->> 'newBatchId') || '|'"
                + " || (payload::json ->> 'qty') FROM inventory.outbox WHERE tenant_id = '"
                + f.tenant()
                + "' AND event_type = 'LotSplit'"),
        is(f.batch() + "|" + child + "|4"));
    assertThat("nothing was received", events(f, "StockReceived"), is(1L));
    assertThat("nothing was adjusted", events(f, "StockAdjusted"), is(0L));
  }

  @Test
  @DisplayName("The child is linked to its source, and the split shows against both batches")
  void theChildIsLinkedToItsSource() {
    Lot f = lot(10, "LINK", "1.0000", "2098-01-01");
    JsonObject action = splitOk(f, "3", null);
    String child = action.getString("resultBatchId");

    JsonArray down =
        Envelopes.ok(
                as(
                        "/admin/inventory/lot-genealogy/batch/" + f.batch() + "/descendants",
                        f.tenant(),
                        f.staff(),
                        "OWNER")
                    .get())
            .getJsonArray("descendants");
    assertThat(down.size(), is(1));
    assertThat(down.getJsonObject(0).getString("childBatchId"), is(child));
    assertThat(down.getJsonObject(0).getString("relationType"), is("SPLIT"));
    assertThat(
        down.getJsonObject(0).getJsonNumber("qty").bigDecimalValue(), comparesEqualTo(d("3")));

    for (String batch : List.of(f.batch(), child)) {
      JsonArray actions =
          Envelopes.okArray(
              as("/admin/inventory/lots/" + batch + "/actions", f.tenant(), f.staff(), "OWNER")
                  .get());
      assertThat(batch, actions.size(), is(1));
      assertThat(actions.getJsonObject(0).getString("id"), is(action.getString("id")));
    }
  }

  @Test
  @DisplayName("The child keeps the condition of its source: ownership, duty, status and grade")
  void theChildKeepsTheConditionOfItsSource() {
    String supplier = Ids.newId().toString();
    Lot f =
        lotOf(
            Ids.newId().toString(),
            Ids.newId().toString(),
            10,
            "COND",
            "1.0000",
            "2098-01-01",
            ",\"ownership\":\"CONSIGNMENT\",\"supplierId\":\"" + supplier + "\"");
    assertThat(
        put(
                "/admin/inventory/batches/" + f.batch() + "/grade",
                "{\"grade\":\"B\"}",
                f.tenant(),
                "OWNER")
            .getStatus(),
        is(200));
    // Held off sale, and in bond: stock in either condition is never sellable, split or not.
    assertThat(
        put(
                "/admin/inventory/batches/" + f.batch() + "/material-status",
                "{\"materialStatus\":\"QUARANTINE\",\"reason\":\"Awaiting lab result\"}",
                f.tenant(),
                "OWNER")
            .getStatus(),
        is(200));
    Envelopes.exec(
        PG,
        "UPDATE inventory.inventory_batches SET duty_status = 'DUTY_SUSPENDED' WHERE id = '"
            + f.batch()
            + "'");

    String child = splitOk(f, "4", null).getString("resultBatchId");

    assertThat(
        sql(
            "SELECT ownership || '|' || coalesce(owner_supplier_id::text, 'none') || '|' ||"
                + " duty_status || '|' || material_status || '|' ||"
                + " coalesce(material_status_reason, 'none') || '|' || coalesce(grade, 'none')"
                + " FROM inventory.inventory_batches WHERE id = '"
                + child
                + "'"),
        is("CONSIGNMENT|" + supplier + "|DUTY_SUSPENDED|QUARANTINE|Awaiting lab result|B"));
    assertThat(
        "the source keeps what it held back",
        sql(
            "SELECT material_status || '|' || duty_status FROM inventory.inventory_batches WHERE id"
                + " = '"
                + f.batch()
                + "'"),
        is("QUARANTINE|DUTY_SUSPENDED"));
  }

  // ── what a split refuses ───────────────────────────────────────────────────

  @Test
  @DisplayName("A split of more than the batch holds is refused, and nothing moves")
  void aSplitOfMoreThanTheBatchHoldsIsRefused() {
    Lot f = lot(5, "SMALL", "1.0000", "2098-01-01");
    String before = footprint(f);

    Response r = split(f, f.batch(), "5.001", "NOPE");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(422));
    assertThat(body, containsString("INSUFFICIENT_QTY"));

    assertThat(footprint(f), is(before));
    assertThat(remaining(f.batch()), comparesEqualTo(d("5")));
  }

  @Test
  @DisplayName("The same split sent twice moves what the batch holds, never more")
  void theSameSplitSentTwiceNeverMovesMoreThanTheBatchHolds() {
    Lot f = lot(10, "TWICE", "1.0000", "2098-01-01");
    splitOk(f, "6", "TWICE-1");
    String before = footprint(f);

    Response again = split(f, f.batch(), "6", "TWICE-1");
    assertThat(again.readEntity(String.class), again.getStatus(), is(422));

    assertThat(footprint(f), is(before));
    assertThat(remaining(f.batch()), comparesEqualTo(d("4")));
    assertThat("the source and the one child", batches(f), is(2L));
    assertThat(held(f), comparesEqualTo(d("10")));
    assertThat(events(f, "LotSplit"), is(1L));
  }

  @Test
  @DisplayName("A batch no one has is not found, and nothing is written")
  void aBatchNoOneHasIsNotFound() {
    Lot f = lot(5, "KNOWN", "1.0000", "2098-01-01");
    String before = footprint(f);

    Response r = split(f, Ids.newId().toString(), "1", null);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(404));
    assertThat(body, containsString("BATCH_NOT_FOUND"));

    assertThat(footprint(f), is(before));
  }

  @Test
  @DisplayName("Another business, of any role, cannot split our batch, and nothing of ours moves")
  void anotherBusinessCannotSplitOurBatch() {
    Lot f = lot(20, "OURS", "1.0000", "2098-01-01");
    String before = footprint(f);
    String other = Ids.newId().toString();

    for (String role :
        List.of("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
      Response r = splitAs(other, Ids.newId().toString(), role, f.batch(), "5", "THEIRS");
      String body = r.readEntity(String.class);
      assertThat(role + ": " + body, r.getStatus(), is(oneOf(403, 404)));
      assertThat(role + ": " + body, body.contains(f.batch()), is(false));
    }
    // Naming our batch as theirs: a request under another tenant id reaches nothing of ours.
    Response named =
        post(
            "/admin/inventory/lots/split",
            "{\"sourceBatchId\":\"" + f.batch() + "\",\"qty\":5}",
            other,
            Ids.newId().toString(),
            "OWNER");
    assertThat(named.readEntity(String.class), named.getStatus(), is(404));

    assertThat(footprint(f), is(before));
    assertThat(remaining(f.batch()), comparesEqualTo(d("20")));
    assertThat(
        "the other business has nothing",
        sql("SELECT count(*) FROM inventory.inventory_batches WHERE tenant_id = '" + other + "'"),
        is("0"));
    assertThat(
        sql("SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '" + other + "'"),
        is("0"));
    assertThat(
        sql("SELECT count(*) FROM inventory.lot_actions WHERE tenant_id = '" + other + "'"),
        is("0"));
  }

  @Test
  @DisplayName("Two splits racing for the same stock: one wins, the other is refused, none is made")
  void twoSplitsRacingForTheSameStock() throws Exception {
    Lot f = lot(10, "RACE", "1.0000", "2098-01-01");

    List<Integer> statuses =
        Concurrency.inParallel(
            2,
            () -> {
              Response r = split(f, f.batch(), "8", null);
              r.readEntity(String.class);
              return r.getStatus();
            });

    assertThat(statuses, containsInAnyOrder(200, 422));
    assertThat(remaining(f.batch()), comparesEqualTo(d("2")));
    assertThat(held(f), comparesEqualTo(d("10")));
    assertThat(batches(f), is(2L));
    assertThat(movements(f, "LOT_SPLIT"), is(2L));
  }

  // ── one transaction ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A split that fails at any of its writes leaves the source, the ledger and lots as were")
  void aSplitThatFailsPartWayChangesNothing() {
    // The split writes the child batch, two movements, a genealogy link, a lot action and an
    // outbox row. Whichever of the last four is refused, none of the others may stay behind.
    for (String table : List.of("stock_movements", "lot_genealogy", "lot_actions", "outbox")) {
      Lot f = lot(10, "ATOMIC-" + table, "1.0000", "2098-01-01");
      String before = footprint(f);
      String trigger = "refuse_" + table + "_" + f.tenant().replace('-', '_').substring(24);
      Envelopes.exec(
          PG,
          "CREATE FUNCTION inventory."
              + trigger
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
              + " IF NEW.tenant_id = '"
              + f.tenant()
              + "' THEN RAISE EXCEPTION 'refused for the test'; END IF; RETURN NEW; END $$");
      Envelopes.exec(
          PG,
          "CREATE TRIGGER "
              + trigger
              + " BEFORE INSERT ON inventory."
              + table
              + " FOR EACH ROW EXECUTE FUNCTION inventory."
              + trigger
              + "()");
      try {
        Response r = split(f, f.batch(), "4", "ATOMIC-CHILD");
        String body = r.readEntity(String.class);
        assertThat(table + ": " + body, r.getStatus(), greaterThanOrEqualTo(500));
      } finally {
        Envelopes.exec(PG, "DROP TRIGGER " + trigger + " ON inventory." + table);
        Envelopes.exec(PG, "DROP FUNCTION inventory." + trigger + "()");
      }

      assertThat(table + ": nothing of the split stays", footprint(f), is(before));
      assertThat(table, remaining(f.batch()), comparesEqualTo(d("10")));
    }
  }

  // ── a split inside a recall ────────────────────────────────────────────────

  private Response openRecall(Lot f, String reference, String lot) {
    String line =
        "{\"variantId\":\""
            + f.variant()
            + "\""
            + (lot == null ? "" : ",\"batchNo\":\"" + lot + "\"")
            + "}";
    return post(
        "/admin/recalls",
        "{\"reference\":\""
            + reference
            + "\",\"kind\":\"WITHDRAWAL\",\"hazard\":\"ALLERGEN\",\"reason\":\"Undeclared peanut\","
            + "\"source\":\"FSA\",\"items\":["
            + line
            + "]}",
        f.tenant(),
        f.staff(),
        "OWNER");
  }

  /** Opens a recall of the lot (or of every pack of the variant for a null lot). */
  private JsonObject recall(Lot f, String reference, String lot) {
    return Envelopes.created(openRecall(f, reference, lot));
  }

  /** The batches a recall holds, by id, each with how sure the recall is of it. */
  private static Map<String, String> heldBatches(JsonObject recall) {
    Map<String, String> out = new HashMap<>();
    for (JsonObject b : recall.getJsonArray("batches").getValuesAs(JsonObject.class)) {
      out.put(b.getString("batchId"), b.getString("match"));
    }
    return out;
  }

  private Response recallAction(Lot f, String recall, String what, String json) {
    return post("/admin/recalls/" + recall + "/" + what, json, f.tenant(), f.staff(), "OWNER");
  }

  private Response cancelRecall(Lot f, String recall) {
    return recallAction(f, recall, "cancel", "{\"reason\":\"Opened in error\"}");
  }

  private Response closeRecall(Lot f, String recall) {
    return recallAction(f, recall, "close", "{\"notes\":\"All of it recovered\"}");
  }

  private Response destroyAtStore(Lot f, String recall, String qty) {
    return post(
        "/admin/inventory/recalls/" + recall + "/stores/" + f.store() + "/actions",
        "{\"qtyFound\":" + qty + ",\"disposition\":\"DESTROYED\",\"noticeDisplayed\":true}",
        f.tenant(),
        f.staff(),
        "OWNER");
  }

  private Response releaseBatch(Lot f, String recall, String batch) {
    return post(
        "/admin/inventory/recalls/" + recall + "/batches/" + batch + "/release",
        "{\"reason\":\"Pack checked: not affected\"}",
        f.tenant(),
        f.staff(),
        "OWNER");
  }

  private static String materialStatus(String batch) {
    return sql(
        "SELECT material_status FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  private static String statusReason(String batch) {
    return sql(
        "SELECT coalesce(material_status_reason, 'none') FROM inventory.inventory_batches"
            + " WHERE id = '"
            + batch
            + "'");
  }

  private static String batchNo(String batch) {
    return sql(
        "SELECT coalesce(batch_no, 'none') FROM inventory.inventory_batches WHERE id = '"
            + batch
            + "'");
  }

  /** How a recall holds a batch: match, the status to restore, the quantity held, and when. */
  private static String hold(String recall, String batch) {
    return sql(
        "SELECT match_type || '|' || prior_material_status || '|' || qty_at_quarantine || '|' ||"
            + " quarantined_on FROM inventory.recall_batches WHERE recall_id = '"
            + recall
            + "' AND batch_id = '"
            + batch
            + "'");
  }

  /** A sale of the variant at the store, as the OrderFulfilled consumer records it. */
  private String sell(Lot f, String qty) {
    UUID order = Ids.newId();
    inventory.deductSaleFromOrderOnce(
        Ids.newId(),
        "it",
        Ids.parse(f.tenant()),
        Ids.parse(f.store()),
        Ids.parse(f.variant()),
        new BigDecimal(qty),
        order);
    return order.toString();
  }

  @Test
  @DisplayName("A split of stock a recall holds is held by that recall, and cancelling frees both")
  void aSplitOfHeldStockJoinsTheRecallAndCancellingRestoresBoth() {
    Lot f = lot(20, "HELD-1", "1.0000", "2098-01-01");
    String recall = recall(f, "HOLD-1", "HELD-1").getString("id");
    assertThat(materialStatus(f.batch()), is("RECALLED"));

    String child = splitOk(f, "8", null).getString("resultBatchId");

    assertThat(materialStatus(child), is("RECALLED"));
    assertThat(statusReason(child), is("Recall HOLD-1"));
    assertThat(
        "the recall's own record: held as it arrived, to be put back as AVAILABLE, not RECALLED",
        hold(recall, child),
        is("IN_SCOPE|AVAILABLE|8.000|ARRIVAL"));
    assertThat(hold(recall, f.batch()), is("IN_SCOPE|AVAILABLE|20.000|OPEN"));

    assertThat(cancelRecall(f, recall).getStatus(), is(200));
    assertThat(materialStatus(f.batch()), is("AVAILABLE"));
    assertThat(materialStatus(child), is("AVAILABLE"));
    assertThat(statusReason(child), is("none"));
    assertThat(onHand(f), comparesEqualTo(d("20")));
  }

  @Test
  @DisplayName("A child held by two recalls is freed only when both let it go")
  void aChildHeldByTwoRecallsIsFreedWhenBothLetGo() {
    Lot f = lot(20, "HELD-2", "1.0000", "2098-01-01");
    String byLot = recall(f, "HOLD-2A", "HELD-2").getString("id");
    String byVariant = recall(f, "HOLD-2B", null).getString("id");

    String child = splitOk(f, "5", null).getString("resultBatchId");

    assertThat(hold(byLot, child), is("IN_SCOPE|AVAILABLE|5.000|ARRIVAL"));
    assertThat(hold(byVariant, child), is("IN_SCOPE|AVAILABLE|5.000|ARRIVAL"));

    assertThat(cancelRecall(f, byLot).getStatus(), is(200));
    assertThat(materialStatus(f.batch()), is("RECALLED"));
    assertThat("the other recall still holds the child", materialStatus(child), is("RECALLED"));

    assertThat(cancelRecall(f, byVariant).getStatus(), is(200));
    assertThat(materialStatus(f.batch()), is("AVAILABLE"));
    assertThat(materialStatus(child), is("AVAILABLE"));
  }

  @Test
  @DisplayName("A recall closes over the child too: its stock is written off with its source's")
  void aRecallClosesOverTheChildToo() {
    Lot f = lot(20, "HELD-3", "1.0000", "2098-01-01");
    String recall = recall(f, "HOLD-3", "HELD-3").getString("id");
    String child = splitOk(f, "8", null).getString("resultBatchId");

    assertThat(destroyAtStore(f, recall, "20").getStatus(), is(201));

    assertThat(remaining(f.batch()), comparesEqualTo(d("0")));
    assertThat(
        "the child was held, so it was destroyed with the rest",
        remaining(child),
        comparesEqualTo(d("0")));
    Response closed = closeRecall(f, recall);
    assertThat(closed.readEntity(String.class), closed.getStatus(), is(200));
  }

  @Test
  @DisplayName("A held batch whose lot is unknown is split into a child that can be released")
  void aChildOfAnUnknownLotCanBeReleasedLikeItsSource() {
    Lot f = lot(10, null, "1.0000", "2098-01-01");
    String recall = recall(f, "HOLD-4", "SOME-LOT").getString("id");
    assertThat(hold(recall, f.batch()), is("LOT_UNKNOWN|AVAILABLE|10.000|OPEN"));

    String child = splitOk(f, "4", null).getString("resultBatchId");

    assertThat(hold(recall, child), is("LOT_UNKNOWN|AVAILABLE|4.000|ARRIVAL"));
    Response released = releaseBatch(f, recall, child);
    assertThat(released.readEntity(String.class), released.getStatus(), is(200));
    assertThat(materialStatus(child), is("AVAILABLE"));
    assertThat(
        "the source is still held until it is checked", materialStatus(f.batch()), is("RECALLED"));
    assertThat(releaseBatch(f, recall, f.batch()).getStatus(), is(200));
    assertThat(materialStatus(f.batch()), is("AVAILABLE"));
  }

  @Test
  @DisplayName("A child of stock a recall checked and released is not held by that recall again")
  void aChildOfReleasedStockIsNotHeldAgain() {
    Lot f = lot(10, null, "1.0000", "2098-01-01");
    String recall = recall(f, "HOLD-5", "SOME-LOT").getString("id");
    assertThat(releaseBatch(f, recall, f.batch()).getStatus(), is(200));
    assertThat(materialStatus(f.batch()), is("AVAILABLE"));

    String child = splitOk(f, "4", null).getString("resultBatchId");

    assertThat(materialStatus(child), is("AVAILABLE"));
    assertThat(hold(recall, child), nullValue());
    assertThat(onHand(f), comparesEqualTo(d("10")));
  }

  @Test
  @DisplayName("Stock marked RECALLED by hand, with no recall behind it, splits into held stock")
  void handMarkedRecalledStockSplitsIntoHeldStock() {
    Lot f = lot(10, "HAND", "1.0000", "2098-01-01");
    assertThat(
        put(
                "/admin/inventory/batches/" + f.batch() + "/material-status",
                "{\"materialStatus\":\"RECALLED\",\"reason\":\"Supplier called\"}",
                f.tenant(),
                "OWNER")
            .getStatus(),
        is(200));

    String child = splitOk(f, "3", null).getString("resultBatchId");

    assertThat(materialStatus(child), is("RECALLED"));
    assertThat(statusReason(child), is("Supplier called"));
    assertThat(
        sql("SELECT count(*) FROM inventory.recall_batches WHERE tenant_id = '" + f.tenant() + "'"),
        is("0"));
  }

  // ── the lot a split child is, to a recall ──────────────────────────────────

  @Test
  @DisplayName("The child carries its source's lot number unless a person names another")
  void theChildCarriesItsSourcesLot() {
    Lot f = lot(20, "LOT-X", "1.0000", "2098-01-01");
    String child = splitOk(f, "8", null).getString("resultBatchId");
    assertThat(batchNo(child), is("LOT-X"));
    assertThat(batchNo(splitOk(f, "2", "REPACK-1").getString("resultBatchId")), is("REPACK-1"));
  }

  @Test
  @DisplayName("A recall of the source's lot, opened after a split, holds the source and the child")
  void aRecallOfTheSourcesLotHoldsTheChild() {
    Lot f = lot(20, "LOT-A", "1.0000", "2098-01-01");
    String child = splitOk(f, "8", null).getString("resultBatchId");

    JsonObject recall = recall(f, "AFTER-1", "LOT-A");

    Map<String, String> held = heldBatches(recall);
    assertThat(held.keySet(), containsInAnyOrder(f.batch(), child));
    assertThat(held.get(f.batch()), is("IN_SCOPE"));
    assertThat(held.get(child), is("IN_SCOPE"));
    assertThat(materialStatus(child), is("RECALLED"));
    assertThat("held, not gone", held(f), comparesEqualTo(d("20")));
  }

  @Test
  @DisplayName("A child given a lot number of its own is still reached by a recall of the source's")
  void aRelabelledChildIsStillReachedByTheSourcesLot() {
    Lot f = lot(20, "LOT-B", "1.0000", "2098-01-01");
    String child = splitOk(f, "8", "REPACK-B").getString("resultBatchId");
    String grandchild =
        Envelopes.ok(
                post(
                    "/admin/inventory/lots/split",
                    "{\"sourceBatchId\":\"" + child + "\",\"qty\":3,\"batchNo\":\"REPACK-B2\"}",
                    f.tenant(),
                    f.staff(),
                    "OWNER"))
            .getString("resultBatchId");

    Map<String, String> bySource = heldBatches(recall(f, "AFTER-2A", "LOT-B"));
    assertThat(
        "the source, its child and the child's child",
        bySource.keySet(),
        containsInAnyOrder(f.batch(), child, grandchild));
    assertThat(bySource.get(grandchild), is("IN_SCOPE"));

    Lot g = lot(20, "LOT-C", "1.0000", "2098-01-01");
    String gChild = splitOk(g, "8", "REPACK-C").getString("resultBatchId");
    Map<String, String> byNewNumber = heldBatches(recall(g, "AFTER-2B", "REPACK-C"));
    assertThat(
        "the new number names only the child", byNewNumber.keySet(), containsInAnyOrder(gChild));

    Lot h = lot(20, "LOT-D", "1.0000", "2098-01-01");
    splitOk(h, "8", "REPACK-D");
    assertThat(
        "a lot that is neither's holds neither",
        heldBatches(recall(h, "AFTER-2C", "SOMEONE-ELSES")).size(),
        is(0));
  }

  @Test
  @DisplayName("A recall finds the buyers of the child as well as of the source")
  void aRecallFindsTheBuyersOfTheChild() {
    Lot f = lot(20, "LOT-E", "1.0000", "2098-01-01");
    splitOk(f, "8", "REPACK-E");
    // Drawn oldest first: all 12 of the source, then 2 of the child.
    String order = sell(f, "14");

    JsonObject recall = recall(f, "AFTER-3", "LOT-E");

    assertThat(recall.getInt("ordersAffected"), is(1));
    assertThat(recall.getJsonNumber("qtySold").bigDecimalValue(), comparesEqualTo(d("14")));
    assertThat(
        sql(
            "SELECT count(*) FROM inventory.recall_sales WHERE recall_id = '"
                + recall.getString("id")
                + "' AND order_id = '"
                + order
                + "'"),
        is("2"));
  }

  @Test
  @DisplayName("A child of stock with no lot is held as possibly affected, as its source is")
  void aChildOfStockWithNoLotIsHeldAsPossiblyAffected() {
    Lot f = lot(10, null, "1.0000", "2098-01-01");
    String child = splitOk(f, "4", null).getString("resultBatchId");

    Map<String, String> held = heldBatches(recall(f, "AFTER-4", "ANY-LOT"));

    assertThat(held.get(f.batch()), is("LOT_UNKNOWN"));
    assertThat(held.get(child), is("LOT_UNKNOWN"));
  }

  // ── stock still awaiting putaway ───────────────────────────────────────────

  private static String openTasks(Lot f) {
    return sql(
        "SELECT coalesce(string_agg(qty::text, ',' ORDER BY qty), 'none')"
            + " FROM inventory.putaway_tasks WHERE tenant_id = '"
            + f.tenant()
            + "' AND store_id = '"
            + f.store()
            + "' AND status = 'OPEN'");
  }

  @Test
  @DisplayName(
      "A split of stock awaiting putaway leaves the waiting list showing the stock there is")
  void aSplitOfStockAwaitingPutawayKeepsTheWaitingListTrue() {
    Lot f = lot(20, "WAIT", "1.0000", "2098-01-01");
    assertThat(openTasks(f), is("20.000"));

    String child = splitOk(f, "8", null).getString("resultBatchId");

    assertThat("12 to place at the source, 8 at the child", openTasks(f), is("8.000,12.000"));
    JsonArray listed =
        Envelopes.okArray(
            as(
                    "/admin/inventory/putaway/tasks?storeId=" + f.store(),
                    f.tenant(),
                    f.staff(),
                    "OWNER")
                .get());
    BigDecimal waiting = BigDecimal.ZERO;
    for (JsonObject t : listed.getValuesAs(JsonObject.class)) {
      waiting = waiting.add(t.getJsonNumber("qty").bigDecimalValue());
    }
    assertThat(
        "what the list says waits is what the store holds", waiting, comparesEqualTo(held(f)));
    assertThat(
        Envelopes.find(listed, "batchId", child).getJsonNumber("qty").bigDecimalValue(),
        comparesEqualTo(d("8")));
  }

  @Test
  @DisplayName("Splitting the whole of a batch awaiting putaway moves its task to the child")
  void splittingTheWholeBatchMovesItsPutawayTask() {
    Lot f = lot(6, "WAIT-ALL", "1.0000", "2098-01-01");

    String child = splitOk(f, "6", null).getString("resultBatchId");

    assertThat(openTasks(f), is("6.000"));
    assertThat(
        sql(
            "SELECT batch_id FROM inventory.putaway_tasks WHERE tenant_id = '"
                + f.tenant()
                + "' AND status = 'OPEN'"),
        is(child));
  }

  @Test
  @DisplayName("A split of stock with no zone and no task waiting makes no task for its child")
  void aSplitOfUnzonedStockWithNoTaskMakesNone() {
    Lot f = lot(10, "NO-TASK", "1.0000", "2098-01-01");
    Envelopes.exec(
        PG, "DELETE FROM inventory.putaway_tasks WHERE tenant_id = '" + f.tenant() + "'");
    assertThat(openTasks(f), is("none"));

    splitOk(f, "4", null);

    assertThat(openTasks(f), is("none"));
  }

  @Test
  @DisplayName("A split of stock that has a zone makes no putaway task")
  void aSplitOfZonedStockMakesNoPutawayTask() {
    String zone = Ids.newId().toString();
    Lot f =
        lotOf(
            Ids.newId().toString(),
            Ids.newId().toString(),
            10,
            "ZONED",
            "1.0000",
            "2098-01-01",
            ",\"zoneId\":\"" + zone + "\"");
    assertThat(openTasks(f), is("none"));

    String child = splitOk(f, "4", null).getString("resultBatchId");

    assertThat(openTasks(f), is("none"));
    assertThat(
        sql("SELECT zone_id FROM inventory.inventory_batches WHERE id = '" + child + "'"),
        is(zone));
  }

  // ── a caller held to stores ────────────────────────────────────────────────

  private Response heldTo(
      String method, String path, String json, String tenant, String role, String stores) {
    Invocation.Builder b =
        as(path, tenant, Ids.newId().toString(), role).header("X-Store-Ids", stores);
    return "PUT".equals(method)
        ? b.put(Entity.entity(json, MediaType.APPLICATION_JSON))
        : b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static void refusedForStore(Response r, String what) {
    String body = r.readEntity(String.class);
    assertThat(what + ": " + body, r.getStatus(), is(403));
    assertThat(what, body, containsString("STORE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName("A caller held to other stores cannot split or grade a batch at a store not theirs")
  void aCallerHeldToOtherStoresCannotSplitOrGrade() {
    Lot f = lot(10, "SCOPED", "1.0000", "2098-01-01");
    String elsewhere = Ids.newId().toString();
    String split = "{\"sourceBatchId\":\"" + f.batch() + "\",\"qty\":2}";
    String grade = "{\"grade\":\"B\"}";
    String gradePath = "/admin/inventory/batches/" + f.batch() + "/grade";
    String before = footprint(f);

    for (String role : List.of("MANAGER", "STOREKEEPER")) {
      refusedForStore(
          heldTo("POST", "/admin/inventory/lots/split", split, f.tenant(), role, elsewhere),
          role + " split");
      refusedForStore(
          heldTo("PUT", gradePath, grade, f.tenant(), role, elsewhere), role + " grade");
    }
    assertThat("nothing moved", footprint(f), is(before));
    assertThat(
        sql("SELECT grade FROM inventory.inventory_batches WHERE id = '" + f.batch() + "'"),
        is((String) null));

    // A batch no one has is not found, whatever stores the caller keeps: 404 before 403.
    Response nobody =
        heldTo(
            "POST",
            "/admin/inventory/lots/split",
            "{\"sourceBatchId\":\"" + Ids.newId() + "\",\"qty\":2}",
            f.tenant(),
            "MANAGER",
            elsewhere);
    assertThat(nobody.readEntity(String.class), nobody.getStatus(), is(404));
    assertThat(
        heldTo(
                "PUT",
                "/admin/inventory/batches/" + Ids.newId() + "/grade",
                grade,
                f.tenant(),
                "MANAGER",
                elsewhere)
            .getStatus(),
        is(404));

    // Held to this batch's store, the same calls go through.
    assertThat(
        heldTo("POST", "/admin/inventory/lots/split", split, f.tenant(), "STOREKEEPER", f.store())
            .getStatus(),
        is(200));
    assertThat(
        heldTo("PUT", gradePath, grade, f.tenant(), "STOREKEEPER", f.store()).getStatus(), is(200));
  }

  @Test
  @DisplayName("Another business's store-held staff, naming our store and batch, find nothing")
  void anotherBusinessesStoreHeldStaffFindNothing() {
    Lot f = lot(10, "STRANGERS", "1.0000", "2098-01-01");
    String before = footprint(f);
    String stranger = Ids.newId().toString();
    String gradePath = "/admin/inventory/batches/" + f.batch() + "/grade";

    for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER")) {
      Response split =
          heldTo(
              "POST",
              "/admin/inventory/lots/split",
              "{\"sourceBatchId\":\"" + f.batch() + "\",\"qty\":2}",
              stranger,
              role,
              f.store());
      assertThat(role + " split", split.getStatus(), is(404));
      Response grade = heldTo("PUT", gradePath, "{\"grade\":\"C\"}", stranger, role, f.store());
      assertThat(role + " grade", grade.getStatus(), is(404));
    }
    assertThat(footprint(f), is(before));
  }

  @Test
  @DisplayName("Another business, of any role, cannot grade our batch")
  void anotherBusinessCannotGradeOurBatch() {
    Lot f = lot(10, "GRADED", "1.0000", "2098-01-01");
    String before = footprint(f);
    String other = Ids.newId().toString();

    for (String role :
        List.of("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
      Response r =
          as(
                  "/admin/inventory/batches/" + f.batch() + "/grade",
                  other,
                  Ids.newId().toString(),
                  role)
              .put(Entity.entity("{\"grade\":\"C\"}", MediaType.APPLICATION_JSON));
      String body = r.readEntity(String.class);
      assertThat(role + ": " + body, r.getStatus(), is(oneOf(403, 404)));
    }

    assertThat(footprint(f), is(before));
    assertThat(
        sql("SELECT grade FROM inventory.inventory_batches WHERE id = '" + f.batch() + "'"),
        is((String) null));
  }

  @Test
  @DisplayName("A recall another business opens naming our variant and lot holds none of our stock")
  void anotherBusinessesRecallDoesNotHoldOurSplitStock() {
    Lot f = lot(20, "OUR-LOT", "1.0000", "2098-01-01");
    String child = splitOk(f, "8", "OUR-REPACK").getString("resultBatchId");
    String stranger = Ids.newId().toString();
    TENANTS.with(stranger, "GBP", "GB");

    JsonObject theirs =
        Envelopes.created(
            post(
                "/admin/recalls",
                "{\"reference\":\"THEIRS-1\",\"kind\":\"WITHDRAWAL\",\"hazard\":\"ALLERGEN\","
                    + "\"reason\":\"Undeclared peanut\",\"source\":\"FSA\",\"items\":[{\"variantId\":\""
                    + f.variant()
                    + "\",\"batchNo\":\"OUR-LOT\"}]}",
                stranger,
                Ids.newId().toString(),
                "OWNER"));

    assertThat(theirs.getJsonArray("batches").size(), is(0));
    assertThat(materialStatus(f.batch()), is("AVAILABLE"));
    assertThat(materialStatus(child), is("AVAILABLE"));
    assertThat(
        sql(
            "SELECT count(*) FROM inventory.recall_batches WHERE batch_id IN ('"
                + f.batch()
                + "', '"
                + child
                + "')"),
        is("0"));
  }

  // ── a retried split ────────────────────────────────────────────────────────

  private Response keyedSplit(Lot f, String qty, String key) {
    Invocation.Builder b =
        as("/admin/inventory/lots/split", f.tenant(), f.staff(), "OWNER")
            .header("Idempotency-Key", key);
    return b.post(
        Entity.entity(
            "{\"sourceBatchId\":\"" + f.batch() + "\",\"qty\":" + qty + "}",
            MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName(
      "A split retried under its Idempotency-Key answers the first split and moves nothing")
  void aRetriedSplitDoesNotSplitTwice() {
    Lot f = lot(10, "RETRY", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();

    JsonObject first = Envelopes.ok(keyedSplit(f, "2", key));
    String afterFirst = footprint(f);
    JsonObject again = Envelopes.ok(keyedSplit(f, "2", key));

    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat(again.getString("resultBatchId"), is(first.getString("resultBatchId")));
    assertThat("a retry changes nothing", footprint(f), is(afterFirst));
    assertThat(remaining(f.batch()), comparesEqualTo(d("8")));
    assertThat(batches(f), is(2L));
    assertThat(events(f, "LotSplit"), is(1L));

    // A different key is a different attempt, so it splits again.
    JsonObject next = Envelopes.ok(keyedSplit(f, "2", Ids.newId().toString()));
    assertThat(next.getString("id"), not(first.getString("id")));
    assertThat(remaining(f.batch()), comparesEqualTo(d("6")));
    assertThat(batches(f), is(3L));

    // Without a key every request is an attempt of its own.
    splitOk(f, "1", null);
    splitOk(f, "1", null);
    assertThat(remaining(f.batch()), comparesEqualTo(d("4")));
  }

  @Test
  @DisplayName("A retry still answers the first split after the source has been split dry")
  void aRetryAnswersEvenOnceTheSourceIsEmpty() {
    Lot f = lot(6, "DRY", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();

    JsonObject first = Envelopes.ok(keyedSplit(f, "6", key));
    JsonObject again = Envelopes.ok(keyedSplit(f, "6", key));

    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat(remaining(f.batch()), comparesEqualTo(d("0")));
    assertThat(batches(f), is(2L));
  }

  @Test
  @DisplayName("A key used for a different split is refused, and nothing moves")
  void aKeyUsedForAnotherSplitIsRefused() {
    Lot f = lot(10, "REUSED", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();
    Envelopes.ok(keyedSplit(f, "2", key));
    String before = footprint(f);

    Response other = keyedSplit(f, "3", key);
    String body = other.readEntity(String.class);
    assertThat(body, other.getStatus(), is(409));
    assertThat(body, containsString("IDEMPOTENCY_KEY_REUSED"));

    assertThat(footprint(f), is(before));
  }

  @Test
  @DisplayName("A malformed Idempotency-Key is refused before anything is read")
  void aMalformedKeyIsRefused() {
    Lot f = lot(10, "BADKEY", "1.0000", "2098-01-01");
    String before = footprint(f);

    Response r = keyedSplit(f, "2", "not-a-uuid");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(400));
    assertThat(body, containsString("IDEMPOTENCY_KEY_INVALID"));

    assertThat(footprint(f), is(before));
  }

  @Test
  @DisplayName("The same key in two businesses is two attempts: each splits its own batch")
  void theSameKeyInTwoBusinessesIsTwoAttempts() {
    Lot mine = lot(10, "MINE", "1.0000", "2098-01-01");
    Lot theirs = lot(10, "THEIRS", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();

    JsonObject a = Envelopes.ok(keyedSplit(mine, "2", key));
    JsonObject b = Envelopes.ok(keyedSplit(theirs, "2", key));

    assertThat(a.getString("id"), not(b.getString("id")));
    assertThat(remaining(mine.batch()), comparesEqualTo(d("8")));
    assertThat(remaining(theirs.batch()), comparesEqualTo(d("8")));
  }

  @Test
  @DisplayName("The same split sent twice at once under one key is made once")
  void theSameKeyAtOnceSplitsOnce() throws Exception {
    Lot f = lot(10, "AT-ONCE", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();

    List<String> ids =
        Concurrency.inParallel(
            4,
            () -> {
              Response r = keyedSplit(f, "2", key);
              String body = r.readEntity(String.class);
              assertThat(body, r.getStatus(), is(200));
              return Envelopes.parse(body).getJsonObject("data").getString("id");
            });

    assertThat(ids.stream().distinct().count(), is(1L));
    assertThat(remaining(f.batch()), comparesEqualTo(d("8")));
    assertThat(batches(f), is(2L));
    assertThat(movements(f, "LOT_SPLIT"), is(2L));
  }
}
