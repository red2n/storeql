package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tax-inclusive price lists (intent/vat-inclusive-pricing.md): where a shop sells at VAT-inclusive
 * shelf prices, the shelf price is the price. It is quoted, resolved and its VAT carried as an
 * amount exactly, in every basket; a business that sells ex-VAT is unchanged; and a business in
 * retail mode cannot price an item it has no VAT category for, mix modes, or switch mode under
 * promotions whose money would change meaning. Real Postgres; tenant-svc is a stub.
 */
@HelidonTest
class PricingInclusiveIT {

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
    STUB = TenantSvcStub.start();
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private record Shop(String tenant, String list) {}

  private Response post(String path, String json, String tenant, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject object(Response r) {
    String body = r.readEntity(String.class);
    return Json.createReader(new StringReader(body)).readObject();
  }

  private static JsonObject data(Response r) {
    return object(r).getJsonObject("data");
  }

  private static String errorCode(Response r) {
    return object(r).getJsonObject("error").getString("code");
  }

  private static BigDecimal n(JsonObject o, String key) {
    return o.getJsonNumber(key).bigDecimalValue();
  }

  private static void rates(String tenant, TestRig rig) {
    rig.post(
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        tenant);
    rig.post(
        "/vat-rates",
        "{\"code\":\"T5\",\"name\":\"Reduced\",\"rate\":0.05,\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        tenant);
    rig.post(
        "/vat-rates",
        "{\"code\":\"T0\",\"name\":\"Zero\",\"rate\":0,\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        tenant);
  }

  /** The calls a setup makes, each of which must succeed. */
  private final class TestRig {
    void post(String path, String json, String tenant) {
      Response r = PricingInclusiveIT.this.post(path, json, tenant, "OWNER");
      String body = r.readEntity(String.class);
      assertThat(path + " " + body, r.getStatus() < 300, is(true));
    }

    String create(String path, String json, String tenant) {
      Response r = PricingInclusiveIT.this.post(path, json, tenant, "OWNER");
      assertThat(r.getStatus(), is(201));
      return data(r).getString("id");
    }
  }

  private final TestRig rig = new TestRig();

  private Shop shop(String taxMode) {
    String tenant = Ids.newId().toString();
    STUB.with(tenant, "GBP", "GB");
    rates(tenant, rig);
    String list =
        rig.create(
            "/admin/price-lists",
            "{\"name\":\"Shelf\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                + (taxMode == null ? "" : "\"taxMode\":\"" + taxMode + "\",")
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            tenant);
    return new Shop(tenant, list);
  }

  /** Puts a variant on the shop's list at the price, with its VAT code. */
  private String price(Shop s, String price, String vatCode) {
    String variant = Ids.newId().toString();
    rig.post(
        "/product-vat-categories",
        "{\"variantId\":\"" + variant + "\",\"vatCode\":\"" + vatCode + "\"}",
        s.tenant());
    rig.post(
        "/admin/price-lists/" + s.list() + "/items",
        "{\"variantId\":\"" + variant + "\",\"price\":" + price + ",\"minQty\":1}",
        s.tenant());
    return variant;
  }

  private JsonObject quote(Shop s, String... variantAndQty) {
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < variantAndQty.length; i += 2) {
      lines.append(i == 0 ? "" : ",");
      lines
          .append("{\"variantId\":\"")
          .append(variantAndQty[i])
          .append("\",\"qty\":")
          .append(variantAndQty[i + 1])
          .append("}");
    }
    Response r = post("/prices/quote", "{\"lines\":[" + lines + "]}", s.tenant(), "OWNER");
    assertThat(r.getStatus(), is(200));
    return data(r);
  }

  // ── a shelf price is the price ───────────────────────────────────────────────

  @Test
  @DisplayName("Shelf prices of 1.29 at 20%, 1.99 at 5% and 2.49 at 0% are quoted exactly")
  void shelfPricesAreQuotedExactly() {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "1.29", "T1");
    String b = price(s, "1.99", "T5");
    String c = price(s, "2.49", "T0");

    JsonObject q = quote(s, a, "1", b, "1", c, "1");

    assertThat(q.getBoolean("taxInclusive"), is(true));
    JsonArray lines = q.getJsonArray("lines");
    assertThat(n(lines.getJsonObject(0), "lineGross"), is(new BigDecimal("1.29")));
    assertThat(n(lines.getJsonObject(0), "vatAmount"), is(new BigDecimal("0.22")));
    assertThat(n(lines.getJsonObject(0), "netTotal"), is(new BigDecimal("1.07")));
    assertThat(n(lines.getJsonObject(1), "lineGross"), is(new BigDecimal("1.99")));
    assertThat(n(lines.getJsonObject(1), "vatAmount"), is(new BigDecimal("0.09")));
    assertThat(n(lines.getJsonObject(1), "netTotal"), is(new BigDecimal("1.90")));
    assertThat(n(lines.getJsonObject(2), "lineGross"), is(new BigDecimal("2.49")));
    assertThat(n(lines.getJsonObject(2), "vatAmount"), is(new BigDecimal("0.00")));
    assertThat(n(q, "subtotal"), is(new BigDecimal("5.77")));
    assertThat(n(q, "total"), is(new BigDecimal("5.77")));
    assertThat(n(q, "vatAmount"), is(new BigDecimal("0.31")));
    assertThat(q.getJsonArray("vatByRate").size(), is(3));
  }

  @Test
  @DisplayName(
      "A quantity is priced on the line: 3 at 1.29 is 3.87 with 0.65 VAT, not 3 times 0.22")
  void aQuantityIsPricedOnTheLine() {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "1.29", "T1");

    JsonObject line = quote(s, a, "3").getJsonArray("lines").getJsonObject(0);

    assertThat(n(line, "lineGross"), is(new BigDecimal("3.87")));
    assertThat(n(line, "vatAmount"), is(new BigDecimal("0.65")));
    assertThat(n(line, "netTotal"), is(new BigDecimal("3.22")));
  }

  @Test
  @DisplayName("Every shelf price from 1.00 to 1.99 in one basket comes out as itself")
  void everyPriceInAHundredPenceBandIsExact() {
    Shop s = shop("INCLUSIVE");
    List<String> variants = new ArrayList<>();
    List<String> prices = new ArrayList<>();
    for (int p = 100; p <= 199; p++) {
      String price = BigDecimal.valueOf(p, 2).toPlainString();
      prices.add(price);
      variants.add(price(s, price, "T1"));
    }
    String[] args = new String[variants.size() * 2];
    for (int i = 0; i < variants.size(); i++) {
      args[i * 2] = variants.get(i);
      args[i * 2 + 1] = "1";
    }

    JsonObject q = quote(s, args);

    BigDecimal sum = BigDecimal.ZERO;
    JsonArray lines = q.getJsonArray("lines");
    for (int i = 0; i < prices.size(); i++) {
      JsonObject l = lines.getJsonObject(i);
      assertThat("line " + prices.get(i), n(l, "lineGross"), is(new BigDecimal(prices.get(i))));
      assertThat(n(l, "netTotal").add(n(l, "vatAmount")).compareTo(n(l, "lineGross")), is(0));
      sum = sum.add(n(l, "lineGross"));
    }
    assertThat(n(q, "total").compareTo(sum), is(0));
  }

  @Test
  @DisplayName("Resolving one item shows its shelf price as the price with VAT")
  void resolvingOneItemShowsTheShelfPrice() {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "1.29", "T1");

    Response r = post("/prices/resolve", "{\"variantId\":\"" + a + "\"}", s.tenant(), "OWNER");

    assertThat(r.getStatus(), is(200));
    JsonObject p = data(r);
    assertThat(p.getBoolean("taxInclusive"), is(true));
    assertThat(n(p, "totalWithVat"), is(new BigDecimal("1.29")));
    assertThat(n(p, "vatAmount"), is(new BigDecimal("0.22")));
    assertThat(n(p, "unitPrice"), is(new BigDecimal("1.07")));
  }

  @Test
  @DisplayName(
      "A basket discount reduces the VAT: the lines still add up to the total to the penny")
  void aBasketDiscountReducesTheVat() {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "1.29", "T1");
    String b = price(s, "1.99", "T5");
    String promo =
        rig.create(
            "/admin/promotions",
            "{\"name\":\"Ten off the shop\",\"type\":\"BASKET_PERCENT\",\"value\":10,\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            s.tenant());
    rig.post("/admin/promotions/" + promo + "/items", "{\"scopeType\":\"ALL\"}", s.tenant());

    JsonObject q = quote(s, a, "1", b, "1");

    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    for (JsonObject l : q.getJsonArray("lines").getValuesAs(JsonObject.class)) {
      gross = gross.add(n(l, "lineGross"));
      vat = vat.add(n(l, "vatAmount"));
      assertThat(n(l, "netTotal").add(n(l, "vatAmount")).compareTo(n(l, "lineGross")), is(0));
    }
    assertThat(n(q, "totalDiscount").signum() > 0, is(true));
    assertThat(n(q, "total").compareTo(gross), is(0));
    assertThat(n(q, "total").compareTo(n(q, "subtotal").subtract(n(q, "totalDiscount"))), is(0));
    assertThat(n(q, "vatAmount").compareTo(vat), is(0));
    assertThat(
        "VAT fell with the discount",
        n(q, "vatAmount").compareTo(new BigDecimal("0.31")) < 0,
        is(true));
  }

  // ── a business that sells ex-VAT is unchanged ────────────────────────────────

  @Test
  @DisplayName("A list with no tax mode is net: 100.00 at 20% quotes to 120.00, as it always did")
  void anExclusiveBusinessIsUnchanged() {
    Shop s = shop(null);
    String a = price(s, "100.00", "T1");

    JsonObject q = quote(s, a, "1");

    assertThat(q.getBoolean("taxInclusive"), is(false));
    JsonObject line = q.getJsonArray("lines").getJsonObject(0);
    assertThat(n(line, "netTotal"), is(new BigDecimal("100.00")));
    assertThat(n(line, "vatAmount"), is(new BigDecimal("20.00")));
    assertThat(n(q, "total"), is(new BigDecimal("120.00")));
  }

  // ── guards ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Pricing an item with no VAT category on a tax-inclusive list is refused")
  void noVatCategoryIsRefusedOnAnInclusiveList() {
    Shop s = shop("INCLUSIVE");
    String variant = Ids.newId().toString();

    Response r =
        post(
            "/admin/price-lists/" + s.list() + "/items",
            "{\"variantId\":\"" + variant + "\",\"price\":1.29,\"minQty\":1}",
            s.tenant(),
            "OWNER");

    assertThat(r.getStatus(), is(409));
    assertThat(errorCode(r), is("PRICING_VAT_CATEGORY_REQUIRED"));
  }

  @Test
  @DisplayName("An ex-VAT business keeps its fallback: an item with no category is still priced")
  void anExclusiveBusinessStillPricesAnUncategorisedItem() {
    Shop s = shop(null);
    String variant = Ids.newId().toString();

    Response r =
        post(
            "/admin/price-lists/" + s.list() + "/items",
            "{\"variantId\":\"" + variant + "\",\"price\":10.00,\"minQty\":1}",
            s.tenant(),
            "OWNER");

    assertThat(r.getStatus() < 300, is(true));
  }

  @Test
  @DisplayName("A second list in the other mode is refused while one is active")
  void oneModeAtATime() {
    Shop s = shop("INCLUSIVE");

    Response r =
        post(
            "/admin/price-lists",
            "{\"name\":\"Net\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"taxMode\":\"EXCLUSIVE\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            s.tenant(),
            "OWNER");

    assertThat(r.getStatus(), is(409));
    assertThat(errorCode(r), is("PRICING_TAX_MODE_MIXED"));
  }

  @Test
  @DisplayName("A tax-inclusive list is refused while an amount promotion is running")
  void anAbsolutePromotionBlocksTheFirstInclusiveList() {
    String tenant = Ids.newId().toString();
    STUB.with(tenant, "GBP", "GB");
    rates(tenant, rig);
    rig.post(
        "/admin/promotions",
        "{\"name\":\"Five off\",\"type\":\"BASKET_FLAT\",\"value\":5.00,\"startsAt\":\"2020-01-01T00:00:00Z\"}",
        tenant);

    Response r =
        post(
            "/admin/price-lists",
            "{\"name\":\"Shelf\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"taxMode\":\"INCLUSIVE\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            tenant,
            "OWNER");

    assertThat(r.getStatus(), is(409));
    assertThat(errorCode(r), is("PRICING_PROMOTIONS_IN_OTHER_MODE"));
  }

  @Test
  @DisplayName("A tax mode nobody defined is a bad request")
  void anUnknownTaxModeIsRefused() {
    String tenant = Ids.newId().toString();
    STUB.with(tenant, "GBP", "GB");

    Response r =
        post(
            "/admin/price-lists",
            "{\"name\":\"Shelf\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"taxMode\":\"GROSS-ISH\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            tenant,
            "OWNER");

    assertThat(r.getStatus(), is(400));
  }

  @Test
  @DisplayName("Another business's lists and promotions never block this one's mode")
  void modesAreHeldPerBusiness() {
    shop("INCLUSIVE");
    String other = Ids.newId().toString();
    STUB.with(other, "GBP", "GB");
    rates(other, rig);

    Response r =
        post(
            "/admin/price-lists",
            "{\"name\":\"Net\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"taxMode\":\"EXCLUSIVE\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            other,
            "OWNER");

    assertThat(r.getStatus(), is(201));
  }

  @Test
  @DisplayName("Another business cannot quote this one's shelf price")
  void aPriceIsNotVisibleToAnotherBusiness() {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "1.29", "T1");
    String other = Ids.newId().toString();
    STUB.with(other, "GBP", "GB");
    rates(other, rig);

    Response r =
        post(
            "/prices/quote",
            "{\"lines\":[{\"variantId\":\"" + a + "\",\"qty\":1}]}",
            other,
            "OWNER");

    assertThat(r.getStatus(), is(404));
  }

  // ── the rest of the contract ─────────────────────────────────────────────────

  private Response get(String path, String tenant, String roles, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i + 1 < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", roles).get();
  }

  private String priceWithoutCategory(Shop s, String price) {
    String variant = Ids.newId().toString();
    rig.post(
        "/admin/price-lists/" + s.list() + "/items",
        "{\"variantId\":\"" + variant + "\",\"price\":" + price + ",\"minQty\":1}",
        s.tenant());
    return variant;
  }

  private static String batchBody(String... variantAndCode) {
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < variantAndCode.length; i += 2) {
      rows.append(i == 0 ? "" : ",");
      rows.append("{\"variantId\":\"")
          .append(variantAndCode[i])
          .append("\",\"vatCode\":\"")
          .append(variantAndCode[i + 1])
          .append("\"}");
    }
    return "{\"items\":[" + rows + "]}";
  }

  @Test
  @DisplayName("A list shows its tax mode")
  void aListShowsItsTaxMode() {
    Shop s = shop("INCLUSIVE");

    Response r = get("/price-lists/" + s.list(), s.tenant(), "CASHIER");

    assertThat(r.getStatus(), is(200));
    assertThat(data(r).getString("taxMode"), is("INCLUSIVE"));
    assertThat(data(shopListOf(shop(null))).getString("taxMode"), is("EXCLUSIVE"));
  }

  private Response shopListOf(Shop s) {
    return get("/price-lists/" + s.list(), s.tenant(), "CASHIER");
  }

  // ── promotions, stickers and other currencies ────────────────────────────────

  @Test
  @DisplayName("An item promotion on a shelf price reduces the shelf price and the VAT in it")
  void anItemPromotionReducesTheShelfPrice() {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "2.00", "T1");
    String promo =
        rig.create(
            "/admin/promotions",
            "{\"name\":\"Ten off\",\"type\":\"PERCENT\",\"value\":10,\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            s.tenant());
    rig.post("/admin/promotions/" + promo + "/items", "{\"scopeType\":\"ALL\"}", s.tenant());

    JsonObject line = quote(s, a, "1").getJsonArray("lines").getJsonObject(0);

    assertThat(n(line, "lineTotal"), is(new BigDecimal("2.00")));
    assertThat(n(line, "discount"), is(new BigDecimal("0.20")));
    assertThat(n(line, "lineGross"), is(new BigDecimal("1.80")));
    assertThat(n(line, "vatAmount"), is(new BigDecimal("0.30")));
    assertThat(n(line, "netTotal"), is(new BigDecimal("1.50")));
  }

  @Test
  @DisplayName("A reduced-price sticker cut from a shelf-price list is itself a shelf price")
  void aStickerOnAShelfPriceListIsAShelfPrice() {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "4.00", "T1");
    String store = Ids.newId().toString();
    Response created =
        post(
            "/markdowns",
            "{\"storeId\":\""
                + store
                + "\",\"variantId\":\""
                + a
                + "\",\"batchNo\":\"B-1\",\"expiryDate\":\""
                + java.time.LocalDate.now().plusDays(2)
                + "\",\"qty\":2,\"percentOff\":50,\"reason\":\"SHORT_DATED\"}",
            s.tenant(),
            "STOREKEEPER");
    assertThat(created.getStatus(), is(201));
    JsonObject md = data(created);
    assertThat(n(md, "markdownPrice"), is(new BigDecimal("2.00")));

    Response r =
        post(
            "/prices/quote",
            "{\"storeId\":\""
                + store
                + "\",\"lines\":[{\"variantId\":\""
                + a
                + "\",\"qty\":2,\"markdownId\":\""
                + md.getString("id")
                + "\"}]}",
            s.tenant(),
            "CASHIER");

    assertThat(r.getStatus(), is(200));
    JsonObject q = data(r);
    JsonObject line = q.getJsonArray("lines").getJsonObject(0);
    assertThat(q.getBoolean("taxInclusive"), is(true));
    assertThat(n(line, "lineGross"), is(new BigDecimal("4.00")));
    assertThat(n(line, "vatAmount"), is(new BigDecimal("0.67")));
    assertThat(n(line, "netTotal"), is(new BigDecimal("3.33")));
    assertThat(n(q, "total"), is(new BigDecimal("4.00")));
  }

  @Test
  @DisplayName("VAT is worked to the currency's own minor unit: whole yen and three-decimal dinars")
  void otherCurrenciesKeepTheirOwnMinorUnits() {
    String yen = Ids.newId().toString();
    STUB.with(yen, "JPY", "JP");
    rig.post(
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.10,\"exempt\":false,\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        yen);
    String yenList =
        rig.create(
            "/admin/price-lists",
            "{\"name\":\"Shelf\",\"channel\":\"ALL\",\"currency\":\"JPY\",\"taxMode\":\"INCLUSIVE\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            yen);
    Shop jp = new Shop(yen, yenList);
    String ramen = price(jp, "129", "T1");

    JsonObject yenLine = quote(jp, ramen, "1").getJsonArray("lines").getJsonObject(0);

    assertThat(n(yenLine, "lineGross"), is(new BigDecimal("129")));
    assertThat(n(yenLine, "vatAmount"), is(new BigDecimal("12")));
    assertThat(n(yenLine, "netTotal"), is(new BigDecimal("117")));

    String dinar = Ids.newId().toString();
    STUB.with(dinar, "KWD", "KW");
    rates(dinar, rig);
    String dinarList =
        rig.create(
            "/admin/price-lists",
            "{\"name\":\"Shelf\",\"channel\":\"ALL\",\"currency\":\"KWD\",\"taxMode\":\"INCLUSIVE\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            dinar);
    Shop kw = new Shop(dinar, dinarList);
    String dates = price(kw, "1.295", "T5");

    JsonObject dinarLine = quote(kw, dates, "1").getJsonArray("lines").getJsonObject(0);

    assertThat(n(dinarLine, "lineGross"), is(new BigDecimal("1.295")));
    assertThat(n(dinarLine, "vatAmount"), is(new BigDecimal("0.062")));
    assertThat(n(dinarLine, "netTotal"), is(new BigDecimal("1.233")));
  }

  // ── switching a list off and on ──────────────────────────────────────────────

  @Test
  @DisplayName("Switching a list back on is held to the same one-mode rule as making one")
  void reactivatingARetiredListIsHeldToTheModeRule() {
    Shop s = shop("INCLUSIVE");
    assertThat(
        post(
                "/admin/price-lists/" + s.list() + "/deactivate",
                "{\"reason\":\"retire\"}",
                s.tenant(),
                "OWNER")
            .getStatus(),
        is(200));
    String net =
        rig.create(
            "/admin/price-lists",
            "{\"name\":\"Net\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"taxMode\":\"EXCLUSIVE\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            s.tenant());

    Response blocked =
        post(
            "/admin/price-lists/" + s.list() + "/activate",
            "{\"reason\":\"back\"}",
            s.tenant(),
            "OWNER");

    assertThat(blocked.getStatus(), is(409));
    assertThat(errorCode(blocked), is("PRICING_TAX_MODE_MIXED"));
    assertThat(
        post(
                "/admin/price-lists/" + net + "/deactivate",
                "{\"reason\":\"retire\"}",
                s.tenant(),
                "OWNER")
            .getStatus(),
        is(200));
    assertThat(
        post(
                "/admin/price-lists/" + s.list() + "/activate",
                "{\"reason\":\"back\"}",
                s.tenant(),
                "OWNER")
            .getStatus(),
        is(200));
  }

  @Test
  @DisplayName("Ending the amount promotion lets the first tax-inclusive list through")
  void endingThePromotionClearsTheWay() {
    String tenant = Ids.newId().toString();
    STUB.with(tenant, "GBP", "GB");
    rates(tenant, rig);
    String promo =
        rig.create(
            "/admin/promotions",
            "{\"name\":\"Five off\",\"type\":\"BASKET_FLAT\",\"value\":5.00,\"startsAt\":\"2020-01-01T00:00:00Z\"}",
            tenant);
    String list =
        "{\"name\":\"Shelf\",\"channel\":\"ALL\",\"currency\":\"GBP\",\"taxMode\":\"INCLUSIVE\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}";
    Response refused = post("/admin/price-lists", list, tenant, "OWNER");
    assertThat(refused.getStatus(), is(409));
    JsonObject error = object(refused).getJsonObject("error");
    assertThat(error.getString("code"), is("PRICING_PROMOTIONS_IN_OTHER_MODE"));
    assertThat(error.getString("message").contains("Five off"), is(true));

    assertThat(
        post(
                "/admin/promotions/" + promo + "/deactivate",
                "{\"reason\":\"ended\"}",
                tenant,
                "OWNER")
            .getStatus(),
        is(200));

    assertThat(post("/admin/price-lists", list, tenant, "OWNER").getStatus(), is(201));
  }

  // ── VAT readiness and the batch fix ──────────────────────────────────────────

  @Test
  @DisplayName("Readiness lists priced variants with no category, and the batch clears them")
  void readinessListsGapsAndTheBatchClearsThem() {
    Shop s = shop(null);
    String done = price(s, "1.00", "T1");
    String gapA = priceWithoutCategory(s, "2.00");
    String gapB = priceWithoutCategory(s, "3.00");

    JsonObject before = data(get("/admin/pricing/vat-readiness", s.tenant(), "MANAGER"));

    assertThat(before.getInt("variantsWithoutCategory"), is(2));
    assertThat(before.getBoolean("ready"), is(false));
    assertThat(before.getString("taxMode"), is("EXCLUSIVE"));
    List<String> listed = new ArrayList<>();
    for (JsonObject g : before.getJsonArray("gaps").getValuesAs(JsonObject.class)) {
      listed.add(g.getString("variantId"));
    }
    assertThat(listed.contains(gapA) && listed.contains(gapB) && !listed.contains(done), is(true));

    Response fixed =
        post(
            "/product-vat-categories/batch",
            batchBody(gapA, "T1", gapB, "T5"),
            s.tenant(),
            "MANAGER");

    assertThat(fixed.getStatus(), is(200));
    assertThat(data(fixed).getInt("assigned"), is(2));
    JsonObject after = data(get("/admin/pricing/vat-readiness", s.tenant(), "MANAGER"));
    assertThat(after.getInt("variantsWithoutCategory"), is(0));
    assertThat(after.getBoolean("ready"), is(true));
    assertThat(after.getJsonArray("gaps").size(), is(0));
  }

  @Test
  @DisplayName("Readiness is paged by cursor")
  void readinessIsPaged() {
    Shop s = shop(null);
    for (int i = 0; i < 3; i++) priceWithoutCategory(s, "1.00");

    Response first = get("/admin/pricing/vat-readiness", s.tenant(), "OWNER", "limit", "2");

    JsonObject page = object(first);
    assertThat(page.getJsonObject("data").getJsonArray("gaps").size(), is(2));
    assertThat(page.getJsonObject("data").getInt("variantsWithoutCategory"), is(3));
    String next = page.getJsonObject("meta").getString("nextCursor");
    JsonObject second =
        object(
            get("/admin/pricing/vat-readiness", s.tenant(), "OWNER", "limit", "2", "after", next));
    assertThat(second.getJsonObject("data").getJsonArray("gaps").size(), is(1));
    assertThat(second.getJsonObject("meta").containsKey("nextCursor"), is(false));
  }

  @Test
  @DisplayName("The batch is all or none: one unknown code writes nothing and names the row")
  void theBatchIsAllOrNothing() {
    Shop s = shop(null);
    String a = Ids.newId().toString();
    String b = Ids.newId().toString();

    Response r =
        post("/product-vat-categories/batch", batchBody(a, "T1", b, "ZZ"), s.tenant(), "OWNER");

    assertThat(r.getStatus(), is(400));
    JsonObject err = object(r).getJsonObject("error");
    assertThat(err.getString("code"), is("PRICING_VAT_BATCH_INVALID"));
    assertThat(err.getString("message").contains("row 2"), is(true));
    assertThat(get("/product-vat-categories/" + a, s.tenant(), "OWNER").getStatus(), is(404));
  }

  @Test
  @DisplayName("The batch refuses a variant twice, an empty list and more than 500 rows")
  void theBatchRefusesMalformedRequests() {
    Shop s = shop(null);
    String a = Ids.newId().toString();
    assertThat(
        post("/product-vat-categories/batch", batchBody(a, "T1", a, "T5"), s.tenant(), "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        post("/product-vat-categories/batch", "{\"items\":[]}", s.tenant(), "OWNER").getStatus(),
        is(400));
    String[] many = new String[1002];
    for (int i = 0; i < many.length; i += 2) {
      many[i] = Ids.newId().toString();
      many[i + 1] = "T1";
    }
    assertThat(
        post("/product-vat-categories/batch", batchBody(many), s.tenant(), "OWNER").getStatus(),
        is(400));
  }

  @Test
  @DisplayName("Only management reads readiness or assigns categories in a batch")
  void onlyManagementManagesVatSetup() {
    Shop s = shop(null);
    String a = Ids.newId().toString();
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, get("/admin/pricing/vat-readiness", s.tenant(), role).getStatus(), is(403));
      assertThat(
          role,
          post("/product-vat-categories/batch", batchBody(a, "T1"), s.tenant(), role).getStatus(),
          is(403));
    }
    assertThat(get("/product-vat-categories/" + a, s.tenant(), "OWNER").getStatus(), is(404));
  }

  @Test
  @DisplayName("Another business's setup is invisible and untouched by this one's")
  void vatSetupIsPerBusiness() {
    Shop mine = shop(null);
    String gap = priceWithoutCategory(mine, "2.00");
    String other = Ids.newId().toString();
    STUB.with(other, "GBP", "GB");
    rates(other, rig);

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      JsonObject theirs = data(get("/admin/pricing/vat-readiness", other, role));
      assertThat(role, theirs.getInt("variantsWithoutCategory"), is(0));
      assertThat(role, theirs.getJsonArray("gaps").size(), is(0));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertThat(role, get("/admin/pricing/vat-readiness", other, role).getStatus(), is(403));
    }
    // their batch lands in their own business only
    assertThat(
        post("/product-vat-categories/batch", batchBody(gap, "T1"), other, "OWNER").getStatus(),
        is(200));
    assertThat(
        data(get("/admin/pricing/vat-readiness", mine.tenant(), "OWNER"))
            .getInt("variantsWithoutCategory"),
        is(1));
    assertThat(get("/product-vat-categories/" + gap, mine.tenant(), "OWNER").getStatus(), is(404));
  }

  @Test
  @DisplayName("A repricing proposal cannot be applied to a shelf price whose VAT category is gone")
  void repricingIsHeldToTheCategoryRule() throws Exception {
    Shop s = shop("INCLUSIVE");
    String a = price(s, "2.00", "T1");
    rig.create(
        "/admin/competitor-prices",
        "{\"variantId\":\""
            + a
            + "\",\"competitor\":\"Rival A\",\"price\":1.80,\"observedOn\":\""
            + java.time.LocalDate.now()
            + "\"}",
        s.tenant());
    String rule =
        rig.create(
            "/admin/repricing/rules",
            "{\"name\":\"Match\",\"priceListId\":\""
                + s.list()
                + "\",\"strategy\":\"MATCH_LOWEST\",\"value\":0,\"floorPercent\":50,"
                + "\"rounding\":\"NONE\",\"maxAgeDays\":14}",
            s.tenant());
    Response run = post("/admin/repricing/rules/" + rule + "/run", "{}", s.tenant(), "OWNER");
    assertThat(run.getStatus(), is(200));
    String proposal = data(run).getJsonArray("proposals").getJsonObject(0).getString("id");
    com.storeql.test.Envelopes.exec(
        PG,
        "DELETE FROM pricing.product_vat_categories WHERE tenant_id = '"
            + s.tenant()
            + "' AND variant_id = '"
            + a
            + "'");

    Response apply =
        post("/admin/repricing/proposals/" + proposal + "/apply", "{}", s.tenant(), "OWNER");

    assertThat(apply.getStatus(), is(409));
    assertThat(errorCode(apply), is("PRICING_VAT_CATEGORY_REQUIRED"));
  }
}
