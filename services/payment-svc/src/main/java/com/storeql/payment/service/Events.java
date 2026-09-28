package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import java.util.UUID;

/**
 * Builds the outbox rows payment-svc publishes.
 *
 * <p>Each factory returns an {@link OutboxRow} for a caller to write in the same transaction as the
 * state change it announces, so the event and the write commit together.
 */
final class Events {

  private Events() {}

  /**
   * A tender captured against an order. {@code method} is how it was paid (CASH, CARD, UPI, WALLET,
   * STORE_CREDIT, or the online provider's name): order-svc records it per tender because a German
   * fiscal file lists every payment as cash or not, and the security module signs that split
   * (18.5). Null is written as an absent field, which older consumers never read.
   */
  static OutboxRow paymentCaptured(
      UUID tenantId,
      UUID paymentId,
      UUID orderId,
      java.math.BigDecimal amount,
      String method,
      UUID storeId) {
    String methodField =
        method == null || method.isBlank() ? "" : ",\"method\":\"" + clean(method) + "\"";
    // The store the tender was taken at, so the ledger posts it to that store (17.7).
    String storeField = storeId == null ? "" : ",\"storeId\":\"" + storeId + "\"";
    return new OutboxRow(
        "PaymentCaptured",
        "storeql.payment.payment-captured",
        tenantId,
        paymentId,
        String.format(
            "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\"%s\",\"paymentId\":\"%s\","
                + "\"orderId\":\"%s\",\"amount\":%s%s%s}",
            tenantId, paymentId, orderId, amount.toPlainString(), methodField, storeField));
  }

  static OutboxRow paymentFailed(UUID tenantId, UUID paymentId, UUID orderId) {
    return new OutboxRow(
        "PaymentFailed",
        "storeql.payment.payment-failed",
        tenantId,
        paymentId,
        String.format(
            "{\"eventType\":\"PaymentFailed\",\"tenantId\":\"%s\",\"paymentId\":\"%s\",\"orderId\":\"%s\"}",
            tenantId, paymentId, orderId));
  }

  /**
   * PaymentRefunded carries an {@code eventId} (consumer dedupe) and the refunded {@code amount} so
   * order-svc can accumulate it against the order total and flip the order to REFUNDED /
   * PARTIALLY_REFUNDED without a callback. Emitted by both the manual refund endpoint and the
   * automatic order-event refund path.
   */
  static OutboxRow paymentRefunded(
      UUID tenantId,
      UUID refundId,
      UUID orderId,
      java.math.BigDecimal amount,
      java.util.List<com.storeql.payment.domain.Domain.RefundAllocation> tenders) {
    return paymentRefunded(tenantId, refundId, orderId, amount, tenders, null);
  }

  /**
   * As above, saying what kind of refund it is: {@code ORDER_ADJUSTMENT} for a line closed short or
   * substituted (substitutions for out-of-stock online lines), which order-svc records without
   * moving the order's status; null for a return's or a cancellation's, which do.
   */
  static OutboxRow paymentRefunded(
      UUID tenantId,
      UUID refundId,
      UUID orderId,
      java.math.BigDecimal amount,
      java.util.List<com.storeql.payment.domain.Domain.RefundAllocation> tenders,
      String kind) {
    // Each tender's share, so the ledger credits the control account the money left from (17.7).
    StringBuilder shares = new StringBuilder();
    for (var t : tenders) {
      if (shares.length() > 0) shares.append(',');
      shares.append("{\"paymentId\":\"").append(t.paymentId()).append('"');
      if (t.method() != null) shares.append(",\"method\":\"").append(clean(t.method())).append('"');
      if (t.storeId() != null) shares.append(",\"storeId\":\"").append(t.storeId()).append('"');
      shares.append(",\"amount\":").append(t.amount().toPlainString()).append('}');
    }
    return new OutboxRow(
        "PaymentRefunded",
        "storeql.payment.payment-refunded",
        tenantId,
        refundId,
        String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\"%s\","
                + "\"refundId\":\"%s\",\"orderId\":\"%s\",\"amount\":%s,\"tenders\":[%s]%s}",
            Ids.newId(),
            tenantId,
            refundId,
            orderId,
            amount.toPlainString(),
            shares,
            kind == null ? "" : ",\"kind\":\"" + clean(kind) + "\""));
  }

  /**
   * A cardholder's bank has taken a card payment back, or says it will (11.9). The ledger moves the
   * amount out of card clearing into disputed receipts when {@code fundsWithdrawn}, and books the
   * acquirer's fee; notification-svc tells the business and says by when it must answer.
   */
  static OutboxRow disputeOpened(com.storeql.payment.domain.Disputes.Dispute d) {
    return disputeEvent("PaymentDisputeOpened", "storeql.payment.dispute-opened", d, null);
  }

  /** The acquirer has debited a dispute opened without it: the same postings, later. */
  static OutboxRow disputeFundsWithdrawn(com.storeql.payment.domain.Disputes.Dispute d) {
    return disputeEvent(
        "PaymentDisputeFundsWithdrawn", "storeql.payment.dispute-funds-withdrawn", d, null);
  }

  /**
   * A dispute is over. {@code outcome} is WON (the money comes back), LOST or ACCEPTED (it does
   * not).
   */
  static OutboxRow disputeClosed(com.storeql.payment.domain.Disputes.Dispute d, String outcome) {
    return disputeEvent("PaymentDisputeClosed", "storeql.payment.dispute-closed", d, outcome);
  }

  private static OutboxRow disputeEvent(
      String type, String topic, com.storeql.payment.domain.Disputes.Dispute d, String outcome) {
    StringBuilder json = new StringBuilder(320);
    json.append("{\"eventId\":\"").append(com.storeql.ids.Ids.newId()).append('"');
    json.append(",\"eventType\":\"").append(type).append('"');
    json.append(",\"tenantId\":\"").append(d.tenantId()).append('"');
    json.append(",\"disputeId\":\"").append(d.id()).append('"');
    json.append(",\"paymentId\":\"").append(d.paymentId()).append('"');
    json.append(",\"orderId\":\"").append(d.orderId()).append('"');
    if (d.storeId() != null) json.append(",\"storeId\":\"").append(d.storeId()).append('"');
    json.append(",\"amount\":").append(d.amount().toPlainString());
    json.append(",\"feeAmount\":").append(d.feeAmount().toPlainString());
    json.append(",\"currency\":\"").append(clean(d.currency())).append('"');
    json.append(",\"reason\":\"").append(clean(d.reason())).append('"');
    json.append(",\"fundsWithdrawn\":").append(d.fundsWithdrawn());
    if (d.evidenceDueBy() != null) {
      json.append(",\"evidenceDueBy\":\"").append(d.evidenceDueBy()).append('"');
    }
    if (outcome != null) json.append(",\"outcome\":\"").append(clean(outcome)).append('"');
    json.append(",\"occurredAt\":\"").append(java.time.Instant.now()).append("\"}");
    return new OutboxRow(type, topic, d.tenantId(), d.id(), json.toString());
  }

  /**
   * A payout has been reconciled against what this service holds (11.10): the ledger clears card
   * clearing to the bank, store by store, books the acquirer's fees and puts what answered to
   * nothing into unallocated receipts. Each store's four figures are signed so the usual case is
   * positive and {@code bank = clearing + unallocated - fees}.
   */
  static OutboxRow settlementReconciled(
      com.storeql.payment.domain.Settlements.Batch b,
      java.util.List<com.storeql.payment.domain.Settlements.StoreTotals> stores) {
    StringBuilder json = new StringBuilder(480);
    json.append("{\"eventId\":\"").append(com.storeql.ids.Ids.newId()).append('"');
    json.append(",\"eventType\":\"SettlementReconciled\"");
    json.append(",\"tenantId\":\"").append(b.tenantId()).append('"');
    json.append(",\"batchId\":\"").append(b.id()).append('"');
    json.append(",\"provider\":\"").append(clean(b.provider())).append('"');
    json.append(",\"reference\":\"").append(clean(b.reference())).append('"');
    json.append(",\"currency\":\"").append(clean(b.currency())).append('"');
    json.append(",\"payoutDate\":\"").append(b.payoutDate()).append('"');
    json.append(",\"netAmount\":").append(b.netAmount().toPlainString());
    json.append(",\"stores\":[");
    boolean first = true;
    for (com.storeql.payment.domain.Settlements.StoreTotals s : stores) {
      if (!first) json.append(',');
      first = false;
      json.append('{');
      if (s.storeId() != null) json.append("\"storeId\":\"").append(s.storeId()).append("\",");
      json.append("\"bank\":").append(s.bank().toPlainString());
      json.append(",\"fees\":").append(s.fees().toPlainString());
      json.append(",\"clearing\":").append(s.clearing().toPlainString());
      json.append(",\"unallocated\":").append(s.unallocated().toPlainString());
      json.append('}');
    }
    json.append("],\"occurredAt\":\"").append(java.time.Instant.now()).append("\"}");
    return new OutboxRow(
        "SettlementReconciled",
        "storeql.payment.settlement-reconciled",
        b.tenantId(),
        b.id(),
        json.toString());
  }

  /** What is left of a text once nothing in it can end a JSON string early or break one. */
  private static String clean(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch != '\\' && ch != '"' && ch >= ' ') out.append(ch);
    }
    return out.toString();
  }
}
