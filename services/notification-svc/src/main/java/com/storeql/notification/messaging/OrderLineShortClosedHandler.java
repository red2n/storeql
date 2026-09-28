package com.storeql.notification.messaging;

import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Tells a shopper an item of their online order was unavailable (substitutions for out-of-stock
 * online lines): on {@code OrderLineShortClosed}, naming the product when order-svc could, how
 * many, and what goes back to them when anything does.
 */
@ApplicationScoped
class OrderLineShortClosedHandler {

  private static final String TYPE = "ORDER_LINE_SHORT";

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
                .text("item", LineEvents.item(obj, "variantName"))
                .text("qty", LineEvents.qty(obj)));
  }
}
