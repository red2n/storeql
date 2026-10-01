package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.json.Jsons;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tells a business a card payment has been charged back (11.9), and by when it has to answer: a
 * dispute nobody answers is a dispute lost, and the date is the bank's, not ours. {@link Notifier}
 * dedupes redeliveries, so a business is told once per dispute. A malformed payload is skipped.
 *
 * <p>Expected payload: {@code {eventId, tenantId, disputeId, orderId, storeId?, amount, currency,
 * reason, evidenceDueBy?}}.
 */
@ApplicationScoped
class PaymentDisputeOpenedHandler {

  private static final Logger LOG = System.getLogger(PaymentDisputeOpenedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "PAYMENT_DISPUTE_OPENED";

  /** The event carries UTC and a message has no viewer's zone to convert to, so it says so. */
  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    String storeId;
    BigDecimal amount;
    String currency;
    String reason;
    Instant dueBy;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"PaymentDisputeOpened".equals(obj.getString("eventType", ""))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = obj.getString("storeId", null);
      amount = obj.getJsonNumber("amount").bigDecimalValue();
      currency = obj.getString("currency");
      reason = obj.getString("reason", "GENERAL");
      dueBy =
          obj.containsKey("evidenceDueBy") ? Instant.parse(obj.getString("evidenceDueBy")) : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed PaymentDisputeOpened payload skipped: " + e.getMessage());
      return;
    }
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId == null || storeId.isBlank() ? tenantId.toString() : storeId,
        new Messages.Message(
            "PAYMENT_DISPUTE_OPENED",
            Catalogue.Form.ALERT,
            null,
            Values.of()
                .money("amount", amount, currency)
                .text("reason", reason.toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                .text("reason_code", reason)
                .moment("due_by", dueBy)));
  }
}
