package com.storeql.gateway.filters;

import com.storeql.discovery.ServiceRegistry;
import com.storeql.gateway.ControlPlane;
import com.storeql.ids.Ids;
import com.storeql.web.HttpHeaders;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Storefront suspension gate. A deactivated tenant's online shop must stop serving — but the
 * gateway is stateless about tenant data, so it asks tenant-svc ({@code GET /storefront/active})
 * and caches the answer for a short TTL to keep the hot path fast.
 *
 * <p><strong>Fail-open:</strong> a lookup error (tenant-svc down, timeout) returns {@code active}.
 * A transient tenant-svc blip must not take every storefront offline; the hard block that matters
 * (staff login) is enforced in iam-svc independently.
 */
@ApplicationScoped
public class TenantStatusGate {

  private static final Logger LOG = System.getLogger(TenantStatusGate.class.getName());
  private static final long TTL_MILLIS = 15_000;

  /** Hard cap, least recently used out — bounds memory under tenant-id churn/abuse. */
  static final int MAX_ENTRIES = 10_000;

  @Inject ServiceRegistry registry;
  @Inject @ControlPlane WebClient webClient;

  private final LookupCache<Boolean> cache =
      new LookupCache<>(MAX_ENTRIES, TTL_MILLIS, true, System::currentTimeMillis);

  /**
   * True if the tenant may transact. Cached for {@value #TTL_MILLIS}ms, one lookup however many ask
   * at once, the last answer kept while it is refreshed; fails open on error. An id that is not a
   * UUIDv7 names no tenant: it is refused without a lookup and without being remembered, so garbage
   * in a header costs neither tenant-svc a call nor this cache a slot.
   */
  public boolean isActive(String tenantId) {
    String id;
    try {
      id = Ids.parse(tenantId).toString();
    } catch (RuntimeException e) {
      return false;
    }
    return cache.get(id, this::lookup, v -> true);
  }

  int cacheSize() {
    return cache.size();
  }

  private Boolean lookup(String tenantId) {
    var instance = registry.resolve("tenant-svc");
    if (instance.isEmpty()) {
      return true; // can't resolve tenant-svc → fail open
    }
    try (var resp =
        webClient
            .get(instance.get().baseUri() + "/storefront/active")
            .header(io.helidon.http.HeaderNames.create(HttpHeaders.TENANT_ID), tenantId)
            .request()) {
      if (resp.status().code() == 404) {
        // Only logged branch that actually blocks — the fail-open branches below stay quiet on
        // purpose, matching every other traffic-control filter in the gateway (RateLimitFilter,
        // BruteForceFilter): expected per-request outcomes aren't worth log volume. This one is
        // the exception because it's the gate doing its job, not routine traffic.
        LOG.log(Level.INFO, "Storefront gate: tenant {0} not found — blocking", tenantId);
        return false; // tenant does not exist → reject, not fail-open
      }
      if (resp.status().code() != 200) {
        return true; // server error / unavailability → fail open (keep storefronts up)
      }
      String body = resp.as(String.class);
      // Inactive only on an explicit, successfully-read negative — otherwise fail open.
      boolean active = parseActive(body);
      if (!active) {
        LOG.log(Level.INFO, "Storefront gate: tenant {0} is suspended — blocking", tenantId);
      }
      return active;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "tenant-status lookup failed for " + tenantId + ": " + e.getMessage());
      return true;
    }
  }

  /** Inactive only on an explicit {@code data.active: false}; anything unreadable is active. */
  static boolean parseActive(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      JsonObject data = reader.readObject().getJsonObject("data");
      return data == null || data.getBoolean("active", true);
    } catch (RuntimeException e) {
      return true;
    }
  }
}
