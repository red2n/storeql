package com.storeql.discovery;

import io.helidon.webclient.api.WebClient;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reusable client for the Consul HTTP API: self-register, deregister, and look up healthy
 * instances.
 *
 * <p>This is the one place Consul registration/discovery logic lives (golden rule #4 — discover,
 * don't hardcode). Services use it to register themselves; the gateway uses it to resolve
 * upstreams. There is no Helidon-native Consul integration, so we call the Consul agent HTTP API
 * directly via Helidon WebClient.
 */
public class ConsulClient implements ServiceRegistry {

  private static final Logger LOG = System.getLogger(ConsulClient.class.getName());

  private final WebClient webClient;
  private final String consulBaseUri;

  /** Consul is on the same network: a lookup that takes longer than this is a failed lookup. */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);

  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(2);

  /** How long the last good list is still served when Consul cannot be asked. */
  public static final Duration DEFAULT_MAX_STALE = Duration.ofSeconds(60);

  public ConsulClient(String consulHost, int consulPort) {
    this(consulHost, consulPort, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  public ConsulClient(
      String consulHost, int consulPort, Duration connectTimeout, Duration readTimeout) {
    this(consulHost, consulPort, connectTimeout, readTimeout, DEFAULT_CACHE_TTL);
  }

  /** As above, with the lookup cache's life set: a test shortens it. */
  ConsulClient(
      String consulHost,
      int consulPort,
      Duration connectTimeout,
      Duration readTimeout,
      Duration cacheTtl) {
    this.cacheTtlNanos = cacheTtl.toNanos();
    this.consulBaseUri = "http://" + consulHost + ":" + consulPort;
    this.webClient =
        WebClient.builder()
            .baseUri(consulBaseUri)
            .connectTimeout(connectTimeout)
            .readTimeout(readTimeout)
            .build();
  }

  /**
   * Register this service instance with Consul, including an HTTP health check against its
   * readiness probe so Consul only advertises the instance while it is ready.
   *
   * @return the registered service id (use it to deregister)
   */
  public String register(String serviceName, String advertiseHost, int port) {
    String serviceId = serviceName + "-" + port;
    String body =
        """
                {
                  "ID": "%s",
                  "Name": "%s",
                  "Address": "%s",
                  "Port": %d,
                  "Check": {
                    "HTTP": "http://%s:%d/health/ready",
                    "Interval": "10s",
                    "DeregisterCriticalServiceAfter": "1m"
                  }
                }
                """
            .formatted(serviceId, serviceName, advertiseHost, port, advertiseHost, port);
    try {
      try (var response = webClient.put("/v1/agent/service/register").submit(body)) {
        if (response.status().family() != io.helidon.http.Status.Family.SUCCESSFUL) {
          LOG.log(
              Level.WARNING,
              "Consul registration of {0} answered {1} (running unregistered)",
              serviceId,
              response.status().code());
          return serviceId;
        }
      }
      LOG.log(
          Level.INFO, "Registered with Consul as {0} ({1}:{2})", serviceId, advertiseHost, port);
    } catch (Exception e) {
      // Non-fatal: the service still runs; it just isn't discoverable until Consul is reachable.
      LOG.log(
          Level.WARNING, "Consul registration failed (running unregistered): " + e.getMessage());
    }
    return serviceId;
  }

  /** Deregister a previously-registered service id (best effort). */
  public void deregister(String serviceId) {
    if (serviceId == null) {
      return;
    }
    try {
      try (var response = webClient.put("/v1/agent/service/deregister/" + serviceId).request()) {
        if (response.status().family() != io.helidon.http.Status.Family.SUCCESSFUL) {
          LOG.log(
              Level.WARNING,
              "Consul deregistration of {0} answered {1}",
              serviceId,
              response.status().code());
          return;
        }
      }
      LOG.log(Level.INFO, "Deregistered {0} from Consul", serviceId);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Consul deregistration failed: " + e.getMessage());
    }
  }

  /**
   * All currently-healthy ("passing") instances of a service.
   *
   * <p>One caller at a time asks Consul for a service; the others do not wait on it. When Consul is
   * slow or down, the last good list keeps being served (for {@link #DEFAULT_MAX_STALE}) rather
   * than being replaced by an empty one, so a Consul blip does not turn into 503s for every proxied
   * request. Only a service never seen before makes callers wait for the first answer.
   */
  public List<ServiceInstance> healthyInstances(String serviceName) {
    long now = System.nanoTime();
    CachedInstances cached = instanceCache.get(serviceName);
    if (cached != null && now < cached.expiresAtNanos) {
      return cached.instances;
    }
    CompletableFuture<List<ServiceInstance>> mine = new CompletableFuture<>();
    CompletableFuture<List<ServiceInstance>> theirs = inflight.putIfAbsent(serviceName, mine);
    if (theirs != null) {
      // Someone is already asking: use the last good list if there is one, else share the answer.
      if (cached != null && now < cached.staleUntilNanos) {
        return cached.instances;
      }
      return theirs.join();
    }
    try {
      // A caller that read the cache just before another finished may find the answer already in.
      CachedInstances again = instanceCache.get(serviceName);
      if (again != null && System.nanoTime() < again.expiresAtNanos) {
        mine.complete(again.instances);
        return again.instances;
      }
      Optional<List<ServiceInstance>> asked = fetchHealthyInstances(serviceName);
      List<ServiceInstance> result;
      if (asked.isPresent()) {
        List<ServiceInstance> fresh = asked.get();
        result = fresh;
        instanceCache.put(
            serviceName,
            new CachedInstances(
                fresh,
                System.nanoTime() + cacheTtlNanos,
                System.nanoTime() + cacheTtlNanos + maxStaleNanos));
      } else if (cached != null && System.nanoTime() < cached.staleUntilNanos) {
        // Consul could not be asked: keep the last good list, and ask again after the short TTL.
        result = cached.instances;
        instanceCache.put(
            serviceName,
            new CachedInstances(
                cached.instances, System.nanoTime() + cacheTtlNanos, cached.staleUntilNanos));
      } else {
        // Nothing good to fall back on: an empty answer, kept as short as a good one so a
        // down service does not become a Consul hammering loop.
        result = List.of();
        instanceCache.put(
            serviceName,
            new CachedInstances(
                result, System.nanoTime() + cacheTtlNanos, System.nanoTime() + cacheTtlNanos));
      }
      mine.complete(result);
      return result;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inflight.remove(serviceName, mine);
      if (!mine.isDone()) {
        // An Error: release whoever waits on this lookup.
        mine.completeExceptionally(new IllegalStateException("Consul lookup did not complete"));
      }
    }
  }

  /** The healthy instances Consul lists, or empty when Consul could not be asked. */
  private Optional<List<ServiceInstance>> fetchHealthyInstances(String serviceName) {
    List<ServiceInstance> out = new ArrayList<>();
    try {
      // NOTE: pass the filter via queryParam — embedding "?passing=true" in the path makes
      // Helidon's
      // WebClient URL-encode the '?' into the path, so Consul returns an empty list.
      String json;
      try (var response =
          webClient
              .get("/v1/health/service/" + serviceName)
              .queryParam("passing", "true")
              .request()) {
        if (response.status().code() != 200) {
          LOG.log(
              Level.WARNING,
              "Consul lookup for {0} answered {1}",
              serviceName,
              response.status().code());
          return Optional.empty();
        }
        json = response.as(String.class);
      }
      try (var reader = Json.createReader(new StringReader(json))) {
        JsonArray entries = reader.readArray();
        for (int i = 0; i < entries.size(); i++) {
          JsonObject svc = entries.getJsonObject(i).getJsonObject("Service");
          JsonObject node = entries.getJsonObject(i).getJsonObject("Node");
          String address = svc.getString("Address", "");
          if (address.isBlank() && node != null) {
            address = node.getString("Address", "");
          }
          int port = svc.getInt("Port", 0);
          if (!address.isBlank() && port > 0) {
            out.add(new ServiceInstance(serviceName, address, port));
          }
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Consul lookup for {0} failed: {1}", serviceName, e.getMessage());
      return Optional.empty();
    }
    return Optional.of(List.copyOf(out));
  }

  /** One healthy instance of a service, chosen at random (basic client-side load balancing). */
  @Override
  public Optional<ServiceInstance> resolve(String serviceName) {
    List<ServiceInstance> instances = healthyInstances(serviceName);
    if (instances.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(instances.get(ThreadLocalRandom.current().nextInt(instances.size())));
  }

  /**
   * Short-TTL lookup cache: resolution runs on EVERY proxied request, so without it each request
   * pays an extra HTTP roundtrip to Consul (latency + Consul load). 3s staleness is within Consul's
   * own 10s health-check interval, so it adds no meaningful failover delay. Empty results are
   * cached too — a down service must not turn into a Consul hammering loop.
   */
  private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(3);

  private final long cacheTtlNanos;

  private final long maxStaleNanos = DEFAULT_MAX_STALE.toNanos();

  private final ConcurrentMap<String, CachedInstances> instanceCache = new ConcurrentHashMap<>();

  private final ConcurrentMap<String, CompletableFuture<List<ServiceInstance>>> inflight =
      new ConcurrentHashMap<>();

  private record CachedInstances(
      List<ServiceInstance> instances, long expiresAtNanos, long staleUntilNanos) {}
}
