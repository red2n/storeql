package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.AddConfig;
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
 * Dunning (21.12), over HTTP and a real database.
 *
 * <p>The assertion this class exists for is the last one: <b>an administrator's suspension survives
 * a payment.</b> Paying up is supposed to bring a business back, and it must bring back only the
 * business the platform itself switched off for money — a decision somebody took deliberately is
 * not an argument a payment can win. Everything else here is the idempotency that makes the run
 * safe to run twice, which only a database can answer.
 */
@HelidonTest
@AddConfig(key = "storeql.billing.test-clock.enabled", value = "true")
class DunningIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String BILLING = "/platform/billing";
  private static final String DUNNING = BILLING + "/dunning";
  private static final String PLANS = "/platform/plans";
  private static final String PLATFORM = "/platform";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the notice itself (SJ-D68) ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "Each reminder is a notice to the business's billing address, carrying a link that pays")
  void aReminderIsANoticeToTheBillingAddressWithALinkThatPays() throws Exception {
    sellerIs();
    planOnSale("NOTICE-" + Ids.newId().toString().substring(28));
    Answer policy =
        platform(
            "PUT",
            DUNNING + "/policy",
            "{\"enabled\":true,\"reminderDays\":[1,2],\"suspendAfterDays\":3,"
                + "\"uncollectibleAfterDays\":30}");
    assertThat(policy.text(), policy.status(), is(200));
    String tenant = onboard("Noticed");
    JsonObject invoice = onlyInvoice(tenant);
    String id = invoice.getString("id");
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));

    // Where it goes: the owner's sign-up address, until the business names another.
    Answer renamed =
        owner(
            "PUT",
            "/admin/tenant/billing/details",
            "{\"country\":\"IE\",\"billingEmail\":\"accounts@noticed.example\"}",
            tenant);
    assertThat(renamed.text(), renamed.status(), is(200));

    Answer run = platform("POST", DUNNING + "/run?asOf=" + due.plusDays(1), null);
    assertThat(run.text(), run.status(), is(200));
    List<String> notices = notices(tenant);
    assertThat("one reminder, one notice", notices, hasSize(1));
    String payload = notices.get(0);
    assertThat(payload, containsString("\"eventType\":\"DunningNoticeIssued\""));
    assertThat(payload, containsString("\"step\":\"REMINDER_1\""));
    assertThat(
        payload, containsString("\"invoiceNumber\":\"" + invoice.getString("number") + "\""));
    assertThat(payload, containsString("\"recipient\":\"accounts@noticed.example\""));
    assertThat(payload, containsString("\"platform\":\"StoreQL Platform Ltd\""));
    assertThat(
        "what is left on it, VAT included",
        payload,
        containsString(
            "\"amountDue\":"
                + invoice.getJsonNumber("totalAmount").bigDecimalValue().toPlainString()));
    assertThat(
        "when the service goes unless paid",
        payload,
        containsString("\"suspendOn\":\"" + due.plusDays(3) + "\""));
    assertThat(payload, containsString("\"payUrl\":\"http://localhost:8088/#/pay/"));

    // A run that runs again owes nothing more: the step, the link and the notice are one claim.
    assertThat(platform("POST", DUNNING + "/run?asOf=" + due.plusDays(1), null).status(), is(200));
    assertThat(notices(tenant), hasSize(1));
    assertThat(steps(id), is(List.of("REMINDER_1")));

    // The second reminder replaces the link: the newest notice is the one to act on.
    String first = tokenIn(payload);
    assertThat(platform("POST", DUNNING + "/run?asOf=" + due.plusDays(2), null).status(), is(200));
    List<String> two = notices(tenant);
    assertThat(two, hasSize(2));
    assertThat(two.get(1), containsString("\"step\":\"REMINDER_2\""));
    assertThat(
        "an older notice's link no longer pays",
        call("POST", "/billing/pay/" + first, null, null, null).status(),
        is(404));

    // The link in the notice pays, with no sign-in, and is then spent.
    String token = tokenIn(two.get(1));
    Answer paid = call("POST", "/billing/pay/" + token, null, null, null);
    assertThat(paid.text(), paid.status(), is(200));
    assertThat(paid.data().getString("status"), is("PAID"));
    assertThat(call("POST", "/billing/pay/" + token, null, null, null).status(), is(404));
    assertThat(steps(id), hasItem("RESOLVED"));
  }

  @Test
  @DisplayName("The suspension is a notice too, with the way back in it")
  void theSuspensionIsANoticeWithTheWayBackInIt() throws Exception {
    sellerIs();
    planOnSale("NOTICE-S-" + Ids.newId().toString().substring(28));
    assertThat(
        platform(
                "PUT",
                DUNNING + "/policy",
                "{\"enabled\":true,\"reminderDays\":[1],\"suspendAfterDays\":2,"
                    + "\"uncollectibleAfterDays\":30}")
            .status(),
        is(200));
    String tenant = onboard("Cut off");
    JsonObject invoice = onlyInvoice(tenant);
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));

    assertThat(platform("POST", DUNNING + "/run?asOf=" + due.plusDays(2), null).status(), is(200));
    assertThat(steps(invoice.getString("id")), is(List.of("REMINDER_1", "SUSPENDED")));
    assertThat(tenantStatus(tenant), is("INACTIVE"));
    List<String> notices = notices(tenant);
    assertThat(notices, hasSize(2));
    String suspension = notices.get(1);
    assertThat(suspension, containsString("\"step\":\"SUSPENDED\""));
    assertThat("nothing left to warn of", suspension, containsString("\"suspendOn\":null"));
    assertThat(
        "to the owner's sign-up address, since the business named no other",
        suspension,
        containsString("\"recipient\":\"" + TenantOnboarding.ownerEmail("Cut off") + "\""));

    // The link in the suspension notice is the way back: paying brings the business back.
    Answer paid = call("POST", "/billing/pay/" + tokenIn(suspension), null, null, null);
    assertThat(paid.text(), paid.status(), is(200));
    assertThat(tenantStatus(tenant), is("ACTIVE"));
  }

  @Test
  @DisplayName("A business with no billing address is named by the run, never chased in silence")
  void aBusinessWithNoAddressIsNamedNotChasedInSilence() throws Exception {
    sellerIs();
    planOnSale("NOTICE-N-" + Ids.newId().toString().substring(28));
    assertThat(
        platform(
                "PUT",
                DUNNING + "/policy",
                "{\"enabled\":true,\"reminderDays\":[1],\"suspendAfterDays\":2,"
                    + "\"uncollectibleAfterDays\":30}")
            .status(),
        is(200));
    String tenant = onboard("Unreachable");
    JsonObject invoice = onlyInvoice(tenant);
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));
    clearBillingEmail(tenant);

    // Well past suspension, and still: nothing is taken away from a business nobody could tell.
    Answer run = platform("POST", DUNNING + "/run?asOf=" + due.plusDays(5), null);
    assertThat(run.text(), run.status(), is(200));
    assertThat("named, by tenant", run.text(), containsString("\"tenantId\":\"" + tenant + "\""));
    assertThat(run.text(), containsString("no billing email"));
    assertThat(steps(invoice.getString("id")), is(empty()));
    assertThat(notices(tenant), is(empty()));
    assertThat(tenantStatus(tenant), is("ACTIVE"));
  }

  /** Every notice queued for a business, oldest first, as the event notification-svc will read. */
  private static List<String> notices(String tenant) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT payload FROM outbox WHERE tenant_id = ?::uuid"
                  + " AND event_type = 'DunningNoticeIssued' ORDER BY created_at, id")) {
        ps.setString(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static void clearBillingEmail(String tenant) throws Exception {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE subscriptions SET billing_email = NULL WHERE tenant_id = ?::uuid")) {
        ps.setString(1, tenant);
        assertThat(ps.executeUpdate(), is(1));
      }
    }
  }

  /** The token in a notice's pay link. */
  private static String tokenIn(String payload) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"payUrl\":\"[^\"]*/#/pay/([^\"]+)\"").matcher(payload);
    assertThat("the notice carries a pay link", m.find(), is(true));
    return m.group(1);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private record Answer(int status, JsonObject body, String text) {

    JsonObject data() {
      return body.getJsonObject("data");
    }

    List<JsonObject> list() {
      return body.getJsonArray("data").getValuesAs(JsonObject.class);
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  /** A query string is split off: {@link WebTarget#path(String)} percent-encodes a {@code ?}. */
  private Answer call(String method, String path, String json, String tenant, String roles) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        t =
            eq < 0
                ? t.queryParam(pair, "")
                : t.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    Invocation.Builder b = t.request(MediaType.APPLICATION_JSON).header("X-User-Id", Ids.newId());
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    Entity<String> body = Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON);
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "PUT" -> b.put(body);
          // The default JAX-RS connector is HttpURLConnection, which refuses PATCH outright. Same
          // workaround as WeighingInstrumentIT: the JDK's own client, which does not.
          case "PATCH" -> throw new UnsupportedOperationException("use patch()");
          default -> b.post(body);
        };
    String text = r.readEntity(String.class);
    return new Answer(r.getStatus(), asObject(text), text);
  }

  private static JsonObject asObject(String text) {
    if (text == null || text.isBlank()) return JsonObject.EMPTY_JSON_OBJECT;
    try {
      return Json.createReader(new StringReader(text)).readObject();
    } catch (RuntimeException e) {
      return JsonObject.EMPTY_JSON_OBJECT;
    }
  }

  /**
   * A PATCH, through the JDK's HTTP client.
   *
   * <p>The default JAX-RS connector is {@code HttpURLConnection}, which rejects PATCH as an invalid
   * method before the request leaves. {@code WeighingInstrumentIT} hit the same wall and does the
   * same thing.
   */
  private Answer patch(String path, String json) throws Exception {
    java.net.http.HttpResponse<String> r =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(target.path(path).getUri())
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .header("X-User-Id", Ids.newId().toString())
                    .header("X-Roles", "PLATFORM_ADMIN")
                    .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), asObject(r.body()), r.body());
  }

  private Answer platform(String method, String path, String json) {
    return call(method, path, json, null, "PLATFORM_ADMIN");
  }

  private Answer owner(String method, String path, String json, String tenantId) {
    return call(method, path, json, tenantId, "OWNER");
  }

  /** Nothing is billed, and so nothing is chased, until the platform has said who it is. */
  private void sellerIs() {
    Answer saved =
        platform(
            "PUT",
            BILLING + "/profile",
            "{\"legalName\":\"StoreQL Platform Ltd\",\"addressLine1\":\"1 Quay Street\","
                + "\"city\":\"Dublin\",\"country\":\"IE\",\"vatNumber\":\"IE1234567X\","
                + "\"invoicePrefix\":\"INV\",\"paymentTermsDays\":0,\"taxRate\":\"0.2300\"}");
    assertThat(saved.text(), saved.status(), is(200));
  }

  /** A plan on sale, with no trial and due the day it is issued, so it is overdue tomorrow. */
  private void planOnSale(String code) {
    Answer written =
        platform(
            "POST",
            PLANS,
            "{\"code\":\""
                + code
                + "\",\"name\":\""
                + code
                + " plan\",\"billingInterval\":\"MONTH\",\"trialDays\":0,\"isPublic\":true,"
                + "\"sortOrder\":1}");
    assertThat(written.text(), written.status(), is(201));
    String id = written.data().getString("id");
    assertThat(
        platform("POST", PLANS + "/" + id + "/prices", "{\"currency\":\"EUR\",\"amount\":10.00}")
            .status(),
        is(200));
    assertThat(platform("POST", PLANS + "/" + id + "/activate", null).status(), is(200));
    assertThat(platform("POST", PLANS + "/" + id + "/default", null).status(), is(200));
  }

  private String onboard(String name) {
    return TenantOnboarding.onboard(target, name, "IE", "EUR");
  }

  private JsonObject onlyInvoice(String tenantId) {
    List<JsonObject> invoices =
        owner("GET", "/admin/tenant/billing/invoices?limit=50", null, tenantId).list();
    assertThat("the business was billed for its first period", invoices.isEmpty(), is(false));
    return invoices.get(0);
  }

  private List<String> steps(String invoiceId) {
    return platform("GET", DUNNING + "/invoices/" + invoiceId + "/events", null).list().stream()
        .map(e -> e.getString("step"))
        .toList();
  }

  private String tenantStatus(String tenantId) {
    return platform("GET", PLATFORM + "/tenants/" + tenantId, null).data().getString("status");
  }

  private String deactivationReason(String tenantId) {
    JsonObject t = platform("GET", PLATFORM + "/tenants/" + tenantId, null).data();
    return t.containsKey("deactivatedReason") && !t.isNull("deactivatedReason")
        ? t.getString("deactivatedReason")
        : null;
  }

  /** A tight policy so a few days of the test clock walk the whole schedule. */
  private void tightPolicy() {
    Answer set =
        platform(
            "PUT",
            DUNNING + "/policy",
            "{\"enabled\":true,\"reminderDays\":[1,2],\"suspendAfterDays\":3,"
                + "\"uncollectibleAfterDays\":6}");
    assertThat(set.text(), set.status(), is(200));
  }

  private static String plus(LocalDate from, int days) {
    return from.plusDays(days).toString();
  }

  // ── the tests ──────────────────────────────────────────────────────────────

  // "A platform that has set no policy still chases, on the published defaults" is asserted by k6
  // `dunning-flow` instead. The policy is a singleton and these tests share one database, so once
  // any
  // other test has set it, a test asserting the defaults is asserting the order the runner picked.
  // `DunningPolicyTest` covers the defaults themselves without a database.

  @Test
  @DisplayName("The stages are refused out of order")
  void theStagesMustBeInOrder() {
    Answer early =
        platform(
            "PUT",
            DUNNING + "/policy",
            "{\"enabled\":true,\"reminderDays\":[1,20],\"suspendAfterDays\":14,"
                + "\"uncollectibleAfterDays\":30}");
    assertThat(early.status(), is(400));
    assertThat(early.code(), is("DUNNING_POLICY_INVALID"));

    Answer backwards =
        platform(
            "PUT",
            DUNNING + "/policy",
            "{\"enabled\":true,\"reminderDays\":[1],\"suspendAfterDays\":20,"
                + "\"uncollectibleAfterDays\":10}");
    assertThat(backwards.status(), is(400));
    assertThat(backwards.code(), is("DUNNING_POLICY_INVALID"));
  }

  @Test
  @DisplayName("A step happens once, however many times the run is run")
  void aStepHappensOnce() {
    sellerIs();
    tightPolicy();
    planOnSale("IT-DUN-ONCE-" + Ids.newId().toString().substring(0, 8));
    String shop = onboard("Chased once");
    JsonObject invoice = onlyInvoice(shop);
    String id = invoice.getString("id");
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));

    assertThat("nothing is owed on the due date itself", steps(id), is(List.of()));

    for (int i = 0; i < 3; i++) {
      assertThat(platform("POST", DUNNING + "/run?asOf=" + plus(due, 1), null).status(), is(200));
    }
    assertThat("three runs, one reminder", steps(id), is(List.of("REMINDER_1")));
  }

  @Test
  @DisplayName(
      "A run that has not run for days owes every notice that was missed, before suspending")
  void everyMissedNoticeComesBeforeTheSuspension() {
    sellerIs();
    tightPolicy();
    planOnSale("IT-DUN-MISS-" + Ids.newId().toString().substring(0, 8));
    String shop = onboard("Chased late");
    JsonObject invoice = onlyInvoice(shop);
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));

    // Straight to the day the service would be interrupted, with nothing sent yet.
    assertThat(platform("POST", DUNNING + "/run?asOf=" + plus(due, 3), null).status(), is(200));

    List<String> taken = steps(invoice.getString("id"));
    assertThat(
        "both reminders were owed and go first: suspending a business the platform never finished"
            + " telling was late is the opposite of what dunning is for",
        taken,
        is(List.of("REMINDER_1", "REMINDER_2", "SUSPENDED")));
    assertThat(tenantStatus(shop), is("INACTIVE"));
    assertThat(deactivationReason(shop), is("NON_PAYMENT"));
  }

  @Test
  @DisplayName("Giving up on the debt ends the subscription it was owed on")
  void givingUpEndsTheSubscription() {
    // SJ-D69: the move to CANCELLED set the status without cancelled_at, which the table's own
    // constraint refuses, so the write-off happened and the subscription never ended.
    sellerIs();
    tightPolicy();
    planOnSale("IT-DUN-GIVE-" + Ids.newId().toString().substring(28));
    String shop = onboard("Given up on");
    JsonObject invoice = onlyInvoice(shop);
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));

    Answer run = platform("POST", DUNNING + "/run?asOf=" + plus(due, 6), null);
    assertThat(run.text(), run.status(), is(200));

    assertThat(steps(invoice.getString("id")), hasItem("UNCOLLECTIBLE"));
    JsonObject sub =
        owner("GET", "/admin/tenant/billing", null, shop).data().getJsonObject("subscription");
    assertThat(sub.toString(), sub.getString("status"), is("CANCELLED"));
  }

  @Test
  @DisplayName("A pay link pays once, and a second use of it opens nothing")
  void aPayLinkPaysOnce() {
    sellerIs();
    tightPolicy();
    planOnSale("IT-DUN-PAY-" + Ids.newId().toString().substring(0, 8));
    String shop = onboard("Pays by link");
    JsonObject invoice = onlyInvoice(shop);
    String id = invoice.getString("id");
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));
    assertThat(platform("POST", DUNNING + "/run?asOf=" + plus(due, 3), null).status(), is(200));
    assertThat(tenantStatus(shop), is("INACTIVE"));

    String token =
        platform("GET", DUNNING + "/invoices/" + id + "/pay-link", null).data().getString("token");

    // No identity at all: the business is suspended and cannot sign in.
    Answer paid = call("POST", "/billing/pay/" + token, null, null, null);
    assertThat(paid.text(), paid.status(), is(200));
    assertThat(paid.data().getString("status"), is("PAID"));

    Answer again = call("POST", "/billing/pay/" + token, null, null, null);
    assertThat("an old link cannot pay it twice", again.status(), is(404));
    assertThat(again.code(), is("PAY_LINK_INVALID"));

    Answer nonsense = call("POST", "/billing/pay/" + token + "xx", null, null, null);
    assertThat("and a token nobody issued answers the same", nonsense.status(), is(404));
    assertThat(nonsense.code(), is("PAY_LINK_INVALID"));

    assertThat("paying up brings the business back", tenantStatus(shop), is("ACTIVE"));
    assertThat("and clears the reason", deactivationReason(shop), is((String) null));
    assertThat("the file ends somewhere", steps(id), hasItem("RESOLVED"));
  }

  @Test
  @DisplayName("An administrator's suspension survives a payment")
  void anAdministratorsSuspensionSurvivesAPayment() throws Exception {
    // The assertion this class exists for. Paying up lifts what the platform imposed for money and
    // nothing else: a decision somebody took is not an argument a payment can win. Before V24 the
    // reason was not recorded at all, so the two suspensions were indistinguishable.
    sellerIs();
    tightPolicy();
    planOnSale("IT-DUN-ADMIN-" + Ids.newId().toString().substring(0, 8));
    String shop = onboard("Switched off by hand");
    JsonObject invoice = onlyInvoice(shop);
    String id = invoice.getString("id");

    Answer off = patch(PLATFORM + "/tenants/" + shop + "/status", "{\"status\":\"INACTIVE\"}");
    assertThat(off.text(), off.status(), is(200));
    assertThat(tenantStatus(shop), is("INACTIVE"));
    assertThat(
        "recorded as the administrator's doing, not the platform's",
        deactivationReason(shop),
        is("ADMINISTRATOR"));

    // It pays everything it owes, in full.
    Answer settled =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":" + invoice.get("totalAmount") + ",\"method\":\"BANK_TRANSFER\"}");
    assertThat(settled.text(), settled.status(), is(200));
    assertThat(settled.data().getJsonObject("invoice").getString("status"), is("PAID"));

    assertThat("and is still switched off", tenantStatus(shop), is("INACTIVE"));
    assertThat(
        "for the reason an administrator gave, untouched",
        deactivationReason(shop),
        is("ADMINISTRATOR"));
  }

  @Test
  @DisplayName("A due date moves outwards only, and the extension is on the file")
  void aDueDateMovesOutwardsOnly() {
    sellerIs();
    tightPolicy();
    planOnSale("IT-DUN-DATE-" + Ids.newId().toString().substring(0, 8));
    String shop = onboard("Promises to pay");
    JsonObject invoice = onlyInvoice(shop);
    String id = invoice.getString("id");
    LocalDate due = LocalDate.parse(invoice.getString("dueDate"));

    Answer moved =
        platform(
            "PUT",
            BILLING + "/invoices/" + id + "/due-date",
            "{\"dueDate\":\"" + plus(due, 30) + "\",\"reason\":\"they promised to pay\"}");
    assertThat(moved.text(), moved.status(), is(200));
    assertThat(moved.data().getString("dueDate"), is(plus(due, 30)));

    Answer back =
        platform(
            "PUT",
            BILLING + "/invoices/" + id + "/due-date",
            "{\"dueDate\":\"" + plus(due, 5) + "\",\"reason\":\"back again\"}");
    assertThat("a date only ever moves out", back.status(), is(400));
    assertThat(back.code(), is("DUE_DATE_NOT_LATER"));

    assertThat(steps(id), hasItem("DUE_DATE_EXTENDED"));
    assertThat(
        "and the reason travels with it",
        platform("GET", DUNNING + "/invoices/" + id + "/events", null).text(),
        containsString("promised to pay"));

    // The chase is paused while the promise stands.
    assertThat(platform("POST", DUNNING + "/run?asOf=" + plus(due, 4), null).status(), is(200));
    assertThat(
        "nothing chased in the meantime",
        steps(id).stream().anyMatch(s -> s.startsWith("REMINDER")),
        is(false));
  }

  @Test
  @DisplayName("A business does not read or run the platform's own chasing")
  void aBusinessDoesNotRunTheChase() {
    sellerIs();
    String shop = onboard("Not its business");

    assertThat(owner("GET", DUNNING + "/policy", null, shop).status(), is(403));
    assertThat(
        owner(
                "PUT",
                DUNNING + "/policy",
                "{\"enabled\":false,\"reminderDays\":[1],\"suspendAfterDays\":2,"
                    + "\"uncollectibleAfterDays\":3}",
                shop)
            .status(),
        is(403));
    assertThat(owner("POST", DUNNING + "/run", null, shop).status(), is(403));
    assertThat(owner("GET", DUNNING + "/overdue", null, shop).status(), is(403));
    assertThat(
        "nor mint itself a pay link for an invoice",
        owner("GET", DUNNING + "/invoices/" + Ids.newId() + "/pay-link", null, shop).status(),
        is(403));
  }
}
