package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Return controls, slice 2: the gift-card tender that follows a card charged by order-svc, and the
 * direct exchange (the returned value pays the new order through an EXCHANGE tender, only the
 * difference goes back to the original tenders).
 */
@HelidonTest
class ReturnExchangeGiftCardIT {

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
  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private void captureCard(UUID tenantId, UUID orderId, String amount) {
    UUID id = Ids.newId();
    repo.createTender(
        new PaymentTender(
            id,
            tenantId,
            orderId,
            new BigDecimal(amount),
            PaymentTender.METHOD_CARD,
            null,
            null,
            "CAPTURED",
            null,
            Instant.now(),
            null),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenantId, id, "{}"));
  }

  private List<String> events(UUID tenantId, String type) throws Exception {
    List<String> out = new ArrayList<>();
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM payment.outbox WHERE tenant_id = ? AND event_type = ?"
                    + " ORDER BY created_at")) {
      ps.setObject(1, tenantId);
      ps.setString(2, type);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getString(1));
      }
    }
    return out;
  }

  private List<String> capturedFor(UUID tenantId, UUID orderId) throws Exception {
    return events(tenantId, "PaymentCaptured").stream()
        .filter(p -> p.contains(orderId.toString()))
        .toList();
  }

  private static BigDecimal sum(List<BigDecimal> xs) {
    return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static void assertAmount(BigDecimal actual, String expected) {
    assertThat(actual.toPlainString(), actual.compareTo(new BigDecimal(expected)), is(0));
  }

  private List<PaymentTender> tenders(UUID tenant, UUID order, String method) {
    return repo.findTendersByOrder(tenant, order).stream()
        .filter(t -> method.equals(t.method()))
        .toList();
  }

  private List<RefundTender> refunds(UUID tenant, UUID order, String method) {
    return repo.findRefundsByOrder(tenant, order).stream()
        .filter(r -> method.equals(r.method()))
        .toList();
  }

  // ── gift card tender ───────────────────────────────────────────────────────

  @Test
  void giftCardRedeemedRecordsOneTenderAndOneCaptureOnce() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID store = Ids.newId();
    UUID redemption = Ids.newId();
    UUID event = Ids.newId();

    assertThat(
        service.recordGiftCardRedemption(
            event, CONSUMER, tenant, redemption, order, store, new BigDecimal("12.50")),
        is(true));
    // The same event again, and the same redemption under another event id: nothing repeats.
    assertThat(
        service.recordGiftCardRedemption(
            event, CONSUMER, tenant, redemption, order, store, new BigDecimal("12.50")),
        is(false));
    assertThat(
        service.recordGiftCardRedemption(
            Ids.newId(), CONSUMER, tenant, redemption, order, store, new BigDecimal("12.50")),
        is(false));

    List<PaymentTender> gift = tenders(tenant, order, "GIFT_CARD");
    assertThat(gift.size(), is(1));
    assertAmount(gift.get(0).amount(), "12.50");
    assertThat(gift.get(0).reference(), is(redemption.toString()));
    assertThat(gift.get(0).storeId(), is(store));
    List<String> captured = events(tenant, "PaymentCaptured");
    assertThat(captured.size(), is(1));
    assertThat(captured.get(0), containsString("\"method\":\"GIFT_CARD\""));
    assertThat(captured.get(0), containsString("\"storeId\":\"" + store + "\""));
  }

  @Test
  void anotherBusinessesGiftCardEventTouchesNothing() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID order = Ids.newId();
    service.recordGiftCardRedemption(
        Ids.newId(), CONSUMER, other, Ids.newId(), order, null, new BigDecimal("5.00"));

    assertThat(repo.findTendersByOrder(tenant, order).size(), is(0));
    assertThat(events(tenant, "PaymentCaptured").size(), is(0));
  }

  @Test
  void aClientCannotRecordAGiftCardTender() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    var answer =
        ItCalls.call(
            target,
            "POST",
            "/payments",
            ItCalls.Caller.owner(tenant),
            "{\"orderId\":\"" + order + "\",\"amount\":10.00,\"method\":\"GIFT_CARD\"}",
            Ids.newId().toString());

    assertThat(answer.body().toString(), answer.status(), is(400));
    assertThat(answer.body().toString(), containsString("PAYMENT_GIFT_CARD_VIA_REDEEM"));
    assertThat(repo.findTendersByOrder(tenant, order).size(), is(0));
    assertThat(events(tenant, "PaymentCaptured").size(), is(0));
  }

  // ── exchange ───────────────────────────────────────────────────────────────

  private PaymentService.ExchangeReturn exchange(
      UUID newOrder, UUID store, String exchanged, String refund, UUID ret) {
    return new PaymentService.ExchangeReturn(
        newOrder, store, new BigDecimal(exchanged), new BigDecimal(refund), ret, null, "GBP");
  }

  @Test
  void likeForLikeExchangeMovesNoMoneyAndCallsNoProvider() throws Exception {
    UUID tenant = Ids.newId();
    UUID oldOrder = Ids.newId();
    UUID newOrder = Ids.newId();
    UUID store = Ids.newId();
    UUID ret = Ids.newId();
    captureCard(tenant, oldOrder, "40.00");
    UUID event = Ids.newId();
    var ex = exchange(newOrder, store, "25.00", "25.00", ret);

    service.exchangeForOrderEvent(event, CONSUMER, tenant, oldOrder, ex);
    service.exchangeForOrderEvent(event, CONSUMER, tenant, oldOrder, ex);

    List<RefundTender> refunds = repo.findRefundsByOrder(tenant, oldOrder);
    assertThat(refunds.size(), is(1));
    assertThat(refunds.get(0).method(), is("EXCHANGE"));
    assertAmount(refunds.get(0).amount(), "25.00");
    List<PaymentTender> ex1 = tenders(tenant, newOrder, "EXCHANGE");
    assertThat(ex1.size(), is(1));
    assertAmount(ex1.get(0).amount(), "25.00");
    assertThat(ex1.get(0).storeId(), is(store));

    List<String> refunded = events(tenant, "PaymentRefunded");
    assertThat(refunded.size(), is(1));
    assertThat(refunded.get(0), containsString("\"refundMethod\":\"EXCHANGE\""));
    assertThat(refunded.get(0), containsString("\"method\":\"EXCHANGE\",\"amount\":25.00"));
    assertThat(refunded.get(0), containsString("\"returnId\":\"" + ret + "\""));
    assertThat(refunded.get(0), containsString("\"orderId\":\"" + oldOrder + "\""));
    // The old sale's own capture is on the outbox too: only the new order's is the exchange.
    List<String> captured = capturedFor(tenant, newOrder);
    assertThat(captured.size(), is(1));
    assertThat(captured.get(0), containsString("\"orderId\":\"" + newOrder + "\""));
    assertThat(captured.get(0), containsString("\"method\":\"EXCHANGE\""));
    assertThat(captured.get(0), containsString("\"amount\":25.00"));
  }

  @Test
  void aCheaperNewBasketAlsoRefundsTheDifferenceToTheOriginalTender() throws Exception {
    UUID tenant = Ids.newId();
    UUID oldOrder = Ids.newId();
    UUID newOrder = Ids.newId();
    captureCard(tenant, oldOrder, "40.00");
    UUID event = Ids.newId();
    // R = 30 returned, B = 18 new basket: 18 exchanged, 12 back to the card.
    var ex = exchange(newOrder, Ids.newId(), "18.00", "30.00", Ids.newId());

    service.exchangeForOrderEvent(event, CONSUMER, tenant, oldOrder, ex);
    service.exchangeForOrderEvent(event, CONSUMER, tenant, oldOrder, ex);

    assertAmount(
        sum(refunds(tenant, oldOrder, "EXCHANGE").stream().map(RefundTender::amount).toList()),
        "18.00");
    assertAmount(
        sum(refunds(tenant, oldOrder, "CARD").stream().map(RefundTender::amount).toList()),
        "12.00");
    assertAmount(
        sum(tenders(tenant, newOrder, "EXCHANGE").stream().map(PaymentTender::amount).toList()),
        "18.00");
    List<String> refunded = events(tenant, "PaymentRefunded");
    assertThat(refunded.size(), is(2));
    // Both rows share the transaction's timestamp, so do not depend on their order.
    String joined = String.join("\n", refunded);
    assertThat(joined, containsString("\"refundMethod\":\"EXCHANGE\""));
    assertThat(joined, containsString("\"refundMethod\":\"ORIGINAL\""));
    assertThat(joined, containsString("\"method\":\"CARD\",\"amount\":12.00"));
    assertThat(capturedFor(tenant, newOrder).size(), is(1));
  }

  @Test
  void anExchangeIsCappedAtWhatTheOriginalStillHasCaptured() throws Exception {
    UUID tenant = Ids.newId();
    UUID oldOrder = Ids.newId();
    UUID newOrder = Ids.newId();
    captureCard(tenant, oldOrder, "10.00");
    // Partly refunded before: 4 left.
    service.refundForOrderEvent(
        Ids.newId(), CONSUMER, tenant, oldOrder, new BigDecimal("6.00"), "x");

    service.exchangeForOrderEvent(
        Ids.newId(),
        CONSUMER,
        tenant,
        oldOrder,
        exchange(newOrder, null, "9.00", "9.00", Ids.newId()));

    assertAmount(
        sum(refunds(tenant, oldOrder, "EXCHANGE").stream().map(RefundTender::amount).toList()),
        "4.00");
    assertAmount(
        sum(tenders(tenant, newOrder, "EXCHANGE").stream().map(PaymentTender::amount).toList()),
        "4.00");
  }

  @Test
  void anotherBusinessesExchangeEventTouchesNothing() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID oldOrder = Ids.newId();
    UUID newOrder = Ids.newId();
    captureCard(tenant, oldOrder, "40.00");

    service.exchangeForOrderEvent(
        Ids.newId(),
        CONSUMER,
        other,
        oldOrder,
        exchange(newOrder, null, "25.00", "30.00", Ids.newId()));

    assertThat(repo.findRefundsByOrder(tenant, oldOrder).size(), is(0));
    assertThat(repo.findRefundsByOrder(other, oldOrder).size(), is(0));
    assertThat(repo.findTendersByOrder(other, newOrder).size(), is(0));
    assertThat(events(tenant, "PaymentRefunded").size(), is(0));
    assertThat(events(other, "PaymentCaptured").size(), is(0));
  }
}
