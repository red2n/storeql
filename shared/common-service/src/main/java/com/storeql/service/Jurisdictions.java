package com.storeql.service;

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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Which laws bind the tenant a service is acting for, read from tenant-svc's jurisdiction rules
 * ({@code GET /admin/tenant/obligations}) instead of a list of countries and dates in the service's
 * own code — the lesson of SJ-D53, applied to the law.
 *
 * <p><b>Refuses rather than guesses.</b> When the rules cannot be read, {@link #inForce} throws
 * {@code 503 OBLIGATIONS_UNAVAILABLE}. A duty assumed absent is how a till takes a payment the law
 * forbids; a duty assumed present blocks a sale the law allows. The caller decides which way a
 * refused answer should fail for its own case.
 *
 * <p>A country's rules change only with the law, by migration, so they are cached per country for
 * {@link #TTL}; a failed read is not cached. Every window is fetched, not only today's, so a
 * question about any day is answered from the same list.
 */
@ApplicationScoped
public class Jurisdictions {

  private static final Logger LOG = System.getLogger(Jurisdictions.class.getName());
  static final Duration TTL = Duration.ofHours(1);

  /**
   * The earliest day tenant-svc accepts; asking "as of" it returns every window, since nothing had
   * ended by then.
   */
  private static final String EVERY_WINDOW = "1900-01-01";

  /** One obligation as it reaches a country, and the window it applies in. */
  public record Obligation(
      String code,
      String scope,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      BigDecimal limitValue,
      String limitUnit,
      String qualifier) {

    /** An obligation that sets no number and names no case. */
    public Obligation(String code, String scope, LocalDate effectiveFrom, LocalDate effectiveTo) {
      this(code, scope, effectiveFrom, effectiveTo, null, null, null);
    }

    /** True when the law sets a number (a period, a minimum, a share) on this obligation. */
    public boolean hasLimit() {
      return limitValue != null && limitUnit != null;
    }

    /** True from its first day to its last, inclusive; an open window never ends. */
    public boolean inForceOn(LocalDate day) {
      return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }
  }

  /**
   * A cash payment limit reaching a country (09.17): cash of {@code fromAmount} or more, in the
   * currency the law names, is refused while it is in force.
   */
  public record CashLimit(
      String scope,
      String currency,
      BigDecimal fromAmount,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String citation) {

    public boolean inForceOn(LocalDate day) {
      return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    /** Whether a cash payment of this amount is refused under this limit. */
    public boolean refuses(BigDecimal amount) {
      return amount.compareTo(fromAmount) >= 0;
    }
  }

  /**
   * A deposit return scheme reaching a country (09.16): what a drink in an in-scope container
   * carries as a deposit while the scheme is in force, and how the deposit is taxed.
   */
  public record DepositScheme(
      String scope,
      String currency,
      BigDecimal depositEach,
      List<String> materials,
      int minVolumeMl,
      int maxVolumeMl,
      String vatTreatment,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String citation) {

    public static final String OUTSIDE_SCOPE = "OUTSIDE_SCOPE";
    public static final String STANDARD = "STANDARD";

    public boolean inForceOn(LocalDate day) {
      return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    /** Whether a container of this material and volume is in the scheme. */
    public boolean covers(String material, int volumeMl) {
      return material != null
          && materials.stream().anyMatch(m -> m.equalsIgnoreCase(material.trim()))
          && volumeMl >= minVolumeMl
          && volumeMl <= maxVolumeMl;
    }

    /** Whether the deposit is taxed as the drink is, rather than outside the scope of VAT. */
    public boolean taxed() {
      return STANDARD.equals(vatTreatment);
    }
  }

  /** The sheet tenant-svc gives for a country: its obligations, cash limits and deposit schemes. */
  record Sheet(
      List<Obligation> obligations,
      List<CashLimit> cashLimits,
      List<DepositScheme> depositSchemes) {}

  private record Cached(Sheet sheet, Instant expiresAt) {}

  @Inject TenantProfiles profiles;
  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "storeql.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private BiFunction<UUID, String, Optional<String>> fetch;

  @PostConstruct
  void init() {
    ServiceReader client = ServiceReader.tenantSvc(settings, tenantSvcUrl);
    fetch =
        (tenantId, country) ->
            client.body(
                tenantId,
                "/admin/tenant/obligations",
                Map.of("country", country, "on", EVERY_WINDOW));
  }

  /** For tests: the tenant profiles to take a country from, a stand-in fetch, and a clock. */
  static Jurisdictions forTest(
      TenantProfiles profiles, BiFunction<UUID, String, Optional<String>> fetch, Clock clock) {
    Jurisdictions j = new Jurisdictions();
    j.profiles = profiles;
    j.fetch = fetch;
    j.clock = clock;
    return j;
  }

  /**
   * Whether an obligation binds the tenant's own country on a day.
   *
   * @param code the obligation's code, such as {@code PRICE_REDUCTION_PRIOR_PRICE}
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} or {@code OBLIGATIONS_UNAVAILABLE}
   */
  public boolean inForce(UUID tenantId, String code, LocalDate day) {
    return inForceIn(tenantId, profiles.requireCountry(tenantId), code, day);
  }

  /**
   * Whether an obligation binds a named country on a day — for a store across a border from the
   * business that owns it.
   *
   * @throws ApiException 503 {@code OBLIGATIONS_UNAVAILABLE}
   */
  public boolean inForceIn(UUID tenantId, String country, String code, LocalDate day) {
    return obligations(tenantId, country).stream()
        .anyMatch(o -> o.code().equals(code) && o.inForceOn(day));
  }

  /**
   * The countries whose law reaches an offer: the business's own and, at one of its stores, that
   * store's. With no store — or a store that is not the business's — every store's, since an offer
   * made nowhere in particular is made wherever the business trades. A store that records no
   * country trades in the business's own.
   *
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} or {@code
   *     TENANT_STORES_UNAVAILABLE}
   */
  public Set<String> countriesTrading(UUID tenantId, UUID storeId) {
    Set<String> out = new TreeSet<>();
    out.add(profiles.requireCountry(tenantId));
    TenantProfiles.Stores stores = profiles.stores(tenantId, storeId);
    if (stores.has(storeId)) {
      String country = stores.countries().get(storeId);
      if (country != null) out.add(country);
    } else {
      out.addAll(stores.countries().values());
    }
    return Set.copyOf(out);
  }

  /**
   * Whether an obligation binds any country an offer reaches, by {@link #countriesTrading}: a store
   * across a border cannot trade outside the law of the country it stands in.
   *
   * @throws ApiException 503 when the tenant, its stores or any country's rules cannot be read
   */
  public boolean inForceWhereTrading(UUID tenantId, UUID storeId, String code, LocalDate day) {
    for (String country : countriesTrading(tenantId, storeId)) {
      if (inForceIn(tenantId, country, code, day)) return true;
    }
    return false;
  }

  /**
   * Every obligation that reaches a country, with its window.
   *
   * @throws ApiException 503 {@code OBLIGATIONS_UNAVAILABLE} when tenant-svc cannot answer
   */
  public List<Obligation> obligations(UUID tenantId, String country) {
    return sheet(tenantId, country).obligations();
  }

  /**
   * The cash payment limits that reach a country, in force or upcoming (09.17).
   *
   * @throws ApiException 503 {@code OBLIGATIONS_UNAVAILABLE} when tenant-svc cannot be read
   */
  public List<CashLimit> cashLimits(UUID tenantId, String country) {
    return sheet(tenantId, country).cashLimits();
  }

  /**
   * The lowest cash limit in force on a day, in a currency, where a store trades: the store's
   * country when a store is named, else the business's own. A limit the law names in another
   * currency does not apply — the equivalent is a matter of rate, not of law — so a business whose
   * currency is not the law's meets no limit until the register carries one in its currency.
   *
   * @param storeId the store the cash is taken at, or null for the business's own country
   * @return the limit, or empty when none binds
   */
  public Optional<CashLimit> cashLimit(
      UUID tenantId, UUID storeId, String currency, LocalDate day) {
    String country = countryOf(tenantId, storeId);
    String cur = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    return cashLimits(tenantId, country).stream()
        .filter(l -> l.inForceOn(day) && l.currency().equalsIgnoreCase(cur))
        .min(java.util.Comparator.comparing(CashLimit::fromAmount));
  }

  /** The deposit return schemes that reach a country, in force or upcoming (09.16). */
  public List<DepositScheme> depositSchemes(UUID tenantId, String country) {
    return sheet(tenantId, country).depositSchemes();
  }

  /**
   * The deposit scheme in force on a day, in a currency, where a store trades (09.16): the store's
   * country when a store is named, else the business's own. A scheme the law names in another
   * currency does not apply.
   *
   * @return the scheme, or empty when none binds
   */
  public Optional<DepositScheme> depositScheme(
      UUID tenantId, UUID storeId, String currency, LocalDate day) {
    String country = countryOf(tenantId, storeId);
    String cur = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    return depositSchemes(tenantId, country).stream()
        .filter(d -> d.inForceOn(day) && d.currency().equalsIgnoreCase(cur))
        .findFirst();
  }

  private String countryOf(UUID tenantId, UUID storeId) {
    String country = profiles.requireCountry(tenantId);
    if (storeId != null) {
      TenantProfiles.Stores stores = profiles.stores(tenantId, storeId);
      String storeCountry = stores.countries().get(storeId);
      if (storeCountry != null) country = storeCountry;
    }
    return country;
  }

  private Sheet sheet(UUID tenantId, String country) {
    String cc = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
    Instant now = clock.instant();
    Cached hit = cache.get(cc);
    if (hit != null && hit.expiresAt().isAfter(now)) return hit.sheet();
    Sheet read =
        fetch
            .apply(tenantId, cc)
            .flatMap(Jurisdictions::parse)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "OBLIGATIONS_UNAVAILABLE",
                        "the legal obligations for "
                            + cc
                            + " could not be read from tenant-svc; nothing was assumed, try again",
                        List.of()));
    cache.put(cc, new Cached(read, now.plus(TTL)));
    return read;
  }

  /**
   * Reads a {@code GET /admin/tenant/obligations} response; empty when any obligation in it cannot
   * be read, because a list with one duty silently missing is worse than no list.
   */
  static Optional<Sheet> parse(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      if (!data.containsKey("obligations") || data.isNull("obligations")) return Optional.empty();
      List<CashLimit> limits = new ArrayList<>();
      if (data.containsKey("cashLimits") && !data.isNull("cashLimits")) {
        for (JsonValue value : data.getJsonArray("cashLimits")) {
          JsonObject l = value.asJsonObject();
          limits.add(
              new CashLimit(
                  l.getString("scope", null),
                  l.getString("currency"),
                  l.getJsonNumber("fromAmount").bigDecimalValue(),
                  LocalDate.parse(l.getString("effectiveFrom")),
                  l.containsKey("effectiveTo") && !l.isNull("effectiveTo")
                      ? LocalDate.parse(l.getString("effectiveTo"))
                      : null,
                  l.getString("citation", null)));
        }
      }
      List<DepositScheme> schemes = new ArrayList<>();
      if (data.containsKey("depositSchemes") && !data.isNull("depositSchemes")) {
        for (JsonValue value : data.getJsonArray("depositSchemes")) {
          JsonObject d = value.asJsonObject();
          List<String> materials = new ArrayList<>();
          if (d.containsKey("materials") && !d.isNull("materials")) {
            for (JsonValue m : d.getJsonArray("materials")) {
              materials.add(((jakarta.json.JsonString) m).getString());
            }
          }
          schemes.add(
              new DepositScheme(
                  d.getString("scope", null),
                  d.getString("currency"),
                  d.getJsonNumber("depositEach").bigDecimalValue(),
                  List.copyOf(materials),
                  d.getInt("minVolumeMl", 0),
                  d.getInt("maxVolumeMl", Integer.MAX_VALUE),
                  d.getString("vatTreatment", DepositScheme.OUTSIDE_SCOPE),
                  LocalDate.parse(d.getString("effectiveFrom")),
                  d.containsKey("effectiveTo") && !d.isNull("effectiveTo")
                      ? LocalDate.parse(d.getString("effectiveTo"))
                      : null,
                  d.getString("citation", null)));
        }
      }
      List<Obligation> out = new ArrayList<>();
      for (JsonValue value : data.getJsonArray("obligations")) {
        JsonObject o = value.asJsonObject();
        LocalDate to =
            o.containsKey("effectiveTo") && !o.isNull("effectiveTo")
                ? LocalDate.parse(o.getString("effectiveTo"))
                : null;
        out.add(
            new Obligation(
                o.getString("code"),
                o.getString("scope", ""),
                LocalDate.parse(o.getString("effectiveFrom")),
                to,
                o.containsKey("limitValue") && !o.isNull("limitValue")
                    ? o.getJsonNumber("limitValue").bigDecimalValue()
                    : null,
                o.containsKey("limitUnit") && !o.isNull("limitUnit")
                    ? o.getString("limitUnit")
                    : null,
                o.containsKey("qualifier") && !o.isNull("qualifier")
                    ? o.getString("qualifier")
                    : null));
      }
      return Optional.of(new Sheet(List.copyOf(out), List.copyOf(limits), List.copyOf(schemes)));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "unreadable legal obligations: {0}", e.getMessage());
      return Optional.empty();
    }
  }
}
