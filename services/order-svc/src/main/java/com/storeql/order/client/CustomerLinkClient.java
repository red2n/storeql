package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.config.ServiceConfig;
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
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Sync client for customer-svc's {@code POST /customers/me}: the customer record a shop holds for
 * the signed-in shopper, created there on first use (golden rule #1 — foreign data comes from the
 * owning service; rule #4 — the instance comes from Consul).
 *
 * <p>This is the join SJ-D44 was missing. A login is global and a customer record is per-tenant, so
 * an online order used to carry a login id in a column every other service read as a customer id —
 * and loyalty, the confirmation email and erasure all silently skipped online orders.
 *
 * <p><strong>Fail-open, unlike {@link PricingClient}.</strong> A sale must not be lost because the
 * link could not be made: the order still records {@code login_id}, which is enough to authorise
 * the shopper's own reads and enough for an erasure to find it. The link is repaired the next time
 * that shopper buys, or when they open their account page.
 */
@ApplicationScoped
public class CustomerLinkClient {

  private static final System.Logger LOG = System.getLogger(CustomerLinkClient.class.getName());

  private static final String CUSTOMER_SERVICE = "customer-svc";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

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
   * Resolves the shopper's customer record in this tenant, creating it if the shop holds none.
   *
   * <p>The shopper's own identity is forwarded rather than order-svc's: customer-svc reads the
   * login and email from the identity headers and nothing else, so this call can reach no record
   * but the caller's. {@code CUSTOMER} is the role the shopper actually holds — the endpoint is on
   * the open-mutation allowlist precisely because it cannot name a subject.
   *
   * <p>{@code @Retry}/{@code @CircuitBreaker}: the standard pattern (2 retries, breaker trips after
   * 60% failures in a 5-call window), on the checkout path, so a slow customer-svc costs the sale
   * one timeout rather than every sale.
   *
   * @param tenantId the tenant the order is being placed in
   * @param loginId the authenticated shopper's login id
   * @param email that login's own email, as the gateway read it from the verified JWT
   * @return the customer id, or empty when customer-svc could not be reached or the token carried
   *     no email for it to identify the shopper by
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "linkUnavailable")
  public Optional<UUID> customerIdFor(UUID tenantId, UUID loginId, String email) {
    if (email == null || email.isBlank()) {
      return Optional.empty();
    }
    ServiceInstance instance = registry.resolve(CUSTOMER_SERVICE).orElse(null);
    if (instance == null) {
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/customers/me")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.USER_ID), loginId.toString())
            .header(HeaderNames.create(HttpHeaders.USER_EMAIL), email)
            .header(HeaderNames.create(HttpHeaders.ROLES), "CUSTOMER")
            // The resource consumes JSON; a body with no type is a 415 before it is anything else,
            // which is exactly how the live check found this — the tests never reach the wire.
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit("{}")) {
      if (res.status().code() != 200) {
        LOG.log(
            System.Logger.Level.WARNING,
            "customer link for login {0} refused with {1}",
            loginId,
            res.status().code());
        return Optional.empty();
      }
      try (JsonReader reader = Json.createReader(new StringReader(res.as(String.class)))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        if (data == null || !data.containsKey("id") || data.isNull("id")) {
          return Optional.empty();
        }
        return Optional.of(Ids.parse(data.getString("id")));
      }
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above; the parameters
  // must stay in the signature to match customerIdFor(...) even though only loginId is logged.
  @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"})
  private Optional<UUID> linkUnavailable(UUID tenantId, UUID loginId, String email) {
    LOG.log(
        System.Logger.Level.WARNING,
        "customer link skipped for login {0}: customer-svc unreachable or circuit open —"
            + " the order keeps its login id and the link is made on the next one",
        loginId);
    return Optional.empty();
  }
}
