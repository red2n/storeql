package com.storeql.customer;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.customer.service.MarketingConsentService;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The marketing-consent cascade: withdrawing the MARKETING purpose switches every marketing channel
 * off in the same step, a channel cannot be switched back on while it stands withdrawn, a business
 * under a per-purpose consent law (DPDP) needs the purpose granted before any channel and one
 * elsewhere does not, {@code allowance} answers no rather than sending, the start-up reconciliation
 * fixes what predates this rule, and none of it crosses a tenant.
 */
@HelidonTest
class MarketingPurposeGateIT {

  private static final PostgresSupport PG;

  /** A British business: no per-purpose consent law binds it. */
  private static final String GB = Ids.newId().toString();

  /** A second British business, for the isolation test — untouched by anything else here. */
  private static final String OTHER = Ids.newId().toString();

  /** An Indian business: the DPDP obligation binds it from well before these tests run. */
  private static final String IN = Ids.newId().toString();

  private static final String STAFF_USER = Ids.newId().toString();

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(GB, "GBP", "GB")
        .with(OTHER, "GBP", "GB")
        .with(IN, "INR", "IN")
        .withObligation("IN", "DPDP", "COUNTRY", "2020-01-01", null);
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "customer");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  // Kafka is disabled in-test; the reconciliation job is driven directly, as its own start-up
  // runner would drive it.
  @Inject MarketingConsentService marketing;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @Test
  @DisplayName(
      "Withdrawing MARKETING switches every channel off with PURPOSE_WITHDRAWN evidence, and"
          + " granting it back never switches one on by itself")
  void theCascadeAndTheGate() {
    String login = Ids.newId().toString();
    String email = login.substring(login.length() - 12) + "@example.co.uk";
    claim(GB, login, email);
    String id = data(shopper(GB, login, email, "/customers/me").get()).getString("id");

    // Elsewhere, a channel's own consent stands on its own: no purpose need be answered first.
    assertThat(setMarketing(GB, login, email, "EMAIL", true).getStatus(), is(200));
    assertThat(setMarketing(GB, login, email, "SMS", true).getStatus(), is(200));
    assertThat(granted(myMarketing(GB, login, email), "EMAIL"), is(true));
    assertThat(granted(myMarketing(GB, login, email), "SMS"), is(true));

    // Withdraw the purpose: the cascade switches every channel off, on the same step.
    data(
        shopper(GB, login, email, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"MARKETING\",\"granted\":false}]}")));
    JsonArray afterWithdrawal = myMarketing(GB, login, email);
    assertThat(granted(afterWithdrawal, "EMAIL"), is(false));
    assertThat(granted(afterWithdrawal, "SMS"), is(false));
    assertThat(basisOf(afterWithdrawal, "EMAIL"), is("NONE"));
    assertThat(
        "one PURPOSE_WITHDRAWN row per channel that was on",
        scalar(
            PG,
            "SELECT count(*) FROM customer.marketing_consent_log WHERE customer_id = '"
                + id
                + "' AND source = 'PURPOSE_WITHDRAWN'"),
        is("2"));

    // A channel cannot be switched back on while the purpose stands withdrawn.
    assertCode(setMarketing(GB, login, email, "EMAIL", true), 409, "MARKETING_PURPOSE_NOT_GRANTED");
    assertCode(
        staff(GB, "OWNER", "/customers/" + id + "/marketing")
            .put(json("{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}]}")),
        409,
        "MARKETING_PURPOSE_NOT_GRANTED");

    // Granting MARKETING back never switches a channel on by itself.
    data(
        shopper(GB, login, email, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"MARKETING\",\"granted\":true}]}")));
    assertThat(
        "granting the purpose alone does not re-open the channel",
        granted(myMarketing(GB, login, email), "EMAIL"),
        is(false));

    // Now that the purpose is granted, the channel can be switched on again.
    assertThat(setMarketing(GB, login, email, "EMAIL", true).getStatus(), is(200));
    assertThat(granted(myMarketing(GB, login, email), "EMAIL"), is(true));
  }

  @Test
  @DisplayName(
      "A business under the DPDP obligation refuses a channel until MARKETING is granted; a"
          + " business elsewhere accepts the channel's own consent alone")
  void dpdpGatesTheChannelElsewhereDoesNot() {
    publishNotice(IN);
    String inLogin = Ids.newId().toString();
    String inEmail = inLogin.substring(inLogin.length() - 12) + "@example.in";
    claim(IN, inLogin, inEmail);

    // Nobody has answered MARKETING yet: the DPDP obligation binds, so the channel is refused.
    assertCode(
        setMarketing(IN, inLogin, inEmail, "EMAIL", true), 409, "MARKETING_PURPOSE_NOT_GRANTED");

    data(
        shopper(IN, inLogin, inEmail, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"MARKETING\",\"granted\":true}]}")));
    assertThat(
        "granted now: the channel may be switched on",
        setMarketing(IN, inLogin, inEmail, "EMAIL", true).getStatus(),
        is(200));

    String gbLogin = Ids.newId().toString();
    String gbEmail = gbLogin.substring(gbLogin.length() - 12) + "@example.co.uk";
    claim(GB, gbLogin, gbEmail);
    assertThat(
        "elsewhere: nobody has answered MARKETING and no per-purpose law binds, so the channel's"
            + " own consent is enough",
        setMarketing(GB, gbLogin, gbEmail, "EMAIL", true).getStatus(),
        is(200));
  }

  @Test
  @DisplayName("allowance says no once the purpose is withdrawn, even against a stale preference")
  void allowanceSaysNoAfterWithdrawal() {
    String login = Ids.newId().toString();
    String email = login.substring(login.length() - 12) + "@example.co.uk";
    claim(GB, login, email);
    String id = data(shopper(GB, login, email, "/customers/me").get()).getString("id");
    setMarketing(GB, login, email, "EMAIL", true);
    assertThat(allowed(GB, id, "EMAIL"), is(true));

    data(
        shopper(GB, login, email, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"MARKETING\",\"granted\":false}]}")));
    assertThat("the cascade already turned it off", allowed(GB, id, "EMAIL"), is(false));

    // A stale preference — as a row predating this rule would be — is still caught live: force
    // marketing_preferences back on directly, bypassing the API (and so the cascade) entirely.
    exec(
        PG,
        "UPDATE customer.marketing_preferences SET granted = TRUE, basis = 'CONSENT'"
            + " WHERE customer_id = '"
            + id
            + "' AND channel = 'EMAIL'");
    assertThat(
        "the preference row says on, but the live purpose gate still refuses",
        allowed(GB, id, "EMAIL"),
        is(false));
  }

  @Test
  @DisplayName("The start-up reconciliation fixes a stale row once; a second run changes nothing")
  void reconciliationIsIdempotent() {
    String login = Ids.newId().toString();
    String email = login.substring(login.length() - 12) + "@example.co.uk";
    claim(GB, login, email);
    String id = data(shopper(GB, login, email, "/customers/me").get()).getString("id");
    setMarketing(GB, login, email, "EMAIL", true);
    setMarketing(GB, login, email, "SMS", true);

    // Simulate data from before this rule existed: MARKETING withdrawn directly in the table, the
    // cascade never having run because there was none.
    exec(
        PG,
        "INSERT INTO customer.purpose_consents"
            + " (tenant_id, customer_id, purpose, granted, updated_at)"
            + " VALUES ('"
            + GB
            + "', '"
            + id
            + "', 'MARKETING', FALSE, now())"
            + " ON CONFLICT (tenant_id, customer_id, purpose) DO UPDATE SET granted = FALSE");
    assertThat(
        "untouched by the live API: still on",
        scalar(
            PG,
            "SELECT granted::text FROM customer.marketing_preferences WHERE customer_id = '"
                + id
                + "' AND channel = 'EMAIL'"),
        is("true"));

    int fixedFirst = marketing.reconcilePurposeWithdrawals();
    assertThat("EMAIL and SMS for this customer, at least", fixedFirst >= 2, is(true));
    assertThat(
        scalar(
            PG,
            "SELECT granted::text FROM customer.marketing_preferences WHERE customer_id = '"
                + id
                + "' AND channel = 'EMAIL'"),
        is("false"));
    String logCountAfterFirst =
        scalar(
            PG,
            "SELECT count(*) FROM customer.marketing_consent_log WHERE customer_id = '"
                + id
                + "' AND source = 'PURPOSE_WITHDRAWN'");
    assertThat(logCountAfterFirst, is("2"));

    int fixedSecond = marketing.reconcilePurposeWithdrawals();
    // Other tests in this class may have left their own stale rows for the *same* reconciliation
    // sweep (it runs over every tenant), so this only asserts nothing changes for *this* customer.
    assertThat(
        "a second run leaves this customer's rows exactly as the first left them",
        scalar(
            PG,
            "SELECT count(*) FROM customer.marketing_consent_log WHERE customer_id = '"
                + id
                + "' AND source = 'PURPOSE_WITHDRAWN'"),
        is(logCountAfterFirst));
    assertThat(fixedSecond >= 0, is(true));
  }

  @Test
  @DisplayName(
      "Another business's owner, manager, storekeeper, cashier and shopper cannot read or change"
          + " our customer's preferences or purposes, even naming our customer id, and nothing of"
          + " ours moves")
  void hardIsolationAcrossTenants() {
    String login = Ids.newId().toString();
    String email = login.substring(login.length() - 12) + "@example.co.uk";
    claim(GB, login, email);
    String id = data(shopper(GB, login, email, "/customers/me").get()).getString("id");
    setMarketing(GB, login, email, "EMAIL", true);
    String logCountBefore =
        scalar(
            PG,
            "SELECT count(*) FROM customer.marketing_consent_log WHERE customer_id = '" + id + "'");

    for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER")) {
      assertThat(
          role + " GET marketing",
          staff(OTHER, role, "/customers/" + id + "/marketing").get().getStatus(),
          is(404));
      assertThat(
          role + " PUT marketing",
          staff(OTHER, role, "/customers/" + id + "/marketing")
              .put(json("{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}]}"))
              .getStatus(),
          is(404));
      // The allowance is notification-svc's yes-or-no question and answers no rather than failing:
      // for another business's customer exactly as for an id nobody holds, so it confirms nothing
      // and mints no opt-out token.
      JsonObject allowance =
          data(staff(OTHER, role, "/customers/" + id + "/marketing/allowance?channel=EMAIL").get());
      assertThat(role + " allowance says no", allowance.getBoolean("allowed"), is(false));
      assertThat(
          role + " ...and carries no token",
          allowance.containsKey("unsubscribeToken") && !allowance.isNull("unsubscribeToken"),
          is(false));
      assertThat(
          role + " ...exactly as for an id nobody holds",
          allowance.getString("reason"),
          is(
              data(staff(
                          OTHER,
                          role,
                          "/customers/" + Ids.newId() + "/marketing/allowance?channel=EMAIL")
                      .get())
                  .getString("reason")));
      assertThat(
          role + " GET privacy",
          staff(OTHER, role, "/customers/" + id + "/privacy").get().getStatus(),
          is(404));
      assertThat(
          role + " PUT privacy consents",
          staff(OTHER, role, "/customers/" + id + "/privacy/consents")
              .put(json("{\"choices\":[{\"purpose\":\"MARKETING\",\"granted\":true}]}"))
              .getStatus(),
          is(404));
    }
    // The evidence log needs customers.privacy: owner and manager reach the tenant check and are
    // refused by it (404); storekeeper and cashier are refused before that even applies (403).
    for (String role : List.of("OWNER", "MANAGER")) {
      assertThat(
          role + " privacy log",
          staff(OTHER, role, "/customers/" + id + "/privacy/log").get().getStatus(),
          is(404));
    }
    for (String role : List.of("STOREKEEPER", "CASHIER")) {
      assertThat(
          role + " privacy log is never served",
          staff(OTHER, role, "/customers/" + id + "/privacy/log").get().getStatus(),
          is(403));
    }

    // A shopper of the other business is not staff at all: the shared filter refuses first.
    String otherShopper = Ids.newId().toString();
    String otherShopperEmail =
        otherShopper.substring(otherShopper.length() - 12) + "@example.co.uk";
    assertThat(
        shopper(OTHER, otherShopper, otherShopperEmail, "/customers/" + id + "/marketing")
            .get()
            .getStatus(),
        is(403));
    assertThat(
        shopper(OTHER, otherShopper, otherShopperEmail, "/customers/" + id + "/privacy")
            .get()
            .getStatus(),
        is(403));

    assertThat(
        "nothing of ours moved",
        scalar(
            PG,
            "SELECT count(*) FROM customer.marketing_consent_log WHERE customer_id = '" + id + "'"),
        is(logCountBefore));
    assertThat(granted(myMarketing(GB, login, email), "EMAIL"), is(true));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private void claim(String tenant, String login, String email) {
    Response r = shopper(tenant, login, email, "/customers/me").post(json("{}"));
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
  }

  private void publishNotice(String tenant) {
    Response r =
        staff(tenant, "OWNER", "/customers/privacy/notices")
            .post(json("{\"language\":\"en\",\"title\":\"Notice\",\"body\":\"What we do.\"}"));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private Response setMarketing(
      String tenant, String login, String email, String channel, boolean granted) {
    return shopper(tenant, login, email, "/customers/me/marketing")
        .put(
            json(
                "{\"channels\":[{\"channel\":\""
                    + channel
                    + "\",\"granted\":"
                    + granted
                    + "}],\"notice\":\"Send me offers\"}"));
  }

  private JsonArray myMarketing(String tenant, String login, String email) {
    return dataArray(shopper(tenant, login, email, "/customers/me/marketing").get());
  }

  private boolean allowed(String tenant, String customerId, String channel) {
    return data(staff(
                tenant,
                "OWNER",
                "/customers/" + customerId + "/marketing/allowance?channel=" + channel)
            .get())
        .getBoolean("allowed");
  }

  private static JsonObject consent(JsonArray preferences, String channel) {
    return preferences.getValuesAs(JsonObject.class).stream()
        .filter(p -> p.getString("channel").equals(channel))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + channel + " in " + preferences));
  }

  private static boolean granted(JsonArray preferences, String channel) {
    return consent(preferences, channel).getBoolean("granted");
  }

  private static String basisOf(JsonArray preferences, String channel) {
    return consent(preferences, channel).getString("basis");
  }

  private Invocation.Builder shopper(String tenant, String login, String email, String path) {
    return WebTargets.at(target, path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "CUSTOMER")
        .header("X-User-Id", login)
        .header("X-User-Email", email);
  }

  private Invocation.Builder staff(String tenant, String role, String path) {
    return WebTargets.at(target, path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", role)
        .header("X-User-Id", STAFF_USER);
  }

  private static Entity<String> json(String body) {
    return Entity.entity(body, MediaType.APPLICATION_JSON);
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private static JsonArray dataArray(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonArray("data");
    }
  }

  private static void assertCode(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, body, org.hamcrest.Matchers.containsString("\"code\":\"" + code + "\""));
  }
}
