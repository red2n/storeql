package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.pricing.messaging.SalesTaxEventHandler;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Output VAT from real sales: the events of a sale and of what comes back from it become the
 * figures boxes 1 and 6 of the VAT return are made of. Until this, nothing wrote them, so a
 * business's return showed no sales. Each test is a business of its own.
 */
@HelidonTest
class SalesTaxIT {

  private static final PostgresSupport PG;
  private static final List<String[]> BUSINESSES = new ArrayList<>();
  private static int next = 0;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub stub = TenantSvcStub.start();
    for (int i = 0; i < 16; i++) {
      String tenant = Ids.newId().toString();
      String store = Ids.newId().toString();
      stub.with(tenant, "GBP", "GB").withStoreIn(tenant, store, "GB", "Europe/London");
      BUSINESSES.add(new String[] {tenant, store});
    }
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject SalesTaxEventHandler handler;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private static final String BREAD = Ids.newId().toString();
  private static final String JAM = Ids.newId().toString();
  private static final String BOOK = Ids.newId().toString();
  private static final String JUNE = "2026-06-15T10:00:00Z";

  private static synchronized String[] biz() {
    return BUSINESSES.get(next++);
  }

  private int feed(String json) {
    return handler.handle(json);
  }

  // ── event builders ───────────────────────────────────────────────────────────

  private static String line(String variant, String code, String rate, String vat, String gross) {
    return "{\"variantId\":\""
        + variant
        + "\",\"lineId\":\""
        + Ids.newId()
        + "\",\"qty\":1,\"lineTotal\":"
        + new BigDecimal(gross).subtract(new BigDecimal(vat))
        + ",\"vatAmount\":"
        + vat
        + ",\"vatCode\":\""
        + code
        + "\",\"vatRate\":"
        + rate
        + ",\"grossTotal\":"
        + gross
        + "}";
  }

  private static String shelfBasket() {
    return line(BREAD, "T1", "0.2", "0.22", "1.29")
        + ","
        + line(JAM, "T5", "0.05", "0.09", "1.99")
        + ","
        + line(BOOK, "T0", "0", "0.00", "2.49");
  }

  private static String confirmed(
      String eventId, String[] b, String order, String at, String lines) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderConfirmed\",\"occurredAt\":\""
        + at
        + "\",\"tenantId\":\""
        + b[0]
        + "\",\"orderId\":\""
        + order
        + "\",\"storeId\":\""
        + b[1]
        + "\",\"channel\":\"POS\",\"lines\":["
        + lines
        + "]}";
  }

  private static String returned(String[] b, String order, String at, String total, String items) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"OrderReturned\",\"occurredAt\":\""
        + at
        + "\",\"tenantId\":\""
        + b[0]
        + "\",\"orderId\":\""
        + order
        + "\",\"returnId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + b[1]
        + "\",\"currency\":\"GBP\",\"refundAmount\":"
        + total
        + ",\"items\":["
        + items
        + "]}";
  }

  private static String ended(String type, String[] b, String order, String at) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + type
        + "\",\"occurredAt\":\""
        + at
        + "\",\"tenantId\":\""
        + b[0]
        + "\",\"orderId\":\""
        + order
        + "\",\"storeId\":\""
        + b[1]
        + "\"}";
  }

  // ── what the return reads ────────────────────────────────────────────────────

  private JsonObject vatReturn(String tenant, String from, String to) {
    Response r =
        target
            .path("/vat-return")
            .queryParam("from", from)
            .queryParam("to", to)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", "OWNER")
            .get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private JsonObject june(String tenant) {
    return vatReturn(tenant, "2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z");
  }

  private static void box(JsonObject r, String name, String expected) {
    assertThat(
        name, r.getJsonNumber(name).bigDecimalValue(), comparesEqualTo(new BigDecimal(expected)));
  }

  private static String count(String sql) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (java.sql.SQLException e) {
      throw new AssertionError(e);
    }
  }

  private static String rows(String order) {
    return count("SELECT count(*) FROM pricing.tax_transactions WHERE order_id = '" + order + "'");
  }

  // ── a sale ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "a shelf-price sale is output VAT of 0.31 on net sales of 5.46, line by line and by rate")
  void aSaleIsOutputVat() {
    String[] b = biz();
    String order = Ids.newId().toString();

    assertThat(feed(confirmed(Ids.newId().toString(), b, order, JUNE, shelfBasket())), is(3));

    JsonObject r = june(b[0]);
    box(r, "box1", "0.31");
    box(r, "box6", "5.46");
    assertThat(
        count(
            "SELECT string_agg(vat_code || ':' || net_amount || ':' || vat_amount, ',' ORDER BY vat_code)"
                + " FROM pricing.tax_transactions WHERE order_id = '"
                + order
                + "'"),
        is("T0:2.49:0.00,T1:1.07:0.22,T5:1.90:0.09"));
  }

  @Test
  @DisplayName("an event delivered twice records the sale once")
  void redeliveryRecordsNothingTwice() {
    String[] b = biz();
    String order = Ids.newId().toString();
    String event = confirmed(Ids.newId().toString(), b, order, JUNE, shelfBasket());

    assertThat(feed(event), is(3));
    assertThat(feed(event), is(0));

    assertThat(rows(order), is("3"));
    box(june(b[0]), "box1", "0.31");
  }

  @Test
  @DisplayName("a net-priced line (no gross carried) is gross = net + VAT")
  void aNetPricedLine() {
    String[] b = biz();
    String order = Ids.newId().toString();
    String line =
        "{\"variantId\":\""
            + BREAD
            + "\",\"qty\":1,\"lineTotal\":10.00,\"vatAmount\":2.00,\"vatCode\":\"T1\",\"vatRate\":0.2}";

    assertThat(feed(confirmed(Ids.newId().toString(), b, order, JUNE, line)), is(1));

    JsonObject r = june(b[0]);
    box(r, "box1", "2.00");
    box(r, "box6", "10.00");
    assertThat(
        count("SELECT gross_amount FROM pricing.tax_transactions WHERE order_id = '" + order + "'"),
        is("12.00"));
  }

  // ── what comes back ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("a returned loaf takes back its 1.29 with the 0.22 of VAT inside it")
  void aReturnTakesBackItsVat() {
    String[] b = biz();
    String order = Ids.newId().toString();
    feed(confirmed(Ids.newId().toString(), b, order, JUNE, shelfBasket()));

    int n =
        feed(
            returned(
                b,
                order,
                "2026-06-16T09:00:00Z",
                "1.29",
                "{\"variantId\":\""
                    + BREAD
                    + "\",\"qty\":1,\"netAmount\":1.07,\"vatAmount\":0.22}"));

    assertThat(n, is(1));
    JsonObject r = june(b[0]);
    box(r, "box1", "0.09");
    box(r, "box6", "4.39");
    assertThat(
        count(
            "SELECT vat_code || ':' || vat_rate FROM pricing.tax_transactions WHERE order_id = '"
                + order
                + "' AND gross_amount < 0"),
        is("T1:0.2000"));
  }

  @Test
  @DisplayName(
      "a return line with no VAT of its own is split from the return's total: net 1.90 of 1.99 leaves 0.09")
  void aReturnWithNoVatFigureIsDerived() {
    String[] b = biz();
    String order = Ids.newId().toString();
    feed(confirmed(Ids.newId().toString(), b, order, JUNE, shelfBasket()));

    feed(
        returned(
            b,
            order,
            "2026-06-16T09:00:00Z",
            "1.99",
            "{\"variantId\":\"" + JAM + "\",\"qty\":1,\"netAmount\":1.90}"));

    JsonObject r = june(b[0]);
    box(r, "box1", "0.22");
    box(r, "box6", "3.56");
    assertThat(
        count(
            "SELECT invoice_ref || ':' || vat_code FROM pricing.tax_transactions WHERE order_id = '"
                + order
                + "' AND gross_amount < 0"),
        is("VAT-DERIVED:T5"));
  }

  @Test
  @DisplayName("a two-line return's VAT is shared over its lines to the penny, whatever the split")
  void theSplitAddsUp() {
    String[] b = biz();
    String order = Ids.newId().toString();
    feed(confirmed(Ids.newId().toString(), b, order, JUNE, shelfBasket()));

    // 3.28 refunded for the loaf and the jam: nets 1.07 and 1.90 leave 0.31 to share
    feed(
        returned(
            b,
            order,
            "2026-06-16T09:00:00Z",
            "3.28",
            "{\"variantId\":\""
                + BREAD
                + "\",\"qty\":1,\"netAmount\":1.07},{\"variantId\":\""
                + JAM
                + "\",\"qty\":1,\"netAmount\":1.90}"));

    JsonObject r = june(b[0]);
    box(r, "box1", "0.00"); // 0.31 sold, 0.31 taken back, whichever way it is shared
    box(r, "box6", "2.49");
  }

  @Test
  @DisplayName("a return with no sale behind it (a no-receipt return) is VAT taken back too")
  void aNoReceiptReturn() {
    String[] b = biz();
    String ret = Ids.newId().toString();
    String event =
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"NoReceiptReturnRecorded\",\"occurredAt\":\""
            + JUNE
            + "\",\"tenantId\":\""
            + b[0]
            + "\",\"returnId\":\""
            + ret
            + "\",\"storeId\":\""
            + b[1]
            + "\",\"items\":[{\"variantId\":\""
            + BREAD
            + "\",\"qty\":1,\"unitPrice\":1.29,\"taxAmount\":0.22,\"condition\":\"SEALED\"}]}";

    assertThat(feed(event), is(1));

    JsonObject r = june(b[0]);
    box(r, "box1", "-0.22");
    box(r, "box6", "-1.07");
  }

  @Test
  @DisplayName(
      "a void after a part-return takes back only what is left; a cancel before any sale takes back nothing")
  void aVoidTakesBackWhatIsLeft() {
    String[] b = biz();
    String order = Ids.newId().toString();
    feed(confirmed(Ids.newId().toString(), b, order, JUNE, shelfBasket()));
    feed(
        returned(
            b,
            order,
            "2026-06-16T09:00:00Z",
            "1.29",
            "{\"variantId\":\"" + BREAD + "\",\"qty\":1,\"netAmount\":1.07,\"vatAmount\":0.22}"));

    int back = feed(ended("OrderVoided", b, order, "2026-06-17T09:00:00Z"));

    assertThat(back, is(2)); // the jam and the book; the loaf had gone already
    JsonObject r = june(b[0]);
    box(r, "box1", "0.00");
    box(r, "box6", "0.00");
    // and asking again takes back nothing more
    assertThat(feed(ended("OrderVoided", b, order, "2026-06-18T09:00:00Z")), is(0));
    box(june(b[0]), "box1", "0.00");

    assertThat(feed(ended("OrderCancelled", b, Ids.newId().toString(), JUNE)), is(0));
  }

  // ── the day it belongs to ────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "a sale at 00:30 on 1 July in London is July's, though it is 23:30 on 30 June in UTC")
  void theStoresOwnDay() {
    String[] b = biz();
    String order = Ids.newId().toString();

    feed(confirmed(Ids.newId().toString(), b, order, "2026-06-30T23:30:00Z", shelfBasket()));

    box(june(b[0]), "box1", "0.00");
    JsonObject july = vatReturn(b[0], "2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z");
    box(july, "box1", "0.31");
    box(july, "box6", "5.46");
    // and in winter the two days are the same
    String[] w = biz();
    feed(
        confirmed(
            Ids.newId().toString(),
            w,
            Ids.newId().toString(),
            "2026-12-31T23:30:00Z",
            shelfBasket()));
    box(vatReturn(w[0], "2026-12-01T00:00:00Z", "2027-01-01T00:00:00Z"), "box1", "0.31");
  }

  // ── nothing else ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a business's sales are in its own return only")
  void perBusiness() {
    String[] mine = biz();
    String[] theirs = biz();
    feed(confirmed(Ids.newId().toString(), mine, Ids.newId().toString(), JUNE, shelfBasket()));

    box(june(theirs[0]), "box1", "0.00");
    box(june(theirs[0]), "box6", "0.00");
    box(june(mine[0]), "box1", "0.31");
  }

  @Test
  @DisplayName("what is not a sale event, or is malformed, records nothing and does not throw")
  void notOurs() {
    String[] b = biz();
    assertThat(feed("not json"), is(0));
    assertThat(feed("{\"eventType\":\"OrderConfirmed\"}"), is(0));
    assertThat(feed("{\"eventType\":\"StockReceived\",\"tenantId\":\"" + b[0] + "\"}"), is(0));
    // a line with no VAT figure is left out rather than guessed
    String line = "{\"variantId\":\"" + BREAD + "\",\"qty\":1,\"lineTotal\":5.00}";
    assertThat(
        feed(confirmed(Ids.newId().toString(), b, Ids.newId().toString(), JUNE, line)), is(0));
    box(june(b[0]), "box1", "0.00");
  }
}
