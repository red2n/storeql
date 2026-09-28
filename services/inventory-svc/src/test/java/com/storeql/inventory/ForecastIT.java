package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statistical demand forecast (06.x) through the service: a run over a store's demand history,
 * the forecast read back, the reorder point that now takes its expected demand from it, and the
 * store scope and validation at the door.
 */
@HelidonTest
class ForecastIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String NO_WINDOWS = "{\"data\":[]}";

  /** pricing-svc, standing in: no promotion windows unless a test says otherwise. */
  private static final JsonStub PRICING =
      JsonStub.start("pricing-svc").on("GET", "/admin/promotions/windows", 200, NO_WINDOWS);

  private static final String KEEPER = "01a090ae-7f1e-7f05-bde4-50df0324c37c";

  @Inject WebTarget target;

  private UUID tenant;
  private UUID store;
  private UUID otherStore;
  private final UUID steady = Ids.newId();
  private final UUID occasional = Ids.newId();
  private final UUID monthlyOnly = Ids.newId();
  private final UUID yoghurt = Ids.newId();
  private final UUID crackers = Ids.newId();
  private final UUID cola = Ids.newId();

  @AfterAll
  static void stopDb() {
    PRICING.close();
    PG.stop();
  }

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    store = Ids.newId();
    otherStore = Ids.newId();
    PRICING.on("GET", "/admin/promotions/windows", 200, NO_WINDOWS);
  }

  /** A promotion window over one variant at the store, from {@code from} until {@code until}. */
  private String window(UUID variant, LocalDate from, LocalDate until) {
    return "{\"promotionId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + store
        + "\",\"name\":\"Week of it\",\"type\":\"PERCENT\",\"value\":20,\"channel\":\"ALL\","
        + "\"active\":true,\"startsAt\":\""
        + from
        + "T00:00:00Z\",\"endsAt\":\""
        + until
        + "T00:00:00Z\",\"variantIds\":[\""
        + variant
        + "\"],\"allVariants\":false}";
  }

  // ── the history a forecast reads ────────────────────────────────────────────

  private void bucket(UUID storeId, UUID variant, LocalDate day, String type, String qty)
      throws SQLException {
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO inventory.demand_history (id, tenant_id, store_id, variant_id,"
                    + " bucket_date, bucket_type, demand_qty, movement_count, computed_at)"
                    + " VALUES (?,?,?,?,?,?,?,1,now())")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenant);
      ps.setObject(3, storeId);
      ps.setObject(4, variant);
      ps.setObject(5, day);
      ps.setString(6, type);
      ps.setBigDecimal(7, new BigDecimal(qty));
      ps.executeUpdate();
    }
  }

  /**
   * A dated batch received on {@code received}, living {@code lifeDays}, with {@code left} unsold.
   */
  private void batch(UUID variant, LocalDate received, int lifeDays, String left)
      throws SQLException {
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO inventory.inventory_batches (id, tenant_id, store_id, variant_id, batch_no,"
                    + " received_qty, remaining_qty, expiry_date, created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenant);
      ps.setObject(3, store);
      ps.setObject(4, variant);
      ps.setString(5, "B-" + received);
      ps.setBigDecimal(6, new BigDecimal("40"));
      ps.setBigDecimal(7, new BigDecimal(left));
      ps.setObject(8, received.plusDays(lifeDays));
      ps.setObject(9, java.time.OffsetDateTime.of(received.atTime(6, 0), ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void seed() throws SQLException {
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    for (int i = 0; i < 60; i++) {
      LocalDate day = yesterday.minusDays(i);
      bucket(store, steady, day, "DAY", "4");
      if (i % 5 == 0) bucket(store, occasional, day, "DAY", "2");
    }
    // A variant with only a monthly bucket: the old path, still honoured.
    bucket(store, monthlyOnly, yesterday.withDayOfMonth(1), "MONTH", "300");
    // Another store's history is not this store's.
    bucket(otherStore, steady, yesterday, "DAY", "999");
  }

  // ── the door ────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String role, String stores) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant.toString())
            .header("X-User-Id", KEEPER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return stores == null ? b : b.header("X-Store-Ids", stores);
  }

  private Response run(String role, String stores, String body) {
    return as("/admin/inventory/forecasts/run", role, stores)
        .post(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private String runBody(UUID storeId, Integer horizon) {
    return "{\"storeId\":\""
        + storeId
        + "\""
        + (horizon == null ? "" : ",\"horizonDays\":" + horizon)
        + "}";
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static JsonArray dataArray(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonArray("data");
  }

  private static double num(JsonObject o, String field) {
    return o.getJsonNumber(field).doubleValue();
  }

  @Test
  @DisplayName(
      "A run forecasts every variant with history at the store, names its method, and reads back with its points")
  void runAndReadBack() throws SQLException {
    seed();
    JsonObject result = data(run("OWNER", null, runBody(store, 28)));
    assertThat(result.getInt("variants"), is(2));
    assertThat(result.getInt("horizonDays"), is(28));
    assertThat(result.getJsonObject("byMethod").getInt("SES"), is(1));
    assertThat(result.getJsonObject("byMethod").getInt("CROSTON_SBA"), is(1));

    JsonArray list =
        dataArray(as("/admin/inventory/forecasts?store=" + store, "OWNER", null).get());
    assertThat(list.size(), is(2));

    JsonObject f =
        data(as("/admin/inventory/forecasts/" + store + "/" + steady, "OWNER", null).get());
    assertThat(f.getString("method"), is("SES"));
    assertThat(f.getBoolean("intermittent"), is(false));
    assertThat(f.getInt("historyDays"), is(60));
    assertThat(f.getJsonArray("points").size(), is(28));
    assertThat(num(f, "next7"), closeTo(28.0, 0.5));
    assertThat(num(f, "mape"), closeTo(0.0, 0.01));
    assertThat(
        f.getJsonArray("points").getJsonObject(0).getString("day"),
        is(LocalDate.now(ZoneOffset.UTC).toString()));

    JsonObject g =
        data(as("/admin/inventory/forecasts/" + store + "/" + occasional, "OWNER", null).get());
    assertThat(g.getString("method"), is("CROSTON_SBA"));
    assertThat(g.getBoolean("intermittent"), is(true));
    assertThat(num(g, "next28"), greaterThan(5.0));

    try (Response none =
        as("/admin/inventory/forecasts/" + store + "/" + monthlyOnly, "OWNER", null).get()) {
      String body = none.readEntity(String.class);
      assertThat(body, none.getStatus(), is(404));
      assertThat(body, containsString("FORECAST_NOT_FOUND"));
    }
    // Run again: still one forecast per variant.
    data(run("OWNER", null, runBody(store, 28)));
    assertThat(
        dataArray(as("/admin/inventory/forecasts?store=" + store, "OWNER", null).get()).size(),
        is(2));
  }

  @Test
  @DisplayName(
      "The reorder point takes its expected demand from the forecast when there is one, and from the monthly average when there is not")
  void reorderPointUsesTheForecast() throws SQLException {
    seed();
    data(run("OWNER", null, runBody(store, 28)));
    for (UUID v : new UUID[] {steady, monthlyOnly}) {
      Response put =
          as("/admin/inventory/rop-plans", "OWNER", null)
              .put(
                  Entity.entity(
                      "{\"storeId\":\""
                          + store
                          + "\",\"variantId\":\""
                          + v
                          + "\",\"leadTimeDays\":7,\"orderingCost\":50.00,\"holdingCostPct\":0.20,\"unitCost\":10.00}",
                      MediaType.APPLICATION_JSON));
      assertThat(put.readEntity(String.class), put.getStatus(), is(200));
    }
    Response compute =
        as("/admin/inventory/rop-plans/compute?store=" + store, "OWNER", null)
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(compute.readEntity(String.class), compute.getStatus(), is(200));

    JsonObject forecasted =
        data(
            as(
                    "/admin/inventory/rop-plans/by-variant?store=" + store + "&variant=" + steady,
                    "OWNER",
                    null)
                .get());
    assertThat(
        "four a day from the forecast", num(forecasted, "avgDailyDemand"), closeTo(4.0, 0.05));
    assertThat("seven days of four", num(forecasted, "rop"), closeTo(28.0, 0.5));

    JsonObject monthly =
        data(
            as(
                    "/admin/inventory/rop-plans/by-variant?store="
                        + store
                        + "&variant="
                        + monthlyOnly,
                    "OWNER",
                    null)
                .get());
    assertThat(
        "300 a month is ten a day, as before", num(monthly, "avgDailyDemand"), closeTo(10.0, 0.01));
    assertThat(num(monthly, "rop"), closeTo(70.0, 0.01));
  }

  @Test
  @DisplayName(
      "A store-scoped keeper forecasts only their stores; a horizon is one day to a year; an empty store is an honest zero")
  void scopeValidationAndEmpty() throws SQLException {
    seed();
    try (Response other = run("STOREKEEPER", otherStore.toString(), runBody(store, 28))) {
      String body = other.readEntity(String.class);
      assertThat(body, other.getStatus(), is(403));
      assertThat(body, containsString("STORE_ACCESS_DENIED"));
    }
    try (Response read =
        as("/admin/inventory/forecasts?store=" + store, "STOREKEEPER", otherStore.toString())
            .get()) {
      assertThat(read.getStatus(), is(403));
    }
    try (Response byPath =
        as(
                "/admin/inventory/forecasts/" + store + "/" + steady,
                "STOREKEEPER",
                otherStore.toString())
            .get()) {
      assertThat(byPath.getStatus(), is(403));
    }
    assertThat(run("STOREKEEPER", store.toString(), runBody(store, 14)).getStatus(), is(200));
    try (Response zero = run("OWNER", null, runBody(store, 0))) {
      String body = zero.readEntity(String.class);
      assertThat(body, zero.getStatus(), is(400));
      assertThat(body, containsString("FORECAST_HORIZON_INVALID"));
    }
    assertThat(run("OWNER", null, runBody(store, 400)).getStatus(), is(400));
    assertThat(run("OWNER", null, "{\"storeId\":\"not-an-id\"}").getStatus(), is(400));
    JsonObject empty = data(run("OWNER", null, runBody(Ids.newId(), null)));
    assertThat(empty.getInt("variants"), is(0));
    assertThat(empty.getInt("horizonDays"), is(28));
  }

  @Test
  @DisplayName(
      "A fresh item is known by its batches: its shelf life, its waste, and the longest cover an order should get")
  void freshItemsAreKnownByTheirBatches() throws SQLException {
    seed();
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    // Six a day for ninety days; a five-day batch every five days, the two most recent ones already
    // past their date with three left unsold each (the newest still has three days to run): 540
    // sold, 6 wasted.
    for (int i = 0; i < 90; i++) {
      bucket(store, yoghurt, yesterday.minusDays(i), "DAY", "6");
    }
    for (int i = 0; i < 18; i++) {
      LocalDate received = yesterday.minusDays(2L + 5L * i);
      batch(yoghurt, received, 5, i == 1 || i == 2 ? "3" : "0");
    }
    JsonObject result = data(run("OWNER", null, runBody(store, 28)));
    assertThat(result.getInt("fresh"), is(1));

    JsonObject f =
        data(as("/admin/inventory/forecasts/" + store + "/" + yoghurt, "OWNER", null).get());
    assertThat(f.getBoolean("fresh"), is(true));
    assertThat(f.getInt("shelfLifeDays"), is(5));
    assertThat(f.getInt("maxCoverDays"), is(5));
    assertThat("6 wasted of 540 sold + 6 = 1.10%", num(f, "wasteRatePct"), closeTo(1.10, 0.01));
    assertThat(
        "a fresh item's level follows its last eight weeks", f.getInt("historyDays"), is(56));
    assertThat(num(f, "next7"), closeTo(42.0, 0.5));

    JsonObject keeps =
        data(as("/admin/inventory/forecasts/" + store + "/" + steady, "OWNER", null).get());
    assertThat(keeps.getBoolean("fresh"), is(false));
    assertThat(!keeps.containsKey("shelfLifeDays") || keeps.isNull("shelfLifeDays"), is(true));
  }

  @Test
  @DisplayName(
      "Thirteen months give the year its shape, and a promotion's days are lifted by what its last one sold")
  void promotionsAndSeasonsShapeTheForecast() throws SQLException {
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    // Crackers: fourteen months, twenty a day through December, ten a day otherwise.
    for (int i = 0; i < 420; i++) {
      LocalDate day = yesterday.minusDays(i);
      bucket(store, crackers, day, "DAY", day.getMonth() == Month.DECEMBER ? "20" : "10");
    }
    // Cola: four months at ten a day, twenty-five through the fortnight a promotion ran.
    for (int i = 0; i < 120; i++) {
      bucket(store, cola, yesterday.minusDays(i), "DAY", i >= 60 && i < 74 ? "25" : "10");
    }
    String ran = window(cola, yesterday.minusDays(73), yesterday.minusDays(59));
    String coming = window(cola, yesterday.plusDays(1), yesterday.plusDays(8));
    PRICING.on("GET", "/admin/promotions/windows", 200, "{\"data\":[" + ran + "," + coming + "]}");

    JsonObject result = data(run("OWNER", null, runBody(store, 28)));
    assertThat(result.toString(), result.getInt("variants"), is(2));
    assertThat(result.getInt("seasonal"), is(1));
    assertThat(result.getInt("promoted"), is(1));
    JsonStub.Call asked = PRICING.calls().get(PRICING.calls().size() - 1);
    assertThat(asked.tenantId(), is(tenant.toString()));
    assertThat(asked.query(), containsString("store=" + store));

    JsonObject c =
        data(as("/admin/inventory/forecasts/" + store + "/" + crackers, "OWNER", null).get());
    JsonArray indices = c.getJsonArray("seasonalIndices");
    assertThat(indices.size(), is(12));
    assertThat(
        indices.getJsonNumber(Month.DECEMBER.getValue() - 1).doubleValue(), greaterThan(1.6));
    assertThat(c.getInt("historyDays"), is(182));
    assertThat(!c.containsKey("uplift") || c.isNull("uplift"), is(true));
    for (JsonObject point : c.getJsonArray("points").getValuesAs(JsonObject.class)) {
      boolean december = LocalDate.parse(point.getString("day")).getMonth() == Month.DECEMBER;
      assertThat(point.toString(), num(point, "qty"), closeTo(december ? 20.0 : 10.0, 1.2));
    }

    JsonObject k =
        data(as("/admin/inventory/forecasts/" + store + "/" + cola, "OWNER", null).get());
    assertThat(k.getJsonArray("seasonalIndices").size(), is(0));
    assertThat(num(k, "uplift"), closeTo(2.5, 0.05));
    assertThat(k.getString("upliftSource"), is("ITEM"));
    assertThat(k.getInt("promotedHistoryDays"), is(14));
    assertThat(k.getInt("promotedAheadDays"), is(7));
    assertThat(num(k, "level"), closeTo(10.0, 0.3));
    assertThat(num(k, "next7"), closeTo(175.0, 6.0));
    assertThat(num(k, "next28"), closeTo(385.0, 12.0));
    assertThat(num(k, "mape"), closeTo(0.0, 1.0));
  }
}
