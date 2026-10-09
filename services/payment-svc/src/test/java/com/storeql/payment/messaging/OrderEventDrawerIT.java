package com.storeql.payment.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.TillSession;
import com.storeql.payment.dto.Dtos.CloseTillRequest;
import com.storeql.payment.dto.Dtos.TillReportResponse;
import com.storeql.payment.repo.CashManagementRepository;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.CashManagementService;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.web.TenantContext;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cash an order event gave back is counted in the drawer the till named, end to end: the event as
 * order-svc writes it, through the handler payment-svc consumes it with, to the refund row and the
 * drawer's own report. A cancellation, a void, a return and an exchange's cash-back all leave the
 * drawer that gave the cash; a gift card charged at the till is rung on it; and a drawer that is
 * not this business's open one at the store the money was taken at is dropped from the money, never
 * the money from the books -- an event is never refused over where it is counted.
 */
@HelidonTest
class OrderEventDrawerIT {

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

  @Inject OrderEventHandler handler;
  @Inject PaymentRepository payments;
  @Inject CashManagementRepository tills;
  @Inject CashManagementService cash;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private final UUID biz = Ids.newId();
  private final UUID store = Ids.newId();
  private final UUID elsewhere = Ids.newId();

  private TenantContext ctx() {
    UUID user = Ids.newId();
    return new TenantContext() {
      @Override
      public UUID requireTenantId() {
        return biz;
      }

      @Override
      public UUID tenantId() {
        return biz;
      }

      @Override
      public UUID userId() {
        return user;
      }

      @Override
      public UUID requireUserId() {
        return user;
      }
    };
  }

  /** A drawer on the SESSION basis with a float of 100.00. */
  private UUID drawerAt(UUID at) {
    UUID id = Ids.newId();
    tills.openTill(
        new TillSession(
            id,
            biz,
            at,
            Ids.newId(),
            new BigDecimal("100.00"),
            TillSession.STATUS_OPEN,
            null,
            null,
            Instant.now(),
            null,
            TillSession.BASIS_SESSION));
    return id;
  }

  /** A tender of the order, taken at {@code at}, naming {@code session} (or none). */
  private void tender(UUID order, String method, String amount, UUID at, UUID session) {
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            biz,
            order,
            new BigDecimal(amount),
            method,
            "CARD".equals(method) ? "AUTH 1" : null,
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            at),
        new OutboxRow(
            "PaymentCaptured", "storeql.payment.payment-captured", biz, id, "{\"eventId\":\"x\"}"),
        null,
        session);
  }

  private TillReportResponse x(UUID session) {
    return cash.xReport(biz, session, ctx());
  }

  private void close(UUID session, String counted) {
    cash.zReport(biz, session, new CloseTillRequest(new BigDecimal(counted), null), ctx());
  }

  private static void sameMoney(String what, BigDecimal actual, String expected) {
    assertThat(what + " was " + actual, actual.compareTo(new BigDecimal(expected)), is(0));
  }

  /** The refunds of the order as {@code method amount session}, ordered, one per line. */
  private String refundsOf(UUID order) {
    return Envelopes.scalar(
        PG,
        "SELECT coalesce(string_agg(method || ' ' || amount::numeric(10,2) || ' '"
            + " || coalesce(till_session_id::text, '-'), ';' ORDER BY method, amount), 'none')"
            + " FROM payment.refund_tenders WHERE tenant_id = '"
            + biz
            + "' AND order_id = '"
            + order
            + "'");
  }

  private String tendersOf(UUID order) {
    return Envelopes.scalar(
        PG,
        "SELECT coalesce(string_agg(method || ' ' || amount::numeric(10,2) || ' '"
            + " || coalesce(till_session_id::text, '-'), ';' ORDER BY method, amount), 'none')"
            + " FROM payment.payment_tenders WHERE tenant_id = '"
            + biz
            + "' AND order_id = '"
            + order
            + "'");
  }

  private String cancelled(UUID eventId, UUID order, String drawerMember) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderCancelled\",\"tenantId\":\""
        + biz
        + "\",\"orderId\":\""
        + order
        + "\",\"reason\":\"changed their mind\""
        + drawerMember
        + "}";
  }

  private String voided(UUID eventId, UUID order, String drawerMember) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderVoided\",\"tenantId\":\""
        + biz
        + "\",\"orderId\":\""
        + order
        + "\",\"storeId\":\""
        + store
        + "\",\"items\":[]"
        + drawerMember
        + "}";
  }

  private String returned(UUID order, String refundAmount, String drawerMember) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"OrderReturned\",\"tenantId\":\""
        + biz
        + "\",\"orderId\":\""
        + order
        + "\",\"returnId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + store
        + "\",\"refundAmount\":"
        + refundAmount
        + ",\"refundMethod\":\"ORIGINAL\",\"currency\":\"GBP\",\"items\":[]"
        + drawerMember
        + "}";
  }

  private static String drawer(UUID session) {
    return ",\"tillSessionId\":\"" + session + "\"";
  }

  // ── a cancellation ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("the cash a cancellation hands back leaves the drawer the cancel named")
  void aCancellationsCashLeavesTheDrawerThatGaveIt() {
    UUID t = drawerAt(store);
    UUID order = Ids.newId();
    tender(order, "CASH", "5.00", store, t);
    sameMoney("the drawer holds the float and the sale", x(t).expectedCashInTill(), "105.00");

    handler.handle(cancelled(Ids.newId(), order, drawer(t)));

    assertThat(refundsOf(order), is("CASH 5.00 " + t));
    sameMoney("the drawer after handing the cash back", x(t).expectedCashInTill(), "100.00");
  }

  @Test
  @DisplayName("a cancellation naming no drawer, or one that cannot be this cash's, still refunds")
  void aCancellationIsNeverRefusedOverItsDrawer() {
    UUID t = drawerAt(store);
    UUID atElsewhere = drawerAt(elsewhere);
    UUID closed = drawerAt(store);
    close(closed, "100.00");
    UUID none = Ids.newId();
    UUID other = Ids.newId();
    UUID shut = Ids.newId();
    UUID unknown = Ids.newId();
    tender(none, "CASH", "1.00", store, t);
    tender(other, "CASH", "2.00", store, t);
    tender(shut, "CASH", "3.00", store, t);
    tender(unknown, "CASH", "4.00", store, t);

    handler.handle(cancelled(Ids.newId(), none, ""));
    handler.handle(cancelled(Ids.newId(), other, drawer(atElsewhere)));
    handler.handle(cancelled(Ids.newId(), shut, drawer(closed)));
    handler.handle(cancelled(Ids.newId(), unknown, drawer(Ids.newId())));

    assertThat(refundsOf(none), is("CASH 1.00 -"));
    assertThat(refundsOf(other), is("CASH 2.00 -"));
    assertThat(refundsOf(shut), is("CASH 3.00 -"));
    assertThat(refundsOf(unknown), is("CASH 4.00 -"));
    // all four sit in drawer t as takings; none of the refunds is in any drawer
    sameMoney("the drawer t", x(t).expectedCashInTill(), "110.00");
    sameMoney("apart, not lost", x(t).notAtTill().get("CASH").refunds(), "10.00");
  }

  // ── a void ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the cash a void hands back leaves the drawer the void named")
  void aVoidsCashLeavesTheDrawerThatGaveIt() {
    UUID t = drawerAt(store);
    UUID order = Ids.newId();
    tender(order, "CASH", "20.00", store, t);

    handler.handle(voided(Ids.newId(), order, drawer(t)));

    assertThat(refundsOf(order), is("CASH 20.00 " + t));
    sameMoney("the drawer", x(t).expectedCashInTill(), "100.00");
  }

  @Test
  @DisplayName("a void naming a drawer of another store is refunded, counted at no drawer")
  void aVoidNamingAnotherStoresDrawerIsStillRefunded() {
    UUID t = drawerAt(store);
    UUID atElsewhere = drawerAt(elsewhere);
    UUID order = Ids.newId();
    tender(order, "CASH", "20.00", store, t);

    handler.handle(voided(Ids.newId(), order, drawer(atElsewhere)));

    assertThat(refundsOf(order), is("CASH 20.00 -"));
    sameMoney("the other store's drawer is untouched", x(atElsewhere).expectedCashInTill(), "100");
  }

  // ── a return to a drawer that has closed ───────────────────────────────────

  @Test
  @DisplayName("a return that names a drawer which has closed is refunded and counted at no drawer")
  void aReturnNamingAClosedDrawerIsNotAtATill() {
    UUID t = drawerAt(store);
    UUID order = Ids.newId();
    tender(order, "CASH", "30.00", store, t);
    close(t, "130.00");

    handler.handle(returned(order, "4.00", drawer(t)));
    handler.handle(voided(Ids.newId(), order, drawer(t)));

    assertThat(refundsOf(order), is("CASH 4.00 -;CASH 26.00 -"));
    sameMoney("the closed drawer is as it was counted", x(t).expectedCashInTill(), "130.00");
  }

  // ── an exchange ────────────────────────────────────────────────────────────

  private String exchanged(UUID order, UUID newOrder, String drawerMember) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"OrderReturned\",\"tenantId\":\""
        + biz
        + "\",\"orderId\":\""
        + order
        + "\",\"returnId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + store
        + "\",\"refundAmount\":30.00,\"refundMethod\":\"EXCHANGE\",\"currency\":\"GBP\","
        + "\"exchangeOrderId\":\""
        + newOrder
        + "\",\"exchangeAmount\":18.00,\"items\":[]"
        + drawerMember
        + "}";
  }

  @Test
  @DisplayName("the cash back from an exchange leaves the drawer the exchange was rung on")
  void anExchangesCashBackLeavesTheDrawerThatGaveIt() {
    UUID t = drawerAt(store);
    UUID order = Ids.newId();
    UUID newOrder = Ids.newId();
    tender(order, "CASH", "30.00", store, t);

    handler.handle(exchanged(order, newOrder, drawer(t)));

    // 18 of the 30 pays for the new basket, 12 goes back as cash: out of this drawer
    assertThat(refundsOf(order), is("CASH 12.00 " + t + ";EXCHANGE 18.00 " + t));
    assertThat(tendersOf(newOrder), is("EXCHANGE 18.00 " + t));
    sameMoney("the drawer holds float + 30 - 12", x(t).expectedCashInTill(), "118.00");
    assertThat(
        "nothing of it is left apart",
        x(t).notAtTill().isEmpty() || x(t).notAtTill().get("CASH") == null,
        is(true));
  }

  @Test
  @DisplayName("an exchange naming no drawer, or another store's, is still made; the drawer is not")
  void anExchangeIsNeverRefusedOverItsDrawer() {
    UUID t = drawerAt(store);
    UUID atElsewhere = drawerAt(elsewhere);
    UUID order = Ids.newId();
    UUID other = Ids.newId();
    tender(order, "CASH", "30.00", store, t);
    tender(other, "CASH", "30.00", store, t);

    handler.handle(exchanged(order, Ids.newId(), ""));
    handler.handle(exchanged(other, Ids.newId(), drawer(atElsewhere)));

    assertThat(refundsOf(order), is("CASH 12.00 -;EXCHANGE 18.00 -"));
    assertThat(refundsOf(other), is("CASH 12.00 -;EXCHANGE 18.00 -"));
  }

  @Test
  @DisplayName("the same exchange delivered twice moves its money once")
  void anExchangeReplayedMovesItsMoneyOnce() {
    UUID t = drawerAt(store);
    UUID order = Ids.newId();
    UUID newOrder = Ids.newId();
    tender(order, "CASH", "30.00", store, t);
    String event = exchanged(order, newOrder, drawer(t));

    handler.handle(event);
    handler.handle(event);

    assertThat(refundsOf(order), is("CASH 12.00 " + t + ";EXCHANGE 18.00 " + t));
    sameMoney("the drawer", x(t).expectedCashInTill(), "118.00");
  }

  // ── a gift card rung at the till ───────────────────────────────────────────

  private String giftCardRedeemed(UUID order, String amount, UUID at, String drawerMember) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"GiftCardRedeemed\",\"tenantId\":\""
        + biz
        + "\",\"redemptionId\":\""
        + Ids.newId()
        + "\",\"giftCardId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + order
        + "\",\"storeId\":\""
        + at
        + "\",\"amount\":"
        + amount
        + ",\"currency\":\"GBP\""
        + drawerMember
        + "}";
  }

  @Test
  @DisplayName("a gift card charged at the till is a tender of the drawer it was rung on")
  void aGiftCardTenderIsInItsDrawer() {
    UUID t = drawerAt(store);
    UUID order = Ids.newId();

    handler.handle(giftCardRedeemed(order, "8.00", store, drawer(t)));

    assertThat(tendersOf(order), is("GIFT_CARD 8.00 " + t));
    var gift = x(t).tenderSummary().get("GIFT_CARD");
    sameMoney("the drawer's gift card sales", gift.sales(), "8.00");
    sameMoney("a gift card moves no cash", x(t).expectedCashInTill(), "100.00");
  }

  @Test
  @DisplayName("a gift card tender is recorded, naming no drawer, when the drawer cannot be its")
  void aGiftCardTenderIsNeverRefusedOverItsDrawer() {
    UUID t = drawerAt(store);
    UUID atElsewhere = drawerAt(elsewhere);
    UUID closed = drawerAt(store);
    close(closed, "100.00");
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    UUID c = Ids.newId();
    UUID d = Ids.newId();

    handler.handle(giftCardRedeemed(a, "1.00", store, ""));
    handler.handle(giftCardRedeemed(b, "2.00", store, drawer(atElsewhere)));
    handler.handle(giftCardRedeemed(c, "3.00", store, drawer(closed)));
    handler.handle(giftCardRedeemed(d, "4.00", store, drawer(Ids.newId())));

    assertThat(tendersOf(a), is("GIFT_CARD 1.00 -"));
    assertThat(tendersOf(b), is("GIFT_CARD 2.00 -"));
    assertThat(tendersOf(c), is("GIFT_CARD 3.00 -"));
    assertThat(tendersOf(d), is("GIFT_CARD 4.00 -"));
    sameMoney("the open drawer holds none of it", x(t).expectedCashInTill(), "100.00");
  }
}
