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
 * customer, marks that customer's active cart at that store as CHECKED_OUT so it no longer appears
 * as their open cart. Guest/POS orders (no customerId in the event) are silently skipped.
 *
 * <p>Idempotent: UPDATE WHERE status='ACTIVE' is a no-op if the cart is already CHECKED_OUT.
 */
@ApplicationScoped
class OrderPlacedHandler {

  private static final Logger LOG = System.getLogger(OrderPlacedHandler.class.getName());

  @Inject CartService cartService;

  void handle(String json) {
    UUID tenantId;
    UUID customerId;
    UUID storeId;
    boolean online;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
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
      cartService.onOnlineOrderPlaced(tenantId, customerId);
      LOG.log(Level.INFO, "Cart marked CHECKED_OUT for shopper {0}", customerId);
      return;
    }
    if (customerId == null || storeId == null) {
      return; // POS or anonymous order — no cart to close
    }

    cartService.onOrderPlaced(tenantId, customerId, storeId);
    LOG.log(
        Level.INFO, "Cart marked CHECKED_OUT for customer {0} at store {1}", customerId, storeId);
  }
}
