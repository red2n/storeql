package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gapless legal receipt sequence.
 *
 * <p>Fiscal law in several markets requires consecutive receipt numbers with no holes, and requires
 * that the absence of holes can be shown to an inspector. The interesting tests here are the ones
 * about what must <em>not</em> happen: a number burned by a sale that never completes, a second
 * number for one sale, and a voided receipt quietly closing its own gap.
 */
@HelidonTest
class FiscalReceiptIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(FiscalReceiptIT.T, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  private static final String T = "01a090ae-611e-702a-9bdf-bcc7032115c4";
  private static final String S = "01a090ae-611e-7035-a4da-400bf673cfe8";
  private static final String S2 = "01a090ae-611e-703a-b34c-b0ca607d8240";
  private static final String V = "01a090ae-611e-7055-9838-5de027ce9e0a";

  @Inject WebTarget target;
  @Inject com.storeql.order.service.OrderService orderService;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response post(String path, String json, String tenant) {
    var req = target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER");
    // A void is retryable, so it carries a key: a fresh one for each attempt here.
    if (path.endsWith("/void")) req = req.header("Idempotency-Key", Ids.newId().toString());
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Placing an order needs an Idempotency-Key (golden rule 11); a fresh one per call. */
  private Response place(String json, String tenant) {
    return target
        .path("/orders")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String tenant, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) {
      t = t.queryParam(params[i], params[i + 1]);
    }
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  private static String extractId(String json) {
    var m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  /** Places a POS order and confirms it — a completed sale. Returns the order id. */
  private String sell(String store, String total) {
    Response r =
        place(
            "{\"storeId\":\""
                + store
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":"
                + total
                + "}],"
                + "\"currency\":\"GBP\"}",
            T);
    String placed = r.readEntity(String.class);
    assertThat(placed, r.getStatus(), is(201));
    String id = extractId(placed);
    assertThat(post("/orders/" + id + "/confirm", "{}", T).getStatus(), is(200));
    return id;
  }

  private String placeOnly(String store) {
    Response r =
        place(
            "{\"storeId\":\""
                + store
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],"
                + "\"currency\":\"GBP\"}",
            T);
    return extractId(r.readEntity(String.class));
  }

  private long numberOf(String orderId) {
    Response r = get("/admin/orders/" + orderId + "/fiscal-receipt", T);
    assertThat(r.getStatus(), is(200));
    var m = Pattern.compile("\"number\":(\\d+)").matcher(r.readEntity(String.class));
    assertThat(m.find(), is(true));
    return Long.parseLong(m.group(1));
  }

  private String audit(String store) {
    return get(
            "/admin/fiscal-receipts/audit",
            T,
            "storeId",
            store,
            "period",
            String.valueOf(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear()))
        .readEntity(String.class);
  }

  // ── the sequence ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A completed sale is numbered without anyone asking")
  void numberedOnConfirm() {
    String order = sell(S, "10.00");
    Response r = get("/admin/orders/" + order + "/fiscal-receipt", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    // A sequence that only numbers the sales somebody remembered to print is not a sequence.
    assertThat(body, containsString("\"number\":"));
    assertThat(body, containsString("\"fullNumber\":"));
  }

  @Test
  @DisplayName("Numbers run consecutively, one per sale")
  void consecutive() {
    String a = sell(S2, "1.00");
    String b = sell(S2, "2.00");
    String c = sell(S2, "3.00");
    long na = numberOf(a);
    assertThat(numberOf(b), is(na + 1));
    assertThat(numberOf(c), is(na + 2));
  }

  @Test
  @DisplayName("A basket that is never paid for burns no number")
  void pendingBurnsNothing() {
    String store = Ids.newId().toString();
    String abandoned = placeOnly(store);

    // Issuing for a PENDING order is refused — this is where gaps come from.
    Response refused = post("/admin/orders/" + abandoned + "/fiscal-receipt", "{}", T);
    assertThat(refused.getStatus(), is(400));
    assertThat(refused.readEntity(String.class), containsString("ORDER_NOT_SELLABLE"));

    // The next real sale still gets number 1, not 2.
    String real = sell(store, "9.99");
    assertThat(numberOf(real), is(1L));
  }

  @Test
  @DisplayName("Reprinting returns the number already issued, never a second one")
  void issuingTwiceIsIdempotent() {
    String order = sell(Ids.newId().toString(), "4.00");
    long first = numberOf(order);

    Response again = post("/admin/orders/" + order + "/fiscal-receipt", "{}", T);
    assertThat(again.getStatus(), is(200));
    var m = Pattern.compile("\"number\":(\\d+)").matcher(again.readEntity(String.class));
    assertThat(m.find(), is(true));
    // Two numbers for one sale is how a day's takings get counted twice.
    assertThat(Long.parseLong(m.group(1)), is(first));
  }

  @Test
  @DisplayName("Two stores keep separate sequences, and both start at one")
  void perStoreSequences() {
    String x = Ids.newId().toString();
    String y = Ids.newId().toString();
    assertThat(numberOf(sell(x, "1.00")), is(1L));
    assertThat(numberOf(sell(y, "1.00")), is(1L));
    assertThat(numberOf(sell(x, "2.00")), is(2L));
  }

  @Test
  @DisplayName("A voided sale keeps its number, and the sequence stays intact")
  void voidKeepsTheNumber() {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    String voided = sell(store, "2.00");
    sell(store, "3.00");
    long n = numberOf(voided);

    assertThat(
        post("/orders/" + voided + "/void", "{\"reason\":\"wrong item scanned\"}", T).getStatus(),
        is(200));

    Response r = get("/admin/orders/" + voided + "/fiscal-receipt", T);
    String body = r.readEntity(String.class);
    // Ring the sale, take the cash, void the receipt, close the gap, and the till balances. The
    // number staying is what stops that.
    assertThat(body, containsString("\"number\":" + n));
    assertThat(body, containsString("\"voidedAt\""));
    assertThat(body, containsString("wrong item scanned"));
    assertThat(audit(store), containsString("\"intact\":true"));
  }

  @Test
  @DisplayName("A till sale is numbered when the payment that completes it lands")
  void aTillSaleIsNumberedWhenPaymentCompletesIt() {
    // The first version of this sequence hooked only confirmOrder, which the till never calls:
    // till sales are confirmed by payment capture. So it numbered the sales a manager confirmed by
    // hand and almost none of the ones rung up at a till — the ones fiscal law is written about.
    String store = Ids.newId().toString();
    String orderId = placeOnly(store);
    UUID tenant = Ids.parse(T);
    UUID order = Ids.parse(orderId);

    // A split tender: nothing is numbered until the sale is complete.
    orderService.handlePaymentCaptured(tenant, order, Ids.newId(), new BigDecimal("2.00"), "CASH");
    assertThat(get("/admin/orders/" + orderId + "/fiscal-receipt", T).getStatus(), is(404));

    orderService.handlePaymentCaptured(tenant, order, Ids.newId(), new BigDecimal("3.00"), "CARD");
    assertThat(numberOf(orderId), is(1L));
  }

  private Response getAs(String path, String tenant, String roles) {
    return target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", roles).get();
  }

  @Test
  @DisplayName("The till can read the number it has to print; a stranger cannot")
  void theTillCanReadItsReceiptNumber() {
    String store = Ids.newId().toString();
    String orderId = placeOnly(store);
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(orderId), Ids.newId(), new BigDecimal("5.00"));

    Response asCashier = getAs("/orders/" + orderId + "/fiscal-receipt", T, "CASHIER");
    assertThat(asCashier.getStatus(), is(200));
    assertThat(asCashier.readEntity(String.class), containsString("\"number\":1"));

    // The admin route is management-only, which is why the till could never print this before.
    assertThat(
        getAs("/admin/orders/" + orderId + "/fiscal-receipt", T, "CASHIER").getStatus(), is(403));

    // A customer who did not buy it gets the same 404 as an order that does not exist.
    assertThat(getAs("/orders/" + orderId + "/fiscal-receipt", T, "CUSTOMER").getStatus(), is(404));
  }

  // ── the audit ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An unbroken series audits as intact, with no gaps listed")
  void intactSeries() {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    sell(store, "2.00");
    sell(store, "3.00");

    String body = audit(store);
    assertThat(body, containsString("\"intact\":true"));
    assertThat(body, containsString("\"gaps\":[]"));
    assertThat(body, containsString("\"issued\":3"));
    assertThat(body, containsString("\"expected\":3"));
  }

  @Test
  @DisplayName("A hole is found, and reported with its range rather than a count")
  void aHoleIsFound() throws Exception {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    String gone = sell(store, "2.00");
    String alsoGone = sell(store, "3.00");
    sell(store, "4.00");

    // Reach past the API and delete two rows — the thing the API will not let anyone do, done
    // directly, so the audit has something real to find.
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st =
            c.prepareStatement("DELETE FROM \"order\".fiscal_receipts WHERE order_id = ANY (?)")) {
      st.setArray(1, c.createArrayOf("uuid", new Object[] {Ids.parse(gone), Ids.parse(alsoGone)}));
      st.executeUpdate();
    }

    String body = audit(store);
    assertThat(body, containsString("\"intact\":false"));
    // Contiguous holes are one gap with a range, not two findings — "2 to 3" is what gets
    // explained to an inspector.
    assertThat(body, containsString("\"from\":2"));
    assertThat(body, containsString("\"to\":3"));
    assertThat(body, containsString("\"issued\":2"));
    assertThat(body, containsString("\"expected\":4"));
  }

  @Test
  @DisplayName("The audit is scoped to one tenant's store")
  void auditIsTenantScoped() {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    String other =
        get(
                "/admin/fiscal-receipts/audit",
                "01a090ae-611e-701d-9d60-a9d7516ed03b",
                "storeId",
                store,
                "period",
                String.valueOf(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear()))
            .readEntity(String.class);
    assertThat(other, containsString("\"issued\":0"));
    assertThat(other, containsString("\"intact\":true"));
  }

  // ── the property the whole design exists for ───────────────────────────────

  @Test
  @DisplayName("Eight tills selling at once produce eight consecutive numbers, no duplicates")
  void concurrentTillsDoNotCollide() throws Exception {
    String store = Ids.newId().toString();
    int tills = 8;

    List<Callable<String>> work = new ArrayList<>();
    for (int i = 0; i < tills; i++) {
      final int n = i;
      work.add(() -> sell(store, "1.0" + n));
    }

    List<String> orders = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(tills)) {
      for (var f : pool.invokeAll(work)) {
        orders.add(f.get());
      }
    }

    var numbers = orders.stream().map(this::numberOf).sorted().toList();
    // This is the trade the design makes: the counter row serialises concurrent tills, which a
    // SEQUENCE would not have to do — and a SEQUENCE would gap on the first aborted transaction.
    assertThat(numbers.size(), is(tills));
    assertThat(numbers.get(0), is(1L));
    assertThat(numbers.get(tills - 1), is((long) tills));
    assertThat("duplicate numbers issued", numbers.stream().distinct().count(), is((long) tills));
    assertThat(audit(store), containsString("\"intact\":true"));
    assertThat(audit(store), not(containsString("\"from\"")));
  }

  // ── the series and the wait ────────────────────────────────────────────────

  private Response putAs(String path, String json, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", roles)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String seriesJson(String store, String period, String prefix) {
    return "{\"storeId\":\""
        + store
        + "\",\"seriesCode\":\"MAIN\",\"period\":\""
        + period
        + "\",\"prefix\":\""
        + prefix
        + "\"}";
  }

  private static String thisYear() {
    return String.valueOf(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear());
  }

  @Test
  @DisplayName("A prefix set on a series prints in front of every number issued after it")
  void prefixPrintsOnTheNextNumber() {
    String store = Ids.newId().toString();
    String year = thisYear();
    Response set =
        putAs("/admin/fiscal-receipts/series", seriesJson(store, year, "gb-ldn-01"), "OWNER");
    assertThat(set.getStatus(), is(200));
    String body = set.readEntity(String.class);
    // Upper-cased, and the counter opened at one — setting a prefix hands out no number.
    assertThat(body, containsString("\"prefix\":\"GB-LDN-01\""));
    assertThat(body, containsString("\"nextNumber\":1"));
    String order = sell(store, "3.00");
    String receipt = get("/admin/orders/" + order + "/fiscal-receipt", T).readEntity(String.class);
    assertThat(receipt, containsString("\"fullNumber\":\"GB-LDN-01-"));
    assertThat(receipt, containsString("\"number\":1"));
    // And the store's series lists it, with the counter moved on.
    String listed =
        get("/admin/fiscal-receipts/series", T, "storeId", store).readEntity(String.class);
    assertThat(listed, containsString("\"seriesCode\":\"MAIN\""));
    assertThat(listed, containsString("\"nextNumber\":2"));
  }

  @Test
  @DisplayName("Changing the prefix renumbers nothing: documents already issued keep theirs")
  void changingThePrefixLeavesIssuedDocumentsAlone() {
    String store = Ids.newId().toString();
    String year = thisYear();
    assertThat(
        putAs("/admin/fiscal-receipts/series", seriesJson(store, year, "OLD"), "OWNER").getStatus(),
        is(200));
    String first = sell(store, "1.00");
    assertThat(
        putAs("/admin/fiscal-receipts/series", seriesJson(store, year, "NEW"), "OWNER").getStatus(),
        is(200));
    String second = sell(store, "2.00");
    String a = get("/admin/orders/" + first + "/fiscal-receipt", T).readEntity(String.class);
    String b = get("/admin/orders/" + second + "/fiscal-receipt", T).readEntity(String.class);
    assertThat(a, containsString("\"fullNumber\":\"OLD-"));
    assertThat(b, containsString("\"fullNumber\":\"NEW-"));
    // Same counter: 1 then 2. A prefix change that restarted the count would be a second series.
    assertThat(numberOf(second), is(numberOf(first) + 1));
  }

  @Test
  @DisplayName(
      "A prefix is letters, digits and hyphens; a period is a year — anything else is refused")
  void badPrefixOrPeriodIsRefused() {
    String store = Ids.newId().toString();
    Response spaces =
        putAs(
            "/admin/fiscal-receipts/series",
            seriesJson(store, thisYear(), "not a prefix!"),
            "OWNER");
    assertThat(spaces.getStatus(), is(400));
    assertThat(spaces.readEntity(String.class), containsString("RECEIPT_PREFIX_INVALID"));
    Response tooLong =
        putAs(
            "/admin/fiscal-receipts/series",
            seriesJson(store, thisYear(), "ABCDEFGHIJKLMNOPQ"),
            "OWNER");
    assertThat(tooLong.getStatus(), is(400));
    Response period =
        putAs("/admin/fiscal-receipts/series", seriesJson(store, "this year", "GB"), "OWNER");
    assertThat(period.getStatus(), is(400));
    assertThat(period.readEntity(String.class), containsString("RECEIPT_PERIOD_INVALID"));
    // Nothing was opened by the refusals.
    assertThat(
        get("/admin/fiscal-receipts/series", T, "storeId", store).readEntity(String.class),
        containsString("\"data\":[]"));
  }

  @Test
  @DisplayName("A cashier can neither read the counters nor set a prefix; another tenant sees none")
  void countersAreManagementOnly() {
    String store = Ids.newId().toString();
    assertThat(
        putAs("/admin/fiscal-receipts/series", seriesJson(store, thisYear(), "GB"), "OWNER")
            .getStatus(),
        is(200));
    assertThat(
        putAs("/admin/fiscal-receipts/series", seriesJson(store, thisYear(), "X"), "CASHIER")
            .getStatus(),
        is(403));
    assertThat(
        target
            .path("/admin/fiscal-receipts/series")
            .queryParam("storeId", store)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get()
            .getStatus(),
        is(403));
    // The refusal changed nothing.
    assertThat(
        get("/admin/fiscal-receipts/series", T, "storeId", store).readEntity(String.class),
        containsString("\"prefix\":\"GB\""));
    String other = Ids.newId().toString();
    assertThat(
        target
            .path("/admin/fiscal-receipts/series")
            .queryParam("storeId", store)
            .request()
            .header("X-Tenant-Id", other)
            .header("X-Roles", "OWNER")
            .get()
            .readEntity(String.class),
        containsString("\"data\":[]"));
  }

  @Test
  @DisplayName("A till that asks with ?wait= gets the number in one request, once payment lands")
  void theTillWaitsServerSideForTheNumber() throws Exception {
    String store = Ids.newId().toString();
    String orderId = placeOnly(store);
    UUID tenant = Ids.parse(T);
    UUID order = Ids.parse(orderId);
    // Before anything lands, a bounded wait still ends in 404 — it does not hang and does not lie.
    long started = System.nanoTime();
    Response early =
        target
            .path("/orders/" + orderId + "/fiscal-receipt")
            .queryParam("wait", "1")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get();
    assertThat(early.getStatus(), is(404));
    assertThat(early.readEntity(String.class), containsString("ORDER_RECEIPT_NOT_ISSUED"));
    assertThat((System.nanoTime() - started) / 1_000_000L >= 900L, is(true));
    // The payment that completes the sale lands while the till is waiting.
    Thread payer =
        new Thread(
            () -> {
              try {
                Thread.sleep(600);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              orderService.handlePaymentCaptured(
                  tenant, order, Ids.newId(), new BigDecimal("5.00"));
            });
    payer.start();
    Response numbered =
        target
            .path("/orders/" + orderId + "/fiscal-receipt")
            .queryParam("wait", "10")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get();
    payer.join();
    assertThat(numbered.getStatus(), is(200));
    assertThat(numbered.readEntity(String.class), containsString("\"number\":1"));
    // A customer who did not buy it cannot wait on it either: the same 404, at once.
    assertThat(
        target
            .path("/orders/" + orderId + "/fiscal-receipt")
            .queryParam("wait", "10")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .get()
            .getStatus(),
        is(404));
  }

  @Test
  @DisplayName("The wait is capped: asking for an hour gets at most twenty seconds")
  void theWaitIsCapped() {
    String store = Ids.newId().toString();
    String orderId = placeOnly(store);
    long started = System.nanoTime();
    Response r =
        target
            .path("/orders/" + orderId + "/fiscal-receipt")
            .queryParam("wait", "3600")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get();
    long ms = (System.nanoTime() - started) / 1_000_000L;
    assertThat(r.getStatus(), is(404));
    // Twenty seconds is the most a request may hold a worker; a till that wants longer asks again.
    assertThat("held for " + ms + "ms", ms < 25_000L, is(true));
    assertThat("returned early at " + ms + "ms", ms >= 19_000L, is(true));
  }

  // ── 18.4: the hash chain and the register export ───────────────────────────

  private static String hashOfNumber(String store, long number) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st =
            c.prepareStatement(
                "SELECT hash FROM \"order\".fiscal_receipts WHERE tenant_id = ? AND store_id = ?"
                    + " AND number = ?")) {
      st.setObject(1, Ids.parse(T));
      st.setObject(2, Ids.parse(store));
      st.setLong(3, number);
      try (var rs = st.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  @Test
  @DisplayName(
      "Every document carries a hash that chains to the one before, and the audit re-derives them")
  void theChainIsIntactAndVerifiable() throws Exception {
    String store = Ids.newId().toString();
    String first = sell(store, "1.00");
    sell(store, "2.00");
    sell(store, "3.00");
    String one = get("/admin/orders/" + first + "/fiscal-receipt", T).readEntity(String.class);
    assertThat(one, containsString("\"prevHash\":\"GENESIS\""));
    assertThat(one.matches("(?s).*\"hash\":\"[0-9a-f]{64}\".*"), is(true));
    assertThat(hashOfNumber(store, 1), is(not(hashOfNumber(store, 2))));
    String audit = audit(store);
    assertThat(audit, containsString("\"chainIntact\":true"));
    assertThat(audit, containsString("\"chainFrom\":1"));
    assertThat(audit, containsString("\"chainBrokenAt\":null"));
  }

  @Test
  @DisplayName("A figure changed on a stored document breaks the chain at that document")
  void tamperingBreaksTheChain() throws Exception {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    sell(store, "2.00");
    sell(store, "3.00");
    // Reach past the API and change the one thing the API will not: a document's figures.
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st =
            c.prepareStatement(
                "UPDATE \"order\".fiscal_receipts SET gross_total = gross_total + 100"
                    + " WHERE tenant_id = ? AND store_id = ? AND number = 2")) {
      st.setObject(1, Ids.parse(T));
      st.setObject(2, Ids.parse(store));
      assertThat(st.executeUpdate(), is(1));
    }
    String audit = audit(store);
    // The sequence is still intact — no number is missing. The chain says which document lies.
    assertThat(audit, containsString("\"intact\":true"));
    assertThat(audit, containsString("\"chainIntact\":false"));
    assertThat(audit, containsString("\"chainBrokenAt\":2"));
  }

  @Test
  @DisplayName("Documents issued before the chain are passed over; the chain begins at the next")
  void documentsBeforeTheChainAreSkipped() throws Exception {
    String store = Ids.newId().toString();
    sell(store, "1.00");
    sell(store, "2.00");
    sell(store, "3.00");
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st =
            c.prepareStatement(
                "UPDATE \"order\".fiscal_receipts SET hash = NULL, prev_hash = NULL"
                    + " WHERE tenant_id = ? AND store_id = ? AND number = 1")) {
      st.setObject(1, Ids.parse(T));
      st.setObject(2, Ids.parse(store));
      st.executeUpdate();
    }
    String audit = audit(store);
    assertThat(audit, containsString("\"chainIntact\":true"));
    assertThat(audit, containsString("\"chainFrom\":2"));
  }

  @Test
  @DisplayName(
      "The register exports with every hash, as CSV or as JSON with the lines; management only")
  void theRegisterExports() {
    String store = Ids.newId().toString();
    String year = thisYear();
    sell(store, "1.00");
    String voided = sell(store, "2.00");
    post("/orders/" + voided + "/void", "{\"reason\":\"wrong, item\"}", T);
    Response csv = get("/admin/fiscal-receipts/export", T, "storeId", store, "period", year);
    assertThat(csv.getStatus(), is(200));
    assertThat(csv.getMediaType().toString(), containsString("text/csv"));
    String text = csv.readEntity(String.class);
    String[] rows = text.strip().split("\n");
    assertThat(rows.length, is(3));
    assertThat(
        rows[0],
        is(
            "number,fullNumber,issuedAt,orderId,currency,grossTotal,taxTotal,voidedAt,voidReason,prevHash,hash"));
    assertThat(rows[1], containsString(",GENESIS,"));
    // A reason with a comma in it is quoted, so the file stays a file.
    assertThat(rows[2], containsString("\"wrong, item\""));
    assertThat(rows[2].matches(".*,[0-9a-f]{64},[0-9a-f]{64}$"), is(true));
    Response json =
        get("/admin/fiscal-receipts/export", T, "storeId", store, "period", year, "format", "json");
    assertThat(json.getStatus(), is(200));
    String body = json.readEntity(String.class);
    assertThat(body, containsString("\"documents\":["));
    assertThat(body, containsString("\"lines\":[{\"variantId\":\"" + V + "\""));
    assertThat(body, containsString("\"qty\":1"));
    assertThat(
        target
            .path("/admin/fiscal-receipts/export")
            .queryParam("storeId", store)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get()
            .getStatus(),
        is(403));
  }
}
