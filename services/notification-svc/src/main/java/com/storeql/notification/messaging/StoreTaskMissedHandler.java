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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Tells a store that a task fell due and was never done (store operations & workforce).
 *
 * <p>Addressed to the store's devices, like a shortage or a failed check, because whoever is on
 * shift is the one who can still do it — and because the missed closing check is the one a manager
 * has to hear about tonight, not read on a report next week. Idempotent through {@link Notifier}'s
 * (eventId, type) dedupe: the sweep announces each miss once, and a redelivery must not nag.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, title, kind, businessDate, dueAt,
 * required}}.
 */
@ApplicationScoped
class StoreTaskMissedHandler {

  private static final Logger LOG = System.getLogger(StoreTaskMissedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "STORE_TASK_MISSED";

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    String title;
    String kind;
    String businessDate;
    boolean required;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      title = obj.getString("title");
      kind = obj.getString("kind", "");
      businessDate = obj.getString("businessDate");
      required = obj.getBoolean("required", true);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StoreTaskMissed payload skipped: " + e.getMessage());
      return;
    }
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId.toString(),
        new Messages.Message(
            "STORE_TASK_MISSED",
            Catalogue.Form.ALERT,
            null,
            Values.of()
                .text("title", title)
                .flag("opening", "OPENING".equalsIgnoreCase(kind))
                .flag("closing", "CLOSING".equalsIgnoreCase(kind))
                .day("date", LocalDate.parse(businessDate))
                .flag("required", required)));
  }
}
