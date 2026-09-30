package com.storeql.cart.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain records owned by cart-svc. Never returned over HTTP — use DTOs. */
public final class Domain {

  private Domain() {}

  public record Cart(
      UUID id,
      UUID tenantId,
      UUID customerId, // null for guest carts
      String sessionId, // null for authenticated carts
      UUID storeId,
      String status, // ACTIVE | CHECKED_OUT | ABANDONED
      Instant createdAt,
      Instant updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CHECKED_OUT = "CHECKED_OUT";
    public static final String STATUS_ABANDONED = "ABANDONED";
  }

  public record CartItem(
      UUID id,
      UUID cartId,
      UUID tenantId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice, // null until pricing-svc enriches the view
      Instant addedAt) {}

  /**
   * What staff did to a cart that was not theirs, kept with who did it. Written on the same
   * transaction as the change.
   *
   * @param action {@code ADD_ITEM}, {@code SET_QTY} or {@code REMOVE_ITEM}
   * @param actorId the signed-in staff user; null only when a service made the call
   * @param actorRole the highest staff role the caller held
   */
  public record StaffAction(
      String action, UUID actorId, String actorRole, UUID itemId, UUID variantId, BigDecimal qty) {

    public static final String ADD_ITEM = "ADD_ITEM";
    public static final String SET_QTY = "SET_QTY";
    public static final String REMOVE_ITEM = "REMOVE_ITEM";
  }
}
