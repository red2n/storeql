package com.storeql.pricing.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import java.util.UUID;

/** Builds {@link OutboxRow} instances for all events published by pricing-svc. */
final class Events {

  private Events() {}

  static OutboxRow priceChanged(UUID tenantId, UUID priceListId) {
    return new OutboxRow(
        "PriceChanged",
        "storeql.pricing.price-changed",
        tenantId,
        priceListId,
        String.format(
            // The eventId is what a consumer with no key of its own (the webhook fan-out) tells one
            // change from the next by: a price list changes many times and has nothing else to go
            // on.
            "{\"eventType\":\"PriceChanged\",\"tenantId\":\"%s\",\"priceListId\":\"%s\","
                + "\"eventId\":\"%s\"}",
            tenantId, priceListId, Ids.newId()));
  }

  static OutboxRow promotionActivated(UUID tenantId, UUID promotionId) {
    return new OutboxRow(
        "PromotionActivated",
        "storeql.pricing.promotion-activated",
        tenantId,
        promotionId,
        String.format(
            "{\"eventType\":\"PromotionActivated\",\"tenantId\":\"%s\",\"promotionId\":\"%s\","
                + "\"eventId\":\"%s\"}",
            tenantId, promotionId, Ids.newId()));
  }
}
