package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.json.Jsons;
import com.storeql.notification.service.Notifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Sends a welcome notification when a user registers. The recipient email is carried on the event
 * itself. Malformed payloads are skipped; a channel/delivery failure propagates so the consumer
 * loop retries (the send is idempotent per event in {@link Notifier}).
 */
@ApplicationScoped
class UserRegisteredHandler {

  private static final Logger LOG = System.getLogger(UserRegisteredHandler.class.getName());

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID userId;
    String email;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      email = obj.getString("email", null);
      // The account the welcome is about, so deleting the account can erase it.
      userId =
          obj.containsKey("aggregateId") && !obj.isNull("aggregateId")
              ? Ids.parse(obj.getString("aggregateId"))
              : null;
      tenantId =
          obj.containsKey("tenantId") && !obj.isNull("tenantId")
              ? Ids.parse(obj.getString("tenantId"))
              : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed UserRegistered payload skipped: " + e.getMessage());
      return;
    }

    if (email == null || email.isBlank()) {
      return;
    }
    String body =
        "Hi,\n\nYour StoreQL account (" + email + ") is ready. Welcome aboard!\n\n— StoreQL";
    notifier.notifyOnce(eventId, "WELCOME", tenantId, userId, email, "Welcome to StoreQL", body);
  }
}
