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
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opening stock and the reconciliation report (intent catalogue-import): the STOCK phase asks
 * inventory-svc once per item under the dry run's id, a call that landed and was not answered is
 * asked again without doubling, and the report sets the file against the catalogue, the price list
 * and the stock as they are afterwards. pricing-svc and inventory-svc are small stateful fakes; the
 * real inventory-svc side is proved by its own OpeningStockIT.
 */
@HelidonTest
class ImportReconcileIT {

  private static final String T = Ids.newId().toString();
  private static final String OTHER = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String OTHER_STORE = Ids.newId().toString();
  private static final String SHELF_LIST = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final JsonStub PRICING;
  private static final JsonStub INVENTORY;
  private static final JsonStub CONSUL;

  /** Prices as the fake pricing-svc holds them, by variant id. */
  private static final Map<String, BigDecimal> PRICES = new ConcurrentHashMap<>();

  /** Opened stock as the fake inventory-svc holds it: store|variant to job|qty|cost. */
  private static final Map<String, String[]> OPENED = new ConcurrentHashMap<>();

  /** The next opening call is applied, then not answered (503), once. */
  private static final AtomicBoolean LOSE_ANSWER = new AtomicBoolean(false);

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
        "POST",
        "/product-vat-categories/batch",
        call -> {
          int n = items(call.body()).size();
          return JsonStub.Answer.ok("{\"assigned\":" + n + "}");
        });
    PRICING.on(
        "POST",
        "/admin/price-lists/" + SHELF_LIST + "/items/batch",
        call -> {
          var items = items(call.body());
          for (var o : items.getValuesAs(JsonObject.class)) {
            PRICES.put(o.getString("variantId"), o.getJsonNumber("price").bigDecimalValue());
          }
          return JsonStub.Answer.ok("{\"upserted\":" + items.size() + ",\"errors\":[]}");
        });
    PRICING.on(
        "GET",
        "/price-lists/" + SHELF_LIST + "/items",
        call -> {
          var data = Json.createArrayBuilder();
          PRICES.forEach(
              (variant, price) ->
                  data.add(
                      Json.createObjectBuilder()
                          .add("variantId", variant)
                          .add("price", price)
                          .add("minQty", 1)));
          return new JsonStub.Answer(
              200,
              Json.createObjectBuilder()
                  .add("data", data)
                  .add("meta", Json.createObjectBuilder().addNull("nextCursor"))
                  .build()
                  .toString());
        });

    INVENTORY = JsonStub.start();
    INVENTORY.on(
        "POST",
        "/admin/inventory/opening-stock",
        call -> {
          JsonObject req = Json.createReader(new StringReader(call.body())).readObject();
          String job = req.getString("jobId");
          String store = req.getString("storeId");
          int loaded = 0;
          int replayed = 0;
          int already = 0;
          var lines = Json.createArrayBuilder();
          for (var l : req.getJsonArray("lines").getValuesAs(JsonObject.class)) {
            String key = store + "|" + l.getString("variantId");
            String[] held = OPENED.get(key);
            String outcome;
            if (held == null) {
              OPENED.put(
                  key,
                  new String[] {
                    job,
                    l.getJsonNumber("qty").bigDecimalValue().toPlainString(),
                    l.containsKey("unitCost") ? l.getJsonNumber("unitCost").toString() : null,
                    l.containsKey("expiryDate") ? l.getString("expiryDate") : "",
                    l.containsKey("batchNo") ? l.getString("batchNo") : ""
                  });
              outcome = "LOADED";
              loaded++;
            } else if (held[0].equals(job)) {
              outcome = "REPLAYED";
              replayed++;
            } else {
              outcome = "ALREADY_OPENED";
              already++;
            }
            lines.add(
                Json.createObjectBuilder()
                    .add("variantId", l.getString("variantId"))
                    .add("outcome", outcome)
                    .add("batchId", Ids.newId().toString())
                    .add("held", false));
          }
          if (LOSE_ANSWER.getAndSet(false)) {
            return new JsonStub.Answer(503, "{}");
          }
          return JsonStub.Answer.ok(
              Json.createObjectBuilder()
                  .add("loaded", loaded)
                  .add("replayed", replayed)
                  .add("alreadyOpened", already)
                  .add("lines", lines)
                  .build()
                  .toString());
        });

    CONSUL = JsonStub.start();
    CONSUL.on("GET", "/v1/health/service/" + "pricing-svc", 200, instance(PRICING));
    CONSUL.on("GET", "/v1/health/service/" + "inventory-svc", 200, instance(INVENTORY));
    URI consul = URI.create(CONSUL.baseUrl());
    System.setProperty("storeql.consul.host", consul.getHost());
    System.setProperty("storeql.consul.port", String.valueOf(consul.getPort()));
  }

  private static JsonArray items(String body) {
    return Json.createReader(new StringReader(body)).readObject().getJsonArray("items");
  }

  private static String instance(JsonStub stub) {
    URI u = URI.create(stub.baseUrl());
    return "[{\"Node\":{\"Address\":\""
        + u.getHost()
        + "\"},\"Service\":{\"Address\":\""
        + u.getHost()
        + "\",\"Port\":"
        + u.getPort()
        + "}}]";
  }

  @Inject WebTarget target;
  @Inject ImportWorker worker;

  @AfterAll
  static void stop() {
    try {
      PRICING.close();
      INVENTORY.close();
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
  private static final Staff CASHIER = new Staff(T, "CASHIER");

  private Invocation.Builder as(String path, Staff who, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i + 1 < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request()
        .header("X-Tenant-Id", who.tenant())
        .header("X-User-Id", Ids.newId().toString())
        .header("X-Roles", who.roles());
  }

  private static final String MAPPING =
      "{\"columns\":{\"sku\":\"PLU\",\"name\":\"Description\",\"barcode\":\"EAN\","
          + "\"vatCode\":\"VAT\",\"price\":\"Retail Price\",\"cost\":\"Cost\",\"stockQty\":\"On Hand\","
          + "\"expiry\":\"Best Before\",\"lot\":\"Lot\"},"
          + "\"aliasColumns\":[{\"header\":\"Old EAN\",\"kind\":\"OLD_EAN\",\"packQty\":1}],"
          + "\"vatCodes\":{\"A\":\"T1\",\"B\":\"T5\"},\"priceBasis\":\"INCLUSIVE\","
          + "\"decimalMark\":\".\",\"dateFormat\":\"dd/MM/yyyy\",\"categorySeparator\":\">\"}";

  private static final String HEADER =
      "PLU,Description,EAN,VAT,Retail Price,Cost,On Hand,Best Before,Lot,Old EAN\n";

  private String mapping(Staff who) {
    String name = "m-" + Ids.newId();
    assertThat(
        as("/admin/catalogue-imports/mappings/" + name, who)
            .put(Entity.entity(MAPPING, MediaType.APPLICATION_JSON))
            .getStatus(),
        is(200));
    return name;
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private JsonObject dryRun(Staff who, String store, String mapping, String csv) {
    JsonObject made =
        data(
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
                .post(Entity.entity(csv.getBytes(StandardCharsets.UTF_8), "text/csv")),
            201);
    String job = made.getString("id");
    // the fake inventory-svc totals what a job opened, as the real one does
    INVENTORY.on("GET", "/admin/inventory/opening-stock/" + job, call -> totals(job));
    return made;
  }

  private static JsonStub.Answer totals(String job) {
    Map<String, int[]> lines = new java.util.TreeMap<>();
    Map<String, BigDecimal[]> sums = new java.util.TreeMap<>();
    OPENED.forEach(
        (key, held) -> {
          if (!held[0].equals(job)) return;
          String store = key.substring(0, key.indexOf('|'));
          lines.computeIfAbsent(store, k -> new int[2]);
          sums.computeIfAbsent(store, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
          lines.get(store)[0]++;
          sums.get(store)[0] = sums.get(store)[0].add(new BigDecimal(held[1]));
          if (held[2] == null) lines.get(store)[1]++;
          else
            sums.get(store)[1] =
                sums.get(store)[1].add(new BigDecimal(held[1]).multiply(new BigDecimal(held[2])));
        });
    var stores = Json.createArrayBuilder();
    lines.forEach(
        (store, c) ->
            stores.add(
                Json.createObjectBuilder()
                    .add("storeId", store)
                    .add("lines", c[0])
                    .add("qty", sums.get(store)[0].toPlainString())
                    .add("value", sums.get(store)[1].toPlainString())
                    .add("uncostedLines", c[1])));
    return JsonStub.Answer.ok(
        Json.createObjectBuilder().add("jobId", job).add("stores", stores).build().toString());
  }

  private JsonObject apply(Staff who, String dryRunId) {
    return data(
        as("/admin/catalogue-imports/" + dryRunId + "/apply", who, "priceListId", SHELF_LIST)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON)),
        202);
  }

  private JsonObject job(Staff who, String id) {
    return data(as("/admin/catalogue-imports/" + id, who).get(), 200);
  }

  private JsonObject report(Staff who, String id) {
    return data(as("/admin/catalogue-imports/" + id + "/reconciliation", who).get(), 200);
  }

  private static String sku() {
    return "S" + Ids.newId().toString().substring(26);
  }

  private static String ean13(long n) {
    String body = String.format("5%011d", n);
    int sum = 0;
    for (int i = 0; i < 12; i++) sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
    return body + ((10 - sum % 10) % 10);
  }

  private static final java.util.concurrent.atomic.AtomicLong N =
      new java.util.concurrent.atomic.AtomicLong(700_000);

  private static String measure(JsonObject report, String name, String side) {
    for (var m : report.getJsonArray("measures").getValuesAs(JsonObject.class)) {
      if (name.equals(m.getString("name"))) return m.getString(side);
    }
    throw new AssertionError("no measure " + name + " in " + report);
  }

  private static boolean matches(JsonObject report, String name) {
    for (var m : report.getJsonArray("measures").getValuesAs(JsonObject.class)) {
      if (name.equals(m.getString("name"))) return m.getBoolean("match");
    }
    throw new AssertionError("no measure " + name + " in " + report);
  }

  private static BigDecimal num(String s) {
    return new BigDecimal(s);
  }

  // ── opening stock and the report ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "stock is opened under the dry run's id with its cost, last day and lot, and the report agrees")
  void openedAndReconciled() {
    String m = mapping(OWNER);
    String p = sku();
    String csv =
        HEADER
            + p
            + "1,Bread,"
            + ean13(N.incrementAndGet())
            + ",A,1.29,0.80,24,31/12/2030,L-1,\n"
            + p
            + "2,Jam,,B,1.99,1.10,4.5,,,\n"
            + p
            + "3,Loose item,,A,0.50,,3,,,\n";
    JsonObject dry = dryRun(OWNER, STORE, m, csv);
    INVENTORY.reset();

    JsonObject queued = apply(OWNER, dry.getString("id"));
    worker.drain();

    JsonObject done = job(OWNER, queued.getString("id"));
    assertThat(done.toString(), done.getString("status"), is("DONE"));
    assertThat(done.getJsonObject("summary").getInt("opened"), is(3));
    var calls = INVENTORY.calls().stream().filter(c -> c.method().equals("POST")).toList();
    assertThat(calls.size(), is(1));
    assertThat(calls.get(0).body(), containsString("\"jobId\":\"" + dry.getString("id") + "\""));
    assertThat(calls.get(0).body(), containsString("\"storeId\":\"" + STORE + "\""));
    assertThat(calls.get(0).body(), containsString("\"expiryDate\":\"2030-12-31\""));
    assertThat(calls.get(0).body(), containsString("\"batchNo\":\"L-1\""));
    assertThat(calls.get(0).body(), containsString("\"unitCost\":0.80"));
    assertThat(calls.get(0).header("x-roles"), is("OWNER"));

    JsonObject r = report(OWNER, queued.getString("id"));
    assertThat(r.toString(), r.getBoolean("reconciled"), is(true));
    assertThat(measure(r, "SKUS", "file"), is("3"));
    assertThat(measure(r, "BARCODES", "loaded"), is("1"));
    assertThat(num(measure(r, "STOCK_QTY", "loaded")).compareTo(num("31.5")), is(0));
    assertThat(num(measure(r, "STOCK_VALUE", "loaded")).compareTo(num("24.15")), is(0));
    assertThat(measure(r, "STOCK_UNCOSTED", "loaded"), is("1"));
    assertThat(r.getJsonArray("prices").size(), is(2));
    assertThat(r.getJsonArray("unmatchedSkus").size(), is(0));
  }

  @Test
  @DisplayName(
      "a call that landed and was not answered is asked again without opening the stock twice")
  void answerLost() {
    String m = mapping(OWNER);
    String p = sku();
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + p + "1,Bread,,A,1.29,0.80,10,,,\n");
    LOSE_ANSWER.set(true);
    JsonObject queued = apply(OWNER, dry.getString("id"));

    worker.drain();
    assertThat(job(OWNER, queued.getString("id")).getString("status"), is("APPLYING"));

    // the lease runs out as it would if the worker had died; the next claim asks again
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement()) {
      st.execute(
          "UPDATE product.import_jobs SET lease_until = now() - interval '1 second' WHERE id = '"
              + queued.getString("id")
              + "'");
    } catch (java.sql.SQLException e) {
      throw new AssertionError(e);
    }
    worker.drain();

    JsonObject done = job(OWNER, queued.getString("id"));
    assertThat(done.getString("status"), is("DONE"));
    assertThat(done.getJsonObject("summary").getInt("openedAgain"), is(1));
    JsonObject r = report(OWNER, queued.getString("id"));
    assertThat(r.toString(), r.getBoolean("reconciled"), is(true));
    assertThat(num(measure(r, "STOCK_QTY", "loaded")).compareTo(num("10")), is(0));
  }

  @Test
  @DisplayName(
      "stock another import opened first is left as it is, said so, and shows short in the report")
  void openedByAnotherImport() {
    String m = mapping(OWNER);
    String p = sku();
    String first = HEADER + p + "1,Bread,,A,1.29,0.80,10,,,\n";
    JsonObject firstDry = dryRun(OWNER, STORE, m, first);
    apply(OWNER, firstDry.getString("id"));
    worker.drain();

    // a corrected file, a new dry run: the quantity differs but the opening is not repeated
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + p + "1,Bread,,A,1.29,0.80,99,,,\n");
    JsonObject queued = apply(OWNER, dry.getString("id"));
    worker.drain();

    JsonObject done = job(OWNER, queued.getString("id"));
    assertThat(done.getString("status"), is("DONE"));
    assertThat(done.getJsonObject("summary").getInt("alreadyOpened"), is(1));
    assertThat(done.toString(), containsString("stock was opened by an earlier import"));
    JsonObject r = report(OWNER, queued.getString("id"));
    assertThat(r.getBoolean("reconciled"), is(false));
    assertThat(matches(r, "STOCK_QTY"), is(false));
    assertThat(measure(r, "STOCK_QTY", "loaded"), is("0"));
  }

  @Test
  @DisplayName("a price changed behind the import's back is named, by SKU and by VAT code")
  void priceChanged() {
    String m = mapping(OWNER);
    String p = sku();
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + p + "1,Bread,,A,1.29,0.80,1,,,\n");
    JsonObject queued = apply(OWNER, dry.getString("id"));
    worker.drain();
    PRICES.replaceAll((variant, price) -> price.compareTo(num("1.29")) == 0 ? num("1.39") : price);

    JsonObject r = report(OWNER, queued.getString("id"));

    assertThat(r.getBoolean("reconciled"), is(false));
    assertThat(r.getInt("priceMismatchCount") >= 1, is(true));
    assertThat(
        r.getJsonArray("priceMismatches").toString(),
        containsString(p + "1: file 1.29, price list 1.39"));
    PRICES.replaceAll((variant, price) -> price.compareTo(num("1.39")) == 0 ? num("1.29") : price);
  }

  // ── refusals and isolation ───────────────────────────────────────────────────

  @Test
  @DisplayName("a dry run has nothing to reconcile; nobody else's job can be read, whoever asks")
  void whoAndWhat() {
    String m = mapping(OWNER);
    JsonObject dry = dryRun(OWNER, STORE, m, HEADER + sku() + "1,Bread,,A,1.29,0.80,1,,,\n");

    Response notApply =
        as("/admin/catalogue-imports/" + dry.getString("id") + "/reconciliation", OWNER).get();
    assertThat(notApply.readEntity(String.class), containsString("IMPORT_NOT_AN_APPLY"));

    JsonObject queued = apply(OWNER, dry.getString("id"));
    worker.drain();
    String id = queued.getString("id");
    assertThat(report(OWNER, id).getBoolean("reconciled"), is(true));

    assertThat(
        as("/admin/catalogue-imports/" + id + "/reconciliation", RIVAL).get().getStatus(), is(404));
    assertThat(
        as("/admin/catalogue-imports/" + id + "/reconciliation", CASHIER).get().getStatus(),
        is(403));
  }

  @Test
  @DisplayName(
      "the same file in two businesses opens each its own stock and reconciles each on its own")
  void perBusiness() {
    String p = sku();
    String csv = HEADER + p + "1,Bread,,A,1.29,0.80,5,,,\n";
    JsonObject mine = dryRun(OWNER, STORE, mapping(OWNER), csv);
    JsonObject theirs = dryRun(RIVAL, OTHER_STORE, mapping(RIVAL), csv);

    JsonObject a = apply(OWNER, mine.getString("id"));
    worker.drain();
    JsonObject b =
        data(
            as(
                    "/admin/catalogue-imports/" + theirs.getString("id") + "/apply",
                    RIVAL,
                    "priceListId",
                    SHELF_LIST)
                .header("Idempotency-Key", Ids.newId().toString())
                .post(Entity.entity("{}", MediaType.APPLICATION_JSON)),
            202);
    worker.drain();

    assertThat(job(OWNER, a.getString("id")).getString("status"), is("DONE"));
    assertThat(job(RIVAL, b.getString("id")).getString("status"), is("DONE"));
    assertThat(report(OWNER, a.getString("id")).getBoolean("reconciled"), is(true));
    assertThat(report(RIVAL, b.getString("id")).getBoolean("reconciled"), is(true));
    assertThat(
        as("/admin/catalogue-imports/" + a.getString("id") + "/reconciliation", RIVAL)
            .get()
            .getStatus(),
        is(404));
  }
}
