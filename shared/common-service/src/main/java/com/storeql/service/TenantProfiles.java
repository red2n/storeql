package com.storeql.service;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A tenant's own trading currency and country, read from tenant-svc, which owns them (golden rule
 * #1), for every service that has to stamp one onto a row or a response.
 *
 * <p><b>Why this exists.</b> Services used to fill a missing currency with a literal — {@code
 * "GBP"} in pricing, customer, payment, product and order code, {@code "EUR"} in fiscal code — and
 * a missing country with {@code "GB"}. On a multi-tenant platform that is not a harmless default: a
 * Japanese tenant's price list, store credit or Z-report came out in pounds with no error. The
 * tenant declares both at onboarding and neither can change afterwards, so the right answer is
 * always available; this reads it.
 *
 * <p><b>No fallback, deliberately.</b> When the profile cannot be read, {@link #requireCurrency}
 * and {@link #requireCountry} refuse with {@code 503 TENANT_PROFILE_UNAVAILABLE} rather than guess.
 * A refused write can be retried; money recorded in the wrong currency cannot be told apart from
 * money recorded in the right one. Callers that only display a value use {@link #find}.
 *
 * <p>Profiles are cached per tenant for {@link #TTL}: the two fields are fixed at onboarding, so a
 * short cache costs nothing in correctness and keeps a busy till from asking tenant-svc on every
 * sale. A failed read is not cached, so an outage ends when tenant-svc answers again.
 *
 * <p>The instance is resolved through Consul (rule #4), or taken from {@code
 * storeql.clients.tenant-svc.url} when that is set — for a deployment without discovery and for
 * integration tests, which point it at a stub.
 */
@ApplicationScoped
public class TenantProfiles {

  private static final Logger LOG = System.getLogger(TenantProfiles.class.getName());
  static final Duration TTL = Duration.ofMinutes(5);
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

  /** What a service needs to know about the tenant it is acting for. */
  /**
   * What tenant-svc says of a business.
   *
   * @param sandbox whether it is a sandbox (22.8): a stand-in for a live business where nothing is
   *     real — no message leaves it and no money moves. False for a live business, and for a
   *     profile from before sandboxes existed.
   */
  public record Profile(UUID tenantId, String currency, String country, boolean sandbox) {}

  private record Cached(Profile profile, Instant expiresAt) {}

  /**
   * A tenant's stores, and the country each trades in where one is recorded. A store can sit across
   * a border from the business that owns it, and the law of the store's country reaches what it
   * sells.
   *
   * @param ids every store of the tenant
   * @param countries the upper-cased country of each store that records one
   * @param warehouses the stores of type WAREHOUSE: stock-only sites that serve shops (depot / DC
   *     replenishment); every other store is a shop
   * @param dark the stores of type DARK_STORE: shops with no shop floor, which fill online orders
   *     for delivery only — no collection is offered there and no till opens (ship-from-store and
   *     dark-store picking)
   * @param zones the IANA time zone id of each store that records one (delivery and collection
   *     slots): a store's own hours and windows are set and shown in this zone, never a platform
   *     default — no zone is assumed for a store that records none
   * @param tillPhones what each store's till asks for a phone (a phone at the till): {@code
   *     REQUIRED}, {@code OPTIONAL} or {@code OFF}, for each store that records one of the three
   */
  public record Stores(
      Set<UUID> ids,
      Map<UUID, String> countries,
      Set<UUID> warehouses,
      Map<UUID, Point> points,
      Set<UUID> dark,
      Map<UUID, String> zones,
      Map<UUID, String> tillPhones) {

    /** What a store's till may ask for a phone; tenant-svc's {@code stores.till_phone}. */
    public static final Set<String> TILL_PHONE = Set.of("REQUIRED", "OPTIONAL", "OFF");

    public Stores {
      ids = Set.copyOf(ids);
      countries = Map.copyOf(countries);
      warehouses = Set.copyOf(warehouses);
      points = Map.copyOf(points);
      dark = Set.copyOf(dark);
      zones = Map.copyOf(zones);
      tillPhones = Map.copyOf(tillPhones);
    }

    /** As before a till's phone choice was read: no store records one. */
    public Stores(
        Set<UUID> ids,
        Map<UUID, String> countries,
        Set<UUID> warehouses,
        Map<UUID, Point> points,
        Set<UUID> dark,
        Map<UUID, String> zones) {
      this(ids, countries, warehouses, points, dark, zones, Map.of());
    }

    /** As before time zones were read: no store records one. */
    public Stores(
        Set<UUID> ids,
        Map<UUID, String> countries,
        Set<UUID> warehouses,
        Map<UUID, Point> points,
        Set<UUID> dark) {
      this(ids, countries, warehouses, points, dark, Map.of());
    }

    /** As before dark stores were read: no store is one. */
    public Stores(
        Set<UUID> ids, Map<UUID, String> countries, Set<UUID> warehouses, Map<UUID, Point> points) {
      this(ids, countries, warehouses, points, Set.of(), Map.of());
    }

    /** As before warehouses were read: no store is one. */
    public Stores(Set<UUID> ids, Map<UUID, String> countries) {
      this(ids, countries, Set.of(), Map.of(), Set.of(), Map.of());
    }

    /** As before coordinates were read. */
    public Stores(Set<UUID> ids, Map<UUID, String> countries, Set<UUID> warehouses) {
      this(ids, countries, warehouses, Map.of(), Set.of(), Map.of());
    }

    /**
     * Whether the store is one of the tenant's dark stores: it fills online orders for delivery and
     * offers no collection and no till.
     */
    public boolean isDark(UUID storeId) {
      return storeId != null && dark.contains(storeId);
    }

    /** Where the store is, or null when it records no coordinates. */
    public Point where(UUID storeId) {
      return storeId == null ? null : points.get(storeId);
    }

    /** Whether the store is one of the tenant's. */
    public boolean has(UUID storeId) {
      return storeId != null && ids.contains(storeId);
    }

    /** Whether the store is one of the tenant's warehouses. */
    public boolean isWarehouse(UUID storeId) {
      return storeId != null && warehouses.contains(storeId);
    }

    /**
     * The store's own IANA time zone (delivery and collection slots), or {@code null} when the
     * store is unknown, records none, or what it records is not a zone {@link java.time.ZoneId} can
     * resolve — never a guess, since a window set or shown in the wrong zone is wrong by hours, not
     * by nothing.
     *
     * @param storeId the store
     * @return the zone, or {@code null}
     */
    public java.time.ZoneId zoneOf(UUID storeId) {
      if (storeId == null) return null;
      String tz = zones.get(storeId);
      if (tz == null || tz.isBlank()) return null;
      try {
        return java.time.ZoneId.of(tz);
      } catch (RuntimeException e) {
        return null;
      }
    }

    /**
     * What the store's till asks for a phone (a phone at the till): {@code REQUIRED}, {@code
     * OPTIONAL} or {@code OFF}, or {@code null} when the store is unknown or records none of the
     * three. What a {@code null} means is the caller's to say; order-svc takes it for {@code
     * OPTIONAL}, so a store it cannot read never refuses a sale.
     *
     * @param storeId the store
     * @return the choice, or {@code null}
     */
    public String tillPhoneOf(UUID storeId) {
      return storeId == null ? null : tillPhones.get(storeId);
    }
  }

  /** A store's latitude and longitude, in degrees. */
  public record Point(double lat, double lng) {}

  /** One page of {@code GET /admin/stores}. */
  record StorePage(
      List<UUID> ids,
      Map<UUID, String> countries,
      Set<UUID> warehouses,
      Map<UUID, Point> points,
      Set<UUID> dark,
      Map<UUID, String> zones,
      Map<UUID, String> tillPhones,
      String nextCursor) {}

  private record CachedStores(Stores stores, Instant readAt) {}

  /**
   * How soon a store the cache does not know is looked for again: soon enough that a store opened a
   * minute ago is found, seldom enough that a made-up store id cannot turn every request into a
   * read of tenant-svc.
   */
  static final Duration REREAD = Duration.ofSeconds(30);

  /** A tenant with more pages of stores than this is refused rather than half-read. */
  private static final int MAX_STORE_PAGES = 100;

  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "storeql.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
  private final Map<UUID, CachedStores> storeCache = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private Function<UUID, Optional<String>> fetch;
  private BiFunction<UUID, String, Optional<String>> storesFetch =
      (tenantId, after) -> Optional.empty();

  @PostConstruct
  void init() {
    ServiceReader client = ServiceReader.tenantSvc(settings, tenantSvcUrl);
    fetch = tenantId -> client.body(tenantId, "/admin/tenant", Map.of());
    storesFetch =
        (tenantId, after) ->
            client.body(
                tenantId,
                "/admin/stores",
                after == null ? Map.of("limit", "100") : Map.of("limit", "100", "after", after));
  }

  /** For tests: a fetch function standing in for tenant-svc, and a clock to age the cache with. */
  public static TenantProfiles forTest(Function<UUID, Optional<String>> fetch, Clock clock) {
    TenantProfiles p = new TenantProfiles();
    p.fetch = fetch;
    p.clock = clock;
    return p;
  }

  /** For tests: as {@link #forTest(Function, Clock)}, with pages of stores by cursor. */
  static TenantProfiles forTest(
      Function<UUID, Optional<String>> fetch,
      BiFunction<UUID, String, Optional<String>> storesFetch,
      Clock clock) {
    TenantProfiles p = forTest(fetch, clock);
    p.storesFetch = storesFetch;
    return p;
  }

  /**
   * The tenant's stores and their countries, from the cache or tenant-svc. Cached for {@link #TTL};
   * a store the cache does not know is looked for again at most every {@link #REREAD}. A failed
   * read is not cached.
   *
   * @param including a store the caller is acting at, or null
   * @throws ApiException 503 {@code TENANT_STORES_UNAVAILABLE} when they cannot be read
   */
  public Stores stores(UUID tenantId, UUID including) {
    Instant now = clock.instant();
    CachedStores hit = storeCache.get(tenantId);
    boolean fresh = hit != null && hit.readAt().plus(TTL).isAfter(now);
    boolean lookAgain =
        fresh
            && including != null
            && !hit.stores().has(including)
            && !hit.readAt().plus(REREAD).isAfter(now);
    if (fresh && !lookAgain) return hit.stores();
    Stores read =
        readStores(tenantId)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "TENANT_STORES_UNAVAILABLE",
                        "the tenant's stores could not be read from tenant-svc; nothing was assumed,"
                            + " try again",
                        List.of()));
    storeCache.put(tenantId, new CachedStores(read, now));
    return read;
  }

  private Optional<Stores> readStores(UUID tenantId) {
    Set<UUID> ids = new HashSet<>();
    Map<UUID, String> countries = new HashMap<>();
    Set<UUID> warehouses = new HashSet<>();
    Map<UUID, Point> points = new HashMap<>();
    Set<UUID> dark = new HashSet<>();
    Map<UUID, String> zones = new HashMap<>();
    Map<UUID, String> tillPhones = new HashMap<>();
    String after = null;
    for (int page = 0; page < MAX_STORE_PAGES; page++) {
      Optional<StorePage> read =
          storesFetch.apply(tenantId, after).flatMap(TenantProfiles::parseStores);
      if (read.isEmpty()) return Optional.empty();
      ids.addAll(read.get().ids());
      countries.putAll(read.get().countries());
      warehouses.addAll(read.get().warehouses());
      points.putAll(read.get().points());
      dark.addAll(read.get().dark());
      zones.putAll(read.get().zones());
      tillPhones.putAll(read.get().tillPhones());
      if (read.get().nextCursor() == null) {
        return Optional.of(new Stores(ids, countries, warehouses, points, dark, zones, tillPhones));
      }
      after = read.get().nextCursor();
    }
    LOG.log(Level.WARNING, "more than {0} pages of stores for {1}", MAX_STORE_PAGES, tenantId);
    return Optional.empty();
  }

  /**
   * Reads one page of {@code GET /admin/stores}: each store's id and, when it records one, its
   * country, upper-cased but not otherwise judged — a country the rules cannot read is refused
   * where the rules are asked, not quietly dropped here. {@code timezone} is kept verbatim (an IANA
   * zone id, e.g. {@code "Europe/Warsaw"}); {@link Stores#zoneOf} is where an unreadable one is
   * turned into "none" rather than this parse.
   */
  static Optional<StorePage> parseStores(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      List<UUID> ids = new ArrayList<>();
      Map<UUID, String> countries = new HashMap<>();
      Set<UUID> warehouses = new HashSet<>();
      Map<UUID, Point> points = new HashMap<>();
      Set<UUID> dark = new HashSet<>();
      Map<UUID, String> zones = new HashMap<>();
      Map<UUID, String> tillPhones = new HashMap<>();
      for (JsonValue value : root.getJsonArray("data")) {
        JsonObject store = value.asJsonObject();
        UUID id = Ids.parse(store.getString("id"));
        ids.add(id);
        String type =
            store.containsKey("type") && !store.isNull("type")
                ? upper(store.getString("type"))
                : null;
        if ("WAREHOUSE".equals(type)) warehouses.add(id);
        if ("DARK_STORE".equals(type)) dark.add(id);
        if (store.containsKey("geoLat")
            && !store.isNull("geoLat")
            && store.containsKey("geoLng")
            && !store.isNull("geoLng")) {
          points.put(
              id,
              new Point(
                  store.getJsonNumber("geoLat").doubleValue(),
                  store.getJsonNumber("geoLng").doubleValue()));
        }
        String country =
            store.containsKey("country") && !store.isNull("country")
                ? upper(store.getString("country"))
                : null;
        if (country != null && !country.isEmpty()) countries.put(id, country);
        if (store.containsKey("timezone")
            && !store.isNull("timezone")
            && store.get("timezone").getValueType() == jakarta.json.JsonValue.ValueType.STRING) {
          String tz = store.getString("timezone").strip();
          if (!tz.isEmpty()) zones.put(id, tz);
        }
        if (store.containsKey("tillPhone")
            && !store.isNull("tillPhone")
            && store.get("tillPhone").getValueType() == jakarta.json.JsonValue.ValueType.STRING) {
          String ask = upper(store.getString("tillPhone"));
          if (Stores.TILL_PHONE.contains(ask)) tillPhones.put(id, ask);
        }
      }
      JsonObject meta =
          root.containsKey("meta") && !root.isNull("meta") ? root.getJsonObject("meta") : null;
      String next =
          meta != null && meta.containsKey("nextCursor") && !meta.isNull("nextCursor")
              ? meta.getString("nextCursor")
              : null;
      return Optional.of(
          new StorePage(
              List.copyOf(ids),
              Map.copyOf(countries),
              Set.copyOf(warehouses),
              Map.copyOf(points),
              Set.copyOf(dark),
              Map.copyOf(zones),
              Map.copyOf(tillPhones),
              next == null || next.isBlank() ? null : next));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "unreadable page of stores: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * The tenant's profile, from the cache or tenant-svc.
   *
   * @return empty when tenant-svc cannot be reached, has no such tenant, or answers with a profile
   *     that does not carry a valid ISO 4217 currency and ISO 3166 country
   */
  /**
   * How the business names itself on an e-invoice: its registered name, its VAT identifier (BT-31
   * or BT-48) and its Peppol participant identifier (BT-34 or BT-49). Each is null until the
   * business sets it.
   */
  public record Identity(
      UUID tenantId, String legalName, String vatNumber, String einvoiceScheme, String einvoiceId) {

    /** Whether an access point could deliver to the business. */
    public boolean hasElectronicAddress() {
      return einvoiceScheme != null && einvoiceId != null;
    }
  }

  /**
   * The business's e-invoicing identity, read afresh on every call: it is asked for when an invoice
   * arrives or is issued, which is rare, and one cached from before a correction would misdirect an
   * invoice the moment the correction was made.
   *
   * @return the identity, or empty when tenant-svc could not be read
   */
  public Optional<Identity> identity(UUID tenantId) {
    if (tenantId == null) return Optional.empty();
    return fetch.apply(tenantId).flatMap(body -> parseIdentity(tenantId, body));
  }

  /** Reads the identity out of a {@code GET /admin/tenant} response. */
  static Optional<Identity> parseIdentity(UUID tenantId, String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      return Optional.of(
          new Identity(
              tenantId,
              text(data, "legalName"),
              text(data, "vatNumber"),
              text(data, "einvoiceScheme"),
              text(data, "einvoiceId")));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "malformed tenant identity for {0}: {1}", tenantId, e.getMessage());
      return Optional.empty();
    }
  }

  private static String text(JsonObject o, String key) {
    if (!o.containsKey(key) || o.isNull(key)) return null;
    if (o.get(key).getValueType() != jakarta.json.JsonValue.ValueType.STRING) return null;
    String s = o.getString(key).strip();
    return s.isEmpty() ? null : s;
  }

  private record CachedName(String name, Instant expiresAt) {}

  private final Map<UUID, CachedName> names = new ConcurrentHashMap<>();

  /**
   * The name the business trades under, as it gave it at onboarding and may change since: what a
   * message it sends is signed with. Cached for {@link #TTL}, like the profile; a failed read is
   * not cached.
   *
   * @return the name, or empty when tenant-svc could not be read
   */
  public Optional<String> businessName(UUID tenantId) {
    if (tenantId == null) return Optional.empty();
    Instant now = clock.instant();
    CachedName hit = names.get(tenantId);
    if (hit != null && hit.expiresAt().isAfter(now)) return Optional.of(hit.name());
    Optional<String> read = fetch.apply(tenantId).flatMap(TenantProfiles::parseName);
    read.ifPresent(n -> names.put(tenantId, new CachedName(n, now.plus(TTL))));
    return read;
  }

  /**
   * Reads the business name out of a {@code GET /admin/tenant} response: its {@code name}, the one
   * it trades under — not {@code legalName}, which is for invoices.
   */
  static Optional<String> parseName(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      return Optional.ofNullable(text(root.getJsonObject("data"), "name"));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  public Optional<Profile> find(UUID tenantId) {
    if (tenantId == null) return Optional.empty();
    Instant now = clock.instant();
    Cached hit = cache.get(tenantId);
    if (hit != null && hit.expiresAt().isAfter(now)) return Optional.of(hit.profile());
    Optional<Profile> read = fetch.apply(tenantId).flatMap(body -> parse(tenantId, body));
    read.ifPresent(p -> cache.put(tenantId, new Cached(p, now.plus(TTL))));
    return read;
  }

  /**
   * The tenant's declared currency.
   *
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} when it cannot be read
   */
  /**
   * Whether the business is a sandbox (22.8). False when its profile cannot be read: a business
   * whose nature is unknown is treated as live, so an outage never makes a real business behave as
   * a sandbox — the callers that must not act on a sandbox read the profile for other reasons first
   * and fail closed there.
   */
  public boolean isSandbox(UUID tenantId) {
    return find(tenantId).map(Profile::sandbox).orElse(false);
  }

  public String requireCurrency(UUID tenantId) {
    return require(tenantId).currency();
  }

  /**
   * The tenant's declared country.
   *
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} when it cannot be read
   */
  public String requireCountry(UUID tenantId) {
    return require(tenantId).country();
  }

  /**
   * The currency a request names, or the tenant's own when it names none (SJ-D53).
   *
   * @param requested as sent; blank means none
   * @return an upper-cased ISO 4217 code
   * @throws ApiException 400 {@code CURRENCY_INVALID} for anything that is not one; 503 {@code
   *     TENANT_PROFILE_UNAVAILABLE} when none is named and the tenant's cannot be read
   */
  public String currencyOr(UUID tenantId, String requested) {
    String code = upper(requested);
    if (code == null || code.isEmpty()) return requireCurrency(tenantId);
    if (!ISO_CURRENCIES.contains(code)) {
      throw ApiException.badRequest(
          "CURRENCY_INVALID", "currency must be an ISO 4217 code such as EUR or JPY");
    }
    return code;
  }

  /**
   * The country a request names, or the tenant's own when it names none (SJ-D53).
   *
   * @param requested as sent; blank means none
   * @return an upper-cased ISO 3166-1 alpha-2 code
   * @throws ApiException 400 {@code COUNTRY_INVALID} for anything that is not one; 503 {@code
   *     TENANT_PROFILE_UNAVAILABLE} when none is named and the tenant's cannot be read
   */
  public String countryOr(UUID tenantId, String requested) {
    String code = upper(requested);
    if (code == null || code.isEmpty()) return requireCountry(tenantId);
    if (!ISO_COUNTRIES.contains(code)) {
      throw ApiException.badRequest(
          "COUNTRY_INVALID", "country must be an ISO 3166-1 alpha-2 code such as DE or JP");
    }
    return code;
  }

  private static final java.util.Set<String> ISO_CURRENCIES =
      java.util.Currency.getAvailableCurrencies().stream()
          .map(java.util.Currency::getCurrencyCode)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private static final java.util.Set<String> ISO_COUNTRIES =
      java.util.Set.of(Locale.getISOCountries());

  private Profile require(UUID tenantId) {
    return find(tenantId)
        .orElseThrow(
            () ->
                new ApiException(
                    503,
                    "TENANT_PROFILE_UNAVAILABLE",
                    "the tenant's currency and country could not be read from tenant-svc;"
                        + " nothing was assumed, try again",
                    List.of()));
  }

  /**
   * Reads a {@code GET /admin/tenant} response. Extracted so every rule is reachable by a unit
   * test, for the reason SJ-D14 established: parsing inline behind discovery is where a
   * cross-service contract goes wrong and stays wrong.
   */
  static Optional<Profile> parse(UUID tenantId, String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      String currency = upper(data.getString("currency", null));
      String country = upper(data.getString("country", null));
      if (currency == null || !CURRENCY.matcher(currency).matches()) return Optional.empty();
      if (country == null || !COUNTRY.matcher(country).matches()) return Optional.empty();
      String mode = upper(data.getString("mode", null));
      return Optional.of(new Profile(tenantId, currency, country, "SANDBOX".equals(mode)));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "malformed tenant profile for {0}: {1}", tenantId, e.getMessage());
      return Optional.empty();
    }
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
  }
}
