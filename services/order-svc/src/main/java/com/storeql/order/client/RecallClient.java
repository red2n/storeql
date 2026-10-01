package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.config.ServiceConfig;
import com.storeql.order.domain.StopSale.ActiveRecall;
import com.storeql.service.ServiceReader;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Sync client for inventory-svc's open recalls, {@code GET /admin/inventory/recalls/active}: the
 * same list the till keeps and checks each item against, read here so an order is checked too
 * (stop-sale at checkout). Golden rule #1 — recalls live there; rule #4 — a configured address
 * first, else the instance from Consul.
 *
 * <p>A staff read in inventory-svc, asked as a storekeeper with the order's tenant, as order-svc's
 * other reads of inventory-svc are; no store is named, because a recall belongs to the business. A
 * till sale replayed from the offline queue asks with {@code ?endedSince=} as well ({@link
 * #openOrEndedSince}), so a recall closed since the sale was rung up still judges it.
 *
 * <p><strong>Fail-open.</strong> An unreadable answer never refuses a sale: the till has already
 * checked the item against its own copy of the list, and a checkout that stopped whenever
 * inventory-svc was slow would close every shop in the business. The timeouts are short for the
 * same reason: a sale waits on this answer.
 */
@ApplicationScoped
public class RecallClient {

  private static final Logger LOG = System.getLogger(RecallClient.class.getName());
  private static final String INVENTORY_SERVICE = "inventory-svc";
  private static final String INTERNAL_ROLE = "STOREKEEPER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    // With discovery off and no address configured there is nobody to ask, and asking a Consul
    // that is not there would cost every sale a failed connection.
    registry =
        config.consulEnabled() ? new ConsulClient(config.consulHost(), config.consulPort()) : null;
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(1))
            .readTimeout(Duration.ofSeconds(2))
            .build();
  }

  /**
   * Every scope line of every open recall of the business.
   *
   * @return the lines, empty when none is open; an empty optional when inventory-svc could not be
   *     located, reached or read, and the sale then goes ahead unchecked here
   */
  @Retry(maxRetries = 1, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "unavailable")
  public Optional<List<ActiveRecall>> active(UUID tenantId) {
    return read(tenantId, null);
  }

  /**
   * Every scope line of every open recall of the business, and of every one closed or cancelled at
   * or after {@code endedSince}, each saying when it opened and, once ended, when and how. Asked
   * only for a till sale replayed from the offline queue: it is judged against every recall open
   * when it was rung up, one that has ended since included. The till's own list stays the open
   * ones.
   *
   * @return the lines; an empty optional when inventory-svc could not be located, reached or read
   */
  @Retry(maxRetries = 1, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "unavailableSince")
  public Optional<List<ActiveRecall>> openOrEndedSince(UUID tenantId, Instant endedSince) {
    return read(tenantId, endedSince);
  }

  private Optional<List<ActiveRecall>> read(UUID tenantId, Instant endedSince) {
    String base = locate().orElse(null);
    if (base == null) {
      LOG.log(
          Level.WARNING, "inventory-svc could not be located; recalls not read for {0}", tenantId);
      return Optional.empty();
    }
    HttpClientRequest request =
        webClient
            .get(base + "/admin/inventory/recalls/active")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE);
    if (endedSince != null) request = request.queryParam("endedSince", endedSince.toString());
    try (HttpClientResponse res = request.request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(Level.WARNING, "active recalls for {0}: HTTP {1}", tenantId, res.status().code());
        return Optional.empty();
      }
      return parse(body);
    }
  }

  /** A configured address first, else the instance from Consul when discovery is on. */
  private Optional<String> locate() {
    Optional<String> configured = ServiceReader.configuredUrl(INVENTORY_SERVICE);
    if (configured.isPresent() || registry == null) return configured;
    return registry.resolve(INVENTORY_SERVICE).map(ServiceInstance::baseUri);
  }

  /**
   * Reads the active list. A line that cannot be read makes the whole answer unreadable: a list
   * with a hole in it would pass the one item it could not read.
   */
  static Optional<List<ActiveRecall>> parse(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      List<ActiveRecall> out = new ArrayList<>();
      for (JsonValue v : root.getJsonArray("data")) {
        JsonObject o = v.asJsonObject();
        out.add(
            new ActiveRecall(
                Ids.parse(o.getString("recallId")),
                text(o, "reference"),
                text(o, "kind"),
                text(o, "hazard"),
                Ids.parse(o.getString("variantId")),
                text(o, "batchNo"),
                date(o, "expiryFrom"),
                date(o, "expiryTo"),
                instant(o, "openedAt"),
                instant(o, "endedAt"),
                text(o, "endedAs")));
      }
      return Optional.of(List.copyOf(out));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "unreadable list of active recalls: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  private static String text(JsonObject o, String key) {
    return !o.containsKey(key) || o.isNull(key) ? null : o.getString(key);
  }

  private static LocalDate date(JsonObject o, String key) {
    String v = text(o, key);
    return v == null || v.isBlank() ? null : LocalDate.parse(v.strip());
  }

  /**
   * When the recall opened, or ended. Absent or unreadable, the time is taken as not said: a recall
   * whose opening is not said counts as open before any sale, and one whose end is not said as not
   * yet ended — so a line is judged as it would be without the time, never let through for want of
   * it.
   */
  private static Instant instant(JsonObject o, String key) {
    String v = text(o, key);
    if (v == null || v.isBlank()) return null;
    try {
      return Instant.parse(v.strip());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<List<ActiveRecall>> unavailable(UUID tenantId) {
    LOG.log(Level.WARNING, "inventory-svc unavailable; recalls not read for {0}", tenantId);
    return Optional.empty();
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<List<ActiveRecall>> unavailableSince(UUID tenantId, Instant endedSince) {
    LOG.log(
        Level.WARNING, "inventory-svc unavailable; recalls ended since not read for {0}", tenantId);
    return Optional.empty();
  }
}
