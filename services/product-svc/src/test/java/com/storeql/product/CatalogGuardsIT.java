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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

  // ── refusals the negative-coverage audit found untested (1 Oct 2026) ─────────

  private static final String[] OTHER_MANAGEMENT = {"OWNER", "MANAGER"};
  private static final String[] LOWER_ROLES = {"STOREKEEPER", "CASHIER", "CUSTOMER"};

  /** Rows this test can see for itself: a count over the product schema, read behind the app. */
  private static int count(String sql, String... params) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  private String variantOf(String tenant) {
    return id(body(variant(tenant, product(tenant), sku(), null), 201));
  }

  private Response putImage(String tenant, String product, byte[] bytes, String type) {
    return target
        .path("/admin/products/" + product + "/image")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(bytes, type));
  }

  private static void assertRefused(Response r, int status, String code) {
    assertThat(body(r, status), containsString(code));
  }

  @Test
  @DisplayName("A brand is read, renamed or removed only by its own business's management")
  void aBrandIsChangedOnlyByItsOwnManagement() {
    String name = "Brand " + Ids.newId();
    String brand =
        id(body(send("POST", "/admin/brands", "{\"name\":\"" + name + "\"}", T, "OWNER"), 201));
    String path = "/admin/brands/" + brand;

    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("PUT", path, "{\"name\":\"Hijacked\"}", RIVAL, role), 404, "BRAND_NOT_FOUND");
      assertRefused(send("DELETE", path, null, RIVAL, role), 404, "BRAND_NOT_FOUND");
      assertRefused(send("GET", path, null, RIVAL, role), 404, "BRAND_NOT_FOUND");
    }
    for (String role : LOWER_ROLES) {
      assertRefused(send("PUT", path, "{\"name\":\"Hijacked\"}", T, role), 403, "FORBIDDEN");
      assertRefused(send("DELETE", path, null, T, role), 403, "FORBIDDEN");
    }
    assertRefused(send("PUT", path, "{\"name\":\"  \"}", T, "OWNER"), 400, "VALIDATION_FAILED");
    assertRefused(
        send("GET", "/admin/brands/" + Ids.newId(), null, T, "OWNER"), 404, "BRAND_NOT_FOUND");
    assertThat(
        "nothing moved",
        count(
            "SELECT count(*) FROM product.brands WHERE id = ?::uuid AND name = ?"
                + " AND status = 'ACTIVE'",
            brand,
            name),
        is(1));
  }

  @Test
  @DisplayName("Another business's staff or a lower role cannot delete a product's image")
  void aProductImageIsRemovedOnlyByItsOwnManagement() {
    String p = product(T);
    body(putImage(T, p, new byte[1024], "image/png"), 200);
    String held =
        "SELECT count(*) FROM product.product_images WHERE product_id = ?::uuid"
            + " AND tenant_id = ?::uuid";
    assertThat(count(held, p, T), is(1));

    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("DELETE", "/admin/products/" + p + "/image", null, RIVAL, role),
          404,
          "PRODUCT_NOT_FOUND");
    }
    for (String role : LOWER_ROLES) {
      assertRefused(
          send("DELETE", "/admin/products/" + p + "/image", null, T, role), 403, "FORBIDDEN");
    }
    assertRefused(
        send("DELETE", "/admin/products/" + Ids.newId() + "/image", null, T, "OWNER"),
        404,
        "PRODUCT_NOT_FOUND");
    assertThat("the image stays", count(held, p, T), is(1));
  }

  @Test
  @DisplayName("An image of no named type is refused and stores nothing; a gif is not accepted")
  void anImageOfNoNamedTypeIsRefused() {
    String p = product(T);
    String held = "SELECT count(*) FROM product.product_images WHERE product_id = ?::uuid";
    assertRefused(putImage(T, p, new byte[1024], "image/*"), 400, "PRODUCT_IMAGE_TYPE_INVALID");
    assertThat(count(held, p), is(0));
    assertThat(putImage(T, p, new byte[1024], "image/gif").getStatus(), is(415));
    assertThat(count(held, p), is(0));
  }

  @Test
  @DisplayName("A product with no image, or another business's image, is not found")
  void anImageThatIsNotThereIsNotFound() {
    String withImage = product(T);
    body(putImage(T, withImage, new byte[512], "image/png"), 200);
    String without = product(T);

    assertRefused(
        send("GET", "/catalog/products/" + without + "/image", null, T, "CUSTOMER"),
        404,
        "PRODUCT_IMAGE_NOT_FOUND");
    assertRefused(
        send("GET", "/catalog/products/" + Ids.newId() + "/image", null, T, "CUSTOMER"),
        404,
        "PRODUCT_IMAGE_NOT_FOUND");
    // Naming our product from another storefront shows nothing of ours.
    assertRefused(
        send("GET", "/catalog/products/" + withImage + "/image", null, RIVAL, "CUSTOMER"),
        404,
        "PRODUCT_IMAGE_NOT_FOUND");
    assertThat(
        send("GET", "/catalog/products/" + withImage + "/image", null, T, "CUSTOMER").getStatus(),
        is(200));
  }

  @Test
  @DisplayName("A catalogue read that names no shop is refused rather than guessed")
  void aCatalogueReadThatNamesNoShopIsRefused() {
    assertRefused(target.path("/catalog/products").request().get(), 400, "NO_STOREFRONT");
    assertRefused(
        target.path("/catalog/products/" + Ids.newId() + "/image").request().get(),
        400,
        "NO_STOREFRONT");
    assertRefused(
        target.path("/catalog/scan").queryParam("code", "12345").request().get(),
        400,
        "NO_STOREFRONT");
  }

  // ── supplier CSV ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A supplier CSV that cannot be read, or sent by a lower role, imports nothing")
  void aSupplierCsvThatCannotBeReadImportsNothing() {
    String products = "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid";
    String categories = "SELECT count(*) FROM product.categories WHERE tenant_id = ?::uuid";
    int productsBefore = count(products, T);
    int categoriesBefore = count(categories, T);
    String path = "/admin/import/supplier-csv";

    assertRefused(
        send("POST", path, "{\"csv\":\"Product Description,Category\\n\"}", T, "OWNER"),
        400,
        "CSV_EMPTY");
    assertRefused(
        send("POST", path, "{\"csv\":\"SKU,Quantity\\nA1,3\"}", T, "OWNER"),
        400,
        "CSV_MISSING_COLUMNS");
    assertRefused(send("POST", path, "{\"csv\":\"  \"}", T, "OWNER"), 400, "INVALID_BODY");
    assertRefused(send("POST", path, "{\"csv\":\"\"}", T, "OWNER"), 400, "INVALID_BODY");

    String readable = "{\"csv\":\"Product Description,Category\\nTea " + Ids.newId() + ",Drinks\"}";
    for (String role : new String[] {"CASHIER", "CUSTOMER"}) {
      assertRefused(send("POST", path, readable, T, role), 403, "FORBIDDEN");
    }
    assertThat(count(products, T), is(productsBefore));
    assertThat(count(categories, T), is(categoriesBefore));
  }

  // ── attribute groups ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("An attribute group or a variant's values that are not there are not found")
  void anAttributeGroupOrItsValuesThatAreNotThereAreNotFound() {
    String v = variantOf(T);
    String base = "/admin/products/variants/" + v + "/attribute-groups/";
    String held =
        "SELECT count(*) FROM product.variant_attribute_group_values WHERE variant_id = ?::uuid";

    assertRefused(
        send("GET", "/admin/attribute-groups/BOGUS_GROUP", null, T, "OWNER"),
        404,
        "ATTRIBUTE_GROUP_NOT_FOUND");
    assertRefused(
        send("PUT", base + "NONEXISTENT", "{\"values\":\"{}\"}", T, "OWNER"),
        404,
        "ATTRIBUTE_GROUP_NOT_FOUND");
    assertRefused(
        send("GET", base + "WEB", null, T, "OWNER"), 404, "ATTRIBUTE_GROUP_VALUES_NOT_FOUND");
    assertRefused(
        send("DELETE", base + "WEB", null, T, "OWNER"), 404, "ATTRIBUTE_GROUP_VALUES_NOT_FOUND");
    assertThat("nothing was written", count(held, v), is(0));

    body(send("PUT", base + "WEB", "{\"values\":\"{}\"}", T, "OWNER"), 200);
    assertThat(count(held, v), is(1));
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(send("GET", base + "WEB", null, RIVAL, role), 404, "VARIANT_NOT_FOUND");
      assertRefused(
          send("DELETE", base + "WEB", null, RIVAL, role), 404, "ATTRIBUTE_GROUP_VALUES_NOT_FOUND");
    }
    assertThat("the other business removed nothing", count(held, v), is(1));
  }

  // ── catalog groups ───────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "An unknown or another business's catalog group is not found; an odd type is refused")
  void aCatalogGroupAndItsElementsAreRefusedWhenWrong() {
    String group =
        id(
            body(
                send(
                    "POST",
                    "/admin/catalog-groups",
                    "{\"name\":\"Group " + Ids.newId() + "\"}",
                    T,
                    "OWNER"),
                201));
    String v = variantOf(T);
    String assigned =
        "SELECT count(*) FROM product.variant_catalog_assignments WHERE variant_id = ?::uuid";
    String elements =
        "SELECT count(*) FROM product.catalog_group_elements WHERE group_id = ?::uuid";
    String element =
        "{\"elementName\":\"colour\",\"dataType\":\"TEXT\",\"required\":false,\"sortOrder\":0}";

    assertRefused(
        send("GET", "/admin/catalog-groups/" + Ids.newId(), null, T, "OWNER"),
        404,
        "CATALOG_GROUP_NOT_FOUND");
    assertRefused(
        send(
            "POST",
            "/admin/products/variants/" + v + "/catalog-assignment",
            "{\"groupId\":\"" + Ids.newId() + "\"}",
            T,
            "OWNER"),
        404,
        "CATALOG_GROUP_NOT_FOUND");
    assertThat(count(assigned, v), is(0));

    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("GET", "/admin/catalog-groups/" + group, null, RIVAL, role),
          404,
          "CATALOG_GROUP_NOT_FOUND");
      assertRefused(
          send("DELETE", "/admin/catalog-groups/" + group, null, RIVAL, role),
          404,
          "CATALOG_GROUP_NOT_FOUND");
      assertRefused(
          send("POST", "/admin/catalog-groups/" + group + "/elements", element, RIVAL, role),
          404,
          "CATALOG_GROUP_NOT_FOUND");
    }
    assertRefused(
        send(
            "POST",
            "/admin/catalog-groups/" + group + "/elements",
            "{\"elementName\":\"x\",\"dataType\":\"ENUM\",\"required\":false,\"sortOrder\":0}",
            T,
            "OWNER"),
        400,
        "INVALID_DATA_TYPE");
    assertThat(count(elements, group), is(0));

    String made =
        id(
            body(
                send("POST", "/admin/catalog-groups/" + group + "/elements", element, T, "OWNER"),
                201));
    assertRefused(
        send(
            "DELETE",
            "/admin/catalog-groups/" + group + "/elements/" + Ids.newId(),
            null,
            T,
            "OWNER"),
        404,
        "ELEMENT_NOT_FOUND");
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("DELETE", "/admin/catalog-groups/" + group + "/elements/" + made, null, RIVAL, role),
          404,
          "ELEMENT_NOT_FOUND");
    }
    assertThat("the element stays", count(elements, group), is(1));
    assertThat(
        "the group stays active",
        count(
            "SELECT count(*) FROM product.catalog_groups WHERE id = ?::uuid AND status = 'ACTIVE'",
            group),
        is(1));
  }

  // ── category sets ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A category set, member or assignment that is not there is not found")
  void aCategorySetMemberOrAssignmentThatIsNotThereIsNotFound() {
    String set =
        id(
            body(
                send(
                    "POST",
                    "/admin/category-sets",
                    "{\"name\":\"Set "
                        + Ids.newId()
                        + "\",\"purpose\":\"MERCHANDISING\",\"controlled\":false}",
                    T,
                    "OWNER"),
                201));
    String category = category(T, "Member", null);
    String v = variantOf(T);
    String sets = "SELECT count(*) FROM product.category_sets WHERE id = ?::uuid";

    assertRefused(
        send("GET", "/admin/category-sets/" + Ids.newId(), null, T, "OWNER"),
        404,
        "CATEGORY_SET_NOT_FOUND");
    assertRefused(
        send("DELETE", "/admin/category-sets/" + Ids.newId(), null, T, "OWNER"),
        404,
        "CATEGORY_SET_NOT_FOUND");
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("GET", "/admin/category-sets/" + set, null, RIVAL, role),
          404,
          "CATEGORY_SET_NOT_FOUND");
      assertRefused(
          send("DELETE", "/admin/category-sets/" + set, null, RIVAL, role),
          404,
          "CATEGORY_SET_NOT_FOUND");
    }
    assertThat("the set stays", count(sets, set), is(1));

    // A category that was never added is not a member to remove.
    assertRefused(
        send("DELETE", "/admin/category-sets/" + set + "/members/" + category, null, T, "OWNER"),
        404,
        "CATEGORY_SET_MEMBER_NOT_FOUND");
    // A variant never placed in the set has no assignment to remove.
    assertRefused(
        send(
            "DELETE",
            "/admin/products/variants/" + v + "/category-set-assignments/" + set,
            null,
            T,
            "OWNER"),
        404,
        "CATEGORY_SET_ASSIGNMENT_NOT_FOUND");
  }

  // ── containers ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A container type or link that is not there, or is another business's, is not found")
  void aContainerTypeOrLinkThatIsNotThereIsNotFound() {
    String type =
        id(
            body(
                send(
                    "POST",
                    "/admin/container-types",
                    "{\"code\":\"C-" + Ids.newId() + "\",\"name\":\"Case\"}",
                    T,
                    "OWNER"),
                201));
    String v = variantOf(T);
    String links =
        "SELECT count(*) FROM product.variant_container_links WHERE variant_id = ?::uuid";

    assertRefused(
        send("GET", "/admin/container-types/" + Ids.newId(), null, T, "OWNER"),
        404,
        "CONTAINER_TYPE_NOT_FOUND");
    assertRefused(
        send(
            "POST",
            "/admin/products/variants/" + v + "/container-links",
            "{\"containerTypeId\":\"" + Ids.newId() + "\",\"qtyPerContainer\":6}",
            T,
            "OWNER"),
        404,
        "CONTAINER_TYPE_NOT_FOUND");
    assertThat(count(links, v), is(0));
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("GET", "/admin/container-types/" + type, null, RIVAL, role),
          404,
          "CONTAINER_TYPE_NOT_FOUND");
    }

    String link =
        id(
            body(
                send(
                    "POST",
                    "/admin/products/variants/" + v + "/container-links",
                    "{\"containerTypeId\":\"" + type + "\",\"qtyPerContainer\":6}",
                    T,
                    "OWNER"),
                201));
    assertRefused(
        send(
            "DELETE",
            "/admin/products/variants/" + v + "/container-links/" + Ids.newId(),
            null,
            T,
            "OWNER"),
        404,
        "CONTAINER_LINK_NOT_FOUND");
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send(
              "DELETE",
              "/admin/products/variants/" + v + "/container-links/" + link,
              null,
              RIVAL,
              role),
          404,
          "CONTAINER_LINK_NOT_FOUND");
    }
    assertThat("the link stays", count(links, v), is(1));
  }

  // ── units of measure ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("A conversion with no path, or another business's item conversion, is not found")
  void aConversionWithNoPathIsNotFound() {
    Response noPath =
        target
            .path("/admin/uom/convert")
            .queryParam("from", "BANANA")
            .queryParam("to", "EA")
            .queryParam("qty", "1")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertRefused(noPath, 404, "CONVERSION_NOT_FOUND");
    assertRefused(
        send("DELETE", "/admin/uom/item-conversions/" + Ids.newId(), null, T, "OWNER"),
        404,
        "CONVERSION_NOT_FOUND");

    String v = variantOf(T);
    String made =
        id(
            body(
                send(
                    "POST",
                    "/admin/uom/item-conversions",
                    "{\"variantId\":\""
                        + v
                        + "\",\"fromUom\":\"BOX\",\"toUom\":\"EA\",\"factor\":12}",
                    T,
                    "OWNER"),
                200));
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("DELETE", "/admin/uom/item-conversions/" + made, null, RIVAL, role),
          404,
          "CONVERSION_NOT_FOUND");
    }
    assertThat(
        "the conversion stays",
        count("SELECT count(*) FROM product.uom_item_conversions WHERE id = ?::uuid", made),
        is(1));
  }

  // ── cross-references, relationships, revisions ───────────────────────────────

  @Test
  @DisplayName("A cross-reference to an unknown party type, or one not there, is refused")
  void aCrossReferenceIsRefusedWhenWrong() {
    String v = variantOf(T);
    String base = "/admin/products/variants/" + v + "/cross-references";
    String held = "SELECT count(*) FROM product.item_cross_references WHERE variant_id = ?::uuid";

    assertRefused(
        send(
            "POST",
            base,
            "{\"partyType\":\"BROKER\",\"partyId\":\""
                + Ids.newId()
                + "\",\"crossRefNumber\":\"X1\"}",
            T,
            "OWNER"),
        400,
        "INVALID_PARTY_TYPE");
    assertThat("nothing was recorded", count(held, v), is(0));

    String ref =
        id(
            body(
                send(
                    "POST",
                    base,
                    "{\"partyType\":\"SUPPLIER\",\"partyId\":\""
                        + Ids.newId()
                        + "\",\"crossRefNumber\":\"X2\"}",
                    T,
                    "OWNER"),
                201));
    assertRefused(
        send("DELETE", base + "/" + Ids.newId(), null, T, "OWNER"), 404, "CROSS_REF_NOT_FOUND");
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("DELETE", base + "/" + ref, null, RIVAL, role), 404, "CROSS_REF_NOT_FOUND");
    }
    assertThat("the reference stays", count(held, v), is(1));
  }

  @Test
  @DisplayName("A relationship of an unknown type, to itself, or not there is refused")
  void aRelationshipIsRefusedWhenWrong() {
    String v0 = variantOf(T);
    String v1 = variantOf(T);
    String theirs = variantOf(RIVAL);
    String base = "/admin/products/variants/" + v0 + "/relationships";
    String held = "SELECT count(*) FROM product.item_relationships WHERE variant_id = ?::uuid";

    assertRefused(
        send(
            "POST",
            base,
            "{\"relatedVariantId\":\"" + v1 + "\",\"relationshipType\":\"ENEMIES\"}",
            T,
            "OWNER"),
        400,
        "INVALID_RELATIONSHIP_TYPE");
    assertRefused(
        send(
            "POST",
            base,
            "{\"relatedVariantId\":\"" + v0 + "\",\"relationshipType\":\"SUBSTITUTE\"}",
            T,
            "OWNER"),
        400,
        "SELF_RELATIONSHIP");
    assertRefused(
        send(
            "POST",
            base,
            "{\"relatedVariantId\":\"" + theirs + "\",\"relationshipType\":\"SUBSTITUTE\"}",
            T,
            "OWNER"),
        404,
        "VARIANT_NOT_FOUND");
    assertThat("nothing was recorded", count(held, v0), is(0));

    String made =
        id(
            body(
                send(
                    "POST",
                    base,
                    "{\"relatedVariantId\":\"" + v1 + "\",\"relationshipType\":\"SUBSTITUTE\"}",
                    T,
                    "OWNER"),
                201));
    assertRefused(
        send("DELETE", base + "/" + Ids.newId(), null, T, "OWNER"), 404, "RELATIONSHIP_NOT_FOUND");
    for (String role : OTHER_MANAGEMENT) {
      assertRefused(
          send("DELETE", base + "/" + made, null, RIVAL, role), 404, "RELATIONSHIP_NOT_FOUND");
    }
    assertThat("the link stays", count(held, v0), is(1));
  }

  @Test
  @DisplayName("A revision that is not there is not found")
  void aRevisionThatIsNotThereIsNotFound() {
    String v = variantOf(T);
    String base = "/admin/products/variants/" + v + "/revisions";
    assertRefused(send("GET", base + "/current", null, T, "OWNER"), 404, "REVISION_NOT_FOUND");
    assertRefused(
        send("GET", base + "/" + Ids.newId(), null, T, "OWNER"), 404, "REVISION_NOT_FOUND");
    assertRefused(
        send(
            "GET",
            "/admin/products/variants/" + Ids.newId() + "/revisions/current",
            null,
            T,
            "OWNER"),
        404,
        "REVISION_NOT_FOUND");
    assertThat(
        "no revision was made by asking",
        count("SELECT count(*) FROM product.item_revisions WHERE variant_id = ?::uuid", v),
        is(0));
  }
}
