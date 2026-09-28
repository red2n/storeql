package com.storeql.order;

import static com.storeql.test.Envelopes.created;
import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.messaging.RetentionSweeper;
import com.storeql.order.service.OrderService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The retention purge of personal details on settled orders (21.16): everything settled before the
 * period the business set loses its name, address, phone and notes; an open order, a held order, a
 * held customer's order and a recent order keep theirs; a class hold stops the lot; the run is
 * announced with its counts; and no period means no purge. Kafka and Consul disabled; tenant-svc is
 * a stub serving each business's schedule.
 */
@HelidonTest
class RetentionPurgeIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String AT_ONCE = "01a090ae-611e-7070-8516-000000000001";
  private static final String HELD = "01a090ae-611e-7070-8516-000000000002";
  private static final String CLASS_HELD = "01a090ae-611e-7070-8516-000000000003";
  private static final String UNSET = "01a090ae-611e-7070-8516-000000000004";
  private static final String MONTH = "01a090ae-611e-7070-8516-000000000005";
  private static final String S = "01a090ae-611e-7070-8516-000000000011";
  private static final String V = "01a090ae-611e-7070-8516-000000000021";
  private static final String OWNER = "01a090ae-611e-7070-8516-000000000031";
  private static final UUID HELD_CUSTOMER = Ids.parse("01a090ae-611e-7070-8516-000000000041");

  static {
    PG = PostgresSupport.start().wire("order");
    TENANTS = TenantSvcStub.start();
    for (String t : List.of(AT_ONCE, HELD, CLASS_HELD, UNSET, MONTH)) {
      TENANTS.with(t, "GBP", "GB");
    }
    TENANTS
        .withRetention(AT_ONCE, Map.of("ORDER_PERSONAL_DATA", 0), List.of())
        .withRetention(
            CLASS_HELD,
            Map.of("ORDER_PERSONAL_DATA", 0),
            List.of("{\"subjectKind\":\"ALL\",\"dataClass\":\"ORDER_PERSONAL_DATA\"}"))
        .withRetention(UNSET, Map.of("TRANSACTIONS", 2190), List.of())
        .withRetention(MONTH, Map.of("ORDER_PERSONAL_DATA", 30), List.of());
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.retention-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService orderService;
  @Inject RetentionSweeper sweeper;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  @Test
  void settledOrdersLoseTheirPersonalDetailsAndOpenOnesKeepThem() {
    String settled = tillSale(AT_ONCE, null, true);
    String open = tillSale(AT_ONCE, null, false);
    JsonObject run = ok(sweep(AT_ONCE, "OWNER"));
    assertThat(run.getString("dataClass"), is("ORDER_PERSONAL_DATA"));
    assertThat(run.getInt("rowsAffected"), is(1));
    assertThat(run.getInt("heldSkipped"), is(0));
    assertThat(phoneOf(settled), is((String) null));
    assertThat(phoneOf(open), is("+447400900123"));
    String payload =
        scalar(
            PG,
            "SELECT payload FROM \"order\".outbox WHERE event_type = 'RetentionRunCompleted'"
                + " AND tenant_id = '"
                + AT_ONCE
                + "' ORDER BY created_at DESC LIMIT 1");
    assertThat(payload, containsString("\"service\":\"order-svc\""));
    assertThat(payload, containsString("\"rowsAffected\":1"));
    assertThat(payload, containsString("\"dataClass\":\"ORDER_PERSONAL_DATA\""));
    // Nothing left to purge: a second run does nothing and says so.
    assertThat(ok(sweep(AT_ONCE, "OWNER")).getInt("rowsAffected"), is(0));
  }

  @Test
  void aHeldOrderAndAHeldCustomersOrderAreKept() {
    TENANTS.with(HELD, "GBP", "GB");
    String plain = tillSale(HELD, null, true);
    String ofHeldCustomer = tillSale(HELD, HELD_CUSTOMER, true);
    String heldOrder = tillSale(HELD, null, true);
    TENANTS.withRetention(
        HELD,
        Map.of("ORDER_PERSONAL_DATA", 0),
        List.of(
            "{\"subjectKind\":\"CUSTOMER\",\"subjectId\":\"" + HELD_CUSTOMER + "\"}",
            "{\"subjectKind\":\"ORDER\",\"dataClass\":\"ORDER_PERSONAL_DATA\",\"subjectId\":\""
                + heldOrder
                + "\"}"));
    JsonObject run = ok(sweep(HELD, "OWNER"));
    assertThat(run.getInt("rowsAffected"), is(1));
    assertThat(run.getInt("heldSkipped"), is(2));
    assertThat(phoneOf(plain), is((String) null));
    assertThat(phoneOf(ofHeldCustomer), is("+447400900123"));
    assertThat(phoneOf(heldOrder), is("+447400900123"));
  }

  @Test
  void aHoldOnTheClassStopsEverythingAndNoPeriodMeansNoPurge() {
    String settled = tillSale(CLASS_HELD, null, true);
    JsonObject run = ok(sweep(CLASS_HELD, "OWNER"));
    assertThat(run.getInt("rowsAffected"), is(0));
    assertThat(run.getInt("heldSkipped"), is(1));
    assertThat(phoneOf(settled), is("+447400900123"));

    tillSale(UNSET, null, true);
    Response unset = sweep(UNSET, "OWNER");
    assertThat(unset.getStatus(), is(409));
    assertThat(unset.readEntity(String.class), containsString("RETENTION_PERIOD_NOT_SET"));
    assertThat(sweep(AT_ONCE, "CASHIER").getStatus(), is(403));
    assertThat(sweep(AT_ONCE, "STOREKEEPER").getStatus(), is(403));
    assertThat(sweep(AT_ONCE, "CUSTOMER").getStatus(), is(403));
  }

  @Test
  void aPeriodCountsFromSettlementAndTheSweeperVisitsEveryTenant() {
    String old = tillSale(MONTH, null, true);
    String recent = tillSale(MONTH, null, true);
    exec(
        PG,
        "UPDATE \"order\".orders SET updated_at = now() - interval '31 days' WHERE id = '"
            + old
            + "'");
    sweeper.sweepQuietly();
    assertThat(phoneOf(old), is((String) null));
    assertThat(phoneOf(recent), is("+447400900123"));
    assertThat(
        scalar(
            PG,
            "SELECT COUNT(*) FROM \"order\".outbox WHERE event_type = 'RetentionRunCompleted'"
                + " AND tenant_id = '"
                + MONTH
                + "'"),
        is("1"));
    // A business with no period set was visited and left alone.
    assertThat(
        scalar(
            PG,
            "SELECT COUNT(*) FROM \"order\".outbox WHERE event_type = 'RetentionRunCompleted'"
                + " AND tenant_id = '"
                + UNSET
                + "'"),
        is("0"));
  }

  // ── harness ────────────────────────────────────────────────────────────────

  /** A till sale with a phone number left, paid for (and so settled) or not. */
  private String tillSale(String tenant, UUID customer, boolean paid) {
    Response r =
        WebTargets.at(target, "/orders")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .header("X-User-Id", OWNER)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                        + (customer == null ? "" : "\"customerId\":\"" + customer + "\",")
                        + "\"currency\":\"GBP\",\"contactPhone\":\"+447400900123\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"unitPrice\":5.00}]}",
                    MediaType.APPLICATION_JSON));
    String id = created(r).getString("id");
    if (paid) {
      orderService.handlePaymentCaptured(
          Ids.parse(tenant), Ids.parse(id), Ids.newId(), new BigDecimal("5.00"));
    }
    return id;
  }

  private Response sweep(String tenant, String roles) {
    return WebTargets.at(target, "/admin/orders/retention/sweep")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .header("X-User-Id", OWNER)
        .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
  }

  /**
   * The order's number as typed, or — should a purge ever leave it behind — its international form
   * (a phone at the till): a purged order answers null only when both are gone.
   */
  private static String phoneOf(String orderId) {
    return scalar(
        PG,
        "SELECT coalesce(contact_phone, contact_phone_e164) FROM \"order\".orders WHERE id = '"
            + orderId
            + "'");
  }
}
