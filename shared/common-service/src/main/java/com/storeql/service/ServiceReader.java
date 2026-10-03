package com.storeql.service;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Reads another service on a tenant's behalf: located through Consul (rule #4), or at {@code
 * storeql.clients.<service>.url} when that is set — for a deployment without discovery, and for
 * integration tests, which stand a stub there; the tenant and a staff role stamped on the call, for
 * the reason SJ-D13 established — a call carrying no identity is one routing mistake away from an
 * impersonation; tried again on a server error or a failed connection, and never on a 4xx.
 *
 * <p>common-service reads tenant-svc with it for {@link TenantProfiles}, {@link Jurisdictions} and
 * {@link Retention}; a service reads whatever it needs after the fact with it — order-svc the
 * buyer's VAT status, the customer's billing address and the items' names for an invoice (18.9).
 */
public final class ServiceReader {

  private static final Logger LOG = System.getLogger(ServiceReader.class.getName());

  /**
   * The reads under {@code /admin/tenant} sit in the staff-operable tier, so the lowest staff role
   * reaches them.
   */
  static final String TENANT_ROLE = "STOREKEEPER";

  static final int TENANT_ATTEMPTS = 3;

  /**
   * What a read came back with.
   *
   * @param status the HTTP status, or 0 when the service could not be located or reached
   */
  public record Reply(int status, String body) {

    public boolean ok() {
      return status == 200;
    }

    public boolean unreachable() {
      return status == 0 || status >= 500;
    }
  }

  private final WebClient web;
  private final ConsulClient consul;
  private final String service;
  private final String role;
  private final int attempts;
  private final Optional<String> configuredBase;
  private final ReadBreaker breaker =
      new ReadBreaker(
          (int) configLong("storeql.clients.breaker.failures", 5L),
          Duration.ofSeconds(configLong("storeql.clients.breaker.open-seconds", 5L)),
          System::nanoTime);

  private static long configLong(String key, long fallback) {
    return Cfg.getLong(key, fallback);
  }

  /**
   * @param service the service name, as Consul and the override property know it
   * @param role the staff role stamped on every read
   * @param attempts how many times a read is tried while the service is unreachable
   * @param configuredUrl the service's address when discovery is not to decide it
   */
  public ServiceReader(
      ServiceSettings settings,
      String service,
      String role,
      int attempts,
      Optional<String> configuredUrl) {
    web =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    consul =
        settings.consulEnabled()
            ? new ConsulClient(settings.consulHost(), settings.consulPort())
            : null;
    this.service = service;
    this.role = role;
    this.attempts = attempts;
    configuredBase = configuredUrl.filter(u -> !u.isBlank());
  }

  /** common-service's own reads of tenant-svc. */
  static ServiceReader tenantSvc(ServiceSettings settings, Optional<String> tenantSvcUrl) {
    return new ServiceReader(settings, "tenant-svc", TENANT_ROLE, TENANT_ATTEMPTS, tenantSvcUrl);
  }

  /**
   * A service's address as {@code storeql.clients.<service>.url} sets it; empty when it is not set,
   * and discovery decides.
   */
  public static Optional<String> configuredUrl(String service) {
    return ConfigProvider.getConfig()
        .getOptionalValue("storeql.clients." + service + ".url", String.class)
        .filter(u -> !u.isBlank());
  }

  /**
   * A GET under the tenant's identity, as its status and body.
   *
   * @param path the path, starting with a slash
   * @param query query parameters, sent as parameters rather than folded into the path
   */
  public Reply get(UUID tenantId, String path, Map<String, String> query) {
    String base = configuredBase.orElse(null);
    if (base == null && consul != null) {
      base = consul.resolve(service).map(ServiceInstance::baseUri).orElse(null);
    }
    if (base == null) {
      LOG.log(Level.WARNING, "{0} could not be located for {1} of {2}", service, path, tenantId);
      return new Reply(0, null);
    }
    if (breaker.isOpen()) {
      // The service has just failed several reads in a row: answer "unreachable" without waiting
      // out the retries again; one read is let through when the open period ends.
      return new Reply(0, null);
    }
    Reply last = new Reply(0, null);
    for (int attempt = 1; attempt <= attempts; attempt++) {
      if (attempt > 1 && !backoff(attempt)) break;
      HttpClientRequest request =
          web.get(base + path).header(HeaderNames.create(HttpHeaders.ROLES), role);
      if (tenantId != null) {
        request = request.header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString());
      }
      for (Map.Entry<String, String> param : query.entrySet()) {
        request = request.queryParam(param.getKey(), param.getValue());
      }
      try (HttpClientResponse res = request.request()) {
        last = new Reply(res.status().code(), res.as(String.class));
        if (!last.unreachable()) {
          breaker.success();
          // A refusal is not a blip. Callers here treat an unreadable answer as "no opinion" and
          // carry on — which is right for an outage and wrong for a 401/403, where the platform is
          // misconfigured and will go on being misconfigured silently. SJ-D65 hid behind exactly
          // that: an entitlement read answered 403 for a whole release and enforced nothing. A 404
          // stays quiet; it is how these reads say "this business has none".
          if (last.status() >= 400 && last.status() != 404) {
            LOG.log(
                Level.WARNING,
                "{0}{1} for {2} was refused: HTTP {3} — this read is not doing its job",
                service,
                path,
                tenantId,
                last.status());
          }
          return last;
        }
        LOG.log(Level.WARNING, "{0}{1} for {2}: HTTP {3}", service, path, tenantId, last.status());
      } catch (RuntimeException e) {
        LOG.log(
            Level.WARNING, "{0}{1} for {2} failed: {3}", service, path, tenantId, e.getMessage());
        last = new Reply(0, null);
      }
    }
    breaker.failure();
    return last;
  }

  /**
   * Jittered backoff before retry number {@code attempt}: 100 ms per attempt plus up to 100 ms of
   * jitter, so a service coming back is not hit by every caller in the same instant.
   *
   * @return false if the wait was interrupted (stop retrying)
   */
  private static boolean backoff(int attempt) {
    try {
      Thread.sleep(
          100L * (attempt - 1) + java.util.concurrent.ThreadLocalRandom.current().nextLong(100L));
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** The body of a 200; empty when the service cannot be located, refuses, or keeps failing. */
  public Optional<String> body(UUID tenantId, String path, Map<String, String> query) {
    Reply r = get(tenantId, path, query);
    return r.ok() ? Optional.of(r.body()) : Optional.empty();
  }
}
