package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
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
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sales by category (19.x): what each category took, from sales recorded line by line and the
 * catalogue's own word on where each variant sits. Kafka is off in-test, so the projection is fed
 * as the dispatchers would feed it.
 */
@HelidonTest
class SalesByCategoryIT {

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

  // One business per test: the four tests share a database and a test instance (Helidon keeps one
  // per class), and each seeds the same shop.
  private UUID tenant;
  private UUID otherTenant;

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    otherTenant = Ids.newId();
  }

  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();
  private static final UUID DRINKS = Ids.newId();
  private static final UUID SOFT = Ids.newId();
  private static final UUID SNACKS = Ids.newId();
  private static final UUID COLA = Ids.newId();
  private static final UUID CRISPS = Ids.newId();
  private static final UUID BAGS = Ids.newId();
  private static final UUID COLA_CAN = Ids.newId();
  private static final UUID COLA_BOTTLE = Ids.newId();
  private static final UUID CRISPS_BAG = Ids.newId();
  private static final UUID BAG_FOR_LIFE = Ids.newId();
  private static final UUID NEVER_ANNOUNCED = Ids.newId();

  @Inject WebTarget target;
  @Inject ReportingService reporting;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response get(UUID business, String role, String query) {
    return WebTargets.at(target, "/admin/reports/sales/by-category" + query)
        .request()
        .header("X-Tenant-Id", business.toString())
        .header("X-Roles", role)
        .get();
  }

  private JsonObject report(UUID business, String query) {
    Response r = get(business, "OWNER", query);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static List<JsonObject> rows(JsonObject data) {
    return data.getJsonArray("rows").getValuesAs(JsonObject.class);
  }

  private static JsonObject row(JsonObject data, UUID category) {
    return rows(data).stream()
        .filter(
            r ->
                category == null
                    ? !r.containsKey("categoryId") || r.isNull("categoryId")
                    : r.containsKey("categoryId")
                        && !r.isNull("categoryId")
                        && category.toString().equals(r.getString("categoryId")))
        .findFirst()
        .orElse(null);
  }

  private static BigDecimal money(JsonObject row, String field) {
    return row.getJsonNumber(field).bigDecimalValue();
  }

  private static SaleLine line(UUID variant, String qty, String total) {
    return new SaleLine(variant, new BigDecimal(qty), null, new BigDecimal(total));
  }

  private void sale(
      UUID business, UUID order, UUID store, String channel, String gross, List<SaleLine> lines) {
    reporting.recordSale(
        business, order, store, channel, null, new BigDecimal(gross), "GBP", lines);
  }

  private void seed() {
    Instant t0 = Instant.parse("2026-09-23T09:00:00Z");
    // The catalogue: cola (a can announced with the product, a bottle created later) under Soft
    // drinks under Drinks; crisps under Snacks; a bag for life with no category at all.
    reporting.applyProductCategorised(tenant, COLA, List.of(SOFT, DRINKS), List.of(COLA_CAN), t0);
    reporting.applyVariantCreated(tenant, COLA_BOTTLE, COLA);
    reporting.applyProductCategorised(tenant, CRISPS, List.of(SNACKS), List.of(CRISPS_BAG), t0);
    reporting.applyProductCategorised(tenant, BAGS, List.of(), List.of(BAG_FOR_LIFE), t0);
    // Three sales: a till basket at A, a web order at B with a variant nobody announced, and a
    // bag at A.
    sale(
        tenant,
        Ids.newId(),
        STORE_A,
        "POS",
        "5.50",
        List.of(line(COLA_CAN, "2", "4.00"), line(CRISPS_BAG, "1", "1.50")));
    sale(
        tenant,
        Ids.newId(),
        STORE_B,
        "ONLINE",
        "15.00",
        List.of(line(COLA_BOTTLE, "3", "6.00"), line(NEVER_ANNOUNCED, "1", "9.00")));
    sale(tenant, Ids.newId(), STORE_A, "POS", "2.00", List.of(line(BAG_FOR_LIFE, "1", "2.00")));
    // Another business sold cola too; it is nobody else's cola.
    reporting.applyProductCategorised(
        otherTenant, COLA, List.of(SOFT, DRINKS), List.of(COLA_CAN), t0);
    sale(otherTenant, Ids.newId(), STORE_A, "POS", "40.00", List.of(line(COLA_CAN, "20", "40.00")));
  }

  @Test
  @DisplayName(
      "Each category's takings, by the leaf category or rolled up to the top, with its share")
  void byLeafAndByTop() {
    seed();
    JsonObject leaf = report(tenant, "");
    assertThat(leaf.getString("level"), is("leaf"));
    JsonObject soft = row(leaf, SOFT);
    assertThat("soft drinks: the can and the bottle, two orders", soft.getInt("orders"), is(2));
    assertThat(money(soft, "units"), is(new BigDecimal("5.000")));
    assertThat(money(soft, "gross"), is(new BigDecimal("10.00")));
    assertThat(money(row(leaf, SNACKS), "gross"), is(new BigDecimal("1.50")));
    JsonObject none = row(leaf, null);
    assertThat(
        "uncategorised: the bag with no category and the variant nobody announced",
        money(none, "gross"),
        is(new BigDecimal("11.00")));
    assertThat(none.getInt("orders"), is(2));
    // Shares of the currency's total (22.50): 44.44 + 6.67 + 48.89.
    assertThat(money(soft, "share"), is(new BigDecimal("44.44")));
    assertThat(money(row(leaf, SNACKS), "share"), is(new BigDecimal("6.67")));
    assertThat(money(none, "share"), is(new BigDecimal("48.89")));
    assertThat(
        "largest first",
        rows(leaf).get(0).getJsonNumber("gross").bigDecimalValue(),
        is(new BigDecimal("11.00")));
    assertThat(row(leaf, DRINKS), is(nullValue()));

    JsonObject top = report(tenant, "?level=top");
    assertThat(top.getString("level"), is("top"));
    assertThat(
        "soft drinks roll up to drinks",
        money(row(top, DRINKS), "gross"),
        is(new BigDecimal("10.00")));
    assertThat(row(top, SOFT), is(nullValue()));
    assertThat(
        "snacks is its own top", money(row(top, SNACKS), "gross"), is(new BigDecimal("1.50")));
    assertThat(money(row(top, null), "gross"), is(new BigDecimal("11.00")));
  }

  @Test
  @DisplayName("Filtered by store, channel and dates; a wrong level is refused")
  void filters() {
    seed();
    JsonObject atA = report(tenant, "?storeId=" + STORE_A);
    assertThat(money(row(atA, SOFT), "gross"), is(new BigDecimal("4.00")));
    assertThat(money(row(atA, SNACKS), "gross"), is(new BigDecimal("1.50")));
    assertThat(money(row(atA, null), "gross"), is(new BigDecimal("2.00")));

    JsonObject online = report(tenant, "?channel=ONLINE");
    assertThat(money(row(online, SOFT), "gross"), is(new BigDecimal("6.00")));
    assertThat(row(online, SNACKS), is(nullValue()));
    assertThat(money(row(online, null), "gross"), is(new BigDecimal("9.00")));

    LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    assertThat(rows(report(tenant, "?from=" + tomorrow)).size(), is(0));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    assertThat(rows(report(tenant, "?from=" + today + "&to=" + today)).size() > 0, is(true));

    try (Response bad = get(tenant, "OWNER", "?level=sideways")) {
      String body = bad.readEntity(String.class);
      assertThat(body, bad.getStatus(), is(400));
      assertThat(body, containsString("REPORT_LEVEL_INVALID"));
    }
    try (Response notDate = get(tenant, "OWNER", "?from=yesterday")) {
      assertThat(notDate.getStatus(), is(400));
    }
  }

  @Test
  @DisplayName(
      "A redelivered sale counts once; a category move re-announced moves the takings, and an older announcement cannot undo it")
  void idempotentAndOrdered() {
    seed();
    UUID order = Ids.newId();
    sale(tenant, order, STORE_A, "POS", "4.00", List.of(line(COLA_CAN, "2", "4.00")));
    sale(tenant, order, STORE_A, "POS", "4.00", List.of(line(COLA_CAN, "2", "4.00")));
    assertThat(
        "one order, delivered twice, counted once",
        money(row(report(tenant, ""), SOFT), "gross"),
        is(new BigDecimal("14.00")));

    // Crisps move under Drinks, announced later than the first word; then the first word is
    // redelivered late and must not win.
    reporting.applyProductCategorised(
        tenant,
        CRISPS,
        List.of(DRINKS),
        List.of(CRISPS_BAG),
        Instant.parse("2026-09-23T12:00:00Z"));
    JsonObject moved = report(tenant, "");
    assertThat(row(moved, SNACKS), is(nullValue()));
    assertThat(money(row(moved, DRINKS), "gross"), is(new BigDecimal("1.50")));
    reporting.applyProductCategorised(
        tenant,
        CRISPS,
        List.of(SNACKS),
        List.of(CRISPS_BAG),
        Instant.parse("2026-09-23T09:00:00Z"));
    JsonObject still = report(tenant, "");
    assertThat("the stale announcement changed nothing", row(still, SNACKS), is(nullValue()));
    assertThat(money(row(still, DRINKS), "gross"), is(new BigDecimal("1.50")));
  }

  @Test
  @DisplayName("A business sees only its own categories, and a storekeeper none of the report")
  void isolationAndRoles() {
    seed();
    JsonObject theirs = report(otherTenant, "");
    assertThat(money(row(theirs, SOFT), "gross"), is(new BigDecimal("40.00")));
    assertThat(row(theirs, SNACKS), is(nullValue()));
    assertThat(row(theirs, null), is(nullValue()));
    try (Response keeper = get(tenant, "STOREKEEPER", "")) {
      assertThat(keeper.getStatus(), is(403));
    }
    JsonObject fresh = report(Ids.newId(), "");
    assertThat(rows(fresh).size(), is(0));
    assertThat(fresh.get("rows").getValueType(), is(JsonValue.ValueType.ARRAY));
  }
}
