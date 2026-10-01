package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Range: clusters, dated changes and the review behind them (07.18), over HTTP and a real database.
 *
 * <p>The assertion this class exists for is the one about intent and state: <b>a change recorded
 * for a future day does nothing until it is applied, and then it is exactly what the live range
 * says.</b> That cannot be had from a unit test, because the live range is {@code product_stores}
 * and the decision log is a second table, and the whole design rests on the two agreeing only when
 * a sweep has run.
 *
 * <p>The refusals are the other half. A de-list against a line that is ranged everywhere cannot be
 * expressed at all under the convention that no rows means every store, and it must fail loudly and
 * stay due rather than be applied as a silent no-op.
 */
@HelidonTest
class AssortmentIT {

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();

  /**
   * A sweep applies everything the business has due, so a test that counts what a sweep did needs a
   * business of its own. Sharing one tenant would make each count depend on which test ran first.
   */
  private static final String T_SWEEP = Ids.newId().toString();

  private static final String T_CLUSTER = Ids.newId().toString();
  private static final String T_DELIST = Ids.newId().toString();
  private static final String T_EMPTY = Ids.newId().toString();
  private static final String T_TICK = Ids.newId().toString();
  private static final String STORE_A = Ids.newId().toString();
  private static final String STORE_B = Ids.newId().toString();
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
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .with(RIVAL, "GBP", "GB")
        .with(T_SWEEP, "GBP", "GB")
        .with(T_CLUSTER, "GBP", "GB")
        .with(T_DELIST, "GBP", "GB")
        .with(T_EMPTY, "GBP", "GB")
        .with(T_TICK, "GBP", "GB");
  }

  @Inject WebTarget target;
  @Inject com.storeql.product.messaging.AssortmentSweeper sweeper;

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

  private Response get(String path, String query, String value, String tenant) {
    var t = target.path(path);
    if (query != null) t = t.queryParam(query, value);
    return t.request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .get();
  }

  private static String body(Response r, int expected) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(expected));
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

  private String product(String tenant, String categoryId) {
    return id(
        post(
            "/admin/products",
            "{\"name\":\"Line "
                + Ids.newId()
                + "\""
                + (categoryId == null ? "" : ",\"categoryId\":\"" + categoryId + "\"")
                + "}",
            tenant));
  }

  private String variant(String tenant, String productId) {
    return id(
        post(
            "/admin/products/" + productId + "/variants",
            "{\"sku\":\"S-" + Ids.newId() + "\"}",
            tenant));
  }

  private String cluster(String tenant, String... stores) {
    String id =
        id(
            post(
                "/admin/assortment/clusters",
                "{\"code\":\"C-" + Ids.newId() + "\",\"name\":\"Scotland\"}",
                tenant));
    if (stores.length > 0) {
      StringBuilder json = new StringBuilder("{\"storeIds\":[");
      for (int i = 0; i < stores.length; i++) {
        if (i > 0) json.append(',');
        json.append('"').append(stores[i]).append('"');
      }
      body(post("/admin/assortment/clusters/" + id + "/stores", json + "]}", tenant), 200);
    }
    return id;
  }

  /** A sweep as of a named day, or today when the day is null. */
  private String sweep(String tenant, String asOf) {
    var t = target.path("/admin/assortment/changes/apply");
    if (asOf != null) t = t.queryParam("asOf", asOf);
    return body(
        t.request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", "OWNER")
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON)),
        200);
  }

  /** The live range as the catalogue holds it — the state the log is in front of. */
  private String liveRange(String productId, String tenant) {
    return body(get("/admin/products/" + productId + "/stores", null, null, tenant), 200);
  }

  private String recordChange(
      String tenant, String productId, String target, boolean cluster, String action, String day) {
    return id(
        post(
            "/admin/assortment/changes",
            "{\"productId\":\""
                + productId
                + "\",\""
                + (cluster ? "clusterId" : "storeId")
                + "\":\""
                + target
                + "\",\"action\":\""
                + action
                + "\",\"effectiveFrom\":\""
                + day
                + "\",\"reason\":\"Range review\"}",
            tenant));
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A change is intent until it is applied, and then it is the live range")
  void intentThenState() {
    String p = product(T_SWEEP, null);
    // Relative to today, so the test never expires: the change is due 400 days from now.
    LocalDate due = LocalDate.now().plusDays(400);
    recordChange(T_SWEEP, p, STORE_A, false, "LIST", due.toString());

    // Nothing has moved: the line is still unrestricted, which is what no rows means.
    assertThat(liveRange(p, T_SWEEP), not(containsString(STORE_A)));

    // Not due yet, either: the day is what decides, not the recording.
    assertThat(sweep(T_SWEEP, null), containsString("\"applied\":0"));

    assertThat(sweep(T_SWEEP, due.toString()), containsString("\"applied\":1"));
    assertThat(liveRange(p, T_SWEEP), containsString(STORE_A));

    // Applied once and only once: the second sweep finds nothing, which is what stops a de-list
    // somebody did by hand from being undone by a change that keeps re-applying itself.
    assertThat(sweep(T_SWEEP, due.plusDays(90).toString()), containsString("\"applied\":0"));
  }

  @Test
  @DisplayName("A cluster's membership is read on the day the change is applied")
  void clusterMembershipIsRead() {
    // "All the Scottish shops" must keep meaning that when a shop opens. A membership frozen at
    // decision time would quietly leave the new shop out.
    String p = product(T_CLUSTER, null);
    String c = cluster(T_CLUSTER, STORE_A);
    recordChange(T_CLUSTER, p, c, true, "LIST", "2026-01-01");
    body(
        post(
            "/admin/assortment/clusters/" + c + "/stores",
            "{\"storeIds\":[\"" + STORE_B + "\"]}",
            T_CLUSTER),
        200);

    assertThat(sweep(T_CLUSTER, null), containsString("\"applied\":1"));
    String range = liveRange(p, T_CLUSTER);
    assertThat(range, containsString(STORE_A));
    assertThat("the shop that joined after the decision", range, containsString(STORE_B));
  }

  @Test
  @DisplayName("A de-list against a line ranged everywhere is refused, and stays due")
  void cannotDelistWhatIsEverywhere() {
    // No rows means every store, so there is no way to take the line out of one store without first
    // saying which stores keep it — and product-svc does not own the list of stores to fill in.
    String p = product(T_DELIST, null);
    String change = recordChange(T_DELIST, p, STORE_A, false, "DELIST", "2026-01-01");

    String swept = sweep(T_DELIST, null);
    assertThat(swept, containsString("\"applied\":0"));
    assertThat(swept, containsString("ASSORTMENT_RANGED_EVERYWHERE"));
    assertThat(swept, containsString(change));

    // Still due, so fixing the cause is enough: nothing has to be re-entered.
    assertThat(
        body(get("/admin/assortment/changes/due", null, null, T_DELIST), 200),
        containsString(change));

    // Give the line a range. Within one sweep the de-list is tried first — same day, recorded
    // earlier — and is still refused at that moment, because the range it needs does not exist yet.
    recordChange(T_DELIST, p, STORE_B, false, "LIST", "2026-01-01");
    String second = sweep(T_DELIST, null);
    assertThat(second, containsString("\"applied\":1"));
    assertThat(
        "the de-list is still ahead of the list that enables it", second, containsString(change));

    // The next sweep applies it, because now the line has a range to be taken out of.
    String third = sweep(T_DELIST, null);
    assertThat(third, containsString("\"applied\":1"));
    String range = liveRange(p, T_DELIST);
    assertThat(range, containsString(STORE_B));
    assertThat("and gone from the store it was dropped in", range, not(containsString(STORE_A)));
  }

  @Test
  @DisplayName("A change aimed at an empty cluster is refused rather than applied as a no-op")
  void emptyClusterIsRefused() {
    String p = product(T_EMPTY, null);
    String c = cluster(T_EMPTY);
    String change = recordChange(T_EMPTY, p, c, true, "LIST", "2026-01-01");
    String swept = sweep(T_EMPTY, null);
    assertThat(swept, containsString("CLUSTER_EMPTY"));
    assertThat("and it is still waiting", swept, containsString(change));
  }

  @Test
  @DisplayName("Neither target, or both, is a bad request")
  void oneTargetOnly() {
    String p = product(T, null);
    String neither =
        body(
            post(
                "/admin/assortment/changes",
                "{\"productId\":\"" + p + "\",\"action\":\"LIST\",\"reason\":\"why\"}",
                T),
            400);
    assertThat(neither, containsString("ASSORTMENT_TARGET_REQUIRED"));

    String c = cluster(T, STORE_A);
    String both =
        body(
            post(
                "/admin/assortment/changes",
                "{\"productId\":\""
                    + p
                    + "\",\"storeId\":\""
                    + STORE_A
                    + "\",\"clusterId\":\""
                    + c
                    + "\",\"action\":\"LIST\",\"reason\":\"why\"}",
                T),
            400);
    assertThat(both, containsString("ASSORTMENT_TARGET_REQUIRED"));
  }

  @Test
  @DisplayName("A change with no reason is refused, which is what the log is for")
  void reasonIsRequired() {
    String p = product(T, null);
    String refused =
        body(
            post(
                "/admin/assortment/changes",
                "{\"productId\":\""
                    + p
                    + "\",\"storeId\":\""
                    + STORE_A
                    + "\",\"action\":\"LIST\",\"reason\":\"  \"}",
                T),
            400);
    assertThat(refused, containsString("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("An unknown action is refused before it reaches the table")
  void unknownAction() {
    String p = product(T, null);
    assertThat(
        body(
            post(
                "/admin/assortment/changes",
                "{\"productId\":\""
                    + p
                    + "\",\"storeId\":\""
                    + STORE_A
                    + "\",\"action\":\"MAYBE\",\"reason\":\"why\"}",
                T),
            400),
        containsString("ASSORTMENT_ACTION_UNKNOWN"));
  }

  @Test
  @DisplayName("Two active clusters cannot share a code")
  void clusterCodesAreUnique() {
    String code = "C-" + Ids.newId();
    body(
        post("/admin/assortment/clusters", "{\"code\":\"" + code + "\",\"name\":\"North\"}", T),
        201);
    assertThat(
        body(
            post(
                "/admin/assortment/clusters",
                "{\"code\":\"" + code + "\",\"name\":\"North again\"}",
                T),
            409),
        containsString("CLUSTER_CODE_TAKEN"));
    // ... but another business may use the same code, because a cluster is that business's own.
    body(
        post("/admin/assortment/clusters", "{\"code\":\"" + code + "\",\"name\":\"North\"}", RIVAL),
        201);
  }

  // ── range review ───────────────────────────────────────────────────────────

  private String openReview(String tenant, String categoryId) {
    return id(
        post(
            "/admin/assortment/reviews",
            "{\"categoryId\":\""
                + categoryId
                + "\",\"name\":\"H2 review\",\"periodFrom\":\"2026-01-01\",\"periodTo\":\"2026-07-01\"}",
            tenant));
  }

  private String addLine(
      String tenant, String review, String variantId, boolean ownBrand, int rank) {
    return body(
        post(
            "/admin/assortment/reviews/" + review + "/lines",
            "{\"lines\":[{\"variantId\":\""
                + variantId
                + "\",\"unitsSold\":120.5,\"revenue\":340.00,\"margin\":70.00,\"currency\":\"GBP\","
                + "\"rankInCategory\":"
                + rank
                + ",\"ownBrand\":"
                + ownBrand
                + "}]}",
            tenant),
        200);
  }

  private String decide(
      String tenant, String review, String variantId, String decision, String note) {
    return post(
            "/admin/assortment/reviews/" + review + "/decisions",
            "{\"variantId\":\""
                + variantId
                + "\",\"decision\":\""
                + decision
                + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"")
                + "}",
            tenant)
        .readEntity(String.class);
  }

  @Test
  @DisplayName("A closed review produces a de-list carrying the figures it was decided on")
  void aReviewProducesChanges() {
    String categoryId = id(post("/admin/categories", "{\"name\":\"Cola " + Ids.newId() + "\"}", T));
    String p = product(T, categoryId);
    String v = variant(T, p);
    String review = openReview(T, categoryId);
    addLine(T, review, v, false, 44);

    // Undecided lines stop it closing: a review closed with lines unread would leave a buyer
    // believing a category was gone through when part of it was not.
    String tooEarly =
        body(
            post(
                "/admin/assortment/reviews/" + review + "/close",
                "{\"storeId\":\"" + STORE_A + "\",\"effectiveFrom\":\"2026-10-01\"}",
                T),
            409);
    assertThat(tooEarly, containsString("REVIEW_LINES_UNDECIDED"));

    decide(T, review, v, "DELIST", null);
    String closed =
        body(
            post(
                "/admin/assortment/reviews/" + review + "/close",
                "{\"storeId\":\"" + STORE_A + "\",\"effectiveFrom\":\"2026-10-01\"}",
                T),
            200);
    assertThat(closed, containsString("\"status\":\"DECIDED\""));
    assertThat(closed, containsString("\"action\":\"DELIST\""));
    // The reason names the review, the period and the rank — the sentence somebody needs a year on.
    assertThat(closed, containsString("H2 review"));
    assertThat(closed, containsString("ranked 44 in category"));
    assertThat("recorded, not applied", closed, not(containsString("\"appliedAt\":\"")));

    // And closing twice is refused.
    assertThat(
        body(
            post(
                "/admin/assortment/reviews/" + review + "/close",
                "{\"storeId\":\"" + STORE_A + "\"}",
                T),
            409),
        containsString("REVIEW_NOT_OPEN"));
  }

  @Test
  @DisplayName("A product keeps its range while one of its variants is kept")
  void aKeptVariantHoldsTheRange() {
    String categoryId =
        id(post("/admin/categories", "{\"name\":\"Juice " + Ids.newId() + "\"}", T));
    String p = product(T, categoryId);
    String keep = variant(T, p);
    String drop = variant(T, p);
    String review = openReview(T, categoryId);
    addLine(T, review, keep, false, 3);
    addLine(T, review, drop, false, 51);
    decide(T, review, keep, "KEEP", null);
    decide(T, review, drop, "DELIST", null);

    String closed =
        body(
            post(
                "/admin/assortment/reviews/" + review + "/close",
                "{\"storeId\":\"" + STORE_A + "\"}",
                T),
            200);
    assertThat("nothing to do to the range", closed, containsString("\"changes\":[]"));
    assertThat(closed, containsString("\"leftAlone\""));
    assertThat(closed, containsString("stays ranged"));
  }

  @Test
  @DisplayName("Dropping an own-brand line asks for a note")
  void ownBrandNeedsANote() {
    String categoryId = id(post("/admin/categories", "{\"name\":\"Own " + Ids.newId() + "\"}", T));
    String p = product(T, categoryId);
    String v = variant(T, p);
    String review = openReview(T, categoryId);
    addLine(T, review, v, true, 40);

    assertThat(decide(T, review, v, "DELIST", null), containsString("REVIEW_OWN_BRAND_NOTE"));
    // With a note it goes through: a buyer may well be right, and this is not a refusal.
    assertThat(
        decide(T, review, v, "DELIST", "Reformulated line replaces it in March"),
        containsString("\"decision\":\"DELIST\""));
  }

  @Test
  @DisplayName("Money without a currency is refused, and so is a period that ends before it starts")
  void figuresMustAddUp() {
    String categoryId = id(post("/admin/categories", "{\"name\":\"Tea " + Ids.newId() + "\"}", T));
    String p = product(T, categoryId);
    String v = variant(T, p);
    String review = openReview(T, categoryId);

    String noCurrency =
        body(
            post(
                "/admin/assortment/reviews/" + review + "/lines",
                "{\"lines\":[{\"variantId\":\"" + v + "\",\"revenue\":12.00,\"ownBrand\":false}]}",
                T),
            400);
    assertThat(noCurrency, containsString("REVIEW_LINE_CURRENCY"));

    String backwards =
        body(
            post(
                "/admin/assortment/reviews",
                "{\"categoryId\":\""
                    + categoryId
                    + "\",\"name\":\"Backwards\",\"periodFrom\":\"2026-07-01\",\"periodTo\":\"2026-01-01\"}",
                T),
            400);
    assertThat(backwards, containsString("REVIEW_PERIOD_INVALID"));
  }

  @Test
  @DisplayName("Another business cannot see or close this one's review")
  void tenantsAreSeparate() {
    String categoryId =
        id(post("/admin/categories", "{\"name\":\"Beans " + Ids.newId() + "\"}", T));
    String review = openReview(T, categoryId);
    assertThat(
        body(get("/admin/assortment/reviews/" + review, null, null, RIVAL), 404),
        containsString("REVIEW_NOT_FOUND"));
    assertThat(
        body(
            post(
                "/admin/assortment/reviews/" + review + "/close",
                "{\"storeId\":\"" + STORE_A + "\"}",
                RIVAL),
            404),
        containsString("REVIEW_NOT_FOUND"));
  }

  @Test
  @DisplayName("A caller with no roles is refused")
  void rolesAreRequired() {
    // No gateway in front of this test, so the request arrives with no roles rather than no token:
    // 403, where a call through the gateway would be 401.
    Response r =
        target
            .path("/admin/assortment/clusters")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", USER)
            .get();
    assertThat(r.readEntity(String.class), r.getStatus(), is(403));
  }

  @Test
  @DisplayName("The sweeper puts a due change into force without anybody pressing a button")
  void theSweeperTicks() {
    // Without this the feature is half a promise: a buyer records a change for the first Monday of
    // the month and somebody still has to remember to apply it that morning. The sweeper finds
    // which
    // businesses have work rather than being told, so a business added after it was written is
    // covered.
    String p = product(T_TICK, null);
    recordChange(T_TICK, p, STORE_A, false, "LIST", LocalDate.now().minusDays(1).toString());
    assertThat(liveRange(p, T_TICK), not(containsString(STORE_A)));

    assertTrue(sweeper.sweepQuietly() >= 1, "the tick applied the change that was due");
    assertThat(liveRange(p, T_TICK), containsString(STORE_A));

    // And a second tick does nothing to it, which is what makes a catch-up run after an outage
    // safe.
    int again = sweeper.sweepQuietly();
    assertThat(liveRange(p, T_TICK), containsString(STORE_A));
    assertEquals(0, again, "nothing was left due for this business");
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

  /** Puts a cluster into the state no endpoint reaches: retired. */
  private static void retire(String clusterId) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE product.store_clusters SET status = 'RETIRED' WHERE id = ?::uuid")) {
      ps.setString(1, clusterId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  private static final String LINES_OF_REVIEW =
      "SELECT count(*) FROM product.range_review_lines WHERE review_id = ?::uuid";
  private static final String REVIEW_STATUS =
      "SELECT count(*) FROM product.range_reviews WHERE id = ?::uuid AND status = ?";

  private String category(String tenant) {
    return id(post("/admin/categories", "{\"name\":\"Cat " + Ids.newId() + "\"}", tenant));
  }

  /** A review of a fresh category with one line (a variant of its own product). */
  private String[] reviewWithALine(String tenant) {
    String categoryId = category(tenant);
    String v = variant(tenant, product(tenant, categoryId));
    String review = openReview(tenant, categoryId);
    addLine(tenant, review, v, false, 5);
    return new String[] {review, v};
  }

  @Test
  @DisplayName("Stores are added only to our own, live cluster, and only as real store ids")
  void storesAreAddedOnlyToOurOwnLiveCluster() {
    String c = cluster(T);
    String path = "/admin/assortment/clusters/" + c + "/stores";
    String members =
        "SELECT count(*) FROM product.store_cluster_members WHERE cluster_id = ?::uuid";

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(
          send("POST", path, "{\"storeIds\":[\"" + STORE_A + "\"]}", RIVAL, role),
          404,
          "CLUSTER_NOT_FOUND");
      assertRefused(
          send("GET", "/admin/assortment/clusters/" + c, null, RIVAL, role),
          404,
          "CLUSTER_NOT_FOUND");
    }
    assertRefused(
        post(
            "/admin/assortment/clusters/" + Ids.newId() + "/stores",
            "{\"storeIds\":[\"" + STORE_A + "\"]}",
            T),
        404,
        "CLUSTER_NOT_FOUND");
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertRefused(
          send("POST", path, "{\"storeIds\":[\"" + STORE_A + "\"]}", T, role), 403, "FORBIDDEN");
    }
    // An empty list is said before the service is asked; a blank or odd id is named.
    assertRefused(post(path, "{\"storeIds\":[]}", T), 400, "VALIDATION_FAILED");
    assertRefused(post(path, "{\"storeIds\":[\"\"]}", T), 400, "ASSORTMENT_ID_REQUIRED");
    assertRefused(post(path, "{\"storeIds\":[\"store-1\"]}", T), 400, "ASSORTMENT_ID_INVALID");
    assertThat("the cluster still has no stores", count(members, c), is(0));

    retire(c);
    assertRefused(post(path, "{\"storeIds\":[\"" + STORE_A + "\"]}", T), 409, "CLUSTER_RETIRED");
    assertThat("a retired cluster took none", count(members, c), is(0));
  }

  @Test
  @DisplayName("A change aimed at another business's cluster, or dated badly, records nothing")
  void aBadChangeRecordsNothing() {
    String p = product(T, null);
    String theirs = product(RIVAL, null);
    String c = cluster(T, STORE_A);
    String recorded = "SELECT count(*) FROM product.assortment_changes WHERE product_id = ?::uuid";

    assertRefused(
        send(
            "POST",
            "/admin/assortment/changes",
            "{\"productId\":\""
                + theirs
                + "\",\"clusterId\":\""
                + c
                + "\",\"action\":\"LIST\",\"reason\":\"why\"}",
            RIVAL,
            "OWNER"),
        404,
        "CLUSTER_NOT_FOUND");
    assertThat(count(recorded, theirs), is(0));

    assertRefused(
        post(
            "/admin/assortment/changes",
            "{\"productId\":\""
                + p
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"action\":\"LIST\",\"effectiveFrom\":\"01/10/2026\",\"reason\":\"why\"}",
            T),
        400,
        "ASSORTMENT_DATE_INVALID");
    assertThat(count(recorded, p), is(0));
    assertRefused(
        get("/admin/assortment/changes/due", "asOf", "yesterday", T),
        400,
        "ASSORTMENT_DATE_INVALID");
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertRefused(
          send(
              "POST",
              "/admin/assortment/changes",
              "{\"productId\":\""
                  + p
                  + "\",\"storeId\":\""
                  + STORE_A
                  + "\",\"action\":\"LIST\",\"reason\":\"why\"}",
              T,
              role),
          403,
          "FORBIDDEN");
    }
    assertThat(count(recorded, p), is(0));
  }

  @Test
  @DisplayName("A line's history needs the line, and the line has to be an id")
  void aLinesHistoryNeedsTheLineAsAnId() {
    assertRefused(get("/admin/assortment/changes", null, null, T), 400, "ASSORTMENT_ID_REQUIRED");
    assertRefused(
        get("/admin/assortment/changes", "product", "not-an-id", T), 400, "ASSORTMENT_ID_INVALID");
    // Another business's line is an empty history, not ours.
    String p = product(T, null);
    String listed = body(get("/admin/assortment/changes", "product", p, RIVAL), 200);
    assertThat(listed, containsString("\"data\":[]"));
  }

  @Test
  @DisplayName("A review is abandoned or closed once; another business's or a cashier's cannot")
  void aReviewEndsOnce() {
    String[] made = reviewWithALine(T);
    String review = made[0];
    String path = "/admin/assortment/reviews/" + review;

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(send("POST", path + "/abandon", "{}", RIVAL, role), 404, "REVIEW_NOT_FOUND");
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertRefused(send("POST", path + "/abandon", "{}", T, role), 403, "FORBIDDEN");
    }
    assertRefused(
        post("/admin/assortment/reviews/" + Ids.newId() + "/abandon", "{}", T),
        404,
        "REVIEW_NOT_FOUND");
    assertThat("still open", count(REVIEW_STATUS, review, "OPEN"), is(1));

    body(post(path + "/abandon", "{}", T), 200);
    assertRefused(post(path + "/abandon", "{}", T), 409, "REVIEW_NOT_OPEN");
    assertRefused(
        post(path + "/close", "{\"storeId\":\"" + STORE_A + "\"}", T), 409, "REVIEW_NOT_OPEN");
    assertRefused(
        post(path + "/decisions", "{\"variantId\":\"" + made[1] + "\",\"decision\":\"KEEP\"}", T),
        409,
        "REVIEW_NOT_OPEN");
    assertThat("still abandoned", count(REVIEW_STATUS, review, "ABANDONED"), is(1));

    // A closed review cannot be abandoned afterwards.
    String[] decided = reviewWithALine(T);
    decide(T, decided[0], decided[1], "KEEP", null);
    body(
        post(
            "/admin/assortment/reviews/" + decided[0] + "/close",
            "{\"storeId\":\"" + STORE_A + "\"}",
            T),
        200);
    assertRefused(
        post("/admin/assortment/reviews/" + decided[0] + "/abandon", "{}", T),
        409,
        "REVIEW_NOT_OPEN");
    assertThat(count(REVIEW_STATUS, decided[0], "DECIDED"), is(1));
  }

  @Test
  @DisplayName("An unknown decision, or a line not under review, leaves the line undecided")
  void aLineIsDecidedOnlyWithAKnownDecisionAndOnlyIfItIsUnderReview() {
    String[] made = reviewWithALine(T);
    String review = made[0];
    String undecided = LINES_OF_REVIEW + " AND decision IS NULL";
    String path = "/admin/assortment/reviews/" + review + "/decisions";

    assertRefused(
        post(path, "{\"variantId\":\"" + made[1] + "\",\"decision\":\"MAYBE\"}", T),
        400,
        "REVIEW_DECISION_UNKNOWN");
    String notAdded = variant(T, product(T, null));
    assertRefused(
        post(path, "{\"variantId\":\"" + notAdded + "\",\"decision\":\"KEEP\"}", T),
        404,
        "REVIEW_LINE_NOT_FOUND");
    assertRefused(
        post(path, "{\"variantId\":\"" + Ids.newId() + "\",\"decision\":\"KEEP\"}", T),
        404,
        "REVIEW_LINE_NOT_FOUND");
    assertThat("the line stays undecided", count(undecided, review), is(1));
    assertThat("and no line was added by asking", count(LINES_OF_REVIEW, review), is(1));
  }

  @Test
  @DisplayName("A review with no lines decides nothing, and stays open")
  void aReviewWithNoLinesDecidesNothing() {
    String review = openReview(T, category(T));
    assertRefused(
        post(
            "/admin/assortment/reviews/" + review + "/close",
            "{\"storeId\":\"" + STORE_A + "\"}",
            T),
        409,
        "REVIEW_EMPTY");
    assertThat("still open", count(REVIEW_STATUS, review, "OPEN"), is(1));
    assertThat(
        "and produced no change",
        count("SELECT count(*) FROM product.assortment_changes WHERE review_id = ?::uuid", review),
        is(0));
  }

  @Test
  @DisplayName("Negative sales or revenue are refused and the review gets no line")
  void negativeFiguresAreRefused() {
    String categoryId = category(T);
    String v = variant(T, product(T, categoryId));
    String review = openReview(T, categoryId);
    String path = "/admin/assortment/reviews/" + review + "/lines";

    assertRefused(
        post(
            path,
            "{\"lines\":[{\"variantId\":\"" + v + "\",\"unitsSold\":-5,\"ownBrand\":false}]}",
            T),
        400,
        "REVIEW_LINE_FIGURES");
    assertRefused(
        post(
            path,
            "{\"lines\":[{\"variantId\":\""
                + v
                + "\",\"revenue\":-1.00,\"currency\":\"GBP\",\"ownBrand\":false}]}",
            T),
        400,
        "REVIEW_LINE_FIGURES");
    assertThat(count(LINES_OF_REVIEW, review), is(0));
    // Another business's review takes no line either.
    assertRefused(
        send(
            "POST",
            path,
            "{\"lines\":[{\"variantId\":\"" + v + "\",\"unitsSold\":1,\"ownBrand\":false}]}",
            RIVAL,
            "OWNER"),
        404,
        "REVIEW_NOT_FOUND");
    assertThat(count(LINES_OF_REVIEW, review), is(0));
  }
}
