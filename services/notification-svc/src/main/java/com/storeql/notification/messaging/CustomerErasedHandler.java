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
 * A shop erased a customer (SJ-D43): erase the messages that shop sent them. Idempotent — a
 * redacted message is not touched again, so a redelivered event changes nothing.
 */
@ApplicationScoped
class CustomerErasedHandler {

  private static final Logger LOG = System.getLogger(CustomerErasedHandler.class.getName());

  @Inject NotificationErasure erasure;

  void handle(String json) {
    UUID tenantId;
    UUID customerId;
    try (var reader = Jsons.reader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      tenantId = Ids.parse(obj.getString("tenantId"));
      customerId = Ids.parse(obj.getString("customerId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed CustomerErased payload skipped: " + e.getMessage());
      return;
    }
    int n = erasure.customerErased(tenantId, customerId);
    LOG.log(Level.INFO, "Customer {0} erased: {1} message(s) redacted", customerId, n);
  }
}
