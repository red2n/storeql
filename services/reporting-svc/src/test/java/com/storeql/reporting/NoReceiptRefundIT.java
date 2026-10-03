package com.storeql.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
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
 * A return made without a receipt has no sale for {@code PaymentRefunded} to name, so order-svc's
 * {@code NoReceiptReturnRecorded} is what lowers the sales reports: the refund lands on its day at
 * its store, once per event, never counted as an order, and never in another store's or business's
 * figures. Kafka is off in-test, so the projection is fed as the dispatcher feeds it.
 */
@HelidonTest
class NoReceiptRefundIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("reporting");
  private static final String CONSUMER = "reporting-svc/sales-events";

  @Inject WebTarget target;
  @Inject ReportingService reporting;

  private UUID tenant;
  private UUID otherTenant;
  private UUID storeA;
  private UUID storeB;

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    otherTenant = Ids.newId();
    storeA = Ids.newId();
    storeB = Ids.newId();
  }

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private void sale(UUID business, UUID store, String gross) {
    reporting.recordSale(
        business, Ids.newId(), store, "POS", null, new BigDecimal(gross), "GBP", List.of());
  }

  private void noReceipt(UUID eventId, UUID business, UUID ret, UUID store, String amount) {
    reporting.applyNoReceiptRefund(
        eventId, CONSUMER, business, ret, store, new BigDecimal(amount), "GBP");
  }

  private JsonObject report(UUID business, String pathAndQuery) {
    try (Response r =
        WebTargets.at(target, pathAndQuery)
            .request()
            .header("X-Tenant-Id", business.toString())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", "OWNER")
            .get()) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(200));
      return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
    }
  }

  private static JsonObject only(JsonObject data) {
    List<JsonObject> rows = data.getJsonArray("rows").getValuesAs(JsonObject.class);
    assertThat(rows.toString(), rows.size(), is(1));
    return rows.get(0);
  }

  private static void money(JsonObject row, String field, String expected) {
    assertThat(
        field + " in " + row,
        row.getJsonNumber(field).bigDecimalValue().compareTo(new BigDecimal(expected)),
        is(0));
  }

  @Test
  @DisplayName("A no-receipt refund lowers its store's net in the summary and by day, once")
  void aNoReceiptRefundLowersTheStoresNetOnce() {
    sale(tenant, storeA, "100.00");
    sale(tenant, storeB, "50.00");
    UUID event = Ids.newId();
    UUID ret = Ids.newId();
    noReceipt(event, tenant, ret, storeA, "12.50");
    noReceipt(event, tenant, ret, storeA, "12.50"); // redelivered
    noReceipt(Ids.newId(), tenant, ret, storeA, "12.50"); // the same return, announced again

    JsonObject a = only(report(tenant, "/admin/reports/sales/summary?storeId=" + storeA));
    assertThat(a.getJsonNumber("orders").longValue(), is(1L));
    money(a, "gross", "100.00");
    money(a, "refunded", "12.50");
    money(a, "net", "87.50");

    JsonObject b = only(report(tenant, "/admin/reports/sales/summary?storeId=" + storeB));
    money(b, "refunded", "0");
    money(b, "net", "50.00");

    JsonObject all = only(report(tenant, "/admin/reports/sales/summary"));
    assertThat(all.getJsonNumber("orders").longValue(), is(2L));
    money(all, "net", "137.50");

    JsonObject day = only(report(tenant, "/admin/reports/sales/by-day?storeId=" + storeA));
    assertThat(day.getJsonNumber("orders").longValue(), is(1L));
    money(day, "refunded", "12.50");
    money(day, "net", "87.50");
  }

  @Test
  @DisplayName("Another business's no-receipt refund changes nothing of ours")
  void anotherBusinessesRefundIsNotOurs() {
    sale(tenant, storeA, "40.00");
    noReceipt(Ids.newId(), otherTenant, Ids.newId(), storeA, "9.00");

    JsonObject mine = only(report(tenant, "/admin/reports/sales/summary"));
    money(mine, "refunded", "0");
    money(mine, "net", "40.00");
    JsonObject theirs = only(report(otherTenant, "/admin/reports/sales/summary"));
    assertThat(theirs.getJsonNumber("orders").longValue(), is(0L));
    money(theirs, "net", "-9.00");
  }
}
