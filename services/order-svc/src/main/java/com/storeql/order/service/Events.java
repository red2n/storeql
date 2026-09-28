package com.storeql.order.service;

import static com.storeql.events.EventPayload.esc;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.GiftCard;
import com.storeql.order.domain.Domain.GiftCardTransaction;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.ReturnItem;
import com.storeql.order.domain.RecallNotice.Line;
import com.storeql.order.domain.RecallNotice.Notice;
import com.storeql.service.OutboxRow;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds {@link OutboxRow} instances for all events published by order-svc. Request-supplied
 * strings (channel, cancel reason) are escaped — they must not be able to corrupt event JSON.
 * Public for the one builder a consumer in another package needs; the rest stay package-private.
 */
public final class Events {

  static final String TOPIC_RECALL_NOTICE_ISSUED = "storeql.order.recall-notice-issued";
  static final String TOPIC_GIFT_CARD_LOADED = "storeql.order.gift-card-loaded";

  private Events() {}

  /**
   * A recall notice issued to a buyer this service could identify (05.10): everything
   * notification-svc needs to write to them under GPSR art.36, and the ids it resolves an address
   * from. Built with the JSON API because the notice is free text with line breaks in it.
   */
  public static OutboxRow recallNoticeIssued(Notice n, List<Line> lines) {
    JsonArrayBuilder remedies = Json.createArrayBuilder();
    n.remedies().stream().map(Enum::name).sorted().forEach(remedies::add);
    JsonArrayBuilder items = Json.createArrayBuilder();
    for (Line l : lines) {
      JsonObjectBuilder line =
          Json.createObjectBuilder().add("variantId", l.variantId().toString()).add("qty", l.qty());
      nullable(line, "productName", l.productName());
      nullable(line, "sku", l.sku());
      nullable(line, "batchNo", l.batchNo());
      nullable(line, "expiryDate", l.expiryDate() == null ? null : l.expiryDate().toString());
      items.add(line);
    }
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "RecallNoticeIssued")
            .add("tenantId", n.tenantId().toString())
            .add("aggregateId", n.id().toString())
            .add("occurredAt", Instant.now().toString())
            .add("noticeId", n.id().toString())
            .add("recallId", n.recallId().toString())
            .add("reference", n.reference())
            .add("hazard", n.hazard())
            .add("reason", n.reason())
            .add("customerNotice", n.customerNotice())
            .add("remedies", remedies)
            .add("orderId", n.orderId().toString())
            .add("storeId", n.storeId().toString())
            .add("channel", n.channel())
            .add("soldAt", n.soldAt().toString())
            .add("lines", items);
    nullable(b, "singleRemedyReason", n.singleRemedyReason());
    nullable(b, "contactPhone", n.contactPhone());
    nullable(b, "contactUrl", n.contactUrl());
    nullable(b, "customerId", n.customerId() == null ? null : n.customerId().toString());
    nullable(b, "loginId", n.loginId() == null ? null : n.loginId().toString());
    nullable(b, "buyerPhone", n.buyerPhone());
    return new OutboxRow(
        "RecallNoticeIssued",
        TOPIC_RECALL_NOTICE_ISSUED,
        n.tenantId(),
        n.id(),
        b.build().toString());
  }

  /**
   * A gift card issued or reloaded, and how it was paid for (17.11): purchase-svc posts the money
   * taken, or the value given away, against the gift card liability, once per card transaction.
   */
  static OutboxRow giftCardLoaded(GiftCard gc, GiftCardTransaction tx, String paidBy) {
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "GiftCardLoaded")
            .add("tenantId", gc.tenantId().toString())
            .add("giftCardId", gc.id().toString())
            .add("transactionId", tx.id().toString())
            .add("kind", tx.txType())
            .add("amount", tx.amount())
            .add("currency", gc.currency())
            .add("paidBy", paidBy);
    nullable(b, "storeId", gc.storeId() == null ? null : gc.storeId().toString());
    return new OutboxRow(
        "GiftCardLoaded", TOPIC_GIFT_CARD_LOADED, gc.tenantId(), gc.id(), b.build().toString());
  }

  private static void nullable(JsonObjectBuilder b, String name, String value) {
    if (value == null) {
      b.addNull(name);
    } else {
      b.add(name, value);
    }
  }

  /**
   * Deposits paid back at the till for containers brought back (09.16): payment-svc records the
   * cash leaving the drawer against the till session, once per event.
   */
  static OutboxRow containerDepositRefunded(com.storeql.order.domain.Domain.ContainerRefund r) {
    return new OutboxRow(
        "ContainerDepositRefunded",
        "storeql.order.container-deposit-refunded",
        r.tenantId(),
        r.id(),
        "{\"eventType\":\"ContainerDepositRefunded\",\"eventId\":\""
            + r.id()
            + "\",\"tenantId\":\""
            + r.tenantId()
            + "\",\"storeId\":\""
            + r.storeId()
            + "\",\"tillSessionId\":\""
            + r.tillSessionId()
            + "\",\"refundedBy\":\""
            + r.refundedBy()
            + "\",\"currency\":\""
            + esc(r.currency())
            + "\",\"containers\":"
            + r.containers()
            + ",\"amount\":"
            + r.amount().toPlainString()
            + "}");
  }

  static OutboxRow orderPlaced(
      UUID tenantId, UUID orderId, String channel, UUID customerId, UUID loginId, UUID storeId) {
    return orderPlaced(tenantId, orderId, channel, customerId, loginId, storeId, null);
  }

  /**
   * As above, naming the checkout the order is a part of when a delivery was split across stores
   * (order orchestration); an order never split carries no {@code groupId}.
   */
  static OutboxRow orderPlaced(
      UUID tenantId,
      UUID orderId,
      String channel,
      UUID customerId,
      UUID loginId,
      UUID storeId,
      UUID groupId) {
    return orderPlaced(
        tenantId, orderId, channel, customerId, loginId, storeId, groupId, null, null, null);
  }

  /**
   * As above, naming the delivery or collection window the order holds, when it has one (delivery
   * and collection slots); an order with no window (a till sale, or a store with none) carries
   * none.
   */
  static OutboxRow orderPlaced(
      UUID tenantId,
      UUID orderId,
      String channel,
      UUID customerId,
      UUID loginId,
      UUID storeId,
      UUID groupId,
      Instant slotStartsAt,
      Instant slotEndsAt,
      String slotTimeZone) {
    String customerPart =
        customerId != null ? ",\"customerId\":\"" + customerId + "\"" : ",\"customerId\":null";
    // Both ids, because consumers key on different ones: loyalty wants the shop's customer record,
    // while cart-svc holds a shopper's basket under the login their token carries. Until SJ-D44
    // these were the same value in this payload, and the confusion was invisible.
    String loginPart = loginId != null ? ",\"loginId\":\"" + loginId + "\"" : ",\"loginId\":null";
    return new OutboxRow(
        "OrderPlaced",
        "storeql.order.order-placed",
        tenantId,
        orderId,
        "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
            + tenantId
            + "\",\"orderId\":\""
            + orderId
            + "\",\"channel\":\""
            + esc(channel)
            + "\",\"storeId\":\""
            + storeId
            + "\""
            + customerPart
            + loginPart
            + (groupId != null ? ",\"groupId\":\"" + groupId + "\"" : "")
            + slotFields(slotStartsAt, slotEndsAt, slotTimeZone)
            + "}");
  }

  /**
   * The three fields OrderPlaced and OrderConfirmed carry only when the order holds a delivery or
   * collection window (delivery and collection slots) — absent, not JSON null, so an order with no
   * window looks exactly as it did before this feature existed.
   */
  private static String slotFields(Instant slotStartsAt, Instant slotEndsAt, String slotTimeZone) {
    if (slotStartsAt == null || slotEndsAt == null || slotTimeZone == null) {
      return "";
    }
    return ",\"slotStartsAt\":\""
        + slotStartsAt
        + "\",\"slotEndsAt\":\""
        + slotEndsAt
        + "\",\"slotTimeZone\":\""
        + esc(slotTimeZone)
        + "\"";
  }

  /**
   * OrderConfirmed carries an {@code eventId} (consumer dedupe) plus the buyer and settled amount
   * so downstream consumers can react to the sale without a callback to order-svc — customer-svc
   * accrues loyalty from {@code customerId}/{@code total} (guest orders send {@code
   * customerId:null} and earn nothing), and purchase-svc posts the sale to the ledger from {@code
   * total} and {@code taxAmount} (17.7), and reporting-svc records the sale line by line from
   * {@code lines} — each line's variant, quantity, unit price and money — for sales by category
   * (19.x). Emitted exactly once, at full payment (see OrderRepository.applyPaymentCaptured).
   */
  static OutboxRow orderConfirmed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal total,
      BigDecimal taxAmount,
      String currency,
      List<OrderItem> lines) {
    return orderConfirmed(
        tenantId,
        orderId,
        storeId,
        channel,
        customerId,
        total,
        taxAmount,
        currency,
        lines,
        null,
        null,
        null,
        null);
  }

  /**
   * As above, saying how the order is fulfilled and where a delivery goes: purchase-svc raises a
   * dropship supplier's order from this event (consignment and dropship stock ownership), shipped
   * to the customer, so the address rides on the event rather than in a call back to order-svc. The
   * four fields are always present; a till sale carries them as JSON null.
   */
  static OutboxRow orderConfirmed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal total,
      BigDecimal taxAmount,
      String currency,
      List<OrderItem> lines,
      String fulfilmentType,
      String deliveryAddress,
      String deliveryRecipientName,
      String deliveryRecipientPhone) {
    return orderConfirmed(
        tenantId,
        orderId,
        storeId,
        channel,
        customerId,
        total,
        taxAmount,
        currency,
        lines,
        fulfilmentType,
        deliveryAddress,
        deliveryRecipientName,
        deliveryRecipientPhone,
        null,
        null,
        null);
  }

  /**
   * As above, naming the delivery or collection window the order holds, when it has one (delivery
   * and collection slots): notification-svc says the window in the confirmation; an order with no
   * window carries none.
   */
  static OutboxRow orderConfirmed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal total,
      BigDecimal taxAmount,
      String currency,
      List<OrderItem> lines,
      String fulfilmentType,
      String deliveryAddress,
      String deliveryRecipientName,
      String deliveryRecipientPhone,
      Instant slotStartsAt,
      Instant slotEndsAt,
      String slotTimeZone) {
    String customerPart = customerId != null ? "\"" + customerId + "\"" : "null";
    String amount = total != null ? total.toPlainString() : "0";
    // The VAT inside the total, so the ledger can post revenue net of it (17.7).
    String tax = taxAmount != null ? taxAmount.toPlainString() : "0";
    String cur = java.util.Objects.requireNonNull(currency, "an order always carries its currency");
    return new OutboxRow(
        "OrderConfirmed",
        "storeql.order.order-confirmed",
        tenantId,
        orderId,
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"OrderConfirmed\",\"occurredAt\":\""
            // When the order was confirmed, on the confirmation's own transaction: inventory-svc
            // lists the orders waiting to be picked by it, so two confirmations that ride different
            // partitions and arrive in either order still wait in the order they happened.
            + Instant.now()
            + "\",\"tenantId\":\""
            + tenantId
            + "\",\"orderId\":\""
            + orderId
            + "\",\"storeId\":\""
            + storeId
            + "\",\"channel\":\""
            + esc(channel)
            + "\",\"customerId\":"
            + customerPart
            + ",\"total\":"
            + amount
            + ",\"taxAmount\":"
            + tax
            + ",\"currency\":\""
            + esc(cur)
            + "\",\"fulfilmentType\":"
            + jsonText(fulfilmentType)
            + ",\"deliveryAddress\":"
            + jsonText(deliveryAddress)
            + ",\"deliveryRecipientName\":"
            + jsonText(deliveryRecipientName)
            + ",\"deliveryRecipientPhone\":"
            + jsonText(deliveryRecipientPhone)
            + ",\"lines\":"
            + confirmedLines(lines)
            + slotFields(slotStartsAt, slotEndsAt, slotTimeZone)
            + "}");
  }

  /**
   * The sale line by line: what reporting-svc groups by category. Unit price is omitted when
   * unknown.
   */
  /** A JSON string, or JSON null for nothing. */
  private static String jsonText(String value) {
    return value == null || value.isBlank() ? "null" : "\"" + esc(value) + "\"";
  }

  private static String confirmedLines(List<OrderItem> lines) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < lines.size(); i++) {
      OrderItem line = lines.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(line.variantId())
          .append("\",\"qty\":")
          .append(line.qty().toPlainString());
      if (line.unitPrice() != null) {
        sb.append(",\"unitPrice\":").append(line.unitPrice().toPlainString());
      }
      sb.append(",\"lineTotal\":")
          .append(line.lineTotal() != null ? line.lineTotal().toPlainString() : "0")
          .append('}');
    }
    return sb.append(']').toString();
  }

  /**
   * A picked delivery order handed to a carrier (ship-from-store): the shopper is told it is on its
   * way with the carrier and reference; a business's webhooks hear it. The buyer's ids are JSON
   * null for a guest checkout.
   */
  static OutboxRow orderDispatched(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String carrier,
      String reference,
      Integer parcels) {
    return new OutboxRow(
        "OrderDispatched",
        "storeql.order.order-dispatched",
        tenantId,
        orderId,
        handoverPayload("OrderDispatched", tenantId, orderId, storeId, customerId, loginId)
            + ",\"carrier\":"
            + jsonText(carrier)
            + ",\"reference\":"
            + jsonText(reference)
            + ",\"parcels\":"
            + (parcels == null ? "null" : parcels.toString())
            + "}");
  }

  /** A picked pickup order handed to its shopper at the counter (ship-from-store). */
  static OutboxRow orderCollected(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String collectedBy) {
    return new OutboxRow(
        "OrderCollected",
        "storeql.order.order-collected",
        tenantId,
        orderId,
        handoverPayload("OrderCollected", tenantId, orderId, storeId, customerId, loginId)
            + ",\"collectedBy\":"
            + jsonText(collectedBy)
            + "}");
  }

  private static String handoverPayload(
      String type, UUID tenantId, UUID orderId, UUID storeId, UUID customerId, UUID loginId) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + type
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\",\"tenantId\":\""
        + tenantId
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + storeId
        + "\",\"customerId\":"
        + (customerId == null ? "null" : "\"" + customerId + "\"")
        + ",\"loginId\":"
        + (loginId == null ? "null" : "\"" + loginId + "\"");
  }

  /**
   * A line of an online order closed short by the store (substitutions for out-of-stock online
   * lines): the quantity that will never be handed over, what goes back to the shopper, and the
   * order's total now. inventory-svc releases the hold and shortens the waiting line, payment-svc
   * refunds, notification-svc tells the shopper.
   */
  static OutboxRow orderLineShortClosed(
      Order order, UUID variantId, String variantName, BigDecimal qty, BigDecimal refund) {
    return new OutboxRow(
        "OrderLineShortClosed",
        "storeql.order.order-line-short-closed",
        order.tenantId(),
        order.id(),
        adjustmentPayload("OrderLineShortClosed", order)
            + ",\"variantId\":\""
            + variantId
            + "\",\"variantName\":"
            + jsonText(variantName)
            + ",\"qty\":"
            + qty.toPlainString()
            + ",\"refundAmount\":"
            + refund.toPlainString()
            + "}");
  }

  /**
   * A line of an online order replaced by a substitute the store put in the bag: what was swapped
   * for what, how much, what the substitute is charged (never more than the original) and what goes
   * back. The substitute's own deduction rides an {@code OrderFulfilled} beside this.
   */
  static OutboxRow orderLineSubstituted(
      Order order,
      UUID fromVariantId,
      String fromName,
      UUID toVariantId,
      String toName,
      BigDecimal qty,
      BigDecimal charged,
      BigDecimal refund) {
    return new OutboxRow(
        "OrderLineSubstituted",
        "storeql.order.order-line-substituted",
        order.tenantId(),
        order.id(),
        adjustmentPayload("OrderLineSubstituted", order)
            + ",\"fromVariantId\":\""
            + fromVariantId
            + "\",\"fromName\":"
            + jsonText(fromName)
            + ",\"toVariantId\":\""
            + toVariantId
            + "\",\"toName\":"
            + jsonText(toName)
            + ",\"qty\":"
            + qty.toPlainString()
            + ",\"chargedAmount\":"
            + charged.toPlainString()
            + ",\"refundAmount\":"
            + refund.toPlainString()
            + "}");
  }

  private static String adjustmentPayload(String type, Order order) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + type
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\",\"tenantId\":\""
        + order.tenantId()
        + "\",\"orderId\":\""
        + order.id()
        + "\",\"storeId\":\""
        + order.storeId()
        + "\",\"customerId\":"
        + (order.customerId() == null ? "null" : "\"" + order.customerId() + "\"")
        + ",\"loginId\":"
        + (order.loginId() == null ? "null" : "\"" + order.loginId() + "\"")
        + ",\"currency\":"
        + jsonText(order.currency())
        + ",\"orderTotal\":"
        + (order.total() == null ? "0" : order.total().toPlainString())
        + kind(order.channel(), order.fulfilmentType());
  }

  static OutboxRow orderCancelled(UUID tenantId, UUID orderId, String reason) {
    return orderCancelled(tenantId, orderId, reason, null, null);
  }

  /**
   * As above, saying which kind of order was cancelled ({@code channel}, {@code fulfilmentType}):
   * inventory-svc's waiting list (wave picking) remembers a cancelled online pickup or delivery
   * order as done, so its confirmation arriving late cannot make it wait again, and remembers
   * nothing for a till sale.
   */
  static OutboxRow orderCancelled(
      UUID tenantId, UUID orderId, String reason, String channel, String fulfilmentType) {
    // eventId lets payment-svc dedupe the automatic refund of a cancelled (paid) order; existing
    // consumers (inventory-svc hold release) ignore the extra fields.
    return new OutboxRow(
        "OrderCancelled",
        "storeql.order.order-cancelled",
        tenantId,
        orderId,
        String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"OrderCancelled\",\"tenantId\":\"%s\","
                + "\"orderId\":\"%s\",\"reason\":\"%s\"%s}",
            Ids.newId(), tenantId, orderId, esc(reason), kind(channel, fulfilmentType)));
  }

  /** The {@code channel} and {@code fulfilmentType} members, when known; nothing when not. */
  private static String kind(String channel, String fulfilmentType) {
    return (channel == null ? "" : ",\"channel\":\"" + esc(channel) + "\"")
        + (fulfilmentType == null ? "" : ",\"fulfilmentType\":\"" + esc(fulfilmentType) + "\"");
  }

  static OutboxRow orderFulfilled(
      UUID tenantId, UUID orderId, UUID storeId, List<OrderItem> items) {
    return orderFulfilled(tenantId, orderId, storeId, items, java.util.Map.of(), 2);
  }

  /**
   * OrderFulfilled with each line's revenue, net of VAT and of the order's discounts ({@code
   * netAmount}), so inventory-svc can set it against the cost of the batches the line draws down
   * (19.7). A line with no price known carries none, and the gross-margin report counts it as
   * unpriced rather than as free.
   *
   * @param unitNet net revenue per unit by variant, from {@code LineRevenue.unitNet}
   * @param scale the currency's minor-unit digits
   */
  static OutboxRow orderFulfilled(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<OrderItem> items,
      java.util.Map<UUID, BigDecimal> unitNet,
      int scale) {
    return orderFulfilled(
        tenantId, orderId, storeId, items, unitNet, scale, java.util.Map.of(), null, null, null);
  }

  /**
   * As above, saying what each line still has outstanding after this handover ({@code
   * outstandingQty}), whether the order is now {@code FULFILLED} or {@code PARTIALLY_FULFILLED}
   * ({@code status}), and which kind of order it is ({@code channel}, {@code fulfilmentType}).
   * inventory-svc's waiting list (wave picking) is set from these absolute figures, so a
   * redelivered or reordered event states the same truth instead of subtracting twice, and a
   * fulfilled online pickup or delivery order leaves the list for good; a till sale leaves nothing.
   *
   * @param outstanding what is still to hand over per variant, after this handover
   * @param status the order's status after this handover, or null to say nothing
   * @param channel the order's channel, or null to say nothing
   * @param fulfilmentType the order's fulfilment type, or null to say nothing
   */
  static OutboxRow orderFulfilled(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<OrderItem> items,
      java.util.Map<UUID, BigDecimal> unitNet,
      int scale,
      java.util.Map<UUID, BigDecimal> outstanding,
      String status,
      String channel,
      String fulfilmentType) {
    return orderFulfilled(
        tenantId,
        orderId,
        storeId,
        items,
        unitNet,
        scale,
        outstanding,
        status,
        channel,
        fulfilmentType,
        null,
        null);
  }

  /**
   * As above, naming the buyer ({@code customerId}, the shop's record; {@code loginId}, the login
   * that placed it), so notification-svc can tell a shopper their pickup is ready for collection
   * (ship-from-store and dark-store picking) without a call back to order-svc. Both are JSON null
   * for a guest checkout or a till sale.
   */
  static OutboxRow orderFulfilled(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<OrderItem> items,
      java.util.Map<UUID, BigDecimal> unitNet,
      int scale,
      java.util.Map<UUID, BigDecimal> outstanding,
      String status,
      String channel,
      String fulfilmentType,
      UUID customerId,
      UUID loginId) {
    // eventId is required by inventory-svc's OrderEventHandler for per-line dedupe — without it,
    // every OrderFulfilled is dropped as a malformed event and stock is never deducted.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(Ids.newId())
        .append("\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append('"');
    if (status != null) sb.append(",\"status\":\"").append(esc(status)).append('"');
    sb.append(kind(channel, fulfilmentType));
    sb.append(",\"customerId\":")
        .append(customerId == null ? "null" : "\"" + customerId + "\"")
        .append(",\"loginId\":")
        .append(loginId == null ? "null" : "\"" + loginId + "\"");
    sb.append(",\"items\":[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(items.get(i).variantId())
          .append("\",\"qty\":")
          .append(items.get(i).qty().toPlainString());
      BigDecimal left = outstanding.get(items.get(i).variantId());
      if (left != null) sb.append(",\"outstandingQty\":").append(left.toPlainString());
      BigDecimal net =
          com.storeql.order.domain.LineRevenue.forQty(
              unitNet, items.get(i).variantId(), items.get(i).qty(), scale);
      if (net != null) sb.append(",\"netAmount\":").append(net.toPlainString());
      sb.append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderFulfilled", "storeql.order.order-fulfilled", tenantId, orderId, sb.toString());
  }

  static OutboxRow orderReturned(
      UUID tenantId,
      UUID orderId,
      UUID returnId,
      UUID storeId,
      List<ReturnItem> items,
      BigDecimal refundAmount,
      String refundMethod,
      String currency) {
    // eventId is required by inventory-svc's OrderEventHandler for per-line dedupe — without it
    // every OrderReturned is dropped as malformed and stock is never restocked. refundAmount +
    // refundMethod let payment-svc reverse the captured payment for ORIGINAL-tender returns.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(Ids.newId())
        .append("\",\"eventType\":\"OrderReturned\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"returnId\":\"")
        .append(returnId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"refundAmount\":")
        .append(refundAmount != null ? refundAmount.toPlainString() : "0")
        .append(",\"refundMethod\":\"")
        .append(esc(refundMethod))
        .append("\",\"currency\":\"")
        .append(esc(currency))
        .append("\",\"items\":[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(items.get(i).variantId())
          .append("\",\"qty\":")
          .append(items.get(i).qty().toPlainString())
          .append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderReturned", "storeql.order.order-returned", tenantId, orderId, sb.toString());
  }

  static OutboxRow orderVoided(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      List<com.storeql.order.domain.Domain.RestockLine> restock) {
    // SJ-D40 made a paid till sale deduct stock, so voiding one must put the stock back. items is
    // what to put back: each line net of anything already returned, or empty when the sale was
    // never handed over and nothing was deducted. eventId and storeId are what inventory-svc's
    // OrderEventHandler needs to restock and dedupe per line, exactly as for OrderReturned.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(Ids.newId())
        .append("\",\"eventType\":\"OrderVoided\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"items\":[");
    for (int i = 0; i < restock.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(restock.get(i).variantId())
          .append("\",\"qty\":")
          .append(restock.get(i).qty().toPlainString())
          .append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderVoided", "storeql.order.order-voided", tenantId, orderId, sb.toString());
  }

  static OutboxRow layawayCreated(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCreated",
        "storeql.order.layaway-created",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCreated\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }

  static OutboxRow layawayCompleted(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCompleted",
        "storeql.order.layaway-completed",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCompleted\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }

  static OutboxRow layawayCancelled(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCancelled",
        "storeql.order.layaway-cancelled",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCancelled\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }
}
