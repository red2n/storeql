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
}
