package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
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
 * Allergens, country of origin, age-restricted sales and selling by weight.
 *
 * <p>Four capabilities the readiness review graded absent, all of them law rather than product
 * strategy. The tests are written around the ways each one is got wrong in practice: an empty
 * allergen list read as "free from", one national age applied in every country, and a weighed item
 * with no unit to price it by.
 */
@HelidonTest
class FoodSafetyIT {

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final TenantSvcStub STUB;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "product");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");

    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
    // British tenants: GPSR's online-offer rule does not bind them (01.12).
    STUB =
        TenantSvcStub.start()
            .with(FoodSafetyIT.T, "GBP", "GB")
            .with(FoodSafetyIT.OTHER, "GBP", "GB");
  }

  /** A tenant minted for one test, described to tenant-svc's stub before it creates anything. */
  private static String britishTenant() {
    String tenant = com.storeql.ids.Ids.newId().toString();
    STUB.with(tenant, "GBP", "GB");
    return tenant;
  }

  private static final String T = "01a090ae-611e-7029-867a-5082a55a290f";
  private static final String OTHER = "01a090ae-611e-7034-89f9-56cd87f91b14";

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String tenant) {
    return target.path(path).request().header("X-Tenant-Id", tenant).get();
  }

  /** An /admin/ read, which is management-gated and needs a role stated. */
  private Response getAdmin(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  private Response getWith(String path, String param, String value, String tenant) {
    return target
        .path(path)
        .queryParam(param, value)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  private static String field(String json, String name) {
    int i = json.indexOf("\"" + name + "\":\"");
    if (i < 0) {
      return null;
    }
    int s = i + name.length() + 4;
    return json.substring(s, json.indexOf('"', s));
  }

  /** A product with one variant, returning the variant id. */
  private String variant(String tenant, String name, String sku) {
    String pid =
        field(
            post("/admin/products", "{\"name\":\"" + name + "\"}", tenant).readEntity(String.class),
            "id");
    Response v = post("/admin/products/" + pid + "/variants", "{\"sku\":\"" + sku + "\"}", tenant);
    assertThat(v.getStatus(), is(201));
    return field(v.readEntity(String.class), "id");
  }

  // ── allergens ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The fourteen regulated allergens are seeded and readable without a login")
  void theFourteen() {
    Response r = target.path("/catalog/allergens").request().get();
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    // Spot-check the ones most often left out of a hand-rolled list.
    assertThat(body, containsString("LUPIN"));
    assertThat(body, containsString("MOLLUSCS"));
    assertThat(body, containsString("SULPHITES"));
    assertThat(body, containsString("CELERY"));
    assertThat(body, containsString("1169/2011"));
    // Fourteen, not thirteen and not fifteen.
    assertThat(body.split("\"code\"").length - 1, is(14));
  }

  @Test
  @DisplayName("An undeclared product is not a free-from product")
  void undeclaredIsNotFreeFrom() {
    String v = variant(T, "Biscuits", "BISC-1");

    String body = get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class);
    // This is the assertion the whole feature exists for. Both products have an empty list; only
    // the status tells them apart, and getting it wrong sends someone to hospital.
    assertThat(body, containsString("\"status\":\"NOT_APPLICABLE\""));
    assertThat(body, not(containsString("\"status\":\"DECLARED\"")));
  }

  @Test
  @DisplayName("Declaring nothing is a positive statement, and is not the same as not declaring")
  void declaringNoneIsAStatement() {
    String v = variant(T, "Plain rice", "RICE-1");

    assertThat(
        put("/admin/products/variants/" + v + "/allergens", "{\"allergens\":[]}", T).getStatus(),
        is(200));

    String body = get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class);
    assertThat(body, containsString("\"status\":\"DECLARED\""));
    assertThat(body, containsString("\"allergens\":[]"));
  }

  @Test
  @DisplayName("Contains and may-contain are kept apart, because the law keeps them apart")
  void containsVersusMayContain() {
    String v = variant(T, "Chocolate bar", "CHOC-1");

    assertThat(
        put(
                "/admin/products/variants/" + v + "/allergens",
                "{\"allergens\":[{\"code\":\"MILK\",\"presence\":\"CONTAINS\"},"
                    + "{\"code\":\"NUTS\",\"presence\":\"MAY_CONTAIN\"}]}",
                T)
            .getStatus(),
        is(200));

    String body = get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class);
    assertThat(body, containsString("\"code\":\"MILK\",\"presence\":\"CONTAINS\""));
    assertThat(body, containsString("\"code\":\"NUTS\",\"presence\":\"MAY_CONTAIN\""));
  }

  @Test
  @DisplayName("A declaration replaces the last one, so a mistake can be corrected")
  void declarationReplaces() {
    String v = variant(T, "Soup", "SOUP-1");
    put(
        "/admin/products/variants/" + v + "/allergens",
        "{\"allergens\":[{\"code\":\"CELERY\",\"presence\":\"CONTAINS\"}]}",
        T);
    // The recipe never had celery in it.
    put(
        "/admin/products/variants/" + v + "/allergens",
        "{\"allergens\":[{\"code\":\"MILK\",\"presence\":\"CONTAINS\"}]}",
        T);

    String body = get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class);
    assertThat(body, containsString("MILK"));
    // Merging instead of replacing would make "we were wrong, it has no celery" unsayable.
    assertThat(body, not(containsString("CELERY")));
  }

  @Test
  @DisplayName("An invented allergen, a bad presence and a double declaration are all refused")
  void badDeclarations() {
    String v = variant(T, "Mystery", "MYST-1");
    String base = "/admin/products/variants/" + v + "/allergens";

    Response invented =
        put(base, "{\"allergens\":[{\"code\":\"GLUTEN_FREE\",\"presence\":\"CONTAINS\"}]}", T);
    assertThat(invented.getStatus(), is(400));
    assertThat(invented.readEntity(String.class), containsString("PRODUCT_UNKNOWN_ALLERGEN"));

    Response probably =
        put(base, "{\"allergens\":[{\"code\":\"MILK\",\"presence\":\"PROBABLY\"}]}", T);
    assertThat(probably.getStatus(), is(400));
    assertThat(probably.readEntity(String.class), containsString("PRODUCT_INVALID_PRESENCE"));

    // Declared twice with different answers: the stricter one matters, so guessing is refused.
    Response twice =
        put(
            base,
            "{\"allergens\":[{\"code\":\"MILK\",\"presence\":\"CONTAINS\"},"
                + "{\"code\":\"MILK\",\"presence\":\"MAY_CONTAIN\"}]}",
            T);
    assertThat(twice.getStatus(), is(400));
    assertThat(twice.readEntity(String.class), containsString("PRODUCT_DUPLICATE_ALLERGEN"));
  }

  @Test
  @DisplayName("Every product carrying an allergen can be found — the query a recall runs")
  void recallQuery() {
    String a = variant(T, "Cake", "CAKE-1");
    String b = variant(T, "Bread", "BREAD-1");
    String c = variant(T, "Water", "WATER-1");
    put(
        "/admin/products/variants/" + a + "/allergens",
        "{\"allergens\":[{\"code\":\"NUTS\",\"presence\":\"CONTAINS\"}]}",
        T);
    put(
        "/admin/products/variants/" + b + "/allergens",
        "{\"allergens\":[{\"code\":\"NUTS\",\"presence\":\"MAY_CONTAIN\"}]}",
        T);
    put("/admin/products/variants/" + c + "/allergens", "{\"allergens\":[]}", T);

    String all = getAdmin("/admin/products/by-allergen/NUTS", T).readEntity(String.class);
    // Both, by default: a withdrawal normally has to cover the may-contains too.
    assertThat(all, containsString(a));
    assertThat(all, containsString(b));
    assertThat(all, not(containsString(c)));

    String only =
        getWith("/admin/products/by-allergen/NUTS", "presence", "CONTAINS", T)
            .readEntity(String.class);
    assertThat(only, containsString(a));
    assertThat(only, not(containsString(b)));
  }

  @Test
  @DisplayName("The undeclared list is what an inspector asks for")
  void allergenGaps() {
    String declared = variant(T, "Known", "KNOWN-1");
    put("/admin/products/variants/" + declared + "/allergens", "{\"allergens\":[]}", T);

    String gaps = getAdmin("/admin/products/allergen-gaps", T).readEntity(String.class);
    // NOT_APPLICABLE is the default for a variant nobody has touched, so a non-food line does not
    // clutter the list; only something explicitly marked UNDECLARED appears.
    assertThat(gaps, not(containsString(declared)));
  }

  @Test
  @DisplayName("One tenant cannot read another's declarations")
  void tenantIsolation() {
    String v = variant(T, "Private recipe", "PRIV-1");
    put(
        "/admin/products/variants/" + v + "/allergens",
        "{\"allergens\":[{\"code\":\"SESAME\",\"presence\":\"CONTAINS\"}]}",
        T);

    assertThat(get("/catalog/variants/" + v + "/allergens", OTHER).getStatus(), is(404));
    assertThat(
        getAdmin("/admin/products/by-allergen/SESAME", OTHER).readEntity(String.class),
        not(containsString(v)));
  }

  // ── SJ-D42: marking an item as food is what makes its allergens owed ─────

  @Test
  @DisplayName("Marking an item as food puts it on the allergen-gaps list until it is declared")
  void markingFoodOpensAGap() {
    String v = variant(T, "Flapjack", "FLAP-1");
    String gaps = "/admin/products/allergen-gaps";
    assertThat(getAdmin(gaps, T).readEntity(String.class), not(containsString(v)));

    assertThat(
        put("/admin/products/variants/" + v + "/compliance", "{\"food\":true}", T).getStatus(),
        is(200));
    assertThat(
        get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class),
        containsString("\"status\":\"UNDECLARED\""));
    // Before the flag existed nothing ever wrote UNDECLARED, so this list could not contain
    // anything.
    assertThat(getAdmin(gaps, T).readEntity(String.class), containsString(v));

    put(
        "/admin/products/variants/" + v + "/allergens",
        "{\"allergens\":[{\"code\":\"CEREALS_GLUTEN\",\"presence\":\"CONTAINS\"}]}",
        T);
    assertThat(getAdmin(gaps, T).readEntity(String.class), not(containsString(v)));
  }

  @Test
  @DisplayName(
      "Marking a declared item as food again does not turn its declaration into an unknown")
  void remarkingFoodKeepsTheDeclaration() {
    String v = variant(T, "Oatcakes", "OAT-1");
    put("/admin/products/variants/" + v + "/allergens", "{\"allergens\":[]}", T);
    put("/admin/products/variants/" + v + "/compliance", "{\"food\":true}", T);

    assertThat(
        get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class),
        containsString("\"status\":\"DECLARED\""));
  }

  @Test
  @DisplayName("Marking an item as not food removes its allergen statement")
  void notFoodClearsTheDeclaration() {
    String v = variant(T, "Bin bags", "BIN-1");
    // Declared by mistake on something nobody eats.
    put(
        "/admin/products/variants/" + v + "/allergens",
        "{\"allergens\":[{\"code\":\"MILK\",\"presence\":\"CONTAINS\"}]}",
        T);
    assertThat(
        put("/admin/products/variants/" + v + "/compliance", "{\"food\":false}", T).getStatus(),
        is(200));

    String body = get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class);
    assertThat(body, containsString("\"status\":\"NOT_APPLICABLE\""));
    assertThat(body, not(containsString("MILK")));
  }

  @Test
  @DisplayName("Updating other fields without the flag leaves the allergen status alone")
  void omittingTheFlagLeavesStatus() {
    String v = variant(T, "Shortbread", "SHORT-1");
    put("/admin/products/variants/" + v + "/compliance", "{\"food\":true}", T);
    put("/admin/products/variants/" + v + "/compliance", "{\"countryOfOrigin\":\"GB\"}", T);

    assertThat(
        get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class),
        containsString("\"status\":\"UNDECLARED\""));
  }

  // ── the HSN or SAC code an Indian e-invoice names each line by (18.9) ───────

  @Test
  @DisplayName(
      "An HSN code is kept as its digits, read back with the variant, and refused otherwise")
  void anHsnCodeIsKeptAsDigitsAndResolvedWithTheVariant() {
    String v = variant(T, "Paracetamol 500 mg", "PARA-500");
    Response set =
        put("/admin/products/variants/" + v + "/compliance", "{\"hsnCode\":\" 3004.90 99 \"}", T);
    String setBody = set.readEntity(String.class);
    assertThat(setBody, set.getStatus(), is(200));
    assertThat(setBody, containsString("\"hsnCode\":\"30049099\""));
    String resolved =
        getWith("/admin/products/variants/resolve", "ids", v, T).readEntity(String.class);
    assertThat(resolved, containsString("\"hsnCode\":\"30049099\""));

    for (String bad : new String[] {"123", "30049O99", "3004909901", "12345", "';DROP"}) {
      Response r =
          put("/admin/products/variants/" + v + "/compliance", "{\"hsnCode\":\"" + bad + "\"}", T);
      String body = r.readEntity(String.class);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(body, containsString("PRODUCT_INVALID_HSN_CODE"));
    }
    assertThat(
        "nothing refused was kept",
        getWith("/admin/products/variants/resolve", "ids", v, T).readEntity(String.class),
        containsString("\"hsnCode\":\"30049099\""));

    assertThat(
        "another business resolves nothing of ours",
        getWith("/admin/products/variants/resolve", "ids", v, OTHER).readEntity(String.class),
        not(containsString("30049099")));

    // A service's SAC code is six digits; the update replaces every field, so leaving it out clears
    // it.
    assertThat(
        put("/admin/products/variants/" + v + "/compliance", "{\"hsnCode\":\"998314\"}", T)
            .readEntity(String.class),
        containsString("\"hsnCode\":\"998314\""));
    put("/admin/products/variants/" + v + "/compliance", "{\"countryOfOrigin\":\"IN\"}", T);
    assertThat(
        getWith("/admin/products/variants/resolve", "ids", v, T).readEntity(String.class),
        not(containsString("998314")));
  }

  // ── age restriction, across the five markets ───────────────────────────────

  @Test
  @DisplayName("The same bottle of wine is 18 in the UK, 20 in Japan and 21 in the US")
  void oneProductFiveJurisdictions() {
    String wine = variant(T, "Rioja 75cl", "WINE-1");
    assertThat(
        put(
                "/admin/products/variants/" + wine + "/compliance",
                "{\"restrictionCategory\":\"ALCOHOL\",\"countryOfOrigin\":\"ES\"}",
                T)
            .getStatus(),
        is(200));

    String base = "/catalog/variants/" + wine + "/age-check";
    assertThat(ageAt(base, "GB"), is(18));
    assertThat(ageAt(base, "US"), is(21));
    assertThat(ageAt(base, "JP"), is(20));
    assertThat(ageAt(base, "CN"), is(18));
    // India varies by state; 21 is the conservative floor the seed chose, and a tenant trading
    // where it differs overrides it.
    assertThat(ageAt(base, "IN"), is(21));
  }

  private int ageAt(String path, String country) {
    Response r = getWith(path, "country", country, T);
    assertThat(country + " -> " + r.getStatus(), r.getStatus(), is(200));
    var m =
        java.util.regex.Pattern.compile("\"minimumAge\":(\\d+)")
            .matcher(r.readEntity(String.class));
    assertThat("no minimumAge for " + country, m.find(), is(true));
    return Integer.parseInt(m.group(1));
  }

  @Test
  @DisplayName("An unrestricted product answers no restriction rather than refusing")
  void unrestricted() {
    String bread = variant(T, "Bread", "BREAD-2");
    Response r = getWith("/catalog/variants/" + bread + "/age-check", "country", "GB", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    // A null field is omitted entirely, so "no minimumAge in the JSON" is not a safe thing for a
    // till to act on. The explicit flag is what a client reads.
    assertThat(body, containsString("\"restricted\":false"));
    assertThat(body, not(containsString("minimumAge")));
  }

  @Test
  @DisplayName("A tenant may be stricter than the law, and may never be laxer")
  void stricterYesLaxerNo() {
    String wine = variant(T, "Wine", "WINE-2");
    put(
        "/admin/products/variants/" + wine + "/compliance",
        "{\"restrictionCategory\":\"ALCOHOL\"}",
        T);

    // Challenge-25 as a policy: allowed.
    assertThat(
        put(
                "/admin/age-restriction-rules",
                "{\"country\":\"GB\",\"category\":\"ALCOHOL\",\"minimumAge\":25,"
                    + "\"reason\":\"Challenge 25\"}",
                T)
            .getStatus(),
        is(200));
    assertThat(ageAt("/catalog/variants/" + wine + "/age-check", "GB"), is(25));

    // Sixteen in the UK is an offence, and a system that lets it be configured has helped.
    Response lax =
        put(
            "/admin/age-restriction-rules",
            "{\"country\":\"GB\",\"category\":\"ALCOHOL\",\"minimumAge\":16}",
            T);
    assertThat(lax.getStatus(), is(400));
    assertThat(lax.readEntity(String.class), containsString("PRODUCT_AGE_BELOW_STATUTORY"));
  }

  @Test
  @DisplayName("An override in one tenant does not move another tenant's rule")
  void overrideIsTenantScoped() {
    String wine = variant(T, "Wine", "WINE-3");
    put(
        "/admin/products/variants/" + wine + "/compliance",
        "{\"restrictionCategory\":\"ALCOHOL\"}",
        T);
    put(
        "/admin/age-restriction-rules",
        "{\"country\":\"GB\",\"category\":\"ALCOHOL\",\"minimumAge\":25}",
        T);

    String otherWine = variant(OTHER, "Wine", "WINE-4");
    put(
        "/admin/products/variants/" + otherWine + "/compliance",
        "{\"restrictionCategory\":\"ALCOHOL\"}",
        OTHER);
    Response r =
        target
            .path("/catalog/variants/" + otherWine + "/age-check")
            .queryParam("country", "GB")
            .request()
            .header("X-Tenant-Id", OTHER)
            .get();
    assertThat(r.readEntity(String.class), containsString("\"minimumAge\":18"));
  }

  @Test
  @DisplayName(
      "A restricted item with no rule for that country refuses, rather than saying no restriction")
  void missingRuleRefuses() {
    String knife = variant(T, "Kitchen knife", "KNIFE-1");
    put(
        "/admin/products/variants/" + knife + "/compliance",
        "{\"restrictionCategory\":\"KNIVES\"}",
        T);

    // Japan restricts knives, but this seed has no JP rule for them. Answering "no restriction"
    // would be a system telling a till it is fine to sell a knife to a child.
    Response r = getWith("/catalog/variants/" + knife + "/age-check", "country", "JP", T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PRODUCT_NO_AGE_RULE"));
  }

  @Test
  @DisplayName("The rules in force in a country say which are the tenant's own")
  void rulesListing() {
    put(
        "/admin/age-restriction-rules",
        "{\"country\":\"GB\",\"category\":\"ALCOHOL\",\"minimumAge\":25,\"reason\":\"Challenge 25\"}",
        T);
    String body =
        getWith("/admin/age-restriction-rules", "country", "GB", T).readEntity(String.class);
    assertThat(
        body, containsString("\"category\":\"ALCOHOL\",\"country\":\"GB\",\"minimumAge\":25"));
    assertThat(body, containsString("\"tenantOverride\":true"));
    // The statutory ones are still listed, and still marked as statutory.
    assertThat(
        body, containsString("\"category\":\"TOBACCO\",\"country\":\"GB\",\"minimumAge\":18"));
  }

  // ── 10.8: a date of birth, not an age ─────────────────────────────────────

  private static final java.time.LocalDate BAN_DAY = java.time.LocalDate.of(2027, 1, 1);

  private String cutoffAt(String variant, String country, String tenant) {
    Response r = getWith("/catalog/variants/" + variant + "/age-check", "country", country, tenant);
    assertThat(country + " -> " + r.getStatus(), r.getStatus(), is(200));
    return r.readEntity(String.class);
  }

  private String tobacco(String tenant) {
    String v = variant(tenant, "Cigarettes", "CIG-" + com.storeql.ids.Ids.newId());
    assertThat(
        put(
                "/admin/products/variants/" + v + "/compliance",
                "{\"restrictionCategory\":\"TOBACCO\"}",
                tenant)
            .getStatus(),
        is(200));
    return v;
  }

  @Test
  @DisplayName("The generational tobacco ban is a date of birth, and the till sees it from its day")
  void theGenerationalBanTakesEffectOnItsDay() throws Exception {
    String tenant = britishTenant();
    String cig = tobacco(tenant);

    String rules =
        getWith("/admin/age-restriction-rules", "country", "GB", tenant).readEntity(String.class);
    assertThat(
        rules,
        containsString(
            "\"bornBefore\":\"2009-01-01\",\"bornBeforeFrom\":\"2027-01-01\",\"category\":\"TOBACCO\""));

    // Until 1 Jan 2027 everyone the ban catches is under 18 anyway, so the till is not asked about
    // it yet; from that day it is. Written to stay true on either side of the date.
    String gb = cutoffAt(cig, "GB", tenant);
    assertThat(gb, containsString("\"minimumAge\":18"));
    if (java.time.LocalDate.now(java.time.ZoneOffset.UTC).isBefore(BAN_DAY)) {
      assertThat(gb, not(containsString("bornBefore\"")));
    } else {
      assertThat(gb, containsString("\"bornBefore\":\"2009-01-01\""));
    }

    // A cut-off already in force, and one not yet, in jurisdictions this seed does not carry.
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO product.age_restriction_rules"
                    + " (country, category, minimum_age, note, born_before, born_before_from)"
                    + " VALUES (?, 'TOBACCO', 18, 'test', DATE '2009-01-01', ?)")) {
      ps.setString(1, "NZ");
      ps.setObject(2, java.time.LocalDate.of(2023, 1, 1));
      ps.executeUpdate();
      ps.setString(1, "AU");
      ps.setObject(2, java.time.LocalDate.of(2999, 1, 1));
      ps.executeUpdate();
    }
    String nz = cutoffAt(cig, "NZ", tenant);
    assertThat(nz, containsString("\"bornBefore\":\"2009-01-01\""));
    assertThat(nz, containsString("\"bornBeforeTenantOverride\":false"));
    assertThat(cutoffAt(cig, "AU", tenant), not(containsString("bornBefore\"")));
    // Wine carries no cut-off anywhere.
    String wine = variant(tenant, "Wine", "WINE-CUT");
    put(
        "/admin/products/variants/" + wine + "/compliance",
        "{\"restrictionCategory\":\"ALCOHOL\"}",
        tenant);
    assertThat(cutoffAt(wine, "GB", tenant), not(containsString("bornBefore\"")));
  }

  @Test
  @DisplayName("A business may adopt a cut-off early or an earlier one, never a later one")
  void aTenantCutoffIsStricterNeverLaxer() {
    String tenant = britishTenant();
    String cig = tobacco(tenant);
    String rule = "{\"country\":\"GB\",\"category\":\"TOBACCO\",\"minimumAge\":18,\"bornBefore\":";

    Response early =
        put("/admin/age-restriction-rules", rule + "\"2009-01-01\",\"reason\":\"early\"}", tenant);
    assertThat(early.getStatus(), is(200));
    String now = cutoffAt(cig, "GB", tenant);
    assertThat(now, containsString("\"bornBefore\":\"2009-01-01\""));
    if (java.time.LocalDate.now(java.time.ZoneOffset.UTC).isBefore(BAN_DAY)) {
      assertThat(
          "before the law takes effect the date is the business's",
          now,
          containsString("\"bornBeforeTenantOverride\":true"));
    }

    assertThat(
        put("/admin/age-restriction-rules", rule + "\"2008-06-01\"}", tenant).getStatus(), is(200));
    String stricter = cutoffAt(cig, "GB", tenant);
    assertThat(stricter, containsString("\"bornBefore\":\"2008-06-01\""));
    assertThat(stricter, containsString("\"bornBeforeTenantOverride\":true"));

    Response later = put("/admin/age-restriction-rules", rule + "\"2010-01-01\"}", tenant);
    assertThat(later.getStatus(), is(400));
    assertThat(later.readEntity(String.class), containsString("PRODUCT_BORN_BEFORE_LAXER"));

    for (String bad :
        new String[] {
          "\"01/01/2009\"",
          "\"2009-02-30\"",
          "\"1850-01-01\"",
          "\"2999-01-01\"",
          "\"2009-01-01' OR '1'='1\"",
          "\"" + "9".repeat(500) + "\""
        }) {
      Response r = put("/admin/age-restriction-rules", rule + bad + "}", tenant);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(bad, r.readEntity(String.class), containsString("PRODUCT_INVALID_BORN_BEFORE"));
    }
    // Every refusal left the stricter date standing, and another business sees none of it.
    assertThat(cutoffAt(cig, "GB", tenant), containsString("\"bornBefore\":\"2008-06-01\""));
    String theirs = tobacco(OTHER);
    if (java.time.LocalDate.now(java.time.ZoneOffset.UTC).isBefore(BAN_DAY)) {
      assertThat(cutoffAt(theirs, "GB", OTHER), not(containsString("bornBefore\"")));
    }
    // Setting the age alone again clears the business's own date: a rule is replaced, not merged.
    assertThat(
        put(
                "/admin/age-restriction-rules",
                "{\"country\":\"GB\",\"category\":\"TOBACCO\",\"minimumAge\":21}",
                tenant)
            .getStatus(),
        is(200));
    String cleared = cutoffAt(cig, "GB", tenant);
    assertThat(cleared, containsString("\"minimumAge\":21"));
    assertThat(cleared, not(containsString("2008-06-01")));
  }

  // ── origin and selling by weight ───────────────────────────────────────────

  @Test
  @DisplayName("Country of origin is stored as a code and a sentence")
  void origin() {
    String toms = variant(T, "Tomatoes", "TOM-1");
    assertThat(
        put(
                "/admin/products/variants/" + toms + "/compliance",
                "{\"countryOfOrigin\":\"es\",\"originDetail\":\"Produce of Spain, packed in the UK\","
                    + "\"soldBy\":\"WEIGHT\",\"netContentUom\":\"KG\"}",
                T)
            .getStatus(),
        is(200));

    String body = get("/catalog/variants/" + toms + "/compliance", T).readEntity(String.class);
    // Lower case in, upper case out — a country code is a legal claim, not free text.
    assertThat(body, containsString("\"countryOfOrigin\":\"ES\""));
    assertThat(body, containsString("packed in the UK"));

    Response bad =
        put(
            "/admin/products/variants/" + toms + "/compliance",
            "{\"countryOfOrigin\":\"SPAIN\"}",
            T);
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("PRODUCT_INVALID_COUNTRY"));
  }

  @Test
  @DisplayName("Loose produce is sold by weight, with a unit to price it by")
  void soldByWeight() {
    String bananas = variant(T, "Bananas loose", "BAN-1");
    assertThat(
        put(
                "/admin/products/variants/" + bananas + "/compliance",
                "{\"soldBy\":\"WEIGHT\",\"netContentUom\":\"KG\",\"tareWeight\":0.02}",
                T)
            .getStatus(),
        is(200));
    String body = get("/catalog/variants/" + bananas + "/compliance", T).readEntity(String.class);
    assertThat(body, containsString("\"soldBy\":\"WEIGHT\""));
    assertThat(body, containsString("\"netContentUom\":\"KG\""));
  }

  @Test
  @DisplayName("Sold by weight with no unit is refused — a shelf edge could not price it")
  void weightNeedsAUnit() {
    String cheese = variant(T, "Cheese counter", "CHEESE-1");
    Response r =
        put("/admin/products/variants/" + cheese + "/compliance", "{\"soldBy\":\"WEIGHT\"}", T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PRODUCT_NET_CONTENT_REQUIRED"));
  }

  @Test
  @DisplayName("A catch-weight item needs no fixed content — that is the point of it")
  void catchWeight() {
    String beef = variant(T, "Rib of beef", "BEEF-1");
    assertThat(
        put(
                "/admin/products/variants/" + beef + "/compliance",
                "{\"soldBy\":\"WEIGHT\",\"catchWeight\":true}",
                T)
            .getStatus(),
        is(200));
    assertThat(
        get("/catalog/variants/" + beef + "/compliance", T).readEntity(String.class),
        containsString("\"catchWeight\":true"));
  }

  @Test
  @DisplayName("An unknown unit and a negative tare are both refused")
  void badWeightFields() {
    String v = variant(T, "Odd", "ODD-1");
    String base = "/admin/products/variants/" + v + "/compliance";

    Response uom = put(base, "{\"soldBy\":\"WEIGHT\",\"netContentUom\":\"STONE\"}", T);
    assertThat(uom.getStatus(), is(400));
    assertThat(uom.readEntity(String.class), containsString("PRODUCT_UNKNOWN_UOM"));

    Response tare =
        put(base, "{\"soldBy\":\"WEIGHT\",\"netContentUom\":\"KG\",\"tareWeight\":-1}", T);
    assertThat(tare.getStatus(), is(400));
    assertThat(tare.readEntity(String.class), containsString("PRODUCT_INVALID_TARE"));
  }

  @Test
  @DisplayName("Setting compliance does not quietly reset an allergen declaration")
  void complianceDoesNotClobberAllergens() {
    String v = variant(T, "Cake", "CAKE-2");
    put(
        "/admin/products/variants/" + v + "/allergens",
        "{\"allergens\":[{\"code\":\"EGGS\",\"presence\":\"CONTAINS\"}]}",
        T);
    put("/admin/products/variants/" + v + "/compliance", "{\"countryOfOrigin\":\"GB\"}", T);

    String body = get("/catalog/variants/" + v + "/allergens", T).readEntity(String.class);
    assertThat(body, containsString("\"status\":\"DECLARED\""));
    assertThat(body, containsString("EGGS"));
  }

  // ── refusals the negative-coverage audit found untested (1 Oct 2026) ─────────

  private static String unique(String prefix) {
    String raw = com.storeql.ids.Ids.newId().toString();
    return prefix + "-" + raw.substring(raw.length() - 12);
  }

  @Test
  @DisplayName("The age rules and an age check need the country, and it must be two letters")
  void theRulesAndAgeCheckNeedACountry() {
    String tenant = britishTenant();
    String wine = variant(tenant, "Wine", unique("WINE"));
    assertThat(
        put(
                "/admin/products/variants/" + wine + "/compliance",
                "{\"restrictionCategory\":\"ALCOHOL\"}",
                tenant)
            .getStatus(),
        is(200));

    Response rules = getAdmin("/admin/age-restriction-rules", tenant);
    assertThat(rules.getStatus(), is(400));
    assertThat(rules.readEntity(String.class), containsString("PRODUCT_COUNTRY_REQUIRED"));
    Response check = get("/catalog/variants/" + wine + "/age-check", tenant);
    assertThat(check.getStatus(), is(400));
    assertThat(check.readEntity(String.class), containsString("PRODUCT_COUNTRY_REQUIRED"));

    Response long3 = getWith("/catalog/variants/" + wine + "/age-check", "country", "GBR", tenant);
    assertThat(long3.getStatus(), is(400));
    assertThat(long3.readEntity(String.class), containsString("PRODUCT_INVALID_COUNTRY"));
    Response blank = getWith("/admin/age-restriction-rules", "country", "  ", tenant);
    assertThat(blank.getStatus(), is(400));
    assertThat(blank.readEntity(String.class), containsString("PRODUCT_COUNTRY_REQUIRED"));
  }

  @Test
  @DisplayName("An age outside a human life is refused and leaves the rules as they were")
  void anAgeOutsideAHumanLifeIsRefused() {
    String tenant = britishTenant();
    for (int age : new int[] {121, -1}) {
      Response r =
          put(
              "/admin/age-restriction-rules",
              "{\"country\":\"GB\",\"category\":\"ALCOHOL\",\"minimumAge\":" + age + "}",
              tenant);
      assertThat("age " + age, r.getStatus(), is(400));
      assertThat(r.readEntity(String.class), containsString("PRODUCT_INVALID_AGE"));
    }
    String rules =
        getWith("/admin/age-restriction-rules", "country", "GB", tenant).readEntity(String.class);
    assertThat(
        "no rule of ours was written", rules, not(containsString("\"tenantOverride\":true")));
  }

  @Test
  @DisplayName("A selling unit outside the four is refused and the compliance stays as it was")
  void aSellingUnitOutsideTheFourIsRefused() {
    String tenant = britishTenant();
    String v = variant(tenant, "Loose rice", unique("RICE"));
    String path = "/admin/products/variants/" + v + "/compliance";
    assertThat(
        put(path, "{\"soldBy\":\"WEIGHT\",\"netContentUom\":\"KG\"}", tenant).getStatus(), is(200));

    Response bad = put(path, "{\"soldBy\":\"CASE\"}", tenant);
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("PRODUCT_INVALID_SOLD_BY"));

    String kept = get("/catalog/variants/" + v + "/compliance", tenant).readEntity(String.class);
    assertThat(kept, containsString("\"soldBy\":\"WEIGHT\""));
    assertThat(kept, containsString("\"netContentUom\":\"KG\""));
  }
}
