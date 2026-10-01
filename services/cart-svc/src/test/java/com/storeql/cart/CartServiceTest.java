package com.storeql.cart;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.cart.domain.Domain.Cart;
import com.storeql.cart.domain.Domain.CartItem;
import com.storeql.cart.domain.Domain.StaffAction;
import com.storeql.cart.dto.Dtos.AddItemRequest;
import com.storeql.cart.dto.Dtos.CreateCartRequest;
import com.storeql.cart.dto.Dtos.UpdateItemQtyRequest;
import com.storeql.cart.repo.CartRepository;
import com.storeql.cart.service.CartService;
import com.storeql.ids.Ids;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CartService core rules. Repositories are replaced with simple stubs so no DB is
 * needed here; the Testcontainers integration test covers the real DB path.
 */
class CartServiceTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();

  private CartService service;

  // Simple in-memory stubs ──────────────────────────────────────────────────

  private boolean tenantActive = true;
  private boolean storeActive = true;
  private Cart insertedCart = null;
  private CartItem insertedItem = null;
  private StaffAction trace = null;
  private int plainWrites = 0;

  @BeforeEach
  void setUp() {
    TenantStatusRepository tenantRepo =
        new TenantStatusRepository() {
          @Override
          public boolean isActive(UUID tenantId) {
            return tenantActive;
          }

          @Override
          public boolean upsertTenantStatus(UUID t, String s, Instant i) {
            return true;
          }
        };

    StoreStatusRepository storeRepo =
        new StoreStatusRepository() {
          @Override
          public boolean isActive(UUID tenantId, UUID storeId) {
            return storeActive;
          }

          @Override
          public boolean upsertStoreStatus(UUID s, UUID t, String st, Instant i) {
            return true;
          }
        };

    CartRepository cartRepo =
        new CartRepository() {
          @Override
          public Cart insert(Cart c) {
            insertedCart = c;
            return c;
          }

          @Override
          public Optional<Cart> findActiveByCustomer(UUID tenantId, UUID customerId) {
            return Optional.empty();
          }

          @Override
          public Optional<Cart> findActiveBySession(UUID tenantId, String sessionId) {
            return Optional.empty();
          }

          @Override
          public Optional<Cart> findById(UUID tenantId, UUID cartId) {
            return insertedCart != null ? Optional.of(insertedCart) : Optional.empty();
          }

          @Override
          public List<CartItem> findItemsByCart(UUID tenantId, UUID cartId) {
            return insertedItem != null ? List.of(insertedItem) : List.of();
          }

          @Override
          public CartItem upsertItem(CartItem item) {
            insertedItem = item;
            return item;
          }

          @Override
          public CartItem upsertItem(CartItem item, StaffAction audit) {
            insertedItem = item;
            trace = audit;
            return item;
          }

          @Override
          public Optional<CartItem> findItemById(UUID tenantId, UUID itemId) {
            return insertedItem != null && insertedItem.id().equals(itemId)
                ? Optional.of(insertedItem)
                : Optional.empty();
          }

          @Override
          public void updateItemQty(UUID tenantId, UUID cartId, UUID itemId, BigDecimal qty) {
            plainWrites++;
          }

          @Override
          public void updateItemQty(
              UUID tenantId, UUID cartId, UUID itemId, BigDecimal qty, StaffAction audit) {
            trace = audit;
          }

          @Override
          public void deleteItem(UUID tenantId, UUID cartId, UUID itemId) {
            plainWrites++;
          }

          @Override
          public void deleteItem(UUID tenantId, UUID cartId, UUID itemId, StaffAction audit) {
            trace = audit;
          }
        };

    service = new CartService();
    // Inject stubs via field assignment (CDI not needed for unit tests).
    setField(service, "repo", cartRepo);
    setField(service, "tenantStatusRepo", tenantRepo);
    setField(service, "storeStatusRepo", storeRepo);
  }

  // ── Flow guard ────────────────────────────────────────────────────────────

  @Test
  void createCart_blockedWhenTenantSuspended() {
    tenantActive = false;
    var ctx = ctx(TENANT, CUSTOMER);
    var req = new CreateCartRequest(STORE.toString(), null);

    ApiException ex = assertThrows(ApiException.class, () -> service.createOrGetCart(ctx, req));
    assertThat(ex.code(), is("TENANT_NOT_OPERATIONAL"));
  }

  @Test
  void createCart_blockedWhenStoreClosed() {
    storeActive = false;
    var ctx = ctx(TENANT, CUSTOMER);
    var req = new CreateCartRequest(STORE.toString(), null);

    ApiException ex = assertThrows(ApiException.class, () -> service.createOrGetCart(ctx, req));
    assertThat(ex.code(), is("STORE_NOT_OPERATIONAL"));
  }

  @Test
  void addItem_blockedWhenTenantSuspended() {
    // Pre-create cart in active state.
    insertedCart =
        new Cart(
            Ids.newId(),
            TENANT,
            CUSTOMER,
            null,
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    tenantActive = false;
    var ctx = ctx(TENANT, CUSTOMER);
    var req =
        new AddItemRequest(
            insertedCart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null);

    ApiException ex = assertThrows(ApiException.class, () -> service.addItem(ctx, req));
    assertThat(ex.code(), is("TENANT_NOT_OPERATIONAL"));
  }

  // ── Happy path ────────────────────────────────────────────────────────────

  @Test
  void createCart_createsNewCartForAuthenticatedCustomer() {
    var ctx = ctx(TENANT, CUSTOMER);
    var req = new CreateCartRequest(STORE.toString(), null);

    var response = service.createOrGetCart(ctx, req);

    assertThat(response.status(), is(Cart.STATUS_ACTIVE));
    assertThat(response.storeId(), is(STORE.toString()));
  }

  // ── IDOR guard (finding #4) ──────────────────────────────────────────────

  @Test
  void addItem_blockedForCustomerWhoDoesNotOwnTheCart() {
    insertedCart =
        new Cart(
            Ids.newId(),
            TENANT,
            CUSTOMER,
            null,
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    UUID otherCustomer = Ids.newId();
    var ctx = ctx(TENANT, otherCustomer);
    var req =
        new AddItemRequest(
            insertedCart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null);

    ApiException ex = assertThrows(ApiException.class, () -> service.addItem(ctx, req));
    assertThat(ex.code(), is("CART_NOT_FOUND"));
  }

  @Test
  void addItem_allowedForStaffOnAnyCustomersCart() {
    insertedCart =
        new Cart(
            Ids.newId(),
            TENANT,
            CUSTOMER,
            null,
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    var ctx = ctx(TENANT, Ids.newId(), Set.of("CASHIER"));
    var req =
        new AddItemRequest(
            insertedCart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null);

    // must not throw — staff may operate on any cart in the tenant
    service.addItem(ctx, req);
  }

  // ── Assisted shopping leaves a trace ──────────────────────────────────────

  private Cart shoppersCart() {
    insertedCart =
        new Cart(
            Ids.newId(),
            TENANT,
            CUSTOMER,
            null,
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    return insertedCart;
  }

  @Test
  void staffAddingToAShoppersCartLeavesATraceNamingWhoAndInWhatRole() {
    Cart cart = shoppersCart();
    UUID staff = Ids.newId();
    UUID variant = Ids.newId();
    service.addItem(
        ctx(TENANT, staff, Set.of("CASHIER", "MANAGER")),
        new AddItemRequest(cart.id().toString(), variant.toString(), BigDecimal.TWO, null, null));

    assertThat(trace.action(), is(StaffAction.ADD_ITEM));
    assertThat(trace.actorId(), is(staff));
    assertThat(trace.actorRole(), is("MANAGER"));
    assertThat(trace.variantId(), is(variant));
    assertThat(trace.qty().compareTo(BigDecimal.TWO), is(0));
  }

  @Test
  void aShopperOnTheirOwnCartLeavesNoTrace() {
    Cart cart = shoppersCart();
    service.addItem(
        ctx(TENANT, CUSTOMER, Set.of("CUSTOMER")),
        new AddItemRequest(
            cart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null));
    assertThat(trace == null, is(true));
    assertThat(insertedItem != null, is(true));
  }

  @Test
  void staffOnTheirOwnCartLeaveNoTrace() {
    Cart cart = shoppersCart();
    service.addItem(
        ctx(TENANT, CUSTOMER, Set.of("CASHIER")),
        new AddItemRequest(
            cart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null));
    assertThat(trace == null, is(true));
  }

  @Test
  void staffChangingAndRemovingLeaveATraceEach() {
    Cart cart = shoppersCart();
    UUID staff = Ids.newId();
    var staffCtx = ctx(TENANT, staff, Set.of("STOREKEEPER"));
    service.addItem(
        ctx(TENANT, CUSTOMER, Set.of("CUSTOMER")),
        new AddItemRequest(
            cart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null));
    UUID item = insertedItem.id();

    service.updateItemQty(
        staffCtx, item, new UpdateItemQtyRequest(cart.id().toString(), BigDecimal.TEN, null));
    assertThat(trace.action(), is(StaffAction.SET_QTY));
    assertThat(trace.actorId(), is(staff));
    assertThat(trace.actorRole(), is("STOREKEEPER"));
    assertThat(trace.itemId(), is(item));
    assertThat(trace.qty().compareTo(BigDecimal.TEN), is(0));

    trace = null;
    service.removeItem(staffCtx, item, cart.id().toString(), null);
    assertThat(trace.action(), is(StaffAction.REMOVE_ITEM));
    assertThat(trace.itemId(), is(item));
    assertThat(plainWrites, is(0));
  }

  @Test
  void aShoppersOwnChangeAndRemovalUseTheUntracedWrites() {
    Cart cart = shoppersCart();
    var mine = ctx(TENANT, CUSTOMER, Set.of("CUSTOMER"));
    service.addItem(
        mine,
        new AddItemRequest(
            cart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null));
    UUID item = insertedItem.id();
    service.updateItemQty(
        mine, item, new UpdateItemQtyRequest(cart.id().toString(), BigDecimal.TEN, null));
    service.removeItem(mine, item, cart.id().toString(), null);
    assertThat(plainWrites, is(2));
    assertThat(trace == null, is(true));
  }

  // ── Change and removal obey the same guards as add ────────────────────────

  private Cart cartWithAnItem(String status) {
    insertedCart = shoppersCart();
    service.addItem(
        ctx(TENANT, CUSTOMER, Set.of("CUSTOMER")),
        new AddItemRequest(
            insertedCart.id().toString(), Ids.newId().toString(), BigDecimal.ONE, null, null));
    var c = insertedCart;
    insertedCart =
        new Cart(c.id(), TENANT, CUSTOMER, null, STORE, status, c.createdAt(), c.updatedAt());
    plainWrites = 0;
    return insertedCart;
  }

  @Test
  void changeAndRemoveRefuseACartThatIsNotActive() {
    for (String status : List.of(Cart.STATUS_CHECKED_OUT, Cart.STATUS_ABANDONED)) {
      Cart cart = cartWithAnItem(status);
      var mine = ctx(TENANT, CUSTOMER, Set.of("CUSTOMER"));
      UUID item = insertedItem.id();
      ApiException a =
          assertThrows(
              ApiException.class,
              () ->
                  service.updateItemQty(
                      mine,
                      item,
                      new UpdateItemQtyRequest(cart.id().toString(), BigDecimal.TEN, null)));
      assertThat(a.code(), is("CART_NOT_ACTIVE"));
      ApiException b =
          assertThrows(
              ApiException.class, () -> service.removeItem(mine, item, cart.id().toString(), null));
      assertThat(b.code(), is("CART_NOT_ACTIVE"));
      assertThat(plainWrites, is(0));
    }
  }

  @Test
  void changeAndRemoveRefuseASuspendedBusinessAndAClosedStore() {
    Cart cart = cartWithAnItem(Cart.STATUS_ACTIVE);
    var mine = ctx(TENANT, CUSTOMER, Set.of("CUSTOMER"));
    UUID item = insertedItem.id();
    var change = new UpdateItemQtyRequest(cart.id().toString(), BigDecimal.TEN, null);

    tenantActive = false;
    assertThat(
        assertThrows(ApiException.class, () -> service.updateItemQty(mine, item, change)).code(),
        is("TENANT_NOT_OPERATIONAL"));
    assertThat(
        assertThrows(
                ApiException.class,
                () -> service.removeItem(mine, item, cart.id().toString(), null))
            .code(),
        is("TENANT_NOT_OPERATIONAL"));

    tenantActive = true;
    storeActive = false;
    assertThat(
        assertThrows(ApiException.class, () -> service.updateItemQty(mine, item, change)).code(),
        is("STORE_NOT_OPERATIONAL"));
    assertThat(
        assertThrows(
                ApiException.class,
                () -> service.removeItem(mine, item, cart.id().toString(), null))
            .code(),
        is("STORE_NOT_OPERATIONAL"));
    assertThat(plainWrites, is(0));
  }

  @Test
  void addItem_guestCartRequiresMatchingSessionId() {
    insertedCart =
        new Cart(
            Ids.newId(),
            TENANT,
            null,
            "guest-session-abc",
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    var ctx = ctx(TENANT, null);
    var wrongSession =
        new AddItemRequest(
            insertedCart.id().toString(),
            Ids.newId().toString(),
            BigDecimal.ONE,
            null,
            "not-the-right-session");

    ApiException ex = assertThrows(ApiException.class, () -> service.addItem(ctx, wrongSession));
    assertThat(ex.code(), is("CART_NOT_FOUND"));

    var rightSession =
        new AddItemRequest(
            insertedCart.id().toString(),
            Ids.newId().toString(),
            BigDecimal.ONE,
            null,
            "guest-session-abc");
    service.addItem(ctx, rightSession); // must not throw
  }

  @Test
  void createCart_mintsHighEntropySessionForGuest() {
    var ctx = ctx(TENANT, null); // guest: no customer identity
    var req = new CreateCartRequest(STORE.toString(), null); // no client session supplied

    var response = service.createOrGetCart(ctx, req);

    // Server minted a session token (256-bit → 43-char base64url) and stored it on the cart.
    assertThat(response.customerId(), is((String) null));
    org.junit.jupiter.api.Assertions.assertNotNull(response.sessionId());
    org.junit.jupiter.api.Assertions.assertTrue(
        response.sessionId().length() >= 40, "session token must be high-entropy");
    assertThat(insertedCart.sessionId(), is(response.sessionId()));
  }

  @Test
  void createCart_ignoresClientChosenSessionWhenCreatingNewGuestCart() {
    var ctx = ctx(TENANT, null);
    // A client tries to name its own (weak, guessable) session. No cart exists for it
    // (stub findActiveBySession returns empty), so the server must NOT create one under
    // that id — it mints its own instead.
    var req = new CreateCartRequest(STORE.toString(), "weak-guessable-123");

    var response = service.createOrGetCart(ctx, req);

    org.junit.jupiter.api.Assertions.assertNotEquals("weak-guessable-123", response.sessionId());
    org.junit.jupiter.api.Assertions.assertTrue(response.sessionId().length() >= 40);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static TenantContext ctx(UUID tenantId, UUID userId) {
    return ctx(tenantId, userId, Set.of());
  }

  private static TenantContext ctx(UUID tenantId, UUID userId, Set<String> roles) {
    return new TenantContext() {
      @Override
      public UUID requireTenantId() {
        return tenantId;
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }

      @Override
      public UUID userId() {
        return userId;
      }

      @Override
      public boolean hasRole(String role) {
        return roles.contains(role);
      }
    };
  }

  private static void setField(Object target, String name, Object value) {
    try {
      var f = findField(target.getClass(), name);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("cannot inject field " + name, e);
    }
  }

  private static java.lang.reflect.Field findField(Class<?> cls, String name) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      for (var f : c.getDeclaredFields()) {
        if (f.getName().equals(name)) return f;
      }
    }
    throw new RuntimeException("field not found: " + name);
  }
}
