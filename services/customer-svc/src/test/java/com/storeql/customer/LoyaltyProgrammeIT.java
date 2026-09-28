package com.storeql.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.customer.service.CustomerService;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loyalty tiers and points expiry (13.x): the business's programme, tiers from qualifying points
 * with their multiplier on a sale, points spent from the lot that dies first, the sweep that lets
 * them die and tiers fall, a rule change with notice, and the shopper's own view.
 */
@HelidonTest
class LoyaltyProgrammeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  static {
    System.setProperty("storeql.customer.loyalty.sweep-seconds", "0");
  }

  private static final TenantSvcStub TENANTS = TenantSvcStub.start();
  private static final String OWNER = "01a090ae-7f1e-7f05-bde4-50df0324c37c";

  @Inject WebTarget target;
  @Inject CustomerService service;

  private UUID tenant;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    TENANTS.with(tenant.toString(), "GBP", "GB");
  }

  // ── the door ────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String role) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant.toString())
        .header("X-User-Id", OWNER)
        .header("X-Roles", role);
  }

  private Response put(String path, String role, String body) {
    return as(path, role).put(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private Response post(String path, String role, String body) {
    return as(path, role).post(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static JsonArray dataArray(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonArray("data");
  }

  private static double num(JsonObject o, String field) {
    return o.getJsonNumber(field).doubleValue();
  }

  private static String programme(Integer expiryMonths, Integer qualifyingMonths, String tiers) {
    return "{"
        + (expiryMonths == null ? "" : "\"expiryMonths\":" + expiryMonths + ",")
        + (qualifyingMonths == null ? "" : "\"qualifyingMonths\":" + qualifyingMonths + ",")
        + "\"tiers\":["
        + tiers
        + "],\"reason\":\"the autumn scheme\"}";
  }

  private static final String LADDER =
      "{\"name\":\"BRONZE\",\"threshold\":0,\"multiplier\":1},"
          + "{\"name\":\"SILVER\",\"threshold\":10,\"multiplier\":1.5},"
          + "{\"name\":\"GOLD\",\"threshold\":50,\"multiplier\":2}";

  private String customer(String email) {
    Response r =
        post(
            "/customers",
            "OWNER",
            "{\"email\":\"" + email + "\",\"firstName\":\"Loyal\",\"lastName\":\"Shopper\"}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  private JsonObject earn(String customerId, String points) {
    return data(
        post(
            "/customers/" + customerId + "/loyalty/earn",
            "OWNER",
            "{\"points\":" + points + ",\"reason\":\"test\"}"));
  }

  private JsonObject loyalty(String customerId) {
    return data(as("/customers/" + customerId + "/loyalty", "OWNER").get());
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

  // ── the programme ───────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A business starts on the platform's default, sets its own, and is refused by name for a ladder that cannot be honoured")
  void programmeDefaultsThenSet() {
    JsonObject d = data(as("/admin/loyalty/programme", "OWNER").get());
    assertThat(d.getBoolean("isDefault"), is(true));
    assertThat(d.getJsonArray("tiers").size(), is(4));
    assertThat(d.containsKey("expiryMonths") && !d.isNull("expiryMonths"), is(false));

    JsonObject set = data(put("/admin/loyalty/programme", "OWNER", programme(12, 12, LADDER)));
    assertThat(set.getBoolean("isDefault"), is(false));
    assertThat(set.getInt("expiryMonths"), is(12));
    assertThat(set.getInt("qualifyingMonths"), is(12));
    assertThat(set.getJsonArray("tiers").getJsonObject(1).getString("name"), is("SILVER"));
    assertThat(num(set.getJsonArray("tiers").getJsonObject(2), "multiplier"), closeTo(2.0, 0.001));
    assertThat(set.getString("reason"), is("the autumn scheme"));
    assertThat(set.getString("setBy"), is(OWNER));
    assertThat(
        data(as("/admin/loyalty/programme", "MANAGER").get()).getBoolean("isDefault"), is(false));

    Response descending =
        put(
            "/admin/loyalty/programme",
            "OWNER",
            programme(
                12,
                12,
                "{\"name\":\"BRONZE\",\"threshold\":0},{\"name\":\"GOLD\",\"threshold\":50},{\"name\":\"SILVER\",\"threshold\":10}"));
    String body = descending.readEntity(String.class);
    assertThat(body, descending.getStatus(), is(400));
    assertThat(body, containsString("LOYALTY_TIERS_INVALID"));
    Response noMonths = put("/admin/loyalty/programme", "OWNER", programme(0, 12, LADDER));
    body = noMonths.readEntity(String.class);
    assertThat(body, noMonths.getStatus(), is(400));
    assertThat(body, containsString("LOYALTY_EXPIRY_INVALID"));
    assertThat(
        put("/admin/loyalty/programme", "OWNER", "{\"tiers\":[],\"reason\":\"x\"}").getStatus(),
        is(400));
    assertThat(
        put("/admin/loyalty/programme", "CASHIER", programme(12, 12, LADDER)).getStatus(), is(403));
    assertThat(as("/admin/loyalty/programme", "CASHIER").get().getStatus(), is(403));
    assertThat(post("/admin/loyalty/expiry/run", "STOREKEEPER", "").getStatus(), is(403));
  }

  // ── tiers and the multiplier ────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Reaching a tier is announced, its multiplier applies to the next sale and says so, and the account shows the way to the next")
  void tiersFromQualifyingPointsWithMultipliedEarning() throws SQLException {
    data(put("/admin/loyalty/programme", "OWNER", programme(12, 12, LADDER)));
    String id = customer("tiers@example.com");
    JsonObject after = earn(id, "12");
    assertThat(after.getString("tier"), is("SILVER"));
    JsonObject view = loyalty(id);
    assertThat(view.getString("tier"), is("SILVER"));
    assertThat(num(view, "qualifyingPoints"), closeTo(12.0, 0.001));
    assertThat(num(view, "multiplier"), closeTo(1.5, 0.001));
    assertThat(view.getJsonObject("nextTier").getString("name"), is("GOLD"));
    assertThat(num(view.getJsonObject("nextTier"), "pointsToGo"), closeTo(38.0, 0.001));
    assertThat(view.getString("tierSince"), not(nullValue()));
    assertThat(view.getInt("expiryMonths"), is(12));
    assertThat(view.containsKey("expiringSoon") && !view.isNull("expiringSoon"), is(false));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.outbox WHERE tenant_id = ? AND event_type = 'LoyaltyTierChanged'"
                + " AND payload LIKE '%\"toTier\":\"SILVER\"%'",
            tenant),
        is("1"));

    // A £10.00 sale at SILVER: ten base points at ×1.5.
    service.accrueLoyaltyFromOrder(
        Ids.newId(), tenant, Ids.parse(id), Ids.newId(), new BigDecimal("10.00"), BigDecimal.ZERO);
    JsonObject account = loyalty(id);
    assertThat(num(account, "pointsBalance"), closeTo(27.0, 0.001));
    JsonArray ledger =
        dataArray(
            WebTargets.at(target, "/customers/" + id + "/loyalty/ledger?limit=5")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", tenant.toString())
                .header("X-User-Id", OWNER)
                .header("X-Roles", "OWNER")
                .get());
    JsonObject sale = ledger.getJsonObject(0);
    assertThat(num(sale, "points"), closeTo(15.0, 0.001));
    assertThat(sale.getString("reason"), containsString("SILVER ×1.5"));
    // Two lots, each dying in twelve months.
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.loyalty_point_lots WHERE tenant_id = ? AND customer_id = ?"
                + " AND remaining > 0 AND expires_at > now() + interval '11 months'",
            tenant,
            Ids.parse(id)),
        is("2"));
  }

  // ── lots, spending order and the sweep ──────────────────────────────────────

  @Test
  @DisplayName(
      "Points are spent from the lot that dies first, the sweep writes off what died and announces it, and a second sweep finds nothing")
  void lotsDieInOrder() throws SQLException {
    data(put("/admin/loyalty/programme", "OWNER", programme(12, null, LADDER)));
    String id = customer("lots@example.com");
    UUID customerId = Ids.parse(id);
    earn(id, "30");
    earn(id, "20");
    // The first lot dies yesterday; the second in a year.
    sql(
        "UPDATE customer.loyalty_point_lots SET expires_at = now() - interval '1 day'"
            + " WHERE tenant_id = ? AND customer_id = ? AND points = 30",
        tenant,
        customerId);
    data(
        post(
            "/customers/" + id + "/loyalty/redeem",
            "OWNER",
            "{\"points\":10,\"reason\":\"a discount\"}"));
    assertThat(
        sql(
            "SELECT remaining FROM customer.loyalty_point_lots WHERE tenant_id = ? AND customer_id = ?"
                + " AND points = 30",
            tenant,
            customerId),
        is("20.00"));
    assertThat(
        sql(
            "SELECT remaining FROM customer.loyalty_point_lots WHERE tenant_id = ? AND customer_id = ?"
                + " AND points = 20",
            tenant,
            customerId),
        is("20.00"));
    // The dying lot shows as expiring; the balance is 40.
    JsonObject before = loyalty(id);
    assertThat(num(before, "pointsBalance"), closeTo(40.0, 0.001));
    assertThat(num(before.getJsonObject("expiringSoon"), "points"), closeTo(20.0, 0.001));

    JsonObject run = data(post("/admin/loyalty/expiry/run", "OWNER", ""));
    assertThat(run.getInt("customers"), is(1));
    assertThat(num(run, "points"), closeTo(20.0, 0.001));
    JsonObject after = loyalty(id);
    assertThat(num(after, "pointsBalance"), closeTo(20.0, 0.001));
    assertThat(after.containsKey("expiringSoon") && !after.isNull("expiringSoon"), is(false));
    JsonArray ledger =
        dataArray(
            WebTargets.at(target, "/customers/" + id + "/loyalty/ledger?limit=5")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", tenant.toString())
                .header("X-User-Id", OWNER)
                .header("X-Roles", "OWNER")
                .get());
    JsonObject expire = ledger.getJsonObject(0);
    assertThat(expire.getString("type"), is("EXPIRE"));
    assertThat(num(expire, "points"), closeTo(-20.0, 0.001));
    assertThat(expire.getString("reason"), containsString("expired under the 12-month rule"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.loyalty_point_lots WHERE tenant_id = ? AND customer_id = ?"
                + " AND remaining = 0 AND expired_entry_id IS NOT NULL",
            tenant,
            customerId),
        is("1"));
    assertThat(
        sql(
            "SELECT payload FROM customer.outbox WHERE tenant_id = ? AND event_type = 'LoyaltyExpired'",
            tenant),
        containsString("\"points\":20"));
    JsonObject again = data(post("/admin/loyalty/expiry/run", "OWNER", ""));
    assertThat(again.getInt("customers"), is(0));
    assertThat(num(again, "points"), closeTo(0.0, 0.001));
  }

  // ── a rule that changes, and a tier that falls ──────────────────────────────

  @Test
  @DisplayName(
      "A new expiry rule gives points already held a month's notice, and lifting it lets them live; a tier falls when its earning leaves the window")
  void ruleChangesWithNoticeAndTiersFall() throws SQLException {
    data(put("/admin/loyalty/programme", "OWNER", programme(null, 12, LADDER)));
    String id = customer("notice@example.com");
    UUID customerId = Ids.parse(id);
    earn(id, "40");
    assertThat(loyalty(id).getString("tier"), is("SILVER"));
    // Earned two years ago, by the record.
    sql(
        "UPDATE customer.loyalty_point_lots SET earned_at = now() - interval '2 years'"
            + " WHERE tenant_id = ? AND customer_id = ?",
        tenant,
        customerId);
    data(put("/admin/loyalty/programme", "OWNER", programme(12, 12, LADDER)));
    JsonObject soon = loyalty(id).getJsonObject("expiringSoon");
    assertThat(num(soon, "points"), closeTo(40.0, 0.001));
    long daysAway = ChronoUnit.DAYS.between(Instant.now(), Instant.parse(soon.getString("on")));
    assertThat((double) daysAway, closeTo(30.0, 1.0));
    data(put("/admin/loyalty/programme", "OWNER", programme(null, 12, LADDER)));
    JsonObject lifted = loyalty(id);
    assertThat(lifted.containsKey("expiringSoon") && !lifted.isNull("expiringSoon"), is(false));
    assertThat(lifted.containsKey("expiryMonths") && !lifted.isNull("expiryMonths"), is(false));

    // The earning that reached SILVER is thirteen months old: the sweep drops the tier and says so.
    sql(
        "UPDATE customer.loyalty_ledger SET created_at = now() - interval '13 months'"
            + " WHERE tenant_id = ? AND customer_id = ? AND type = 'EARN'",
        tenant,
        customerId);
    JsonObject run = data(post("/admin/loyalty/expiry/run", "OWNER", ""));
    assertThat(run.getInt("retiered"), is(1));
    JsonObject fallen = loyalty(id);
    assertThat(fallen.getString("tier"), is("BRONZE"));
    assertThat(num(fallen, "qualifyingPoints"), closeTo(0.0, 0.001));
    assertThat(num(fallen, "pointsBalance"), closeTo(40.0, 0.001));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.outbox WHERE tenant_id = ? AND event_type = 'LoyaltyTierChanged'"
                + " AND payload LIKE '%\"fromTier\":\"SILVER\",\"toTier\":\"BRONZE\"%'",
            tenant),
        is("1"));
  }

  // ── the shopper's own view ──────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A shopper reads their own points, tier and what is about to expire; without a record there is nothing yet")
  void myLoyalty() {
    data(put("/admin/loyalty/programme", "OWNER", programme(12, 12, LADDER)));
    String login = Ids.newId().toString();
    Invocation.Builder me =
        target
            .path("/customers/me")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant.toString())
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", login)
            .header("X-User-Email", "me@example.com");
    Response none =
        target
            .path("/customers/me/loyalty")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant.toString())
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", login)
            .header("X-User-Email", "me@example.com")
            .get();
    assertThat(none.getStatus(), is(404));
    JsonObject mine = data(me.post(Entity.entity("{}", MediaType.APPLICATION_JSON)));
    earn(mine.getString("id"), "12");
    JsonObject view =
        data(
            target
                .path("/customers/me/loyalty")
                .request(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", tenant.toString())
                .header("X-Roles", "CUSTOMER")
                .header("X-User-Id", login)
                .header("X-User-Email", "me@example.com")
                .get());
    assertThat(view.getString("tier"), is("SILVER"));
    assertThat(num(view, "pointsBalance"), closeTo(12.0, 0.001));
    assertThat(view.getJsonObject("nextTier").getString("name"), is("GOLD"));
    assertThat(num(view, "multiplier"), greaterThan(1.0));
  }
}
