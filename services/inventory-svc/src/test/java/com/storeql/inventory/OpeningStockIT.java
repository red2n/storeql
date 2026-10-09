package com.storeql.inventory;

import static com.storeql.test.Envelopes.created;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opening stock (intent catalogue-import): what a business already holds when it starts, opened
 * once per (store, variant) through the door every arrival uses, by management at a store they
 * keep, and never past another business.
 */
@HelidonTest
class OpeningStockIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String OTHER_STORE = Ids.newId().toString();
  private static final String RIVAL_STORE = Ids.newId().toString();
  private static final String OWNER_ID = Ids.newId().toString();

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .with(RIVAL, "GBP", "GB")
            .withStore(T, STORE, "GB")
            .withStore(T, OTHER_STORE, "GB")
            .withStore(RIVAL, RIVAL_STORE, "GB");
    PG.wire("inventory");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    TENANTS.close();
    PG.stop();
  }

  // ── harness ──────────────────────────────────────────────────────────────────

  private Response send(
      String method, String path, String json, String tenant, String roles, String storeIds) {
    Invocation.Builder b =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("X-User-Id", OWNER_ID);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String line(String variant, String qty, String cost, String expiry, String lot) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + (cost == null ? "" : ",\"unitCost\":" + cost)
        + (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
        + (lot == null ? "" : ",\"batchNo\":\"" + lot + "\"")
        + "}";
  }

  private static String body(String job, String store, String... lines) {
    return "{\"jobId\":\""
        + job
        + "\",\"storeId\":\""
        + store
        + "\",\"lines\":["
        + String.join(",", lines)
        + "]}";
  }

  private Response open(String json, String tenant, String roles) {
    return send("POST", "/admin/inventory/opening-stock", json, tenant, roles, null);
  }

  private JsonObject opened(String json) {
    return ok(open(json, T, "OWNER"));
  }

  private static String id() {
    return Ids.newId().toString();
  }

  private static String count(String sql) {
    return scalar(PG, sql);
  }

  private static String batches(String tenant, String variant) {
    return count(
        "SELECT count(*) FROM inventory.inventory_batches WHERE tenant_id = '"
            + tenant
            + "' AND variant_id = '"
            + variant
            + "'");
  }

  private static String movements(String tenant, String variant) {
    return count(
        "SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '"
            + tenant
            + "' AND variant_id = '"
            + variant
            + "'");
  }

  private static String events(String variant) {
    return count(
        "SELECT count(*) FROM inventory.outbox WHERE event_type = 'StockReceived'"
            + " AND payload::text LIKE '%"
            + variant
            + "%'");
  }

  // ── the point of the row ─────────────────────────────────────────────────────

  @Test
  @DisplayName("opens stock as received batches with their cost, lot and last day of sale")
  void opensStock() {
    String job = id();
    String bread = id();
    String milk = id();
    String expiry = LocalDate.now().plusDays(5).toString();

    JsonObject result =
        opened(
            body(
                job,
                STORE,
                line(bread, "24", "0.6500", null, null),
                line(milk, "11.500", "1.0000", expiry, "L-77")));

    assertThat(result.getInt("loaded"), is(2));
    assertThat(result.getInt("replayed"), is(0));
    assertThat(result.getInt("alreadyOpened"), is(0));
    JsonArray lines = result.getJsonArray("lines");
    assertThat(lines.getJsonObject(0).getString("outcome"), is("LOADED"));
    assertThat(lines.getJsonObject(0).getBoolean("held"), is(false));
    String batch = lines.getJsonObject(1).getString("batchId");

    assertThat(
        count(
            "SELECT received_qty || '|' || remaining_qty || '|' || cost_price || '|' || expiry_date"
                + " || '|' || batch_no || '|' || store_id || '|' || ownership || '|' || duty_status"
                + " FROM inventory.inventory_batches WHERE id = '"
                + batch
                + "'"),
        is("11.500|11.500|1.0000|" + expiry + "|L-77|" + STORE + "|OWNED|DUTY_PAID"));
    // a receipt like any other: the movement names the cause and the person, the event goes out
    assertThat(
        count(
            "SELECT type || '|' || ref_type || '|' || ref_id || '|' || actor_id"
                + " FROM inventory.stock_movements WHERE batch_id = '"
                + batch
                + "'"),
        is("RECEIVE|OPENING_STOCK|" + job + "|" + OWNER_ID));
    assertThat(events(milk), is("1"));
    // it is on the shelf for sale: counted available at the store
    assertThat(
        count(
            "SELECT COALESCE(SUM(remaining_qty), 0) FROM inventory.inventory_batches"
                + " WHERE store_id = '"
                + STORE
                + "' AND variant_id = '"
                + bread
                + "' AND material_status = 'AVAILABLE'"),
        is("24.000"));
  }

  @Test
  @DisplayName("the same job asking again writes nothing more and says so")
  void replay() {
    String job = id();
    String item = id();
    String json = body(job, STORE, line(item, "10", "2.0000", null, null));
    String batch = opened(json).getJsonArray("lines").getJsonObject(0).getString("batchId");

    JsonObject again = opened(json);

    assertThat(again.getInt("loaded"), is(0));
    assertThat(again.getInt("replayed"), is(1));
    assertThat(again.getJsonArray("lines").getJsonObject(0).getString("outcome"), is("REPLAYED"));
    assertThat(again.getJsonArray("lines").getJsonObject(0).getString("batchId"), is(batch));
    assertThat(batches(T, item), is("1"));
    assertThat(movements(T, item), is("1"));
    assertThat(events(item), is("1"));
  }

  @Test
  @DisplayName("another job cannot open an item that was opened: the line is left alone")
  void notTwice() {
    String item = id();
    String fresh = id();
    String first =
        opened(body(id(), STORE, line(item, "10", "2.0000", null, null)))
            .getJsonArray("lines")
            .getJsonObject(0)
            .getString("batchId");

    JsonObject second =
        opened(
            body(
                id(),
                STORE,
                line(item, "99", "9.0000", null, null),
                line(fresh, "3", "1.0000", null, null)));

    assertThat(second.getInt("alreadyOpened"), is(1));
    assertThat(second.getInt("loaded"), is(1));
    JsonObject refused = second.getJsonArray("lines").getJsonObject(0);
    assertThat(refused.getString("outcome"), is("ALREADY_OPENED"));
    assertThat(refused.getString("batchId"), is(first));
    assertThat(batches(T, item), is("1"));
    assertThat(
        count("SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '" + first + "'"),
        is("10.000"));
    assertThat(batches(T, fresh), is("1"));
  }

  @Test
  @DisplayName("the same item at another store is a separate opening")
  void perStore() {
    String item = id();
    opened(body(id(), STORE, line(item, "10", "2.0000", null, null)));

    JsonObject other = opened(body(id(), OTHER_STORE, line(item, "7", "2.0000", null, null)));

    assertThat(other.getInt("loaded"), is(1));
    assertThat(batches(T, item), is("2"));
  }

  @Test
  @DisplayName("a lot under recall is opened held, not for sale")
  void recalledLotIsHeld() {
    String item = id();
    JsonObject recall =
        created(
            send(
                "POST",
                "/admin/recalls",
                "{\"reference\":\"OPEN-"
                    + id()
                    + "\",\"kind\":\"RECALL\",\"hazard\":\"ALLERGEN\",\"reason\":\"Undeclared peanut\","
                    + "\"source\":\"FSA\",\"customerNotice\":\"Do not eat. Return it to the store.\","
                    + "\"remedies\":[\"REFUND\",\"REPLACEMENT\"],\"contactPhone\":\"0800 100 200\","
                    + "\"items\":[{\"variantId\":\""
                    + item
                    + "\",\"batchNo\":\"BAD-1\"}]}",
                T,
                "OWNER",
                null));
    assertThat(recall.getString("id").isEmpty(), is(false));

    JsonObject result =
        opened(
            body(
                id(),
                STORE,
                line(item, "6", "1.0000", LocalDate.now().plusDays(9).toString(), "BAD-1")));

    JsonObject l = result.getJsonArray("lines").getJsonObject(0);
    assertThat(l.getBoolean("held"), is(true));
    assertThat(
        count(
            "SELECT material_status FROM inventory.inventory_batches WHERE id = '"
                + l.getString("batchId")
                + "'"),
        is("RECALLED"));
  }

  @Test
  @DisplayName("a batch with no zone is placed by the store's rule, or waits for a person")
  void putaway() {
    String item = id();
    String batch =
        opened(body(id(), STORE, line(item, "4", null, null, null)))
            .getJsonArray("lines")
            .getJsonObject(0)
            .getString("batchId");

    assertThat(
        count(
            "SELECT count(*) FROM inventory.putaway_tasks WHERE batch_id = '"
                + batch
                + "' AND status = 'OPEN'"),
        is("1"));
  }

  // ── a body that is wrong writes nothing ──────────────────────────────────────

  @Test
  @DisplayName("a wrong line anywhere refuses the call and nothing of it is written")
  void wrongBodies() {
    String good = id();
    String job = id();
    String[] bad = {
      line(id(), "0", null, null, null),
      line(id(), "-1", null, null, null),
      line(id(), "1.0005", null, null, null),
      line(id(), "1", "-0.5", null, null),
      line(id(), "1", "1.00001", null, null),
      line(id(), "1", null, "31/12/2026", null),
      line("not-an-id", "1", null, null, null),
      line("3f2504e0-4f89-41d3-9a0c-0305e82c3301", "1", null, null, null)
    };
    for (String line : bad) {
      Response r = open(body(job, STORE, line(good, "5", "1.0000", null, null), line), T, "OWNER");
      assertThat(line, r.getStatus(), is(400));
      r.close();
    }
    assertThat(batches(T, good), is("0"));

    // the same item twice
    Response dup =
        open(
            body(job, STORE, line(good, "5", null, null, null), line(good, "6", null, null, null)),
            T,
            "OWNER");
    assertThat(dup.readEntity(String.class), containsString("INVENTORY_OPENING_DUPLICATE_LINE"));
    // no lines, a job that is no id, too many lines
    assertThat(open(body(job, STORE), T, "OWNER").getStatus(), is(400));
    assertThat(
        open(body("nope", STORE, line(good, "1", null, null, null)), T, "OWNER").getStatus(),
        is(400));
    String[] many = new String[501];
    for (int i = 0; i < many.length; i++) many[i] = line(id(), "1", null, null, null);
    assertThat(open(body(job, STORE, many), T, "OWNER").getStatus(), is(400));
    assertThat(batches(T, good), is("0"));
  }

  // ── who may open stock ───────────────────────────────────────────────────────

  @Test
  @DisplayName("management opens stock; no one else does, and a keeper of other stores cannot")
  void whoMayOpen() {
    String item = id();
    String json = body(id(), STORE, line(item, "5", "1.0000", null, null));

    for (String roles : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Response r = open(json, T, roles);
      assertThat(roles, r.getStatus(), is(403));
      r.close();
    }
    Response elsewhere =
        send("POST", "/admin/inventory/opening-stock", json, T, "MANAGER", OTHER_STORE);
    assertThat(elsewhere.getStatus(), is(403));
    elsewhere.close();
    assertThat(batches(T, item), is("0"));

    Response mine = send("POST", "/admin/inventory/opening-stock", json, T, "MANAGER", STORE);
    assertThat(mine.getStatus(), is(200));
    mine.close();
    assertThat(batches(T, item), is("1"));
  }

  // ── tenant isolation ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("another business cannot open stock at our store, whoever it is, and moves nothing")
  void otherBusinessCannotOpenOurStore() {
    String item = id();
    String json = body(id(), STORE, line(item, "5", "1.0000", null, null));

    for (String roles : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      Response r = open(json, RIVAL, roles);
      assertThat(roles, r.getStatus() == 404 || r.getStatus() == 403, is(true));
      r.close();
    }
    assertThat(batches(RIVAL, item) + batches(T, item), is("00"));
  }

  @Test
  @DisplayName(
      "the same item id opened in two businesses is two openings; a job's totals are its business's")
  void perBusinessAndSummary() {
    String item = id();
    String job = id();
    String rivalJob = id();
    String uncosted = id();
    opened(
        body(
            job,
            STORE,
            line(item, "12", "0.3333", null, null),
            line(uncosted, "4.5", null, null, null)));
    JsonObject theirs =
        ok(
            open(
                body(rivalJob, RIVAL_STORE, line(item, "100", "9.0000", null, null)),
                RIVAL,
                "OWNER"));
    assertThat(theirs.getInt("loaded"), is(1));

    JsonObject totals =
        ok(send("GET", "/admin/inventory/opening-stock/" + job, null, T, "OWNER", null));
    JsonObject store = totals.getJsonArray("stores").getJsonObject(0);
    assertThat(totals.getJsonArray("stores").size(), is(1));
    assertThat(store.getString("storeId"), is(STORE));
    assertThat(store.getInt("lines"), is(2));
    assertThat(store.getString("qty"), is("16.500"));
    // 12 x 0.3333, exactly; the line with no cost is counted apart, not valued at nothing
    assertThat(
        new java.math.BigDecimal(store.getString("value")),
        org.hamcrest.Matchers.comparesEqualTo(new java.math.BigDecimal("3.9996")));
    assertThat(store.getInt("uncostedLines"), is(1));

    // the other business sees nothing of our job, and ours nothing of theirs
    JsonObject nothing =
        ok(send("GET", "/admin/inventory/opening-stock/" + job, null, RIVAL, "OWNER", null));
    assertThat(nothing.getJsonArray("stores").size(), is(0));
    JsonObject ours =
        ok(send("GET", "/admin/inventory/opening-stock/" + rivalJob, null, T, "OWNER", null));
    assertThat(ours.getJsonArray("stores").size(), is(0));
    // and a job opened by someone else is not summarised for a store the reader is not held to
    Response held =
        send("GET", "/admin/inventory/opening-stock/" + job, null, T, "MANAGER", OTHER_STORE);
    assertThat(ok(held).getJsonArray("stores").size(), is(0));
  }
}
