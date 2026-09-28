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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What a business is allowed, by the plan it is on (21.8).
 *
 * <p>The price list lives in tenant-svc, but a limit is only real where it is enforced, and that is
 * the service that owns the thing being counted: iam-svc knows how many staff there are,
 * product-svc how many products. Each reads its allowances here rather than the other service's
 * tables, and counts its own rows.
 *
 * <p><b>Fail open, deliberately.</b> A business on no plan is unrestricted — which is every
 * business that predates plans, and every business while the platform names no default — and so is
 * one whose allowances cannot be read this moment. The alternative is that a blip in tenant-svc
 * stops a shop taking on staff or listing a product, which is a worse failure than briefly letting
 * somebody past a ceiling they are about to be told about. A limit is a commercial boundary, not a
 * safety one; it does not deserve to fail closed the way spend authority does.
 */
@ApplicationScoped
public class Entitlements {

  /** Short: a business that has just been moved to a bigger plan should feel it quickly. */
  static final Duration TTL = Duration.ofMinutes(1);

  private static final String PATH = "/admin/tenant/plan/limits";

  /**
   * What one business is allowed.
   *
   * @param limits how many, by key; a key absent means no limit
   * @param features whether at all, by key; a key absent means not withheld
   */
  public record Allowance(Map<String, Long> limits, Map<String, Boolean> features) {

    public Allowance {
      limits = Map.copyOf(limits);
      features = Map.copyOf(features);
    }

    /** Nothing is withheld: no plan, or an answer that could not be read. */
    static Allowance unrestricted() {
      return new Allowance(Map.of(), Map.of());
    }
  }

  private record Cached(Allowance allowance, Instant expiresAt) {}

  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "storeql.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private Function<UUID, Optional<String>> fetch;

  @PostConstruct
  void init() {
    ServiceReader client = ServiceReader.tenantSvc(settings, tenantSvcUrl);
    fetch = tenantId -> client.body(tenantId, PATH, Map.of());
  }

  /** For tests: an answer standing in for tenant-svc, and a clock to age the cache with. */
  static Entitlements forTest(Function<UUID, Optional<String>> fetch, Clock clock) {
    Entitlements e = new Entitlements();
    e.fetch = fetch;
    e.clock = clock;
    return e;
  }

  /**
   * How many of something the business may have.
   *
   * @return empty when there is no ceiling — unlimited, not named by the plan, no plan at all, or
   *     the allowances could not be read
   */
  public OptionalLong limit(UUID tenantId, String key) {
    Long value = allowance(tenantId).limits().get(key);
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /**
   * Whether the business may use a feature at all.
   *
   * @return true unless its plan says otherwise
   */
  public boolean allows(UUID tenantId, String featureKey) {
    return !Boolean.FALSE.equals(allowance(tenantId).features().get(featureKey));
  }

  /**
   * Refuses one more of something when the plan does not stretch to it, naming the figures so the
   * answer is actionable rather than a door closed.
   *
   * @param what what is being counted, in the plural, as a person would say it
   * @param used how many there are now; asked for only when there is a ceiling, so a service pays
   *     for the count only when it matters
   * @throws ApiException 409 {@code PLAN_LIMIT_REACHED}
   */
  public void requireRoom(UUID tenantId, String key, String what, LongSupplier used) {
    OptionalLong ceiling = limit(tenantId, key);
    if (ceiling.isEmpty()) return;
    long limit = ceiling.getAsLong();
    long have = used.getAsLong();
    if (have < limit) return;
    throw limitReached(limit, what, have);
  }

  /**
   * The refusal a plan limit gives, wherever it is given: tenant-svc answers for stores and staff
   * from its own tables and never comes through here, so the sentence lives in one place or the
   * platform says the same thing two ways.
   *
   * @param what what is counted, in the plural, as a person would say it
   */
  /**
   * Refuses when what a business would hold in bytes exceeds a plan's cap in megabytes (21.11).
   *
   * <p>A cap on bytes is "would this fit", not "how many are there": the check is on the total the
   * write would leave behind, so a file that fits exactly is taken and the one that does not is
   * refused before a byte of it is stored. The cap is in MB because that is how a plan is sold; a
   * MB is 1,048,576 bytes here, which is what the disk it lands on counts in.
   *
   * @param key a limit key whose value is megabytes, e.g. {@link #IMAGES_MB_MAX}
   * @param what what is capped, for the refusal: "MB of product images"
   * @param bytesAfter what the business would hold once this write is in, in bytes
   * @throws ApiException 409 {@code PLAN_LIMIT_REACHED} when it would not fit
   */
  public void requireBytesWithin(UUID tenantId, String key, String what, LongSupplier bytesAfter) {
    OptionalLong ceiling = limit(tenantId, key);
    if (ceiling.isEmpty()) return;
    long limitMb = ceiling.getAsLong();
    long after = bytesAfter.getAsLong();
    if (after <= limitMb * MB) return;
    throw ApiException.conflict(
        "PLAN_LIMIT_REACHED",
        "This plan allows "
            + limitMb
            + " "
            + what
            + " and this would make "
            + megabytes(after)
            + "; a larger plan is needed, or room made first");
  }

  static final long MB = 1024L * 1024L;

  /** Bytes as a person reads them: {@code 5.3 MB}, one decimal, never "5.0000". */
  static String megabytes(long bytes) {
    return new java.math.BigDecimal(bytes)
            .divide(new java.math.BigDecimal(MB), 1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        + " MB";
  }

  public static ApiException limitReached(long limit, String what, long have) {
    return ApiException.conflict(
        "PLAN_LIMIT_REACHED",
        "This plan allows "
            + limit
            + " "
            + what
            + " and the business has "
            + have
            + "; a larger plan is needed to add another");
  }

  private Allowance allowance(UUID tenantId) {
    if (tenantId == null) return Allowance.unrestricted();
    Instant now = clock.instant();
    Cached hit = cache.get(tenantId);
    if (hit != null && hit.expiresAt().isAfter(now)) return hit.allowance();
    Allowance read = fetch.apply(tenantId).flatMap(Entitlements::parse).orElse(null);
    if (read == null) {
      // Not cached: a failed read must not hold a business unrestricted for a whole TTL, nor keep
      // it restricted; the next call asks again.
      return Allowance.unrestricted();
    }
    cache.put(tenantId, new Cached(read, now.plus(TTL)));
    return read;
  }

  static Optional<Allowance> parse(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      if (!data.containsKey("grants") || data.isNull("grants")) return Optional.empty();
      Map<String, Long> limits = new HashMap<>();
      Map<String, Boolean> features = new HashMap<>();
      for (JsonValue value : data.getJsonArray("grants")) {
        JsonObject grant = value.asJsonObject();
        String key = grant.getString("key", null);
        if (key == null || key.isBlank()) continue;
        if (grant.containsKey("limitValue") && !grant.isNull("limitValue")) {
          limits.put(key, grant.getJsonNumber("limitValue").longValue());
        }
        if (grant.containsKey("enabled") && !grant.isNull("enabled")) {
          features.put(key, grant.getBoolean("enabled"));
        }
      }
      return Optional.of(new Allowance(limits, features));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The keys this platform enforces, so a caller names one rather than inventing it. */
  public static final String STORES_MAX = "stores.max";

  public static final String STAFF_MAX = "staff.max";
  public static final String PRODUCTS_MAX = "products.max";
  public static final String FEATURE_STOREFRONT = "feature.storefront";

  /** API requests a minute, across every login and the online shop; the gateway enforces it. */
  public static final String REQUESTS_PER_MINUTE = "requests.per-minute";

  /** Megabytes of product images; product-svc enforces it. */
  public static final String IMAGES_MB_MAX = "images.mb.max";

  /** Megabytes of supplier e-invoice documents kept; purchase-svc enforces it. */
  public static final String DOCUMENTS_MB_MAX = "documents.mb.max";

  /** Every key, for a service that wants to check its own against the platform's. */
  public static List<String> keys() {
    return List.of(
        STORES_MAX,
        STAFF_MAX,
        PRODUCTS_MAX,
        FEATURE_STOREFRONT,
        REQUESTS_PER_MINUTE,
        IMAGES_MB_MAX,
        DOCUMENTS_MB_MAX);
  }
}
