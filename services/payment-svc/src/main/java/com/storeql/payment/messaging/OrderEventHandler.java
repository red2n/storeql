package com.storeql.payment.messaging;

import com.storeql.ids.Ids;
import com.storeql.payment.service.PaymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Automatic refunds in response to order events. {@code OrderReturned} refunds the return amount
 * (to the ORIGINAL tenders, or recorded as a STORE_CREDIT / GIFT_CARD refund whose value goes to a
 * liability); {@code OrderCancelled} refunds whatever is still captured (unpaid pay-later
 * cancellations are a no-op). Both are idempotent on the order event's {@code eventId} inside
 * {@link PaymentService#refundForOrderEvent}. Separated from {@link OrderEventConsumer} so Kafka
 * lifecycle and domain logic each change for one reason (SRP).
 *
 * <p>Malformed payloads are logged and skipped (they will never parse on redelivery); a failed
 * refund write propagates so the consumer loop redelivers, and the eventId dedupe keeps that safe.
 */
@ApplicationScoped
class OrderEventHandler {

  private static final Logger LOG = System.getLogger(OrderEventHandler.class.getName());
  static final String CONSUMER_NAME = "payment-svc/order-refund";
  static final String REFUND_METHOD_ORIGINAL = "ORIGINAL";

  /** The kind on a refund for a line closed short or substituted: order-svc keeps the status. */
  static final String ADJUSTMENT_KIND = "ORDER_ADJUSTMENT";

  @Inject PaymentService service;
  @Inject com.storeql.payment.repo.CashMovementRepository cashMovements;

  void handle(String json) {
    if (json.contains("\"ContainerDepositRefunded\"")) {
      handleContainerRefund(json);
      return;
    }
    if (json.contains("\"GiftCardRedeemed\"")) {
      handleGiftCardRedeemed(json);
      return;
    }
    String eventType;
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    PaymentService.ExchangeReturn exchange = null;
    BigDecimal requestedAmount; // null => cancellation: refund all remaining captured
    String reason;
    String kind = null;
    PaymentService.ReturnRefund returnRefund = null;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventType = obj.getString("eventType", null);
      if ("OrderReturned".equals(eventType)) {
        // ORIGINAL reverses the captured tenders; STORE_CREDIT / GIFT_CARD are recorded as a
        // refund of that method (the value goes to a liability). Anything else is not ours.
        String method = obj.getString("refundMethod", REFUND_METHOD_ORIGINAL);
        if (!REFUND_METHOD_ORIGINAL.equals(method)
            && !"STORE_CREDIT".equals(method)
            && !"GIFT_CARD".equals(method)
            && !"EXCHANGE".equals(method)) {
          return;
        }
        if ("EXCHANGE".equals(method)) {
          exchange = exchangeOf(obj);
          if (exchange == null) {
            LOG.log(Level.WARNING, "Exchange return without an exchange order skipped");
            return;
          }
        }
        returnRefund =
            new PaymentService.ReturnRefund(
                method,
                obj.containsKey("returnId") && !obj.isNull("returnId")
                    ? Ids.parse(obj.getString("returnId"))
                    : null,
                obj.containsKey("customerId") && !obj.isNull("customerId")
                    ? Ids.parse(obj.getString("customerId"))
                    : null,
                obj.containsKey("currency") && !obj.isNull("currency")
                    ? obj.getString("currency")
                    : null);
        requestedAmount = obj.getJsonNumber("refundAmount").bigDecimalValue();
        reason = "Return refund";
      } else if ("OrderCancelled".equals(eventType)) {
        requestedAmount = null;
        reason = "Order cancelled";
      } else if ("OrderLineShortClosed".equals(eventType)
          || "OrderLineSubstituted".equals(eventType)) {
        // Substitutions for out-of-stock online lines: what the shopper paid for what they will
        // not get — or the difference to a cheaper substitute — goes back; a substitute charged
        // the same refunds nothing. order-svc has already lowered the order's total by as much,
        // so the refund is marked an adjustment and moves no status there.
        requestedAmount = obj.getJsonNumber("refundAmount").bigDecimalValue();
        if (requestedAmount.signum() <= 0) {
          return;
        }
        reason =
            "OrderLineShortClosed".equals(eventType) ? "Line closed short" : "Line substituted";
        kind = ADJUSTMENT_KIND;
      } else {
        return; // not a refund-triggering event
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed order event skipped: " + e.getMessage());
      return;
    }

    if (exchange != null) {
      service.exchangeForOrderEvent(eventId, CONSUMER_NAME, tenantId, orderId, exchange);
      return;
    }
    if (returnRefund != null) {
      service.refundReturnForOrderEvent(
          eventId, CONSUMER_NAME, tenantId, orderId, requestedAmount, reason, returnRefund);
      return;
    }
    service.refundForOrderEvent(
        eventId, CONSUMER_NAME, tenantId, orderId, requestedAmount, reason, kind);
  }

  private static PaymentService.ExchangeReturn exchangeOf(JsonObject obj) {
    if (!obj.containsKey("exchangeOrderId") || obj.isNull("exchangeOrderId")) return null;
    BigDecimal refund = obj.getJsonNumber("refundAmount").bigDecimalValue();
    BigDecimal exchanged =
        obj.containsKey("exchangeAmount") && !obj.isNull("exchangeAmount")
            ? obj.getJsonNumber("exchangeAmount").bigDecimalValue()
            : refund;
    // Never more exchanged than returned, never negative.
    exchanged = exchanged.min(refund).max(BigDecimal.ZERO);
    return new PaymentService.ExchangeReturn(
        Ids.parse(obj.getString("exchangeOrderId")),
        obj.containsKey("storeId") && !obj.isNull("storeId")
            ? Ids.parse(obj.getString("storeId"))
            : null,
        exchanged,
        refund,
        obj.containsKey("returnId") && !obj.isNull("returnId")
            ? Ids.parse(obj.getString("returnId"))
            : null,
        obj.containsKey("customerId") && !obj.isNull("customerId")
            ? Ids.parse(obj.getString("customerId"))
            : null,
        obj.containsKey("currency") && !obj.isNull("currency") ? obj.getString("currency") : null);
  }

  /**
   * A gift card charged by order-svc's redeem: the tender follows (once per redemption), so an
   * order is never counted paid by a card that was not charged.
   */
  void handleGiftCardRedeemed(String json) {
    UUID eventId;
    UUID tenantId;
    UUID redemptionId;
    UUID orderId;
    UUID storeId;
    BigDecimal amount;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"GiftCardRedeemed".equals(obj.getString("eventType", null))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      redemptionId = Ids.parse(obj.getString("redemptionId"));
      orderId = Ids.parse(obj.getString("orderId"));
      storeId =
          obj.containsKey("storeId") && !obj.isNull("storeId")
              ? Ids.parse(obj.getString("storeId"))
              : null;
      amount = obj.getJsonNumber("amount").bigDecimalValue();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed gift card event skipped: " + e.getMessage());
      return;
    }
    if (amount.signum() <= 0) return;
    service.recordGiftCardRedemption(
        eventId, CONSUMER_NAME + "/gift-card", tenantId, redemptionId, orderId, storeId, amount);
  }

  /**
   * A deposit refunded at the till for containers brought back (09.16): the cash left the drawer,
   * so the till session carries a pay-out for it, once per event.
   */
  void handleContainerRefund(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID tillSessionId;
    UUID refundedBy;
    BigDecimal amount;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"ContainerDepositRefunded".equals(obj.getString("eventType", null))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      tillSessionId = Ids.parse(obj.getString("tillSessionId"));
      refundedBy = Ids.parse(obj.getString("refundedBy"));
      amount = obj.getJsonNumber("amount").bigDecimalValue();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed container refund event skipped: " + e.getMessage());
      return;
    }
    if (amount.signum() <= 0) return;
    cashMovements.insertMovement(
        tenantId,
        storeId,
        tillSessionId,
        "PAY_OUT",
        amount,
        "Container deposit refund",
        null,
        refundedBy,
        Ids.derived(eventId, "deposit-refund").toString());
  }
}
