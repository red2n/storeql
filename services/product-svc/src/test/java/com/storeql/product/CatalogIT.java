package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.product.repo.ProductRepository;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the product catalog against real Postgres (Testcontainers): create brand →
 * category → product → variant, public browse, tenant isolation, duplicate SKU 409, delist removes
 * from public list.
 */
@HelidonTest
class CatalogIT {

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;

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
    // The tenants this suite acts for, as tenant-svc describes them: British, so GPSR's
    // online-offer
    // rule does not bind them and creating a product asks nothing more (01.12).
    TenantSvcStub.start()
        .with(CatalogIT.TENANT_A, "GBP", "GB")
        .with(CatalogIT.TENANT_B, "GBP", "GB")
        .with("01a090ae-611e-7011-ae7d-1bd68c966ff6", "GBP", "GB");
  }

  private static final String TENANT_A = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String TENANT_B = "01a090ae-611e-700f-b645-a14095230b77";

  @Inject WebTarget target;

  /** Any one of the module's repositories is the outbox store the scheduled purge runs through. */
  @Inject ProductRepository outboxStore;

  @AfterAll
  static void stopDb() {
    PG.stop();
    REDIS.stop();
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String path, String tenant) {
    return target.path(path).request().header("X-Tenant-Id", tenant).get(String.class);
  }

  /** Like {@link #get} but for /admin/... paths, which require a staff role. */
  private String getAdmin(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  private Response listProductsAdmin(String tenant, int limit, String after) {
    WebTarget t = target.path("/admin/products").queryParam("limit", limit);
    if (after != null) t = t.queryParam("after", after);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  private Response put(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Bypasses the app entirely — proves a read came from cache rather than the DB. */
  private static void rawUpdateProductName(String productId, String name) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement("UPDATE product.products SET name = ? WHERE id = ?::uuid")) {
      ps.setString(1, name);
      ps.setString(2, productId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void catalogFlowAndIsolation() {
    // product
    Response p = post("/admin/products", "{\"name\":\"Rice 5kg\"}", TENANT_A);
    assertThat(p.getStatus(), is(201));
    String productId = field(p.readEntity(String.class), "id");

    // variant
    Response v =
        post("/admin/products/" + productId + "/variants", "{\"sku\":\"RICE-5KG\"}", TENANT_A);
    assertThat(v.getStatus(), is(201));

    // duplicate sku → 409
    Response dup =
        post("/admin/products/" + productId + "/variants", "{\"sku\":\"RICE-5KG\"}", TENANT_A);
    assertThat(dup.getStatus(), is(409));

    // public list shows it (tenant A)
    assertThat(get("/catalog/products", TENANT_A), containsString("Rice 5kg"));

    // tenant B sees nothing (isolation)
    assertThat(get("/catalog/products", TENANT_B), not(containsString("Rice 5kg")));

    // delist removes from public list
    target
        .path("/admin/products/" + productId)
        .request()
        .header("X-Tenant-Id", TENANT_A)
        .header("X-Roles", "OWNER")
        .delete();
    assertThat(get("/catalog/products", TENANT_A), not(containsString("Rice 5kg")));
  }

  /**
   * The update used to run its UPDATE and then re-read the row. Against a delisted variant the
   * UPDATE matched nothing, and the re-read still answered 200 with the old values, as if the edit
   * had been saved.
   */
  @Test
  void aVariantEditIsSavedOrRefusedNeverSilentlyDropped() {
    String productId =
        field(
            post("/admin/products", "{\"name\":\"Kettle\"}", TENANT_A).readEntity(String.class),
            "id");
    String variantId =
        field(
            post("/admin/products/" + productId + "/variants", "{\"sku\":\"KETTLE-1\"}", TENANT_A)
                .readEntity(String.class),
            "id");
    String path = "/admin/products/" + productId + "/variants/" + variantId;

    Response edited = put(path, "{\"sku\":\"KETTLE-1\",\"manufacturerPn\":\"MFR-A\"}", TENANT_A);
    assertThat(edited.getStatus(), is(200));
    assertThat(edited.readEntity(String.class), containsString("\"manufacturerPn\":\"MFR-A\""));

    target.path(path).request().header("X-Tenant-Id", TENANT_A).header("X-Roles", "OWNER").delete();
    Response afterDelist =
        put(path, "{\"sku\":\"KETTLE-1\",\"manufacturerPn\":\"MFR-B\"}", TENANT_A);
    assertThat(afterDelist.getStatus(), is(409));
    assertThat(afterDelist.readEntity(String.class), containsString("VARIANT_NOT_ACTIVE"));
    assertThat(getAdmin(path, TENANT_A), containsString("\"manufacturerPn\":\"MFR-A\""));
  }

  @Test
  void resolveVariantsReturnsNameAndSkuAndIsolatesTenants() {
    Response p = post("/admin/products", "{\"name\":\"Resolve Me\"}", TENANT_A);
    String productId = field(p.readEntity(String.class), "id");
    Response v =
        post("/admin/products/" + productId + "/variants", "{\"sku\":\"RESOLVE-1\"}", TENANT_A);
    String variantId = field(v.readEntity(String.class), "id");

    // Resolve maps the variant UUID to its product name + SKU.
    String resolved = resolve(variantId, TENANT_A);
    assertThat(resolved, containsString("Resolve Me"));
    assertThat(resolved, containsString("RESOLVE-1"));
    assertThat(resolved, containsString(variantId));

    // Another tenant cannot resolve tenant A's variant (isolation).
    assertThat(resolve(variantId, TENANT_B), not(containsString("Resolve Me")));

    // A malformed id is a 400, not a 500.
    Response bad =
        target
            .path("/admin/products/variants/resolve")
            .queryParam("ids", "not-a-uuid")
            .request()
            .header("X-Tenant-Id", TENANT_A)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(bad.getStatus(), is(400));
  }

  private String resolve(String variantId, String tenant) {
    return target
        .path("/admin/products/variants/resolve")
        .queryParam("ids", variantId)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  @Test
  void getProductIsCachedAndInvalidatedOnUpdate() {
    Response p = post("/admin/products", "{\"name\":\"Cached Widget\"}", TENANT_A);
    assertThat(p.getStatus(), is(201));
    String productId = field(p.readEntity(String.class), "id");

    // first read — populates the cache
    assertThat(get("/catalog/products/" + productId, TENANT_A), containsString("Cached Widget"));

    // mutate the row directly in Postgres, bypassing the app and its cache eviction
    rawUpdateProductName(productId, "Mutated Behind Cache");

    // still served from cache — proves the read isn't hitting Postgres every time
    assertThat(get("/catalog/products/" + productId, TENANT_A), containsString("Cached Widget"));

    // a real update goes through the app, which evicts the cache key
    Response updated =
        put(
            "/admin/products/" + productId,
            "{\"name\":\"Updated Widget\",\"sellableOnline\":true,\"sellablePos\":true}",
            TENANT_A);
    assertThat(updated.getStatus(), is(200));

    // next read reflects the update, not the raw mutation — cache was invalidated, not just expired
    assertThat(get("/catalog/products/" + productId, TENANT_A), containsString("Updated Widget"));
    assertThat(
        get("/catalog/products/" + productId, TENANT_A),
        not(containsString("Mutated Behind Cache")));
  }

  @Test
  void bulkImportSharedCategoryResolvesToOneRowAcrossProducts() {
    // Two products in the same import sharing a category and brand — regression guard for the
    // category/brand name-to-id caching in ProductService.bulkImport: both should resolve to the
    // same category/brand row, not each trigger their own independent lookup gone wrong.
    Response r =
        post(
            "/admin/import",
            "{\"categories\":[{\"name\":\"Grocery\"}],"
                + "\"products\":["
                + "{\"name\":\"Rice\",\"categoryName\":\"Grocery\",\"brandName\":\"Acme\","
                + "\"variants\":[{\"sku\":\"BULK-RICE\"}]},"
                + "{\"name\":\"Pasta\",\"categoryName\":\"Grocery\",\"brandName\":\"Acme\","
                + "\"variants\":[{\"sku\":\"BULK-PASTA\"}]}"
                + "]}",
            TENANT_A);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"categoriesCreated\":1"));
    assertThat(body, containsString("\"productsCreated\":2"));
    assertThat(body, containsString("\"variantsCreated\":2"));
    assertThat(body, containsString("\"errors\":[]"));

    String riceProductId = fieldNear(body, "\"sku\":\"BULK-RICE\"", "productId");
    String pastaProductId = fieldNear(body, "\"sku\":\"BULK-PASTA\"", "productId");

    String riceCategoryId =
        field(getAdmin("/admin/products/" + riceProductId, TENANT_A), "categoryId");
    String pastaCategoryId =
        field(getAdmin("/admin/products/" + pastaProductId, TENANT_A), "categoryId");
    assertThat(riceCategoryId, is(pastaCategoryId));
  }

  /**
   * F12 follow-up (AUDIT.md): {@code ProductService.bulkImport} never called {@code
   * Validations.validate()} per-item, so per-item constraints (e.g. {@code
   * ImportCategoryRequest.name @NotBlank}) were dead code — a blank category name would silently
   * create a blank-named category instead of failing. Proves the fix without breaking the
   * documented partial-success contract: the bad category lands in {@code errors}, but the
   * well-formed product in the same request still imports.
   */
  @Test
  void bulkImportRejectsAnInvalidItemButStillImportsTheRest() {
    Response r =
        post(
            "/admin/import",
            "{\"categories\":[{\"name\":\"\"}],"
                + "\"products\":["
                + "{\"name\":\"Good Product\",\"variants\":[{\"sku\":\"BULK-GOOD-SKU\"}]}"
                + "]}",
            TENANT_A);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"categoriesCreated\":0"));
    assertThat(body, containsString("\"productsCreated\":1"));
    assertThat(body, containsString("\"variantsCreated\":1"));
    assertThat(body, not(containsString("\"errors\":[]")));
    assertThat(body, containsString("\"item\":\"category:\""));
    assertThat(body, containsString("Request validation failed"));

    // The blank-named category was never created — not silently persisted as "".
    assertThat(getAdmin("/admin/categories", TENANT_A), not(containsString("\"name\":\"\"")));
  }

  /**
   * Same F12 follow-up, at the nested variant level: {@code ImportVariantRequest.sku @NotBlank} was
   * equally dead code inside the products loop's inner variant loop. {@code
   * ImportProductRequest.variants} already carries {@code @Valid} (prior F12 fix), so validating
   * the whole product cascades into its variants — a product with a blank-SKU variant fails as one
   * whole item (neither the product nor any of its variants are created), but a separate,
   * well-formed product in the same batch still imports. That is the granularity the
   * partial-success contract actually promises at this layer: whole-item isolation, not
   * per-sibling-variant isolation within a single bad product.
   */
  @Test
  void bulkImportRejectsAnInvalidVariantAndStillImportsOtherProducts() {
    Response r =
        post(
            "/admin/import",
            "{\"products\":["
                + "{\"name\":\"Bad Product\",\"variants\":[{\"sku\":\"\"}]},"
                + "{\"name\":\"Good Product\",\"variants\":[{\"sku\":\"BULK-SIBLING-SKU\"}]}"
                + "]}",
            TENANT_A);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"productsCreated\":1"));
    assertThat(body, containsString("\"variantsCreated\":1"));
    assertThat(body, containsString("BULK-SIBLING-SKU"));
    assertThat(body, not(containsString("\"errors\":[]")));
    assertThat(body, containsString("\"item\":\"product:Bad Product\""));
    assertThat(body, containsString("Request validation failed"));
  }

  /**
   * A hole in a list of rows is refused whole, the place named, and nothing of the request is
   * committed. It used to reach the row loop, whose error handler read the row's name and failed: a
   * {@code 500}, after the category and the row before the hole had been written, which a retry
   * then met as its own duplicates.
   */
  @Test
  void bulkImportWithANullRowIsRefusedWholeAndWritesNothing() throws Exception {
    String suffix = Ids.newId().toString();
    String category = "Holes " + suffix;
    String product = "Before the hole " + suffix;
    String sku = "HOLE-" + suffix;
    int productEvents = outboxCount(TENANT_A, "ProductCreated");
    int variantEvents = outboxCount(TENANT_A, "VariantCreated");

    for (String body :
        new String[] {
          "{\"categories\":[{\"name\":\""
              + category
              + "\"}],\"products\":[{\"name\":\""
              + product
              + "\",\"categoryName\":\""
              + category
              + "\",\"variants\":[{\"sku\":\""
              + sku
              + "\"}]},null]}",
          "{\"categories\":[{\"name\":\""
              + category
              + "\"},null],\"products\":[{\"name\":\""
              + product
              + "\",\"variants\":[{\"sku\":\""
              + sku
              + "\"}]}]}"
        }) {
      Response r = post("/admin/import", body, TENANT_A);
      String answer = r.readEntity(String.class);
      assertThat(answer, r.getStatus(), is(400));
      assertThat(answer, containsString("VALIDATION_FAILED"));
      assertThat(
          answer,
          body.contains("},null]}")
              ? containsString("products[1]: must not be null")
              : containsString("categories[1]: must not be null"));
    }

    assertThat("the category was not written", namedRows("categories", category), is(0));
    assertThat("nor the row before the hole", namedRows("products", product), is(0));
    assertThat(skuRows(sku), is(0));
    assertThat(outboxCount(TENANT_A, "ProductCreated"), is(productEvents));
    assertThat(outboxCount(TENANT_A, "VariantCreated"), is(variantEvents));

    // The same sheet without its hole imports: the refusal was the hole's, not the rows'.
    Response clean =
        post(
            "/admin/import",
            "{\"categories\":[{\"name\":\""
                + category
                + "\"}],\"products\":[{\"name\":\""
                + product
                + "\",\"categoryName\":\""
                + category
                + "\",\"variants\":[{\"sku\":\""
                + sku
                + "\"}]}]}",
            TENANT_A);
    String imported = clean.readEntity(String.class);
    assertThat(imported, clean.getStatus(), is(200));
    assertThat(imported, containsString("\"errors\":[]"));
    assertThat(namedRows("categories", category), is(1));
    assertThat(namedRows("products", product), is(1));
    assertThat(skuRows(sku), is(1));
  }

  /** Rows of TENANT_A's {@code product.<table>} with this name. */
  private static int namedRows(String table, String name) throws SQLException {
    String sql =
        switch (table) {
          case "categories" ->
              "SELECT count(*) FROM product.categories WHERE tenant_id = ?::uuid AND name = ?";
          case "products" ->
              "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid AND name = ?";
          default -> throw new IllegalArgumentException(table);
        };
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, TENANT_A);
      ps.setString(2, name);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** Variants of TENANT_A with this SKU. */
  private static int skuRows(String sku) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM product.product_variants WHERE tenant_id = ?::uuid AND sku ="
                    + " ?")) {
      ps.setString(1, TENANT_A);
      ps.setString(2, sku);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  @Test
  void blankNameIs400() {
    Response bad = post("/admin/products", "{\"name\":\"\"}", TENANT_A);
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("VALIDATION_FAILED"));
  }

  /**
   * Characterization coverage for {@code BrandRepository} (extracted from {@code ProductRepository}
   * — F2, AUDIT.md) — pins down current CRUD + tenant-isolation behavior since these endpoints
   * previously had none beyond the incidental exercise inside bulk-import.
   */
  @Test
  void brandsCrudAndTenantIsolation() {
    Response created = post("/admin/brands", "{\"name\":\"Acme\"}", TENANT_A);
    assertThat(created.getStatus(), is(201));
    String brandId = field(created.readEntity(String.class), "id");

    assertThat(getAdmin("/admin/brands", TENANT_A), containsString("Acme"));
    assertThat(getAdmin("/admin/brands", TENANT_B), not(containsString("Acme")));

    Response renamed = put("/admin/brands/" + brandId, "{\"name\":\"Acme Renamed\"}", TENANT_A);
    assertThat(renamed.getStatus(), is(200));
    assertThat(getAdmin("/admin/brands/" + brandId, TENANT_A), containsString("Acme Renamed"));

    Response deactivated = delete("/admin/brands/" + brandId, TENANT_A);
    assertThat(deactivated.getStatus(), is(200));
    assertThat(getAdmin("/admin/brands", TENANT_A), not(containsString("Acme Renamed")));
  }

  /**
   * Characterization coverage for {@code CategoryRepository} (extracted from {@code
   * ProductRepository} — F2, AUDIT.md): parent/child linkage, tenant isolation, bad-parent 400, and
   * rename/deactivate — previously uncovered beyond bulk-import's incidental exercise.
   */
  @Test
  void categoriesCrudWithParentAndTenantIsolation() {
    Response parent = post("/admin/categories", "{\"name\":\"Beverages\"}", TENANT_A);
    assertThat(parent.getStatus(), is(201));
    String parentId = field(parent.readEntity(String.class), "id");

    Response child =
        post(
            "/admin/categories",
            "{\"name\":\"Soft Drinks\",\"parentId\":\"" + parentId + "\"}",
            TENANT_A);
    assertThat(child.getStatus(), is(201));
    String childBody = child.readEntity(String.class);
    String childId = field(childBody, "id");
    assertThat(field(childBody, "parentId"), is(parentId));

    assertThat(getAdmin("/admin/categories", TENANT_A), containsString("Soft Drinks"));
    assertThat(getAdmin("/admin/categories", TENANT_B), not(containsString("Soft Drinks")));

    // Unknown parentId is a 400 (PARENT_NOT_FOUND), not a 500.
    Response badParent =
        post(
            "/admin/categories",
            "{\"name\":\"Orphan\",\"parentId\":\"01a090ae-611e-701d-9d60-a9d7516ed03b\"}",
            TENANT_A);
    assertThat(badParent.getStatus(), is(400));

    Response renamed =
        put(
            "/admin/categories/" + childId,
            "{\"name\":\"Fizzy Drinks\",\"parentId\":\"" + parentId + "\"}",
            TENANT_A);
    assertThat(renamed.getStatus(), is(200));
    assertThat(getAdmin("/admin/categories/" + childId, TENANT_A), containsString("Fizzy Drinks"));

    Response deactivated = delete("/admin/categories/" + childId, TENANT_A);
    assertThat(deactivated.getStatus(), is(200));
    assertThat(getAdmin("/admin/categories", TENANT_A), not(containsString("Fizzy Drinks")));
  }

  private Response delete(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .delete();
  }

  @Test
  void listProductsAdminPaginatesWithCursor() {
    // Dedicated tenant so products created by other tests never leak into these pages.
    String tenant = "01a090ae-611e-7011-ae7d-1bd68c966ff6";
    var allIds = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      Response r = post("/admin/products", "{\"name\":\"Paginate " + i + "\"}", tenant);
      assertThat(r.getStatus(), is(201));
      allIds.add(field(r.readEntity(String.class), "id"));
    }

    Response p1 = listProductsAdmin(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllIds(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    Response p2 = listProductsAdmin(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllIds(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen, is(allIds));
  }

  @Test
  void categorySetMemberAndAssignmentEndpointsRejectBlankIds() {
    Response csR =
        post(
            "/admin/category-sets",
            "{\"name\":\"Seasonal\",\"purpose\":\"MERCHANDISING\",\"controlled\":false}",
            TENANT_A);
    assertThat(csR.getStatus(), is(201));
    String setId = field(csR.readEntity(String.class), "id");

    // AddCategorySetMemberRequest.categoryId is @NotBlank — an empty string must be rejected.
    Response memberR =
        post("/admin/category-sets/" + setId + "/members", "{\"categoryId\":\"\"}", TENANT_A);
    assertThat(memberR.getStatus(), is(400));

    // AssignVariantCategorySetRequest.setId/categoryId are @NotBlank.
    // The variant is the business's, found before the body is read (3 Oct 2026).
    String productId =
        field(
            post("/admin/products", "{\"name\":\"Blank ids\"}", TENANT_A).readEntity(String.class),
            "id");
    String variantId =
        field(
            post("/admin/products/" + productId + "/variants", "{\"sku\":\"BLANK-1\"}", TENANT_A)
                .readEntity(String.class),
            "id");
    Response assignR =
        post(
            "/admin/products/variants/" + variantId + "/category-set-assignments",
            "{\"setId\":\"\",\"categoryId\":\"\"}",
            TENANT_A);
    assertThat(assignR.getStatus(), is(400));
  }

  private static java.util.Set<String> extractAllIds(String json) {
    var ids = new java.util.HashSet<String>();
    int from = 0;
    while (true) {
      int start = json.indexOf("\"id\":\"", from);
      if (start < 0) break;
      start += 6;
      int end = json.indexOf('"', start);
      ids.add(json.substring(start, end));
      from = end;
    }
    return ids;
  }

  /** Returns meta.nextCursor, or null when the field is absent/null (no further page). */
  private static String extractNextCursor(String json) {
    int key = json.indexOf("\"nextCursor\":");
    if (key < 0) return null;
    int valueStart = key + "\"nextCursor\":".length();
    if (json.startsWith("null", valueStart)) return null;
    int start = json.indexOf('"', valueStart) + 1;
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  /**
   * Find {@code name} in the JSON object that contains {@code marker}. JSON-B serialises record
   * components alphabetically, so a field can appear before or after the marker within the same
   * object — this extracts the enclosing object first rather than assuming a scan direction.
   */
  private static String fieldNear(String json, String marker, String name) {
    int m = json.indexOf(marker);
    if (m < 0) throw new AssertionError(marker + " not found in " + json);
    int objStart = json.lastIndexOf('{', m);
    int objEnd = json.indexOf('}', m);
    String obj = json.substring(objStart, objEnd + 1);
    return field(obj, name);
  }

  // ── the catalogue event (03.8) ─────────────────────────────────────────────

  /** The newest outbox payload of a type for a tenant, or empty. */
  private static String outbox(String tenant, String type, String aggregateId) throws Exception {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM product.outbox WHERE tenant_id = ?::uuid AND event_type = ?"
                    + (aggregateId == null ? "" : " AND aggregate_id = ?::uuid")
                    + " ORDER BY created_at DESC LIMIT 1")) {
      ps.setString(1, tenant);
      ps.setString(2, type);
      if (aggregateId != null) ps.setString(3, aggregateId);
      var rs = ps.executeQuery();
      return rs.next() ? rs.getString(1) : "";
    }
  }

  private static int outboxCount(String tenant, String type) throws Exception {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM product.outbox WHERE tenant_id = ?::uuid AND event_type = ?")) {
      ps.setString(1, tenant);
      ps.setString(2, type);
      var rs = ps.executeQuery();
      rs.next();
      return rs.getInt(1);
    }
  }

  /** An imported product announces its category like one made by POST /admin/products. */
  @Test
  void anImportedProductAnnouncesItsCategoryAndAnUncategorisedOneDoesNot() throws Exception {
    String suffix = Ids.newId().toString();
    String category = "Imported " + suffix;
    String sku = "IMP-C-" + suffix;
    String bareSku = "IMP-N-" + suffix;
    Response r =
        post(
            "/admin/import",
            "{\"categories\":[{\"name\":\""
                + category
                + "\"}],\"products\":[{\"name\":\"Categorised "
                + suffix
                + "\",\"categoryName\":\""
                + category
                + "\",\"variants\":[{\"sku\":\""
                + sku
                + "\"}]},{\"name\":\"Bare "
                + suffix
                + "\",\"variants\":[{\"sku\":\""
                + bareSku
                + "\"}]}]}",
            TENANT_A);
    String answer = r.readEntity(String.class);
    assertThat(answer, r.getStatus(), is(200));
    assertThat(answer, containsString("\"errors\":[]"));
    String categorisedId = fieldNear(answer, sku, "productId");
    String bareId = fieldNear(answer, bareSku, "productId");

    String announced = outbox(TENANT_A, "ProductCategorised", categorisedId);
    assertThat(announced, containsString(categorisedId));
    assertThat(announced, containsString("\"categoryPath\":[\""));
    assertThat(outbox(TENANT_A, "ProductCategorised", bareId), is(""));
    assertThat(outbox(TENANT_A, "ProductCreated", bareId), not(is("")));
    // Another business's outbox holds nothing of it.
    assertThat(outbox(TENANT_B, "ProductCategorised", categorisedId), is(""));
  }

  @Test
  void aProductAnnouncesItsCategoryPathAndItsVariants() throws Exception {
    String drinks =
        field(
            post("/admin/categories", "{\"name\":\"Drinks\"}", TENANT_A).readEntity(String.class),
            "id");
    String soft =
        field(
            post(
                    "/admin/categories",
                    "{\"name\":\"Soft drinks\",\"parentId\":\"" + drinks + "\"}",
                    TENANT_A)
                .readEntity(String.class),
            "id");
    Response created =
        post("/admin/products", "{\"name\":\"Cola\",\"categoryId\":\"" + soft + "\"}", TENANT_A);
    assertThat(created.getStatus(), is(201));
    String productId = field(created.readEntity(String.class), "id");
    // Announced at creation: the path runs from the product's own category to the root.
    String announced = outbox(TENANT_A, "ProductCategorised", productId);
    assertThat(announced, containsString("\"categoryPath\":[\"" + soft + "\",\"" + drinks + "\"]"));
    assertThat(announced, containsString("\"variantIds\":[]"));

    String variantId =
        field(
            post(
                    "/admin/products/" + productId + "/variants",
                    "{\"sku\":\"COLA-330\",\"unit\":\"PCS\"}",
                    TENANT_A)
                .readEntity(String.class),
            "id");
    // A variant created afterwards names its product, which is what a projection needs.
    assertThat(
        outbox(TENANT_A, "VariantCreated", variantId),
        containsString("\"productId\":\"" + productId + "\""));

    // Re-categorised: announced again, now with the variant, on the new path.
    String snacks =
        field(
            post("/admin/categories", "{\"name\":\"Snacks\"}", TENANT_A).readEntity(String.class),
            "id");
    assertThat(
        put(
                "/admin/products/" + productId,
                "{\"name\":\"Cola\",\"categoryId\":\""
                    + snacks
                    + "\",\"sellableOnline\":true,\"sellablePos\":true}",
                TENANT_A)
            .getStatus(),
        is(200));
    String moved = outbox(TENANT_A, "ProductCategorised", productId);
    assertThat(moved, containsString("\"categoryPath\":[\"" + snacks + "\"]"));
    assertThat(moved, containsString("\"variantIds\":[\"" + variantId + "\"]"));

    // A category moved under a new parent re-announces the catalogue.
    int before = outboxCount(TENANT_A, "ProductCategorised");
    assertThat(
        put(
                "/admin/categories/" + snacks,
                "{\"name\":\"Snacks\",\"parentId\":\"" + drinks + "\"}",
                TENANT_A)
            .getStatus(),
        is(200));
    assertThat(outboxCount(TENANT_A, "ProductCategorised") > before, is(true));
    assertThat(
        outbox(TENANT_A, "ProductCategorised", productId),
        containsString("\"categoryPath\":[\"" + snacks + "\",\"" + drinks + "\"]"));
    // A rename alone does not.
    int afterMove = outboxCount(TENANT_A, "ProductCategorised");
    assertThat(
        put(
                "/admin/categories/" + snacks,
                "{\"name\":\"Crisps\",\"parentId\":\"" + drinks + "\"}",
                TENANT_A)
            .getStatus(),
        is(200));
    assertThat(outboxCount(TENANT_A, "ProductCategorised"), is(afterMove));
    // Another tenant announced nothing.
    assertThat(outbox(TENANT_B, "ProductCategorised", productId), is(""));
  }

  @Test
  void theCatalogueCanBeReannouncedForAConsumerThatArrivedLate() throws Exception {
    String cat =
        field(
            post("/admin/categories", "{\"name\":\"Republished\"}", TENANT_B)
                .readEntity(String.class),
            "id");
    String p1 =
        field(
            post("/admin/products", "{\"name\":\"One\",\"categoryId\":\"" + cat + "\"}", TENANT_B)
                .readEntity(String.class),
            "id");
    String p2 =
        field(
            post("/admin/products", "{\"name\":\"Two\"}", TENANT_B).readEntity(String.class), "id");
    String v1 =
        field(
            post(
                    "/admin/products/" + p1 + "/variants",
                    "{\"sku\":\"ONE-1\",\"unit\":\"PCS\"}",
                    TENANT_B)
                .readEntity(String.class),
            "id");
    int before = outboxCount(TENANT_B, "ProductCategorised");

    Response r = post("/admin/products/republish-catalogue", "{}", TENANT_B);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    assertThat(body, containsString("\"announced\":2"));
    assertThat(outboxCount(TENANT_B, "ProductCategorised"), is(before + 2));
    assertThat(
        outbox(TENANT_B, "ProductCategorised", p1),
        containsString("\"variantIds\":[\"" + v1 + "\"]"));
    assertThat(outbox(TENANT_B, "ProductCategorised", p2), containsString("\"categoryPath\":[]"));
    // Management only: the shared filter refuses a storekeeper under /admin.
    Response keeper =
        target
            .path("/admin/products/republish-catalogue")
            .request()
            .header("X-Tenant-Id", TENANT_B)
            .header("X-Roles", "STOREKEEPER")
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(keeper.getStatus(), is(403));
  }

  // ── the scheduled outbox purge finds its rows through an index (V1__init.sql) ─────────

  @Test
  void theOutboxPurgeTakesOnlyOldPublishedRowsAndCanFindThemThroughItsIndex() throws Exception {
    UUID tenant = Ids.newId();
    UUID oldPublished = Ids.newId();
    UUID recentPublished = Ids.newId();
    UUID oldUnpublished = Ids.newId();
    Instant now = Instant.now();
    outboxRow(oldPublished, tenant, now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(29)));
    outboxRow(
        recentPublished, tenant, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
    outboxRow(oldUnpublished, tenant, now.minus(Duration.ofDays(30)), null);

    // What the scheduler runs: published more than a week ago, a batch at a time.
    int purged = outboxStore.purgePublished(now.minus(Duration.ofDays(7)), 1000);

    assertThat("only the old published row went", purged, is(1));
    assertThat(outboxRows(oldPublished), is(0));
    assertThat("a recently published one stays", outboxRows(recentPublished), is(1));
    assertThat("a row never published is never purged", outboxRows(oldUnpublished), is(1));

    // The purge's own statement, planned with every other way of finding the rows ruled out: it
    // must be able to use the index on published_at. Without one, each batch read the whole table.
    String plan =
        planOf(
            "SELECT id FROM product.outbox WHERE published_at IS NOT NULL"
                + " AND published_at < now() - interval '7 days'"
                + " ORDER BY published_at ASC LIMIT 1000 FOR UPDATE SKIP LOCKED");
    assertThat(plan, containsString("idx_outbox_published"));
    // And the index holds only what the purge can take, so it shrinks as the purge runs.
    assertThat(indexDefinition("idx_outbox_published"), containsString("published_at IS NOT NULL"));
  }

  private static void outboxRow(UUID id, UUID tenant, Instant createdAt, Instant publishedAt)
      throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO product.outbox (id, event_type, topic, tenant_id, aggregate_id,"
                    + " payload, created_at, published_at)"
                    + " VALUES (?, 'PurgeTest', 'storeql.test.purge', ?, ?, '{}', ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, Ids.newId());
      ps.setObject(4, createdAt.atOffset(ZoneOffset.UTC));
      if (publishedAt == null) {
        ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(5, publishedAt.atOffset(ZoneOffset.UTC));
      }
      ps.executeUpdate();
    }
  }

  private static int outboxRows(UUID id) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement("SELECT count(*) FROM product.outbox WHERE id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** How Postgres would run a statement when a table scan and a sort are not on offer. */
  private static String planOf(String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      st.execute("SET enable_sort = off");
      StringBuilder plan = new StringBuilder();
      try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
      return plan.toString();
    }
  }

  private static String indexDefinition(String index) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'product' AND indexname = ?")) {
      ps.setString(1, index);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : "";
      }
    }
  }
}
