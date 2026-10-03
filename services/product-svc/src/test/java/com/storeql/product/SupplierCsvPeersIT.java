package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A supplier CSV import when the services it follows up with are not there (1 Oct 2026): the
 * catalogue is committed before stock is received and prices are set, so a refusal that followed it
 * answered an error for an import that had happened, and sending it again met its own duplicates.
 *
 * <p>Discovery is a stand-in here: inventory-svc is not listed in it, and pricing-svc is listed but
 * answers nothing the import can use. That is the two ways a peer is down, and each must leave the
 * catalogue as the caller sent it or say plainly what it did not do.
 */
@HelidonTest
class SupplierCsvPeersIT {

  private static final String T = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String PATH = "/admin/import/supplier-csv";

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final JsonStub PRICING;
  private static final JsonStub CONSUL;

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
    // The destination store is one of the business's, as tenant-svc lists them: a store that is
    // not is refused before the peers are asked about.
    TenantSvcStub.start().with(T, "GBP", "GB").withStore(T, STORE, "GB");

    // pricing-svc: in discovery, and answering 404 to everything, so it can neither list nor make
    // the business's default price list.
    PRICING = JsonStub.start();
    // Discovery itself: no inventory-svc at all, and the pricing-svc above.
    CONSUL = JsonStub.start();
    CONSUL.on("GET", "/v1/health/service/inventory-svc", 200, "[]");
    URI pricing = URI.create(PRICING.baseUrl());
    CONSUL.on(
        "GET",
        "/v1/health/service/pricing-svc",
        200,
        "[{\"Node\":{\"Address\":\""
            + pricing.getHost()
            + "\"},\"Service\":{\"Address\":\""
            + pricing.getHost()
            + "\",\"Port\":"
            + pricing.getPort()
            + "}}]");
    URI consul = URI.create(CONSUL.baseUrl());
    System.setProperty("storeql.consul.host", consul.getHost());
    System.setProperty("storeql.consul.port", String.valueOf(consul.getPort()));
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    // The stand-ins and the properties that point at them go first: stopping the database checks
    // what the class left behind and can fail, and the classes that follow in this JVM must not be
    // left asking a discovery server that is gone.
    try {
      PRICING.close();
      CONSUL.close();
      System.clearProperty("storeql.consul.host");
      System.clearProperty("storeql.consul.port");
    } finally {
      try {
        PG.stop();
      } finally {
        REDIS.stop();
      }
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private Response importSheet(String csv, String storeId) {
    String json =
        "{\"csv\":\""
            + csv
            + "\""
            + (storeId == null ? "" : ",\"storeId\":\"" + storeId + "\"")
            + "}";
    return target
        .path(PATH)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String body(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

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

  private static final String PRODUCTS =
      "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid";
  private static final String CATEGORIES =
      "SELECT count(*) FROM product.categories WHERE tenant_id = ?::uuid";
  private static final String OUTBOX =
      "SELECT count(*) FROM product.outbox WHERE tenant_id = ?::uuid";
  private static final String NAMED =
      "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid AND name = ?";
  private static final String VARIANTS_OF_SKU =
      "SELECT count(*) FROM product.product_variants WHERE tenant_id = ?::uuid AND sku = ?";

  /**
   * A SKU nobody else in this schema holds. Every sheet here carries its own, because a sheet with
   * no SKU column is numbered {@code IMP-1}, {@code IMP-2}… from its first row on, and the second
   * such sheet for the same business meets the first one's variant: it is a duplicate, the row is
   * that import's error, and the follow-ups for the variant (stock, prices) are never reached.
   */
  private static String sku() {
    return "PEERS-" + Ids.newId();
  }

  // ── the stock service is not there ───────────────────────────────────────────

  @Test
  @DisplayName(
      "A sheet of quantities for a store is refused, and nothing imported, if inventory is gone")
  void aSheetOfQuantitiesIsRefusedWhenInventoryIsGone() {
    int products = count(PRODUCTS, T);
    int categories = count(CATEGORIES, T);
    int events = count(OUTBOX, T);
    String name = "Tea " + Ids.newId();
    String sku = sku();

    Response refused =
        importSheet(
            "Product ID,Product Description,Category,Quantity\\n" + sku + "," + name + ",Tea,5",
            STORE);

    String answer = body(refused, 503);
    assertThat(answer, containsString("INVENTORY_UNAVAILABLE"));
    assertThat("nothing was imported", count(PRODUCTS, T), is(products));
    assertThat("not even the category", count(CATEGORIES, T), is(categories));
    assertThat("and no event was written", count(OUTBOX, T), is(events));
    assertThat(count(NAMED, T, name), is(0));
    assertThat("nor the variant", count(VARIANTS_OF_SKU, T, sku), is(0));
  }

  @Test
  @DisplayName("A sheet that asks inventory-svc nothing is imported without it")
  void aSheetThatAsksInventoryNothingIsImportedWithoutIt() {
    String quantitiesButNoStore = "Tea " + Ids.newId();
    String storeButNoQuantities = "Coffee " + Ids.newId();
    String firstSku = sku();
    String secondSku = sku();

    // Quantities, but no store to receive them into: nothing is received, so nothing is asked.
    String first =
        body(
            importSheet(
                "Product ID,Product Description,Quantity\\n"
                    + firstSku
                    + ","
                    + quantitiesButNoStore
                    + ",5",
                null),
            200);
    assertThat(first, containsString("\"productsCreated\":1"));
    assertThat(first, containsString("\"variantsCreated\":1"));
    assertThat(first, not(containsString("stockReceived")));

    // A store, but no quantities.
    String second =
        body(
            importSheet(
                "Product ID,Product Description\\n" + secondSku + "," + storeButNoQuantities,
                STORE),
            200);
    assertThat(second, containsString("\"productsCreated\":1"));
    assertThat(second, containsString("\"variantsCreated\":1"));
    assertThat(second, not(containsString("stockReceived")));

    assertThat(count(NAMED, T, quantitiesButNoStore), is(1));
    assertThat(count(NAMED, T, storeButNoQuantities), is(1));
    assertThat(count(VARIANTS_OF_SKU, T, firstSku), is(1));
    assertThat(count(VARIANTS_OF_SKU, T, secondSku), is(1));
  }

  // ── the price service is there, and cannot do what the import needs ─────────

  @Test
  @DisplayName("A price step that fails once the catalogue is written is reported with a 200")
  void aPriceStepThatFailsAfterTheCommitIsReported() {
    String name = "Priced " + Ids.newId();
    String sku = sku();

    // pricing-svc is listed, so the import is not refused up front. It cannot list the business's
    // price lists, which is only found out after the catalogue is written: the caller is told what
    // was not done, in plain words, and the products are there to be priced. An answer that is not
    // its lists makes no second Default list (2 Oct 2026).
    String answer =
        body(
            importSheet(
                "Product ID,Product Description,Price\\n" + sku + "," + name + ",2.50", null),
            200);

    assertThat(answer, containsString("\"productsCreated\":1"));
    assertThat(answer, containsString("\"variantsCreated\":1"));
    assertThat(answer, containsString("\"pricesSet\":0"));
    assertThat(
        answer,
        containsString(
            "1 prices not set: pricing-svc answered HTTP 404 when asked for its price lists"));
    assertThat(
        "no list was made",
        PRICING.calls().stream()
            .filter(c -> c.method().equals("POST") && c.path().equals("/admin/price-lists"))
            .count(),
        is(0L));
    assertThat("the catalogue was imported", count(NAMED, T, name), is(1));
    assertThat(
        "with its variant, which the prices were for", count(VARIANTS_OF_SKU, T, sku), is(1));
  }
}
