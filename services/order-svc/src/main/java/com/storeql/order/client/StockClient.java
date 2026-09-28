package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.ServiceConfig;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Sync client for inventory-svc's stock by store (order orchestration): what each store can give of
 * an order's products, read when a delivery order is routed. Golden rule #1 — the stock lives
 * there; rule #4 — a configured address first, else the instance from Consul.
 *
 * <p>The read is a staff read in inventory-svc; a shopper's checkout asks as a storekeeper, the way
 * purchase-svc reads the network. The quantities are never shown to the shopper.
 */
@ApplicationScoped
public class StockClient {

  private static final System.Logger LOG = System.getLogger(StockClient.class.getName());
  private static final String INVENTORY_SERVICE = "inventory-svc";
  private static final String INTERNAL_ROLE = "STOREKEEPER";

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
   * What each store can give, and which products the supplier fulfils per order.
   *
   * @param available store → product → quantity available
   * @param dropship the products no store holds because a supplier ships them
   */
  public record Stock(Map<UUID, Map<UUID, BigDecimal>> available, Set<UUID> dropship) {
    public Stock {
      Map<UUID, Map<UUID, BigDecimal>> copy = new HashMap<>();
      available.forEach((store, qty) -> copy.put(store, Map.copyOf(qty)));
      available = Map.copyOf(copy);
      dropship = Set.copyOf(dropship);
    }
  }

  /**
   * The stock of every store for the products named.
   *
   * @return the stock; empty when inventory-svc could not answer, and the order is then placed at
   *     the delivery-area store as it always was
   */
  @Retry(maxRetries = 1, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "unavailable")
  public Optional<Stock> stockByStore(UUID tenantId, Collection<UUID> variantIds) {
    String base =
        com.storeql.service.ServiceReader.configuredUrl(INVENTORY_SERVICE)
            .orElseGet(
                () ->
                    registry.resolve(INVENTORY_SERVICE).map(ServiceInstance::baseUri).orElse(null));
    if (base == null) return Optional.empty();
    String joined = variantIds.stream().map(UUID::toString).collect(Collectors.joining(","));
    try (HttpClientResponse res =
        webClient
            .get(base + "/admin/inventory/network/stock")
            .queryParam("variants", joined)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(System.Logger.Level.WARNING, "stock by store HTTP {0}", res.status().code());
        return Optional.empty();
      }
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        return Optional.of(parse(reader.readObject().getJsonObject("data")));
      }
    }
  }

  static Stock parse(JsonObject data) {
    Map<UUID, Map<UUID, BigDecimal>> available = new HashMap<>();
    JsonArray levels = data.getJsonArray("levels");
    if (levels != null) {
      for (JsonObject l : levels.getValuesAs(JsonObject.class)) {
        BigDecimal qty = l.getJsonNumber("available").bigDecimalValue();
        if (qty.signum() <= 0) continue;
        available
            .computeIfAbsent(Ids.parse(l.getString("storeId")), k -> new HashMap<>())
            .put(Ids.parse(l.getString("variantId")), qty);
      }
    }
    Set<UUID> dropship = new HashSet<>();
    JsonArray ds = data.getJsonArray("dropship");
    if (ds != null) {
      for (int i = 0; i < ds.size(); i++) dropship.add(Ids.parse(ds.getString(i)));
    }
    return new Stock(available, dropship);
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<Stock> unavailable(UUID tenantId, Collection<UUID> variantIds) {
    LOG.log(System.Logger.Level.WARNING, "inventory-svc unavailable; order not routed");
    return Optional.empty();
  }
}
