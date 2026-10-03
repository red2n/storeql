package com.storeql.purchase.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The sale as order-svc and payment-svc announce it, for the ledger (17.7). */
@ApplicationScoped
class SalesEventConsumer extends BaseKafkaConsumer {

  @Inject SalesEventHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.order-confirmed",
      defaultValue = "storeql.order.order-confirmed")
  String orderConfirmed;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.payment-captured",
      defaultValue = "storeql.payment.payment-captured")
  String paymentCaptured;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.payment-refunded",
      defaultValue = "storeql.payment.payment-refunded")
  String paymentRefunded;

  // Chargebacks (11.9): the acquirer taking a card payment back, and how the dispute ended.
  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.dispute-opened",
      defaultValue = "storeql.payment.dispute-opened")
  String disputeOpened;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.dispute-funds-withdrawn",
      defaultValue = "storeql.payment.dispute-funds-withdrawn")
  String disputeFundsWithdrawn;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.dispute-closed",
      defaultValue = "storeql.payment.dispute-closed")
  String disputeClosed;

  // A payout reconciled against the acquirer's settlement file (11.10).
  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.settlement-reconciled",
      defaultValue = "storeql.payment.settlement-reconciled")
  String settlementReconciled;

  // A return with no receipt, announced by order-svc itself.
  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.no-receipt-return-recorded",
      defaultValue = "storeql.order.no-receipt-return-recorded")
  String noReceiptReturn;

  @Override
  protected List<String> topics() {
    return List.of(
        orderConfirmed,
        paymentCaptured,
        paymentRefunded,
        disputeOpened,
        disputeFundsWithdrawn,
        disputeClosed,
        settlementReconciled,
        noReceiptReturn);
  }

  @Override
  protected String consumerName() {
    return "purchase-sales-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-sales";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(orderConfirmed)) {
      handler.orderConfirmed(value);
    } else if (topic.equals(paymentCaptured)) {
      handler.paymentCaptured(value);
    } else if (topic.equals(paymentRefunded)) {
      handler.paymentRefunded(value);
    } else if (topic.equals(disputeOpened) || topic.equals(disputeFundsWithdrawn)) {
      handler.disputeFundsTaken(value);
    } else if (topic.equals(disputeClosed)) {
      handler.disputeClosed(value);
    } else if (topic.equals(settlementReconciled)) {
      handler.settlementReconciled(value);
    } else if (topic.equals(noReceiptReturn)) {
      handler.noReceiptReturn(value);
    }
  }
}
