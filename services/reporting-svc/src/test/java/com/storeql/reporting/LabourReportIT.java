package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Labour against sales, over HTTP and a real database.
 *
 * <p>Four things here need the database. The projection is <b>keyed on the time entry</b>, so the
 * same announcement twice is one row — at-least-once delivery is the rule. A <b>correction takes
 * its predecessor's figure back out</b>, because a report that counted a corrected day twice would
 * look right and be wrong. The report is a <b>full outer join</b>: a day with takings and no hours
 * is as real as a day with hours and no sales. And an <b>uncosted hour makes the cost unknown
 * rather than zero</b>, with the caveat carried beside it.
 */
@HelidonTest
class LabourReportIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "reporting");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final UUID T = Ids.newId();
  private static final UUID OTHER = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID SECOND_STORE = Ids.newId();

  @Inject WebTarget target;

  /** Kafka is off in a test, so the projection is driven as the consumer would drive it. */
  @Inject ReportingService reporting;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private JsonObject report(UUID tenant, LocalDate from, LocalDate to, UUID store) {
    var t =
        target.path("/admin/reports/sales/labour").queryParam("from", from).queryParam("to", to);
    if (store != null) t = t.queryParam("storeId", store.toString());
    Response r =
        t.request().header("X-Tenant-Id", tenant.toString()).header("X-Roles", "OWNER").get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static List<JsonObject> rows(JsonObject data) {
    return data.getJsonArray("rows").getValuesAs(JsonObject.class);
  }

  private static JsonObject day(JsonObject data, LocalDate day) {
    return rows(data).stream()
        .filter(r -> day.toString().equals(r.getString("day")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + day + " in " + data));
  }

  private void sale(UUID tenant, UUID store, BigDecimal gross) {
    reporting.recordSale(tenant, Ids.newId(), store, "POS", null, gross, "GBP");
  }

  private void hours(
      UUID tenant,
      UUID entry,
      UUID supersedes,
      UUID store,
      LocalDate day,
      long minutes,
      String cost) {
    reporting.recordLabour(
        tenant,
        entry,
        supersedes,
        store,
        day,
        minutes,
        cost == null ? null : new BigDecimal(cost),
        cost == null ? null : "GBP");
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A day's takings and the cost of the hours that earned them, side by side")
  void takingsAgainstLabour() {
    sale(T, STORE, new BigDecimal("400.00"));
    sale(T, STORE, new BigDecimal("600.00"));
    hours(T, Ids.newId(), null, STORE, today(), 480, "96.00");
    hours(T, Ids.newId(), null, STORE, today(), 240, "48.00");

    JsonObject row = day(report(T, today(), today().plusDays(1), null), today());
    assertThat(row.getJsonNumber("net").bigDecimalValue(), is(new BigDecimal("1000.00")));
    assertThat(row.getJsonNumber("hours").bigDecimalValue(), is(new BigDecimal("12.00")));
    assertThat(row.getJsonNumber("labourCost").bigDecimalValue(), is(new BigDecimal("144.00")));
    // 144 of 1000: the figure a shop is run on.
    assertThat(row.getJsonNumber("labourPercent").bigDecimalValue(), is(new BigDecimal("14.40")));
    assertThat(row.getJsonNumber("salesPerHour").bigDecimalValue(), is(new BigDecimal("83.33")));
  }

  @Test
  @DisplayName(
      "Dinar takings and cost keep their third place and yen are whole, through the database")
  void moneyIsAtTheCurrencysOwnUnits() {
    UUID dinars = Ids.newId();
    reporting.recordSale(dinars, Ids.newId(), STORE, "POS", null, new BigDecimal("10.125"), "KWD");
    reporting.recordLabour(
        dinars, Ids.newId(), null, STORE, today(), 180, new BigDecimal("3.125"), "KWD");
    JsonObject kwd = day(report(dinars, today(), today().plusDays(1), null), today());
    // A two-place column would have said 10.13 and 3.13.
    assertThat(kwd.getJsonNumber("net").bigDecimalValue(), is(new BigDecimal("10.125")));
    assertThat(kwd.getJsonNumber("labourCost").bigDecimalValue(), is(new BigDecimal("3.125")));
    assertThat(kwd.getJsonNumber("salesPerHour").bigDecimalValue(), is(new BigDecimal("3.375")));

    UUID yen = Ids.newId();
    reporting.recordSale(yen, Ids.newId(), STORE, "POS", null, new BigDecimal("10000"), "JPY");
    reporting.recordLabour(
        yen, Ids.newId(), null, STORE, today(), 180, new BigDecimal("3000"), "JPY");
    JsonObject jpy = day(report(yen, today(), today().plusDays(1), null), today());
    assertThat(jpy.getJsonNumber("net").bigDecimalValue(), is(new BigDecimal("10000")));
    assertThat(jpy.getJsonNumber("labourCost").bigDecimalValue(), is(new BigDecimal("3000")));
    assertThat(jpy.getJsonNumber("salesPerHour").bigDecimalValue(), is(new BigDecimal("3333")));
    assertThat(jpy.getJsonNumber("labourPercent").bigDecimalValue(), is(new BigDecimal("30.00")));
  }

  @Test
  @DisplayName("The same entry announced twice is one row, and a correction replaces its figure")
  void idempotentAndCorrectable() {
    UUID tenant = Ids.newId();
    UUID entry = Ids.newId();
    sale(tenant, STORE, new BigDecimal("500.00"));
    hours(tenant, entry, null, STORE, today(), 480, "96.00");
    hours(tenant, entry, null, STORE, today(), 480, "96.00");

    JsonObject once = day(report(tenant, today(), today().plusDays(1), null), today());
    assertThat(
        "at-least-once delivery is the rule",
        once.getJsonNumber("hours").bigDecimalValue(),
        is(new BigDecimal("8.00")));

    // A correction: the manager fixed a forgotten clock-out, so the day is nine hours, not
    // seventeen.
    hours(tenant, Ids.newId(), entry, STORE, today(), 540, "108.00");
    JsonObject corrected = day(report(tenant, today(), today().plusDays(1), null), today());
    assertThat(
        "a report that counted both would look right and be wrong",
        corrected.getJsonNumber("hours").bigDecimalValue(),
        is(new BigDecimal("9.00")));
    assertThat(
        corrected.getJsonNumber("labourCost").bigDecimalValue(), is(new BigDecimal("108.00")));
  }

  @Test
  @DisplayName("An hour nobody had a rate for makes the cost unknown, not zero")
  void uncostedHoursAreUnknown() {
    // A Saturday shown as free labour would be worse than one that says it does not know.
    UUID tenant = Ids.newId();
    sale(tenant, STORE, new BigDecimal("200.00"));
    hours(tenant, Ids.newId(), null, STORE, today(), 300, "50.00");
    hours(tenant, Ids.newId(), null, STORE, today(), 180, null);

    JsonObject row = day(report(tenant, today(), today().plusDays(1), null), today());
    assertThat(
        "the hours are all there",
        row.getJsonNumber("hours").bigDecimalValue(),
        is(new BigDecimal("8.00")));
    assertThat(row.getJsonNumber("uncostedHours").bigDecimalValue(), is(new BigDecimal("3.00")));
    assertThat(
        "and the caveat travels with the figure", row.toString(), containsString("uncostedHours"));
  }

  @Test
  @DisplayName("A day with hours and no sales, and a day with sales and no hours, both appear")
  void bothSidesOfTheJoin() {
    UUID tenant = Ids.newId();
    LocalDate stocktake = today().minusDays(1);
    hours(tenant, Ids.newId(), null, STORE, stocktake, 480, "96.00");
    sale(tenant, STORE, new BigDecimal("300.00"));

    JsonObject data = report(tenant, today().minusDays(2), today().plusDays(1), null);
    JsonObject closed = day(data, stocktake);
    assertThat(
        "a stocktake day: hours, no takings",
        closed.getJsonNumber("hours").bigDecimalValue(),
        is(new BigDecimal("8.00")));
    assertThat(closed.getJsonNumber("net").bigDecimalValue(), is(new BigDecimal("0.00")));
    assertThat(
        "a percentage of nothing is no percentage, not a large one",
        closed.containsKey("labourPercent") && !closed.isNull("labourPercent"),
        is(false));

    JsonObject trading = day(data, today());
    assertThat(trading.getJsonNumber("net").bigDecimalValue(), is(new BigDecimal("300.00")));
    assertThat(
        "no hours recorded that day",
        trading.getJsonNumber("hours").bigDecimalValue(),
        is(new BigDecimal("0.00")));
  }

  @Test
  @DisplayName("One store's hours are not another's")
  void perStore() {
    UUID tenant = Ids.newId();
    hours(tenant, Ids.newId(), null, STORE, today(), 480, "96.00");
    hours(tenant, Ids.newId(), null, SECOND_STORE, today(), 240, "48.00");

    JsonObject first = day(report(tenant, today(), today().plusDays(1), STORE), today());
    assertThat(first.getJsonNumber("hours").bigDecimalValue(), is(new BigDecimal("8.00")));
    JsonObject both = day(report(tenant, today(), today().plusDays(1), null), today());
    assertThat(both.getJsonNumber("hours").bigDecimalValue(), is(new BigDecimal("12.00")));
  }

  @Test
  @DisplayName("Another business's hours are not in this one's report")
  void tenantsAreSeparate() {
    UUID entry = Ids.newId();
    hours(OTHER, entry, null, STORE, today(), 480, "96.00");
    JsonObject data = report(T, today(), today().plusDays(1), null);
    assertThat(data.toString(), not(containsString(entry.toString())));
  }
}
