package com.storeql.cart;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.cart.repo.CartRepository;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for cart-svc against real Postgres + Redis (Testcontainers): cart/item
 * lifecycle, and proof that the cart-items read is cache-aside (served from Redis, invalidated on
 * write) rather than hitting Postgres on every view. Kafka/Consul disabled.
 */
@HelidonTest
class CartCachingIT {

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "cart");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");

    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
  }

  private static final String TENANT_A = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String CUSTOMER_A = "01a090ae-611e-7011-ae7d-1bd68c966ff6";
  private static final String STORE_A = "01a090ae-611e-7014-8cd5-baf0862fa319";

  @Inject WebTarget target;
  @Inject CartRepository repo;

  @AfterAll
  static void stopDb() {
    PG.stop();
    REDIS.stop();
  }

  private Response post(String path, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", TENANT_A)
        .header("X-User-Id", CUSTOMER_A)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String pathAndQuery) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    return t.request()
        .header("X-Tenant-Id", TENANT_A)
        .header("X-User-Id", CUSTOMER_A)
        .get(String.class);
  }

  private Response put(String path, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", TENANT_A)
        .header("X-User-Id", CUSTOMER_A)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Bypasses the app entirely — proves a read came from cache rather than the DB. */
  private static void rawUpdateItemQty(String itemId, String qty) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement("UPDATE cart.cart_items SET qty = ? WHERE id = ?::uuid")) {
      ps.setBigDecimal(1, new java.math.BigDecimal(qty));
      ps.setString(2, itemId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void cartLifecycle() {
    Response c = post("/cart", "{\"storeId\":\"" + STORE_A + "\"}");
    assertThat(c.getStatus(), is(200));
    String cartId = field(c.readEntity(String.class), "id");

    String variantId = Ids.newId().toString();
    Response added =
        post(
            "/cart/items",
            "{\"cartId\":\"" + cartId + "\",\"variantId\":\"" + variantId + "\",\"qty\":3}");
    assertThat(added.getStatus(), is(200));
    assertThat(added.readEntity(String.class), containsString("3"));

    assertThat(get("/cart?cartId=" + cartId), containsString(variantId));
  }

  @Test
  void cartItemsAreCachedAndInvalidatedOnUpdate() {
    Response c = post("/cart", "{\"storeId\":\"" + STORE_A + "\"}");
    assertThat(c.getStatus(), is(200));
    String cartId = field(c.readEntity(String.class), "id");

    String variantId = Ids.newId().toString();
    Response added =
        post(
            "/cart/items",
            "{\"cartId\":\"" + cartId + "\",\"variantId\":\"" + variantId + "\",\"qty\":3}");
    assertThat(added.getStatus(), is(200));
    String itemId = field(added.readEntity(String.class), "id");

    // first view — populates the items cache
    assertThat(get("/cart?cartId=" + cartId), containsString("3.0000"));

    // mutate the row directly in Postgres, bypassing the app and its cache eviction
    rawUpdateItemQty(itemId, "99");

    // still served from cache — proves the read isn't hitting Postgres every time
    assertThat(get("/cart?cartId=" + cartId), containsString("3.0000"));

    // a real update goes through the app, which evicts the items cache
    Response updated = put("/cart/items/" + itemId, "{\"cartId\":\"" + cartId + "\",\"qty\":7}");
    assertThat(updated.getStatus(), is(200));

    // next read reflects the update, not the raw mutation — cache was invalidated, not just expired
    String view = get("/cart?cartId=" + cartId);
    assertThat(view, containsString("7.0000"));
    assertThat(view, not(containsString("99.0000")));
  }

  /**
   * markCheckedOutByCustomerAndStore is what the OrderPlaced event handler calls; it didn't use to
   * know the cart's id (only customerId/storeId), so it could only evict the pointer cache and the
   * items cache self-corrected within the TTL instead of immediately. It now captures the affected
   * cart's id via {@code RETURNING id} and evicts both caches right away.
   */
  @Test
  void markCheckedOutEvictsTheItemsCacheImmediately() {
    Response c = post("/cart", "{\"storeId\":\"" + STORE_A + "\"}");
    assertThat(c.getStatus(), is(200));
    String cartId = field(c.readEntity(String.class), "id");

    String variantId = Ids.newId().toString();
    Response added =
        post(
            "/cart/items",
            "{\"cartId\":\"" + cartId + "\",\"variantId\":\"" + variantId + "\",\"qty\":3}");
    assertThat(added.getStatus(), is(200));
    String itemId = field(added.readEntity(String.class), "id");

    // first view — populates the items cache
    assertThat(get("/cart?cartId=" + cartId), containsString("3.0000"));

    // simulate the OrderPlaced handler marking the cart checked out, then a raw mutation (as if a
    // human inspected the now-archived cart's data directly)
    repo.markCheckedOutByCustomerAndStore(
        Ids.parse(TENANT_A), Ids.parse(CUSTOMER_A), Ids.parse(STORE_A));
    rawUpdateItemQty(itemId, "99");

    // the items cache was evicted by markCheckedOutByCustomerAndStore itself, not left to expire —
    // a direct repo read reflects the raw mutation immediately. (Other tests share this customer's
    // cart, so find the item we just mutated by id rather than assuming list position.)
    var freshItems = repo.findItemsByCart(Ids.parse(TENANT_A), Ids.parse(cartId));
    var mutated =
        freshItems.stream().filter(i -> i.id().equals(Ids.parse(itemId))).findFirst().orElseThrow();
    assertThat(mutated.qty().toPlainString(), is("99.0000"));
  }

  /**
   * An online order is placed at the store its delivery resolves to, or split across several (order
   * orchestration); the shopper's one active cart is closed whichever store it names.
   */
  @Test
  void anOnlineOrderAtAnotherStoreClosesTheShoppersCart() {
    Response c = post("/cart", "{\"storeId\":\"" + STORE_A + "\"}");
    assertThat(c.getStatus(), is(200));
    String cartId = field(c.readEntity(String.class), "id");
    UUID elsewhere = Ids.newId();
    // At another store, the store-matched close finds nothing to close.
    repo.markCheckedOutByCustomerAndStore(Ids.parse(TENANT_A), Ids.parse(CUSTOMER_A), elsewhere);
    assertThat(cartStatus(cartId), is("ACTIVE"));
    repo.markCheckedOutByCustomer(Ids.parse(TENANT_A), Ids.parse(CUSTOMER_A));
    assertThat(cartStatus(cartId), is("CHECKED_OUT"));
    // A second part of the same checkout finds nothing left to close.
    repo.markCheckedOutByCustomer(Ids.parse(TENANT_A), Ids.parse(CUSTOMER_A));
    assertThat(cartStatus(cartId), is("CHECKED_OUT"));
  }

  private static String cartStatus(String cartId) {
    return com.storeql.test.Envelopes.scalar(
        PG, "SELECT status FROM cart.carts WHERE id = '" + cartId + "'");
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
