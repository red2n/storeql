package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.Domain.SaleLine;
import com.storeql.reporting.service.ReportingService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A voided till sale leaves the sales reports: order-svc's {@code OrderVoided}, projected over a
 * real database and read back over HTTP.
 *
 * <p>The sale's fact is <b>marked, never deleted</b> — the row and its lines stay, with the moment
 * the void was heard — and every report that sums sales leaves it out: the summary, the days, the
 * categories and labour against takings. The void is <b>idempotent per event</b>, a void heard
 * <b>before its sale</b> still voids the sale when it lands, and <b>another business's void naming
 * our order id changes nothing of ours</b>. Kafka is off in-test, so the projection is fed as the
 * dispatcher feeds it.
 */
@HelidonTest
class SalesVoidIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("reporting");

  /** The consumer name SalesEventDispatcher dedupes under. */
  private static final String CONSUMER = "reporting-svc/sales-events";

  private static final String[] STAFF = {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER"};

  @Inject WebTarget target;
  @Inject ReportingService reporting;

  // One business per test: Helidon keeps one test instance per class and the tests share a
  // database, so each seeds its own shop.
  private UUID tenant;
  private UUID otherTenant;
  private UUID store;
  private UUID drinks;
  private UUID snacks;
  private UUID cola;
  private UUID crisps;

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    otherTenant = Ids.newId();
    store = Ids.newId();
    drinks = Ids.newId();
    snacks = Ids.newId();
    UUID colaProduct = Ids.newId();
    UUID crispsProduct = Ids.newId();
    cola = Ids.newId();
    crisps = Ids.newId();
    OffsetDateTime announced = OffsetDateTime.parse("2026-09-23T09:00:00Z");
    reporting.applyProductCategorised(
        tenant, colaProduct, List.of(drinks), List.of(cola), announced.toInstant());
    reporting.applyProductCategorised(
        tenant, crispsProduct, List.of(snacks), List.of(crisps), announced.toInstant());
  }

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── feeding the projection, as SalesEventDispatcher does ─────────────────

  private void sale(UUID business, UUID order, String channel, String gross, SaleLine line) {
    reporting.recordSale(
        business, order, store, channel, null, new BigDecimal(gross), "GBP", List.of(line));
  }

  private static SaleLine line(UUID variant, String qty, String total) {
    return new SaleLine(variant, new BigDecimal(qty), null, new BigDecimal(total));
  }

  private void voided(UUID eventId, UUID business, UUID order) {
    reporting.applySaleVoided(eventId, CONSUMER, business, order);
  }

  // ── reading the reports ──────────────────────────────────────────────────

  private Response get(UUID business, String role, UUID storeScope, String pathAndQuery) {
    var request =
        WebTargets.at(target, pathAndQuery)
            .request()
            .header("X-Tenant-Id", business.toString())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role);
    if (storeScope != null) {
      request = request.header("X-Store-Ids", storeScope.toString());
    }
    return request.get();
  }

  private JsonObject report(UUID business, String pathAndQuery) {
    try (Response r = get(business, "OWNER", null, pathAndQuery)) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(200));
      return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
    }
  }

  private static List<JsonObject> rows(JsonObject data) {
    return data.getJsonArray("rows").getValuesAs(JsonObject.class);
  }

  private static BigDecimal money(JsonObject row, String field) {
    return row.getJsonNumber(field).bigDecimalValue();
  }

  private static JsonObject only(JsonObject data) {
    List<JsonObject> rows = rows(data);
    assertThat(rows.toString(), rows.size(), is(1));
    return rows.get(0);
  }

  private JsonObject summary(UUID business) {
    return report(business, "/admin/reports/sales/summary");
  }

  private JsonObject byDay(UUID business) {
    return report(business, "/admin/reports/sales/by-day");
  }

  private JsonObject byCategory(UUID business) {
    return report(business, "/admin/reports/sales/by-category");
  }

  private static String labourPath() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    return "/admin/reports/sales/labour?from=" + today + "&to=" + today.plusDays(1);
  }

  private static JsonObject category(JsonObject data, UUID categoryId) {
    return rows(data).stream()
        .filter(
            r ->
                r.containsKey("categoryId")
                    && !r.isNull("categoryId")
                    && categoryId.toString().equals(r.getString("categoryId")))
        .findFirst()
        .orElse(null);
  }

  // ── the database, read directly: the row is marked, never removed ────────

  private record Fact(OffsetDateTime voidedAt, int lines) {}

  /** The sale's fact as stored, or null when there is none. */
  private static Fact fact(UUID business, UUID order) throws SQLException {
    DataSource ds = PG.dataSource();
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT f.voided_at,"
                    + " (SELECT COUNT(*) FROM reporting.sales_line_facts l"
                    + "   WHERE l.tenant_id = f.tenant_id AND l.order_id = f.order_id) AS lines"
                    + " FROM reporting.sales_facts f WHERE f.tenant_id = ? AND f.order_id = ?")) {
      ps.setObject(1, business);
      ps.setObject(2, order);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? new Fact(rs.getObject("voided_at", OffsetDateTime.class), rs.getInt("lines"))
            : null;
      }
    }
  }

  /** How many voids are held for a business's order. */
  private static int voidsHeld(UUID business, UUID order) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM reporting.sales_voids WHERE tenant_id = ? AND order_id = ?")) {
      ps.setObject(1, business);
      ps.setObject(2, order);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** How many voids are held for a business, whatever the order. */
  private static int voidsHeld(UUID business) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT COUNT(*) FROM reporting.sales_voids WHERE tenant_id = ?")) {
      ps.setObject(1, business);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  // ── the point of the change ──────────────────────────────────────────────

  @Test
  @DisplayName(
      "A voided till sale leaves the summary, the days, the categories and labour; its row stays,"
          + " marked")
  void aVoidedSaleLeavesEverySalesReport() throws SQLException {
    UUID tillSale = Ids.newId();
    UUID webOrder = Ids.newId();
    sale(tenant, tillSale, "POS", "40.00", line(cola, "20", "40.00"));
    sale(tenant, webOrder, "ONLINE", "60.00", line(crisps, "40", "60.00"));
    // A part-refund on the till sale before it was voided: it leaves with the sale, or the net
    // would go negative by money the report no longer counts as taken.
    reporting.applySalesRefund(Ids.newId(), CONSUMER, tenant, tillSale, new BigDecimal("10.00"));

    JsonObject before = only(summary(tenant));
    assertThat(before.getInt("orders"), is(2));
    assertThat(money(before, "gross"), is(new BigDecimal("100.00")));
    assertThat(money(before, "refunded"), is(new BigDecimal("10.00")));

    voided(Ids.newId(), tenant, tillSale);

    JsonObject after = only(summary(tenant));
    assertThat("the voided sale is not an order taken", after.getInt("orders"), is(1));
    assertThat(money(after, "gross"), is(new BigDecimal("60.00")));
    assertThat(money(after, "refunded"), is(new BigDecimal("0.00")));
    assertThat(money(after, "net"), is(new BigDecimal("60.00")));

    JsonObject day = only(byDay(tenant));
    assertThat(day.getInt("orders"), is(1));
    assertThat(money(day, "gross"), is(new BigDecimal("60.00")));
    assertThat(money(day, "net"), is(new BigDecimal("60.00")));

    JsonObject categories = byCategory(tenant);
    assertThat(
        "the voided sale's lines leave the category report",
        category(categories, drinks),
        is(nullValue()));
    assertThat(money(category(categories, snacks), "gross"), is(new BigDecimal("60.00")));
    assertThat(money(category(categories, snacks), "share"), is(new BigDecimal("100.00")));

    JsonObject labour = only(report(tenant, labourPath()));
    assertThat(money(labour, "gross"), is(new BigDecimal("60.00")));
    assertThat(money(labour, "net"), is(new BigDecimal("60.00")));

    Fact kept = fact(tenant, tillSale);
    assertThat("the fact is marked, never deleted", kept, is(notNullValue()));
    assertThat(kept.voidedAt(), is(notNullValue()));
    assertThat("its lines stay too", kept.lines(), is(1));
    assertThat("the web order stands", fact(tenant, webOrder).voidedAt(), is(nullValue()));
  }

  @Test
  @DisplayName("A redelivered void changes nothing, and a second word on the same sale neither")
  void aRedeliveredVoidChangesNothing() throws SQLException {
    UUID tillSale = Ids.newId();
    UUID other = Ids.newId();
    sale(tenant, tillSale, "POS", "40.00", line(cola, "20", "40.00"));
    sale(tenant, other, "POS", "15.00", line(crisps, "10", "15.00"));
    UUID event = Ids.newId();
    voided(event, tenant, tillSale);
    OffsetDateTime first = fact(tenant, tillSale).voidedAt();

    voided(event, tenant, tillSale);
    voided(Ids.newId(), tenant, tillSale);

    assertThat("the moment first heard stands", fact(tenant, tillSale).voidedAt(), is(first));
    assertThat(voidsHeld(tenant, tillSale), is(1));
    JsonObject after = only(summary(tenant));
    assertThat(after.getInt("orders"), is(1));
    assertThat(money(after, "gross"), is(new BigDecimal("15.00")));
    assertThat("the other sale is untouched", fact(tenant, other).voidedAt(), is(nullValue()));

    // A redelivered OrderConfirmed for the voided sale does not bring it back.
    sale(tenant, tillSale, "POS", "40.00", line(cola, "20", "40.00"));
    assertThat(money(only(summary(tenant)), "gross"), is(new BigDecimal("15.00")));
    assertThat(fact(tenant, tillSale).voidedAt(), is(first));
  }

  @Test
  @DisplayName("A void heard before its sale still voids the sale when the sale lands")
  void aVoidHeardBeforeItsSaleStillVoidsIt() throws SQLException {
    UUID tillSale = Ids.newId();
    // A consumer catching up reads its topics in no particular order.
    voided(Ids.newId(), tenant, tillSale);
    assertThat("nothing to mark yet", fact(tenant, tillSale), is(nullValue()));

    sale(tenant, tillSale, "POS", "40.00", line(cola, "20", "40.00"));

    assertThat(fact(tenant, tillSale).voidedAt(), is(notNullValue()));
    assertThat(rows(summary(tenant)).size(), is(0));
    assertThat(rows(byDay(tenant)).size(), is(0));
    assertThat(rows(byCategory(tenant)).size(), is(0));
    assertThat(rows(report(tenant, labourPath())).size(), is(0));
  }

  @Test
  @DisplayName(
      "Another business's void naming our order id changes nothing of ours, and its staff of every"
          + " role, naming our store, see none of our sales")
  void anotherBusinessCannotVoidOrSeeOurSales() throws SQLException {
    UUID ours = Ids.newId();
    sale(tenant, ours, "POS", "40.00", line(cola, "20", "40.00"));

    // The other business's OrderVoided, for our order's id.
    voided(Ids.newId(), otherTenant, ours);

    assertThat("our sale stands", fact(tenant, ours).voidedAt(), is(nullValue()));
    assertThat(fact(tenant, ours).lines(), is(1));
    assertThat("no void was written under our business", voidsHeld(tenant), is(0));
    assertThat("no sale was written under theirs", fact(otherTenant, ours), is(nullValue()));
    JsonObject ourSummary = only(summary(tenant));
    assertThat(ourSummary.getInt("orders"), is(1));
    assertThat(money(ourSummary, "gross"), is(new BigDecimal("40.00")));
    assertThat(money(only(byDay(tenant)), "gross"), is(new BigDecimal("40.00")));
    assertThat(money(category(byCategory(tenant), drinks), "gross"), is(new BigDecimal("40.00")));
    assertThat(money(only(report(tenant, labourPath())), "gross"), is(new BigDecimal("40.00")));

    // Their staff, of every role, whose store scope names our store and who ask for it by id.
    String scoped = "?storeId=" + store;
    List<String> reads =
        List.of(
            "/admin/reports/sales/summary" + scoped,
            "/admin/reports/sales/by-day" + scoped,
            "/admin/reports/sales/by-category" + scoped,
            labourPath() + "&storeId=" + store);
    for (String role : STAFF) {
      for (String read : reads) {
        try (Response r = get(otherTenant, role, store, read)) {
          String body = r.readEntity(String.class);
          if ("OWNER".equals(role) || "MANAGER".equals(role)) {
            assertThat(role + " " + read + ": " + body, r.getStatus(), is(200));
            JsonObject data =
                Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
            assertThat(role + " " + read + " sees nothing of ours", rows(data).size(), is(0));
          } else {
            // The sales reports are management's; a till or stockroom role is refused by role,
            // whichever business asks, before anything is read.
            assertThat(role + " " + read + ": " + body, r.getStatus(), is(403));
          }
          assertThat(role + " " + read, body.contains("40.00"), is(false));
          assertThat(role + " " + read, body.contains(ours.toString()), is(false));
        }
      }
    }

    // And their void, redelivered, still leaves ours alone.
    voided(Ids.newId(), otherTenant, ours);
    assertThat(fact(tenant, ours).voidedAt(), is(nullValue()));
    assertThat(money(only(summary(tenant)), "gross"), is(new BigDecimal("40.00")));
    assertThat(rows(summary(otherTenant)).size(), is(0));
    assertThat(voidsHeld(otherTenant, ours), is(1));
  }
}
