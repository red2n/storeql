package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shelf space: fixtures, planograms, space plans and resets (07.17), over HTTP and a real database.
 *
 * <p>Three things here cannot be had from a unit test, and they are the reason this class exists.
 *
 * <p><b>Capacity is a generated column.</b> {@code facings * depth} is computed by Postgres, so no
 * write path can leave it disagreeing with the layout it came from. A test against a stubbed
 * repository would assert the arithmetic the application happened to do on the way in, which is the
 * thing that goes stale.
 *
 * <p><b>One draft and one layout in force per fixture are partial unique indexes.</b> The rule is
 * the index, not the check in the service, and only a database enforces it against two people
 * working at once.
 *
 * <p><b>Publishing supersedes under a deferred foreign key.</b> The predecessor has to be marked
 * before the successor is inserted or the partial unique index refuses it, and the deferred
 * constraint is what makes that order legal at all.
 */
@HelidonTest
class MerchandisingIT {

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

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
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String query, String value, String tenant) {
    var t = target.path(path);
    if (query != null) t = t.queryParam(query, value);
    return t.request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .get();
  }

  private static String id(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return data(body).getString("id");
  }

  private static jakarta.json.JsonObject data(String body) {
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static String body(Response r, int expected) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(expected));
    return body;
  }

  private String fixture(String tenant, int shelves, int widthMm) {
    return id(
        post(
            "/admin/merchandising/fixtures",
            "{\"storeId\":\""
                + STORE
                + "\",\"code\":\"F-"
                + Ids.newId()
                + "\",\"name\":\"Gondola\",\"kind\":\"GONDOLA\",\"shelfCount\":"
                + shelves
                + ",\"shelfWidthMm\":"
                + widthMm
                + "}",
            tenant));
  }

  /** A variant with a recorded facing width, so a layout can be checked against the shelf. */
  private String variant(String tenant, Integer facingWidthMm) {
    return variant(tenant, facingWidthMm, null);
  }

  private String variant(String tenant, Integer facingWidthMm, String categoryId) {
    String product =
        id(
            post(
                "/admin/products",
                "{\"name\":\"Line "
                    + Ids.newId()
                    + "\""
                    + (categoryId == null ? "" : ",\"categoryId\":\"" + categoryId + "\"")
                    + "}",
                tenant));
    String variant =
        id(
            post(
                "/admin/products/" + product + "/variants",
                "{\"sku\":\"S-" + Ids.newId() + "\"}",
                tenant));
    if (facingWidthMm != null) {
      body(
          put(
              "/admin/merchandising/variants/" + variant + "/facing-width",
              "{\"facingWidthMm\":" + facingWidthMm + "}",
              tenant),
          200);
    }
    return variant;
  }

  private String draft(String tenant, String fixtureId) {
    return id(
        post(
            "/admin/merchandising/fixtures/" + fixtureId + "/planograms",
            "{\"effectiveFrom\":\"2026-10-01\"}",
            tenant));
  }

  private static String position(String variantId, int shelf, int seq, int facings, int depth) {
    return "{\"variantId\":\""
        + variantId
        + "\",\"shelf\":"
        + shelf
        + ",\"sequence\":"
        + seq
        + ",\"facings\":"
        + facings
        + ",\"depth\":"
        + depth
        + ",\"minPresentation\":1}";
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("Capacity is the database's arithmetic, and a layout's total is the sum of it")
  void capacityIsGenerated() {
    String fixtureId = fixture(T, 4, 1000);
    String planogram = draft(T, fixtureId);
    String v1 = variant(T, 60);
    String v2 = variant(T, 80);

    String fit =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":["
                    + position(v1, 1, 1, 3, 4)
                    + ","
                    + position(v2, 1, 2, 2, 5)
                    + "]}",
                T),
            200);
    // The save answers with the fit: 3 facings of 60mm and 2 of 80mm on a 1000mm shelf.
    assertThat(fit, containsString("\"usedMm\":340"));

    // 3x4 and 2x5: the numbers are read back from the generated column, not from the request.
    String saved = body(get("/admin/merchandising/planograms/" + planogram, null, null, T), 200);
    assertThat(saved, containsString("\"capacity\":12"));
    assertThat(saved, containsString("\"capacity\":10"));
    assertThat(saved, containsString("\"totalCapacity\":22"));
  }

  @Test
  @DisplayName("A layout wider than its shelf is refused when it is saved, not at publication")
  void theShelfIsFinite() {
    // 1000mm of shelf, 5 facings of a 250mm line: 1250mm. Refused now rather than at publication,
    // because finding out after a reset has been scheduled around the layout is finding out too
    // late.
    String fixtureId = fixture(T, 1, 1000);
    String planogram = draft(T, fixtureId);
    String wide = variant(T, 250);

    String refused =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(wide, 1, 1, 5, 2) + "]}",
                T),
            409);
    assertThat(refused, containsString("PLANOGRAM_SHELF_OVERFLOWS"));
  }

  @Test
  @DisplayName(
      "A line with no recorded width is placed, and the answer says it could not be checked")
  void whatCannotBeMeasuredIsNotRefused() {
    // Most catalogues have gaps. A planogram nobody can save because one line lacks a measurement
    // would be worse than one whose check is partial and honest about being partial.
    String fixtureId = fixture(T, 1, 1000);
    String planogram = draft(T, fixtureId);
    String unmeasured = variant(T, null);

    String fit =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(unmeasured, 1, 1, 9, 2) + "]}",
                T),
            200);
    assertThat(fit, containsString("\"unmeasured\":1"));
    assertThat("nothing measurable was used", fit, containsString("\"usedMm\":0"));
  }

  @Test
  @DisplayName("A shelf beyond the fixture's shelf count is refused")
  void beyondTheFixture() {
    String fixtureId = fixture(T, 2, 1000);
    String planogram = draft(T, fixtureId);
    String v = variant(T, 50);
    String refused =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(v, 3, 1, 1, 1) + "]}",
                T),
            400);
    assertThat(refused, containsString("PLANOGRAM_SHELF_BEYOND_FIXTURE"));
  }

  @Test
  @DisplayName("One draft per fixture, and the second is refused by the index")
  void oneDraftAtATime() {
    // Two people drawing the same shelf at once is a merge nobody wins.
    String fixtureId = fixture(T, 2, 1000);
    draft(T, fixtureId);
    String refused =
        body(
            post(
                "/admin/merchandising/fixtures/" + fixtureId + "/planograms",
                "{\"effectiveFrom\":\"2026-11-01\"}",
                T),
            409);
    assertThat(refused, containsString("PLANOGRAM_DRAFT_EXISTS"));
  }

  @Test
  @DisplayName("Publishing supersedes the version in force, and an empty layout publishes nothing")
  void publishingSupersedes() {
    String fixtureId = fixture(T, 2, 2000);
    String v = variant(T, 100);

    String first = draft(T, fixtureId);
    String empty =
        body(post("/admin/merchandising/planograms/" + first + "/publish", "{}", T), 409);
    assertThat(empty, containsString("PLANOGRAM_EMPTY"));

    body(
        put(
            "/admin/merchandising/planograms/" + first + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 4, 3) + "]}",
            T),
        200);
    String published =
        body(post("/admin/merchandising/planograms/" + first + "/publish", "{}", T), 200);
    assertThat(published, containsString("\"status\":\"PUBLISHED\""));
    assertThat(published, containsString("\"version\":1"));

    // A second version: the predecessor must be marked before this one is inserted, which only the
    // deferred foreign key allows.
    String second = draft(T, fixtureId);
    body(
        put(
            "/admin/merchandising/planograms/" + second + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 6, 3) + "]}",
            T),
        200);
    String successor =
        body(post("/admin/merchandising/planograms/" + second + "/publish", "{}", T), 200);
    assertThat(successor, containsString("\"version\":2"));
    assertThat(successor, containsString("\"supersedes\":\"" + first + "\""));

    String old = body(get("/admin/merchandising/planograms/" + first, null, null, T), 200);
    assertThat(old, containsString("\"status\":\"SUPERSEDED\""));
    assertThat(old, containsString("\"supersededBy\":\"" + second + "\""));

    // And the layout in force is the new one.
    String inForce =
        body(get("/admin/merchandising/fixtures/" + fixtureId + "/planogram", null, null, T), 200);
    assertThat(inForce, containsString("\"id\":\"" + second + "\""));
  }

  @Test
  @DisplayName("A published layout is never edited again")
  void publishedIsImmutable() {
    String fixtureId = fixture(T, 1, 2000);
    String v = variant(T, 100);
    String planogram = draft(T, fixtureId);
    body(
        put(
            "/admin/merchandising/planograms/" + planogram + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 2, 2) + "]}",
            T),
        200);
    body(post("/admin/merchandising/planograms/" + planogram + "/publish", "{}", T), 200);

    String refused =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(v, 1, 1, 3, 2) + "]}",
                T),
            409);
    assertThat(refused, containsString("PLANOGRAM_NOT_DRAFT"));
  }

  @Test
  @DisplayName("A category's actual share is measured from the shelves, not typed in")
  void spaceIsMeasured() {
    // The variance is what a space plan is for: a promise of eight per cent against what the bays
    // actually give the category. Only a planned category appears, so there is a plan here.
    String categoryId =
        id(post("/admin/categories", "{\"name\":\"Crisps " + Ids.newId() + "\"}", T));
    String fixtureId = fixture(T, 2, 1000);
    String v = variant(T, 200, categoryId);
    body(
        put(
            "/admin/merchandising/space-plans",
            "{\"storeId\":\""
                + STORE
                + "\",\"categoryId\":\""
                + categoryId
                + "\",\"targetShare\":0.0800}",
            T),
        200);
    String planogram = draft(T, fixtureId);
    body(
        put(
            "/admin/merchandising/planograms/" + planogram + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 2, 2) + "]}",
            T),
        200);
    body(post("/admin/merchandising/planograms/" + planogram + "/publish", "{}", T), 200);

    String report = body(get("/admin/merchandising/space", "store", STORE, T), 200);
    // 2 facings x 200mm of a 2x1000mm fixture: 400 of 2000.
    assertThat(report, containsString("\"actualMm\":400"));
  }

  @Test
  @DisplayName("A reset is overdue when its day has passed, and cancelling it needs a reason")
  void resets() {
    String categoryId =
        id(post("/admin/categories", "{\"name\":\"Soft drinks " + Ids.newId() + "\"}", T));
    String reset =
        id(
            post(
                "/admin/merchandising/resets",
                "{\"categoryId\":\""
                    + categoryId
                    + "\",\"name\":\"Spring reset\",\"scheduledFor\":\"2020-03-01\"}",
                T));
    String listed = body(get("/admin/merchandising/resets", null, null, T), 200);
    assertThat("its day is long past", listed, containsString("\"overdue\":true"));

    String noReason =
        body(post("/admin/merchandising/resets/" + reset + "/cancel", "{\"reason\":\"\"}", T), 400);
    assertThat(noReason, containsString("VALIDATION_FAILED"));

    String cancelled =
        body(
            post(
                "/admin/merchandising/resets/" + reset + "/cancel",
                "{\"reason\":\"Supplier pulled the range\"}",
                T),
            200);
    assertThat(cancelled, containsString("\"status\":\"CANCELLED\""));
    assertThat(
        "a cancelled reset is not overdue", cancelled, not(containsString("\"overdue\":true")));
  }

  @Test
  @DisplayName("Another business cannot read or touch this one's fixtures")
  void tenantsAreSeparate() {
    String fixtureId = fixture(T, 2, 1000);
    assertThat(
        body(
            get("/admin/merchandising/fixtures/" + fixtureId + "/planogram", null, null, RIVAL),
            404),
        containsString("PLANOGRAM_NOT_FOUND"));
    assertThat(
        body(post("/admin/merchandising/fixtures/" + fixtureId + "/retire", "{}", RIVAL), 404),
        containsString("FIXTURE_NOT_FOUND"));
  }

  @Test
  @DisplayName("A caller with no roles is refused, whatever the tenant header says")
  void rolesAreRequired() {
    // No gateway in front of this test, so the request arrives with no roles rather than no token:
    // 403, where a call through the gateway would be a 401.
    Response r =
        target
            .path("/admin/merchandising/fixtures")
            .queryParam("store", STORE)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", USER)
            .get();
    assertThat(r.readEntity(String.class), r.getStatus(), is(403));
  }

  // ── refusals the negative-coverage audit found untested (1 Oct 2026) ─────────

  private Response send(String method, String path, String json, String tenant, String roles) {
    var b =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles);
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static void assertRefused(Response r, int status, String code) {
    assertThat(body(r, status), containsString(code));
  }

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

  private static String tail() {
    String raw = Ids.newId().toString();
    return raw.substring(raw.length() - 12);
  }

  private String category(String tenant) {
    return id(post("/admin/categories", "{\"name\":\"Cat " + Ids.newId() + "\"}", tenant));
  }

  /** A published layout on a fixture of its own, ready to be put in a reset. */
  private String published(String tenant) {
    String fixtureId = fixture(tenant, 2, 2000);
    String planogram = draft(tenant, fixtureId);
    String v = variant(tenant, 100);
    body(
        put(
            "/admin/merchandising/planograms/" + planogram + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 2, 2) + "]}",
            tenant),
        200);
    body(post("/admin/merchandising/planograms/" + planogram + "/publish", "{}", tenant), 200);
    return planogram;
  }

  private String reset(String tenant, String categoryId) {
    return id(
        post(
            "/admin/merchandising/resets",
            "{\"categoryId\":\""
                + categoryId
                + "\",\"name\":\"Reset "
                + Ids.newId()
                + "\",\"scheduledFor\":\"2026-12-01\"}",
            tenant));
  }

  private static String attachBody(String planogram) {
    return "{\"planogramId\":\"" + planogram + "\"}";
  }

  @Test
  @DisplayName("A brand is made own-brand only by its own business's management")
  void aBrandIsMadeOwnBrandOnlyByItsOwnManagement() {
    String brand = id(post("/admin/brands", "{\"name\":\"Own " + Ids.newId() + "\"}", T));
    String path = "/admin/merchandising/brands/" + brand + "/own-brand";
    String untouched = "SELECT count(*) FROM product.brands WHERE id = ?::uuid AND NOT own_brand";

    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertRefused(send("PUT", path, "{\"ownBrand\":true}", T, role), 403, "FORBIDDEN");
    }
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(send("PUT", path, "{\"ownBrand\":true}", RIVAL, role), 404, "BRAND_NOT_FOUND");
    }
    assertRefused(
        send(
            "PUT",
            "/admin/merchandising/brands/" + Ids.newId() + "/own-brand",
            "{\"ownBrand\":true}",
            T,
            "OWNER"),
        404,
        "BRAND_NOT_FOUND");
    assertThat("the flag never moved", count(untouched, brand), is(1));

    // Its own manager can, and can take it back.
    body(send("PUT", path, "{\"ownBrand\":true}", T, "MANAGER"), 200);
    assertThat(count(untouched, brand), is(0));
    body(send("PUT", path, "{\"ownBrand\":false}", T, "OWNER"), 200);
    assertThat(count(untouched, brand), is(1));
  }

  @Test
  @DisplayName("A fixture of an unknown kind, a code already in use, or a lower role is refused")
  void aFixtureIsRefusedWhenItsKindCodeOrCallerIsWrong() {
    String code = "P-" + tail();
    String json =
        "{\"storeId\":\""
            + STORE
            + "\",\"code\":\""
            + code
            + "\",\"name\":\"Pallet\",\"kind\":\"PALLET\",\"shelfCount\":1,\"shelfWidthMm\":1200}";
    String named =
        "SELECT count(*) FROM product.merch_fixtures WHERE tenant_id = ?::uuid"
            + " AND lower(code) = lower(?)";
    assertRefused(
        send("POST", "/admin/merchandising/fixtures", json, T, "OWNER"),
        400,
        "FIXTURE_KIND_UNKNOWN");
    assertThat(count(named, T, code), is(0));

    String gondola = json.replace("PALLET", "GONDOLA");
    assertRefused(
        send("POST", "/admin/merchandising/fixtures", gondola, T, "CASHIER"), 403, "FORBIDDEN");
    assertThat(count(named, T, code), is(0));

    String first = id(send("POST", "/admin/merchandising/fixtures", gondola, T, "OWNER"));
    // The same code in another case is the same code: the index is on lower(code).
    assertRefused(
        send(
            "POST",
            "/admin/merchandising/fixtures",
            gondola.replace(code, code.toLowerCase(java.util.Locale.ROOT)),
            T,
            "OWNER"),
        409,
        "FIXTURE_CODE_TAKEN");
    assertThat("still one", count(named, T, code), is(1));

    // Another business may use it.
    body(send("POST", "/admin/merchandising/fixtures", gondola, RIVAL, "OWNER"), 201);

    // Retired, the code is free again, and retiring twice is refused.
    body(send("POST", "/admin/merchandising/fixtures/" + first + "/retire", "{}", T, "OWNER"), 200);
    assertRefused(
        send("POST", "/admin/merchandising/fixtures/" + first + "/retire", "{}", T, "OWNER"),
        409,
        "FIXTURE_ALREADY_RETIRED");
    body(
        send("POST", "/admin/merchandising/fixtures/" + first + "/retire", "{}", T, "CASHIER"),
        403);
    body(send("POST", "/admin/merchandising/fixtures", gondola, T, "OWNER"), 201);
    assertThat(count(named, T, code), is(2));
  }

  @Test
  @DisplayName("Nothing new is drawn for a retired fixture")
  void nothingNewIsDrawnForARetiredFixture() {
    String fixtureId = fixture(T, 2, 1000);
    body(post("/admin/merchandising/fixtures/" + fixtureId + "/retire", "{}", T), 200);
    assertRefused(
        post(
            "/admin/merchandising/fixtures/" + fixtureId + "/planograms",
            "{\"effectiveFrom\":\"2026-10-01\"}",
            T),
        409,
        "FIXTURE_RETIRED");
    assertThat(
        "no layout was started",
        count("SELECT count(*) FROM product.planograms WHERE fixture_id = ?::uuid", fixtureId),
        is(0));
  }

  @Test
  @DisplayName(
      "An id that is missing or not a UUIDv7, and a day not written as a date, are refused")
  void aBadIdOrDayIsRefused() {
    String code = "X-" + tail();
    String fixtures = "SELECT count(*) FROM product.merch_fixtures WHERE code = ?";
    assertRefused(
        post(
            "/admin/merchandising/fixtures",
            "{\"storeId\":\"store-1\",\"code\":\""
                + code
                + "\",\"name\":\"x\",\"kind\":\"GONDOLA\",\"shelfCount\":1,\"shelfWidthMm\":900}",
            T),
        400,
        "MERCH_ID_INVALID");
    assertThat(count(fixtures, code), is(0));
    assertRefused(get("/admin/merchandising/space", "store", "abc", T), 400, "MERCH_ID_INVALID");
    assertRefused(get("/admin/merchandising/fixtures", null, null, T), 400, "MERCH_ID_REQUIRED");
    assertRefused(get("/admin/merchandising/space", null, null, T), 400, "MERCH_ID_REQUIRED");

    String fixtureId = fixture(T, 1, 1000);
    assertRefused(
        post(
            "/admin/merchandising/fixtures/" + fixtureId + "/planograms",
            "{\"effectiveFrom\":\"next week\"}",
            T),
        400,
        "MERCH_DATE_INVALID");
    assertThat(
        count("SELECT count(*) FROM product.planograms WHERE fixture_id = ?::uuid", fixtureId),
        is(0));

    String categoryId = category(T);
    String name = "Reset " + Ids.newId();
    assertRefused(
        post(
            "/admin/merchandising/resets",
            "{\"categoryId\":\""
                + categoryId
                + "\",\"name\":\""
                + name
                + "\",\"scheduledFor\":\"31/12/2026\"}",
            T),
        400,
        "MERCH_DATE_INVALID");
    // A reset needs its day and its name, said before the service is asked.
    assertRefused(
        post(
            "/admin/merchandising/resets",
            "{\"categoryId\":\"" + categoryId + "\",\"name\":\"" + name + "\"}",
            T),
        400,
        "VALIDATION_FAILED");
    assertRefused(
        post(
            "/admin/merchandising/resets",
            "{\"categoryId\":\""
                + categoryId
                + "\",\"name\":\"  \",\"scheduledFor\":\"2026-12-01\"}",
            T),
        400,
        "VALIDATION_FAILED");
    assertRefused(
        send(
            "POST",
            "/admin/merchandising/resets",
            "{\"categoryId\":\""
                + categoryId
                + "\",\"name\":\""
                + name
                + "\",\"scheduledFor\":\"2026-12-01\"}",
            T,
            "CASHIER"),
        403,
        "FORBIDDEN");
    assertThat(
        "no reset was planned",
        count(
            "SELECT count(*) FROM product.category_resets WHERE category_id = ?::uuid", categoryId),
        is(0));
    assertRefused(
        put(
            "/admin/merchandising/space-plans",
            "{\"storeId\":\""
                + STORE
                + "\",\"categoryId\":\""
                + categoryId
                + "\",\"targetShare\":0.1,\"reviewOn\":\"tomorrow\"}",
            T),
        400,
        "MERCH_DATE_INVALID");
    assertThat(
        count(
            "SELECT count(*) FROM product.category_space_plans WHERE category_id = ?::uuid",
            categoryId),
        is(0));
  }

  @Test
  @DisplayName("A planogram, reset or layout that is not there, or not ours, is not found")
  void anUnknownPlanogramOrResetIsNotFound() {
    String categoryId = category(T);
    String published = published(T);
    String mine = reset(T, categoryId);

    assertRefused(
        put(
            "/admin/merchandising/planograms/" + Ids.newId() + "/positions",
            "{\"positions\":[]}",
            T),
        404,
        "PLANOGRAM_NOT_FOUND");
    assertRefused(
        post("/admin/merchandising/planograms/" + Ids.newId() + "/publish", "{}", T),
        404,
        "PLANOGRAM_NOT_FOUND");
    assertRefused(
        get("/admin/merchandising/planograms/" + Ids.newId(), null, null, T),
        404,
        "PLANOGRAM_NOT_FOUND");
    assertRefused(
        get("/admin/merchandising/planograms/" + published, null, null, RIVAL),
        404,
        "PLANOGRAM_NOT_FOUND");
    assertRefused(
        post(
            "/admin/merchandising/resets/" + mine + "/planograms",
            attachBody(Ids.newId().toString()),
            T),
        404,
        "PLANOGRAM_NOT_FOUND");

    // A reset that is not ours is not found, whatever it is asked to do.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(
          send("POST", "/admin/merchandising/resets/" + mine + "/complete", "{}", RIVAL, role),
          404,
          "RESET_NOT_FOUND");
      assertRefused(
          send(
              "POST",
              "/admin/merchandising/resets/" + mine + "/cancel",
              "{\"reason\":\"not yours\"}",
              RIVAL,
              role),
          404,
          "RESET_NOT_FOUND");
      assertRefused(
          send(
              "POST",
              "/admin/merchandising/resets/" + mine + "/planograms",
              attachBody(published),
              RIVAL,
              role),
          404,
          "RESET_NOT_FOUND");
    }
    assertRefused(
        post("/admin/merchandising/resets/" + Ids.newId() + "/complete", "{}", T),
        404,
        "RESET_NOT_FOUND");
    assertRefused(
        post(
            "/admin/merchandising/resets/" + Ids.newId() + "/planograms", attachBody(published), T),
        404,
        "RESET_NOT_FOUND");
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertRefused(
          send("POST", "/admin/merchandising/resets/" + mine + "/complete", "{}", T, role),
          403,
          "FORBIDDEN");
    }
    assertThat(
        "our reset never moved",
        count(
            "SELECT count(*) FROM product.category_resets WHERE id = ?::uuid"
                + " AND status = 'PLANNED'",
            mine),
        is(1));
    assertThat(
        "and holds no layout",
        count(
            "SELECT count(*) FROM product.category_reset_planograms WHERE reset_id = ?::uuid",
            mine),
        is(0));
  }

  @Test
  @DisplayName("A reset ends once, and a finished one takes nothing more")
  void aResetEndsOnce() {
    String categoryId = category(T);
    String published = published(T);
    String r = reset(T, categoryId);
    body(post("/admin/merchandising/resets/" + r + "/complete", "{}", T), 200);

    assertRefused(
        post("/admin/merchandising/resets/" + r + "/complete", "{}", T), 409, "RESET_NOT_OPEN");
    assertRefused(
        post("/admin/merchandising/resets/" + r + "/cancel", "{\"reason\":\"late\"}", T),
        409,
        "RESET_NOT_OPEN");
    assertRefused(
        post("/admin/merchandising/resets/" + r + "/planograms", attachBody(published), T),
        409,
        "RESET_NOT_OPEN");
    String status =
        "SELECT count(*) FROM product.category_resets WHERE id = ?::uuid AND status = ?";
    assertThat("still completed", count(status, r, "COMPLETED"), is(1));
    assertThat(
        "nothing was attached",
        count("SELECT count(*) FROM product.category_reset_planograms WHERE reset_id = ?::uuid", r),
        is(0));

    // A cancelled one cannot be completed either.
    String called = reset(T, categoryId);
    body(
        post(
            "/admin/merchandising/resets/" + called + "/cancel",
            "{\"reason\":\"range changed\"}",
            T),
        200);
    assertRefused(
        post("/admin/merchandising/resets/" + called + "/complete", "{}", T),
        409,
        "RESET_NOT_OPEN");
    assertThat("still cancelled", count(status, called, "CANCELLED"), is(1));
  }

  @Test
  @DisplayName("A reset moves published layouts only, and two resets cannot move one shelf")
  void aResetMovesOnlyPublishedLayoutsOnce() {
    String categoryId = category(T);
    String attached =
        "SELECT count(*) FROM product.category_reset_planograms WHERE reset_id = ?::uuid";

    String draft = draft(T, fixture(T, 1, 1000));
    String r0 = reset(T, categoryId);
    assertRefused(
        post("/admin/merchandising/resets/" + r0 + "/planograms", attachBody(draft), T),
        409,
        "PLANOGRAM_NOT_PUBLISHED");
    assertThat("a draft was not attached", count(attached, r0), is(0));

    String published = published(T);
    String r1 = reset(T, categoryId);
    String r2 = reset(T, categoryId);
    body(post("/admin/merchandising/resets/" + r1 + "/planograms", attachBody(published), T), 200);
    assertRefused(
        post("/admin/merchandising/resets/" + r2 + "/planograms", attachBody(published), T),
        409,
        "RESET_PLANOGRAM_CLAIMED");
    assertThat(count(attached, r1), is(1));
    assertThat("the second reset holds none", count(attached, r2), is(0));
  }

  @Test
  @DisplayName("A category's plan saved with the id in capitals is answered, not lost")
  void aPlanSavedWithAnUppercaseCategoryIdIsAnswered() {
    String store = Ids.newId().toString();
    String categoryId = category(T);
    Response saved =
        put(
            "/admin/merchandising/space-plans",
            "{\"storeId\":\""
                + store
                + "\",\"categoryId\":\""
                + categoryId.toUpperCase(java.util.Locale.ROOT)
                + "\",\"targetShare\":0.0800}",
            T);
    String answer = body(saved, 200);
    assertThat(answer, containsString(categoryId));
    assertThat(
        "one plan, in the store it was saved for",
        count(
            "SELECT count(*) FROM product.category_space_plans WHERE category_id = ?::uuid"
                + " AND store_id = ?::uuid",
            categoryId,
            store),
        is(1));
  }
}
