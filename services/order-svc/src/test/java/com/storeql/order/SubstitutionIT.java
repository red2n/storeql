package com.storeql.order;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Substitutions for out-of-stock online lines, against real Postgres: a line the store cannot fill
 * is closed short — the order owes less, the money goes back — or, where the shopper allowed it,
 * replaced by a substitute charged at no more than the original and picked at once; the stand-ins
 * the business declared are suggested with what the store has; the store reads what it still owes;
 * a key closes once; and another business sees none of it and changes nothing, even naming our
 * store.
 */
@HelidonTest
class SubstitutionIT {

  private static final String T = "01a0d940-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0d940-611e-702c-a97b-d1b8025478e2";
  private static final String S = "01a0d940-611e-703c-a378-a4972ea461e1";
  private static final String S2 = "01a0d940-611e-703c-a378-a4972ea461e2";

  /** Apples, the line that runs short. */
  private static final String V = "01a0d940-611e-7037-a4b7-c854f0266ae1";

  /** Pears, the declared stand-in, five on the shelf. */
  private static final String V2 = "01a0d940-611e-7037-a4b7-c854f0266ae2";

  /** Plums, declared but none on the shelf. */
  private static final String V3 = "01a0d940-611e-7037-a4b7-c854f0266ae3";

  /** A bag: related, but not as a substitute. */
  private static final String V4 = "01a0d940-611e-7037-a4b7-c854f0266ae4";

  private static final String SHOPPER = "01a0d940-611e-700b-bde4-50df0324c3e1";
  private static final String STRANGER = "01a0d940-611e-700b-bde4-50df0324c3e2";
  private static final String STAFF = "01a0d940-611e-700b-bde4-50df0324c3e3";

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub PEERS;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .with(T2, "GBP", "GB")
            .withStoreAt(T, S, 53.8008, -1.5491)
            .withStoreAt(T, S2, 53.9600, -1.0873);
    // inventory-svc says what the shelf holds; product-svc names the products and their stand-ins.
    PEERS =
        JsonStub.start("inventory-svc", "product-svc")
            .on(
                "GET",
                "/admin/inventory/network/stock",
                200,
                "{\"data\":{\"levels\":[{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + V2
                    + "\",\"available\":5}],\"dropship\":[]}}")
            .on(
                "GET",
                "/admin/products/variants/" + V + "/relationships",
                200,
                "{\"data\":[{\"relationshipType\":\"SUBSTITUTE\",\"relatedVariantId\":\""
                    + V2
                    + "\"},{\"relationshipType\":\"SUBSTITUTE\",\"relatedVariantId\":\""
                    + V3
                    + "\"},{\"relationshipType\":\"ACCESSORY\",\"relatedVariantId\":\""
                    + V4
                    + "\"}]}")
            .on(
                "GET",
                "/admin/products/variants/resolve",
                200,
                "{\"data\":[{\"variantId\":\""
                    + V
                    + "\",\"productName\":\"Apples\",\"sku\":\"APL\"},{\"variantId\":\""
                    + V2
                    + "\",\"productName\":\"Pears\",\"sku\":\"PEA\"},{\"variantId\":\""
                    + V3
                    + "\",\"productName\":\"Plums\",\"sku\":\"PLU\"}]}");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService svc;

  @AfterAll
  static void stop() {
    PEERS.close();
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String storeIds,
      String key) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("Idempotency-Key", key == null ? Ids.newId().toString() : key);
    if (user != null) b = b.header("X-User-Id", user);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return "GET".equals(method)
        ? b.get()
        : b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
  }

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String storeIds) {
    return call(method, path, json, tenant, user, roles, storeIds, null);
  }

  private Response asOwner(String method, String path, String json) {
    return call(method, path, json, T, STAFF, "OWNER", null);
  }

  /** Staff at S, as a cashier: the picker. */
  private Response asPicker(String method, String path, String json) {
    return call(method, path, json, T, STAFF, "CASHIER", S);
  }

  private static String code(Response r, int status) {
    return Envelopes.parse(Envelopes.bodyOf(r, status)).getString("code");
  }

  private static BigDecimal money(JsonObject o, String key) {
    return o.getJsonNumber(key).bigDecimalValue();
  }

  /**
   * The shopper places an online order at S for {@code qty} apples at 10.00, a delivery or a
   * pickup.
   */
  private UUID place(String fulfilment, Boolean allowSubstitutions, int qty) {
    boolean delivery = "DELIVERY".equals(fulfilment);
    String body =
        "{\"storeId\":\""
            + S
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\""
            + fulfilment
            + "\",\"currency\":\"GBP\",\"contactPhone\":\"07700900123\",\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":"
            + qty
            + ",\"unitPrice\":10.00}]"
            + (allowSubstitutions == null ? "" : ",\"allowSubstitutions\":" + allowSubstitutions)
            + (delivery
                ? ",\"deliveryLine1\":\"1 Park Row\",\"deliveryCity\":\"Leeds\","
                    + "\"deliveryPostalCode\":\"LS1 5AB\",\"deliveryRecipientName\":\"Sam Shopper\","
                    + "\"deliveryRecipientPhone\":\"07700900123\""
                : "")
            + "}";
    JsonObject placed =
        Envelopes.created(call("POST", "/orders", body, T, SHOPPER, "CUSTOMER", null));
    return Ids.parse(placed.getString("id"));
  }

  private UUID confirmed(String fulfilment, Boolean allowSubstitutions, int qty) {
    UUID id = place(fulfilment, allowSubstitutions, qty);
    assertThat(asOwner("POST", "/orders/" + id + "/confirm", "{}").getStatus(), is(200));
    return id;
  }

  private void pick(UUID id, int qty) {
    assertThat(
        asOwner(
                "POST",
                "/orders/" + id + "/fulfil",
                "{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":" + qty + "}]}")
            .getStatus(),
        is(200));
  }

  private JsonObject order(UUID id) {
    return Envelopes.ok(asOwner("GET", "/orders/" + id, null));
  }

  private static JsonObject line(JsonObject order, String variantId) {
    return order.getJsonArray("items").stream()
        .map(JsonValue::asJsonObject)
        .filter(i -> variantId.equals(i.getString("variantId")))
        .findFirst()
        .orElseThrow();
  }

  private static String shortPath(UUID id) {
    return "/orders/" + id + "/lines/" + V + "/short";
  }

  private static String substitutePath(UUID id) {
    return "/orders/" + id + "/lines/" + V + "/substitute";
  }

  private static String suggestionsPath(UUID id) {
    return "/orders/" + id + "/lines/" + V + "/substitutes";
  }

  private static String substitute(String variantId, String unitPrice, Integer qty) {
    return "{\"substituteVariantId\":\""
        + variantId
        + "\""
        + (unitPrice == null ? "" : ",\"unitPrice\":" + unitPrice)
        + (qty == null ? "" : ",\"qty\":" + qty)
        + "}";
  }

  private static String event(String type, UUID orderId) {
    return scalar(
        PG,
        "SELECT payload FROM \"order\".outbox WHERE event_type = '"
            + type
            + "' AND aggregate_id = '"
            + orderId
            + "' ORDER BY created_at DESC LIMIT 1");
  }

  private static String adjustments(UUID orderId) {
    return scalar(
        PG,
        "SELECT count(*) FROM \"order\".order_line_adjustments WHERE order_id = '" + orderId + "'");
  }

  // ── closing short ──────────────────────────────────────────────────────────

  @Test
  void aLineIsClosedShortTheOrderOwesLessAndTheMoneyGoesBack() {
    UUID id = confirmed("DELIVERY", null, 2);
    JsonObject after =
        Envelopes.ok(
            asPicker("POST", shortPath(id), "{\"qty\":1,\"reason\":\"last one bruised\"}"));
    assertThat(money(after, "total"), comparesEqualTo(new BigDecimal("10.00")));
    assertThat(after.getString("status"), is("CONFIRMED"));
    JsonObject apples = line(after, V);
    assertThat(money(apples, "shortQty"), comparesEqualTo(BigDecimal.ONE));
    assertThat(money(apples, "outstandingQty"), comparesEqualTo(BigDecimal.ONE));
    assertThat(money(apples, "lineTotal"), comparesEqualTo(new BigDecimal("10.00")));
    // The shopper is told, by name, and refunded the apple they will not get.
    String told = event("OrderLineShortClosed", id);
    assertThat(told, containsString("\"variantName\":\"Apples\""));
    assertThat(told, containsString("\"refundAmount\":10.00"));
    assertThat(told, containsString("\"orderTotal\":10.00"));
    assertThat(told, containsString("\"loginId\":\"" + SHOPPER + "\""));
    assertThat(told, containsString("\"fulfilmentType\":\"DELIVERY\""));
    JsonArray history = Envelopes.okArray(asOwner("GET", "/orders/" + id + "/history", null));
    assertThat(history.toString(), containsString("short: 1 × " + V));
    assertThat(history.toString(), containsString("last one bruised"));
    assertThat(adjustments(id), is("1"));
    assertThat(
        scalar(
            PG,
            "SELECT kind || ' ' || refund_amount || ' ' || COALESCE(reason, '') FROM"
                + " \"order\".order_line_adjustments WHERE order_id = '"
                + id
                + "'"),
        is("SHORT_CLOSED 10.00 last one bruised"));
    // The other apple is picked: every line is picked or closed, so the order is FULFILLED.
    pick(id, 1);
    JsonObject done = order(id);
    assertThat(done.getString("status"), is("FULFILLED"));
    assertThat(money(done, "total"), comparesEqualTo(new BigDecimal("10.00")));
    // And a picked order has no line left to close.
    assertThat(code(asPicker("POST", shortPath(id), "{}"), 409), is("ORDER_LINE_NOT_ADJUSTABLE"));
  }

  @Test
  void aCloseIsRefusedBeyondWhatIsOwedOffTheOrderAndOffAnOnlineOrderStillOwing() {
    UUID id = confirmed("PICKUP", null, 2);
    assertThat(
        code(asPicker("POST", shortPath(id), "{\"qty\":3}"), 409),
        is("ORDER_LINE_QTY_EXCEEDS_OUTSTANDING"));
    assertThat(asPicker("POST", shortPath(id), "{\"qty\":0}").getStatus(), is(400));
    assertThat(
        code(asPicker("POST", "/orders/" + id + "/lines/" + V4 + "/short", "{}"), 400),
        is("ORDER_LINE_UNKNOWN"));
    // Not yet paid for: nothing to close.
    UUID pending = place("DELIVERY", null, 1);
    assertThat(
        code(asPicker("POST", shortPath(pending), "{}"), 409), is("ORDER_LINE_NOT_ADJUSTABLE"));
    // A till sale is handed over as it is paid; it has no line to close.
    JsonObject till =
        Envelopes.created(
            asOwner(
                "POST",
                "/orders",
                "{\"storeId\":\""
                    + S
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"currency\":\"GBP\","
                    + "\"items\":[{\"variantId\":\""
                    + V
                    + "\",\"qty\":1,\"unitPrice\":10.00}]}"));
    UUID tillId = Ids.parse(till.getString("id"));
    assertThat(asOwner("POST", "/orders/" + tillId + "/confirm", "{}").getStatus(), is(200));
    assertThat(
        code(asPicker("POST", shortPath(tillId), "{}"), 409), is("ORDER_LINE_NOT_ADJUSTABLE"));
    // With no quantity, everything the line still owes is closed; the order is then complete.
    pick(id, 1);
    JsonObject closed = Envelopes.ok(asPicker("POST", shortPath(id), null));
    assertThat(closed.getString("status"), is("FULFILLED"));
    assertThat(money(line(closed, V), "outstandingQty"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(money(closed, "total"), comparesEqualTo(new BigDecimal("10.00")));
  }

  // ── substituting ───────────────────────────────────────────────────────────

  @Test
  void aSubstituteIsPutInTheBagAtNoMoreThanTheOriginalsPriceAndPickedAtOnce() {
    UUID id = confirmed("PICKUP", null, 2);
    String applesItem = line(order(id), V).getString("id");
    // Pears are 12.00 at the store; the shopper paid 10.00 an apple and pays no more.
    JsonObject after =
        Envelopes.ok(asPicker("POST", substitutePath(id), substitute(V2, "12.00", null)));
    assertThat(after.getString("status"), is("FULFILLED"));
    assertThat(money(after, "total"), comparesEqualTo(new BigDecimal("20.00")));
    JsonObject apples = line(after, V);
    assertThat(money(apples, "shortQty"), comparesEqualTo(new BigDecimal("2")));
    assertThat(money(apples, "outstandingQty"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(money(apples, "lineTotal"), comparesEqualTo(BigDecimal.ZERO));
    JsonObject pears = line(after, V2);
    assertThat(money(pears, "qty"), comparesEqualTo(new BigDecimal("2")));
    assertThat(money(pears, "lineTotal"), comparesEqualTo(new BigDecimal("20.00")));
    assertThat(money(pears, "unitPrice"), comparesEqualTo(new BigDecimal("10.00")));
    assertThat(money(pears, "outstandingQty"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(pears.getString("substitutesItemId"), is(applesItem));
    // The pears are deducted and their revenue recorded as any picked line is; the swap is told.
    String picked = event("OrderFulfilled", id);
    assertThat(picked, containsString("\"variantId\":\"" + V2 + "\""));
    assertThat(picked, containsString("\"status\":\"FULFILLED\""));
    String swapped = event("OrderLineSubstituted", id);
    assertThat(swapped, containsString("\"fromName\":\"Apples\""));
    assertThat(swapped, containsString("\"toName\":\"Pears\""));
    assertThat(swapped, containsString("\"chargedAmount\":20.00"));
    assertThat(swapped, containsString("\"refundAmount\":0.00"));
    assertThat(
        scalar(
            PG,
            "SELECT kind || ' ' || charged_amount || ' ' || refund_amount FROM"
                + " \"order\".order_line_adjustments WHERE order_id = '"
                + id
                + "'"),
        is("SUBSTITUTED 20.00 0.00"));
    JsonArray history = Envelopes.okArray(asOwner("GET", "/orders/" + id + "/history", null));
    assertThat(history.toString(), containsString("substituted: 2 × " + V + " → " + V2));

    // A cheaper substitute for one of two: charged its own price, the difference refunded, the
    // other apple still owed.
    UUID part = confirmed("DELIVERY", null, 2);
    JsonObject half =
        Envelopes.ok(asPicker("POST", substitutePath(part), substitute(V2, "8.00", 1)));
    assertThat(half.getString("status"), is("PARTIALLY_FULFILLED"));
    assertThat(money(half, "total"), comparesEqualTo(new BigDecimal("18.00")));
    assertThat(money(line(half, V), "outstandingQty"), comparesEqualTo(BigDecimal.ONE));
    assertThat(money(line(half, V), "lineTotal"), comparesEqualTo(new BigDecimal("10.00")));
    assertThat(money(line(half, V2), "lineTotal"), comparesEqualTo(new BigDecimal("8.00")));
    String cheaper = event("OrderLineSubstituted", part);
    assertThat(cheaper, containsString("\"chargedAmount\":8.00"));
    assertThat(cheaper, containsString("\"refundAmount\":2.00"));
    // Then the last apple is closed short: complete, and 8.00 is all the shopper pays.
    JsonObject done = Envelopes.ok(asPicker("POST", shortPath(part), "{}"));
    assertThat(done.getString("status"), is("FULFILLED"));
    assertThat(money(done, "total"), comparesEqualTo(new BigDecimal("8.00")));
    assertThat(adjustments(part), is("2"));
  }

  @Test
  void theShoppersNoIsFinalAndABadSubstituteIsRefused() {
    UUID no = confirmed("DELIVERY", false, 1);
    assertThat(order(no).getBoolean("allowSubstitutions"), is(false));
    assertThat(
        code(asPicker("POST", substitutePath(no), substitute(V2, "8.00", null)), 409),
        is("ORDER_SUBSTITUTION_NOT_ALLOWED"));
    // Closing short is always open to the store.
    assertThat(asPicker("POST", shortPath(no), "{}").getStatus(), is(200));

    UUID yes = confirmed("DELIVERY", null, 1);
    assertThat(order(yes).getBoolean("allowSubstitutions"), is(true));
    assertThat(
        code(asPicker("POST", substitutePath(yes), substitute(V, "8.00", null)), 400),
        is("ORDER_SUBSTITUTE_SAME_VARIANT"));
    // Plums are declared, but the shelf is empty.
    assertThat(
        code(asPicker("POST", substitutePath(yes), substitute(V3, "8.00", null)), 409),
        is("ORDER_SUBSTITUTE_NOT_SELLABLE"));
    // With server-side pricing off, the store must say what the substitute costs.
    assertThat(
        code(asPicker("POST", substitutePath(yes), substitute(V2, null, null)), 400),
        is("ORDER_PRICE_REQUIRED"));
    assertThat(
        code(asPicker("POST", substitutePath(yes), substitute(V2, "8.00", 3)), 409),
        is("ORDER_LINE_QTY_EXCEEDS_OUTSTANDING"));
    assertThat(asPicker("POST", substitutePath(yes), "{}").getStatus(), is(400));
    // Nothing of that landed.
    JsonObject still = order(yes);
    assertThat(still.getString("status"), is("CONFIRMED"));
    assertThat(still.getJsonArray("items"), hasSize(1));
    assertThat(adjustments(yes), is("0"));
  }

  @Test
  void theStandInsTheBusinessDeclaredAreSuggestedWithWhatTheStoreHas() {
    UUID id = confirmed("DELIVERY", null, 1);
    JsonArray suggested =
        Envelopes.okArray(call("GET", suggestionsPath(id), null, T, STAFF, "STOREKEEPER", S));
    assertThat(suggested, hasSize(2));
    JsonObject pears = suggested.getJsonObject(0);
    assertThat(pears.getString("variantId"), is(V2));
    assertThat(pears.getString("productName"), is("Pears"));
    assertThat(pears.getString("sku"), is("PEA"));
    assertThat(money(pears, "available"), comparesEqualTo(new BigDecimal("5")));
    JsonObject plums = suggested.getJsonObject(1);
    assertThat(plums.getString("variantId"), is(V3));
    assertThat(money(plums, "available"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(suggested.toString(), not(containsString(V4)));
  }

  // ── the queue, and once ────────────────────────────────────────────────────

  @Test
  void theStoreReadsWhatItStillOwesAndAKeyClosesOnce() {
    UUID owing = confirmed("DELIVERY", null, 2);
    UUID noSubs = confirmed("PICKUP", false, 1);
    UUID pending = place("DELIVERY", null, 1);
    UUID picked = confirmed("PICKUP", null, 1);
    pick(picked, 1);
    pick(owing, 1);
    JsonArray queue = Envelopes.okArray(asPicker("GET", "/orders/owing?store=" + S, null));
    List<String> ids = queue.stream().map(v -> v.asJsonObject().getString("orderId")).toList();
    assertThat(ids, org.hamcrest.Matchers.hasItems(owing.toString(), noSubs.toString()));
    assertThat(ids, not(org.hamcrest.Matchers.hasItem(pending.toString())));
    assertThat(ids, not(org.hamcrest.Matchers.hasItem(picked.toString())));
    JsonObject row = Envelopes.find(queue, "orderId", owing.toString());
    assertThat(row.getString("status"), is("PARTIALLY_FULFILLED"));
    assertThat(row.getBoolean("allowSubstitutions"), is(true));
    JsonObject owed = row.getJsonArray("lines").getJsonObject(0);
    assertThat(owed.getString("variantId"), is(V));
    assertThat(money(owed, "outstandingQty"), comparesEqualTo(BigDecimal.ONE));
    assertThat(
        Envelopes.find(queue, "orderId", noSubs.toString()).getBoolean("allowSubstitutions"),
        is(false));
    assertThat(asPicker("GET", "/orders/owing", null).getStatus(), is(400));

    // The same key twice closes once: the second answer is the order as the first left it.
    String key = Ids.newId().toString();
    JsonObject first =
        Envelopes.ok(call("POST", shortPath(owing), "{\"qty\":1}", T, STAFF, "CASHIER", S, key));
    JsonObject again =
        Envelopes.ok(call("POST", shortPath(owing), "{\"qty\":1}", T, STAFF, "CASHIER", S, key));
    assertThat(first.getString("status"), is("FULFILLED"));
    assertThat(again.getString("status"), is("FULFILLED"));
    assertThat(money(again, "total"), comparesEqualTo(new BigDecimal("10.00")));
    assertThat(adjustments(owing), is("1"));
    assertThat(
        Envelopes.okArray(asPicker("GET", "/orders/owing?store=" + S, null)).toString(),
        not(containsString(owing.toString())));
  }

  /**
   * The refund payment-svc makes for a close or a substitution comes back as a {@code
   * PaymentRefunded} of kind ORDER_ADJUSTMENT: the money is recorded and the order keeps its
   * status, where a plain refund would move it towards REFUNDED.
   */
  @Test
  void anAdjustmentRefundIsRecordedWithoutMovingTheOrder() {
    UUID id = confirmed("PICKUP", null, 2);
    assertThat(
        asPicker("POST", substitutePath(id), substitute(V2, "8.00", null)).getStatus(), is(200));
    UUID tenant = Ids.parse(T);
    svc.applyRefund(Ids.newId(), tenant, id, new BigDecimal("4.00"), true);
    JsonObject kept = order(id);
    assertThat(kept.getString("status"), is("FULFILLED"));
    assertThat(
        Envelopes.okArray(asOwner("GET", "/orders/" + id + "/history", null)).toString(),
        containsString("refunded: 4.00"));
    svc.applyRefund(Ids.newId(), tenant, id, new BigDecimal("16.00"), false);
    assertThat(order(id).getString("status"), is("REFUNDED"));
  }

  // ── whose it is ────────────────────────────────────────────────────────────

  @Test
  void anotherBusinessSeesNothingAndChangesNothingEvenNamingOurStore() {
    UUID id = confirmed("DELIVERY", null, 2);
    String pears = substitute(V2, "8.00", null);
    // Reads and writes by the other business's staff, whatever their role, even with our store's
    // id in their scope: the order does not exist for them (404, never 403 or 409).
    for (String roles : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER"}) {
      assertThat(
          roles,
          code(call("POST", shortPath(id), "{}", T2, STAFF, roles, S), 404),
          is("ORDER_NOT_FOUND"));
      assertThat(
          roles,
          code(call("POST", substitutePath(id), pears, T2, STAFF, roles, S), 404),
          is("ORDER_NOT_FOUND"));
      assertThat(
          roles,
          code(call("GET", suggestionsPath(id), null, T2, STAFF, roles, S), 404),
          is("ORDER_NOT_FOUND"));
      assertThat(
          roles,
          Envelopes.okArray(call("GET", "/orders/owing?store=" + S, null, T2, STAFF, roles, S)),
          hasSize(0));
    }
    // Our own staff assigned to another store, and a shopper, are refused too.
    assertThat(
        code(call("POST", shortPath(id), "{}", T, STAFF, "CASHIER", S2), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(call("POST", substitutePath(id), pears, T, STAFF, "STOREKEEPER", S2), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(call("GET", suggestionsPath(id), null, T, STAFF, "CASHIER", S2), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(call("GET", "/orders/owing?store=" + S, null, T, STAFF, "CASHIER", S2), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        call("POST", shortPath(id), "{}", T, SHOPPER, "CUSTOMER", null).getStatus(), is(403));
    assertThat(
        call("POST", substitutePath(id), pears, T, SHOPPER, "CUSTOMER", null).getStatus(), is(403));
    assertThat(
        call("GET", "/orders/owing?store=" + S, null, T, STRANGER, "CUSTOMER", null).getStatus(),
        is(403));
    // Nothing of ours moved, and nothing was written under them.
    JsonObject still = order(id);
    assertThat(still.getString("status"), is("CONFIRMED"));
    assertThat(money(still, "total"), comparesEqualTo(new BigDecimal("20.00")));
    assertThat(money(line(still, V), "shortQty"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(still.getJsonArray("items"), hasSize(1));
    assertThat(adjustments(id), is("0"));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".order_line_adjustments WHERE tenant_id = '" + T2 + "'"),
        is("0"));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".outbox WHERE aggregate_id = '"
                + id
                + "' AND event_type IN ('OrderLineShortClosed', 'OrderLineSubstituted')"),
        is("0"));
    assertThat(
        Envelopes.okArray(call("GET", "/orders/mine", null, T2, SHOPPER, "CUSTOMER", null)),
        hasSize(0));
    // Then the store closes it, and the other business still reads none of it.
    assertThat(asPicker("POST", shortPath(id), "{\"qty\":1}").getStatus(), is(200));
    assertThat(
        call("GET", "/orders/" + id + "/history", null, T2, STAFF, "OWNER", S).getStatus(),
        is(404));
    assertThat(
        Envelopes.ok(call("GET", "/orders/" + id, null, T, SHOPPER, "CUSTOMER", null))
            .getJsonArray("items")
            .getJsonObject(0)
            .get("shortQty"),
        not(nullValue()));
  }
}
