package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantProfilesTest {

  private static final UUID TENANT = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478e1");

  private static String body(String currency, String country) {
    return "{\"data\":{\"id\":\""
        + TENANT
        + "\",\"currency\":"
        + json(currency)
        + ",\"country\":"
        + json(country)
        + "}}";
  }

  private static String json(String v) {
    return v == null ? "null" : "\"" + v + "\"";
  }

  @Test
  @DisplayName("The business name signs its messages; read once, cached, and a failed read retried")
  void theBusinessNameIsReadAndCached() {
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var answer =
        new java.util.concurrent.atomic.AtomicReference<Optional<String>>(Optional.empty());
    var clock =
        new java.util.concurrent.atomic.AtomicReference<>(
            java.time.Instant.parse("2026-09-21T08:00:00Z"));
    TenantProfiles p =
        TenantProfiles.forTest(
            t -> {
              reads.incrementAndGet();
              return answer.get();
            },
            new java.time.Clock() {
              @Override
              public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
              }

              @Override
              public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
              }

              @Override
              public java.time.Instant instant() {
                return clock.get();
              }
            });
    assertTrue(p.businessName(TENANT).isEmpty(), "tenant-svc not answering: no name");
    answer.set(
        Optional.of(
            "{\"data\":{\"name\":\" Hollins Grocers \",\"legalName\":\"Hollins Grocers Ltd\","
                + "\"currency\":\"GBP\"}}"));
    assertEquals("Hollins Grocers", p.businessName(TENANT).orElseThrow());
    assertEquals("Hollins Grocers", p.businessName(TENANT).orElseThrow());
    assertEquals(2, reads.get(), "the failed read was not cached; the good one was");
    clock.set(clock.get().plus(TenantProfiles.TTL).plusSeconds(1));
    answer.set(Optional.of("{\"data\":{\"name\":\"Hollins & Daughters\"}}"));
    assertEquals(
        "Hollins & Daughters", p.businessName(TENANT).orElseThrow(), "renamed, and read again");
    assertTrue(TenantProfiles.parseName("{\"data\":{\"name\":\"  \"}}").isEmpty());
    assertTrue(
        TenantProfiles.parseName("{\"data\":{\"legalName\":\"Hollins Grocers Ltd\"}}").isEmpty(),
        "the legal name is not the name a message is signed with");
    assertTrue(TenantProfiles.parseName("not json").isEmpty());
    assertTrue(p.businessName(null).isEmpty());
  }

  @Test
  @DisplayName("The e-invoicing identity is read as given, and a blank or non-text field is none")
  void readsTheEInvoicingIdentity() {
    var id =
        TenantProfiles.parseIdentity(
                TENANT,
                "{\"data\":{\"currency\":\"EUR\",\"country\":\"DE\",\"legalName\":\" Muster GmbH \","
                    + "\"vatNumber\":\"DE123456789\",\"einvoiceScheme\":\"9930\","
                    + "\"einvoiceId\":\"DE123456789\"}}")
            .orElseThrow();
    assertEquals("Muster GmbH", id.legalName());
    assertEquals("DE123456789", id.vatNumber());
    assertTrue(id.hasElectronicAddress());
    var none =
        TenantProfiles.parseIdentity(
                TENANT,
                "{\"data\":{\"vatNumber\":\"  \",\"einvoiceScheme\":9930,\"einvoiceId\":null}}")
            .orElseThrow();
    assertEquals(null, none.vatNumber());
    assertEquals(null, none.einvoiceScheme());
    assertTrue(!none.hasElectronicAddress());
    assertTrue(TenantProfiles.parseIdentity(TENANT, "{\"data\":null}").isEmpty());
    assertTrue(TenantProfiles.parseIdentity(TENANT, "not json").isEmpty());
  }

  @Test
  @DisplayName("A tenant's own currency and country are read, trimmed and upper-cased")
  void readsTheTenantsOwnProfile() {
    var p = TenantProfiles.parse(TENANT, body(" jpy ", "jp")).orElseThrow();
    assertEquals("JPY", p.currency());
    assertEquals("JP", p.country());
    assertEquals(TENANT, p.tenantId());
    assertEquals("KWD", TenantProfiles.parse(TENANT, body("KWD", "KW")).orElseThrow().currency());
  }

  @Test
  @DisplayName(
      "A sandbox says so in its profile; a live business, or one from before sandboxes, does not")
  void readsWhetherTheTenantIsASandbox() {
    var live = TenantProfiles.parse(TENANT, body("GBP", "GB")).orElseThrow();
    assertEquals(false, live.sandbox());
    var marked =
        TenantProfiles.parse(
                TENANT,
                "{\"data\":{\"id\":\""
                    + TENANT
                    + "\",\"currency\":\"GBP\",\"country\":\"GB\",\"mode\":\"LIVE\"}}")
            .orElseThrow();
    assertEquals(false, marked.sandbox());
    var sandbox =
        TenantProfiles.parse(
                TENANT,
                "{\"data\":{\"id\":\""
                    + TENANT
                    + "\",\"currency\":\"GBP\",\"country\":\"GB\",\"mode\":\"SANDBOX\"}}")
            .orElseThrow();
    assertEquals(true, sandbox.sandbox());

    // And through the reader: cached like the rest, and false — not a guess of true — when the
    // profile cannot be read at all.
    var answer =
        new java.util.concurrent.atomic.AtomicReference<Optional<String>>(Optional.empty());
    TenantProfiles p = TenantProfiles.forTest(t -> answer.get(), java.time.Clock.systemUTC());
    assertEquals(false, p.isSandbox(TENANT));
    answer.set(
        Optional.of(
            "{\"data\":{\"id\":\""
                + TENANT
                + "\",\"currency\":\"GBP\",\"country\":\"GB\",\"mode\":\"SANDBOX\"}}"));
    assertEquals(true, p.isSandbox(TENANT));
    assertEquals(false, p.isSandbox(null));
  }

  @Test
  @DisplayName("Anything short of a real code is no profile, never a guess")
  void refusesWhatIsNotAProfile() {
    for (String bad :
        new String[] {
          body(null, "JP"),
          body("JP", "JP"),
          body("JPYX", "JP"),
          body("J1Y", "JP"),
          body("JPY", null),
          body("JPY", "JPN"),
          body("JPY", ""),
          "{\"data\":null}",
          "{}",
          "{not json",
          "[]",
          "{\"data\":{\"currency\":\"';--\",\"country\":\"JP\"}}"
        }) {
      assertTrue(TenantProfiles.parse(TENANT, bad).isEmpty(), bad);
    }
  }

  @Test
  @DisplayName("A profile is cached for five minutes; a failed read is not cached")
  void cachesWhatItReadsAndNotWhatItCouldNot() {
    AtomicInteger calls = new AtomicInteger();
    Instant[] now = {Instant.parse("2026-09-13T10:00:00Z")};
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0];
          }
        };
    boolean[] up = {false};
    var profiles =
        TenantProfiles.forTest(
            id -> {
              calls.incrementAndGet();
              return up[0] ? Optional.of(body("JPY", "JP")) : Optional.empty();
            },
            clock);

    // tenant-svc down: refused, not guessed, and asked again next time
    var down = assertThrows(ApiException.class, () -> profiles.requireCurrency(TENANT));
    assertEquals(503, down.status());
    assertEquals("TENANT_PROFILE_UNAVAILABLE", down.code());
    assertTrue(profiles.find(TENANT).isEmpty());
    assertEquals(2, calls.get());

    up[0] = true;
    assertEquals("JPY", profiles.requireCurrency(TENANT));
    assertEquals("JP", profiles.requireCountry(TENANT));
    for (int i = 0; i < 100; i++) profiles.find(TENANT);
    assertEquals(3, calls.get());

    now[0] = now[0].plus(TenantProfiles.TTL).plusSeconds(1);
    profiles.find(TENANT);
    assertEquals(4, calls.get());
    assertTrue(profiles.find(null).isEmpty());
  }

  @Test
  @DisplayName("A named currency or country must be a real code; a blank one is the tenant's own")
  void namedCodesAreValidatedAndBlankMeansTheTenants() {
    var profiles =
        TenantProfiles.forTest(id -> Optional.of(body("JPY", "JP")), java.time.Clock.systemUTC());
    assertEquals("JPY", profiles.currencyOr(TENANT, null));
    assertEquals("JPY", profiles.currencyOr(TENANT, "   "));
    assertEquals("EUR", profiles.currencyOr(TENANT, " eur "));
    assertEquals("KWD", profiles.currencyOr(TENANT, "KWD"));
    assertEquals("JP", profiles.countryOr(TENANT, ""));
    assertEquals("IE", profiles.countryOr(TENANT, "ie"));
    for (String bad : new String[] {"12", "POUNDS", "';-", "<B>", "GB P", "ZZZ", "£"}) {
      var e = assertThrows(ApiException.class, () -> profiles.currencyOr(TENANT, bad), bad);
      assertEquals(400, e.status());
      assertEquals("CURRENCY_INVALID", e.code());
    }
    for (String bad : new String[] {"GBR", "Z1", "UK", "<", "ZZ"}) {
      var e = assertThrows(ApiException.class, () -> profiles.countryOr(TENANT, bad), bad);
      assertEquals("COUNTRY_INVALID", e.code());
    }
  }

  private static final UUID STORE_DE = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f1");
  private static final UUID STORE_NONE = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f2");
  private static final UUID STORE_NEW = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f3");

  /** A clock the store tests move by hand. */
  private static final class Moving extends Clock {
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

  private static String storesPage(String next, String... rows) {
    return "{\"data\":["
        + String.join(",", rows)
        + "],\"meta\":{\"nextCursor\":"
        + json(next)
        + "}}";
  }

  private static String store(UUID id, String country) {
    return "{\"id\":\"" + id + "\",\"country\":" + json(country) + "}";
  }

  @Test
  @DisplayName(
      "Every page of stores is read; each store's country kept, upper-cased; none recorded is none")
  void storesAreReadPageByPage() {
    AtomicInteger reads = new AtomicInteger();
    Moving clock = new Moving();
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) -> {
              reads.incrementAndGet();
              return Optional.of(
                  after == null
                      ? storesPage("c1", store(STORE_DE, " de "))
                      : storesPage(null, store(STORE_NONE, null)));
            },
            clock);
    var stores = profiles.stores(TENANT, null);
    assertEquals(java.util.Set.of(STORE_DE, STORE_NONE), stores.ids());
    assertEquals(java.util.Map.of(STORE_DE, "DE"), stores.countries());
    assertEquals(2, reads.get());

    profiles.stores(TENANT, STORE_DE);
    assertEquals(2, reads.get(), "a known store is served from the cache");

    profiles.stores(TENANT, STORE_NEW);
    assertEquals(2, reads.get(), "an unknown store does not force a read inside the re-read gap");
    clock.now = clock.now.plus(TenantProfiles.REREAD).plusSeconds(1);
    profiles.stores(TENANT, STORE_NEW);
    assertEquals(4, reads.get(), "after the gap an unknown store is looked for once");
    profiles.stores(TENANT, STORE_NEW);
    assertEquals(4, reads.get(), "and not again straight away, however often it is asked for");

    clock.now = clock.now.plus(TenantProfiles.TTL);
    profiles.stores(TENANT, null);
    assertEquals(6, reads.get(), "the cache ages out");
  }

  @Test
  @DisplayName("Each store's type is kept, upper-cased; one recorded as neither is a store")
  void storesKnowWhetherTheyAreWarehouses() {
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) ->
                Optional.of(
                    storesPage(
                        null,
                        "{\"id\":\"" + STORE_DE + "\",\"type\":\"warehouse\"}",
                        "{\"id\":\"" + STORE_NONE + "\",\"type\":\"STORE\"}",
                        "{\"id\":\"" + STORE_NEW + "\"}")),
            new Moving());
    var stores = profiles.stores(TENANT, null);
    assertTrue(stores.isWarehouse(STORE_DE));
    assertFalse(stores.isWarehouse(STORE_NONE));
    assertFalse(stores.isWarehouse(STORE_NEW), "no type recorded is a store, as tenant-svc says");
    assertFalse(stores.isWarehouse(null));
  }

  @Test
  @DisplayName("Each store's coordinates are kept where it records them; none is none")
  void storesKnowWhereTheyAre() {
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) ->
                Optional.of(
                    storesPage(
                        null,
                        "{\"id\":\"" + STORE_DE + "\",\"geoLat\":53.8,\"geoLng\":-1.55}",
                        "{\"id\":\"" + STORE_NONE + "\",\"geoLat\":null}")),
            new Moving());
    var stores = profiles.stores(TENANT, null);
    assertEquals(53.8, stores.where(STORE_DE).lat(), 1e-9);
    assertEquals(-1.55, stores.where(STORE_DE).lng(), 1e-9);
    assertEquals(null, stores.where(STORE_NONE));
  }

  @Test
  @DisplayName("A dark store is known as one; a shop and a warehouse are not")
  void storesKnowWhichAreDark() {
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) ->
                Optional.of(
                    storesPage(
                        null,
                        "{\"id\":\"" + STORE_DE + "\",\"type\":\"DARK_STORE\"}",
                        "{\"id\":\"" + STORE_NONE + "\",\"type\":\"STORE\"}")),
            new Moving());
    var stores = profiles.stores(TENANT, null);
    assertTrue(stores.isDark(STORE_DE));
    assertFalse(stores.isDark(STORE_NONE));
    assertFalse(stores.isWarehouse(STORE_DE));
    assertFalse(stores.isDark(null));
  }

  @Test
  @DisplayName("Each store's time zone is kept where it records one; none or unreadable is none")
  void storesKnowTheirTimeZone() {
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) ->
                Optional.of(
                    storesPage(
                        null,
                        "{\"id\":\"" + STORE_DE + "\",\"timezone\":\"Europe/Warsaw\"}",
                        "{\"id\":\"" + STORE_NONE + "\",\"timezone\":null}")),
            new Moving());
    var stores = profiles.stores(TENANT, null);
    assertEquals(java.time.ZoneId.of("Europe/Warsaw"), stores.zoneOf(STORE_DE));
    assertEquals(null, stores.zoneOf(STORE_NONE), "no zone recorded is no zone");
    assertEquals(null, stores.zoneOf(STORE_NEW), "an unknown store is no zone");
    assertEquals(null, stores.zoneOf(null));
  }

  @Test
  @DisplayName("A time zone tenant-svc could not have meant is none, never a guess")
  void anUnreadableTimeZoneIsNone() {
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) ->
                Optional.of(
                    storesPage(
                        null,
                        "{\"id\":\"" + STORE_DE + "\",\"timezone\":\"Not/AZone\"}",
                        "{\"id\":\"" + STORE_NONE + "\",\"timezone\":\"\"}",
                        "{\"id\":\"" + STORE_NEW + "\",\"timezone\":123}")),
            new Moving());
    var stores = profiles.stores(TENANT, null);
    assertEquals(null, stores.zoneOf(STORE_DE), "not a real IANA zone id");
    assertEquals(null, stores.zoneOf(STORE_NONE), "blank is no zone");
    assertEquals(null, stores.zoneOf(STORE_NEW), "a number, not text, is no zone");
  }

  @Test
  @DisplayName("Each store says what its till asks for a phone; anything else is not recorded")
  void storesKnowWhatTheirTillAsks() {
    UUID storeText = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f4");
    UUID storeNumber = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f5");
    var profiles =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) ->
                Optional.of(
                    storesPage(
                        null,
                        "{\"id\":\"" + STORE_DE + "\",\"tillPhone\":\"REQUIRED\"}",
                        "{\"id\":\"" + STORE_NEW + "\",\"tillPhone\":\" off \"}",
                        "{\"id\":\"" + STORE_NONE + "\",\"tillPhone\":null}",
                        "{\"id\":\"" + storeText + "\",\"tillPhone\":\"SOMETIMES\"}",
                        "{\"id\":\"" + storeNumber + "\",\"tillPhone\":1}")),
            new Moving());
    var stores = profiles.stores(TENANT, null);
    assertEquals("REQUIRED", stores.tillPhoneOf(STORE_DE));
    assertEquals("OFF", stores.tillPhoneOf(STORE_NEW), "read as tenant-svc meant it");
    assertEquals(null, stores.tillPhoneOf(STORE_NONE), "none recorded");
    assertEquals(null, stores.tillPhoneOf(storeText), "not one of the three: not recorded");
    assertEquals(null, stores.tillPhoneOf(storeNumber), "a number, not text, is not recorded");
    assertEquals(null, stores.tillPhoneOf(Ids.parse("01a090ae-611e-702c-a97b-d1b8025478f6")));
    assertEquals(null, stores.tillPhoneOf(null));
  }

  @Test
  @DisplayName("Every existing Stores constructor still compiles and carries no zones")
  void everyExistingStoresConstructorStillWorks() {
    assertEquals(
        null,
        new TenantProfiles.Stores(
                java.util.Set.of(STORE_DE),
                java.util.Map.of(),
                java.util.Set.of(),
                java.util.Map.of(),
                java.util.Set.of(),
                java.util.Map.of())
            .tillPhoneOf(STORE_DE),
        "the (ids, countries, warehouses, points, dark, zones) constructor records no till choice");
    assertEquals(
        null,
        new TenantProfiles.Stores(java.util.Set.of(STORE_DE), java.util.Map.of()).zoneOf(STORE_DE),
        "the (ids, countries) constructor");
    assertEquals(
        null,
        new TenantProfiles.Stores(
                java.util.Set.of(STORE_DE), java.util.Map.of(), java.util.Set.of())
            .zoneOf(STORE_DE),
        "the (ids, countries, warehouses) constructor");
    assertEquals(
        null,
        new TenantProfiles.Stores(
                java.util.Set.of(STORE_DE),
                java.util.Map.of(),
                java.util.Set.of(),
                java.util.Map.of())
            .zoneOf(STORE_DE),
        "the (ids, countries, warehouses, points) constructor");
    assertEquals(
        null,
        new TenantProfiles.Stores(
                java.util.Set.of(STORE_DE),
                java.util.Map.of(),
                java.util.Set.of(),
                java.util.Map.of(),
                java.util.Set.of())
            .zoneOf(STORE_DE),
        "the (ids, countries, warehouses, points, dark) constructor");
  }

  @Test
  @DisplayName("Stores that cannot be read are refused, never taken to be none, and not cached")
  void unreadableStoresAreRefused() {
    AtomicInteger reads = new AtomicInteger();
    for (String bad :
        new String[] {
          null,
          "{not json",
          "{}",
          "{\"data\":null}",
          "{\"data\":[{\"country\":\"DE\"}]}",
          "{\"data\":[{\"id\":\"x' OR '1'='1\",\"country\":\"DE\"}]}"
        }) {
      var profiles =
          TenantProfiles.forTest(
              id -> Optional.empty(),
              (tenant, after) -> {
                reads.incrementAndGet();
                return Optional.ofNullable(bad);
              },
              Clock.systemUTC());
      ApiException e = assertThrows(ApiException.class, () -> profiles.stores(TENANT, null));
      assertEquals(503, e.status(), String.valueOf(bad));
      assertEquals("TENANT_STORES_UNAVAILABLE", e.code());
      int before = reads.get();
      assertThrows(ApiException.class, () -> profiles.stores(TENANT, null));
      assertTrue(reads.get() > before, "a failure is not cached: " + bad);
    }
    var endless =
        TenantProfiles.forTest(
            id -> Optional.empty(),
            (tenant, after) -> Optional.of(storesPage("again", store(STORE_DE, "DE"))),
            Clock.systemUTC());
    assertThrows(ApiException.class, () -> endless.stores(TENANT, null));
  }
}
