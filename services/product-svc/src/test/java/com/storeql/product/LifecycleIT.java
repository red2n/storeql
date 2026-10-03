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
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Item lifecycle: a line is listed before it goes on sale (NEW_LINE), sells (ACTIVE), is run down
 * (DISCONTINUED) and is taken off (DELISTED). The shop lists what is on sale or being run down; the
 * till refuses a new line with its launch day and finds nothing delisted; each move goes out with
 * the variants it covers, and a move a line cannot make from where it is is refused.
 */
@HelidonTest
class LifecycleIT {

  // Declared before the static block, which registers them with the tenant-svc stub.
  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();

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

  private Response post(String path, String json, String tenant, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String tenant, String roles, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", roles).get();
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

  private Response move(String product, String action, String tenant, String roles) {
    return post("/admin/products/" + product + "/" + action, "", tenant, roles);
  }

  private String barcode() {
    String body = String.format("5%011d", Math.abs(System.nanoTime()) % 100_000_000_000L);
    return body + com.storeql.gs1.Gtin.checkDigit(body);
  }

  private static String outboxTypes() throws Exception {
    StringBuilder out = new StringBuilder();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs =
            st.executeQuery(
                "SELECT event_type, payload FROM product.outbox WHERE tenant_id = '"
                    + T
                    + "'::uuid ORDER BY created_at")) {
      while (rs.next())
        out.append(rs.getString(1)).append(' ').append(rs.getString(2)).append('\n');
    }
    return out.toString();
  }

  @Test
  @DisplayName(
      "A new line is hidden and refused with its launch day until launched, then listed and scanned")
  void aNewLineWaitsForItsLaunch() {
    String code = barcode();
    String launchDay = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(400).toString();
    String created =
        body(
            post(
                "/admin/products",
                "{\"name\":\"Spring cola "
                    + Ids.newId()
                    + "\",\"status\":\"new_line\",\"launchOn\":\""
                    + launchDay
                    + "\"}",
                T,
                "OWNER"),
            201);
    assertThat(created, containsString("\"status\":\"NEW_LINE\""));
    assertThat(created, containsString("\"launchOn\":\"" + launchDay + "\""));
    String pid = id(created);
    body(
        post(
            "/admin/products/" + pid + "/variants",
            "{\"sku\":\"SC-" + Ids.newId() + "\",\"barcode\":\"" + code + "\"}",
            T,
            "OWNER"),
        201);

    assertThat(
        "hidden from the shop",
        body(get("/catalog/products", T, null, "limit", "100"), 200),
        not(containsString(pid)));
    String refused = body(get("/catalog/variants/by-barcode/" + code, T, "CASHIER"), 409);
    assertThat(refused, containsString("PRODUCT_NOT_ON_SALE_YET"));
    assertThat(refused, containsString(launchDay));
    assertThat(
        body(move(pid, "discontinue", T, "OWNER"), 409),
        containsString("PRODUCT_LIFECYCLE_INVALID"));
    assertThat(move(pid, "launch", T, "CASHIER").getStatus(), is(403));
    assertThat(move(pid, "launch", RIVAL, "OWNER").getStatus(), is(404));

    String launched = body(move(pid, "launch", T, "OWNER"), 200);
    assertThat(launched, containsString("\"status\":\"ACTIVE\""));
    assertThat("the launch day is spent", launched, not(containsString("launchOn")));
    assertThat(body(get("/catalog/products", T, null, "limit", "100"), 200), containsString(pid));
    body(get("/catalog/variants/by-barcode/" + code, T, "CASHIER"), 200);
    assertThat(
        body(move(pid, "launch", T, "OWNER"), 409), containsString("PRODUCT_LIFECYCLE_INVALID"));
  }

  @Test
  @DisplayName(
      "A discontinued line still sells, leaves with its variants, comes back, and delisted is gone")
  void aDiscontinuedLineSellsWhileStockLastsThenGoes() throws Exception {
    String code = barcode();
    String pid =
        id(
            body(
                post("/admin/products", "{\"name\":\"Old cola " + Ids.newId() + "\"}", T, "OWNER"),
                201));
    String vid =
        id(
            body(
                post(
                    "/admin/products/" + pid + "/variants",
                    "{\"sku\":\"OC-" + Ids.newId() + "\",\"barcode\":\"" + code + "\"}",
                    T,
                    "OWNER"),
                201));
    assertThat(
        body(move(pid, "reinstate", T, "OWNER"), 409), containsString("PRODUCT_LIFECYCLE_INVALID"));

    String discontinued = body(move(pid, "discontinue", T, "OWNER"), 200);
    assertThat(discontinued, containsString("\"status\":\"DISCONTINUED\""));
    assertThat(discontinued, containsString("\"discontinuedAt\":"));
    assertThat(
        "still in the shop",
        body(get("/catalog/products", T, null, "limit", "100"), 200),
        containsString(pid));
    body(get("/catalog/variants/by-barcode/" + code, T, "CASHIER"), 200);
    assertThat(
        "the admin list filters by state",
        body(get("/admin/products", T, "OWNER", "status", "DISCONTINUED", "limit", "100"), 200),
        containsString(pid));
    String events = outboxTypes();
    assertThat(events, containsString("ProductDiscontinued"));
    assertThat(
        "the event names the variants it covers",
        events,
        containsString("\"variantIds\":[\"" + vid + "\"]"));

    body(move(pid, "reinstate", T, "OWNER"), 200);
    assertThat(outboxTypes(), containsString("ProductReinstated"));
    body(
        target
            .path("/admin/products/" + pid)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .delete(),
        200);
    assertThat(
        body(get("/catalog/products", T, null, "limit", "100"), 200), not(containsString(pid)));
    body(get("/catalog/variants/by-barcode/" + code, T, "CASHIER"), 404);
    assertThat(
        "delisted goes out with its variants too",
        outboxTypes(),
        containsString("ProductDelisted {\"eventId\""));
    assertThat(
        body(move(pid, "reinstate", T, "OWNER"), 409), containsString("PRODUCT_LIFECYCLE_INVALID"));
    assertThat(
        body(move(pid, "launch", T, "OWNER"), 409), containsString("PRODUCT_LIFECYCLE_INVALID"));
  }

  @Test
  @DisplayName(
      "A status the lifecycle does not know, or a launch day on a line on sale, is refused")
  void creationRefusesWhatTheLifecycleDoesNotKnow() {
    assertThat(
        body(post("/admin/products", "{\"name\":\"Odd\",\"status\":\"RETIRED\"}", T, "OWNER"), 400),
        containsString("PRODUCT_STATUS_INVALID"));
    assertThat(
        body(
            post("/admin/products", "{\"name\":\"Odd\",\"launchOn\":\"2027-01-01\"}", T, "OWNER"),
            400),
        containsString("PRODUCT_LAUNCH_ON_NEEDS_NEW_LINE"));
    assertThat(
        body(
            post(
                "/admin/products",
                "{\"name\":\"Odd\",\"status\":\"NEW_LINE\",\"launchOn\":\"soon\"}",
                T,
                "OWNER"),
            400),
        containsString("PRODUCT_LAUNCH_ON_INVALID"));
  }

  // ── discontinuing is the whole business's (2 Oct 2026) ───────────────────────

  /** A lifecycle move by a member of staff held to the stores named (none: the whole business). */
  private Response moveAs(String product, String action, String tenant, String roles, String held) {
    var req =
        target
            .path("/admin/products/" + product + "/" + action)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", roles);
    if (held != null) req = req.header("X-Store-Ids", held);
    return req.post(Entity.entity("", MediaType.APPLICATION_JSON));
  }

  private static int discontinuedEvents(String product) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM product.outbox WHERE aggregate_id = ?::uuid"
                    + " AND event_type = 'ProductDiscontinued'")) {
      ps.setString(1, product);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private String status(String product) {
    String body = body(get("/admin/products/" + product, T, "OWNER"), 200);
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("status");
  }

  @Test
  @DisplayName(
      "Only an owner or a manager of the whole business discontinues a line; nothing moves else")
  void onlyTheWholeBusinessDiscontinuesALine() throws Exception {
    String pid =
        id(
            body(
                post("/admin/products", "{\"name\":\"Run-down " + Ids.newId() + "\"}", T, "OWNER"),
                201));
    String store = Ids.newId().toString();
    String another = Ids.newId().toString();

    // A manager held to stores, one or several: the line stops being reordered at every store,
    // which is not theirs to decide. Who can is named.
    for (String held : new String[] {store, store + "," + another}) {
      String refused = body(moveAs(pid, "discontinue", T, "MANAGER", held), 403);
      assertThat(refused, containsString("BUSINESS_WIDE_ONLY"));
      assertThat(refused, containsString("an owner or a manager of the whole business"));
    }
    // Below management, held or not: refused at the door.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertThat(role, moveAs(pid, "discontinue", T, role, store).getStatus(), is(403));
      assertThat(role, moveAs(pid, "discontinue", T, role, null).getStatus(), is(403));
    }
    // Another business: held, refused as held; held to none, our line is not found.
    String refusedRival = body(moveAs(pid, "discontinue", RIVAL, "MANAGER", store), 403);
    assertThat(refusedRival, containsString("BUSINESS_WIDE_ONLY"));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          body(moveAs(pid, "discontinue", RIVAL, role, null), 404),
          containsString("PRODUCT_NOT_FOUND"));
    }
    assertThat("nothing moved", status(pid), is("ACTIVE"));
    assertThat("and nothing was announced", discontinuedEvents(pid), is(0));

    // A manager of the whole business may, as an owner may.
    String discontinued = body(moveAs(pid, "discontinue", T, "MANAGER", null), 200);
    assertThat(discontinued, containsString("\"status\":\"DISCONTINUED\""));
    assertThat(discontinuedEvents(pid), is(1));
    String byOwner =
        id(
            body(
                post("/admin/products", "{\"name\":\"Old " + Ids.newId() + "\"}", T, "OWNER"),
                201));
    body(moveAs(byOwner, "discontinue", T, "OWNER", null), 200);
    assertThat(status(byOwner), is("DISCONTINUED"));
  }

  // ── delisting and reinstating are the whole business's too (2 Oct 2026) ──────────

  /** A delist by a member of staff held to the stores named (none: the whole business). */
  private Response delistAs(String product, String tenant, String roles, String held) {
    var req =
        target
            .path("/admin/products/" + product)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", roles);
    if (held != null) req = req.header("X-Store-Ids", held);
    return req.delete();
  }

  private static int events(String product, String type) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM product.outbox WHERE aggregate_id = ?::uuid"
                    + " AND event_type = ?")) {
      ps.setString(1, product);
      ps.setString(2, type);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static String rangeOf(String product) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT string_agg(store_id::text, ',' ORDER BY store_id) FROM"
                    + " product.product_stores WHERE tenant_id = ?::uuid AND product_id = ?::uuid")) {
      ps.setString(1, T);
      ps.setString(2, product);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  @Test
  @DisplayName(
      "Only an owner or a manager of the whole business delists or reinstates a line, even a held"
          + " manager's own local line; nothing moves else")
  void onlyTheWholeBusinessDelistsOrReinstatesALine() throws Exception {
    String store = Ids.newId().toString();
    String another = Ids.newId().toString();

    // The manager's own local line: made by them, naming no store, so ranged to theirs alone.
    String local =
        id(
            body(
                createAs("{\"name\":\"Local loaf " + Ids.newId() + "\"}", T, "MANAGER", store),
                201));
    assertThat("ranged to their store alone", rangeOf(local), is(store));
    // A line of the whole business, run down by the owner.
    String runDown =
        id(
            body(
                post("/admin/products", "{\"name\":\"Run-down " + Ids.newId() + "\"}", T, "OWNER"),
                201));
    body(moveAs(runDown, "discontinue", T, "OWNER", null), 200);

    // A manager held to stores, one or several: neither line is theirs to delist or bring back,
    // their own local line included. Who can is named.
    for (String held : new String[] {store, store + "," + another}) {
      for (String line : new String[] {local, runDown}) {
        String delist = body(delistAs(line, T, "MANAGER", held), 403);
        assertThat(delist, containsString("BUSINESS_WIDE_ONLY"));
        assertThat(delist, containsString("an owner or a manager of the whole business"));
      }
      String reinstate = body(moveAs(runDown, "reinstate", T, "MANAGER", held), 403);
      assertThat(reinstate, containsString("BUSINESS_WIDE_ONLY"));
      assertThat(reinstate, containsString("an owner or a manager of the whole business"));
    }
    // Below management, held or not, and the platform operator: refused at the door.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER", "PLATFORM_ADMIN"}) {
      for (String held : new String[] {store, null}) {
        assertThat(role, delistAs(local, T, role, held).getStatus(), is(403));
        assertThat(role, moveAs(runDown, "reinstate", T, role, held).getStatus(), is(403));
      }
    }
    // Another business: held (even naming our store), refused as held; held to none, our lines
    // are not found.
    assertThat(
        body(delistAs(local, RIVAL, "MANAGER", store), 403), containsString("BUSINESS_WIDE_ONLY"));
    assertThat(
        body(moveAs(runDown, "reinstate", RIVAL, "MANAGER", store), 403),
        containsString("BUSINESS_WIDE_ONLY"));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          body(delistAs(local, RIVAL, role, null), 404), containsString("PRODUCT_NOT_FOUND"));
      assertThat(
          body(moveAs(runDown, "reinstate", RIVAL, role, null), 404),
          containsString("PRODUCT_NOT_FOUND"));
    }
    assertThat("nothing moved", status(local), is("ACTIVE"));
    assertThat("nothing moved", status(runDown), is("DISCONTINUED"));
    assertThat("its range is as it was", rangeOf(local), is(store));
    assertThat("and nothing was announced", events(local, "ProductDelisted"), is(0));
    assertThat(events(runDown, "ProductDelisted"), is(0));
    assertThat(events(runDown, "ProductReinstated"), is(0));

    // A manager of the whole business may, as an owner may.
    body(moveAs(runDown, "reinstate", T, "MANAGER", null), 200);
    assertThat(status(runDown), is("ACTIVE"));
    assertThat(events(runDown, "ProductReinstated"), is(1));
    body(delistAs(runDown, T, "MANAGER", null), 200);
    assertThat(status(runDown), is("DELISTED"));
    assertThat(events(runDown, "ProductDelisted"), is(1));
    body(delistAs(local, T, "OWNER", null), 200);
    assertThat(status(local), is("DELISTED"));
    assertThat(events(local, "ProductDelisted"), is(1));
  }

  /** A product made by a member of staff held to the stores named (none: the whole business). */
  private Response createAs(String json, String tenant, String roles, String held) {
    return as("/admin/products", tenant, roles, held)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** A request by a member of staff held to the stores named (none: the whole business). */
  private jakarta.ws.rs.client.Invocation.Builder as(
      String path, String tenant, String roles, String held) {
    var req =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", roles);
    return held == null ? req : req.header("X-Store-Ids", held);
  }

  // ── launching is the whole business's too (3 Oct 2026) ────────────────────────────

  @Test
  @DisplayName(
      "Only an owner or a manager of the whole business launches a new line, even a held manager's"
          + " own; nothing moves else")
  void onlyTheWholeBusinessLaunchesALine() throws Exception {
    String store = Ids.newId().toString();
    String another = Ids.newId().toString();
    String launchDay = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(400).toString();

    // The manager's own new line: made by them, naming no store, so ranged to theirs alone.
    String local =
        id(
            body(
                createAs(
                    "{\"name\":\"Local new " + Ids.newId() + "\",\"status\":\"NEW_LINE\"}",
                    T,
                    "MANAGER",
                    store),
                201));
    assertThat("ranged to their store alone", rangeOf(local), is(store));
    // The owner's new line, sold everywhere from a day the business chose.
    String embargoed =
        id(
            body(
                post(
                    "/admin/products",
                    "{\"name\":\"Embargoed "
                        + Ids.newId()
                        + "\",\"status\":\"NEW_LINE\",\"launchOn\":\""
                        + launchDay
                        + "\"}",
                    T,
                    "OWNER"),
                201));

    // A manager held to stores, one or several: neither line is theirs to put on sale.
    for (String held : new String[] {store, store + "," + another}) {
      for (String line : new String[] {local, embargoed}) {
        String refused = body(moveAs(line, "launch", T, "MANAGER", held), 403);
        assertThat(refused, containsString("BUSINESS_WIDE_ONLY"));
        assertThat(refused, containsString("an owner or a manager of the whole business"));
      }
    }
    // Below management, held or not, and the platform operator: refused at the door.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER", "PLATFORM_ADMIN"}) {
      for (String held : new String[] {store, null}) {
        assertThat(role, moveAs(embargoed, "launch", T, role, held).getStatus(), is(403));
      }
    }
    // Another business: held (even naming our store), refused as held; held to none, our line is
    // not found.
    assertThat(
        body(moveAs(embargoed, "launch", RIVAL, "MANAGER", store), 403),
        containsString("BUSINESS_WIDE_ONLY"));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          body(moveAs(embargoed, "launch", RIVAL, role, null), 404),
          containsString("PRODUCT_NOT_FOUND"));
    }
    assertThat("nothing moved", status(local), is("NEW_LINE"));
    assertThat("nothing moved", status(embargoed), is("NEW_LINE"));
    assertThat("and nothing was announced", events(local, "ProductLaunched"), is(0));
    assertThat(events(embargoed, "ProductLaunched"), is(0));

    // A manager of the whole business may, as an owner may.
    body(moveAs(embargoed, "launch", T, "MANAGER", null), 200);
    assertThat(status(embargoed), is("ACTIVE"));
    assertThat(events(embargoed, "ProductLaunched"), is(1));
    body(moveAs(local, "launch", T, "OWNER", null), 200);
    assertThat(status(local), is("ACTIVE"));
    assertThat("its range is as it was", rangeOf(local), is(store));
  }

  // ── taking a variant off sale is the whole business's too (3 Oct 2026) ─────────────

  /** A variant with a barcode under the product, made by the owner; its id. */
  private String variantOf(String product, String sku, String code) {
    return id(
        body(
            post(
                "/admin/products/" + product + "/variants",
                "{\"sku\":\"" + sku + "\",\"barcode\":\"" + code + "\"}",
                T,
                "OWNER"),
            201));
  }

  /** A variant's delist by a member of staff held to the stores named (none: whole business). */
  private Response delistVariantAs(
      String product, String variant, String tenant, String roles, String held) {
    return as("/admin/products/" + product + "/variants/" + variant, tenant, roles, held).delete();
  }

  /** The variant holding that SKU in the business, as stored: {@code id|product_id|status}. */
  private static String variantRow(String tenant, String sku) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT string_agg(id::text || '|' || product_id::text || '|' || status, ',')"
                    + " FROM product.product_variants WHERE tenant_id = ?::uuid AND sku = ?")) {
      ps.setString(1, tenant);
      ps.setString(2, sku);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static int productsNamed(String tenant, String name) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid AND name = ?")) {
      ps.setString(1, tenant);
      ps.setString(2, name);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  @Test
  @DisplayName(
      "Only an owner or a manager of the whole business takes a variant off sale, even one of a"
          + " held manager's own line; nothing moves else")
  void onlyTheWholeBusinessDelistsAVariant() throws Exception {
    String store = Ids.newId().toString();
    String another = Ids.newId().toString();

    // A line of the whole business, sold at every store, and the manager's own local line.
    String everywhere =
        id(
            body(
                post("/admin/products", "{\"name\":\"Cola " + Ids.newId() + "\"}", T, "OWNER"),
                201));
    String local =
        id(
            body(
                createAs("{\"name\":\"Local loaf " + Ids.newId() + "\"}", T, "MANAGER", store),
                201));
    String skuEverywhere = "CO-" + Ids.newId();
    String skuLocal = "LL-" + Ids.newId();
    String codeEverywhere = barcode();
    String codeLocal = barcode();
    String vEverywhere = variantOf(everywhere, skuEverywhere, codeEverywhere);
    String vLocal = variantOf(local, skuLocal, codeLocal);
    String rowEverywhere = variantRow(T, skuEverywhere);
    String rowLocal = variantRow(T, skuLocal);
    assertThat(rowEverywhere, is(vEverywhere + "|" + everywhere + "|ACTIVE"));

    // A manager held to stores, one or several: neither variant is theirs to take off sale, the
    // one of their own line included. Who can is named.
    for (String held : new String[] {store, store + "," + another}) {
      String refused = body(delistVariantAs(everywhere, vEverywhere, T, "MANAGER", held), 403);
      assertThat(refused, containsString("BUSINESS_WIDE_ONLY"));
      assertThat(refused, containsString("an owner or a manager of the whole business"));
      assertThat(
          body(delistVariantAs(local, vLocal, T, "MANAGER", held), 403),
          containsString("BUSINESS_WIDE_ONLY"));
    }
    // Below management, held or not, and the platform operator: refused at the door.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER", "PLATFORM_ADMIN"}) {
      for (String held : new String[] {store, null}) {
        assertThat(
            role, delistVariantAs(everywhere, vEverywhere, T, role, held).getStatus(), is(403));
      }
    }
    // Another business naming our product and variant: held (even naming our store), refused as
    // held; held to none, our variant is not found.
    assertThat(
        body(delistVariantAs(everywhere, vEverywhere, RIVAL, "MANAGER", store), 403),
        containsString("BUSINESS_WIDE_ONLY"));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          body(delistVariantAs(everywhere, vEverywhere, RIVAL, role, null), 404),
          containsString("VARIANT_NOT_FOUND"));
    }
    assertThat("nothing moved", variantRow(T, skuEverywhere), is(rowEverywhere));
    assertThat("nothing moved", variantRow(T, skuLocal), is(rowLocal));
    body(get("/catalog/variants/by-barcode/" + codeEverywhere, T, "CASHIER"), 200);
    body(get("/catalog/variants/by-barcode/" + codeLocal, T, "CASHIER"), 200);

    // A manager of the whole business may, as an owner may.
    assertThat(
        body(delistVariantAs(everywhere, vEverywhere, T, "MANAGER", null), 200),
        containsString("\"status\":\"INACTIVE\""));
    assertThat(variantRow(T, skuEverywhere), is(vEverywhere + "|" + everywhere + "|INACTIVE"));
    body(get("/catalog/variants/by-barcode/" + codeEverywhere, T, "CASHIER"), 404);
    body(delistVariantAs(local, vLocal, T, "OWNER", null), 200);
    assertThat(variantRow(T, skuLocal), is(vLocal + "|" + local + "|INACTIVE"));
  }

  private static String replaceSheet(String name, String sku) {
    return "{\"mode\":\"REPLACE\",\"products\":[{\"name\":\""
        + name
        + "\",\"sellableOnline\":false,\"variants\":[{\"sku\":\""
        + sku
        + "\"}]}]}";
  }

  @Test
  @DisplayName(
      "A held manager's REPLACE import never drops a variant already there; an owner's sheet still"
          + " wins, and another business's touches nothing of ours")
  void aHeldManagersReplaceNeverDropsAVariant() throws Exception {
    String store = Ids.newId().toString();
    String everywhere =
        id(
            body(
                post("/admin/products", "{\"name\":\"Cola " + Ids.newId() + "\"}", T, "OWNER"),
                201));
    String sku = "CO-" + Ids.newId();
    String code = barcode();
    String variant = variantOf(everywhere, sku, code);
    String before = variantRow(T, sku);
    assertThat(before, is(variant + "|" + everywhere + "|ACTIVE"));

    // The held manager's sheet moves our SKU under a line of their own, which REPLACE would do by
    // dropping the variant there: that variant's error, nothing dropped, and no empty line made.
    String mine = "Mine " + Ids.newId();
    Entity<String> sheet = Entity.entity(replaceSheet(mine, sku), MediaType.APPLICATION_JSON);
    String answer = body(as("/admin/import", T, "MANAGER", store).post(sheet), 200);
    assertThat(answer, containsString("BUSINESS_WIDE_ONLY"));
    assertThat(answer, containsString("an owner or a manager of the whole business"));
    assertThat(answer, containsString("\"productsCreated\":0"));
    assertThat(answer, containsString("\"variantsCreated\":0"));
    assertThat("the variant is the one that was there", variantRow(T, sku), is(before));
    assertThat("no line was left behind", productsNamed(T, mine), is(0));
    body(get("/catalog/variants/by-barcode/" + code, T, "CASHIER"), 200);

    // Another business, held (even naming our store) or not: the SKU is free in theirs, so the
    // sheet is theirs to import, and nothing of ours is looked at or touched.
    for (String held : new String[] {store, null}) {
      String theirs = "Theirs " + Ids.newId();
      String imported =
          body(
              as("/admin/import", RIVAL, "MANAGER", held)
                  .post(Entity.entity(replaceSheet(theirs, sku), MediaType.APPLICATION_JSON)),
              200);
      assertThat(imported, containsString("\"errors\":[]"));
      assertThat("ours is as it was", variantRow(T, sku), is(before));
      assertThat(productsNamed(T, theirs), is(0));
    }

    // An owner's sheet still wins: the variant there is dropped and the row's own written.
    String owners = "Owner's " + Ids.newId();
    String replaced =
        body(
            as("/admin/import", T, "OWNER", null)
                .post(Entity.entity(replaceSheet(owners, sku), MediaType.APPLICATION_JSON)),
            200);
    assertThat(replaced, containsString("\"errors\":[]"));
    assertThat(replaced, containsString("\"variantsCreated\":1"));
    assertThat("a new variant holds the SKU now", variantRow(T, sku), is(not(before)));
    assertThat(productsNamed(T, owners), is(1));
  }
}
