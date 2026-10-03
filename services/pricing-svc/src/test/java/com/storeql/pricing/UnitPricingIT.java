package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.pricing.messaging.CatalogueEventHandler;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit prices (03.13): every quote a shopper sees carries the price per kilogram, litre, metre,
 * square metre or item of what they are charged, promotions included; a shelf label shows it for
 * the regular and the promotional price; an item with no declared measure is a named gap rather
 * than a silent omission; and only the business the law binds is told the gap matters.
 */
@HelidonTest
class UnitPricingIT {

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
    // As tenant-svc's jurisdiction rules have it: the UK's amended Price Marking Order, the EU's
    // Directive 98/6/EC art.3 reaching Germany, nothing for Japan.
    STUB =
        TenantSvcStub.start()
            .withObligation("GB", "UNIT_PRICING", "GB", "2026-04-06", null)
            .withObligation("DE", "UNIT_PRICING", "EU", "2000-03-18", null);
  }

  /** Each helper-sent measure is newer than the last, as product-svc's versions are. */
  private static final java.util.concurrent.atomic.AtomicLong VERSION =
      new java.util.concurrent.atomic.AtomicLong(100);

  @Inject WebTarget target;
  @Inject CatalogueEventHandler catalogue;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private record Shop(String tenant, String currency) {}

  private static Shop shop(String currency, String country) {
    String tenant = Ids.newId().toString();
    STUB.with(tenant, currency, country);
    return new Shop(tenant, currency);
  }

  private Response post(String path, String json, Shop s, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", s.tenant())
        .header("X-Roles", roles)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, Shop s, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", s.tenant())
        .header("X-Roles", roles)
        .get();
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static String id(Response r) {
    assertThat(r.getStatus(), is(201));
    return data(r).getString("id");
  }

  /** One price list for the shop, carrying each variant at its net price, all at 20% VAT. */
  private void price(Shop s, String... variantAndNetPrice) {
    post(
            "/vat-rates",
            "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            s,
            "OWNER")
        .close();
    String list =
        id(
            post(
                "/admin/price-lists",
                "{\"name\":\"Unit "
                    + Ids.newId()
                    + "\",\"channel\":\"ALL\",\"currency\":\""
                    + s.currency()
                    + "\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
                s,
                "OWNER"));
    for (int i = 0; i < variantAndNetPrice.length; i += 2) {
      post(
              "/product-vat-categories",
              "{\"variantId\":\"" + variantAndNetPrice[i] + "\",\"vatCode\":\"T1\"}",
              s,
              "OWNER")
          .close();
      Response item =
          post(
              "/admin/price-lists/" + list + "/items",
              "{\"variantId\":\""
                  + variantAndNetPrice[i]
                  + "\",\"price\":"
                  + variantAndNetPrice[i + 1]
                  + ",\"minQty\":1}",
              s,
              "OWNER");
      // Adding an item answers 200 or 201 by its resource; anything else is a failed setup.
      assertThat(item.getStatus(), org.hamcrest.Matchers.lessThan(300));
    }
  }

  private static String measured(
      String eventId,
      Shop s,
      String variant,
      String soldBy,
      String unit,
      String quantity,
      long version) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"VariantMeasured\",\"tenantId\":\""
        + s.tenant()
        + "\",\"aggregateId\":\""
        + variant
        + "\",\"occurredAt\":\"2026-09-14T00:00:00Z\",\"productId\":\""
        + Ids.newId()
        + "\",\"version\":"
        + version
        + ",\"soldBy\":\""
        + soldBy
        + "\",\"unit\":"
        + (unit == null ? "null" : "\"" + unit + "\"")
        + ",\"quantity\":"
        + (quantity == null ? "null" : "\"" + quantity + "\"")
        + "}";
  }

  private boolean measure(Shop s, String variant, String soldBy, String unit, String quantity) {
    return catalogue.handle(
        measured(
            Ids.newId().toString(), s, variant, soldBy, unit, quantity, VERSION.incrementAndGet()));
  }

  private JsonObject resolve(Shop s, String variant, String channel) {
    Response r =
        post(
            "/prices/resolve",
            "{\"variantId\":\"" + variant + "\",\"channel\":\"" + channel + "\",\"qty\":1}",
            s,
            "CUSTOMER");
    assertThat(r.getStatus(), is(200));
    return data(r);
  }

  private static String amount(JsonObject unitPricing) {
    return unitPricing.getJsonNumber("amount").bigDecimalValue().toPlainString();
  }

  // ── quotes ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A 750 ml bottle at £1.80 is quoted at £2.40 per litre, on resolve and in the basket")
  void aMeasuredItemIsQuotedWithItsUnitPrice() {
    Shop gb = shop("GBP", "GB");
    String wine = Ids.newId().toString();
    price(gb, wine, "1.50");
    assertThat(measure(gb, wine, "EACH", "L", "0.75"), is(true));

    JsonObject r = resolve(gb, wine, "ONLINE");
    assertThat(r.getJsonNumber("totalWithVat").bigDecimalValue().toPlainString(), is("1.80"));
    JsonObject u = r.getJsonObject("unitPricing");
    assertThat(amount(u), is("2.40"));
    assertThat(u.getString("unit"), is("L"));
    assertThat(u.getString("label"), is("per litre"));
    assertThat(r.getBoolean("unitPriceRequired"), is(true));

    Response q =
        post(
            "/prices/quote",
            "{\"lines\":[{\"variantId\":\"" + wine + "\",\"qty\":2}]}",
            gb,
            "OWNER");
    assertThat(q.getStatus(), is(200));
    JsonObject line = data(q).getJsonArray("lines").getJsonObject(0);
    assertThat(amount(line.getJsonObject("unitPricing")), is("2.40"));
  }

  @Test
  @DisplayName("A promotional price is unit-priced too, and a shelf label shows both")
  void aPromotionIsUnitPricedAndLabelled() {
    Shop gb = shop("GBP", "GB");
    String wine = Ids.newId().toString();
    price(gb, wine, "1.50");
    measure(gb, wine, "EACH", "L", "0.75");
    String promo =
        id(
            post(
                "/admin/promotions",
                "{\"name\":\"Half price\",\"type\":\"PERCENT\",\"value\":50,\"startsAt\":\"2020-01-01T00:00:00Z\"}",
                gb,
                "OWNER"));
    assertThat(
        post("/admin/promotions/" + promo + "/items", "{\"scopeType\":\"ALL\"}", gb, "OWNER")
            .getStatus(),
        is(201));

    JsonObject r = resolve(gb, wine, "ONLINE");
    assertThat(r.getJsonNumber("totalWithVat").bigDecimalValue().toPlainString(), is("0.90"));
    assertThat(amount(r.getJsonObject("unitPricing")), is("1.20"));

    Response labels =
        post(
            "/prices/shelf-labels",
            "{\"variantIds\":[\"" + wine + "\",\"" + wine + "\"]}",
            gb,
            "STOREKEEPER");
    assertThat(labels.getStatus(), is(200));
    var all =
        Json.createReader(new StringReader(labels.readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    assertThat("a variant named twice is one label", all.size(), is(1));
    JsonObject label = all.getJsonObject(0);
    assertThat(label.getJsonNumber("regularPrice").bigDecimalValue().toPlainString(), is("1.80"));
    assertThat(amount(label.getJsonObject("regularUnitPrice")), is("2.40"));
    assertThat(
        label.getJsonNumber("promotionalPrice").bigDecimalValue().toPlainString(), is("0.90"));
    assertThat(amount(label.getJsonObject("promotionalUnitPrice")), is("1.20"));
    assertThat(label.getString("promotionName"), is("Half price"));
    assertThat(label.getBoolean("measureDeclared"), is(true));
  }

  @Test
  @DisplayName("Loose goods: 2.5 kg at £3.60 per kg is unit-priced per kg, not per line")
  void aWeighedLineIsUnitPricedPerKilogram() {
    Shop gb = shop("GBP", "GB");
    String apples = Ids.newId().toString();
    price(gb, apples, "3.00");
    measure(gb, apples, "WEIGHT", "KG", "1");
    Response q =
        post(
            "/prices/quote",
            "{\"lines\":[{\"variantId\":\"" + apples + "\",\"qty\":2.5}]}",
            gb,
            "CASHIER");
    JsonObject line = data(q).getJsonArray("lines").getJsonObject(0);
    assertThat(amount(line.getJsonObject("unitPricing")), is("3.60"));
    assertThat(line.getJsonObject("unitPricing").getString("label"), is("per kg"));
  }

  // ── gaps ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "No measure, no unit price: the item is a named gap until one is declared, and again if withdrawn")
  void noMeasureIsAGap() {
    Shop gb = shop("GBP", "GB");
    String unmeasured = Ids.newId().toString();
    price(gb, unmeasured, "2.00");

    JsonObject r = resolve(gb, unmeasured, "ONLINE");
    assertThat(
        "no unit price is invented",
        !r.containsKey("unitPricing") || r.isNull("unitPricing"),
        is(true));
    assertThat(r.getBoolean("unitPriceRequired"), is(true));
    JsonObject gaps = data(get("/admin/unit-pricing/gaps", gb, "MANAGER"));
    assertThat(gaps.getBoolean("required"), is(true));
    assertThat(gaps.toString(), containsString(unmeasured));

    measure(gb, unmeasured, "EACH", "EA", "6");
    assertThat(amount(resolve(gb, unmeasured, "ONLINE").getJsonObject("unitPricing")), is("0.40"));
    assertThat(
        data(get("/admin/unit-pricing/gaps", gb, "OWNER")).toString(),
        not(containsString(unmeasured)));

    measure(gb, unmeasured, "EACH", null, null);
    assertThat(
        data(get("/admin/unit-pricing/gaps", gb, "OWNER")).toString(), containsString(unmeasured));
  }

  // ── which law ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A German business is bound by EU law; a Japanese one is not, and rounds to whole yen")
  void whichBusinessesAreBound() {
    Shop de = shop("EUR", "DE");
    String de1 = Ids.newId().toString();
    price(de, de1, "1.00");
    assertThat(resolve(de, de1, "POS").getBoolean("unitPriceRequired"), is(true));

    Shop jp = shop("JPY", "JP");
    String rice = Ids.newId().toString();
    price(jp, rice, "498");
    measure(jp, rice, "EACH", "KG", "0.5");
    JsonObject r = resolve(jp, rice, "ONLINE");
    assertThat(r.getBoolean("unitPriceRequired"), is(false));
    // The VAT on ¥498 at 20% is ¥100 (99.6, whole yen — never 99.60), so half a kilo is ¥598
    // with VAT and a kilo ¥1,196: the currency's own minor units all the way through.
    assertThat(
        "598 yen with VAT for half a kilo is 1196 a kilo, whole yen",
        amount(r.getJsonObject("unitPricing")),
        is("1196"));
    assertThat(
        data(get("/admin/unit-pricing/gaps", jp, "OWNER")).getBoolean("required"), is(false));
  }

  // ── the projection ─────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A measure is recorded once per event, replaced by a later one, and malformed ones are skipped")
  void measuresAreProjectedOnceAndMalformedOnesSkipped() {
    Shop gb = shop("GBP", "GB");
    String v = Ids.newId().toString();
    price(gb, v, "1.00");
    String once = Ids.newId().toString();
    assertThat(
        catalogue.handle(measured(once, gb, v, "EACH", "KG", "0.25", VERSION.incrementAndGet())),
        is(true));
    assertThat(
        catalogue.handle(measured(once, gb, v, "EACH", "L", "9", VERSION.incrementAndGet())),
        is(false));
    assertThat(resolve(gb, v, "ONLINE").getJsonObject("unitPricing").getString("unit"), is("KG"));

    assertThat(measure(gb, v, "EACH", "BAG", "1"), is(false));
    assertThat(measure(gb, v, "EACH", "KG", "-1"), is(false));
    assertThat(measure(gb, v, "EACH", "KG", "0"), is(false));
    assertThat(measure(gb, v, "EACH", "KG", null), is(false));
    assertThat(measure(gb, v, "EACH", null, "1"), is(false));
    assertThat(measure(gb, v, "EACH", "KG", "a lot"), is(false));
    assertThat(
        catalogue.handle(
            "{\"eventType\":\"VariantMeasured\",\"tenantId\":\"" + gb.tenant() + "\"}"),
        is(false));
    assertThat(catalogue.handle("not json"), is(false));
    assertThat(
        "the last good measure stands",
        amount(resolve(gb, v, "ONLINE").getJsonObject("unitPricing")),
        is("4.80"));

    assertThat(measure(gb, v, "EACH", "L", "2"), is(true));
    assertThat(amount(resolve(gb, v, "ONLINE").getJsonObject("unitPricing")), is("0.60"));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Labels refuse bad input by name; labels are for staff and gaps for management")
  void refusals() {
    Shop gb = shop("GBP", "GB");
    String v = Ids.newId().toString();
    price(gb, v, "1.00");
    Response none = post("/prices/shelf-labels", "{\"variantIds\":[]}", gb, "STOREKEEPER");
    assertThat(none.getStatus(), is(400));
    assertThat(none.readEntity(String.class), containsString("PRICING_LABELS_INVALID"));
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 201; i++)
      many.append(i == 0 ? "" : ",").append('"').append(Ids.newId()).append('"');
    assertThat(
        post("/prices/shelf-labels", "{\"variantIds\":[" + many + "]}", gb, "STOREKEEPER")
            .readEntity(String.class),
        containsString("PRICING_LABELS_INVALID"));
    assertThat(
        post("/prices/shelf-labels", "{\"variantIds\":[\"x' OR '1'='1\"]}", gb, "STOREKEEPER")
            .getStatus(),
        is(400));
    assertThat(post("/prices/shelf-labels", "{}", gb, "STOREKEEPER").getStatus(), is(400));

    String unpriced = Ids.newId().toString();
    JsonObject label =
        Json.createReader(
                new StringReader(
                    post(
                            "/prices/shelf-labels",
                            "{\"variantIds\":[\"" + unpriced + "\"]}",
                            gb,
                            "CASHIER")
                        .readEntity(String.class)))
            .readObject()
            .getJsonArray("data")
            .getJsonObject(0);
    assertThat(
        "an unpriced variant is a label that says so, not a failed batch",
        label.getBoolean("priced"),
        is(false));

    assertThat(
        post("/prices/shelf-labels", "{\"variantIds\":[\"" + v + "\"]}", gb, "CUSTOMER")
            .getStatus(),
        is(403));
    assertThat(get("/admin/unit-pricing/gaps", gb, "CASHIER").getStatus(), is(403));
    assertThat(get("/admin/unit-pricing/gaps", gb, "CUSTOMER").getStatus(), is(403));

    // Another business sees neither this one's prices nor its gaps.
    Shop rival = shop("GBP", "GB");
    assertThat(
        data(get("/admin/unit-pricing/gaps", rival, "OWNER")).toString(), not(containsString(v)));
  }

  // ── abuse ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Twenty deliveries of one measure event at once record it once")
  void concurrentRedeliveryRecordsOnce() throws Exception {
    Shop gb = shop("GBP", "GB");
    String v = Ids.newId().toString();
    String event =
        measured(Ids.newId().toString(), gb, v, "EACH", "L", "0.5", VERSION.incrementAndGet());
    var pool = Executors.newFixedThreadPool(20);
    try {
      List<Future<Boolean>> tries = new ArrayList<>();
      for (int k = 0; k < 20; k++) tries.add(pool.submit(() -> catalogue.handle(event)));
      int recorded = 0;
      for (var f : tries) if (f.get()) recorded++;
      assertThat(recorded, is(1));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "An older measure arriving after a newer one never replaces it, and a missing version is malformed")
  void anOlderMeasureNeverReplacesANewerOne() {
    Shop gb = shop("GBP", "GB");
    String v = Ids.newId().toString();
    price(gb, v, "1.00");
    assertThat(
        catalogue.handle(measured(Ids.newId().toString(), gb, v, "EACH", "KG", "0.5", 7)),
        is(true));
    assertThat(
        "version 6 is older",
        catalogue.handle(measured(Ids.newId().toString(), gb, v, "EACH", "L", "1", 6)),
        is(false));
    assertThat(
        "version 7 again is not newer",
        catalogue.handle(measured(Ids.newId().toString(), gb, v, "EACH", "L", "1", 7)),
        is(false));
    assertThat(resolve(gb, v, "ONLINE").getJsonObject("unitPricing").getString("unit"), is("KG"));
    assertThat(
        catalogue.handle(measured(Ids.newId().toString(), gb, v, "EACH", "L", "1", 8)), is(true));
    assertThat(resolve(gb, v, "ONLINE").getJsonObject("unitPricing").getString("unit"), is("L"));

    String noVersion =
        measured(Ids.newId().toString(), gb, v, "EACH", "KG", "1", 9).replace("\"version\":9,", "");
    assertThat(catalogue.handle(noVersion), is(false));
    assertThat(
        catalogue.handle(measured(Ids.newId().toString(), gb, v, "EACH", "KG", "1", -1)),
        is(false));
    assertThat(resolve(gb, v, "ONLINE").getJsonObject("unitPricing").getString("unit"), is("L"));
  }

  @Test
  @DisplayName(
      "SJ-D55: 375 g at a price per kilogram is quoted, and a bulk-only tier still refuses one item")
  void aFractionOfAUnitIsPriced() {
    Shop gb = shop("GBP", "GB");
    String cheese = Ids.newId().toString();
    price(gb, cheese, "12.00");
    measure(gb, cheese, "WEIGHT", "KG", "1");
    Response q =
        post(
            "/prices/quote",
            "{\"lines\":[{\"variantId\":\"" + cheese + "\",\"qty\":0.375}]}",
            gb,
            "CASHIER");
    assertThat("a weighed line under one kilogram is priced, not refused", q.getStatus(), is(200));
    JsonObject line = data(q).getJsonArray("lines").getJsonObject(0);
    assertThat(line.getJsonNumber("lineTotal").bigDecimalValue().toPlainString(), is("4.50"));
    assertThat(
        "5.40 with VAT for 0.375 kg is 14.40 a kilogram",
        amount(line.getJsonObject("unitPricing")),
        is("14.40"));
    assertThat(
        post(
                "/prices/resolve",
                "{\"variantId\":\"" + cheese + "\",\"channel\":\"POS\",\"qty\":0.375}",
                gb,
                "CASHIER")
            .getStatus(),
        is(200));

    Shop bulk = shop("GBP", "GB");
    // Its own standard rate: nothing is quoted without one (SJ-D56).
    post(
            "/vat-rates",
            "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            bulk,
            "OWNER")
        .close();
    String screws = Ids.newId().toString();
    String list =
        id(
            post(
                "/admin/price-lists",
                "{\"name\":\"Bulk "
                    + Ids.newId()
                    + "\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
                bulk,
                "OWNER"));
    assertThat(
        post(
                "/admin/price-lists/" + list + "/items",
                "{\"variantId\":\"" + screws + "\",\"price\":0.05,\"minQty\":100}",
                bulk,
                "OWNER")
            .getStatus(),
        org.hamcrest.Matchers.lessThan(300));
    assertThat(
        "one item never gets the bulk price",
        post(
                "/prices/quote",
                "{\"lines\":[{\"variantId\":\"" + screws + "\",\"qty\":1}]}",
                bulk,
                "CASHIER")
            .getStatus(),
        is(404));
    assertThat(
        "nor half of one",
        post(
                "/prices/quote",
                "{\"lines\":[{\"variantId\":\"" + screws + "\",\"qty\":0.5}]}",
                bulk,
                "CASHIER")
            .getStatus(),
        is(404));
    assertThat(
        post(
                "/prices/quote",
                "{\"lines\":[{\"variantId\":\"" + screws + "\",\"qty\":100}]}",
                bulk,
                "CASHIER")
            .getStatus(),
        is(200));
  }
}
