package com.storeql.test;

import com.sun.net.httpserver.HttpServer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for tenant-svc's {@code GET /admin/tenant}, {@code GET /admin/tenant/obligations},
 * {@code GET /admin/stores} and {@code GET /admin/stores/{id}} in a service's integration tests,
 * where discovery is off. Each tenant answers with the currency and country it was registered with;
 * an unregistered tenant is {@code 404}, so a test that forgets to register one sees the refusal a
 * real unknown tenant would get rather than a borrowed default.
 *
 * <p>Start it in the test's static initialiser, before Helidon boots: it points {@code
 * storeql.clients.tenant-svc.url} at itself.
 */
public final class TenantSvcStub implements AutoCloseable {

  private static final String STORES = "/admin/stores";

  private final HttpServer server;
  private final Map<String, String> profiles = new ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> obligations = new ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> cashLimits = new ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> depositSchemes = new ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> stores = new ConcurrentHashMap<>();
  private final java.util.Map<String, String> retention =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> fxRates = new ConcurrentHashMap<>();

  /** Percentage of net each business's people earn, for the commission rating route. */
  private final Map<String, String> commissionPercent = new ConcurrentHashMap<>();

  /** The last rating request received, so a test can assert what figures were sent. */
  private final java.util.concurrent.atomic.AtomicReference<String> lastRating =
      new java.util.concurrent.atomic.AtomicReference<>();

  /** When true the rating route fails, so a caller's fail-closed behaviour can be proven. */
  private final java.util.concurrent.atomic.AtomicBoolean ratingDown =
      new java.util.concurrent.atomic.AtomicBoolean();

  private final java.util.concurrent.atomic.AtomicInteger requests =
      new java.util.concurrent.atomic.AtomicInteger();

  private TenantSvcStub(HttpServer server) {
    this.server = server;
  }

  /** Starts the stub on a free local port and points the client property at it. */
  public static TenantSvcStub start() {
    HttpServer server = JsonStub.serve("tenant-svc-stub");
    TenantSvcStub stub = new TenantSvcStub(server);
    // What a plan allows the business (21.8, 21.11), as Entitlements reads it: the grants of the
    // limits given with withLimit, and an empty list — unrestricted — for a business given none.
    // The JDK server routes by the longest matching context, so this wins over /admin/tenant.
    server.createContext(
        "/admin/tenant/plan/limits",
        exchange -> {
          stub.requests.incrementAndGet();
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          java.util.Map<String, Long> limits =
              tenant == null
                  ? java.util.Map.of()
                  : stub.limits.getOrDefault(tenant, java.util.Map.of());
          StringBuilder grants = new StringBuilder();
          for (var e : limits.entrySet()) {
            if (grants.length() > 0) grants.append(',');
            grants
                .append("{\"key\":\"")
                .append(e.getKey())
                .append("\",\"limitValue\":")
                .append(e.getValue())
                .append('}');
          }
          JsonStub.reply(exchange, 200, "{\"data\":{\"grants\":[" + grants + "]}}");
        });
    server.createContext(
        "/admin/tenant",
        exchange -> {
          stub.requests.incrementAndGet();
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          String body = tenant == null ? null : stub.profiles.get(tenant);
          JsonStub.reply(
              exchange,
              body == null ? 404 : 200,
              body == null
                  ? "{\"error\":{\"code\":\"TENANT_NOT_FOUND\",\"message\":\"no such tenant\"}}"
                  : body);
        });
    // Which business holds an e-invoicing address (07.13, the transport seam): tenant-svc's
    // platform-wide lookup, answered from the identities registered here. By scheme and id, else
    // by VAT number; 404 when none holds it, 409 when more than one does.
    // What a period of sales earns, as tenant-svc answers it (store operations & workforce). A flat
    // percentage per business here: the marginal-band rule is tenant-svc's own to prove, and what a
    // caller's test needs is a deterministic answer and the figures it was asked about.
    server.createContext(
        "/admin/workforce/commission/rate",
        exchange -> {
          stub.requests.incrementAndGet();
          String body = JsonStub.body(exchange);
          stub.lastRating.set(body);
          if (stub.ratingDown.get()) {
            JsonStub.reply(exchange, 503, "{\"error\":{\"code\":\"DOWN\",\"message\":\"no\"}}");
            return;
          }
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          String percent = tenant == null ? null : stub.commissionPercent.get(tenant);
          JsonStub.reply(exchange, 200, rated(body, percent));
        });
    server.createContext(
        "/platform/tenants/by-einvoice-address",
        exchange -> {
          stub.requests.incrementAndGet();
          Map<String, String> q = JsonStub.query(exchange.getRequestURI().getRawQuery());
          String scheme = q.get("scheme");
          String id = q.get("id");
          String vat = q.get("vatNumber");
          java.util.List<String> holders =
              stub.profiles.values().stream()
                  .filter(
                      json ->
                          scheme != null
                              ? holds(json, "einvoiceScheme", scheme)
                                  && holds(json, "einvoiceId", id)
                              : vat != null && holds(json, "vatNumber", vat))
                  .toList();
          if (holders.size() == 1) {
            JsonStub.reply(exchange, 200, holders.get(0));
          } else if (holders.isEmpty()) {
            JsonStub.reply(
                exchange,
                404,
                "{\"error\":{\"code\":\"TENANT_NOT_FOUND\",\"message\":\"nobody holds it\"}}");
          } else {
            JsonStub.reply(
                exchange,
                409,
                "{\"error\":{\"code\":\"TENANT_EINVOICE_ADDRESS_SHARED\",\"message\":\""
                    + holders.size()
                    + " businesses hold it\"}}");
          }
        });
    // The longer context wins, so the obligations route is not answered as a profile.
    server.createContext(
        "/admin/tenant/obligations",
        exchange -> {
          stub.requests.incrementAndGet();
          String query = exchange.getRequestURI().getRawQuery();
          String country = "";
          for (String pair : query == null ? new String[0] : query.split("&")) {
            if (pair.startsWith("country=")) country = pair.substring(8);
          }
          JsonStub.reply(
              exchange,
              200,
              "{\"data\":{\"country\":\""
                  + country
                  + "\",\"obligations\":["
                  + String.join(",", stub.obligations.getOrDefault(country, java.util.List.of()))
                  + "],\"cashLimits\":["
                  + String.join(",", stub.cashLimits.getOrDefault(country, java.util.List.of()))
                  + "],\"depositSchemes\":["
                  + String.join(",", stub.depositSchemes.getOrDefault(country, java.util.List.of()))
                  + "]}}");
        });
    // A tenant's exchange rates (03.x): the home currency from its profile and the rates given
    // with withFxRate; a tenant with none keeps only its home currency.
    server.createContext(
        "/admin/tenant/fx-rates",
        exchange -> {
          stub.requests.incrementAndGet();
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          String profile = tenant == null ? null : stub.profiles.get(tenant);
          if (profile == null) {
            JsonStub.reply(
                exchange,
                404,
                "{\"error\":{\"code\":\"TENANT_NOT_FOUND\",\"message\":\"no such tenant\"}}");
            return;
          }
          java.util.regex.Matcher m =
              java.util.regex.Pattern.compile("\"currency\":\"([A-Z]{3})\"").matcher(profile);
          String home = m.find() ? m.group(1) : "GBP";
          JsonStub.reply(
              exchange,
              200,
              "{\"data\":{\"home\":\""
                  + home
                  + "\",\"rates\":["
                  + String.join(",", stub.fxRates.getOrDefault(tenant, java.util.List.of()))
                  + "]}}");
        });
    // A tenant's retention schedule (21.16), as registered; a tenant with none has an empty one.
    server.createContext(
        "/admin/tenant/retention",
        exchange -> {
          stub.requests.incrementAndGet();
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          String body = tenant == null ? null : stub.retention.get(tenant);
          JsonStub.reply(
              exchange,
              200,
              body != null
                  ? body
                  : "{\"data\":{\"country\":\"GB\",\"countries\":[\"GB\"],\"classes\":[],"
                      + "\"holds\":[]}}");
        });
    // A tenant's stores, one page; a tenant with none registered has none. Under it, one of them
    // by id, and 404 for a store that is not the tenant's.
    server.createContext(
        STORES,
        exchange -> {
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          java.util.List<String> own =
              tenant == null
                  ? java.util.List.of()
                  : stub.stores.getOrDefault(tenant, java.util.List.of());
          String rest = exchange.getRequestURI().getPath().substring(STORES.length());
          if (rest.length() > 1) {
            String opening = "{\"id\":\"" + rest.substring(1) + "\"";
            String found = own.stream().filter(s -> s.startsWith(opening)).findFirst().orElse(null);
            JsonStub.reply(
                exchange,
                found == null ? 404 : 200,
                found == null
                    ? "{\"error\":{\"code\":\"TENANT_STORE_NOT_FOUND\",\"message\":\"no such store\"}}"
                    : "{\"data\":" + found + "}");
            return;
          }
          JsonStub.reply(
              exchange,
              200,
              "{\"data\":[" + String.join(",", own) + "],\"meta\":{\"nextCursor\":null}}");
        });
    server.start();
    System.setProperty("storeql.clients.tenant-svc.url", JsonStub.baseOf(server));
    return stub;
  }

  /** Registers a tenant's declared currency and country. */
  private final java.util.Map<String, java.util.Map<String, Long>> limits =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * A limit the business's plan carries (21.8, 21.11): {@code stores.max}, {@code images.mb.max}… A
   * business given none is unrestricted, as a business on no plan is.
   */
  public TenantSvcStub withLimit(String tenantId, String key, long value) {
    limits
        .computeIfAbsent(tenantId, k -> new java.util.concurrent.ConcurrentHashMap<>())
        .put(key, value);
    return this;
  }

  public TenantSvcStub with(String tenantId, String currency, String country) {
    profiles.put(
        tenantId,
        "{\"data\":{\"id\":\""
            + tenantId
            + "\",\"currency\":\""
            + currency
            + "\",\"country\":\""
            + country
            + "\",\"status\":\"ACTIVE\"}}");
    return this;
  }

  /**
   * Gives a registered tenant the e-invoicing identity {@code GET /admin/tenant} returns: its VAT
   * identifier and its Peppol participant identifier. Nulls leave a field out.
   */
  public TenantSvcStub withIdentity(
      String tenantId, String vatNumber, String einvoiceScheme, String einvoiceId) {
    return extend(
        tenantId,
        field("vatNumber", vatNumber)
            + field("einvoiceScheme", einvoiceScheme)
            + field("einvoiceId", einvoiceId));
  }

  /** Gives a registered tenant the legal name {@code GET /admin/tenant} returns (18.9). */
  public TenantSvcStub withLegalName(String tenantId, String legalName) {
    return extend(tenantId, field("legalName", legalName));
  }

  private TenantSvcStub extend(String tenantId, String fields) {
    profiles.computeIfPresent(
        tenantId, (id, json) -> json.substring(0, json.length() - 2) + fields + "}}");
    return this;
  }

  /** Whether a registered profile carries the field with the value, case aside. */
  private static boolean holds(String json, String field, String value) {
    return value != null
        && json.toLowerCase(java.util.Locale.ROOT)
            .contains(("\"" + field + "\":\"" + value + "\"").toLowerCase(java.util.Locale.ROOT));
  }

  private static String field(String name, String value) {
    return value == null ? "" : ",\"" + name + "\":\"" + value + "\"";
  }

  /**
   * Registers a deposit return scheme that reaches a country (09.16), as tenant-svc's {@code GET
   * /admin/tenant/obligations} lists it under {@code depositSchemes}.
   *
   * @param materials comma-separated, e.g. {@code PET,ALUMINIUM,STEEL}
   * @param vatTreatment OUTSIDE_SCOPE or STANDARD
   */
  public TenantSvcStub withDepositScheme(
      String country,
      String scope,
      String currency,
      String depositEach,
      String materials,
      int minVolumeMl,
      int maxVolumeMl,
      String vatTreatment,
      String effectiveFrom,
      String citation) {
    StringBuilder mats = new StringBuilder();
    for (String m : materials.split(",")) {
      if (mats.length() > 0) mats.append(',');
      mats.append('"').append(m.trim()).append('"');
    }
    depositSchemes
        .computeIfAbsent(country, c -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"scope\":\""
                + scope
                + "\",\"currency\":\""
                + currency
                + "\",\"depositEach\":"
                + depositEach
                + ",\"materials\":["
                + mats
                + "],\"minVolumeMl\":"
                + minVolumeMl
                + ",\"maxVolumeMl\":"
                + maxVolumeMl
                + ",\"vatTreatment\":\""
                + vatTreatment
                + "\",\"effectiveFrom\":\""
                + effectiveFrom
                + "\",\"citation\":\""
                + citation
                + "\",\"summary\":\"stubbed\",\"status\":\"IN_FORCE\"}");
    return this;
  }

  /**
   * Registers a cash payment limit that reaches a country (09.17), as tenant-svc's {@code GET
   * /admin/tenant/obligations} lists it under {@code cashLimits}.
   *
   * @param effectiveTo the last day it applies, or null while it still does
   */
  public TenantSvcStub withCashLimit(
      String country,
      String scope,
      String currency,
      String fromAmount,
      String effectiveFrom,
      String effectiveTo,
      String citation) {
    cashLimits
        .computeIfAbsent(country, c -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"scope\":\""
                + scope
                + "\",\"currency\":\""
                + currency
                + "\",\"fromAmount\":"
                + fromAmount
                + ",\"effectiveFrom\":\""
                + effectiveFrom
                + "\""
                + (effectiveTo == null ? "" : ",\"effectiveTo\":\"" + effectiveTo + "\"")
                + ",\"citation\":\""
                + citation
                + "\",\"summary\":\"stubbed\",\"status\":\"IN_FORCE\"}");
    return this;
  }

  /**
   * Registers an obligation that reaches a country, as tenant-svc's {@code GET
   * /admin/tenant/obligations} lists it.
   *
   * @param effectiveTo the last day it applies, or null while it still does
   */
  public TenantSvcStub withObligation(
      String country, String code, String scope, String effectiveFrom, String effectiveTo) {
    obligations
        .computeIfAbsent(country, c -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"code\":\""
                + code
                + "\",\"scope\":\""
                + scope
                + "\",\"effectiveFrom\":\""
                + effectiveFrom
                + "\""
                + (effectiveTo == null ? "" : ",\"effectiveTo\":\"" + effectiveTo + "\"")
                + "}");
    return this;
  }

  /**
   * Registers a tenant's retention schedule (21.16), as tenant-svc's {@code GET
   * /admin/tenant/retention} answers it.
   *
   * @param periods the period set per class, e.g. {@code "ORDER_PERSONAL_DATA", 0}; a class not
   *     named has no period, so nothing of it is purged
   * @param holds hold objects as the sheet lists them, e.g. {@code
   *     {"subjectKind":"CUSTOMER","subjectId":"…"}}
   */
  /**
   * Gives a registered tenant an exchange rate (03.x): {@code rate} home units per one unit of
   * {@code currency}, as tenant-svc's {@code GET /admin/tenant/fx-rates} would list it.
   */
  public TenantSvcStub withFxRate(String tenantId, String currency, String rate) {
    fxRates
        .computeIfAbsent(tenantId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"currency\":\""
                + currency
                + "\",\"rate\":"
                + rate
                + ",\"effectiveFrom\":\"2026-01-01\"}");
    return this;
  }

  public TenantSvcStub withRetention(
      String tenantId, java.util.Map<String, Integer> periods, java.util.List<String> holds) {
    StringBuilder classes = new StringBuilder();
    for (var e : periods.entrySet()) {
      if (classes.length() > 0) classes.append(',');
      classes
          .append("{\"code\":\"")
          .append(e.getKey())
          .append("\",\"periodDays\":")
          .append(e.getValue())
          .append('}');
    }
    retention.put(
        tenantId,
        "{\"data\":{\"country\":\"GB\",\"countries\":[\"GB\"],\"classes\":["
            + classes
            + "],\"holds\":["
            + String.join(",", holds)
            + "]}}");
    return this;
  }

  /**
   * Registers one of a tenant's stores, as tenant-svc's {@code GET /admin/stores} lists it.
   *
   * @param country the store's country, or null when it records none
   */
  public TenantSvcStub withStore(String tenantId, String storeId, String country) {
    return withStore(tenantId, storeId, country, null, null, null);
  }

  /**
   * Registers one of a tenant's stores with the postal address an invoice prints (18.9). Nulls
   * leave a field out.
   */
  public TenantSvcStub withStore(
      String tenantId, String storeId, String country, String line1, String city, String pincode) {
    stores
        .computeIfAbsent(tenantId, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"id\":\""
                + storeId
                + "\",\"country\":"
                + (country == null ? "null" : "\"" + country + "\"")
                + field("line1", line1)
                + field("city", city)
                + field("pincode", pincode)
                + "}");
    return this;
  }

  /**
   * Registers one of a tenant's warehouses (type WAREHOUSE), as tenant-svc's {@code GET
   * /admin/stores} lists it: a stock-only site that serves shops (depot / DC replenishment).
   */
  public TenantSvcStub withWarehouse(String tenantId, String storeId) {
    stores
        .computeIfAbsent(tenantId, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add("{\"id\":\"" + storeId + "\",\"country\":null,\"type\":\"WAREHOUSE\"}");
    return this;
  }

  /**
   * Registers one of a tenant's shops (type STORE) with its coordinates, as tenant-svc's {@code GET
   * /admin/stores} lists it: where an online order is routed from (order orchestration).
   */
  public TenantSvcStub withStoreAt(String tenantId, String storeId, double lat, double lng) {
    stores
        .computeIfAbsent(tenantId, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"id\":\""
                + storeId
                + "\",\"country\":\"GB\",\"type\":\"STORE\",\"geoLat\":"
                + lat
                + ",\"geoLng\":"
                + lng
                + "}");
    return this;
  }

  /**
   * Registers one of a tenant's dark stores (type DARK_STORE) with its coordinates, as tenant-svc's
   * {@code GET /admin/stores} lists it: a shop with no shop floor that fills online orders for
   * delivery only (ship-from-store and dark-store picking).
   */
  public TenantSvcStub withDarkStore(String tenantId, String storeId, double lat, double lng) {
    stores
        .computeIfAbsent(tenantId, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"id\":\""
                + storeId
                + "\",\"country\":\"GB\",\"type\":\"DARK_STORE\",\"geoLat\":"
                + lat
                + ",\"geoLng\":"
                + lng
                + "}");
    return this;
  }

  /** How many profile reads have reached the stub, to show a cache holding. */
  public int requests() {
    return requests.get();
  }

  /** Puts every one of a business's people on a flat percentage of net, for the rating route. */
  public TenantSvcStub withCommission(String tenantId, String percentOfNet) {
    commissionPercent.put(tenantId, percentOfNet);
    return this;
  }

  /** The body of the last rating call, so a test can assert which days and figures were sent. */
  public String lastRating() {
    return lastRating.get();
  }

  /**
   * Makes the rating route fail, so a caller that must refuse rather than pay zeros can be proven.
   */
  public TenantSvcStub ratingDown(boolean down) {
    ratingDown.set(down);
    return this;
  }

  /**
   * Rates a request as one segment per person at a flat percentage.
   *
   * <p>Deliberately simple, and deliberately not a second implementation of the marginal bands:
   * those are tenant-svc's rule and tenant-svc's tests. What a caller needs from a stub is an
   * answer it can predict.
   */
  private static String rated(String body, String percentOfNet) {
    StringBuilder out = new StringBuilder("{\"data\":[");
    java.util.regex.Matcher sellers =
        java.util.regex.Pattern.compile("\\{\"userId\":\"([0-9a-fA-F-]+)\",\"days\":\\[(.*?)\\]\\}")
            .matcher(body == null ? "" : body);
    boolean first = true;
    while (sellers.find()) {
      String userId = sellers.group(1);
      String days = sellers.group(2);
      java.math.BigDecimal net = java.math.BigDecimal.ZERO;
      String from = null;
      String to = null;
      java.util.regex.Matcher day =
          java.util.regex.Pattern.compile("\"day\":\"([0-9-]+)\",\"net\":(-?[0-9.]+)")
              .matcher(days);
      while (day.find()) {
        if (from == null) from = day.group(1);
        to = day.group(1);
        net = net.add(new java.math.BigDecimal(day.group(2)));
      }
      if (from == null) continue;
      java.math.BigDecimal rate =
          percentOfNet == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(percentOfNet);
      java.math.BigDecimal commission =
          net.signum() <= 0
              ? java.math.BigDecimal.ZERO.setScale(2)
              : net.multiply(rate)
                  .divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
      if (!first) out.append(',');
      first = false;
      out.append("{\"userId\":\"").append(userId).append("\",\"segments\":[");
      out.append("{\"schemeId\":")
          .append(percentOfNet == null ? "null" : "\"" + STUB_SCHEME + "\"")
          .append(",\"schemeName\":")
          .append(percentOfNet == null ? "null" : "\"stub scheme\"")
          .append(",\"from\":\"")
          .append(from)
          .append("\",\"to\":\"")
          .append(to)
          .append("\",\"amount\":\"")
          .append(net.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
          .append("\",\"bands\":[");
      if (percentOfNet != null && net.signum() > 0) {
        out.append("{\"thresholdFrom\":\"0.00\",\"rate\":\"")
            .append(rate.toPlainString())
            .append("\",\"amountInBand\":\"")
            .append(net.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
            .append("\",\"commission\":\"")
            .append(commission.toPlainString())
            .append("\"}");
      }
      out.append("],\"commission\":\"")
          .append(commission.toPlainString())
          .append("\"}],\"commission\":\"")
          .append(commission.toPlainString())
          .append("\"}");
    }
    return out.append("]}").toString();
  }

  /** The scheme id this stub claims to have rated under. */
  public static final String STUB_SCHEME = "01900000-0000-7000-8000-000000000001";

  @Override
  public void close() {
    server.stop(0);
  }
}
