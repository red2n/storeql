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
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A lot merge moves stock between two named batches, it does not make any or lose any. The quantity
 * leaves the source batch and arrives in the target batch, on one transaction with the movements
 * that say so, the genealogy link, the lot action and the {@code LotMerge} event. What the business
 * holds of the variant is the same after as before, and the ledger nets to zero without reading as
 * a write-off, a find, a receipt or a sale. A recall of either batch's lot reaches the merged
 * batch. Real Postgres; Kafka and Consul disabled; tenant-svc is a stub.
 */
@HelidonTest
class LotMergeIT {

  private static final PostgresSupport PG;
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

  /** A business, a person in it, and the store and variant its batches are of. */
  private record Stock(String tenant, String staff, String store, String variant) {}

  /** A new business trading in the currency, with a store and a variant of its own. */
  private Stock stockIn(String currency, String country) {
    String tenant = Ids.newId().toString();
    TENANTS.with(tenant, currency, country);
    return new Stock(
        tenant, Ids.newId().toString(), Ids.newId().toString(), Ids.newId().toString());
  }

  private Stock stock() {
    return stockIn("GBP", "GB");
  }

  /** The same business and variant, at a store of its own. */
  private static Stock elsewhere(Stock s) {
    return new Stock(s.tenant(), s.staff(), Ids.newId().toString(), s.variant());
  }

  /** The same business and store, of another variant. */
  private static Stock otherVariant(Stock s) {
    return new Stock(s.tenant(), s.staff(), s.store(), Ids.newId().toString());
  }

  private Invocation.Builder as(String path, String tenant, String user, String role) {
    Invocation.Builder b =
        WebTargets.at(target, path).request().header("X-Tenant-Id", tenant).header("X-Roles", role);
    return user == null ? b : b.header("X-User-Id", user);
  }

  private Response post(String path, String json, String tenant, String user, String role) {
    return as(path, tenant, user, role).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json, Stock s) {
    return as(path, s.tenant(), s.staff(), "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Receives a batch; a null lot, cost or expiry is left off, and {@code more} is added as is. */
  private String receive(Stock s, int qty, String lot, String cost, String expiry, String more) {
    JsonObject received =
        Envelopes.created(
            post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + s.store()
                    + "\",\"variantId\":\""
                    + s.variant()
                    + "\",\"qty\":"
                    + qty
                    + (lot == null ? "" : ",\"batchNo\":\"" + lot + "\"")
                    + (cost == null ? "" : ",\"costPrice\":" + cost)
                    + (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
                    + more
                    + "}",
                s.tenant(),
                s.staff(),
                "OWNER"));
    return received.getString("id");
  }

  private String receive(Stock s, int qty, String lot, String cost, String expiry) {
    return receive(s, qty, lot, cost, expiry, "");
  }

  private static String mergeJson(String source, String target, String qty) {
    return "{\"sourceBatchId\":\""
        + source
        + "\",\"targetBatchId\":\""
        + target
        + "\",\"qty\":"
        + qty
        + "}";
  }

  private Response mergeAs(
      String tenant, String user, String role, String source, String target, String qty) {
    return post("/admin/inventory/lots/merge", mergeJson(source, target, qty), tenant, user, role);
  }

  private Response merge(Stock s, String source, String target, String qty) {
    return mergeAs(s.tenant(), s.staff(), "OWNER", source, target, qty);
  }

  private JsonObject mergeOk(Stock s, String source, String target, String qty) {
    return Envelopes.ok(merge(s, source, target, qty));
  }

  private Response keyedMerge(Stock s, String source, String target, String qty, String key) {
    return as("/admin/inventory/lots/merge", s.tenant(), s.staff(), "OWNER")
        .header("Idempotency-Key", key)
        .post(Entity.entity(mergeJson(source, target, qty), MediaType.APPLICATION_JSON));
  }

  /** The refusal's code, after checking its status. */
  private static String refusedWith(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getJsonObject("error").getString("code");
  }

  private static String sql(String query) {
    return Envelopes.scalar(PG, query);
  }

  private static BigDecimal number(String query) {
    return new BigDecimal(sql(query));
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static BigDecimal remaining(String batch) {
    return number(
        "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  private static BigDecimal received(String batch) {
    return number(
        "SELECT received_qty FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  private static BigDecimal cost(String batch) {
    String c = sql("SELECT cost_price FROM inventory.inventory_batches WHERE id = '" + batch + "'");
    return c == null ? null : new BigDecimal(c);
  }

  private static String expiry(String batch) {
    return sql("SELECT expiry_date FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  private static String materialStatus(String batch) {
    return sql(
        "SELECT material_status FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  /** What the business holds of the variant: the sum over its batches. */
  private static BigDecimal held(Stock s) {
    return number(
        "SELECT coalesce(sum(remaining_qty), 0) FROM inventory.inventory_batches WHERE tenant_id ="
            + " '"
            + s.tenant()
            + "' AND variant_id = '"
            + s.variant()
            + "'");
  }

  /** What the variant's batches are worth at their own cost, as the valuation sums it. */
  private static BigDecimal worth(Stock s) {
    return number(
        "SELECT coalesce(sum(remaining_qty * cost_price), 0) FROM inventory.inventory_batches"
            + " WHERE tenant_id = '"
            + s.tenant()
            + "' AND variant_id = '"
            + s.variant()
            + "'");
  }

  private static long count(String table, Stock s) {
    return Long.parseLong(
        sql("SELECT count(*) FROM inventory." + table + " WHERE tenant_id = '" + s.tenant() + "'"));
  }

  private static long movements(Stock s, String type) {
    return Long.parseLong(
        sql(
            "SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '"
                + s.tenant()
                + "' AND type = '"
                + type
                + "'"));
  }

  private static long events(Stock s, String eventType) {
    return Long.parseLong(
        sql(
            "SELECT count(*) FROM inventory.outbox WHERE tenant_id = '"
                + s.tenant()
                + "' AND event_type = '"
                + eventType
                + "'"));
  }

  /** Everything a merge writes, as one string: it must read the same before and after a refusal. */
  private static String footprint(Stock s, String... batches) {
    StringBuilder out = new StringBuilder();
    out.append(count("inventory_batches", s))
        .append('|')
        .append(count("stock_movements", s))
        .append('|')
        .append(count("lot_actions", s))
        .append('|')
        .append(count("lot_genealogy", s))
        .append('|')
        .append(count("outbox", s));
    for (String b : batches) {
      out.append('|')
          .append(remaining(b))
          .append('/')
          .append(received(b))
          .append('/')
          .append(cost(b))
          .append('/')
          .append(expiry(b));
    }
    return out.toString();
  }

  private BigDecimal onHand(Stock s) {
    JsonArray levels =
        Envelopes.okArray(
            as("/admin/inventory/levels?store=" + s.store(), s.tenant(), s.staff(), "OWNER").get());
    return Envelopes.find(levels, "variantId", s.variant())
        .getJsonNumber("onHand")
        .bigDecimalValue();
  }

  // ── the quantity moves ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A merge takes the quantity out of the source and puts it into the target")
  void aMergeMovesTheQuantityFromTheSourceToTheTarget() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "2.5000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "2.5000", "2098-01-01");
    assertThat(onHand(s), comparesEqualTo(d("15")));
    assertThat(worth(s), comparesEqualTo(d("37.5")));

    JsonObject action = mergeOk(s, source, target, "4");

    assertThat(action.getString("actionType"), is("MERGE"));
    assertThat(action.getString("sourceBatchId"), is(source));
    assertThat(action.getString("resultBatchId"), is(target));
    assertThat(remaining(source), comparesEqualTo(d("6")));
    assertThat(remaining(target), comparesEqualTo(d("9")));
    assertThat("the source received what it did", received(source), comparesEqualTo(d("10")));
    assertThat(
        "what the target holds stays within what it has received",
        received(target),
        comparesEqualTo(d("9")));
    assertThat("what the business holds is unchanged", held(s), comparesEqualTo(d("15")));
    assertThat(onHand(s), comparesEqualTo(d("15")));
    assertThat("so is what it is worth", worth(s), comparesEqualTo(d("37.5")));
    assertThat("no batch is made for the merge", count("inventory_batches", s), is(2L));
    assertThat("two equal costs are left as they were", cost(target), comparesEqualTo(d("2.5")));
    assertThat(
        sql("SELECT cost_price FROM inventory.inventory_batches WHERE id = '" + target + "'"),
        is("2.5000"));
  }

  @Test
  @DisplayName("Merging the whole of a batch leaves the source empty and the target holding it")
  void mergingTheWholeBatchLeavesTheSourceEmpty() {
    Stock s = stock();
    String source = receive(s, 6, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 2, "LOT-B", "1.0000", "2098-01-01");

    mergeOk(s, source, target, "6");

    assertThat(remaining(source), comparesEqualTo(d("0")));
    assertThat(remaining(target), comparesEqualTo(d("8")));
    assertThat(held(s), comparesEqualTo(d("8")));
  }

  @Test
  @DisplayName("The ledger nets to zero: out of the source, into the target, not an adjustment")
  void theLedgerNetsToZero() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");

    JsonObject action = mergeOk(s, source, target, "4");

    assertThat("only the two receipts are receipts", movements(s, "RECEIVE"), is(2L));
    assertThat(movements(s, "ADJUST"), is(0L));
    assertThat(movements(s, "SALE"), is(0L));
    assertThat(movements(s, "LOT_MERGE"), is(2L));
    assertThat(
        number(
            "SELECT sum(qty) FROM inventory.stock_movements WHERE tenant_id = '"
                + s.tenant()
                + "' AND type = 'LOT_MERGE'"),
        comparesEqualTo(d("0")));
    assertThat(
        "out of the source",
        number(
            "SELECT qty FROM inventory.stock_movements WHERE batch_id = '"
                + source
                + "' AND type = 'LOT_MERGE'"),
        comparesEqualTo(d("-4")));
    assertThat(
        "into the target",
        number(
            "SELECT qty FROM inventory.stock_movements WHERE batch_id = '"
                + target
                + "' AND type = 'LOT_MERGE'"),
        comparesEqualTo(d("4")));
    assertThat(
        "both legs cite the lot action and name the person who merged",
        sql(
            "SELECT string_agg(DISTINCT ref_type || '|' || ref_id || '|' || actor_id || '|' ||"
                + " store_id || '|' || variant_id, ',') FROM inventory.stock_movements"
                + " WHERE tenant_id = '"
                + s.tenant()
                + "' AND type = 'LOT_MERGE'"),
        is(
            "LOT_MERGE|"
                + action.getString("id")
                + "|"
                + s.staff()
                + "|"
                + s.store()
                + "|"
                + s.variant()));

    for (String batch : List.of(source, target)) {
      assertThat(
          batch,
          number(
              "SELECT sum(qty) FROM inventory.stock_movements WHERE tenant_id = '"
                  + s.tenant()
                  + "' AND batch_id = '"
                  + batch
                  + "'"),
          comparesEqualTo(remaining(batch)));
    }
    assertThat(
        number(
            "SELECT sum(qty) FROM inventory.stock_movements WHERE tenant_id = '"
                + s.tenant()
                + "' AND variant_id = '"
                + s.variant()
                + "'"),
        comparesEqualTo(held(s)));

    String base = "/admin/inventory/movements?variant=" + s.variant();
    assertThat(
        Envelopes.okArray(as(base + "&type=ADJUST", s.tenant(), s.staff(), "OWNER").get()).size(),
        is(0));
    assertThat(
        Envelopes.okArray(as(base + "&type=LOT_MERGE", s.tenant(), s.staff(), "OWNER").get())
            .size(),
        is(2));
  }

  @Test
  @DisplayName("A merge is neither a write-off nor a find in the shrinkage report")
  void aMergeIsNotShrinkage() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String report = "/admin/inventory/reports/shrinkage?groupBy=REASON&storeId=" + s.store();

    mergeOk(s, source, target, "4");

    assertThat(
        "nothing written off or found",
        Envelopes.okArray(as(report, s.tenant(), s.staff(), "OWNER").get()).size(),
        is(0));

    // The same report does count a real write-off, so an empty answer above is not a blind one.
    assertThat(
        post(
                "/admin/inventory/adjust",
                "{\"storeId\":\""
                    + s.store()
                    + "\",\"variantId\":\""
                    + s.variant()
                    + "\",\"delta\":-1,\"reason\":\"Dropped\"}",
                s.tenant(),
                s.staff(),
                "OWNER")
            .getStatus(),
        is(200));
    JsonArray rows = Envelopes.okArray(as(report, s.tenant(), s.staff(), "OWNER").get());
    BigDecimal writtenOff = BigDecimal.ZERO;
    for (JsonObject row : rows.getValuesAs(JsonObject.class)) {
      writtenOff = writtenOff.add(row.getJsonNumber("qtyWrittenOff").bigDecimalValue());
    }
    assertThat(writtenOff, comparesEqualTo(d("1")));
  }

  @Test
  @DisplayName("A merge is announced once as a LotMerge, and creates no stock event")
  void aMergeIsAnnouncedOnce() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");

    mergeOk(s, source, target, "4");

    assertThat(events(s, "LotMerge"), is(1L));
    assertThat(
        sql(
            "SELECT aggregate_id || '|' || (payload::json ->> 'targetBatchId') || '|'"
                + " || (payload::json ->> 'qty') FROM inventory.outbox WHERE tenant_id = '"
                + s.tenant()
                + "' AND event_type = 'LotMerge'"),
        is(source + "|" + target + "|4"));
    assertThat("nothing was received", events(s, "StockReceived"), is(2L));
    assertThat("nothing was adjusted", events(s, "StockAdjusted"), is(0L));
    assertThat(events(s, "LotMergeIn"), is(0L));
  }

  @Test
  @DisplayName("The merge is linked in the genealogy and recorded against both batches")
  void theMergeIsLinkedAndRecorded() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");

    JsonObject action = mergeOk(s, source, target, "4");

    JsonArray down =
        Envelopes.ok(
                as(
                        "/admin/inventory/lot-genealogy/batch/" + source + "/descendants",
                        s.tenant(),
                        s.staff(),
                        "OWNER")
                    .get())
            .getJsonArray("descendants");
    assertThat(down.size(), is(1));
    assertThat(down.getJsonObject(0).getString("childBatchId"), is(target));
    assertThat(down.getJsonObject(0).getString("relationType"), is("MERGE"));
    assertThat(
        down.getJsonObject(0).getJsonNumber("qty").bigDecimalValue(), comparesEqualTo(d("4")));
    JsonArray up =
        Envelopes.ok(
                as(
                        "/admin/inventory/lot-genealogy/batch/" + target + "/ancestors",
                        s.tenant(),
                        s.staff(),
                        "OWNER")
                    .get())
            .getJsonArray("ancestors");
    assertThat(up.getJsonObject(0).getString("parentBatchId"), is(source));

    for (String batch : List.of(source, target)) {
      JsonArray actions =
          Envelopes.okArray(
              as("/admin/inventory/lots/" + batch + "/actions", s.tenant(), s.staff(), "OWNER")
                  .get());
      assertThat(batch, actions.size(), is(1));
      assertThat(actions.getJsonObject(0).getString("id"), is(action.getString("id")));
      assertThat(actions.getJsonObject(0).getString("actionType"), is("MERGE"));
    }
  }

  @Test
  @DisplayName("Two merges between the same batches add up on the one link they share")
  void twoMergesBetweenTheSameBatchesAddUpOnOneLink() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");

    mergeOk(s, source, target, "2");
    mergeOk(s, source, target, "3");

    assertThat(count("lot_genealogy", s), is(1L));
    assertThat(
        number(
            "SELECT qty FROM inventory.lot_genealogy WHERE tenant_id = '"
                + s.tenant()
                + "' AND parent_batch_id = '"
                + source
                + "' AND child_batch_id = '"
                + target
                + "'"),
        comparesEqualTo(d("5")));
    assertThat("each merge is its own action", count("lot_actions", s), is(2L));
    assertThat(remaining(target), comparesEqualTo(d("10")));
  }

  @Test
  @DisplayName("Stock merged back the way it came is a link each way, and a recall still ends")
  void stockMergedBackIsALinkEachWay() {
    Stock s = stock();
    String a = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String b = receive(s, 10, "LOT-B", "1.0000", "2098-01-01");

    mergeOk(s, a, b, "2");
    mergeOk(s, b, a, "1");

    assertThat(count("lot_genealogy", s), is(2L));
    Map<String, String> held = heldBatches(recall(s, "BOTH-WAYS", "LOT-A"));
    assertThat(held.keySet(), containsInAnyOrder(a, b));
    assertThat(heldBatches(recall(s, "BOTH-WAYS-B", "LOT-B")).keySet(), containsInAnyOrder(a, b));
  }

  // ── what a merge refuses ───────────────────────────────────────────────────

  @Test
  @DisplayName("A merge of more than the source holds is refused, and nothing moves")
  void aMergeOfMoreThanTheSourceHoldsIsRefused() {
    Stock s = stock();
    String source = receive(s, 5, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String before = footprint(s, source, target);

    assertThat(refusedWith(merge(s, source, target, "5.001"), 422), is("INSUFFICIENT_QTY"));

    assertThat(footprint(s, source, target), is(before));
  }

  @Test
  @DisplayName("Batches of another store or another variant are not merged, and nothing moves")
  void batchesOfAnotherStoreOrVariantAreNotMerged() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    Stock otherStore = elsewhere(s);
    String atOtherStore = receive(otherStore, 5, "LOT-B", "1.0000", "2098-01-01");
    Stock otherProduct = otherVariant(s);
    String ofOtherVariant = receive(otherProduct, 5, "LOT-C", "1.0000", "2098-01-01");
    String before = footprint(s, source, atOtherStore, ofOtherVariant);

    assertThat(
        refusedWith(merge(s, source, atOtherStore, "2"), 422),
        is("INVENTORY_LOT_MERGE_STORE_MISMATCH"));
    assertThat(
        refusedWith(merge(s, atOtherStore, source, "2"), 422),
        is("INVENTORY_LOT_MERGE_STORE_MISMATCH"));
    assertThat(
        refusedWith(merge(s, source, ofOtherVariant, "2"), 422),
        is("INVENTORY_LOT_MERGE_VARIANT_MISMATCH"));

    assertThat(footprint(s, source, atOtherStore, ofOtherVariant), is(before));
  }

  @Test
  @DisplayName("A batch cannot be merged into itself")
  void aBatchCannotBeMergedIntoItself() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String before = footprint(s, source);

    assertThat(
        refusedWith(merge(s, source, source, "2"), 422), is("INVENTORY_LOT_MERGE_SAME_BATCH"));

    assertThat(footprint(s, source), is(before));
  }

  @Test
  @DisplayName("A batch no one has is not found, as source or as target, and nothing is written")
  void aBatchNoOneHasIsNotFound() {
    Stock s = stock();
    String known = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String before = footprint(s, known);

    assertThat(
        refusedWith(merge(s, Ids.newId().toString(), known, "1"), 404), is("BATCH_NOT_FOUND"));
    assertThat(
        refusedWith(merge(s, known, Ids.newId().toString(), "1"), 404), is("BATCH_NOT_FOUND"));

    assertThat(footprint(s, known), is(before));
  }

  @Test
  @DisplayName("A quantity that is not above zero is refused")
  void aQuantityNotAboveZeroIsRefused() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String before = footprint(s, source, target);

    for (String qty : List.of("0", "-1")) {
      assertThat(qty, refusedWith(merge(s, source, target, qty), 400), is("VALIDATION_FAILED"));
    }

    assertThat(footprint(s, source, target), is(before));
  }

  @Test
  @DisplayName("Stock is not merged across ownership, duty, material status or grade")
  void stockIsNotMergedAcrossOwnershipDutyStatusOrGrade() {
    Stock s = stock();
    String owned = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String consigned =
        receive(
            s,
            5,
            "LOT-B",
            "1.0000",
            "2098-01-01",
            ",\"ownership\":\"CONSIGNMENT\",\"supplierId\":\"" + Ids.newId() + "\"");
    String quarantined = receive(s, 5, "LOT-C", "1.0000", "2098-01-01");
    assertThat(
        put(
                "/admin/inventory/batches/" + quarantined + "/material-status",
                "{\"materialStatus\":\"QUARANTINE\",\"reason\":\"Awaiting lab result\"}",
                s)
            .getStatus(),
        is(200));
    String inBond = receive(s, 5, "LOT-D", "1.0000", "2098-01-01");
    Envelopes.exec(
        PG,
        "UPDATE inventory.inventory_batches SET duty_status = 'DUTY_SUSPENDED' WHERE id = '"
            + inBond
            + "'");
    String rejected = receive(s, 5, "LOT-E", "1.0000", "2098-01-01");
    assertThat(
        put("/admin/inventory/batches/" + rejected + "/grade", "{\"grade\":\"REJECT\"}", s)
            .getStatus(),
        is(200));
    String before = footprint(s, owned, consigned, quarantined, inBond, rejected);

    for (String other : List.of(consigned, quarantined, inBond, rejected)) {
      assertThat(
          other + " into the owned, available, duty-paid batch",
          refusedWith(merge(s, other, owned, "1"), 422),
          is("INVENTORY_LOT_MERGE_CONDITION_MISMATCH"));
      assertThat(
          "and the other way",
          refusedWith(merge(s, owned, other, "1"), 422),
          is("INVENTORY_LOT_MERGE_CONDITION_MISMATCH"));
    }

    assertThat(footprint(s, owned, consigned, quarantined, inBond, rejected), is(before));
  }

  @Test
  @DisplayName("Consigned stock of one supplier is not merged into another supplier's")
  void consignedStockOfOneSupplierIsNotMergedIntoAnothers() {
    Stock s = stock();
    String one =
        receive(
            s,
            5,
            "LOT-A",
            "1.0000",
            "2098-01-01",
            ",\"ownership\":\"CONSIGNMENT\",\"supplierId\":\"" + Ids.newId() + "\"");
    String two =
        receive(
            s,
            5,
            "LOT-B",
            "1.0000",
            "2098-01-01",
            ",\"ownership\":\"CONSIGNMENT\",\"supplierId\":\"" + Ids.newId() + "\"");
    String before = footprint(s, one, two);

    assertThat(
        refusedWith(merge(s, one, two, "1"), 422), is("INVENTORY_LOT_MERGE_CONDITION_MISMATCH"));

    assertThat(footprint(s, one, two), is(before));
  }

  // ── what it costs ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("The target's unit cost becomes the average of the two, weighted by quantity")
  void theTargetsCostIsTheWeightedAverage() {
    Stock s = stock(); // pounds: two decimal places
    String source = receive(s, 4, "LOT-A", "3.0000", "2098-01-01");
    String target = receive(s, 6, "LOT-B", "2.0000", "2098-01-01");

    mergeOk(s, source, target, "4");

    // (6 x 2.00 + 4 x 3.00) / 10 = 2.40
    assertThat(cost(target), comparesEqualTo(d("2.40")));
    assertThat("the source keeps its own", cost(source), comparesEqualTo(d("3")));
  }

  @Test
  @DisplayName("The blended cost is rounded to the business currency's own minor units")
  void theBlendedCostIsRoundedToTheCurrencysMinorUnits() {
    // Pounds: (2 x 1.00 + 1 x 2.00) / 3 = 1.3333 -> 1.33
    Stock gbp = stockIn("GBP", "GB");
    String gs = receive(gbp, 1, "LOT-A", "2.0000", "2098-01-01");
    String gt = receive(gbp, 2, "LOT-B", "1.0000", "2098-01-01");
    mergeOk(gbp, gs, gt, "1");
    assertThat(
        sql("SELECT cost_price FROM inventory.inventory_batches WHERE id = '" + gt + "'"),
        is("1.3300"));

    // Yen have no minor unit: (2 x 10 + 3 x 11) / 5 = 10.6 -> 11
    Stock jpy = stockIn("JPY", "JP");
    String js = receive(jpy, 3, "LOT-A", "11.0000", "2098-01-01");
    String jt = receive(jpy, 2, "LOT-B", "10.0000", "2098-01-01");
    mergeOk(jpy, js, jt, "3");
    assertThat(
        sql("SELECT cost_price FROM inventory.inventory_batches WHERE id = '" + jt + "'"),
        is("11.0000"));

    // Kuwaiti dinars keep three places: (2 x 1.001 + 1 x 1.000) / 3 = 1.000667 -> 1.001
    Stock kwd = stockIn("KWD", "KW");
    String ks = receive(kwd, 1, "LOT-A", "1.0000", "2098-01-01");
    String kt = receive(kwd, 2, "LOT-B", "1.0010", "2098-01-01");
    mergeOk(kwd, ks, kt, "1");
    assertThat(
        sql("SELECT cost_price FROM inventory.inventory_batches WHERE id = '" + kt + "'"),
        is("1.0010"));
  }

  @Test
  @DisplayName("Equal costs need no currency and are left exactly as they are")
  void equalCostsNeedNoCurrency() {
    // A business tenant-svc has never heard of: its currency cannot be read.
    Stock s =
        new Stock(
            Ids.newId().toString(),
            Ids.newId().toString(),
            Ids.newId().toString(),
            Ids.newId().toString());
    String source = receive(s, 4, "LOT-A", "2.5000", "2098-01-01");
    String target = receive(s, 6, "LOT-B", "2.5000", "2098-01-01");

    mergeOk(s, source, target, "4");

    assertThat(
        sql("SELECT cost_price FROM inventory.inventory_batches WHERE id = '" + target + "'"),
        is("2.5000"));
  }

  @Test
  @DisplayName("Costs that differ are not blended in a currency nobody can read: refused, unmoved")
  void differingCostsAreRefusedWhenTheCurrencyCannotBeRead() {
    Stock s =
        new Stock(
            Ids.newId().toString(),
            Ids.newId().toString(),
            Ids.newId().toString(),
            Ids.newId().toString());
    String source = receive(s, 4, "LOT-A", "3.0000", "2098-01-01");
    String target = receive(s, 6, "LOT-B", "2.0000", "2098-01-01");
    String before = footprint(s, source, target);

    assertThat(refusedWith(merge(s, source, target, "4"), 503), is("TENANT_PROFILE_UNAVAILABLE"));

    assertThat(footprint(s, source, target), is(before));
  }

  @Test
  @DisplayName("Stock with a cost is not merged with stock that has none; two with none merge")
  void aCostAndNoCostAreNotMixed() {
    Stock s = stock();
    String costed = receive(s, 5, "LOT-A", "2.0000", "2098-01-01");
    String uncosted = receive(s, 5, "LOT-B", null, "2098-01-01");
    String alsoUncosted = receive(s, 5, "LOT-C", null, "2098-01-01");
    String before = footprint(s, costed, uncosted);

    assertThat(
        refusedWith(merge(s, costed, uncosted, "1"), 422), is("INVENTORY_LOT_MERGE_COST_UNKNOWN"));
    assertThat(
        refusedWith(merge(s, uncosted, costed, "1"), 422), is("INVENTORY_LOT_MERGE_COST_UNKNOWN"));
    assertThat(footprint(s, costed, uncosted), is(before));

    mergeOk(s, uncosted, alsoUncosted, "2");
    assertThat(remaining(alsoUncosted), comparesEqualTo(d("7")));
    assertThat(cost(alsoUncosted), nullValue());
  }

  @Test
  @DisplayName("The merged batch is sold by the earlier of the two use-by dates")
  void theMergedBatchTakesTheEarlierUseByDate() {
    Stock s = stock();
    String early = receive(s, 10, "LOT-A", "1.0000", "2097-06-01");
    String late = receive(s, 10, "LOT-B", "1.0000", "2098-06-01");
    String undated = receive(s, 10, "LOT-C", "1.0000", null);
    String neverDated = receive(s, 10, "LOT-D", "1.0000", null);
    String alsoNeverDated = receive(s, 10, "LOT-E", "1.0000", null);

    mergeOk(s, early, late, "1");
    assertThat("the target now holds stock that expires sooner", expiry(late), is("2097-06-01"));

    mergeOk(s, late, early, "1");
    assertThat("a later date does not move an earlier one", expiry(early), is("2097-06-01"));

    mergeOk(s, early, undated, "1");
    assertThat("a date beats none", expiry(undated), is("2097-06-01"));

    mergeOk(s, neverDated, alsoNeverDated, "1");
    assertThat("two with none stay without", expiry(alsoNeverDated), nullValue());
    mergeOk(s, alsoNeverDated, early, "1");
    assertThat("none does not lift a date", expiry(early), is("2097-06-01"));
  }

  // ── a recall reaches the merged batch ──────────────────────────────────────

  private JsonObject recall(Stock s, String reference, String lot) {
    return Envelopes.created(
        post(
            "/admin/recalls",
            "{\"reference\":\""
                + reference
                + "\",\"kind\":\"WITHDRAWAL\",\"hazard\":\"ALLERGEN\","
                + "\"reason\":\"Undeclared peanut\",\"source\":\"FSA\",\"items\":[{\"variantId\":\""
                + s.variant()
                + "\",\"batchNo\":\""
                + lot
                + "\"}]}",
            s.tenant(),
            s.staff(),
            "OWNER"));
  }

  private static Map<String, String> heldBatches(JsonObject recall) {
    Map<String, String> out = new HashMap<>();
    for (JsonObject b : recall.getJsonArray("batches").getValuesAs(JsonObject.class)) {
      out.put(b.getString("batchId"), b.getString("match"));
    }
    return out;
  }

  private Response cancelRecall(Stock s, String recall) {
    return post(
        "/admin/recalls/" + recall + "/cancel",
        "{\"reason\":\"Opened in error\"}",
        s.tenant(),
        s.staff(),
        "OWNER");
  }

  @Test
  @DisplayName("A recall of the source's lot, opened after a merge, holds the target")
  void aRecallOfTheSourcesLotAfterAMergeHoldsTheTarget() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    mergeOk(s, source, target, "4");

    Map<String, String> held = heldBatches(recall(s, "AFTER-A", "LOT-A"));

    assertThat(held.keySet(), containsInAnyOrder(source, target));
    assertThat(held.get(target), is("IN_SCOPE"));
    assertThat(materialStatus(target), is("RECALLED"));
  }

  @Test
  @DisplayName("A recall of the target's own lot holds the target but not the source")
  void aRecallOfTheTargetsLotDoesNotHoldTheSource() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    mergeOk(s, source, target, "4");

    Map<String, String> held = heldBatches(recall(s, "AFTER-B", "LOT-B"));

    assertThat(held.keySet(), containsInAnyOrder(target));
    assertThat(materialStatus(source), is("AVAILABLE"));
    assertThat(heldBatches(recall(s, "AFTER-C", "LOT-C")).size(), is(0));
  }

  @Test
  @DisplayName("Through two merges, a recall of the first lot still holds the last batch")
  void aRecallReachesThroughTwoMerges() {
    Stock s = stock();
    String first = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String second = receive(s, 10, "LOT-B", "1.0000", "2098-01-01");
    String third = receive(s, 10, "LOT-C", "1.0000", "2098-01-01");
    mergeOk(s, first, second, "3");
    mergeOk(s, second, third, "3");

    Map<String, String> held = heldBatches(recall(s, "AFTER-D", "LOT-A"));

    assertThat(held.keySet(), containsInAnyOrder(first, second, third));
  }

  @Test
  @DisplayName("A recall finds the buyers of a merged batch's stock under the merged-in lot")
  void aRecallFindsTheBuyersOfTheMergedBatch() {
    Stock s = stock();
    // Sold oldest use-by first: the target goes before the source.
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2097-01-01");
    mergeOk(s, source, target, "4");
    UUID order = Ids.newId();
    inventory.deductSaleFromOrderOnce(
        Ids.newId(),
        "it",
        Ids.parse(s.tenant()),
        Ids.parse(s.store()),
        Ids.parse(s.variant()),
        new BigDecimal("3"),
        order);

    JsonObject recall = recall(s, "AFTER-E", "LOT-A");

    assertThat(recall.getInt("ordersAffected"), is(1));
    assertThat(recall.getJsonNumber("qtySold").bigDecimalValue(), comparesEqualTo(d("3")));
    assertThat(
        sql(
            "SELECT batch_id FROM inventory.recall_sales WHERE recall_id = '"
                + recall.getString("id")
                + "' AND order_id = '"
                + order
                + "'"),
        is(target));
  }

  @Test
  @DisplayName("Held stock is not merged into stock on sale, so a hold cannot be lifted by it")
  void heldStockIsNotMergedIntoStockOnSale() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    recall(s, "BEFORE-A", "LOT-A");
    String before = footprint(s, source, target);

    assertThat(
        refusedWith(merge(s, source, target, "2"), 422),
        is("INVENTORY_LOT_MERGE_CONDITION_MISMATCH"));
    assertThat(
        refusedWith(merge(s, target, source, "2"), 422),
        is("INVENTORY_LOT_MERGE_CONDITION_MISMATCH"));

    assertThat(footprint(s, source, target), is(before));
  }

  @Test
  @DisplayName("A target held by another recall joins the source's, and is freed when both end")
  void aMergeOfHeldStockJoinsTheRecallsOfTheSource() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String byA = recall(s, "HOLD-A", "LOT-A").getString("id");
    String byB = recall(s, "HOLD-B", "LOT-B").getString("id");
    assertThat(materialStatus(source), is("RECALLED"));
    assertThat(materialStatus(target), is("RECALLED"));

    mergeOk(s, source, target, "4");

    assertThat(
        "the target is held by the recall of the lot merged into it, as it arrived",
        sql(
            "SELECT match_type || '|' || prior_material_status || '|' || qty_at_quarantine || '|'"
                + " || quarantined_on FROM inventory.recall_batches WHERE recall_id = '"
                + byA
                + "' AND batch_id = '"
                + target
                + "'"),
        is("IN_SCOPE|AVAILABLE|9.000|ARRIVAL"));

    assertThat(cancelRecall(s, byB).getStatus(), is(200));
    assertThat("the other recall still holds it", materialStatus(target), is("RECALLED"));
    assertThat(cancelRecall(s, byA).getStatus(), is(200));
    assertThat(materialStatus(target), is("AVAILABLE"));
    assertThat(materialStatus(source), is("AVAILABLE"));
  }

  // ── stock awaiting putaway ─────────────────────────────────────────────────

  private static String openTasks(Stock s) {
    return sql(
        "SELECT coalesce(string_agg(qty::text, ',' ORDER BY qty), 'none')"
            + " FROM inventory.putaway_tasks WHERE tenant_id = '"
            + s.tenant()
            + "' AND store_id = '"
            + s.store()
            + "' AND status = 'OPEN'");
  }

  @Test
  @DisplayName("A merge between batches awaiting putaway leaves the waiting list true")
  void aMergeKeepsThePutawayListTrue() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    assertThat(openTasks(s), is("5.000,10.000"));

    mergeOk(s, source, target, "4");
    assertThat("6 left to place at the source, 9 at the target", openTasks(s), is("6.000,9.000"));

    mergeOk(s, source, target, "6");
    assertThat("the emptied source has nothing to place", openTasks(s), is("15.000"));
    assertThat(held(s), comparesEqualTo(d("15")));
  }

  // ── another business, a caller held to stores ──────────────────────────────

  @Test
  @DisplayName("Another business, of any role, cannot merge our batches, and nothing of ours moves")
  void anotherBusinessCannotMergeOurBatches() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String before = footprint(s, source, target);
    String other = Ids.newId().toString();
    TENANTS.with(other, "GBP", "GB");
    Stock theirs = new Stock(other, Ids.newId().toString(), Ids.newId().toString(), s.variant());
    String ownBatch = receive(theirs, 5, "LOT-X", "1.0000", "2098-01-01");

    for (String role :
        List.of("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
      for (String[] pair :
          new String[][] {
            {source, target}, {source, ownBatch}, {ownBatch, target}, {target, source}
          }) {
        Response r = mergeAs(other, Ids.newId().toString(), role, pair[0], pair[1], "1");
        String body = r.readEntity(String.class);
        assertThat(role + ": " + body, r.getStatus(), is(oneOf(403, 404)));
        assertThat(role + ": " + body, body.contains(source) || body.contains(target), is(false));
      }
    }

    assertThat(footprint(s, source, target), is(before));
    assertThat(remaining(ownBatch), comparesEqualTo(d("5")));
    assertThat(count("lot_actions", theirs), is(0L));
    assertThat(count("stock_movements", theirs), is(1L));
  }

  private Response heldTo(Stock s, String role, String stores, String source, String target) {
    return as("/admin/inventory/lots/merge", s.tenant(), Ids.newId().toString(), role)
        .header("X-Store-Ids", stores)
        .post(Entity.entity(mergeJson(source, target, "1"), MediaType.APPLICATION_JSON));
  }

  private static void refusedForStore(Response r, String what) {
    String body = r.readEntity(String.class);
    assertThat(what + ": " + body, r.getStatus(), is(403));
    assertThat(what, body, containsString("STORE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName("A caller held to other stores cannot merge batches at a store they do not keep")
  void aCallerHeldToOtherStoresCannotMerge() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String before = footprint(s, source, target);
    String notTheirs = Ids.newId().toString();

    for (String role : List.of("MANAGER", "STOREKEEPER")) {
      refusedForStore(heldTo(s, role, notTheirs, source, target), role);
    }
    assertThat(footprint(s, source, target), is(before));

    // A batch no one has is not found, whatever stores the caller keeps: 404 before 403.
    // (A source that exists at a store not theirs is refused first, as it is when the target
    // exists.)
    assertThat(
        heldTo(s, "MANAGER", notTheirs, Ids.newId().toString(), target).getStatus(), is(404));
    // The source is the caller's own here, so the missing target is what they are told.
    assertThat(
        heldTo(s, "MANAGER", s.store(), source, Ids.newId().toString()).getStatus(), is(404));

    // Held to the batches' store, the same call goes through.
    assertThat(heldTo(s, "STOREKEEPER", s.store(), source, target).getStatus(), is(200));
  }

  @Test
  @DisplayName("Both batches are held to the caller's stores, not only the source")
  void bothBatchesAreHeldToTheCallersStores() {
    Stock here = stock();
    Stock there = elsewhere(here);
    String atHere = receive(here, 10, "LOT-A", "1.0000", "2098-01-01");
    String atThere = receive(there, 5, "LOT-B", "1.0000", "2098-01-01");
    String before = footprint(here, atHere, atThere);

    // Held to the source's store only, the target's store is not theirs: 403, not a mismatch.
    refusedForStore(heldTo(here, "MANAGER", here.store(), atHere, atThere), "target not theirs");
    // Held to the target's store only, the source's store is not theirs.
    refusedForStore(heldTo(here, "MANAGER", there.store(), atHere, atThere), "source not theirs");
    // Held to both, they reach the rule that the batches must share a store.
    Response both = heldTo(here, "MANAGER", here.store() + "," + there.store(), atHere, atThere);
    assertThat(refusedWith(both, 422), is("INVENTORY_LOT_MERGE_STORE_MISMATCH"));

    assertThat(footprint(here, atHere, atThere), is(before));
  }

  // ── a retried merge ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A merge retried under its Idempotency-Key answers the first merge and moves nothing")
  void aRetriedMergeDoesNotMergeTwice() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();

    JsonObject first = Envelopes.ok(keyedMerge(s, source, target, "2", key));
    String afterFirst = footprint(s, source, target);
    JsonObject again = Envelopes.ok(keyedMerge(s, source, target, "2", key));

    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat("a retry changes nothing", footprint(s, source, target), is(afterFirst));
    assertThat(remaining(source), comparesEqualTo(d("8")));
    assertThat(remaining(target), comparesEqualTo(d("7")));
    assertThat(events(s, "LotMerge"), is(1L));

    JsonObject next = Envelopes.ok(keyedMerge(s, source, target, "2", Ids.newId().toString()));
    assertThat(next.getString("id"), not(first.getString("id")));
    assertThat(remaining(source), comparesEqualTo(d("6")));
    assertThat(remaining(target), comparesEqualTo(d("9")));

    mergeOk(s, source, target, "1");
    mergeOk(s, source, target, "1");
    assertThat(
        "without a key every request is an attempt of its own",
        remaining(source),
        comparesEqualTo(d("4")));
  }

  @Test
  @DisplayName("A key used for a different merge is refused, and nothing moves")
  void aKeyUsedForAnotherMergeIsRefused() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String third = receive(s, 5, "LOT-C", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();
    Envelopes.ok(keyedMerge(s, source, target, "2", key));
    String before = footprint(s, source, target, third);

    assertThat(
        refusedWith(keyedMerge(s, source, target, "3", key), 409), is("IDEMPOTENCY_KEY_REUSED"));
    assertThat(
        refusedWith(keyedMerge(s, source, third, "2", key), 409), is("IDEMPOTENCY_KEY_REUSED"));

    assertThat(footprint(s, source, target, third), is(before));
  }

  @Test
  @DisplayName("The same merge sent four times at once under one key is made once")
  void theSameKeyAtOnceMergesOnce() throws Exception {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String key = Ids.newId().toString();

    List<String> ids =
        Concurrency.inParallel(
            4,
            () -> {
              Response r = keyedMerge(s, source, target, "2", key);
              String body = r.readEntity(String.class);
              assertThat(body, r.getStatus(), is(200));
              return Envelopes.parse(body).getJsonObject("data").getString("id");
            });

    assertThat(ids.stream().distinct().count(), is(1L));
    assertThat(remaining(source), comparesEqualTo(d("8")));
    assertThat(remaining(target), comparesEqualTo(d("7")));
    assertThat(movements(s, "LOT_MERGE"), is(2L));
  }

  // ── one transaction ────────────────────────────────────────────────────────

  @Test
  @DisplayName("A merge that fails at any of its writes leaves both batches, the ledger and lots")
  void aMergeThatFailsPartWayChangesNothing() {
    // The merge updates two batches and writes two movements, a genealogy link, a lot action and
    // an outbox row. Whichever of the last four is refused, none of the others may stay behind.
    for (String table : List.of("stock_movements", "lot_genealogy", "lot_actions", "outbox")) {
      Stock s = stock();
      String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
      String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
      String before = footprint(s, source, target);
      String trigger = "refuse_" + table + "_" + s.tenant().replace('-', '_').substring(24);
      Envelopes.exec(
          PG,
          "CREATE FUNCTION inventory."
              + trigger
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
              + " IF NEW.tenant_id = '"
              + s.tenant()
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
        Response r = merge(s, source, target, "4");
        String body = r.readEntity(String.class);
        assertThat(table + ": " + body, r.getStatus(), greaterThanOrEqualTo(500));
      } finally {
        Envelopes.exec(PG, "DROP TRIGGER " + trigger + " ON inventory." + table);
        Envelopes.exec(PG, "DROP FUNCTION inventory." + trigger + "()");
      }

      assertThat(table + ": nothing of the merge stays", footprint(s, source, target), is(before));
    }
  }

  @Test
  @DisplayName("Merges in opposite directions at once do not deadlock, and the stock is conserved")
  void mergesInOppositeDirectionsDoNotDeadlock() throws Exception {
    Stock s = stock();
    String a = receive(s, 20, "LOT-A", "1.0000", "2098-01-01");
    String b = receive(s, 20, "LOT-B", "1.0000", "2098-01-01");
    AtomicInteger turn = new AtomicInteger();

    List<Integer> statuses =
        Concurrency.inParallel(
            8,
            () -> {
              boolean forward = turn.getAndIncrement() % 2 == 0;
              Response r = merge(s, forward ? a : b, forward ? b : a, "1");
              r.readEntity(String.class);
              return r.getStatus();
            });

    assertThat(statuses.toString(), statuses.stream().allMatch(x -> x == 200), is(true));
    assertThat(remaining(a).add(remaining(b)), comparesEqualTo(d("40")));
    assertThat(held(s), comparesEqualTo(d("40")));
    assertThat(movements(s, "LOT_MERGE"), is(16L));
  }

  // ── what a merge of held stock restores to ─────────────────────────────────

  private void quarantine(Stock s, String batch) {
    assertThat(
        put(
                "/admin/inventory/batches/" + batch + "/material-status",
                "{\"materialStatus\":\"QUARANTINE\",\"reason\":\"Awaiting lab result\"}",
                s)
            .getStatus(),
        is(200));
  }

  @Test
  @DisplayName("Held stock merged with held stock is restored to the more restrictive prior status")
  void aMergeOfHeldBatchesRestoresToTheMoreRestrictivePriorStatus() {
    for (boolean sourceQuarantined : List.of(true, false)) {
      Stock s = stock();
      String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
      String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
      quarantine(s, sourceQuarantined ? source : target);
      String byA = recall(s, "HOLD-A", "LOT-A").getString("id");
      String byB = recall(s, "HOLD-B", "LOT-B").getString("id");

      mergeOk(s, source, target, "4");

      assertThat(
          "every recall holding the target records the stricter status",
          sql(
              "SELECT string_agg(DISTINCT prior_material_status, ',') FROM"
                  + " inventory.recall_batches WHERE batch_id = '"
                  + target
                  + "'"),
          is("QUARANTINE"));
      assertThat(cancelRecall(s, byB).getStatus(), is(200));
      assertThat(materialStatus(target), is("RECALLED"));
      assertThat(cancelRecall(s, byA).getStatus(), is(200));
      assertThat(
          "never put on sale: part of it was quarantined",
          materialStatus(target),
          is("QUARANTINE"));
    }
  }

  // ── a merge link a recall can follow ───────────────────────────────────────

  @Test
  @DisplayName("A merge between two batches already linked as a transform is still a MERGE link")
  void aMergeUpgradesAnExistingTransformLink() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    assertThat(
        post(
                "/admin/inventory/lot-genealogy",
                "{\"parentBatchId\":\""
                    + source
                    + "\",\"childBatchId\":\""
                    + target
                    + "\",\"qty\":1,\"relationType\":\"TRANSFORM\"}",
                s.tenant(),
                s.staff(),
                "OWNER")
            .getStatus(),
        is(201));

    mergeOk(s, source, target, "2");

    assertThat(
        sql(
            "SELECT relation_type || '|' || qty FROM inventory.lot_genealogy WHERE parent_batch_id"
                + " = '"
                + source
                + "' AND child_batch_id = '"
                + target
                + "'"),
        is("MERGE|3.000"));
    assertThat(
        heldBatches(recall(s, "AFTER-T", "LOT-A")).keySet(), containsInAnyOrder(source, target));
  }

  // ── POST /lot-genealogy names two batches ──────────────────────────────────

  private Response link(Stock s, String role, String stores, String parent, String child) {
    Invocation.Builder b =
        as("/admin/inventory/lot-genealogy", s.tenant(), Ids.newId().toString(), role);
    if (stores != null) {
      b = b.header("X-Store-Ids", stores);
    }
    return b.post(
        Entity.entity(
            "{\"parentBatchId\":\""
                + parent
                + "\",\"childBatchId\":\""
                + child
                + "\",\"qty\":1,\"relationType\":\"SPLIT\"}",
            MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("A link between two batches is written only by a caller held to both their stores")
  void aLinkIsHeldToTheStoresOfBothBatches() {
    Stock here = stock();
    Stock there = elsewhere(here);
    String atHere = receive(here, 10, "LOT-A", "1.0000", "2098-01-01");
    String alsoHere = receive(here, 5, "LOT-B", "1.0000", "2098-01-01");
    String atThere = receive(there, 5, "LOT-C", "1.0000", "2098-01-01");
    String notTheirs = Ids.newId().toString();

    refusedForStore(link(here, "MANAGER", notTheirs, atHere, alsoHere), "neither store");
    refusedForStore(link(here, "MANAGER", here.store(), atHere, atThere), "child not theirs");
    refusedForStore(link(here, "MANAGER", here.store(), atThere, atHere), "parent not theirs");
    assertThat(
        "a batch no one has is a 404 before any 403",
        link(here, "MANAGER", notTheirs, atHere, Ids.newId().toString()).getStatus(),
        is(404));
    assertThat(
        link(here, "MANAGER", notTheirs, Ids.newId().toString(), atHere).getStatus(), is(404));
    assertThat(count("lot_genealogy", here), is(0L));

    assertThat(link(here, "MANAGER", here.store(), atHere, alsoHere).getStatus(), is(201));
    assertThat(link(here, "OWNER", null, atHere, atThere).getStatus(), is(201));
    assertThat(count("lot_genealogy", here), is(2L));
  }

  @Test
  @DisplayName("Another business, of any role, cannot link our batches")
  void anotherBusinessCannotLinkOurBatches() {
    Stock mine = stock();
    Stock theirs = stock();
    String a = receive(mine, 10, "LOT-A", "1.0000", "2098-01-01");
    String b = receive(mine, 5, "LOT-B", "1.0000", "2098-01-01");

    for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER")) {
      Response r = link(theirs, role, null, a, b);
      String body = r.readEntity(String.class);
      assertThat(role + ": " + body, r.getStatus(), is(oneOf(403, 404)));
    }

    assertThat(count("lot_genealogy", mine), is(0L));
    assertThat(count("lot_genealogy", theirs), is(0L));
  }

  // ── Idempotency-Key is read before any batch ───────────────────────────────

  @Test
  @DisplayName("A malformed Idempotency-Key is refused before any batch is read")
  void aMalformedKeyIsRefusedBeforeAnyBatchIsRead() {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String target = receive(s, 5, "LOT-B", "1.0000", "2098-01-01");
    String before = footprint(s, source, target);

    assertThat(
        refusedWith(keyedMerge(s, source, target, "1", "not-a-uuid"), 400),
        is("IDEMPOTENCY_KEY_INVALID"));
    assertThat(
        "a batch no one has",
        refusedWith(
            keyedMerge(s, Ids.newId().toString(), Ids.newId().toString(), "1", "not-a-uuid"), 400),
        is("IDEMPOTENCY_KEY_INVALID"));

    assertThat(footprint(s, source, target), is(before));
  }

  @Test
  @DisplayName("A retried blended merge answers the first merge even when the currency is unread")
  void aRetriedBlendedMergeNeedsNoCurrency() {
    Stock s =
        new Stock(
            Ids.newId().toString(),
            Ids.newId().toString(),
            Ids.newId().toString(),
            Ids.newId().toString());
    String source = receive(s, 4, "LOT-A", "3.0000", "2098-01-01");
    String target = receive(s, 6, "LOT-B", "2.0000", "2098-01-01");
    String key = Ids.newId().toString();
    String action = Ids.newId().toString();
    Envelopes.exec(
        PG,
        "INSERT INTO inventory.lot_actions (id, tenant_id, action_type, source_batch_id,"
            + " result_batch_id, qty, idempotency_key) VALUES ('"
            + action
            + "', '"
            + s.tenant()
            + "', 'MERGE', '"
            + source
            + "', '"
            + target
            + "', 4, '"
            + key
            + "')");
    String before = footprint(s, source, target);

    JsonObject again = Envelopes.ok(keyedMerge(s, source, target, "4", key));

    assertThat(again.getString("id"), is(action));
    assertThat(footprint(s, source, target), is(before));
    assertThat(
        "a first attempt still needs the currency",
        refusedWith(merge(s, source, target, "4"), 503),
        is("TENANT_PROFILE_UNAVAILABLE"));
  }

  // ── lock order against a recall's cancel ───────────────────────────────────

  @Test
  @DisplayName("A split waits for a recall's lock before it takes its batch, so a cancel cannot")
  void aSplitTakesTheRecallsBeforeTheBatch() throws Exception {
    Stock s = stock();
    String source = receive(s, 10, "LOT-A", "1.0000", "2098-01-01");
    String recall = recall(s, "LOCK-A", "LOT-A").getString("id");

    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      // What a cancel does first: lock the recall.
      st.execute("SELECT id FROM inventory.recalls WHERE id = '" + recall + "' FOR UPDATE");
      CompletableFuture<Integer> split =
          CompletableFuture.supplyAsync(
              () -> {
                Response r =
                    post(
                        "/admin/inventory/lots/split",
                        "{\"sourceBatchId\":\"" + source + "\",\"qty\":2}",
                        s.tenant(),
                        s.staff(),
                        "OWNER");
                r.readEntity(String.class);
                return r.getStatus();
              });
      long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!"1".equals(sql("SELECT least(count(*), 1) FROM pg_locks WHERE NOT granted"))) {
        assertThat("the split reached the recall's lock", System.nanoTime() < give, is(true));
        Thread.sleep(20);
      }
      // Waiting on the recall, the split holds no lock on the batch: what a cancel takes next.
      st.execute(
          "SELECT id FROM inventory.inventory_batches WHERE id = '"
              + source
              + "' FOR UPDATE NOWAIT");
      st.executeUpdate(
          "UPDATE inventory.inventory_batches SET material_status_changed_at = now() WHERE id = '"
              + source
              + "'");
      c.commit();

      assertThat(split.get(30, TimeUnit.SECONDS), is(200));
    }
    assertThat(remaining(source), comparesEqualTo(d("8")));
  }
}
