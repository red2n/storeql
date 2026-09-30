package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.gs1.Gtin;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The refusals a catalogue gives, from the flow catalogue: a category is never its own ancestor, a
 * SKU or barcode already held is named, a barcode that claims to be a GTIN passes its check digit,
 * a delisted variant does not scan, the plan's product ceiling holds, and staff of another business
 * or a lower role change nothing.
 */
@HelidonTest
class CatalogGuardsIT {

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();
  private static final String CAPPED = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final AtomicLong COUNTER = new AtomicLong(system());

  private static long system() {
    return System.nanoTime() % 1_000_000_000L;
  }

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "product");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .with(RIVAL, "GBP", "GB")
        .with(CAPPED, "GBP", "GB")
        .withLimit(CAPPED, "products.max", 1);
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private Response send(String method, String path, String json, String tenant, String roles) {
    var b = target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", roles);
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String body(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private static String id(String body) {
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  private String category(String tenant, String name, String parentId) {
    String json =
        "{\"name\":\""
            + name
            + " "
            + Ids.newId()
            + "\""
            + (parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"")
            + "}";
    return id(body(send("POST", "/admin/categories", json, tenant, "OWNER"), 201));
  }

  private Response reparent(String tenant, String id, String parentId) {
    String name = "Renamed " + Ids.newId();
    return send(
        "PUT",
        "/admin/categories/" + id,
        "{\"name\":\""
            + name
            + "\""
            + (parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"")
            + "}",
        tenant,
        "OWNER");
  }

  private String product(String tenant) {
    return id(
        body(
            send(
                "POST",
                "/admin/products",
                "{\"name\":\"Guard " + Ids.newId() + "\"}",
                tenant,
                "OWNER"),
            201));
  }

  private Response variant(String tenant, String product, String sku, String barcode) {
    return send(
        "POST",
        "/admin/products/" + product + "/variants",
        "{\"sku\":\""
            + sku
            + "\""
            + (barcode == null ? "" : ",\"barcode\":\"" + barcode + "\"")
            + "}",
        tenant,
        "OWNER");
  }

  private Response editVariant(
      String tenant, String product, String variant, String sku, String barcode) {
    return send(
        "PUT",
        "/admin/products/" + product + "/variants/" + variant,
        "{\"sku\":\""
            + sku
            + "\""
            + (barcode == null ? "" : ",\"barcode\":\"" + barcode + "\"")
            + "}",
        tenant,
        "OWNER");
  }

  private Response scan(String code, String tenant) {
    return target
        .path("/catalog/scan")
        .queryParam("code", code)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "CASHIER")
        .get();
  }

  private static String sku() {
    return "G-" + Ids.newId();
  }

  /** A GTIN-13 nobody else in this schema holds, check digit and all. */
  private static String freshEan13() {
    String body = String.format("6%011d", COUNTER.incrementAndGet() % 100_000_000_000L);
    return body + Gtin.checkDigit(body);
  }

  private static String withWrongCheckDigit(String gtin) {
    int last = gtin.charAt(gtin.length() - 1) - '0';
    return gtin.substring(0, gtin.length() - 1) + ((last + 1) % 10);
  }

  // ── categories: CAT-cycle gap ────────────────────────────────────────────────

  @Test
  @DisplayName("A category cannot be placed under itself or under its own descendant")
  void aCategoryCycleIsRefused() {
    String a = category(T, "A", null);
    String b = category(T, "B", a);
    String c = category(T, "C", b);

    assertThat(body(reparent(T, a, a), 409), containsString("PRODUCT_CATEGORY_CYCLE"));
    assertThat(body(reparent(T, a, b), 409), containsString("PRODUCT_CATEGORY_CYCLE"));
    assertThat(body(reparent(T, a, c), 409), containsString("PRODUCT_CATEGORY_CYCLE"));
    assertThat(
        "nothing moved",
        body(send("GET", "/admin/categories/" + a, null, T, "OWNER"), 200),
        not(containsString("parentId")));

    // A move that makes no cycle is still allowed: C up under A, then A left as a root.
    body(reparent(T, c, a), 200);
    body(reparent(T, a, null), 200);
  }

  @Test
  @DisplayName("Two re-parents that would each be fine alone cannot together make a cycle")
  void racingReparentsCannotCreateACycle() throws Exception {
    for (int round = 0; round < 5; round++) {
      String x = category(T, "X", null);
      String y = category(T, "Y", null);
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Callable<Integer> xUnderY =
            () -> {
              go.await();
              return reparent(T, x, y).getStatus();
            };
        Callable<Integer> yUnderX =
            () -> {
              go.await();
              return reparent(T, y, x).getStatus();
            };
        Future<Integer> f1 = pool.submit(xUnderY);
        Future<Integer> f2 = pool.submit(yUnderX);
        go.countDown();
        int s1 = f1.get();
        int s2 = f2.get();
        assertThat("one wins, one is refused as a cycle", s1 + s2, is(200 + 409));
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  @DisplayName("Another business's category cannot be a parent, moved or read by name or id")
  void categoriesDoNotCrossBusinesses() {
    String mine = category(T, "Mine", null);
    String theirs = category(RIVAL, "Theirs", null);

    // A parent of another business is not found, on create and on re-parent.
    assertThat(
        body(
            send(
                "POST",
                "/admin/categories",
                "{\"name\":\"Sneaky\",\"parentId\":\"" + theirs + "\"}",
                T,
                "OWNER"),
            400),
        containsString("PARENT_NOT_FOUND"));
    assertThat(body(reparent(T, mine, theirs), 400), containsString("PARENT_NOT_FOUND"));

    // Every role of the other business, naming our id, finds nothing and moves nothing.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          body(
              send("PUT", "/admin/categories/" + mine, "{\"name\":\"Hijacked\"}", RIVAL, role),
              404),
          containsString("CATEGORY_NOT_FOUND"));
      body(send("DELETE", "/admin/categories/" + mine, null, RIVAL, role), 404);
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertThat(
          send("PUT", "/admin/categories/" + mine, "{\"name\":\"Hijacked\"}", RIVAL, role)
              .getStatus(),
          is(403));
      assertThat(
          send("DELETE", "/admin/categories/" + mine, null, RIVAL, role).getStatus(), is(403));
    }
    String stillMine = body(send("GET", "/admin/categories/" + mine, null, T, "OWNER"), 200);
    assertThat(stillMine, not(containsString("Hijacked")));
    assertThat(stillMine, containsString("ACTIVE"));
  }

  // ── SKU and barcode ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("A SKU or barcode already held is a named 409, on create and on update")
  void duplicateSkuAndBarcodeAreNamed() {
    String p = product(T);
    String sku = sku();
    String barcode = freshEan13();
    String first = id(body(variant(T, p, sku, barcode), 201));

    assertThat(body(variant(T, p, sku, null), 409), containsString("PRODUCT_SKU_DUPLICATE"));
    assertThat(
        "the SKU is unique across the business's products, not just this one",
        body(variant(T, product(T), sku, null), 409),
        containsString("PRODUCT_SKU_DUPLICATE"));
    assertThat(
        body(variant(T, p, sku(), barcode), 409), containsString("PRODUCT_BARCODE_DUPLICATE"));

    String second = id(body(variant(T, p, sku(), freshEan13()), 201));
    assertThat(
        body(editVariant(T, p, second, sku, null), 409), containsString("PRODUCT_SKU_DUPLICATE"));
    assertThat(
        body(editVariant(T, p, second, sku(), barcode), 409),
        containsString("PRODUCT_BARCODE_DUPLICATE"));
    // Editing a variant to what it already holds is not a duplicate of itself.
    body(editVariant(T, p, first, sku, barcode), 200);

    // Another business may use the same SKU and barcode.
    body(variant(RIVAL, product(RIVAL), sku, barcode), 201);
  }

  @Test
  @DisplayName("A barcode shaped like a GTIN must pass its check digit; any other code is kept")
  void aGtinShapedBarcodeIsCheckedOthersAreNot() {
    String p = product(T);
    String good = freshEan13();
    String bad = withWrongCheckDigit(freshEan13());

    assertThat(body(variant(T, p, sku(), bad), 400), containsString("PRODUCT_BARCODE_INVALID"));
    body(variant(T, p, sku(), good), 201);

    // GTIN-8, GTIN-12 and GTIN-14 shapes are held to it too.
    String body12 = String.format("0%010d", COUNTER.incrementAndGet() % 10_000_000_000L);
    String upc = body12 + Gtin.checkDigit(body12);
    body(variant(T, p, sku(), upc), 201);
    assertThat(
        body(variant(T, p, sku(), withWrongCheckDigit(upc)), 400),
        containsString("PRODUCT_BARCODE_INVALID"));
    String body8 = String.format("%07d", COUNTER.incrementAndGet() % 10_000_000L);
    assertThat(
        body(variant(T, p, sku(), body8 + ((Gtin.checkDigit(body8) + 1) % 10)), 400),
        containsString("PRODUCT_BARCODE_INVALID"));

    // Not a GTIN by shape: internal codes, letters, other lengths, are accepted as before.
    body(variant(T, p, sku(), "SHELF-" + Ids.newId()), 201);
    body(variant(T, p, sku(), "12345"), 201);
    body(variant(T, p, sku(), "1234567890123456"), 201);
    body(variant(T, p, sku(), null), 201);

    // And on update.
    String v = id(body(variant(T, p, sku(), null), 201));
    assertThat(
        body(editVariant(T, p, v, sku(), bad), 400), containsString("PRODUCT_BARCODE_INVALID"));
    body(editVariant(T, p, v, sku(), freshEan13()), 200);
  }

  @Test
  @DisplayName("A delisted variant does not scan, by barcode or by the scan lookup")
  void aDelistedVariantDoesNotScan() {
    String p = product(T);
    String barcode = freshEan13();
    String sku = sku();
    String v = id(body(variant(T, p, sku, barcode), 201));
    body(send("GET", "/catalog/variants/by-barcode/" + barcode, null, T, "CASHIER"), 200);
    body(scan(sku, T), 200);

    body(send("DELETE", "/admin/products/" + p + "/variants/" + v, null, T, "OWNER"), 200);

    assertThat(
        body(send("GET", "/catalog/variants/by-barcode/" + barcode, null, T, "CASHIER"), 404),
        containsString("VARIANT_NOT_FOUND"));
    assertThat(
        "the scan lookup, which also tries the SKU",
        body(scan(sku, T), 404),
        containsString("VARIANT_NOT_FOUND"));
    body(scan(barcode, T), 404);
    // Another business, naming the same code, finds nothing either way.
    body(send("GET", "/catalog/variants/by-barcode/" + barcode, null, RIVAL, "CASHIER"), 404);
  }

  // ── roles ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A cashier cannot create a category, brand or product; a shopper cannot list them")
  void lowerRolesAreRefused() {
    assertThat(
        send("POST", "/admin/categories", "{\"name\":\"No\"}", T, "CASHIER").getStatus(), is(403));
    assertThat(
        send("POST", "/admin/brands", "{\"name\":\"No\"}", T, "CASHIER").getStatus(), is(403));
    assertThat(
        send("POST", "/admin/products", "{\"name\":\"No\"}", T, "CASHIER").getStatus(), is(403));
    String p = product(T);
    assertThat(
        send("PUT", "/admin/products/" + p, "{\"name\":\"No\"}", T, "CASHIER").getStatus(),
        is(403));
    assertThat(send("GET", "/admin/products", null, T, "CUSTOMER").getStatus(), is(403));
    assertThat(send("GET", "/admin/categories", null, T, "CUSTOMER").getStatus(), is(403));
  }

  @Test
  @DisplayName("Another business's staff, of any role, cannot read, edit or move our product")
  void anotherBusinessCannotTouchOurProduct() {
    String p = product(T);
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      body(send("GET", "/admin/products/" + p, null, RIVAL, role), 404);
      assertThat(
          body(
              send(
                  "PUT",
                  "/admin/products/" + p,
                  "{\"name\":\"Stolen\",\"sellableOnline\":true,\"sellablePos\":true}",
                  RIVAL,
                  role),
              404),
          containsString("PRODUCT_NOT_FOUND"));
      body(send("POST", "/admin/products/" + p + "/discontinue", "", RIVAL, role), 404);
      body(send("POST", "/admin/products/" + p + "/launch", "", RIVAL, role), 404);
      body(send("DELETE", "/admin/products/" + p, null, RIVAL, role), 404);
      body(variant(RIVAL, p, sku(), null), 404);
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertThat(
          send("POST", "/admin/products/" + p + "/discontinue", "", RIVAL, role).getStatus(),
          is(403));
      assertThat(send("DELETE", "/admin/products/" + p, null, RIVAL, role).getStatus(), is(403));
    }
    String mine = body(send("GET", "/admin/products/" + p, null, T, "OWNER"), 200);
    assertThat(mine, containsString("\"status\":\"ACTIVE\""));
    assertThat(mine, not(containsString("Stolen")));
  }

  @Test
  @DisplayName("A cashier cannot discontinue or delist a product")
  void aCashierCannotMoveALine() {
    String p = product(T);
    assertThat(
        send("POST", "/admin/products/" + p + "/discontinue", "", T, "CASHIER").getStatus(),
        is(403));
    assertThat(send("DELETE", "/admin/products/" + p, null, T, "CASHIER").getStatus(), is(403));
    assertThat(
        body(send("GET", "/admin/products/" + p, null, T, "OWNER"), 200),
        containsString("\"status\":\"ACTIVE\""));
  }

  // ── plan limit ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A product beyond the plan's ceiling is refused, and a business with none is not")
  void theProductCeilingHolds() {
    product(CAPPED);
    assertThat(
        body(send("POST", "/admin/products", "{\"name\":\"Second\"}", CAPPED, "OWNER"), 409),
        containsString("PLAN_LIMIT_REACHED"));
    // No plan ceiling for T: it lists as many as it likes.
    product(T);
    product(T);
  }

  // ── images ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "An image at exactly 256 KB, an empty one, or for another business's product is refused")
  void imageBoundaries() {
    String p = product(T);
    Response tooBig =
        target
            .path("/admin/products/" + p + "/image")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity(new byte[256 * 1024], "image/png"));
    assertThat(body(tooBig, 400), containsString("PRODUCT_IMAGE_TOO_LARGE"));

    Response empty =
        target
            .path("/admin/products/" + p + "/image")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity(new byte[0], "image/png"));
    assertThat(body(empty, 400), containsString("PRODUCT_IMAGE_EMPTY"));

    Response foreign =
        target
            .path("/admin/products/" + p + "/image")
            .request()
            .header("X-Tenant-Id", RIVAL)
            .header("X-Roles", "OWNER")
            .put(Entity.entity(new byte[1024], "image/png"));
    assertThat(body(foreign, 404), containsString("PRODUCT_NOT_FOUND"));
    body(send("GET", "/admin/products/" + p, null, T, "OWNER"), 200);
  }
}
