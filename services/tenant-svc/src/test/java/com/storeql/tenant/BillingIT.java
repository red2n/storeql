package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Concurrency;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Subscription billing (21.9), over HTTP and a real database.
 *
 * <p>The assertions worth an integration test are the ones only a database can answer: that an
 * invoice number is gapless <em>under concurrency</em>, that a period is invoiced once however many
 * times the run is run, and that the four tax treatments come out of the jurisdiction data rather
 * than out of a constant. The arithmetic is unit-tested without a database in {@code ProrationTest}
 * and {@code BillingTaxTest}, where it belongs.
 */
@HelidonTest
// The run may be asked for a day other than today. On here for the same reason it is on in compose
// and off in k8s: a year of periods has to be drivable in seconds for the engine to be testable at
// all, and a platform administrator being able to bill next March is not a feature.
@AddConfig(key = "storeql.billing.test-clock.enabled", value = "true")
class BillingIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String BILLING = "/platform/billing";
  private static final String PLANS = "/platform/plans";
  private static final String MINE = "/admin/tenant/billing";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private record Answer(int status, JsonObject body, String text) {

    JsonObject data() {
      return body.getJsonObject("data");
    }

    java.util.List<JsonObject> list() {
      return body.getJsonArray("data").getValuesAs(JsonObject.class);
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  /**
   * One call.
   *
   * <p>A query string is split off and passed as parameters: {@link WebTarget#path(String)}
   * percent-encodes a {@code ?}, so {@code path("/x?limit=1")} asks for a path that literally
   * contains a question mark, matches no route, and answers 404 with a body that is not JSON. Same
   * trap as WebClient folding a {@code ?} into the path.
   */
  private Answer call(String method, String path, String json, String tenant, String roles) {
    return call(method, path, json, tenant, roles, null);
  }

  /** One call, as staff who name the stores they work in. */
  private Answer call(
      String method, String path, String json, String tenant, String roles, String stores) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        t =
            eq < 0
                ? t.queryParam(pair, "")
                : t.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    Invocation.Builder b = t.request(MediaType.APPLICATION_JSON).header("X-User-Id", Ids.newId());
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    Entity<String> body = Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON);
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "PUT" -> b.put(body);
          default -> b.post(body);
        };
    String text = r.readEntity(String.class);
    return new Answer(r.getStatus(), asObject(text), text);
  }

  /** The body as an object, or an empty one — never a parser exception that hides the body. */
  private static JsonObject asObject(String text) {
    if (text == null || text.isBlank()) return JsonObject.EMPTY_JSON_OBJECT;
    try {
      return Json.createReader(new StringReader(text)).readObject();
    } catch (RuntimeException e) {
      return JsonObject.EMPTY_JSON_OBJECT;
    }
  }

  private Answer platform(String method, String path, String json) {
    return call(method, path, json, null, "PLATFORM_ADMIN");
  }

  private Answer owner(String method, String path, String json, String tenantId) {
    return call(method, path, json, tenantId, "OWNER");
  }

  /** The platform's own identity, without which nothing can be billed. */
  private void sellerIs(String country, String rate) {
    Answer saved =
        platform(
            "PUT",
            BILLING + "/profile",
            "{\"legalName\":\"StoreQL Platform Ltd\",\"addressLine1\":\"1 Quay Street\","
                + "\"city\":\"Dublin\",\"postcode\":\"D02 XY45\",\"country\":\""
                + country
                + "\",\"vatNumber\":\"IE1234567X\",\"invoicePrefix\":\"INV\","
                + "\"paymentTermsDays\":14,\"taxRate\":\""
                + rate
                + "\"}");
    assertThat(saved.text(), saved.status(), is(200));
  }

  /** A plan on sale in euro at {@code amount}, with no trial so the first period bills at once. */
  private String sellablePlan(String code, String amount) {
    Answer written =
        platform(
            "POST",
            PLANS,
            "{\"code\":\""
                + code
                + "\",\"name\":\""
                + code
                + " plan\",\"billingInterval\":\"MONTH\",\"trialDays\":0,\"isPublic\":true,"
                + "\"sortOrder\":1}");
    assertThat(written.text(), written.status(), is(201));
    String id = written.data().getString("id");
    assertThat(
        platform(
                "POST",
                PLANS + "/" + id + "/prices",
                "{\"currency\":\"EUR\",\"amount\":" + amount + "}")
            .status(),
        is(200));
    assertThat(platform("POST", PLANS + "/" + id + "/activate", null).status(), is(200));
    return id;
  }

  private String onboard(String name) {
    return TenantOnboarding.onboard(target, name, "IE", "EUR");
  }

  /** A store of the business's own, so another business's staff can name its id. */
  private String storeOf(String tenantId) {
    Answer store =
        owner(
            "POST",
            "/admin/stores",
            "{\"name\":\"Front shop\",\"code\":\"FS-"
                + Ids.newId()
                + "\",\"country\":\"IE\",\"timezone\":\"Europe/Dublin\"}",
            tenantId);
    assertThat(store.text(), store.status(), is(201));
    return store.data().getString("id");
  }

  private List<JsonObject> invoicesOf(String tenantId) {
    return owner("GET", MINE + "/invoices?limit=100", null, tenantId).list();
  }

  // ── the tests ──────────────────────────────────────────────────────────────

  // "Nothing is billed until the platform has said who it is" is asserted by k6 `billing-flow`
  // instead. These tests share one database and the profile is a singleton, so once any other test
  // has saved it, a test asserting it is unset is really asserting the order the runner picked. k6
  // runs against a stack built from scratch, where the claim is actually true.

  @Test
  @DisplayName("Signing up subscribes the business and bills its first period in advance")
  void signingUpIsSubscribing() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-FIRST-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));

    String shop = onboard("Bills in advance");
    JsonObject sub = owner("GET", MINE, null, shop).data().getJsonObject("subscription");
    assertThat(sub.getString("status"), is("ACTIVE"));
    assertThat(new BigDecimal(sub.get("priceAmount").toString()), is(new BigDecimal("10.0000")));

    List<JsonObject> invoices = invoicesOf(shop);
    assertThat("the first period is billed the day it starts", invoices.size(), is(1));
    JsonObject invoice = invoices.get(0);
    assertThat(invoice.getString("status"), is("OPEN"));
    assertThat(invoice.getString("taxTreatment"), is("DOMESTIC"));
    assertThat("the run raises periods", invoice.getString("kind"), is("PERIOD"));
    assertThat(invoice.getString("number"), containsString("INV-"));
    // The platform's own rate, because the business is in the platform's own country.
    assertThat(new BigDecimal(invoice.get("taxRate").toString()), is(new BigDecimal("0.2300")));
    BigDecimal net = new BigDecimal(invoice.get("netAmount").toString());
    BigDecimal tax = new BigDecimal(invoice.get("taxAmount").toString());
    BigDecimal total = new BigDecimal(invoice.get("totalAmount").toString());
    assertThat("an invoice adds up", net.add(tax).compareTo(total), is(0));
  }

  @Test
  @DisplayName("An upgrade the same day raises an adjustment beside the period, not instead of it")
  void aProrationIsNotAPeriod() {
    // The defect the first live run of k6 billing-flow found. Both invoices have the same
    // period_start — the day the business signed up — and uq_invoices_period was written over
    // (subscription, period_start) with nothing to say which invoices are periods, so it refused
    // the
    // proration and the upgrade answered 500. A proration is an adjustment: it can happen more than
    // once inside a period and must not compete for the period's slot.
    sellerIs("IE", "0.2300");
    String tag = Ids.newId().toString().substring(0, 8);
    String small = sellablePlan("IT-ADJ-S-" + tag, "10.00");
    String big = sellablePlan("IT-ADJ-B-" + tag, "20.00");
    assertThat(platform("POST", PLANS + "/" + small + "/default", null).status(), is(200));
    String shop = onboard("Upgrades at once");

    Answer up =
        owner("POST", MINE + "/plan", "{\"planId\":\"" + big + "\",\"when\":\"NOW\"}", shop);
    assertThat(up.text(), up.status(), is(200));

    List<JsonObject> raised = invoicesOf(shop);
    assertThat("the period and the adjustment both exist", raised.size(), is(2));
    Set<String> kinds = raised.stream().map(i -> i.getString("kind")).collect(Collectors.toSet());
    assertThat(kinds, is(Set.of("PERIOD", "ADJUSTMENT")));
    assertThat(
        "and they are for the same period",
        raised.stream().map(i -> i.getString("periodStart")).distinct().count(),
        is(1L));
  }

  @Test
  @DisplayName("A number is gapless though twenty businesses are billed at once")
  void numbersAreGaplessUnderConcurrency() {
    // The one assertion that needs a real database: the counter is taken under its own row lock, in
    // the same transaction as the invoice, so twenty concurrent issues produce twenty consecutive
    // numbers and no holes. A Postgres sequence would not do this — it keeps a number even when the
    // transaction that asked for it rolls back, and a hole is exactly what an auditor asks about.
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-RACE-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));

    List<String> shops = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      shops.add(onboard("Race " + i));
    }

    List<String> numbers =
        shops.stream()
            .flatMap(s -> invoicesOf(s).stream())
            .map(i -> i.getString("number"))
            .sorted()
            .toList();
    assertThat("every business was invoiced", numbers.size(), is(20));
    assertThat("no number was handed out twice", Set.copyOf(numbers).size(), is(20));

    List<Integer> sequence =
        numbers.stream().map(n -> Integer.parseInt(n.substring(n.lastIndexOf('-') + 1))).toList();
    for (int i = 1; i < sequence.size(); i++) {
      assertThat(
          "the sequence has no hole at " + numbers.get(i),
          sequence.get(i) - sequence.get(i - 1),
          is(1));
    }
  }

  @Test
  @DisplayName("A period is invoiced once, however many times the run is run")
  void aPeriodIsInvoicedOnce() throws Exception {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-ONCE-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Billed once");
    int before = invoicesOf(shop).size();

    // A year ahead, three times over, and concurrently: the unique index on (subscription, period
    // start) is what makes this safe, not a check somebody remembered to write.
    Concurrency.inParallel(
        3,
        () ->
            platform(
                "POST", BILLING + "/run?asOf=" + java.time.LocalDate.now().plusMonths(1), null));

    List<String> periods =
        invoicesOf(shop).stream().map(i -> i.getString("periodStart")).collect(Collectors.toList());
    assertThat("more than the first period was billed", periods.size(), greaterThan(before));
    assertThat("and no period twice", Set.copyOf(periods).size(), is(periods.size()));
  }

  @Test
  @DisplayName("An invoice is withdrawn with a reason and keeps its number; a paid one is not")
  void anInvoiceIsNeverEdited() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-VOID-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Never edited");
    JsonObject invoice = invoicesOf(shop).get(0);
    String id = invoice.getString("id");
    String number = invoice.getString("number");

    Answer voided =
        platform("POST", BILLING + "/invoices/" + id + "/void", "{\"reason\":\"raised in error\"}");
    assertThat(voided.text(), voided.status(), is(200));
    JsonObject after = voided.data().getJsonObject("invoice");
    assertThat(after.getString("status"), is("VOID"));
    assertThat("the number stays in the sequence", after.getString("number"), is(number));
    assertThat(after.getString("voidedReason"), containsString("raised in error"));

    // Withdrawn once. A second attempt is not idempotent silence: the invoice is no longer open.
    Answer again =
        platform("POST", BILLING + "/invoices/" + id + "/void", "{\"reason\":\"again\"}");
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("INVOICE_NOT_VOIDABLE"));

    // And no money may be applied to it.
    Answer paid =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":1.00,\"method\":\"BANK_TRANSFER\"}");
    assertThat(paid.status(), is(409));
    assertThat(paid.code(), is("INVOICE_NOT_OPEN"));
  }

  @Test
  @DisplayName("Money settles an invoice, and a settled one takes no more")
  void moneySettlesAnInvoice() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-PAID-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Pays up");
    JsonObject invoice = invoicesOf(shop).get(0);
    String id = invoice.getString("id");
    String total = invoice.get("totalAmount").toString();

    Answer part =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":1.00,\"method\":\"BANK_TRANSFER\",\"providerRef\":\"PART-1\"}");
    assertThat(part.text(), part.status(), is(200));
    JsonObject partly = part.data().getJsonObject("invoice");
    assertThat("part-paid is still owed", partly.getString("status"), is("OPEN"));
    assertThat(
        "and the outstanding figure is what is left",
        new BigDecimal(partly.get("outstanding").toString()),
        is(new BigDecimal(total).subtract(new BigDecimal("1.00"))));

    Answer rest =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":"
                + new BigDecimal(total).subtract(new BigDecimal("1.00")).toPlainString()
                + ",\"method\":\"BANK_TRANSFER\",\"providerRef\":\"PART-2\"}");
    assertThat(rest.text(), rest.status(), is(200));
    JsonObject settled = rest.data().getJsonObject("invoice");
    assertThat(settled.getString("status"), is("PAID"));
    assertThat(new BigDecimal(settled.get("outstanding").toString()).signum(), is(0));
    assertThat("both payments are kept", rest.data().getJsonArray("payments").size(), is(2));

    Answer more =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":1.00,\"method\":\"BANK_TRANSFER\",\"providerRef\":\"PART-3\"}");
    assertThat("a settled invoice takes no more", more.status(), is(409));
    assertThat(more.code(), is("INVOICE_NOT_OPEN"));
  }

  @Test
  @DisplayName(
      "A business in another member state with no checked number pays its own country's rate")
  void theTreatmentComesFromTheJurisdictionData() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-VAT-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("German buyer");

    // Notices go to the owner's sign-up address until the business names another (SJ-D72).
    assertThat(
        owner("GET", MINE, null, shop)
            .data()
            .getJsonObject("subscription")
            .getString("billingEmail"),
        is(TenantOnboarding.ownerEmail("German buyer")));

    // It moves its billing address to Germany. Its VAT number, if it has one, is unchecked.
    Answer moved =
        owner("PUT", MINE + "/details", "{\"country\":\"DE\",\"name\":\"Weinhaus\"}", shop);
    assertThat(moved.text(), moved.status(), is(200));
    assertThat(
        "an update that says nothing about the address leaves it standing",
        moved.data().getJsonObject("subscription").getString("billingEmail"),
        is(TenantOnboarding.ownerEmail("German buyer")));
    Answer renamed =
        owner(
            "PUT",
            MINE + "/details",
            "{\"country\":\"DE\",\"billingEmail\":\"accounts@weinhaus.example\"}",
            shop);
    assertThat(renamed.text(), renamed.status(), is(200));
    assertThat(
        "the address the business names is the one kept — it used to be dropped (SJ-D72)",
        renamed.data().getJsonObject("subscription").getString("billingEmail"),
        is("accounts@weinhaus.example"));
    assertThat(
        owner(
                "PUT",
                MINE + "/details",
                "{\"country\":\"DE\",\"billingEmail\":\"not an address\"}",
                shop)
            .status(),
        is(400));
    assertThat(
        "an unchecked number is treated as no number",
        moved.data().getJsonObject("subscription").getJsonObject("buyer").getBoolean("vatChecked"),
        is(false));

    // With no rate set for Germany the run passes this business over and names it, rather than
    // guessing at a rate or stopping every other business's invoice.
    Answer passedOver =
        platform("POST", BILLING + "/run?asOf=" + java.time.LocalDate.now().plusMonths(1), null);
    assertThat(passedOver.text(), passedOver.status(), is(200));
    assertThat(
        "the business the platform has no rate for is named, not silently unbilled",
        passedOver.text(),
        containsString("BILLING_RATE_NOT_SET"));
    assertThat(
        "and it is this business",
        passedOver.data().getJsonArray("skipped").toString(),
        containsString(shop));

    assertThat(
        platform(
                "PUT",
                BILLING + "/vat-rates",
                "{\"country\":\"DE\",\"effectiveFrom\":\"2020-01-01\",\"rate\":\"0.1900\"}")
            .status(),
        is(200));
    Answer ran =
        platform("POST", BILLING + "/run?asOf=" + java.time.LocalDate.now().plusMonths(1), null);
    assertThat(ran.text(), ran.status(), is(200));

    JsonObject latest =
        invoicesOf(shop).stream()
            .filter(i -> "DESTINATION".equals(i.getString("taxTreatment")))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no destination-taxed invoice: " + invoicesOf(shop)));
    assertThat(
        "Germany's rate, not Ireland's",
        new BigDecimal(latest.get("taxRate").toString()),
        is(new BigDecimal("0.1900")));
  }

  @Test
  @DisplayName("A changed VAT number discards the check against the old one")
  void changingTheNumberDiscardsTheCheck() {
    // The rule that stops the reverse charge being obtained by editing a number after a check. It
    // is
    // worth an integration test rather than a unit one because the discarding happens on the way to
    // the database, and what an invoice is then taxed at depends on it.
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-NUM-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Edits its number");

    assertThat(
        owner("PUT", MINE + "/details", "{\"country\":\"DE\",\"vatNumber\":\"DE111111111\"}", shop)
            .status(),
        is(200));
    Answer checked =
        platform(
            "POST",
            BILLING + "/tenants/" + shop + "/vat-check",
            "{\"vatNumber\":\"DE111111111\",\"source\":\"SIMULATED\"}");
    assertThat(checked.text(), checked.status(), is(200));
    assertThat(
        checked
            .data()
            .getJsonObject("subscription")
            .getJsonObject("buyer")
            .getBoolean("vatChecked"),
        is(true));

    Answer swapped =
        owner("PUT", MINE + "/details", "{\"country\":\"DE\",\"vatNumber\":\"DE222222222\"}", shop);
    assertThat(swapped.text(), swapped.status(), is(200));
    JsonObject buyer = swapped.data().getJsonObject("subscription").getJsonObject("buyer");
    assertThat("a new number carries no check", buyer.getBoolean("vatChecked"), is(false));
    assertThat(buyer.getString("vatNumber"), is("DE222222222"));
    assertThat(
        "and the history says why the treatment will change",
        swapped.data().getJsonArray("events").toString(),
        containsString("no longer counts"));
  }

  @Test
  @DisplayName("A business does not write the platform's books")
  void aBusinessDoesNotWriteThePlatformsBooks() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-ABUSE-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String mine = onboard("Mine");
    String theirs = onboard("Theirs");
    String theirInvoice = invoicesOf(theirs).get(0).getString("id");

    // Another business's invoice is not found rather than forbidden: a 403 would confirm the id is
    // real, which is the one thing a guesser wants to know.
    Answer peek = owner("GET", MINE + "/invoices/" + theirInvoice, null, mine);
    assertThat(peek.status(), is(404));
    assertThat(peek.code(), is("INVOICE_NOT_FOUND"));

    assertThat(
        "an owner does not read everybody's receivables",
        owner("GET", BILLING + "/receivables", null, mine).status(),
        is(403));
    // A body that passes validation, so what is measured is the authorisation and not the shape:
    // Bean Validation runs before the method, and a junk body answers 400 before the 403.
    Answer impostor =
        owner(
            "PUT",
            BILLING + "/profile",
            "{\"legalName\":\"Not the platform\",\"addressLine1\":\"1 Nowhere\",\"city\":\"Nowhere\","
                + "\"country\":\"IE\",\"invoicePrefix\":\"XXX\",\"paymentTermsDays\":1,"
                + "\"taxRate\":\"0.0000\"}",
            mine);
    assertThat("nor say who the platform is: " + impostor.text(), impostor.status(), is(403));
    assertThat(
        "a cashier does not read what the business pays",
        call("GET", MINE, null, mine, "CASHIER").status(),
        is(403));
    assertThat(
        "and the platform's own run is not a business's to trigger",
        owner("POST", BILLING + "/run", null, mine).status(),
        is(403));
  }

  @Test
  @DisplayName("The receivables name every invoice's business, and only the platform reads them")
  void theReceivablesNameEachBusiness() {
    // The platform console's receivables are every business's invoices on one screen. A row that
    // does not say whose it is cannot be chased, so each names its business — and a business's own
    // answers carry the same field, which is only ever its own id.
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-OWED-" + Ids.newId().toString().substring(0, 8), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String mine = onboard("Owes the platform");
    String theirs = onboard("Also owes the platform");
    String myStore = storeOf(mine);
    JsonObject myInvoice = invoicesOf(mine).get(0);
    String myInvoiceId = myInvoice.getString("id");
    String theirInvoiceId = invoicesOf(theirs).get(0).getString("id");

    Answer owed = platform("GET", BILLING + "/receivables?limit=100", null);
    assertThat(owed.text(), owed.status(), is(200));
    List<JsonObject> rows = owed.list();
    assertThat("there is something owed", rows.size(), greaterThan(0));
    for (JsonObject row : rows) {
      assertThat("every row names its business: " + row, row.containsKey("tenantId"), is(true));
      // A real business id, in the one form ids take here — not a blank or a placeholder.
      assertThat(Ids.parse(row.getString("tenantId")).toString(), is(row.getString("tenantId")));
    }
    Map<String, String> businessOf =
        rows.stream()
            .collect(
                Collectors.toMap(
                    r -> r.getString("id"), r -> r.getString("tenantId"), (a, b) -> a));
    assertThat(
        "our invoice is owed: " + owed.text(), businessOf.containsKey(myInvoiceId), is(true));
    assertThat("and named as ours", businessOf.get(myInvoiceId), is(mine));
    assertThat("theirs is named as theirs", businessOf.get(theirInvoiceId), is(theirs));

    // A business's own answers are unchanged apart from the field, which is its own id.
    for (String business : List.of(mine, theirs)) {
      for (JsonObject own : invoicesOf(business)) {
        assertThat(
            "an own invoice names its own business", own.getString("tenantId"), is(business));
      }
    }
    Answer file = owner("GET", MINE + "/invoices/" + myInvoiceId, null, mine);
    assertThat(file.text(), file.status(), is(200));
    assertThat(file.data().getJsonObject("invoice").getString("tenantId"), is(mine));

    // Another business's staff of every role, even naming our store, read none of it and change
    // none of it.
    for (String role : List.of("OWNER", "MANAGER", "CASHIER", "STOREKEEPER")) {
      Answer everybodys =
          call("GET", BILLING + "/receivables?limit=100", null, theirs, role, myStore);
      assertThat(role + " does not read the receivables", everybodys.status(), is(403));
      assertThat(everybodys.text(), not(containsString(mine)));
      assertThat(everybodys.text(), not(containsString(myInvoiceId)));

      Answer ours = call("GET", MINE + "/invoices/" + myInvoiceId, null, theirs, role, myStore);
      assertThat(
          role + " does not open our invoice",
          ours.status(),
          is(Set.of("OWNER", "MANAGER").contains(role) ? 404 : 403));
      assertThat(ours.text(), not(containsString(mine)));

      Answer theirList = call("GET", MINE + "/invoices?limit=100", null, theirs, role, myStore);
      if (Set.of("OWNER", "MANAGER").contains(role)) {
        assertThat(theirList.text(), theirList.status(), is(200));
        for (JsonObject own : theirList.list()) {
          assertThat(
              role + " sees only its own business's invoices",
              own.getString("tenantId"),
              is(theirs));
        }
      } else {
        assertThat(role + " does not read what the business pays", theirList.status(), is(403));
      }
      assertThat(theirList.text(), not(containsString(mine)));
      assertThat(theirList.text(), not(containsString(myInvoiceId)));

      Answer pushed =
          call(
              "PUT",
              BILLING + "/invoices/" + myInvoiceId + "/due-date",
              "{\"dueDate\":\"" + java.time.LocalDate.now().plusYears(1) + "\",\"reason\":\"x\"}",
              theirs,
              role,
              myStore);
      assertThat(role + " does not move our due date", pushed.status(), is(403));
      Answer settled =
          call(
              "POST",
              BILLING + "/invoices/" + myInvoiceId + "/payments",
              "{\"amount\":1.00,\"method\":\"BANK_TRANSFER\"}",
              theirs,
              role,
              myStore);
      assertThat(role + " does not pay our invoice down", settled.status(), is(403));
    }

    JsonObject after =
        owner("GET", MINE + "/invoices/" + myInvoiceId, null, mine).data().getJsonObject("invoice");
    assertThat(
        "our invoice is as it was", after.getString("dueDate"), is(myInvoice.getString("dueDate")));
    assertThat(after.getString("status"), is("OPEN"));
    assertThat(
        new BigDecimal(after.get("outstanding").toString()),
        is(new BigDecimal(myInvoice.get("outstanding").toString())));
    assertThat(after.getString("tenantId"), is(mine));
  }
}
