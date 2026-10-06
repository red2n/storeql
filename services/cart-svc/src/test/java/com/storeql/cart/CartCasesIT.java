package com.storeql.cart;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import com.storeql.cart.repo.CartRepository;
import com.storeql.ids.Ids;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantStatusRepository;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cart's refusals and ownership rules over real Postgres, and the trace assisted shopping
 * leaves: staff changing a cart that is not theirs is recorded with who and in what role; a shopper
 * on their own cart writes nothing; another business's staff of every role, a shopper and a guest
 * reach nothing of ours and leave nothing behind. Redis is off: the cache fails open.
 */
@HelidonTest
class CartCasesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("cart");
  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  @Inject WebTarget target;
  @Inject TenantStatusRepository tenants;
  @Inject StoreStatusRepository stores;
  @Inject CartRepository carts;
  @Inject com.storeql.service.TenantDataRepository outbox;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String pathAndQuery, UUID tenant, UUID user, String role) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    Invocation.Builder b = t.request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (user != null) b = b.header("X-User-Id", user.toString());
    if (role != null) b = b.header("X-Roles", role);
    return b;
  }

  private Response post(String path, UUID tenant, UUID user, String role, String json) {
    return as(path, tenant, user, role).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, UUID tenant, UUID user, String role, String json) {
    return as(path, tenant, user, role).put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static void refused(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, containsString(code));
  }

  /** A signed-in shopper's cart at a store. */
  private String cartOf(UUID tenant, UUID shopper, UUID store) {
    return data(post("/cart", tenant, shopper, "CUSTOMER", "{\"storeId\":\"" + store + "\"}"), 200)
        .getString("id");
  }

  private String addItem(UUID tenant, UUID shopper, String cartId, UUID variant, String qty) {
    return data(
            post(
                "/cart/items",
                tenant,
                shopper,
                "CUSTOMER",
                "{\"cartId\":\""
                    + cartId
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"qty\":"
                    + qty
                    + "}"),
            200)
        .getString("id");
  }

  private static String scalar(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  private static long count(String table, UUID tenant, String cartId) {
    return Long.parseLong(
        scalar(
            "SELECT COUNT(*) FROM cart."
                + table
                + " WHERE tenant_id = '"
                + tenant
                + "' AND cart_id = '"
                + cartId
                + "'"));
  }

  private static String qtyOf(String itemId) {
    return scalar("SELECT qty FROM cart.cart_items WHERE id = '" + itemId + "'");
  }

  // ── catalogue cases ────────────────────────────────────────────────────────

  @Test
  @DisplayName("SHOP-34/35: a guest's session token is server-minted; the same token resumes it")
  void guestSessionIsServerMintedAndResumes() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    String body = "{\"storeId\":\"" + store + "\",\"sessionId\":\"weak-guessable-123\"}";
    JsonObject first = data(post("/cart", tenant, null, null, body), 200);
    String token = first.getString("sessionId");
    assertThat(token.equals("weak-guessable-123"), is(false));
    assertThat(token.length(), greaterThan(39));
    JsonObject again =
        data(
            post(
                "/cart",
                tenant,
                null,
                null,
                "{\"storeId\":\"" + store + "\",\"sessionId\":\"" + token + "\"}"),
            200);
    assertThat(again.getString("id"), is(first.getString("id")));
    assertThat(again.getString("sessionId"), is(token));
  }

  @Test
  @DisplayName(
      "SHOP-36: adding a variant already in the cart adds to its quantity, not a second line")
  void addingTheSameVariantIncrements() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    String cart = cartOf(tenant, shopper, Ids.newId());
    UUID variant = Ids.newId();
    String first = addItem(tenant, shopper, cart, variant, "1");
    String second = addItem(tenant, shopper, cart, variant, "2");
    assertThat(second, is(first));
    assertThat(
        scalar("SELECT COUNT(*) FROM cart.cart_items WHERE cart_id = '" + cart + "'"), is("1"));
    assertThat(
        new java.math.BigDecimal(qtyOf(first)).compareTo(new java.math.BigDecimal("3")), is(0));
  }

  @Test
  @DisplayName("SHOP-37: a suspended business refuses adds")
  void suspendedTenantRefusesAdds() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    String cart = cartOf(tenant, shopper, Ids.newId());
    tenants.upsertTenantStatus(tenant, "SUSPENDED", Instant.now());
    refused(
        post(
            "/cart/items",
            tenant,
            shopper,
            "CUSTOMER",
            "{\"cartId\":\"" + cart + "\",\"variantId\":\"" + Ids.newId() + "\",\"qty\":1}"),
        409,
        "TENANT_NOT_OPERATIONAL");
    assertThat(count("cart_items", tenant, cart), is(0L));
  }

  @Test
  @DisplayName("SHOP-38: a closed store refuses cart operations while the business is active")
  void closedStoreRefusesAdds() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID store = Ids.newId();
    String cart = cartOf(tenant, shopper, store);
    stores.upsertStoreStatus(store, tenant, "CLOSED", Instant.now());
    refused(
        post(
            "/cart/items",
            tenant,
            shopper,
            "CUSTOMER",
            "{\"cartId\":\"" + cart + "\",\"variantId\":\"" + Ids.newId() + "\",\"qty\":1}"),
        409,
        "STORE_NOT_OPERATIONAL");
    // A store that belongs to another business is never open to us.
    UUID other = Ids.newId();
    refused(
        post("/cart", other, Ids.newId(), "CUSTOMER", "{\"storeId\":\"" + store + "\"}"),
        409,
        "STORE_NOT_OPERATIONAL");
  }

  @Test
  @DisplayName("SHOP-39: adding to a checked-out cart is refused")
  void checkedOutCartRefusesAdds() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    String cart = cartOf(tenant, shopper, Ids.newId());
    carts.markCheckedOutByCustomer(tenant, shopper);
    refused(
        post(
            "/cart/items",
            tenant,
            shopper,
            "CUSTOMER",
            "{\"cartId\":\"" + cart + "\",\"variantId\":\"" + Ids.newId() + "\",\"qty\":1}"),
        409,
        "CART_NOT_ACTIVE");
    assertThat(count("cart_items", tenant, cart), is(0L));
  }

  @Test
  @DisplayName("SHOP-41: a guest's cart id is not its credential; the session token is")
  void guestCartNeedsItsToken() {
    UUID tenant = Ids.newId();
    JsonObject guest =
        data(post("/cart", tenant, null, null, "{\"storeId\":\"" + Ids.newId() + "\"}"), 200);
    String cart = guest.getString("id");
    String token = guest.getString("sessionId");
    UUID variant = Ids.newId();
    String item =
        data(
                post(
                    "/cart/items",
                    tenant,
                    null,
                    null,
                    "{\"cartId\":\""
                        + cart
                        + "\",\"variantId\":\""
                        + variant
                        + "\",\"qty\":1,\"sessionId\":\""
                        + token
                        + "\"}"),
                200)
            .getString("id");

    refused(as("/cart?cartId=" + cart, tenant, null, null).get(), 404, "CART_NOT_FOUND");
    refused(
        target
            .path("/cart")
            .queryParam("cartId", cart)
            .queryParam("session", "guess")
            .request()
            .header("X-Tenant-Id", tenant)
            .get(),
        404,
        "CART_NOT_FOUND");
    refused(
        post(
            "/cart/items",
            tenant,
            null,
            null,
            "{\"cartId\":\"" + cart + "\",\"variantId\":\"" + Ids.newId() + "\",\"qty\":5}"),
        404,
        "CART_NOT_FOUND");
    refused(
        put("/cart/items/" + item, tenant, null, null, "{\"cartId\":\"" + cart + "\",\"qty\":9}"),
        404,
        "CART_NOT_FOUND");
    assertThat(
        as("/cart/items/" + item + "?cartId=" + cart, tenant, null, null).delete().getStatus(),
        is(404));
    assertThat(new java.math.BigDecimal(qtyOf(item)).intValue(), is(1));
    assertThat(count("cart_items", tenant, cart), is(1L));
    // With the token, the guest sees it.
    assertThat(
        target
            .path("/cart")
            .queryParam("cartId", cart)
            .queryParam("session", token)
            .request()
            .header("X-Tenant-Id", tenant)
            .get()
            .getStatus(),
        is(200));
  }

  @Test
  @DisplayName("a read naming no cart, no session and no signed-in shopper is refused 400")
  void aCartReadWithNoIdentityIsRefused() {
    UUID tenant = Ids.newId();
    // A shopper's cart exists, but a caller who names nothing and is nobody is not handed it.
    cartOf(tenant, Ids.newId(), Ids.newId());

    refused(as("/cart", tenant, null, null).get(), 400, "CART_NO_IDENTITY");
    // A blank session token names nothing either.
    refused(as("/cart?session=", tenant, null, null).get(), 400, "CART_NO_IDENTITY");
  }

  @Test
  @DisplayName(
      "SHOP-42: another shopper of the same business cannot read or change a cart, even by id")
  void anotherShopperCannotTouchACart() {
    UUID tenant = Ids.newId();
    UUID owner = Ids.newId();
    UUID stranger = Ids.newId();
    String cart = cartOf(tenant, owner, Ids.newId());
    String item = addItem(tenant, owner, cart, Ids.newId(), "1");

    refused(as("/cart?cartId=" + cart, tenant, stranger, "CUSTOMER").get(), 404, "CART_NOT_FOUND");
    refused(
        post(
            "/cart/items",
            tenant,
            stranger,
            "CUSTOMER",
            "{\"cartId\":\"" + cart + "\",\"variantId\":\"" + Ids.newId() + "\",\"qty\":1}"),
        404,
        "CART_NOT_FOUND");
    refused(
        put(
            "/cart/items/" + item,
            tenant,
            stranger,
            "CUSTOMER",
            "{\"cartId\":\"" + cart + "\",\"qty\":50}"),
        404,
        "CART_NOT_FOUND");
    assertThat(
        as("/cart/items/" + item + "?cartId=" + cart, tenant, stranger, "CUSTOMER")
            .delete()
            .getStatus(),
        is(404));
    assertThat(new java.math.BigDecimal(qtyOf(item)).intValue(), is(1));
    assertThat(count("cart_items", tenant, cart), is(1L));
    assertThat(count("cart_staff_actions", tenant, cart), is(0L));
  }

  @Test
  @DisplayName(
      "SHOP-44: another business, whoever it sends, finds no cart of ours and changes nothing")
  void otherBusinessFindsNothing() {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID owner = Ids.newId();
    String cart = cartOf(tenant, owner, Ids.newId());
    String item = addItem(tenant, owner, cart, Ids.newId(), "1");

    java.util.List<String> roles = new java.util.ArrayList<>(java.util.List.of(STAFF));
    roles.add("CUSTOMER");
    roles.add(null); // a guest
    for (String role : roles) {
      UUID user = role == null ? null : Ids.newId();
      String who = String.valueOf(role);
      refused(as("/cart?cartId=" + cart, other, user, role).get(), 404, "CART_NOT_FOUND");
      refused(
          post(
              "/cart/items",
              other,
              user,
              role,
              "{\"cartId\":\"" + cart + "\",\"variantId\":\"" + Ids.newId() + "\",\"qty\":1}"),
          404,
          "CART_NOT_FOUND");
      refused(
          put("/cart/items/" + item, other, user, role, "{\"cartId\":\"" + cart + "\",\"qty\":50}"),
          404,
          "CART_NOT_FOUND");
      assertThat(
          who,
          as("/cart/items/" + item + "?cartId=" + cart, other, user, role).delete().getStatus(),
          is(404));
    }
    assertThat(new java.math.BigDecimal(qtyOf(item)).intValue(), is(1));
    assertThat(count("cart_items", tenant, cart), is(1L));
    assertThat(count("cart_staff_actions", tenant, cart), is(0L));
    assertThat(
        scalar("SELECT COUNT(*) FROM cart.cart_staff_actions WHERE tenant_id = '" + other + "'"),
        is("0"));
  }

  @Test
  @DisplayName("SHOP-45: merging a guest cart needs a signed-in user")
  void mergeNeedsSignIn() {
    UUID tenant = Ids.newId();
    JsonObject guest =
        data(post("/cart", tenant, null, null, "{\"storeId\":\"" + Ids.newId() + "\"}"), 200);
    refused(
        post(
            "/cart/merge",
            tenant,
            null,
            null,
            "{\"sessionId\":\"" + guest.getString("sessionId") + "\"}"),
        400,
        "CART_MERGE_NO_AUTH");
    assertThat(
        scalar("SELECT status FROM cart.carts WHERE id = '" + guest.getString("id") + "'"),
        is("ACTIVE"));
  }

  @Test
  @DisplayName(
      "SHOP-46: merging folds the guest's items into the shopper's cart and abandons the guest cart")
  void mergeFoldsAndAbandons() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID store = Ids.newId();
    JsonObject guest =
        data(post("/cart", tenant, null, null, "{\"storeId\":\"" + store + "\"}"), 200);
    String token = guest.getString("sessionId");
    UUID variant = Ids.newId();
    data(
        post(
            "/cart/items",
            tenant,
            null,
            null,
            "{\"cartId\":\""
                + guest.getString("id")
                + "\",\"variantId\":\""
                + variant
                + "\",\"qty\":2,\"sessionId\":\""
                + token
                + "\"}"),
        200);

    // Another business's shopper holding our guest's token merges nothing.
    refused(
        post(
            "/cart/merge",
            Ids.newId(),
            Ids.newId(),
            "CUSTOMER",
            "{\"sessionId\":\"" + token + "\"}"),
        404,
        "CART_NOT_FOUND");
    assertThat(
        scalar("SELECT status FROM cart.carts WHERE id = '" + guest.getString("id") + "'"),
        is("ACTIVE"));

    JsonObject merged =
        data(
            post("/cart/merge", tenant, shopper, "CUSTOMER", "{\"sessionId\":\"" + token + "\"}"),
            200);
    assertThat(merged.getString("customerId"), is(shopper.toString()));
    assertThat(
        scalar(
            "SELECT qty FROM cart.cart_items WHERE cart_id = '"
                + merged.getString("id")
                + "' AND variant_id = '"
                + variant
                + "'"),
        is("2.0000"));
    assertThat(
        scalar("SELECT status FROM cart.carts WHERE id = '" + guest.getString("id") + "'"),
        is("ABANDONED"));
  }

  @Test
  @DisplayName("SHOP-47: removing an item through the wrong cart is refused and the item stays")
  void removeThroughTheWrongCartIsRefused() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID mate = Ids.newId();
    String mine = cartOf(tenant, shopper, Ids.newId());
    String theirs = cartOf(tenant, mate, Ids.newId());
    String item = addItem(tenant, mate, theirs, Ids.newId(), "1");
    refused(
        as("/cart/items/" + item + "?cartId=" + mine, tenant, shopper, "CUSTOMER").delete(),
        404,
        "CART_ITEM_NOT_FOUND");
    assertThat(count("cart_items", tenant, theirs), is(1L));
  }

  // ── assisted shopping leaves a trace ───────────────────────────────────────

  @Test
  @DisplayName(
      "Staff of every role changing a shopper's cart leave a trace naming who and in what role")
  void assistedShoppingIsTraced() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    String cart = cartOf(tenant, shopper, Ids.newId());
    UUID variant = Ids.newId();
    long expected = 0;
    for (String role : STAFF) {
      UUID staff = Ids.newId();
      JsonObject added =
          data(
              post(
                  "/cart/items",
                  tenant,
                  staff,
                  role,
                  "{\"cartId\":\"" + cart + "\",\"variantId\":\"" + variant + "\",\"qty\":1}"),
              200);
      String item = added.getString("id");
      assertThat(
          scalar(
              "SELECT actor_role FROM cart.cart_staff_actions WHERE tenant_id = '"
                  + tenant
                  + "' AND cart_id = '"
                  + cart
                  + "' AND actor_id = '"
                  + staff
                  + "' AND action = 'ADD_ITEM'"),
          is(role));
      data(
          put(
              "/cart/items/" + item,
              tenant,
              staff,
              role,
              "{\"cartId\":\"" + cart + "\",\"qty\":7}"),
          200);
      assertThat(
          scalar(
              "SELECT qty FROM cart.cart_staff_actions WHERE tenant_id = '"
                  + tenant
                  + "' AND actor_id = '"
                  + staff
                  + "' AND action = 'SET_QTY'"),
          is("7.0000"));
      assertThat(
          as("/cart/items/" + item + "?cartId=" + cart, tenant, staff, role).delete().getStatus(),
          is(204));
      assertThat(
          scalar(
              "SELECT variant_id FROM cart.cart_staff_actions WHERE tenant_id = '"
                  + tenant
                  + "' AND actor_id = '"
                  + staff
                  + "' AND action = 'REMOVE_ITEM'"),
          is(variant.toString()));
      expected += 3;
      assertThat(count("cart_staff_actions", tenant, cart), is(expected));
    }
  }

  @Test
  @DisplayName("A shopper on their own cart, and staff only reading a cart, leave no trace")
  void ownChangesAndReadsLeaveNoTrace() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    String cart = cartOf(tenant, shopper, Ids.newId());
    String item = addItem(tenant, shopper, cart, Ids.newId(), "1");
    data(
        put(
            "/cart/items/" + item,
            tenant,
            shopper,
            "CUSTOMER",
            "{\"cartId\":\"" + cart + "\",\"qty\":4}"),
        200);
    assertThat(
        as("/cart?cartId=" + cart, tenant, Ids.newId(), "CASHIER").get().getStatus(), is(200));
    assertThat(count("cart_staff_actions", tenant, cart), is(0L));
    // A staff member's own cart is their own: no trace either.
    UUID staff = Ids.newId();
    String staffCart =
        data(post("/cart", tenant, staff, "CASHIER", "{\"storeId\":\"" + Ids.newId() + "\"}"), 200)
            .getString("id");
    data(
        post(
            "/cart/items",
            tenant,
            staff,
            "CASHIER",
            "{\"cartId\":\"" + staffCart + "\",\"variantId\":\"" + Ids.newId() + "\",\"qty\":1}"),
        200);
    assertThat(count("cart_staff_actions", tenant, staffCart), is(0L));
  }

  /** An outbox row of the probe, created long ago, published when {@code published} says. */
  private static void outboxProbe(String marker, String published) {
    Envelopes.exec(
        PG,
        "INSERT INTO cart.outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
            + " created_at, published_at) VALUES ('"
            + Ids.newId()
            + "', 'PurgeProbe', '"
            + marker
            + "', '"
            + Ids.newId()
            + "', '"
            + Ids.newId()
            + "', '{}', now() - interval '11 days', "
            + published
            + ")");
  }

  /**
   * The hourly purge, run against this schema: published outbox rows older than the cutoff go, in
   * batches, oldest first; a row recent enough, or not yet published, stays whatever its age. This
   * service keeps no processed_events table, which the purge's second statement takes as nothing to
   * trim and not as an error.
   */
  @Test
  @DisplayName("The purge trims published outbox rows in batches, and no more")
  void thePurgeTrimsOnlyWhatIsPublishedAndOld() {
    String tail = Ids.newId().toString();
    String marker = "purge-" + tail.substring(tail.length() - 12);
    for (int i = 0; i < 3; i++) outboxProbe(marker, "now() - interval '10 days'");
    for (int i = 0; i < 2; i++) outboxProbe(marker, "now() - interval '1 hour'");
    outboxProbe(marker, "NULL");
    Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(7));

    assertThat("a batch of two", outbox.purgePublished(cutoff, 2), is(2));
    assertThat("then the rest", outbox.purgePublished(cutoff, 2), is(1));
    assertThat("and no more", outbox.purgePublished(cutoff, 2), is(0));
    assertThat(
        "the recent and the unpublished stay",
        scalar("SELECT count(*) FROM cart.outbox WHERE topic = '" + marker + "'"),
        is("3"));
    assertThat("no dedupe table is no error", outbox.purgeProcessedEvents(cutoff, 100), is(0));
  }

  /**
   * The hourly purge (common-service) deletes published outbox rows in batches, oldest first, and
   * each batch needs an index on published_at or it scans the whole table. With sequential scans
   * switched off the planner takes an index only when one can serve the statement, so the plan
   * names it. (cart-svc keeps no processed_events table, so the purge's second statement has no
   * index to serve here.)
   */
  @Test
  @DisplayName("The purge of published outbox rows is served by an index")
  void thePurgeOfPublishedOutboxRowsIsServedByAnIndex() throws Exception {
    StringBuilder plan = new StringBuilder();
    try (var conn =
            java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute("SET enable_seqscan = off");
      try (var rs =
          st.executeQuery(
              "EXPLAIN SELECT id FROM cart.outbox WHERE published_at IS NOT NULL"
                  + " AND published_at < now() ORDER BY published_at ASC LIMIT 1000"
                  + " FOR UPDATE SKIP LOCKED")) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
    }
    assertThat(plan.toString(), containsString("idx_outbox_published"));
  }
}
