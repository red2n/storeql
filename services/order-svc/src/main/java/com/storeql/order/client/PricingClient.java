package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.config.ServiceConfig;
import com.storeql.service.ServiceReader;
import com.storeql.web.ApiException;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for pricing-svc's {@code POST /prices/resolve} (golden rule #1: foreign data comes
 * from the owning service, never its tables; rule #4: the instance is resolved via Consul, not a
 * hardcoded host:port).
 *
 * <p>Fail-closed: when price enforcement is on and pricing-svc is unreachable or has no price for
 * the variant, order placement is rejected — a sale at a client-chosen price is worse than a lost
 * sale.
 */
@ApplicationScoped
public class PricingClient {

  private static final System.Logger LOG = System.getLogger(PricingClient.class.getName());

  /**
   * Stamped on the two calls added with the promotion engine, for the reason SJ-D13 established: an
   * internal call carrying no principal is one routing mistake away from being an impersonation, so
   * it names a role rather than relying on a bypass.
   *
   * <p>Deliberately <em>not</em> added to the filter's open-mutation allowlist the way {@code
   * /prices/resolve*} was. Quoting only reveals a shopper's own basket, but {@code
   * /prices/redemptions} spends a coupon — and an endpoint that spends money should not be open
   * merely because the one beside it could be.
   */
  private static final String INTERNAL_ROLE = "CASHIER";

  private static final String PRICING_SERVICE = "pricing-svc";

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
   * pricing-svc's address: {@code storeql.clients.pricing-svc.url} when that is set, for a
   * deployment without discovery and for integration tests, else what discovery resolves.
   */
  private Optional<String> locate() {
    return ServiceReader.configuredUrl(PRICING_SERVICE)
        .or(() -> registry.resolve(PRICING_SERVICE).map(ServiceInstance::baseUri));
  }

  /**
   * The pricing-svc-resolved figures for one order line: {@code unitPrice} already has any active
   * promotion discount applied, and {@code vatAmount} is the per-unit tax pricing-svc computed from
   * the variant's VAT category. Both are authoritative — never overridden by client input when
   * price enforcement is on.
   */
  public record ResolvedLine(BigDecimal unitPrice, BigDecimal vatAmount) {}

  /**
   * Returns the effective unit price and VAT for one order line, as decided by pricing-svc (price
   * list + active promotions + VAT rate). Throws 422 when no price is configured, 503 when
   * pricing-svc cannot be reached.
   *
   * <p>{@code @Retry}: up to 2 retries on transient network errors; aborts immediately on {@link
   * ApiException} (a valid error response from pricing-svc — retrying a 404 is pointless).
   * {@code @CircuitBreaker}: trips after 60 % failures in a 5-request window; stays open for 5 s so
   * a dead pricing-svc doesn't cause every checkout to block for 5 s before failing. {@link
   * CircuitBreakerOpenException} is caught below and mapped to 503.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(
      requestVolumeThreshold = 5,
      failureRatio = 0.6,
      delay = 5000,
      // A refusal is an answer, not a failure: four stickers refused in a row must not open the
      // breaker and turn the fifth cashier's honest question into a 503.
      skipOn = {ApiException.class})
  public ResolvedLine resolveLine(
      UUID tenantId, UUID variantId, UUID storeId, String channel, BigDecimal qty) {
    String pricingBase =
        locate()
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonObjectBuilder payload = Json.createObjectBuilder().add("variantId", variantId.toString());
    if (storeId != null) payload.add("storeId", storeId.toString());
    if (channel != null) payload.add("channel", channel);
    if (qty != null) payload.add("qty", qty);

    try (HttpClientResponse res =
        webClient
            .post(pricingBase + "/prices/resolve")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload.build().toString())) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for variant " + variantId);
      }
      String body = res.as(String.class);
      var refused = relayedRefusal(status, body);
      if (refused.isPresent()) throw refused.get();
      if (status != 200) {
        throw unavailable("pricing-svc returned HTTP " + status, null);
      }
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        BigDecimal unitPrice = data.getJsonNumber("unitPrice").bigDecimalValue();
        BigDecimal vatAmount =
            data.containsKey("vatAmount") && !data.isNull("vatAmount")
                ? data.getJsonNumber("vatAmount").bigDecimalValue()
                : BigDecimal.ZERO;
        return new ResolvedLine(unitPrice, vatAmount);
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc unreachable", e);
    }
  }

  /**
   * One line to resolve in a {@link #resolveLines} batch call.
   *
   * @param markdownId the reduce-to-clear markdown a scanned sticker named (05.4), or null
   */
  public record LineRequest(UUID variantId, BigDecimal qty, UUID markdownId) {
    public LineRequest(UUID variantId, BigDecimal qty) {
      this(variantId, qty, null);
    }
  }

  /**
   * One promotion that pricing-svc applied to a basket.
   *
   * @param variantId the line it came off, or null for a whole-basket promotion
   */
  public record AppliedPromotion(
      UUID promotionId, String name, UUID variantId, BigDecimal amount) {}

  /**
   * A basket priced by pricing-svc: net line prices with line-level promotions already in them,
   * plus the whole-basket discount that belongs to no single line.
   *
   * @param lines per-line net unit price and VAT, in request order
   * @param basketDiscount what the basket-level rules took off the order as a whole
   * @param applied every promotion that took money off, for the receipt and the redemption ledger
   * @param rejectedCoupons codes the customer presented that did not apply, and why
   */
  /**
   * One line of a quoted basket.
   *
   * <p>Deliberately <em>not</em> a {@link ResolvedLine}. That record's {@code vatAmount} is per
   * unit, because {@code /prices/resolve-batch} prices a single unit and the caller multiplies out;
   * a quote prices the whole line and returns the line's VAT. Reusing the record made the two
   * meanings indistinguishable at the call site, and checkout multiplied a line total by the
   * quantity a second time — £144 of VAT on an £80 basket (SJ-D20).
   *
   * @param unitPrice net of <em>line-level</em> promotions only, per unit. The basket-level
   *     discount is excluded on purpose: it is returned separately and subtracted once by the
   *     caller, and folding it in here charged it twice.
   * @param lineVat VAT for the whole line, already multiplied out
   */
  /**
   * One line as pricing-svc quoted it.
   *
   * @param unitPrice the line's value after line-level promotions, divided by the quantity and
   *     rounded — stored on the order for display and refunds
   * @param lineNet the line's value after line-level promotions, as pricing-svc computed it.
   *     Carried rather than re-derived, because {@code unitPrice × qty} does not reproduce it:
   *     three units of a £100 line give a unit price of 33.33 and multiply back to 99.99. The
   *     order's subtotal must agree with the quote it was built from, so this is the figure that
   *     counts
   * @param lineVat VAT for the whole line, already multiplied out (SJ-D20)
   */
  public record QuotedLine(
      BigDecimal unitPrice,
      BigDecimal lineNet,
      BigDecimal lineVat,
      /** The VAT code the quote applied; null from a pricing-svc that did not say (18.9). */
      String vatCode,
      /** The rate it applied, as a fraction; null from a pricing-svc that did not say (18.9). */
      BigDecimal vatRate) {

    public QuotedLine(BigDecimal unitPrice, BigDecimal lineNet, BigDecimal lineVat) {
      this(unitPrice, lineNet, lineVat, null, null);
    }
  }

  public record QuotedBasket(
      List<QuotedLine> lines,
      BigDecimal basketDiscount,
      List<AppliedPromotion> applied,
      java.util.Map<String, String> rejectedCoupons) {
    public QuotedBasket {
      lines = List.copyOf(lines);
      applied = List.copyOf(applied);
      rejectedCoupons = java.util.Map.copyOf(rejectedCoupons);
    }
  }

  /**
   * Prices a whole basket through {@code POST /prices/quote}.
   *
   * <p>Distinct from {@link #resolveLines}, which prices each line independently: a spend
   * threshold, a basket percentage and a buy-one-get-one all need the order total to exist before
   * they mean anything, and the per-line form gave them nothing to be about. Checkout uses this;
   * {@code resolveLines} stays for callers that genuinely are pricing one line at a time.
   *
   * <p>Fails closed exactly as {@code resolveLines} does — an unpriceable line is 422, an
   * unreachable pricing-svc is 503 — because a checkout that silently prices at zero is worse than
   * one that refuses.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(
      requestVolumeThreshold = 5,
      failureRatio = 0.6,
      delay = 5000,
      // A refusal is an answer, not a failure: four stickers refused in a row must not open the
      // breaker and turn the fifth cashier's honest question into a 503.
      skipOn = {ApiException.class})
  public QuotedBasket quoteBasket(
      UUID tenantId,
      List<LineRequest> lines,
      UUID storeId,
      String channel,
      UUID customerId,
      List<String> couponCodes) {
    if (lines.isEmpty())
      return new QuotedBasket(List.of(), BigDecimal.ZERO, List.of(), java.util.Map.of());
    String pricingBase =
        locate()
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonArrayBuilder linesArray = Json.createArrayBuilder();
    for (LineRequest l : lines) {
      JsonObjectBuilder lineObj =
          Json.createObjectBuilder().add("variantId", l.variantId().toString());
      if (l.qty() != null) lineObj.add("qty", l.qty());
      if (l.markdownId() != null) lineObj.add("markdownId", l.markdownId().toString());
      linesArray.add(lineObj);
    }
    JsonObjectBuilder body = Json.createObjectBuilder().add("lines", linesArray);
    if (storeId != null) body.add("storeId", storeId.toString());
    if (channel != null) body.add("channel", channel);
    if (customerId != null) body.add("customerId", customerId.toString());
    if (couponCodes != null && !couponCodes.isEmpty()) {
      JsonArrayBuilder codes = Json.createArrayBuilder();
      for (String c : couponCodes) if (c != null && !c.isBlank()) codes.add(c);
      body.add("couponCodes", codes);
    }

    try (HttpClientResponse res =
        webClient
            .post(pricingBase + "/prices/quote")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body.build().toString())) {
      int status = res.status().code();
      String payload = res.as(String.class);
      if (status == 404) {
        // A markdown the basket names that pricing-svc does not know is the till's mistake, not
        // a missing price; it is told so rather than being handed the generic price failure.
        String code = errorCode(payload);
        if (code != null && code.startsWith("PRICING_MARKDOWN")) {
          throw ApiException.notFound(code, errorMessage(payload));
        }
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for one or more order lines");
      }
      // The basket itself was refused — a sticker for another product, a sticker with no packs
      // left, no VAT rate to charge (05.4, SJ-D56) — and the cashier needs the reason, not a 503.
      var refused = relayedRefusal(status, payload);
      if (refused.isPresent()) throw refused.get();
      if (status != 200) throw unavailable("pricing-svc returned HTTP " + status, null);
      try (JsonReader reader = Json.createReader(new StringReader(payload))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        return parseQuote(data);
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc call failed", e);
    }
  }

  /**
   * Parsing lives in its own method so it can be tested without a web client behind service
   * discovery and a circuit breaker — which is exactly why SJ-D14 went unnoticed: the only two
   * sites in the codebase that parsed a DTO inline were the two that were wrong.
   *
   * <p>Every optional field is read with the {@code containsKey && !isNull} form rather than {@code
   * isNull} alone, because JSON-B omits a null field entirely and {@code isNull} throws on an
   * absent key. That is SJ-D14 exactly, and it 503'd every guest checkout for months.
   */
  static QuotedBasket parseQuote(JsonObject data) {
    JsonArray lineArray = data.getJsonArray("lines");
    List<QuotedLine> lines = new ArrayList<>(lineArray.size());
    for (var l : lineArray) {
      JsonObject o = l.asJsonObject();
      BigDecimal qty = num(o, "qty", BigDecimal.ONE);
      // lineTotal minus the LINE-level discount, not netTotal: netTotal already has this line's
      // share of the basket-level discount taken off it, and the caller subtracts that separately.
      // Reading netTotal here charged the customer the basket discount twice (SJ-D20).
      BigDecimal afterLineDiscount =
          num(o, "lineTotal", BigDecimal.ZERO).subtract(num(o, "discount", BigDecimal.ZERO));
      BigDecimal unit =
          qty.signum() == 0
              ? BigDecimal.ZERO
              : afterLineDiscount.divide(qty, 2, java.math.RoundingMode.HALF_UP);
      lines.add(
          new QuotedLine(
              unit,
              afterLineDiscount,
              num(o, "vatAmount", BigDecimal.ZERO),
              o.containsKey("vatCode") && !o.isNull("vatCode") ? o.getString("vatCode") : null,
              num(o, "vatRate", null)));
    }

    List<AppliedPromotion> applied = new ArrayList<>();
    if (data.containsKey("appliedPromotions") && !data.isNull("appliedPromotions")) {
      for (var a : data.getJsonArray("appliedPromotions")) {
        JsonObject o = a.asJsonObject();
        applied.add(
            new AppliedPromotion(
                Ids.parse(o.getString("promotionId")),
                o.containsKey("name") && !o.isNull("name") ? o.getString("name") : "",
                o.containsKey("variantId") && !o.isNull("variantId")
                    ? Ids.parse(o.getString("variantId"))
                    : null,
                num(o, "amount", BigDecimal.ZERO)));
      }
    }

    java.util.Map<String, String> rejected = new java.util.LinkedHashMap<>();
    if (data.containsKey("rejectedCoupons") && !data.isNull("rejectedCoupons")) {
      JsonObject r = data.getJsonObject("rejectedCoupons");
      for (String k : r.keySet()) rejected.put(k, r.getString(k));
    }

    return new QuotedBasket(lines, num(data, "basketDiscount", BigDecimal.ZERO), applied, rejected);
  }

  /**
   * Tells pricing-svc that an order used these promotions, so their usage caps are spent.
   *
   * <p><b>Quietly</b>, and that is the important word. The order is already placed and, at a till,
   * already paid for. A customer must not lose their order because a redemption counter could not
   * be written — so this logs and returns rather than throwing. The write is idempotent on the
   * order, so the worst case of a lost call is an uncounted redemption, which is recoverable;
   * failing the checkout is not.
   */
  public void recordRedemptionsQuietly(
      UUID tenantId,
      UUID orderId,
      UUID customerId,
      List<AppliedPromotion> applied,
      String currency) {
    try {
      String pricingBase = locate().orElse(null);
      if (pricingBase == null) {
        LOG.log(
            System.Logger.Level.WARNING,
            "No pricing-svc instance to record promotion redemptions for order {0}",
            orderId);
        return;
      }
      JsonArrayBuilder arr = Json.createArrayBuilder();
      for (AppliedPromotion a : applied) {
        JsonObjectBuilder o =
            Json.createObjectBuilder()
                .add("promotionId", a.promotionId().toString())
                .add("name", a.name() == null ? "" : a.name())
                .add("amount", a.amount());
        if (a.variantId() != null) o.add("variantId", a.variantId().toString());
        arr.add(o);
      }
      JsonObjectBuilder body =
          Json.createObjectBuilder()
              .add("orderId", orderId.toString())
              .add(
                  "currency",
                  java.util.Objects.requireNonNull(
                      currency, "a redemption is recorded in the order's currency"))
              .add("appliedPromotions", arr);
      if (customerId != null) body.add("customerId", customerId.toString());

      try (HttpClientResponse res =
          webClient
              .post(pricingBase + "/prices/redemptions")
              .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
              .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
              .header(HeaderNames.CONTENT_TYPE, "application/json")
              .submit(body.build().toString())) {
        int status = res.status().code();
        if (status != 200 && status != 201) {
          LOG.log(
              System.Logger.Level.WARNING,
              "pricing-svc returned HTTP {0} recording redemptions for order {1}",
              status,
              orderId);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Could not record promotion redemptions for order " + orderId,
          e);
    }
  }

  /**
   * Tells pricing-svc what an order sold at reduced-price stickers (05.4), so each sticker counts
   * down and can run out. Quietly, for the same reason as {@link #recordRedemptionsQuietly}: the
   * order stands and is paid for; the count is idempotent on the order and can be caught up.
   *
   * @param tenantId the tenant
   * @param orderId the order
   * @param items its lines; those with no markdown are ignored
   */
  public void recordMarkdownRedemptionsQuietly(
      UUID tenantId, UUID orderId, List<com.storeql.order.domain.Domain.OrderItem> items) {
    try {
      JsonArrayBuilder arr = Json.createArrayBuilder();
      int stickered = 0;
      for (var i : items) {
        if (i.markdownId() == null) continue;
        arr.add(
            Json.createObjectBuilder()
                .add("markdownId", i.markdownId().toString())
                .add("qty", i.qty()));
        stickered++;
      }
      if (stickered == 0) return;
      String pricingBase = locate().orElse(null);
      if (pricingBase == null) {
        LOG.log(
            System.Logger.Level.WARNING,
            "No pricing-svc instance to record markdown redemptions for order {0}",
            orderId);
        return;
      }
      JsonObject body =
          Json.createObjectBuilder().add("orderId", orderId.toString()).add("lines", arr).build();
      try (HttpClientResponse res =
          webClient
              .post(pricingBase + "/prices/markdown-redemptions")
              .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
              .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
              .header(HeaderNames.CONTENT_TYPE, "application/json")
              .submit(body.toString())) {
        int status = res.status().code();
        if (status != 200) {
          LOG.log(
              System.Logger.Level.WARNING,
              "pricing-svc returned HTTP {0} recording markdown redemptions for order {1}",
              status,
              orderId);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Could not record markdown redemptions for order " + orderId,
          e);
    }
  }

  /** The machine code in an error envelope, or null when the body is not one. */
  static String errorCode(String payload) {
    try (JsonReader reader = Json.createReader(new StringReader(payload))) {
      JsonObject o = reader.readObject();
      if (!o.containsKey("error") || o.isNull("error")) return null;
      JsonObject err = o.getJsonObject("error");
      return err.containsKey("code") && !err.isNull("code") ? err.getString("code") : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static String errorMessage(String payload) {
    try (JsonReader reader = Json.createReader(new StringReader(payload))) {
      JsonObject err = reader.readObject().getJsonObject("error");
      return err.containsKey("message") && !err.isNull("message")
          ? err.getString("message")
          : "refused by pricing-svc";
    } catch (RuntimeException e) {
      return "refused by pricing-svc";
    }
  }

  private static BigDecimal num(JsonObject o, String key, BigDecimal fallback) {
    return o.containsKey(key) && !o.isNull(key) ? o.getJsonNumber(key).bigDecimalValue() : fallback;
  }

  /**
   * Batch form of {@link #resolveLine} — resolves every line of an order in one HTTP call instead
   * of one call per line, removing the per-line round trip (and circuit-breaker/retry overhead)
   * that checkout used to pay once per item. Results are returned in the same order as {@code
   * lines}. Same fail-closed behavior as the single-line form: any line that can't be priced fails
   * the whole call (placeOrder never persisted a partially-priced order before this change either).
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(
      requestVolumeThreshold = 5,
      failureRatio = 0.6,
      delay = 5000,
      // A refusal is an answer, not a failure: four stickers refused in a row must not open the
      // breaker and turn the fifth cashier's honest question into a 503.
      skipOn = {ApiException.class})
  public List<ResolvedLine> resolveLines(
      UUID tenantId, List<LineRequest> lines, UUID storeId, String channel) {
    if (lines.isEmpty()) return List.of();
    String pricingBase =
        locate()
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonArrayBuilder linesArray = Json.createArrayBuilder();
    for (LineRequest l : lines) {
      JsonObjectBuilder lineObj =
          Json.createObjectBuilder().add("variantId", l.variantId().toString());
      if (storeId != null) lineObj.add("storeId", storeId.toString());
      if (channel != null) lineObj.add("channel", channel);
      if (l.qty() != null) lineObj.add("qty", l.qty());
      linesArray.add(lineObj);
    }
    String payload = Json.createObjectBuilder().add("lines", linesArray).build().toString();

    try (HttpClientResponse res =
        webClient
            .post(pricingBase + "/prices/resolve-batch")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload)) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for one or more order lines");
      }
      String body = res.as(String.class);
      var refused = relayedRefusal(status, body);
      if (refused.isPresent()) throw refused.get();
      if (status != 200) {
        throw unavailable("pricing-svc returned HTTP " + status, null);
      }
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray results = reader.readObject().getJsonObject("data").getJsonArray("results");
        List<ResolvedLine> resolved = new ArrayList<>(results.size());
        for (var r : results) {
          JsonObject data = r.asJsonObject();
          BigDecimal unitPrice = data.getJsonNumber("unitPrice").bigDecimalValue();
          BigDecimal vatAmount =
              data.containsKey("vatAmount") && !data.isNull("vatAmount")
                  ? data.getJsonNumber("vatAmount").bigDecimalValue()
                  : BigDecimal.ZERO;
          resolved.add(new ResolvedLine(unitPrice, vatAmount));
        }
        return resolved;
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc unreachable", e);
    }
  }

  /**
   * A refusal pricing-svc explained — an error code on a 400 or a 409 — relayed with its status,
   * code and message, so the till and the shop see the reason (no VAT rate configured, a sticker
   * for another product) rather than a 503. Empty for anything else, including a body that cannot
   * be read.
   */
  static java.util.Optional<ApiException> relayedRefusal(int status, String payload) {
    if (status != 400 && status != 409) return java.util.Optional.empty();
    try {
      String code = errorCode(payload);
      if (code == null || code.isBlank()) return java.util.Optional.empty();
      return java.util.Optional.of(
          status == 400
              ? ApiException.badRequest(code, errorMessage(payload))
              : ApiException.conflict(code, errorMessage(payload)));
    } catch (RuntimeException e) {
      return java.util.Optional.empty();
    }
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "ORDER_PRICING_UNAVAILABLE", message, List.of(), cause);
  }
}
