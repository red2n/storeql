package com.storeql.reporting.client;

import com.storeql.reporting.config.ServiceConfig;
import com.storeql.reporting.domain.PendingWork.Customer;
import com.storeql.reporting.domain.PendingWork.Payment;
import com.storeql.reporting.domain.PendingWork.Purchase;
import com.storeql.reporting.domain.PendingWork.Readings;
import com.storeql.service.ServiceReader;
import com.storeql.service.ServiceReader.Reply;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Asks purchase-svc, payment-svc and customer-svc how many things of their own wait for a person in
 * a business, {@code GET /admin/pending-work} on each (golden rule #1: their tables, their answer).
 * Each is a {@link ServiceReader} found through Consul or at {@code storeql.clients.<service>.url},
 * asked as a manager of the caller's business.
 *
 * <p>The three reads go out together on virtual threads and share one deadline, {@code
 * storeql.reporting.waiting-work.timeout-millis}. <strong>A count that cannot be had is empty,
 * never zero</strong>: a service that cannot be located, refuses, fails, is too slow, or answers
 * something other than exactly its contract all read as empty, because a zero would say nothing
 * waits.
 */
@ApplicationScoped
public class PendingWorkClient {

  private static final Logger LOG = System.getLogger(PendingWorkClient.class.getName());

  static final String PURCHASE_SERVICE = "purchase-svc";
  static final String PAYMENT_SERVICE = "payment-svc";
  static final String CUSTOMER_SERVICE = "customer-svc";

  static final String PATH = "/admin/pending-work";

  /** Staff: the count is a manager's read, and the lowest role the owning services admit. */
  static final String INTERNAL_ROLE = "MANAGER";

  /**
   * One try: a screen polled every few seconds is its own retry, and a second try spends the
   * deadline.
   */
  private static final int ATTEMPTS = 1;

  /** How one read is made; replaced in a unit test. */
  @FunctionalInterface
  interface Reads {
    Reply get(String service, UUID tenantId, String path);
  }

  @Inject ServiceConfig config;

  /** How long all three reads may take together. */
  @Inject
  @ConfigProperty(name = "storeql.reporting.waiting-work.timeout-millis", defaultValue = "2000")
  long timeoutMillis;

  private final Map<String, ServiceReader> readers = new ConcurrentHashMap<>();

  private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

  Reads reads = this::readerGet;

  /**
   * What the three services say about a business's waiting work.
   *
   * @param tenantId the caller's business, from the token
   */
  public Readings read(UUID tenantId) {
    Future<Optional<Purchase>> purchase =
        pool.submit(() -> fetch(PURCHASE_SERVICE, tenantId, PendingWorkClient::purchase));
    Future<Optional<Payment>> payment =
        pool.submit(() -> fetch(PAYMENT_SERVICE, tenantId, PendingWorkClient::payment));
    Future<Optional<Customer>> customer =
        pool.submit(() -> fetch(CUSTOMER_SERVICE, tenantId, PendingWorkClient::customer));
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    return new Readings(
        await(PURCHASE_SERVICE, purchase, deadline),
        await(PAYMENT_SERVICE, payment, deadline),
        await(CUSTOMER_SERVICE, customer, deadline));
  }

  @PreDestroy
  void close() {
    pool.shutdownNow();
  }

  private Reply readerGet(String service, UUID tenantId, String path) {
    return readers
        .computeIfAbsent(
            service,
            s ->
                new ServiceReader(
                    config, s, INTERNAL_ROLE, ATTEMPTS, ServiceReader.configuredUrl(s)))
        .get(tenantId, path, Map.of());
  }

  private <T> Optional<T> fetch(
      String service, UUID tenantId, Function<String, Optional<T>> parse) {
    Reply reply = reads.get(service, tenantId, PATH);
    if (!reply.ok()) return Optional.empty();
    Optional<T> read = parse.apply(reply.body());
    if (read.isEmpty()) {
      LOG.log(
          Level.WARNING, "{0}{1} for {2} answered something unreadable", service, PATH, tenantId);
    }
    return read;
  }

  /** The read's answer, or empty once the shared deadline has passed (the read is cancelled). */
  private <T> Optional<T> await(String service, Future<Optional<T>> read, long deadline) {
    try {
      return read.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      read.cancel(true);
      LOG.log(Level.WARNING, "{0}{1} did not answer within {2} ms", service, PATH, timeoutMillis);
    } catch (ExecutionException e) {
      LOG.log(Level.WARNING, "{0}{1} failed: {2}", service, PATH, String.valueOf(e.getCause()));
    } catch (InterruptedException e) {
      read.cancel(true);
      Thread.currentThread().interrupt();
    }
    return Optional.empty();
  }

  // ── reading the answers: {"data": {...}}, and exactly the members the contract names ──────────

  /**
   * purchase-svc's five members: four counts and whether approval limits are set.
   *
   * @return empty if the body is not that envelope, or a member is missing or of another type
   */
  static Optional<Purchase> purchase(String body) {
    return data(body)
        .flatMap(
            d -> {
              Optional<Long> orders = count(d, "purchaseOrdersPendingApproval");
              Optional<Long> runs = count(d, "paymentRunsProposed");
              Optional<Long> invoices = count(d, "supplierInvoicesFlagged");
              Optional<Long> syncs = count(d, "accountingSyncsUncertain");
              Optional<Boolean> routed = flag(d, "approvalsRouted");
              if (orders.isEmpty()
                  || runs.isEmpty()
                  || invoices.isEmpty()
                  || syncs.isEmpty()
                  || routed.isEmpty()) {
                return Optional.empty();
              }
              return Optional.of(
                  new Purchase(
                      orders.get(), runs.get(), invoices.get(), syncs.get(), routed.get()));
            });
  }

  /** payment-svc's one member, {@code cardRefundDuesNeedingAttention}. */
  static Optional<Payment> payment(String body) {
    return data(body).flatMap(d -> count(d, "cardRefundDuesNeedingAttention")).map(Payment::new);
  }

  /** customer-svc's one member, {@code privacyRequestsOpen}. */
  static Optional<Customer> customer(String body) {
    return data(body).flatMap(d -> count(d, "privacyRequestsOpen")).map(Customer::new);
  }

  /** The envelope's {@code data} object; empty for anything else, including an error envelope. */
  private static Optional<JsonObject> data(String body) {
    if (body == null || body.isBlank()) return Optional.empty();
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonValue root = reader.readValue();
      if (root instanceof JsonObject envelope && envelope.get("data") instanceof JsonObject data) {
        return Optional.of(data);
      }
      return Optional.empty();
    } catch (JsonException | IllegalStateException e) {
      return Optional.empty();
    }
  }

  /** A whole number, zero or more: a count. Text, a fraction, a negative or a null is not one. */
  private static Optional<Long> count(JsonObject data, String member) {
    if (data.get(member) instanceof JsonNumber number && number.isIntegral()) {
      try {
        long value = number.longValueExact();
        return value >= 0 ? Optional.of(value) : Optional.empty();
      } catch (ArithmeticException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  /** A true or a false; anything else, text or a number included, is not one. */
  private static Optional<Boolean> flag(JsonObject data, String member) {
    JsonValue value = data.get(member);
    if (value == null) return Optional.empty();
    return switch (value.getValueType()) {
      case TRUE -> Optional.of(true);
      case FALSE -> Optional.of(false);
      default -> Optional.empty();
    };
  }
}
