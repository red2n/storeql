package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Jurisdiction rules: which laws reach which business, on which day, and that nobody can rewrite
 * them through the API.
 */
@HelidonTest
class ObligationIT {

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

  private static final String OBLIGATIONS = "/admin/tenant/obligations";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private String onboard(String country, String currency) {
    return TenantOnboarding.onboard(target, "Obligations " + country, country, currency);
  }

  private Response read(String tenant, String roles, String country, String on) {
    var t = target.path(OBLIGATIONS);
    if (country != null) t = t.queryParam("country", country);
    if (on != null) t = t.queryParam("on", on);
    var b = t.request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    return b.get();
  }

  private String sheet(String tenant, String country, String on) {
    Response r = read(tenant, "OWNER", country, on);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  /** The one obligation object carrying this code. */
  private static String obligation(String body, String code) {
    int at = body.indexOf("\"code\":\"" + code + "\"");
    if (at < 0) throw new AssertionError(code + " not in " + body);
    return body.substring(body.lastIndexOf('{', at), body.indexOf('}', at) + 1);
  }

  @Test
  @DisplayName("The cash limits that reach a country come with the sheet, in the law's currency")
  void cashLimitsComeWithTheSheet() {
    String fr = onboard("FR", "EUR");
    String today = sheet(fr, null, null);
    assertThat(today, containsString("\"cashLimits\":["));
    String limit = cashLimit(today, "FR");
    assertThat(limit, containsString("\"currency\":\"EUR\""));
    assertThat(limit, containsString("\"fromAmount\":1000"));
    assertThat(limit, containsString("\"status\":\"IN_FORCE\""));
    String eu = cashLimit(today, "EU");
    assertThat(eu, containsString("\"fromAmount\":10000"));
    assertThat(eu, containsString("\"effectiveFrom\":\"2027-07-10\""));
    assertThat(eu, containsString("\"status\":\"UPCOMING\""));
    assertThat(
        "from the day the Regulation applies, the EU cap is in force too",
        cashLimit(sheet(fr, null, "2027-07-10"), "EU"),
        containsString("\"status\":\"IN_FORCE\""));

    String de = onboard("DE", "EUR");
    String germany = sheet(de, null, null);
    assertThat(
        "Germany has no limit of its own",
        germany.substring(
            germany.indexOf("\"cashLimits\":["),
            germany.indexOf("]", germany.indexOf("\"cashLimits\":[")) + 1),
        not(containsString("\"scope\":\"DE\"")));
    assertThat(cashLimit(germany, "EU"), containsString("UPCOMING"));

    String in = onboard("IN", "INR");
    String india = cashLimit(sheet(in, null, null), "IN");
    assertThat(india, containsString("\"currency\":\"INR\""));
    assertThat(india, containsString("\"fromAmount\":200000"));
    assertThat(india, containsString("269ST"));

    String gb = onboard("GB", "GBP");
    assertThat(
        "Britain sets no cash limit", sheet(gb, null, null), containsString("\"cashLimits\":[]"));
  }

  /** The one deposit scheme object carrying this scope. */
  private static String depositScheme(String body, String scope) {
    int start = body.indexOf("\"depositSchemes\":[");
    if (start < 0) throw new AssertionError("no depositSchemes in " + body);
    int at = body.indexOf("\"scope\":\"" + scope + "\"", start);
    if (at < 0) throw new AssertionError(scope + " has no deposit scheme in " + body);
    return body.substring(body.lastIndexOf('{', at), body.indexOf('}', at) + 1);
  }

  /** A store in the tenant's country, for the storefront config that names the scheme there. */
  private String store(String tenant, String country, String timezone) {
    Response r =
        target
            .path("/admin/stores")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .post(
                jakarta.ws.rs.client.Entity.entity(
                    "{\"name\":\"Main\",\"code\":\"MAIN-"
                        + Ids.newId().toString().substring(0, 8)
                        + "\",\"line1\":\"1 Main St\",\"country\":\""
                        + country
                        + "\",\"city\":\"Town\",\"postcode\":\"10117\",\"timezone\":\""
                        + timezone
                        + "\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    int at = body.indexOf("\"id\":\"");
    return body.substring(at + 6, at + 42);
  }

  private String storefrontConfig(String tenant, String store) {
    Response r =
        target
            .path("/storefront/config")
            .queryParam("store", store)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  @Test
  @DisplayName(
      "The deposit return schemes that reach a country come with the sheet, and the storefront config names the one in force at the store")
  void depositSchemesComeWithTheSheet() {
    String de = onboard("DE", "EUR");
    String germany = sheet(de, null, null);
    String pfand = depositScheme(germany, "DE");
    assertThat(pfand, containsString("\"currency\":\"EUR\""));
    assertThat(pfand, containsString("\"depositEach\":0.25"));
    assertThat(pfand, containsString("\"vatTreatment\":\"STANDARD\""));
    assertThat(pfand, containsString("\"status\":\"IN_FORCE\""));
    assertThat(pfand, containsString("GLASS"));
    assertThat(pfand, containsString("\"minVolumeMl\":100"));
    assertThat(pfand, containsString("Verpackungsgesetz"));
    String config = storefrontConfig(de, store(de, "DE", "Europe/Berlin"));
    assertThat(config, containsString("\"depositScheme\":{"));
    assertThat(config, containsString("\"scope\":\"DE\""));

    String gb = onboard("GB", "GBP");
    String britain = sheet(gb, null, null);
    String uk = depositScheme(britain, "GB");
    assertThat(uk, containsString("\"currency\":\"GBP\""));
    assertThat(uk, containsString("\"depositEach\":0.20"));
    assertThat(uk, containsString("\"vatTreatment\":\"OUTSIDE_SCOPE\""));
    assertThat(uk, containsString("\"effectiveFrom\":\"2027-10-01\""));
    assertThat(uk, containsString("\"status\":\"UPCOMING\""));
    assertThat("glass is outside the UK scheme", uk, not(containsString("GLASS")));
    assertThat(uk, containsString("2025/67"));
    assertThat(
        "from the day it starts, in force",
        depositScheme(sheet(gb, null, "2027-10-01"), "GB"),
        containsString("\"status\":\"IN_FORCE\""));
    assertThat(
        "nothing in force at a British store today",
        storefrontConfig(gb, store(gb, "GB", "Europe/London")),
        not(containsString("\"depositScheme\"")));

    String fr = onboard("FR", "EUR");
    String france = sheet(fr, null, null);
    assertThat(
        "France has no scheme in the register",
        france.substring(france.indexOf("\"depositSchemes\":[")),
        containsString("\"depositSchemes\":[]"));
  }

  private static String cashLimit(String body, String scope) {
    int start = body.indexOf("\"cashLimits\":[");
    if (start < 0) throw new AssertionError("no cashLimits in " + body);
    int at = body.indexOf("\"scope\":\"" + scope + "\"", start);
    if (at < 0) throw new AssertionError(scope + " has no cash limit in " + body);
    return body.substring(body.lastIndexOf('{', at), body.indexOf('}', at) + 1);
  }

  @Test
  @DisplayName("A British business gets UK law, and no EU law made after the UK left")
  void aBritishBusinessIsNotBoundByEuLawMadeAfterItLeft() {
    String gb = onboard("GB", "GBP");
    // No country asked for: the business's own.
    String body = sheet(gb, null, "2026-09-14");
    assertThat(body, containsString("\"country\":\"GB\""));
    assertThat(obligation(body, "UK_GDPR"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(obligation(body, "UNIT_PRICING"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(obligation(body, "TOBACCO_BIRTH_COHORT"), containsString("\"status\":\"UPCOMING\""));
    assertThat(body, not(containsString("GPSR_ONLINE_OFFER")));
    assertThat(body, not(containsString("\"scope\":\"EU\"")));
    // While a member, EU law reached it, and the window says the day it stopped.
    String before = sheet(gb, null, "2019-06-01");
    assertThat(obligation(before, "GDPR"), containsString("\"effectiveTo\":\"2020-01-31\""));
    assertThat(obligation(before, "GDPR"), containsString("\"scope\":\"EU\""));
  }

  @Test
  @DisplayName(
      "A German business inherits EU law and its own; a Portuguese one does not get Germany's")
  void membersInheritTheRegimeAndKeepTheirOwn() {
    String de = onboard("DE", "EUR");
    String body = sheet(de, null, "2026-09-14");
    assertThat(obligation(body, "GPSR_ONLINE_OFFER"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(
        obligation(body, "CRA_VULNERABILITY_REPORTING"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(obligation(body, "E_INVOICING_RECEIVE"), containsString("\"scope\":\"DE\""));
    assertThat(obligation(body, "E_INVOICING_ISSUE"), containsString("\"status\":\"UPCOMING\""));
    // Directive 98/6/EC art.3 (V12): a unit price is EU law too, not the UK's alone.
    assertThat(obligation(body, "UNIT_PRICING"), containsString("\"scope\":\"EU\""));
    assertThat(
        "in force first, then upcoming",
        body.indexOf("E_INVOICING_RECEIVE"),
        is(org.hamcrest.Matchers.lessThan(body.indexOf("E_INVOICING_ISSUE"))));

    String pt = sheet(de, "PT", "2026-09-14");
    assertThat(pt, containsString("\"code\":\"GDPR\""));
    assertThat(pt, containsString("CERTIFIED_BILLING"));
    assertThat(pt, not(containsString("FISCAL_TSE")));
  }

  @Test
  @DisplayName("The day decides: in force from its date, upcoming the day before, gone once ended")
  void theDayDecides() {
    String gb = onboard("GB", "GBP");
    assertThat(
        obligation(sheet(gb, "GB", "2026-12-31"), "TOBACCO_BIRTH_COHORT"),
        containsString("\"status\":\"UPCOMING\""));
    assertThat(
        obligation(sheet(gb, "GB", "2027-01-01"), "TOBACCO_BIRTH_COHORT"),
        containsString("\"status\":\"IN_FORCE\""));
    // After the UK left, the EU's GDPR row has ended for it and is not listed at all.
    assertThat(sheet(gb, "GB", "2020-02-01"), not(containsString("\"code\":\"GDPR\"")));
    // A country the table knows nothing about has no obligations, not an error.
    assertThat(sheet(gb, "US", "2026-09-14"), containsString("\"obligations\":[]"));
  }

  @Test
  @DisplayName("Every staff role reads the rules; a shopper and a caller with no role do not")
  void staffReadTheRules() {
    String gb = onboard("GB", "GBP");
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "MANAGER", "OWNER"}) {
      assertThat(role, read(gb, role, null, null).getStatus(), is(200));
    }
    assertThat(read(gb, "CUSTOMER", null, null).getStatus(), is(403));
    assertThat(read(gb, null, null, null).getStatus(), is(403));
  }

  @Test
  @DisplayName("A country or a date that is not one is refused, and nothing writes a rule")
  void badInputAndNoWrites() {
    String gb = onboard("GB", "GBP");
    for (String bad : new String[] {"GBR", "ZZ", "g", "GB' OR '1'='1", "9".repeat(300)}) {
      Response r = read(gb, "OWNER", bad, null);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(bad, r.readEntity(String.class), containsString("COUNTRY_INVALID"));
    }
    for (String bad :
        new String[] {"tomorrow", "2026-02-30", "1850-01-01", "9999-01-01", "14/09/2026"}) {
      Response r = read(gb, "OWNER", "GB", bad);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(bad, r.readEntity(String.class), containsString("OBLIGATION_DATE_INVALID"));
    }
    // A fresh builder per method: Jersey keeps a body set on a builder, and refuses it on DELETE.
    java.util.function.Supplier<jakarta.ws.rs.client.Invocation.Builder> owner =
        () ->
            target.path(OBLIGATIONS).request().header("X-Tenant-Id", gb).header("X-Roles", "OWNER");
    assertThat(owner.get().post(Entity.json("{}")).getStatus(), anyOf(is(404), is(405)));
    assertThat(owner.get().put(Entity.json("{}")).getStatus(), anyOf(is(404), is(405)));
    assertThat(owner.get().delete().getStatus(), anyOf(is(404), is(405)));
  }

  @Test
  @DisplayName("A business whose country cannot be read is told so, never matched to a law")
  void aBusinessWithNoReadableCountryIsToldSo() {
    // Sign-up checks only the length of the country, so two ideographic spaces are stored. The
    // underlying gap (sign-up takes a country that is no ISO code) is not closed here.
    String odd = onboard("\\u3000\\u3000", "GBP");
    Response r = read(odd, "OWNER", null, null);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(409));
    assertThat(body, containsString("TENANT_COUNTRY_MISSING"));

    // Naming a country by hand still works for that business, and a bad one is still refused.
    assertThat(read(odd, "OWNER", "GB", null).getStatus(), is(200));
    Response bad = read(odd, "OWNER", "ZZ", null);
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("COUNTRY_INVALID"));
  }

  @Test
  @DisplayName("Twenty reads at once all answer, with the same rules")
  void concurrentReadsAgree() throws Exception {
    String de = onboard("DE", "EUR");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 20; i++) {
        futures.add(pool.submit(() -> sheet(de, null, "2026-09-14")));
      }
      java.util.Set<String> bodies = new java.util.HashSet<>();
      for (var f : futures) bodies.add(f.get());
      assertThat(bodies.size(), is(1));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "An obligation row's limit, unit and qualifier reach the sheet; rows without one are unchanged")
  void anObligationCarriesItsLimit() throws Exception {
    String code = "T_LIMIT_" + Ids.newId().toString().replace("-", "").substring(20).toUpperCase();
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO tenant.legal_obligations (code, scope_kind, scope, effective_from,"
                    + " citation, summary, limit_value, limit_unit, qualifier)"
                    + " VALUES (?, 'COUNTRY', 'NL', DATE '2020-01-01', 'test citation', 'test',"
                    + " 30, 'DAYS', 'EMAIL')")) {
      ps.setString(1, code);
      ps.executeUpdate();
    }
    String nl = onboard("NL", "EUR");
    String row = obligation(sheet(nl, null, null), code);
    assertThat(row, containsString("\"limitValue\":30"));
    assertThat(row, containsString("\"limitUnit\":\"DAYS\""));
    assertThat(row, containsString("\"qualifier\":\"EMAIL\""));
    String plain = obligation(sheet(nl, null, null), "GDPR");
    assertThat(plain, not(containsString("limitValue")));
    assertThat(plain, not(containsString("qualifier")));
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO tenant.legal_obligations (code, scope_kind, scope, effective_from,"
                    + " citation, summary, limit_value) VALUES ('T_NOUNIT', 'COUNTRY', 'NL',"
                    + " DATE '2020-01-01', 'c', 's', 5)")) {
      try {
        ps.executeUpdate();
        throw new AssertionError("a number without its unit must be refused by the database");
      } catch (java.sql.SQLException expected) {
        assertThat(expected.getMessage(), containsString("chk_obligation_limit_pair"));
      }
    }
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
