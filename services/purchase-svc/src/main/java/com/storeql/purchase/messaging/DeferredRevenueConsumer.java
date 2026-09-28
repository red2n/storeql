package com.storeql.purchase.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Loyalty points as customer-svc announces them and gift cards as order-svc does (17.11). */
@ApplicationScoped
class DeferredRevenueConsumer extends BaseKafkaConsumer {

  @Inject DeferredRevenueHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.loyalty-earned",
      defaultValue = "storeql.customer.loyalty-earned")
  String loyaltyEarned;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.loyalty-redeemed",
      defaultValue = "storeql.customer.loyalty-redeemed")
  String loyaltyRedeemed;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.loyalty-adjusted",
      defaultValue = "storeql.customer.loyalty-adjusted")
  String loyaltyAdjusted;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.loyalty-expired",
      defaultValue = "storeql.customer.loyalty-expired")
  String loyaltyExpired;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.gift-card-loaded",
      defaultValue = "storeql.order.gift-card-loaded")
  String giftCardLoaded;

  @Override
  protected List<String> topics() {
    return List.of(loyaltyEarned, loyaltyRedeemed, loyaltyAdjusted, loyaltyExpired, giftCardLoaded);
  }

  @Override
  protected String consumerName() {
    return "purchase-deferred-revenue-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-deferred-revenue";
  }

  @Override
  protected void handle(String topic, String value) {
    if (topic.equals(giftCardLoaded)) {
      handler.giftCardLoaded(value);
    } else {
      handler.loyalty(value);
    }
  }
}
