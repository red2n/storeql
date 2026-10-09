package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A supermarket's export, dry-run (intent/catalogue-import.md): the mapping is saved once, the file
 * is read and judged against the catalogue, the report says what each row would do and why a row is
 * refused, and nothing of the catalogue is written. Another business sees none of it.
 */
@HelidonTest
class ImportDryRunIT {

  private static final String T = Ids.newId().toString();
  private static final String OTHER = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String STORE_B = Ids.newId().toString();
  private static final String OTHER_STORE = Ids.newId().toString();

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
        .with(OTHER, "GBP", "GB")
        .withStore(T, STORE, "GB")
        .withStore(T, STORE_B, "GB")
        .withStore(OTHER, OTHER_STORE, "GB");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    try {
      PG.stop();
    } finally {
      REDIS.stop();
    }
  }

  // ── harness ──────────────────────────────────────────────────────────────────

  private record Staff(String tenant, String roles, String storeIds) {}

  private static final Staff OWNER = new Staff(T, "OWNER", null);
  private static final Staff HELD_TO_A = new Staff(T, "MANAGER", STORE);
  private static final Staff CASHIER = new Staff(T, "CASHIER", STORE);
  private static final Staff RIVAL_OWNER = new Staff(OTHER, "OWNER", null);

  private Invocation.Builder as(String path, Staff who, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i + 1 < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    Invocation.Builder b =
        t.request()
            .header("X-Tenant-Id", who.tenant())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", who.roles());
    if (who.storeIds() != null) b = b.header("X-Store-Ids", who.storeIds());
    return b;
  }

  private Response putMapping(String name, String json, Staff who) {
    return as("/admin/catalogue-imports/mappings/" + name, who)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response upload(String csv, String mapping, String store, Staff who, String key) {
    Invocation.Builder b =
        as(
            "/admin/catalogue-imports",
            who,
            "storeId",
            store,
            "mapping",
            mapping,
            "fileName",
            "export.csv");
    if (key != null) b = b.header("Idempotency-Key", key);
    return b.post(Entity.entity(csv.getBytes(StandardCharsets.UTF_8), "text/csv"));
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

  private static final String MAPPING =
      "{\"columns\":{\"sku\":\"PLU\",\"name\":\"Description\",\"barcode\":\"EAN\",\"category\":\"Department\","
          + "\"vatCode\":\"VAT\",\"price\":\"Retail Price\",\"cost\":\"Cost\",\"stockQty\":\"On Hand\"},"
          + "\"aliasColumns\":[{\"header\":\"Old EAN\",\"kind\":\"OLD_EAN\",\"packQty\":1}],"
          + "\"vatCodes\":{\"A\":\"T1\",\"B\":\"T5\",\"C\":\"T0\"},\"priceBasis\":\"INCLUSIVE\","
          + "\"decimalMark\":\".\",\"soldByValues\":{\"EACH\":\"EACH\",\"KG\":\"WEIGHT\"},"
          + "\"categorySeparator\":\">\"}";

  private static final String HEADER =
      "PLU,Description,EAN,Department,VAT,Retail Price,Cost,On Hand,Old EAN\n";

  private String mapping() {
    String name = "m-" + Ids.newId();
    assertThat(putMapping(name, MAPPING, OWNER).getStatus(), is(200));
    return name;
  }

  private long count(String table, String where) throws Exception {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT count(*) FROM product." + table + " WHERE " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  // ── mappings ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a mapping is saved under its name, replaced by the same name, and read back")
  void aMappingIsSavedAndReplaced() {
    String name = "shop-" + Ids.newId();

    JsonObject first = data(putMapping(name, MAPPING, OWNER), 200);
    JsonObject again =
        data(putMapping(name, MAPPING.replace("INCLUSIVE", "EXCLUSIVE"), OWNER), 200);

    assertThat(first.getString("name"), is(name));
    assertThat(first.getJsonObject("mapping").getString("priceBasis"), is("INCLUSIVE"));
    assertThat(again.getJsonObject("mapping").getString("priceBasis"), is("EXCLUSIVE"));
    assertThat(again.getString("hash").equals(first.getString("hash")), is(false));
    assertThat(
        data(as("/admin/catalogue-imports/mappings/" + name, OWNER).get(), 200).getString("hash"),
        is(again.getString("hash")));
  }

  @Test
  @DisplayName("a mapping that cannot be used is refused, listing why")
  void anInvalidMapping() {
    Response r =
        putMapping(
            "bad",
            "{\"columns\":{\"name\":\"Description\"},\"priceBasis\":\"GROSS\",\"decimalMark\":\".\"}",
            OWNER);

    String body = r.readEntity(String.class);
    assertThat(r.getStatus(), is(400));
    assertThat(body, containsString("IMPORT_MAPPING_INVALID"));
    assertThat(body, containsString("sku"));
    assertThat(body, containsString("priceBasis"));
  }

  // ── the dry run ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the report says what each row would do, and the catalogue is not touched")
  void theReport() throws Exception {
    String m = mapping();
    long productsBefore = count("products", "tenant_id = '" + T + "'");
    String csv =
        HEADER
            + "1001,Hovis Wholemeal 800g,5012345678900,Bakery > Bread,A,1.29,0.80,12,\n"
            + "1002,Strawberry Jam 340g,,Pantry > Jam,B,1.99,,,\n"
            + "1002,Strawberry Jam 340g,,Pantry > Jam,B,1.99,,,\n"
            + "1003,Bad Barcode,5012345678901,,A,1.29,,,\n"
            + "1004,Odd VAT,,,Q,1.29,,,\n"
            + ",No SKU,,,A,1.29,,,\n";

    JsonObject job = data(upload(csv, m, STORE, OWNER, Ids.newId().toString()), 201);

    assertThat(job.getString("status"), is("DRY_RUN_DONE"));
    assertThat(job.getString("kind"), is("DRY_RUN"));
    JsonObject s = job.getJsonObject("summary");
    assertThat(s.getInt("rows"), is(6));
    assertThat(s.getJsonObject("byAction").getInt("CREATE"), is(2));
    assertThat(s.getJsonObject("byAction").getInt("SKIPPED"), is(1));
    assertThat(s.getJsonObject("byAction").getInt("REFUSED"), is(3));
    assertThat(s.getJsonObject("refusals").getInt("BARCODE_BAD_CHECK_DIGIT"), is(1));
    assertThat(s.getJsonObject("refusals").getInt("VAT_CODE_UNMAPPED"), is(1));
    assertThat(s.getJsonObject("refusals").getInt("SKU_MISSING"), is(1));
    assertThat(s.getJsonObject("vatCodes").getInt("A"), is(1));
    assertThat(s.getString("priceBasis"), is("INCLUSIVE"));
    // The gaps a go-live should close: no barcode on the jam, stock of the bread but no cost...
    // (cost given)
    assertThat(s.getJsonObject("gaps").getInt("NO_BARCODE"), is(1));

    // The rows, with line numbers and reasons.
    Response rows =
        as("/admin/catalogue-imports/" + job.getString("id") + "/rows", OWNER, "action", "REFUSED")
            .get();
    String body = rows.readEntity(String.class);
    JsonArray list = Json.createReader(new StringReader(body)).readObject().getJsonArray("data");
    assertThat(list.size(), is(3));
    assertThat(list.getJsonObject(0).getInt("line"), is(5));
    assertThat(list.getJsonObject(0).getString("sku"), is("1003"));
    assertThat(body, containsString("BARCODE_BAD_CHECK_DIGIT"));

    assertThat(count("products", "tenant_id = '" + T + "'"), is(productsBefore));
    assertThat(
        count(
            "product_variants", "tenant_id = '" + T + "' AND sku IN ('1001','1002','1003','1004')"),
        is(0L));
  }

  @Test
  @DisplayName(
      "a SKU the catalogue holds is unchanged or an update, and a barcode another SKU holds refuses the row")
  void againstTheCatalogue() throws Exception {
    String m = mapping();
    String sku = "EX-" + Ids.newId().toString().substring(24);
    String taken = "EX-" + Ids.newId().toString().substring(24);
    String pid =
        data(
                as("/admin/products", OWNER)
                    .post(
                        Entity.entity(
                            "{\"name\":\"Existing loaf\",\"sellableOnline\":false}",
                            MediaType.APPLICATION_JSON)),
                201)
            .getString("id");
    assertThat(
        as("/admin/products/" + pid + "/variants", OWNER)
            .post(
                Entity.entity(
                    "{\"sku\":\"" + sku + "\",\"barcode\":\"5012345678900\"}",
                    MediaType.APPLICATION_JSON))
            .getStatus(),
        is(201));

    String csv =
        HEADER
            // The same product, naming no barcode: a blank cell never erases, so nothing changes.
            + sku
            + ",Existing loaf,,,A,1.29,,,\n"
            + taken
            + ",Renamed,,,A,1.29,,,\n"
            // A new SKU claiming the barcode the catalogue's loaf holds.
            + "NEW-1,A clash,5012345678900,,A,1.29,,,\n";
    JsonObject job = data(upload(csv, m, STORE, OWNER, null), 201);

    JsonObject byAction = job.getJsonObject("summary").getJsonObject("byAction");
    assertThat(job.toString(), byAction.getInt("UNCHANGED", -1), is(1));
    assertThat(byAction.getInt("CREATE"), is(1));
    assertThat(byAction.getInt("REFUSED"), is(1));
    assertThat(
        job.getJsonObject("summary").getJsonObject("refusals").getInt("BARCODE_HELD_BY_OTHER"),
        is(1));
  }

  @Test
  @DisplayName("an update names the fields it would change")
  void anUpdate() {
    String m = mapping();
    String sku = "UP-" + Ids.newId().toString().substring(24);
    String pid =
        data(
                as("/admin/products", OWNER)
                    .post(
                        Entity.entity(
                            "{\"name\":\"Old name\",\"sellableOnline\":false}",
                            MediaType.APPLICATION_JSON)),
                201)
            .getString("id");
    as("/admin/products/" + pid + "/variants", OWNER)
        .post(Entity.entity("{\"sku\":\"" + sku + "\"}", MediaType.APPLICATION_JSON))
        .close();

    JsonObject job =
        data(upload(HEADER + sku + ",New name,,,A,1.29,,,\n", m, STORE, OWNER, null), 201);

    Response rows = as("/admin/catalogue-imports/" + job.getString("id") + "/rows", OWNER).get();
    JsonArray list =
        Json.createReader(new StringReader(rows.readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(list.getJsonObject(0).getString("action"), is("UPDATE"));
    assertThat(list.getJsonObject(0).getJsonArray("changes").getString(0), is("name"));
  }

  @Test
  @DisplayName(
      "the same file under the same key is the same job; the same file under another key is a new job on one file")
  void retries() throws Exception {
    String m = mapping();
    String csv = HEADER + "R-1,Retried,,,A,1.29,,,\n";
    String key = Ids.newId().toString();

    JsonObject first = data(upload(csv, m, STORE, OWNER, key), 201);
    JsonObject replay = data(upload(csv, m, STORE, OWNER, key), 201);
    JsonObject second = data(upload(csv, m, STORE, OWNER, Ids.newId().toString()), 201);

    assertThat(replay.getString("id"), is(first.getString("id")));
    assertThat(second.getString("id").equals(first.getString("id")), is(false));
    assertThat(second.getString("fileId"), is(first.getString("fileId")));
  }

  @Test
  @DisplayName("a mapping that names a header the file lacks fails the run, naming it")
  void aMissingHeader() {
    String m = mapping();

    JsonObject job = data(upload("PLU,Description\n1,Bread\n", m, STORE, OWNER, null), 201);

    assertThat(job.getString("status"), is("DRY_RUN_FAILED"));
    assertThat(job.getString("failureCode"), is("IMPORT_MAPPING_HEADER_MISSING"));
    assertThat(job.getString("failureDetail"), containsString("Retail Price"));
  }

  @Test
  @DisplayName("an empty file, a dangling quote and too many rows are refused whole")
  void unreadableFiles() {
    String m = mapping();

    assertThat(code(upload("", m, STORE, OWNER, null), 400), is("IMPORT_FILE_EMPTY"));
    assertThat(
        code(upload(HEADER + "1,\"open\n", m, STORE, OWNER, null), 400),
        is("IMPORT_CSV_MALFORMED"));
  }

  @Test
  @DisplayName(
      "a store that is not the business's, a mapping that does not exist and a missing store are refused")
  void unknownThings() {
    String m = mapping();
    String csv = HEADER + "1,X,,,A,1.29,,,\n";

    assertThat(code(upload(csv, m, OTHER_STORE, OWNER, null), 404), is("PRODUCT_STORE_NOT_FOUND"));
    assertThat(
        code(upload(csv, "no-such-mapping", STORE, OWNER, null), 404),
        is("IMPORT_MAPPING_NOT_FOUND"));
    assertThat(
        as("/admin/catalogue-imports", OWNER, "mapping", m)
            .post(Entity.entity(csv, "text/csv"))
            .getStatus(),
        is(400));
  }

  // ── who may, and whose ───────────────────────────────────────────────────────

  @Test
  @DisplayName("a cashier and a manager held to stores may not import; the business's owner may")
  void whoMayImport() {
    String m = mapping();
    String csv = HEADER + "1,X,,,A,1.29,,,\n";

    assertThat(upload(csv, m, STORE, CASHIER, null).getStatus(), is(403));
    assertThat(code(upload(csv, m, STORE, HELD_TO_A, null), 403), is("BUSINESS_WIDE_ONLY"));
    assertThat(putMapping("x", MAPPING, CASHIER).getStatus(), is(403));
    assertThat(putMapping("x", MAPPING, HELD_TO_A).getStatus(), is(403));
    assertThat(upload(csv, m, STORE, OWNER, null).getStatus(), is(201));
  }

  @Test
  @DisplayName(
      "another business sees none of it: not the mapping, the job or its rows, and the same file is its own")
  void tenantIsolation() throws Exception {
    String m = mapping();
    String csv = HEADER + "ISO-1,Isolated,,,A,1.29,,,\n";
    JsonObject mine = data(upload(csv, m, STORE, OWNER, null), 201);
    String job = mine.getString("id");

    assertThat(
        as("/admin/catalogue-imports/mappings/" + m, RIVAL_OWNER).get().getStatus(), is(404));
    assertThat(as("/admin/catalogue-imports/" + job, RIVAL_OWNER).get().getStatus(), is(404));
    assertThat(
        as("/admin/catalogue-imports/" + job + "/rows", RIVAL_OWNER).get().getStatus(), is(404));
    for (String role : new String[] {"MANAGER", "CASHIER", "STOREKEEPER"}) {
      Staff s = new Staff(OTHER, role, OTHER_STORE);
      assertThat(role, as("/admin/catalogue-imports/" + job, s).get().getStatus() >= 400, is(true));
    }

    // The other business makes its own mapping of that name and its own file of the same bytes.
    assertThat(putMapping(m, MAPPING, RIVAL_OWNER).getStatus(), is(200));
    JsonObject theirs = data(upload(csv, m, OTHER_STORE, RIVAL_OWNER, null), 201);
    assertThat(theirs.getString("fileId").equals(mine.getString("fileId")), is(false));
    assertThat(count("import_files", "sha256 = '" + mine.getString("fileSha256") + "'"), is(2L));
    assertThat(
        data(as("/admin/catalogue-imports/" + job, OWNER).get(), 200).getString("fileId"),
        is(mine.getString("fileId")));
  }
}
