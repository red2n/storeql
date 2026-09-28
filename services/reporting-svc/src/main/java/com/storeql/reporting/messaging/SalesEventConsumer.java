package com.storeql.reporting.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Subscribes to the order/payment events that feed the sales projection (N4). Delegates dispatch to
 * {@link SalesEventDispatcher} which routes by topic. Runs in its own consumer group so it balances
 * independently of the inventory {@link StockEventConsumer}. Consumer lifecycle is inherited from
 * {@link BaseKafkaConsumer}; no business logic here (SRP).
 */
@ApplicationScoped
class SalesEventConsumer extends BaseKafkaConsumer {

  private static final List<String> TOPICS =
      List.of(
          "storeql.order.order-confirmed",
          "storeql.payment.payment-refunded",
          "storeql.order.order-voided");

  @Inject SalesEventDispatcher dispatcher;

  @Override
  protected List<String> topics() {
    return TOPICS;
  }

  @Override
  protected String consumerName() {
    return "reporting-sales-event-consumer";
  }

  @Override
  protected String groupId() {
    return "reporting-svc-sales";
  }

  @Override
  protected void handle(String topic, String value) {
    dispatcher.dispatch(topic, value);
  }
}
