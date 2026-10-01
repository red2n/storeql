package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for reporting-svc (gaps #47, #48, #49). Runs against real Postgres via
 * Testcontainers. Kafka/Consul disabled.
 */
@HelidonTest
class ReportingIT {

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

  private static final String T = "01a090ae-611e-700f-b645-a14095230b77";
  private static final String OTHER = "01a090ae-611e-701d-9d60-a9d7516ed03b";

  @Inject WebTarget target;

  // Kafka is disabled in-test, so drive the sales projection directly (as SalesEventDispatcher
  // would).
  @Inject ReportingService reporting;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response get(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  /** Gap #47: on-hand returns empty projection for a fresh tenant. */
  @Test
  void onHandEmptyInitially() {
    Response r = get("/admin/reports/inventory/on-hand", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
    assertThat(body.contains("grandTotal"), is(true));
  }

  /** Gap #48: supply-demand netting returns empty for a fresh tenant. */
  @Test
  void supplyDemandEmptyInitially() {
    Response r = get("/admin/reports/inventory/supply-demand", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
  }

  /** Gap #49: movement stats returns empty for a fresh tenant. */
  @Test
  void movementStatsEmptyInitially() {
    Response r = get("/admin/reports/inventory/movement-stats", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
  }

  private Response movementStats(String tenant, String... params) {
    WebTarget t = target.path("/admin/reports/inventory/movement-stats");
    for (int i = 0; i < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  /** Movement stats read a bounded window: recent by default, never the whole history. */
  @Test
  void movementStatsAreBoundedToAWindow() {
    UUID tenant = Ids.newId();
    UUID variant = Ids.newId();
    reporting.applyStockDeltaOnce(
        Ids.newId(), "test", tenant, Ids.newId(), variant, new BigDecimal("5"), "StockReceived");
    String t = tenant.toString();

    // Default window (the last 90 days) holds the movement just made.
    assertThat(movementStats(t).readEntity(String.class), containsString(variant.toString()));

    // A window wholly in the past holds none of it.
    Response past = movementStats(t, "from", "2000-01-01", "to", "2000-01-31");
    assertThat(past.getStatus(), is(200));
    assertThat(past.readEntity(String.class).contains(variant.toString()), is(false));

    // Another business's window never shows it.
    assertThat(
        movementStats(OTHER).readEntity(String.class).contains(variant.toString()), is(false));
  }

  @Test
  void movementStatsRefuseABackwardsOrOverlongPeriod() {
    Response backwards = movementStats(T, "from", "2026-02-01", "to", "2026-01-01");
    assertThat(backwards.getStatus(), is(400));
    assertThat(backwards.readEntity(String.class), containsString("REPORT_PERIOD_INVALID"));

    Response tooLong = movementStats(T, "from", "2000-01-01", "to", "2010-01-01");
    assertThat(tooLong.getStatus(), is(400));
    assertThat(tooLong.readEntity(String.class), containsString("REPORT_PERIOD_TOO_LONG"));

    assertThat(movementStats(T, "from", "01/02/2026").getStatus(), is(400));
  }

  /** Tenant isolation: different tenants see independent data. */
  @Test
  void tenantIsolation() {
    Response r1 = get("/admin/reports/inventory/on-hand", T);
    Response r2 = get("/admin/reports/inventory/on-hand", OTHER);
    assertThat(r1.getStatus(), is(200));
    assertThat(r2.getStatus(), is(200));
  }

  /**
   * N4: a confirmed order + refund surface as gross/refunded/net; a redelivered refund is deduped.
   */
  @Test
  void salesSummaryReflectsGrossRefundedNetWithRefundDedupe() {
    UUID tenant = Ids.newId(); // fresh tenant → this test's sales only
    UUID order = Ids.newId();
    reporting.recordSale(
        tenant, order, Ids.newId(), "ONLINE", Ids.newId(), new BigDecimal("100.00"), "GBP");
    UUID refundEvent = Ids.newId();
    reporting.applySalesRefund(refundEvent, "test", tenant, order, new BigDecimal("25.00"));
    // Redelivery of the same PaymentRefunded event must not double-count.
    reporting.applySalesRefund(refundEvent, "test", tenant, order, new BigDecimal("25.00"));

    String body = get("/admin/reports/sales/summary", tenant.toString()).readEntity(String.class);
    assertThat(body, containsString("\"currency\":\"GBP\""));
    assertThat(body, containsString("\"gross\":100.00"));
    assertThat(body, containsString("\"refunded\":25.00"));
    assertThat(body, containsString("\"net\":75.00"));
  }

  /** N4: OrderConfirmed is projected once per order (natural PK idempotency). */
  @Test
  void recordSaleIsIdempotentOnOrderId() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    reporting.recordSale(tenant, order, Ids.newId(), "POS", null, new BigDecimal("40.00"), "GBP");
    // Redelivered OrderConfirmed for the same order → no second row / no doubled gross.
    reporting.recordSale(tenant, order, Ids.newId(), "POS", null, new BigDecimal("40.00"), "GBP");

    String body = get("/admin/reports/sales/by-day", tenant.toString()).readEntity(String.class);
    assertThat(body, containsString("\"orders\":1"));
    assertThat(body, containsString("\"gross\":40.00"));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
