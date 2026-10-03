package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.json.Jsons;
import com.storeql.notification.service.NotificationErasure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * A person deleted their account (SJ-D43): erase the platform's own messages about it, such as the
 * welcome email. Messages a shop sent stay with that shop. Idempotent, like the customer erasure.
 */
@ApplicationScoped
class AccountDeletedHandler {

  private static final Logger LOG = System.getLogger(AccountDeletedHandler.class.getName());

  @Inject NotificationErasure erasure;

  void handle(String json) {
    UUID userId;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      userId = Ids.parse(obj.getString("aggregateId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed AccountDeleted payload skipped: " + e.getMessage());
      return;
    }
    int n = erasure.accountDeleted(userId);
    LOG.log(Level.INFO, "Account {0} deleted: {1} message(s) redacted", userId, n);
  }
}
