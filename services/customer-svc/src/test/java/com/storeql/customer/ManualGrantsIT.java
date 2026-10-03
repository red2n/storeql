package com.storeql.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What management hands out by hand: a loyalty adjustment and store credit issued are OWNER or
 * MANAGER only and keep who did it and why; a manual award or adjustment writes once per
 * Idempotency-Key; another business's staff of every role, and a shopper, move nothing. Earning at
 * a sale, redeeming points and redeeming store credit stay open to the till.
 */
@HelidonTest
class ManualGrantsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  static {
    System.setProperty("storeql.customer.loyalty.sweep-seconds", "0");
  }

  private static final TenantSvcStub TENANTS = TenantSvcStub.start();
  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  @Inject WebTarget target;

  private UUID tenant;
  private UUID other;
  private UUID manager;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  @BeforeEach
  void twoBusinesses() {
    tenant = Ids.newId();
    other = Ids.newId();
    manager = Ids.newId();
    TENANTS.with(tenant.toString(), "GBP", "GB");
    TENANTS.with(other.toString(), "GBP", "GB");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, UUID business, UUID user, String role) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", business.toString())
        .header("X-User-Id", user.toString())
        .header("X-Roles", role);
  }

  private Response post(
      String path, UUID business, String role, String key, String body, UUID user) {
    Invocation.Builder b = as(path, business, user, role);
    if (key != null) b = b.header("Idempotency-Key", key);
    return b.post(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private Response post(String path, String role, String key, String body) {
    return post(path, tenant, role, key, body, manager);
  }

  private static String key() {
    return Ids.newId().toString();
  }

  private String customer() {
    Response r =
        post(
            "/customers",
            tenant,
            "OWNER",
            null,
            "{\"email\":\""
                + Ids.newId()
                + "@example.com\",\"firstName\":\"G\",\"lastName\":\"S\"}",
            manager);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  private String sql(String statement, Object... params) throws SQLException {
    try (var c = PG.dataSource().getConnection();
        var ps = c.prepareStatement(statement)) {
      for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
      if (statement.trim().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
        try (var rs = ps.executeQuery()) {
          return rs.next() ? String.valueOf(rs.getObject(1)) : null;
        }
      }
      ps.executeUpdate();
      return null;
    }
  }

  private BigDecimal points(String customerId) throws SQLException {
    String v =
        sql(
            "SELECT points_balance FROM customer.loyalty_accounts"
                + " WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            Ids.parse(customerId));
    return v == null ? BigDecimal.ZERO : new BigDecimal(v);
  }

  private BigDecimal credit(String customerId) throws SQLException {
    String v =
        sql(
            "SELECT balance FROM customer.store_credit_accounts"
                + " WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            Ids.parse(customerId));
    return v == null ? BigDecimal.ZERO : new BigDecimal(v);
  }

  private long count(String table, String customerId) throws SQLException {
    return Long.parseLong(
        sql(
            "SELECT COUNT(*) FROM customer." + table + " WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            Ids.parse(customerId)));
  }

  private static final String ADJUST = "{\"points\":25,\"reason\":\"goodwill after a late order\"}";
  private static final String EARN = "{\"points\":40,\"reason\":\"welcome\"}";
  private static final String ISSUE = "{\"amount\":15.00,\"reason\":\"damaged goods\"}";

  // ── 1. a manual adjustment is management's ─────────────────────────────────

  @Test
  @DisplayName("A cashier or storekeeper cannot adjust points, up or down; nothing is written")
  void adjustRefusesBelowManager() throws SQLException {
    String id = customer();
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      for (String body : new String[] {ADJUST, "{\"points\":-10,\"reason\":\"zeroing out\"}"}) {
        Response r = post("/customers/" + id + "/loyalty/adjust", role, key(), body);
        assertThat(role + " " + body, r.getStatus(), is(403));
      }
    }
    assertThat(points(id).signum(), is(0));
    assertThat(count("loyalty_ledger", id), is(0L));
    assertThat(count("manual_grants", id), is(0L));
  }

  @Test
  @DisplayName("A manager adjusts up and down; the grant keeps who did it and why")
  void managerAdjustsAndItIsKept() throws SQLException {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", "MANAGER", key(), ADJUST).getStatus(),
        is(200));
    assertThat(points(id).compareTo(new BigDecimal("25")), is(0));
    assertThat(
        post(
                "/customers/" + id + "/loyalty/adjust",
                "OWNER",
                key(),
                "{\"points\":-5,\"reason\":\"a mistaken award\"}")
            .getStatus(),
        is(200));
    assertThat(points(id).compareTo(new BigDecimal("20")), is(0));
    assertThat(count("manual_grants", id), is(2L));
    assertThat(
        sql(
            "SELECT actor_id FROM customer.manual_grants WHERE tenant_id = ? AND customer_id = ?"
                + " AND kind = 'LOYALTY_ADJUST' AND reason = ?",
            tenant,
            Ids.parse(id),
            "goodwill after a late order"),
        is(manager.toString()));
  }

  @Test
  @DisplayName("An adjustment needs a reason and a key")
  void adjustNeedsReasonAndKey() throws SQLException {
    String id = customer();
    Response noKey = post("/customers/" + id + "/loyalty/adjust", "MANAGER", null, ADJUST);
    assertThat(noKey.getStatus(), is(400));
    assertThat(noKey.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", "MANAGER", "not-a-key", ADJUST).getStatus(),
        is(400));
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", "MANAGER", key(), "{\"points\":5}")
            .getStatus(),
        is(400));
    assertThat(
        post(
                "/customers/" + id + "/loyalty/adjust",
                "MANAGER",
                key(),
                "{\"points\":5,\"reason\":\"  \"}")
            .getStatus(),
        is(400));
    assertThat(points(id).signum(), is(0));
    assertThat(count("manual_grants", id), is(0L));
  }

  // ── 2. earn and adjust are idempotent ──────────────────────────────────────

  @Test
  @DisplayName("A retried manual earn under the same key answers with the first and writes once")
  void earnRetryWritesOnce() throws SQLException {
    String id = customer();
    String k = key();
    Response first = post("/customers/" + id + "/loyalty/earn", "MANAGER", k, EARN);
    assertThat(first.getStatus(), is(200));
    Response retry = post("/customers/" + id + "/loyalty/earn", "MANAGER", k, EARN);
    assertThat(retry.getStatus(), is(200));
    assertThat(points(id).compareTo(new BigDecimal("40")), is(0));
    assertThat(count("loyalty_ledger", id), is(1L));
    assertThat(count("manual_grants", id), is(1L));
    assertThat(
        Json.createReader(new StringReader(retry.readEntity(String.class)))
            .readObject()
            .getJsonObject("data")
            .getJsonNumber("pointsBalance")
            .bigDecimalValue()
            .compareTo(new BigDecimal("40")),
        is(0));
  }

  @Test
  @DisplayName("A retried adjustment under the same key writes once")
  void adjustRetryWritesOnce() throws SQLException {
    String id = customer();
    String k = key();
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", "MANAGER", k, ADJUST).getStatus(), is(200));
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", "MANAGER", k, ADJUST).getStatus(), is(200));
    assertThat(points(id).compareTo(new BigDecimal("25")), is(0));
    assertThat(count("loyalty_ledger", id), is(1L));
  }

  @Test
  @DisplayName("Manual earn needs a key; a different key is a different attempt")
  void earnNeedsAKeyAndNewKeyIsNew() throws SQLException {
    String id = customer();
    Response noKey = post("/customers/" + id + "/loyalty/earn", "MANAGER", null, EARN);
    assertThat(noKey.getStatus(), is(400));
    assertThat(noKey.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    assertThat(points(id).signum(), is(0));
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "MANAGER", key(), EARN).getStatus(), is(200));
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "MANAGER", key(), EARN).getStatus(), is(200));
    assertThat(points(id).compareTo(new BigDecimal("80")), is(0));
  }

  @Test
  @DisplayName("A key reused for a different request is refused, and the first stands")
  void keyReusedForSomethingElseIsRefused() throws SQLException {
    String id = customer();
    String k = key();
    assertThat(post("/customers/" + id + "/loyalty/earn", "MANAGER", k, EARN).getStatus(), is(200));
    Response different =
        post(
            "/customers/" + id + "/loyalty/earn",
            "MANAGER",
            k,
            "{\"points\":999,\"reason\":\"other\"}");
    assertThat(different.getStatus(), is(409));
    assertThat(different.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REUSED"));
    assertThat(points(id).compareTo(new BigDecimal("40")), is(0));
    // The same key on the adjust endpoint is another request too.
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", "MANAGER", k, ADJUST).getStatus(), is(409));
    assertThat(points(id).compareTo(new BigDecimal("40")), is(0));
    // And another customer's account is not touched by the first customer's key.
    String elsewhere = customer();
    assertThat(
        post("/customers/" + elsewhere + "/loyalty/earn", "MANAGER", k, EARN).getStatus(), is(409));
    assertThat(points(elsewhere).signum(), is(0));
  }

  @Test
  @DisplayName(
      "Another business naming our customer under a key we already used takes nothing and writes nothing")
  void keysAreScopedToTheBusiness() throws SQLException {
    String id = customer();
    String k = key();
    assertThat(post("/customers/" + id + "/loyalty/earn", "OWNER", k, EARN).getStatus(), is(200));
    // The other business, naming our customer with the same key, finds no such customer and
    // takes nothing of ours.
    Response r = post("/customers/" + id + "/loyalty/earn", other, "OWNER", k, EARN, Ids.newId());
    assertThat(r.getStatus(), is(404));
    assertThat(points(id).compareTo(new BigDecimal("40")), is(0));
    assertThat(count("manual_grants", id), is(1L));
  }

  // ── 3. store credit by hand is management's ────────────────────────────────

  @Test
  @DisplayName("A cashier or storekeeper cannot issue store credit; nothing is written")
  void issueRefusesBelowManager() throws SQLException {
    String id = customer();
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Response r = post("/customers/" + id + "/store-credit/issue", role, key(), ISSUE);
      assertThat(role, r.getStatus(), is(403));
    }
    assertThat(credit(id).signum(), is(0));
    assertThat(count("store_credit_ledger", id), is(0L));
    assertThat(count("manual_grants", id), is(0L));
  }

  @Test
  @DisplayName("A manager issues store credit; the grant keeps who did it and why")
  void managerIssuesAndItIsKept() throws SQLException {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "MANAGER", key(), ISSUE).getStatus(),
        is(200));
    assertThat(credit(id).compareTo(new BigDecimal("15")), is(0));
    assertThat(
        sql(
            "SELECT actor_id FROM customer.manual_grants WHERE tenant_id = ? AND customer_id = ?"
                + " AND kind = 'STORE_CREDIT_ISSUE' AND reason = ?",
            tenant,
            Ids.parse(id),
            "damaged goods"),
        is(manager.toString()));
    assertThat(
        sql(
            "SELECT currency FROM customer.manual_grants WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            Ids.parse(id)),
        is("GBP"));
  }

  @Test
  @DisplayName("Issuing store credit needs a reason")
  void issueNeedsAReason() throws SQLException {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "MANAGER", key(), "{\"amount\":5}")
            .getStatus(),
        is(400));
    assertThat(credit(id).signum(), is(0));
    assertThat(count("manual_grants", id), is(0L));
  }

  @Test
  @DisplayName("The till still redeems points and store credit; management awards by hand")
  void tillPathsStayOpen() throws SQLException {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "MANAGER", key(), EARN).getStatus(), is(200));
    assertThat(
        post(
                "/customers/" + id + "/loyalty/redeem",
                "CASHIER",
                null,
                "{\"points\":10,\"reason\":\"discount\"}")
            .getStatus(),
        is(200));
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "OWNER", key(), ISSUE).getStatus(),
        is(200));
    assertThat(
        post(
                "/customers/" + id + "/store-credit/redeem",
                "CASHIER",
                null,
                "{\"amount\":5.00,\"reason\":\"tender\"}")
            .getStatus(),
        is(200));
    assertThat(points(id).compareTo(new BigDecimal("30")), is(0));
    assertThat(credit(id).compareTo(new BigDecimal("10")), is(0));
  }

  // ── tenant isolation ───────────────────────────────────────────────────────

  @Test
  @DisplayName("Another business's staff of every role, naming our customer, move nothing")
  void otherBusinessMovesNothing() throws SQLException {
    String id = customer();
    for (String role : STAFF) {
      Response earn =
          post("/customers/" + id + "/loyalty/earn", other, role, key(), EARN, Ids.newId());
      assertThat(
          "earn as " + role,
          earn.getStatus(),
          is(role.equals("OWNER") || role.equals("MANAGER") ? 404 : 403));
      Response adjust =
          post("/customers/" + id + "/loyalty/adjust", other, role, key(), ADJUST, Ids.newId());
      assertThat(
          "adjust as " + role,
          adjust.getStatus(),
          is(role.equals("OWNER") || role.equals("MANAGER") ? 404 : 403));
      Response issue =
          post("/customers/" + id + "/store-credit/issue", other, role, key(), ISSUE, Ids.newId());
      assertThat(
          "issue as " + role,
          issue.getStatus(),
          is(role.equals("OWNER") || role.equals("MANAGER") ? 404 : 403));
    }
    assertThat(points(id).signum(), is(0));
    assertThat(credit(id).signum(), is(0));
    assertThat(count("loyalty_ledger", id), is(0L));
    assertThat(count("store_credit_ledger", id), is(0L));
    assertThat(count("manual_grants", id), is(0L));
    // Nothing was written under the other business either.
    assertThat(
        sql("SELECT COUNT(*) FROM customer.manual_grants WHERE tenant_id = ?", other), is("0"));
  }

  @Test
  @DisplayName("Another business's staff, and a shopper, cannot spend our customer's store credit")
  void otherBusinessCannotRedeemStoreCredit() throws SQLException {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "OWNER", key(), ISSUE).getStatus(),
        is(200));
    String redeem = "{\"amount\":5.00,\"reason\":\"tender\",\"orderId\":\"" + Ids.newId() + "\"}";
    for (String role : STAFF) {
      Response r =
          post("/customers/" + id + "/store-credit/redeem", other, role, null, redeem, Ids.newId());
      String body = r.readEntity(String.class);
      assertThat(role + " " + body, r.getStatus(), is(404));
      assertThat(role + " " + body, body, containsString("CUSTOMER_NOT_FOUND"));
    }
    UUID shopper = Ids.newId();
    for (UUID business : new UUID[] {tenant, other}) {
      Response r =
          post(
              "/customers/" + id + "/store-credit/redeem",
              business,
              "CUSTOMER",
              null,
              redeem,
              shopper);
      assertThat("a shopper of " + business, r.getStatus(), is(403));
    }
    assertThat("the balance is as issued", credit(id).compareTo(new BigDecimal("15")), is(0));
    assertThat("only the issue is in the ledger", count("store_credit_ledger", id), is(1L));
    assertThat(
        "nothing was written under the other business",
        sql("SELECT COUNT(*) FROM customer.store_credit_ledger WHERE tenant_id = ?", other),
        is("0"));
  }

  @Test
  @DisplayName(
      "Store credit is issued in its currency's own minor units: a dinar's third place stands,"
          + " half a yen or a fourth fils is refused with nothing written")
  void storeCreditIsInTheCurrencysUnits() throws SQLException {
    TENANTS.with(tenant.toString(), "KWD", "KW");
    String id = customer();
    Response ok =
        post(
            "/customers/" + id + "/store-credit/issue",
            "OWNER",
            key(),
            "{\"amount\":1.125,\"reason\":\"goodwill\"}");
    String okBody = ok.readEntity(String.class);
    assertThat(okBody, ok.getStatus(), is(200));
    assertThat(okBody, containsString("\"balance\":1.125"));
    assertThat(okBody, containsString("\"currency\":\"KWD\""));
    assertThat(credit(id).compareTo(new BigDecimal("1.125")), is(0));

    for (String body :
        new String[] {
          "{\"amount\":1.1255,\"reason\":\"goodwill\"}",
          "{\"amount\":500.5,\"currency\":\"JPY\",\"reason\":\"goodwill\"}",
          "{\"amount\":10.005,\"currency\":\"GBP\",\"reason\":\"goodwill\"}"
        }) {
      Response r = post("/customers/" + id + "/store-credit/issue", "OWNER", key(), body);
      String answer = r.readEntity(String.class);
      assertThat(body + " " + answer, r.getStatus(), is(400));
      assertThat(body + " " + answer, answer, containsString("STORE_CREDIT_AMOUNT_INVALID"));
    }
    Response yen =
        post(
            "/customers/" + id + "/store-credit/issue",
            "OWNER",
            key(),
            "{\"amount\":500,\"currency\":\"JPY\",\"reason\":\"goodwill\"}");
    String yenBody = yen.readEntity(String.class);
    assertThat(yenBody, yen.getStatus(), is(200));
    assertThat(yenBody, containsString("\"balance\":500"));
    assertThat(
        "one issue per currency, nothing for the refusals",
        count("store_credit_ledger", id),
        is(2L));
  }

  @Test
  @DisplayName("A shopper, whoever they name, is refused on every manual grant")
  void shopperIsRefused() throws SQLException {
    String id = customer();
    UUID shopper = Ids.newId();
    assertThat(
        post("/customers/" + id + "/loyalty/earn", tenant, "CUSTOMER", key(), EARN, shopper)
            .getStatus(),
        is(403));
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", tenant, "CUSTOMER", key(), ADJUST, shopper)
            .getStatus(),
        is(403));
    assertThat(
        post("/customers/" + id + "/store-credit/issue", tenant, "CUSTOMER", key(), ISSUE, shopper)
            .getStatus(),
        is(403));
    assertThat(
        post("/customers/" + id + "/loyalty/adjust", other, "CUSTOMER", key(), ADJUST, shopper)
            .getStatus(),
        is(403));
    assertThat(points(id).signum(), is(0));
    assertThat(credit(id).signum(), is(0));
    assertThat(count("manual_grants", id), is(0L));
  }

  // ── catalogue cases: LOY-04, LOY-12, LOY-13, LOY-17, MKT-12 ────────────────

  private jakarta.json.JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  @Test
  @DisplayName("LOY-04: a new customer has a zero-balance BRONZE account, not a 404")
  void newCustomerIsZeroBronze() {
    String id = customer();
    var d = data(as("/customers/" + id + "/loyalty", tenant, manager, "OWNER").get());
    assertThat(d.getJsonNumber("pointsBalance").bigDecimalValue().signum(), is(0));
    assertThat(d.getString("tier"), is("BRONZE"));
  }

  @Test
  @DisplayName(
      "LOY-12: a downward adjustment with a reason takes points off and shows in the ledger")
  void downwardAdjustmentIsInTheLedger() throws SQLException {
    String id = customer();
    assertThat(
        post(
                "/customers/" + id + "/loyalty/earn",
                "OWNER",
                key(),
                "{\"points\":100,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));
    var d =
        data(
            post(
                "/customers/" + id + "/loyalty/adjust",
                "MANAGER",
                key(),
                "{\"points\":-50,\"reason\":\"goodwill correction\"}"));
    assertThat(
        d.getJsonNumber("pointsBalance").bigDecimalValue().compareTo(new BigDecimal("50")), is(0));
    assertThat(
        sql(
            "SELECT points FROM customer.loyalty_ledger WHERE tenant_id = ? AND customer_id = ?"
                + " AND type = 'ADJUST' AND reason = ?",
            tenant,
            Ids.parse(id),
            "goodwill correction"),
        is("-50.00"));
    var ledger =
        Json.createReader(
                new StringReader(
                    as("/customers/" + id + "/loyalty/ledger", tenant, manager, "MANAGER")
                        .get()
                        .readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat(ledger.size(), is(2));
    assertThat(ledger.getJsonObject(0).getString("type"), is("ADJUST"));
    assertThat(ledger.getJsonObject(0).getString("reason"), is("goodwill correction"));
  }

  @Test
  @DisplayName("LOY-13: an upward adjustment across a tier threshold re-tiers and announces it")
  void upwardAdjustmentReTiers() throws SQLException {
    String id = customer();
    assertThat(
        post(
                "/customers/" + id + "/loyalty/earn",
                "OWNER",
                key(),
                "{\"points\":990,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));
    var d =
        data(
            post(
                "/customers/" + id + "/loyalty/adjust",
                "MANAGER",
                key(),
                "{\"points\":20,\"reason\":\"apology credit\"}"));
    assertThat(d.getString("tier"), is("SILVER"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.outbox WHERE tenant_id = ? AND aggregate_id = ?"
                + " AND event_type = 'LoyaltyTierChanged' AND payload LIKE '%SILVER%'",
            tenant, Ids.parse(id)),
        is("1"));
  }

  @Test
  @DisplayName("LOY-17: the ledger has no way to edit or delete an entry")
  void ledgerCannotBeEdited() throws SQLException {
    String id = customer();
    assertThat(
        post(
                "/customers/" + id + "/loyalty/earn",
                "OWNER",
                key(),
                "{\"points\":10,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));
    String entry =
        sql(
            "SELECT id FROM customer.loyalty_ledger WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            Ids.parse(id));
    for (String path :
        new String[] {
          "/customers/" + id + "/loyalty/ledger", "/customers/" + id + "/loyalty/ledger/" + entry,
        }) {
      assertThat(
          "PUT " + path,
          as(path, tenant, manager, "OWNER")
              .put(Entity.entity("{\"points\":1000}", MediaType.APPLICATION_JSON))
              .getStatus(),
          org.hamcrest.Matchers.anyOf(is(404), is(405)));
      assertThat(
          "DELETE " + path,
          as(path, tenant, manager, "OWNER").delete().getStatus(),
          org.hamcrest.Matchers.anyOf(is(404), is(405)));
    }
    assertThat(count("loyalty_ledger", id), is(1L));
    assertThat(points(id).compareTo(BigDecimal.TEN), is(0));
  }

  @Test
  @DisplayName("MKT-12: consent a cashier records is tagged STAFF with the cashier's own id")
  void staffRecordedConsentNamesTheStaffMember() throws SQLException {
    String id = customer();
    UUID cashier = Ids.newId();
    Response put =
        as("/customers/" + id + "/marketing", tenant, cashier, "CASHIER")
            .put(
                Entity.entity(
                    "{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}],"
                        + "\"notice\":\"Said yes at the counter\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(put.readEntity(String.class), put.getStatus(), is(200));
    assertThat(
        sql(
            "SELECT source FROM customer.marketing_consent_log WHERE tenant_id = ?"
                + " AND customer_id = ? ORDER BY recorded_at DESC LIMIT 1",
            tenant,
            Ids.parse(id)),
        is("STAFF"));
    assertThat(
        sql(
            "SELECT actor_id FROM customer.marketing_consent_log WHERE tenant_id = ?"
                + " AND customer_id = ? ORDER BY recorded_at DESC LIMIT 1",
            tenant,
            Ids.parse(id)),
        is(cashier.toString()));
  }

  // ── manual earn is management's ────────────────────────────────────────────

  @Test
  @DisplayName("A cashier or storekeeper cannot award points by hand; nothing is written")
  void earnRefusesBelowManager() throws SQLException {
    String id = customer();
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      assertThat(
          role, post("/customers/" + id + "/loyalty/earn", role, key(), EARN).getStatus(), is(403));
    }
    assertThat(points(id).signum(), is(0));
    assertThat(count("loyalty_ledger", id), is(0L));
    assertThat(count("manual_grants", id), is(0L));
  }

  @Test
  @DisplayName("A manager awards points by hand with a reason; the grant keeps who did it")
  void managerEarnsAndItIsKept() throws SQLException {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "MANAGER", key(), EARN).getStatus(), is(200));
    assertThat(
        sql(
            "SELECT actor_id FROM customer.manual_grants WHERE tenant_id = ? AND customer_id = ?"
                + " AND kind = 'LOYALTY_EARN' AND reason = 'welcome'",
            tenant,
            Ids.parse(id)),
        is(manager.toString()));
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "MANAGER", key(), "{\"points\":5}").getStatus(),
        is(400));
    assertThat(points(id).compareTo(new BigDecimal("40")), is(0));
  }

  // ── LOY-11: redeem is once per order ───────────────────────────────────────

  private String seeded(String points) {
    String id = customer();
    assertThat(
        post(
                "/customers/" + id + "/loyalty/earn",
                "MANAGER",
                key(),
                "{\"points\":" + points + ",\"reason\":\"seed\"}")
            .getStatus(),
        is(200));
    return id;
  }

  private static String redeem(String points, UUID order) {
    return "{\"points\":"
        + points
        + (order == null ? "" : ",\"orderId\":\"" + order + "\"")
        + ",\"reason\":\"tender\"}";
  }

  @Test
  @DisplayName("LOY-11: a retried redemption for the same order and points takes nothing more")
  void redeemRetryForTheSameOrderDebitsOnce() throws SQLException {
    String id = seeded("100");
    UUID order = Ids.newId();
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("30", order))
            .getStatus(),
        is(200));
    Response retry =
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("30", order));
    assertThat(retry.getStatus(), is(200));
    assertThat(points(id).compareTo(new BigDecimal("70")), is(0));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.loyalty_ledger WHERE tenant_id = ? AND customer_id = ?"
                + " AND type = 'REDEEM'",
            tenant,
            Ids.parse(id)),
        is("1"));
    assertThat(
        Json.createReader(new StringReader(retry.readEntity(String.class)))
            .readObject()
            .getJsonObject("data")
            .getJsonNumber("pointsBalance")
            .bigDecimalValue()
            .compareTo(new BigDecimal("70")),
        is(0));
  }

  @Test
  @DisplayName("LOY-11: the same order with other points is refused 409 and nothing is written")
  void redeemSameOrderOtherPointsIsRefused() throws SQLException {
    String id = seeded("100");
    UUID order = Ids.newId();
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("30", order))
            .getStatus(),
        is(200));
    Response other =
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("40", order));
    assertThat(other.getStatus(), is(409));
    assertThat(other.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REUSED"));
    assertThat(points(id).compareTo(new BigDecimal("70")), is(0));
    assertThat(count("manual_grants", id), is(2L)); // the seeding earn and the one redemption
  }

  @Test
  @DisplayName("LOY-11: another order redeems again, and an order is per customer")
  void redeemIsPerCustomerAndOrder() throws SQLException {
    String id = seeded("100");
    String mate = seeded("100");
    UUID order = Ids.newId();
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("10", order))
            .getStatus(),
        is(200));
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("10", Ids.newId()))
            .getStatus(),
        is(200));
    assertThat(
        post("/customers/" + mate + "/loyalty/redeem", "CASHIER", null, redeem("10", order))
            .getStatus(),
        is(200));
    assertThat(points(id).compareTo(new BigDecimal("80")), is(0));
    assertThat(points(mate).compareTo(new BigDecimal("90")), is(0));
  }

  @Test
  @DisplayName("LOY-11: too few points is refused 422 and the order can still be redeemed later")
  void refusedRedeemLeavesNoMark() throws SQLException {
    String id = seeded("20");
    UUID order = Ids.newId();
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("50", order))
            .getStatus(),
        is(422));
    assertThat(count("manual_grants", id), is(1L));
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("20", order))
            .getStatus(),
        is(200));
    assertThat(points(id).signum(), is(0));
  }

  @Test
  @DisplayName("LOY-11: two calls at once for one order take the points once")
  void concurrentRedeemsForOneOrderDebitOnce() throws Exception {
    String id = seeded("100");
    UUID order = Ids.newId();
    java.util.List<Integer> statuses =
        com.storeql.test.Concurrency.inParallel(
            6,
            () ->
                post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("30", order))
                    .getStatus());
    for (int st : statuses) assertThat(st, is(200));
    assertThat(points(id).compareTo(new BigDecimal("70")), is(0));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.loyalty_ledger WHERE tenant_id = ? AND customer_id = ?"
                + " AND type = 'REDEEM'",
            tenant,
            Ids.parse(id)),
        is("1"));
  }

  @Test
  @DisplayName("A redemption with no order keeps spending on every call; a key makes it replay")
  void redeemWithoutOrderAndOptionalKey() throws SQLException {
    String id = seeded("100");
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("10", null))
            .getStatus(),
        is(200));
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("10", null))
            .getStatus(),
        is(200));
    assertThat(points(id).compareTo(new BigDecimal("80")), is(0));

    String k = key();
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", k, redeem("10", null)).getStatus(),
        is(200));
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", k, redeem("10", null)).getStatus(),
        is(200));
    assertThat(points(id).compareTo(new BigDecimal("70")), is(0));
    Response other = post("/customers/" + id + "/loyalty/redeem", "CASHIER", k, redeem("15", null));
    assertThat(other.getStatus(), is(409));
    assertThat(points(id).compareTo(new BigDecimal("70")), is(0));
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", "nope", redeem("1", null))
            .getStatus(),
        is(400));
  }

  @Test
  @DisplayName("Another business naming our customer and order redeems nothing")
  void otherBusinessCannotRedeem() throws SQLException {
    String id = seeded("100");
    UUID order = Ids.newId();
    for (String role : STAFF) {
      assertThat(
          role,
          post(
                  "/customers/" + id + "/loyalty/redeem",
                  other,
                  role,
                  null,
                  redeem("10", order),
                  Ids.newId())
              .getStatus(),
          is(404));
    }
    assertThat(
        post(
                "/customers/" + id + "/loyalty/redeem",
                other,
                "CUSTOMER",
                null,
                redeem("10", order),
                Ids.newId())
            .getStatus(),
        is(403));
    assertThat(points(id).compareTo(new BigDecimal("100")), is(0));
    assertThat(
        sql("SELECT COUNT(*) FROM customer.manual_grants WHERE tenant_id = ?", other), is("0"));
    // Our own redemption for that order is still available to us.
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, redeem("10", order))
            .getStatus(),
        is(200));
  }

  // ── store credit issue is retry-safe ───────────────────────────────────────

  @Test
  @DisplayName(
      "Issuing store credit needs a key: missing is 400, malformed is 400, nothing written")
  void issueNeedsAKey() throws SQLException {
    String id = customer();
    Response none = post("/customers/" + id + "/store-credit/issue", "MANAGER", null, ISSUE);
    assertThat(none.getStatus(), is(400));
    assertThat(none.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "MANAGER", "nope", ISSUE).getStatus(),
        is(400));
    assertThat(credit(id).signum(), is(0));
    assertThat(count("manual_grants", id), is(0L));
  }

  @Test
  @DisplayName("A retried issue under the same key answers with the first and writes once")
  void issueRetryWritesOnce() throws SQLException {
    String id = customer();
    String k = key();
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "MANAGER", k, ISSUE).getStatus(), is(200));
    Response retry = post("/customers/" + id + "/store-credit/issue", "MANAGER", k, ISSUE);
    assertThat(retry.getStatus(), is(200));
    assertThat(
        Json.createReader(new StringReader(retry.readEntity(String.class)))
            .readObject()
            .getJsonObject("data")
            .getJsonNumber("balance")
            .bigDecimalValue()
            .compareTo(new BigDecimal("15")),
        is(0));
    assertThat(credit(id).compareTo(new BigDecimal("15")), is(0));
    assertThat(count("store_credit_ledger", id), is(1L));
    assertThat(count("manual_grants", id), is(1L));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.outbox WHERE tenant_id = ? AND aggregate_id = ?"
                + " AND event_type = 'StoreCreditIssued'",
            tenant,
            Ids.parse(id)),
        is("1"));
  }

  @Test
  @DisplayName("Two issues at once under one key write once")
  void concurrentIssuesUnderOneKeyWriteOnce() throws Exception {
    String id = customer();
    String k = key();
    java.util.List<Integer> statuses =
        com.storeql.test.Concurrency.inParallel(
            6,
            () ->
                post("/customers/" + id + "/store-credit/issue", "MANAGER", k, ISSUE).getStatus());
    for (int st : statuses) assertThat(st, is(200));
    assertThat(credit(id).compareTo(new BigDecimal("15")), is(0));
    assertThat(count("store_credit_ledger", id), is(1L));
    assertThat(count("manual_grants", id), is(1L));
  }

  @Test
  @DisplayName("The same key for a different issue is 409 and the first stands; a new key is new")
  void issueKeyReusedForSomethingElse() throws SQLException {
    String id = customer();
    String k = key();
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "MANAGER", k, ISSUE).getStatus(), is(200));
    Response other =
        post(
            "/customers/" + id + "/store-credit/issue",
            "MANAGER",
            k,
            "{\"amount\":99.00,\"reason\":\"other\"}");
    assertThat(other.getStatus(), is(409));
    assertThat(other.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REUSED"));
    assertThat(
        post(
                "/customers/" + id + "/store-credit/issue",
                "MANAGER",
                k,
                "{\"amount\":15.00,\"currency\":\"JPY\",\"reason\":\"x\"}")
            .getStatus(),
        is(409));
    assertThat(credit(id).compareTo(new BigDecimal("15")), is(0));
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "MANAGER", key(), ISSUE).getStatus(),
        is(200));
    assertThat(credit(id).compareTo(new BigDecimal("30")), is(0));
  }

  @Test
  @DisplayName("Another business's key is its own: it neither replays nor blocks ours")
  void issueKeysAreScopedToTheBusiness() throws SQLException {
    String id = customer();
    String k = key();
    // The other business, naming our customer with a key, finds no customer and writes nothing.
    assertThat(
        post("/customers/" + id + "/store-credit/issue", other, "OWNER", k, ISSUE, Ids.newId())
            .getStatus(),
        is(404));
    assertThat(credit(id).signum(), is(0));
    assertThat(
        sql("SELECT COUNT(*) FROM customer.manual_grants WHERE tenant_id = ?", other), is("0"));
    // Our use of the same key is not replayed from theirs.
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "MANAGER", k, ISSUE).getStatus(), is(200));
    assertThat(credit(id).compareTo(new BigDecimal("15")), is(0));
  }

  // ── a manual adjustment's refusals: each by name, each writing nothing ───────

  private static final String V4_ID = "123e4567-e89b-42d3-a456-426614174000";

  /** Everything an adjustment writes for one customer, as one comparable line. */
  private String written(String customerId) throws SQLException {
    return points(customerId).toPlainString()
        + " ledger="
        + count("loyalty_ledger", customerId)
        + " grants="
        + count("manual_grants", customerId)
        + " events="
        + sql(
            "SELECT COUNT(*) FROM customer.outbox WHERE tenant_id = ? AND aggregate_id = ?",
            tenant,
            Ids.parse(customerId));
  }

  /** The stable code of a refusal: the problem's own member, or the legacy envelope's. */
  private static String codeOf(String body) {
    jakarta.json.JsonObject problem = Json.createReader(new StringReader(body)).readObject();
    return problem.containsKey("code")
        ? problem.getString("code")
        : problem.getJsonObject("error").getString("code");
  }

  private static void assertRefused(String what, Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(what + ": " + body, r.getStatus(), is(status));
    assertThat(what + ": " + body, codeOf(body), is(code));
  }

  private static String pointsBody(String figure) {
    return "{\"points\":" + figure + ",\"reason\":\"a correction\"}";
  }

  @Test
  @DisplayName(
      "An adjustment refused for its role, business, id, key or body says why by code and writes nothing")
  void adjustRefusalsNameTheirCodeAndWriteNothing() throws SQLException {
    String id = customer();
    String path = "/customers/" + id + "/loyalty/adjust";
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "OWNER", key(), EARN).getStatus(), is(200));
    String before = written(id);

    // The role: a till is not management, and nor is a shopper.
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertRefused(role, post(path, tenant, role, key(), ADJUST, manager), 403, "FORBIDDEN");
    }
    // The business: another one's management finds no such customer, the rest are turned back.
    for (String role : STAFF) {
      boolean management = role.equals("OWNER") || role.equals("MANAGER");
      assertRefused(
          "another business's " + role,
          post(path, other, role, key(), ADJUST, Ids.newId()),
          management ? 404 : 403,
          management ? "CUSTOMER_NOT_FOUND" : "FORBIDDEN");
    }
    assertRefused(
        "another business's shopper",
        post(path, other, "CUSTOMER", key(), ADJUST, Ids.newId()),
        403,
        "FORBIDDEN");
    // The id: one nobody holds here, and two that are not ids of ours at all.
    assertRefused(
        "a customer nobody holds",
        post(
            "/customers/" + Ids.newId() + "/loyalty/adjust",
            tenant,
            "OWNER",
            key(),
            ADJUST,
            manager),
        404,
        "CUSTOMER_NOT_FOUND");
    assertRefused(
        "an id that is not an id",
        post("/customers/not-a-uuid/loyalty/adjust", tenant, "OWNER", key(), ADJUST, manager),
        400,
        "INVALID_UUID");
    assertRefused(
        "a version-4 id",
        post("/customers/" + V4_ID + "/loyalty/adjust", tenant, "OWNER", key(), ADJUST, manager),
        400,
        "INVALID_UUID");
    // The key: absent, not a UUID, and a UUID of the wrong version.
    assertRefused(
        "no key",
        post(path, tenant, "MANAGER", null, ADJUST, manager),
        400,
        "IDEMPOTENCY_KEY_REQUIRED");
    assertRefused(
        "a key that is not a UUID",
        post(path, tenant, "MANAGER", "nope", ADJUST, manager),
        400,
        "IDEMPOTENCY_KEY_INVALID");
    assertRefused(
        "a version-4 key",
        post(path, tenant, "MANAGER", V4_ID, ADJUST, manager),
        400,
        "IDEMPOTENCY_KEY_INVALID");
    // The body: what a constraint refuses, what is no figure at all, what cannot be read.
    String[][] badBodies = {
      {"{}", "VALIDATION_FAILED"},
      {"{\"reason\":\"no points\"}", "VALIDATION_FAILED"},
      {"{\"points\":5}", "VALIDATION_FAILED"},
      {"{\"points\":5,\"reason\":\"  \"}", "VALIDATION_FAILED"},
      {pointsBody("5").replace("a correction", "r".repeat(501)), "VALIDATION_FAILED"},
      {pointsBody("0"), "VALIDATION_FAILED"},
      {pointsBody("0.00"), "VALIDATION_FAILED"},
      {pointsBody("0.004"), "VALIDATION_FAILED"},
      {pointsBody("-0.001"), "VALIDATION_FAILED"},
      {pointsBody("100000000000000000"), "VALIDATION_FAILED"},
      {pointsBody("-100000000000000000"), "VALIDATION_FAILED"},
      {pointsBody("\"lots\""), "REQUEST_BODY_INVALID"},
      {"{\"points\":5,\"reason\":", "REQUEST_BODY_INVALID"},
    };
    for (String[] bad : badBodies) {
      assertRefused(bad[0], post(path, tenant, "MANAGER", key(), bad[0], manager), 400, bad[1]);
    }
    Response nothing = post(path, tenant, "MANAGER", key(), "null", manager);
    String nothingBody = nothing.readEntity(String.class);
    assertThat(nothingBody, nothing.getStatus(), is(400));
    assertThat(
        nothingBody,
        codeOf(nothingBody),
        org.hamcrest.Matchers.anyOf(is("BODY_REQUIRED"), is("REQUEST_BODY_INVALID")));

    assertThat("nothing moved for our customer", written(id), is(before));
    assertThat(
        "nothing was written under the other business",
        sql("SELECT COUNT(*) FROM customer.manual_grants WHERE tenant_id = ?", other),
        is("0"));

    // A key already carried out for one correction refuses another, and the first stands.
    String k = key();
    assertThat(post(path, tenant, "MANAGER", k, ADJUST, manager).getStatus(), is(200));
    assertRefused(
        "the same key for another correction",
        post(path, tenant, "MANAGER", k, pointsBody("999"), manager),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    assertThat(points(id).compareTo(new BigDecimal("65")), is(0));
  }

  @Test
  @DisplayName(
      "A deduction larger than the balance takes the balance, and the ledger and the event say what came off")
  void aDeductionLargerThanTheBalanceRecordsWhatCameOff() throws SQLException {
    String id = customer();
    assertThat(
        post(
                "/customers/" + id + "/loyalty/earn",
                "OWNER",
                key(),
                "{\"points\":20,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));
    var d =
        data(
            post(
                "/customers/" + id + "/loyalty/adjust",
                "MANAGER",
                key(),
                "{\"points\":-50,\"reason\":\"zeroing out\"}"));
    assertThat(d.getJsonNumber("pointsBalance").bigDecimalValue().signum(), is(0));

    // The ledger and the event say the 20 that came off, not the 50 that was asked for.
    String where = " WHERE tenant_id = ? AND customer_id = ? AND type = 'ADJUST'";
    assertThat(
        sql("SELECT points FROM customer.loyalty_ledger" + where, tenant, Ids.parse(id)),
        is("-20.00"));
    assertThat(
        sql("SELECT balance_after FROM customer.loyalty_ledger" + where, tenant, Ids.parse(id)),
        is("0.00"));
    var event =
        Json.createReader(
                new StringReader(
                    sql(
                        "SELECT payload FROM customer.outbox WHERE tenant_id = ?"
                            + " AND aggregate_id = ? AND event_type = 'LoyaltyAdjusted'",
                        tenant,
                        Ids.parse(id))))
            .readObject();
    assertThat(
        event.getJsonNumber("points").bigDecimalValue().compareTo(new BigDecimal("-20")), is(0));
    // The grant keeps what was asked, and who asked.
    // The column holds points or store credit, so it is as wide as any currency's minor units
    // (V15); the value is what counts.
    assertThat(
        new BigDecimal(
                sql(
                    "SELECT amount FROM customer.manual_grants WHERE tenant_id = ? AND customer_id = ?"
                        + " AND kind = 'LOYALTY_ADJUST'",
                    tenant,
                    Ids.parse(id)))
            .compareTo(new BigDecimal("-50")),
        is(0));
    // And no lot has anything left to spend.
    assertThat(
        new BigDecimal(
                sql(
                    "SELECT COALESCE(SUM(remaining), 0) FROM customer.loyalty_point_lots"
                        + " WHERE tenant_id = ? AND customer_id = ?",
                    tenant,
                    Ids.parse(id)))
            .signum(),
        is(0));
  }

  @Test
  @DisplayName("A deduction with no points to take is refused 422 and its key is not spent")
  void aDeductionWithNothingToTakeIsRefusedAndTheKeyIsNotSpent() throws SQLException {
    String id = customer();
    String path = "/customers/" + id + "/loyalty/adjust";
    String k = key();
    String deduction = "{\"points\":-5,\"reason\":\"a mistaken award\"}";
    String before = written(id);

    assertRefused(
        "a balance of nothing",
        post(path, tenant, "MANAGER", k, deduction, manager),
        422,
        "LOYALTY_INSUFFICIENT_POINTS");
    assertThat("nothing was written", written(id), is(before));

    // Points arrive; the same attempt under the same key now stands: the refusal spent nothing.
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "OWNER", key(), pointsBody("10")).getStatus(),
        is(200));
    var d = data(post(path, tenant, "MANAGER", k, deduction, manager));
    assertThat(
        d.getJsonNumber("pointsBalance").bigDecimalValue().compareTo(new BigDecimal("5")), is(0));
  }

  @Test
  @DisplayName(
      "Points the ledger cannot hold are refused 400 on earn, redeem and adjust, and move nothing")
  void pointsTheLedgerCannotHoldAreRefused() throws SQLException {
    String id = seeded("10");
    String before = written(id);
    for (String figure : new String[] {"0.004", "1.005", "100000000000000000"}) {
      assertRefused(
          "earn " + figure,
          post("/customers/" + id + "/loyalty/earn", "MANAGER", key(), pointsBody(figure)),
          400,
          "VALIDATION_FAILED");
      assertRefused(
          "redeem " + figure,
          post("/customers/" + id + "/loyalty/redeem", "CASHIER", null, pointsBody(figure)),
          400,
          "VALIDATION_FAILED");
      assertRefused(
          "adjust " + figure,
          post("/customers/" + id + "/loyalty/adjust", "MANAGER", key(), pointsBody(figure)),
          400,
          "VALIDATION_FAILED");
    }
    assertThat(written(id), is(before));
  }

  // ── an erased customer is given nothing by hand ─────────────────────────────

  @Test
  @DisplayName(
      "A manual earn or adjustment, up or down, for an erased customer is refused 409 by owner and manager alike, a retry too, and writes nothing")
  void anErasedCustomerIsGivenNothingByHand() throws SQLException {
    String id = seeded("40");
    String firstEarn = key();
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "MANAGER", firstEarn, EARN).getStatus(),
        is(200));
    Response erased = as("/customers/" + id, tenant, manager, "OWNER").delete();
    assertThat(erased.readEntity(String.class), erased.getStatus(), is(204));
    String before = written(id);

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(
          role + " earning",
          post("/customers/" + id + "/loyalty/earn", role, key(), EARN),
          409,
          "CUSTOMER_ANONYMIZED");
      assertRefused(
          role + " adjusting up",
          post("/customers/" + id + "/loyalty/adjust", role, key(), ADJUST),
          409,
          "CUSTOMER_ANONYMIZED");
      assertRefused(
          role + " adjusting down",
          post("/customers/" + id + "/loyalty/adjust", role, key(), pointsBody("-5")),
          409,
          "CUSTOMER_ANONYMIZED");
    }
    // A retry of an award made before the erasure gives nothing more either.
    assertRefused(
        "a retried earlier award",
        post("/customers/" + id + "/loyalty/earn", "MANAGER", firstEarn, EARN),
        409,
        "CUSTOMER_ANONYMIZED");
    // Below management it is still the role that is refused, before the record is looked at.
    assertRefused(
        "a cashier earning",
        post("/customers/" + id + "/loyalty/earn", "CASHIER", key(), EARN),
        403,
        "FORBIDDEN");
    // Another business's management still finds no such customer.
    assertRefused(
        "another business's owner",
        post("/customers/" + id + "/loyalty/earn", other, "OWNER", key(), EARN, Ids.newId()),
        404,
        "CUSTOMER_NOT_FOUND");

    // Nothing moved: no points, no ledger entry, no grant, no event.
    assertThat(written(id), is(before));
    assertThat(points(id).compareTo(new BigDecimal("80")), is(0));
  }

  private void erase(String customerId) {
    Response erased = as("/customers/" + customerId, tenant, manager, "OWNER").delete();
    assertThat(erased.readEntity(String.class), erased.getStatus(), is(204));
  }

  /** Everything a grant or a spend could move: points, store credit, their ledgers, events. */
  private String held(String customerId) throws SQLException {
    return written(customerId)
        + " credit="
        + credit(customerId).toPlainString()
        + " creditLedger="
        + count("store_credit_ledger", customerId)
        + " creditAccounts="
        + count("store_credit_accounts", customerId);
  }

  private static String creditRedeem(String amount, UUID order) {
    return "{\"amount\":"
        + amount
        + (order == null ? "" : ",\"orderId\":\"" + order + "\"")
        + ",\"reason\":\"tender\"}";
  }

  @Test
  @DisplayName(
      "Store credit issued by hand to an erased customer is refused 409 by owner and manager"
          + " alike, in any currency, a retry of an earlier issue too, and writes nothing")
  void anErasedCustomerIsIssuedNoStoreCredit() throws SQLException {
    String id = customer();
    String issuePath = "/customers/" + id + "/store-credit/issue";
    String firstIssue = key();
    assertThat(post(issuePath, "MANAGER", firstIssue, ISSUE).getStatus(), is(200));
    erase(id);
    String before = held(id);

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertRefused(
          role + " issuing", post(issuePath, role, key(), ISSUE), 409, "CUSTOMER_ANONYMIZED");
      assertRefused(
          role + " issuing in another currency",
          post(
              issuePath,
              role,
              key(),
              "{\"amount\":500,\"currency\":\"JPY\",\"reason\":\"goodwill\"}"),
          409,
          "CUSTOMER_ANONYMIZED");
    }
    // A retry of an issue made before the erasure gives nothing more either.
    assertRefused(
        "a retried earlier issue",
        post(issuePath, "MANAGER", firstIssue, ISSUE),
        409,
        "CUSTOMER_ANONYMIZED");
    // Below management it is still the role that is refused, before the record is looked at.
    assertRefused("a cashier issuing", post(issuePath, "CASHIER", key(), ISSUE), 403, "FORBIDDEN");
    // Another business's management still finds no such customer.
    assertRefused(
        "another business's owner",
        post(issuePath, other, "OWNER", key(), ISSUE, Ids.newId()),
        404,
        "CUSTOMER_NOT_FOUND");

    // Nothing moved: no credit, no ledger entry, no account in another currency, no grant, no
    // event.
    assertThat(held(id), is(before));
    assertThat(credit(id).compareTo(new BigDecimal("15")), is(0));
    assertThat(count("store_credit_accounts", id), is(1L));
  }

  @Test
  @DisplayName(
      "An erased customer's points and store credit are not spent: a new spend is refused 409"
          + " whoever asks, a spend made before the erasure answers its retry with nothing more"
          + " taken, and nothing moves")
  void anErasedCustomerSpendsNothingNew() throws SQLException {
    String id = seeded("100");
    String pointsPath = "/customers/" + id + "/loyalty/redeem";
    String creditPath = "/customers/" + id + "/store-credit/redeem";
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "OWNER", key(), ISSUE).getStatus(),
        is(200));
    UUID pointsOrder = Ids.newId();
    UUID creditOrder = Ids.newId();
    String pointsKey = key();
    assertThat(post(pointsPath, "CASHIER", null, redeem("30", pointsOrder)).getStatus(), is(200));
    assertThat(post(pointsPath, "CASHIER", pointsKey, pointsBody("10")).getStatus(), is(200));
    assertThat(
        post(creditPath, "CASHIER", null, creditRedeem("5.00", creditOrder)).getStatus(), is(200));
    erase(id);
    String before = held(id);

    for (String role : new String[] {"CASHIER", "MANAGER", "OWNER"}) {
      assertRefused(
          role + " spending points for a new order",
          post(pointsPath, role, null, redeem("10", Ids.newId())),
          409,
          "CUSTOMER_ANONYMIZED");
      assertRefused(
          role + " spending points under a new key",
          post(pointsPath, role, key(), pointsBody("10")),
          409,
          "CUSTOMER_ANONYMIZED");
      assertRefused(
          role + " spending points with neither an order nor a key",
          post(pointsPath, role, null, pointsBody("10")),
          409,
          "CUSTOMER_ANONYMIZED");
      assertRefused(
          role + " spending store credit for a new order",
          post(creditPath, role, null, creditRedeem("5.00", Ids.newId())),
          409,
          "CUSTOMER_ANONYMIZED");
      assertRefused(
          role + " spending store credit with no order",
          post(creditPath, role, null, creditRedeem("5.00", null)),
          409,
          "CUSTOMER_ANONYMIZED");
    }

    // A spend recorded before the erasure answers its retry as it stands: the tender it paid is
    // recorded from that answer, and nothing more is taken.
    assertThat(
        data(post(pointsPath, "CASHIER", null, redeem("30", pointsOrder)))
            .getJsonNumber("pointsBalance")
            .bigDecimalValue()
            .compareTo(new BigDecimal("60")),
        is(0));
    assertThat(
        data(post(pointsPath, "CASHIER", pointsKey, pointsBody("10")))
            .getJsonNumber("pointsBalance")
            .bigDecimalValue()
            .compareTo(new BigDecimal("60")),
        is(0));
    assertThat(
        data(post(creditPath, "CASHIER", null, creditRedeem("5.00", creditOrder)))
            .getJsonNumber("balance")
            .bigDecimalValue()
            .compareTo(BigDecimal.TEN),
        is(0));
    // Another business's till still finds no such customer.
    assertRefused(
        "another business's cashier",
        post(creditPath, other, "CASHIER", null, creditRedeem("5.00", Ids.newId()), Ids.newId()),
        404,
        "CUSTOMER_NOT_FOUND");

    assertThat(held(id), is(before));
  }

  // ── a store-credit spend is once per order, when the calls race too ───────────

  /** Fires every call at once and gives back each one's status and body, in no order. */
  private List<String[]> race(int calls, java.util.function.Supplier<Response> call)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls);
    try {
      CountDownLatch ready = new CountDownLatch(calls);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<String[]>> answers = new ArrayList<>();
      for (int i = 0; i < calls; i++) {
        answers.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  Response r = call.get();
                  return new String[] {String.valueOf(r.getStatus()), r.readEntity(String.class)};
                }));
      }
      ready.await();
      go.countDown();
      List<String[]> out = new ArrayList<>();
      for (Future<String[]> a : answers) out.add(a.get(60, TimeUnit.SECONDS));
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  private long redeemsFor(String customerId, UUID order) throws SQLException {
    return Long.parseLong(
        sql(
            "SELECT COUNT(*) FROM customer.store_credit_ledger WHERE tenant_id = ?"
                + " AND customer_id = ? AND order_id = ? AND type = 'REDEEM'",
            tenant,
            Ids.parse(customerId),
            order));
  }

  private static BigDecimal balanceIn(String body) {
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getJsonNumber("balance")
        .bigDecimalValue();
  }

  @Test
  @DisplayName(
      "Store-credit spends for one order fired at once take the credit once: every call answers"
          + " 200 with the balance after the one spend, and one REDEEM is recorded")
  void racingSpendsForOneOrderTakeOnce() throws Exception {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "OWNER", key(), ISSUE).getStatus(),
        is(200));
    UUID order = Ids.newId();
    String body = creditRedeem("5.00", order);

    List<String[]> answers =
        race(8, () -> post("/customers/" + id + "/store-credit/redeem", "CASHIER", null, body));

    for (String[] a : answers) {
      assertThat(a[1], a[0], is("200"));
      // 15 − 5 once. A second deduction would answer 5, a third 0.
      assertThat(a[1], balanceIn(a[1]).compareTo(BigDecimal.TEN), is(0));
    }
    assertThat(credit(id).compareTo(BigDecimal.TEN), is(0));
    assertThat(redeemsFor(id, order), is(1L));
    // The seed's ISSUE and the one REDEEM: nothing else in the ledger.
    assertThat(count("store_credit_ledger", id), is(2L));
  }

  @Test
  @DisplayName(
      "After an erasure, retries of a spend made before it fired at once all answer it as it"
          + " stands, while new spends fired with them are refused 409: nothing more is taken")
  void racingRetriesForAnErasedCustomerTakeNothingMore() throws Exception {
    String id = customer();
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "OWNER", key(), ISSUE).getStatus(),
        is(200));
    UUID order = Ids.newId();
    String creditPath = "/customers/" + id + "/store-credit/redeem";
    assertThat(post(creditPath, "CASHIER", null, creditRedeem("5.00", order)).getStatus(), is(200));
    erase(id);
    String before = held(id);

    java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
    List<String[]> answers =
        race(
            8,
            () ->
                n.getAndIncrement() % 2 == 0
                    ? post(creditPath, "CASHIER", null, creditRedeem("5.00", order))
                    : post(creditPath, "CASHIER", null, creditRedeem("5.00", Ids.newId())));

    int replays = 0;
    for (String[] a : answers) {
      if ("200".equals(a[0])) {
        replays++;
        assertThat(a[1], balanceIn(a[1]).compareTo(BigDecimal.TEN), is(0));
      } else {
        assertThat(a[1], a[0], is("409"));
        assertThat(a[1], codeOf(a[1]), is("CUSTOMER_ANONYMIZED"));
      }
    }
    assertThat(replays, is(4));
    assertThat(redeemsFor(id, order), is(1L));
    assertThat(held(id), is(before));
  }
}
