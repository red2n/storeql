package com.storeql.payment.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.payment.config.Jsons;
import com.storeql.payment.config.ServiceConfig;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;

/**
 * Sync client for tenant-svc's public {@code GET /storefront/config?store=} — used to learn which
 * tenders the owner has enabled for a store (golden rule #1: store settings belong to tenant-svc).
 *
 * <p>Results are cached for a short TTL: tender capture is the POS hot path and the enabled-methods
 * set changes rarely. Lookup failures return {@code null} (unknown) so the caller can fail open —
 * blocking every sale in the shop because tenant-svc is briefly down would be worse than briefly
 * accepting a tender the owner disabled.
 */
@ApplicationScoped
public class TenantStoreClient {

  private static final Logger LOG = System.getLogger(TenantStoreClient.class.getName());
  private static final String TENANT_SERVICE = "tenant-svc";
  private static final long CACHE_TTL_MILLIS = 60_000;

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  /** The most (tenant, store) answers kept; the least recently used goes first. */
  @Inject
  @ConfigProperty(name = "storeql.payment.store-methods-cache.max-entries", defaultValue = "10000")
  int cacheMaxEntries = 10_000;

  /**
   * How long an expired answer may still be served when tenant-svc cannot be read; older than this
   * it is dropped, so a closed store or an erased business does not stay in memory.
   */
  @Inject
  @ConfigProperty(name = "storeql.payment.store-methods-cache.max-stale-hours", defaultValue = "6")
  int cacheMaxStaleHours = 6;

  private record CacheEntry(Set<String> methods, long fetchedAt) {}

  /** Access-ordered, so the eldest entry is the least recently used. Guarded by {@link #lock}. */
  private final Map<String, CacheEntry> cache = new LinkedHashMap<>(64, 0.75f, true);

  /** Held for map operations only, never across the HTTP call. */
  private final ReentrantLock lock = new ReentrantLock();

  /** How many answers are cached now. */
  int cacheSize() {
    lock.lock();
    try {
      return cache.size();
    } finally {
      lock.unlock();
    }
  }

  private CacheEntry cached(String key, long now) {
    lock.lock();
    try {
      CacheEntry e = cache.get(key);
      long maxStale = Duration.ofHours(Math.max(1, cacheMaxStaleHours)).toMillis();
      if (e != null && now - e.fetchedAt() > maxStale) {
        cache.remove(key);
        return null;
      }
      return e;
    } finally {
      lock.unlock();
    }
  }

  private void remember(String key, CacheEntry entry) {
    lock.lock();
    try {
      cache.put(key, entry);
      int max = Math.max(1, cacheMaxEntries);
      var eldest = cache.entrySet().iterator();
      while (cache.size() > max && eldest.hasNext()) {
        eldest.next();
        eldest.remove();
      }
    } finally {
      lock.unlock();
    }
  }

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
  }

  /**
   * The enabled payment-method codes for a store, or {@code Optional.empty()} when they can't be
   * determined right now (tenant-svc unreachable / unexpected response) — callers treat an empty
   * Optional as "don't enforce".
   */
  public Optional<Set<String>> enabledMethods(UUID tenantId, UUID storeId) {
    String key = tenantId + ":" + storeId;
    long now = System.currentTimeMillis();
    CacheEntry cached = cached(key, now);
    if (cached != null && now - cached.fetchedAt() < CACHE_TTL_MILLIS) {
      return Optional.of(cached.methods());
    }
    Optional<Set<String>> fetched = fetch(tenantId, storeId);
    if (fetched.isPresent()) {
      remember(key, new CacheEntry(fetched.get(), now));
      return fetched;
    }
    // Serve stale over nothing: an expired entry still reflects the owner's last-known intent.
    return cached != null ? Optional.of(cached.methods()) : Optional.empty();
  }

  /**
   * {@code @CircuitBreaker}: trips after 60% failures in a 5-call window so a down tenant-svc
   * doesn't cost every cache-miss (once per store per {@link #CACHE_TTL_MILLIS}) the full
   * connect+read timeout on this POS tender-capture hot path. No {@code @Retry} — {@link
   * #enabledMethods} already serves stale cached data on failure, so retrying here would only add
   * latency to card capture without changing the outcome. {@code @Fallback} preserves the existing
   * "return empty, caller fails open" contract once the method is allowed to throw.
   */
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "fetchUnavailable")
  Optional<Set<String>> fetch(UUID tenantId, UUID storeId) {
    // A configured URL first, discovery second, as every other client; and a host that cannot be
    // reached fails open here rather than failing the sale — the fallback below never sees a
    // self-invocation, so the catch is explicit.
    String base =
        com.storeql.service.ServiceReader.configuredUrl(TENANT_SERVICE)
            .or(() -> registry.resolve(TENANT_SERVICE).map(ServiceInstance::baseUri))
            .orElse(null);
    if (base == null) {
      LOG.log(Level.WARNING, "no healthy tenant-svc instance — skipping method enforcement");
      return Optional.empty();
    }
    try {
      return read(tenantId, storeId, base);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "tenant-svc unreachable ({0}) — skipping method enforcement",
          e.getMessage());
      return Optional.empty();
    }
  }

  private Optional<Set<String>> read(UUID tenantId, UUID storeId, String base) {
    try (HttpClientResponse res =
        webClient
            .get(base + "/storefront/config")
            .queryParam("store", storeId.toString())
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .request()) {
      if (res.status().code() != 200) {
        LOG.log(
            Level.WARNING,
            "tenant-svc store config returned HTTP {0} — skipping method enforcement",
            res.status().code());
        return Optional.empty();
      }
      String body = res.as(String.class);
      try (JsonReader reader = Jsons.PROVIDER.createReader(new StringReader(body))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        var arr = data.getJsonArray("enabledPaymentMethods");
        if (arr == null) return Optional.empty();
        List<String> methods = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
          methods.add(arr.getString(i));
        }
        return Optional.of(Set.copyOf(methods));
      }
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above; tenantId
  // must stay in the signature to match fetch(...)'s parameter types even though it's unused.
  @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"})
  private Optional<Set<String>> fetchUnavailable(UUID tenantId, UUID storeId) {
    LOG.log(
        Level.WARNING,
        "tenant-svc store config lookup skipped for store {0}: unreachable or circuit open",
        storeId);
    return Optional.empty();
  }
}
