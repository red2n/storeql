package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.PriceEvaluation;
import com.storeql.pricing.repo.AppliedPriceRepository;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.pricing.service.AppliedPriceService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The prior price of a price reduction (03.12, Directive 98/6/EC art.6a): what a shopper is offered
 * is recorded as it changes — a price set, a list stopped, a VAT rate overwritten, a promotion
 * starting and ending on schedule — into a ledger nothing can edit, each change as it stood at its
 * own moment and in order, and a reduction is announced only against the lowest price the ledger
 * proves for the 30 days before, where the law binds. What the ledger cannot prove, it says so.
 */
@HelidonTest
class AppliedPriceIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub STUB;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    // The tests work the queue themselves, so each step is seen before the next.
    System.setProperty("storeql.pricing.applied-price-sweeper.enabled", "false");
    // Germany has taken up art.6a(5) and (3); France is bound by art.6a with neither.
    STUB =
        TenantSvcStub.start()
            .withObligation("DE", "PRICE_REDUCTION_PRIOR_PRICE", "EU", "2022-05-28", null)
            .withObligation("DE", "PRICE_REDUCTION_PROGRESSIVE", "DE", "2022-05-28", null)
            .withObligation("DE", "PRICE_REDUCTION_PERISHABLE_EXEMPT", "DE", "2022-05-28", null)
            .withObligation("DE", "UNIT_PRICING", "EU", "2000-03-18", null)
            .withObligation("FR", "PRICE_REDUCTION_PRIOR_PRICE", "EU", "2022-05-28", null)
            .withObligation("FR", "UNIT_PRICING", "EU", "2000-03-18", null);
  }

  @Inject WebTarget target;
  @Inject AppliedPriceService ledger;
  @Inject AppliedPriceRepository queue;
  @Inject PricingRepository catalogue;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private record Shop(String tenant, String currency) {}

  private static Shop shop(String currency, String country) {
    String t = Ids.newId().toString();
    STUB.with(t, currency, country);
    return new Shop(t, currency);
  }

  private static String newId() {
    return Ids.newId().toString();
  }

  private Response post(String path, String json, Shop s, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", s.tenant())
        .header("X-Roles", roles)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, Shop s, String roles, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i + 1 < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request().header("X-Tenant-Id", s.tenant()).header("X-Roles", roles).get();
  }

  private Response put(String path, String json, Shop s) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", s.tenant())
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject body(Response r) {
    return Json.createReader(new StringReader(r.readEntity(String.class))).readObject();
  }

  private static JsonObject data(Response r) {
    return body(r).getJsonObject("data");
  }

  private static String id(Response r) {
    assertThat(r.getStatus(), is(201));
    return data(r).getString("id");
  }

  /** A price list carrying the variant at a net price, at 20% VAT; returns the list id. */
  private String price(Shop s, String variant, String net) {
    post(
            "/vat-rates",
            "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,\"exempt\":false,\"effectiveFrom\":\"2020-01-01T00:00:00Z\"}",
            s,
            "OWNER")
        .close();
    post(
            "/product-vat-categories",
            "{\"variantId\":\"" + variant + "\",\"vatCode\":\"T1\"}",
            s,
            "OWNER")
        .close();
    String list =
        id(
            post(
                "/admin/price-lists",
                "{\"name\":\"Prior "
                    + Ids.newId()
                    + "\",\"channel\":\"ALL\",\"currency\":\""
                    + s.currency()
                    + "\",\"effectiveFrom\":\"2020-01-01T00:00:00Z\"}",
                s,
                "OWNER"));
    setPrice(s, list, variant, net);
    return list;
  }

  private void setPrice(Shop s, String list, String variant, String net) {
    assertThat(
        post(
                "/admin/price-lists/" + list + "/items",
                "{\"variantId\":\"" + variant + "\",\"price\":" + net + ",\"minQty\":1}",
                s,
                "OWNER")
            .getStatus(),
        lessThan(300));
  }

  private String promotion(Shop s, String json) {
    String promo = id(post("/admin/promotions", json, s, "OWNER"));
    assertThat(
        post("/admin/promotions/" + promo + "/items", "{\"scopeType\":\"ALL\"}", s, "OWNER")
            .getStatus(),
        is(201));
    return promo;
  }

  private static String percentOff(String name, int percent) {
    return "{\"name\":\""
        + name
        + "\",\"type\":\"PERCENT\",\"value\":"
        + percent
        + ",\"startsAt\":\"2020-01-01T00:00:00Z\"}";
  }

  /** Works the queue until nothing is due. */
  private void drain() {
    for (int i = 0; i < 200 && ledger.processDue() > 0; i++) {
      // keep going
    }
  }

  private JsonObject resolve(Shop s, String variant, String channel) {
    return resolveAt(s, variant, channel, null);
  }

  private JsonObject resolveAt(Shop s, String variant, String channel, String store) {
    Response r =
        post(
            "/prices/resolve",
            "{\"variantId\":\""
                + variant
                + "\","
                + (store == null ? "" : "\"storeId\":\"" + store + "\",")
                + "\"channel\":\""
                + channel
                + "\",\"qty\":1}",
            s,
            "CUSTOMER");
    assertThat(r.getStatus(), is(200));
    return data(r);
  }

  private JsonObject historyOf(Shop s, String variant) {
    Response r = get("/admin/prices/history", s, "MANAGER", "variantId", variant);
    assertThat(r.getStatus(), is(200));
    return data(r);
  }

  private JsonArray history(Shop s, String variant) {
    return historyOf(s, variant).getJsonArray("rows");
  }

  private static List<JsonObject> rows(JsonArray a, String channel) {
    List<JsonObject> out = new ArrayList<>();
    for (var v : a)
      if (v.asJsonObject().getString("channel").equals(channel)) out.add(v.asJsonObject());
    return out;
  }

  private static List<JsonObject> priced(List<JsonObject> rows) {
    return rows.stream().filter(o -> o.getBoolean("priced")).toList();
  }

  private static boolean uncertain(JsonObject row) {
    return row.containsKey("uncertainSince") && !row.isNull("uncertainSince");
  }

  /** A price applied in the past, as the ledger would have recorded it, at 20% VAT. */
  private static void applied(
      Shop s, String variant, String channel, String gross, String regularGross, Instant from)
      throws SQLException {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO pricing.applied_prices (id, tenant_id, variant_id, channel, store_id, priced, price,"
                    + " net_price, regular_price, promotion_name, currency, applied_from, uncertain_since, recorded_at,"
                    + " cause) VALUES (?::uuid, ?::uuid, ?::uuid, ?, NULL, true, ?, ?, ?, NULL, ?, ?, NULL, ?, 'FIXTURE')")) {
      ps.setString(1, Ids.newId().toString());
      ps.setString(2, s.tenant());
      ps.setString(3, variant);
      ps.setString(4, channel);
      ps.setBigDecimal(5, new BigDecimal(gross));
      ps.setBigDecimal(
          6, new BigDecimal(gross).divide(new BigDecimal("1.20"), 2, RoundingMode.HALF_UP));
      ps.setBigDecimal(7, new BigDecimal(regularGross));
      ps.setString(8, s.currency());
      ps.setObject(9, from.atOffset(ZoneOffset.UTC));
      ps.setObject(10, from.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static String money(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue().setScale(2).toPlainString();
  }

  // ── the ledger ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A price set is recorded on both channels once, net and gross; again, nothing; a new one at once")
  void aPriceIsRecordedOnce() {
    Shop de = shop("EUR", "DE");
    String v = newId();
    String list = price(de, v, "10.00");
    drain();
    List<JsonObject> online = priced(rows(history(de, v), "ONLINE"));
    assertThat(online.size(), is(1));
    assertThat(priced(rows(history(de, v), "POS")).size(), is(1));
    assertThat(money(online.get(0), "price"), is("12.00"));
    assertThat("before VAT, for a till", money(online.get(0), "netPrice"), is("10.00"));

    int before = history(de, v).size();
    setPrice(de, list, v, "10.00");
    drain();
    assertThat("the same offer again is not a change", history(de, v).size(), is(before));

    setPrice(de, list, v, "11.00");
    JsonObject latest = rows(history(de, v), "ONLINE").get(0);
    assertThat(
        "the write recorded its own change before answering", money(latest, "price"), is("13.20"));
    assertThat(latest.getString("cause"), is("PRICE_SET"));
    assertThat(uncertain(latest), is(false));
  }

  @Test
  @DisplayName(
      "A VAT rate overwritten in place, and a list stopped, are recorded as what the shopper saw")
  void vatAndStoppedListsAreRecorded() {
    Shop de = shop("EUR", "DE");
    String v = newId();
    String list = price(de, v, "10.00");
    drain();
    assertThat(
        put(
                "/vat-rates/T1",
                "{\"code\":\"T1\",\"name\":\"Reduced\",\"rate\":0.05,\"exempt\":false,\"effectiveFrom\":\"2020-01-01T00:00:00Z\"}",
                de)
            .getStatus(),
        is(200));
    drain();
    JsonObject vat = rows(history(de, v), "ONLINE").get(0);
    assertThat(money(vat, "price"), is("10.50"));
    assertThat("nothing else changed meanwhile, so it is certain", uncertain(vat), is(false));

    assertThat(
        post(
                "/admin/price-lists/" + list + "/deactivate",
                "{\"reason\":\"season over\"}",
                de,
                "OWNER")
            .getStatus(),
        is(200));
    drain();
    JsonObject latest = rows(history(de, v), "ONLINE").get(0);
    assertThat(
        "a variant with no price in force is recorded as unpriced",
        latest.getBoolean("priced"),
        is(false));
  }

  /**
   * The one recorded application beginning at an instant.
   *
   * @throws AssertionError when there is none, naming what was there instead — a positional index
   *     would have failed with a price and no hint of which row it came from
   */
  private static JsonObject at(List<JsonObject> rows, Instant from) {
    return rows.stream()
        .filter(r -> Instant.parse(r.getString("appliedFrom")).equals(from))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no price recorded from "
                        + from
                        + "; the history holds "
                        + rows.stream()
                            .map(r -> r.getString("appliedFrom") + "=" + money(r, "price"))
                            .toList()));
  }

  @Test
  @DisplayName(
      "A promotion scheduled to start and end is recorded at its moments, not when the worker ran")
  void scheduledBoundariesAreRecordedAtTheirMoments() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    drain();
    Instant starts = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
    Instant ends = starts.plusSeconds(2);
    promotion(
        de,
        "{\"name\":\"Flash\",\"type\":\"PERCENT\",\"value\":50,\"startsAt\":\""
            + starts
            + "\",\"endsAt\":\""
            + ends
            + "\"}");
    drain();
    // A second and a half past the end, not half a second: the boundaries are recorded by a worker,
    // and inside a reactor build a whole second can disappear between the sleep ending and the pass
    // running. A margin thinner than the machine's own noise measures the machine.
    Thread.sleep(Duration.between(Instant.now(), ends).toMillis() + 1500);
    drain();
    List<JsonObject> online = rows(history(de, v), "ONLINE");
    assertThat(online.size(), greaterThanOrEqualTo(3));

    // Found by what they are, not by where they sit. The worker decides how many rows it writes, so
    // an index into its output asserts the worker's shape rather than the rule under test: that the
    // reduction is recorded from the moment the promotion *starts*, and the regular price from the
    // moment it *ends*, whenever the worker happened to run.
    JsonObject reduced = at(online, starts);
    assertThat(
        "a reduction recorded from the moment it starts", money(reduced, "price"), is("6.00"));
    JsonObject restored = at(online, ends);
    assertThat(
        "and the regular price from the moment it ends", money(restored, "price"), is("12.00"));
  }

  @Test
  @DisplayName("Nothing edits or deletes the ledger or the list prices it is rebuilt from")
  void theLedgerIsAppendOnly() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    drain();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      for (String sql :
          new String[] {
            "UPDATE pricing.applied_prices SET price = 1 WHERE tenant_id = '" + de.tenant() + "'",
            "DELETE FROM pricing.applied_prices WHERE tenant_id = '" + de.tenant() + "'",
            "UPDATE pricing.price_list_item_prices SET price = 99 WHERE tenant_id = '"
                + de.tenant()
                + "'",
            "DELETE FROM pricing.price_list_item_prices WHERE tenant_id = '" + de.tenant() + "'"
          }) {
        SQLException refused =
            Assertions.assertThrows(
                SQLException.class, () -> c.createStatement().executeUpdate(sql), sql);
        assertThat(refused.getMessage(), containsString("append-only"));
      }
    }
  }

  // ── the prior price ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "In Germany a reduction is announced against the lowest price of the 30 days before it")
  void aReductionIsAnnouncedAgainstThe30DayLowest() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    applied(de, v, "ONLINE", "12.00", "12.00", daysAgo(40));
    drain();
    promotion(de, percentOff("Summer", 20));
    drain();
    JsonObject r = resolve(de, v, "ONLINE");
    assertThat(money(r, "totalWithVat"), is("9.60"));
    assertThat(r.getBoolean("priorPriceRequired"), is(true));
    assertThat(r.getString("priorPriceStatus"), is("ANNOUNCEABLE"));
    assertThat(money(r, "priorPrice"), is("12.00"));
    assertThat(r.getBoolean("reductionAnnounceable"), is(true));

    JsonObject label =
        body(post(
                "/prices/shelf-labels",
                "{\"variantIds\":[\"" + v + "\"],\"channel\":\"ONLINE\"}",
                de,
                "STOREKEEPER"))
            .getJsonArray("data")
            .getJsonObject(0);
    assertThat(money(label, "priorPrice"), is("12.00"));
    assertThat(label.getBoolean("reductionAnnounceable"), is(true));
  }

  @Test
  @DisplayName("A price raised just before a promotion never becomes its was price")
  void aRaisedPriceIsNotTheWasPrice() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    String list = price(de, v, "10.00");
    applied(de, v, "ONLINE", "12.00", "12.00", daysAgo(40));
    drain();
    setPrice(de, list, v, "15.00");
    promotion(de, percentOff("Fake sale", 20));
    drain();
    JsonObject r = resolve(de, v, "ONLINE");
    assertThat(
        "18.00 less 20% is 14.40, above the 12.00 it sold at",
        money(r, "totalWithVat"), is("14.40"));
    assertThat(r.getString("priorPriceStatus"), is("NOT_LOWER"));
    assertThat(money(r, "priorPrice"), is("12.00"));
    assertThat(r.getBoolean("reductionAnnounceable"), is(false));
  }

  @Test
  @DisplayName(
      "A price reduced from its first moment has no prior price in Germany; a British business is not bound")
  void noHistory() {
    Shop de = shop("EUR", "DE");
    String v = newId();
    promotion(de, percentOff("Opening", 10));
    price(de, v, "10.00");
    drain();
    JsonObject r = resolve(de, v, "ONLINE");
    assertThat(r.getString("priorPriceStatus"), is("NO_HISTORY"));
    assertThat(r.getBoolean("reductionAnnounceable"), is(false));

    Shop gb = shop("GBP", "GB");
    String w = newId();
    promotion(gb, percentOff("Opening", 10));
    price(gb, w, "10.00");
    drain();
    JsonObject g = resolve(gb, w, "ONLINE");
    assertThat(g.getBoolean("priorPriceRequired"), is(false));
    assertThat(
        "where art.6a does not bind, the regular price may stand as the was price",
        g.getBoolean("reductionAnnounceable"),
        is(true));
  }

  @Test
  @DisplayName("A price set yesterday and reduced today proves nothing about the 30 days before")
  void aFreshPriceHasShortHistory() {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    drain();
    promotion(de, percentOff("Too soon", 20));
    drain();
    JsonObject r = resolve(de, v, "ONLINE");
    assertThat(r.getString("priorPriceStatus"), is("SHORT_HISTORY"));
    assertThat(money(r, "priorPrice"), is("12.00"));
    assertThat(r.getBoolean("reductionAnnounceable"), is(false));
  }

  /** 12.00 for 40 days, then 10% off, then an exclusive 20% off over it, then the 20% stopped. */
  private JsonObject[] steps(Shop s, String v) throws Exception {
    price(s, v, "10.00");
    applied(s, v, "ONLINE", "12.00", "12.00", daysAgo(40));
    drain();
    promotion(
        s,
        "{\"name\":\"Ten\",\"type\":\"PERCENT\",\"value\":10,\"priority\":100,\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    drain();
    String deeper =
        promotion(
            s,
            "{\"name\":\"Twenty\",\"type\":\"PERCENT\",\"value\":20,\"priority\":10,\"exclusive\":true,\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    drain();
    JsonObject deep = resolve(s, v, "ONLINE");
    assertThat(
        post("/admin/promotions/" + deeper + "/deactivate", "{\"reason\":\"ended\"}", s, "OWNER")
            .getStatus(),
        is(200));
    drain();
    return new JsonObject[] {deep, resolve(s, v, "ONLINE")};
  }

  @Test
  @DisplayName(
      "A deeper second step keeps the first reference only where the law takes up art.6a(5)")
  void progressiveOnlyWhereTheLawAllows() throws Exception {
    JsonObject[] germany = steps(shop("EUR", "DE"), newId());
    assertThat(money(germany[0], "totalWithVat"), is("9.60"));
    assertThat(
        "Germany: a progressively deeper reduction is measured from before its first step",
        money(germany[0], "priorPrice"),
        is("12.00"));
    assertThat(germany[0].getString("priorPriceStatus"), is("ANNOUNCEABLE"));

    JsonObject[] france = steps(shop("EUR", "FR"), newId());
    assertThat(money(france[0], "totalWithVat"), is("9.60"));
    assertThat(
        "France: the deeper step is a reduction of its own, from the 10.80 before it",
        money(france[0], "priorPrice"),
        is("10.80"));
    assertThat(france[0].getString("priorPriceStatus"), is("ANNOUNCEABLE"));

    for (JsonObject after : new JsonObject[] {germany[1], france[1]}) {
      assertThat(money(after, "totalWithVat"), is("10.80"));
      assertThat(
          "back to 10% after 20% is measured against the 20% price, everywhere",
          money(after, "priorPrice"), is("9.60"));
      assertThat(after.getString("priorPriceStatus"), is("NOT_LOWER"));
      assertThat(after.getBoolean("reductionAnnounceable"), is(false));
    }
  }

  @Test
  @DisplayName("A store-scoped promotion has its own ledger at that store")
  void aStoreScopedPromotion() {
    Shop de = shop("EUR", "DE");
    String v = newId();
    String store = newId();
    price(de, v, "10.00");
    drain();
    promotion(
        de,
        "{\"name\":\"This shop only\",\"type\":\"FLAT\",\"value\":2,\"storeId\":\""
            + store
            + "\",\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    drain();
    List<JsonObject> atStore = new ArrayList<>();
    for (var row : history(de, v))
      if (store.equals(row.asJsonObject().getString("storeId", "")))
        atStore.add(row.asJsonObject());
    assertThat(atStore.size(), greaterThanOrEqualTo(2));
    List<JsonObject> businessWide = priced(rows(history(de, v), "ONLINE"));
    assertThat(
        "the business-wide offer is untouched",
        businessWide.stream()
            .filter(o -> !o.containsKey("storeId") || o.isNull("storeId"))
            .allMatch(o -> money(o, "price").equals("12.00")),
        is(true));
  }

  @Test
  @DisplayName(
      "A British business with a shop in Germany is bound there and online, not at its British shop")
  void aStoreAcrossTheBorder() {
    Shop gb = shop("GBP", "GB");
    String german = newId();
    String british = newId();
    STUB.withStore(gb.tenant(), german, "DE").withStore(gb.tenant(), british, "GB");
    String v = newId();
    price(gb, v, "10.00");
    drain();
    promotion(gb, percentOff("Across the border", 10));
    drain();

    JsonObject home = resolveAt(gb, v, "POS", british);
    assertThat(home.getBoolean("priorPriceRequired"), is(false));
    assertThat(home.getBoolean("reductionAnnounceable"), is(true));

    JsonObject abroad = resolveAt(gb, v, "POS", german);
    assertThat(abroad.getBoolean("priorPriceRequired"), is(true));
    assertThat(abroad.getString("priorPriceStatus"), is("SHORT_HISTORY"));
    assertThat(abroad.getBoolean("reductionAnnounceable"), is(false));

    assertThat(
        "online, the German shop's law reaches the offer",
        resolve(gb, v, "ONLINE").getBoolean("priorPriceRequired"),
        is(true));
    assertThat(
        "a store that is not the business's cannot narrow the law",
        resolveAt(gb, v, "POS", newId()).getBoolean("priorPriceRequired"),
        is(true));
  }

  // ── what the ledger cannot prove ───────────────────────────────────────────

  @Test
  @DisplayName("A price read before the change behind it is recorded is pending, never announced")
  void aLedgerBehindIsPending() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    applied(de, v, "ONLINE", "12.00", "12.00", daysAgo(40));
    drain();
    promotion(de, percentOff("Just started", 20));

    JsonObject early = resolve(de, v, "ONLINE");
    assertThat(money(early, "totalWithVat"), is("9.60"));
    assertThat(early.getString("priorPriceStatus"), is("PENDING"));
    assertThat(early.getBoolean("reductionAnnounceable"), is(false));
    assertThat(historyOf(de, v).getInt("pending"), greaterThanOrEqualTo(1));

    drain();
    JsonObject recorded = resolve(de, v, "ONLINE");
    assertThat(recorded.getString("priorPriceStatus"), is("ANNOUNCEABLE"));
    assertThat(recorded.getBoolean("reductionAnnounceable"), is(true));
  }

  @Test
  @DisplayName(
      "A VAT rate overwritten twice before the worker runs: the first span is uncertain, so no was price")
  void anOverwriteWhileQueuedIsUncertain() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    applied(de, v, "ONLINE", "12.00", "12.00", daysAgo(40));
    drain();
    for (String rate : new String[] {"0.05", "0.10"}) {
      assertThat(
          put(
                  "/vat-rates/T1",
                  "{\"code\":\"T1\",\"name\":\"Changed\",\"rate\":"
                      + rate
                      + ",\"exempt\":false,\"effectiveFrom\":\"2020-01-01T00:00:00Z\"}",
                  de)
              .getStatus(),
          is(200));
    }
    drain();
    List<JsonObject> online = rows(history(de, v), "ONLINE");
    assertThat(money(online.get(0), "price"), is("11.00"));
    assertThat(
        "the later change is certain, and closes the span", uncertain(online.get(0)), is(false));
    assertThat(money(online.get(1), "price"), is("11.00"));
    assertThat(
        "the earlier one could only read the later rate, so it says it cannot know",
        uncertain(online.get(1)),
        is(true));

    promotion(de, percentOff("After the muddle", 20));
    drain();
    JsonObject r = resolve(de, v, "ONLINE");
    assertThat(money(r, "totalWithVat"), is("8.80"));
    assertThat(r.getString("priorPriceStatus"), is("UNCERTAIN"));
    assertThat(r.getBoolean("reductionAnnounceable"), is(false));
  }

  @Test
  @DisplayName(
      "Claims keep each variant in order: nothing later for a variant is taken while its earlier is held")
  void claimsKeepEachVariantInOrder() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    String w = newId();
    price(de, v, "10.00");
    drain();
    UUID tenant = Ids.parse(de.tenant());
    for (int i = 0; i < 3; i++) queue.enqueue(tenant, Ids.parse(v), null, "ORDER_" + i);
    queue.enqueue(tenant, Ids.parse(w), null, "ORDER_W");

    List<UUID> held = new ArrayList<>();
    try {
      List<PriceEvaluation> first = claim(held);
      List<PriceEvaluation> mine = first.stream().filter(e -> e.tenantId().equals(tenant)).toList();
      assertThat(
          mine.stream().map(PriceEvaluation::cause).sorted().toList(),
          is(List.of("ORDER_0", "ORDER_W")));
      assertThat(
          "a second worker finds nothing of this tenant's to take",
          claim(held).stream().filter(e -> e.tenantId().equals(tenant)).count(),
          is(0L));

      PriceEvaluation v0 =
          mine.stream().filter(e -> e.cause().equals("ORDER_0")).findFirst().orElseThrow();
      queue.complete(v0.id());
      held.remove(v0.id());
      assertThat(
          "the next for the variant is taken once the first is done",
          claim(held).stream()
              .filter(e -> e.tenantId().equals(tenant))
              .map(PriceEvaluation::cause)
              .toList(),
          is(List.of("ORDER_1")));
    } finally {
      release(held);
    }
    drain();
    assertThat(historyOf(de, v).getInt("pending"), is(0));
  }

  private List<PriceEvaluation> claim(List<UUID> held) {
    List<PriceEvaluation> got = queue.claimDue(1000, Duration.ofMinutes(2));
    got.forEach(e -> held.add(e.id()));
    return got;
  }

  /** Hands claimed evaluations back, so other tests' queues are not left leased. */
  private static void release(List<UUID> ids) throws SQLException {
    if (ids.isEmpty()) return;
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "UPDATE pricing.price_evaluations SET due_at = as_of WHERE id = ANY (?)")) {
      ps.setArray(1, c.createArrayOf("uuid", ids.toArray()));
      ps.executeUpdate();
    }
  }

  /** How many catalogue evaluations are queued for a variant, and how many as overwrites. */
  private static int[] catalogued(UUID tenant, UUID variant) throws SQLException {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*), count(*) FILTER (WHERE overwrites) FROM pricing.price_evaluations"
                    + " WHERE tenant_id = ?::uuid AND variant_id = ?::uuid AND cause = 'CATALOGUED'")) {
      ps.setString(1, tenant.toString());
      ps.setString(2, variant.toString());
      try (var rs = ps.executeQuery()) {
        rs.next();
        return new int[] {rs.getInt(1), rs.getInt(2)};
      }
    }
  }

  @Test
  @DisplayName(
      "A catalogue change is queued only where a category scope lets it move a price, and then as an overwrite")
  void aCatalogueChangeIsQueuedOnlyWhereItCanMatter() throws Exception {
    Shop de = shop("EUR", "DE");
    UUID tenant = Ids.parse(de.tenant());
    UUID product = Ids.newId();
    UUID plain = Ids.newId();
    assertThat(
        catalogue.projectVariantCreatedOnce(Ids.newId(), "test", tenant, plain, product), is(true));
    assertThat(
        "no category scope: the catalogue decides no price, so nothing is queued or made uncertain",
        catalogued(tenant, plain)[0],
        is(0));

    String promo = id(post("/admin/promotions", percentOff("Drinks", 10), de, "OWNER"));
    assertThat(
        post(
                "/admin/promotions/" + promo + "/items",
                "{\"scopeType\":\"CATEGORY\",\"scopeId\":\"" + newId() + "\"}",
                de,
                "OWNER")
            .getStatus(),
        is(201));
    UUID placed = Ids.newId();
    UUID event = Ids.newId();
    assertThat(
        catalogue.projectVariantCreatedOnce(event, "test", tenant, placed, product), is(true));
    assertThat(
        "the same event again is not a second change",
        catalogue.projectVariantCreatedOnce(event, "test", tenant, placed, product),
        is(false));
    assertThat(
        "with a category scope, queued once, as a change nothing can rewind",
        catalogued(tenant, placed),
        is(new int[] {1, 1}));
    drain();
    assertThat(historyOf(de, placed.toString()).getInt("pending"), is(0));
  }

  // ── where a reduction is said ──────────────────────────────────────────────

  @Test
  @DisplayName(
      "The storefront banner: an item reduction only when every reduced price can be announced; a basket offer always")
  void theStorefrontBanner() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    drain();
    String item = promotion(de, percentOff("Twenty off", 20));
    Response basket =
        post(
            "/admin/promotions",
            "{\"name\":\"Spend and save\",\"type\":\"BASKET_PERCENT\",\"value\":10,\"minOrderAmount\":50,\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            de,
            "OWNER");
    String basketId = id(basket);
    drain();
    assertThat(advertised(de, item), is(false));
    assertThat("a condition, not a reduced price", advertised(de, basketId), is(true));

    Shop fr = shop("EUR", "FR");
    String w = newId();
    price(fr, w, "10.00");
    applied(fr, w, "ONLINE", "12.00", "12.00", daysAgo(40));
    drain();
    String proven = promotion(fr, percentOff("Proven", 20));
    assertThat("pending, not yet", advertised(fr, proven), is(false));
    drain();
    assertThat(
        "every reduced price proven: the banner may say it", advertised(fr, proven), is(true));

    Shop gb = shop("GBP", "GB");
    String x = newId();
    price(gb, x, "10.00");
    drain();
    String british = promotion(gb, percentOff("British", 20));
    drain();
    assertThat("not bound", advertised(gb, british), is(true));
  }

  private boolean advertised(Shop s, String promotionId) {
    Response r = get("/promotions", s, "CUSTOMER");
    assertThat(r.getStatus(), is(200));
    for (var p : body(r).getJsonArray("data")) {
      JsonObject o = p.asJsonObject();
      if (o.getString("id").equals(promotionId)) return o.getBoolean("reductionAnnounceable");
    }
    throw new AssertionError("promotion " + promotionId + " not listed");
  }

  @Test
  @DisplayName(
      "The reductions list says, per channel, which may be announced and why; for management only")
  void theReductionsList() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    applied(de, v, "ONLINE", "12.00", "12.00", daysAgo(40));
    drain();
    promotion(de, percentOff("Listed", 20));
    drain();

    Response online = get("/admin/prices/reductions", de, "MANAGER", "channel", "ONLINE");
    assertThat(online.getStatus(), is(200));
    JsonObject o = data(online);
    assertThat(o.getInt("pending"), is(0));
    JsonObject row = o.getJsonArray("rows").getJsonObject(0);
    assertThat(row.getString("variantId"), is(v));
    assertThat(money(row, "price"), is("9.60"));
    assertThat(money(row, "priorPrice"), is("12.00"));
    assertThat(row.getString("priorPriceStatus"), is("ANNOUNCEABLE"));
    assertThat(row.getBoolean("reductionAnnounceable"), is(true));

    JsonObject pos =
        data(get("/admin/prices/reductions", de, "MANAGER", "channel", "pos"))
            .getJsonArray("rows")
            .getJsonObject(0);
    assertThat(
        "no 40 days recorded at the till", pos.getString("priorPriceStatus"), is("SHORT_HISTORY"));
    assertThat(pos.getBoolean("reductionAnnounceable"), is(false));

    assertThat(
        get("/admin/prices/reductions", de, "MANAGER", "channel", "CARRIER_PIGEON").getStatus(),
        is(400));
    assertThat(get("/admin/prices/reductions", de, "CASHIER").getStatus(), is(403));
    assertThat(
        data(get("/admin/prices/reductions", shop("EUR", "DE"), "MANAGER"))
            .getJsonArray("rows")
            .size(),
        is(0));
  }

  private String sticker(Shop s, String store, String variant, String reason) {
    Response r =
        post(
            "/markdowns",
            "{\"storeId\":\""
                + store
                + "\",\"variantId\":\""
                + variant
                + "\",\"batchNo\":\"B-"
                + Ids.newId()
                + "\",\"expiryDate\":\""
                + LocalDate.now().plusDays(2)
                + "\",\"qty\":2,\"percentOff\":25,\"reason\":\""
                + reason
                + "\"}",
            s,
            "STOREKEEPER");
    assertThat(r.getStatus(), is(201));
    return data(r).getString("labelCode");
  }

  private JsonObject scanned(Shop s, String code) {
    Response r = get("/prices/markdown-labels/" + code, s, "CASHIER");
    assertThat(r.getStatus(), is(200));
    return data(r);
  }

  @Test
  @DisplayName(
      "A sticker at the till: short-dated goods exempt in Germany; elsewhere only a proven prior price")
  void aStickerAtTheTill() throws Exception {
    Shop de = shop("EUR", "DE");
    String store = newId();
    String v = newId();
    price(de, v, "10.00");
    drain();
    JsonObject exempt = scanned(de, sticker(de, store, v, "SHORT_DATED"));
    assertThat(exempt.getBoolean("perishableExempt"), is(true));
    assertThat(exempt.getBoolean("reductionAnnounceable"), is(true));
    assertThat(money(exempt, "wasPrice"), is("10.00"));

    JsonObject damaged = scanned(de, sticker(de, store, v, "DAMAGED_PACK"));
    assertThat(
        "a damaged pack is not goods about to spoil",
        damaged.getBoolean("perishableExempt"),
        is(false));
    assertThat(damaged.getString("priorPriceStatus"), is("SHORT_HISTORY"));
    assertThat(damaged.getBoolean("reductionAnnounceable"), is(false));
    assertThat(damaged.containsKey("wasPrice") && !damaged.isNull("wasPrice"), is(false));

    Shop fr = shop("EUR", "FR");
    String w = newId();
    price(fr, w, "10.00");
    applied(fr, w, "POS", "12.00", "12.00", daysAgo(40));
    drain();
    JsonObject proven = scanned(fr, sticker(fr, store, w, "SHORT_DATED"));
    assertThat(
        "France has not taken up art.6a(3)", proven.getBoolean("perishableExempt"), is(false));
    assertThat(proven.getString("priorPriceStatus"), is("ANNOUNCEABLE"));
    assertThat(money(proven, "priorPrice"), is("12.00"));
    assertThat(
        "shown before VAT, as the till shows prices", money(proven, "wasPrice"), is("10.00"));

    Shop raised = shop("EUR", "FR");
    String x = newId();
    price(raised, x, "10.00");
    applied(raised, x, "POS", "9.00", "9.00", daysAgo(40));
    drain();
    JsonObject cheaper = scanned(raised, sticker(raised, store, x, "SHORT_DATED"));
    assertThat(
        "25% off 12.00 is 9.00, what it sold at before the price went up",
        cheaper.getString("priorPriceStatus"), is("NOT_LOWER"));
    assertThat(cheaper.getBoolean("reductionAnnounceable"), is(false));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The history is for management, about a variant, and never another business's")
  void historyRefusals() {
    Shop de = shop("EUR", "DE");
    String v = newId();
    price(de, v, "10.00");
    drain();
    assertThat(get("/admin/prices/history", de, "CASHIER", "variantId", v).getStatus(), is(403));
    assertThat(
        get("/admin/prices/history", de, "OWNER", "variantId", "x' OR '1'='1").getStatus(),
        is(400));
    assertThat(history(shop("EUR", "DE"), v).size(), is(0));
    assertThat(
        target
            .path("/admin/prices/history")
            .queryParam("variantId", v)
            .request()
            .header("X-Tenant-Id", de.tenant())
            .header("X-Roles", "OWNER")
            .post(Entity.json("{}"))
            .getStatus(),
        not(is(200)));
  }

  // ── abuse ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Prices set from four clients at once while four workers run: all recorded, ending on the price in force")
  void concurrentWritersAndWorkers() throws Exception {
    Shop de = shop("EUR", "DE");
    String v = newId();
    String list = price(de, v, "10.00");
    var pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int k = 0; k < 4; k++) {
        int writer = k;
        jobs.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 5; i++)
                    setPrice(de, list, v, (writer + i) % 2 == 0 ? "10.00" : "11.00");
                }));
      }
      for (int k = 0; k < 4; k++) {
        jobs.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 20; i++) ledger.processDue();
                }));
      }
      for (var f : jobs) f.get();
    } finally {
      pool.shutdownNow();
    }
    drain();
    assertThat(historyOf(de, v).getInt("pending"), is(0));
    List<JsonObject> online = priced(rows(history(de, v), "ONLINE"));
    JsonObject last = online.get(0);
    assertThat(
        "the ledger ends on what a shopper is offered",
        money(last, "price"),
        is(money(resolve(de, v, "ONLINE"), "totalWithVat")));
    assertThat("and ends certain", uncertain(last), is(false));
    for (int i = 1; i < online.size(); i++) {
      JsonObject newer = online.get(i - 1);
      JsonObject older = online.get(i);
      if (money(newer, "price").equals(money(older, "price"))) {
        assertThat(
            "an offer is repeated only to open or close an uncertain span",
            uncertain(newer) || uncertain(older),
            is(true));
      }
    }
  }

  @Test
  @DisplayName(
      "A claimed whole-tenant evaluation keeps its claim while it works: the lease is renewed")
  void leaseIsRenewedWhileWorking() throws Exception {
    UUID tenant = com.storeql.ids.Ids.newId();
    queue.enqueue(tenant, null, null, "WHOLE_TENANT_TEST");
    List<UUID> held = new ArrayList<>();
    try {
      PriceEvaluation mine =
          claim(held).stream().filter(e -> e.tenantId().equals(tenant)).findFirst().orElseThrow();
      queue.renewLease(tenant, mine.id(), Duration.ofHours(1));
      try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
          var ps =
              c.prepareStatement(
                  "SELECT due_at > clock_timestamp() + interval '50 minutes'"
                      + " FROM pricing.price_evaluations WHERE id = ?")) {
        ps.setObject(1, mine.id());
        try (var rs = ps.executeQuery()) {
          assertThat(rs.next() && rs.getBoolean(1), is(true));
        }
      }
    } finally {
      // Others' claims are handed back; ours is deleted by its tenant (a throwaway).
      release(held);
      try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
          var ps =
              c.prepareStatement("DELETE FROM pricing.price_evaluations WHERE tenant_id = ?")) {
        ps.setObject(1, tenant);
        ps.executeUpdate();
      }
    }
  }
}
