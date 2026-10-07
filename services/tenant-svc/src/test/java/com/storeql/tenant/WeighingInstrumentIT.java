package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The weighing-instrument register (Weights and Measures Act 1985 s.11): what is written, how
 * standing is derived from the history, and everything the wrong caller or the wrong shape must be
 * refused.
 */
@HelidonTest
class WeighingInstrumentIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "tenant");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String OWNER = "01a090c9-7777-7000-8000-000000000001";
  private static final String RIVAL_OWNER = "01a090c9-7777-7000-8000-000000000002";

  @Inject WebTarget target;

  private static String tenantA;
  private static String storeA;
  private static String storeB;
  private static String tenantR;

  private String lastPatchBody = "";

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Invocation.Builder as(String path, String tenant, String roles, String stores) {
    var b = target.path(path).request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles).header("X-User-Id", OWNER);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return b;
  }

  /**
   * The JAX-RS client's JDK connector cannot send PATCH, and the reflective workaround is closed by
   * the module system, so status changes go through the JDK HttpClient instead.
   */
  private int patch(String path, String tenant, String roles, String json) {
    try {
      var req =
          java.net.http.HttpRequest.newBuilder(target.getUri().resolve(path))
              .header("Content-Type", MediaType.APPLICATION_JSON)
              .header("X-Tenant-Id", tenant)
              .header("X-Roles", roles)
              .header("X-User-Id", OWNER)
              .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(json))
              .build();
      var res =
          java.net.http.HttpClient.newHttpClient()
              .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
      lastPatchBody = res.body();
      return res.statusCode();
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in: " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  private synchronized void setUpTenants() {
    if (tenantA != null) return;
    tenantA = onboard(OWNER, "Scales Ltd");
    tenantR = onboard(RIVAL_OWNER, "Rival Ltd");
    storeA = addStore(tenantA, "A");
    storeB = addStore(tenantA, "B");
  }

  private String onboard(String owner, String name) {
    Response r =
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", owner)
            .post(
                Entity.entity(
                    "{\"businessName\":\"" + name + "\",\"country\":\"GB\",\"currency\":\"GBP\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return field(body, "id");
  }

  private String addStore(String tenant, String suffix) {
    Response r =
        as("/admin/stores", tenant, "OWNER", null)
            .post(
                Entity.entity(
                    "{\"name\":\"Store "
                        + suffix
                        + "\",\"code\":\"S"
                        + suffix
                        + "-"
                        + Ids.newId()
                        + "\",\"country\":\"GB\",\"timezone\":\"Europe/London\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return field(body, "id");
  }

  private static String scale(String extra) {
    return "{\"identifier\":\"Deli "
        + Ids.newId()
        + "\",\"serialNumber\":\"SN-"
        + Ids.newId()
        + "\",\"make\":\"Avery\",\"model\":\"X\",\"kind\":\"COUNTER\",\"maxCapacity\":15,"
        + "\"capacityUom\":\"KG\",\"scaleInterval\":0.005"
        + extra
        + "}";
  }

  private String base(String store) {
    return "/admin/stores/" + store + "/weighing-instruments";
  }

  private Response create(String store, String tenant, String roles, String stores, String json) {
    return as(base(store), tenant, roles, stores)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response verify(String id, String json) {
    return as(base(storeA) + "/" + id + "/verifications", tenantA, "OWNER", null)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String standing(String id) {
    return field(
        as(base(storeA) + "/" + id, tenantA, "CASHIER", storeA).get().readEntity(String.class),
        "standing");
  }

  private static String entry(
      String kind, String on, String by, boolean passed, String due, String extra) {
    return "{\"kind\":\""
        + kind
        + "\",\"performedOn\":\""
        + on
        + "\",\"performedBy\":\""
        + by
        + "\",\"passed\":"
        + passed
        + (due == null ? "" : ",\"nextDue\":\"" + due + "\"")
        + extra
        + "}";
  }

  @Test
  @DisplayName(
      "Standing is derived from the history: never verified, passed, repaired, re-verified")
  void standingFollowsTheHistory() {
    setUpTenants();
    Response created = create(storeA, tenantA, "OWNER", null, scale(""));
    String body = created.readEntity(String.class);
    assertThat(body, created.getStatus(), is(201));
    String id = field(body, "id");
    assertThat(body, containsString("\"standing\":\"NEVER_VERIFIED\""));
    assertThat(body, containsString("\"certified\":false"));

    assertThat(
        verify(
                id,
                entry(
                    "INITIAL",
                    "2026-01-10",
                    "Trading Standards",
                    true,
                    "2027-01-10",
                    ",\"certificateRef\":\"TS/1\""))
            .getStatus(),
        is(201));
    assertThat(standing(id), is("CERTIFIED"));

    assertThat(
        verify(id, entry("REPAIR", "2026-03-01", "Avery", false, null, "")).getStatus(), is(201));
    assertThat("a repair breaks the stamp", standing(id), is("REPAIRED_SINCE"));

    assertThat(
        verify(
                id,
                entry("RE_VERIFICATION", "2026-03-03", "Trading Standards", true, "2027-03-03", ""))
            .getStatus(),
        is(201));
    assertThat(standing(id), is("CERTIFIED"));

    assertThat(
        verify(id, entry("INSPECTION", "2026-04-01", "Trading Standards", false, null, ""))
            .getStatus(),
        is(201));
    assertThat("a failed inspection takes it out of trade", standing(id), is("FAILED"));

    // An old pass whose due date has gone is overdue, not certified.
    assertThat(
        verify(
                id,
                entry("RE_VERIFICATION", "2026-05-01", "Trading Standards", true, "2026-06-01", ""))
            .getStatus(),
        is(201));
    assertThat(standing(id), is("OVERDUE"));

    // The certified filter is what the till asks.
    String certified =
        target
            .path(base(storeA))
            .queryParam("certified", "true")
            .request()
            .header("X-Tenant-Id", tenantA)
            .header("X-Roles", "CASHIER")
            .header("X-Store-Ids", storeA)
            .get()
            .readEntity(String.class);
    assertThat(certified, not(containsString(id)));
  }

  @Test
  @DisplayName("Status: out of service is never certified, and retirement is final")
  void statusOverridesPaperwork() {
    setUpTenants();
    String id =
        field(create(storeA, tenantA, "OWNER", null, scale("")).readEntity(String.class), "id");
    verify(id, entry("INITIAL", "2026-01-10", "TS", true, null, ""));
    assertThat(standing(id), is("CERTIFIED"));

    String status = base(storeA) + "/" + id + "/status";
    assertThat(patch(status, tenantA, "MANAGER", "{\"status\":\"OUT_OF_SERVICE\"}"), is(200));
    assertThat(standing(id), is("OUT_OF_SERVICE"));

    assertThat(patch(status, tenantA, "OWNER", "{\"status\":\"BROKEN\"}"), is(400));
    assertThat(patch(status, tenantA, "OWNER", "{\"status\":\"RETIRED\"}"), is(200));
    assertThat(patch(status, tenantA, "OWNER", "{\"status\":\"IN_SERVICE\"}"), is(409));
    assertThat(lastPatchBody, containsString("INSTRUMENT_RETIRED"));
    assertThat(
        verify(id, entry("INSPECTION", "2026-04-01", "x", true, null, "")).getStatus(), is(409));
    // A cashier cannot change a status.
    assertThat(patch(status, tenantA, "CASHIER", "{\"status\":\"IN_SERVICE\"}"), is(403));
  }

  @Test
  @DisplayName("The shape is enforced: kinds, dates, a repair is never a pass, schemes are checked")
  void theShapeIsEnforced() {
    setUpTenants();
    assertThat(
        create(
                storeA,
                tenantA,
                "OWNER",
                null,
                scale("").replace("\"kind\":\"COUNTER\"", "\"kind\":\"BATHROOM\""))
            .getStatus(),
        is(400));
    assertThat(
        create(storeA, tenantA, "OWNER", null, "{\"serialNumber\":\"x\"}").getStatus(), is(400));
    String scheme =
        ",\"labelScheme\":\"{\\\"prefixes\\\":[\\\"20\\\"],\\\"itemDigits\\\":5,\\\"valueKind\\\":\\\"PRICE\\\",\\\"valueDecimals\\\":2}\"";
    Response schemeOnCounter = create(storeA, tenantA, "OWNER", null, scale(scheme));
    assertThat(schemeOnCounter.getStatus(), is(400));
    assertThat(
        schemeOnCounter.readEntity(String.class), containsString("INSTRUMENT_SCHEME_INVALID"));
    String labelling =
        scale(scheme.replace("[\\\"20\\\"]", "[\\\"2\\\"]"))
            .replace("\"kind\":\"COUNTER\"", "\"kind\":\"LABELLING\"");
    assertThat(
        "a one-digit prefix",
        create(storeA, tenantA, "OWNER", null, labelling).getStatus(),
        is(400));
    Response ok =
        create(
            storeA,
            tenantA,
            "OWNER",
            null,
            scale(scheme).replace("\"kind\":\"COUNTER\"", "\"kind\":\"LABELLING\""));
    assertThat(ok.getStatus(), is(201));
    String id = field(ok.readEntity(String.class), "id");

    assertThat(
        verify(id, entry("REPAIR", "2026-01-10", "x", true, null, "")).readEntity(String.class),
        containsString("VERIFICATION_REPAIR_NOT_PASS"));
    assertThat(
        verify(id, entry("INITIAL", "2026-01-10", "x", true, "2025-01-01", ""))
            .readEntity(String.class),
        containsString("VERIFICATION_DUE_BEFORE_DONE"));
    assertThat(
        verify(id, entry("INITIAL", "tuesday", "x", true, null, "")).readEntity(String.class),
        containsString("VERIFICATION_DATE_INVALID"));
    assertThat(
        verify(id, entry("BLESSING", "2026-01-10", "x", true, null, "")).readEntity(String.class),
        containsString("VERIFICATION_KIND_UNKNOWN"));
    assertThat(
        verify(id, entry("INITIAL", "2099-01-10", "x", true, null, "")).readEntity(String.class),
        containsString("VERIFICATION_DATE_INVALID"));

    // The same serial number twice in the tenant is a duplicate.
    String serial =
        field(
            as(base(storeA) + "/" + id, tenantA, "OWNER", null).get().readEntity(String.class),
            "serialNumber");
    Response dup =
        create(
            storeB,
            tenantA,
            "OWNER",
            null,
            scale("")
                .replaceFirst(
                    "\"serialNumber\":\"[^\"]+\"", "\"serialNumber\":\"" + serial + "\""));
    assertThat(dup.getStatus(), is(409));
    assertThat(dup.readEntity(String.class), containsString("INSTRUMENT_DUPLICATE"));
  }

  @Test
  @DisplayName(
      "Writes are management-only; reads are staff at the store; other tenants see nothing")
  void theWrongCallerIsRefused() {
    setUpTenants();
    assertThat(
        "a cashier cannot register",
        create(storeA, tenantA, "CASHIER", storeA, scale("")).getStatus(),
        is(403));
    assertThat(
        "a shopper cannot",
        create(storeA, tenantA, "CUSTOMER", null, scale("")).getStatus(),
        is(403));
    assertThat(
        "a rival's owner finds no such store",
        create(storeA, tenantR, "OWNER", null, scale("")).getStatus(),
        is(404));

    String id =
        field(create(storeA, tenantA, "OWNER", null, scale("")).readEntity(String.class), "id");
    assertThat(
        "a cashier at the store reads the register",
        as(base(storeA), tenantA, "CASHIER", storeA).get().getStatus(),
        is(200));
    assertThat(
        "a cashier at another store does not",
        as(base(storeA), tenantA, "CASHIER", storeB).get().getStatus(),
        is(403));
    assertThat(as(base(storeA), tenantR, "OWNER", null).get().getStatus(), is(404));
    assertThat(
        "a cashier cannot verify",
        as(base(storeA) + "/" + id + "/verifications", tenantA, "CASHIER", storeA)
            .post(
                Entity.entity(
                    entry("INITIAL", "2026-01-10", "me", true, null, ""),
                    MediaType.APPLICATION_JSON))
            .getStatus(),
        is(403));
    // An instrument at store A is not reachable through store B's path, even in the same tenant.
    assertThat(as(base(storeB) + "/" + id, tenantA, "OWNER", null).get().getStatus(), is(404));
  }

  @Test
  @DisplayName("The history is append-only: no route changes or removes an entry")
  void historyIsAppendOnly() throws Exception {
    setUpTenants();
    String id =
        field(create(storeA, tenantA, "OWNER", null, scale("")).readEntity(String.class), "id");
    String entryId =
        field(
            verify(id, entry("INITIAL", "2026-01-10", "TS", true, null, ""))
                .readEntity(String.class),
            "id");
    assertThat(
        as(base(storeA) + "/" + id + "/verifications/" + entryId, tenantA, "OWNER", null)
            .delete()
            .getStatus(),
        is(404));
    assertThat(
        as(base(storeA) + "/" + id + "/verifications/" + entryId, tenantA, "OWNER", null)
            .put(Entity.entity("{}", MediaType.APPLICATION_JSON))
            .getStatus(),
        is(404));
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM tenant.weighing_instrument_verifications WHERE instrument_id = ?")) {
      ps.setObject(1, Ids.parse(id));
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getInt(1), is(1));
      }
    }
  }

  @Test
  @DisplayName(
      "An instrument's markings are held to what it keeps: a capacity of nothing broke the table's"
          + " own check and 1E+15 overflowed NUMERIC(18,4), both 500s; now 400 and none is made."
          + " A store's coordinates are on the globe: 1000 overflowed NUMERIC(9,6)")
  void markingsAndCoordinatesAreHeld() {
    setUpTenants();
    for (String bad : java.util.List.of("0", "-15", "1E+15", "15.00001")) {
      Response capacity =
          create(
              storeA,
              tenantA,
              "OWNER",
              null,
              scale("").replace("\"maxCapacity\":15", "\"maxCapacity\":" + bad));
      String body = capacity.readEntity(String.class);
      assertThat(bad + " -> " + body, capacity.getStatus(), is(400));
      assertThat(body, containsString("VALIDATION_FAILED"));
      assertThat(body, containsString("maxCapacity: "));
      Response interval =
          create(
              storeA,
              tenantA,
              "OWNER",
              null,
              scale("").replace("\"scaleInterval\":0.005", "\"scaleInterval\":" + bad));
      String text = interval.readEntity(String.class);
      assertThat(bad + " -> " + text, interval.getStatus(), is(400));
      assertThat(text, containsString("scaleInterval: "));
    }
    String made =
        com.storeql.test.Envelopes.scalar(
            PG,
            "SELECT count(*) FROM tenant.weighing_instruments WHERE tenant_id = '"
                + tenantA
                + "' AND max_capacity <= 0");
    assertThat(made, is("0"));

    String stores = "SELECT count(*) FROM tenant.stores WHERE tenant_id = '" + tenantA + "'";
    String before = com.storeql.test.Envelopes.scalar(PG, stores);
    for (String geo :
        java.util.List.of(
            "\"geoLat\":1000", "\"geoLat\":90.5", "\"geoLng\":-180.5", "\"geoLat\":51.5073511")) {
      Response r =
          as("/admin/stores", tenantA, "OWNER", null)
              .post(
                  Entity.entity(
                      "{\"name\":\"Far\",\"code\":\"F-"
                          + Ids.newId()
                          + "\",\"country\":\"GB\",\"timezone\":\"Europe/London\","
                          + geo
                          + "}",
                      MediaType.APPLICATION_JSON));
      String body = r.readEntity(String.class);
      assertThat(geo + " -> " + body, r.getStatus(), is(400));
      assertThat(body, containsString("VALIDATION_FAILED"));
      assertThat(body, containsString(geo.contains("geoLat") ? "geoLat: " : "geoLng: "));
    }
    assertThat("no store was made", com.storeql.test.Envelopes.scalar(PG, stores), is(before));
  }
}
