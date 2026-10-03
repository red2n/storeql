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

  /** A business whose sweeps re-judge a held manager's change (2 Oct 2026). */
  private static final String T_HELD = Ids.newId().toString();

  /** A business whose plans are recorded in awkward orders, swept apart from the rest. */
  private static final String T_PLAN = Ids.newId().toString();

  private static final String STORE_A = Ids.newId().toString();
  private static final String STORE_B = Ids.newId().toString();

  /** A store of the other business: never any of ours to range, whoever asks. */
  private static final String RIVAL_STORE = Ids.newId().toString();

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
    // Stores are tenant-svc's, and a range names only the business's own (07.18): each business
    // that ranges a store here has it registered, and the rival has one that is not ours.
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .with(RIVAL, "GBP", "GB")
        .with(T_SWEEP, "GBP", "GB")
        .with(T_CLUSTER, "GBP", "GB")
        .with(T_DELIST, "GBP", "GB")
        .with(T_EMPTY, "GBP", "GB")
        .with(T_TICK, "GBP", "GB")
        .with(T_HELD, "GBP", "GB")
        .with(T_PLAN, "GBP", "GB")
        .withStore(T, STORE_A, "GB")
        .withStore(T, STORE_B, "GB")
        .withStore(T_SWEEP, STORE_A, "GB")
        .withStore(T_CLUSTER, STORE_A, "GB")
        .withStore(T_CLUSTER, STORE_B, "GB")
        .withStore(T_DELIST, STORE_A, "GB")
        .withStore(T_DELIST, STORE_B, "GB")
        .withStore(T_TICK, STORE_A, "GB")
        .withStore(T_HELD, STORE_A, "GB")
        .withStore(T_HELD, STORE_B, "GB")
        .withStore(T_PLAN, STORE_A, "GB")
        .withStore(T_PLAN, STORE_B, "GB")
        .withStore(RIVAL, RIVAL_STORE, "GB");
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

  // ── a store id is the business's own, or it is refused (1 Oct 2026 hardening) ──

  private static final String MEMBERS_OF_CLUSTER =
      "SELECT count(*) FROM product.store_cluster_members WHERE cluster_id = ?::uuid";

  @Test
  @DisplayName("A cluster takes only the business's own stores, all or none")
  void aClusterTakesOnlyTheBusinessesOwnStores() {
    String c = cluster(T);
    String path = "/admin/assortment/clusters/" + c + "/stores";
    String neverMade = Ids.newId().toString();

    // Another business's store and a store nobody made are refused alike, so the answer says
    // nothing about who holds an id — and for either role that may range.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(
          send("POST", path, "{\"storeIds\":[\"" + RIVAL_STORE + "\"]}", T, role),
          404,
          "ASSORTMENT_STORE_NOT_FOUND");
      assertRefused(
          send("POST", path, "{\"storeIds\":[\"" + neverMade + "\"]}", T, role),
          404,
          "ASSORTMENT_STORE_NOT_FOUND");
    }
    assertThat("nothing was added", count(MEMBERS_OF_CLUSTER, c), is(0));

    // One store that is not the business's stops the whole list: ours is not added either.
    assertRefused(
        post(path, "{\"storeIds\":[\"" + STORE_A + "\",\"" + RIVAL_STORE + "\"]}", T),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertThat("all or none", count(MEMBERS_OF_CLUSTER, c), is(0));

    // The other way round: the rival's staff cannot put our store in their cluster.
    String theirs = cluster(RIVAL);
    assertRefused(
        send(
            "POST",
            "/admin/assortment/clusters/" + theirs + "/stores",
            "{\"storeIds\":[\"" + STORE_A + "\"]}",
            RIVAL,
            "OWNER"),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertThat("the rival's cluster holds none of ours", count(MEMBERS_OF_CLUSTER, theirs), is(0));

    // The business's own stores are added, and a list sent twice is left alone.
    body(post(path, "{\"storeIds\":[\"" + STORE_A + "\",\"" + STORE_B + "\"]}", T), 200);
    assertThat(count(MEMBERS_OF_CLUSTER, c), is(2));
    body(post(path, "{\"storeIds\":[\"" + STORE_A + "\"]}", T), 200);
    assertThat(count(MEMBERS_OF_CLUSTER, c), is(2));
    body(
        send(
            "POST",
            "/admin/assortment/clusters/" + theirs + "/stores",
            "{\"storeIds\":[\"" + RIVAL_STORE + "\"]}",
            RIVAL,
            "OWNER"),
        200);
    assertThat("a business ranges its own store", count(MEMBERS_OF_CLUSTER, theirs), is(1));
  }

  @Test
  @DisplayName("A change or a closing review aimed at a stranger's store records nothing")
  void aChangeAimedAtAStrangersStoreRecordsNothing() {
    String p = product(T, null);
    String recorded = "SELECT count(*) FROM product.assortment_changes WHERE product_id = ?::uuid";

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(
          send(
              "POST",
              "/admin/assortment/changes",
              "{\"productId\":\""
                  + p
                  + "\",\"storeId\":\""
                  + RIVAL_STORE
                  + "\",\"action\":\"LIST\",\"reason\":\"why\"}",
              T,
              role),
          404,
          "ASSORTMENT_STORE_NOT_FOUND");
    }
    assertThat("no change was recorded", count(recorded, p), is(0));

    // The other way round: the rival's staff cannot aim a change at our store.
    String theirs = product(RIVAL, null);
    assertRefused(
        send(
            "POST",
            "/admin/assortment/changes",
            "{\"productId\":\""
                + theirs
                + "\",\"storeId\":\""
                + STORE_A
                + "\",\"action\":\"LIST\",\"reason\":\"why\"}",
            RIVAL,
            "OWNER"),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertThat(count(recorded, theirs), is(0));

    // A closing review: it stays open and produces no change, because its changes would have
    // been aimed at a store that is not the business's.
    String[] made = reviewWithALine(T);
    decide(T, made[0], made[1], "DELIST", null);
    assertRefused(
        post(
            "/admin/assortment/reviews/" + made[0] + "/close",
            "{\"storeId\":\"" + RIVAL_STORE + "\"}",
            T),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertThat("still open", count(REVIEW_STATUS, made[0], "OPEN"), is(1));
    assertThat(
        "and produced no change",
        count("SELECT count(*) FROM product.assortment_changes WHERE review_id = ?::uuid", made[0]),
        is(0));

    // Its own store closes it, which is the other half of the same rule.
    body(
        post(
            "/admin/assortment/reviews/" + made[0] + "/close",
            "{\"storeId\":\"" + STORE_A + "\"}",
            T),
        200);
    assertThat(count(REVIEW_STATUS, made[0], "DECIDED"), is(1));
  }

  /** As {@link #send}, for a caller who is held to the stores named. */
  private Response sendHeld(
      String method, String path, String json, String tenant, String roles, String heldStores) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", roles)
        .header("X-Store-Ids", heldStores)
        .method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName(
      "A manager held to one store ranges that store only: not the business's 404, not theirs 403")
  void aManagerHeldToOneStoreRangesOnlyThatStore() {
    String c = cluster(T);
    String path = "/admin/assortment/clusters/" + c + "/stores";

    // The order every door keeps (2 Oct 2026): the store must be the business's (404), then the
    // caller's (403). Another business's store is not found whoever names it, held or not.
    assertRefused(
        sendHeld("POST", path, "{\"storeIds\":[\"" + RIVAL_STORE + "\"]}", T, "MANAGER", STORE_B),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertRefused(
        sendHeld("POST", path, "{\"storeIds\":[\"" + STORE_A + "\"]}", T, "MANAGER", STORE_B),
        403,
        "STORE_ACCESS_DENIED");
    assertThat("nothing was added", count(MEMBERS_OF_CLUSTER, c), is(0));

    body(
        sendHeld("POST", path, "{\"storeIds\":[\"" + STORE_B + "\"]}", T, "MANAGER", STORE_B), 200);
    assertThat("the store they keep is added", count(MEMBERS_OF_CLUSTER, c), is(1));

    // The same hold, in the same order, for a change and for closing a review, and nothing is
    // recorded. Dated far ahead, so that no sweep in this class finds them due. The line is ranged
    // to STORE_A first: a line sold at every store is not a held manager's to range at all.
    String later = LocalDate.now().plusDays(400).toString();
    String p = product(T, null);
    rangeTo(T, p, STORE_A);
    String recorded = "SELECT count(*) FROM product.assortment_changes WHERE product_id = ?::uuid";
    assertRefused(
        sendHeld(
            "POST",
            "/admin/assortment/changes",
            heldChange(p, RIVAL_STORE, later),
            T,
            "MANAGER",
            STORE_B),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertRefused(
        sendHeld(
            "POST",
            "/admin/assortment/changes",
            heldChange(p, STORE_A, later),
            T,
            "MANAGER",
            STORE_B),
        403,
        "STORE_ACCESS_DENIED");
    assertThat("no change was recorded", count(recorded, p), is(0));
    body(
        sendHeld(
            "POST",
            "/admin/assortment/changes",
            heldChange(p, STORE_B, later),
            T,
            "MANAGER",
            STORE_B),
        201);
    assertThat("the store they keep is ranged", count(recorded, p), is(1));
    assertThat(
        "and the change is marked as a held manager's",
        count(
            "SELECT count(*) FROM product.assortment_changes"
                + " WHERE product_id = ?::uuid AND held_to_stores",
            p),
        is(1));

    // A review whose line is sold at A and B: de-listing it at B leaves A, which is theirs to do.
    String categoryId = category(T);
    String reviewed = product(T, categoryId);
    String v = variant(T, reviewed);
    String review = openReview(T, categoryId);
    addLine(T, review, v, false, 5);
    decide(T, review, v, "DELIST", null);
    rangeTo(T, reviewed, STORE_A, STORE_B);
    String close = "/admin/assortment/reviews/" + review + "/close";
    assertRefused(
        sendHeld("POST", close, "{\"storeId\":\"" + RIVAL_STORE + "\"}", T, "MANAGER", STORE_B),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertRefused(
        sendHeld("POST", close, "{\"storeId\":\"" + STORE_A + "\"}", T, "MANAGER", STORE_B),
        403,
        "STORE_ACCESS_DENIED");
    assertThat("still open", count(REVIEW_STATUS, review, "OPEN"), is(1));
    body(
        sendHeld(
            "POST",
            close,
            "{\"storeId\":\"" + STORE_B + "\",\"effectiveFrom\":\"" + later + "\"}",
            T,
            "MANAGER",
            STORE_B),
        200);
    assertThat("closed at the store they keep", count(REVIEW_STATUS, review, "DECIDED"), is(1));
  }

  /** Ranges a product to the stores named, as the business's owner. */
  private void rangeTo(String tenant, String productId, String... stores) {
    StringBuilder json = new StringBuilder("{\"storeIds\":[");
    for (int i = 0; i < stores.length; i++) {
      if (i > 0) json.append(',');
      json.append('"').append(stores[i]).append('"');
    }
    body(
        send("PUT", "/admin/products/" + productId + "/stores", json + "]}", tenant, "OWNER"), 200);
  }

  // ── a held manager never moves a line to or from every store (2 Oct 2026) ────────

  private static final String CHANGES_OF =
      "SELECT count(*) FROM product.assortment_changes WHERE product_id = ?::uuid";

  @Test
  @DisplayName(
      "A held manager does not aim a change, or a review, at a cluster — even one of theirs")
  void aHeldManagerDoesNotAimAtACluster() {
    String c = cluster(T, STORE_B);
    String p = product(T, null);
    rangeTo(T, p, STORE_A);
    String later = LocalDate.now().plusDays(400).toString();

    // The cluster holds their store alone today; it is read again on the day the change applies.
    for (String action : new String[] {"LIST", "DELIST"}) {
      assertRefused(
          sendHeld(
              "POST",
              "/admin/assortment/changes",
              "{\"productId\":\""
                  + p
                  + "\",\"clusterId\":\""
                  + c
                  + "\",\"action\":\""
                  + action
                  + "\",\"effectiveFrom\":\""
                  + later
                  + "\",\"reason\":\"why\"}",
              T,
              "MANAGER",
              STORE_B),
          403,
          "BUSINESS_WIDE_ONLY");
    }
    assertThat("nothing was recorded", count(CHANGES_OF, p), is(0));

    String[] made = reviewWithALine(T);
    decide(T, made[0], made[1], "KEEP", null);
    assertRefused(
        sendHeld(
            "POST",
            "/admin/assortment/reviews/" + made[0] + "/close",
            "{\"clusterId\":\"" + c + "\"}",
            T,
            "MANAGER",
            STORE_B),
        403,
        "BUSINESS_WIDE_ONLY");
    assertThat("still open", count(REVIEW_STATUS, made[0], "OPEN"), is(1));

    // A manager of the whole business aims at it.
    body(
        send(
            "POST",
            "/admin/assortment/changes",
            "{\"productId\":\""
                + p
                + "\",\"clusterId\":\""
                + c
                + "\",\"action\":\"LIST\",\"effectiveFrom\":\""
                + later
                + "\",\"reason\":\"why\"}",
            T,
            "MANAGER"),
        201);
  }

  @Test
  @DisplayName(
      "A held manager does not list or de-list a line sold everywhere, nor leave it nowhere")
  void aHeldManagerDoesNotMoveALineToOrFromEveryStore() {
    String later = LocalDate.now().plusDays(400).toString();
    String everywhere = product(T, null);
    for (String action : new String[] {"LIST", "DELIST"}) {
      assertRefused(
          sendHeld(
              "POST",
              "/admin/assortment/changes",
              "{\"productId\":\""
                  + everywhere
                  + "\",\"storeId\":\""
                  + STORE_B
                  + "\",\"action\":\""
                  + action
                  + "\",\"effectiveFrom\":\""
                  + later
                  + "\",\"reason\":\"why\"}",
              T,
              "MANAGER",
              STORE_B),
          403,
          "BUSINESS_WIDE_ONLY");
    }
    assertThat("nothing was recorded", count(CHANGES_OF, everywhere), is(0));
    assertThat("still sold at every store", liveRange(everywhere, T), containsString("[]"));

    // Sold at their store alone: de-listing it there would leave no store, which is every store.
    // Nobody can do that, an owner no more than they can, so it is 409 for both (2 Oct 2026; it
    // was 403 BUSINESS_WIDE_ONLY for them, which sent them to someone who could not either).
    String onlyTheirs = product(T, null);
    rangeTo(T, onlyTheirs, STORE_B);
    String lastStore =
        "{\"productId\":\""
            + onlyTheirs
            + "\",\"storeId\":\""
            + STORE_B
            + "\",\"action\":\"DELIST\",\"effectiveFrom\":\""
            + later
            + "\",\"reason\":\"why\"}";
    assertRefused(
        sendHeld("POST", "/admin/assortment/changes", lastStore, T, "MANAGER", STORE_B),
        409,
        "ASSORTMENT_LAST_STORE");
    assertRefused(
        send("POST", "/admin/assortment/changes", lastStore, T, "OWNER"),
        409,
        "ASSORTMENT_LAST_STORE");
    assertThat(count(CHANGES_OF, onlyTheirs), is(0));

    // A closing review that would introduce the everywhere line at their store refuses whole.
    String categoryId = category(T);
    String introduced = product(T, categoryId);
    String v = variant(T, introduced);
    String review = openReview(T, categoryId);
    addLine(T, review, v, false, 1);
    decide(T, review, v, "INTRODUCE", null);
    assertRefused(
        sendHeld(
            "POST",
            "/admin/assortment/reviews/" + review + "/close",
            "{\"storeId\":\"" + STORE_B + "\"}",
            T,
            "MANAGER",
            STORE_B),
        403,
        "BUSINESS_WIDE_ONLY");
    assertThat("the review stays open", count(REVIEW_STATUS, review, "OPEN"), is(1));
    assertThat("and recorded nothing", count(CHANGES_OF, introduced), is(0));
  }

  @Test
  @DisplayName("Another business's staff cannot record a change to our line, even naming our store")
  void anotherBusinessCannotRecordAChangeToOurLine() {
    String ours = product(T, null);
    String later = LocalDate.now().plusDays(400).toString();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String store : new String[] {STORE_A, RIVAL_STORE}) {
        String json = heldChange(ours, store, later);
        assertRefused(
            send("POST", "/admin/assortment/changes", json, RIVAL, role), 404, "PRODUCT_NOT_FOUND");
        // Their manager held to our store, as though it were theirs.
        assertRefused(
            sendHeld("POST", "/admin/assortment/changes", json, RIVAL, role, STORE_A),
            404,
            "PRODUCT_NOT_FOUND");
        assertRefused(
            sendHeld("POST", "/admin/assortment/changes", json, RIVAL, role, RIVAL_STORE),
            404,
            "PRODUCT_NOT_FOUND");
      }
    }
    // A shopper is not staff at all.
    assertRefused(
        send(
            "POST",
            "/admin/assortment/changes",
            heldChange(ours, STORE_A, later),
            RIVAL,
            "CUSTOMER"),
        403,
        "FORBIDDEN");
    // A line nobody made is not found either.
    assertRefused(
        post("/admin/assortment/changes", heldChange(Ids.newId().toString(), STORE_A, later), T),
        404,
        "PRODUCT_NOT_FOUND");
    assertThat("nothing was recorded against our line", count(CHANGES_OF, ours), is(0));
    assertThat("still sold at every store", liveRange(ours, T), containsString("[]"));
  }

  @Test
  @DisplayName("The sweep judges a change on the range it finds, and never leaves a line nowhere")
  void theSweepJudgesTheRangeItFinds() {
    // A held manager lists a line at their store beside another store; before the day, the owner
    // makes it sold everywhere again. Applied as recorded, it would take the line off every shelf
    // but theirs.
    String p = product(T_HELD, null);
    rangeTo(T_HELD, p, STORE_A);
    String change =
        id(
            sendHeld(
                "POST",
                "/admin/assortment/changes",
                heldChange(p, STORE_B, "2026-01-01"),
                T_HELD,
                "MANAGER",
                STORE_B));
    body(
        send("PUT", "/admin/products/" + p + "/stores", "{\"storeIds\":[]}", T_HELD, "OWNER"), 200);
    String swept = sweep(T_HELD, null);
    assertThat(swept, containsString(change));
    assertThat(swept, containsString("BUSINESS_WIDE_ONLY"));
    assertThat("still sold at every store", liveRange(p, T_HELD), containsString("[]"));
    assertThat(
        "and still due",
        body(get("/admin/assortment/changes/due", null, null, T_HELD), 200),
        containsString(change));

    // An owner's de-list of the last store a line is sold at would leave no store — every store.
    // Recorded while the line is sold at two stores; before the day, the other is taken off. No
    // range can make it apply as it reads, so the sweep closes it as refused (2 Oct 2026): it is
    // reported once, kept on the line's history with its reason, and never due again.
    String last = product(T_HELD, null);
    rangeTo(T_HELD, last, STORE_A, STORE_B);
    String delist = recordChange(T_HELD, last, STORE_A, false, "DELIST", "2026-01-01");
    rangeTo(T_HELD, last, STORE_A);
    String second = sweep(T_HELD, null);
    assertThat(second, containsString(delist));
    assertThat(second, containsString("ASSORTMENT_LAST_STORE"));
    assertThat(second, containsString("\"stillDue\":false"));
    assertThat("still sold at STORE_A alone", liveRange(last, T_HELD), containsString(STORE_A));
    assertClosed(T_HELD, last, delist);
  }

  /**
   * A change the sweep refused for good: no longer due, never reported by a later sweep, and on the
   * line's history with the refusal and when it was made — and not applied.
   */
  private void assertClosed(String tenant, String productId, String changeId) {
    assertThat(
        "no longer due",
        body(get("/admin/assortment/changes/due", "asOf", "2099-12-31", tenant), 200),
        not(containsString(changeId)));
    assertThat("never reported again", sweep(tenant, null), not(containsString(changeId)));
    assertThat(
        count(
            "SELECT count(*) FROM product.assortment_changes WHERE id = ?::uuid"
                + " AND refused_at IS NOT NULL AND applied_at IS NULL"
                + " AND refusal_code = 'ASSORTMENT_LAST_STORE'",
            changeId),
        is(1));
    String history = body(get("/admin/assortment/changes", "product", productId, tenant), 200);
    assertThat(history, containsString("\"refusalCode\":\"ASSORTMENT_LAST_STORE\""));
    assertThat(history, containsString("\"refusedAt\""));
  }

  @Test
  @DisplayName("A de-list of a line's last store is refused when recorded, by anyone, not left due")
  void aDelistOfTheLastStoreIsRefusedWhenRecorded() {
    String later = LocalDate.now().plusDays(400).toString();
    String p = product(T, null);
    rangeTo(T, p, STORE_A);
    String delist =
        "{\"productId\":\""
            + p
            + "\",\"storeId\":\""
            + STORE_A
            + "\",\"action\":\"DELIST\",\"effectiveFrom\":\""
            + later
            + "\",\"reason\":\"why\"}";
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      String refused = body(send("POST", "/admin/assortment/changes", delist, T, role), 409);
      assertThat(refused, containsString("ASSORTMENT_LAST_STORE"));
      assertThat(refused, containsString("discontinue the line"));
    }
    assertThat("nothing was recorded, so nothing is due for ever", count(CHANGES_OF, p), is(0));

    // A listing at another store planned for the day before makes it a swap, and it is recorded.
    String before = LocalDate.now().plusDays(399).toString();
    body(
        send(
            "POST",
            "/admin/assortment/changes",
            "{\"productId\":\""
                + p
                + "\",\"storeId\":\""
                + STORE_B
                + "\",\"action\":\"LIST\",\"effectiveFrom\":\""
                + before
                + "\",\"reason\":\"why\"}",
            T,
            "OWNER"),
        201);
    body(send("POST", "/admin/assortment/changes", delist, T, "OWNER"), 201);
    assertThat(count(CHANGES_OF, p), is(2));
  }

  // ── no order of recording leaves a de-list due for ever (2 Oct 2026) ─────────────

  @Test
  @DisplayName("A de-list that would strand one recorded for a later day is refused when recorded")
  void aDelistThatWouldStrandOneRecordedForLaterIsRefused() {
    // Ranged at A and B. De-list A for day 400 first, then de-list B for day 395: on day 395 the
    // line is left at A, so on day 400 the first would leave it nowhere. Far ahead, so no sweep in
    // this class meets either.
    String p = product(T_PLAN, null);
    rangeTo(T_PLAN, p, STORE_A, STORE_B);
    String later = LocalDate.now().plusDays(400).toString();
    String earlier = LocalDate.now().plusDays(395).toString();
    String first = recordChange(T_PLAN, p, STORE_A, false, "DELIST", later);
    String refused =
        body(
            send(
                "POST",
                "/admin/assortment/changes",
                "{\"productId\":\""
                    + p
                    + "\",\"storeId\":\""
                    + STORE_B
                    + "\",\"action\":\"DELIST\",\"effectiveFrom\":\""
                    + earlier
                    + "\",\"reason\":\"why\"}",
                T_PLAN,
                "OWNER"),
            409);
    assertThat(refused, containsString("ASSORTMENT_LAST_STORE"));
    assertThat("names the de-list it would strand", refused, containsString(later));
    assertThat("only the first is recorded", count(CHANGES_OF, p), is(1));
    assertThat(
        body(get("/admin/assortment/changes", "product", p, T_PLAN), 200), containsString(first));
  }

  @Test
  @DisplayName(
      "A listing that gives a waiting de-list its last store stands; the sweep closes the de-list")
  void aListingThatStrandsAWaitingDelistStands() {
    // Sold everywhere: the de-list of A waits for a range. A later listing at A gives it one and
    // is recorded — it is the later decision, and no recorded change can be withdrawn.
    String p = product(T_PLAN, null);
    String delist = recordChange(T_PLAN, p, STORE_A, false, "DELIST", "2026-01-01");
    String list = recordChange(T_PLAN, p, STORE_A, false, "LIST", "2026-01-05");

    String first = sweep(T_PLAN, null);
    assertThat(first, containsString("ASSORTMENT_RANGED_EVERYWHERE"));
    assertThat(liveRange(p, T_PLAN), containsString(STORE_A));
    // The next sweep finds the de-list would take the line out of its last store, and closes it.
    String second = sweep(T_PLAN, null);
    assertThat(second, containsString(delist));
    assertThat(second, containsString("ASSORTMENT_LAST_STORE"));
    assertThat(liveRange(p, T_PLAN), containsString(STORE_A));
    assertClosed(T_PLAN, p, delist);
    assertThat(
        "the listing was applied",
        count(
            "SELECT count(*) FROM product.assortment_changes"
                + " WHERE id = ?::uuid AND applied_at IS NOT NULL",
            list),
        is(1));
  }

  @Test
  @DisplayName(
      "A cluster that grows to every store a line is sold at does not keep its de-list due")
  void aClusterThatGrowsDoesNotKeepItsDelistDue() {
    // Ranged at A and B; the cluster holds A alone, so the de-list leaves B and is recorded.
    String p = product(T_PLAN, null);
    rangeTo(T_PLAN, p, STORE_A, STORE_B);
    String c = cluster(T_PLAN, STORE_A);
    String delist = recordChange(T_PLAN, p, c, true, "DELIST", "2026-01-01");
    // B joins before the day: membership is read on the day, and now holds every store.
    body(
        post(
            "/admin/assortment/clusters/" + c + "/stores",
            "{\"storeIds\":[\"" + STORE_B + "\"]}",
            T_PLAN),
        200);
    String swept = sweep(T_PLAN, null);
    assertThat(swept, containsString(delist));
    assertThat(swept, containsString("ASSORTMENT_LAST_STORE"));
    String range = liveRange(p, T_PLAN);
    assertThat(range, containsString(STORE_A));
    assertThat(range, containsString(STORE_B));
    assertClosed(T_PLAN, p, delist);
  }

  private static String heldChange(String productId, String storeId, String day) {
    return "{\"productId\":\""
        + productId
        + "\",\"storeId\":\""
        + storeId
        + "\",\"action\":\"LIST\",\"effectiveFrom\":\""
        + day
        + "\",\"reason\":\"why\"}";
  }

  // ── a review line's money is in its currency's minor units (2 Oct 2026) ─────────

  private static String lineWithMoney(String variantId, String revenue, String currency) {
    return "{\"lines\":[{\"variantId\":\""
        + variantId
        + "\",\"revenue\":"
        + revenue
        + ",\"currency\":\""
        + currency
        + "\",\"ownBrand\":false}]}";
  }

  @Test
  @DisplayName("A line's money is held to its currency's minor units, and written back at them")
  void aLinesMoneyIsHeldToItsCurrencysMinorUnits() {
    String categoryId = category(T);
    String v = variant(T, product(T, categoryId));
    String review = openReview(T, categoryId);
    String path = "/admin/assortment/reviews/" + review + "/lines";

    // Pounds have two decimals and yen none: more is refused, never rounded into the record.
    assertRefused(post(path, lineWithMoney(v, "12.345", "GBP"), T), 400, "REVIEW_LINE_FIGURES");
    assertRefused(post(path, lineWithMoney(v, "1200.5", "JPY"), T), 400, "REVIEW_LINE_FIGURES");
    // Three letters ISO 4217 does not know are not a currency.
    assertRefused(post(path, lineWithMoney(v, "12.00", "ZZZ"), T), 400, "REVIEW_LINE_CURRENCY");
    assertThat(count(LINES_OF_REVIEW, review), is(0));

    // A dinar keeps its third decimal, which the column used to round away.
    String dinar = body(post(path, lineWithMoney(v, "12.345", "KWD"), T), 200);
    assertThat(dinar, containsString("\"revenue\":\"12.345\""));
    assertThat(
        count(
            "SELECT count(*) FROM product.range_review_lines WHERE review_id = ?::uuid"
                + " AND revenue = 12.345 AND currency = 'KWD'",
            review),
        is(1));

    // Sent again, the line is refreshed: whole yen, written back as whole yen.
    String yen = body(post(path, lineWithMoney(v, "1200", "JPY"), T), 200);
    assertThat(yen, containsString("\"revenue\":\"1200\""));
    assertThat(count(LINES_OF_REVIEW, review), is(1));

    // Another business's staff, its held manager naming our store as theirs, add nothing to it.
    assertRefused(
        send("POST", path, lineWithMoney(v, "1.00", "GBP"), RIVAL, "OWNER"),
        404,
        "REVIEW_NOT_FOUND");
    assertRefused(
        sendHeld("POST", path, lineWithMoney(v, "1.00", "GBP"), RIVAL, "MANAGER", STORE_A),
        404,
        "REVIEW_NOT_FOUND");
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      Response r = send("POST", path, lineWithMoney(v, "1.00", "GBP"), RIVAL, role);
      assertThat(role, r.getStatus(), is(403));
      r.close();
    }
    assertThat(
        "the line is as we left it",
        count(
            "SELECT count(*) FROM product.range_review_lines WHERE review_id = ?::uuid"
                + " AND revenue = 1200 AND currency = 'JPY'",
            review),
        is(1));
  }

  // ── a closing review is judged 400, 404, 403, then its state (2 Oct 2026) ─────────

  @Test
  @DisplayName("A closing review is told what is wrong with the request before its own state")
  void aClosingReviewIsJudgedRequestThenWhatItNamesThenItsState() {
    // Three reviews no close can finish: one with no lines, one with a line undecided, one closed.
    String empty = openReview(T, category(T));
    String undecided = reviewWithALine(T)[0];
    String[] made = reviewWithALine(T);
    decide(T, made[0], made[1], "KEEP", null);
    body(
        post(
            "/admin/assortment/reviews/" + made[0] + "/close",
            "{\"storeId\":\"" + STORE_A + "\"}",
            T),
        200);
    String closed = made[0];

    String[][] cases = {
      {empty, "REVIEW_EMPTY"}, {undecided, "REVIEW_LINES_UNDECIDED"}, {closed, "REVIEW_NOT_OPEN"}
    };
    for (String[] c : cases) {
      String close = "/admin/assortment/reviews/" + c[0] + "/close";
      // 400: neither target, or both.
      assertRefused(post(close, "{}", T), 400, "ASSORTMENT_TARGET_REQUIRED");
      assertRefused(
          sendHeld(
              "POST",
              close,
              "{\"storeId\":\"" + STORE_B + "\",\"clusterId\":\"" + Ids.newId() + "\"}",
              T,
              "MANAGER",
              STORE_B),
          400,
          "ASSORTMENT_TARGET_REQUIRED");
      // 404: another business's store, whoever names it; a cluster nobody made.
      assertRefused(
          post(close, "{\"storeId\":\"" + RIVAL_STORE + "\"}", T),
          404,
          "ASSORTMENT_STORE_NOT_FOUND");
      assertRefused(
          sendHeld("POST", close, "{\"storeId\":\"" + RIVAL_STORE + "\"}", T, "MANAGER", STORE_B),
          404,
          "ASSORTMENT_STORE_NOT_FOUND");
      assertRefused(
          post(close, "{\"clusterId\":\"" + Ids.newId() + "\"}", T), 404, "CLUSTER_NOT_FOUND");
      // 403: the business's store a held manager does not keep.
      assertRefused(
          sendHeld("POST", close, "{\"storeId\":\"" + STORE_A + "\"}", T, "MANAGER", STORE_B),
          403,
          "STORE_ACCESS_DENIED");
      // 409: only then the review's own state, to a caller who may close it there.
      assertRefused(post(close, "{\"storeId\":\"" + STORE_A + "\"}", T), 409, c[1]);
      assertRefused(
          sendHeld("POST", close, "{\"storeId\":\"" + STORE_B + "\"}", T, "MANAGER", STORE_B),
          409,
          c[1]);
    }

    // A review that is not the business's is not found, before the store it names is judged.
    for (String review : new String[] {Ids.newId().toString(), empty}) {
      String close = "/admin/assortment/reviews/" + review + "/close";
      assertRefused(send("POST", close, "{}", RIVAL, "OWNER"), 400, "ASSORTMENT_TARGET_REQUIRED");
      assertRefused(
          send("POST", close, "{\"storeId\":\"" + RIVAL_STORE + "\"}", RIVAL, "OWNER"),
          404,
          "REVIEW_NOT_FOUND");
      assertRefused(
          send("POST", close, "{\"storeId\":\"" + STORE_A + "\"}", RIVAL, "OWNER"),
          404,
          "REVIEW_NOT_FOUND");
    }

    assertThat("still open", count(REVIEW_STATUS, empty, "OPEN"), is(1));
    assertThat("still open", count(REVIEW_STATUS, undecided, "OPEN"), is(1));
    assertThat("closed once", count(REVIEW_STATUS, closed, "DECIDED"), is(1));
    assertThat(
        "and nothing was recorded by the refusals",
        count(
            "SELECT count(*) FROM product.assortment_changes WHERE review_id IN (?::uuid, ?::uuid)",
            empty,
            undecided),
        is(0));
  }

  // ── who can stop a line being sold, as a held manager is told (2 Oct 2026) ─────

  @Test
  @DisplayName(
      "A held manager's last-store refusal names who can discontinue the line, which they cannot")
  void aHeldManagerIsToldWhoCanDiscontinue() {
    String later = LocalDate.now().plusDays(400).toString();
    String p = product(T, null);
    rangeTo(T, p, STORE_A);
    String delist =
        "{\"productId\":\""
            + p
            + "\",\"storeId\":\""
            + STORE_A
            + "\",\"action\":\"DELIST\",\"effectiveFrom\":\""
            + later
            + "\",\"reason\":\"why\"}";

    // Held to the line's one store: told who can stop it being sold, never to do it themselves.
    String held =
        body(sendHeld("POST", "/admin/assortment/changes", delist, T, "MANAGER", STORE_A), 409);
    assertThat(held, containsString("ASSORTMENT_LAST_STORE"));
    assertThat(
        held,
        containsString(
            "to stop selling it, ask an owner or a manager of the whole business to discontinue"
                + " the line"));
    // Because they cannot: discontinuing is the whole business's.
    assertRefused(
        sendHeld("POST", "/admin/products/" + p + "/discontinue", "", T, "MANAGER", STORE_A),
        403,
        "BUSINESS_WIDE_ONLY");
    // Another business's manager, held to our store or theirs, moves nothing of ours either.
    for (String heldTo : new String[] {STORE_A, RIVAL_STORE}) {
      assertRefused(
          sendHeld("POST", "/admin/products/" + p + "/discontinue", "", RIVAL, "MANAGER", heldTo),
          403,
          "BUSINESS_WIDE_ONLY");
    }
    assertRefused(
        send("POST", "/admin/products/" + p + "/discontinue", "", RIVAL, "OWNER"),
        404,
        "PRODUCT_NOT_FOUND");
    assertThat("nothing was recorded", count(CHANGES_OF, p), is(0));
    assertThat(
        body(send("GET", "/admin/products/" + p, null, T, "OWNER"), 200),
        containsString("\"status\":\"ACTIVE\""));

    // A manager of the whole business is told to discontinue it, and does.
    String whole = body(send("POST", "/admin/assortment/changes", delist, T, "MANAGER"), 409);
    assertThat(
        whole,
        containsString(
            "to stop selling it, discontinue the line; to move it, list it at another store"
                + " first"));
    assertThat(
        body(send("POST", "/admin/products/" + p + "/discontinue", "", T, "MANAGER"), 200),
        containsString("\"status\":\"DISCONTINUED\""));
  }
}
