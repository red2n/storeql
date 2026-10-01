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
   * (a retry re-hits the same REDEEM and is a no-op). Throws {@code 422} when the balance is
   * insufficient, {@code 404} when the customer is unknown, {@code 503} when customer-svc is
   * unreachable. {@code @Retry} is safe here precisely because the redeem is idempotent.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
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
        throw new ApiException(
            422,
            "PAYMENT_STORE_CREDIT_INSUFFICIENT",
            "insufficient store credit for this customer",
            List.of());
      }
      if (status == 404) {
        throw ApiException.notFound(
            "PAYMENT_CUSTOMER_NOT_FOUND", "customer " + customerId + " not found");
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

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "PAYMENT_CUSTOMER_UNAVAILABLE", message, List.of(), cause);
  }
}
