package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JurisdictionsTest {

  private static final UUID TENANT = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478e2");

  /** A clock a test can move. */
  private static final class Hands extends Clock {
    Instant now = Instant.parse("2026-09-14T09:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final String RULES =
      "{\"data\":{\"country\":\"GB\",\"obligations\":["
          + "{\"code\":\"GDPR\",\"scope\":\"EU\",\"effectiveFrom\":\"2018-05-25\",\"effectiveTo\":\"2020-01-31\"},"
          + "{\"code\":\"TOBACCO_BIRTH_COHORT\",\"scope\":\"GB\",\"effectiveFrom\":\"2027-01-01\"}]}}";

  private static final String LIMITS =
      "{\"data\":{\"country\":\"FR\",\"obligations\":[],\"cashLimits\":["
          + "{\"scope\":\"EU\",\"currency\":\"EUR\",\"fromAmount\":10000,\"effectiveFrom\":\"2027-07-10\",\"citation\":\"AMLR art.80\"},"
          + "{\"scope\":\"FR\",\"currency\":\"EUR\",\"fromAmount\":1000.00,\"effectiveFrom\":\"2015-09-01\",\"citation\":\"CMF L112-6\"},"
          + "{\"scope\":\"FR\",\"currency\":\"USD\",\"fromAmount\":1,\"effectiveFrom\":\"2000-01-01\",\"effectiveTo\":\"2001-01-01\",\"citation\":\"old\"}]}}";

  private static final String SCHEMES =
      "{\"data\":{\"country\":\"DE\",\"obligations\":[],\"cashLimits\":[],\"depositSchemes\":["
          + "{\"scope\":\"DE\",\"currency\":\"EUR\",\"depositEach\":0.25,\"materials\":[\"PET\",\"ALUMINIUM\",\"STEEL\",\"GLASS\"],"
          + "\"minVolumeMl\":100,\"maxVolumeMl\":3000,\"vatTreatment\":\"STANDARD\",\"effectiveFrom\":\"2003-01-01\",\"citation\":\"VerpackG §31\"},"
          + "{\"scope\":\"GB\",\"currency\":\"GBP\",\"depositEach\":0.20,\"materials\":[\"PET\",\"ALUMINIUM\",\"STEEL\"],"
          + "\"minVolumeMl\":150,\"maxVolumeMl\":3000,\"vatTreatment\":\"OUTSIDE_SCOPE\",\"effectiveFrom\":\"2027-10-01\",\"citation\":\"SI 2025/67\"}]}}";

  @Test
  @DisplayName(
      "The deposit scheme in force where the store trades, in the currency, covers a container by material and volume")
  void theDepositSchemeInForceCoversAContainer() {
    var j = Jurisdictions.forTest(profiles("DE"), (t, c) -> Optional.of(SCHEMES), new Hands());
    var scheme = j.depositScheme(TENANT, null, "eur", LocalDate.of(2026, 9, 16));
    assertTrue(scheme.isPresent());
    assertEquals("DE", scheme.get().scope());
    assertEquals(0, scheme.get().depositEach().compareTo(new java.math.BigDecimal("0.25")));
    assertTrue(scheme.get().taxed(), "Germany taxes the Pfand as the drink");
    assertTrue(scheme.get().covers("pet", 500));
    assertTrue(scheme.get().covers("GLASS", 3000));
    assertTrue(!scheme.get().covers("GLASS", 5000), "a five-litre jar is outside the band");
    assertTrue(!scheme.get().covers("PET", 50), "a miniature is below it");
    assertTrue(!scheme.get().covers("CARDBOARD", 500), "a carton is not a material it names");
    assertTrue(!scheme.get().covers(null, 500));
    assertTrue(
        j.depositScheme(TENANT, null, "GBP", LocalDate.of(2026, 9, 16)).isEmpty(),
        "the sheet's German scheme is not in pounds");
    assertEquals(2, j.depositSchemes(TENANT, "DE").size());
    var uk = j.depositSchemes(TENANT, "DE").get(1);
    assertTrue(!uk.inForceOn(LocalDate.of(2027, 9, 30)));
    assertTrue(uk.inForceOn(LocalDate.of(2027, 10, 1)));
    assertTrue(!uk.taxed(), "the UK deposit is outside the scope of VAT");
  }

  @Test
  @DisplayName("A sheet without deposit schemes has none")
  void aSheetWithoutSchemesHasNone() {
    var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(RULES), new Hands());
    assertTrue(j.depositSchemes(TENANT, "GB").isEmpty());
    assertTrue(j.depositScheme(TENANT, null, "GBP", LocalDate.of(2026, 9, 16)).isEmpty());
  }

  @Test
  @DisplayName("The cash limit is the lowest in force in the currency where the store trades")
  void theLowestCashLimitInForceInTheCurrencyBinds() {
    var j = Jurisdictions.forTest(profiles("FR"), (t, c) -> Optional.of(LIMITS), new Hands());
    var limit = j.cashLimit(TENANT, null, "eur", LocalDate.of(2026, 9, 16));
    assertTrue(limit.isPresent());
    assertEquals(new java.math.BigDecimal("1000.00"), limit.get().fromAmount());
    assertTrue(limit.get().refuses(new java.math.BigDecimal("1000.00")));
    assertFalse(limit.get().refuses(new java.math.BigDecimal("999.99")));
    assertTrue(
        j.cashLimit(TENANT, null, "GBP", LocalDate.of(2026, 9, 16)).isEmpty(),
        "a limit in another currency does not bind");
    assertTrue(
        j.cashLimit(TENANT, null, "USD", LocalDate.of(2026, 9, 16)).isEmpty(),
        "one that ended does not bind");
    assertEquals(3, j.cashLimits(TENANT, "FR").size());
    assertFalse(
        j.cashLimits(TENANT, "FR").get(0).inForceOn(LocalDate.of(2027, 7, 9)),
        "the EU cap is upcoming");
    assertTrue(j.cashLimits(TENANT, "FR").get(0).inForceOn(LocalDate.of(2027, 7, 10)));
  }

  @Test
  @DisplayName("A sheet without cash limits still reads, with none")
  void aSheetWithoutLimitsHasNone() {
    var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(RULES), new Hands());
    assertTrue(j.cashLimits(TENANT, "GB").isEmpty());
    assertTrue(j.cashLimit(TENANT, null, "GBP", LocalDate.of(2026, 9, 16)).isEmpty());
  }

  private static final String LIMITED =
      "{\"data\":{\"country\":\"DE\",\"obligations\":["
          + "{\"code\":\"X_PERIOD\",\"scope\":\"EU\",\"effectiveFrom\":\"2018-05-25\","
          + "\"limitValue\":30,\"limitUnit\":\"DAYS\",\"qualifier\":\"EMAIL\"},"
          + "{\"code\":\"X_PLAIN\",\"scope\":\"DE\",\"effectiveFrom\":\"2018-05-25\"}]}}";

  @Test
  @DisplayName(
      "An obligation with a limit reaches a consuming service with its number, unit and case")
  void anObligationLimitReachesTheConsumer() {
    var j = Jurisdictions.forTest(profiles("DE"), (t, c) -> Optional.of(LIMITED), new Hands());
    var rows = j.obligations(TENANT, "DE");
    var limited = rows.stream().filter(o -> o.code().equals("X_PERIOD")).findFirst().orElseThrow();
    assertEquals(0, limited.limitValue().compareTo(new java.math.BigDecimal("30")));
    assertEquals("DAYS", limited.limitUnit());
    assertEquals("EMAIL", limited.qualifier());
    assertTrue(limited.hasLimit());
    var plain = rows.stream().filter(o -> o.code().equals("X_PLAIN")).findFirst().orElseThrow();
    assertFalse(plain.hasLimit(), "a row with no limit is read as before");
    assertEquals(null, plain.limitValue());
    assertEquals(null, plain.qualifier());
  }

  private static TenantProfiles profiles(String country) {
    return TenantProfiles.forTest(
        id -> Optional.of("{\"data\":{\"currency\":\"GBP\",\"country\":\"" + country + "\"}}"),
        Clock.systemUTC());
  }

  @Test
  @DisplayName("An obligation binds from its first day, not the day before")
  void inForceFromItsDay() {
    var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(RULES), new Hands());
    assertFalse(j.inForce(TENANT, "TOBACCO_BIRTH_COHORT", LocalDate.of(2026, 12, 31)));
    assertTrue(j.inForce(TENANT, "TOBACCO_BIRTH_COHORT", LocalDate.of(2027, 1, 1)));
    assertTrue(j.inForce(TENANT, "TOBACCO_BIRTH_COHORT", LocalDate.of(2040, 6, 1)));
    // A code the rules do not carry binds nobody.
    assertFalse(j.inForce(TENANT, "NOT_A_LAW", LocalDate.of(2040, 6, 1)));
  }

  @Test
  @DisplayName("A window that closed ends the obligation on its last day")
  void anEndedWindowEnds() {
    var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(RULES), new Hands());
    assertTrue(j.inForce(TENANT, "GDPR", LocalDate.of(2020, 1, 31)));
    assertFalse(j.inForce(TENANT, "GDPR", LocalDate.of(2020, 2, 1)));
    assertFalse(j.inForce(TENANT, "GDPR", LocalDate.of(2018, 5, 24)));
  }

  @Test
  @DisplayName("The tenant's own country is asked about, and a store's may be named instead")
  void theCountryAskedAbout() {
    List<String> asked = new ArrayList<>();
    var j =
        Jurisdictions.forTest(
            profiles("DE"),
            (t, c) -> {
              asked.add(c);
              return Optional.of(RULES);
            },
            new Hands());
    j.inForce(TENANT, "GDPR", LocalDate.of(2026, 1, 1));
    j.inForceIn(TENANT, " fr ", "GDPR", LocalDate.of(2026, 1, 1));
    assertEquals(List.of("DE", "FR"), asked);
  }

  @Test
  @DisplayName("A country's rules are cached for an hour; a failed read is not cached")
  void cachedPerCountryButNotFailures() {
    Hands hands = new Hands();
    int[] reads = {0};
    boolean[] down = {true};
    var j =
        Jurisdictions.forTest(
            profiles("GB"),
            (t, c) -> {
              reads[0]++;
              return down[0] ? Optional.empty() : Optional.of(RULES);
            },
            hands);
    assertThrows(ApiException.class, () -> j.obligations(TENANT, "GB"));
    down[0] = false;
    j.obligations(TENANT, "GB");
    j.obligations(TENANT, "GB");
    assertEquals(2, reads[0]);
    hands.now = hands.now.plus(Jurisdictions.TTL).plusSeconds(1);
    j.obligations(TENANT, "GB");
    assertEquals(3, reads[0]);
  }

  @Test
  @DisplayName("Rules that cannot be read are refused, never assumed absent")
  void unreadableRulesAreRefused() {
    for (String bad :
        new String[] {
          "not json",
          "{\"data\":null}",
          "{\"data\":{\"country\":\"GB\"}}",
          "{\"data\":{\"obligations\":[{\"code\":\"GDPR\",\"effectiveFrom\":\"soon\"}]}}",
          "{\"data\":{\"obligations\":[{\"scope\":\"EU\",\"effectiveFrom\":\"2018-05-25\"}]}}"
        }) {
      var j = Jurisdictions.forTest(profiles("GB"), (t, c) -> Optional.of(bad), new Hands());
      ApiException e =
          assertThrows(
              ApiException.class, () -> j.inForce(TENANT, "GDPR", LocalDate.of(2019, 1, 1)), bad);
      assertEquals(503, e.status(), bad);
      assertEquals("OBLIGATIONS_UNAVAILABLE", e.code(), bad);
    }
    // A tenant whose own country cannot be read is refused before any rule is asked for.
    var noProfile =
        Jurisdictions.forTest(
            TenantProfiles.forTest(id -> Optional.empty(), Clock.systemUTC()),
            (t, c) -> Optional.of(RULES),
            new Hands());
    assertEquals(
        "TENANT_PROFILE_UNAVAILABLE",
        assertThrows(
                ApiException.class,
                () -> noProfile.inForce(TENANT, "GDPR", LocalDate.of(2019, 1, 1)))
            .code());
  }

  @Test
  @DisplayName(
      "An offer at a store reaches its country and the business's; with no store, every store's")
  void theCountriesAnOfferReaches() {
    UUID de = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f1");
    UUID none = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f2");
    UUID fr = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f3");
    UUID stranger = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f4");
    TenantProfiles profiles =
        TenantProfiles.forTest(
            id -> Optional.of("{\"data\":{\"currency\":\"GBP\",\"country\":\"GB\"}}"),
            (t, after) ->
                Optional.of(
                    "{\"data\":[{\"id\":\""
                        + de
                        + "\",\"country\":\"DE\"},{\"id\":\""
                        + none
                        + "\"},{\"id\":\""
                        + fr
                        + "\",\"country\":\"FR\"}],\"meta\":{}}"),
            Clock.systemUTC());
    String euOnly =
        "{\"data\":{\"obligations\":[{\"code\":\"PRICE_REDUCTION_PRIOR_PRICE\",\"scope\":\"EU\","
            + "\"effectiveFrom\":\"2022-05-28\"}]}}";
    var j =
        Jurisdictions.forTest(
            profiles,
            (t, c) -> Optional.of("GB".equals(c) ? "{\"data\":{\"obligations\":[]}}" : euOnly),
            new Hands());
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB", "DE", "FR"), j.countriesTrading(TENANT, null));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB", "DE"), j.countriesTrading(TENANT, de));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB"), j.countriesTrading(TENANT, none));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Set.of("GB", "DE", "FR"),
        j.countriesTrading(TENANT, stranger),
        "a store that is not the business's cannot narrow the law to the business's own country");

    LocalDate day = LocalDate.of(2026, 9, 14);
    assertTrue(j.inForceWhereTrading(TENANT, de, "PRICE_REDUCTION_PRIOR_PRICE", day));
    assertFalse(j.inForceWhereTrading(TENANT, none, "PRICE_REDUCTION_PRIOR_PRICE", day));
    assertTrue(
        j.inForceWhereTrading(TENANT, null, "PRICE_REDUCTION_PRIOR_PRICE", day),
        "online, a British business with a German shop is bound");
    assertFalse(
        j.inForce(TENANT, "PRICE_REDUCTION_PRIOR_PRICE", day),
        "the business's own country alone is unchanged");
  }

  @Test
  @DisplayName("Stores that cannot be read refuse the question rather than narrow it")
  void unreadableStoresRefuse() {
    TenantProfiles profiles =
        TenantProfiles.forTest(
            id -> Optional.of("{\"data\":{\"currency\":\"GBP\",\"country\":\"GB\"}}"),
            (t, after) -> Optional.empty(),
            Clock.systemUTC());
    var j = Jurisdictions.forTest(profiles, (t, c) -> Optional.of(RULES), new Hands());
    ApiException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () -> j.inForceWhereTrading(TENANT, null, "GDPR", LocalDate.of(2019, 1, 1)));
    org.junit.jupiter.api.Assertions.assertEquals(503, e.status());
  }
}
