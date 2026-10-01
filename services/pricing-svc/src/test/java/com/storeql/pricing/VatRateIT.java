package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import com.storeql.ids.Ids;
import com.storeql.pricing.service.AppliedPriceService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SJ-D56: no VAT rate is ever assumed. A business whose standard rate (T1) is not set is quoted
 * nothing — not 20%, not zero — and says why; one that charges no VAT sets it exempt; only
 * management sets what sales are taxed at.
 */
@HelidonTest
class VatRateIT {

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
    System.setProperty("storeql.pricing.applied-price-sweeper.enabled", "false");
    STUB = TenantSvcStub.start();
  }

  @Inject WebTarget target;
  @Inject AppliedPriceService ledger;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private record Shop(String tenant, String currency) {}

  private static Shop shop(String currency, String country) {
    String t = Ids.newId().toString();
    STUB.with(t, currency, country);
    return new Shop(t, currency);
  }

  private Response send(String method, String path, String json, Shop s, String roles) {
    var request =
        target.path(path).request().header("X-Tenant-Id", s.tenant()).header("X-Roles", roles);
    return json == null
        ? request.method(method)
        : request.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject body(Response r) {
    return Json.createReader(new StringReader(r.readEntity(String.class))).readObject();
  }

  private static String errorCode(Response r) {
    JsonObject b = body(r);
    return b.containsKey("error") && !b.isNull("error")
        ? b.getJsonObject("error").getString("code")
        : null;
  }

  private static String rate(String code, String rate, boolean exempt) {
    return "{\"code\":\""
        + code
        + "\",\"name\":\"Standard\",\"rate\":"
        + rate
        + ",\"exempt\":"
        + exempt
        + ",\"effectiveFrom\":\"2020-01-01T00:00:00Z\"}";
  }

  /** A list carrying the variant at a net price, and no VAT rate. */
  private void priced(Shop s, String variant, String net) {
    Response list =
        send(
            "POST",
            "/admin/price-lists",
            "{\"name\":\"VAT "
                + Ids.newId()
                + "\",\"channel\":\"ALL\",\"currency\":\""
                + s.currency()
                + "\",\"effectiveFrom\":\"2020-01-01T00:00:00Z\"}",
            s,
            "OWNER");
    assertThat(list.getStatus(), is(201));
    String listId = body(list).getJsonObject("data").getString("id");
    assertThat(
        send(
                "POST",
                "/admin/price-lists/" + listId + "/items",
                "{\"variantId\":\"" + variant + "\",\"price\":" + net + ",\"minQty\":1}",
                s,
                "OWNER")
            .getStatus(),
        lessThan(300));
  }

  private Response resolve(Shop s, String variant) {
    return send(
        "POST",
        "/prices/resolve",
        "{\"variantId\":\"" + variant + "\",\"channel\":\"ONLINE\",\"qty\":1}",
        s,
        "CUSTOMER");
  }

  private void drain() {
    for (int i = 0; i < 50 && ledger.processDue() > 0; i++) {
      // keep going
    }
  }

  @Test
  @DisplayName(
      "A Japanese business with no standard rate is quoted nothing and told why; once set, 10% is charged")
  void noRateNoPrice() {
    Shop jp = shop("JPY", "JP");
    String v = Ids.newId().toString();
    priced(jp, v, "1000");

    Response refused = resolve(jp, v);
    assertThat(refused.getStatus(), is(409));
    assertThat(errorCode(refused), is("PRICING_VAT_RATE_NOT_CONFIGURED"));
    Response quote =
        send(
            "POST",
            "/prices/quote",
            "{\"channel\":\"POS\",\"lines\":[{\"variantId\":\"" + v + "\",\"qty\":1}]}",
            jp,
            "OWNER");
    assertThat("a basket is not quoted either", quote.getStatus(), is(409));
    assertThat(errorCode(quote), is("PRICING_VAT_RATE_NOT_CONFIGURED"));
    Response labels =
        send("POST", "/prices/shelf-labels", "{\"variantIds\":[\"" + v + "\"]}", jp, "STOREKEEPER");
    assertThat("nor a shelf label printed with a guessed tax", labels.getStatus(), is(409));

    assertThat(
        send("POST", "/vat-rates", rate("T1", "0.10", false), jp, "OWNER").getStatus(), is(201));
    Response ok = resolve(jp, v);
    assertThat(ok.getStatus(), is(200));
    JsonObject price = body(ok).getJsonObject("data");
    assertThat(
        price
            .getJsonNumber("vatRate")
            .bigDecimalValue()
            .compareTo(new java.math.BigDecimal("0.10")),
        is(0));
    assertThat(
        price
            .getJsonNumber("totalWithVat")
            .bigDecimalValue()
            .compareTo(new java.math.BigDecimal("1100")),
        is(0));
  }

  @Test
  @DisplayName("A business that charges no VAT sets its standard rate exempt, and is charged none")
  void exemptChargesNone() {
    Shop kw = shop("KWD", "KW");
    String v = Ids.newId().toString();
    priced(kw, v, "2.500");
    assertThat(send("POST", "/vat-rates", rate("T1", "0", true), kw, "OWNER").getStatus(), is(201));
    JsonObject price = body(resolve(kw, v)).getJsonObject("data");
    assertThat(price.getJsonNumber("vatAmount").bigDecimalValue().signum(), is(0));
    assertThat(
        price
            .getJsonNumber("totalWithVat")
            .bigDecimalValue()
            .compareTo(price.getJsonNumber("unitPrice").bigDecimalValue()),
        is(0));
  }

  @Test
  @DisplayName("Only management sets what sales are taxed at; any staff member may read it")
  void onlyManagementSetsVat() {
    Shop gb = shop("GBP", "GB");
    String v = Ids.newId().toString();
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Response create = send("POST", "/vat-rates", rate("T1", "0.20", false), gb, role);
      assertThat(role + " creating a rate", create.getStatus(), is(403));
    }
    assertThat(
        send("POST", "/vat-rates", rate("T1", "0.20", false), gb, "CUSTOMER").getStatus(), is(403));
    assertThat(
        send("POST", "/vat-rates", rate("T1", "0.20", false), gb, "MANAGER").getStatus(), is(201));
    assertThat(
        send("POST", "/vat-rates", rate("T0", "0", false), gb, "MANAGER").getStatus(), is(201));
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      assertThat(
          role + " lowering the rate",
          send("PUT", "/vat-rates/T1", rate("T1", "0.01", false), gb, role).getStatus(),
          is(403));
      assertThat(
          role + " moving a product to zero-rated",
          send(
                  "POST",
                  "/product-vat-categories",
                  "{\"variantId\":\"" + v + "\",\"vatCode\":\"T0\"}",
                  gb,
                  role)
              .getStatus(),
          is(403));
    }
    assertThat(send("GET", "/vat-rates/T1", null, gb, "CASHIER").getStatus(), is(200));
    assertThat(
        send(
                "POST",
                "/product-vat-categories",
                "{\"variantId\":\"" + v + "\",\"vatCode\":\"T0\"}",
                gb,
                "OWNER")
            .getStatus(),
        is(200));
  }

  @Test
  @DisplayName("A rate outside 0 to 1, a percentage sent as a fraction, or no number is refused")
  void badRatesRefused() {
    Shop gb = shop("GBP", "GB");
    for (String bad : new String[] {"1.5", "-0.1", "20", "null"}) {
      Response r = send("POST", "/vat-rates", rate("T1", bad, false), gb, "OWNER");
      assertThat(bad, r.getStatus(), is(400));
      assertThat(bad, errorCode(r), is("VALIDATION_FAILED"));
    }
    // SJ-D57: a body that is not the JSON the request takes was a 500 on every service.
    for (String body :
        new String[] {
          rate("T1", "\"twenty\"", false),
          "{not json",
          "[]",
          "{\"code\":\"T1\",\"rate\":{}}",
          "\"T1\""
        }) {
      Response r = send("POST", "/vat-rates", body, gb, "OWNER");
      assertThat(body, r.getStatus(), is(400));
      assertThat(body, errorCode(r), is("REQUEST_BODY_INVALID"));
    }
    Response none = send("GET", "/vat-rates/T1", null, gb, "OWNER");
    assertThat("nothing refused was stored", none.getStatus(), is(404));
    assertThat(errorCode(none), is("PRICING_VAT_CODE_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "The ledger records no offer while no rate is set, and the price from the moment it is")
  void theLedgerWaitsForTheRate() {
    Shop de = shop("EUR", "DE");
    String v = Ids.newId().toString();
    priced(de, v, "10.00");
    drain();
    JsonObject before = history(de, v);
    assertThat(
        "no rate: not offered",
        before.getJsonArray("rows").getJsonObject(0).getBoolean("priced"),
        is(false));

    assertThat(
        send("POST", "/vat-rates", rate("T1", "0.19", false), de, "OWNER").getStatus(), is(201));
    drain();
    JsonObject latest = history(de, v).getJsonArray("rows").getJsonObject(0);
    assertThat(latest.getBoolean("priced"), is(true));
    assertThat(
        latest
            .getJsonNumber("price")
            .bigDecimalValue()
            .compareTo(new java.math.BigDecimal("11.90")),
        is(0));
  }

  private JsonObject history(Shop s, String variant) {
    Response r =
        target
            .path("/admin/prices/history")
            .queryParam("variantId", variant)
            .request()
            .header("X-Tenant-Id", s.tenant())
            .header("X-Roles", "MANAGER")
            .get();
    assertThat(r.getStatus(), is(200));
    return body(r).getJsonObject("data");
  }

  @Test
  @DisplayName(
      "Twenty shoppers at once with no rate set are all refused cleanly; twenty owners setting it at once make one")
  void atOnce() throws Exception {
    Shop pt = shop("EUR", "PT");
    String v = Ids.newId().toString();
    priced(pt, v, "10.00");
    var pool = Executors.newFixedThreadPool(10);
    try {
      List<Callable<Response>> shoppers = new ArrayList<>();
      for (int i = 0; i < 20; i++) shoppers.add(() -> resolve(pt, v));
      for (Future<Response> f : pool.invokeAll(shoppers)) {
        Response r = f.get();
        assertThat(r.getStatus(), is(409));
        assertThat(errorCode(r), is("PRICING_VAT_RATE_NOT_CONFIGURED"));
      }
      List<Callable<Response>> owners = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        owners.add(() -> send("POST", "/vat-rates", rate("T1", "0.23", false), pt, "OWNER"));
      }
      int created = 0;
      for (Future<Response> f : pool.invokeAll(owners)) {
        int status = f.get().getStatus();
        if (status == 201) created++;
        else assertThat(status, is(409));
      }
      assertThat(created, is(1));
    } finally {
      pool.shutdownNow();
    }
    assertThat(resolve(pt, v).getStatus(), is(200));
  }

  @Test
  @DisplayName(
      "A variant with no VAT category, an unknown VAT code, and another business's variant are refused by name")
  void anUncategorisedVariantHasNoVatCategory() {
    Shop gb = shop("GBP", "GB");
    Shop rival = shop("GBP", "GB");
    String v = Ids.newId().toString();
    String other = Ids.newId().toString();
    assertThat(
        send("POST", "/vat-rates", rate("T1", "0.20", false), gb, "OWNER").getStatus(), is(201));
    assertThat(
        send("POST", "/vat-rates", rate("T1", "0.20", false), rival, "OWNER").getStatus(), is(201));

    Response none = send("GET", "/product-vat-categories/" + v, null, gb, "OWNER");
    assertThat(none.getStatus(), is(404));
    assertThat(errorCode(none), is("PRICING_VAT_CATEGORY_NOT_FOUND"));

    // A code the business has not configured: refused, and the variant is still uncategorised.
    Response unknown =
        send(
            "POST",
            "/product-vat-categories",
            "{\"variantId\":\"" + v + "\",\"vatCode\":\"ZZ\"}",
            gb,
            "OWNER");
    assertThat(unknown.getStatus(), is(404));
    assertThat(errorCode(unknown), is("PRICING_VAT_CODE_NOT_FOUND"));
    Response still = send("GET", "/product-vat-categories/" + v, null, gb, "OWNER");
    assertThat(still.getStatus(), is(404));
    assertThat(errorCode(still), is("PRICING_VAT_CATEGORY_NOT_FOUND"));

    // Another business's categorised variant is not ours to read, whatever the role.
    assertThat(
        send(
                "POST",
                "/product-vat-categories",
                "{\"variantId\":\"" + other + "\",\"vatCode\":\"T1\"}",
                rival,
                "OWNER")
            .getStatus(),
        is(200));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Response foreign = send("GET", "/product-vat-categories/" + other, null, gb, role);
      assertThat(role, foreign.getStatus(), is(404));
      assertThat(role, errorCode(foreign), is("PRICING_VAT_CATEGORY_NOT_FOUND"));
    }
    assertThat(
        send("GET", "/product-vat-categories/" + other, null, rival, "OWNER").getStatus(), is(200));
  }
}
