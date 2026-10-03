package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.service.UsageService;
import com.storeql.test.Concurrency;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Usage metering and quotas (21.10), over HTTP and a real database.
 *
 * <p>What only a database can answer: that a thing is counted once however often its event comes,
 * that a threshold is raised once under concurrent records, and that what a period used lands on
 * the invoice that bills it — in arrears, beside the next period in advance — once, with the trial
 * free and the last period of an ended subscription billed on its own. The arithmetic is {@code
 * MetersTest}'s.
 */
@HelidonTest
@AddConfig(key = "storeql.billing.test-clock.enabled", value = "true")
class UsageIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String PLANS = "/platform/plans";
  private static final String BILLING = "/platform/billing";
  private static final String MINE = "/admin/tenant/billing";
  private static final String USAGE = "/admin/tenant/usage";

  @Inject WebTarget target;
  @Inject UsageService usage;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private record Answer(int status, JsonObject body, String text) {

    JsonObject data() {
      return body.getJsonObject("data");
    }

    List<JsonObject> list() {
      return body.getJsonArray("data").getValuesAs(JsonObject.class);
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  private Answer call(String method, String path, String json, String tenant, String roles) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        t = t.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    Invocation.Builder b = t.request(MediaType.APPLICATION_JSON).header("X-User-Id", Ids.newId());
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    Entity<String> body = Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON);
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "PUT" -> b.put(body);
          default -> b.post(body);
        };
    String text = r.readEntity(String.class);
    JsonObject parsed = JsonObject.EMPTY_JSON_OBJECT;
    try {
      if (text != null && !text.isBlank()) {
        parsed = Json.createReader(new StringReader(text)).readObject();
      }
    } catch (RuntimeException ignored) {
      // Not JSON: the text is kept for the assertion message.
    }
    return new Answer(r.getStatus(), parsed, text);
  }

  private Answer platform(String method, String path, String json) {
    return call(method, path, json, null, "PLATFORM_ADMIN");
  }

  private Answer owner(String method, String path, String tenantId) {
    return call(method, path, null, tenantId, "OWNER");
  }

  private void sellerIs() {
    Answer saved =
        platform(
            "PUT",
            BILLING + "/profile",
            "{\"legalName\":\"StoreQL Platform Ltd\",\"addressLine1\":\"1 Quay Street\","
                + "\"city\":\"Dublin\",\"postcode\":\"D02 XY45\",\"country\":\"IE\","
                + "\"vatNumber\":\"IE1234567X\",\"invoicePrefix\":\"INV\","
                + "\"paymentTermsDays\":14,\"taxRate\":\"0.2300\"}");
    assertThat(saved.text(), saved.status(), is(200));
  }

  /**
   * A plan on sale in euro at 10.00 a month, with the meters and overage prices given, made the
   * plan a new business starts on.
   */
  private String meteredPlan(int trialDays, String meters, String... prices) {
    String code = "IT-USE-" + Ids.newId().toString().substring(28);
    Answer written =
        platform(
            "POST",
            PLANS,
            "{\"code\":\""
                + code
                + "\",\"name\":\"Metered\",\"billingInterval\":\"MONTH\",\"trialDays\":"
                + trialDays
                + ",\"isPublic\":true,\"sortOrder\":1}");
    assertThat(written.text(), written.status(), is(201));
    String id = written.data().getString("id");
    assertThat(
        platform("POST", PLANS + "/" + id + "/prices", "{\"currency\":\"EUR\",\"amount\":10.00}")
            .status(),
        is(200));
    Answer metered = platform("PUT", PLANS + "/" + id + "/meters", "{\"meters\":" + meters + "}");
    assertThat(metered.text(), metered.status(), is(200));
    for (String price : prices) {
      Answer priced = platform("POST", PLANS + "/" + id + "/meter-prices", price);
      assertThat(priced.text(), priced.status(), is(200));
    }
    assertThat(platform("POST", PLANS + "/" + id + "/activate", null).status(), is(200));
    assertThat(platform("POST", PLANS + "/" + id + "/default", null).status(), is(200));
    return id;
  }

  private static String price(String meter, String unitAmount) {
    return "{\"meter\":\"" + meter + "\",\"currency\":\"EUR\",\"unitAmount\":" + unitAmount + "}";
  }

  private String onboard(String name) {
    return TenantOnboarding.onboard(target, name, "IE", "EUR");
  }

  private void orders(String tenantId, int n) {
    for (int i = 0; i < n; i++) {
      usage.record(Ids.parse(tenantId), Meters.ORDERS, 1, "order:" + Ids.newId());
    }
  }

  private JsonObject subscription(String tenantId) {
    return owner("GET", MINE, tenantId).data().getJsonObject("subscription");
  }

  private void runOn(String day) {
    Answer run = platform("POST", BILLING + "/run?asOf=" + day, null);
    assertThat(run.text(), run.status(), is(200));
    assertThat(run.text(), run.data().getJsonArray("skipped").size(), is(0));
  }

  private List<JsonObject> invoicesOf(String tenantId) {
    return owner("GET", MINE + "/invoices?limit=100", tenantId).list();
  }

  private List<JsonObject> linesOf(String tenantId, String invoiceId) {
    return owner("GET", MINE + "/invoices/" + invoiceId, tenantId)
        .data()
        .getJsonArray("lines")
        .getValuesAs(JsonObject.class);
  }

  private JsonObject meter(JsonObject usageView, String meter) {
    return usageView.getJsonArray("meters").getValuesAs(JsonObject.class).stream()
        .filter(m -> m.getString("meter").equals(meter))
        .findFirst()
        .orElseThrow();
  }

  private static BigDecimal money(JsonObject o, String key) {
    return new BigDecimal(o.get(key).toString());
  }

  // ── the tests ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A plan names what it includes of each meter, and refuses a promise nobody keeps")
  void aPlanIncludesMeters() {
    String plan =
        meteredPlan(
            0,
            "[{\"meter\":\"orders\",\"included\":1000},{\"meter\":\"SMS\",\"included\":50,"
                + "\"hard\":true}]",
            price("ORDERS", "0.0500"),
            price("SMS", "0.0350"));
    JsonObject file = platform("GET", PLANS + "/" + plan, null).data();
    List<JsonObject> meters = file.getJsonArray("meters").getValuesAs(JsonObject.class);
    assertThat(meters.size(), is(2));
    assertThat("a key is taken in any case", meters.get(0).getString("meter"), is("ORDERS"));
    assertThat(meters.get(1).getBoolean("hard"), is(true));
    assertThat(file.getJsonArray("meterPrices").size(), is(2));

    String path = PLANS + "/" + plan + "/meters";
    Answer unknown =
        platform("PUT", path, "{\"meters\":[{\"meter\":\"API_CALLS\",\"included\":5}]}");
    assertThat(unknown.status(), is(400));
    assertThat(unknown.code(), is("PLAN_METER_UNKNOWN"));
    Answer ordersHard =
        platform("PUT", path, "{\"meters\":[{\"meter\":\"ORDERS\",\"included\":5,\"hard\":true}]}");
    assertThat("an order is never refused", ordersHard.status(), is(400));
    assertThat(ordersHard.code(), is("PLAN_METER_NOT_REFUSABLE"));
    Answer hardUnlimited =
        platform("PUT", path, "{\"meters\":[{\"meter\":\"SMS\",\"hard\":true}]}");
    assertThat(hardUnlimited.code(), is("PLAN_METER_HARD_UNLIMITED"));
    Answer twice =
        platform(
            "PUT", path, "{\"meters\":[{\"meter\":\"SMS\",\"included\":1},{\"meter\":\"sms\"}]}");
    assertThat(twice.code(), is("PLAN_METER_TWICE"));
    // The published code for an allowance below nothing (k6 usage-metering-flow, the API guide):
    // the service's to name, not the request's VALIDATION_FAILED (PlanMeterRulesTest).
    Answer negative = platform("PUT", path, "{\"meters\":[{\"meter\":\"SMS\",\"included\":-1}]}");
    assertThat(negative.text(), negative.status(), is(400));
    assertThat(negative.code(), is("PLAN_METER_INCLUDED_INVALID"));
    Answer badCurrency =
        platform(
            "POST",
            PLANS + "/" + plan + "/meter-prices",
            "{\"meter\":\"SMS\",\"currency\":\"EURO\",\"unitAmount\":1}");
    assertThat(badCurrency.status(), is(400));
    assertThat(
        "none of that changed what the plan includes",
        platform("GET", PLANS + "/" + plan, null).data().getJsonArray("meters").size(),
        is(2));
    assertThat(
        "a business does not price the platform",
        call("PUT", path, "{\"meters\":[]}", Ids.newId().toString(), "OWNER").status(),
        is(403));

    List<JsonObject> keys =
        platform("GET", PLANS + "/meter-keys", null)
            .data()
            .getJsonArray("meters")
            .getValuesAs(JsonObject.class);
    assertThat(
        keys.stream()
            .filter(k -> k.getString("key").equals("ORDERS"))
            .findFirst()
            .orElseThrow()
            .getBoolean("refusable"),
        is(false));
  }

  @Test
  @DisplayName("A thing is counted once, and each threshold is raised once, under concurrency too")
  void countedOnce() throws Exception {
    sellerIs();
    meteredPlan(0, "[{\"meter\":\"ORDERS\",\"included\":20}]", price("ORDERS", "0.5000"));
    String shop = onboard("Counts once");
    java.util.UUID tenant = Ids.parse(shop);

    // Twenty orders, each announced twice, at once: the till's offline replay of the same sale is
    // the same order.
    List<String> orders = IntStream.range(0, 20).mapToObj(i -> "order:" + Ids.newId()).toList();
    java.util.concurrent.atomic.AtomicInteger next =
        new java.util.concurrent.atomic.AtomicInteger();
    Concurrency.inParallel(
        40,
        () ->
            usage.record(
                tenant, Meters.ORDERS, 1, orders.get(next.getAndIncrement() % orders.size())));

    JsonObject view = owner("GET", USAGE, shop).data();
    JsonObject o = meter(view, "ORDERS");
    assertThat("twenty orders, not forty", o.getInt("used"), is(20));
    assertThat(o.getInt("over"), is(0));
    List<JsonObject> alerts = view.getJsonArray("alerts").getValuesAs(JsonObject.class);
    assertThat(
        "80% and 100%, each once however the records raced",
        alerts.stream().map(a -> a.getInt("threshold")).sorted().toList(), is(List.of(80, 100)));

    orders(shop, 3);
    JsonObject after = meter(owner("GET", USAGE, shop).data(), "ORDERS");
    assertThat(after.getInt("used"), is(23));
    assertThat(after.getInt("over"), is(3));
    assertThat(
        "three over at 0.50", money(after, "estimate").compareTo(new BigDecimal("1.50")), is(0));
    assertThat(
        "and no third alert",
        owner("GET", USAGE, shop).data().getJsonArray("alerts").size(),
        is(2));
    assertThat(
        "the platform sees who reached what",
        platform("GET", BILLING + "/usage-alerts?limit=200", null).list().stream()
            .map(a -> a.getString("tenantId"))
            .toList(),
        hasItem(shop));
    assertThat(
        "a rival counted nothing",
        meter(owner("GET", USAGE, onboard("Rival")).data(), "ORDERS").getInt("used"),
        is(0));
  }

  @Test
  @DisplayName("Use beyond the plan is billed in arrears, beside the next period, once")
  void billedInArrears() {
    sellerIs();
    meteredPlan(
        0,
        "[{\"meter\":\"ORDERS\",\"included\":3},{\"meter\":\"SMS\",\"included\":2,\"hard\":true}]",
        price("ORDERS", "0.5000"),
        price("SMS", "0.0350"));
    String shop = onboard("Bills its overage");
    java.util.UUID tenant = Ids.parse(shop);
    String periodEnd = subscription(shop).getString("periodEnd");

    orders(shop, 5);
    usage.record(tenant, Meters.SMS, 3, "sms:" + Ids.newId());
    runOn(periodEnd);
    runOn(periodEnd);

    List<JsonObject> periods =
        invoicesOf(shop).stream().filter(i -> i.getString("kind").equals("PERIOD")).toList();
    assertThat("the first period and the renewal, and no third", periods.size(), is(2));
    JsonObject renewal =
        periods.stream()
            .filter(i -> i.getString("periodStart").equals(periodEnd))
            .findFirst()
            .orElseThrow();
    List<JsonObject> lines = linesOf(shop, renewal.getString("id"));
    assertThat(lines.get(0).getString("kind"), is("PLAN"));
    JsonObject orderLine = lines.get(1);
    assertThat(orderLine.getString("kind"), is("USAGE"));
    assertThat(orderLine.getString("description"), containsString("Orders taken"));
    assertThat(money(orderLine, "quantity").compareTo(new BigDecimal("2")), is(0));
    assertThat(money(orderLine, "amount").compareTo(new BigDecimal("1.0000")), is(0));
    JsonObject smsLine = lines.get(2);
    assertThat(
        "a text part priced to four places is billed, and shown, to four places: " + lines,
        smsLine.get("unitAmount").toString() + " " + smsLine.get("amount"),
        is("0.035 0.035"));
    assertThat(
        "the invoice rounds its total, not its lines: 10 + 1 + 0.035 = 11.04",
        money(renewal, "netAmount").compareTo(new BigDecimal("11.04")),
        is(0));

    List<JsonObject> history =
        owner("GET", USAGE, shop).data().getJsonArray("history").getValuesAs(JsonObject.class);
    assertThat(history.size(), is(2));
    assertThat(
        "each billed period names its invoice",
        history.stream().allMatch(h -> h.getString("invoiceId").equals(renewal.getString("id"))),
        is(true));

    // Recorded after that period was billed — as a replayed sale can be — it is billed with the
    // next one: late, never lost, never twice.
    orders(shop, 4);
    runOn(subscription(shop).getString("periodEnd"));
    List<JsonObject> latest =
        owner("GET", USAGE, shop).data().getJsonArray("history").getValuesAs(JsonObject.class);
    JsonObject next =
        latest.stream()
            .filter(h -> h.getString("meter").equals("ORDERS"))
            .findFirst()
            .orElseThrow();
    assertThat("only the four not yet billed", next.getInt("used"), is(4));
    assertThat(next.getInt("overage"), is(1));
  }

  @Test
  @DisplayName("A trial is free whatever it used, and says why nothing was charged")
  void aTrialIsFree() {
    sellerIs();
    meteredPlan(14, "[{\"meter\":\"ORDERS\",\"included\":1}]", price("ORDERS", "0.5000"));
    String shop = onboard("Trials");
    JsonObject sub = subscription(shop);
    assertThat(sub.getString("status"), is("TRIALING"));
    orders(shop, 3);
    assertThat(
        "a trial's estimate is nothing",
        money(meter(owner("GET", USAGE, shop).data(), "ORDERS"), "estimate").signum(),
        is(0));

    runOn(sub.getString("periodEnd"));
    JsonObject first = invoicesOf(shop).get(0);
    assertThat(
        "the first invoice carries the plan and nothing else",
        linesOf(shop, first.getString("id")).size(),
        is(1));
    JsonObject period =
        owner("GET", USAGE, shop)
            .data()
            .getJsonArray("history")
            .getValuesAs(JsonObject.class)
            .get(0);
    assertThat(period.getInt("overage"), is(2));
    assertThat(period.getString("notCharged"), is("TRIAL"));
  }

  @Test
  @DisplayName("An ended subscription's last period is billed on its own; nothing over, no invoice")
  void theLastPeriodIsBilled() {
    sellerIs();
    meteredPlan(0, "[{\"meter\":\"ORDERS\",\"included\":1}]", price("ORDERS", "0.5000"));
    String over = onboard("Ends over");
    String under = onboard("Ends under");
    orders(over, 3);
    orders(under, 1);
    for (String shop : List.of(over, under)) {
      Answer cancelled = call("POST", MINE + "/cancel", "{\"reason\":\"closing\"}", shop, "OWNER");
      assertThat(cancelled.text(), cancelled.status(), is(200));
    }
    runOn(subscription(over).getString("periodEnd"));

    assertThat(subscription(over).getString("status"), is("CANCELLED"));
    List<JsonObject> adjustments =
        invoicesOf(over).stream().filter(i -> i.getString("kind").equals("ADJUSTMENT")).toList();
    assertThat(adjustments.size(), is(1));
    JsonObject line = linesOf(over, adjustments.get(0).getString("id")).get(0);
    assertThat(line.getString("kind"), is("USAGE"));
    assertThat(money(line, "amount").compareTo(new BigDecimal("1.0000")), is(0));

    assertThat(subscription(under).getString("status"), is("CANCELLED"));
    assertThat(
        "nothing over its allowance: no invoice",
        invoicesOf(under).stream().noneMatch(i -> i.getString("kind").equals("ADJUSTMENT")),
        is(true));
    JsonObject closed =
        owner("GET", USAGE, under)
            .data()
            .getJsonArray("history")
            .getValuesAs(JsonObject.class)
            .get(0);
    assertThat("but written down", closed.getInt("used"), is(1));
    assertThat(closed.get("invoiceId"), nullValue());
  }

  @Test
  @DisplayName("Only a hard ceiling refuses, only on a meter that may be refused, and only its own")
  void aHardCeilingRefuses() {
    sellerIs();
    meteredPlan(
        0,
        "[{\"meter\":\"ORDERS\",\"included\":1},{\"meter\":\"SMS\",\"included\":2,\"hard\":true}]");
    String shop = onboard("Hard ceiling");
    java.util.UUID tenant = Ids.parse(shop);
    String allowance = USAGE + "/allowance?meter=SMS&quantity=";

    Answer before = call("GET", allowance + "2", null, shop, "STOREKEEPER");
    assertThat("the staff identity a service reads under", before.status(), is(200));
    assertThat(before.data().getBoolean("allowed"), is(true));
    usage.record(tenant, Meters.SMS, 2, "sms:" + Ids.newId());
    Answer full = call("GET", allowance + "1", null, shop, "STOREKEEPER");
    assertThat(full.data().getBoolean("allowed"), is(false));
    assertThat(full.data().getInt("used"), is(2));

    orders(shop, 5);
    assertThat(
        "an order is never refused, however far over",
        call("GET", USAGE + "/allowance?meter=ORDERS&quantity=1", null, shop, "STOREKEEPER")
            .data()
            .getBoolean("allowed"),
        is(true));
    assertThat(
        "a rival's ceiling is its own",
        call("GET", allowance + "1", null, onboard("Rival two"), "STOREKEEPER")
            .data()
            .getBoolean("allowed"),
        is(true));

    Answer unknown = call("GET", USAGE + "/allowance?meter=FAXES", null, shop, "STOREKEEPER");
    assertThat(unknown.code(), is("USAGE_METER_UNKNOWN"));
    assertThat(
        call("GET", allowance + "0", null, shop, "STOREKEEPER").code(),
        is("USAGE_QUANTITY_INVALID"));
    assertThat(
        "what was used is management's",
        call("GET", USAGE, null, shop, "CASHIER").status(),
        is(403));
    JsonValue included = meter(owner("GET", USAGE, shop).data(), "SMS").get("included");
    assertThat(included.toString(), is("2"));
  }
}
