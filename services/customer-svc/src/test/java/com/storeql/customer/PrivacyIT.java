package com.storeql.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The privacy surface: the login-to-customer link (SJ-D44), marketing consent and its evidence
 * (PECR reg.22, UK GDPR art.7(1)), and the one-click opt-out (reg.23).
 *
 * <p>The data export has its own path through order-svc and is covered where that seam is real; the
 * pieces this service owns are asserted here.
 */
@HelidonTest
class PrivacyIT {

  private static final PostgresSupport PG;
  private static final String TENANT = "01a090c3-38ae-7b21-9c0f-6f2b5a1d4e70";

  static {
    PG = PostgresSupport.start();
    // A British business: no per-purpose consent law binds it, so a channel's own consent stands
    // on its own — exactly what this file's marketing-preference tests already assume.
    TenantSvcStub.start().with(TENANT, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "customer");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /**
   * WebTarget.path() percent-encodes a "?", so the query string is split off and added properly.
   */
  private WebTarget at(String path) {
    String[] parts = path.split("\\?", 2);
    WebTarget t = target.path(parts[0]);
    if (parts.length == 2) {
      for (String pair : parts[1].split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv.length == 2 ? kv[1] : "");
      }
    }
    return t;
  }

  private Response asShopper(String path, String method, String login, String email, String json) {
    var builder =
        at(path)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", login)
            .header("X-User-Email", email);
    return switch (method) {
      case "POST" -> builder.post(Entity.entity(json, MediaType.APPLICATION_JSON));
      case "PUT" -> builder.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> builder.get();
    };
  }

  private Response asStaff(String path, String method, String json) {
    var builder =
        at(path)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER");
    return switch (method) {
      case "POST" -> builder.post(Entity.entity(json, MediaType.APPLICATION_JSON));
      case "PUT" -> builder.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> builder.get();
    };
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in: " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  private static String column(String sql, Object param) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps = c.prepareStatement(sql)) {
      ps.setObject(1, param);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName(
      "A shopper's login claims one customer record, and claiming again finds the same one")
  void loginClaimsOneRecord() {
    String login = Ids.newId().toString();
    Response first = asShopper("/customers/me", "POST", login, "shopper@example.com", "{}");
    assertThat(first.getStatus(), is(200));
    String id = field(first.readEntity(String.class), "id");

    Response second = asShopper("/customers/me", "POST", login, "shopper@example.com", "{}");
    assertThat(second.getStatus(), is(200));
    assertThat(field(second.readEntity(String.class), "id"), is(id));

    assertThat(
        column("SELECT login_id FROM \"customer\".customers WHERE id = ?", Ids.parse(id)),
        is(login));
  }

  @Test
  @DisplayName(
      "A login adopts the record the till already had for that email, rather than a second")
  void theTillsRecordIsAdopted() {
    String email = "walkin@example.com";
    Response created =
        asStaff(
            "/customers",
            "POST",
            "{\"email\":\"" + email + "\",\"firstName\":\"Wal\",\"lastName\":\"Kin\"}");
    assertThat(created.getStatus(), is(201));
    String tillId = field(created.readEntity(String.class), "id");

    String login = Ids.newId().toString();
    Response claimed = asShopper("/customers/me", "POST", login, email, "{}");
    assertThat(claimed.getStatus(), is(200));
    assertThat(
        "the same person, not a second record",
        field(claimed.readEntity(String.class), "id"),
        is(tillId));
  }

  @Test
  @DisplayName("A token with no email cannot claim a record there would be no way to reach")
  void anEmaillessTokenIsRefused() {
    Response r = asShopper("/customers/me", "POST", Ids.newId().toString(), null, "{}");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("CUSTOMER_LOGIN_UNIDENTIFIED"));
  }

  @Test
  @DisplayName("Silence is not consent: a channel nobody has decided refuses the send")
  void noPreferenceMeansNoMarketing() {
    String login = Ids.newId().toString();
    String id =
        field(
            asShopper("/customers/me", "POST", login, "quiet@example.com", "{}")
                .readEntity(String.class),
            "id");

    String decision =
        asStaff("/customers/" + id + "/marketing/allowance?channel=EMAIL", "GET", null)
            .readEntity(String.class);
    assertThat(decision, containsString("\"allowed\":false"));
    assertThat(decision, containsString("no consent recorded"));
  }

  @Test
  @DisplayName("Consent is recorded with the wording it was given against, and permits a send")
  void consentIsRecordedWithItsEvidence() {
    String login = Ids.newId().toString();
    String id =
        field(
            asShopper("/customers/me", "POST", login, "willing@example.com", "{}")
                .readEntity(String.class),
            "id");

    Response set =
        asShopper(
            "/customers/me/marketing",
            "PUT",
            login,
            "willing@example.com",
            "{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}],"
                + "\"notice\":\"Email me about offers and new lines\"}");
    assertThat(set.getStatus(), is(200));
    assertThat(set.readEntity(String.class), containsString("\"granted\":true"));

    String decision =
        asStaff("/customers/" + id + "/marketing/allowance?channel=EMAIL", "GET", null)
            .readEntity(String.class);
    assertThat(decision, containsString("\"allowed\":true"));
    assertThat(decision, containsString("\"basis\":\"CONSENT\""));
    assertThat(
        "an allowed send carries its own way out", decision, containsString("unsubscribeToken"));

    // The evidence art.7(1) asks for: the wording, not just the fact.
    assertThat(
        column(
            "SELECT notice FROM \"customer\".marketing_consent_log WHERE customer_id = ?"
                + " ORDER BY recorded_at DESC LIMIT 1",
            Ids.parse(id)),
        is("Email me about offers and new lines"));
    assertThat(
        column(
            "SELECT source FROM \"customer\".marketing_consent_log WHERE customer_id = ?"
                + " ORDER BY recorded_at DESC LIMIT 1",
            Ids.parse(id)),
        is("PREFERENCE_CENTRE"));
  }

  @Test
  @DisplayName("The unsubscribe link stops every channel, needs no sign-in, and works twice")
  void oneClickUnsubscribe() {
    String login = Ids.newId().toString();
    String id =
        field(
            asShopper("/customers/me", "POST", login, "leaving@example.com", "{}")
                .readEntity(String.class),
            "id");
    assertThat(
        asShopper(
                "/customers/me/marketing",
                "PUT",
                login,
                "leaving@example.com",
                "{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true},"
                    + "{\"channel\":\"SMS\",\"granted\":true}],\"notice\":\"Offers\"}")
            .getStatus(),
        is(200));

    String token =
        field(
            asStaff("/customers/" + id + "/marketing/allowance?channel=EMAIL", "GET", null)
                .readEntity(String.class),
            "unsubscribeToken");

    // No tenant header, no identity at all: the token is the whole capability.
    Response out =
        target
            .path("/marketing/unsubscribe")
            .request(MediaType.APPLICATION_JSON)
            .post(Entity.entity("{\"token\":\"" + token + "\"}", MediaType.APPLICATION_JSON));
    assertThat(out.getStatus(), is(200));

    String after =
        asStaff("/customers/" + id + "/marketing/allowance?channel=EMAIL", "GET", null)
            .readEntity(String.class);
    assertThat(after, containsString("\"allowed\":false"));
    assertThat(after, containsString("opted out"));
    assertThat(
        "SMS goes too — an unsubscribe link means all of it",
        asStaff("/customers/" + id + "/marketing/allowance?channel=SMS", "GET", null)
            .readEntity(String.class),
        containsString("\"allowed\":false"));

    // An objection does not expire: the second click is not an error.
    Response again =
        target
            .path("/marketing/unsubscribe")
            .request(MediaType.APPLICATION_JSON)
            .post(Entity.entity("{\"token\":\"" + token + "\"}", MediaType.APPLICATION_JSON));
    assertThat(again.getStatus(), is(200));
  }

  @Test
  @DisplayName("An unknown opt-out token is refused rather than silently accepted")
  void anUnknownTokenIsRefused() {
    Response out =
        target
            .path("/marketing/unsubscribe")
            .request(MediaType.APPLICATION_JSON)
            .post(Entity.entity("{\"token\":\"not-a-token\"}", MediaType.APPLICATION_JSON));
    assertThat(out.getStatus(), is(404));
    assertThat(out.readEntity(String.class), containsString("UNSUBSCRIBE_TOKEN_INVALID"));
  }

  @Test
  @DisplayName("An erased customer may not be marketed to, whatever was recorded before")
  void anErasedCustomerIsNeverMarketable() {
    String login = Ids.newId().toString();
    String id =
        field(
            asShopper("/customers/me", "POST", login, "erased@example.com", "{}")
                .readEntity(String.class),
            "id");
    assertThat(
        asShopper(
                "/customers/me/marketing",
                "PUT",
                login,
                "erased@example.com",
                "{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}],\"notice\":\"Offers\"}")
            .getStatus(),
        is(200));

    Response erased =
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .delete();
    assertThat(erased.getStatus(), is(204));

    String decision =
        asStaff("/customers/" + id + "/marketing/allowance?channel=EMAIL", "GET", null)
            .readEntity(String.class);
    assertThat(decision, containsString("\"allowed\":false"));
    assertThat(decision, containsString("no active customer record"));
  }

  @Test
  @DisplayName("The erasure event names the login, so the shop's orders can be found by it")
  void theErasureEventCarriesTheLogin() {
    String login = Ids.newId().toString();
    String id =
        field(
            asShopper("/customers/me", "POST", login, "bye@example.com", "{}")
                .readEntity(String.class),
            "id");
    assertThat(
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .delete()
            .getStatus(),
        is(204));

    String payload =
        column(
            "SELECT payload FROM \"customer\".outbox WHERE event_type = 'CustomerErased'"
                + " AND aggregate_id = ?",
            Ids.parse(id));
    assertThat(payload, containsString("\"loginId\":\"" + login + "\""));
    // Ids only: the event outlives its handling and must not carry what it exists to erase.
    assertThat(payload, not(containsString("bye@example.com")));
  }

  @Test
  @DisplayName("A shopper's preferences and their evidence are part of their data export")
  void theExportCarriesConsent() {
    String login = Ids.newId().toString();
    asShopper("/customers/me", "POST", login, "exporter@example.com", "{}");
    asShopper(
        "/customers/me/marketing",
        "PUT",
        login,
        "exporter@example.com",
        "{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}],\"notice\":\"Send me offers\"}");

    // order-svc is not running here, so the export fails closed rather than serving a half one.
    Response export = asShopper("/customers/me/export", "GET", login, "exporter@example.com", null);
    String exportBody = export.readEntity(String.class);
    System.out.println("EXPORT-DEBUG status=" + export.getStatus() + " body=" + exportBody);
    try {
      var svc =
          jakarta
              .enterprise
              .inject
              .spi
              .CDI
              .current()
              .select(com.storeql.customer.service.CustomerService.class)
              .get();
      svc.export(Ids.parse(TENANT), null, Ids.parse(login), "x@y.z");
    } catch (RuntimeException e) {
      System.out.println("EXPORT-DEBUG direct threw " + e.getClass().getName() + ": " + e);
    }
    assertThat(export.getStatus(), is(503));
    assertThat(exportBody, containsString("EXPORT_ORDERS_UNAVAILABLE"));
  }

  @Test
  @DisplayName("Eight checkouts at once for one login make one customer record, not eight")
  void concurrentClaimsMakeOneRecord() throws Exception {
    String login = Ids.newId().toString();
    String email = "racer@example.com";
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
      var gate = new java.util.concurrent.CountDownLatch(1);
      for (int i = 0; i < 8; i++) {
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  Response r = asShopper("/customers/me", "POST", login, email, "{}");
                  String body = r.readEntity(String.class);
                  return r.getStatus() + " " + field(body, "id");
                }));
      }
      gate.countDown();
      var ids = new java.util.HashSet<String>();
      for (var f : futures) {
        String[] parts = f.get(30, java.util.concurrent.TimeUnit.SECONDS).split(" ");
        assertThat("every racer gets a record", parts[0], is("200"));
        ids.add(parts[1]);
      }
      assertThat("and it is the same record", ids.size(), is(1));
    } finally {
      pool.shutdownNow();
    }
    assertThat(
        column(
            "SELECT count(*)::text FROM \"customer\".customers WHERE login_id = ?",
            Ids.parse(login)),
        is("1"));
  }

  @Test
  @DisplayName("A shopper cannot read or set another person's record, export or preferences")
  void aShopperReachesNobodyElse() {
    String victimLogin = Ids.newId().toString();
    String victimId =
        field(
            asShopper("/customers/me", "POST", victimLogin, "victim@example.com", "{}")
                .readEntity(String.class),
            "id");
    String attacker = Ids.newId().toString();
    asShopper("/customers/me", "POST", attacker, "attacker@example.com", "{}");

    // The id-addressed shapes require a staff role; a customer token gets 403 on all of them.
    assertThat(
        asShopper(
                "/customers/" + victimId + "/export", "GET", attacker, "attacker@example.com", null)
            .getStatus(),
        is(403));
    assertThat(
        asShopper(
                "/customers/" + victimId + "/marketing",
                "GET",
                attacker,
                "attacker@example.com",
                null)
            .getStatus(),
        is(403));
    assertThat(
        asShopper(
                "/customers/" + victimId + "/marketing",
                "PUT",
                attacker,
                "attacker@example.com",
                "{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}]}")
            .getStatus(),
        is(403));
    assertThat(
        asShopper(
                "/customers/" + victimId + "/marketing/allowance?channel=EMAIL",
                "GET",
                attacker,
                "attacker@example.com",
                null)
            .getStatus(),
        is(403));
    // And the /me shapes resolve the attacker, never the victim: nothing of the victim's leaks.
    String mine =
        asShopper("/customers/me", "GET", attacker, "attacker@example.com", null)
            .readEntity(String.class);
    assertThat(mine, containsString("attacker@example.com"));
    assertThat(mine, not(containsString("victim@example.com")));
  }

  @Test
  @DisplayName("An unsubscribe with a channel nobody offers is refused, and stops nothing")
  void anUnsubscribeWithAnUnknownChannelStopsNothing() {
    String login = Ids.newId().toString();
    String id =
        field(
            asShopper("/customers/me", "POST", login, "picky@example.com", "{}")
                .readEntity(String.class),
            "id");
    asShopper(
        "/customers/me/marketing",
        "PUT",
        login,
        "picky@example.com",
        "{\"channels\":[{\"channel\":\"EMAIL\",\"granted\":true}],\"notice\":\"Offers\"}");
    String token =
        field(
            asStaff("/customers/" + id + "/marketing/allowance?channel=EMAIL", "GET", null)
                .readEntity(String.class),
            "unsubscribeToken");
    Response bad =
        target
            .path("/marketing/unsubscribe")
            .request(MediaType.APPLICATION_JSON)
            .post(
                Entity.entity(
                    "{\"token\":\"" + token + "\",\"channel\":\"FAX\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(bad.getStatus(), is(400));
    assertThat(
        "the email consent is untouched",
        asStaff("/customers/" + id + "/marketing/allowance?channel=EMAIL", "GET", null)
            .readEntity(String.class),
        containsString("\"allowed\":true"));
  }

  @Test
  @DisplayName("A blank or missing token is a validation error, not a lookup")
  void aBlankTokenIsRefusedBeforeAnyLookup() {
    Response r =
        target
            .path("/marketing/unsubscribe")
            .request(MediaType.APPLICATION_JSON)
            .post(Entity.entity("{\"token\":\"\"}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("A channel the API does not know is refused rather than quietly ignored")
  void anUnknownChannelIsRefused() {
    String login = Ids.newId().toString();
    asShopper("/customers/me", "POST", login, "odd@example.com", "{}");
    Response r =
        asShopper(
            "/customers/me/marketing",
            "PUT",
            login,
            "odd@example.com",
            "{\"channels\":[{\"channel\":\"CARRIER_PIGEON\",\"granted\":true}]}");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("MARKETING_CHANNEL_UNKNOWN"));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
