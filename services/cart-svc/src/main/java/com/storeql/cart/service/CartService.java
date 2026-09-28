package com.storeql.cart.service;

import com.storeql.cart.domain.Domain.Cart;
import com.storeql.cart.domain.Domain.CartItem;
import com.storeql.cart.dto.Dtos.AddItemRequest;
import com.storeql.cart.dto.Dtos.CartItemResponse;
import com.storeql.cart.dto.Dtos.CartResponse;
import com.storeql.cart.dto.Dtos.CartViewResponse;
import com.storeql.cart.dto.Dtos.CreateCartRequest;
import com.storeql.cart.dto.Dtos.MergeCartRequest;
import com.storeql.cart.dto.Dtos.UpdateItemQtyRequest;
import com.storeql.cart.repo.CartRepository;
import com.storeql.ids.Ids;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Business logic for cart-svc. Thin resource → this service → repository. */
@ApplicationScoped
public class CartService {

  @Inject CartRepository repo;
  @Inject TenantStatusRepository tenantStatusRepo;
  @Inject StoreStatusRepository storeStatusRepo;

  // ── Cart lifecycle ────────────────────────────────────────────────────────

  /**
   * Returns the caller's existing ACTIVE cart, or creates a new one. Guest carts are identified by
   * sessionId; authenticated carts by customerId from the JWT.
   *
   * <p>The guest session token is <strong>minted server-side</strong> (high-entropy, from {@link
   * SecureRandom}) whenever a new guest cart is created — a client-supplied value is only ever
   * honoured to look up an <em>existing</em> cart, never to create one. Cart ownership rests on
   * knowledge of this token ({@link #requireOwnership}), so allowing a client to choose it would
   * let a caller create (and let others guess) a cart under a weak/sequential id.
   */
  public CartResponse createOrGetCart(TenantContext ctx, CreateCartRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = parseUuid(req.storeId(), "storeId");

    guardTenantAndStore(tenantId, storeId);

    // Prefer customerId (authenticated) over sessionId (guest).
    UUID customerId = ctx.userId() != null && !ctx.hasRole("GUEST") ? ctx.userId() : null;

    // Returning caller: a client-supplied guest session only resolves an EXISTING cart.
    String suppliedSession = req.sessionId();
    Cart existing =
        customerId != null
            ? repo.findActiveByCustomer(tenantId, customerId).orElse(null)
            : (suppliedSession != null && !suppliedSession.isBlank())
                ? repo.findActiveBySession(tenantId, suppliedSession).orElse(null)
                : null;

    if (existing != null) return toResponse(existing);

    // New cart: mint the guest session token here rather than trusting the client's.
    String sessionId = customerId == null ? newSessionToken() : null;

    Cart cart =
        new Cart(
            Ids.newId(),
            tenantId,
            customerId,
            sessionId,
            storeId,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    try {
      return toResponse(repo.insert(cart));
    } catch (ApiException e) {
      // The find above is not atomic with this insert: a concurrent request for the same
      // customer/session can win the race and insert first, tripping the partial unique index on
      // (tenant_id, customer_id|session_id) WHERE status='ACTIVE'. Return that winner's cart
      // instead of erroring.
      if (!"DUPLICATE".equals(e.code())) throw e;
      Cart winner =
          customerId != null
              ? repo.findActiveByCustomer(tenantId, customerId).orElse(null)
              : repo.findActiveBySession(tenantId, sessionId).orElse(null);
      if (winner == null) throw e;
      return toResponse(winner);
    }
  }

  /**
   * Returns a cart together with its items.
   *
   * @param ctx caller context; supplies the tenant and the authenticated identity
   * @param cartId cart id to look up, or {@code null} to resolve by session/identity
   * @param sessionId guest session token, or {@code null} for an authenticated caller
   * @return the cart and its items
   * @throws ApiException {@code CART_NO_IDENTITY} (400) when none of cartId, sessionId or an
   *     authenticated identity is supplied; {@code CART_NOT_FOUND} (404) when no cart matches or
   *     the caller does not own it
   */
  public CartViewResponse viewCart(TenantContext ctx, String cartId, String sessionId) {
    UUID tenantId = ctx.requireTenantId();

    Cart cart = resolveCart(tenantId, cartId, sessionId, ctx);
    List<CartItem> items = repo.findItemsByCart(tenantId, cart.id());
    return new CartViewResponse(
        toResponse(cart), items.stream().map(this::toItemResponse).toList());
  }

  // ── Item operations ───────────────────────────────────────────────────────

  /**
   * Adds an item to the cart, incrementing the quantity if the variant is already present.
   *
   * @param ctx caller context; supplies the tenant and the authenticated identity
   * @param req the target cart, the variant, the quantity and the unit price
   * @return the inserted or incremented cart item
   * @throws ApiException {@code CART_NOT_FOUND} (404) when the cart does not exist or the caller
   *     does not own it; {@code CART_NOT_ACTIVE} (409) when the cart is already checked out or
   *     abandoned; {@code TENANT_NOT_OPERATIONAL}/{@code STORE_NOT_OPERATIONAL} (409) when the
   *     tenant or store is not open for business
   */
  public CartItemResponse addItem(TenantContext ctx, AddItemRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID cartId = parseUuid(req.cartId(), "cartId");

    Cart cart =
        repo.findById(tenantId, cartId)
            .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", "cart not found"));
    requireOwnership(cart, ctx, req.sessionId());
    if (!Cart.STATUS_ACTIVE.equals(cart.status()))
      throw ApiException.conflict("CART_NOT_ACTIVE", "cart is not active");

    guardTenantAndStore(tenantId, cart.storeId());

    CartItem item =
        new CartItem(
            Ids.newId(),
            cartId,
            tenantId,
            parseUuid(req.variantId(), "variantId"),
            req.qty(),
            req.unitPrice(),
            Instant.now());
    return toItemResponse(repo.upsertItem(item));
  }

  /**
   * Sets the quantity of an existing cart item.
   *
   * @param ctx caller context; supplies the tenant and the authenticated identity
   * @param itemId the cart item to change
   * @param req the owning cart and the new quantity
   * @return the item with its updated quantity
   * @throws ApiException {@code CART_NOT_FOUND} (404) when the cart does not exist or the caller
   *     does not own it; {@code CART_ITEM_NOT_FOUND} (404) when the item does not exist or belongs
   *     to a different cart
   */
  public CartItemResponse updateItemQty(TenantContext ctx, UUID itemId, UpdateItemQtyRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID cartId = parseUuid(req.cartId(), "cartId");

    Cart cart =
        repo.findById(tenantId, cartId)
            .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", "cart not found"));
    requireOwnership(cart, ctx, req.sessionId());

    CartItem item =
        repo.findItemById(tenantId, itemId)
            .orElseThrow(() -> ApiException.notFound("CART_ITEM_NOT_FOUND", "item not found"));
    if (!item.cartId().equals(cartId))
      throw ApiException.notFound("CART_ITEM_NOT_FOUND", "item not found in this cart");

    repo.updateItemQty(tenantId, cartId, itemId, req.qty());
    return toItemResponse(repo.findItemById(tenantId, itemId).orElseThrow());
  }

  /**
   * Removes an item from the cart.
   *
   * @param ctx caller context; supplies the tenant and the authenticated identity
   * @param itemId the cart item to delete
   * @param cartIdStr the owning cart id
   * @param sessionId guest session token, or {@code null} for an authenticated caller
   * @throws ApiException {@code CART_NOT_FOUND} (404) when the cart does not exist or the caller
   *     does not own it; {@code CART_ITEM_NOT_FOUND} (404) when the item does not exist or belongs
   *     to a different cart
   */
  public void removeItem(TenantContext ctx, UUID itemId, String cartIdStr, String sessionId) {
    UUID tenantId = ctx.requireTenantId();
    UUID cartId = parseUuid(cartIdStr, "cartId");

    Cart cart =
        repo.findById(tenantId, cartId)
            .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", "cart not found"));
    requireOwnership(cart, ctx, sessionId);

    repo.findItemById(tenantId, itemId)
        .filter(i -> i.cartId().equals(cartId))
        .orElseThrow(() -> ApiException.notFound("CART_ITEM_NOT_FOUND", "item not found"));
    repo.deleteItem(tenantId, cartId, itemId);
  }

  // ── Merge guest cart ──────────────────────────────────────────────────────

  /**
   * Merges a guest cart (by sessionId) into the authenticated customer's cart. Creates the customer
   * cart if it does not exist. The guest cart is abandoned after the merge.
   */
  public CartResponse mergeCart(TenantContext ctx, MergeCartRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID customerId = ctx.userId();
    if (customerId == null)
      throw ApiException.badRequest("CART_MERGE_NO_AUTH", "merge requires an authenticated user");

    Cart guestCart =
        repo.findActiveBySession(tenantId, req.sessionId())
            .orElseThrow(
                () -> ApiException.notFound("CART_NOT_FOUND", "guest cart not found or inactive"));

    guardTenantAndStore(tenantId, guestCart.storeId());

    Cart customerCart =
        repo.findActiveByCustomer(tenantId, customerId)
            .orElseGet(
                () -> {
                  Cart c =
                      new Cart(
                          Ids.newId(),
                          tenantId,
                          customerId,
                          null,
                          guestCart.storeId(),
                          Cart.STATUS_ACTIVE,
                          Instant.now(),
                          Instant.now());
                  return repo.insert(c);
                });

    repo.mergeItems(tenantId, guestCart.id(), customerCart.id());
    return toResponse(repo.findById(tenantId, customerCart.id()).orElseThrow());
  }

  // ── Called by OrderPlacedHandler ─────────────────────────────────────────

  /**
   * Marks the customer's active cart at a store as checked out once their order is placed.
   *
   * <p>Idempotent, as the {@code OrderPlaced} consumer may redeliver: a cart already moved out of
   * {@code ACTIVE} is left untouched. Guest and POS orders carry no customer id and are ignored —
   * they have no server-side cart to retire.
   *
   * @param tenantId owning tenant
   * @param customerId the ordering customer, or {@code null} for a guest/POS order
   * @param storeId the store the order was placed against
   */
  public void onOrderPlaced(UUID tenantId, UUID customerId, UUID storeId) {
    if (customerId == null) return; // guest or POS order — no cart to mark
    repo.markCheckedOutByCustomerAndStore(tenantId, customerId, storeId);
  }

  /**
   * Marks the shopper's active cart checked out once their online order is placed, whichever store
   * the order went to: a delivery resolves to the store serving the postcode and may be split
   * across several (order orchestration), none of which need be the store the cart was filled at.
   * Idempotent: every part of a split announces itself, and the first closes the cart.
   *
   * @param loginId the shopper's login, which is what holds a cart
   */
  public void onOnlineOrderPlaced(UUID tenantId, UUID loginId) {
    if (loginId == null) return; // a guest checkout has no server-side cart
    repo.markCheckedOutByCustomer(tenantId, loginId);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void guardTenantAndStore(UUID tenantId, UUID storeId) {
    if (!tenantStatusRepo.isActive(tenantId))
      throw ApiException.conflict(
          "TENANT_NOT_OPERATIONAL",
          "Tenant is suspended or blocked — cart operations are unavailable");
    if (!storeStatusRepo.isActive(tenantId, storeId))
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL",
          "Store is closed or suspended — cart operations are unavailable for this location");
  }

  private Cart resolveCart(UUID tenantId, String cartId, String sessionId, TenantContext ctx) {
    if (cartId != null && !cartId.isBlank()) {
      Cart cart =
          repo.findById(tenantId, parseUuid(cartId, "cartId"))
              .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", "cart not found"));
      requireOwnership(cart, ctx, sessionId);
      return cart;
    }
    if (sessionId != null && !sessionId.isBlank()) {
      return repo.findActiveBySession(tenantId, sessionId)
          .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", "cart not found"));
    }
    if (ctx.userId() != null) {
      return repo.findActiveByCustomer(tenantId, ctx.userId())
          .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", "no active cart"));
    }
    throw ApiException.badRequest("CART_NO_IDENTITY", "cartId, session, or auth required");
  }

  /**
   * Object-level authZ for cart-by-cartId lookups: a {@code cartId} alone is not proof of
   * ownership. Staff may operate on any cart in the tenant (assisted shopping); an authenticated
   * customer's cart must belong to them; a guest cart requires knowledge of the session token it
   * was created with (the cartId is returned to any caller who can view it, the sessionId is not).
   */
  private void requireOwnership(Cart cart, TenantContext ctx, String suppliedSessionId) {
    if (isStaff(ctx)) return;
    if (cart.customerId() != null) {
      if (!cart.customerId().equals(ctx.userId()))
        throw ApiException.notFound("CART_NOT_FOUND", "cart not found");
      return;
    }
    if (cart.sessionId() == null || !cart.sessionId().equals(suppliedSessionId))
      throw ApiException.notFound("CART_NOT_FOUND", "cart not found");
  }

  private boolean isStaff(TenantContext ctx) {
    return ctx.hasRole("CASHIER")
        || ctx.hasRole("STOREKEEPER")
        || ctx.hasRole("MANAGER")
        || ctx.hasRole("OWNER");
  }

  private UUID parseUuid(String val, String field) {
    try {
      return Ids.parse(val);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "INVALID_" + field.toUpperCase(Locale.ROOT),
          field + " must be a UUID",
          List.of(),
          e);
    }
  }

  private static final SecureRandom SESSION_RNG = new SecureRandom();

  /** A new opaque, high-entropy (256-bit) guest cart session token. */
  private static String newSessionToken() {
    byte[] bytes = new byte[32];
    SESSION_RNG.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private CartResponse toResponse(Cart c) {
    return new CartResponse(
        c.id().toString(),
        c.tenantId().toString(),
        c.customerId() != null ? c.customerId().toString() : null,
        c.sessionId(),
        c.storeId().toString(),
        c.status(),
        c.createdAt().toString(),
        c.updatedAt().toString());
  }

  private CartItemResponse toItemResponse(CartItem i) {
    return new CartItemResponse(
        i.id().toString(),
        i.cartId().toString(),
        i.variantId().toString(),
        i.qty(),
        i.unitPrice(),
        i.addedAt().toString());
  }
}
