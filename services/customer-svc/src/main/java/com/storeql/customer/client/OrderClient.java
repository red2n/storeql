package com.storeql.customer.client;

import com.storeql.customer.config.ServiceConfig;
import com.storeql.customer.json.Jsons;
import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.web.ApiException;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Sync client for order-svc's {@code GET /orders/export}: the sales half of a person's data export
 * (golden rule #1 — order-svc owns orders, so they are asked for, never joined to).
 *
 * <p><strong>Fail-closed.</strong> A data export that quietly omits a person's purchases is not an
 * incomplete answer, it is a wrong one — the regulation asks for the personal data concerning them,
 * and a shop that hands over half of it while saying "here is your data" has answered falsely. So
 * an unreachable order-svc produces a 503 and no file, and the request is made again.
 */
@ApplicationScoped
public class OrderClient {

  private static final String ORDER_SERVICE = "order-svc";

  /**
   * The export route is staff-gated, and this call is made on behalf of a shopper asking for their
   * own data or of staff handling their request. It names a role for the reason SJ-D13 established:
   * an internal call carrying no principal is one routing mistake from being an impersonation.
   */
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
            .readTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Reads every order one person placed at this shop, as raw JSON objects passed straight into the
   * export.
   *
   * @param tenantId owning tenant
   * @param customerId the shop's record of the person, or {@code null}
   * @param loginId the login they sign in with, or {@code null}
   * @return the orders, newest first
   * @throws ApiException {@code EXPORT_ORDERS_UNAVAILABLE} (503) when order-svc cannot be reached,
   *     so a partial export is never served as a complete one. An open circuit surfaces as {@code
   *     CircuitBreakerOpenException} instead — thrown by the interceptor before the method body
   *     runs, so it cannot be caught here and is translated by the caller.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public JsonArray ordersOf(UUID tenantId, UUID customerId, UUID loginId) {
    ServiceInstance instance =
        registry
            .resolve(ORDER_SERVICE)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "EXPORT_ORDERS_UNAVAILABLE",
                        "order-svc is unreachable, so the export would be incomplete",
                        List.of()));
    // queryParam, never a "?" in the path string: the WebClient percent-encodes it into the path,
    // and order-svc then reads "export?customer=…" as an order id and answers 400. The tests never
    // reach the wire, so this was found by driving the running stack (ConsulClient has the same
    // note for the same reason).
    var request =
        webClient
            .get(instance.baseUri() + "/orders/export")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString());
    if (customerId != null) {
      request = request.queryParam("customer", customerId.toString());
    }
    if (loginId != null) {
      request = request.queryParam("login", loginId.toString());
    }
    try (HttpClientResponse res =
        request.header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE).request()) {
      if (res.status().code() != 200) {
        throw new ApiException(
            503,
            "EXPORT_ORDERS_UNAVAILABLE",
            "order-svc answered " + res.status().code() + ", so the export would be incomplete",
            List.of());
      }
      try (JsonReader reader = Jsons.reader(new StringReader(res.as(String.class)))) {
        JsonArray data = reader.readObject().getJsonArray("data");
        return data == null ? Jsons.array().build() : data;
      }
    }
  }
}
