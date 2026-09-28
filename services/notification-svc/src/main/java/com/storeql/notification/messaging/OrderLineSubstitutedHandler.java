package com.storeql.notification.messaging;

import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Tells a shopper the store put a substitute in their online order (substitutions for out-of-stock
 * online lines): on {@code OrderLineSubstituted}, what was swapped for what, how many, that they
 * pay no more, and the difference going back when the substitute cost less.
 */
@ApplicationScoped
class OrderLineSubstitutedHandler {

  private static final String TYPE = "ORDER_LINE_SUBSTITUTED";

  @Inject Notifier notifier;
  @Inject CustomerClient customers;

  void handle(String json) {
    LineEvents.tell(
        notifier,
        customers,
        json,
        TYPE,
        obj ->
            Values.of()
                .text("item", LineEvents.item(obj, "fromName"))
                .text("substitute", LineEvents.item(obj, "toName"))
                .text("qty", LineEvents.qty(obj)));
  }
}
