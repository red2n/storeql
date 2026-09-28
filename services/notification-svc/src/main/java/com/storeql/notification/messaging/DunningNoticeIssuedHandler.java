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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Writes and sends a business the notice that it is late paying the platform (21.12): a reminder at
 * each day the dunning policy names, and the day its service is interrupted, each carrying the link
 * that pays the invoice with no sign-in.
 *
 * <p>The notice is from the platform, not from the business to itself: it is signed with the
 * platform's name, which the event carries, and it goes to the address on the business's
 * subscription, which the event carries too — the owner's from sign-up, or the one the business set
 * since (SJ-D72). notification-svc holds neither; tenant-svc, which owns the subscription, says
 * where each notice goes.
 *
 * <p>Idempotent on the event id, which tenant-svc writes with the step in one transaction, so a
 * redelivery tells the business nothing twice. A notice with no address, or a malformed one, is
 * logged and skipped: tenant-svc has already named the business in its run, so nothing here is lost
 * quietly.
 */
@ApplicationScoped
public class DunningNoticeIssuedHandler {

  private static final Logger LOG = System.getLogger(DunningNoticeIssuedHandler.class.getName());

  @Inject Notifier notifier;

  /**
   * @param json the {@code DunningNoticeIssued} payload
   */
  public void handle(String json) {
    JsonObject obj;
    UUID eventId;
    UUID tenantId;
    String step;
    BigDecimal amountDue;
    LocalDate dueDate;
    LocalDate suspendOn;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      if (!"DunningNoticeIssued".equals(obj.getString("eventType", ""))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      step = obj.getString("step");
      amountDue = obj.getJsonNumber("amountDue").bigDecimalValue();
      dueDate = LocalDate.parse(obj.getString("dueDate"));
      suspendOn =
          obj.containsKey("suspendOn") && !obj.isNull("suspendOn")
              ? LocalDate.parse(obj.getString("suspendOn"))
              : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed DunningNoticeIssued skipped: " + e.getMessage());
      return;
    }
    String recipient = obj.getString("recipient", null);
    if (recipient == null || recipient.isBlank()) {
      LOG.log(Level.DEBUG, "No billing address on notice {0}; nothing sent", eventId);
      return;
    }
    // The suspension has its own words; every reminder shares one message, whatever its number.
    String type = "SUSPENDED".equals(step) ? "SERVICE_SUSPENDED" : "INVOICE_OVERDUE";
    String currency = obj.getString("currency", "");
    // In the business's own language, signed by the platform: a notice from the platform is not
    // one of the business's messages to its customers, so its sign-off is not the business's.
    notifier.notifyOnce(
        eventId,
        type,
        tenantId,
        null,
        recipient.trim(),
        new Messages.Message(
            type,
            Catalogue.Form.EMAIL,
            null,
            Values.of()
                .text("invoice", obj.getString("invoiceNumber", ""))
                .money("amount_due", amountDue, currency)
                .day("due_date", dueDate)
                .number("days_overdue", new BigDecimal(obj.getInt("daysOverdue", 0)))
                .text("pay_link", obj.getString("payUrl", ""))
                .day("suspend_on", suspendOn)
                .text("shop", obj.getString("platform", "StoreQL"))));
  }
}
