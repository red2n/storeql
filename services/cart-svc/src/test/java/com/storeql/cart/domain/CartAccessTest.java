package com.storeql.cart.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.cart.domain.Domain.Cart;
import com.storeql.ids.Ids;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartAccessTest {

  private static final UUID SHOPPER = Ids.newId();

  private static Cart cartOf(UUID customer, String session) {
    return new Cart(
        Ids.newId(),
        Ids.newId(),
        customer,
        session,
        Ids.newId(),
        Cart.STATUS_ACTIVE,
        Instant.now(),
        Instant.now());
  }

  @Test
  void aSignedInShopperOwnsOnlyTheirOwnCart() {
    Cart c = cartOf(SHOPPER, null);
    assertTrue(CartAccess.owns(c, SHOPPER, null));
    assertFalse(CartAccess.owns(c, Ids.newId(), null));
    assertFalse(CartAccess.owns(c, null, null));
  }

  @Test
  void aGuestCartIsOwnedByWhoeverKnowsItsSessionToken() {
    Cart c = cartOf(null, "token-abc");
    assertTrue(CartAccess.owns(c, null, "token-abc"));
    assertFalse(CartAccess.owns(c, null, "guess"));
    assertFalse(CartAccess.owns(c, null, null));
  }

  @Test
  void staffOnAShoppersCartAreAssisting() {
    Cart c = cartOf(SHOPPER, null);
    assertTrue(CartAccess.isAssisted(c, Set.of("CASHIER"), Ids.newId(), null));
    assertTrue(CartAccess.isAssisted(c, Set.of("OWNER"), Ids.newId(), null));
  }

  @Test
  void staffOnTheirOwnCartOrAShopperOnTheirsAreNot() {
    Cart c = cartOf(SHOPPER, null);
    assertFalse(CartAccess.isAssisted(c, Set.of("CASHIER"), SHOPPER, null));
    assertFalse(CartAccess.isAssisted(c, Set.of("CUSTOMER"), SHOPPER, null));
    assertFalse(CartAccess.isAssisted(c, Set.of(), Ids.newId(), null));
  }

  @Test
  void staffOnAGuestCartAreAssistingUnlessTheyHoldItsToken() {
    Cart c = cartOf(null, "token-abc");
    assertTrue(CartAccess.isAssisted(c, Set.of("MANAGER"), Ids.newId(), null));
    assertFalse(CartAccess.isAssisted(c, Set.of("MANAGER"), Ids.newId(), "token-abc"));
  }

  @Test
  void theHighestStaffRoleIsTheOneNamed() {
    assertEquals("OWNER", CartAccess.staffRole(Set.of("CASHIER", "OWNER")));
    assertEquals("MANAGER", CartAccess.staffRole(Set.of("CASHIER", "MANAGER", "CUSTOMER")));
    assertEquals("CASHIER", CartAccess.staffRole(Set.of("CASHIER")));
    assertNull(CartAccess.staffRole(Set.of("CUSTOMER", "GUEST")));
  }
}
