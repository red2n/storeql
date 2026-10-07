package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Item master data is kept centrally; a branch edits only what is local to it (3 Oct 2026). A
 * manager held to stores writes to a line only when it is ranged solely to their stores ({@code 403
 * BUSINESS_WIDE_ONLY} otherwise, after the product's 404, before any write); whole-business
 * catalogue data and policy need an owner or a manager held to no store. Written, not run this
 * round.
 */
@HelidonTest
class LineHeldIT {

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String OTHER_STORE = Ids.newId().toString();
  private static final String[] BELOW_MANAGEMENT = {"STOREKEEPER", "CASHIER", "CUSTOMER"};

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
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .with(RIVAL, "GBP", "GB")
        .withStore(T, STORE, "GB")
        .withStore(T, OTHER_STORE, "GB");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  /**
   * A request by staff of {@code tenant} in {@code role}, held to the stores named (null: none).
   */
  private Invocation.Builder as(String path, String tenant, String role, String held) {
    // WebTargets.at, never target.path: a "?" in a path string is escaped into the path.
    var req =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role);
    return held == null ? req : req.header("X-Store-Ids", held);
  }

  private Response send(
      String verb, String path, String json, String tenant, String role, String held) {
    Invocation.Builder b = as(path, tenant, role, held);
    return json == null
        ? b.method(verb)
        : b.method(verb, Entity.entity(json, MediaType.APPLICATION_JSON));
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

  private static void assertWideOnly(Response r) {
    String b = r.readEntity(String.class);
    assertThat(b, r.getStatus(), is(403));
    assertThat(b, containsString("BUSINESS_WIDE_ONLY"));
  }

  private String owners(String name) {
    return id(
        body(
            send("POST", "/admin/products", "{\"name\":\"" + name + "\"}", T, "OWNER", null), 201));
  }

  /** A valid GTIN-13 no other variant has: a barcode is unique within a business. */
  private static String barcode() {
    String body = String.format("5%011d", Math.abs(System.nanoTime()) % 100_000_000_000L);
    return body + com.storeql.gs1.Gtin.checkDigit(body);
  }

  private String variant(String product, String sku, String barcode) {
    return id(
        body(
            send(
                "POST",
                "/admin/products/" + product + "/variants",
                "{\"sku\":\"" + sku + "\",\"barcode\":\"" + barcode + "\"}",
                T,
                "OWNER",
                null),
            201));
  }

  private static String scalar(String sql, String... args) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static String productRow(String product) throws Exception {
    return scalar(
        "SELECT name || '|' || sellable_online || '|' || sellable_pos || '|' || status"
            + " FROM product.products WHERE tenant_id = ?::uuid AND id = ?::uuid",
        T,
        product);
  }

  private static String variantRow(String variant) throws Exception {
    return scalar(
        "SELECT sku || '|' || coalesce(barcode, '') || '|' || status FROM product.product_variants"
            + " WHERE tenant_id = ?::uuid AND id = ?::uuid",
        T,
        variant);
  }

  private static String count(String table, String column, String value) throws Exception {
    return scalar(
        "SELECT count(*) FROM product."
            + table
            + " WHERE tenant_id = ?::uuid AND "
            + column
            + " = ?::uuid",
        T,
        value);
  }

  // ── a held manager and an owner's line ──────────────────────────────────────

  @Test
  @DisplayName(
      "A held manager changes nothing of an owner's everywhere-line, however they come to it")
  void aHeldManagerCannotEditAnOwnersEverywhereLine() throws Exception {
    String p = owners("Owner line " + Ids.newId());
    String v = variant(p, "OWN-" + Ids.newId(), barcode());
    String beforeProduct = productRow(p);
    String beforeVariant = variantRow(v);
    String pv = "/admin/products/" + p;

    assertWideOnly(
        send(
            "PUT",
            pv,
            "{\"name\":\"Taken\",\"sellableOnline\":false,\"sellablePos\":false}",
            T,
            "MANAGER",
            STORE));
    assertWideOnly(
        send(
            "PUT",
            pv + "/variants/" + v,
            "{\"sku\":\"FREED\",\"barcode\":null}",
            T,
            "MANAGER",
            STORE));
    assertWideOnly(send("POST", pv + "/variants", "{\"sku\":\"NEW-1\"}", T, "MANAGER", STORE));
    // As an image: the route takes no other content type (a JSON body is 415 before it runs).
    assertWideOnly(
        as(pv + "/image", T, "MANAGER", STORE)
            .put(Entity.entity(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png")));
    assertWideOnly(send("DELETE", pv + "/image", null, T, "MANAGER", STORE));
    assertWideOnly(send("PUT", pv + "/safety-information", "{}", T, "MANAGER", STORE));
    String vv = "/admin/products/variants/" + v;
    assertWideOnly(send("PUT", vv + "/allergens", "{\"allergens\":[]}", T, "MANAGER", STORE));
    assertWideOnly(send("PUT", vv + "/compliance", "{}", T, "MANAGER", STORE));
    assertWideOnly(
        send("PUT", vv + "/attribute-groups/WEB", "{\"values\":\"{}\"}", T, "MANAGER", STORE));
    assertWideOnly(send("POST", vv + "/cross-references", "{}", T, "MANAGER", STORE));
    assertWideOnly(send("POST", vv + "/revisions", "{}", T, "MANAGER", STORE));

    assertThat("the product row is as it was", productRow(p), is(beforeProduct));
    assertThat("the variant's SKU and barcode are as they were", variantRow(v), is(beforeVariant));
    assertThat("no variant was added", count("product_variants", "product_id", p), is("1"));
    assertThat("no image was stored", count("product_images", "product_id", p), is("0"));
  }

  @Test
  @DisplayName("A line sold at their store and another is still not theirs to edit")
  void aLineBeyondTheirStoresIsNotTheirs() throws Exception {
    String p = owners("Two stores " + Ids.newId());
    String other = OTHER_STORE;
    body(
        send(
            "PUT",
            "/admin/products/" + p + "/stores",
            "{\"storeIds\":[\"" + STORE + "\"]}",
            T,
            "OWNER",
            null),
        200);
    // Held to their store alone it is theirs, so ranging it to a second store (by the owner) is
    // what makes it the whole business's.
    String before = productRow(p);
    body(
        send(
            "PUT",
            "/admin/products/" + p + "/stores",
            "{\"storeIds\":[\"" + STORE + "\",\"" + other + "\"]}",
            T,
            "OWNER",
            null),
        200);
    assertWideOnly(
        send(
            "PUT",
            "/admin/products/" + p,
            "{\"name\":\"Nope\",\"sellableOnline\":true,\"sellablePos\":true}",
            T,
            "MANAGER",
            STORE));
    assertThat(productRow(p), is(before));
  }

  @Test
  @DisplayName(
      "A held manager edits their own local line; owners and business-wide managers any line")
  void theirOwnLocalLineAndTheWholeBusiness() throws Exception {
    String local =
        id(
            body(
                send(
                    "POST",
                    "/admin/products",
                    "{\"name\":\"Local " + Ids.newId() + "\"}",
                    T,
                    "MANAGER",
                    STORE),
                201));
    body(
        send(
            "PUT",
            "/admin/products/" + local,
            "{\"name\":\"Local edited\",\"sellableOnline\":false,\"sellablePos\":true}",
            T,
            "MANAGER",
            STORE),
        200);
    assertThat(productRow(local), containsString("Local edited|false|true"));
    String v =
        id(
            body(
                send(
                    "POST",
                    "/admin/products/" + local + "/variants",
                    "{\"sku\":\"LOC-" + Ids.newId() + "\"}",
                    T,
                    "MANAGER",
                    STORE),
                201));
    body(
        send(
            "PUT",
            "/admin/products/" + local + "/variants/" + v,
            "{\"sku\":\"LOC-EDIT-" + Ids.newId() + "\"}",
            T,
            "MANAGER",
            STORE),
        200);

    String everywhere = owners("Everywhere " + Ids.newId());
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      body(
          send(
              "PUT",
              "/admin/products/" + everywhere,
              "{\"name\":\"By " + role + "\",\"sellableOnline\":true,\"sellablePos\":true}",
              T,
              role,
              null),
          200);
      assertThat(productRow(everywhere), containsString("By " + role));
    }
  }

  @Test
  @DisplayName(
      "A variant create or update is judged on the range as it stands: widened, a held manager"
          + " takes no SKU or barcode")
  void identifierWritesFollowTheRange() throws Exception {
    String p = owners("Widening " + Ids.newId());
    body(
        send(
            "PUT",
            "/admin/products/" + p + "/stores",
            "{\"storeIds\":[\"" + STORE + "\"]}",
            T,
            "OWNER",
            null),
        200);
    String mine = "MINE-" + Ids.newId();
    String v =
        id(
            body(
                send(
                    "POST",
                    "/admin/products/" + p + "/variants",
                    "{\"sku\":\"" + mine + "\",\"barcode\":\"" + barcode() + "\"}",
                    T,
                    "MANAGER",
                    STORE),
                201));
    body(
        send(
            "PUT",
            "/admin/products/" + p,
            "{\"name\":\"Widening\",\"sellableOnline\":true,\"sellablePos\":true}",
            T,
            "MANAGER",
            STORE),
        200);
    body(
        send(
            "PUT",
            "/admin/products/" + p + "/stores",
            "{\"storeIds\":[\"" + STORE + "\",\"" + OTHER_STORE + "\"]}",
            T,
            "OWNER",
            null),
        200);
    String before = variantRow(v);
    String taken = "TAKEN-" + Ids.newId();
    String takenBarcode = barcode();
    assertWideOnly(
        send(
            "POST",
            "/admin/products/" + p + "/variants",
            "{\"sku\":\"" + taken + "\",\"barcode\":\"" + takenBarcode + "\"}",
            T,
            "MANAGER",
            STORE));
    assertWideOnly(
        send(
            "PUT",
            "/admin/products/" + p + "/variants/" + v,
            "{\"sku\":\"" + taken + "\",\"barcode\":\"" + takenBarcode + "\"}",
            T,
            "MANAGER",
            STORE));
    assertThat(variantRow(v), is(before));
    assertThat(
        "no variant took the SKU",
        scalar(
            "SELECT count(*) FROM product.product_variants WHERE tenant_id = ?::uuid AND sku = ?",
            T,
            taken),
        is("0"));
    // The owner is not held to the range.
    body(
        send(
            "PUT",
            "/admin/products/" + p + "/variants/" + v,
            "{\"sku\":\"" + taken + "\",\"barcode\":\"" + takenBarcode + "\"}",
            T,
            "OWNER",
            null),
        200);
  }

  @Test
  @DisplayName("The two-call SKU hijack is refused: nothing is freed, and the SKU stays taken")
  void theSkuHijackIsRefused() throws Exception {
    String sku = "HIJ-" + Ids.newId();
    String ownersLine = owners("Hijack target " + Ids.newId());
    String ownersVariant = variant(ownersLine, sku, barcode());
    String before = variantRow(ownersVariant);
    String mine =
        id(
            body(
                send(
                    "POST",
                    "/admin/products",
                    "{\"name\":\"Mine " + Ids.newId() + "\"}",
                    T,
                    "MANAGER",
                    STORE),
                201));
    String myVariant =
        id(
            body(
                send(
                    "POST",
                    "/admin/products/" + mine + "/variants",
                    "{\"sku\":\"MY-" + Ids.newId() + "\"}",
                    T,
                    "MANAGER",
                    STORE),
                201));

    // Call 1, by the owner's own path: refused, so the SKU is not freed.
    assertWideOnly(
        send(
            "PUT",
            "/admin/products/" + ownersLine + "/variants/" + ownersVariant,
            "{\"sku\":\"FREED\"}",
            T,
            "MANAGER",
            STORE));
    // Call 1, through the manager's own line: the variant is not that product's.
    Response mismatch =
        send(
            "PUT",
            "/admin/products/" + mine + "/variants/" + ownersVariant,
            "{\"sku\":\"FREED\"}",
            T,
            "MANAGER",
            STORE);
    assertThat(mismatch.readEntity(String.class), mismatch.getStatus(), is(404));
    // Call 2: the SKU is still held, so it cannot be reused.
    Response reuse =
        send(
            "PUT",
            "/admin/products/" + mine + "/variants/" + myVariant,
            "{\"sku\":\"" + sku + "\"}",
            T,
            "MANAGER",
            STORE);
    assertThat(reuse.readEntity(String.class), reuse.getStatus(), is(409));
    assertThat(variantRow(ownersVariant), is(before));
  }

  // ── whole-business catalogue data and policy ────────────────────────────────

  @Test
  @DisplayName("Categories, brands and age rules are for an owner or a business-wide manager")
  void wholeBusinessDataIsNotAHeldManagers() throws Exception {
    String cat =
        id(
            body(
                send(
                    "POST",
                    "/admin/categories",
                    "{\"name\":\"Fresh " + Ids.newId() + "\"}",
                    T,
                    "OWNER",
                    null),
                201));
    String before = body(send("GET", "/admin/categories/" + cat, null, T, "OWNER", null), 200);
    String rules =
        body(send("GET", "/admin/age-restriction-rules?country=GB", null, T, "OWNER", null), 200);

    assertWideOnly(send("DELETE", "/admin/categories/" + cat, null, T, "MANAGER", STORE));
    assertWideOnly(
        send("PUT", "/admin/categories/" + cat, "{\"name\":\"Renamed\"}", T, "MANAGER", STORE));
    assertWideOnly(
        send("POST", "/admin/categories", "{\"name\":\"Another\"}", T, "MANAGER", STORE));
    assertWideOnly(send("POST", "/admin/brands", "{\"name\":\"Brand\"}", T, "MANAGER", STORE));
    assertWideOnly(
        send(
            "PUT",
            "/admin/age-restriction-rules",
            "{\"country\":\"GB\",\"category\":\"ALCOHOL\",\"minimumAge\":25}",
            T,
            "MANAGER",
            STORE));
    assertThat(
        body(send("GET", "/admin/categories/" + cat, null, T, "OWNER", null), 200), is(before));
    assertThat(
        body(send("GET", "/admin/age-restriction-rules?country=GB", null, T, "OWNER", null), 200),
        is(rules));

    // Below management the role is refused first.
    for (String role : BELOW_MANAGEMENT) {
      assertThat(
          send("DELETE", "/admin/categories/" + cat, null, T, role, null).getStatus(), is(403));
    }

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      String c =
          id(
              body(
                  send(
                      "POST",
                      "/admin/categories",
                      "{\"name\":\"By " + role + Ids.newId() + "\"}",
                      T,
                      role,
                      null),
                  201));
      body(send("DELETE", "/admin/categories/" + c, null, T, role, null), 200);
      body(
          send(
              "PUT",
              "/admin/age-restriction-rules",
              "{\"country\":\"GB\",\"category\":\"ALCOHOL\",\"minimumAge\":25}",
              T,
              role,
              null),
          200);
    }
  }

  // ── another business ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Another business's staff and shoppers move nothing of ours, held to our store or not")
  void anotherBusinessMovesNothing() throws Exception {
    String p = owners("Ours " + Ids.newId());
    String v = variant(p, "OURS-" + Ids.newId(), barcode());
    String cat =
        id(
            body(
                send(
                    "POST",
                    "/admin/categories",
                    "{\"name\":\"Ours " + Ids.newId() + "\"}",
                    T,
                    "OWNER",
                    null),
                201));
    String catBefore = body(send("GET", "/admin/categories/" + cat, null, T, "OWNER", null), 200);
    String pBefore = productRow(p);
    String vBefore = variantRow(v);

    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      for (String held : new String[] {null, STORE}) {
        for (Response r :
            new Response[] {
              send(
                  "PUT",
                  "/admin/products/" + p,
                  "{\"name\":\"X\",\"sellableOnline\":false,\"sellablePos\":false}",
                  RIVAL,
                  role,
                  held),
              send(
                  "PUT",
                  "/admin/products/" + p + "/variants/" + v,
                  "{\"sku\":\"X\"}",
                  RIVAL,
                  role,
                  held),
              send(
                  "POST",
                  "/admin/products/" + p + "/variants",
                  "{\"sku\":\"X\"}",
                  RIVAL,
                  role,
                  held),
              send(
                  "POST",
                  "/admin/products/" + p + "/variants/" + v + "/relist",
                  null,
                  RIVAL,
                  role,
                  held),
              send("DELETE", "/admin/products/" + p + "/variants/" + v, null, RIVAL, role, held),
              send("DELETE", "/admin/categories/" + cat, null, RIVAL, role, held),
            }) {
          int status = r.getStatus();
          r.close();
          assertThat(role + " held=" + held, status, is(oneOf(403, 404)));
        }
      }
    }
    assertThat(productRow(p), is(pBefore));
    assertThat(variantRow(v), is(vBefore));
    assertThat(
        body(send("GET", "/admin/categories/" + cat, null, T, "OWNER", null), 200), is(catBefore));
  }

  // ── variant delist and relist ───────────────────────────────────────────────

  @Test
  @DisplayName(
      "A variant is the path product's: another product's variant is 404 on delist and relist")
  void theVariantMustBeThatProducts() throws Exception {
    String a = owners("A " + Ids.newId());
    String b = owners("B " + Ids.newId());
    String v = variant(a, "PA-" + Ids.newId(), barcode());
    String before = variantRow(v);
    for (String[] call :
        new String[][] {
          {"DELETE", "/admin/products/" + b + "/variants/" + v},
          {"POST", "/admin/products/" + b + "/variants/" + v + "/relist"},
          {"GET", "/admin/products/" + b + "/variants/" + v},
        }) {
      Response r = send(call[0], call[1], null, T, "OWNER", null);
      String text = r.readEntity(String.class);
      assertThat(text, r.getStatus(), is(404));
      assertThat(text, containsString("VARIANT_NOT_FOUND"));
    }
    assertThat(variantRow(v), is(before));
  }

  @Test
  @DisplayName(
      "Relist: owner and business-wide manager only; 409 when on sale; then it scans again")
  void relistThenScan() throws Exception {
    String p = owners("Relist " + Ids.newId());
    String code = barcode();
    String v = variant(p, "RL-" + Ids.newId(), code);
    String path = "/admin/products/" + p + "/variants/" + v;

    assertThat(
        body(send("POST", path + "/relist", null, T, "OWNER", null), 409),
        containsString("VARIANT_NOT_DELISTED"));
    body(send("DELETE", path, null, T, "OWNER", null), 200);
    assertThat(variantRow(v), containsString("|INACTIVE"));
    body(send("GET", "/catalog/variants/by-barcode/" + code, null, T, "CASHIER", null), 404);

    assertWideOnly(send("POST", path + "/relist", null, T, "MANAGER", STORE));
    for (String role : BELOW_MANAGEMENT) {
      assertThat(send("POST", path + "/relist", null, T, role, null).getStatus(), is(403));
    }
    assertThat(variantRow(v), containsString("|INACTIVE"));

    body(send("POST", path + "/relist", null, T, "MANAGER", null), 200);
    assertThat(variantRow(v), containsString("|ACTIVE"));
    body(send("GET", "/catalog/variants/by-barcode/" + code, null, T, "CASHIER", null), 200);

    // A delisted product's variant is not relisted under it.
    String dead = owners("Dead " + Ids.newId());
    String dv = variant(dead, "DD-" + Ids.newId(), barcode());
    body(
        send("DELETE", "/admin/products/" + dead + "/variants/" + dv, null, T, "OWNER", null), 200);
    body(send("DELETE", "/admin/products/" + dead, null, T, "OWNER", null), 200);
    assertThat(
        body(
            send(
                "POST",
                "/admin/products/" + dead + "/variants/" + dv + "/relist",
                null,
                T,
                "OWNER",
                null),
            409),
        containsString("VARIANT_PRODUCT_NOT_ON_SALE"));
  }
}
