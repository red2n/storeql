package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The business audit trail (20.11): every discount, void, no-sale, cancel and return this service
 * records, read back as one stream naming who did it — with the wrong caller, the wrong tenant, the
 * wrong store, the wrong input and a hammering cashier tried beside the right ones.
 */
@HelidonTest
class AuditTrailIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(AuditTrailIT.T, "USD", "US").with(AuditTrailIT.OTHER_T, "USD", "US");
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

  private static final String T = "01a090d1-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a090d1-1111-7000-8000-000000000002";
  private static final String STORE = "01a090d1-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a090d1-2222-7000-8000-00000000000b";
  private static final String V = "01a090d1-3333-7000-8000-000000000001";

  /** The manager who discounts and cancels. */
  private static final String M1 = "01a090d1-4444-7000-8000-000000000001";

  /** The manager who voids and takes returns. */
  private static final String M2 = "01a090d1-4444-7000-8000-000000000002";

  /** The cashier who opens the drawer without a sale. */
  private static final String C = "01a090d1-4444-7000-8000-000000000003";

  @Inject WebTarget target;
  @Inject OrderService orderService;

  private static boolean seeded;
  private static String discountedOrder;
  private static String voidedOrder;
  private static String cancelledOrder;
  private static String returnedOrder;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private Invocation.Builder as(
      WebTarget t, String tenant, String roles, String user, String stores) {
    var b = t.request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (user != null) b = b.header("X-User-Id", user);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return b;
  }

  private Response post(String path, String json, String user, String roles) {
    return as(target.path(path), T, roles, user, null)
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** A two-unit till sale at $10.00 each, placed by {@code user} as a manager. */
  private String place(String user, String extra) {
    Response r =
        post(
            "/orders",
            "{\"storeId\":\""
                + STORE
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],\"currency\":\"USD\""
                + extra
                + "}",
            user,
            "MANAGER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return json(body).getJsonObject("data").getString("id");
  }

  private void pay(String orderId, String total) {
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(orderId), Ids.newId(), new BigDecimal(total));
  }

  /** Five sensitive actions by three people, once per class. */
  private synchronized void seed() {
    if (seeded) return;
    discountedOrder = place(M1, ",\"discountAmount\":2.00,\"discountReason\":\"damaged box\"");

    voidedOrder = place(M2, "");
    pay(voidedOrder, "20.00");
    assertThat(
        post("/orders/" + voidedOrder + "/void", "{\"reason\":\"rang up twice\"}", M2, "MANAGER")
            .getStatus(),
        is(200));

    assertThat(
        post(
                "/pos/no-sale",
                "{\"storeId\":\"" + STORE + "\",\"reason\":\"drawer check\"}",
                C,
                "CASHIER")
            .getStatus(),
        is(201));

    cancelledOrder = place(M1, "");
    assertThat(
        post(
                "/orders/" + cancelledOrder + "/cancel",
                "{\"reason\":\"customer walked out\"}",
                M1,
                "MANAGER")
            .getStatus(),
        is(200));

    returnedOrder = place(M2, "");
    pay(returnedOrder, "20.00");
    assertThat(
        post(
                "/orders/" + returnedOrder + "/returns",
                "{\"reason\":\"chipped\",\"items\":[{\"variantId\":\""
                    + V
                    + "\",\"qty\":1,\"condition\":\"SEALED\"}]}",
                M2,
                "MANAGER")
            .getStatus(),
        is(201));
    seeded = true;
  }

  private Response trail(String tenant, String roles, String user, String stores, String... kv) {
    WebTarget t = target.path("/admin/audit/events");
    for (int i = 0; i + 1 < kv.length; i += 2) t = t.queryParam(kv[i], kv[i + 1]);
    return as(t, tenant, roles, user, stores).get();
  }

  private static JsonObject json(String body) {
    try (var r = Json.createReader(new StringReader(body))) {
      return r.readObject();
    }
  }

  private static JsonArray rows(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return json(body).getJsonArray("data");
  }

  private static String str(JsonObject o, String key) {
    return o.containsKey(key) && o.get(key).getValueType() != JsonValue.ValueType.NULL
        ? o.getString(key)
        : null;
  }

  private static JsonObject ofType(JsonArray rows, String type) {
    for (JsonValue v : rows) {
      JsonObject o = v.asJsonObject();
      if (type.equals(o.getString("type"))) return o;
    }
    throw new AssertionError("no " + type + " in " + rows);
  }

  // ── the trail ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Every sensitive action is on the trail, newest first, naming who did it")
  void everySensitiveActionIsOnTheTrailNamingItsActor() {
    seed();
    JsonArray all = rows(trail(T, "OWNER", null, null));
    assertThat(all.toString(), all.size(), is(5));

    JsonObject discount = ofType(all, "DISCOUNT");
    assertThat(str(discount, "actorId"), is(M1));
    assertThat(str(discount, "orderId"), is(discountedOrder));
    assertThat(discount.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("2.00")));
    assertThat(str(discount, "reason"), is("damaged box"));
    assertThat("the role that authorised it", str(discount, "detail"), is("MANAGER"));

    JsonObject voided = ofType(all, "VOID");
    assertThat(str(voided, "actorId"), is(M2));
    assertThat(str(voided, "orderId"), is(voidedOrder));
    assertThat(str(voided, "reason"), is("rang up twice"));

    JsonObject noSale = ofType(all, "NO_SALE");
    assertThat(str(noSale, "actorId"), is(C));
    assertThat("a no-sale has no order", str(noSale, "orderId"), is(nullValue()));
    assertThat(str(noSale, "reason"), is("drawer check"));

    JsonObject cancel = ofType(all, "CANCEL");
    assertThat(str(cancel, "actorId"), is(M1));
    assertThat(str(cancel, "orderId"), is(cancelledOrder));
    assertThat("the status it was cancelled from", str(cancel, "detail"), is("PENDING"));

    JsonObject ret = ofType(all, "RETURN");
    assertThat("a return now names who took it back", str(ret, "actorId"), is(M2));
    assertThat(str(ret, "orderId"), is(returnedOrder));
    assertThat(ret.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("10.00")));
    assertThat("the refund method", str(ret, "detail"), is("ORIGINAL"));
    // Its lines, with the condition each came back in, and that no manager was needed.
    JsonObject returnedLine = ret.getJsonArray("lines").getJsonObject(0);
    assertThat(returnedLine.getString("variantId"), is(V));
    assertThat(returnedLine.getString("condition"), is("SEALED"));
    assertThat(returnedLine.getJsonNumber("qty").bigDecimalValue().intValue(), is(1));
    assertThat(str(ret, "approvedBy"), is(nullValue()));
    assertThat(ret.getJsonArray("outsidePolicy").size(), is(0));

    // Every event is at the store, and the stream is newest first.
    Instant previous = null;
    for (JsonValue v : all) {
      JsonObject o = v.asJsonObject();
      assertThat(str(o, "storeId"), is(STORE));
      Instant at = Instant.parse(o.getString("occurredAt"));
      if (previous != null) assertThat(o.toString(), !at.isAfter(previous), is(true));
      previous = at;
    }
  }

  @Test
  @DisplayName("The trail narrows by type, actor, store and period")
  void theTrailNarrowsByTypeActorStoreAndPeriod() {
    seed();
    JsonArray voids = rows(trail(T, "OWNER", null, null, "type", "VOID"));
    assertThat(voids.size(), is(1));
    assertThat(voids.getJsonObject(0).getString("type"), is("VOID"));
    assertThat("any case", rows(trail(T, "OWNER", null, null, "type", "void")).size(), is(1));

    JsonArray byM1 = rows(trail(T, "OWNER", null, null, "actor", M1));
    List<String> types = new ArrayList<>();
    for (JsonValue v : byM1) types.add(v.asJsonObject().getString("type"));
    assertThat(types, containsInAnyOrder("DISCOUNT", "CANCEL"));

    assertThat(rows(trail(T, "OWNER", null, null, "store", STORE)).size(), is(5));
    assertThat(rows(trail(T, "OWNER", null, null, "store", OTHER_STORE)).size(), is(0));

    String future = Instant.now().plusSeconds(3600).toString();
    assertThat(rows(trail(T, "OWNER", null, null, "from", future)).size(), is(0));
    assertThat(rows(trail(T, "OWNER", null, null, "to", "2020-01-01T00:00:00Z")).size(), is(0));
    assertThat(
        rows(trail(T, "OWNER", null, null, "from", "2020-01-01T00:00:00Z", "to", future)).size(),
        is(5));
  }

  @Test
  @DisplayName("The trail pages without gaps or repeats")
  void theTrailPagesWithoutGapsOrRepeats() {
    seed();
    Set<String> seen = new HashSet<>();
    String after = null;
    int pages = 0;
    do {
      Response r =
          after == null
              ? trail(T, "OWNER", null, null, "limit", "2")
              : trail(T, "OWNER", null, null, "limit", "2", "after", after);
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(200));
      JsonObject env = json(body);
      for (JsonValue v : env.getJsonArray("data")) {
        assertThat("repeated", seen.add(v.asJsonObject().getString("id")), is(true));
      }
      after = str(env.getJsonObject("meta"), "nextCursor");
      pages++;
    } while (after != null && pages < 10);
    assertThat(pages, is(3));
    assertThat(seen.size(), is(5));
  }

  // ── who may read it ───────────────────────────────────────────────────────

  @Test
  @DisplayName("The trail is management-only, tenant-scoped and store-scoped")
  void theTrailIsManagementOnlyTenantScopedAndStoreScoped() {
    seed();
    assertThat(trail(T, "CASHIER", C, null).getStatus(), is(403));
    assertThat(trail(T, "STOREKEEPER", Ids.newId().toString(), null).getStatus(), is(403));
    assertThat(trail(T, "CUSTOMER", Ids.newId().toString(), null).getStatus(), is(403));
    assertThat(trail(T, null, null, null).getStatus(), anyOf(is(401), is(403)));

    // Another tenant's owner sees nothing of this tenant's.
    assertThat(rows(trail(OTHER_T, "OWNER", null, null)).size(), is(0));

    // A manager bound to another store sees nothing; bound to this store, everything; asking for
    // a store outside their scope is refused rather than answered empty.
    assertThat(rows(trail(T, "MANAGER", M1, OTHER_STORE)).size(), is(0));
    assertThat(rows(trail(T, "MANAGER", M1, STORE)).size(), is(5));
    assertThat(trail(T, "MANAGER", M1, OTHER_STORE, "store", STORE).getStatus(), is(403));
  }

  @Test
  @DisplayName("Bad input is refused by name, and an absurd page size is clamped")
  void badInputIsRefusedByName() {
    seed();
    Response type = trail(T, "OWNER", null, null, "type", "WEATHER");
    assertThat(type.getStatus(), is(400));
    assertThat(type.readEntity(String.class), containsString("AUDIT_TYPE_UNKNOWN"));

    Response cursor = trail(T, "OWNER", null, null, "after", "@@@");
    assertThat(cursor.getStatus(), is(400));
    assertThat(cursor.readEntity(String.class), containsString("INVALID_CURSOR"));
    String nonsense = Base64.getUrlEncoder().withoutPadding().encodeToString("nonsense".getBytes());
    assertThat(trail(T, "OWNER", null, null, "after", nonsense).getStatus(), is(400));

    assertThat(trail(T, "OWNER", null, null, "store", "abc").getStatus(), is(400));
    assertThat(trail(T, "OWNER", null, null, "actor", "abc").getStatus(), is(400));
    assertThat(trail(T, "OWNER", null, null, "from", "yesterday").getStatus(), is(400));

    Response range =
        trail(T, "OWNER", null, null, "from", "2026-02-01T00:00:00Z", "to", "2026-01-01T00:00:00Z");
    assertThat(range.getStatus(), is(400));
    assertThat(range.readEntity(String.class), containsString("AUDIT_RANGE_EMPTY"));

    assertThat(rows(trail(T, "OWNER", null, null, "limit", "100000")).size(), is(5));
    assertThat(rows(trail(T, "OWNER", null, null, "limit", "-1")).size(), is(5));
  }

  // ── abuse ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Hammering the trail as a cashier never gets through")
  void hammeringTheTrailAsACashierNeverGetsThrough() {
    seed();
    for (int i = 0; i < 30; i++) {
      Response r = trail(T, "CASHIER", C, null);
      assertThat("attempt " + i, r.getStatus(), is(403));
      r.close();
    }
  }

  @Test
  @DisplayName("A cursor lifted from one tenant's page leaks nothing to another")
  void aCursorFromOneTenantLeaksNothingToAnother() {
    seed();
    Response first = trail(T, "OWNER", null, null, "limit", "2");
    String cursor = str(json(first.readEntity(String.class)).getJsonObject("meta"), "nextCursor");
    assertThat(cursor, is(notNullValue()));
    assertThat(rows(trail(OTHER_T, "OWNER", null, null, "after", cursor)).size(), is(0));
  }

  @Test
  @DisplayName("The trail cannot be written to")
  void theTrailCannotBeWrittenTo() {
    Response posted =
        as(target.path("/admin/audit/events"), T, "OWNER", null, null)
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(posted.getStatus(), anyOf(is(404), is(405)));
    Response deleted = as(target.path("/admin/audit/events"), T, "OWNER", null, null).delete();
    assertThat(deleted.getStatus(), anyOf(is(404), is(405)));
  }

  @Test
  @DisplayName("A return names who took the goods back, on the order as well as on the trail")
  void aReturnNamesWhoTookTheGoodsBack() {
    seed();
    Response r =
        as(target.path("/orders/" + returnedOrder + "/returns"), T, "OWNER", null, null).get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    assertThat(body, containsString("\"createdBy\":\"" + M2 + "\""));
  }
}
