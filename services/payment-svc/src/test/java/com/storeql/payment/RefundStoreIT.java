package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.PaymentService;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code refund_tenders.store_id}: every path that writes a refund names the store it belongs to,
 * so a store's Z report and tender mix read it directly. From the payment refunded for a manual
 * refund, a return, a cancellation and a line adjustment; from the event's store for an exchange
 * (the store where it was made, the same store the EXCHANGE tender is taken at); and V12 gives the
 * rows written before it the store of their payment, touching nothing else.
 */
@HelidonTest
class RefundStoreIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");
  private static final String CONSUMER = "payment-svc/order-refund";

  @Inject WebTarget target;
  @Inject PaymentRepository payments;
  @Inject PaymentService service;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private UUID tender(UUID tenant, UUID order, UUID store, String amount, String method) {
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenant,
            order,
            new BigDecimal(amount),
            method,
            null,
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            store),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenant, id, "{}"));
    return id;
  }

  /** The stores on an order's refund rows, as text ("null" for none), by method then amount. */
  private List<String> stores(UUID tenant, UUID order) throws Exception {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT store_id FROM payment.refund_tenders WHERE tenant_id = ? AND order_id = ?"
                    + " ORDER BY method, amount")) {
      ps.setObject(1, tenant);
      ps.setObject(2, order);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) out.add(String.valueOf(rs.getObject(1)));
      }
    }
    return out;
  }

  // ── manual refund ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("A back-office refund is the store of the payment it refunds")
  void aManualRefundIsTheStoresOfItsPayment() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID store = Ids.newId();
    UUID payment = tender(tenant, order, store, "20.00", PaymentTender.METHOD_CARD);

    Answer a =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + order + "/refunds",
            new Caller(tenant, Ids.newId(), "MANAGER"),
            "{\"paymentId\":\"" + payment + "\",\"amount\":5.00,\"method\":\"CARD\"}",
            Ids.newId().toString());

    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(stores(tenant, order), contains(store.toString()));
  }

  @Test
  @DisplayName("A refund of a payment taken with no store has no store either")
  void aPaymentWithNoStoreGivesAStorelessRefund() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    tender(tenant, order, null, "20.00", PaymentTender.METHOD_CARD);

    service.refundForOrderEvent(
        Ids.newId(), CONSUMER, tenant, order, new BigDecimal("5.00"), "return");

    assertThat(stores(tenant, order), contains("null"));
  }

  // ── event-driven refunds ───────────────────────────────────────────────────

  @Test
  @DisplayName("A cancellation refunds each tender under the store that tender was taken at")
  void aCancellationIsEachTendersStore() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID tillA = Ids.newId();
    UUID tillB = Ids.newId();
    tender(tenant, order, tillA, "20.00", PaymentTender.METHOD_CARD);
    tender(tenant, order, tillB, "5.00", PaymentTender.METHOD_CASH);

    service.refundForOrderEvent(Ids.newId(), CONSUMER, tenant, order, null, "cancelled");

    // Ordered by method: CARD (store A) then CASH (store B).
    assertThat(stores(tenant, order), contains(tillA.toString(), tillB.toString()));
  }

  @Test
  @DisplayName("A return to the original tender is the payment's store")
  void aReturnToTheOriginalTenderIsThePaymentsStore() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID store = Ids.newId();
    tender(tenant, order, store, "40.00", PaymentTender.METHOD_CARD);

    service.refundReturnForOrderEvent(
        Ids.newId(),
        CONSUMER,
        tenant,
        order,
        new BigDecimal("12.00"),
        "return",
        new PaymentService.ReturnRefund("ORIGINAL", Ids.newId(), null));

    assertThat(stores(tenant, order), contains(store.toString()));
  }

  @Test
  @DisplayName("A return to store credit or a gift card is the payment's store")
  void aReturnToACreditOrGiftCardIsThePaymentsStore() throws Exception {
    for (String method : new String[] {"STORE_CREDIT", "GIFT_CARD"}) {
      UUID tenant = Ids.newId();
      UUID order = Ids.newId();
      UUID store = Ids.newId();
      tender(tenant, order, store, "40.00", PaymentTender.METHOD_CARD);

      service.refundReturnForOrderEvent(
          Ids.newId(),
          CONSUMER,
          tenant,
          order,
          new BigDecimal("12.00"),
          "return",
          new PaymentService.ReturnRefund(method, Ids.newId(), Ids.newId(), "INR"));

      assertThat(method, stores(tenant, order), contains(store.toString()));
    }
  }

  @Test
  @DisplayName("A line closed short or substituted is the payment's store")
  void anOrderAdjustmentIsThePaymentsStore() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID store = Ids.newId();
    tender(tenant, order, store, "40.00", PaymentTender.METHOD_CARD);

    service.refundForOrderEvent(
        Ids.newId(),
        CONSUMER,
        tenant,
        order,
        new BigDecimal("3.00"),
        "line short",
        "ORDER_ADJUSTMENT");

    assertThat(stores(tenant, order), contains(store.toString()));
  }

  @Test
  @DisplayName("A replayed refund event writes no second row")
  void aReplayedEventWritesNoSecondRow() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID store = Ids.newId();
    tender(tenant, order, store, "40.00", PaymentTender.METHOD_CARD);
    UUID event = Ids.newId();

    service.refundForOrderEvent(event, CONSUMER, tenant, order, new BigDecimal("3.00"), "r");
    service.refundForOrderEvent(event, CONSUMER, tenant, order, new BigDecimal("3.00"), "r");

    assertThat(stores(tenant, order), contains(store.toString()));
  }

  // ── exchange ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An exchange's refunds are the store the exchange was made at")
  void anExchangeIsTheEventsStore() throws Exception {
    UUID tenant = Ids.newId();
    UUID oldOrder = Ids.newId();
    UUID soldAt = Ids.newId();
    UUID exchangedAt = Ids.newId();
    tender(tenant, oldOrder, soldAt, "40.00", PaymentTender.METHOD_CARD);
    UUID newOrder = Ids.newId();

    // 25 of goods exchanged for 15 of new goods: 15 is the EXCHANGE refund, 10 goes back to card.
    service.exchangeForOrderEvent(
        Ids.newId(),
        CONSUMER,
        tenant,
        oldOrder,
        new PaymentService.ExchangeReturn(
            newOrder,
            exchangedAt,
            new BigDecimal("15.00"),
            new BigDecimal("25.00"),
            Ids.newId(),
            null,
            "INR"));

    // Both rows are the exchange store's (CARD, EXCHANGE) — where the EXCHANGE tender was taken —
    // not the store the original sale was made at.
    assertThat(stores(tenant, oldOrder), contains(exchangedAt.toString(), exchangedAt.toString()));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT store_id FROM payment.payment_tenders WHERE tenant_id = '"
                + tenant
                + "' AND order_id = '"
                + newOrder
                + "' AND method = 'EXCHANGE'"),
        is(exchangedAt.toString()));
  }

  @Test
  @DisplayName("An exchange event that names no store falls back to the payment's store")
  void anExchangeWithNoStoreFallsBackToThePayment() throws Exception {
    UUID tenant = Ids.newId();
    UUID oldOrder = Ids.newId();
    UUID soldAt = Ids.newId();
    tender(tenant, oldOrder, soldAt, "40.00", PaymentTender.METHOD_CARD);

    service.exchangeForOrderEvent(
        Ids.newId(),
        CONSUMER,
        tenant,
        oldOrder,
        new PaymentService.ExchangeReturn(
            Ids.newId(),
            null,
            new BigDecimal("15.00"),
            new BigDecimal("15.00"),
            Ids.newId(),
            null,
            "INR"));

    assertThat(stores(tenant, oldOrder), contains(soldAt.toString()));
  }

  // ── what reads it ──────────────────────────────────────────────────────────

  private Answer zReport(Caller who, UUID store) {
    return ItCalls.post(
        target,
        "/admin/cash/z-report",
        who,
        "{\"storeId\":\""
            + store
            + "\",\"businessDate\":\""
            + LocalDate.now(ZoneOffset.UTC)
            + "\",\"countedCash\":0,\"currency\":\"INR\"}");
  }

  @Test
  @DisplayName("A store's Z report counts the refunds made at that store, and only that store")
  void theZReportReadsTheRefundsStore() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    UUID orderA = Ids.newId();
    UUID orderB = Ids.newId();
    UUID cardA = tender(tenant, orderA, storeA, "30.00", PaymentTender.METHOD_CARD);
    tender(tenant, orderB, storeB, "50.00", PaymentTender.METHOD_CARD);
    Caller manager = new Caller(tenant, Ids.newId(), "MANAGER");

    Answer refund =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + orderA + "/refunds",
            manager,
            "{\"paymentId\":\"" + cardA + "\",\"amount\":7.50,\"method\":\"CARD\"}",
            Ids.newId().toString());
    assertThat(refund.body().toString(), refund.status(), is(201));
    service.refundForOrderEvent(
        Ids.newId(), CONSUMER, tenant, orderA, new BigDecimal("2.50"), "return");

    Answer a = zReport(manager, storeA);
    Answer b = zReport(manager, storeB);

    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(
        a.data().getJsonNumber("totalRefunds").bigDecimalValue().compareTo(new BigDecimal("10.00")),
        is(0));
    assertThat(
        a.data().getJsonNumber("netSales").bigDecimalValue().compareTo(new BigDecimal("20.00")),
        is(0));
    assertThat(b.data().getJsonNumber("totalRefunds").bigDecimalValue().signum(), is(0));
  }

  @Test
  @DisplayName("Another business's manager naming our store sees none of its refunds")
  void anotherBusinessSeesNoneOfOurRefundsInAZReport() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID order = Ids.newId();
    tender(tenant, order, store, "30.00", PaymentTender.METHOD_CARD);
    service.refundForOrderEvent(
        Ids.newId(), CONSUMER, tenant, order, new BigDecimal("4.00"), "return");
    UUID stranger = Ids.newId();

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer a = zReport(new Caller(stranger, Ids.newId(), role), store);
      // The first ask settles the stranger's day (201); the second answers the stored one (200).
      assertThat(role + " " + a.body(), a.status(), anyOf(is(200), is(201)));
      assertThat(a.data().getJsonNumber("totalRefunds").bigDecimalValue().signum(), is(0));
      assertThat(a.data().getJsonNumber("totalSales").bigDecimalValue().signum(), is(0));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, zReport(new Caller(stranger, Ids.newId(), role), store).status(), is(403));
    }
    assertThat(
        "nothing written for us",
        Envelopes.scalar(
            PG, "SELECT count(*) FROM payment.z_reports WHERE tenant_id = '" + tenant + "'"),
        is("0"));
    // Ours reads its own.
    Answer ours = zReport(new Caller(tenant, Ids.newId(), "MANAGER"), store);
    assertThat(
        ours.data()
            .getJsonNumber("totalRefunds")
            .bigDecimalValue()
            .compareTo(new BigDecimal("4.00")),
        is(0));
  }

  @Test
  @DisplayName("The tender mix of a manager held to a store counts the refunds written at it")
  void theTenderMixReadsTheRefundsStore() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    UUID orderA = Ids.newId();
    UUID orderB = Ids.newId();
    tender(tenant, orderA, storeA, "30.00", PaymentTender.METHOD_CARD);
    tender(tenant, orderB, storeB, "50.00", PaymentTender.METHOD_CARD);
    service.refundForOrderEvent(
        Ids.newId(), CONSUMER, tenant, orderA, new BigDecimal("6.00"), "return");
    service.refundForOrderEvent(
        Ids.newId(), CONSUMER, tenant, orderB, new BigDecimal("9.00"), "return");

    Answer answer =
        ItCalls.get(
            target,
            "/admin/reports/tender-mix",
            new Caller(tenant, Ids.newId(), "MANAGER", storeA));

    assertThat(answer.body().toString(), answer.status(), is(200));
    JsonArray rows = answer.list();
    assertThat(rows.size(), is(1));
    assertThat(
        rows.getJsonObject(0)
            .getJsonNumber("refundedAmount")
            .bigDecimalValue()
            .compareTo(new BigDecimal("6.00")),
        is(0));
  }

  // ── the back-fill ──────────────────────────────────────────────────────────

  private void insertRefund(UUID tenant, UUID order, UUID payment, UUID store) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO payment.refund_tenders"
                    + " (id, tenant_id, order_id, payment_id, amount, method, created_at, store_id)"
                    + " VALUES (?,?,?,?,?,?, now(), ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenant);
      ps.setObject(3, order);
      ps.setObject(4, payment);
      ps.setBigDecimal(5, new BigDecimal("1.00"));
      ps.setString(6, "CARD");
      ps.setObject(7, store);
      ps.executeUpdate();
    }
  }

  private void runBackfill() throws Exception {
    runBackfill("V12__backfill_refund_tender_store.sql");
  }

  private void runBackfill(String migration) throws Exception {
    String sql;
    try (var in = getClass().getResourceAsStream("/db/migration/" + migration)) {
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement()) {
      st.execute("SET search_path TO payment");
      st.execute(sql);
    }
  }

  @Test
  @DisplayName("V12 gives an old refund its payment's store and touches nothing else")
  void theBackfillFillsOnlyWhatWasNeverSet() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID store = Ids.newId();
    UUID keptStore = Ids.newId();
    UUID orderA = Ids.newId();
    UUID orderB = Ids.newId();
    UUID orderC = Ids.newId();
    UUID orderD = Ids.newId();
    UUID withStore = tender(tenant, orderA, store, "9.00", PaymentTender.METHOD_CARD);
    UUID noStore = tender(tenant, orderB, null, "9.00", PaymentTender.METHOD_CARD);
    UUID forKept = tender(tenant, orderC, store, "9.00", PaymentTender.METHOD_CARD);
    UUID ours = tender(tenant, orderD, store, "9.00", PaymentTender.METHOD_CARD);
    insertRefund(tenant, orderA, withStore, null); // written before: gets the payment's store
    insertRefund(tenant, orderB, noStore, null); // its payment had none: stays store-less
    insertRefund(tenant, orderC, forKept, keptStore); // already set: never changed
    insertRefund(other, orderD, ours, null); // another business's row naming our payment: no match

    runBackfill();
    runBackfill(); // once or twice, the same

    assertThat(stores(tenant, orderA), contains(store.toString()));
    assertThat(stores(tenant, orderB), contains("null"));
    assertThat(stores(tenant, orderC), contains(keptStore.toString()));
    assertThat(stores(other, orderD), contains("null"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT store_id FROM payment.refund_tenders WHERE tenant_id = '"
                + other
                + "' AND order_id = '"
                + orderD
                + "'"),
        nullValue());
  }

  @Test
  @DisplayName(
      "V20 fills, once more, a store a refund was written without (a rolling deploy's older build),"
          + " so the store-held reads that no longer fall back to the payment still count it")
  void theSecondBackfillLetsTheReadsTakeTheRefundsOwnStore() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    UUID orderA = Ids.newId();
    UUID orderB = Ids.newId();
    UUID orderC = Ids.newId();
    UUID paidA = tender(tenant, orderA, storeA, "9.00", PaymentTender.METHOD_CARD);
    UUID paidNoStore = tender(tenant, orderB, null, "9.00", PaymentTender.METHOD_CARD);
    UUID paidB = tender(tenant, orderC, storeB, "9.00", PaymentTender.METHOD_CARD);
    insertRefund(tenant, orderA, paidA, null); // written without one: gets its payment's
    insertRefund(tenant, orderB, paidNoStore, null); // its payment had none: stays store-less
    insertRefund(tenant, orderC, paidB, storeA); // set (an exchange at another store): kept
    insertRefund(other, orderA, paidA, null); // another business's row naming our payment

    runBackfill("V20__refund_store_written_everywhere.sql");
    runBackfill("V20__refund_store_written_everywhere.sql"); // once or twice, the same

    assertThat(stores(tenant, orderA), contains(storeA.toString()));
    assertThat(stores(tenant, orderB), contains("null"));
    assertThat(stores(tenant, orderC), contains(storeA.toString()));
    assertThat(stores(other, orderA), contains("null"));

    // A manager held to store A reads the refund now on A, and the exchange's written at A; one
    // held to B reads neither (the exchange was A's, though its payment was B's).
    Answer atA =
        ItCalls.get(
            target,
            "/admin/reports/tender-mix",
            new Caller(tenant, Ids.newId(), "MANAGER", storeA));
    assertThat(atA.body().toString(), atA.status(), is(200));
    assertThat(
        atA.list()
            .getJsonObject(0)
            .getJsonNumber("refundedAmount")
            .bigDecimalValue()
            .compareTo(new BigDecimal("2.00")),
        is(0));
    Answer atB =
        ItCalls.get(
            target,
            "/admin/reports/tender-mix",
            new Caller(tenant, Ids.newId(), "MANAGER", storeB));
    assertThat(
        atB.list().getJsonObject(0).getJsonNumber("refundedAmount").bigDecimalValue().signum(),
        is(0));
    // Another business's manager naming our store reads none of ours.
    Answer stranger =
        ItCalls.get(
            target,
            "/admin/reports/tender-mix?storeId=" + storeA,
            new Caller(other, Ids.newId(), "OWNER"));
    for (int i = 0; stranger.status() == 200 && i < stranger.list().size(); i++) {
      assertThat(
          stranger
              .list()
              .getJsonObject(i)
              .getJsonNumber("capturedAmount")
              .bigDecimalValue()
              .signum(),
          is(0));
    }
  }
}
