package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.PaymentService;
import com.storeql.service.OutboxRow;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the automatic order-event refund path (N5). Exercises {@link
 * PaymentService#refundForOrderEvent} against real Postgres: return refunds are capped at the
 * captured total, cancellations refund the remaining, redelivery of the same order event is
 * deduped, and an unpaid order is a no-op.
 */
@HelidonTest
class PaymentRefundIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "payment");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String CONSUMER = "payment-svc/order-refund";

  @Inject PaymentService service;
  @Inject PaymentRepository repo;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private UUID captureTender(UUID tenantId, UUID orderId, String amount) {
    UUID tenderId = Ids.newId();
    var tender =
        new PaymentTender(
            tenderId,
            tenantId,
            orderId,
            new BigDecimal(amount),
            PaymentTender.METHOD_CARD,
            null,
            null,
            "CAPTURED",
            null,
            Instant.now(),
            null);
    repo.createTender(
        tender,
        new OutboxRow(
            "PaymentCaptured", "storeql.payment.payment-captured", tenantId, tenderId, "{}"));
    return tenderId;
  }

  private BigDecimal totalRefunded(UUID tenantId, UUID orderId) {
    return repo.findRefundsByOrder(tenantId, orderId).stream()
        .map(RefundTender::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** refund_tenders.amount is NUMERIC(14,4), so compare by value, not scale-sensitive equals. */
  private void assertRefunded(UUID tenantId, UUID orderId, String expected) {
    assertThat(totalRefunded(tenantId, orderId).compareTo(new BigDecimal(expected)), is(0));
  }

  @Test
  void returnRefundIsAppliedOnceAndCappedAtCaptured() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    captureTender(tenant, order, "40.00");

    UUID event = Ids.newId();
    // Return refund of 15 against a 40 capture.
    service.refundForOrderEvent(event, CONSUMER, tenant, order, new BigDecimal("15.00"), "return");
    // Redelivery of the SAME order event must not refund again.
    service.refundForOrderEvent(event, CONSUMER, tenant, order, new BigDecimal("15.00"), "return");

    assertRefunded(tenant, order, "15.00");
  }

  @Test
  void returnRefundNeverExceedsCapturedTotal() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    captureTender(tenant, order, "30.00");

    // A return claiming more than was captured is capped at the captured 30.
    service.refundForOrderEvent(
        Ids.newId(), CONSUMER, tenant, order, new BigDecimal("999.00"), "return");

    assertRefunded(tenant, order, "30.00");
  }

  @Test
  void cancellationRefundsAllRemainingAcrossSplitTenders() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    captureTender(tenant, order, "20.00");
    captureTender(tenant, order, "5.00");

    // null requested amount = refund whatever is still captured (25 across two tenders).
    service.refundForOrderEvent(Ids.newId(), CONSUMER, tenant, order, null, "cancelled");

    assertRefunded(tenant, order, "25.00");
    // One refund row per tender touched.
    List<RefundTender> refunds = repo.findRefundsByOrder(tenant, order);
    assertThat(refunds.size(), is(2));
  }

  @Test
  void unpaidOrderCancellationIsANoOp() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    // No captured tender at all (e.g. pay-later order cancelled before payment).

    service.refundForOrderEvent(Ids.newId(), CONSUMER, tenant, order, null, "cancelled");

    assertRefunded(tenant, order, "0");
  }

  // ── return refund methods (return controls) ────────────────────────────────

  private List<String> refundedEvents(UUID tenantId) throws Exception {
    List<String> out = new java.util.ArrayList<>();
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM payment.outbox WHERE tenant_id = ?"
                    + " AND event_type = 'PaymentRefunded' ORDER BY created_at")) {
      ps.setObject(1, tenantId);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getString(1));
      }
    }
    return out;
  }

  private void storeCreditOrGiftCardReturn(String method) throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID customer = Ids.newId();
    UUID ret = Ids.newId();
    captureTender(tenant, order, "40.00");
    // The sale's currency rides on the event, so store credit is credited in what was paid.
    var info =
        new PaymentService.ReturnRefund(
            method, ret, "STORE_CREDIT".equals(method) ? customer : null, "INR");

    UUID event = Ids.newId();
    service.refundReturnForOrderEvent(
        event, CONSUMER, tenant, order, new BigDecimal("15.00"), "return", info);
    service.refundReturnForOrderEvent(
        event, CONSUMER, tenant, order, new BigDecimal("15.00"), "return", info);

    List<RefundTender> refunds = repo.findRefundsByOrder(tenant, order);
    assertThat(refunds.size(), is(1));
    assertThat(refunds.get(0).method(), is(method));
    assertRefunded(tenant, order, "15.00");

    List<String> events = refundedEvents(tenant);
    assertThat(events.size(), is(1));
    String json = events.get(0);
    assertThat(json.contains("\"refundMethod\":\"" + method + "\""), is(true));
    assertThat(json.contains("\"returnId\":\"" + ret + "\""), is(true));
    assertThat(json.contains("\"method\":\"" + method + "\",\"amount\":15.00"), is(true));
    assertThat(
        json.contains("\"customerId\":\"" + customer + "\""), is("STORE_CREDIT".equals(method)));
    assertThat(json, json.contains("\"currency\":\"INR\""), is(true));
  }

  @Test
  void storeCreditReturnRecordsOneStoreCreditRefundOnce() throws Exception {
    storeCreditOrGiftCardReturn("STORE_CREDIT");
  }

  @Test
  void giftCardReturnRecordsOneGiftCardRefundOnce() throws Exception {
    storeCreditOrGiftCardReturn("GIFT_CARD");
  }

  @Test
  void storeCreditReturnIsCappedAtWhatWasCapturedLessWhatWasRefunded() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    captureTender(tenant, order, "30.00");
    var info = new PaymentService.ReturnRefund("STORE_CREDIT", Ids.newId(), Ids.newId());

    service.refundReturnForOrderEvent(
        Ids.newId(), CONSUMER, tenant, order, new BigDecimal("20.00"), "return", info);
    service.refundReturnForOrderEvent(
        Ids.newId(), CONSUMER, tenant, order, new BigDecimal("999.00"), "return", info);

    assertRefunded(tenant, order, "30.00");
  }

  @Test
  void anotherBusinessesReturnEventTouchesNothing() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID order = Ids.newId();
    captureTender(tenant, order, "30.00");

    service.refundReturnForOrderEvent(
        Ids.newId(),
        CONSUMER,
        other,
        order,
        new BigDecimal("10.00"),
        "return",
        new PaymentService.ReturnRefund("GIFT_CARD", Ids.newId(), null));

    assertRefunded(tenant, order, "0");
    assertRefunded(other, order, "0");
    assertThat(refundedEvents(other).size(), is(0));
  }

  @Test
  void originalReturnStillRefundsTheOriginalTenderAndNamesTheMethod() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID ret = Ids.newId();
    captureTender(tenant, order, "40.00");

    service.refundReturnForOrderEvent(
        Ids.newId(),
        CONSUMER,
        tenant,
        order,
        new BigDecimal("12.00"),
        "return",
        new PaymentService.ReturnRefund("ORIGINAL", ret, null));

    List<RefundTender> refunds = repo.findRefundsByOrder(tenant, order);
    assertThat(refunds.size(), is(1));
    assertThat(refunds.get(0).method(), is(PaymentTender.METHOD_CARD));
    String json = refundedEvents(tenant).get(0);
    assertThat(json.contains("\"refundMethod\":\"ORIGINAL\""), is(true));
    assertThat(json.contains("\"customerId\""), is(false));
  }
}
