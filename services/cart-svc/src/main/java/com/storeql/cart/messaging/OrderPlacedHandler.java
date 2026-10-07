package com.storeql.cart.messaging;

import com.storeql.cart.service.CartService;
import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Business handler for {@code storeql.order.order-placed}. When an order is placed for a known
 * customer, marks that customer's ACTIVE cart CHECKED_OUT so it no longer appears as their open
 * cart: at any store for an online order, at the order's own store for a till sale. Guest orders
 * and till sales naming no customer (no customerId in the event) are silently skipped.
 *
 * <p>Idempotent (golden rule 7): the event is recorded in {@code processed_events} on the same
 * transaction that closes the cart, so a redelivery does nothing, and in particular does not close
 * an ACTIVE cart the shopper opened after the first delivery. The key is the event's {@code
 * eventId}; an event published without one is keyed by its order, which places once. A payload with
 * neither is malformed and skipped. A failed write propagates, so the consumer loop redelivers the
 * record, and nothing was recorded to stop it.
 */
@ApplicationScoped
class OrderPlacedHandler {

  private static final Logger LOG = System.getLogger(OrderPlacedHandler.class.getName());

  @Inject CartService cartService;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID customerId;
    UUID storeId;
    boolean online;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = dedupeKey(obj);
      tenantId = Ids.parse(obj.getString("tenantId"));
      online = "ONLINE".equals(obj.getString("channel", null));
      // A cart is held under the login the shopper signed in with, which is not the shop's
      // customer id (SJ-D44) — before that was unpicked, one value stood in both places. loginId
      // is what matches a cart; customerId is the fallback for events published before the split.
      String basketOwner =
          obj.containsKey("loginId") && !obj.isNull("loginId")
              ? obj.getString("loginId")
              : (obj.isNull("customerId") ? null : obj.getString("customerId"));
      customerId = basketOwner == null ? null : Ids.parse(basketOwner);
      String storeIdStr = obj.getString("storeId", null);
      storeId = storeIdStr != null ? Ids.parse(storeIdStr) : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderPlaced payload skipped: " + e.getMessage());
      return;
    }

    // An online order closes the shopper's cart whichever store it went to: a delivery resolves
    // to the store serving the postcode, and a split one goes to several (order orchestration).
    if (online && customerId != null) {
      announce(eventId, customerId, cartService.onOnlineOrderPlaced(eventId, tenantId, customerId));
      return;
    }
    if (customerId == null || storeId == null) {
      return; // POS or anonymous order — no cart to close
    }

    announce(
        eventId, customerId, cartService.onOrderPlaced(eventId, tenantId, customerId, storeId));
  }

  /**
   * What the event is recorded under: its {@code eventId}, or, for one published without it, an id
   * derived from its order. Every order is placed once, and each part of a split order is an order
   * of its own with its own event.
   */
  private static UUID dedupeKey(JsonObject obj) {
    if (obj.containsKey("eventId") && !obj.isNull("eventId")) {
      return Ids.parse(obj.getString("eventId"));
    }
    return Ids.derived(Ids.parse(obj.getString("orderId")), "cart-order-placed");
  }

  private static void announce(UUID eventId, UUID customerId, boolean first) {
    if (first) {
      LOG.log(
          Level.INFO,
          "OrderPlaced {0} handled: any ACTIVE cart of shopper {1} marked CHECKED_OUT",
          eventId,
          customerId);
    } else {
      LOG.log(Level.INFO, "OrderPlaced {0} already handled, skipped", eventId);
    }
  }
}
