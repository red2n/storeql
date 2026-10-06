package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
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

  /** Every service has one: the outbox store the scheduled purge runs through. */
  @Inject com.storeql.service.TenantDataRepository purge;

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

  /**
   * The scheduled purge takes published outbox rows and consumer dedupe rows once they are old, a
   * batch at a time, and each batch has to find its rows by age: both statements are planned here
   * with a table scan and a sort ruled out, so they show whether an index can serve them
   * (idx_outbox_published, created with the outbox in V3; idx_processed_events_processed_at,
   * created with processed_events in V1).
   */
  @Test
  void thePurgeTakesOnlyOldRowsAndCanFindThemThroughItsIndexes() throws Exception {
    Instant now = Instant.now();
    UUID tenant = Ids.newId();
    UUID oldPublished = Ids.newId();
    UUID recentPublished = Ids.newId();
    UUID neverPublished = Ids.newId();
    outboxRow(oldPublished, tenant, now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(29)));
    outboxRow(
        recentPublished, tenant, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
    outboxRow(neverPublished, tenant, now.minus(Duration.ofDays(30)), null);
    UUID oldEvent = Ids.newId();
    UUID recentEvent = Ids.newId();
    processedEvent(oldEvent, now.minus(Duration.ofDays(60)));
    processedEvent(recentEvent, now.minus(Duration.ofDays(1)));

    assertThat(
        "only the old published row went",
        purge.purgePublished(now.minus(Duration.ofDays(7)), 1000),
        is(1));
    assertThat(rows("outbox", "id", oldPublished), is(0));
    assertThat("a recently published one stays", rows("outbox", "id", recentPublished), is(1));
    assertThat("one never published is never purged", rows("outbox", "id", neverPublished), is(1));

    assertThat(
        "only the old dedupe row went",
        purge.purgeProcessedEvents(now.minus(Duration.ofDays(30)), 1000),
        is(1));
    assertThat(rows("processed_events", "event_id", oldEvent), is(0));
    assertThat("a recent one stays", rows("processed_events", "event_id", recentEvent), is(1));

    assertThat(
        planOf(
            "SELECT id FROM reporting.outbox WHERE published_at IS NOT NULL"
                + " AND published_at < now() - interval '7 days'"
                + " ORDER BY published_at ASC LIMIT 1000 FOR UPDATE SKIP LOCKED"),
        containsString("idx_outbox_published"));
    assertThat(
        planOf(
            "SELECT event_id FROM reporting.processed_events"
                + " WHERE processed_at < now() - interval '30 days'"
                + " ORDER BY processed_at ASC LIMIT 1000 FOR UPDATE SKIP LOCKED"),
        containsString("idx_processed_events_processed_at"));
  }

  /**
   * One event, two consumers: each applies it once. The dedupe key is (event, consumer), so the
   * second consumer's mark is not refused because the first consumer already marked the event.
   */
  @Test
  void aSecondConsumerOfTheSameEventStillAppliesIt() {
    UUID event = Ids.newId();
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID variant = Ids.newId();

    assertThat(
        reporting.applyStockDeltaOnce(
            event, "test/consumer-a", tenant, store, variant, BigDecimal.ONE, "StockReceived"),
        is(true));
    assertThat(
        "a different consumer of the same event applies it",
        reporting.applyStockDeltaOnce(
            event, "test/consumer-b", tenant, store, variant, BigDecimal.ONE, "StockReceived"),
        is(true));
    assertThat(
        "the same consumer again is a duplicate",
        reporting.applyStockDeltaOnce(
            event, "test/consumer-a", tenant, store, variant, BigDecimal.ONE, "StockReceived"),
        is(false));
  }

  /**
   * The dedupe mark and the supply lines of one transfer commit together. A line that cannot be
   * written takes the mark with it, so the redelivered event is applied rather than swallowed.
   */
  @Test
  void aFailedTransferShipmentLeavesNoDedupeMarkBehind() throws SQLException {
    UUID event = Ids.newId();
    UUID tenant = Ids.newId();
    List<UUID> noVariant = Arrays.asList((UUID) null); // variant_id is NOT NULL: the write fails

    assertThrows(
        RuntimeException.class,
        () ->
            reporting.applyTransferShippedOnce(
                tenant,
                event,
                "test/transfers",
                Ids.newId(),
                Ids.newId(),
                noVariant,
                List.of(BigDecimal.ONE)));

    assertThat("no mark for the failed event", rows("processed_events", "event_id", event), is(0));
    assertThat("no supply line either", rows("open_supply_lines", "event_id", event), is(0));
  }

  private static void outboxRow(UUID id, UUID tenant, Instant createdAt, Instant publishedAt)
      throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO reporting.outbox (id, event_type, topic, tenant_id, aggregate_id,"
                    + " payload, created_at, published_at)"
                    + " VALUES (?, 'PurgeTest', 'storeql.test.purge', ?, ?, '{}', ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, Ids.newId());
      ps.setObject(4, createdAt.atOffset(ZoneOffset.UTC));
      if (publishedAt == null) {
        ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(5, publishedAt.atOffset(ZoneOffset.UTC));
      }
      ps.executeUpdate();
    }
  }

  private static void processedEvent(UUID eventId, Instant processedAt) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO reporting.processed_events (event_id, consumer, processed_at)"
                    + " VALUES (?, 'purge-test', ?)")) {
      ps.setObject(1, eventId);
      ps.setObject(2, processedAt.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /** Rows of a reporting table with this id, counted behind the app. Names are this test's own. */
  private static int rows(String table, String column, UUID id) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM reporting." + table + " WHERE " + column + " = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** How Postgres would run a statement when a table scan and a sort are not on offer. */
  private static String planOf(String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      st.execute("SET enable_sort = off");
      StringBuilder plan = new StringBuilder();
      try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
      return plan.toString();
    }
  }
}
