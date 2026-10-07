package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decided store-scoping rule for every report (SJ-D74), proved on the sales summary and one
 * inventory report (cross-store on-hand): a store that is <b>named</b> is checked against the
 * caller's own assignment ({@code 403 STORE_ACCESS_DENIED} otherwise); naming <b>none</b> reads the
 * caller's own stores added together for a store-restricted caller, or the whole business for one
 * held to none. Kafka is off in-test, so the projections are fed as the dispatchers would feed
 * them.
 */
@HelidonTest
class ReportStoreScopeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("reporting");

  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  @Inject WebTarget target;
  @Inject ReportingService reporting;

  // One business per test: Helidon keeps one test instance for the class and the tests share a
  // database, so each seeds its own tenant, stores and other tenant.
  private UUID tenant;
  private UUID otherTenant;
  private UUID storeA;
  private UUID storeB;
  private UUID storeC;

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    otherTenant = Ids.newId();
    storeA = Ids.newId();
    storeB = Ids.newId();
    storeC = Ids.newId();
  }

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── feeding the projections, as the dispatchers would feed them ──────────

  private void sale(UUID business, UUID store, String gross) {
    reporting.recordSale(business, Ids.newId(), store, "POS", null, new BigDecimal(gross), "GBP");
  }

  private void onHand(UUID business, UUID store, String qty) {
    reporting.applyStockDeltaOnce(
        Ids.newId(), "test", business, store, Ids.newId(), new BigDecimal(qty), "StockReceived");
  }

  private void seedThreeStores() {
    sale(tenant, storeA, "10.00");
    sale(tenant, storeB, "20.00");
    sale(tenant, storeC, "40.00");
    onHand(tenant, storeA, "1");
    onHand(tenant, storeB, "2");
    onHand(tenant, storeC, "4");
  }

  // ── reading over HTTP, with a caller's store assignment as the gateway would forward it ──

  private Response get(UUID business, String role, String storeIdsHeader, String pathAndQuery) {
    var request =
        WebTargets.at(target, pathAndQuery)
            .request()
            .header("X-Tenant-Id", business.toString())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role);
    if (storeIdsHeader != null) {
      request = request.header("X-Store-Ids", storeIdsHeader);
    }
    return request.get();
  }

  private static List<JsonObject> rows(String body) {
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getJsonArray("rows")
        .getValuesAs(JsonObject.class);
  }

  private static BigDecimal money(JsonObject row, String field) {
    return row.getJsonNumber(field).bigDecimalValue();
  }

  private static BigDecimal sum(List<JsonObject> rows, String field) {
    return rows.stream().map(r -> money(r, field)).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  // ── the sales summary ──────────────────────────────────────────────────

  @Test
  @DisplayName(
      "The sales summary: a manager held to one store sees it, to several sees them added"
          + " together, and naming a store outside the assignment is refused")
  void salesSummaryStoreScope() {
    seedThreeStores();
    String path = "/admin/reports/sales/summary";

    // Held to A, naming none: A alone.
    try (Response r = get(tenant, "MANAGER", storeA.toString(), path)) {
      assertThat(r.getStatus(), is(200));
      List<JsonObject> rows = rows(r.readEntity(String.class));
      assertThat(rows.size(), is(1));
      assertThat(sum(rows, "gross"), is(new BigDecimal("10.00")));
    }

    // Held to A, naming B: refused.
    try (Response r = get(tenant, "MANAGER", storeA.toString(), path + "?storeId=" + storeB)) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(403));
      assertThat(body.contains("STORE_ACCESS_DENIED"), is(true));
    }

    // Held to A and B, naming none: A and B added together, never C.
    try (Response r = get(tenant, "MANAGER", storeA + "," + storeB, path)) {
      assertThat(r.getStatus(), is(200));
      List<JsonObject> rows = rows(r.readEntity(String.class));
      assertThat(sum(rows, "gross"), is(new BigDecimal("30.00")));
    }

    // An owner, held to none: the whole business.
    try (Response r = get(tenant, "OWNER", null, path)) {
      assertThat(r.getStatus(), is(200));
      List<JsonObject> rows = rows(r.readEntity(String.class));
      assertThat(sum(rows, "gross"), is(new BigDecimal("70.00")));
    }
  }

  // ── the cross-store on-hand report ────────────────────────────────────

  @Test
  @DisplayName(
      "Cross-store on-hand: the same store-scoping rule as the sales summary, this time on the"
          + " inventory reports")
  void onHandStoreScope() {
    seedThreeStores();
    String path = "/admin/reports/inventory/on-hand";

    // Held to A, naming none: A alone.
    try (Response r = get(tenant, "MANAGER", storeA.toString(), path)) {
      assertThat(r.getStatus(), is(200));
      List<JsonObject> rows = rows(r.readEntity(String.class));
      assertThat(rows.size(), is(1));
      assertThat(sum(rows, "onHand"), comparesEqualTo(new BigDecimal("1")));
    }

    // Held to A, naming B: refused.
    try (Response r = get(tenant, "MANAGER", storeA.toString(), path + "?storeId=" + storeB)) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(403));
      assertThat(body.contains("STORE_ACCESS_DENIED"), is(true));
    }

    // Held to A and B, naming none: A and B added together, never C.
    try (Response r = get(tenant, "MANAGER", storeA + "," + storeB, path)) {
      assertThat(r.getStatus(), is(200));
      List<JsonObject> rows = rows(r.readEntity(String.class));
      assertThat(rows.size(), is(2));
      assertThat(sum(rows, "onHand"), comparesEqualTo(new BigDecimal("3")));
    }

    // An owner, held to none: the whole business.
    try (Response r = get(tenant, "OWNER", null, path)) {
      assertThat(r.getStatus(), is(200));
      List<JsonObject> rows = rows(r.readEntity(String.class));
      assertThat(rows.size(), is(3));
      assertThat(sum(rows, "onHand"), comparesEqualTo(new BigDecimal("7")));
    }
  }

  // ── tenant isolation, every staff role, naming our own store id ──────────

  @Test
  @DisplayName(
      "Another business's staff of every role, naming our store id, see none of our figures on"
          + " either report — a refused tier answers 403, an admitted one answers empty")
  void tenantIsolationAcrossEveryStaffRole() {
    seedThreeStores();
    List<String> reads =
        List.of(
            "/admin/reports/sales/summary?storeId=" + storeA,
            "/admin/reports/inventory/on-hand?storeId=" + storeA);

    for (String role : STAFF) {
      for (String read : reads) {
        try (Response r = get(otherTenant, role, storeA.toString(), read)) {
          String body = r.readEntity(String.class);
          if ("OWNER".equals(role) || "MANAGER".equals(role)) {
            // Their own assignment names our store id; TenantContext trusts the header (the
            // gateway is the one that would have refused to mint it), but the query is still
            // scoped to their own tenant, which holds none of our facts.
            assertThat(role + " " + read + ": " + body, r.getStatus(), is(200));
            assertThat(role + " " + read, rows(body).size(), is(0));
          } else {
            // STOREKEEPER/CASHIER never reach the reports at all — management-only, refused
            // before the store scope is even resolved.
            assertThat(role + " " + read + ": " + body, r.getStatus(), is(403));
          }
          assertThat(role + " " + read, body.contains("10.00"), is(false));
          assertThat(role + " " + read, body.contains("40.00"), is(false));
        }
      }
    }
  }
}
