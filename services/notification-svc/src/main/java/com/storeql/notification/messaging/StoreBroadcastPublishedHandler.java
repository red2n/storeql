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
import java.util.UUID;

/**
 * Tells a store's devices that management has published a notice (store operations & workforce).
 *
 * <p>Only an urgent notice wakes the devices; the rest wait to be read on the shop floor, because a
 * device that buzzes for every price change is a device that gets muted before the recall comes.
 * One announcement per store the notice reaches, each its own event, idempotent through {@link
 * Notifier}'s (eventId, type) dedupe.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, title, priority, requiresAck, wake}}.
 */
@ApplicationScoped
class StoreBroadcastPublishedHandler {

  private static final Logger LOG =
      System.getLogger(StoreBroadcastPublishedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "STORE_BROADCAST";

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    String title;
    boolean requiresAck;
    boolean wake;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      title = obj.getString("title");
      requiresAck = obj.getBoolean("requiresAck", false);
      wake = obj.getBoolean("wake", false);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING, "Malformed StoreBroadcastPublished payload skipped: " + e.getMessage());
      return;
    }
    if (!wake) {
      LOG.log(
          Level.DEBUG,
          "Notice {0} for store {1} waits to be read; devices not woken",
          title,
          storeId);
      return;
    }
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId.toString(),
        new Messages.Message(
            "STORE_NOTICE_URGENT",
            Catalogue.Form.ALERT,
            null,
            Values.of().text("title", title).flag("acknowledge", requiresAck)));
  }
}
