package com.storeql.purchase.service;

import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;

/**
 * The gate on what in purchasing belongs to the business as a whole rather than to one of its
 * stores: its accounting connection and every journal's push, its e-invoice inbox settings and the
 * fetch that fills it, its deferred-revenue estimates, its supplier master data, its payment runs
 * and the accounts they pay from, a journal posted at no store, a consignment statement, a dropship
 * arrangement. A caller held to particular stores (a manager of one branch) acts at those stores
 * and no further; only a caller held to none — an owner, a business-wide manager, the platform —
 * acts here. The same rule, and the same {@code 403 BUSINESS_WIDE_ONLY}, tenant-svc and
 * customer-svc apply to theirs.
 */
public final class BusinessWide {

  private BusinessWide() {}

  /**
   * @return true when the caller is held to no store, and so may act on what is the whole
   *     business's
   */
  public static boolean heldToNone(TenantContext ctx) {
    return ctx.storeIds().isEmpty();
  }

  /**
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY} for a caller held to stores
   */
  public static void require(TenantContext ctx) {
    require(
        ctx,
        "This belongs to the business as a whole, so it needs a caller who is not held to stores");
  }

  /**
   * @param why what the caller is told: what it is, and why one store's caller cannot act on it
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY} for a caller held to stores
   */
  public static void require(TenantContext ctx, String why) {
    if (!heldToNone(ctx)) {
      throw ApiException.forbidden("BUSINESS_WIDE_ONLY", why);
    }
  }
}
