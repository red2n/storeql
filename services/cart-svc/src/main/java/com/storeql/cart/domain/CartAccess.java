package com.storeql.cart.domain;

import com.storeql.cart.domain.Domain.Cart;
import java.util.Set;
import java.util.UUID;

/**
 * Who a cart belongs to and when staff are merely helping. Pure: the service supplies who is
 * calling and what they showed.
 */
public final class CartAccess {

  /** Staff roles, highest first: the one named on the trace of an assisted action. */
  public static final java.util.List<String> STAFF_ROLES =
      java.util.List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");

  private CartAccess() {}

  /** Whether the roles include any staff role. */
  public static boolean isStaff(Set<String> roles) {
    return staffRole(roles) != null;
  }

  /**
   * @return the highest staff role held, or null when the caller is no staff
   */
  public static String staffRole(Set<String> roles) {
    for (String r : STAFF_ROLES) {
      if (roles.contains(r)) return r;
    }
    return null;
  }

  /**
   * Whether the caller holds the cart the way its owner does: a signed-in shopper's own cart, or a
   * guest cart whose session token they know.
   *
   * @param userId the signed-in user, or null
   * @param suppliedSession the guest session token the caller showed, or null
   */
  public static boolean owns(Cart cart, UUID userId, String suppliedSession) {
    if (cart.customerId() != null) {
      return cart.customerId().equals(userId);
    }
    return cart.sessionId() != null && cart.sessionId().equals(suppliedSession);
  }

  /**
   * Whether a change to this cart is assisted shopping: a member of staff acting on a cart they do
   * not hold as its owner. That is what leaves a trace.
   */
  public static boolean isAssisted(Cart cart, Set<String> roles, UUID userId, String session) {
    return isStaff(roles) && !owns(cart, userId, session);
  }
}
