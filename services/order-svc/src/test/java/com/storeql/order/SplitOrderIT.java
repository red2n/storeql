package com.storeql.order;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Order orchestration and split fulfilment against real Postgres: an online delivery order the
 * delivery-area store cannot fill alone is placed as a group of orders at the shops that can, each
 * with its own lines, holds and total; nothing that adds up is refused; the key places the group
 * once; one shop refusing a hold releases the others; a pickup order is never split; and the group
 * is read by its shopper and the business's staff only. inventory-svc is a stub that says what each
 * store holds and takes the holds; tenant-svc a stub that says where the stores are.
 */
@HelidonTest
class SplitOrderIT {

  private static final String T = "01a0d910-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0d910-611e-702c-a97b-d1b8025478e2";
  private static final String LEEDS = "01a0d910-611e-703c-a378-a4972ea461e1";
  private static final String YORK = "01a0d910-611e-703c-a378-a4972ea461e2";
  private static final String HULL = "01a0d910-611e-703c-a378-a4972ea461e3";
  private static final String DC = "01a0d910-611e-703c-a378-a4972ea461d1";
  private static final String APPLES = "01a0d910-611e-7037-a4b7-c854f0266ae1";
  private static final String PEARS = "01a0d910-611e-7037-a4b7-c854f0266ae2";
  private static final String SHOPPER = "01a0d910-611e-700b-bde4-50df0324c3e1";
  private static final String STRANGER = "01a0d910-611e-700b-bde4-50df0324c3e2";

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub INVENTORY;

  /** What each store holds, as inventory-svc's stock by store answers; set by each test. */
  private static volatile String stock = "{\"levels\":[],\"dropship\":[]}";

  /** The stores whose holds inventory-svc refuses, as a sold-out shelf would. */
  private static final Set<String> REFUSING = ConcurrentHashMap.newKeySet();

  /** What the suite found, put back when it ends: system properties outlive a test class. */
  private static final String ENFORCE_BEFORE =
      System.getProperty("storeql.order.inventory.reserve-enforce");

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .with(T2, "GBP", "GB")
            .withStoreAt(T, LEEDS, 53.8008, -1.5491)
            .withStoreAt(T, YORK, 53.9600, -1.0873)
            .withStoreAt(T, HULL, 53.7676, -0.3274)
            .withWarehouse(T, DC);
    INVENTORY = JsonStub.start("inventory-svc");
    INVENTORY.on(
        "GET",
        "/admin/inventory/network/stock",
        call -> new JsonStub.Answer(200, "{\"data\":" + stock + "}"));
    INVENTORY.on(
        "POST",
        "/inventory/reservations",
        call -> {
          JsonObject hold = Json.createReader(new StringReader(call.body())).readObject();
          if (REFUSING.contains(hold.getString("storeId"))) {
            return new JsonStub.Answer(
                409,
                "{\"error\":{\"code\":\"INVENTORY_INSUFFICIENT_STOCK\",\"message\":\"sold out\"}}");
          }
          return new JsonStub.Answer(201, "{\"data\":{\"id\":\"" + Ids.newId() + "\"}}");
        });
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    // Holds on: routing is part of placing a held online order.
    System.setProperty("storeql.order.inventory.reserve-enforce", "true");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    INVENTORY.close();
    System.clearProperty("storeql.clients.inventory-svc.url");
    if (ENFORCE_BEFORE == null) System.clearProperty("storeql.order.inventory.reserve-enforce");
    else System.setProperty("storeql.order.inventory.reserve-enforce", ENFORCE_BEFORE);
    PG.stop();
  }

  @BeforeEach
  void reset() {
    INVENTORY.reset();
    REFUSING.clear();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static String level(String store, String variant, int qty) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"available\":"
        + qty
        + "}";
  }

  private static void holds(String... levels) {
    stock = "{\"levels\":[" + String.join(",", levels) + "],\"dropship\":[]}";
  }

  private static String line(String variant, int qty, String unitPrice) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"unitPrice\":"
        + unitPrice
        + "}";
  }

  private static String order(String fulfilment, String tax, String... lines) {
    return "{\"storeId\":\""
        + LEEDS
        + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\""
        + fulfilment
        + "\",\"currency\":\"GBP\",\"taxAmount\":"
        + tax
        + ",\"items\":["
        + String.join(",", lines)
        + "],\"deliveryLine1\":\"1 Park Row\",\"deliveryCity\":\"Leeds\","
        + "\"deliveryPostalCode\":\"LS1 5AB\",\"deliveryRecipientName\":\"Sam Shopper\","
        + "\"deliveryRecipientPhone\":\"07700900123\"}";
  }

  private Response place(String body, String key) {
    return target
        .path("/orders")
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", SHOPPER)
        .header("X-Roles", "CUSTOMER")
        .header("Idempotency-Key", key)
        .post(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private Response read(String path, String tenant, String user, String roles) {
    var b = target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", roles);
    if (user != null) b = b.header("X-User-Id", user);
    return b.get();
  }

  private long holdsPlacedAt(String store) {
    return INVENTORY.calls().stream()
        .filter(c -> "POST".equals(c.method()) && "/inventory/reservations".equals(c.path()))
        .filter(c -> c.body().contains("\"storeId\":\"" + store + "\""))
        .count();
  }

  private long calls(String method, String pathEnd) {
    return INVENTORY.calls().stream()
        .filter(c -> method.equals(c.method()) && c.path().endsWith(pathEnd))
        .count();
  }

  private JsonObject split() {
    holds(level(LEEDS, APPLES, 5), level(YORK, PEARS, 5), level(HULL, PEARS, 5));
    return Envelopes.created(
        place(
            order("DELIVERY", "0.55", line(APPLES, 2, "1.50"), line(PEARS, 3, "0.50")),
            Ids.newId().toString()));
  }

  // ── routing ────────────────────────────────────────────────────────────────

  @Test
  void anOrderTheAreaStoreHoldsIsOneOrderAsToday() {
    holds(level(LEEDS, APPLES, 5), level(LEEDS, PEARS, 5), level(YORK, PEARS, 5));
    JsonObject o =
        Envelopes.created(
            place(
                order("DELIVERY", "0", line(APPLES, 2, "1.50"), line(PEARS, 1, "0.50")),
                Ids.newId().toString()));
    assertThat(o.getString("storeId"), is(LEEDS));
    assertThat(o.containsKey("group"), is(false));
    assertThat(holdsPlacedAt(LEEDS), is(2L));
    assertThat(holdsPlacedAt(YORK), is(0L));
  }

  @Test
  void whatTheAreaStoreLacksComesFromTheNearestShopAsAGroupThatAddsUp() {
    JsonObject first = split();
    JsonObject group = first.getJsonObject("group");
    JsonArray parts = group.getJsonArray("parts");
    assertThat(parts, hasSize(2));
    assertThat(parts.getJsonObject(0).getString("storeId"), is(LEEDS));
    assertThat(parts.getJsonObject(1).getString("storeId"), is(YORK));
    assertThat(first.getString("id"), is(parts.getJsonObject(0).getString("orderId")));
    // 2 × 1.50 + 3 × 0.50 + 0.55 tax, shared by subtotal: Leeds 3.00 + 0.37, York 1.50 + 0.18.
    assertThat(
        group.getJsonNumber("total").bigDecimalValue(), comparesEqualTo(new BigDecimal("5.05")));
    assertThat(
        first.getJsonNumber("total").bigDecimalValue(), comparesEqualTo(new BigDecimal("3.37")));
    JsonObject york =
        Envelopes.ok(
            read("/orders/" + parts.getJsonObject(1).getString("orderId"), T, SHOPPER, "CUSTOMER"));
    assertThat(york.getString("storeId"), is(YORK));
    assertThat(
        york.getJsonNumber("total").bigDecimalValue(), comparesEqualTo(new BigDecimal("1.68")));
    assertThat(york.getJsonArray("items"), hasSize(1));
    assertThat(york.getJsonArray("items").getJsonObject(0).getString("variantId"), is(PEARS));
    assertThat(york.getJsonObject("group").getString("id"), is(group.getString("id")));
    assertThat(
        parts.getJsonObject(1).getJsonNumber("units").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("3")));
    // The shopper's history names the checkout on both parts.
    long inGroup =
        Envelopes.okArray(read("/orders/mine", T, SHOPPER, "CUSTOMER")).stream()
            .map(v -> v.asJsonObject())
            .filter(x -> group.getString("id").equals(x.getString("groupId", null)))
            .count();
    assertThat(inGroup, is(2L));
    // Each part held at its own shop, against its own order; each announced with the group.
    assertThat(holdsPlacedAt(LEEDS), is(1L));
    assertThat(holdsPlacedAt(YORK), is(1L));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".outbox WHERE event_type = 'OrderPlaced'"
                + " AND payload LIKE '%\"groupId\":\""
                + group.getString("id")
                + "\"%'"),
        is("2"));
  }

  @Test
  void oneOtherShopHoldingItAllTakesTheWholeOrder() {
    holds(level(LEEDS, APPLES, 1), level(YORK, APPLES, 5), level(HULL, APPLES, 5));
    JsonObject o =
        Envelopes.created(
            place(order("DELIVERY", "0", line(APPLES, 2, "1.50")), Ids.newId().toString()));
    assertThat(o.getString("storeId"), is(YORK));
    assertThat(o.containsKey("group"), is(false));
  }

  @Test
  void nothingThatAddsUpIsUnfulfillableAndHoldsNothing() {
    // The warehouse has plenty, and a warehouse serves its shops, never a shopper.
    holds(level(LEEDS, APPLES, 1), level(YORK, APPLES, 1), level(DC, APPLES, 100));
    Response r = place(order("DELIVERY", "0", line(APPLES, 5, "1.50")), Ids.newId().toString());
    assertThat(
        Envelopes.parse(Envelopes.bodyOf(r, 409)).getString("code"), is("ORDER_UNFULFILLABLE"));
    assertThat(calls("POST", "/inventory/reservations"), is(0L));
  }

  @Test
  void theSameKeyPlacesTheGroupOnce() {
    holds(level(LEEDS, APPLES, 5), level(YORK, PEARS, 5));
    String key = Ids.newId().toString();
    String body = order("DELIVERY", "0", line(APPLES, 1, "1.50"), line(PEARS, 1, "0.50"));
    JsonObject once = Envelopes.created(place(body, key));
    JsonObject again = Envelopes.created(place(body, key));
    assertThat(again.getString("id"), is(once.getString("id")));
    String group = once.getJsonObject("group").getString("id");
    assertThat(
        scalar(PG, "SELECT count(*) FROM \"order\".orders WHERE group_id = '" + group + "'"),
        is("2"));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".order_groups WHERE tenant_id = '"
                + T
                + "' AND idempotency_key = '"
                + key
                + "'"),
        is("1"));
  }

  @Test
  void aShopRefusingItsHoldReleasesWhatTheOthersHeld() {
    holds(level(LEEDS, APPLES, 5), level(YORK, PEARS, 5));
    REFUSING.add(YORK);
    String key = Ids.newId().toString();
    Response r =
        place(order("DELIVERY", "0", line(APPLES, 1, "1.50"), line(PEARS, 1, "0.50")), key);
    assertThat(
        Envelopes.parse(Envelopes.bodyOf(r, 409)).getString("code"),
        is("ORDER_INSUFFICIENT_STOCK"));
    assertThat(calls("POST", "/release"), is(1L));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".order_groups WHERE idempotency_key = '" + key + "'"),
        is("0"));
  }

  @Test
  void aPickupOrderIsNeverSplit() {
    holds(level(LEEDS, APPLES, 1), level(YORK, APPLES, 5));
    JsonObject o =
        Envelopes.created(
            place(order("PICKUP", "0", line(APPLES, 2, "1.50")), Ids.newId().toString()));
    assertThat(o.getString("storeId"), is(LEEDS));
    assertThat(o.containsKey("group"), is(false));
    assertThat(calls("GET", "/admin/inventory/network/stock"), is(0L));
  }

  // ── reading the group ──────────────────────────────────────────────────────

  @Test
  void theGroupIsReadByItsShopperAndTheBusinesssStaffOnly() {
    String group = split().getJsonObject("group").getString("id");
    String path = "/order-groups/" + group;
    assertThat(Envelopes.ok(read(path, T, SHOPPER, "CUSTOMER")).getJsonArray("parts"), hasSize(2));
    assertThat(Envelopes.ok(read(path, T, null, "MANAGER")).getString("id"), is(group));
    assertThat(read(path, T, STRANGER, "CUSTOMER").getStatus(), is(404));
    assertThat(read(path, T2, null, "OWNER").getStatus(), is(404));
    assertThat(read("/order-groups/" + Ids.newId(), T, null, "OWNER").getStatus(), is(404));
  }

  @Test
  void anOrderNeverSplitNamesNoGroup() {
    holds(level(LEEDS, APPLES, 5));
    JsonObject o =
        Envelopes.created(
            place(order("DELIVERY", "0", line(APPLES, 1, "1.50")), Ids.newId().toString()));
    JsonObject read = Envelopes.ok(read("/orders/" + o.getString("id"), T, SHOPPER, "CUSTOMER"));
    assertThat(read.get("group"), nullValue());
  }
}
