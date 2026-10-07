package com.storeql.tenant.api;

import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;

/**
 * The gate on what concerns the business as a whole: its profile, currencies, plan, roles,
 * retention and the like. A caller held to particular stores (a manager of one branch) acts at
 * those stores and no further; only a caller held to none — an owner, a business-wide manager, the
 * platform — changes what belongs to every store.
 */
final class BusinessWide {

  private BusinessWide() {}

  /**
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY} for a caller held to stores
   */
  static void require(TenantContext ctx) {
    if (!ctx.storeIds().isEmpty()) {
      throw ApiException.forbidden(
          "BUSINESS_WIDE_ONLY",
          "This changes the business as a whole, so it needs a caller who is not held to stores");
    }
  }
}
