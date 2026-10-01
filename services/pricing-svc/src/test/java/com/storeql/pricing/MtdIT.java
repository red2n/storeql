package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Making Tax Digital (18.5), driven through the resources: a business registers the number it files
 * under, reads its obligations, files a return from its own records, and cannot file the same
 * period twice — with the wrong caller and the wrong input at every step. HMRC is the simulator
 * here; the real provider is pinned against a stub in its own test.
 */
@HelidonTest
class MtdIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String OTHER_T = "01a090ae-611e-701d-9d60-a9d7516ed03b";
  private static final String V = "01a090ae-611e-7037-a4b7-c854f0266ace";
  private static final String S = "01a090ae-611e-703c-a378-a4972ea461c8";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncate() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE pricing.vat_return_submissions, pricing.vat_registrations,"
              + " pricing.tax_transactions, pricing.input_tax_transactions CASCADE");
    }
  }

  /** The stable code of a refused answer, after checking its status. */
  private static String codeOf(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return json(body).getString("code");
  }

  /** A business that files through HMRC itself, with no grant yet. */
  private static void registerWithHmrc(String tenant) throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "INSERT INTO pricing.vat_registrations (tenant_id, vrn, provider) VALUES ('"
              + tenant
              + "', '123456782', 'HMRC')");
    }
  }

  private static JsonObject json(String body) {
    try (JsonReader r = Json.createReader(new StringReader(body))) {
      return r.readObject();
    }
  }

  private Response call(String method, String path, String json, String tenant, String roles) {
    var b = target.path(path).request().header("X-Tenant-Id", tenant);
    if (roles != null) {
      b = b.header("X-Roles", roles);
    }
    b = b.header("X-User-Id", "01a090ae-611e-7055-9838-5de027ce9e0c");
    return switch (method) {
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      case "POST" -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.get();
    };
  }

  private Response get(String path, String tenant, String roles, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) {
      t = t.queryParam(params[i], params[i + 1]);
    }
    var b = t.request().header("X-Tenant-Id", tenant);
    if (roles != null) {
      b = b.header("X-Roles", roles);
    }
    return b.get();
  }

  private void register(String tenant) {
    Response r =
        call(
            "PUT",
            "/vat-return/mtd/registration",
            "{\"vrn\":\"GB 123 4567 82\",\"provider\":\"simulated\"}",
            tenant,
            "OWNER");
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
  }

  private void sale(String tenant, String net, String vat, String taxPoint) {
    Response r =
        call(
            "POST",
            "/tax-transactions",
            "{\"orderId\":\""
                + com.storeql.ids.Ids.newId()
                + "\",\"orderLineId\":\""
                + com.storeql.ids.Ids.newId()
                + "\",\"variantId\":\""
                + V
                + "\",\"storeId\":\""
                + S
                + "\",\"vatCode\":\"T1\",\"vatRate\":0.20,"
                + "\"netAmount\":"
                + net
                + ",\"vatAmount\":"
                + vat
                + ",\"grossAmount\":"
                + new java.math.BigDecimal(net).add(new java.math.BigDecimal(vat)).toPlainString()
                + ",\"exempt\":false,\"taxPointDate\":\""
                + taxPoint
                + "\"}",
            tenant,
            "OWNER");
    assertThat(r.getStatus(), is(201));
  }

  private static String filing(String key, String finalised) {
    return "{\"periodKey\":\""
        + key
        + "\",\"from\":\"2024-04-01T00:00:00Z\",\"to\":\"2024-07-01T00:00:00Z\","
        + "\"finalised\":"
        + finalised
        + ",\"client\":{\"timezone\":\"UTC+01:00\",\"deviceId\":\"dev-1\"}}";
  }

  @Test
  @DisplayName(
      "A business registers the number it files under, and what it cannot register is refused by name")
  void registration() {
    Response before = get("/vat-return/mtd/registration", T, "OWNER");
    assertThat(before.getStatus(), is(200));
    JsonObject offer = json(before.readEntity(String.class)).getJsonObject("data");
    assertThat(offer.getBoolean("registered"), is(false));
    assertThat(offer.getJsonArray("providers").toString(), is("[\"SIMULATED\"]"));
    assertThat(offer.getBoolean("hmrcConfigured"), is(false));

    Response r =
        call(
            "PUT",
            "/vat-return/mtd/registration",
            "{\"vrn\":\"123456783\",\"provider\":\"SIMULATED\"}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("MTD_VRN_INVALID"));
    r =
        call(
            "PUT",
            "/vat-return/mtd/registration",
            "{\"vrn\":\"123456782\",\"provider\":\"SAGE\"}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("MTD_PROVIDER_UNKNOWN"));
    // HMRC needs the application's credentials and a token key; this deployment has neither.
    r =
        call(
            "PUT",
            "/vat-return/mtd/registration",
            "{\"vrn\":\"123456782\",\"provider\":\"HMRC\"}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("MTD_PROVIDER_NOT_CONFIGURED"));
    r =
        call(
            "PUT",
            "/vat-return/mtd/registration",
            "{\"vrn\":\"\",\"provider\":\"SIMULATED\"}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));

    register(T);
    JsonObject after =
        json(get("/vat-return/mtd/registration", T, "OWNER").readEntity(String.class))
            .getJsonObject("data");
    assertThat(after.getBoolean("registered"), is(true));
    assertThat(after.getString("vrn"), is("123456782"));
    assertThat(after.getString("provider"), is("SIMULATED"));
    assertThat(after.getBoolean("connected"), is(false));

    // Filing through a simulator needs no grant, and the grant endpoints say so.
    r =
        get(
            "/vat-return/mtd/hmrc/authorize-url",
            T,
            "OWNER",
            "redirectUri",
            "https://shop.example/cb");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("MTD_NOT_HMRC"));
    r =
        call(
            "POST",
            "/vat-return/mtd/hmrc/connect",
            "{\"code\":\"x\",\"redirectUri\":\"https://shop.example/cb\"}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(409));
  }

  @Test
  @DisplayName("Obligations need a registration, and are the quarters between two dates")
  void obligations() {
    Response r =
        get(
            "/vat-return/mtd/obligations",
            T,
            "OWNER",
            "from",
            "2024-01-01T00:00:00Z",
            "to",
            "2024-12-31T00:00:00Z");
    assertThat(r.getStatus(), is(404));
    assertThat(r.readEntity(String.class), containsString("MTD_NOT_REGISTERED"));
    register(T);
    r =
        get(
            "/vat-return/mtd/obligations",
            T,
            "OWNER",
            "from",
            "2024-01-01T00:00:00Z",
            "to",
            "2024-12-31T00:00:00Z");
    assertThat(r.getStatus(), is(200));
    var arr = json(r.readEntity(String.class)).getJsonArray("data");
    assertThat(arr.size(), is(4));
    assertThat(arr.getJsonObject(1).getString("periodKey"), is("24A2"));
    assertThat(arr.getJsonObject(1).getString("status"), is("O"));
    r =
        get(
            "/vat-return/mtd/obligations",
            T,
            "OWNER",
            "from",
            "2024-12-31T00:00:00Z",
            "to",
            "2024-01-01T00:00:00Z");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PRICING_INVALID_PERIOD"));
    assertThat(
        codeOf(get("/vat-return/mtd/obligations", T, "OWNER", "from", "2024-01-01T00:00:00Z"), 400),
        is("PRICING_MISSING_TO"));
  }

  @Test
  @DisplayName(
      "A return is filed from the business's own records, once, and what came back is kept")
  void filing() {
    register(T);
    sale(T, "100.00", "20.00", "2024-04-15T10:00:00Z");
    sale(T, "50.75", "10.15", "2024-05-15T10:00:00Z");
    // Another tenant's sale in the same period is another tenant's return.
    register(OTHER_T);
    sale(OTHER_T, "999.00", "199.80", "2024-04-15T10:00:00Z");

    Response r = call("POST", "/vat-return/mtd/submissions", filing("24A2", "false"), T, "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("MTD_NOT_FINALISED"));
    r = call("POST", "/vat-return/mtd/submissions", filing("24-B", "true"), T, "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("MTD_PERIOD_KEY_INVALID"));
    r =
        call(
            "POST",
            "/vat-return/mtd/submissions",
            "{\"periodKey\":\"24A2\",\"from\":\"2024-07-01T00:00:00Z\",\"to\":\"2024-04-01T00:00:00Z\",\"finalised\":true}",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PRICING_INVALID_PERIOD"));

    r = call("POST", "/vat-return/mtd/submissions", filing("24A2", "true"), T, "OWNER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    JsonObject filed = json(body).getJsonObject("data");
    assertThat(filed.getString("status"), is("ACCEPTED"));
    assertThat(filed.getString("vrn"), is("123456782"));
    assertThat(filed.getString("periodKey"), is("24A2"));
    // The same figures GET /vat-return shows — and boxes 6 to 9 in whole pounds, as HMRC requires.
    assertThat(filed.getJsonNumber("box1").bigDecimalValue().toPlainString(), is("30.15"));
    assertThat(filed.getJsonNumber("box3").bigDecimalValue().toPlainString(), is("30.15"));
    assertThat(filed.getJsonNumber("box5").bigDecimalValue().toPlainString(), is("30.15"));
    assertThat(filed.getJsonNumber("box6").bigDecimalValue().toPlainString(), is("150"));
    assertThat(filed.getJsonNumber("box7").bigDecimalValue().toPlainString(), is("0"));
    assertThat(filed.getString("formBundleNumber").length(), is(12));
    assertThat(filed.getString("receiptId"), notNullValue());
    assertThat(filed.getString("provider"), is("SIMULATED"));
    String id = filed.getString("id");

    // Once. HMRC refuses the second with DUPLICATE_SUBMISSION; so does this, before the call.
    r = call("POST", "/vat-return/mtd/submissions", filing("24A2", "true"), T, "OWNER");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("MTD_DUPLICATE_SUBMISSION"));

    // The obligation now reads fulfilled.
    var obligations =
        json(get(
                    "/vat-return/mtd/obligations",
                    T,
                    "OWNER",
                    "from",
                    "2024-04-01T00:00:00Z",
                    "to",
                    "2024-06-30T00:00:00Z")
                .readEntity(String.class))
            .getJsonArray("data");
    assertThat(obligations.getJsonObject(0).getString("periodKey"), is("24A2"));
    assertThat(obligations.getJsonObject(0).getString("status"), is("F"));

    // The record: listed, readable by id, and nobody else's.
    var list =
        json(get("/vat-return/mtd/submissions", T, "OWNER").readEntity(String.class))
            .getJsonArray("data");
    assertThat(list.size(), is(1));
    assertThat(get("/vat-return/mtd/submissions/" + id, T, "OWNER").getStatus(), is(200));
    Response foreign = get("/vat-return/mtd/submissions/" + id, OTHER_T, "OWNER");
    assertThat(codeOf(foreign, 404), is("MTD_SUBMISSION_NOT_FOUND"));
    assertThat(
        "an id nobody issued",
        codeOf(get("/vat-return/mtd/submissions/" + Ids.newId(), T, "OWNER"), 404),
        is("MTD_SUBMISSION_NOT_FOUND"));
    assertThat(
        json(get("/vat-return/mtd/submissions", OTHER_T, "OWNER").readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(0));
    // The other tenant files its own figures.
    r = call("POST", "/vat-return/mtd/submissions", filing("24A2", "true"), OTHER_T, "OWNER");
    assertThat(r.getStatus(), is(201));
    assertThat(
        json(r.readEntity(String.class))
            .getJsonObject("data")
            .getJsonNumber("box1")
            .bigDecimalValue()
            .toPlainString(),
        is("199.80"));
  }

  @Test
  @DisplayName("A VAT return is filed by management, and by nobody else")
  void managementOnly() {
    register(T);
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, get("/vat-return/mtd/registration", T, role).getStatus(), is(403));
      assertThat(
          role,
          call(
                  "PUT",
                  "/vat-return/mtd/registration",
                  "{\"vrn\":\"123456782\",\"provider\":\"SIMULATED\"}",
                  T,
                  role)
              .getStatus(),
          is(403));
      assertThat(
          role,
          call("POST", "/vat-return/mtd/submissions", filing("24A2", "true"), T, role).getStatus(),
          is(403));
      assertThat(role, get("/vat-return/mtd/submissions", T, role).getStatus(), is(403));
      assertThat(
          role,
          get(
                  "/vat-return/mtd/obligations",
                  T,
                  role,
                  "from",
                  "2024-01-01T00:00:00Z",
                  "to",
                  "2024-12-31T00:00:00Z")
              .getStatus(),
          is(403));
    }
    assertThat(get("/vat-return/mtd/registration", T, null).getStatus(), is(403));
    assertThat(get("/vat-return/mtd/registration", T, "MANAGER").getStatus(), is(200));
    // A rival tenant sees an unregistered business, not ours.
    assertThat(
        json(get("/vat-return/mtd/registration", OTHER_T, "OWNER").readEntity(String.class))
            .getJsonObject("data")
            .getBoolean("registered"),
        is(false));
  }

  @Test
  @DisplayName("A grant code of only spaces is refused, and nothing is stored")
  void aGrantCodeOfOnlySpacesIsRefused() throws Exception {
    registerWithHmrc(T);
    Response r =
        call(
            "POST",
            "/vat-return/mtd/hmrc/connect",
            "{\"code\":\"\\u2003\",\"redirectUri\":\"https://shop.example/cb\"}",
            T,
            "OWNER");
    assertThat(codeOf(r, 400), is("MTD_CODE_REQUIRED"));
    JsonObject after =
        json(get("/vat-return/mtd/registration", T, "OWNER").readEntity(String.class))
            .getJsonObject("data");
    assertThat("no grant was stored", after.getBoolean("connected"), is(false));
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(
          role,
          call(
                  "POST",
                  "/vat-return/mtd/hmrc/connect",
                  "{\"code\":\"abc\",\"redirectUri\":\"https://shop.example/cb\"}",
                  T,
                  role)
              .getStatus(),
          is(403));
    }
    Response unregistered =
        call(
            "POST",
            "/vat-return/mtd/hmrc/connect",
            "{\"code\":\"abc\",\"redirectUri\":\"https://shop.example/cb\"}",
            OTHER_T,
            "OWNER");
    assertThat(codeOf(unregistered, 404), is("MTD_NOT_REGISTERED"));
  }

  @Test
  @DisplayName("An authorise redirect that is not an http(s) address, or is missing, is refused")
  void anAuthoriseRedirectThatIsNotHttpIsRefused() throws Exception {
    registerWithHmrc(T);
    assertThat(
        codeOf(
            get(
                "/vat-return/mtd/hmrc/authorize-url",
                T,
                "OWNER",
                "redirectUri",
                "javascript:alert(1)"),
            400),
        is("MTD_REDIRECT_INVALID"));
    assertThat(
        codeOf(get("/vat-return/mtd/hmrc/authorize-url", T, "OWNER"), 400),
        is("MTD_REDIRECT_INVALID"));
    assertThat(
        get(
                "/vat-return/mtd/hmrc/authorize-url",
                T,
                "CASHIER",
                "redirectUri",
                "https://shop.example/cb")
            .getStatus(),
        is(403));
  }
}
