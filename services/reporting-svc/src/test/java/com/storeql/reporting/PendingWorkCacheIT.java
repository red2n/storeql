package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Concurrency;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.JsonStub.Answer;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The few seconds a business's waiting work is kept, against real Postgres with the three owning
 * services stood in for by stub HTTP servers: a business's dashboards between them cause one
 * fan-out per period, a business never sees another's report, a refused caller is refused before
 * the cache is looked at, and a report with a source missing is not kept. The period here is long
 * enough that no test sees it end; its expiry, the size bound and the stamp on a kept report are
 * proved with a clock in {@code WaitingWorkCacheTest}.
 */
@HelidonTest
class PendingWorkCacheIT {

  private static final String PATH = "/admin/reports/system-health/waiting-work";
  private static final String UPSTREAM = "/admin/pending-work";

  private static final PostgresSupport PG;
  private static final JsonStub PURCHASE;
  private static final JsonStub PAYMENT;
  private static final JsonStub CUSTOMER;

  private static final Map<String, Supplier<Answer>> PURCHASE_SAYS = new ConcurrentHashMap<>();
  private static final Map<String, Supplier<Answer>> PAYMENT_SAYS = new ConcurrentHashMap<>();
  private static final Map<String, Supplier<Answer>> CUSTOMER_SAYS = new ConcurrentHashMap<>();

  private static final Supplier<Answer> NO_SUCH_ROUTE =
      () -> new Answer(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"none\"}}");

  private static final List<String> SET =
      List.of(
          "storeql.reporting.waiting-work.timeout-millis",
          "storeql.reporting.waiting-work.cache-millis",
          "storeql.clients.breaker.failures");

  /** A figure no other business here has, so a leak of it cannot be mistaken for another count. */
  private static final long DISTINCTIVE = 4242;

  static {
    PG = PostgresSupport.start().wire("reporting");
    PURCHASE = JsonStub.start("purchase-svc");
    PAYMENT = JsonStub.start("payment-svc");
    CUSTOMER = JsonStub.start("customer-svc");
    route(PURCHASE, PURCHASE_SAYS);
    route(PAYMENT, PAYMENT_SAYS);
    route(CUSTOMER, CUSTOMER_SAYS);
    System.setProperty("storeql.reporting.waiting-work.timeout-millis", "2000");
    // Long enough that the period never ends inside a test.
    System.setProperty("storeql.reporting.waiting-work.cache-millis", "600000");
    System.setProperty("storeql.clients.breaker.failures", "100000");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PURCHASE.close();
    PAYMENT.close();
    CUSTOMER.close();
    SET.forEach(System::clearProperty);
    PG.stop();
  }

  // ── the stand-ins ──────────────────────────────────────────────────────────

  private static void route(JsonStub stub, Map<String, Supplier<Answer>> says) {
    stub.on(
        "GET",
        UPSTREAM,
        call -> says.getOrDefault(String.valueOf(call.tenantId()), NO_SUCH_ROUTE).get());
  }

  private static Answer purchase(long orders, long runs, long invoices, long syncs) {
    return Answer.ok(
        "{\"purchaseOrdersPendingApproval\":"
            + orders
            + ",\"paymentRunsProposed\":"
            + runs
            + ",\"supplierInvoicesFlagged\":"
            + invoices
            + ",\"accountingSyncsUncertain\":"
            + syncs
            + ",\"approvalsRouted\":true}");
  }

  private static Answer payment(long dues) {
    return Answer.ok("{\"cardRefundDuesNeedingAttention\":" + dues + "}");
  }

  private static Answer customer(long requests) {
    return Answer.ok("{\"privacyRequestsOpen\":" + requests + "}");
  }

  private static Answer afterAWhile(Answer then) {
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return then;
  }

  private static UUID business(
      Supplier<Answer> purchase, Supplier<Answer> payment, Supplier<Answer> customer) {
    UUID id = Ids.newId();
    PURCHASE_SAYS.put(id.toString(), purchase);
    PAYMENT_SAYS.put(id.toString(), payment);
    CUSTOMER_SAYS.put(id.toString(), customer);
    return id;
  }

  /** Counts 3, 2, 5, 1, 4 and 7. */
  private static UUID busyBusiness() {
    return business(() -> purchase(3, 2, 5, 1), () -> payment(4), () -> customer(7));
  }

  // ── asking ─────────────────────────────────────────────────────────────────

  private Response ask(UUID business, String roles, String permissions, String storeIds) {
    Invocation.Builder req =
        target
            .path(PATH)
            .request()
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Tenant-Id", business.toString());
    if (roles != null) req = req.header("X-Roles", roles);
    if (permissions != null) req = req.header("X-Permissions", permissions);
    if (storeIds != null) req = req.header("X-Store-Ids", storeIds);
    return req.get();
  }

  private JsonObject asManager(UUID business) {
    return Envelopes.ok(ask(business, "MANAGER", null, null));
  }

  private static List<Long> counts(JsonObject data) {
    List<Long> out = new ArrayList<>();
    for (JsonObject i : data.getJsonArray("items").getValuesAs(JsonObject.class)) {
      out.add(i.isNull("count") ? null : i.getJsonNumber("count").longValueExact());
    }
    return out;
  }

  private static List<String> unreachable(JsonObject data) {
    List<String> out = new ArrayList<>();
    JsonArray named = data.getJsonArray("unreachable");
    for (int i = 0; i < named.size(); i++) out.add(named.getString(i));
    return out;
  }

  private static long callsFor(JsonStub stub, UUID business) {
    return stub.calls().stream().filter(c -> business.toString().equals(c.tenantId())).count();
  }

  private static long downstreamCalls(UUID business) {
    return callsFor(PURCHASE, business)
        + callsFor(PAYMENT, business)
        + callsFor(CUSTOMER, business);
  }

  // ── kept ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Dashboards of one business, whoever opens them, cause one fan-out between them")
  void oneFanOutForManyDashboards() {
    UUID a = busyBusiness();

    JsonObject first = Envelopes.ok(ask(a, "OWNER", null, null));
    JsonObject second = asManager(a);
    JsonObject third = Envelopes.ok(ask(a, "MANAGER", "system.health", null));
    // The business's queues move, but the report is kept for the period.
    PURCHASE_SAYS.put(a.toString(), () -> purchase(9, 9, 9, 9));
    JsonObject fourth = asManager(a);

    assertThat(counts(first), contains(3L, 2L, 5L, 1L, 4L, 7L));
    for (JsonObject kept : List.of(second, third, fourth)) {
      assertThat(counts(kept), contains(3L, 2L, 5L, 1L, 4L, 7L));
      assertThat(kept.getString("generatedAt"), is(first.getString("generatedAt")));
    }
    assertThat(callsFor(PURCHASE, a), is(1L));
    assertThat(callsFor(PAYMENT, a), is(1L));
    assertThat(callsFor(CUSTOMER, a), is(1L));
  }

  @Test
  @DisplayName("Each business has its own report: a second business's first ask reads its own")
  void businessesAreKeptApart() {
    UUID a = busyBusiness();
    UUID b = business(() -> purchase(9, 8, 7, 6), () -> payment(5), () -> customer(4));

    assertThat(counts(asManager(a)), contains(3L, 2L, 5L, 1L, 4L, 7L));
    assertThat(downstreamCalls(b), is(0L));

    assertThat(counts(asManager(b)), contains(9L, 8L, 7L, 6L, 5L, 4L));
    assertThat(downstreamCalls(b), is(3L));

    assertThat(counts(asManager(a)), contains(3L, 2L, 5L, 1L, 4L, 7L));
    assertThat(counts(asManager(b)), contains(9L, 8L, 7L, 6L, 5L, 4L));
    assertThat(downstreamCalls(a), is(3L));
    assertThat(downstreamCalls(b), is(3L));
  }

  @Test
  @DisplayName("A report with a source that did not answer is not kept: the next ask reads again")
  void anUnreachableSourceIsRetried() {
    UUID a = business(() -> purchase(3, 2, 5, 1), () -> new Answer(500, "boom"), () -> customer(7));

    JsonObject gap = asManager(a);
    assertThat(unreachable(gap), contains("CARD_REFUND"));
    assertThat(callsFor(PURCHASE, a), is(1L));

    PAYMENT_SAYS.put(a.toString(), () -> payment(4));
    JsonObject whole = asManager(a);
    assertThat(unreachable(whole), is(List.of()));
    assertThat(counts(whole), contains(3L, 2L, 5L, 1L, 4L, 7L));
    assertThat(callsFor(PURCHASE, a), is(2L));

    // Now every source answered, so it is kept.
    asManager(a);
    assertThat(callsFor(PURCHASE, a), is(2L));
    assertThat(callsFor(PAYMENT, a), is(2L));
    assertThat(callsFor(CUSTOMER, a), is(2L));
  }

  @Test
  @DisplayName("Fifty dashboards of one business opening at once cause one fan-out")
  void aColdCacheIsNotAHerd() throws Exception {
    UUID a =
        business(
            () -> afterAWhile(purchase(DISTINCTIVE, 2, 5, 1)), () -> payment(4), () -> customer(7));

    List<JsonObject> answers = Concurrency.inParallel(50, () -> asManager(a));

    assertThat(answers.size(), is(50));
    for (JsonObject data : answers) {
      assertThat(counts(data), contains(DISTINCTIVE, 2L, 5L, 1L, 4L, 7L));
    }
    assertThat(callsFor(PURCHASE, a), is(1L));
    assertThat(callsFor(PAYMENT, a), is(1L));
    assertThat(callsFor(CUSTOMER, a), is(1L));
  }

  // ── who may be told ────────────────────────────────────────────────────────

  @Test
  @DisplayName("A business with a kept report still refuses a caller who may not see it, first")
  void aRefusedCallerIsRefusedBeforeTheCache() {
    UUID a = business(() -> purchase(DISTINCTIVE, 2, 5, 1), () -> payment(4), () -> customer(7));
    assertThat(counts(asManager(a)).get(0), is(DISTINCTIVE));
    long callsWhenKept = downstreamCalls(a);
    String store = Ids.newId().toString();

    List<String> bodies = new ArrayList<>();
    // A manager the owner has narrowed away from it; a manager held to stores; staff below
    // management, with and without the permission; a shopper; no role at all.
    List<Object[]> refused =
        List.of(
            new Object[] {"MANAGER", "-", null, "SYSTEM_HEALTH_NOT_PERMITTED"},
            new Object[] {"MANAGER", "purchasing.approve", null, "SYSTEM_HEALTH_NOT_PERMITTED"},
            new Object[] {"MANAGER", null, store, "BUSINESS_WIDE_ONLY"},
            new Object[] {"STOREKEEPER", null, null, null},
            new Object[] {"STOREKEEPER", "system.health", null, null},
            new Object[] {"CASHIER", null, null, null},
            new Object[] {"CASHIER", "system.health", store, null},
            new Object[] {"CUSTOMER", null, null, null},
            new Object[] {null, null, null, null});
    for (Object[] who : refused) {
      try (Response r = ask(a, (String) who[0], (String) who[1], (String) who[2])) {
        String body = r.readEntity(String.class);
        assertThat(
            String.join(" ", String.valueOf(who[0]), String.valueOf(who[1]), body),
            r.getStatus(),
            is(403));
        if (who[3] != null) assertThat(body, containsString((String) who[3]));
        bodies.add(body);
      }
    }

    for (String body : bodies) {
      assertThat(body, not(containsString(String.valueOf(DISTINCTIVE))));
      assertThat(body, not(containsString("\"count\"")));
      assertThat(body, not(containsString("generatedAt")));
    }
    assertThat(downstreamCalls(a), is(callsWhenKept));
    // And the people who may are still given it, from the cache.
    assertThat(counts(asManager(a)).get(0), is(DISTINCTIVE));
    assertThat(downstreamCalls(a), is(callsWhenKept));
  }

  @Test
  @DisplayName("Another business's people of every role are given their own report, never ours")
  void anotherBusinessSeesNothingOfOurKeptReport() {
    UUID ours = business(() -> purchase(DISTINCTIVE, 2, 5, 1), () -> payment(4), () -> customer(7));
    UUID theirs = business(() -> purchase(0, 0, 0, 0), () -> payment(0), () -> customer(0));
    String ourStore = Ids.newId().toString();
    assertThat(counts(asManager(ours)).get(0), is(DISTINCTIVE));

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      JsonObject data = Envelopes.ok(ask(theirs, role, null, null));
      assertThat(role, counts(data), contains(0L, 0L, 0L, 0L, 0L, 0L));
    }
    try (Response r = ask(theirs, "MANAGER", null, ourStore)) {
      assertThat(r.getStatus(), is(403));
      assertThat(r.readEntity(String.class), not(containsString(String.valueOf(DISTINCTIVE))));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      try (Response r = ask(theirs, role, null, ourStore)) {
        String body = r.readEntity(String.class);
        assertThat(role + ": " + body, r.getStatus(), is(403));
        assertThat(body, not(containsString(String.valueOf(DISTINCTIVE))));
      }
    }

    assertThat(downstreamCalls(ours), is(3L));
    assertThat(downstreamCalls(theirs), is(3L));
    assertThat(counts(asManager(ours)).get(0), is(DISTINCTIVE));
  }

  @Test
  @DisplayName("The answer a person is given carries no more than it did before it was kept")
  void everyKeptAnswerCarriesTheSameMembers() {
    UUID a = busyBusiness();

    JsonObject first = asManager(a);
    JsonObject kept = asManager(a);

    assertThat(kept.keySet(), is(first.keySet()));
    List<Boolean> capped = new ArrayList<>();
    for (JsonObject i : kept.getJsonArray("items").getValuesAs(JsonObject.class)) {
      capped.add(i.getBoolean("capped"));
    }
    assertThat(capped, everyItem(is(false)));
  }
}
