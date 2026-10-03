package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.config.ServiceConfig;
import com.storeql.order.domain.TradeScales;
import com.storeql.web.ApiException;
import com.storeql.web.HttpHeaders;
import com.storeql.web.TenantContext;
import io.helidon.http.HeaderNames;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for tenant-svc fulfilment resolve (golden rule #1/#4). Used on DELIVERY orders to
 * pick the store that covers the delivery pincode.
 */
@ApplicationScoped
public class TenantClient {

  private static final Logger LOG = System.getLogger(TenantClient.class.getName());
  private static final String TENANT_SERVICE = "tenant-svc";

  /** The staff role a read made on a sale's behalf carries: a shopper's checkout holds none. */
  private static final String INTERNAL_ROLE = "STOREKEEPER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;
  private WebClient saleClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    // A sale waits on the scale register's answer and goes ahead without one, so it is not kept
    // waiting long.
    saleClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(1))
            .readTimeout(Duration.ofSeconds(2))
            .build();
  }

  /**
   * tenant-svc's address: {@code storeql.clients.tenant-svc.url} when that is set — for a
   * deployment without discovery and for integration tests — else what discovery resolves.
   *
   * <p>Every other client in this service already honours that override; this one did not, so
   * nothing here could be driven against a stub and the commission rating could not be tested at
   * all.
   */
  private Optional<String> locate() {
    return com.storeql.service.ServiceReader.configuredUrl(TENANT_SERVICE)
        .or(() -> registry.resolve(TENANT_SERVICE).map(ServiceInstance::baseUri));
  }

  public record ResolvedStore(UUID storeId, String storeName, String storeCode) {}

  /**
   * Resolves the fulfilling store for a delivery pincode. Empty when tenant-svc is unreachable and
   * the caller should keep the client-supplied store. Throws 404 when the pincode is not covered
   * (areas configured but no match).
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<ResolvedStore> resolveFulfilment(UUID tenantId, String pincode) {
    ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "tenant-svc not in discovery — keeping client storeId for DELIVERY");
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/fulfilment/resolve")
            .queryParam("pincode", pincode)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status == 200) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
          JsonObject data = reader.readObject().getJsonObject("data");
          return Optional.of(
              new ResolvedStore(
                  Ids.parse(data.getString("storeId")),
                  data.getString("storeName", ""),
                  data.getString("storeCode", "")));
        }
      }
      if (status == 404) {
        throw new ApiException(
            404,
            "FULFILMENT_AREA_NOT_COVERED",
            "No store delivers to pincode " + pincode,
            List.of());
      }
      LOG.log(Level.WARNING, "fulfilment resolve HTTP {0}: {1}", status, body);
      return Optional.empty();
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "tenant-svc circuit open — keeping client storeId for DELIVERY");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "fulfilment resolve failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /** The trading entity, as tenant-svc holds it: what a fiscal file's header names. */
  public record TenantIdentity(String legalName, String name, String country, String currency) {}

  /** A store's postal identity, as tenant-svc holds it: what a fiscal file's location names. */
  public record StoreIdentity(
      UUID id,
      String name,
      String code,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode) {}

  /**
   * The tenant's legal identity (18.5), read with the caller's own identity forwarded — a manager
   * exporting the register is a staff member, and {@code GET /admin/tenant} is a staff read.
   *
   * @return the identity, or empty when tenant-svc could not be reached or refused
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "tenantUnavailable")
  public Optional<TenantIdentity> tenant(UUID tenantId, TenantContext ctx) {
    ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
    if (instance == null) {
      return Optional.empty();
    }
    try (HttpClientResponse res =
        forward(webClient.get(instance.baseUri() + "/admin/tenant"), tenantId, ctx).request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(Level.WARNING, "tenant read HTTP {0}: {1}", res.status().code(), body);
        return Optional.empty();
      }
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject d = reader.readObject().getJsonObject("data");
        return Optional.of(
            new TenantIdentity(
                d.getString("legalName", null),
                d.getString("name", null),
                d.getString("country", null),
                d.getString("currency", null)));
      }
    }
  }

  /**
   * A store's postal identity (18.5), read with the caller's identity forwarded.
   *
   * @return the store, or empty when tenant-svc could not be reached, or the store is not this
   *     tenant's
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "storeUnavailable")
  public Optional<StoreIdentity> store(UUID tenantId, UUID storeId, TenantContext ctx) {
    ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
    if (instance == null) {
      return Optional.empty();
    }
    try (HttpClientResponse res =
        forward(webClient.get(instance.baseUri() + "/admin/stores/" + storeId), tenantId, ctx)
            .request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(Level.WARNING, "store read HTTP {0}: {1}", res.status().code(), body);
        return Optional.empty();
      }
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject d = reader.readObject().getJsonObject("data");
        return Optional.of(
            new StoreIdentity(
                storeId,
                d.getString("name", null),
                d.getString("code", null),
                d.getString("line1", null),
                d.getString("line2", null),
                d.getString("city", null),
                d.getString("state", null),
                d.getString("country", null),
                d.getString("pincode", null)));
      }
    }
  }

  // ── the weighing-instrument register (certified scales) ─────────────────────

  /**
   * One weighing instrument as the store's register in tenant-svc holds it: whether the register
   * holds it at that store, and whether it may weigh for trade today. Asked as a storekeeper with
   * the order's tenant, since a shopper's checkout could not read the register itself; the register
   * answers for an instrument of another store or another business as it answers for one nobody
   * registered.
   *
   * @return the register's entry, not registered when tenant-svc answers {@code
   *     INSTRUMENT_NOT_FOUND}; empty when the register could not be read, and the sale then goes
   *     ahead
   */
  @Retry(maxRetries = 1, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "instrumentUnavailable")
  public Optional<TradeScales.Entry> weighingInstrument(
      UUID tenantId, UUID storeId, UUID instrumentId) {
    String base = locate().orElse(null);
    if (base == null) {
      LOG.log(Level.WARNING, "tenant-svc not located; scale {0} not checked", instrumentId);
      return Optional.empty();
    }
    try (HttpClientResponse res =
        saleClient
            .get(base + "/admin/stores/" + storeId + "/weighing-instruments/" + instrumentId)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status == 200) return instrumentOf(instrumentId, storeId, body);
      if (status == 404 && "INSTRUMENT_NOT_FOUND".equals(errorCode(body))) {
        return Optional.of(TradeScales.Entry.notRegistered(instrumentId));
      }
      LOG.log(Level.WARNING, "weighing instrument {0}: HTTP {1}", instrumentId, status);
      return Optional.empty();
    }
  }

  /**
   * Reads the register's answer for one instrument. An answer for an instrument that stands in
   * another store is taken as not held here, whatever route gave it: the store is the point. When
   * its latest history entry was recorded, when that entry falls due and when the instrument was
   * last changed are read too, so a till sale replayed from an offline queue can be judged as at
   * the moment it was rung up ({@link TradeScales.Entry#atSale}).
   */
  static Optional<TradeScales.Entry> instrumentOf(UUID instrumentId, UUID storeId, String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject d = reader.readObject().getJsonObject("data");
      String at = text(d, "storeId");
      if (at != null && !storeId.equals(Ids.parse(at))) {
        return Optional.of(TradeScales.Entry.notRegistered(instrumentId));
      }
      JsonObject latest = latestOf(d);
      return Optional.of(
          new TradeScales.Entry(
              instrumentId,
              true,
              text(d, "identifier"),
              d.getBoolean("certified", false),
              text(d, "standing"),
              latest == null ? null : instant(latest, "recordedAt"),
              latest == null ? null : date(latest, "nextDue"),
              instant(d, "updatedAt")));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "unreadable scale {0}: {1}", instrumentId, e.getMessage());
      return Optional.empty();
    }
  }

  /** The register's latest history entry for an instrument, or null when it gave none it read. */
  private static JsonObject latestOf(JsonObject d) {
    JsonValue v = d.get("latestVerification");
    return v instanceof JsonObject latest ? latest : null;
  }

  /**
   * A time the register gave, read leniently: one that cannot be read is taken as not said, and a
   * replayed sale weighed on the scale is then flagged as one the register cannot show was fit —
   * never let through for want of it.
   */
  static Instant instant(JsonObject o, String key) {
    try {
      String v = text(o, key);
      return v == null ? null : Instant.parse(v.strip());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A date the register gave, read as leniently as {@link #instant}. */
  static LocalDate date(JsonObject o, String key) {
    try {
      String v = text(o, key);
      return v == null ? null : LocalDate.parse(v.strip());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A refusal's stable code: the problem's own member, else the legacy envelope's. */
  static String errorCode(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      String code = text(root, "code");
      if (code != null) return code;
      return root.containsKey("error") && !root.isNull("error")
          ? text(root.getJsonObject("error"), "code")
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<TradeScales.Entry> instrumentUnavailable(
      UUID tenantId, UUID storeId, UUID instrumentId) {
    LOG.log(Level.WARNING, "tenant-svc unavailable; scale {0} not checked", instrumentId);
    return Optional.empty();
  }

  // ── what a period of sales earns (store operations & workforce) ─────────────

  /** What one person sold on one day, as this service reads it out of its own orders. */
  public record SellerDay(
      java.time.LocalDate day, java.math.BigDecimal net, java.math.BigDecimal units) {}

  /** One rate band of a segment, as tenant-svc rated it. */
  public record RatedBand(
      java.math.BigDecimal thresholdFrom,
      java.math.BigDecimal rate,
      java.math.BigDecimal amountInBand,
      java.math.BigDecimal commission) {}

  /** A stretch of days under one arrangement, and what it earned. */
  public record RatedSegment(
      UUID schemeId,
      String schemeName,
      java.time.LocalDate from,
      java.time.LocalDate to,
      java.math.BigDecimal amount,
      List<RatedBand> bands,
      java.math.BigDecimal commission) {

    public RatedSegment {
      bands = bands == null ? List.of() : List.copyOf(bands);
    }
  }

  /** What one person's days earned. */
  public record RatedSeller(
      UUID userId, List<RatedSegment> segments, java.math.BigDecimal commission, String currency) {

    public RatedSeller {
      segments = segments == null ? List.of() : List.copyOf(segments);
    }
  }

  /**
   * Asks tenant-svc what these sales earn, under the arrangements it holds.
   *
   * <p>Figures go out and money comes back: no order and no return ever leaves this service, and
   * the commission rule lives once, beside the arrangement it belongs to. A second implementation
   * here would eventually disagree with the one the business agreed to, and somebody would be paid
   * on the wrong one.
   *
   * <p>Empty when tenant-svc could not be reached or refused — and the caller must then <b>refuse
   * to produce a statement</b> rather than produce one of zeros. A zero somebody signs off is worse
   * than an error somebody retries.
   */
  @Retry(maxRetries = 2, delay = 300)
  @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "ratesUnavailable")
  public Optional<List<RatedSeller>> rateCommission(
      UUID tenantId,
      TenantContext ctx,
      java.time.LocalDate from,
      java.time.LocalDate to,
      java.util.Map<UUID, List<SellerDay>> sellers) {
    String base = locate().orElse(null);
    if (base == null) {
      LOG.log(Level.WARNING, "tenant-svc could not be located — commission cannot be rated");
      return Optional.empty();
    }
    jakarta.json.JsonArrayBuilder people = Json.createArrayBuilder();
    for (java.util.Map.Entry<UUID, List<SellerDay>> seller : sellers.entrySet()) {
      jakarta.json.JsonArrayBuilder days = Json.createArrayBuilder();
      for (SellerDay d : seller.getValue()) {
        days.add(
            Json.createObjectBuilder()
                .add("day", d.day().toString())
                .add("net", d.net())
                .add("units", d.units()));
      }
      people.add(
          Json.createObjectBuilder().add("userId", seller.getKey().toString()).add("days", days));
    }
    String body =
        Json.createObjectBuilder()
            .add("from", from.toString())
            .add("to", to.toString())
            .add("sellers", people)
            .build()
            .toString();
    try (HttpClientResponse res =
        forward(webClient.post(base + "/admin/workforce/commission/rate"), tenantId, ctx)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body)) {
      int status = res.status().code();
      String answer = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "commission rating HTTP {0}: {1}", status, answer);
        return Optional.empty();
      }
      return Optional.of(rated(answer));
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "tenant-svc circuit open — commission cannot be rated");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "commission rating failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  private static List<RatedSeller> rated(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      jakarta.json.JsonArray data = reader.readObject().getJsonArray("data");
      List<RatedSeller> out = new java.util.ArrayList<>();
      for (jakarta.json.JsonValue v : data) {
        JsonObject o = v.asJsonObject();
        List<RatedSegment> segments = new java.util.ArrayList<>();
        for (jakarta.json.JsonValue sv : o.getJsonArray("segments")) {
          JsonObject s = sv.asJsonObject();
          List<RatedBand> bands = new java.util.ArrayList<>();
          for (jakarta.json.JsonValue bv : s.getJsonArray("bands")) {
            JsonObject b = bv.asJsonObject();
            bands.add(
                new RatedBand(
                    decimal(b, "thresholdFrom"),
                    decimal(b, "rate"),
                    decimal(b, "amountInBand"),
                    decimal(b, "commission")));
          }
          segments.add(
              new RatedSegment(
                  uuid(s, "schemeId"),
                  text(s, "schemeName"),
                  java.time.LocalDate.parse(s.getString("from")),
                  java.time.LocalDate.parse(s.getString("to")),
                  decimal(s, "amount"),
                  bands,
                  decimal(s, "commission")));
        }
        out.add(
            new RatedSeller(
                Ids.parse(o.getString("userId")),
                segments,
                decimal(o, "commission"),
                text(o, "currency")));
      }
      return List.copyOf(out);
    }
  }

  private static java.math.BigDecimal decimal(JsonObject o, String key) {
    String v = text(o, key);
    return v == null ? null : new java.math.BigDecimal(v);
  }

  private static String text(JsonObject o, String key) {
    return !o.containsKey(key) || o.isNull(key) ? null : o.getString(key);
  }

  private static UUID uuid(JsonObject o, String key) {
    String v = text(o, key);
    return v == null ? null : Ids.parse(v);
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<List<RatedSeller>> ratesUnavailable(
      UUID tenantId,
      TenantContext ctx,
      java.time.LocalDate from,
      java.time.LocalDate to,
      java.util.Map<UUID, List<SellerDay>> sellers) {
    LOG.log(Level.WARNING, "tenant-svc unavailable; commission not rated");
    return Optional.empty();
  }

  /**
   * What an arrangement pays in: its basis, and for a per-unit one the currency its amount is in.
   *
   * @param currency the ISO 4217 code a {@code PER_UNIT} amount is in; null for a percentage
   */
  public record SchemeTerms(String basis, String currency) {

    /** Whether the arrangement pays an amount per unit, in its own currency. */
    public boolean perUnit() {
      return "PER_UNIT".equals(basis);
    }
  }

  /**
   * Every arrangement the business has had — withdrawn and superseded versions too, since a period
   * may have been earned under any of them — with what each pays in.
   *
   * <p>A rating answer says which scheme a stretch of days was under but not what that scheme pays
   * in, and a seller's single {@code currency} names only the last per-unit scheme they were on:
   * this is what says, stretch by stretch, whether a commission is in the statement's currency or
   * has to be translated into it.
   *
   * @return the terms by scheme id, or empty when tenant-svc could not be reached or refused — the
   *     caller then refuses to state anything rather than guess a currency
   */
  @Retry(maxRetries = 2, delay = 300)
  @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "schemesUnavailable")
  public Optional<java.util.Map<UUID, SchemeTerms>> commissionSchemes(
      UUID tenantId, TenantContext ctx) {
    String base = locate().orElse(null);
    if (base == null) {
      LOG.log(Level.WARNING, "tenant-svc could not be located — commission schemes not read");
      return Optional.empty();
    }
    try (HttpClientResponse res =
        forward(webClient.get(base + "/admin/workforce/commission/schemes?all=true"), tenantId, ctx)
            .request()) {
      int status = res.status().code();
      String answer = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "commission schemes HTTP {0}: {1}", status, answer);
        return Optional.empty();
      }
      return Optional.of(schemeTerms(answer));
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "tenant-svc circuit open — commission schemes not read");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "commission schemes read failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /** tenant-svc's scheme list, as the terms each scheme pays in, by id. */
  static java.util.Map<UUID, SchemeTerms> schemeTerms(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      java.util.Map<UUID, SchemeTerms> out = new java.util.LinkedHashMap<>();
      for (JsonValue v : reader.readObject().getJsonArray("data")) {
        JsonObject o = v.asJsonObject();
        String currency = text(o, "currency");
        out.put(
            Ids.parse(o.getString("id")),
            new SchemeTerms(
                text(o, "basis"),
                currency == null || currency.isBlank()
                    ? null
                    : currency.strip().toUpperCase(java.util.Locale.ROOT)));
      }
      return java.util.Map.copyOf(out);
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<java.util.Map<UUID, SchemeTerms>> schemesUnavailable(UUID tenantId, TenantContext ctx) {
    LOG.log(Level.WARNING, "tenant-svc unavailable; commission schemes not read");
    return Optional.empty();
  }

  private static io.helidon.webclient.api.HttpClientRequest forward(
      io.helidon.webclient.api.HttpClientRequest req, UUID tenantId, TenantContext ctx) {
    io.helidon.webclient.api.HttpClientRequest out =
        req.header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), String.join(",", ctx.roles()));
    if (ctx.userId() != null) {
      out = out.header(HeaderNames.create(HttpHeaders.USER_ID), ctx.userId().toString());
    }
    return out;
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<TenantIdentity> tenantUnavailable(UUID tenantId, TenantContext ctx) {
    LOG.log(Level.WARNING, "tenant-svc unavailable; tenant identity not read");
    return Optional.empty();
  }

  @SuppressWarnings("unused")
  Optional<StoreIdentity> storeUnavailable(UUID tenantId, UUID storeId, TenantContext ctx) {
    LOG.log(Level.WARNING, "tenant-svc unavailable; store identity not read");
    return Optional.empty();
  }
}
