package com.storeql.payment.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.payment.config.ServiceConfig;
import com.storeql.web.ApiException;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for order-svc's {@code GET /orders/{id}}, used to verify an online payment claim
 * against the order it's captured against (golden rule #1: foreign data comes from the owning
 * service, never trusted from the caller; rule #4: the instance is resolved via Consul).
 */
@ApplicationScoped
public class OrderClient {

  private static final String ORDER_SERVICE = "order-svc";

  /** See the header comment in {@link #getOrder}. */
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

  /** The order-svc fields needed to validate a payment claim against the order it targets. */
  public record OrderInfo(
      String customerId,
      /**
       * The login that placed the order, for a signed-in shopper's online sale. This — not {@code
       * customerId} — is what a caller's token carries, so it is what ownership is checked against
       * (SJ-D44). Null for a guest checkout and for a till sale.
       */
      String loginId,
      String channel,
      BigDecimal total,
      String status,
      String storeId,
      String currency,
      /**
       * The split checkout the order is a part of (order orchestration), or null: a part is paid
       * with its checkout, never alone.
       */
      String groupId) {

    /** An order as read before split checkouts existed. */
    public OrderInfo(
        String customerId,
        String loginId,
        String channel,
        BigDecimal total,
        String status,
        String storeId,
        String currency) {
      this(customerId, loginId, channel, total, status, storeId, currency, null);
    }
  }

  /**
   * A delivery checkout split across shops (order orchestration), as order-svc reports it.
   *
   * @param loginId the login that placed it; null for a guest checkout
   * @param parts the orders, the delivery-area store's first
   */
  public record GroupInfo(
      UUID id, String loginId, BigDecimal total, String currency, List<GroupPart> parts) {
    public GroupInfo {
      parts = List.copyOf(parts);
    }
  }

  /** One order of a split checkout. */
  public record GroupPart(UUID orderId, UUID storeId, String status, BigDecimal total) {}

  /**
   * Throws 404 when the order doesn't exist in the tenant, 503 when order-svc cannot be reached.
   *
   * <p>{@code @Retry}/{@code @CircuitBreaker}: same rationale as {@code PricingClient} — retry
   * transient failures, trip after sustained failures so a dead order-svc fails fast instead of
   * blocking every online payment for 5s.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public OrderInfo getOrder(UUID tenantId, UUID orderId) {
    String base =
        baseUri()
            .orElseThrow(() -> unavailable("no healthy order-svc instance in discovery", null));

    try (HttpClientResponse res =
        webClient
            .get(base + "/orders/" + orderId)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            // Trusted service-to-service call behind the gateway. It stamps a staff role for the
            // same reason CustomerClient does: reading an order is now staff-gated, and taking
            // payment for one is a till action. Without this the call reads as an anonymous
            // customer and order-svc's object-level check refuses it.
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.notFound("PAYMENT_ORDER_NOT_FOUND", "order " + orderId + " not found");
      }
      if (status != 200) {
        throw unavailable("order-svc returned HTTP " + status, null);
      }
      String body = res.as(String.class);
      try {
        return parseOrder(body);
      } catch (RuntimeException e) {
        throw unavailable("malformed response from order-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("order-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("order-svc unreachable", e);
    }
  }

  /**
   * A split checkout, read as a member of staff for the same reason as {@link #getOrder}.
   *
   * @throws ApiException 404 {@code PAYMENT_GROUP_NOT_FOUND} when order-svc has no such checkout in
   *     the tenant; 503 when order-svc cannot be reached
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public GroupInfo getGroup(UUID tenantId, UUID groupId) {
    String base =
        baseUri()
            .orElseThrow(() -> unavailable("no healthy order-svc instance in discovery", null));
    try (HttpClientResponse res =
        webClient
            .get(base + "/order-groups/" + groupId)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.notFound(
            "PAYMENT_GROUP_NOT_FOUND", "checkout " + groupId + " not found");
      }
      if (status != 200) {
        throw unavailable("order-svc returned HTTP " + status, null);
      }
      String body = res.as(String.class);
      try {
        return parseGroup(body);
      } catch (RuntimeException e) {
        throw unavailable("malformed response from order-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("order-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("order-svc unreachable", e);
    }
  }

  static GroupInfo parseGroup(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject data = reader.readObject().getJsonObject("data");
      List<GroupPart> parts = new java.util.ArrayList<>();
      for (JsonObject p : data.getJsonArray("parts").getValuesAs(JsonObject.class)) {
        parts.add(
            new GroupPart(
                com.storeql.ids.Ids.parse(p.getString("orderId")),
                com.storeql.ids.Ids.parse(p.getString("storeId")),
                p.getString("status"),
                p.getJsonNumber("total").bigDecimalValue()));
      }
      return new GroupInfo(
          com.storeql.ids.Ids.parse(data.getString("id")),
          optionalString(data, "loginId"),
          data.getJsonNumber("total").bigDecimalValue(),
          optionalString(data, "currency"),
          parts);
    }
  }

  /**
   * A configured address first — a deployment without discovery, or a test standing a stub where
   * order-svc would be — else the instance from Consul.
   */
  private java.util.Optional<String> baseUri() {
    return com.storeql.service.ServiceReader.configuredUrl(ORDER_SERVICE)
        .or(() -> registry.resolve(ORDER_SERVICE).map(ServiceInstance::baseUri));
  }

  /**
   * Parses an order response body into the fields payment needs.
   *
   * <p>{@code customerId} is read with {@code containsKey} rather than {@code isNull} alone,
   * because JSON-B omits null fields from a DTO entirely rather than serialising them as null — and
   * a guest order has no customer. {@code isNull} throws on an absent key, so the previous form
   * turned every guest order into "malformed response from order-svc" and a 503, which is the
   * entire guest online checkout path. Events are unaffected and were the reason this went
   * unnoticed: the outbox payloads are hand-built and do emit {@code "customerId":null}.
   *
   * @param body the raw {@code {"data":{...}}} response
   * @return the parsed fields; {@code customerId} null for a guest order
   */
  static OrderInfo parseOrder(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject data = reader.readObject().getJsonObject("data");
      return new OrderInfo(
          optionalString(data, "customerId"),
          optionalString(data, "loginId"),
          data.getString("channel"),
          data.getJsonNumber("total").bigDecimalValue(),
          data.getString("status"),
          optionalString(data, "storeId"),
          optionalString(data, "currency"),
          data.containsKey("group") && !data.isNull("group")
              ? data.getJsonObject("group").getString("id")
              : null);
    }
  }

  /**
   * Reads a field that may be absent or null.
   *
   * <p>Both cases have to be handled and neither is handled by {@code getString}: JSON-B omits a
   * null field from a DTO entirely rather than serialising it as null, and {@code isNull} throws on
   * an absent key rather than returning true. Getting this wrong on {@code customerId} turned every
   * guest order into a 503 (SJ-D14); {@code storeId} and {@code currency} are read the same way
   * here because the same two shapes apply to them, and the caller already treats a null store as
   * legitimate.
   *
   * @param data the order object
   * @param field field name
   * @return the value, or null if the field is absent or JSON null
   */
  private static String optionalString(JsonObject data, String field) {
    return data.containsKey(field) && !data.isNull(field) ? data.getString(field) : null;
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "PAYMENT_ORDER_LOOKUP_UNAVAILABLE", message, List.of(), cause);
  }
}
