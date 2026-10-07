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
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.UUID;

/**
 * Tells every store whose stock a recall took off sale. One event names several stores, and {@link
 * Notifier} dedupes on an event id, so each store's message is keyed by an id derived from the
 * event and the store: a redelivery tells nobody twice, and one store's failed send does not stop
 * the others being retried. A malformed payload is skipped.
 *
 * <p>Expected payload: {@code {eventId, tenantId, reference, kind, hazard, storeIds[]}}.
 */
@ApplicationScoped
class RecallOpenedHandler {

  private static final Logger LOG = System.getLogger(RecallOpenedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "RECALL_OPENED";

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    String reference;
    boolean recall;
    String hazardCode;
    List<UUID> stores;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      reference = obj.getString("reference");
      recall = "RECALL".equals(obj.getString("kind"));
      hazardCode = obj.getString("hazard");
      stores =
          obj.getJsonArray("storeIds").getValuesAs(JsonString.class).stream()
              .map(s -> Ids.parse(s.getString()))
              .toList();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RecallOpened payload skipped: " + e.getMessage());
      return;
    }
    for (UUID store : stores) {
      notifier.notifyOnce(
          perStore(eventId, store),
          NOTIFICATION_TYPE,
          tenantId,
          null,
          store.toString(),
          new Messages.Message(
              "RECALL_OPENED",
              Catalogue.Form.ALERT,
              null,
              Values.of()
                  .text("reference", reference)
                  .flag("recall", recall)
                  .text("hazard", RecallText.hazard(hazardCode))
                  .text("hazard_code", hazardCode)));
    }
  }

  static UUID perStore(UUID eventId, UUID storeId) {
    return Ids.derived(eventId, "store:" + storeId);
  }
}
