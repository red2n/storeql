package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.reporting.config.WaitingWorkRoutes;
import com.storeql.reporting.domain.PendingWork.Kind;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.JsonStub.Answer;
import com.storeql.test.PostgresSupport;
import com.storeql.web.PendingWorkCount;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Waiting work against real Postgres (reporting-svc boots with its database), with purchase-svc,
 * payment-svc and customer-svc each stood in for by a stub HTTP server at {@code
 * storeql.clients.<service>.url}: the counts and labels come back in a fixed order; a source that
 * is down, slow, refusing or talking nonsense is null and named while the rest are still answered;
 * the caller must hold the permission and be held to no store, and be management; and each
 * downstream call carries the caller's own business and a staff role, never another business.
 */
@HelidonTest
class PendingWorkIT {

  private static final String PATH = "/admin/reports/system-health/waiting-work";
  private static final String UPSTREAM = "/admin/pending-work";

  private static final PostgresSupport PG;
  private static final JsonStub PURCHASE;
  private static final JsonStub PAYMENT;
  private static final JsonStub CUSTOMER;

  /** What each service says to a business, by the tenant id stamped on the call. */
  private static final Map<String, Supplier<Answer>> PURCHASE_SAYS = new ConcurrentHashMap<>();

  private static final Map<String, Supplier<Answer>> PAYMENT_SAYS = new ConcurrentHashMap<>();
  private static final Map<String, Supplier<Answer>> CUSTOMER_SAYS = new ConcurrentHashMap<>();

  private static final Supplier<Answer> NO_SUCH_ROUTE =
      () -> new Answer(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"none\"}}");

  /** Tuning the suite sets and puts back: system properties outlive a test class. */
  private static final List<String> SET =
      List.of(
          "storeql.reporting.waiting-work.timeout-millis",
          "storeql.reporting.waiting-work.cache-millis",
          "storeql.clients.breaker.failures");

  /** A slow service sleeps this long; the deadline below is a fraction of it. */
  private static final long SLOW_MILLIS = 4_000;

  static {
    PG = PostgresSupport.start().wire("reporting");
    PURCHASE = JsonStub.start("purchase-svc");
    PAYMENT = JsonStub.start("payment-svc");
    CUSTOMER = JsonStub.start("customer-svc");
    route(PURCHASE, PURCHASE_SAYS);
    route(PAYMENT, PAYMENT_SAYS);
    route(CUSTOMER, CUSTOMER_SAYS);
    System.setProperty("storeql.reporting.waiting-work.timeout-millis", "600");
    // This suite is about the fan-out: every request reads the three services afresh. The few
    // seconds a report is kept have their own suite (PendingWorkCacheIT).
    System.setProperty("storeql.reporting.waiting-work.cache-millis", "0");
    // The breaker is per service and shared by every business: without this a run of deliberate
    // failures here would open it and the next test's good answer would be refused for seconds.
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

  private static Answer purchase(
      long orders, long runs, long invoices, long syncs, boolean routed) {
    return Answer.ok(
        "{\"purchaseOrdersPendingApproval\":"
            + orders
            + ",\"paymentRunsProposed\":"
            + runs
            + ",\"supplierInvoicesFlagged\":"
            + invoices
            + ",\"accountingSyncsUncertain\":"
            + syncs
            + ",\"approvalsRouted\":"
            + routed
            + "}");
  }

  private static Answer payment(long dues) {
    return Answer.ok("{\"cardRefundDuesNeedingAttention\":" + dues + "}");
  }

  private static Answer customer(long requests) {
    return Answer.ok("{\"privacyRequestsOpen\":" + requests + "}");
  }

  private static Answer slow(Answer then) {
    try {
      Thread.sleep(SLOW_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return then;
  }

  /** A business, and what its three services say to it. */
  private static UUID business(
      Supplier<Answer> purchase, Supplier<Answer> payment, Supplier<Answer> customer) {
    UUID id = Ids.newId();
    PURCHASE_SAYS.put(id.toString(), purchase);
    PAYMENT_SAYS.put(id.toString(), payment);
    CUSTOMER_SAYS.put(id.toString(), customer);
    return id;
  }

  /** A business with something waiting in every queue: 3, 2, 5, 1, 4 and 7. */
  private static UUID busyBusiness() {
    return business(() -> purchase(3, 2, 5, 1, true), () -> payment(4), () -> customer(7));
  }

  // ── asking ─────────────────────────────────────────────────────────────────

  private Response ask(
      UUID business, String roles, String permissions, String storeIds, String tenantIdInQuery) {
    WebTarget t = target.path(PATH);
    if (tenantIdInQuery != null) t = t.queryParam("tenantId", tenantIdInQuery);
    Invocation.Builder req = t.request().header("X-User-Id", Ids.newId().toString());
    if (business != null) req = req.header("X-Tenant-Id", business.toString());
    if (roles != null) req = req.header("X-Roles", roles);
    if (permissions != null) req = req.header("X-Permissions", permissions);
    if (storeIds != null) req = req.header("X-Store-Ids", storeIds);
    return req.get();
  }

  private JsonObject asManager(UUID business) {
    return Envelopes.ok(ask(business, "MANAGER", null, null, null));
  }

  private static JsonObject item(JsonObject data, Kind kind) {
    return Envelopes.find(data.getJsonArray("items"), "kind", kind.name());
  }

  private static boolean isNullCount(JsonObject item) {
    return item.containsKey("count") && item.isNull("count");
  }

  private static List<String> unreachable(JsonObject data) {
    List<String> out = new ArrayList<>();
    JsonArray named = data.getJsonArray("unreachable");
    for (int i = 0; i < named.size(); i++) out.add(named.getString(i));
    return out;
  }

  private static List<Long> counts(JsonObject data) {
    List<Long> out = new ArrayList<>();
    for (JsonObject i : data.getJsonArray("items").getValuesAs(JsonObject.class)) {
      out.add(i.isNull("count") ? null : i.getJsonNumber("count").longValueExact());
    }
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

  private static final List<String> ALL_KINDS =
      List.of(
          "PURCHASE_ORDER_APPROVAL",
          "PAYMENT_RUN",
          "SUPPLIER_INVOICE",
          "ACCOUNTING_SYNC",
          "CARD_REFUND",
          "PRIVACY_REQUEST");

  // ── the answer ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "All three reachable: six items in a fixed order with their counts, words and routes")
  void allReachable() {
    UUID a = busyBusiness();

    JsonObject data = asManager(a);

    assertThat(
        Duration.between(Instant.parse(data.getString("generatedAt")), Instant.now()).abs(),
        lessThan(Duration.ofMinutes(1)));
    assertThat(
        data.getJsonArray("items").getValuesAs(JsonObject.class).stream()
            .map(i -> i.getString("kind"))
            .toList(),
        contains(ALL_KINDS.toArray()));
    assertThat(counts(data), contains(3L, 2L, 5L, 1L, 4L, 7L));
    assertThat(unreachable(data), empty());
    for (JsonObject i : data.getJsonArray("items").getValuesAs(JsonObject.class)) {
      assertThat(i.getString("label").isBlank(), is(false));
      String route = WaitingWorkRoutes.OPENS.get(Kind.valueOf(i.getString("kind")));
      if (route == null) {
        assertThat(i.containsKey("opens") && i.isNull("opens"), is(true));
      } else {
        assertThat(i.getString("opens"), is(route));
      }
      assertThat(i.containsKey("note") && i.isNull("note"), is(true));
      assertThat(i.getBoolean("capped"), is(false));
    }
    assertThat(
        item(data, Kind.PURCHASE_ORDER_APPROVAL).getString("label"),
        is("Purchase orders waiting for approval"));
  }

  @Test
  @DisplayName(
      "A business with nothing waiting is told zero for each, not null and not unreachable")
  void nothingWaitingIsZero() {
    UUID a = business(() -> purchase(0, 0, 0, 0, true), () -> payment(0), () -> customer(0));

    JsonObject data = asManager(a);

    assertThat(counts(data), contains(0L, 0L, 0L, 0L, 0L, 0L));
    assertThat(unreachable(data), empty());
  }

  @Test
  @DisplayName("purchase-svc answers 500: its four kinds are null and named, the others answered")
  void purchaseServerError() {
    UUID a = business(() -> new Answer(500, "boom"), () -> payment(4), () -> customer(7));

    JsonObject data = asManager(a);

    assertThat(
        unreachable(data),
        contains("PURCHASE_ORDER_APPROVAL", "PAYMENT_RUN", "SUPPLIER_INVOICE", "ACCOUNTING_SYNC"));
    for (Kind kind :
        List.of(
            Kind.PURCHASE_ORDER_APPROVAL,
            Kind.PAYMENT_RUN,
            Kind.SUPPLIER_INVOICE,
            Kind.ACCOUNTING_SYNC)) {
      assertThat(kind.name(), isNullCount(item(data, kind)), is(true));
    }
    assertThat(item(data, Kind.CARD_REFUND).getInt("count"), is(4));
    assertThat(item(data, Kind.PRIVACY_REQUEST).getInt("count"), is(7));
  }

  @Test
  @DisplayName("payment-svc is too slow: cut off at the deadline, named, and the others answered")
  void paymentSlow() {
    UUID a = business(() -> purchase(3, 2, 5, 1, true), () -> slow(payment(4)), () -> customer(7));

    long started = System.nanoTime();
    JsonObject data = asManager(a);
    long tookMillis = (System.nanoTime() - started) / 1_000_000;

    assertThat(unreachable(data), contains("CARD_REFUND"));
    assertThat(isNullCount(item(data, Kind.CARD_REFUND)), is(true));
    assertThat(item(data, Kind.PURCHASE_ORDER_APPROVAL).getInt("count"), is(3));
    assertThat(item(data, Kind.PRIVACY_REQUEST).getInt("count"), is(7));
    assertThat(
        "a 600 ms deadline took " + tookMillis + " ms", tookMillis, lessThan(SLOW_MILLIS - 500));
  }

  @Test
  @DisplayName("customer-svc talks nonsense or has no such route: null and named, never zero")
  void customerUnreadable() {
    List<Supplier<Answer>> says =
        List.of(
            () -> new Answer(200, "<html>welcome</html>"),
            () -> new Answer(200, "{\"data\":{}}"),
            () -> new Answer(200, "{\"data\":{\"privacyRequestsOpen\":\"many\"}}"),
            () -> new Answer(200, "{\"data\":{\"privacyRequestsOpen\":-3}}"),
            () -> new Answer(403, "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"no\"}}"),
            NO_SUCH_ROUTE);
    for (Supplier<Answer> said : says) {
      UUID a = business(() -> purchase(3, 2, 5, 1, true), () -> payment(4), said);

      JsonObject data = asManager(a);

      assertThat(unreachable(data), contains("PRIVACY_REQUEST"));
      assertThat(isNullCount(item(data, Kind.PRIVACY_REQUEST)), is(true));
      assertThat(counts(data).subList(0, 5), contains(3L, 2L, 5L, 1L, 4L));
    }
  }

  @Test
  @DisplayName("Every service down: still a 200, six nulls and six names, never six zeros")
  void everythingDown() {
    UUID a =
        business(() -> new Answer(503, ""), () -> new Answer(500, "x"), () -> new Answer(502, "y"));

    JsonObject data = asManager(a);

    assertThat(unreachable(data), contains(ALL_KINDS.toArray()));
    assertThat(counts(data), hasSize(6));
    assertThat(counts(data), everyItem(nullValue()));
  }

  @Test
  @DisplayName("No approval limits set: a note on purchase orders alone, and its zero is a zero")
  void approvalsNotRouted() {
    UUID a = business(() -> purchase(0, 2, 5, 1, false), () -> payment(4), () -> customer(7));

    JsonObject data = asManager(a);

    JsonObject orders = item(data, Kind.PURCHASE_ORDER_APPROVAL);
    assertThat(orders.getInt("count"), is(0));
    assertThat(
        orders.getString("note"),
        is("Approval limits are not set, so orders are not held for approval"));
    for (Kind other : Kind.values()) {
      if (other != Kind.PURCHASE_ORDER_APPROVAL) {
        assertThat(other.name(), item(data, other).isNull("note"), is(true));
      }
    }
    assertThat(unreachable(data), empty());
  }

  @Test
  @DisplayName(
      "A count that stopped at the cap says capped; one below it, zero and an unknown do not")
  void aCountAtTheCapIsCapped() {
    int cap = PendingWorkCount.CAP;
    UUID a =
        business(
            () -> purchase(cap - 1, cap, cap + 1, 0, true),
            () -> new Answer(500, "boom"),
            () -> customer(cap));

    JsonObject data = asManager(a);

    assertThat(counts(data), contains((long) cap - 1, (long) cap, cap + 1L, 0L, null, (long) cap));
    List<Boolean> capped = new ArrayList<>();
    for (JsonObject i : data.getJsonArray("items").getValuesAs(JsonObject.class)) {
      assertThat(i.getString("kind"), i.containsKey("capped"), is(true));
      capped.add(i.getBoolean("capped"));
    }
    assertThat(capped, contains(false, true, true, false, false, true));
    assertThat(unreachable(data), contains("CARD_REFUND"));
  }

  @Test
  @DisplayName("With the cache off every request asks the three services again")
  void cacheOffReadsEveryTime() {
    UUID a = busyBusiness();

    asManager(a);
    asManager(a);
    asManager(a);

    assertThat(callsFor(PURCHASE, a), is(3L));
    assertThat(callsFor(PAYMENT, a), is(3L));
    assertThat(callsFor(CUSTOMER, a), is(3L));
  }

  // ── who may ask ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An owner and a manager with no claim and no store restriction are answered")
  void ownerAndManagerAreAnswered() {
    UUID a = busyBusiness();

    assertThat(counts(Envelopes.ok(ask(a, "OWNER", null, null, null))).get(0), is(3L));
    assertThat(counts(Envelopes.ok(ask(a, "MANAGER", null, null, null))).get(0), is(3L));
    // An owner is never narrowed away from it, whatever a claim says.
    assertThat(counts(Envelopes.ok(ask(a, "OWNER", "-", null, null))).get(0), is(3L));
    // A manager on a role that holds just this permission (the IT person) is let in.
    assertThat(counts(Envelopes.ok(ask(a, "MANAGER", "system.health", null, null))).get(0), is(3L));
  }

  @Test
  @DisplayName(
      "A manager without the permission: 403 SYSTEM_HEALTH_NOT_PERMITTED, nothing fanned out")
  void managerWithoutThePermission() {
    UUID a = busyBusiness();

    for (String claim : List.of("-", "purchasing.approve", "staff.manage,finance.payments")) {
      try (Response r = ask(a, "MANAGER", claim, null, null)) {
        String body = r.readEntity(String.class);
        assertThat(claim + ": " + body, r.getStatus(), is(403));
        assertThat(body, containsString("SYSTEM_HEALTH_NOT_PERMITTED"));
      }
    }
    assertThat(downstreamCalls(a), is(0L));
  }

  @Test
  @DisplayName("A manager held to stores: 403 BUSINESS_WIDE_ONLY, nothing fanned out")
  void storeHeldManager() {
    UUID a = busyBusiness();
    String store = Ids.newId().toString();

    for (String stores : List.of(store, store + "," + Ids.newId())) {
      try (Response r = ask(a, "MANAGER", null, stores, null)) {
        String body = r.readEntity(String.class);
        assertThat(body, r.getStatus(), is(403));
        assertThat(body, containsString("BUSINESS_WIDE_ONLY"));
      }
    }
    assertThat(downstreamCalls(a), is(0L));
  }

  @Test
  @DisplayName("The permission is judged before the store hold: no permission is told so first")
  void permissionBeforeStoreHold() {
    UUID a = busyBusiness();

    try (Response r = ask(a, "MANAGER", "-", Ids.newId().toString(), null)) {
      String body = r.readEntity(String.class);
      assertThat(r.getStatus(), is(403));
      assertThat(body, containsString("SYSTEM_HEALTH_NOT_PERMITTED"));
    }
  }

  @Test
  @DisplayName(
      "A storekeeper, a cashier, a shopper and a caller with no role are refused, even naming the permission")
  void notManagement() {
    UUID a = busyBusiness();

    for (String roles :
        new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER", "STOREKEEPER,CASHIER"}) {
      for (String claim : new String[] {null, "system.health"}) {
        try (Response r = ask(a, roles, claim, null, null)) {
          String body = r.readEntity(String.class);
          assertThat(roles + " " + claim + ": " + body, r.getStatus(), is(403));
          assertThat(body, not(containsString("\"count\"")));
        }
      }
    }
    try (Response r = ask(a, null, null, null, null)) {
      assertThat(r.getStatus(), is(403));
    }
    assertThat(downstreamCalls(a), is(0L));
  }

  @Test
  @DisplayName("A platform administrator, who belongs to no business, is not given one's figures")
  void noBusinessNoAnswer() {
    try (Response r = ask(null, "PLATFORM_ADMIN", null, null, null)) {
      assertThat(r.getStatus(), is(401));
    }
  }

  // ── whose business ─────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Each downstream call is the caller's business, as a manager, with nothing else on it")
  void callsCarryTheCallersBusinessAndAStaffRole() {
    UUID a = busyBusiness();
    UUID other = busyBusiness();

    asManager(a);

    for (JsonStub stub : List.of(PURCHASE, PAYMENT, CUSTOMER)) {
      List<JsonStub.Call> calls =
          stub.calls().stream().filter(c -> a.toString().equals(c.tenantId())).toList();
      assertThat(calls, hasSize(1));
      JsonStub.Call call = calls.get(0);
      assertThat(call.method(), is("GET"));
      assertThat(call.path(), is(UPSTREAM));
      assertThat(call.query(), nullValue());
      assertThat(call.header("x-roles"), is("MANAGER"));
      assertThat(call.header("x-store-ids"), nullValue());
      assertThat(call.header("x-permissions"), nullValue());
    }
    assertThat(downstreamCalls(other), is(0L));
  }

  @Test
  @DisplayName("A business named in the query is ignored: the caller's own is the one asked about")
  void tenantIdInTheQueryIsIgnored() {
    UUID mine = business(() -> purchase(0, 0, 0, 0, true), () -> payment(0), () -> customer(0));
    UUID theirs = busyBusiness();

    JsonObject data = Envelopes.ok(ask(mine, "MANAGER", null, null, theirs.toString()));

    assertThat(counts(data), contains(0L, 0L, 0L, 0L, 0L, 0L));
    assertThat(downstreamCalls(theirs), is(0L));
    assertThat(downstreamCalls(mine), is(3L));
  }

  @Test
  @DisplayName(
      "Another business's people of every role, naming our store, are given none of our figures")
  void anotherBusinessSeesNothingOfOurs() {
    UUID ours = busyBusiness();
    UUID theirs = business(() -> purchase(0, 0, 0, 0, true), () -> payment(0), () -> customer(0));
    String ourStore = Ids.newId().toString();
    long ourCallsBefore = downstreamCalls(ours);

    // Their management is answered with their own figures: zeros, and none of ours.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      JsonObject data = Envelopes.ok(ask(theirs, role, null, null, null));
      assertThat(role, counts(data), contains(0L, 0L, 0L, 0L, 0L, 0L));
    }
    // Held to our store they are refused as any store-held caller is.
    try (Response r = ask(theirs, "MANAGER", null, ourStore, null)) {
      assertThat(r.getStatus(), is(403));
      assertThat(r.readEntity(String.class), containsString("BUSINESS_WIDE_ONLY"));
    }
    // Staff below management and a shopper are refused outright.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      try (Response r = ask(theirs, role, null, ourStore, null)) {
        String body = r.readEntity(String.class);
        assertThat(role + ": " + body, r.getStatus(), is(403));
        assertThat(body, not(containsString("\"count\"")));
      }
    }

    assertThat(
        "our services were asked on their behalf", downstreamCalls(ours), is(ourCallsBefore));
    // And ours still sees ours.
    assertThat(counts(asManager(ours)), contains(3L, 2L, 5L, 1L, 4L, 7L));
  }

  @Test
  @DisplayName("Two businesses asking together each get their own figures")
  void concurrentBusinessesDoNotMix() throws Exception {
    UUID a = busyBusiness();
    UUID b = business(() -> purchase(9, 8, 7, 6, false), () -> payment(5), () -> customer(4));
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<JsonObject>> asked = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        UUID who = i % 2 == 0 ? a : b;
        asked.add(pool.submit(() -> asManager(who)));
      }
      for (int i = 0; i < asked.size(); i++) {
        try {
          List<Long> got = counts(asked.get(i).get());
          assertThat(
              "request " + i,
              got,
              i % 2 == 0 ? contains(3L, 2L, 5L, 1L, 4L, 7L) : contains(9L, 8L, 7L, 6L, 5L, 4L));
        } catch (ExecutionException e) {
          throw new AssertionError("request " + i + " failed", e.getCause());
        }
      }
    }
  }
}
