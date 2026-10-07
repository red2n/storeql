package com.storeql.order.messaging;

import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.service.OrderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Handles CustomerErased from customer-svc (SJ-D43): a shop erased a customer, so its orders must
 * stop identifying them. Idempotent on the event id (golden rule 7). A malformed payload is
 * skipped; a transient failure propagates so the consumer loop redelivers rather than losing the
 * erasure.
 */
@ApplicationScoped
class CustomerErasedHandler {

  private static final Logger LOG = System.getLogger(CustomerErasedHandler.class.getName());

  @Inject OrderService svc;

  void handle(String payload) {
    UUID eventId;
    UUID tenantId;
    UUID customerId;
    UUID loginId;
    try (var reader = Json.createReader(new StringReader(payload))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      customerId = Ids.parse(obj.getString("customerId"));
      // Absent for a walk-in the till created, and on events published before the link existed.
      loginId =
          obj.containsKey("loginId") && !obj.isNull("loginId")
              ? Ids.parse(obj.getString("loginId"))
              : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed CustomerErased payload skipped: " + e.getMessage());
      return;
    }
    if (svc.handleCustomerErased(tenantId, customerId, loginId, eventId)) {
      LOG.log(
          Level.INFO,
          "Customer {0} erased: settled orders redacted, open orders held until they finish",
          customerId);
    }
  }
}
