package com.storeql.cart.repo;

import com.storeql.cart.domain.Domain.Cart;
import com.storeql.cart.domain.Domain.CartItem;
import com.storeql.ids.Ids;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.service.RedisCache;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistence for {@link Cart} and {@link CartItem}. All queries filter tenant_id first.
 *
 * <p>Cart-row and item-list reads are cached in Redis (cache-aside, short TTL as a backstop) since
 * they are re-read on nearly every cart request. Only positive lookups are cached — a miss simply
 * falls through to Postgres next time, which sidesteps having to invalidate a cached "not found"
 * the instant a cart is created.
 */
@ApplicationScoped
public class CartRepository extends BaseJdbcRepository {

  /**
   * Field separator for the flat cache encoding — Postgres TEXT columns can never contain a NUL
   * byte, so this never collides with real content and needs no escaping.
   */
  private static final String FS = "\u0000";

  /** Row separator for the cached item list — same NUL-safety argument as {@link #FS}. */
  private static final String RS = "\u0001";

  private static final long CART_TTL_SECONDS = 60;
  private static final long ITEMS_TTL_SECONDS = 60;

  @Inject RedisCache cache;

  // ── Cache ─────────────────────────────────────────────────────────────────

  private static String cartKey(UUID tenantId, UUID cartId) {
    return "cart:" + tenantId + ":" + cartId;
  }

  private static String activeByCustomerKey(UUID tenantId, UUID customerId) {
    return "cart:active:customer:" + tenantId + ":" + customerId;
  }

  private static String activeBySessionKey(UUID tenantId, String sessionId) {
    return "cart:active:session:" + tenantId + ":" + sessionId;
  }

  private static String itemsKey(UUID tenantId, UUID cartId) {
    return "cart-items:" + tenantId + ":" + cartId;
  }

  private Optional<Cart> cachedCart(UUID tenantId, UUID cartId) {
    String cached = cache.get(cartKey(tenantId, cartId));
    return cached == null ? Optional.empty() : Optional.of(decodeCart(cached));
  }

  private void cacheCart(Cart c) {
    cache.put(cartKey(c.tenantId(), c.id()), encodeCart(c), CART_TTL_SECONDS);
  }

  /**
   * Invalidates the cached row. Pointer caches (active-by-customer/session) self-heal: they store
   * only the cart id and re-validate the row's status on next read.
   */
  private void evictCart(UUID tenantId, UUID cartId) {
    cache.evict(cartKey(tenantId, cartId));
  }

  private Optional<List<CartItem>> cachedItems(UUID tenantId, UUID cartId) {
    String cached = cache.get(itemsKey(tenantId, cartId));
    if (cached == null) return Optional.empty();
    if (cached.isEmpty()) return Optional.of(List.of());
    return Optional.of(Arrays.stream(cached.split(RS)).map(CartRepository::decodeItem).toList());
  }

  private void cacheItems(UUID tenantId, UUID cartId, List<CartItem> items) {
    String encoded = items.stream().map(CartRepository::encodeItem).collect(Collectors.joining(RS));
    cache.put(itemsKey(tenantId, cartId), encoded, ITEMS_TTL_SECONDS);
  }

  private void evictItems(UUID tenantId, UUID cartId) {
    cache.evict(itemsKey(tenantId, cartId));
  }

  private static String encodeCart(Cart c) {
    return String.join(
        FS,
        c.id().toString(),
        c.tenantId().toString(),
        c.customerId() == null ? "" : c.customerId().toString(),
        c.sessionId() == null ? "" : c.sessionId(),
        c.storeId().toString(),
        c.status(),
        c.createdAt().toString(),
        c.updatedAt().toString());
  }

  private static Cart decodeCart(String s) {
    String[] f = s.split(FS, -1);
    return new Cart(
        Ids.parse(f[0]),
        Ids.parse(f[1]),
        f[2].isEmpty() ? null : Ids.parse(f[2]),
        f[3].isEmpty() ? null : f[3],
        Ids.parse(f[4]),
        f[5],
        Instant.parse(f[6]),
        Instant.parse(f[7]));
  }

  private static String encodeItem(CartItem i) {
    return String.join(
        FS,
        i.id().toString(),
        i.cartId().toString(),
        i.tenantId().toString(),
        i.variantId().toString(),
        i.qty().toString(),
        i.unitPrice() == null ? "" : i.unitPrice().toString(),
        i.addedAt().toString());
  }

  private static CartItem decodeItem(String s) {
    String[] f = s.split(FS, -1);
    return new CartItem(
        Ids.parse(f[0]),
        Ids.parse(f[1]),
        Ids.parse(f[2]),
        Ids.parse(f[3]),
        new BigDecimal(f[4]),
        f[5].isEmpty() ? null : new BigDecimal(f[5]),
        Instant.parse(f[6]));
  }

  // ── Carts ─────────────────────────────────────────────────────────────────

  /**
   * Inserts a cart and returns it as stored.
   *
   * @param c the cart to persist; its {@code id} must already be a {@code Ids.newId()} UUIDv7
   * @return the row read back after insert, with the server-side timestamps applied
   * @throws com.storeql.web.ApiException {@code DUPLICATE} when the partial unique index on an
   *     active cart per customer/session rejects a concurrent second insert
   */
  public Cart insert(Cart c) {
    exec(
        "INSERT INTO carts (id, tenant_id, customer_id, session_id, store_id, status,"
            + " created_at, updated_at) VALUES (?,?,?,?,?,?, now(), now())",
        ps -> {
          ps.setObject(1, c.id());
          ps.setObject(2, c.tenantId());
          ps.setObject(3, c.customerId());
          ps.setString(4, c.sessionId());
          ps.setObject(5, c.storeId());
          ps.setString(6, c.status());
        },
        "insert cart");
    return findById(c.tenantId(), c.id()).orElseThrow();
  }

  /**
   * Looks a cart up by id, reading through the row cache.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param cartId the cart to fetch
   * @return the cart, or empty when no such cart exists in this tenant
   */
  public Optional<Cart> findById(UUID tenantId, UUID cartId) {
    Optional<Cart> cached = cachedCart(tenantId, cartId);
    if (cached.isPresent()) return cached;
    Optional<Cart> fresh =
        query(
                "SELECT id, tenant_id, customer_id, session_id, store_id, status,"
                    + " created_at, updated_at FROM carts WHERE tenant_id = ? AND id = ?",
                ps -> {
                  ps.setObject(1, tenantId);
                  ps.setObject(2, cartId);
                },
                this::mapCart,
                "find cart by id")
            .stream()
            .findFirst();
    fresh.ifPresent(this::cacheCart);
    return fresh;
  }

  /**
   * The active-by-customer/session pointer caches store only a cart id; on a hit we re-fetch the
   * row (itself cached) and re-check status before trusting it — a cart that has since been checked
   * out or abandoned must not be handed back as "the active cart" just because the pointer hasn't
   * expired yet.
   */
  public Optional<Cart> findActiveByCustomer(UUID tenantId, UUID customerId) {
    String pointerKey = activeByCustomerKey(tenantId, customerId);
    String pointedId = cache.get(pointerKey);
    if (pointedId != null) {
      Optional<Cart> cart = findById(tenantId, Ids.parse(pointedId));
      if (cart.isPresent() && Cart.STATUS_ACTIVE.equals(cart.get().status())) return cart;
      cache.evict(pointerKey);
    }
    Optional<Cart> fresh =
        query(
                "SELECT id, tenant_id, customer_id, session_id, store_id, status,"
                    + " created_at, updated_at FROM carts"
                    + " WHERE tenant_id = ? AND customer_id = ? AND status = 'ACTIVE' LIMIT 1",
                ps -> {
                  ps.setObject(1, tenantId);
                  ps.setObject(2, customerId);
                },
                this::mapCart,
                "find active cart by customer")
            .stream()
            .findFirst();
    fresh.ifPresent(
        c -> {
          cacheCart(c);
          cache.put(pointerKey, c.id().toString(), CART_TTL_SECONDS);
        });
    return fresh;
  }

  /** See {@link #findActiveByCustomer} for the pointer-cache + status-recheck rationale. */
  public Optional<Cart> findActiveBySession(UUID tenantId, String sessionId) {
    String pointerKey = activeBySessionKey(tenantId, sessionId);
    String pointedId = cache.get(pointerKey);
    if (pointedId != null) {
      Optional<Cart> cart = findById(tenantId, Ids.parse(pointedId));
      if (cart.isPresent() && Cart.STATUS_ACTIVE.equals(cart.get().status())) return cart;
      cache.evict(pointerKey);
    }
    Optional<Cart> fresh =
        query(
                "SELECT id, tenant_id, customer_id, session_id, store_id, status,"
                    + " created_at, updated_at FROM carts"
                    + " WHERE tenant_id = ? AND session_id = ? AND status = 'ACTIVE' LIMIT 1",
                ps -> {
                  ps.setObject(1, tenantId);
                  ps.setString(2, sessionId);
                },
                this::mapCart,
                "find active cart by session")
            .stream()
            .findFirst();
    fresh.ifPresent(
        c -> {
          cacheCart(c);
          cache.put(pointerKey, c.id().toString(), CART_TTL_SECONDS);
        });
    return fresh;
  }

  /** Marks a cart's status and touches updated_at. */
  public void updateStatus(UUID tenantId, UUID cartId, String status) {
    exec(
        "UPDATE carts SET status = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, tenantId);
          ps.setObject(3, cartId);
        },
        "update cart status");
    evictCart(tenantId, cartId);
  }

  /**
   * Marks the customer's ACTIVE cart at the given store as CHECKED_OUT. Called when an OrderPlaced
   * event arrives for a known customer. No-op if no matching cart exists.
   *
   * <p>{@code RETURNING id} gives us the affected cart's id (at most one, per the unique
   * active-cart-per-customer index), so both the row cache and the items cache are evicted
   * immediately instead of waiting out {@code CART_TTL_SECONDS}.
   */
  public void markCheckedOutByCustomerAndStore(UUID tenantId, UUID customerId, UUID storeId) {
    UUID cartId =
        inTx(
            c -> {
              try (var ps =
                  c.prepareStatement(
                      "UPDATE carts SET status = 'CHECKED_OUT', updated_at = now()"
                          + " WHERE tenant_id = ? AND customer_id = ? AND store_id = ?"
                          + " AND status = 'ACTIVE' RETURNING id")) {
                ps.setObject(1, tenantId);
                ps.setObject(2, customerId);
                ps.setObject(3, storeId);
                try (var rs = ps.executeQuery()) {
                  return rs.next() ? rs.getObject("id", UUID.class) : null;
                }
              }
            },
            "mark cart checked out");
    evictCheckedOut(tenantId, customerId, cartId);
  }

  /**
   * Marks the shopper's ACTIVE cart checked out, whichever store it was filled at: an online order
   * is placed at the store its delivery resolves to, or split across several (order orchestration),
   * and a shopper has one active cart in a tenant (the unique index), so the store the cart names
   * is not the order's to match.
   */
  public void markCheckedOutByCustomer(UUID tenantId, UUID customerId) {
    UUID cartId =
        inTx(
            c -> {
              try (var ps =
                  c.prepareStatement(
                      "UPDATE carts SET status = 'CHECKED_OUT', updated_at = now()"
                          + " WHERE tenant_id = ? AND customer_id = ? AND status = 'ACTIVE'"
                          + " RETURNING id")) {
                ps.setObject(1, tenantId);
                ps.setObject(2, customerId);
                try (var rs = ps.executeQuery()) {
                  return rs.next() ? rs.getObject("id", UUID.class) : null;
                }
              }
            },
            "mark the shopper's cart checked out");
    evictCheckedOut(tenantId, customerId, cartId);
  }

  private void evictCheckedOut(UUID tenantId, UUID customerId, UUID cartId) {
    cache.evict(activeByCustomerKey(tenantId, customerId));
    if (cartId != null) {
      evictCart(tenantId, cartId);
      evictItems(tenantId, cartId);
    }
  }

  // ── Items ─────────────────────────────────────────────────────────────────

  /**
   * Upserts an item: if the variant is already in the cart, qty is incremented and unit_price is
   * updated if the new value is non-null.
   */
  public CartItem upsertItem(CartItem item) {
    CartItem result =
        inTx(
            c -> {
              try (var ps =
                  c.prepareStatement(
                      "INSERT INTO cart_items (id, cart_id, tenant_id, variant_id, qty,"
                          + " unit_price, added_at) VALUES (?,?,?,?,?,?, now())"
                          + " ON CONFLICT (cart_id, variant_id) DO UPDATE SET"
                          + "   qty        = cart_items.qty + EXCLUDED.qty,"
                          + "   unit_price = COALESCE(EXCLUDED.unit_price, cart_items.unit_price)"
                          + " RETURNING id, cart_id, tenant_id, variant_id, qty, unit_price,"
                          + " added_at")) {
                ps.setObject(1, item.id());
                ps.setObject(2, item.cartId());
                ps.setObject(3, item.tenantId());
                ps.setObject(4, item.variantId());
                ps.setBigDecimal(5, item.qty());
                ps.setBigDecimal(6, item.unitPrice());
                try (var rs = ps.executeQuery()) {
                  if (rs.next()) return mapItem(rs);
                }
              }
              throw ApiException.unprocessable("CART_ITEM_UPSERT_FAILED", "upsert returned no row");
            },
            "upsert cart item");
    evictItems(result.tenantId(), result.cartId());
    return result;
  }

  /**
   * Looks a single cart item up by id.
   *
   * <p>Not cart-scoped: callers that care which cart the item belongs to must compare {@code
   * cartId} themselves, as {@code CartService} does before mutating an item.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param itemId the cart item to fetch
   * @return the item, or empty when no such item exists in this tenant
   */
  public Optional<CartItem> findItemById(UUID tenantId, UUID itemId) {
    return query(
            "SELECT id, cart_id, tenant_id, variant_id, qty, unit_price, added_at"
                + " FROM cart_items WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, itemId);
            },
            this::mapItem,
            "find cart item")
        .stream()
        .findFirst();
  }

  /**
   * Lists a cart's items oldest-first, reading through the item-list cache.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param cartId the cart whose items to list
   * @return the items ordered by {@code added_at}, empty when the cart has none
   */
  public List<CartItem> findItemsByCart(UUID tenantId, UUID cartId) {
    Optional<List<CartItem>> cached = cachedItems(tenantId, cartId);
    if (cached.isPresent()) return cached.get();
    List<CartItem> fresh =
        query(
            "SELECT id, cart_id, tenant_id, variant_id, qty, unit_price, added_at"
                + " FROM cart_items WHERE tenant_id = ? AND cart_id = ? ORDER BY added_at",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, cartId);
            },
            this::mapItem,
            "list cart items");
    cacheItems(tenantId, cartId, fresh);
    return fresh;
  }

  /**
   * Sets a cart item's quantity and evicts the cart's cached item list.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param cartId the owning cart, also matched so an item cannot be moved between carts
   * @param itemId the cart item to update
   * @param qty the new quantity
   */
  public void updateItemQty(UUID tenantId, UUID cartId, UUID itemId, BigDecimal qty) {
    exec(
        "UPDATE cart_items SET qty = ? WHERE tenant_id = ? AND cart_id = ? AND id = ?",
        ps -> {
          ps.setBigDecimal(1, qty);
          ps.setObject(2, tenantId);
          ps.setObject(3, cartId);
          ps.setObject(4, itemId);
        },
        "update cart item qty");
    evictItems(tenantId, cartId);
  }

  /**
   * Deletes a cart item and evicts the cart's cached item list.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param cartId the owning cart, also matched so one cart cannot delete another's item
   * @param itemId the cart item to delete
   */
  public void deleteItem(UUID tenantId, UUID cartId, UUID itemId) {
    exec(
        "DELETE FROM cart_items WHERE tenant_id = ? AND cart_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, cartId);
          ps.setObject(3, itemId);
        },
        "delete cart item");
    evictItems(tenantId, cartId);
  }

  // ── Merge ─────────────────────────────────────────────────────────────────

  /**
   * Merges all items from the guest cart into the target cart within one transaction. Items already
   * present have their qty incremented. The guest cart is then marked ABANDONED.
   */
  public void mergeItems(UUID tenantId, UUID guestCartId, UUID targetCartId) {
    inTx(
        c -> {
          // Upsert each guest item into the target cart.
          try (var sel =
              c.prepareStatement(
                  "SELECT id, cart_id, tenant_id, variant_id, qty, unit_price FROM cart_items"
                      + " WHERE tenant_id = ? AND cart_id = ?")) {
            sel.setObject(1, tenantId);
            sel.setObject(2, guestCartId);
            try (var rs = sel.executeQuery()) {
              while (rs.next()) {
                try (var ins =
                    c.prepareStatement(
                        "INSERT INTO cart_items (id, cart_id, tenant_id, variant_id, qty,"
                            + " unit_price, added_at) VALUES (?,?,?,?,?,?,now())"
                            + " ON CONFLICT (cart_id, variant_id) DO UPDATE SET"
                            + "   qty = cart_items.qty + EXCLUDED.qty,"
                            + "   unit_price = COALESCE(EXCLUDED.unit_price, cart_items.unit_price)")) {
                  ins.setObject(1, Ids.newId());
                  ins.setObject(2, targetCartId);
                  ins.setObject(3, tenantId);
                  ins.setObject(4, rs.getObject("variant_id", UUID.class));
                  ins.setBigDecimal(5, rs.getBigDecimal("qty"));
                  ins.setBigDecimal(6, rs.getBigDecimal("unit_price"));
                  ins.executeUpdate();
                }
              }
            }
          }
          // Abandon the guest cart.
          try (var upd =
              c.prepareStatement(
                  "UPDATE carts SET status = 'ABANDONED', updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            upd.setObject(1, tenantId);
            upd.setObject(2, guestCartId);
            upd.executeUpdate();
          }
          return null;
        },
        "merge guest cart");
    evictCart(tenantId, guestCartId);
    evictItems(tenantId, guestCartId);
    evictItems(tenantId, targetCartId);
  }

  // ── Mappers ───────────────────────────────────────────────────────────────

  private Cart mapCart(ResultSet rs) throws SQLException {
    return new Cart(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("session_id"),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private CartItem mapItem(ResultSet rs) throws SQLException {
    return new CartItem(
        rs.getObject("id", UUID.class),
        rs.getObject("cart_id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("unit_price"),
        rs.getObject("added_at", OffsetDateTime.class).toInstant());
  }
}
