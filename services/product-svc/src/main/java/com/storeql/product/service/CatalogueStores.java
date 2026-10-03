package com.storeql.product.service;

import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stores a catalogue write names, checked before anything is written.
 *
 * <p>Stores are tenant-svc's (database-per-service), so a store id product-svc is handed is asked
 * about there, through {@link TenantProfiles#stores}, as {@code AssortmentService} does for a
 * range. Each must be one of the business's own, and then, for a caller held to stores, one of
 * theirs:
 *
 * <ul>
 *   <li>Another business's store and one nobody made look the same from here and are refused the
 *       same way, {@code 404 PRODUCT_STORE_NOT_FOUND}, so the answer says nothing about who holds
 *       an id.
 *   <li>A store of the business the caller is not held to is {@code 403 STORE_ACCESS_DENIED}, as at
 *       every other door a manager held to stores comes to.
 *   <li>When tenant-svc cannot say, the write is refused ({@code 503 TENANT_STORES_UNAVAILABLE}):
 *       an id that cannot be checked is not accepted unchecked.
 * </ul>
 *
 * <p>A write that names no store does not ask tenant-svc anything, and is never refused for it by
 * tenant-svc. A product that names none is sold at every store, though, which only a caller held to
 * no store may make it: a new product of a caller held to stores that names none is sold at their
 * stores ({@link #rangeOfNew}), and moving a product to or from every store is refused them as the
 * whole business's to decide ({@code 403 BUSINESS_WIDE_ONLY}, {@link #requireChangeHeldTo}).
 *
 * <p>The range (07.18, {@code AssortmentService}) asks the same question of the stores a cluster, a
 * change or a review names, through the same {@link #requireOwn}, with its own refusal ({@code 404
 * ASSORTMENT_STORE_NOT_FOUND}): one check, each door's code as it has always been on the wire.
 */
final class CatalogueStores {

  /** The refusal for a store that is not one of the business's, at a catalogue write. */
  static final String NOT_FOUND = "PRODUCT_STORE_NOT_FOUND";

  /** The refusal for a store that is not one of the business's, at a range write (07.18). */
  static final String ASSORTMENT_NOT_FOUND = "ASSORTMENT_STORE_NOT_FOUND";

  /** As {@link #NOT_FOUND}, for a store a fixture or a space plan names. */
  static final String MERCH_NOT_FOUND = "MERCH_STORE_NOT_FOUND";

  /** The refusal for a store the caller is not held to. */
  static final String ACCESS_DENIED = "STORE_ACCESS_DENIED";

  /**
   * The refusal for what is the whole business's to decide — selling a product at every store, or
   * no longer at every store; aiming a range change at a cluster; launching, discontinuing,
   * delisting or reinstating a line, or taking one of its variants away (a delist, a {@code
   * REPLACE} import), whatever its range — to a caller held to stores. The platform's code for it
   * (tenant-svc, purchase-svc and the rest answer it too): the store may well be theirs, so "not
   * one of your stores" would not say what is wrong.
   */
  static final String BUSINESS_WIDE_ONLY = "BUSINESS_WIDE_ONLY";

  private CatalogueStores() {}

  /**
   * Each store must be one of the business's own, and the caller's.
   *
   * @param profiles tenant-svc's stores, cached
   * @param ctx the caller
   * @param storeIds the stores the write names; empty asks nothing
   * @throws ApiException 404 {@code PRODUCT_STORE_NOT_FOUND} for a store that is not the
   *     business's; 403 {@code STORE_ACCESS_DENIED} for one the caller is not held to; 503 {@code
   *     TENANT_STORES_UNAVAILABLE} when tenant-svc cannot be read
   */
  static void require(TenantProfiles profiles, TenantContext ctx, Collection<UUID> storeIds) {
    requireOwn(profiles, ctx.requireTenantId(), storeIds, NOT_FOUND);
    for (UUID storeId : storeIds) {
      ctx.requireStoreAccess(storeId);
    }
  }

  /**
   * Each store must be one of the business's own, as tenant-svc (which owns stores) says. Every one
   * is checked before the caller is asked about any, so a list naming another business's store is
   * not found whoever sends it.
   *
   * <p>Another business's store and a store nobody made look the same from here and are refused the
   * same way, so the answer tells a caller nothing about who holds an id. A store opened a moment
   * ago is found: {@link TenantProfiles#stores} looks again for one its cache does not know, at
   * most every thirty seconds. A store named twice is asked about once.
   *
   * @param profiles tenant-svc's stores, cached
   * @param tenantId the business
   * @param storeIds the stores the write names; empty asks nothing
   * @param notFoundCode the refusal this door answers with: {@link #NOT_FOUND} for the catalogue,
   *     {@link #ASSORTMENT_NOT_FOUND} for the range
   * @throws ApiException 404 with {@code notFoundCode}; 503 {@code TENANT_STORES_UNAVAILABLE} when
   *     tenant-svc cannot be read, because an id that cannot be checked is refused and not accepted
   */
  static void requireOwn(
      TenantProfiles profiles, UUID tenantId, Collection<UUID> storeIds, String notFoundCode) {
    for (UUID storeId : new LinkedHashSet<>(storeIds)) {
      if (!profiles.stores(tenantId, storeId).has(storeId)) {
        throw ApiException.notFound(notFoundCode, "No such store in this business");
      }
    }
  }

  /**
   * Where a <em>new</em> product is sold: the stores the write names, each once and in the order
   * named; or, naming none, every store for a caller held to none (an owner, a manager of the whole
   * business) and the caller's own stores, all of them, for a caller held to stores.
   *
   * <p>A product with no stores is on every shelf of the business, so a manager held to stores
   * never makes one: their new line is a local line, ranged where they keep stock, as store-level
   * staff's new items are in retail systems generally. It is not refused for naming none, because
   * the admin app's product form names none, and a refusal there could not be acted on. Their
   * stores come from their sign-in (as the gateway passes it), not from the request, so tenant-svc
   * is not asked about them. The stores a write <em>names</em> are checked first ({@link
   * #require}); this only decides what naming none means.
   *
   * @param ctx the caller
   * @param named the stores the write names, already checked; empty for none
   * @return the stores the product is to be sold at; empty for every store
   */
  static List<UUID> rangeOfNew(TenantContext ctx, Collection<UUID> named) {
    if (!named.isEmpty() || ctx.storeIds().isEmpty()) {
      return List.copyOf(new LinkedHashSet<>(named));
    }
    return ctx.storeIds().stream().sorted().collect(Collectors.toUnmodifiableList());
  }

  /**
   * What only a caller held to no store may do.
   *
   * @param ctx the caller
   * @param why what the caller is told: what it is, and why a manager of some stores cannot do it
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY} for a caller held to stores
   */
  static void requireBusinessWide(TenantContext ctx, String why) {
    if (!ctx.storeIds().isEmpty()) {
      throw ApiException.forbidden(BUSINESS_WIDE_ONLY, why);
    }
  }

  /**
   * What a caller held to stores may write to a line (3 Oct 2026): only a line ranged solely to
   * stores they hold. Item master data is kept centrally; a branch edits what is local to it. A
   * line sold everywhere (no range rows) or at any store beyond theirs is the business's, because
   * the product, its variants' SKUs and barcodes, its images, safety and compliance information are
   * what every store sells from. A caller held to no store may write to any line.
   *
   * @param ctx the caller
   * @param range the stores the line is sold at now; empty for every store
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY} when the caller is held to stores and the
   *     line is sold at every store or at a store beyond theirs
   */
  static void requireLineHeldTo(TenantContext ctx, Collection<UUID> range) {
    if (ctx.storeIds().isEmpty()) {
      return;
    }
    if (range.isEmpty() || !ctx.storeIds().containsAll(range)) {
      throw ApiException.forbidden(
          BUSINESS_WIDE_ONLY,
          "This line is sold beyond your stores, so it is for an owner or a manager of the whole"
              + " business to change");
    }
  }

  /**
   * What a caller held to stores may add to a product's range without taking anything away, as an
   * import does: the range as it would stand ({@code current} with {@code added}) is held to {@link
   * #requireChangeHeldTo}. Adding a store to a product sold everywhere takes it off every other
   * shelf, so it is that rule's "no longer at every store".
   *
   * @param ctx the caller
   * @param current the stores the product is sold at now; empty for every store
   * @param added the stores to add
   * @throws ApiException 403 {@code STORE_ACCESS_DENIED} for a store not theirs; 403 {@code
   *     BUSINESS_WIDE_ONLY} for narrowing a product sold at every store
   */
  static void requireAdditionHeldTo(
      TenantContext ctx, Collection<UUID> current, Collection<UUID> added) {
    var after = new LinkedHashSet<UUID>(current);
    after.addAll(added);
    requireChangeHeldTo(ctx, List.copyOf(current), List.copyOf(after));
  }

  /**
   * What a caller held to stores may do to a product's store assortment, which is replaced whole.
   * The stores the change touches — added or taken away — must all be theirs; a store left as it
   * was is no write there, and is not asked about. "Sold everywhere" (no stores) touches every
   * store, so moving a product to it or from it is never theirs to decide: that is refused as the
   * whole business's ({@code BUSINESS_WIDE_ONLY}), not as a store that is not theirs. A caller held
   * to no store may do any of it.
   *
   * <p>Asked inside the transaction that writes the change, of the range read there with the
   * product row locked ({@code ProductRepository.setStoresForProduct}, {@code
   * addStoreAssignments}): asked of a range read before it, a change could be allowed on a range
   * another change had already replaced.
   *
   * @param ctx the caller
   * @param current the stores the product is sold at now; empty for every store
   * @param wanted the stores it is to be sold at; empty for every store
   * @throws ApiException 403 {@code STORE_ACCESS_DENIED} for a store added or taken away that is
   *     not theirs; 403 {@code BUSINESS_WIDE_ONLY} for a move to or from every store
   */
  static void requireChangeHeldTo(TenantContext ctx, List<UUID> current, List<UUID> wanted) {
    if (ctx.storeIds().isEmpty()) {
      return;
    }
    Set<UUID> before = Set.copyOf(current);
    Set<UUID> after = Set.copyOf(wanted);
    if (before.equals(after)) {
      return;
    }
    if (before.isEmpty() || after.isEmpty()) {
      throw ApiException.forbidden(
          BUSINESS_WIDE_ONLY,
          "Selling a product at every store, or no longer at every store, is for an owner or a"
              + " manager of the whole business");
    }
    for (UUID added : after) {
      if (!before.contains(added)) ctx.requireStoreAccess(added);
    }
    for (UUID removed : before) {
      if (!after.contains(removed)) ctx.requireStoreAccess(removed);
    }
  }
}
