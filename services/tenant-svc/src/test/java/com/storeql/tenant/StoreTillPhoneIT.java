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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A phone at the till (intent/phone-at-the-till.md): each store says whether its till asks for the
 * customer's phone — Required, Optional or Don't ask — Optional until its owner or a manager says
 * otherwise; the till reads it from the store list it already reads, and nobody else may change it.
 */
@HelidonTest
class StoreTillPhoneIT {

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

  private static final String OWNER = "01a090c9-8888-7000-8000-000000000001";
  private static final String RIVAL_OWNER = "01a090c9-8888-7000-8000-000000000002";

  @Inject WebTarget target;

  private static String tenantA;
  private static String tenantR;

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

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in: " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  /** The store's own entry in a list of stores (the storefront list names each by storeId). */
  private static String entryOf(String listJson, String storeId) {
    int at = listJson.indexOf("\"storeId\":\"" + storeId + "\"");
    if (at < 0) throw new AssertionError(storeId + " not in: " + listJson);
    int open = listJson.lastIndexOf('{', at);
    int close = listJson.indexOf('}', at);
    return listJson.substring(open, close + 1);
  }

  private synchronized void setUpTenants() {
    if (tenantA != null) return;
    tenantA = onboard(OWNER, "Till Phone Ltd");
    tenantR = onboard(RIVAL_OWNER, "Rival Till Ltd");
  }

  private String onboard(String owner, String name) {
    Response r =
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", owner)
            .post(
                Entity.entity(
                    "{\"businessName\":\"" + name + "\",\"country\":\"IN\",\"currency\":\"INR\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return field(body, "id");
  }

  private Response createStore(String tenant, String extra) {
    return as("/admin/stores", tenant, "OWNER", null)
        .post(
            Entity.entity(
                "{\"name\":\"Till shop\",\"code\":\"T-"
                    + Ids.newId()
                    + "\",\"country\":\"IN\",\"timezone\":\"Asia/Kolkata\""
                    + extra
                    + "}",
                MediaType.APPLICATION_JSON));
  }

  private String addStore(String tenant, String extra) {
    Response r = createStore(tenant, extra);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return field(body, "id");
  }

  private Response update(
      String tenant, String roles, String stores, String storeId, String extra) {
    return as("/admin/stores/" + storeId, tenant, roles, stores)
        .put(
            Entity.entity(
                "{\"name\":\"Till shop\",\"country\":\"IN\",\"timezone\":\"Asia/Kolkata\""
                    + extra
                    + "}",
                MediaType.APPLICATION_JSON));
  }

  private String tillPhoneOf(String tenant, String storeId) {
    Response r = as("/admin/stores/" + storeId, tenant, "OWNER", null).get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return field(body, "tillPhone");
  }

  @Test
  @DisplayName("A store asks Optional until its owner chooses, and the till's store list says so")
  void aStoreAsksOptionalUntilItsOwnerChooses() {
    setUpTenants();
    Response created = createStore(tenantA, "");
    String body = created.readEntity(String.class);
    assertThat(body, created.getStatus(), is(201));
    assertThat("a new store starts on Optional", field(body, "tillPhone"), is("OPTIONAL"));
    String storeId = field(body, "id");
    assertThat(tillPhoneOf(tenantA, storeId), is("OPTIONAL"));

    Response list = as("/storefront/stores", tenantA, null, null).get();
    String listed = list.readEntity(String.class);
    assertThat(listed, list.getStatus(), is(200));
    assertThat(
        "the list a cashier's till reads carries it",
        field(entryOf(listed, storeId), "tillPhone"),
        is("OPTIONAL"));
  }

  @Test
  @DisplayName("The owner or a manager chooses; leaving it out of an update keeps it")
  void theOwnerOrAManagerChooses() {
    setUpTenants();
    String storeId = addStore(tenantA, ",\"tillPhone\":\"required\"");
    assertThat("read the way it was meant", tillPhoneOf(tenantA, storeId), is("REQUIRED"));

    Response keep = update(tenantA, "OWNER", null, storeId, "");
    assertThat(keep.readEntity(String.class), keep.getStatus(), is(200));
    assertThat(
        "an update that leaves it out keeps it", tillPhoneOf(tenantA, storeId), is("REQUIRED"));

    Response manager = update(tenantA, "MANAGER", storeId, storeId, ",\"tillPhone\":\"OFF\"");
    String answered = manager.readEntity(String.class);
    assertThat(answered, manager.getStatus(), is(200));
    assertThat(field(answered, "tillPhone"), is("OFF"));
    assertThat(tillPhoneOf(tenantA, storeId), is("OFF"));

    Response list = as("/storefront/stores", tenantA, null, null).get();
    assertThat(field(entryOf(list.readEntity(String.class), storeId), "tillPhone"), is("OFF"));
  }

  @Test
  @DisplayName("Anything but Required, Optional or Don't ask is refused, and nothing changes")
  void anythingElseIsRefused() {
    setUpTenants();
    Response created = createStore(tenantA, ",\"tillPhone\":\"SOMETIMES\"");
    String body = created.readEntity(String.class);
    assertThat(body, created.getStatus(), is(400));
    assertThat(body, containsString("STORE_TILL_PHONE_INVALID"));

    String storeId = addStore(tenantA, ",\"tillPhone\":\"REQUIRED\"");
    Response updated = update(tenantA, "OWNER", null, storeId, ",\"tillPhone\":\"ASK\"");
    String refused = updated.readEntity(String.class);
    assertThat(refused, updated.getStatus(), is(400));
    assertThat(refused, containsString("STORE_TILL_PHONE_INVALID"));
    assertThat(tillPhoneOf(tenantA, storeId), is("REQUIRED"));
  }

  @Test
  @DisplayName("A cashier or storekeeper cannot change what the till asks")
  void staffWhoRunTheTillCannotChooseForIt() {
    setUpTenants();
    String storeId = addStore(tenantA, ",\"tillPhone\":\"REQUIRED\"");
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Response r = update(tenantA, role, storeId, storeId, ",\"tillPhone\":\"OFF\"");
      String body = r.readEntity(String.class);
      assertThat(role + ": " + body, r.getStatus(), is(403));
    }
    assertThat(tillPhoneOf(tenantA, storeId), is("REQUIRED"));
  }

  @Test
  @DisplayName("Another business can neither read nor change a store's choice, even naming it")
  void anotherBusinessNeverReachesIt() {
    setUpTenants();
    String storeId = addStore(tenantA, ",\"tillPhone\":\"REQUIRED\"");
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      Response changed = update(tenantR, role, storeId, storeId, ",\"tillPhone\":\"OFF\"");
      String body = changed.readEntity(String.class);
      assertThat(
          role + ": " + body,
          changed.getStatus(),
          is(role.equals("OWNER") || role.equals("MANAGER") ? 404 : 403));
    }
    Response read = as("/admin/stores/" + storeId, tenantR, "OWNER", null).get();
    assertThat(read.readEntity(String.class), read.getStatus(), is(404));
    Response list = as("/storefront/stores", tenantR, null, null).get();
    assertThat(list.readEntity(String.class), not(containsString(storeId)));
    assertThat("nothing moved", tillPhoneOf(tenantA, storeId), is("REQUIRED"));
  }
}
