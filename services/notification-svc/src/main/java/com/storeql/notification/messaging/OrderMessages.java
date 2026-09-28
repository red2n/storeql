package com.storeql.notification.messaging;

import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Catalogue;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import java.util.function.Function;

/**
 * How an order's message reaches its shopper: by email to the shop's record of them, in their own
 * language when they have said which, and to the phone in their pocket when their login registered
 * one — each once per event, the push never retried into the email's idempotency. Shared by the
 * confirmation, the ready-for-collection and the on-its-way messages so the three cannot drift.
 */
final class OrderMessages {

  private static final Logger LOG = System.getLogger(OrderMessages.class.getName());

  private OrderMessages() {}

  static void tell(
      Notifier notifier,
      CustomerClient customers,
      UUID eventId,
      String type,
      UUID tenantId,
      UUID customerId,
      UUID orderId,
      Function<Catalogue.Form, Messages.Message> message) {
    String email = customers.emailOf(tenantId, customerId).orElse(null);
    if (email == null) {
      LOG.log(Level.DEBUG, "No email for customer {0} — {1} skipped", customerId, type);
      return;
    }
    String language = customers.languageOf(tenantId, customerId).orElse(null);
    notifier.notifyOnce(
        eventId,
        type,
        tenantId,
        customerId,
        email,
        inLanguage(message, Catalogue.Form.EMAIL, language));
    customers
        .loginIdOf(tenantId, customerId)
        .ifPresent(
            login -> {
              try {
                notifier.notifyOnce(
                    eventId,
                    type + "_PUSH",
                    tenantId,
                    customerId,
                    login.toString(),
                    inLanguage(message, Catalogue.Form.PUSH, language),
                    "PUSH");
              } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "No push for order {0}: {1}", orderId, e.getMessage());
              }
            });
  }

  private static Messages.Message inLanguage(
      Function<Catalogue.Form, Messages.Message> message, Catalogue.Form form, String language) {
    Messages.Message m = message.apply(form);
    return new Messages.Message(m.type(), m.form(), language, m.values());
  }
}
