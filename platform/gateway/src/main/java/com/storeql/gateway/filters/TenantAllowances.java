package com.storeql.gateway.filters;

import com.storeql.discovery.ServiceRegistry;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a business's plan allows it a minute (21.11), as tenant-svc states it, cached here for a
 * minute per business.
 *
 * <p>The gateway is the one door every request comes through, so it is the service that owns
 * "requests" and the one that refuses when a plan's rate is exceeded — the same rule that puts the
 * store limit in tenant-svc and the product limit in product-svc. It reads the allowance the way a
 * service does, under a staff identity for the business, from the route every service reads ({@code
 * /admin/tenant/plan/limits}); a business on no plan, a plan with no rate, and a tenant-svc that
 * cannot be reached all mean unlimited, because a limit fails open and a door that closed on a
 * lookup error would close every shop at once.
 *
 * <p>Bounded like {@link TenantStatusGate}: the cache never grows past {@link #MAX_ENTRIES}, so a
 * flood of invented tenant ids cannot use it to exhaust the heap.
 */
@ApplicationScoped
public class TenantAllowances {

  private static final Logger LOG = System.getLogger(TenantAllowances.class.getName());
  static final long TTL_MILLIS = 60_000;
  static final int MAX_ENTRIES = 10_000;
  static final String KEY = "requests.per-minute";
  private static final String PATH = "/admin/tenant/plan/limits";

  /** The identity the read is made under: any staff role may read what the plan allows. */
  private static final String READER_ROLE = "STOREKEEPER";

  @Inject ServiceRegistry registry;
  @Inject WebClient webClient;

  private record Cached(OptionalLong perMinute, long expiresAt) {}

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  /** The plan's requests a minute for a business, or empty when nothing limits it. */
  public OptionalLong requestsPerMinute(String tenantId) {
    long now = System.currentTimeMillis();
    Cached c = cache.get(tenantId);
    if (c != null && c.expiresAt() > now) {
      return c.perMinute();
    }
    OptionalLong read = lookup(tenantId);
    if (cache.size() >= MAX_ENTRIES && !cache.containsKey(tenantId)) {
      cache.values().removeIf(v -> v.expiresAt() <= now);
      if (cache.size() >= MAX_ENTRIES) {
        var it = cache.keySet().iterator();
        if (it.hasNext()) {
          it.next();
          it.remove();
        }
      }
    }
    cache.put(tenantId, new Cached(read, now + TTL_MILLIS));
    return read;
  }

  private OptionalLong lookup(String tenantId) {
    var instance = registry.resolve("tenant-svc");
    if (instance.isEmpty()) {
      return OptionalLong.empty(); // cannot resolve tenant-svc → unlimited
    }
    try (var resp =
        webClient
            .get(instance.get().baseUri() + PATH)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId)
            .header(HeaderNames.create(HttpHeaders.ROLES), READER_ROLE)
            .request()) {
      if (resp.status().code() != 200) {
        return OptionalLong.empty();
      }
      return parse(resp.as(String.class));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "plan-limits lookup failed for " + tenantId + ": " + e.getMessage());
      return OptionalLong.empty();
    }
  }

  /** The rate in a {@code /admin/tenant/plan/limits} answer, or empty when the plan names none. */
  static OptionalLong parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return OptionalLong.empty();
      JsonObject data = root.getJsonObject("data");
      if (!data.containsKey("grants") || data.isNull("grants")) return OptionalLong.empty();
      for (JsonValue value : data.getJsonArray("grants")) {
        JsonObject grant = value.asJsonObject();
        if (KEY.equals(grant.getString("key", null))
            && grant.containsKey("limitValue")
            && !grant.isNull("limitValue")) {
          return OptionalLong.of(grant.getJsonNumber("limitValue").longValue());
        }
      }
      return OptionalLong.empty();
    } catch (RuntimeException e) {
      return OptionalLong.empty();
    }
  }
}
