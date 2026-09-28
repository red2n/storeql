package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Self-serve signup and trials (21.13), over HTTP and a real database.
 *
 * <p>A prospect reads the price list with no login; a business chooses a plan as it signs up and
 * starts on that plan's trial; a login owns one business, so a second signup is a second trial
 * refused; a trial that is about to end is announced once, and the day it ends the first invoice is
 * announced with the link that pays it — through the outbox, as every notice is.
 */
@HelidonTest
@AddConfig(key = "storeql.billing.test-clock.enabled", value = "true")
class SignupTrialIT {
  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");
  private static final String PLANS = "/platform/plans";
  private static final String BILLING = "/platform/billing";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the price list ───────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A prospect reads the plans on sale with no login: what they cost, what they include, the trial")
  void theProspectReadsThePriceListWithNoLogin() {
    sellerIs();
    String sold = plan("TRIAL-PUB-" + tail(), 14, true, true);
    String drafted = draft("TRIAL-DRAFT-" + tail());
    String hidden = plan("TRIAL-HID-" + tail(), 0, false, true);

    Response r = target.path("/plans").request(MediaType.APPLICATION_JSON).get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    List<JsonObject> plans = parse(body).getJsonArray("data").getValuesAs(JsonObject.class);
    List<String> ids = plans.stream().map(p -> p.getString("id")).toList();
    assertThat("on sale and public: listed", ids, org.hamcrest.Matchers.hasItem(sold));
    assertThat("a draft is not for sale", ids, not(org.hamcrest.Matchers.hasItem(drafted)));
    assertThat(
        "a private plan is sold by hand, not on the list",
        ids,
        not(org.hamcrest.Matchers.hasItem(hidden)));
    JsonObject listed =
        plans.stream().filter(p -> p.getString("id").equals(sold)).findFirst().orElseThrow();
    assertThat(listed.getInt("trialDays"), is(14));
    assertThat(listed.getJsonArray("prices").size(), is(1));
    assertThat(listed.getJsonArray("includes").toString(), containsString("stores.max"));
    assertThat("nothing a prospect cannot act on", body, not(containsString("createdBy")));
  }

  // ── choosing a plan at signup ────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A business chooses a plan as it signs up and starts on that plan's trial; one login owns one business")
  void aBusinessChoosesItsPlanAndItsTrialAtSignup() {
    sellerIs();
    String chosen = plan("TRIAL-CHOSEN-" + tail(), 10, true, true);
    String drafted = draft("TRIAL-NOT-SOLD-" + tail());
    String owner = Ids.newId().toString();

    Response refused = signup(owner, "Drafted plan", drafted);
    String refusedBody = refused.readEntity(String.class);
    assertThat(refusedBody, refused.getStatus(), is(409));
    assertThat(refusedBody, containsString("PLAN_NOT_SOLD"));

    Response created = signup(owner, "Chosen plan", chosen);
    String createdBody = created.readEntity(String.class);
    assertThat(createdBody, created.getStatus(), is(201));
    String tenant = parse(createdBody).getJsonObject("data").getString("id");
    JsonObject mine =
        ok(owner("GET", "/admin/tenant/billing", null, tenant, owner))
            .getJsonObject("subscription");
    assertThat(mine.getString("planId"), is(chosen));
    assertThat(mine.getString("status"), is("TRIALING"));
    assertThat(mine.getString("trialEnd"), is(LocalDate.now().plusDays(10).toString()));

    Response again = signup(owner, "A second business", chosen);
    String againBody = again.readEntity(String.class);
    assertThat("a login owns one business: a second is a second trial", again.getStatus(), is(409));
    assertThat(againBody, containsString("TENANT_ALREADY_OWNED"));
  }

  // ── the trial's end is announced ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "A trial about to end is announced once, and the day it ends the first invoice is announced with the way to pay")
  void theTrialsEndIsAnnouncedOnceAndItsFirstInvoiceWithTheWayToPay() throws Exception {
    sellerIs();
    String chosen = plan("TRIAL-NOTICE-" + tail(), 14, true, true);
    String owner = Ids.newId().toString();
    Response created = signup(owner, "Noticed trial", chosen);
    String tenant = parse(created.readEntity(String.class)).getJsonObject("data").getString("id");
    LocalDate trialEnd = LocalDate.now().plusDays(14);

    // Too early: nothing is said.
    assertThat(
        platform("POST", BILLING + "/run?asOf=" + trialEnd.minusDays(5), null).getStatus(),
        is(200));
    assertThat(notices(tenant), is(empty()));

    // Three days before: once, naming the day and what the plan will cost.
    assertThat(
        platform("POST", BILLING + "/run?asOf=" + trialEnd.minusDays(3), null).getStatus(),
        is(200));
    List<String> ending = notices(tenant);
    assertThat(ending, hasSize(1));
    assertThat(ending.get(0), containsString("\"stage\":\"ENDING\""));
    assertThat(ending.get(0), containsString("\"trialEnd\":\"" + trialEnd + "\""));
    assertThat(
        ending.get(0),
        containsString("\"recipient\":\"" + TenantOnboarding.ownerEmail("Noticed trial") + "\""));
    assertThat(ending.get(0), containsString("\"price\":49"));
    assertThat(ending.get(0), containsString("\"platform\":\"StoreQL Platform Ltd\""));
    assertThat(
        platform("POST", BILLING + "/run?asOf=" + trialEnd.minusDays(2), null).getStatus(),
        is(200));
    assertThat("the run again, a day later, says nothing more", notices(tenant), hasSize(1));

    // The day it ends: the first invoice, and the link that pays it.
    Response run = platform("POST", BILLING + "/run?asOf=" + trialEnd, null);
    assertThat(run.readEntity(String.class), run.getStatus(), is(200));
    List<String> all = notices(tenant);
    assertThat(all, hasSize(2));
    String ended = all.get(1);
    assertThat(ended, containsString("\"stage\":\"ENDED\""));
    Response invoices = owner("GET", "/admin/tenant/billing/invoices?limit=5", null, tenant, owner);
    String invoiceList = invoices.readEntity(String.class);
    assertThat(invoiceList, invoices.getStatus(), is(200));
    JsonObject invoice = parse(invoiceList).getJsonArray("data").getJsonObject(0);
    assertThat(ended, containsString("\"invoiceNumber\":\"" + invoice.getString("number") + "\""));
    assertThat(ended, containsString("\"payUrl\":\"http://localhost:8088/#/pay/"));
    String token = ended.replaceAll(".*\"payUrl\":\"[^\"]*/#/pay/([^\"]+)\".*", "$1");
    Response paid =
        target
            .path("/billing/pay/" + token)
            .request()
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(paid.readEntity(String.class), paid.getStatus(), is(200));
    JsonObject sub =
        ok(owner("GET", "/admin/tenant/billing", null, tenant, owner))
            .getJsonObject("subscription");
    assertThat(sub.getString("status"), is("ACTIVE"));
    assertThat(
        "the trial is over on the file too",
        steps(tenant),
        org.hamcrest.Matchers.hasItem("TRIAL_ENDED"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static String tail() {
    return Ids.newId().toString().substring(28);
  }

  private void sellerIs() {
    Response saved =
        platform(
            "PUT",
            BILLING + "/profile",
            "{\"legalName\":\"StoreQL Platform Ltd\",\"addressLine1\":\"1 Quay Street\","
                + "\"city\":\"Dublin\",\"country\":\"IE\",\"vatNumber\":\"IE1234567X\","
                + "\"invoicePrefix\":\"INV\",\"paymentTermsDays\":7,\"taxRate\":\"0.2300\"}");
    assertThat(saved.readEntity(String.class), saved.getStatus(), is(200));
  }

  private String draft(String code) {
    Response written = platform("POST", PLANS, planJson(code, 14, true));
    String body = written.readEntity(String.class);
    assertThat(body, written.getStatus(), is(201));
    return parse(body).getJsonObject("data").getString("id");
  }

  /** A plan on sale, at 49 a month, allowing two stores. */
  private String plan(String code, int trialDays, boolean isPublic, boolean sell) {
    Response written = platform("POST", PLANS, planJson(code, trialDays, isPublic));
    String body = written.readEntity(String.class);
    assertThat(body, written.getStatus(), is(201));
    String id = parse(body).getJsonObject("data").getString("id");
    assertThat(
        platform("POST", PLANS + "/" + id + "/prices", "{\"currency\":\"EUR\",\"amount\":49}")
            .getStatus(),
        is(200));
    assertThat(
        platform(
                "PUT",
                PLANS + "/" + id + "/includes",
                "{\"grants\":[{\"key\":\"stores.max\",\"limitValue\":2}]}")
            .getStatus(),
        is(200));
    if (sell)
      assertThat(platform("POST", PLANS + "/" + id + "/activate", null).getStatus(), is(200));
    return id;
  }

  private static String planJson(String code, int trialDays, boolean isPublic) {
    return "{\"code\":\""
        + code
        + "\",\"name\":\""
        + code
        + " plan\",\"billingInterval\":\"MONTH\","
        + "\"trialDays\":"
        + trialDays
        + ",\"isPublic\":"
        + isPublic
        + ",\"sortOrder\":1}";
  }

  private Response signup(String ownerUserId, String name, String planId) {
    return target
        .path("/onboarding/tenants")
        .request(MediaType.APPLICATION_JSON)
        .header("X-User-Id", ownerUserId)
        .header("X-User-Email", TenantOnboarding.ownerEmail(name))
        .post(
            Entity.entity(
                "{\"businessName\":\""
                    + name
                    + " "
                    + Ids.newId()
                    + "\",\"country\":\"IE\","
                    + "\"currency\":\"EUR\""
                    + (planId == null ? "" : ",\"planId\":\"" + planId + "\"")
                    + "}",
                MediaType.APPLICATION_JSON));
  }

  private Response platform(String method, String path, String json) {
    return call(method, path, json, null, "PLATFORM_ADMIN", Ids.newId().toString());
  }

  private Response owner(String method, String path, String json, String tenant, String user) {
    return call(method, path, json, tenant, "OWNER", user);
  }

  private Response call(
      String method, String path, String json, String tenant, String roles, String user) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        t = t.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    Invocation.Builder b =
        t.request(MediaType.APPLICATION_JSON).header("X-User-Id", user).header("X-Roles", roles);
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    Entity<String> body = Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(body);
      default -> b.post(body);
    };
  }

  private static JsonObject ok(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonObject("data");
  }

  private static JsonObject parse(String text) {
    try (var reader = Json.createReader(new StringReader(text))) {
      return reader.readObject();
    }
  }

  /** Every trial notice queued for a business, oldest first, as notification-svc will read it. */
  private static List<String> notices(String tenant) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT payload FROM outbox WHERE tenant_id = ?::uuid"
                  + " AND event_type = 'TrialNoticeIssued' ORDER BY created_at, id")) {
        ps.setString(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static List<String> steps(String tenant) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT kind FROM subscription_events WHERE tenant_id = ?::uuid ORDER BY created_at")) {
        ps.setString(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.add(rs.getString(1));
        }
      }
    }
    return out;
  }
}
