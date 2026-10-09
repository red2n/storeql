package com.storeql.product.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.product.config.ServiceConfig;
import com.storeql.web.ApiException;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;

/**
 * Calls pricing-svc to (a) find, or else make, the business's default ALL-channel price list, then
 * (b) batch-upsert selling prices for the catalogue-import rows, in as few round-trips as
 * pricing-svc accepts. Consul-resolved (golden rule #4).
 */
@ApplicationScoped
public class PricingClient {

  private static final String PRICING_SERVICE = "pricing-svc";

  private static final System.Logger LOG = System.getLogger(PricingClient.class.getName());

  /** pricing-svc refuses a batch of more rows than this (400 VALIDATION_FAILED). */
  static final int MAX_ITEMS_PER_CALL = 500;

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(30))
            .build();
  }

  public record PriceItem(String variantId, BigDecimal price) {}

  public record BatchResult(int upserted, List<String> errors) {
    public BatchResult {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Whether pricing-svc is in discovery right now. Asked <em>before</em> an import writes anything
   * whose prices would then have to be set: an import that found out afterwards would have answered
   * an error for a catalogue it had already committed, and the next attempt would meet its own
   * duplicates.
   *
   * @throws ApiException 503 {@code PRICING_UNAVAILABLE} when discovery lists no healthy instance
   */
  public void requireAvailable() {
    if (registry.resolve(PRICING_SERVICE).isEmpty()) {
      throw new ApiException(
          503,
          "PRICING_UNAVAILABLE",
          "pricing-svc is not available, so the prices cannot be set; nothing was imported,"
              + " try again",
          List.of(),
          null);
    }
  }

  /**
   * The VAT codes the business has a rate for, upper-cased: what a dry run checks a file's mapped
   * codes against, so a code with no rate is found before a till refuses to quote it.
   *
   * @return the codes, or empty when pricing-svc cannot be asked (nothing is then claimed either
   *     way)
   */
  public java.util.Optional<java.util.Set<String>> configuredVatCodes(Caller caller) {
    try {
      var instance = registry.resolve(PRICING_SERVICE);
      if (instance.isEmpty()) return java.util.Optional.empty();
      Answered res =
          answered(caller.stamp(webClient.get(instance.get().baseUri() + "/vat-rates")), null);
      if (res.status() != 200) return java.util.Optional.empty();
      try (JsonReader reader = Json.createReader(new StringReader(res.body()))) {
        var arr = reader.readObject().getJsonArray("data");
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
          codes.add(arr.getJsonObject(i).getString("code").toUpperCase(java.util.Locale.ROOT));
        }
        return java.util.Optional.of(codes);
      }
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "pricing-svc's VAT rates could not be read", e);
      return java.util.Optional.empty();
    }
  }

  /**
   * A price list as the import needs to know it: its currency, and whether its prices include VAT.
   */
  public record ListInfo(String id, String currency, String taxMode, boolean active) {}

  private String baseOrFail() {
    return registry
        .resolve(PRICING_SERVICE)
        .orElseThrow(
            () ->
                new ApiException(
                    503,
                    "PRICING_UNAVAILABLE",
                    "no healthy pricing-svc instance in discovery",
                    List.of(),
                    null))
        .baseUri();
  }

  private static ApiException noAnswer(String what, Throwable cause) {
    LOG.log(System.Logger.Level.WARNING, "pricing-svc gave no answer: " + what, cause);
    return new ApiException(503, "PRICING_UNAVAILABLE", what, List.of(), cause);
  }

  /**
   * A price list of the business, as pricing-svc holds it.
   *
   * @throws ApiException 404 {@code IMPORT_PRICE_LIST_NOT_FOUND} when it is not the business's; 503
   *     when pricing-svc gave no answer
   */
  public ListInfo priceList(Caller caller, String id) {
    Answered res;
    try {
      res = answered(caller.stamp(webClient.get(baseOrFail() + "/price-lists/" + id)), null);
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw noAnswer("the price list could not be read", e);
    }
    if (res.status() == 404) {
      throw ApiException.notFound(
          "IMPORT_PRICE_LIST_NOT_FOUND", "price list " + id + " is not one of this business's");
    }
    if (res.status() != 200) {
      throw new ApiException(
          503, "PRICING_UNAVAILABLE", "pricing-svc answered HTTP " + res.status(), List.of(), null);
    }
    try (JsonReader reader = Json.createReader(new StringReader(res.body()))) {
      var d = reader.readObject().getJsonObject("data");
      return new ListInfo(
          d.getString("id"),
          d.getString("currency", null),
          d.getString("taxMode", "EXCLUSIVE"),
          d.getBoolean("active", true));
    } catch (RuntimeException e) {
      throw noAnswer("the price list could not be read", e);
    }
  }

  /** Makes an ALL-channel price list, effective now, in the given tax mode; returns its id. */
  public String createPriceList(Caller caller, String name, String currency, String taxMode) {
    String body =
        Json.createObjectBuilder()
            .add("name", name)
            .add("channel", "ALL")
            .add("currency", currency)
            .add("taxMode", taxMode)
            .add("effectiveFrom", Instant.now().toString())
            .build()
            .toString();
    Answered res;
    try {
      res =
          answered(
              caller
                  .stamp(webClient.post(baseOrFail() + "/admin/price-lists"))
                  .header(HeaderNames.CONTENT_TYPE, "application/json"),
              body);
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw noAnswer("the price list could not be made", e);
    }
    if (res.status() != 201) {
      throw refusal(res, "the price list could not be made");
    }
    try (JsonReader reader = Json.createReader(new StringReader(res.body()))) {
      return reader.readObject().getJsonObject("data").getString("id");
    }
  }

  /** One variant and the VAT code it is sold under. */
  public record VatItem(String variantId, String vatCode) {}

  /**
   * Gives variants their VAT categories, all or none (pricing-svc's batch call, at most 500 rows).
   *
   * @throws ApiException pricing-svc's own refusal ({@code PRICING_VAT_BATCH_INVALID}, naming the
   *     rows) or 503
   */
  public int setVatCategories(Caller caller, List<VatItem> items) {
    if (items.isEmpty()) return 0;
    JsonArrayBuilder arr = Json.createArrayBuilder();
    for (VatItem i : items) {
      arr.add(
          Json.createObjectBuilder().add("variantId", i.variantId()).add("vatCode", i.vatCode()));
    }
    String body = Json.createObjectBuilder().add("items", arr).build().toString();
    Answered res;
    try {
      res =
          answered(
              caller
                  .stamp(webClient.post(baseOrFail() + "/product-vat-categories/batch"))
                  .header(HeaderNames.CONTENT_TYPE, "application/json"),
              body);
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw noAnswer("the VAT categories may not have been set", e);
    }
    if (res.status() != 200) {
      throw refusal(res, "the VAT categories were not set");
    }
    try (JsonReader reader = Json.createReader(new StringReader(res.body()))) {
      return reader.readObject().getJsonObject("data").getInt("assigned", items.size());
    }
  }

  /**
   * Sets prices on one named price list (at most 500 rows). What pricing-svc says about each row is
   * reported; a row it refuses does not stop the others.
   *
   * @throws ApiException 503 when pricing-svc gave no answer, or its refusal of the call as a whole
   */
  public BatchResult setPricesOn(Caller caller, String priceListId, List<PriceItem> items) {
    if (items.isEmpty()) return new BatchResult(0, List.of());
    try {
      return postChunk(caller, baseOrFail(), priceListId, items);
    } catch (NoAnswer e) {
      throw noAnswer(e.reason, e);
    }
  }

  /**
   * The prices a list holds, by variant id (the single-unit price of each), read a page at a time.
   *
   * @throws ApiException 404 {@code IMPORT_PRICE_LIST_NOT_FOUND}; 503 when pricing-svc gave no
   *     answer
   */
  public java.util.Map<String, java.math.BigDecimal> pricesOn(Caller caller, String priceListId) {
    java.util.Map<String, java.math.BigDecimal> out = new java.util.HashMap<>();
    String after = null;
    do {
      Answered res;
      try {
        var req =
            webClient
                .get(baseOrFail() + "/price-lists/" + priceListId + "/items")
                .queryParam("limit", "1000");
        if (after != null) req = req.queryParam("after", after);
        res = answered(caller.stamp(req), null);
      } catch (ApiException e) {
        throw e;
      } catch (RuntimeException e) {
        throw noAnswer("the prices could not be read", e);
      }
      if (res.status() == 404) {
        throw ApiException.notFound(
            "IMPORT_PRICE_LIST_NOT_FOUND",
            "price list " + priceListId + " is not one of this business's");
      }
      if (res.status() != 200) {
        throw new ApiException(
            503,
            "PRICING_UNAVAILABLE",
            "pricing-svc answered HTTP " + res.status(),
            List.of(),
            null);
      }
      try (JsonReader reader = Json.createReader(new StringReader(res.body()))) {
        var root = reader.readObject();
        for (var item : root.getJsonArray("data").getValuesAs(jakarta.json.JsonObject.class)) {
          var min =
              !item.containsKey("minQty") || item.isNull("minQty")
                  ? null
                  : item.getJsonNumber("minQty").bigDecimalValue();
          if (min == null || min.compareTo(java.math.BigDecimal.ONE) <= 0) {
            out.put(item.getString("variantId"), item.getJsonNumber("price").bigDecimalValue());
          }
        }
        var meta = root.getJsonObject("meta");
        after =
            meta == null || !meta.containsKey("nextCursor") || meta.isNull("nextCursor")
                ? null
                : meta.getString("nextCursor");
      } catch (RuntimeException e) {
        throw noAnswer("the prices could not be read", e);
      }
    } while (after != null);
    return out;
  }

  /** The refusal pricing-svc gave, with its own code when it sent one. */
  private static ApiException refusal(Answered res, String what) {
    String code = "PRICING_REFUSED";
    String message = what + ": pricing-svc answered HTTP " + res.status();
    try (JsonReader reader = Json.createReader(new StringReader(res.body()))) {
      var o = reader.readObject();
      if (o.containsKey("code")) code = o.getString("code");
      if (o.containsKey("error") && !o.isNull("error")) {
        var e = o.getJsonObject("error");
        if (e.containsKey("code")) code = e.getString("code");
        if (e.containsKey("message")) message = e.getString("message");
      }
    } catch (RuntimeException ignored) {
      // The words stay generic; the status is said.
    }
    return new ApiException(res.status() >= 500 ? 503 : 409, code, message, List.of(), null);
  }

  /**
   * The rows of a call that was not made because the circuit breaker is open: every one of them,
   * not priced, and why.
   *
   * @param rows how many prices the call carried
   * @return nothing set, and one error saying so
   */
  public static BatchResult notAsked(int rows) {
    return new BatchResult(
        0,
        List.of(
            rows
                + " prices not set: pricing-svc is not being asked for now, after too many recent"
                + " failures; set them again later"));
  }

  /**
   * pricing-svc stopped answering part of the way through: what was set before it did, and every
   * row after it reported as not set, with the reason.
   *
   * <p>Thrown rather than returned so that the circuit breaker on {@link #batchSetPrices} counts
   * it: a failure swallowed inside the method is a success to the breaker, and the breaker of a
   * dead pricing-svc never opened. A caller that has committed what the prices belong to reports
   * {@link #result()} with its own result and does not rethrow.
   */
  public static final class Unreachable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What was set before pricing-svc stopped answering, and every row that was not. */
    private final transient BatchResult result;

    /**
     * @param result what was set, and the rows that were not, each with its reason
     * @param cause the failure that ended it, kept for the log
     */
    public Unreachable(BatchResult result, Throwable cause) {
      super("pricing-svc stopped answering", cause);
      this.result = result;
    }

    /**
     * @return what was set before pricing-svc stopped answering, and every row that was not
     */
    public BatchResult result() {
      return result;
    }
  }

  /**
   * Finds the business's default ALL-channel price list, or makes it when pricing-svc says there is
   * none, then sets the prices on it in calls of at most {@link #MAX_ITEMS_PER_CALL} rows each.
   *
   * <p>What pricing-svc <em>answers</em> is reported row by row and the calls after it are still
   * made: a refusal or a failing row is about that call, and one bad chunk never drops the rest of
   * the sheet. An answer that is not its price lists (a refusal, a failure, words this client
   * cannot read) sets nothing and makes no list: taken for "there is none", it made a second
   * Default list beside the business's own. What it does <em>not</em> answer ends the call: once a
   * request cannot reach pricing-svc, or reaches it and gets no answer, no further request is made,
   * and every row not yet set is reported, with the reason. The method then throws {@link
   * Unreachable}, carrying that result. No reason carries an exception's own words, which name this
   * client's internals; the log keeps them.
   *
   * <p>{@code @CircuitBreaker}: trips after 60% failures in a 5-call window, and a failure is what
   * this method throws — an {@link Unreachable}, or a 503 with no instance in discovery. So a dead
   * pricing-svc opens it, and the next import fails fast with {@code CircuitBreakerOpenException}
   * (see {@link #notAsked}) instead of waiting out the connect and read timeouts. An answer, even
   * an error, is not a failure here: the service is there.
   *
   * <p>No {@code @Retry}: a call that got no answer may have landed, and a retry would send every
   * chunk again from the first, each writing its price history and {@code PriceChanged} events a
   * second time, after waiting out the read timeout once more inside the import.
   *
   * <p>Every call is made as the caller ({@link Caller}): pricing-svc asks them for {@code
   * pricing.write} as it does when they set a price there themselves.
   *
   * @param caller who the prices are set for
   * @param currency the currency a default list is made in, when there is none
   * @param items the prices
   * @return how many prices were set, and why each one that was not was not
   * @throws ApiException 503 {@code PRICING_UNAVAILABLE} when discovery lists no healthy instance.
   *     A caller that has already committed what the prices belong to reports this with its result
   *     and does not rethrow it; see {@link #requireAvailable()} for asking first.
   * @throws Unreachable when pricing-svc stopped answering, carrying what was set and every row
   *     that was not
   */
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public BatchResult batchSetPrices(Caller caller, String currency, List<PriceItem> items) {
    if (items.isEmpty()) return new BatchResult(0, List.of());

    var instance =
        registry
            .resolve(PRICING_SERVICE)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "PRICING_UNAVAILABLE",
                        "no healthy pricing-svc instance in discovery",
                        List.of(),
                        null));

    String priceListId;
    try {
      ListFound list = defaultPriceList(caller, currency, instance.baseUri(), items.size());
      if (list.refused() != null) return new BatchResult(0, List.of(list.refused()));
      priceListId = list.id();
    } catch (NoAnswer e) {
      throw new Unreachable(new BatchResult(0, List.of(e.reason)), e);
    }

    int upserted = 0;
    var errors = new ArrayList<String>();
    for (int from = 0; from < items.size(); from += MAX_ITEMS_PER_CALL) {
      int to = Math.min(items.size(), from + MAX_ITEMS_PER_CALL);
      var chunk = items.subList(from, to);
      try {
        BatchResult part = postChunk(caller, instance.baseUri(), priceListId, chunk);
        upserted += part.upserted();
        errors.addAll(part.errors());
      } catch (NoAnswer e) {
        errors.add(e.reason);
        if (to < items.size()) {
          errors.add(
              (items.size() - to)
                  + " prices not set: not sent, because pricing-svc had stopped answering");
        }
        throw new Unreachable(new BatchResult(upserted, errors), e);
      }
    }
    return new BatchResult(upserted, errors);
  }

  /**
   * A request that got no answer: pricing-svc could not be reached, or never replied. Carries what
   * the rows it leaves unset are reported as, in plain words.
   */
  private static final class NoAnswer extends Exception {

    private static final long serialVersionUID = 1L;

    /** What the rows are reported as. */
    final String reason;

    NoAnswer(String reason, Throwable cause) {
      super(reason, cause);
      this.reason = reason;
    }

    /**
     * The request that failed, said for the rows it leaves unset.
     *
     * @param rows how many rows it leaves unset
     * @param unanswered what they are reported as when the request reached pricing-svc and got no
     *     answer; one never reached is reported as not set, because nothing can have landed
     * @param cause the failure, for the log
     */
    static NoAnswer of(int rows, String unanswered, RuntimeException cause) {
      LOG.log(System.Logger.Level.WARNING, "pricing-svc gave no answer", cause);
      return new NoAnswer(
          causedBy(cause, java.net.ConnectException.class)
              ? rows + " prices not set: pricing-svc could not be reached"
              : unanswered,
          cause);
    }

    /** Whether the chain of causes holds one of {@code kind}, looked for a bounded depth down. */
    private static boolean causedBy(Throwable e, Class<? extends Throwable> kind) {
      Throwable t = e;
      for (int depth = 0; t != null && depth < 16; depth++) {
        if (kind.isInstance(t)) return true;
        t = t.getCause();
      }
      return false;
    }
  }

  /**
   * One call to pricing-svc for at most {@link #MAX_ITEMS_PER_CALL} rows. Whatever it answers,
   * refusals and failures included, is reported as rows not set.
   *
   * @throws NoAnswer when the call got no answer at all, for the caller to stop at
   */
  private BatchResult postChunk(
      Caller caller, String baseUri, String priceListId, List<PriceItem> chunk) throws NoAnswer {
    JsonArrayBuilder arr = Json.createArrayBuilder();
    for (var item : chunk) {
      arr.add(
          Json.createObjectBuilder()
              .add("variantId", item.variantId())
              .add("price", item.price())
              .add("minQty", 1));
    }
    String body = Json.createObjectBuilder().add("items", arr).build().toString();

    Answered res;
    try {
      res =
          answered(
              caller
                  .stamp(
                      webClient.post(
                          baseUri + "/admin/price-lists/" + priceListId + "/items/batch"))
                  .header(HeaderNames.CONTENT_TYPE, "application/json"),
              body);
    } catch (RuntimeException e) {
      throw NoAnswer.of(
          chunk.size(),
          chunk.size()
              + " prices may not have been set: pricing-svc gave no answer; check them on the"
              + " price list before setting them again",
          e);
    }
    if (res.status() >= 400) {
      return new BatchResult(
          0, List.of(chunk.size() + " prices not set: pricing-svc answered HTTP " + res.status()));
    }
    try (JsonReader reader = Json.createReader(new StringReader(res.body()))) {
      var data = reader.readObject().getJsonObject("data");
      int upserted = data.getInt("upserted", 0);
      var errs = new ArrayList<String>();
      var errArr = data.getJsonArray("errors");
      if (errArr != null) {
        for (int i = 0; i < errArr.size(); i++) {
          errs.add(errArr.getString(i));
        }
      }
      return new BatchResult(upserted, errs);
    } catch (RuntimeException e) {
      // It answered, but not in words this client reads: whether the prices were set is not known.
      LOG.log(System.Logger.Level.WARNING, "pricing-svc's answer could not be read", e);
      return new BatchResult(
          0,
          List.of(
              chunk.size()
                  + " prices may not have been set: pricing-svc's answer could not be read; check"
                  + " them on the price list before setting them again"));
    }
  }

  /**
   * The default price list, found or made; or, when pricing-svc answered but no list can be used,
   * what the rows are reported as.
   *
   * @param id the list's id, or null when {@code refused} says why there is none
   * @param refused what every row is reported as, or null when there is a list
   */
  private record ListFound(String id, String refused) {}

  /** A status and body pricing-svc answered with. */
  private record Answered(int status, String body) {}

  /** Sends a request and reads its answer whole; anything thrown is no answer. */
  private static Answered answered(HttpClientRequest req, String body) {
    try (HttpClientResponse res = body == null ? req.request() : req.submit(body)) {
      return new Answered(res.status().code(), res.as(String.class));
    }
  }

  /**
   * Finds the business's ALL-channel price list, or makes one when pricing-svc lists none.
   *
   * <p>A list is made only when pricing-svc has <em>said</em> there is none: an answer that is not
   * its lists (a refusal, a failure, words that cannot be read) sets no price and makes no list.
   *
   * @param rows how many prices wait on the list, for what they are reported as
   * @throws NoAnswer when pricing-svc gave no answer to either request
   */
  private ListFound defaultPriceList(Caller caller, String currency, String baseUri, int rows)
      throws NoAnswer {
    // The list endpoint is cursor-paginated (default 20); ask for the max page. The Default
    // ALL-channel list is created at onboarding, so it sorts first (created_at ASC) — one page
    // is always enough to find it.
    Answered lists;
    try {
      lists =
          answered(
              caller.stamp(
                  webClient
                      // The query as a parameter, never in the path string: a "?" written into
                      // the path is escaped and the request reaches a route that does not exist.
                      .get(baseUri + "/price-lists")
                      .queryParam("limit", "100")),
              null);
    } catch (RuntimeException e) {
      throw NoAnswer.of(rows, rows + " prices not set: pricing-svc gave no answer", e);
    }
    if (lists.status() >= 400) {
      return new ListFound(
          null,
          rows
              + " prices not set: pricing-svc answered HTTP "
              + lists.status()
              + " when asked for its price lists");
    }
    try (JsonReader reader = Json.createReader(new StringReader(lists.body()))) {
      var arr = reader.readObject().getJsonArray("data");
      if (arr == null) throw new jakarta.json.JsonException("no data");
      for (int i = 0; i < arr.size(); i++) {
        var pl = arr.getJsonObject(i);
        boolean active = pl.getBoolean("active", true);
        String ch = pl.getString("channel", "ALL");
        if (active && "ALL".equals(ch)) {
          return new ListFound(pl.getString("id"), null);
        }
      }
      // Fallback: any list
      if (!arr.isEmpty()) return new ListFound(arr.getJsonObject(0).getString("id"), null);
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "pricing-svc's price lists could not be read", e);
      return new ListFound(
          null, rows + " prices not set: pricing-svc's price lists could not be read");
    }

    // pricing-svc says there is none — make the default.
    // The caller resolves the tenant's currency; a default list is never created in a guessed one.
    String cur = java.util.Objects.requireNonNull(currency, "the price list's currency");
    String createBody =
        Json.createObjectBuilder()
            .add("name", "Default")
            .add("channel", "ALL")
            .add("currency", cur)
            .add("effectiveFrom", Instant.now().toString())
            .build()
            .toString();
    Answered made;
    try {
      made =
          answered(
              caller
                  .stamp(webClient.post(baseUri + "/admin/price-lists"))
                  .header(HeaderNames.CONTENT_TYPE, "application/json"),
              createBody);
    } catch (RuntimeException e) {
      // The list may have been made: the next import finds it rather than making another.
      throw NoAnswer.of(
          rows,
          rows
              + " prices not set: pricing-svc gave no answer when asked to make the default price"
              + " list",
          e);
    }
    if (made.status() >= 400) {
      return new ListFound(
          null,
          rows
              + " prices not set: pricing-svc answered HTTP "
              + made.status()
              + " when asked to make the default price list");
    }
    try (JsonReader reader = Json.createReader(new StringReader(made.body()))) {
      return new ListFound(reader.readObject().getJsonObject("data").getString("id"), null);
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "pricing-svc's new price list could not be read", e);
      return new ListFound(
          null,
          rows
              + " prices not set: pricing-svc's answer to making the default price list could not"
              + " be read; it may have been made");
    }
  }
}
