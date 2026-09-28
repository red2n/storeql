package com.storeql.cart.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.cart.service.CartService;
import com.storeql.ids.Ids;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An online order closes the shopper's cart whichever store it was placed at — a delivery resolves
 * to the store serving the postcode, and a split one goes to several (order orchestration); a till
 * sale naming a customer closes only a cart at its store, as before.
 */
class OrderPlacedHandlerTest {

  private static final UUID T = Ids.newId();
  private static final UUID LOGIN = Ids.newId();
  private static final UUID STORE = Ids.newId();

  /** What the handler asked the cart service to close. */
  private final List<String> closed = new ArrayList<>();

  private OrderPlacedHandler handler;

  @BeforeEach
  void setUp() {
    handler = new OrderPlacedHandler();
    handler.cartService =
        new CartService() {
          @Override
          public void onOrderPlaced(UUID tenantId, UUID customerId, UUID storeId) {
            closed.add("at-store " + tenantId + " " + customerId + " " + storeId);
          }

          @Override
          public void onOnlineOrderPlaced(UUID tenantId, UUID loginId) {
            closed.add("online " + tenantId + " " + loginId);
          }
        };
  }

  private static String placed(String channel, String groupPart) {
    return "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + Ids.newId()
        + "\",\"channel\":\""
        + channel
        + "\",\"storeId\":\""
        + STORE
        + "\",\"customerId\":null,\"loginId\":\""
        + LOGIN
        + "\""
        + groupPart
        + "}";
  }

  @Test
  void anOnlineOrderClosesTheShoppersCartWhateverItsStore() {
    handler.handle(placed("ONLINE", ",\"groupId\":\"" + Ids.newId() + "\""));
    assertEquals(List.of("online " + T + " " + LOGIN), closed);
  }

  @Test
  void aTillSaleClosesOnlyACartAtItsStore() {
    handler.handle(placed("POS", ""));
    assertEquals(List.of("at-store " + T + " " + LOGIN + " " + STORE), closed);
  }
}
