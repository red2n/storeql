package com.storeql.cart.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

  /** The event id each of those was recorded under, in the same order. */
  private final List<UUID> recordedUnder = new ArrayList<>();

  private OrderPlacedHandler handler;

  @BeforeEach
  void setUp() {
    handler = new OrderPlacedHandler();
    handler.cartService =
        new CartService() {
          @Override
          public boolean onOrderPlaced(UUID eventId, UUID tenantId, UUID customerId, UUID storeId) {
            closed.add("at-store " + tenantId + " " + customerId + " " + storeId);
            recordedUnder.add(eventId);
            return true;
          }

          @Override
          public boolean onOnlineOrderPlaced(UUID eventId, UUID tenantId, UUID loginId) {
            closed.add("online " + tenantId + " " + loginId);
            recordedUnder.add(eventId);
            return true;
          }
        };
  }

  private static String placed(String channel, String groupPart) {
    return placed(channel, groupPart, Ids.newId(), null);
  }

  /** An OrderPlaced for the order; a null eventId leaves the member out, as an old event is. */
  private static String placed(String channel, String groupPart, UUID order, UUID eventId) {
    return "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + order
        + "\",\"channel\":\""
        + channel
        + "\",\"storeId\":\""
        + STORE
        + "\",\"customerId\":null,\"loginId\":\""
        + LOGIN
        + "\""
        + groupPart
        + (eventId == null ? "" : ",\"eventId\":\"" + eventId + "\"")
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

  @Test
  void theEventIsRecordedUnderItsOwnEventId() {
    UUID online = Ids.newId();
    UUID till = Ids.newId();
    handler.handle(placed("ONLINE", "", Ids.newId(), online));
    handler.handle(placed("POS", "", Ids.newId(), till));
    assertEquals(List.of(online, till), recordedUnder);
  }

  @Test
  void anEventWithoutAnEventIdIsRecordedUnderAnIdDerivedFromItsOrder() {
    UUID order = Ids.newId();
    handler.handle(placed("ONLINE", "", order, null));
    handler.handle(placed("ONLINE", "", order, null));
    handler.handle(placed("ONLINE", "", Ids.newId(), null));
    assertEquals(3, recordedUnder.size());
    assertEquals(recordedUnder.get(0), recordedUnder.get(1), "one order, one key");
    assertNotEquals(recordedUnder.get(0), recordedUnder.get(2), "another order, another key");
    assertEquals(Ids.derived(order, "cart-order-placed"), recordedUnder.get(0));
  }

  @Test
  void anEventNamingNeitherAnEventIdNorAnOrderIsSkipped() {
    handler.handle(
        "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
            + T
            + "\",\"channel\":\"ONLINE\",\"loginId\":\""
            + LOGIN
            + "\"}");
    handler.handle("not json");
    assertEquals(List.of(), closed);
  }

  @Test
  void anOrderWithNoLoginAndNoCustomerClosesNothing() {
    handler.handle(
        "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
            + T
            + "\",\"orderId\":\""
            + Ids.newId()
            + "\",\"channel\":\"ONLINE\",\"storeId\":\""
            + STORE
            + "\",\"customerId\":null,\"loginId\":null,\"eventId\":\""
            + Ids.newId()
            + "\"}");
    assertEquals(List.of(), closed);
  }
}
