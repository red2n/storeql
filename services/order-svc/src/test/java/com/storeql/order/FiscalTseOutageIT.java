package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Placing a German store under its fiscal regime with a cloud security module (18.5) when the
 * provider cannot be had: signing in is refused or fails, the device is unknown to the provider or
 * the lookup fails, or what comes back is not a device at all. Each is a 502 {@code
 * FISCAL_TSE_UNAVAILABLE}, and a refused placement leaves nothing behind: the store keeps the
 * regime and the device it had, no row is written, and nothing of the credentials is told. A change
 * that needs no provider (the same device, another tax number) goes through while the provider is
 * down.
 *
 * <p>The provider is a stub the test breaks and mends, standing where the cloud TSE's base URL
 * points; its credentials are made up for the run.
 */
@HelidonTest
class FiscalTseOutageIT {

  private static final String T = "01a0b7e2-1111-7000-8000-000000000001";
  private static final String USER = "01a0b7e2-4444-7000-8000-000000000001";
  private static final String TSS = "tss-outage-1";

  /** What the provider answers when it is well: a token that is not kept, and the device. */
  private static final String SIGNED_IN = "{\"access_token\":\"provider-token\"}";

  private static final String DEVICE =
      "{\"serial_number\":\"SER-OUTAGE-1\",\"public_key\":\"PUB1\"}";

  private static final String API_KEY = "test-key-" + tail();
  private static final String API_SECRET = "test-secret-" + tail();

  private static final PostgresSupport PG;
  private static final JsonStub PROVIDER;

  private static volatile int authStatus = 200;
  private static volatile String authBody = SIGNED_IN;
  private static volatile int deviceStatus = 200;
  private static volatile String deviceBody = DEVICE;

  /** The last twelve characters of a fresh id: a UUIDv7's first characters are its timestamp. */
  private static String tail() {
    String id = Ids.newId().toString();
    return id.substring(id.length() - 12);
  }

  static {
    PG = PostgresSupport.start().wire("order");
    TenantSvcStub.start().with(T, "EUR", "DE");
    PROVIDER = JsonStub.start();
    PROVIDER.on("POST", "/auth", call -> new JsonStub.Answer(authStatus, authBody));
    PROVIDER.on("GET", "/tss/" + TSS, call -> new JsonStub.Answer(deviceStatus, deviceBody));
    System.setProperty("storeql.fiscal.tse.cloud.base-url", PROVIDER.baseUrl());
    System.setProperty("storeql.fiscal.tse.cloud.api-key", API_KEY);
    System.setProperty("storeql.fiscal.tse.cloud.api-secret", API_SECRET);
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PROVIDER.close();
    // System properties outlive a test class: another suite proves a cloud module with no
    // credentials is refused, and must not find these.
    System.clearProperty("storeql.fiscal.tse.cloud.base-url");
    System.clearProperty("storeql.fiscal.tse.cloud.api-key");
    System.clearProperty("storeql.fiscal.tse.cloud.api-secret");
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────────

  /** A store of the business the projection knows as active, as tenant-svc's events leave it. */
  private static String activeStore() {
    String id = Ids.newId().toString();
    Envelopes.exec(
        PG,
        "INSERT INTO \"order\".store_status (store_id, tenant_id, status, status_changed_at)"
            + " VALUES ('"
            + id
            + "', '"
            + T
            + "', 'ACTIVE', now())");
    return id;
  }

  private Response setRegime(String store, String body, String roles) {
    return target
        .path("/admin/fiscal-receipts/settings")
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", USER)
        .header("X-Roles", roles)
        .put(
            Entity.entity(
                "{\"storeId\":\"" + store + "\"," + body + "}", MediaType.APPLICATION_JSON));
  }

  private JsonObject settings(String store) {
    Response r =
        target
            .path("/admin/fiscal-receipts/settings")
            .queryParam("storeId", store)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    return Envelopes.ok(r);
  }

  /** The placement of a German store with the cloud module the provider is asked about. */
  private static String germanyOnTheCloud(String taxNumber) {
    return "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\""
        + taxNumber
        + "\",\"tseProvider\":\"CLOUD\",\"tseTssId\":\""
        + TSS
        + "\",\"tseClientId\":\"till-1\"";
  }

  private static String germanyOnASimulatedModule() {
    return "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE123456789\","
        + "\"tseProvider\":\"SIMULATED\",\"tseClientId\":\"till-0\"";
  }

  private static JsonObject refused(Response r, int status) {
    return Envelopes.parse(Envelopes.bodyOf(r, status));
  }

  private static boolean absent(JsonObject o, String key) {
    return !o.containsKey(key) || o.isNull(key);
  }

  private static String rows(String table, String store) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM \"order\"."
            + table
            + " WHERE tenant_id = '"
            + T
            + "' AND store_id = '"
            + store
            + "'");
  }

  private static void providerIsWell() {
    authStatus = 200;
    authBody = SIGNED_IN;
    deviceStatus = 200;
    deviceBody = DEVICE;
  }

  /** One way the provider fails a registration: what it answers to the sign-in and the lookup. */
  private record Fault(String what, int signIn, String signInBody, int lookup, String lookupBody) {}

  // ── tests ──────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A provider that fails or refuses is a 502, and the store is left as it was")
  void aProviderThatFailsRefusesThePlacementAndLeavesNothingBehind() {
    List<Fault> faults =
        List.of(
            new Fault("sign-in answers 500", 500, "{}", 200, DEVICE),
            new Fault("credentials refused", 401, "{\"error\":\"no\"}", 200, DEVICE),
            new Fault("sign-in answers no JSON", 200, "<html>maintenance</html>", 200, DEVICE),
            new Fault("device unknown to the provider", 200, SIGNED_IN, 404, "{}"),
            new Fault("lookup fails", 200, SIGNED_IN, 503, "{}"),
            new Fault("bearer token refused", 200, SIGNED_IN, 401, "{}"),
            new Fault("lookup answers no device", 200, SIGNED_IN, 200, "[]"));
    for (Fault fault : faults) {
      authStatus = fault.signIn();
      authBody = fault.signInBody();
      deviceStatus = fault.lookup();
      deviceBody = fault.lookupBody();
      String store = activeStore();
      int askedBefore = PROVIDER.calls().size();

      Response r = setRegime(store, germanyOnTheCloud("DE123456789"), "OWNER");
      String body = r.readEntity(String.class);
      assertThat(fault.what() + ": " + body, r.getStatus(), is(502));
      assertThat(
          fault.what(), Envelopes.parse(body).getString("code"), is("FISCAL_TSE_UNAVAILABLE"));
      assertThat(
          "the provider was really asked: " + fault.what(),
          PROVIDER.calls().size(),
          greaterThan(askedBefore));
      assertThat("no credential is told", body, not(containsString(API_KEY)));
      assertThat("no credential is told", body, not(containsString(API_SECRET)));

      // Nothing was left behind: no settings, no device, and the store reads as it did.
      assertThat(fault.what(), rows("fiscal_store_settings", store), is("0"));
      assertThat(fault.what(), rows("tse_devices", store), is("0"));
      JsonObject now = settings(store);
      assertThat(fault.what(), now.getString("regime"), is("NONE"));
      assertThat(fault.what(), absent(now, "tse"), is(true));
    }
  }

  @Test
  @DisplayName("A store keeps the module it has when a new provider cannot be had")
  void aStoreKeepsItsDeviceWhenTheNewProviderFails() {
    providerIsWell();
    String store = activeStore();
    Response simulated = setRegime(store, germanyOnASimulatedModule(), "OWNER");
    assertThat(simulated.readEntity(String.class), simulated.getStatus(), is(200));
    JsonObject before = settings(store);
    String serial = before.getJsonObject("tse").getString("serialNumber");
    assertThat(before.getJsonObject("tse").getString("provider"), is("SIMULATED"));

    // Moving it to the cloud while the provider is down: refused, and the simulated module stays.
    authStatus = 500;
    authBody = "{}";
    Response r = setRegime(store, germanyOnTheCloud("DE987654321"), "OWNER");
    assertThat(refused(r, 502).getString("code"), is("FISCAL_TSE_UNAVAILABLE"));
    JsonObject after = settings(store);
    assertThat(after.getString("regime"), is("DE_KASSENSICHV"));
    assertThat(
        "the tax number was not changed either",
        after.getString("taxRegistrationNumber"),
        is("DE123456789"));
    assertThat(after.getJsonObject("tse").getString("provider"), is("SIMULATED"));
    assertThat(after.getJsonObject("tse").getString("serialNumber"), is(serial));
    assertThat(rows("tse_devices", store), is("1"));

    // The provider mended, the same request moves the store, and replaces the device, once.
    providerIsWell();
    Response moved = setRegime(store, germanyOnTheCloud("DE987654321"), "OWNER");
    assertThat(moved.readEntity(String.class), moved.getStatus(), is(200));
    JsonObject cloud = settings(store);
    assertThat(cloud.getJsonObject("tse").getString("provider"), is("CLOUD"));
    assertThat(cloud.getJsonObject("tse").getString("externalTssId"), is(TSS));
    assertThat(cloud.getJsonObject("tse").getString("serialNumber"), is("SER-OUTAGE-1"));
    assertThat(cloud.getString("taxRegistrationNumber"), is("DE987654321"));
    assertThat(rows("tse_devices", store), is("1"));
  }

  @Test
  @DisplayName("A change that needs no provider goes through while the provider is down")
  void aChangeThatNeedsNoProviderIsNotStoppedByItsOutage() {
    providerIsWell();
    String store = activeStore();
    Response placed = setRegime(store, germanyOnTheCloud("DE111111111"), "OWNER");
    assertThat(placed.readEntity(String.class), placed.getStatus(), is(200));

    authStatus = 500;
    authBody = "{}";
    deviceStatus = 503;
    deviceBody = "{}";
    int askedBefore = PROVIDER.calls().size();
    // The same device named again, a tax number corrected: nothing to register, nothing to ask.
    Response corrected = setRegime(store, germanyOnTheCloud("DE222222222"), "OWNER");
    assertThat(corrected.readEntity(String.class), corrected.getStatus(), is(200));
    assertThat("the provider was not asked", PROVIDER.calls().size(), is(askedBefore));
    JsonObject now = settings(store);
    assertThat(now.getString("taxRegistrationNumber"), is("DE222222222"));
    assertThat(now.getJsonObject("tse").getString("provider"), is("CLOUD"));
    assertThat(rows("tse_devices", store), is("1"));
  }

  @Test
  @DisplayName("Management only: a refused caller never reaches the provider")
  void aCallerBelowManagementNeverReachesTheProvider() {
    providerIsWell();
    String store = activeStore();
    int askedBefore = PROVIDER.calls().size();
    for (String roles : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Response r = setRegime(store, germanyOnTheCloud("DE123456789"), roles);
      assertThat(roles, r.getStatus(), is(403));
      r.close();
    }
    assertThat("the provider was not asked", PROVIDER.calls().size(), is(askedBefore));
    assertThat(rows("fiscal_store_settings", store), is("0"));
    assertThat(rows("tse_devices", store), is("0"));
  }
}
