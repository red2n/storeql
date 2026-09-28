package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.purchase.messaging.DutyEventHandler;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bonded and duty-suspended stock, the buyer's side: a purchase order can be placed under bond, so
 * its receipt tells inventory-svc the goods arrive with the duty suspended; and when inventory-svc
 * releases goods to home use, the duty it computed is owed to the revenue — once. Written before
 * the code.
 */
@HelidonTest
class DutyIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB");
  }

  private static final String T = PurchaseFixtures.T;
  private static final String T2 = PurchaseFixtures.T2;
  private static final String STORE = PurchaseFixtures.STORE_A;
  private static final String VARIANT = PurchaseFixtures.VARIANT;
  private static final String USER = PurchaseFixtures.USER;

  @Inject WebTarget target;
  @Inject DutyEventHandler handler;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  private Response call(String method, String path, String json, String tenant, String roles) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles);
    return "GET".equals(method) ? b.get() : b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "OWNER");
  }

  private Response get(String path) {
    return call("GET", path, null, T, "OWNER");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private static JsonObject trialBalanceRow(JsonObject tb, String code) {
    for (JsonValue v : tb.getJsonArray("rows")) {
      if (code.equals(v.asJsonObject().getString("nominalCode"))) return v.asJsonObject();
    }
    return null;
  }

  private static String released(String eventId, String tenant, String qty, String duty) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"DutyReleased\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + Ids.newId()
        + "\",\"occurredAt\":\"2026-09-24T10:00:00Z\",\"releaseId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + STORE
        + "\",\"variantId\":\""
        + VARIANT
        + "\",\"qty\":"
        + qty
        + ",\"dutyPerUnit\":2.50,\"dutyAmount\":"
        + duty
        + ",\"currency\":\"GBP\",\"reference\":\"W5 Sep\"}";
  }

  @Test
  void anOrderUnderBondArrivesWithTheDutySuspended() {
    String supplierId =
        Envelopes.created(
                post(
                    "/suppliers",
                    "{\"name\":\"Distillers Ltd\",\"vatRegistered\":true,\"currency\":\"GBP\"}"))
            .getString("id");
    JsonObject po =
        Envelopes.created(
            post(
                "/purchase-orders",
                "{\"supplierId\":\""
                    + supplierId
                    + "\",\"storeId\":\""
                    + STORE
                    + "\",\"currency\":\"GBP\",\"dutyStatus\":\"duty_suspended\"}"));
    assertThat(po.getString("dutyStatus"), is("DUTY_SUSPENDED"));
    assertThat(
        Envelopes.created(post("/purchase-orders", PurchaseFixtures.orderJson(supplierId)))
            .getString("dutyStatus"),
        is("DUTY_PAID"));
    assertThat(
        code(
            post(
                "/purchase-orders",
                "{\"supplierId\":\""
                    + supplierId
                    + "\",\"storeId\":\""
                    + STORE
                    + "\",\"currency\":\"GBP\",\"dutyStatus\":\"DUTY_FREE\"}"),
            400),
        is("PURCHASE_DUTY_STATUS_INVALID"));

    String poId = po.getString("id");
    assertThat(
        post("/purchase-orders/" + poId + "/lines", PurchaseFixtures.lineJson(6, "20.00"))
            .getStatus(),
        is(201));
    assertThat(post("/purchase-orders/" + poId + "/submit", "{}").getStatus(), is(200));
    assertThat(post("/goods-receipts", PurchaseFixtures.receiptJson(poId, 6)).getStatus(), is(201));
    String payload =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM purchase.outbox WHERE event_type = 'GoodsReceived'");
    assertThat(payload, containsString("\"dutyStatus\":\"DUTY_SUSPENDED\""));
    // The goods are ours at cost without the duty: the stock posting is what it always was.
    JsonObject tb = Envelopes.ok(get("/nominal-ledger/trial-balance"));
    assertThat(
        trialBalanceRow(tb, "1001").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("120.00")));
  }

  @Test
  void aReleaseFromBondOwesTheDutyOnce() {
    String eventId = Ids.newId().toString();
    handler.dutyReleased(released(eventId, T, "4", "10.00"));
    handler.dutyReleased(released(eventId, T, "4", "10.00"));
    handler.dutyReleased(released(Ids.newId().toString(), T, "2", "5.00"));

    JsonObject tb = Envelopes.ok(get("/nominal-ledger/trial-balance"));
    assertThat(
        trialBalanceRow(tb, "5030").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("15.00")));
    assertThat(
        trialBalanceRow(tb, "2140").getJsonNumber("balance").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("-15.00")));

    String today = LocalDate.now().toString();
    JsonObject period = Envelopes.ok(get("/admin/duty/releases?from=2026-01-01&to=" + today));
    assertThat(period.getJsonArray("releases").size(), is(2));
    assertThat(
        period.getJsonNumber("totalDuty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("15.00")));
    assertThat(period.getString("currency"), is("GBP"));
    // Another business owes nothing of ours; a cashier reads no return.
    assertThat(
        Envelopes.ok(
                call("GET", "/admin/duty/releases?from=2026-01-01&to=" + today, null, T2, "OWNER"))
            .getJsonArray("releases")
            .size(),
        is(0));
    assertThat(
        call("GET", "/admin/duty/releases?from=2026-01-01&to=" + today, null, T, "CASHIER")
            .getStatus(),
        is(403));
  }
}
