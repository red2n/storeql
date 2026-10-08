package com.storeql.web;

import java.util.Collection;
import java.util.Set;

/**
 * Who may see the system-health screen: a caller who holds {@link Permissions#SYSTEM_HEALTH} and is
 * held to no store. The screen shows the whole business's traffic and waiting work, and a request
 * does not name its store to the gateway, so a caller held to stores is not given a business-wide
 * view in the first version ({@code BUSINESS_WIDE_ONLY}).
 *
 * <p>One rule for the gateway (which reads the verified headers) and for reporting-svc (which reads
 * its {@link TenantContext}), so the two cannot disagree about who is let in.
 */
public final class SystemHealthAccess {

  /** The caller does not hold {@link Permissions#SYSTEM_HEALTH}. */
  public static final String NOT_PERMITTED = "SYSTEM_HEALTH_NOT_PERMITTED";

  /** The caller is held to stores and asked for the whole business. */
  public static final String BUSINESS_WIDE_ONLY = "BUSINESS_WIDE_ONLY";

  private SystemHealthAccess() {}

  /**
   * Judges the caller of a request already known to belong to a business.
   *
   * @param ctx the request's caller
   * @throws ApiException 403 {@code SYSTEM_HEALTH_NOT_PERMITTED}, else 403 {@code
   *     BUSINESS_WIDE_ONLY} for a caller held to stores
   */
  public static void require(TenantContext ctx) {
    require(ctx.roles(), ctx.permissions(), ctx.storeIds());
  }

  /**
   * @param roles the roles carried in the token
   * @param permissionClaim the token's permission claim, or {@code null} when it carries none (the
   *     roles' defaults then apply)
   * @param storeIds the stores the caller is held to; empty for a caller held to none
   * @throws ApiException 403 {@code SYSTEM_HEALTH_NOT_PERMITTED}, else 403 {@code
   *     BUSINESS_WIDE_ONLY} for a caller held to stores
   */
  public static void require(
      Set<String> roles, Set<String> permissionClaim, Collection<?> storeIds) {
    if (!holds(roles, permissionClaim)) {
      throw ApiException.forbidden(
          NOT_PERMITTED, "This screen needs the " + Permissions.SYSTEM_HEALTH + " permission");
    }
    if (storeIds != null && !storeIds.isEmpty()) {
      throw ApiException.forbidden(
          BUSINESS_WIDE_ONLY,
          "The system's health covers the whole business; a caller held to stores cannot see it");
    }
  }

  /** The same judgement as {@link TenantContext#hasPermission}, for a caller with no context. */
  private static boolean holds(Set<String> roles, Set<String> claim) {
    if (Permissions.unrestricted(roles)) return true;
    Set<String> held = claim != null ? claim : Permissions.effective(roles);
    return held.contains(Permissions.SYSTEM_HEALTH);
  }
}
