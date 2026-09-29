package com.storeql.order;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.parse;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
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
 * Return controls (intent/return-controls.md), slice 1: each returned line names its condition, a
 * return is judged against the business's policy before anything is written, a manager is named
 * when it falls outside, a retry answers with the first, and the money and the goods go where the
 * refund method says — with the wrong caller, the wrong business and the wrong input tried beside
 * the right ones.
 */
@HelidonTest
class ReturnsIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(ReturnsIT.T, "USD", "US")
        .with(ReturnsIT.T_POLICY, "USD", "US")
        .with(ReturnsIT.OTHER_T, "USD", "US");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
  }

  /** The business whose policy is the default. */
  private static final String T = "01a0a1c1-1111-7000-8000-000000000001";

  /** A business that sets its own policy. */
  private static final String T_POLICY = "01a0a1c1-1111-7000-8000-000000000002";

  /** Another business, which must never reach the others. */
  private static final String OTHER_T = "01a0a1c1-1111-7000-8000-000000000003";

  private static final String STORE = "01a0a1c1-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1c1-2222-7000-8000-00000000000b";
  private static final String OTHER_T_STORE = "01a0a1c1-2222-7000-8000-00000000000c";
  private static final String V = "01a0a1c1-3333-7000-8000-000000000001";

  private static final String MANAGER = "01a0a1c1-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c1-4444-7000-8000-000000000002";
  private static final String KEEPER = "01a0a1c1-4444-7000-8000-000000000003";

  @Inject WebTarget target;
  @Inject OrderService orderService;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private Invocation.Builder as(
      String path, String tenant, String roles, String user, String stores) {
    var b = target.path(path).request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (user != null) b = b.header("X-User-Id", user);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return b;
  }

  private Response post(
      String path, String json, String tenant, String roles, String user, String key) {
    var b = as(path, tenant, roles, user, null);
    if (key != null) b = b.header("Idempotency-Key", key);
    return b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response returnAs(String order, String json, String tenant, String roles, String user) {
    return post("/orders/" + order + "/returns", json, tenant, roles, user, Ids.newId().toString());
  }

  private Response get(String path, String tenant, String roles, String user) {
    return as(path, tenant, roles, user, null).get();
  }

  /** A paid two-unit till sale at 10.00 each, so FULFILLED; {@code customer} may be null. */
  private String sale(String tenant, String store, String customer) {
    Response r =
        post(
            "/orders",
            "{\"storeId\":\""
                + store
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],\"currency\":\"USD\""
                + (customer == null ? "" : ",\"customerId\":\"" + customer + "\"")
                + "}",
            tenant,
            "MANAGER",
            MANAGER,
            Ids.newId().toString());
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    String id = parse(body).getJsonObject("data").getString("id");
    orderService.handlePaymentCaptured(
        Ids.parse(tenant), Ids.parse(id), Ids.newId(), new BigDecimal("20.00"));
    return id;
  }

  private static String lines(int qty, String condition) {
    return "\"items\":[{\"variantId\":\""
        + V
        + "\",\"qty\":"
        + qty
        + (condition == null ? "" : ",\"condition\":\"" + condition + "\"")
        + "}]";
  }

  private static String body(int qty, String condition, String extra) {
    return "{\"reason\":\"changed mind\"" + extra + "," + lines(qty, condition) + "}";
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return parse(body).getJsonObject("data");
  }

  private static long count(String table, String tenant, String orderColumn, String order) {
    return Long.parseLong(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\"."
                + table
                + " WHERE tenant_id='"
                + tenant
                + "' AND "
                + orderColumn
                + "='"
                + order
                + "'"));
  }

  private static long returnRows(String tenant, String order) {
    return count("returns", tenant, "order_id", order);
  }

  private static long events(String order, String type) {
    return Long.parseLong(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".outbox WHERE aggregate_id='"
                + order
                + "' AND event_type='"
                + type
                + "'"));
  }

  private static JsonObject event(String order, String type) {
    return parse(
        scalar(
            PG,
            "SELECT payload FROM \"order\".outbox WHERE aggregate_id='"
                + order
                + "' AND event_type='"
                + type
                + "' ORDER BY created_at DESC LIMIT 1"));
  }

  private static String statusOf(String order) {
    return scalar(PG, "SELECT status FROM \"order\".orders WHERE id='" + order + "'");
  }

  /** Puts the moment the goods left the store {@code days} days back. */
  private static void backdate(String order, int days) {
    exec(
        PG,
        "UPDATE \"order\".order_status_history SET changed_at = now() - interval '"
            + days
            + " days' WHERE order_id='"
            + order
            + "' AND to_status IN ('FULFILLED','PARTIALLY_FULFILLED')");
  }

  /** Absent or an explicit null: JSON-B leaves a null out, an event writes it. */
  private static boolean nullish(JsonObject o, String key) {
    return !o.containsKey(key) || o.get(key).getValueType() == JsonValue.ValueType.NULL;
  }

  private static String str(JsonObject o, String key) {
    return o.containsKey(key) && o.get(key).getValueType() != JsonValue.ValueType.NULL
        ? o.getString(key)
        : null;
  }

  // ── the condition ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("Every returned line must name one of the four conditions, or nothing is written")
  void conditionIsRequiredAndOneOfFour() {
    String order = sale(T, STORE, null);

    Response missing = returnAs(order, body(1, null, ""), T, "CASHIER", CASHIER);
    assertThat(missing.getStatus(), is(400));
    assertThat(missing.readEntity(String.class), containsString("ORDER_RETURN_CONDITION_REQUIRED"));

    for (String bad : new String[] {"GOOD", "NEW", "USED", "sealed?", "   "}) {
      Response r = returnAs(order, body(1, bad, ""), T, "CASHIER", CASHIER);
      assertThat(bad, r.getStatus(), is(400));
      String text = r.readEntity(String.class);
      assertThat(
          text,
          bad.isBlank()
              ? containsString("ORDER_RETURN_CONDITION_REQUIRED")
              : containsString("ORDER_RETURN_CONDITION_INVALID"));
    }
    assertThat(returnRows(T, order), is(0L));
    assertThat(events(order, "OrderReturned"), is(0L));

    // A lower-case condition is the same word.
    assertThat(returnAs(order, body(1, "opened", ""), T, "CASHIER", CASHIER).getStatus(), is(201));
  }

  // ── within policy ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("A cashier takes a return inside the window alone, and OrderReturned says how")
  void cashierReturnsWithinPolicy() {
    String order = sale(T, STORE, null);
    JsonObject ret = data(returnAs(order, body(1, "SEALED", ""), T, "CASHIER", CASHIER), 201);

    assertThat(ret.getString("status"), is("COMPLETED"));
    assertThat(ret.getString("refundMethod"), is("ORIGINAL"));
    assertThat(str(ret, "approvedBy"), is(nullValue()));
    assertThat(ret.getJsonArray("outsidePolicy"), hasSize(0));
    assertThat(nullish(ret, "giftCard"), is(true));
    assertThat(ret.getString("createdBy"), is(CASHIER));
    assertThat(ret.getJsonArray("items").getJsonObject(0).getString("condition"), is("SEALED"));

    JsonObject ev = event(order, "OrderReturned");
    assertThat(ev.getString("returnId"), is(ret.getString("id")));
    assertThat(ev.getString("refundMethod"), is("ORIGINAL"));
    assertThat(ev.getString("currency"), is("USD"));
    assertThat(ev.getJsonNumber("refundAmount").bigDecimalValue(), is(new BigDecimal("10.00")));
    assertThat(ev.getBoolean("recall"), is(false));
    assertThat(nullish(ev, "customerId"), is(true));
    assertThat(nullish(ev, "giftCardId"), is(true));
    assertThat(nullish(ev, "approvedBy"), is(true));
    JsonObject line = ev.getJsonArray("items").getJsonObject(0);
    assertThat(line.getString("variantId"), is(V));
    assertThat(line.getString("condition"), is("SEALED"));
    assertThat(line.getJsonNumber("qty").bigDecimalValue().compareTo(BigDecimal.ONE), is(0));
  }

  @Test
  @DisplayName("A shopper cannot take a return, and a caller held to another store cannot either")
  void onlyStaffAtTheStoreTakeReturns() {
    String order = sale(T, STORE, null);
    assertThat(
        returnAs(order, body(1, "SEALED", ""), T, "CUSTOMER", Ids.newId().toString()).getStatus(),
        is(403));
    var held =
        as("/orders/" + order + "/returns", T, "CASHIER", CASHIER, OTHER_STORE)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(Entity.entity(body(1, "SEALED", ""), MediaType.APPLICATION_JSON));
    assertThat(held.getStatus(), is(403));
    assertThat(returnRows(T, order), is(0L));
    assertThat(events(order, "OrderReturned"), is(0L));
  }

  // ── outside the policy ────────────────────────────────────────────────────

  @Test
  @DisplayName("Past the window a cashier is refused with nothing written; a manager is named")
  void pastTheWindowNeedsAManager() {
    String order = sale(T, STORE, null);
    backdate(order, 45);

    Response refused = returnAs(order, body(1, "SEALED", ""), T, "CASHIER", CASHIER);
    assertThat(refused.getStatus(), is(403));
    String text = refused.readEntity(String.class);
    assertThat(text, containsString("ORDER_RETURN_NEEDS_MANAGER"));
    assertThat(text, containsString("WINDOW"));
    assertThat(returnRows(T, order), is(0L));
    assertThat(events(order, "OrderReturned"), is(0L));

    // A storekeeper holds no sales.refund either.
    assertThat(
        returnAs(order, body(1, "SEALED", ""), T, "STOREKEEPER", KEEPER).getStatus(), is(403));

    JsonObject ret = data(returnAs(order, body(1, "SEALED", ""), T, "MANAGER", MANAGER), 201);
    assertThat(ret.getString("approvedBy"), is(MANAGER));
    assertThat(ret.getJsonArray("outsidePolicy").toString(), is("[\"WINDOW\"]"));
    assertThat(returnRows(T, order), is(1L));
    assertThat(event(order, "OrderReturned").getString("approvedBy"), is(MANAGER));

    // The audit trail names the lines, their conditions, the approver and why.
    JsonArray trail =
        parse(
                target
                    .path("/admin/audit/events")
                    .queryParam("type", "RETURN")
                    .queryParam("limit", 100)
                    .request(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", T)
                    .header("X-Roles", "OWNER")
                    .get()
                    .readEntity(String.class))
            .getJsonArray("data");
    JsonObject entry = null;
    for (JsonValue v : trail) {
      if (order.equals(v.asJsonObject().getString("orderId"))) entry = v.asJsonObject();
    }
    assertThat(trail.toString(), entry == null, is(false));
    assertThat(entry.getString("approvedBy"), is(MANAGER));
    assertThat(entry.getString("actorId"), is(MANAGER));
    assertThat(entry.getJsonArray("outsidePolicy").toString(), is("[\"WINDOW\"]"));
    JsonObject line = entry.getJsonArray("lines").getJsonObject(0);
    assertThat(line.getString("variantId"), is(V));
    assertThat(line.getString("condition"), is("SEALED"));
    assertThat(line.getJsonNumber("qty").bigDecimalValue().compareTo(BigDecimal.ONE), is(0));
  }

  @Test
  @DisplayName("Faulty goods past the window are never refused outright: a manager takes them")
  void faultyGoodsPastTheWindowGoToAManager() {
    String order = sale(T, STORE, null);
    backdate(order, 400);

    Response refused = returnAs(order, body(1, "FAULTY", ""), T, "CASHIER", CASHIER);
    assertThat(refused.getStatus(), is(403));
    String text = refused.readEntity(String.class);
    assertThat(text, containsString("FAULTY_PAST_WINDOW"));
    assertThat(text, not(containsString("\"WINDOW\"")));
    assertThat(returnRows(T, order), is(0L));

    JsonObject ret = data(returnAs(order, body(1, "FAULTY", ""), T, "MANAGER", MANAGER), 201);
    assertThat(ret.getString("approvedBy"), is(MANAGER));
    assertThat(ret.getJsonArray("outsidePolicy").toString(), is("[\"FAULTY_PAST_WINDOW\"]"));

    // A sealed item past the window is an ordinary late return, not a faulty-goods claim.
    String mixed = sale(T, STORE, null);
    backdate(mixed, 400);
    Response sealed = returnAs(mixed, body(1, "SEALED", ""), T, "MANAGER", MANAGER);
    assertThat(data(sealed, 201).getJsonArray("outsidePolicy").toString(), is("[\"WINDOW\"]"));
  }

  @Test
  @DisplayName("A business sets its own window and cashier ceiling; over either needs a manager")
  void theBusinessOwnPolicyApplies() {
    Response put =
        as("/admin/return-policy", T_POLICY, "OWNER", MANAGER, null)
            .put(
                Entity.entity(
                    "{\"windowDays\":7,\"cashierCeiling\":15.00,\"noReceiptAllowed\":false}",
                    MediaType.APPLICATION_JSON));
    JsonObject set = data(put, 200);
    assertThat(set.getInt("windowDays"), is(7));
    assertThat(set.getString("currency"), is("USD"));
    assertThat(set.getBoolean("usingDefault"), is(false));

    // Over the ceiling: two units is 20.00 against 15.00.
    String order = sale(T_POLICY, STORE, null);
    Response over = returnAs(order, body(2, "SEALED", ""), T_POLICY, "CASHIER", CASHIER);
    assertThat(over.getStatus(), is(403));
    String text = over.readEntity(String.class);
    assertThat(text, containsString("CEILING"));
    assertThat(returnRows(T_POLICY, order), is(0L));
    // Under it: one unit is 10.00, and the cashier takes it alone.
    assertThat(
        returnAs(order, body(1, "SEALED", ""), T_POLICY, "CASHIER", CASHIER).getStatus(), is(201));
    // The manager takes the rest, which is 10.00 too: still under. A dearer return is theirs.
    String dear = sale(T_POLICY, STORE, null);
    JsonObject byManager =
        data(returnAs(dear, body(2, "SEALED", ""), T_POLICY, "MANAGER", MANAGER), 201);
    assertThat(byManager.getJsonArray("outsidePolicy").toString(), is("[\"CEILING\"]"));
    assertThat(byManager.getString("approvedBy"), is(MANAGER));

    // The seven-day window, too: a sale ten days old is late.
    String old = sale(T_POLICY, STORE, null);
    backdate(old, 10);
    assertThat(
        returnAs(old, body(1, "SEALED", ""), T_POLICY, "CASHIER", CASHIER).getStatus(), is(403));
  }

  // ── the policy itself ─────────────────────────────────────────────────────

  @Test
  @DisplayName("The policy is management's, read and set per business, default until set")
  void policyIsManagementOnlyAndPerBusiness() {
    // Nothing set: the default, in words the settings screen can show.
    JsonObject none = data(get("/admin/return-policy", OTHER_T, "MANAGER", MANAGER), 200);
    assertThat(none.getInt("windowDays"), is(30));
    assertThat(nullish(none, "cashierCeiling"), is(true));
    assertThat(none.getBoolean("noReceiptAllowed"), is(false));
    assertThat(none.getBoolean("usingDefault"), is(true));

    String valid = "{\"windowDays\":14,\"cashierCeiling\":50,\"noReceiptAllowed\":true}";
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, get("/admin/return-policy", OTHER_T, role, CASHIER).getStatus(), is(403));
      assertThat(
          role,
          as("/admin/return-policy", OTHER_T, role, CASHIER, null)
              .put(Entity.entity(valid, MediaType.APPLICATION_JSON))
              .getStatus(),
          is(403));
    }
    assertThat(get("/admin/return-policy", OTHER_T, "MANAGER", MANAGER).getStatus(), is(200));

    for (String bad :
        new String[] {
          "{\"windowDays\":0,\"noReceiptAllowed\":false}",
          "{\"windowDays\":3651,\"noReceiptAllowed\":false}",
          "{\"windowDays\":30,\"cashierCeiling\":-1,\"noReceiptAllowed\":false}",
          "{\"noReceiptAllowed\":false}",
          "{\"windowDays\":30}"
        }) {
      assertThat(
          bad,
          as("/admin/return-policy", OTHER_T, "MANAGER", MANAGER, null)
              .put(Entity.entity(bad, MediaType.APPLICATION_JSON))
              .getStatus(),
          is(400));
    }
    // Nothing above changed it.
    assertThat(
        data(get("/admin/return-policy", OTHER_T, "OWNER", MANAGER), 200).getInt("windowDays"),
        is(30));

    JsonObject saved =
        data(
            as("/admin/return-policy", OTHER_T, "MANAGER", MANAGER, null)
                .put(Entity.entity(valid, MediaType.APPLICATION_JSON)),
            200);
    assertThat(saved.getInt("windowDays"), is(14));
    assertThat(saved.getJsonNumber("cashierCeiling").bigDecimalValue().intValue(), is(50));
    assertThat(saved.getBoolean("noReceiptAllowed"), is(true));
    assertThat(
        data(get("/admin/return-policy", OTHER_T, "OWNER", MANAGER), 200).getInt("windowDays"),
        is(14));

    // Another business reads its own (the default), never this one.
    JsonObject theirs = data(get("/admin/return-policy", T, "OWNER", MANAGER), 200);
    assertThat(theirs.getInt("windowDays"), is(30));
    assertThat(theirs.getBoolean("usingDefault"), is(true));
  }

  // ── retries ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A retried return answers with the first, once; a missing or bad key is refused")
  void aRetriedReturnAnswersWithTheFirst() {
    String order = sale(T, STORE, null);
    String key = Ids.newId().toString();
    String path = "/orders/" + order + "/returns";

    JsonObject first = data(post(path, body(1, "SEALED", ""), T, "CASHIER", CASHIER, key), 201);
    JsonObject again = data(post(path, body(1, "SEALED", ""), T, "CASHIER", CASHIER, key), 201);
    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat(again.getString("createdAt"), is(first.getString("createdAt")));
    assertThat(returnRows(T, order), is(1L));
    assertThat(events(order, "OrderReturned"), is(1L));

    // The key upper-cased is the same key.
    JsonObject shouted =
        data(post(path, body(1, "SEALED", ""), T, "CASHIER", CASHIER, key.toUpperCase()), 201);
    assertThat(shouted.getString("id"), is(first.getString("id")));

    // A retry is answered from the first even when the world has moved on.
    backdate(order, 90);
    assertThat(
        data(post(path, body(1, "SEALED", ""), T, "CASHIER", CASHIER, key), 201).getString("id"),
        is(first.getString("id")));

    // The same key on another sale is a mistake, not a replay.
    String elsewhere = sale(T, STORE, null);
    Response reused =
        post(
            "/orders/" + elsewhere + "/returns", body(1, "SEALED", ""), T, "CASHIER", CASHIER, key);
    assertThat(reused.getStatus(), is(409));
    assertThat(reused.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REUSED"));
    assertThat(returnRows(T, elsewhere), is(0L));

    Response none = post(path, body(1, "SEALED", ""), T, "CASHIER", CASHIER, null);
    assertThat(none.getStatus(), is(400));
    assertThat(none.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    Response bad = post(path, body(1, "SEALED", ""), T, "CASHIER", CASHIER, "not-a-key");
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("IDEMPOTENCY_KEY_INVALID"));
    assertThat(returnRows(T, order), is(1L));
  }

  @Test
  @DisplayName("Two callers racing with one key make one return")
  void racingRetriesMakeOneReturn() throws Exception {
    String order = sale(T, STORE, null);
    String key = Ids.newId().toString();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 6; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  Response r =
                      post(
                          "/orders/" + order + "/returns",
                          body(1, "SEALED", ""),
                          T,
                          "CASHIER",
                          CASHIER,
                          key);
                  String text = r.readEntity(String.class);
                  assertThat(text, r.getStatus(), is(201));
                  return parse(text).getJsonObject("data").getString("id");
                }));
      }
      start.countDown();
      String id = results.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS);
      for (var f : results) assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS), is(id));
    } finally {
      pool.shutdownNow();
    }
    assertThat(returnRows(T, order), is(1L));
    assertThat(events(order, "OrderReturned"), is(1L));
  }

  // ── refund methods ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Store credit needs a customer on the sale, and the event names them")
  void storeCreditNeedsACustomer() {
    String anonymous = sale(T, STORE, null);
    Response refused =
        returnAs(
            anonymous,
            body(1, "SEALED", ",\"refundMethod\":\"STORE_CREDIT\""),
            T,
            "CASHIER",
            CASHIER);
    assertThat(refused.getStatus(), is(409));
    assertThat(
        refused.readEntity(String.class),
        containsString("ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER"));
    assertThat(returnRows(T, anonymous), is(0L));
    assertThat(events(anonymous, "OrderReturned"), is(0L));

    String customer = Ids.newId().toString();
    String known = sale(T, STORE, customer);
    JsonObject ret =
        data(
            returnAs(
                known,
                body(1, "SEALED", ",\"refundMethod\":\"STORE_CREDIT\""),
                T,
                "CASHIER",
                CASHIER),
            201);
    assertThat(ret.getString("refundMethod"), is("STORE_CREDIT"));
    JsonObject ev = event(known, "OrderReturned");
    assertThat(ev.getString("refundMethod"), is("STORE_CREDIT"));
    assertThat(ev.getString("customerId"), is(customer));
    assertThat(nullish(ev, "giftCardId"), is(true));
  }

  @Test
  @DisplayName("An unknown refund method is refused")
  void anUnknownRefundMethodIsRefused() {
    String order = sale(T, STORE, null);
    Response r =
        returnAs(order, body(1, "SEALED", ",\"refundMethod\":\"CHEQUE\""), T, "CASHIER", CASHIER);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("ORDER_RETURN_METHOD_INVALID"));
    assertThat(returnRows(T, order), is(0L));
    Response code =
        returnAs(order, body(1, "SEALED", ",\"giftCardCode\":\"ABCD\""), T, "CASHIER", CASHIER);
    assertThat(code.getStatus(), is(400));
    assertThat(returnRows(T, order), is(0L));
  }

  @Test
  @DisplayName("A gift card refund issues a new card that can be redeemed, and says so once")
  void giftCardRefundIssuesANewCard() {
    String order = sale(T, STORE, null);
    JsonObject ret =
        data(
            returnAs(
                order, body(1, "SEALED", ",\"refundMethod\":\"GIFT_CARD\""), T, "CASHIER", CASHIER),
            201);
    JsonObject card = ret.getJsonObject("giftCard");
    assertThat(card.getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("10.00")));
    String code = card.getString("code");

    // The card is this business's and redeemable for what was refunded, no more.
    JsonObject read = data(get("/gift-cards/" + code, T, "CASHIER", CASHIER), 200);
    assertThat(read.getString("id"), is(card.getString("id")));
    assertThat(read.getString("currency"), is("USD"));
    JsonObject redeemed =
        data(
            as("/gift-cards/" + code + "/redeem", T, "CASHIER", CASHIER, null)
                .post(Entity.entity("{\"amount\":4.00}", MediaType.APPLICATION_JSON)),
            200);
    assertThat(
        redeemed.getJsonNumber("currentBalance").bigDecimalValue(), is(new BigDecimal("6.00")));
    Response tooMuch =
        as("/gift-cards/" + code + "/redeem", T, "CASHIER", CASHIER, null)
            .post(Entity.entity("{\"amount\":7.00}", MediaType.APPLICATION_JSON));
    assertThat(tooMuch.getStatus(), is(409));

    // A ledger row names the order and the return; the events carry it.
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".gift_card_transactions WHERE tenant_id='"
                + T
                + "' AND gift_card_id='"
                + card.getString("id")
                + "' AND tx_type='ISSUE' AND order_id='"
                + order
                + "' AND reference='"
                + ret.getString("id")
                + "'"),
        is("1"));
    JsonObject loaded = event(card.getString("id"), "GiftCardLoaded");
    assertThat(loaded.getString("paidBy"), is("RETURN"));
    assertThat(loaded.getString("returnId"), is(ret.getString("id")));
    assertThat(loaded.getString("kind"), is("ISSUE"));
    assertThat(loaded.getString("giftCardId"), is(card.getString("id")));
    assertThat(loaded.getString("currency"), is("USD"));
    assertThat(loaded.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("10.00")));
    assertThat(loaded.containsKey("transactionId"), is(true));
    JsonObject ev = event(order, "OrderReturned");
    assertThat(ev.getString("giftCardId"), is(card.getString("id")));
    assertThat(ev.getString("refundMethod"), is("GIFT_CARD"));

    // The listing of returns never shows the card's code.
    String listed =
        get("/orders/" + order + "/returns", T, "CASHIER", CASHIER).readEntity(String.class);
    assertThat(listed, not(containsString(code)));
  }

  @Test
  @DisplayName("A gift card refund tops up a named card of this business, and no other")
  void giftCardRefundTopsUpANamedCard() {
    // A card this business already holds, with 5.00 on it.
    JsonObject mine =
        data(
            post(
                "/gift-cards",
                "{\"storeId\":\"" + STORE + "\",\"amount\":5.00,\"paidBy\":\"CASH\"}",
                T,
                "MANAGER",
                MANAGER,
                null),
            201);
    String code = mine.getString("code");

    String order = sale(T, STORE, null);
    JsonObject ret =
        data(
            returnAs(
                order,
                body(
                    1,
                    "SEALED",
                    ",\"refundMethod\":\"GIFT_CARD\",\"giftCardCode\":\"" + code + "\""),
                T,
                "CASHIER",
                CASHIER),
            201);
    assertThat(ret.getJsonObject("giftCard").getString("id"), is(mine.getString("id")));
    assertThat(
        ret.getJsonObject("giftCard").getJsonNumber("balance").bigDecimalValue(),
        is(new BigDecimal("15.00")));
    assertThat(
        data(get("/gift-cards/" + code, T, "CASHIER", CASHIER), 200)
            .getJsonNumber("currentBalance")
            .bigDecimalValue(),
        is(new BigDecimal("15.00")));
    JsonObject loaded = event(mine.getString("id"), "GiftCardLoaded");
    assertThat(loaded.getString("paidBy"), is("RETURN"));
    assertThat(loaded.getString("returnId"), is(ret.getString("id")));
    assertThat(loaded.getString("kind"), is("RELOAD"));
    assertThat(loaded.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("10.00")));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".gift_card_transactions WHERE tenant_id='"
                + T
                + "' AND gift_card_id='"
                + mine.getString("id")
                + "' AND tx_type='RELOAD' AND reference='"
                + ret.getString("id")
                + "'"),
        is("1"));

    // Another business's card is not found, and the return is not made.
    JsonObject theirs =
        data(
            post(
                "/gift-cards",
                "{\"storeId\":\"" + OTHER_T_STORE + "\",\"amount\":5.00,\"paidBy\":\"CASH\"}",
                OTHER_T,
                "MANAGER",
                MANAGER,
                null),
            201);
    String second = sale(T, STORE, null);
    Response foreign =
        returnAs(
            second,
            body(
                1,
                "SEALED",
                ",\"refundMethod\":\"GIFT_CARD\",\"giftCardCode\":\""
                    + theirs.getString("code")
                    + "\""),
            T,
            "CASHIER",
            CASHIER);
    assertThat(foreign.getStatus(), is(404));
    assertThat(foreign.readEntity(String.class), containsString("GIFT_CARD_NOT_FOUND"));
    Response unknown =
        returnAs(
            second,
            body(1, "SEALED", ",\"refundMethod\":\"GIFT_CARD\",\"giftCardCode\":\"NOPE-NOPE\""),
            T,
            "CASHIER",
            CASHIER);
    assertThat(unknown.getStatus(), is(404));
    assertThat(returnRows(T, second), is(0L));
    assertThat(events(second, "OrderReturned"), is(0L));
    // Their card was not touched.
    assertThat(
        data(get("/gift-cards/" + theirs.getString("code"), OTHER_T, "CASHIER", CASHIER), 200)
            .getJsonNumber("currentBalance")
            .bigDecimalValue(),
        is(new BigDecimal("5.00")));
  }

  @Test
  @DisplayName("A gift card return replayed with its key adds the value once")
  void aReplayedGiftCardReturnCreditsOnce() {
    String order = sale(T, STORE, null);
    String key = Ids.newId().toString();
    String path = "/orders/" + order + "/returns";
    String json = body(1, "SEALED", ",\"refundMethod\":\"GIFT_CARD\"");
    JsonObject first = data(post(path, json, T, "CASHIER", CASHIER, key), 201);
    JsonObject again = data(post(path, json, T, "CASHIER", CASHIER, key), 201);
    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat(
        again.getJsonObject("giftCard").getString("code"),
        is(first.getJsonObject("giftCard").getString("code")));
    assertThat(
        again.getJsonObject("giftCard").getJsonNumber("balance").bigDecimalValue(),
        is(new BigDecimal("10.00")));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".gift_card_transactions WHERE tenant_id='"
                + T
                + "' AND order_id='"
                + order
                + "'"),
        is("1"));
  }

  // ── another business ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Another business's staff of every role cannot return or void our sale")
  void anotherBusinessCannotReachOurSale() {
    String order = sale(T, STORE, null);
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "MANAGER", "OWNER"}) {
      // Even naming our store and our order.
      var ret =
          as("/orders/" + order + "/returns", OTHER_T, role, Ids.newId().toString(), STORE)
              .header("Idempotency-Key", Ids.newId().toString())
              .post(Entity.entity(body(1, "SEALED", ""), MediaType.APPLICATION_JSON));
      assertThat(role, ret.getStatus(), is(404));
      var voided =
          as("/orders/" + order + "/void", OTHER_T, role, Ids.newId().toString(), STORE)
              .header("Idempotency-Key", Ids.newId().toString())
              .post(Entity.entity("{\"reason\":\"not mine\"}", MediaType.APPLICATION_JSON));
      assertThat(role, voided.getStatus() == 404 || voided.getStatus() == 403, is(true));
      assertThat(
          role,
          get("/orders/" + order + "/returns", OTHER_T, role, Ids.newId().toString()).getStatus(),
          is(404));
    }
    assertThat(returnRows(T, order), is(0L));
    assertThat(returnRows(OTHER_T, order), is(0L));
    assertThat(events(order, "OrderReturned"), is(0L));
    assertThat(events(order, "OrderVoided"), is(0L));
    assertThat(statusOf(order), is("FULFILLED"));

    // A key used by the other business is not a replay for ours.
    String key = Ids.newId().toString();
    Response theirs =
        post(
            "/orders/" + order + "/returns", body(1, "SEALED", ""), OTHER_T, "OWNER", MANAGER, key);
    assertThat(theirs.getStatus(), is(404));
    assertThat(
        data(
                post(
                    "/orders/" + order + "/returns",
                    body(1, "SEALED", ""),
                    T,
                    "OWNER",
                    MANAGER,
                    key),
                201)
            .getString("orderId"),
        is(order));
  }

  // ── voids ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A retried void answers with the first, once; the event names the customer")
  void aRetriedVoidAnswersWithTheFirst() {
    String customer = Ids.newId().toString();
    String order = sale(T, STORE, customer);
    String path = "/orders/" + order + "/void";
    String why = "{\"reason\":\"rang up twice\"}";

    Response none = post(path, why, T, "MANAGER", MANAGER, null);
    assertThat(none.getStatus(), is(400));
    assertThat(none.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    assertThat(
        post(path, why, T, "MANAGER", MANAGER, "nope").readEntity(String.class),
        containsString("IDEMPOTENCY_KEY_INVALID"));
    assertThat(statusOf(order), is("FULFILLED"));
    assertThat(events(order, "OrderVoided"), is(0L));

    String key = Ids.newId().toString();
    JsonObject first = data(post(path, why, T, "MANAGER", MANAGER, key), 200);
    JsonObject again = data(post(path, why, T, "MANAGER", MANAGER, key), 200);
    assertThat(again.getString("voidedAt"), is(first.getString("voidedAt")));
    assertThat(again.getString("reason"), is("rang up twice"));
    assertThat(statusOf(order), is("VOIDED"));
    assertThat(events(order, "OrderVoided"), is(1L));
    assertThat(count("pos_void_log", T, "order_id", order), is(1L));
    assertThat(event(order, "OrderVoided").getString("customerId"), is(customer));

    // A different key on the voided sale is a second void, and refused.
    Response other = post(path, why, T, "MANAGER", MANAGER, Ids.newId().toString());
    assertThat(other.getStatus(), is(409));
    assertThat(other.readEntity(String.class), containsString("ORDER_CANNOT_VOID"));
    assertThat(events(order, "OrderVoided"), is(1L));

    // The same key on another sale is a mistake, not a replay.
    String elsewhere = sale(T, STORE, null);
    Response reused = post("/orders/" + elsewhere + "/void", why, T, "MANAGER", MANAGER, key);
    assertThat(reused.getStatus(), is(409));
    assertThat(statusOf(elsewhere), is("FULFILLED"));

    // A sale with no customer voids with a null one.
    String walkIn = sale(T, STORE, null);
    data(
        post("/orders/" + walkIn + "/void", why, T, "MANAGER", MANAGER, Ids.newId().toString()),
        200);
    assertThat(nullish(event(walkIn, "OrderVoided"), "customerId"), is(true));
  }
}
