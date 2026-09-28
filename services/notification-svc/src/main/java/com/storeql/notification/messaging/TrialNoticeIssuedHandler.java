package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Writes and sends a business the word about its trial (21.13): that it ends on a day and what the
 * plan costs from then, or that it has ended and here is the first invoice with the link that pays
 * it. From the platform, in the business's language, to the address on its subscription.
 *
 * <p>Idempotent on the event id, which tenant-svc claims once per stage of a trial. A malformed
 * event, or one for a stage this service does not know, tells nobody anything.
 */
@ApplicationScoped
public class TrialNoticeIssuedHandler {

  private static final Logger LOG = System.getLogger(TrialNoticeIssuedHandler.class.getName());

  @Inject Notifier notifier;

  /**
   * @param json the {@code TrialNoticeIssued} payload
   */
  public void handle(String json) {
    JsonObject obj;
    UUID eventId;
    UUID tenantId;
    String stage;
    BigDecimal price;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      if (!"TrialNoticeIssued".equals(obj.getString("eventType", ""))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      stage = obj.getString("stage");
      price = obj.getJsonNumber("price").bigDecimalValue();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed TrialNoticeIssued skipped: " + e.getMessage());
      return;
    }
    String type =
        switch (stage) {
          case "ENDING" -> "TRIAL_ENDING";
          case "ENDED" -> "TRIAL_ENDED";
          default -> null;
        };
    if (type == null) {
      LOG.log(
          Level.WARNING, "TrialNoticeIssued {0} names a stage nobody sends: {1}", eventId, stage);
      return;
    }
    String recipient = obj.getString("recipient", null);
    if (recipient == null || recipient.isBlank()) {
      LOG.log(Level.DEBUG, "No billing address on trial notice {0}; nothing sent", eventId);
      return;
    }
    String currency = obj.getString("currency", "");
    Values values =
        Values.of()
            .text("plan", obj.getString("plan", ""))
            .day("trial_end", Payloads.day(obj, "trialEnd"))
            .money("price", price, currency)
            .text("interval", obj.getString("interval", "").toLowerCase(java.util.Locale.ROOT))
            .text("shop", obj.getString("platform", "StoreQL"));
    if ("TRIAL_ENDED".equals(type)) {
      values
          .text("invoice", obj.getString("invoiceNumber", ""))
          .money("amount_due", Payloads.number(obj, "amountDue"), currency)
          .day("due_date", Payloads.day(obj, "dueDate"))
          .text("pay_link", obj.getString("payUrl", ""));
    }
    // A business, not a person, so no subject for an erasure to find; the platform's name signs it.
    notifier.notifyOnce(
        eventId,
        type,
        tenantId,
        null,
        recipient.trim(),
        new Messages.Message(type, Catalogue.Form.EMAIL, null, values));
  }
}
