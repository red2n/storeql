package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Concurrency;
import com.storeql.test.Envelopes;
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
    Answer saved = platform("PUT", BILLING + "/profile", profileJson(country, rate));
    assertThat(saved.text(), saved.status(), is(200));
  }

  private static String profileJson(String country, String rate) {
    return "{\"legalName\":\"StoreQL Platform Ltd\",\"addressLine1\":\"1 Quay Street\","
        + "\"city\":\"Dublin\",\"postcode\":\"D02 XY45\",\"country\":\""
        + country
        + "\",\"vatNumber\":\"IE1234567X\",\"invoicePrefix\":\"INV\","
        + "\"paymentTermsDays\":14,\"taxRate\":\""
        + rate
        + "\"}";
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

  // ── refusals the billing surface makes by name ─────────────────────────────

  /**
   * A plan on sale priced only in {@code currency} at {@code amount}, with a trial of {@code
   * trialDays}.
   */
  private String planIn(String code, String currency, String amount, int trialDays) {
    Answer written =
        platform(
            "POST",
            PLANS,
            "{\"code\":\""
                + code
                + "\",\"name\":\""
                + code
                + " plan\",\"billingInterval\":\"MONTH\",\"trialDays\":"
                + trialDays
                + ",\"isPublic\":true,\"sortOrder\":1}");
    assertThat(written.text(), written.status(), is(201));
    String id = written.data().getString("id");
    assertThat(
        platform(
                "POST",
                PLANS + "/" + id + "/prices",
                "{\"currency\":\"" + currency + "\",\"amount\":" + amount + "}")
            .status(),
        is(200));
    assertThat(platform("POST", PLANS + "/" + id + "/activate", null).status(), is(200));
    return id;
  }

  private static String tail() {
    return Ids.newId().toString().substring(28).toUpperCase(java.util.Locale.ROOT);
  }

  private String planOf(String tenantId) {
    return owner("GET", MINE, null, tenantId)
        .data()
        .getJsonObject("subscription")
        .getString("planCode");
  }

  @Test
  @DisplayName("A business with no subscription is not found, and nothing is recorded for it")
  void aBusinessWithNoSubscriptionIsNotFound() {
    Answer check =
        platform(
            "POST",
            BILLING + "/tenants/" + Ids.newId() + "/vat-check",
            "{\"vatNumber\":\"DE123456789\",\"source\":\"MANUAL\"}");
    assertThat(check.text(), check.status(), is(404));
    assertThat(check.code(), is("SUBSCRIPTION_NOT_FOUND"));
    // A business's own view of it is the same refusal, rather than an empty page.
    Answer own = owner("GET", MINE, null, Ids.newId().toString());
    assertThat(own.text(), own.status(), is(404));
    assertThat(own.code(), is("SUBSCRIPTION_NOT_FOUND"));
  }

  @Test
  @DisplayName("A VAT check names where it came from and what it checked, or it is refused")
  void aCheckFromNowhereIsRefused() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-SRC-" + tail(), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Checks nothing");
    assertThat(
        owner("PUT", MINE + "/details", "{\"country\":\"DE\",\"vatNumber\":\"DE111111111\"}", shop)
            .status(),
        is(200));

    Answer guessed =
        platform(
            "POST",
            BILLING + "/tenants/" + shop + "/vat-check",
            "{\"vatNumber\":\"DE111111111\",\"source\":\"GUESSED\"}");
    assertThat(guessed.status(), is(400));
    assertThat(guessed.code(), is("VAT_CHECK_SOURCE_UNKNOWN"));

    // An ideographic space passes a trim()-based not-blank check and is caught by the service.
    Answer blank =
        platform(
            "POST",
            BILLING + "/tenants/" + shop + "/vat-check",
            "{\"vatNumber\":\"\\u3000\",\"source\":\"MANUAL\"}");
    assertThat(blank.status(), is(400));
    assertThat(blank.code(), is("VAT_NUMBER_REQUIRED"));

    JsonObject buyer =
        owner("GET", MINE, null, shop).data().getJsonObject("subscription").getJsonObject("buyer");
    assertThat("the buyer stays unchecked", buyer.getBoolean("vatChecked"), is(false));

    // The platform records checks; a business does not, and another business's id is no help.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer own =
          call(
              "POST",
              BILLING + "/tenants/" + shop + "/vat-check",
              "{\"vatNumber\":\"DE111111111\",\"source\":\"MANUAL\"}",
              shop,
              role);
      assertThat(role, own.status(), is(403));
    }
    assertThat(
        owner("GET", MINE, null, shop)
            .data()
            .getJsonObject("subscription")
            .getJsonObject("buyer")
            .getBoolean("vatChecked"),
        is(false));
  }

  @Test
  @DisplayName("A plan change is NOW or at PERIOD_END, and anything else changes nothing")
  void aPlanChangeIsNowOrAtPeriodEnd() {
    sellerIs("IE", "0.2300");
    String tag = tail();
    String small = sellablePlan("IT-WHEN-S-" + tag, "10.00");
    String big = sellablePlan("IT-WHEN-B-" + tag, "20.00");
    assertThat(platform("POST", PLANS + "/" + small + "/default", null).status(), is(200));
    String shop = onboard("Changes when");
    String before = planOf(shop);
    int invoices = invoicesOf(shop).size();

    Answer tomorrow =
        owner("POST", MINE + "/plan", "{\"planId\":\"" + big + "\",\"when\":\"TOMORROW\"}", shop);
    assertThat(tomorrow.status(), is(400));
    assertThat(tomorrow.code(), is("PLAN_CHANGE_WHEN_UNKNOWN"));
    assertThat("the subscription is unchanged", planOf(shop), is(before));
    assertThat("and nothing was invoiced", invoicesOf(shop).size(), is(invoices));
  }

  @Test
  @DisplayName("Money arrives by transfer or card; any other method is refused and settles nothing")
  void moneyArrivesByTransferOrCard() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-METH-" + tail(), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Pays by cheque");
    JsonObject invoice = invoicesOf(shop).get(0);
    String id = invoice.getString("id");

    Answer cheque =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":1.00,\"method\":\"CHEQUE\",\"providerRef\":\"CHQ-1\"}");
    assertThat(cheque.status(), is(400));
    assertThat(cheque.code(), is("PAYMENT_METHOD_UNKNOWN"));

    JsonObject after = invoicesOf(shop).get(0);
    assertThat("the invoice is as it was", after.getString("status"), is("OPEN"));
    assertThat(
        new BigDecimal(after.get("outstanding").toString())
            .compareTo(new BigDecimal(invoice.get("outstanding").toString())),
        is(0));
  }

  @Test
  @DisplayName(
      "A payment finer than its invoice's currency is refused, never rounded, and settles nothing")
  void aPaymentFinerThanItsCurrencyIsRefused() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-FINE-" + tail(), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Pays a fraction");
    JsonObject invoice = invoicesOf(shop).get(0);
    String id = invoice.getString("id");
    assertThat(invoice.getString("currency"), is("EUR"));

    for (String amount : new String[] {"1.005", "0.0001"}) {
      Answer fine =
          platform(
              "POST",
              BILLING + "/invoices/" + id + "/payments",
              "{\"amount\":" + amount + ",\"method\":\"BANK_TRANSFER\"}");
      assertThat(amount + " -> " + fine.text(), fine.status(), is(400));
      assertThat(fine.code(), is("BILLING_AMOUNT_INVALID"));
    }
    Answer zero =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":0,\"method\":\"BANK_TRANSFER\"}");
    assertThat(zero.status(), is(400));

    JsonObject after = invoicesOf(shop).get(0);
    assertThat("the invoice is as it was", after.getString("status"), is("OPEN"));
    assertThat(
        new BigDecimal(after.get("outstanding").toString())
            .compareTo(new BigDecimal(invoice.get("outstanding").toString())),
        is(0));
    // A euro's two places are an amount of it.
    Answer cent =
        platform(
            "POST",
            BILLING + "/invoices/" + id + "/payments",
            "{\"amount\":0.01,\"method\":\"BANK_TRANSFER\"}");
    assertThat(cent.text(), cent.status(), is(200));
    assertThat(
        cent.data().getJsonArray("payments").getJsonObject(0).get("amount").toString(), is("0.01"));
  }

  @Test
  @DisplayName("An upgrade to a plan with no price in the business's currency is refused")
  void anUpgradeToAPlanWithNoPriceInTheBusinessCurrencyIsRefused() {
    sellerIs("IE", "0.2300");
    String tag = tail();
    String small = sellablePlan("IT-CUR-S-" + tag, "10.00");
    String dollars = planIn("IT-CUR-D-" + tag, "USD", "25.00", 0);
    assertThat(platform("POST", PLANS + "/" + small + "/default", null).status(), is(200));
    String shop = onboard("Euro business");
    String before = planOf(shop);
    int invoices = invoicesOf(shop).size();

    Answer up =
        owner("POST", MINE + "/plan", "{\"planId\":\"" + dollars + "\",\"when\":\"NOW\"}", shop);
    assertThat(up.status(), is(409));
    assertThat(up.code(), is("PLAN_PRICE_MISSING"));
    assertThat(planOf(shop), is(before));
    assertThat("no invoice was issued", invoicesOf(shop).size(), is(invoices));
  }

  @Test
  @DisplayName("A change with nothing to bill is refused, and no adjustment is issued")
  void aChangeWithNothingToBillIsRefused() {
    sellerIs("IE", "0.2300");
    String tag = tail();
    String free = planIn("IT-FREE-A-" + tag, "EUR", "0.00", 14);
    String alsoFree = planIn("IT-FREE-B-" + tag, "EUR", "0.00", 14);
    assertThat(platform("POST", PLANS + "/" + free + "/default", null).status(), is(200));
    String shop = onboard("Free to free");
    int invoices = invoicesOf(shop).size();

    Answer change =
        owner("POST", MINE + "/plan", "{\"planId\":\"" + alsoFree + "\",\"when\":\"NOW\"}", shop);
    assertThat(change.text(), change.status(), is(409));
    assertThat(change.code(), is("BILLING_PERIOD_ENDING"));
    assertThat("no adjustment was issued", invoicesOf(shop).size(), is(invoices));
  }

  private JsonObject buyerOf(String shop) {
    return owner("GET", MINE, null, shop)
        .data()
        .getJsonObject("subscription")
        .getJsonObject("buyer");
  }

  @Test
  @DisplayName(
      "A billing country that is no ISO code is refused for the buyer, the platform's profile and a"
          + " VAT rate, and nothing is stored")
  void aBillingCountryThatIsNoCountryIsRefused() {
    sellerIs("IE", "0.2300");
    String plan = sellablePlan("IT-CTRY-" + tail(), "10.00");
    assertThat(platform("POST", PLANS + "/" + plan + "/default", null).status(), is(200));
    String shop = onboard("Odd country");
    assertThat(buyerOf(shop).getString("country"), is("IE"));
    int rates = platform("GET", BILLING + "/vat-rates", null).list().size();

    // UK is two letters and is not a country (the United Kingdom is GB), and decides a VAT
    // treatment all the same; two ideographic spaces pass a trim()-based not-blank check.
    for (String bad : List.of("UK", "ZZ", "G1", "\\u3000\\u3000")) {
      Answer details =
          owner("PUT", MINE + "/details", "{\"country\":\"" + bad + "\",\"name\":\"Odd\"}", shop);
      assertThat(bad + " -> " + details.text(), details.status(), is(400));
      assertThat(bad, details.code(), is("COUNTRY_INVALID"));

      Answer profile = platform("PUT", BILLING + "/profile", profileJson(bad, "0.2300"));
      assertThat(bad + " -> " + profile.text(), profile.status(), is(400));
      assertThat(bad, profile.code(), is("COUNTRY_INVALID"));

      Answer rate =
          platform(
              "PUT",
              BILLING + "/vat-rates",
              "{\"country\":\"" + bad + "\",\"effectiveFrom\":\"2020-01-01\",\"rate\":\"0.1000\"}");
      assertThat(bad + " -> " + rate.text(), rate.status(), is(400));
      assertThat(bad, rate.code(), is("COUNTRY_INVALID"));
    }
    assertThat("the buyer stays where it was", buyerOf(shop).getString("country"), is("IE"));
    assertThat(
        "and so does the platform's own profile",
        platform("GET", BILLING + "/profile", null).data().getString("country"),
        is("IE"));
    assertThat(
        "no rate was set for a country that is none",
        platform("GET", BILLING + "/vat-rates", null).list().size(),
        is(rates));

    // As it is typed, in capitals or not, it is stored in capitals.
    Answer typed = owner("PUT", MINE + "/details", "{\"country\":\"de\",\"name\":\"Odd\"}", shop);
    assertThat(typed.text(), typed.status(), is(200));
    assertThat(buyerOf(shop).getString("country"), is("DE"));

    // A business's owner cannot write the platform's profile, whatever country it names.
    Answer theirs = owner("PUT", BILLING + "/profile", profileJson("UK", "0.2300"), shop);
    assertThat(theirs.text(), theirs.status(), is(403));
  }

  // ── a body no rate or amount could be (02 Oct 2026) ──────────────────────

  /** The problem's details, as strings. */
  private static List<String> details(Answer a) {
    return a.body().containsKey("details")
        ? a.body().getJsonArray("details").getValuesAs(jakarta.json.JsonString.class).stream()
            .map(jakarta.json.JsonString::getString)
            .toList()
        : List.of();
  }

  private static String rows(String table) {
    return Envelopes.scalar(PG, "SELECT count(*) FROM tenant." + table);
  }

  @Test
  @DisplayName(
      "A VAT rate, a payment or a dunning policy no figure could be is the platform's 400"
          + " VALIDATION_FAILED, field by field, and nothing is written")
  void aBodyNoFigureCouldBeIsRefusedAndNothingIsWritten() {
    String rates = rows("platform_vat_rates");
    String payments = rows("billing_payments");
    String policy =
        Envelopes.scalar(
            PG,
            "SELECT coalesce(max(reminder_days || '/' || suspend_after_days), '-')"
                + " FROM tenant.dunning_policy");

    // Written as a number, as a client would: twelve characters, eighty million digits once
    // written out. @Digits alone wraps an int on 1E+2147483647 and lets it through.
    for (String absurd : List.of("1E+80000000", "1E-80000000", "1E+2147483647")) {
      Answer rate =
          platform(
              "PUT",
              BILLING + "/vat-rates",
              "{\"country\":\"IE\",\"effectiveFrom\":\"2020-01-01\",\"rate\":" + absurd + "}");
      assertThat(absurd + " -> " + rate.text(), rate.status(), is(400));
      assertThat(rate.code(), is("VALIDATION_FAILED"));
      assertThat(details(rate), is(List.of("rate: is out of range")));

      // The request is wrong before the invoice is looked for: 400, not 404.
      Answer paid =
          platform(
              "POST",
              BILLING + "/invoices/" + Ids.newId() + "/payments",
              "{\"amount\":" + absurd + ",\"method\":\"BANK_TRANSFER\"}");
      assertThat(absurd + " -> " + paid.text(), paid.status(), is(400));
      assertThat(paid.code(), is("VALIDATION_FAILED"));
      assertThat(details(paid), is(List.of("amount: is out of range")));
    }

    // A hole in the reminder days was a 500 from sorting a null; now it is named.
    Answer holed =
        platform(
            "PUT",
            BILLING + "/dunning/policy",
            "{\"enabled\":true,\"reminderDays\":[1,null,5],\"suspendAfterDays\":7,"
                + "\"uncollectibleAfterDays\":30}");
    assertThat(holed.text(), holed.status(), is(400));
    assertThat(holed.code(), is("VALIDATION_FAILED"));
    assertThat(details(holed), is(List.of("reminderDays[1]: must not be null")));

    // In the platform's problem shape, not Helidon's constraint-violation body.
    assertThat(holed.body().getString("type"), is("urn:storeql:problem:VALIDATION_FAILED"));

    // Only the platform administrator is asked what they sent.
    Answer theirs =
        call(
            "PUT",
            BILLING + "/vat-rates",
            "{\"country\":\"IE\",\"effectiveFrom\":\"2020-01-01\",\"rate\":1E+80000000}",
            Ids.newId().toString(),
            "OWNER");
    assertThat(theirs.text(), theirs.status(), is(403));

    assertThat("no rate was written", rows("platform_vat_rates"), is(rates));
    assertThat("no payment was written", rows("billing_payments"), is(payments));
    assertThat(
        "the policy is as it was",
        Envelopes.scalar(
            PG,
            "SELECT coalesce(max(reminder_days || '/' || suspend_after_days), '-')"
                + " FROM tenant.dunning_policy"),
        is(policy));
  }

  @Test
  @DisplayName(
      "A VAT rate or the platform's own rate of 1 or more, and payment terms past 180 days, broke"
          + " the tables' checks as 500s; each is 400 VALIDATION_FAILED naming the field, and"
          + " nothing is written")
  void aRateIsBelowOneAndTermsAreAtMostHalfAYear() {
    String rates = rows("platform_vat_rates");
    String seller =
        "SELECT coalesce(max(tax_rate || '/' || payment_terms_days), '-')"
            + " FROM tenant.platform_billing_profile";
    String sellerBefore = Envelopes.scalar(PG, seller);

    for (String whole : List.of("1", "9.9999")) {
      Answer rate =
          platform(
              "PUT",
              BILLING + "/vat-rates",
              "{\"country\":\"IE\",\"effectiveFrom\":\"2020-01-01\",\"rate\":" + whole + "}");
      assertThat(whole + " -> " + rate.text(), rate.status(), is(400));
      assertThat(rate.code(), is("VALIDATION_FAILED"));
      assertThat(details(rate), is(List.of("rate: must be less than 1")));

      Answer profile = platform("PUT", BILLING + "/profile", profileJson("IE", whole));
      assertThat(whole + " -> " + profile.text(), profile.status(), is(400));
      assertThat(profile.code(), is("VALIDATION_FAILED"));
      assertThat(details(profile), is(List.of("taxRate: must be less than 1")));
    }
    for (String terms : List.of("181", "2147483647")) {
      Answer profile =
          platform(
              "PUT",
              BILLING + "/profile",
              profileJson("IE", "0.2300")
                  .replace("\"paymentTermsDays\":14", "\"paymentTermsDays\":" + terms));
      assertThat(terms + " -> " + profile.text(), profile.status(), is(400));
      assertThat(profile.code(), is("VALIDATION_FAILED"));
      assertThat(
          details(profile), is(List.of("paymentTermsDays: must be less than or equal to 180")));
    }
    assertThat("no rate was written", rows("platform_vat_rates"), is(rates));
    assertThat("the seller is as it was", Envelopes.scalar(PG, seller), is(sellerBefore));

    // Just below one, written with a zero the column keeps unchanged, a rate is taken as sent.
    // Iceland, which no other case here bills in.
    Answer edge =
        platform(
            "PUT",
            BILLING + "/vat-rates",
            "{\"country\":\"IS\",\"effectiveFrom\":\"2020-01-01\",\"rate\":0.99990}");
    assertThat(edge.text(), edge.status(), is(200));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT rate::text FROM tenant.platform_vat_rates WHERE country = 'IS'"
                + " AND effective_from = '2020-01-01'"),
        is("0.9999"));
  }

  @Test
  @DisplayName("No body where one is needed is a 400, not a 500, and nothing is written")
  void noBodyIsBodyRequired() {
    String rates = rows("platform_vat_rates");
    // A literal null reaches the resource as no body at all (the binder may refuse it first).
    for (String path : List.of("/vat-rates", "/profile", "/dunning/policy")) {
      Answer none = platform("PUT", BILLING + path, "null");
      assertThat(path + " -> " + none.text(), none.status(), is(400));
      assertThat(
          path, none.code(), org.hamcrest.Matchers.oneOf("BODY_REQUIRED", "REQUEST_BODY_INVALID"));
    }
    assertThat(rows("platform_vat_rates"), is(rates));
  }

  @Test
  @DisplayName(
      "A whole number is the number sent: payment terms of 4294967326 were bound as 30 and"
          + " written with a 200, 1E+80000000 as 0 and 30.9 as 30; a suspension after 4294967310"
          + " days as 14. Each is 400 REQUEST_BODY_INVALID now, and nothing is written")
  void aWholeNumberIsNeverCutDown() {
    String terms =
        "SELECT coalesce(max(payment_terms_days)::text, '-') FROM tenant.platform_billing_profile";
    String policy =
        "SELECT coalesce(max(reminder_days || '/' || suspend_after_days || '/'"
            + " || uncollectible_after_days), '-') FROM tenant.dunning_policy";
    String termsBefore = Envelopes.scalar(PG, terms);
    String policyBefore = Envelopes.scalar(PG, policy);
    String profile = profileJson("IE", "0.2300");

    for (String cut : List.of("4294967326", "1E+80000000", "30.9", "-4294967266")) {
      Answer a =
          platform(
              "PUT",
              BILLING + "/profile",
              profile.replace("\"paymentTermsDays\":14", "\"paymentTermsDays\":" + cut));
      assertThat(cut + " -> " + a.text(), a.status(), is(400));
      assertThat(cut + " -> " + a.text(), a.code(), is("REQUEST_BODY_INVALID"));
    }
    for (String body :
        List.of(
            "{\"enabled\":true,\"reminderDays\":[3,7],\"suspendAfterDays\":4294967310,"
                + "\"uncollectibleAfterDays\":60}",
            "{\"enabled\":true,\"reminderDays\":[3,4294967297],\"suspendAfterDays\":14,"
                + "\"uncollectibleAfterDays\":60}",
            "{\"enabled\":true,\"reminderDays\":[3,7],\"suspendAfterDays\":14,"
                + "\"uncollectibleAfterDays\":60.5}")) {
      Answer a = platform("PUT", BILLING + "/dunning/policy", body);
      assertThat(body + " -> " + a.text(), a.status(), is(400));
      assertThat(body + " -> " + a.text(), a.code(), is("REQUEST_BODY_INVALID"));
    }
    assertThat("the terms are as they were", Envelopes.scalar(PG, terms), is(termsBefore));
    assertThat("the policy is as it was", Envelopes.scalar(PG, policy), is(policyBefore));

    // Sent as the whole number it is, in any form, it is taken as sent.
    Answer ok =
        platform(
            "PUT",
            BILLING + "/profile",
            profile.replace("\"paymentTermsDays\":14", "\"paymentTermsDays\":30.0"));
    assertThat(ok.text(), ok.status(), is(200));
    assertThat(Envelopes.scalar(PG, terms), is("30"));
    sellerIs("IE", "0.2300");
  }
}
