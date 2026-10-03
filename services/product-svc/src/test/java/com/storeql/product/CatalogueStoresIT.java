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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every store a catalogue write names is one of the business's own and, for a manager held to
 * stores, one of theirs, over HTTP and a real database (2 Oct 2026).
 *
 * <p>{@code PUT /admin/products/{id}/stores} read its ids with {@code UUID.fromString} and took any
 * of them, and a bulk import's {@code storeIds} were assigned unchecked: another business's store,
 * or one nobody made, sat in {@code product_stores} as though it were real, and a manager held to
 * one branch ranged a product into, or out of, every other. Here tenant-svc is a stand-in that
 * lists each business's stores, and the database is counted for what was written.
 */
@HelidonTest
class CatalogueStoresIT {

  /** A British business: ours, with three shops. */
  private static final String T = Ids.newId().toString();

  /** A Japanese business: another one, which must move nothing of ours. */
  private static final String RIVAL = Ids.newId().toString();

  private static final String STORE_A = Ids.newId().toString();
  private static final String STORE_B = Ids.newId().toString();
  private static final String STORE_C = Ids.newId().toString();
  private static final String RIVAL_STORE = Ids.newId().toString();

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
        .with(RIVAL, "JPY", "JP")
        .withStore(T, STORE_A, "GB")
        .withStore(T, STORE_B, "GB")
        .withStore(T, STORE_C, "GB")
        .withStore(RIVAL, RIVAL_STORE, "JP");
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

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** A member of staff as the gateway describes them; null stores for one held to none. */
  private record Staff(String tenant, String roles, String storeIds) {}

  private static final Staff OWNER = new Staff(T, "OWNER", null);

  /** A manager held to store A alone. */
  private static final Staff HELD_TO_A = new Staff(T, "MANAGER", STORE_A);

  private Response send(String method, String path, String json, Staff who) {
    Invocation.Builder b =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", who.tenant())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", who.roles());
    if (who.storeIds() != null) b = b.header("X-Store-Ids", who.storeIds());
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String body(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private static void assertRefused(Response r, int status, String code) {
    assertThat(body(r, status), containsString(code));
  }

  private static String id(String body) {
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  /** A product of ours, sold everywhere until it is ranged. */
  private String product() {
    return id(
        body(
            send(
                "POST",
                "/admin/products",
                "{\"name\":\"Ranged " + Ids.newId() + "\",\"sellableOnline\":false}",
                OWNER),
            201));
  }

  private static String stores(String... ids) {
    StringBuilder json = new StringBuilder("{\"storeIds\":[");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) json.append(',');
      json.append(ids[i] == null ? "null" : "\"" + ids[i] + "\"");
    }
    return json.append("]}").toString();
  }

  private Response range(String productId, Staff who, String... ids) {
    return send("PUT", "/admin/products/" + productId + "/stores", stores(ids), who);
  }

  private static int rows(String sql, String... params) {
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

  private static final String RANGE =
      "SELECT count(*) FROM product.product_stores WHERE tenant_id = ?::uuid"
          + " AND product_id = ?::uuid";
  private static final String RANGED_AT = RANGE + " AND store_id = ?::uuid";
  private static final String PRODUCTS =
      "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid";
  private static final String ASSIGNMENTS =
      "SELECT count(*) FROM product.product_stores WHERE tenant_id = ?::uuid";
  private static final String NAMED =
      "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid AND name = ?";

  /** Everything of a business's catalogue an import could write, counted. */
  private static List<Integer> written(String tenant) {
    return List.of(rows(PRODUCTS, tenant), rows(ASSIGNMENTS, tenant));
  }

  // ── a product's store assortment ─────────────────────────────────────────────

  @Test
  @DisplayName("A store that is not the business's is not found, and the range is not touched")
  void aStoreThatIsNotTheBusinesssIsNotFound() {
    String p = product();
    body(range(p, OWNER, STORE_A), 200);
    String neverMade = Ids.newId().toString();

    assertRefused(range(p, OWNER, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");
    assertRefused(range(p, OWNER, neverMade), 404, "PRODUCT_STORE_NOT_FOUND");
    assertRefused(range(p, OWNER, STORE_B, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");
    assertRefused(range(p, HELD_TO_A, STORE_A, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");

    assertThat("the range is as it was", rows(RANGE, T, p), is(1));
    assertThat(rows(RANGED_AT, T, p, STORE_A), is(1));
  }

  @Test
  @DisplayName("A store id that is not a UUIDv7, or a hole in the list, is a 400")
  void idsThatAreNotUuidV7AreRefused() {
    String p = product();

    assertRefused(range(p, OWNER, "not-an-id"), 400, "INVALID_UUID");
    assertRefused(range(p, OWNER, "123e4567-e89b-42d3-a456-426614174000"), 400, "INVALID_UUID");
    assertRefused(range(p, OWNER, STORE_A, null), 400, "VALIDATION_FAILED");

    assertThat("still sold everywhere", rows(RANGE, T, p), is(0));
  }

  @Test
  @DisplayName("The owner ranges any of the business's stores, each once, or every store")
  void theOwnerRangesAnyOfTheBusinesssStores() {
    String p = product();

    String answer = body(range(p, OWNER, STORE_A, STORE_B, STORE_A), 200);
    assertThat(answer, containsString("[\"" + STORE_A + "\",\"" + STORE_B + "\"]"));
    assertThat(rows(RANGE, T, p), is(2));

    body(range(p, OWNER), 200);
    assertThat("sold everywhere again", rows(RANGE, T, p), is(0));
  }

  @Test
  @DisplayName("A held manager adds and takes away only their own store")
  void aHeldManagerChangesOnlyTheirOwnStore() {
    String p = product();
    body(range(p, OWNER, STORE_B), 200);

    // Adding a store of the business they do not keep.
    assertRefused(range(p, HELD_TO_A, STORE_B, STORE_C), 403, "STORE_ACCESS_DENIED");
    // Taking B, which they do not keep, away.
    assertRefused(range(p, HELD_TO_A, STORE_A), 403, "STORE_ACCESS_DENIED");
    // Every store: onto every other shelf, which is the whole business's to decide.
    assertRefused(range(p, HELD_TO_A), 403, "BUSINESS_WIDE_ONLY");
    assertThat("the range is as it was", rows(RANGE, T, p), is(1));
    assertThat(rows(RANGED_AT, T, p, STORE_B), is(1));

    // Their own store, B left as it was.
    body(range(p, HELD_TO_A, STORE_B, STORE_A), 200);
    assertThat(rows(RANGED_AT, T, p, STORE_A), is(1));
    // And taken away again.
    body(range(p, HELD_TO_A, STORE_B), 200);
    assertThat(rows(RANGED_AT, T, p, STORE_A), is(0));
    assertThat(rows(RANGED_AT, T, p, STORE_B), is(1));

    // A product sold everywhere cannot be narrowed to their store: off every other shelf.
    String everywhere = product();
    assertRefused(range(everywhere, HELD_TO_A, STORE_A), 403, "BUSINESS_WIDE_ONLY");
    assertThat(rows(RANGE, T, everywhere), is(0));
  }

  @Test
  @DisplayName("Another business, whatever its role, changes nothing of our range")
  void anotherBusinessChangesNothingOfOurRange() {
    String p = product();
    body(range(p, OWNER, STORE_A), 200);
    int theirRanges = rows(ASSIGNMENTS, RIVAL);

    for (Staff theirs :
        List.of(
            new Staff(RIVAL, "OWNER", null),
            new Staff(RIVAL, "MANAGER", null),
            new Staff(RIVAL, "MANAGER", RIVAL_STORE))) {
      // Naming our store, or theirs: our product is not theirs to find.
      assertRefused(range(p, theirs, STORE_A, STORE_B), 404, "PRODUCT_NOT_FOUND");
      assertRefused(range(p, theirs, RIVAL_STORE), 404, "PRODUCT_NOT_FOUND");
      assertRefused(range(p, theirs), 404, "PRODUCT_NOT_FOUND");
      assertRefused(
          send("GET", "/admin/products/" + p + "/stores", null, theirs), 404, "PRODUCT_NOT_FOUND");
    }
    // Their storekeeper, cashier and a shopper are refused at the door, as ours are.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      for (String tenant : new String[] {RIVAL, T}) {
        Staff who = new Staff(tenant, role, "CUSTOMER".equals(role) ? null : STORE_A);
        Response r = range(p, who, STORE_A, STORE_B);
        assertThat(role, r.getStatus(), is(403));
        r.close();
      }
    }

    assertThat("our range is as it was", rows(RANGE, T, p), is(1));
    assertThat(rows(RANGED_AT, T, p, STORE_A), is(1));
    assertThat("nothing was ranged in their business", rows(ASSIGNMENTS, RIVAL), is(theirRanges));
  }

  // ── a bulk import's storeIds ─────────────────────────────────────────────────

  private static String importRows(String store, String... names) {
    StringBuilder json = new StringBuilder("{\"products\":[");
    for (int i = 0; i < names.length; i++) {
      if (i > 0) json.append(',');
      json.append("{\"name\":\"")
          .append(names[i])
          .append("\",\"sellableOnline\":false")
          // The last row names the store; the ones before it name none.
          .append(i == names.length - 1 && store != null ? ",\"storeIds\":[\"" + store + "\"]" : "")
          .append(",\"variants\":[{\"sku\":\"ST-")
          .append(Ids.newId())
          .append("\"}]}");
    }
    return json.append("]}").toString();
  }

  @Test
  @DisplayName("An import naming a store that is not the business's imports nothing")
  void anImportNamingAStoreThatIsNotTheBusinesssImportsNothing() {
    List<Integer> before = written(T);
    String first = "First " + Ids.newId();

    for (String store : new String[] {RIVAL_STORE, Ids.newId().toString()}) {
      assertRefused(
          send("POST", "/admin/import", importRows(store, first, "Second " + Ids.newId()), OWNER),
          404,
          "PRODUCT_STORE_NOT_FOUND");
    }

    assertThat("not even the row before it", rows(NAMED, T, first), is(0));
    assertThat("nothing of the catalogue was written", written(T), is(before));
  }

  @Test
  @DisplayName("A held manager's import names only their stores, or imports nothing")
  void aHeldManagersImportNamesOnlyTheirStores() {
    List<Integer> before = written(T);

    assertRefused(
        send("POST", "/admin/import", importRows(STORE_B, "Not theirs " + Ids.newId()), HELD_TO_A),
        403,
        "STORE_ACCESS_DENIED");
    assertThat("nothing of the catalogue was written", written(T), is(before));

    String own = "Theirs " + Ids.newId();
    String answer = body(send("POST", "/admin/import", importRows(STORE_A, own), HELD_TO_A), 200);
    assertThat(answer, containsString("\"productsCreated\":1"));
    assertThat(
        rows(
            "SELECT count(*) FROM product.product_stores s JOIN product.products p"
                + " ON p.id = s.product_id AND p.tenant_id = s.tenant_id"
                + " WHERE s.tenant_id = ?::uuid AND p.name = ? AND s.store_id = ?::uuid",
            T,
            own,
            STORE_A),
        is(1));
  }

  @Test
  @DisplayName("Another business's import naming our store moves nothing, whatever its role")
  void anotherBusinesssImportNamingOurStoreMovesNothing() {
    List<Integer> ours = written(T);
    List<Integer> theirs = written(RIVAL);

    for (Staff who :
        List.of(
            new Staff(RIVAL, "OWNER", null),
            new Staff(RIVAL, "MANAGER", null),
            new Staff(RIVAL, "MANAGER", RIVAL_STORE))) {
      assertRefused(
          send("POST", "/admin/import", importRows(STORE_A, "Ours " + Ids.newId()), who),
          404,
          "PRODUCT_STORE_NOT_FOUND");
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      Staff who = new Staff(RIVAL, role, "CUSTOMER".equals(role) ? null : RIVAL_STORE);
      Response r = send("POST", "/admin/import", importRows(STORE_A, "Ours " + Ids.newId()), who);
      assertThat(role, r.getStatus(), is(403));
      r.close();
    }

    assertThat("nothing of ours was written", written(T), is(ours));
    assertThat("nor of theirs", written(RIVAL), is(theirs));

    // Their owner, naming their own store, imports into their own catalogue.
    String name = "Sencha " + Ids.newId();
    body(
        send(
            "POST",
            "/admin/import",
            importRows(RIVAL_STORE, name),
            new Staff(RIVAL, "OWNER", null)),
        200);
    assertThat(rows(NAMED, RIVAL, name), is(1));
    assertThat(rows(NAMED, T, name), is(0));
    assertThat("nothing of ours was written", written(T), is(ours));
  }

  // ── the range is judged with the product row locked (2 Oct 2026) ──────────────

  /** As {@link #send}, answered later: for a request that has to wait for a lock. */
  private java.util.concurrent.Future<Response> sendLater(
      String method, String path, String json, Staff who) {
    Invocation.Builder b =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", who.tenant())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", who.roles());
    if (who.storeIds() != null) b = b.header("X-Store-Ids", who.storeIds());
    return b.async().method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("A range change waits for the product row, and is judged on the range it then finds")
  void aRangeChangeIsJudgedOnTheRangeItFindsUnderTheLock() throws Exception {
    String p = product();
    body(range(p, OWNER, STORE_B), 200);

    try (Connection other =
        DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      other.setAutoCommit(false);
      // Another change to the same product, under way: its row locked as a range change locks it
      // (FOR NO KEY UPDATE), and the product put back on every shelf, not yet committed.
      try (PreparedStatement lock =
          other.prepareStatement(
              "SELECT id FROM product.products WHERE tenant_id = ?::uuid AND id = ?::uuid"
                  + " FOR NO KEY UPDATE")) {
        lock.setString(1, T);
        lock.setString(2, p);
        lock.executeQuery().close();
      }
      try (PreparedStatement everywhere =
          other.prepareStatement(
              "DELETE FROM product.product_stores WHERE tenant_id = ?::uuid"
                  + " AND product_id = ?::uuid")) {
        everywhere.setString(1, T);
        everywhere.setString(2, p);
        everywhere.executeUpdate();
      }

      // The held manager adds their store beside B: theirs to do on the range read before the
      // other change commits, and not on the range it leaves.
      var pending =
          sendLater("PUT", "/admin/products/" + p + "/stores", stores(STORE_B, STORE_A), HELD_TO_A);
      Thread.sleep(750);
      assertThat("it waits for the product row", pending.isDone(), is(false));
      // A variant added to the product meanwhile does not wait: its foreign-key check takes a key
      // share of the row, which the range lock leaves free (2 Oct 2026; under FOR UPDATE it
      // waited).
      var variant =
          sendLater(
              "POST",
              "/admin/products/" + p + "/variants",
              "{\"sku\":\"NK-" + Ids.newId() + "\"}",
              OWNER);
      assertThat(
          "the variant went in while the range was locked",
          variant.get(10, java.util.concurrent.TimeUnit.SECONDS).getStatus(),
          is(201));
      assertThat("and the range change still waits", pending.isDone(), is(false));
      other.commit();

      assertRefused(
          pending.get(30, java.util.concurrent.TimeUnit.SECONDS), 403, "BUSINESS_WIDE_ONLY");
    }
    assertThat("still sold at every store, as the other change left it", rows(RANGE, T, p), is(0));
  }

  @Test
  @DisplayName(
      "A held manager's new variant waits for a range change under way, and is judged on what it leaves")
  void aHeldManagersVariantWaitsForTheRangeAndIsJudgedOnIt() throws Exception {
    // Their own line: ranged to their store alone.
    String p = product();
    body(range(p, OWNER, STORE_A), 200);
    String sku = "HW-" + Ids.newId();

    try (Connection other =
        DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      other.setAutoCommit(false);
      // The owner is putting it on every shelf: the row locked, the range emptied, not committed.
      try (PreparedStatement lock =
          other.prepareStatement(
              "SELECT id FROM product.products WHERE tenant_id = ?::uuid AND id = ?::uuid"
                  + " FOR NO KEY UPDATE")) {
        lock.setString(1, T);
        lock.setString(2, p);
        lock.executeQuery().close();
      }
      try (PreparedStatement everywhere =
          other.prepareStatement(
              "DELETE FROM product.product_stores WHERE tenant_id = ?::uuid"
                  + " AND product_id = ?::uuid")) {
        everywhere.setString(1, T);
        everywhere.setString(2, p);
        everywhere.executeUpdate();
      }

      // Theirs to add to on the range committed so far; judged under the lock, it must wait.
      var pending =
          sendLater(
              "POST", "/admin/products/" + p + "/variants", "{\"sku\":\"" + sku + "\"}", HELD_TO_A);
      Thread.sleep(750);
      assertThat("it waits for the product row", pending.isDone(), is(false));
      other.commit();

      assertRefused(
          pending.get(30, java.util.concurrent.TimeUnit.SECONDS), 403, "BUSINESS_WIDE_ONLY");
    }
    assertThat("no SKU was taken", rows(VARIANT_OF_SKU, T, sku), is(0));
  }

  // ── a new product of a manager held to stores (2 Oct 2026) ─────────────────────

  /** A new product's body, sold at the stores named, or at every store for none. */
  private static String newProduct(String name, String... storeIds) {
    String stores =
        storeIds.length == 0
            ? ""
            : "," + stores(storeIds).substring(1, stores(storeIds).length() - 1);
    return "{\"name\":\"" + name + "\",\"sellableOnline\":false" + stores + "}";
  }

  @Test
  @DisplayName("A held manager's new product is sold at their stores, never at every store")
  void aHeldManagersNewProductIsSoldAtTheirStores() {
    List<Integer> before = written(T);
    String name = "Held " + Ids.newId();

    // A store of the business they do not keep, alone or beside theirs.
    assertRefused(
        send("POST", "/admin/products", newProduct(name, STORE_B), HELD_TO_A),
        403,
        "STORE_ACCESS_DENIED");
    assertRefused(
        send("POST", "/admin/products", newProduct(name, STORE_A, STORE_B), HELD_TO_A),
        403,
        "STORE_ACCESS_DENIED");
    assertRefused(
        send("POST", "/admin/products", newProduct(name, RIVAL_STORE), HELD_TO_A),
        404,
        "PRODUCT_STORE_NOT_FOUND");
    assertRefused(
        send("POST", "/admin/products", newProduct(name, "not-an-id"), HELD_TO_A),
        400,
        "INVALID_UUID");
    assertThat("nothing was written", written(T), is(before));
    assertThat(rows(NAMED, T, name), is(0));

    // Their own store, named twice: created, and sold there alone.
    String p =
        id(
            body(
                send("POST", "/admin/products", newProduct(name, STORE_A, STORE_A), HELD_TO_A),
                201));
    assertThat(rows(RANGE, T, p), is(1));
    assertThat(rows(RANGED_AT, T, p, STORE_A), is(1));

    // None named, as the admin app's product form sends it: created, and sold at the stores they
    // keep — never at every store, on shelves they do not.
    String bare = "Bare " + Ids.newId();
    String q = id(body(send("POST", "/admin/products", newProduct(bare), HELD_TO_A), 201));
    assertThat(rows(RANGE, T, q), is(1));
    assertThat(rows(RANGED_AT, T, q, STORE_A), is(1));

    // Held to two stores: sold at both.
    String two = "Two held " + Ids.newId();
    String r =
        id(
            body(
                send(
                    "POST",
                    "/admin/products",
                    newProduct(two),
                    new Staff(T, "MANAGER", STORE_A + "," + STORE_C)),
                201));
    assertThat(rows(RANGE, T, r), is(2));
    assertThat(rows(RANGED_AT, T, r, STORE_A), is(1));
    assertThat(rows(RANGED_AT, T, r, STORE_C), is(1));
  }

  @Test
  @DisplayName("An owner or a manager of the whole business creates one sold everywhere")
  void aCallerHeldToNoStoreCreatesOneSoldEverywhere() {
    Staff wholeBusiness = new Staff(T, "MANAGER", null);

    for (Staff who : List.of(OWNER, wholeBusiness)) {
      String p =
          id(body(send("POST", "/admin/products", newProduct("Open " + Ids.newId()), who), 201));
      assertThat("sold at every store", rows(RANGE, T, p), is(0));
    }
    String ranged =
        id(
            body(
                send(
                    "POST",
                    "/admin/products",
                    newProduct("Two " + Ids.newId(), STORE_B, STORE_C),
                    OWNER),
                201));
    assertThat(rows(RANGE, T, ranged), is(2));
  }

  @Test
  @DisplayName(
      "Another business naming our store in a new product creates nothing, whatever its role")
  void anotherBusinessNamingOurStoreInANewProductCreatesNothing() {
    List<Integer> ours = written(T);
    List<Integer> theirs = written(RIVAL);

    for (Staff who :
        List.of(
            new Staff(RIVAL, "OWNER", null),
            new Staff(RIVAL, "MANAGER", null),
            // Their manager, sending our store as though they were held to it.
            new Staff(RIVAL, "MANAGER", STORE_A),
            new Staff(RIVAL, "MANAGER", RIVAL_STORE))) {
      assertRefused(
          send("POST", "/admin/products", newProduct("Ours " + Ids.newId(), STORE_A), who),
          404,
          "PRODUCT_STORE_NOT_FOUND");
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      Staff who = new Staff(RIVAL, role, "CUSTOMER".equals(role) ? null : STORE_A);
      Response r = send("POST", "/admin/products", newProduct("Ours " + Ids.newId(), STORE_A), who);
      assertThat(role, r.getStatus(), is(403));
      r.close();
    }

    assertThat("nothing of ours was written", written(T), is(ours));
    assertThat("nor of theirs", written(RIVAL), is(theirs));
  }

  // ── a held manager's import, row by row (2 Oct 2026) ───────────────────────────

  @Test
  @DisplayName("A held manager's imported new product naming no store is sold at their stores")
  void aHeldManagersImportedNewProductIsSoldAtTheirStores() {
    String bare = "Bare " + Ids.newId();
    String own = "Own " + Ids.newId();

    // The first row names no store, the last names theirs.
    String answer =
        body(send("POST", "/admin/import", importRows(STORE_A, bare, own), HELD_TO_A), 200);

    assertThat(answer, containsString("\"productsCreated\":2"));
    assertThat(answer, containsString("\"errors\":[]"));
    String bareId = productNamed(T, bare);
    assertThat("the row naming no store is sold at theirs", rows(RANGE, T, bareId), is(1));
    assertThat(rows(RANGED_AT, T, bareId, STORE_A), is(1));
    assertThat(rows(RANGED_AT, T, productNamed(T, own), STORE_A), is(1));
  }

  /** The id of the business's product of that name. */
  private static String productNamed(String tenant, String name) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT id FROM product.products WHERE tenant_id = ?::uuid AND name = ?")) {
      ps.setString(1, tenant);
      ps.setString(2, name);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  private static String replaceRow(String name, String store, String sku) {
    return "{\"mode\":\"REPLACE\",\"products\":[{\"name\":\""
        + name
        + "\",\"sellableOnline\":false"
        + (store == null ? "" : ",\"storeIds\":[\"" + store + "\"]")
        + ",\"variants\":[{\"sku\":\""
        + sku
        + "\"}]}]}";
  }

  private static final String VARIANT_OF_SKU =
      "SELECT count(*) FROM product.product_variants WHERE tenant_id = ?::uuid AND sku = ?";

  /**
   * REPLACE exists so a sheet sent twice changes the catalogue rather than duplicating it: a row
   * naming a product already there (same name and category) is that product. The look-up read the
   * product without two of its columns and failed, so every such row was refused.
   */
  @Test
  @DisplayName("An owner's REPLACE reuses the product already there and adds its variant")
  void anOwnersReplaceReusesTheProductAlreadyThere() {
    String name = "Again " + Ids.newId();
    body(send("POST", "/admin/import", importRows(null, name), OWNER), 200);
    String p = productNamed(T, name);
    String sku = "RO-" + Ids.newId();

    String answer = body(send("POST", "/admin/import", replaceRow(name, null, sku), OWNER), 200);

    assertThat(answer, containsString("\"errors\":[]"));
    assertThat(answer, containsString("\"productsCreated\":0"));
    assertThat("one product of that name, the one already there", rows(NAMED, T, name), is(1));
    assertThat(productNamed(T, name), is(p));
    assertThat(rows(VARIANT_OF_SKU, T, sku), is(1));
  }

  @Test
  @DisplayName("A held manager's REPLACE never narrows a product sold everywhere to their store")
  void aHeldManagersReplaceNeverNarrowsEverywhere() {
    String name = "Everywhere " + Ids.newId();
    body(send("POST", "/admin/import", importRows(null, name), OWNER), 200);
    String p = productNamed(T, name);
    String sku = "RP-" + Ids.newId();

    String answer =
        body(send("POST", "/admin/import", replaceRow(name, STORE_A, sku), HELD_TO_A), 200);

    assertThat(answer, containsString("BUSINESS_WIDE_ONLY"));
    assertThat("still sold at every store", rows(RANGE, T, p), is(0));
    assertThat("and the row's variant was not written", rows(VARIANT_OF_SKU, T, sku), is(0));

    // Ranged to B by the owner it is sold beyond their stores: its variants are not theirs to add
    // either (3 Oct 2026), with a store named or none.
    body(range(p, OWNER, STORE_B), 200);
    String beyond =
        body(send("POST", "/admin/import", replaceRow(name, STORE_A, sku), HELD_TO_A), 200);
    assertThat(beyond, containsString("BUSINESS_WIDE_ONLY"));
    assertThat(rows(RANGE, T, p), is(1));
    assertThat(rows(VARIANT_OF_SKU, T, sku), is(0));
    // Ranged to their store alone it is theirs, and a row names it or not.
    body(range(p, OWNER, STORE_A), 200);
    String mine = body(send("POST", "/admin/import", replaceRow(name, null, sku), HELD_TO_A), 200);
    assertThat(mine, containsString("\"errors\":[]"));
    assertThat(rows(VARIANT_OF_SKU, T, sku), is(1));
  }

  @Test
  @DisplayName("Another business's REPLACE naming our product's name touches nothing of ours")
  void anotherBusinesssReplaceTouchesNothingOfOurs() {
    String name = "Shared name " + Ids.newId();
    body(send("POST", "/admin/import", importRows(null, name), OWNER), 200);
    String p = productNamed(T, name);
    List<Integer> ours = written(T);

    for (Staff who :
        List.of(
            new Staff(RIVAL, "OWNER", null),
            new Staff(RIVAL, "MANAGER", STORE_A),
            new Staff(RIVAL, "MANAGER", RIVAL_STORE))) {
      // Naming our store: not one of theirs, so not found, and nothing written.
      assertRefused(
          send("POST", "/admin/import", replaceRow(name, STORE_A, "RV-" + Ids.newId()), who),
          404,
          "PRODUCT_STORE_NOT_FOUND");
    }
    assertThat("our product's range is as it was", rows(RANGE, T, p), is(0));
    assertThat("nothing of ours was written", written(T), is(ours));
  }
}
