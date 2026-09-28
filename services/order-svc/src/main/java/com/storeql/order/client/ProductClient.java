package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.ServiceConfig;
import com.storeql.web.HttpHeaders;
import com.storeql.web.TenantContext;
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
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Sync client for product-svc's variant lookup (golden rule #1 — the names live there; rule #4 —
 * the instance comes from Consul). A fiscal file lists every line by a product's name and code, and
 * this service holds only the variant id.
 *
 * <p>The caller's identity is forwarded, not this service's: the lookup is a staff read in
 * product-svc, and the manager exporting the register is a staff member.
 */
@ApplicationScoped
public class ProductClient {

  private static final System.Logger LOG = System.getLogger(ProductClient.class.getName());
  private static final String PRODUCT_SERVICE = "product-svc";

  /** The role the relationships read is made with: a management read in product-svc. */
  private static final String RELATIONSHIPS_ROLE = "MANAGER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(8))
            .build();
  }

  /** What a line prints: the product's name, its SKU and its unit. */
  public record VariantName(
      String productName,
      String sku,
      String unit,
      /** PET, ALUMINIUM, STEEL or GLASS when sold in a drinks container (09.16); else null. */
      String depositMaterial,
      /** The container's volume in millilitres, with {@code depositMaterial}. */
      Integer depositVolumeMl) {
    public VariantName(String productName, String sku, String unit) {
      this(productName, sku, unit, null, null);
    }
  }

  /**
   * The printable names of a set of variants.
   *
   * @param tenantId the tenant, as the caller's context carries it
   * @param ids the variants to name
   * @param ctx the caller, whose identity is forwarded
   * @return names by variant id; empty when product-svc could not be reached
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "unavailable")
  public Optional<Map<UUID, VariantName>> names(
      UUID tenantId, Collection<UUID> ids, TenantContext ctx) {
    return fetch(tenantId, ids, String.join(",", ctx.roles()), ctx.userId());
  }

  /**
   * The same lookup outside any request, for a consumer naming the lines of a recall notice
   * (05.10). The lookup is a staff read in product-svc; with no caller to forward, the service asks
   * as a member of staff, the way notification-svc reads a customer's address.
   *
   * @param tenantId the tenant the event belongs to
   * @param ids the variants to name
   * @return names by variant id; empty when product-svc could not be reached
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "unavailableAsSystem")
  public Optional<Map<UUID, VariantName>> namesAsSystem(UUID tenantId, Collection<UUID> ids) {
    return fetch(tenantId, ids, "CASHIER", null);
  }

  private Optional<Map<UUID, VariantName>> fetch(
      UUID tenantId, Collection<UUID> ids, String roles, UUID userId) {
    if (ids.isEmpty()) {
      return Optional.of(Map.of());
    }
    // A configured address first, for a deployment without discovery and for a test that stands a
    // stub where product-svc would be (09.16); otherwise the registry.
    String base =
        com.storeql.service.ServiceReader.configuredUrl(PRODUCT_SERVICE)
            .orElseGet(
                () -> registry.resolve(PRODUCT_SERVICE).map(ServiceInstance::baseUri).orElse(null));
    if (base == null) {
      return Optional.empty();
    }
    String joined = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    var req =
        webClient
            .get(base + "/admin/products/variants/resolve")
            .queryParam("ids", joined)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), roles);
    if (userId != null) {
      req = req.header(HeaderNames.create(HttpHeaders.USER_ID), userId.toString());
    }
    try (HttpClientResponse res = req.request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(
            System.Logger.Level.WARNING,
            "variant resolve HTTP {0}: {1}",
            res.status().code(),
            body);
        return Optional.empty();
      }
      Map<UUID, VariantName> out = new HashMap<>();
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray data = reader.readObject().getJsonArray("data");
        if (data != null) {
          for (var v : data.getValuesAs(JsonObject.class)) {
            if (v.containsKey("variantId") && !v.isNull("variantId")) {
              out.put(
                  Ids.parse(v.getString("variantId")),
                  new VariantName(
                      v.getString("productName", null),
                      v.getString("sku", null),
                      v.getString("unit", null),
                      v.getString("depositMaterial", null),
                      v.containsKey("depositVolumeMl") && !v.isNull("depositVolumeMl")
                          ? v.getInt("depositVolumeMl")
                          : null));
            }
          }
        }
      }
      return Optional.of(out);
    }
  }

  /**
   * The variants the business declared as stand-ins for one (product-svc's {@code SUBSTITUTE} item
   * relationships), for a picker choosing a substitute (substitutions for out-of-stock online
   * lines). product-svc keeps that read under its management roles, and the picker is a cashier or
   * storekeeper whom order-svc has already checked against the order's store, so the read is made
   * as the service ({@link #RELATIONSHIPS_ROLE}) with the picker's login forwarded for the record —
   * the way {@code StockClient} reads inventory-svc as staff.
   *
   * @return the related variants, in the order product-svc lists them; empty when there are none or
   *     product-svc could not be reached
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "noSubstitutes")
  public java.util.List<UUID> substitutes(UUID tenantId, UUID variantId, TenantContext ctx) {
    String base =
        com.storeql.service.ServiceReader.configuredUrl(PRODUCT_SERVICE)
            .orElseGet(
                () -> registry.resolve(PRODUCT_SERVICE).map(ServiceInstance::baseUri).orElse(null));
    if (base == null) {
      return java.util.List.of();
    }
    var req =
        webClient
            .get(base + "/admin/products/variants/" + variantId + "/relationships")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), RELATIONSHIPS_ROLE);
    if (ctx.userId() != null) {
      req = req.header(HeaderNames.create(HttpHeaders.USER_ID), ctx.userId().toString());
    }
    try (HttpClientResponse res = req.request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(
            System.Logger.Level.WARNING, "relationships HTTP {0}: {1}", res.status().code(), body);
        return java.util.List.of();
      }
      java.util.List<UUID> out = new java.util.ArrayList<>();
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray data = reader.readObject().getJsonArray("data");
        if (data != null) {
          for (var r : data.getValuesAs(JsonObject.class)) {
            if ("SUBSTITUTE".equals(r.getString("relationshipType", null))
                && r.containsKey("relatedVariantId")
                && !r.isNull("relatedVariantId")) {
              out.add(Ids.parse(r.getString("relatedVariantId")));
            }
          }
        }
      }
      return out;
    }
  }

  @SuppressWarnings("unused")
  java.util.List<UUID> noSubstitutes(UUID tenantId, UUID variantId, TenantContext ctx) {
    LOG.log(System.Logger.Level.WARNING, "product-svc unavailable; no substitutes suggested");
    return java.util.List.of();
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<Map<UUID, VariantName>> unavailable(
      UUID tenantId, Collection<UUID> ids, TenantContext ctx) {
    return unavailableAsSystem(tenantId, ids);
  }

  @SuppressWarnings("unused")
  Optional<Map<UUID, VariantName>> unavailableAsSystem(UUID tenantId, Collection<UUID> ids) {
    LOG.log(System.Logger.Level.WARNING, "product-svc unavailable; variant names not resolved");
    return Optional.empty();
  }
}
