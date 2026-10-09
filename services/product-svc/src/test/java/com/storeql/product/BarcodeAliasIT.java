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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A scanned old EAN, multipack or case code finds the item with its pack quantity (intent
 * catalogue-import): kept per business, compared as GTIN-14, answered only for an item on sale.
 */
@HelidonTest
class BarcodeAliasIT {

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final AtomicLong COUNTER = new AtomicLong(1);

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
    TenantSvcStub.start().with(T, "GBP", "GB").with(RIVAL, "GBP", "GB");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
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

  private String scanned(String code, String tenant) {
    Response r = scan(code, tenant);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  private static String id(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  /** A fresh EAN-13 with its check digit, that no other test in this schema holds. */
  private static String freshEan13() {
    String body = String.format("6%011d", COUNTER.incrementAndGet());
    return body + Gtin.checkDigit(body);
  }

  private record Item(String product, String variant) {}

  private Item item(String tenant, String barcode) {
    String product =
        id(post("/admin/products", "{\"name\":\"Tinned " + Ids.newId() + "\"}", tenant));
    String variant =
        id(
            post(
                "/admin/products/" + product + "/variants",
                "{\"sku\":\"S-"
                    + Ids.newId()
                    + "\""
                    + (barcode == null ? "" : ",\"barcode\":\"" + barcode + "\"")
                    + "}",
                tenant));
    return new Item(product, variant);
  }

  private void alias(String tenant, String variant, String gtin14, String kind, int packQty)
      throws Exception {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO product.variant_barcode_aliases"
                    + " (id, tenant_id, variant_id, gtin14, kind, pack_qty)"
                    + " VALUES (?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.parse(tenant));
      ps.setObject(3, Ids.parse(variant));
      ps.setString(4, gtin14);
      ps.setString(5, kind);
      ps.setInt(6, packQty);
      ps.executeUpdate();
    }
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("an old EAN finds the item and says it is an alias, one unit a scan")
  void anOldEan() throws Exception {
    Item item = item(T, freshEan13());
    String old = freshEan13();
    alias(T, item.variant(), "0" + old, "OLD_EAN", 1);

    String body = scanned(old, T);

    assertThat(body, containsString("\"variantId\":\"" + item.variant() + "\""));
    assertThat(body, containsString("\"alias\":{\"kind\":\"OLD_EAN\",\"packQty\":1}"));
  }

  @Test
  @DisplayName("a case code stands for the units in the case, whatever spelling is scanned")
  void aCaseCode() throws Exception {
    Item item = item(T, freshEan13());
    String caseGtin = "1" + freshEan13().substring(0, 12) + "0";
    caseGtin = caseGtin.substring(0, 13) + Gtin.checkDigit(caseGtin.substring(0, 13));
    alias(T, item.variant(), caseGtin, "CASE", 24);

    // as 14 digits, as an element string, and as a Digital Link
    for (String code :
        new String[] {caseGtin, "01" + caseGtin, "https://id.gs1.org/01/" + caseGtin}) {
      String body = scanned(code, T);
      assertThat(code, body, containsString("\"variantId\":\"" + item.variant() + "\""));
      assertThat(code, body, containsString("\"alias\":{\"kind\":\"CASE\",\"packQty\":24}"));
    }
  }

  @Test
  @DisplayName("an EAN-13 alias is found as its GTIN-14 and the other way about")
  void oneIdentityAcrossLengths() throws Exception {
    Item item = item(T, freshEan13());
    String multipack = freshEan13();
    alias(T, item.variant(), "0" + multipack, "MULTIPACK", 6);

    assertThat(scanned(multipack, T), containsString("\"packQty\":6"));
    assertThat(scanned("0" + multipack, T), containsString("\"packQty\":6"));
  }

  @Test
  @DisplayName("the item's own barcode never reports an alias")
  void ownBarcodeIsNotAnAlias() throws Exception {
    String own = freshEan13();
    Item item = item(T, own);
    alias(T, item.variant(), "0" + freshEan13(), "OLD_EAN", 1);

    String body = scanned(own, T);

    assertThat(body, containsString("\"variantId\":\"" + item.variant() + "\""));
    assertThat(body, not(containsString("\"alias\":{")));
  }

  @Test
  @DisplayName("an item with no barcode of its own is still found by its alias")
  void noBarcodeOfItsOwn() throws Exception {
    Item item = item(T, null);
    String code = freshEan13();
    alias(T, item.variant(), "0" + code, "OLD_EAN", 1);

    assertThat(scanned(code, T), containsString("\"variantId\":\"" + item.variant() + "\""));
  }

  // ── what an alias must not do ──────────────────────────────────────────────

  @Test
  @DisplayName("an alias of an item taken off sale finds nothing")
  void delistedItem() throws Exception {
    Item item = item(T, freshEan13());
    String old = freshEan13();
    alias(T, item.variant(), "0" + old, "OLD_EAN", 1);
    assertThat(scan(old, T).getStatus(), is(200));

    assertThat(
        target
            .path("/admin/products/" + item.product())
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .delete()
            .getStatus(),
        is(200));

    assertThat(scan(old, T).getStatus(), is(404));
  }

  @Test
  @DisplayName("an unknown code is still not found, with a refusal that names the code")
  void unknown() {
    Response r = scan(freshEan13(), T);
    assertThat(r.getStatus(), is(404));
    assertThat(r.readEntity(String.class), containsString("VARIANT_NOT_FOUND"));
  }

  // ── tenant isolation ───────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "the same code is an alias in two businesses: each finds its own item, never the other's")
  void perBusiness() throws Exception {
    Item mine = item(T, freshEan13());
    Item theirs = item(RIVAL, freshEan13());
    String shared = "0" + freshEan13();
    alias(T, mine.variant(), shared, "OLD_EAN", 1);
    alias(RIVAL, theirs.variant(), shared, "CASE", 12);

    String a = scanned(shared, T);
    String b = scanned(shared, RIVAL);

    assertThat(a, containsString("\"variantId\":\"" + mine.variant() + "\""));
    assertThat(a, not(containsString(theirs.variant())));
    assertThat(b, containsString("\"variantId\":\"" + theirs.variant() + "\""));
    assertThat(b, containsString("\"packQty\":12"));
    assertThat(b, not(containsString(mine.variant())));
  }

  @Test
  @DisplayName("a business that holds no alias for a code cannot find another's by it")
  void noLeak() throws Exception {
    Item mine = item(T, freshEan13());
    String old = freshEan13();
    alias(T, mine.variant(), "0" + old, "OLD_EAN", 1);

    assertThat(scan(old, RIVAL).getStatus(), is(404));
    // and the one alias is unique per business at the database
    org.junit.jupiter.api.Assertions.assertThrows(
        java.sql.SQLException.class, () -> alias(T, mine.variant(), "0" + old, "CASE", 2));
  }
}
