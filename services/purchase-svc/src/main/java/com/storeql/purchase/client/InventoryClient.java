package com.storeql.purchase.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.purchase.config.ServiceConfig;
import com.storeql.purchase.domain.OrderProposal;
import com.storeql.purchase.domain.PeriodControl;
import com.storeql.service.ServiceReader;
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
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Asks inventory-svc, which owns stock (golden rule #1), what a store has on hand of a variant — so
 * a return to vendor of goods that were already sold is refused where the buyer can see it rather
 * than skipped line by line in a consumer.
 *
 * <p>Advisory, not authoritative: an empty answer means inventory-svc could not say, and the return
 * goes ahead; the stock movement itself happens where the stock lives, when {@code
 * ReturnedToVendor} is consumed. The same shape as {@link PricingClient}.
 */
@ApplicationScoped
public class InventoryClient {

  private static final Logger LOG = System.getLogger(InventoryClient.class.getName());
  private static final String INVENTORY_SERVICE = "inventory-svc";

  /** {@code GET /admin/inventory/batches} is a staff-operable admin read. */
  private static final String INTERNAL_ROLE = "STOREKEEPER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  /**
   * The planning reads (06.x), through the shared reader so a test or a deployment without
   * discovery can name the address.
   */
  private ServiceReader planning;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    planning =
        new ServiceReader(
            config,
            INVENTORY_SERVICE,
            INTERNAL_ROLE,
            2,
            ServiceReader.configuredUrl(INVENTORY_SERVICE));
  }

  // ── The stock position a proposal reads (06.x) ──────────────────────────────

  /**
   * The store's reorder plans as the proposal policy reads them.
   *
   * @return the plans (empty list when the store has none); empty when inventory-svc could not be
   *     read, so the caller can refuse rather than propose on nothing
   */
  public Optional<List<OrderProposal.Plan>> reorderPlans(UUID tenantId, UUID storeId) {
    ServiceReader.Reply reply =
        planning.get(tenantId, "/admin/inventory/rop-plans", Map.of("store", storeId.toString()));
    if (reply.status() == 404) {
      return Optional.of(List.of());
    }
    if (!reply.ok()) {
      return Optional.empty();
    }
    List<OrderProposal.Plan> plans = new ArrayList<>();
    for (JsonObject p : dataArray(reply.body())) {
      plans.add(
          new OrderProposal.Plan(
              Ids.parse(p.getString("variantId")),
              number(p, "rop"),
              number(p, "eoq"),
              number(p, "minOrderQty"),
              number(p, "maxOrderQty"),
              number(p, "lotMultiplier"),
              number(p, "avgDailyDemand"),
              p.containsKey("leadTimeDays") && !p.isNull("leadTimeDays")
                  ? p.getInt("leadTimeDays")
                  : 7));
    }
    return Optional.of(plans);
  }

  /**
   * What is available to sell per variant at the store, every page of it.
   *
   * @return variant → available; empty when inventory-svc could not be read
   */
  public Optional<Map<UUID, BigDecimal>> availableByVariant(UUID tenantId, UUID storeId) {
    Map<UUID, BigDecimal> out = new HashMap<>();
    String after = null;
    for (int page = 0; page < 200; page++) {
      Map<String, String> query = new HashMap<>();
      query.put("store", storeId.toString());
      query.put("limit", "100");
      if (after != null) {
        query.put("after", after);
      }
      ServiceReader.Reply reply = planning.get(tenantId, "/admin/inventory/levels", query);
      if (!reply.ok()) {
        return Optional.empty();
      }
      JsonObject envelope;
      try (JsonReader reader = Json.createReader(new StringReader(reply.body()))) {
        envelope = reader.readObject();
      }
      JsonArray data = envelope.getJsonArray("data");
      if (data != null) {
        for (JsonObject level : data.getValuesAs(JsonObject.class)) {
          BigDecimal available = number(level, "available");
          if (available != null) {
            out.merge(Ids.parse(level.getString("variantId")), available, BigDecimal::add);
          }
        }
      }
      JsonObject meta =
          envelope.containsKey("meta") && !envelope.isNull("meta")
              ? envelope.getJsonObject("meta")
              : null;
      after =
          meta != null && meta.containsKey("nextCursor") && !meta.isNull("nextCursor")
              ? meta.getString("nextCursor")
              : null;
      if (after == null) {
        break;
      }
    }
    return Optional.of(out);
  }

  /**
   * What a proposal needs of a forecast: the next twenty-eight days, and the shelf life that bounds
   * an order.
   */
  public record ForecastGlance(BigDecimal next28, Integer maxCoverDays) {}

  /**
   * What a warehouse's shops are expected to need of one product (depot / DC replenishment).
   *
   * @param next28 their forecast over 28 days, summed
   * @param avgDailyDemand their average daily demand, summed
   * @param committed already promised to them in transfers not yet shipped
   * @param shops how many of its shops stock it from the warehouse
   */
  public record ServedDemand(
      BigDecimal next28, BigDecimal avgDailyDemand, BigDecimal committed, int shops) {}

  /**
   * How a store is supplied, as inventory-svc's network says.
   *
   * @param servedBy the shop's warehouse, or null when it buys everything direct
   * @param direct what a served shop buys direct
   * @param warehouse whether the store is a warehouse serving shops
   * @param demand a warehouse's shops' needs per product
   */
  public record Sourcing(
      UUID servedBy,
      Set<UUID> direct,
      boolean warehouse,
      Map<UUID, ServedDemand> demand,
      Set<UUID> shops) {
    public static final Sourcing ALONE = new Sourcing(null, Set.of(), false, Map.of(), Set.of());

    public Sourcing {
      direct = Set.copyOf(direct);
      demand = Map.copyOf(demand);
      shops = Set.copyOf(shops);
    }

    /** Whether a served shop buys this product from its warehouse, not from a supplier. */
    public boolean fromWarehouse(UUID variantId) {
      return servedBy != null && !direct.contains(variantId);
    }
  }

  /**
   * How the store is supplied.
   *
   * @return the sourcing; {@link Sourcing#ALONE} when inventory-svc knows no network for it; empty
   *     when inventory-svc could not be read, so the caller can refuse rather than buy for a shop
   *     its warehouse already serves
   */
  public Optional<Sourcing> sourcing(UUID tenantId, UUID storeId) {
    ServiceReader.Reply reply =
        planning.get(
            tenantId, "/admin/inventory/network/sourcing", Map.of("storeId", storeId.toString()));
    if (reply.status() == 404) {
      return Optional.of(Sourcing.ALONE);
    }
    if (!reply.ok()) {
      return Optional.empty();
    }
    try (JsonReader reader = Json.createReader(new StringReader(reply.body()))) {
      JsonObject d = reader.readObject().getJsonObject("data");
      if (d == null) return Optional.of(Sourcing.ALONE);
      UUID servedBy =
          d.containsKey("servedBy") && !d.isNull("servedBy")
              ? Ids.parse(d.getString("servedBy"))
              : null;
      Set<UUID> direct = new java.util.HashSet<>();
      if (d.containsKey("direct") && !d.isNull("direct")) {
        for (JsonValue v : d.getJsonArray("direct")) {
          direct.add(Ids.parse(((jakarta.json.JsonString) v).getString()));
        }
      }
      Map<UUID, ServedDemand> demand = new HashMap<>();
      if (d.containsKey("demand") && !d.isNull("demand")) {
        for (JsonObject x : d.getJsonArray("demand").getValuesAs(JsonObject.class)) {
          demand.put(
              Ids.parse(x.getString("variantId")),
              new ServedDemand(
                  number(x, "next28"),
                  number(x, "avgDailyDemand"),
                  number(x, "committed"),
                  x.getInt("shops", 0)));
        }
      }
      Set<UUID> shops = new java.util.HashSet<>();
      if (d.containsKey("shops") && !d.isNull("shops")) {
        for (JsonValue v : d.getJsonArray("shops")) {
          shops.add(Ids.parse(((jakarta.json.JsonString) v).getString()));
        }
      }
      return Optional.of(
          new Sourcing(servedBy, direct, d.getBoolean("warehouse", false), demand, shops));
    }
  }

  /** One shop's share of a quantity the warehouse would send, by the shops' current needs. */
  public record NeedShare(UUID storeId, BigDecimal need, BigDecimal qty) {}

  /**
   * How a quantity of a product arriving at a warehouse would be shared among the shops it serves
   * by what they need now (cross-docking's fill-from-needs).
   *
   * @return the shares; empty when inventory-svc could not be read
   */
  public Optional<List<NeedShare>> needShares(
      UUID tenantId, UUID warehouseId, UUID variantId, BigDecimal qty) {
    ServiceReader.Reply reply =
        planning.get(
            tenantId,
            "/admin/inventory/network/needs",
            Map.of(
                "warehouseId",
                warehouseId.toString(),
                "variantId",
                variantId.toString(),
                "qty",
                qty.toPlainString()));
    if (!reply.ok()) return Optional.empty();
    List<NeedShare> out = new ArrayList<>();
    for (JsonObject o : dataArray(reply.body())) {
      out.add(
          new NeedShare(Ids.parse(o.getString("storeId")), number(o, "need"), number(o, "qty")));
    }
    return Optional.of(out);
  }

  /**
   * A glance at each forecast variant at the store.
   *
   * @return variant → glance; empty when inventory-svc could not be read (the proposal then falls
   *     back to the plan's average daily demand and says so)
   */
  public Optional<Map<UUID, ForecastGlance>> forecastGlances(UUID tenantId, UUID storeId) {
    ServiceReader.Reply reply =
        planning.get(tenantId, "/admin/inventory/forecasts", Map.of("store", storeId.toString()));
    if (reply.status() == 404) {
      return Optional.of(Map.of());
    }
    if (!reply.ok()) {
      return Optional.empty();
    }
    Map<UUID, ForecastGlance> out = new HashMap<>();
    for (JsonObject f : dataArray(reply.body())) {
      BigDecimal next28 = number(f, "next28");
      Integer maxCover =
          f.containsKey("maxCoverDays") && !f.isNull("maxCoverDays")
              ? f.getInt("maxCoverDays")
              : null;
      if (next28 != null) {
        out.put(Ids.parse(f.getString("variantId")), new ForecastGlance(next28, maxCover));
      }
    }
    return Optional.of(out);
  }

  private static List<JsonObject> dataArray(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonArray data = reader.readObject().getJsonArray("data");
      return data == null ? List.of() : data.getValuesAs(JsonObject.class);
    }
  }

  private static BigDecimal number(JsonObject o, String key) {
    return o.containsKey(key) && !o.isNull(key) ? new BigDecimal(o.get(key).toString()) : null;
  }

  /**
   * What is on hand and available of a variant at a store: the remaining quantity of every
   * available batch, summed.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param variantId the variant
   * @return the quantity, or empty when inventory-svc could not be reached or refused
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<BigDecimal> onHand(UUID tenantId, UUID storeId, UUID variantId) {
    ServiceInstance instance = registry.resolve(INVENTORY_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "inventory-svc not in discovery — on-hand not checked");
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/admin/inventory/batches")
            .queryParam("store", storeId.toString())
            .queryParam("variant", variantId.toString())
            .queryParam("material_status", "AVAILABLE")
            .queryParam("limit", "100")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "on-hand lookup HTTP {0}: {1}", status, body);
        return Optional.empty();
      }
      BigDecimal total = BigDecimal.ZERO;
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray data = reader.readObject().getJsonArray("data");
        if (data != null) {
          for (JsonObject b : data.getValuesAs(JsonObject.class)) {
            if (b.containsKey("remainingQty") && !b.isNull("remainingQty")) {
              total = total.add(new BigDecimal(b.get("remainingQty").toString()));
            }
          }
        }
      }
      return Optional.of(total);
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "inventory-svc circuit open — on-hand not checked");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "on-hand lookup failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * The nominal code a store's stock posts to, from inventory-svc's zone-to-GL mapping (17.3).
   *
   * <p>The store-level mapping — the row with no zone — is the one a goods receipt uses, because a
   * receipt is booked to a store and the zone a batch lands in is decided afterwards by put-away.
   * Empty when the store has no mapping, when inventory-svc cannot be reached, or when the circuit
   * is open; the caller falls back to the default stock code, so a discovery outage changes which
   * code a receipt posts to, never whether it posts.
   *
   * @param tenantId the owning tenant, forwarded as the internal tenant header
   * @param storeId the store the goods were received into
   * @return the mapped nominal code, or empty
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<String> storeNominalCode(UUID tenantId, UUID storeId) {
    return fetch(
        "/admin/inventory/zone-gl-mappings",
        storeId,
        tenantId,
        "GL mapping",
        InventoryClient::parseStoreNominalCode);
  }

  /**
   * The accounting periods inventory-svc holds for a store, for period control (04.7).
   *
   * <p>Empty when inventory-svc cannot be reached: period control fails open, because a discovery
   * outage that refused every goods receipt and every journal would be a far larger outage than a
   * posting landing in a month finance has already closed. The warning is logged either way.
   *
   * @param tenantId the owning tenant
   * @param storeId the store whose periods to read
   * @return the periods, or empty when they could not be read
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<List<PeriodControl.Period>> accountingPeriods(UUID tenantId, UUID storeId) {
    return fetch(
        "/admin/inventory/accounting-periods",
        storeId,
        tenantId,
        "accounting periods",
        body -> Optional.of(parsePeriods(body)));
  }

  private <T> Optional<T> fetch(
      String path,
      UUID storeId,
      UUID tenantId,
      String what,
      java.util.function.Function<String, Optional<T>> parser) {
    ServiceInstance instance = registry.resolve(INVENTORY_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "inventory-svc not in discovery — {0} not read", what);
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + path)
            .queryParam("store", storeId.toString())
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "{0} lookup HTTP {1}: {2}", what, status, body);
        return Optional.empty();
      }
      return parser.apply(body);
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "inventory-svc circuit open — {0} not read", what);
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "{0} lookup failed: {1}", what, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * The store-level nominal code out of a zone-GL-mappings envelope: the row whose zone is null.
   *
   * @param body the response body
   * @return the code, trimmed, or empty when no store-level row carries one
   */
  static Optional<String> parseStoreNominalCode(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject env = reader.readObject();
      if (!env.containsKey("data") || env.isNull("data")) return Optional.empty();
      JsonArray data = env.getJsonArray("data");
      for (JsonObject m : data.getValuesAs(JsonObject.class)) {
        boolean storeLevel = !m.containsKey("zoneId") || m.isNull("zoneId");
        String code = m.getString("nominalCode", "");
        if (storeLevel && !code.isBlank() && code.trim().matches("[A-Za-z0-9]{1,10}")) {
          return Optional.of(code.trim());
        }
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "GL mapping body unreadable: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * The periods out of an accounting-periods envelope. A row whose date does not parse is skipped
   * rather than failing the lot: one bad row must not switch period control off for the store.
   *
   * @param body the response body
   * @return the periods, possibly empty
   */
  static List<PeriodControl.Period> parsePeriods(String body) {
    List<PeriodControl.Period> out = new ArrayList<>();
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject env = reader.readObject();
      if (!env.containsKey("data") || env.isNull("data")) return out;
      for (JsonObject p : env.getJsonArray("data").getValuesAs(JsonObject.class)) {
        String date = p.getString("periodDate", null);
        String status = p.getString("status", null);
        if (date == null || status == null) continue;
        try {
          out.add(new PeriodControl.Period(LocalDate.parse(date), status));
        } catch (java.time.format.DateTimeParseException e) {
          LOG.log(Level.WARNING, "accounting period with unreadable date skipped: {0}", date);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "accounting periods body unreadable: {0}", e.getMessage());
    }
    return out;
  }
}
