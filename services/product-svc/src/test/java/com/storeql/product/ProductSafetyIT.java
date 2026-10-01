package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Product safety information for online offers (01.12, GPSR art.19): a German business cannot offer
 * a product online without its manufacturer, its EU responsible person when the manufacturer is
 * outside the EU, and its warnings; a British one is not bound; and no path — an update, a cleared
 * statement, a bulk import, two writers at once — leaves an online offer without it.
 */
@HelidonTest
class ProductSafetyIT {

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;

  private static final String DE = "01a090ae-611e-7a41-8c3d-4e5f60718201";
  private static final String GB = "01a090ae-611e-7a42-8c3d-4e5f60718202";

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
    // As tenant-svc's jurisdiction rules have it: GPSR reaches Germany and France, not Britain or
    // China. Which countries are inside is data, never a list in product-svc.
    TenantSvcStub.start()
        .with(DE, "EUR", "DE")
        .with(GB, "GBP", "GB")
        .withObligation("DE", "GPSR_ONLINE_OFFER", "EU", "2024-12-13", null)
        .withObligation("FR", "GPSR_ONLINE_OFFER", "EU", "2024-12-13", null);
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String tenant, String roles) {
    var b = target.path(path).request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    return b.header("X-User-Id", Ids.newId().toString());
  }

  private static JsonObject data(Response r) {
    return Json.createReader(new StringReader(r.readEntity(String.class)))
        .readObject()
        .getJsonObject("data");
  }

  private static final String EU_MAKER =
      "{\"manufacturerName\":\"Atelier Lumière SAS\",\"manufacturerAddress\":\"12 rue de la Paix\\n75002 Paris\","
          + "\"manufacturerContact\":\"securite@lumiere.fr\",\"manufacturerCountry\":\"fr\","
          + "\"warnings\":\"Keep away from open flame.\"}";

  private static final String CN_MAKER_NO_REP =
      "{\"manufacturerName\":\"Shenzhen Toys Ltd\",\"manufacturerAddress\":\"1 Nanshan Road, Shenzhen\","
          + "\"manufacturerContact\":\"https://toys.example.cn/safety\",\"manufacturerCountry\":\"CN\",\"noWarnings\":true}";

  private static final String CN_MAKER_WITH_REP =
      "{\"manufacturerName\":\"Shenzhen Toys Ltd\",\"manufacturerAddress\":\"1 Nanshan Road, Shenzhen\","
          + "\"manufacturerContact\":\"https://toys.example.cn/safety\",\"manufacturerCountry\":\"CN\","
          + "\"responsiblePersonName\":\"EU Rep BV\",\"responsiblePersonAddress\":\"Keizersgracht 1, Amsterdam\","
          + "\"responsiblePersonContact\":\"rep@eurep.nl\",\"noWarnings\":true}";

  private Response create(String tenant, String name, boolean online, String safety) {
    return as("/admin/products", tenant, "OWNER")
        .post(
            Entity.json(
                "{\"name\":\""
                    + name
                    + " "
                    + Ids.newId()
                    + "\",\"sellableOnline\":"
                    + online
                    + ",\"sellablePos\":true"
                    + (safety == null ? "" : ",\"safetyInformation\":" + safety)
                    + "}"));
  }

  private String created(String tenant, String name, boolean online, String safety) {
    Response r = create(tenant, name, online, safety);
    assertThat(r.getStatus(), is(201));
    return data(r).getString("id");
  }

  private Response update(String tenant, String id, boolean online) {
    return as("/admin/products/" + id, tenant, "OWNER")
        .put(
            Entity.json(
                "{\"name\":\"Renamed "
                    + id
                    + "\",\"sellableOnline\":"
                    + online
                    + ",\"sellablePos\":true}"));
  }

  private Response state(String tenant, String id, String json, String roles) {
    return as("/admin/products/" + id + "/safety-information", tenant, roles)
        .put(Entity.json(json));
  }

  // ── the rule ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A German business offers a product online only with what GPSR art.19 requires")
  void anEuOnlineOfferNeedsItsSafetyInformation() {
    Response bare = create(DE, "Candle", true, null);
    assertThat(bare.getStatus(), is(400));
    String refusal = bare.readEntity(String.class);
    assertThat(refusal, containsString("PRODUCT_SAFETY_INFORMATION_REQUIRED"));
    for (String item :
        new String[] {
          "MANUFACTURER_NAME",
          "MANUFACTURER_ADDRESS",
          "MANUFACTURER_CONTACT",
          "MANUFACTURER_COUNTRY",
          "WARNINGS"
        }) {
      assertThat(item, refusal, containsString(item));
    }

    String candle = created(DE, "Candle", true, EU_MAKER);
    JsonObject sheet =
        data(as("/admin/products/" + candle + "/safety-information", DE, "OWNER").get());
    assertThat(sheet.getBoolean("recorded"), is(true));
    assertThat(sheet.getBoolean("required"), is(true));
    assertThat(sheet.getJsonArray("missing").size(), is(0));
    assertThat(sheet.getString("manufacturerCountry"), is("FR"));
    assertThat(
        "an EU manufacturer needs no responsible person",
        !sheet.containsKey("responsiblePersonName") || sheet.isNull("responsiblePersonName"),
        is(true));

    // What the shopper sees with the offer, before signing in.
    Response shopper =
        target
            .path("/catalog/products/" + candle + "/safety-information")
            .request()
            .header("X-Tenant-Id", DE)
            .get();
    assertThat(shopper.getStatus(), is(200));
    String shown = shopper.readEntity(String.class);
    assertThat(shown, containsString("Atelier Lumière SAS"));
    assertThat(shown, containsString("Keep away from open flame."));

    Response noRep = create(DE, "Toy", true, CN_MAKER_NO_REP);
    assertThat(noRep.getStatus(), is(400));
    String why = noRep.readEntity(String.class);
    assertThat(why, containsString("RESPONSIBLE_PERSON_NAME"));
    assertThat(why, not(containsString("MANUFACTURER_NAME")));
    assertThat(create(DE, "Toy", true, CN_MAKER_WITH_REP).getStatus(), is(201));

    // For the till alone nothing is required, until it goes online.
    String tillOnly = created(DE, "Lamp", false, null);
    Response goOnline = update(DE, tillOnly, true);
    assertThat(goOnline.getStatus(), is(400));
    assertThat(
        goOnline.readEntity(String.class), containsString("PRODUCT_SAFETY_INFORMATION_REQUIRED"));
    assertThat(state(DE, tillOnly, EU_MAKER, "MANAGER").getStatus(), is(200));
    assertThat(update(DE, tillOnly, true).getStatus(), is(200));
  }

  @Test
  @DisplayName("A British business is not bound, and is told so")
  void aBritishBusinessIsNotBound() {
    String kettle = created(GB, "Kettle", true, null);
    JsonObject sheet =
        data(as("/admin/products/" + kettle + "/safety-information", GB, "OWNER").get());
    assertThat(sheet.getBoolean("recorded"), is(false));
    assertThat(sheet.getBoolean("required"), is(false));
    assertThat(sheet.getJsonArray("missing").size(), is(0));
    assertThat(
        as("/admin/products/safety-information/missing", GB, "OWNER").get(String.class),
        containsString("\"data\":[]"));
    // It may still state it, and it is shown.
    assertThat(state(GB, kettle, CN_MAKER_NO_REP, "OWNER").getStatus(), is(200));
  }

  @Test
  @DisplayName("An online product's statement cannot be cleared; offline, it can")
  void theStatementOfAnOnlineProductCannotBeCleared() {
    String candle = created(DE, "Candle", true, EU_MAKER);
    Response cleared = state(DE, candle, "{\"manufacturerName\":\"Atelier Lumière SAS\"}", "OWNER");
    assertThat(cleared.getStatus(), is(400));
    assertThat(cleared.readEntity(String.class), containsString("MANUFACTURER_ADDRESS"));
    assertThat(
        data(as("/admin/products/" + candle + "/safety-information", DE, "OWNER").get())
            .getString("manufacturerContact"),
        is("securite@lumiere.fr"));

    assertThat(update(DE, candle, false).getStatus(), is(200));
    assertThat(
        state(DE, candle, "{\"manufacturerName\":\"Atelier Lumière SAS\"}", "OWNER").getStatus(),
        is(200));
  }

  @Test
  @DisplayName("Products listed before the rule are named with what each lacks")
  void legacyOnlineProductsAreListedAsMissing() throws Exception {
    String legacy = Ids.newId().toString();
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO product.products (id, tenant_id, name, status, sellable_online, sellable_pos, created_at, updated_at)"
                    + " VALUES (?::uuid, ?::uuid, 'Listed before the rule', 'ACTIVE', true, true, now(), now())")) {
      ps.setString(1, legacy);
      ps.setString(2, DE);
      ps.executeUpdate();
    }
    String complete = created(DE, "Complete", true, EU_MAKER);
    String missing =
        as("/admin/products/safety-information/missing", DE, "OWNER").get(String.class);
    assertThat(missing, containsString(legacy));
    assertThat(missing, containsString("MANUFACTURER_NAME"));
    assertThat(missing, not(containsString(complete)));
  }

  @Test
  @DisplayName(
      "A bulk import adds products for the till, and refuses to offer them online without it")
  void aBulkImportCannotListOnlineWithoutIt() {
    String run = Ids.newId().toString().substring(24);
    Response r =
        as("/admin/import", DE, "OWNER")
            .post(
                Entity.json(
                    "{\"products\":[{\"name\":\"Imported online "
                        + run
                        + "\",\"variants\":[{\"sku\":\"IMP-ON-"
                        + run
                        + "\"}]},"
                        + "{\"name\":\"Imported till "
                        + run
                        + "\",\"sellableOnline\":false,\"variants\":[{\"sku\":\"IMP-TILL-"
                        + run
                        + "\"}]}]}"));
    assertThat(r.getStatus(), lessThan(300));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("Imported online " + run));
    assertThat(body, containsString("PRODUCT_SAFETY_INFORMATION_REQUIRED"));
    assertThat(body, containsString("IMP-TILL-" + run));
    assertThat(body, not(containsString("IMP-ON-" + run + "\",\"variantId")));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Malformed statements, the wrong caller and another business are refused")
  void refusals() {
    String candle = created(DE, "Candle", false, null);
    String[][] bad = {
      {"{\"manufacturerContact\":\"call us\"}", "SAFETY_CONTACT_INVALID"},
      {"{\"manufacturerContact\":\"javascript:alert(1)\"}", "SAFETY_CONTACT_INVALID"},
      {"{\"responsiblePersonContact\":\"http://rep.example\"}", "SAFETY_CONTACT_INVALID"},
      {"{\"manufacturerCountry\":\"DEU\"}", "SAFETY_COUNTRY_INVALID"},
      {"{\"manufacturerCountry\":\"DE' OR '1'='1\"}", "SAFETY_COUNTRY_INVALID"},
      {"{\"manufacturerName\":\"" + "x".repeat(201) + "\"}", "SAFETY_TEXT_INVALID"},
      {"{\"manufacturerName\":\"Acme\\u0007\"}", "SAFETY_TEXT_INVALID"},
      {"{\"warnings\":\"Hot\",\"noWarnings\":true}", "SAFETY_WARNINGS_CONFLICT"},
    };
    for (String[] c : bad) {
      Response r = state(DE, candle, c[0], "OWNER");
      assertThat(c[1], r.getStatus(), is(400));
      assertThat(c[1], r.readEntity(String.class), containsString(c[1]));
    }
    assertThat(create(DE, "Bad", true, "{\"manufacturerCountry\":\"ZZ\"}").getStatus(), is(400));
    assertThat(state(DE, Ids.newId().toString(), EU_MAKER, "OWNER").getStatus(), is(404));
    assertThat(
        as("/admin/products/" + candle + "/safety-information", GB, "OWNER").get().getStatus(),
        is(404));
    assertThat(
        "another business cannot state it either",
        state(GB, candle, EU_MAKER, "OWNER").getStatus(),
        is(404));
    assertThat(state(DE, candle, EU_MAKER, "CASHIER").getStatus(), is(403));
    assertThat(
        as("/admin/products/" + candle + "/safety-information", DE, "STOREKEEPER")
            .get()
            .getStatus(),
        is(403));
    assertThat(state(DE, candle, EU_MAKER, null).getStatus(), is(403));
    assertThat(
        as("/admin/products/" + candle + "/safety-information", DE, "OWNER").delete().getStatus(),
        is(405));
  }

  // ── abuse ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Twenty at once putting a product online and clearing its statement never leave it online without")
  void aRaceNeverLeavesAnOnlineOfferWithoutIt() throws Exception {
    String lamp = created(DE, "Lamp", false, EU_MAKER);
    var pool = Executors.newFixedThreadPool(20);
    try {
      List<Future<Integer>> tries = new ArrayList<>();
      for (int k = 0; k < 20; k++) {
        final boolean clear = k % 2 == 0;
        tries.add(
            pool.submit(
                () ->
                    clear
                        ? state(DE, lamp, "{\"manufacturerName\":\"Atelier Lumière SAS\"}", "OWNER")
                            .getStatus()
                        : update(DE, lamp, true).getStatus()));
      }
      for (var f : tries) assertThat(f.get(), lessThan(500));
    } finally {
      pool.shutdownNow();
    }
    boolean online =
        data(as("/admin/products/" + lamp, DE, "OWNER").get()).getBoolean("sellableOnline");
    int missing =
        data(as("/admin/products/" + lamp + "/safety-information", DE, "OWNER").get())
            .getJsonArray("missing")
            .size();
    assertThat(
        "online " + online + " with " + missing + " missing", online && missing > 0, is(false));
  }

  // ── refusals the negative-coverage audit found untested (1 Oct 2026) ─────────

  @Test
  @DisplayName("The shopper's safety sheet needs a shop, and shows nothing of another business's")
  void theShoppersSheetIsRefusedWithNoShopOrForAnotherBusinesssProduct() {
    String candle = created(DE, "Candle", true, EU_MAKER);
    String path = "/catalog/products/" + candle + "/safety-information";

    // No storefront named: refused, not guessed.
    Response noShop = target.path(path).request().get();
    assertThat(noShop.getStatus(), is(400));
    assertThat(noShop.readEntity(String.class), containsString("NO_STOREFRONT"));

    // Our product named from another business's storefront: not found, and nothing of ours shown.
    Response other = as(path, GB, null).get();
    assertThat(other.getStatus(), is(404));
    String shown = other.readEntity(String.class);
    assertThat(shown, containsString("PRODUCT_NOT_FOUND"));
    assertThat(shown, not(containsString("Atelier Lumière SAS")));

    // An id nobody holds.
    Response unknown =
        as("/catalog/products/" + Ids.newId() + "/safety-information", DE, null).get();
    assertThat(unknown.getStatus(), is(404));
    assertThat(unknown.readEntity(String.class), containsString("PRODUCT_NOT_FOUND"));

    // Our own storefront still reads it, so the refusals above are about who asks.
    assertThat(as(path, DE, null).get().getStatus(), is(200));
  }
}
