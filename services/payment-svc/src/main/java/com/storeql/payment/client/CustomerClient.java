package com.storeql.payment.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.payment.config.Jsons;
import com.storeql.payment.config.ServiceConfig;
import com.storeql.web.ApiException;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for customer-svc, used to redeem a customer's store-credit balance as tender toward
 * an order (golden rule #1: the balance lives in customer-svc, never in payment-svc; rule #4:
 * resolved via Consul). The redeem is idempotent per order in customer-svc, so retrying a capture
 * cannot double-deduct.
 *
 * <p>This is a trusted service-to-service call behind the gateway, so it stamps an internal staff
 * role ({@code CASHIER}) to satisfy customer-svc's {@code AdminAuthorizationFilter} — the
 * redemption is a POS cashier action.
 */
@ApplicationScoped
public class CustomerClient {

  private static final String CUSTOMER_SERVICE = "customer-svc";
  private static final String INTERNAL_ROLE = "CASHIER";

  /** customer-svc's code for an erased customer, passed on unchanged. */
  static final String CUSTOMER_ANONYMIZED = "CUSTOMER_ANONYMIZED";

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
   * Redeem {@code amount} of the customer's store credit for {@code orderId}. Idempotent per order
   * (a retry re-hits the same REDEEM and is a no-op). {@code @Retry} is safe here precisely because
   * the redeem is idempotent.
   *
   * <p>customer-svc's deliberate answers are {@link Refusal}s: permanent for the request as made,
   * so never retried ({@code abortOn}) and never counted against the circuit breaker ({@code
   * skipOn}), which exists for a customer-svc that cannot answer, not for one that says no. Only an
   * unreachable or misbehaving customer-svc is a {@code 503}.
   *
   * @throws Refusal {@code 422 PAYMENT_STORE_CREDIT_INSUFFICIENT} when the balance is too small;
   *     {@code 404 PAYMENT_CUSTOMER_NOT_FOUND} when the customer is unknown; {@code 409
   *     CUSTOMER_ANONYMIZED} when the customer has been erased (customer-svc's code, passed on: an
   *     erased customer spends nothing new, whoever asks, and asking again will not change it)
   * @throws ApiException {@code 503 PAYMENT_CUSTOMER_UNAVAILABLE} when customer-svc is unreachable,
   *     its breaker is open, or it answers anything else
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(
      requestVolumeThreshold = 5,
      failureRatio = 0.6,
      delay = 5000,
      // A refusal is an answer, not a failure: five erased customers in a row must not open the
      // breaker and turn the next shopper's good store credit into a 503.
      skipOn = {Refusal.class})
  public void redeemStoreCredit(
      UUID tenantId, UUID customerId, BigDecimal amount, String currency, UUID orderId) {
    String base =
        com.storeql.service.ServiceReader.configuredUrl(CUSTOMER_SERVICE)
            .or(() -> registry.resolve(CUSTOMER_SERVICE).map(ServiceInstance::baseUri))
            .orElseThrow(() -> unavailable("no healthy customer-svc instance in discovery", null));

    String payload =
        Jsons.PROVIDER
            .createObjectBuilder()
            .add("amount", amount)
            .add("currency", currency)
            .add("orderId", orderId.toString())
            .add("reason", "Store credit tender for order " + orderId)
            .build()
            .toString();

    try (HttpClientResponse res =
        webClient
            .post(base + "/customers/" + customerId + "/store-credit/redeem")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload)) {
      int status = res.status().code();
      if (status == 200) {
        return;
      }
      if (status == 422) {
        throw new Refusal(
            422,
            "PAYMENT_STORE_CREDIT_INSUFFICIENT",
            "insufficient store credit for this customer");
      }
      if (status == 404) {
        throw new Refusal(
            404, "PAYMENT_CUSTOMER_NOT_FOUND", "customer " + customerId + " not found");
      }
      if (status == 409) {
        // customer-svc's only 409 on this call: the customer has been erased, so nobody can show
        // the balance is theirs. One state keeps one code (privacy-requests Decisions), so it is
        // passed on as it was given, never as an outage the till would invite a retry of.
        throw new Refusal(
            409,
            CUSTOMER_ANONYMIZED,
            "the customer has been erased; their store credit can no longer be spent");
      }
      throw unavailable("customer-svc returned HTTP " + status, null);
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("customer-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("customer-svc unreachable", e);
    }
  }

  /**
   * customer-svc's deliberate "no" to a redeem, carried as the stable code payment-svc answers
   * with. A subtype only so the circuit breaker can tell it from an outage; it maps to the HTTP
   * answer exactly as any {@link ApiException}.
   */
  public static final class Refusal extends ApiException {

    private static final long serialVersionUID = 1L;

    Refusal(int status, String code, String message) {
      super(status, code, message, List.of());
    }
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "PAYMENT_CUSTOMER_UNAVAILABLE", message, List.of(), cause);
  }
}
