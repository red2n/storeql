package com.storeql.payment.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.storeql.ids.Ids;
import com.storeql.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OrderEventHandler drives the automatic-refund path. OrderReturned refunds only for
 * ORIGINAL-tender returns; OrderCancelled refunds whatever is still captured (null amount);
 * other/malformed events are skipped without throwing so the consumer loop acks them. payment-svc
 * has no mocking framework on the test classpath, so a capturing subclass stands in for {@link
 * PaymentService}.
 */
class OrderEventHandlerTest {

  private static final UUID EVENT = Ids.newId();
  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final class CapturingPaymentService extends PaymentService {
    int calls;
    UUID eventId;
    String consumer;
    UUID tenantId;
    UUID orderId;
    BigDecimal amount;
    String reason;
    String kind;

    @Override
    public void refundForOrderEvent(
        UUID eventId,
        String consumer,
        UUID tenantId,
        UUID orderId,
        BigDecimal requestedAmount,
        String reason,
        String kind) {
      this.calls++;
      this.eventId = eventId;
      this.consumer = consumer;
      this.tenantId = tenantId;
      this.orderId = orderId;
      this.amount = requestedAmount;
      this.reason = reason;
      this.kind = kind;
    }
  }

  private CapturingPaymentService service;
  private OrderEventHandler handler;

  @BeforeEach
  void setUp() {
    service = new CapturingPaymentService();
    handler = new OrderEventHandler();
    handler.service = service;
  }

  private static String returned(String refundMethod, String refundAmount) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"OrderReturned\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"returnId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"refundAmount\":"
        + refundAmount
        + ",\"refundMethod\":\""
        + refundMethod
        + "\",\"currency\":\"GBP\",\"items\":[]}";
  }

  @Test
  void originalTenderReturnRefundsTheReturnAmount() {
    handler.handle(returned("ORIGINAL", "25.00"));

    assertEquals(1, service.calls);
    assertEquals(EVENT, service.eventId);
    assertEquals(OrderEventHandler.CONSUMER_NAME, service.consumer);
    assertEquals(TENANT, service.tenantId);
    assertEquals(ORDER, service.orderId);
    assertEquals(new BigDecimal("25.00"), service.amount);
    assertNull(service.kind, "a return's refund moves the order's status");
  }

  @Test
  void storeCreditReturnDoesNotReversePayment() {
    handler.handle(returned("STORE_CREDIT", "25.00"));

    assertEquals(0, service.calls);
  }

  @Test
  void cancelRefundsAllRemainingCaptured() {
    String cancelled =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderCancelled\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"reason\":\"changed mind\"}";

    handler.handle(cancelled);

    assertEquals(1, service.calls);
    assertEquals(EVENT, service.eventId);
    // null requestedAmount => "refund whatever is still captured".
    assertNull(service.amount);
  }

  @Test
  void unrelatedEventIsIgnored() {
    handler.handle(
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\"}");

    assertEquals(0, service.calls);
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    handler.handle("{not valid json");

    assertEquals(0, service.calls);
  }

  // ── substitutions for out-of-stock online lines ────────────────────────────

  private static String lineEvent(String type, String refundAmount) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\""
        + type
        + "\",\"occurredAt\":\"2026-09-25T10:00:00Z\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"customerId\":null,\"loginId\":null,\"currency\":\"GBP\",\"orderTotal\":10.00,"
        + "\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\",\"variantId\":\""
        + Ids.newId()
        + "\",\"fromVariantId\":\""
        + Ids.newId()
        + "\",\"toVariantId\":\""
        + Ids.newId()
        + "\",\"variantName\":\"Apples\",\"qty\":1,\"chargedAmount\":8.00,\"refundAmount\":"
        + refundAmount
        + "}";
  }

  /**
   * A line closed short refunds what the shopper paid for it, and a cheaper substitute the
   * difference — each marked an adjustment so order-svc keeps the order's status; a substitute
   * charged the same refunds nothing.
   */
  @Test
  void aLineClosedShortOrSubstitutedRefundsTheDifferenceAsAnAdjustment() {
    handler.handle(lineEvent("OrderLineShortClosed", "10.00"));
    assertEquals(1, service.calls);
    assertEquals(EVENT, service.eventId);
    assertEquals(new BigDecimal("10.00"), service.amount);
    assertEquals("Line closed short", service.reason);
    assertEquals(OrderEventHandler.ADJUSTMENT_KIND, service.kind);

    handler.handle(lineEvent("OrderLineSubstituted", "2.00"));
    assertEquals(2, service.calls);
    assertEquals(new BigDecimal("2.00"), service.amount);
    assertEquals("Line substituted", service.reason);
    assertEquals(OrderEventHandler.ADJUSTMENT_KIND, service.kind);

    handler.handle(lineEvent("OrderLineSubstituted", "0.00"));
    handler.handle(lineEvent("OrderLineShortClosed", "0"));
    assertEquals(2, service.calls, "nothing to give back, nothing refunded");
  }

  // ── container deposit refunds (09.16) ──────────────────────────────

  /** Captures what the handler asks the drawer to record. */
  static final class CapturingCashMovements
      extends com.storeql.payment.repo.CashMovementRepository {
    final java.util.List<String> recorded = new java.util.ArrayList<>();

    @Override
    public com.storeql.payment.dto.Dtos.CashMovementResponse insertMovement(
        UUID tenantId,
        UUID storeId,
        UUID tillSessionId,
        String direction,
        BigDecimal amount,
        String reason,
        UUID authorisedBy,
        UUID recordedBy,
        String idempotencyKey) {
      recorded.add(direction + " " + amount + " " + tillSessionId + " " + idempotencyKey);
      return null;
    }
  }

  private static String containerRefund(String amount) {
    return "{\"eventType\":\"ContainerDepositRefunded\",\"eventId\":\""
        + EVENT
        + "\",\"tenantId\":\""
        + TENANT
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"tillSessionId\":\"01a090ae-611e-7035-a4da-400bf673cfe8\",\"refundedBy\":\""
        + Ids.newId()
        + "\",\"currency\":\"EUR\",\"containers\":3,\"amount\":"
        + amount
        + "}";
  }

  @Test
  void containerRefundLeavesTheDrawerAsAPayOutKeyedOnTheEvent() {
    var drawer = new CapturingCashMovements();
    handler.cashMovements = drawer;
    handler.handle(containerRefund("0.75"));
    assertEquals(1, drawer.recorded.size());
    assertEquals(
        "PAY_OUT 0.75 01a090ae-611e-7035-a4da-400bf673cfe8 " + Ids.derived(EVENT, "deposit-refund"),
        drawer.recorded.get(0));
    assertNull(service.eventId, "a deposit refund is no order refund");
  }

  @Test
  void aZeroOrNegativeContainerRefundRecordsNothing() {
    var drawer = new CapturingCashMovements();
    handler.cashMovements = drawer;
    handler.handle(containerRefund("0"));
    handler.handle(containerRefund("-1.00"));
    assertEquals(0, drawer.recorded.size());
  }

  @Test
  void aMalformedContainerRefundIsSkippedNotThrown() {
    var drawer = new CapturingCashMovements();
    handler.cashMovements = drawer;
    handler.handle("{\"eventType\":\"ContainerDepositRefunded\",\"amount\":\"lots\"}");
    handler.handle("{\"eventType\":\"ContainerDepositRefunded\"}");
    assertEquals(0, drawer.recorded.size());
  }
}
