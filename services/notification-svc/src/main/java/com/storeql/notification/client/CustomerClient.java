package com.storeql.notification.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.notification.config.ServiceConfig;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
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
 * Resolves a customer's email from customer-svc so notification-svc can address an order
 * confirmation (golden rule #1/#4). Best-effort: any failure returns empty and the notification is
 * simply skipped — a missing confirmation email must never block or crash the consumer. {@code GET
 * /customers/{id}} is a read, so customer-svc's mutating-only {@code AdminAuthorizationFilter}
 * doesn't require a role — only the tenant header.
 */
@ApplicationScoped
public class CustomerClient {

  private static final Logger LOG = System.getLogger(CustomerClient.class.getName());
  private static final String CUSTOMER_SERVICE = "customer-svc";

  /** See the header comment in {@link #emailOf}. */
  private static final String INTERNAL_ROLE = "CASHIER";

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
   * {@code @Retry}/{@code @CircuitBreaker}: standard pattern (2 retries, breaker trips after 60%
   * failures in a 5-call window). This runs inside the single-threaded OrderConfirmed Kafka
   * consumer loop — without a breaker, a slow/down customer-svc would cost every queued
   * notification the full connect+read timeout and back up the whole consumer, not just this one
   * lookup. {@code @Fallback} keeps the existing "never blocks the notification" contract.
   */
  /**
   * The login behind a customer record (SJ-D44), for a push to the devices that login registered
   * here (13.7). Empty for a till-only customer, and empty when customer-svc cannot be reached — a
   * push that cannot find its device is skipped, never retried into a storm.
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "loginUnavailable")
  public Optional<UUID> loginIdOf(UUID tenantId, UUID customerId) {
    return read(tenantId, customerId, "loginId").map(UUID::fromString);
  }

  @SuppressWarnings({"PMD.UnusedFormalParameter", "PMD.UnusedPrivateMethod"})
  private Optional<UUID> loginUnavailable(UUID tenantId, UUID customerId) {
    LOG.log(Level.WARNING, "customer-svc unreachable — no login for customer {0}", customerId);
    return Optional.empty();
  }

  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "emailUnavailable")
  public Optional<String> emailOf(UUID tenantId, UUID customerId) {
    return read(tenantId, customerId, "email");
  }

  /**
   * The phone number on a customer record, for a recall notice by text when there is no email
   * (05.10): its international form ({@code phoneE164}, read in the business's own countries), so a
   * number typed {@code 07400 123456} is texted at {@code +447400123456}; the number as typed only
   * when the record has no international form, which a text then refuses unless it already is one.
   * Empty when the record has none or customer-svc cannot be reached.
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "phoneUnavailable")
  public Optional<String> phoneOf(UUID tenantId, UUID customerId) {
    return read(tenantId, customerId, "phoneE164", "phone");
  }

  @SuppressWarnings({"PMD.UnusedFormalParameter", "PMD.UnusedPrivateMethod"})
  private Optional<String> phoneUnavailable(UUID tenantId, UUID customerId) {
    LOG.log(Level.WARNING, "customer-svc unreachable — no phone for customer {0}", customerId);
    return Optional.empty();
  }

  /**
   * The language the customer reads their messages in (13.x), ISO 639: empty when they have not
   * said, or customer-svc cannot be reached — the business's own language is used, and the message
   * still goes.
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "languageUnavailable")
  public Optional<String> languageOf(UUID tenantId, UUID customerId) {
    return read(tenantId, customerId, "preferredLanguage");
  }

  @SuppressWarnings({"PMD.UnusedFormalParameter", "PMD.UnusedPrivateMethod"})
  private Optional<String> languageUnavailable(UUID tenantId, UUID customerId) {
    LOG.log(Level.DEBUG, "customer-svc unreachable — no language for customer {0}", customerId);
    return Optional.empty();
  }

  /** One string field of the customer record, empty when absent, null or blank. */
  /** The first of {@code fields} the customer record holds, from one read of it. */
  private Optional<String> read(UUID tenantId, UUID customerId, String... fields) {
    ServiceInstance instance = registry.resolve(CUSTOMER_SERVICE).orElse(null);
    if (instance == null) {
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/customers/" + customerId)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            // Trusted service-to-service call behind the gateway. Customer records are now
            // staff-gated, and looking one up to email them about their own order is a staff-level
            // read; without this the lookup fails and every email silently falls back to "no
            // address", which the @Fallback would make indistinguishable from a missing email.
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      if (res.status().code() != 200) {
        return Optional.empty();
      }
      try (JsonReader reader = Json.createReader(new StringReader(res.as(String.class)))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        return data == null ? Optional.empty() : firstOf(data, fields);
      }
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above; tenantId
  // must stay in the signature to match emailOf(...)'s parameter types even though it's unused.
  @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"})
  private Optional<String> emailUnavailable(UUID tenantId, UUID customerId) {
    LOG.log(
        Level.WARNING,
        "customer email lookup skipped for {0}: unreachable or circuit open",
        customerId);
    return Optional.empty();
  }

  /**
   * The first of {@code fields} set on the record. containsKey first: JSON-B omits a null field
   * from the DTO rather than serialising it as null, and isNull throws on an absent key. A customer
   * with no email address is ordinary (POS walk-ins are created from a phone number).
   */
  static Optional<String> firstOf(JsonObject data, String... fields) {
    for (String field : fields) {
      if (data.containsKey(field) && !data.isNull(field)) {
        String value = data.getString(field, null);
        if (value != null && !value.isBlank()) {
          return Optional.of(value);
        }
      }
    }
    return Optional.empty();
  }
}
