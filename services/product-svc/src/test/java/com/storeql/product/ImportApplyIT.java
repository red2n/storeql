package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.product.service.ImportWorker;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A dry run applied (intent/catalogue-import.md): products, then their VAT categories, then prices,
 * in chunks, by a background worker acting as the person who started it. Restart-safe, idempotent
 * by SKU, one at a time per business, and never past another business.
 */
@HelidonTest
class ImportApplyIT {

  private static final String T = Ids.newId().toString();
  private static final String OTHER = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String OTHER_STORE = Ids.newId().toString();
  private static final String SHELF_LIST = Ids.newId().toString();
  private static final String NET_LIST = Ids.newId().toString();
  private static final String MADE_LIST = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final JsonStub PRICING;
  private static final JsonStub CONSUL;
  private static final AtomicBoolean VAT_DOWN = new AtomicBoolean(false);
  private static final AtomicBoolean VAT_REFUSES = new AtomicBoolean(false);

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "product");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.product.import-worker.enabled", "false");
    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .with(OTHER, "GBP", "GB")
        .withStore(T, STORE, "GB")
        .withStore(OTHER, OTHER_STORE, "GB");

    PRICING = JsonStub.start();
    PRICING.on(
        "GET",
        "/vat-rates",
        200,
        "{\"data\":[{\"code\":\"T1\"},{\"code\":\"T5\"},{\"code\":\"T0\"}]}");
    PRICING.on(
        "GET",
        "/price-lists/" + SHELF_LIST,
        200,
        "{\"data\":{\"id\":\""
            + SHELF_LIST
            + "\",\"currency\":\"GBP\",\"taxMode\":\"INCLUSIVE\",\"active\":true}}");
    PRICING.on(
        "GET",
        "/price-lists/" + NET_LIST,
        200,
        "{\"data\":{\"id\":\""
            + NET_LIST
            + "\",\"currency\":\"GBP\",\"taxMode\":\"EXCLUSIVE\",\"active\":true}}");
    PRICING.on("POST", "/admin/price-lists", 201, "{\"data\":{\"id\":\"" + MADE_LIST + "\"}}");
    PRICING.on(
        "POST",
        "/product-vat-categories/batch",
        call -> {
          if (VAT_DOWN.get()) return new JsonStub.Answer(503, "{}");
          if (VAT_REFUSES.get()) {
            return new JsonStub.Answer(
                400,
                "{\"code\":\"PRICING_VAT_BATCH_INVALID\",\"error\":{\"code\":\"PRICING_VAT_BATCH_INVALID\","
                    + "\"message\":\"nothing was assigned: row 1: VAT code T1 is not one of this business's rates\"}}");
          }
          int n =
              Json.createReader(new StringReader(call.body()))
                  .readObject()
                  .getJsonArray("items")
                  .size();
          return JsonStub.Answer.ok("{\"assigned\":" + n + "}");
        });
    for (String list : new String[] {SHELF_LIST, NET_LIST, MADE_LIST}) {
      PRICING.on(
          "POST",
          "/admin/price-lists/" + list + "/items/batch",
          call -> {
            int n =
                Json.createReader(new StringReader(call.body()))
                    .readObject()
                    .getJsonArray("items")
                    .size();
            return JsonStub.Answer.ok("{\"upserted\":" + n + ",\"errors\":[]}");
          });
    }
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
  @Inject ImportWorker worker;

  @AfterAll
  static void stop() {
    try {
      PRICING.close();
      CONSUL.close();
      System.clearProperty("storeql.consul.host");
      System.clearProperty("storeql.consul.port");
      System.clearProperty("storeql.product.import-worker.enabled");
    } finally {
      try {
        PG.stop();
      } finally {
        REDIS.stop();
      }
    }
  }

  // ── harness ──────────────────────────────────────────────────────────────────

  private record Staff(String tenant, String roles) {}

  private static final Staff OWNER = new Staff(T, "OWNER");
  private static final Staff RIVAL = new Staff(OTHER, "OWNER");

  private Invocation.Builder as(String path, Staff who, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i + 1 < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request()
        .header("X-Tenant-Id", who.tenant())
        .header("X-User-Id", Ids.newId().toString())
        .header("X-Roles", who.roles());
  }

  private static final String MAPPING =
      "{\"columns\":{\"sku\":\"PLU\",\"name\":\"Description\",\"barcode\":\"EAN\",\"category\":\"Department\","
          + "\"vatCode\":\"VAT\",\"price\":\"Retail Price\",\"brand\":\"Brand\",\"soldBy\":\"Sold By\",\"unit\":\"Unit\"},"
          + "\"aliasColumns\":[{\"header\":\"Old EAN\",\"kind\":\"OLD_EAN\",\"packQty\":1}],"
          + "\"vatCodes\":{\"A\":\"T1\",\"B\":\"T5\",\"C\":\"T0\"},\"priceBasis\":\"INCLUSIVE\","
          + "\"decimalMark\":\".\",\"soldByValues\":{\"EACH\":\"EACH\",\"KG\":\"WEIGHT\"},\"categorySeparator\":\">\"}";

  private static final String HEADER =
      "PLU,Description,EAN,Department,VAT,Retail Price,Brand,Sold By,Unit,Old EAN\n";

  private String mapping(Staff who, String json) {
    String name = "m-" + Ids.newId();
    assertThat(
        as("/admin/catalogue-imports/mappings/" + name, who)
            .put(Entity.entity(json, MediaType.APPLICATION_JSON))
            .getStatus(),
        is(200));
    return name;
  }

  private JsonObject dryRun(Staff who, String store, String mapping, String csv) {
    Response r =
        as(
                "/admin/catalogue-imports",
                who,
                "storeId",
                store,
                "mapping",
                mapping,
                "fileName",
                "x.csv")
            .header("Idempotency-Key", Ids.newId().toString())
            .post(Entity.entity(csv.getBytes(StandardCharsets.UTF_8), "text/csv"));
    return data(r, 201);
  }

  private Response applyRaw(Staff who, String dryRunId, String priceListId, String key) {
    var b =
        priceListId == null
            ? as("/admin/catalogue-imports/" + dryRunId + "/apply", who)
            : as(
                "/admin/catalogue-imports/" + dryRunId + "/apply", who, "priceListId", priceListId);
    if (key != null) b = b.header("Idempotency-Key", key);
    return b.post(Entity.entity("{}", MediaType.APPLICATION_JSON));
  }

  private JsonObject apply(Staff who, String dryRunId, String priceListId) {
    return data(applyRaw(who, dryRunId, priceListId, Ids.newId().toString()), 202);
  }

  private JsonObject job(Staff who, String id) {
    return data(as("/admin/catalogue-imports/" + id, who).get(), 200);
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Json.createReader(new StringReader(body)).readObject().getString("code", "");
  }

  private String one(String sql) throws Exception {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private void exec(String sql) throws Exception {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private long variants(String tenant, String skuPrefix) throws Exception {
    return Long.parseLong(
        one(
            "SELECT count(*) FROM product.product_variants WHERE tenant_id='"
                + tenant
                + "' AND sku LIKE '"
                + skuPrefix
                + "%'"));
  }

  private static String sku() {
    return "S" + Ids.newId().toString().substring(26);
  }

  private java.util.List<JsonStub.Call> pricingCalls(String method, String pathEnds) {
    return PRICING.calls().stream()
        .filter(c -> c.method().equals(method) && c.path().endsWith(pathEnds))
        .toList();
  }

  // ── the happy path ───────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "an applied dry run makes the products, gives them their VAT and prices them, and says what it did")
  void applyHappyPath() throws Exception {
    String m = mapping(OWNER, MAPPING);
    String p = sku();
    String csv =
        HEADER
            + p
            + "1,Hovis Wholemeal 800g,5012345678900,Bakery > Bread,A,1.29,Hovis,,,036000291452\n"
            + p
            + "2,Strawberry Jam 340g,,Pantry > Jam,B,1.99,,,,\n"
            + p
            + "3,Loose Cheddar,,Dairy,A,9.50,,KG,KG,\n"
            + p
            + "4,Odd VAT,,,Q,1.29,,,,\n"
            + p
            + "2,Strawberry Jam 340g,,Pantry > Jam,B,1.99,,,,\n";
    JsonObject dry = dryRun(OWNER, STORE, m, csv);
    PRICING.reset();

    JsonObject queued = apply(OWNER, dry.getString("id"), SHELF_LIST);
    assertThat(queued.getString("kind"), is("APPLY"));
    assertThat(queued.getString("status"), is("QUEUED"));
    worker.drain();
    JsonObject done = job(OWNER, queued.getString("id"));

    assertThat(done.toString(), done.getString("status"), is("DONE"));
    JsonObject s = done.getJsonObject("summary");
    assertThat(s.getInt("created"), is(3));
    assertThat(s.getInt("assigned"), is(3));
    assertThat(s.getInt("upserted"), is(3));
    assertThat(s.getInt("aliases"), is(1));
    assertThat(variants(T, p), is(3L));
    // the product, its brand, category chain, barcode and how it is sold
    assertThat(
        one(
            "SELECT p.name FROM product.product_variants v JOIN product.products p ON p.id=v.product_id WHERE v.tenant_id='"
                + T
                + "' AND v.sku='"
                + p
                + "1'"),
        is("Hovis Wholemeal 800g"));
    assertThat(
        one(
            "SELECT b.name FROM product.product_variants v JOIN product.products p ON p.id=v.product_id JOIN product.brands b ON b.id=p.brand_id WHERE v.tenant_id='"
                + T
                + "' AND v.sku='"
                + p
                + "1'"),
        is("Hovis"));
    assertThat(
        one(
            "SELECT c.name || '<' || pc.name FROM product.product_variants v JOIN product.products p ON p.id=v.product_id JOIN product.categories c ON c.id=p.category_id JOIN product.categories pc ON pc.id=c.parent_id WHERE v.tenant_id='"
                + T
                + "' AND v.sku='"
                + p
                + "1'"),
        is("Bread<Bakery"));
    assertThat(
        one(
            "SELECT gtin14 FROM product.product_variants WHERE tenant_id='"
                + T
                + "' AND sku='"
                + p
                + "1'"),
        is("05012345678900"));
    assertThat(
        one(
            "SELECT sold_by || '/' || net_content_uom FROM product.product_variants WHERE tenant_id='"
                + T
                + "' AND sku='"
                + p
                + "3'"),
        is("WEIGHT/KG"));
    assertThat(
        one(
            "SELECT sellable_pos::text || '/' || sellable_online::text FROM product.products p JOIN product.product_variants v ON v.product_id=p.id WHERE v.tenant_id='"
                + T
                + "' AND v.sku='"
                + p
                + "1'"),
        is("true/false"));
    // the old code finds the same product
    assertThat(
        one(
            "SELECT v.sku FROM product.variant_barcode_aliases a JOIN product.product_variants v ON v.id=a.variant_id WHERE a.tenant_id='"
                + T
                + "' AND a.gtin14='00036000291452'"),
        is(p + "1"));

    // VAT went in one call under the starter's own headers; prices in the list asked for.
    var vat = pricingCalls("POST", "/product-vat-categories/batch");
    assertThat(vat.size(), is(1));
    assertThat(vat.get(0).tenantId(), is(T));
    assertThat(vat.get(0).body(), containsString("\"vatCode\":\"T5\""));
    var prices = pricingCalls("POST", "/admin/price-lists/" + SHELF_LIST + "/items/batch");
    assertThat(prices.size(), is(1));
    assertThat(prices.get(0).body(), containsString("\"price\":9.50"));
    assertThat(prices.get(0).header("x-roles"), is("OWNER"));
  }

  @Test
  @DisplayName("with no price list named one is made, in the file's price basis")
  void aPriceListIsMade() throws Exception {
    String m = mapping(OWNER, MAPPING);
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + sku() + ",Bread,,,A,1.29,,,,\n");
    PRICING.reset();

    JsonObject queued = apply(OWNER, dry.getString("id"), null);
    worker.drain();

    assertThat(job(OWNER, queued.getString("id")).getString("status"), is("DONE"));
    assertThat(job(OWNER, queued.getString("id")).getString("priceListId"), is(MADE_LIST));
    var made = pricingCalls("POST", "/admin/price-lists");
    assertThat(made.get(0).body(), containsString("\"taxMode\":\"INCLUSIVE\""));
    assertThat(
        pricingCalls("POST", "/admin/price-lists/" + MADE_LIST + "/items/batch").size(), is(1));
  }

  @Test
  @DisplayName("a price list of the other price basis is refused: a price is never converted")
  void priceBasisMismatch() throws Exception {
    String m = mapping(OWNER, MAPPING);
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + sku() + ",Bread,,,A,1.29,,,,\n");

    assertThat(
        code(applyRaw(OWNER, dry.getString("id"), NET_LIST, Ids.newId().toString()), 409),
        is("IMPORT_PRICE_BASIS_MISMATCH"));
  }

  // ── what an apply needs ──────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "an apply needs a finished dry run, its mapping unchanged, a key, and is refused for another business")
  void whatAnApplyNeeds() throws Exception {
    String m = mapping(OWNER, MAPPING);
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + sku() + ",Bread,,,A,1.29,,,,\n");

    // no key
    assertThat(applyRaw(OWNER, dry.getString("id"), SHELF_LIST, null).getStatus(), is(400));
    // another business cannot apply it, nor even see it
    assertThat(
        applyRaw(RIVAL, dry.getString("id"), SHELF_LIST, Ids.newId().toString()).getStatus(),
        is(404));
    // the mapping changed since: the dry run no longer says what would happen
    assertThat(
        as("/admin/catalogue-imports/mappings/" + m, OWNER)
            .put(
                Entity.entity(
                    MAPPING.replace("\"A\":\"T1\"", "\"A\":\"T5\""), MediaType.APPLICATION_JSON))
            .getStatus(),
        is(200));
    assertThat(
        code(applyRaw(OWNER, dry.getString("id"), SHELF_LIST, Ids.newId().toString()), 409),
        is("IMPORT_DRY_RUN_REQUIRED"));
  }

  @Test
  @DisplayName("an apply is not a dry run; and a dry run older than a day has expired")
  void applyOfAnApplyAndExpiry() throws Exception {
    String m = mapping(OWNER, MAPPING);
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + sku() + ",Bread,,,A,1.29,,,,\n");
    JsonObject queued = apply(OWNER, dry.getString("id"), SHELF_LIST);
    worker.drain();

    assertThat(
        code(applyRaw(OWNER, queued.getString("id"), SHELF_LIST, Ids.newId().toString()), 409),
        is("IMPORT_DRY_RUN_REQUIRED"));

    JsonObject old = dryRun(OWNER, STORE, m, HEADER + sku() + ",Bread,,,A,1.29,,,,\n");
    exec(
        "UPDATE product.import_jobs SET created_at = now() - interval '25 hours' WHERE id = '"
            + old.getString("id")
            + "'");
    assertThat(
        code(applyRaw(OWNER, old.getString("id"), SHELF_LIST, Ids.newId().toString()), 409),
        is("IMPORT_JOB_EXPIRED"));
  }

  @Test
  @DisplayName("one apply at a time per business; the same key is the same job")
  void oneAtATime() throws Exception {
    String m = mapping(OWNER, MAPPING);
    JsonObject first = dryRun(OWNER, STORE, m, HEADER + sku() + ",Bread,,,A,1.29,,,,\n");
    JsonObject second = dryRun(OWNER, STORE, m, HEADER + sku() + ",Bun,,,A,0.59,,,,\n");
    String key = Ids.newId().toString();

    JsonObject queued = data(applyRaw(OWNER, first.getString("id"), SHELF_LIST, key), 202);
    JsonObject replay = data(applyRaw(OWNER, first.getString("id"), SHELF_LIST, key), 202);
    assertThat(replay.getString("id"), is(queued.getString("id")));
    assertThat(
        code(applyRaw(OWNER, second.getString("id"), SHELF_LIST, Ids.newId().toString()), 409),
        is("IMPORT_ALREADY_RUNNING"));

    worker.drain();
    // finished: the business can apply the next
    assertThat(
        applyRaw(OWNER, second.getString("id"), SHELF_LIST, Ids.newId().toString()).getStatus(),
        is(202));
    worker.drain();
  }

  // ── restart safety, idempotence, refusals ────────────────────────────────────

  @Test
  @DisplayName(
      "a peer that is not there leaves the work waiting; when it is back the job finishes with nothing written twice")
  void resumesAfterAPeerWasDown() throws Exception {
    String m = mapping(OWNER, MAPPING);
    String p = sku();
    JsonObject dry =
        dryRun(OWNER, STORE, m, HEADER + p + "1,Bread,,,A,1.29,,,,\n" + p + "2,Jam,,,B,1.99,,,,\n");
    JsonObject queued = apply(OWNER, dry.getString("id"), SHELF_LIST);

    VAT_DOWN.set(true);
    try {
      worker.drain();
    } finally {
      VAT_DOWN.set(false);
    }
    // products are in; the job is waiting, not failed
    assertThat(variants(T, p), is(2L));
    assertThat(job(OWNER, queued.getString("id")).getString("status"), is("APPLYING"));

    // the lease runs out (the worker would have died); the next claim resumes at the VAT chunk
    exec(
        "UPDATE product.import_jobs SET lease_until = now() - interval '1 second' WHERE id = '"
            + queued.getString("id")
            + "'");
    worker.drain();

    JsonObject done = job(OWNER, queued.getString("id"));
    assertThat(done.getString("status"), is("DONE"));
    assertThat(done.getJsonObject("summary").getInt("created"), is(2));
    assertThat(done.getJsonObject("summary").getInt("assigned"), is(2));
    assertThat(variants(T, p), is(2L));
    assertThat(
        one(
            "SELECT count(*) FROM product.products p JOIN product.product_variants v ON v.product_id=p.id WHERE v.tenant_id='"
                + T
                + "' AND v.sku LIKE '"
                + p
                + "%'"),
        is("2"));
  }

  @Test
  @DisplayName(
      "a refusal by pricing ends the job as failed with its own code; the same dry run can be applied again")
  void aBusinessRefusalFailsTheJob() throws Exception {
    String m = mapping(OWNER, MAPPING);
    String p = sku();
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + p + "1,Bread,,,A,1.29,,,,\n");
    VAT_REFUSES.set(true);
    JsonObject queued = apply(OWNER, dry.getString("id"), SHELF_LIST);
    try {
      worker.drain();
    } finally {
      VAT_REFUSES.set(false);
    }

    JsonObject failed = job(OWNER, queued.getString("id"));
    assertThat(failed.getString("status"), is("FAILED"));
    assertThat(failed.getString("failureCode"), is("PRICING_VAT_BATCH_INVALID"));
    assertThat(variants(T, p), is(1L));

    JsonObject again = apply(OWNER, dry.getString("id"), SHELF_LIST);
    worker.drain();
    assertThat(job(OWNER, again.getString("id")).getString("status"), is("DONE"));
    // the product was not made twice
    assertThat(variants(T, p), is(1L));
  }

  @Test
  @DisplayName(
      "a corrected file updates in place by SKU: nothing is duplicated, a blank cell erases nothing")
  void reimport() throws Exception {
    String m = mapping(OWNER, MAPPING);
    String p = sku();
    JsonObject first =
        dryAndApply(m, HEADER + p + "1,Bread,4006381333931,Bakery > Bread,A,1.29,Hovis,,,\n");
    assertThat(first.toString(), first.getJsonObject("summary").getInt("created"), is(1));

    // The same product again with a corrected name and nothing else said.
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + p + "1,Wholemeal Bread,,,,1.39,,,,\n");
    assertThat(
        dry.toString(),
        dry.getJsonObject("summary").getJsonObject("byAction").getInt("UPDATE", -1),
        is(1));
    JsonObject queued = apply(OWNER, dry.getString("id"), SHELF_LIST);
    worker.drain();

    JsonObject done = job(OWNER, queued.getString("id"));
    assertThat(done.getJsonObject("summary").getInt("updated"), is(1));
    assertThat(done.getJsonObject("summary").getInt("created", 0), is(0));
    assertThat(variants(T, p), is(1L));
    assertThat(
        one(
            "SELECT p.name FROM product.product_variants v JOIN product.products p ON p.id=v.product_id WHERE v.tenant_id='"
                + T
                + "' AND v.sku='"
                + p
                + "1'"),
        is("Wholemeal Bread"));
    // the barcode and brand the second file did not mention are still there
    assertThat(
        one(
            "SELECT gtin14 FROM product.product_variants WHERE tenant_id='"
                + T
                + "' AND sku='"
                + p
                + "1'"),
        is("04006381333931"));
    assertThat(
        one(
            "SELECT b.name FROM product.product_variants v JOIN product.products p ON p.id=v.product_id JOIN product.brands b ON b.id=p.brand_id WHERE v.tenant_id='"
                + T
                + "' AND v.sku='"
                + p
                + "1'"),
        is("Hovis"));
  }

  @Test
  @DisplayName(
      "more than 500 rows run in chunks; every row is written and prices go in calls of at most 500")
  void chunks() throws Exception {
    String m = mapping(OWNER, MAPPING);
    String p = sku();
    StringBuilder csv = new StringBuilder(HEADER);
    for (int i = 0; i < 520; i++)
      csv.append(p).append('-').append(i).append(",Item ").append(i).append(",,,A,1.29,,,,\n");
    JsonObject dry = dryRun(OWNER, STORE, m, csv.toString());
    PRICING.reset();

    JsonObject queued = apply(OWNER, dry.getString("id"), SHELF_LIST);
    worker.drain();

    JsonObject done = job(OWNER, queued.getString("id"));
    assertThat(done.toString(), done.getString("status"), is("DONE"));
    assertThat(done.getJsonObject("summary").getInt("created"), is(520));
    assertThat(done.getJsonObject("summary").getInt("chunks"), is(6));
    assertThat(variants(T, p), is(520L));
    var prices = pricingCalls("POST", "/admin/price-lists/" + SHELF_LIST + "/items/batch");
    assertThat(prices.size(), is(2));
    for (var c : prices) {
      JsonArray items =
          Json.createReader(new StringReader(c.body())).readObject().getJsonArray("items");
      assertThat(items.size() <= 500, is(true));
    }
  }

  // ── isolation ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "the worker writes only the job's business; the same SKU in two businesses is two products")
  void isolation() throws Exception {
    String m = mapping(OWNER, MAPPING);
    String p = sku();
    dryAndApply(m, HEADER + p + "1,Bread,,,A,1.29,,,,\n");

    assertThat(variants(OTHER, p), is(0L));
    String theirs = mapping(RIVAL, MAPPING);
    JsonObject dry =
        dryRun(RIVAL, OTHER_STORE, theirs, HEADER + p + "1,Their bread,,,A,1.29,,,,\n");
    assertThat(dry.getJsonObject("summary").getJsonObject("byAction").getInt("CREATE"), is(1));
    JsonObject queued = apply(RIVAL, dry.getString("id"), SHELF_LIST);
    worker.drain();
    assertThat(job(RIVAL, queued.getString("id")).getString("status"), is("DONE"));
    assertThat(variants(T, p), is(1L));
    assertThat(variants(OTHER, p), is(1L));
    assertThat(
        one(
            "SELECT p.name FROM product.product_variants v JOIN product.products p ON p.id=v.product_id WHERE v.tenant_id='"
                + T
                + "' AND v.sku='"
                + p
                + "1'"),
        is("Bread"));
  }

  private JsonObject dryAndApply(String mapping, String csv) {
    JsonObject dry = dryRun(OWNER, STORE, mapping, csv);
    JsonObject queued = apply(OWNER, dry.getString("id"), SHELF_LIST);
    worker.drain();
    JsonObject done = job(OWNER, queued.getString("id"));
    assertThat(done.toString(), done.getString("status"), is("DONE"));
    return done;
  }
}
