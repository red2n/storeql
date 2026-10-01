package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.config.ServiceConfig;
import com.storeql.service.ServiceReader;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Sync client for iam-svc's staff directory, {@code GET /auth/admin/staff-users}: whether a login
 * is a member of a business's staff allowed at one of its stores. Golden rule #1 — logins and their
 * roles live there; rule #4 — a configured address first, else the instance from Consul.
 *
 * <p>Asked when a till sale replayed from the offline queue leaves entries on the audit trail and
 * the till says somebody other than the sender rang it up: an entry names that person only when the
 * business holds them at the store. The directory answers for the ids it is given, scoped to the
 * stores the call names — a login holding a role at that store, or a business-wide staff role in
 * this business — and leaves out anyone else, another business's staff and shoppers included. Asked
 * as a manager with the order's tenant and store, since naming staff is a management read.
 *
 * <p><strong>Never names on doubt.</strong> An answer that cannot be had or read is no answer, and
 * the entry then reads as rung up by an unknown member of staff: a name on the trail is evidence,
 * and one the business cannot vouch for is worse than none. The sale itself never waits on this.
 */
@ApplicationScoped
public class StaffClient {

  private static final Logger LOG = System.getLogger(StaffClient.class.getName());
  private static final String IAM_SERVICE = "iam-svc";
  private static final String INTERNAL_ROLE = "MANAGER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    // With discovery off and no address configured there is nobody to ask, and asking a Consul
    // that is not there would cost every flagged replay a failed connection.
    registry =
        config.consulEnabled() ? new ConsulClient(config.consulHost(), config.consulPort()) : null;
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(1))
            .readTimeout(Duration.ofSeconds(2))
            .build();
  }

  /**
   * Whether {@code userId} is a login of the business allowed at {@code storeId}.
   *
   * @param tenantId the business, from the caller's token
   * @param storeId the store the sale was made at
   * @param userId the login the till says rang the sale up
   * @return true or false as the directory answers; empty when iam-svc could not be located,
   *     reached or read
   */
  @Retry(maxRetries = 1, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "unavailable")
  public Optional<Boolean> isStaffAt(UUID tenantId, UUID storeId, UUID userId) {
    String base = locate().orElse(null);
    if (base == null) {
      LOG.log(Level.WARNING, "iam-svc could not be located; staff not read for {0}", tenantId);
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(base + "/auth/admin/staff-users")
            .queryParam("ids", userId.toString())
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .header(HeaderNames.create(HttpHeaders.STORE_IDS), storeId.toString())
            .request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(Level.WARNING, "staff directory for {0}: HTTP {1}", tenantId, res.status().code());
        return Optional.empty();
      }
      return named(userId, body);
    }
  }

  /** A configured address first, else the instance from Consul when discovery is on. */
  private Optional<String> locate() {
    Optional<String> configured = ServiceReader.configuredUrl(IAM_SERVICE);
    if (configured.isPresent() || registry == null) return configured;
    return registry.resolve(IAM_SERVICE).map(ServiceInstance::baseUri);
  }

  /**
   * Whether the directory's answer names {@code userId}: {@code {"data":[{"userId","email"}]}},
   * holding only the business's own staff at the stores asked about.
   *
   * @return empty when the answer cannot be read
   */
  static Optional<Boolean> named(UUID userId, String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      for (JsonValue v : root.getJsonArray("data")) {
        String named = v.asJsonObject().getString("userId", null);
        if (named != null && userId.equals(Ids.parse(named))) return Optional.of(true);
      }
      return Optional.of(false);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "unreadable staff directory answer: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<Boolean> unavailable(UUID tenantId, UUID storeId, UUID userId) {
    LOG.log(Level.WARNING, "iam-svc unavailable; staff not read for {0}", tenantId);
    return Optional.empty();
  }
}
