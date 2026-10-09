package com.storeql.product.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.product.config.ServiceConfig;
import com.storeql.web.ApiException;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;

/**
 * Calls inventory-svc {@code POST /admin/inventory/receive/batch} to receive stock for the
 * catalogue-import rows, in as few round-trips as inventory-svc accepts. Consul-resolved (golden
 * rule #4).
 */
@ApplicationScoped
public class InventoryClient {

  private static final String INVENTORY_SERVICE = "inventory-svc";

  private static final System.Logger LOG = System.getLogger(InventoryClient.class.getName());

  /**
   * inventory-svc's own default for {@code storeql.inventory.bulk.receive-max-lines}: a call of
   * more lines is refused whole there ({@code 400 INVENTORY_BULK_TOO_LARGE}).
   */
  static final int DEFAULT_RECEIVE_MAX_LINES = 500;

  @Inject ServiceConfig config;

  /**
   * The most lines one call to inventory-svc carries. Kept at or below inventory-svc's {@code
   * storeql.inventory.bulk.receive-max-lines}, which is its to set: this service cannot read
   * another's configuration, so the two are set together. The initial value is for a client made
   * without a container, as a test makes one.
   */
  @Inject
  @ConfigProperty(
      name = "storeql.product.inventory.receive-max-lines",
      defaultValue = "" + DEFAULT_RECEIVE_MAX_LINES)
  int receiveMaxLines = DEFAULT_RECEIVE_MAX_LINES;

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

  public record ReceiveItem(String variantId, BigDecimal qty) {}

  /**
   * The most decimal places of a quantity inventory-svc receives: {@code BatchReceiveItem.qty} is
   * {@code @Fits(integer = 15, fraction = 3)}, judged on the figure with its trailing zeros
   * dropped, as its {@code NUMERIC(18, 3)} columns keep it.
   */
  public static final int QTY_PLACES = 3;

  /** The most whole digits of a quantity inventory-svc receives (the same {@code @Fits}). */
  public static final int QTY_WHOLE_DIGITS = 15;

  /**
   * Why inventory-svc's batch receive would refuse a quantity, or null when it would take it.
   *
   * <p>inventory-svc validates the whole call before it receives any line
   * ({@code @NotNull @Positive @Fits(15, 3)} on every line's {@code qty}), so one line it refuses
   * is a {@code 400} for every line sent with it, up to {@link #receiveMaxLines} of them. Asked of
   * each line before it is sent, so a line that would be refused is that line's error and never the
   * call's. Judged as inventory-svc judges it: places with trailing zeros dropped ({@code 2.5000}
   * has one), whole digits as written.
   *
   * @param qty the quantity as read
   * @return what is wrong with it, in words that start with the quantity; null when it fits
   */
  public static String quantityProblem(BigDecimal qty) {
    if (qty == null) return "no quantity";
    if (qty.signum() <= 0) return "quantity " + qty.toPlainString() + " is not above zero";
    if ((long) qty.precision() - qty.scale() > QTY_WHOLE_DIGITS) {
      return "quantity "
          + qty.toPlainString()
          + " has more whole digits than stock is kept to ("
          + QTY_WHOLE_DIGITS
          + ")";
    }
    if (qty.scale() > QTY_PLACES && qty.stripTrailingZeros().scale() > QTY_PLACES) {
      return "quantity "
          + qty.toPlainString()
          + " has more decimal places than stock is kept to ("
          + QTY_PLACES
          + ")";
    }
    return null;
  }

  public record BatchResult(int received, List<String> errors) {
    public BatchResult {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Whether inventory-svc is in discovery right now. Asked <em>before</em> an import writes
   * anything that would then have to be received into stock: an import that found out afterwards
   * would have answered an error for a catalogue it had already committed, and the next attempt
   * would meet its own duplicates.
   *
   * @throws ApiException 503 {@code INVENTORY_UNAVAILABLE} when discovery lists no healthy instance
   */
  public void requireAvailable() {
    if (registry.resolve(INVENTORY_SERVICE).isEmpty()) {
      throw new ApiException(
          503,
          "INVENTORY_UNAVAILABLE",
          "inventory-svc is not available, so the stock cannot be received; nothing was imported,"
              + " try again",
          List.of(),
          null);
    }
  }

  /**
   * The lines of a call that was not made because the circuit breaker is open: every one of them,
   * not received, and why.
   *
   * @param lines how many lines the call carried
   * @return nothing received, and one error saying so
   */
  public static BatchResult notAsked(int lines) {
    return new BatchResult(
        0,
        List.of(
            lines
                + " lines not received: inventory-svc is not being asked for now, after too many"
                + " recent failures; receive them again later"));
  }

  /**
   * inventory-svc stopped answering part of the way through: what was received before it did, and
   * every line after it reported as not received, with the reason.
   *
   * <p>Thrown rather than returned so that the circuit breaker on {@link #batchReceive} counts it:
   * a failure swallowed inside the method is a success to the breaker, and the breaker of a dead
   * inventory-svc never opened. A caller that has committed what the stock belongs to reports
   * {@link #result()} with its own result and does not rethrow.
   */
  public static final class Unreachable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What was received before inventory-svc stopped answering, and every line that was not. */
    private final transient BatchResult result;

    /**
     * @param result what was received, and the lines that were not, each with its reason
     * @param cause the failure that ended it, kept for the log
     */
    public Unreachable(BatchResult result, Throwable cause) {
      super("inventory-svc stopped answering", cause);
      this.result = result;
    }

    /**
     * @return what was received before inventory-svc stopped answering, and every line that was not
     */
    public BatchResult result() {
      return result;
    }
  }

  /**
   * Receives the lines at inventory-svc, in calls of at most {@link #receiveMaxLines} lines each.
   *
   * <p>What inventory-svc <em>answers</em> is reported line by line and the calls after it are
   * still made: a refusal or a failing line is about that call, and one bad chunk never drops the
   * rest of the sheet. What it does <em>not</em> answer ends the receipt: once a call cannot reach
   * inventory-svc, or reaches it and gets no answer, no further call is made, and that call's lines
   * and every line after it are reported as not received, with the reason. The method then throws
   * {@link Unreachable}, carrying that result.
   *
   * <p>{@code @CircuitBreaker}: trips after 60% failures in a 5-call window, and a failure is what
   * this method throws — an {@link Unreachable}, or a 503 with no instance in discovery. So a dead
   * inventory-svc opens it, and the next import fails fast with {@code CircuitBreakerOpenException}
   * (see {@link #notAsked}) instead of waiting out the connect and read timeouts. An answer, even
   * an error, is not a failure here: the service is there.
   *
   * <p>No {@code @Retry}: inventory-svc's batch receive takes no idempotency key, so a call that
   * timed out may have landed, and sending it again would receive the stock twice. A receipt is
   * never resent.
   *
   * <p>Asked as the caller ({@link Caller}): inventory-svc holds them to the stores they keep, as
   * it does when they receive stock there themselves.
   *
   * @param caller who the stock is received for
   * @param storeId the store every line is received into
   * @param lines the lines; one whose quantity inventory-svc would refuse ({@link
   *     #quantityProblem}) is reported as not received and is not sent
   * @return how many lines were received, and why each one that was not was not
   * @throws ApiException 503 {@code INVENTORY_UNAVAILABLE} when discovery lists no healthy
   *     instance. A caller that has already committed what the stock belongs to reports this with
   *     its result and does not rethrow it; see {@link #requireAvailable()} for asking first.
   * @throws Unreachable when inventory-svc stopped answering part of the way through, carrying what
   *     was received and every line that was not
   */
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public BatchResult batchReceive(Caller caller, UUID storeId, List<ReceiveItem> lines) {
    // A line inventory-svc would refuse is never sent: it would refuse the whole call with it.
    var refused = new ArrayList<String>();
    var items = new ArrayList<ReceiveItem>(lines.size());
    for (var line : lines) {
      String wrong = quantityProblem(line.qty());
      if (wrong == null) {
        items.add(line);
      } else {
        refused.add(line.variantId() + ": " + wrong + "; not received");
      }
    }
    if (items.isEmpty()) return new BatchResult(0, refused);

    var instance =
        registry
            .resolve(INVENTORY_SERVICE)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "INVENTORY_UNAVAILABLE",
                        "no healthy inventory-svc instance in discovery",
                        List.of(),
                        null));

    int perCall = Math.max(1, receiveMaxLines);
    int received = 0;
    var errors = new ArrayList<String>(refused);
    for (int from = 0; from < items.size(); from += perCall) {
      int to = Math.min(items.size(), from + perCall);
      var chunk = items.subList(from, to);
      try {
        BatchResult part = postChunk(caller, instance.baseUri(), storeId, chunk);
        received += part.received();
        errors.addAll(part.errors());
      } catch (NoAnswer e) {
        errors.add(e.reason(chunk.size()));
        if (to < items.size()) {
          errors.add(
              (items.size() - to)
                  + " lines not received: not sent, because inventory-svc had stopped answering");
        }
        throw new Unreachable(new BatchResult(received, errors), e);
      }
    }
    return new BatchResult(received, errors);
  }

  /** A call that got no answer: inventory-svc could not be reached, or never replied. */
  private static final class NoAnswer extends Exception {

    private static final long serialVersionUID = 1L;

    /** True when the connection was never made, so nothing can have been received. */
    private final boolean neverReached;

    NoAnswer(Throwable cause) {
      super(cause);
      this.neverReached = causedBy(cause, java.net.ConnectException.class);
    }

    /** What the call's lines are reported as. */
    String reason(int lines) {
      return neverReached
          ? lines + " lines not received: inventory-svc could not be reached"
          : lines
              + " lines may not have been received: inventory-svc gave no answer; check the"
              + " store's stock before receiving them again";
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
   * One call to inventory-svc for at most {@link #receiveMaxLines} lines. Whatever it answers,
   * refusals and failures included, is reported as lines not received.
   *
   * @throws NoAnswer when the call got no answer at all, for the caller to stop at
   */
  private BatchResult postChunk(
      Caller caller, String baseUri, UUID storeId, List<ReceiveItem> chunk) throws NoAnswer {
    JsonArrayBuilder arr = Json.createArrayBuilder();
    for (var item : chunk) {
      arr.add(
          Json.createObjectBuilder()
              .add("storeId", storeId.toString())
              .add("variantId", item.variantId())
              .add("qty", item.qty()));
    }
    String body = Json.createObjectBuilder().add("items", arr).build().toString();

    int status;
    String resp;
    try (HttpClientResponse res =
        caller
            .stamp(webClient.post(baseUri + "/admin/inventory/receive/batch"))
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body)) {
      status = res.status().code();
      resp = res.as(String.class);
    } catch (RuntimeException e) {
      // The exception's own words name this client's internals; the log keeps them.
      LOG.log(System.Logger.Level.WARNING, "inventory-svc gave no answer", e);
      throw new NoAnswer(e);
    }
    if (status >= 400) {
      // A refusal (the store is not one the caller keeps, say) or a failure has no data to read:
      // the lines were not received, and the result says how many, rather than that the service
      // was unreachable.
      return new BatchResult(
          0, List.of(chunk.size() + " lines not received: inventory-svc answered HTTP " + status));
    }
    try (JsonReader reader = Json.createReader(new StringReader(resp))) {
      var data = reader.readObject().getJsonObject("data");
      int received = data.getInt("received", 0);
      var errs = new ArrayList<String>();
      var errArr = data.getJsonArray("errors");
      if (errArr != null) {
        for (int i = 0; i < errArr.size(); i++) {
          errs.add(errArr.getString(i));
        }
      }
      return new BatchResult(received, errs);
    } catch (RuntimeException e) {
      // It answered, but not in words this client reads: whether the lines went in is not known.
      LOG.log(System.Logger.Level.WARNING, "inventory-svc's answer could not be read", e);
      return new BatchResult(
          0,
          List.of(
              chunk.size()
                  + " lines may not have been received: inventory-svc's answer could not be read;"
                  + " check the store's stock before receiving them again"));
    }
  }

  // ── opening stock (catalogue import) ─────────────────────────────────────────

  /** One item's stock as a file says it. */
  public record OpenLine(
      String variantId, BigDecimal qty, BigDecimal unitCost, LocalDate expiry, String lot) {}

  /** What inventory-svc did with an opening: the counts, and the items it left alone. */
  public record Opened(
      int loaded, int replayed, int alreadyOpened, int held, List<String> alreadyOpenedVariants) {
    public Opened {
      alreadyOpenedVariants = List.copyOf(alreadyOpenedVariants);
    }
  }

  /** What a job opened at one store, as inventory-svc totals it. */
  public record StoreTotals(
      String storeId, int lines, BigDecimal qty, BigDecimal value, int uncostedLines) {}

  /**
   * Opens stock at a store for an import job: at most {@link #receiveMaxLines} items, once each.
   * The call is idempotent by job, so a call that got no answer is simply asked again.
   *
   * @throws ApiException 503 {@code INVENTORY_UNAVAILABLE} when inventory-svc gave no answer; its
   *     own refusal (a store the caller does not keep, say) with its own code
   */
  public Opened openStock(Caller caller, UUID jobId, UUID storeId, List<OpenLine> lines) {
    if (lines.isEmpty()) return new Opened(0, 0, 0, 0, List.of());
    JsonArrayBuilder arr = Json.createArrayBuilder();
    for (OpenLine l : lines) {
      var o = Json.createObjectBuilder().add("variantId", l.variantId()).add("qty", l.qty());
      if (l.unitCost() != null) o.add("unitCost", l.unitCost());
      if (l.expiry() != null) o.add("expiryDate", l.expiry().toString());
      if (l.lot() != null) o.add("batchNo", l.lot());
      arr.add(o);
    }
    String body =
        Json.createObjectBuilder()
            .add("jobId", jobId.toString())
            .add("storeId", storeId.toString())
            .add("lines", arr)
            .build()
            .toString();
    String text =
        sent(
            caller.stamp(webClient.post(baseOrFail() + "/admin/inventory/opening-stock")),
            body,
            "the opening stock may not have been loaded");
    try (JsonReader reader = Json.createReader(new StringReader(text))) {
      JsonObject data = reader.readObject().getJsonObject("data");
      List<String> left = new ArrayList<>();
      int held = 0;
      for (JsonObject line : data.getJsonArray("lines").getValuesAs(JsonObject.class)) {
        if ("ALREADY_OPENED".equals(line.getString("outcome")))
          left.add(line.getString("variantId"));
        if (line.getBoolean("held", false)) held++;
      }
      return new Opened(
          data.getInt("loaded"), data.getInt("replayed"), data.getInt("alreadyOpened"), held, left);
    }
  }

  /** What a job opened, by store; empty when it opened nothing. */
  public List<StoreTotals> openedBy(Caller caller, UUID jobId) {
    String text =
        sent(
            caller.stamp(webClient.get(baseOrFail() + "/admin/inventory/opening-stock/" + jobId)),
            null,
            "the opening stock could not be read");
    try (JsonReader reader = Json.createReader(new StringReader(text))) {
      List<StoreTotals> out = new ArrayList<>();
      for (JsonObject s :
          reader
              .readObject()
              .getJsonObject("data")
              .getJsonArray("stores")
              .getValuesAs(JsonObject.class)) {
        out.add(
            new StoreTotals(
                s.getString("storeId"),
                s.getInt("lines"),
                new BigDecimal(s.getString("qty")),
                new BigDecimal(s.getString("value")),
                s.getInt("uncostedLines")));
      }
      return out;
    }
  }

  private String baseOrFail() {
    return registry
        .resolve(INVENTORY_SERVICE)
        .orElseThrow(
            () ->
                new ApiException(
                    503,
                    "INVENTORY_UNAVAILABLE",
                    "no healthy inventory-svc instance in discovery",
                    List.of(),
                    null))
        .baseUri();
  }

  /** Sends a request and returns the body of a 200, or throws what the answer says. */
  private static String sent(
      io.helidon.webclient.api.HttpClientRequest req, String body, String what) {
    int status;
    String text;
    try (HttpClientResponse res =
        body == null
            ? req.request()
            : req.header(HeaderNames.CONTENT_TYPE, "application/json").submit(body)) {
      status = res.status().code();
      text = res.as(String.class);
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "inventory-svc gave no answer: " + what, e);
      throw new ApiException(503, "INVENTORY_UNAVAILABLE", what, List.of(), e);
    }
    if (status == 200) return text;
    String code = "INVENTORY_REFUSED";
    String message = what + ": inventory-svc answered HTTP " + status;
    try (JsonReader reader = Json.createReader(new StringReader(text))) {
      JsonObject o = reader.readObject();
      if (o.containsKey("code")) code = o.getString("code");
      if (o.containsKey("error") && !o.isNull("error")) {
        JsonObject e = o.getJsonObject("error");
        if (e.containsKey("code")) code = e.getString("code");
        if (e.containsKey("message")) message = e.getString("message");
      }
    } catch (RuntimeException ignored) {
      // The words stay generic; the status is said.
    }
    throw new ApiException(status >= 500 ? 503 : 409, code, message, List.of(), null);
  }
}
