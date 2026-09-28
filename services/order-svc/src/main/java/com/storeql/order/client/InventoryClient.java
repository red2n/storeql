package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.ServiceConfig;
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
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for inventory-svc's reservation API (golden rule #1: foreign data comes from the
 * owning service; rule #4: the instance is resolved via Consul, not a hardcoded host:port).
 *
 * <p>Used by placeOrder to hold stock for ONLINE orders at checkout, closing the oversell window
 * between order placement and fulfilment. All-or-nothing per order: if any line is short, the lines
 * already held for this attempt are released (best effort — an unreleased hold is reclaimed by
 * inventory-svc's TTL sweeper) and the whole placement fails.
 *
 * <p>Fail-closed by default (see {@code storeql.order.inventory.reserve-enforce}): when inventory
 * cannot confirm stock, the order is rejected — selling stock we may not have is worse than a lost
 * sale. Local rigs without inventory-svc set enforce=false to skip reservation entirely.
 */
@ApplicationScoped
public class InventoryClient {

  private static final Logger LOG = System.getLogger(InventoryClient.class.getName());
  private static final String INVENTORY_SERVICE = "inventory-svc";

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

  /** One order line to hold stock for. */
  public record ReserveLine(UUID variantId, BigDecimal qty) {}

  /**
   * Holds stock for every line of an order; returns the reservation ids. Throws 409
   * ORDER_INSUFFICIENT_STOCK when any line is short (after releasing the lines already held for
   * this attempt), 503 ORDER_INVENTORY_UNAVAILABLE when inventory-svc cannot be reached.
   *
   * <p>Each line's hold is idempotent on a key derived from {@code idemBase}, the line's index and
   * its variant, so a retried placement replays the original holds instead of double-holding stock
   * (golden rule #11). The derived key is a UUIDv7 ({@link Ids#derived}), as inventory-svc requires
   * of every Idempotency-Key.
   */
  public List<UUID> reserveForOrder(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<ReserveLine> lines,
      long ttlSeconds,
      UUID idemBase) {
    List<UUID> held = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      ReserveLine line = lines.get(i);
      // Line index in the key so two order lines for the same variant hold stock separately
      // instead of the second replaying the first line's hold.
      String lineKey = Ids.derived(idemBase, "reserve:" + i + ":" + line.variantId()).toString();
      try {
        held.add(reserveLine(tenantId, orderId, storeId, line, ttlSeconds, lineKey));
      } catch (RuntimeException e) {
        releaseQuietly(tenantId, held);
        throw e;
      }
    }
    return held;
  }

  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  UUID reserveLine(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      ReserveLine line,
      long ttlSeconds,
      String idempotencyKey) {
    String base =
        baseUri()
            .orElseThrow(() -> unavailable("no healthy inventory-svc instance in discovery", null));

    String payload =
        Json.createObjectBuilder()
            .add("storeId", storeId.toString())
            .add("variantId", line.variantId().toString())
            .add("qty", line.qty())
            .add("orderId", orderId.toString())
            .add("ttlSeconds", ttlSeconds)
            .build()
            .toString();

    try (HttpClientResponse res =
        webClient
            .post(base + "/inventory/reservations")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.IDEMPOTENCY_KEY), idempotencyKey)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload)) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status == 201 || status == 200) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
          return Ids.parse(reader.readObject().getJsonObject("data").getString("id"));
        } catch (RuntimeException e) {
          throw unavailable("malformed response from inventory-svc", e);
        }
      }
      if (status == 409 || status == 422) {
        throw ApiException.conflict(
            "ORDER_INSUFFICIENT_STOCK",
            "not enough stock for variant " + line.variantId() + ": " + errorMessage(body));
      }
      throw unavailable("inventory-svc returned HTTP " + status, null);
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("inventory-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("inventory-svc unreachable", e);
    }
  }

  /**
   * Best-effort release of holds after a failed placement attempt. A failure here is only logged:
   * the holds carry a TTL and inventory-svc's reservation sweeper reclaims them.
   */
  public void releaseQuietly(UUID tenantId, List<UUID> reservationIds) {
    for (UUID id : reservationIds) {
      try {
        String base = baseUri().orElse(null);
        if (base == null) {
          LOG.log(Level.WARNING, "release of reservation {0} skipped: no inventory-svc", id);
          continue;
        }
        try (HttpClientResponse res =
            webClient
                .post(base + "/inventory/reservations/" + id + "/release")
                .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
                .submit("")) {
          if (res.status().code() != 200) {
            LOG.log(
                Level.WARNING,
                "release of reservation {0} returned HTTP {1} — TTL sweeper will reclaim it",
                id,
                res.status().code());
          }
        }
      } catch (RuntimeException e) {
        LOG.log(
            Level.WARNING,
            "release of reservation {0} failed ({1}) — TTL sweeper will reclaim it",
            id,
            e.getMessage());
      }
    }
  }

  /**
   * A configured address first — a deployment without discovery, or a test standing a stub where
   * inventory-svc would be — else the instance from Consul.
   */
  private java.util.Optional<String> baseUri() {
    return com.storeql.service.ServiceReader.configuredUrl(INVENTORY_SERVICE)
        .or(() -> registry.resolve(INVENTORY_SERVICE).map(ServiceInstance::baseUri));
  }

  private static String errorMessage(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (root.containsKey("error") && !root.isNull("error")) {
        return root.getJsonObject("error").getString("message", "insufficient stock");
      }
    } catch (RuntimeException ignored) {
      // fall through to the generic message
    }
    return "insufficient stock";
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "ORDER_INVENTORY_UNAVAILABLE", message, List.of(), cause);
  }
}
