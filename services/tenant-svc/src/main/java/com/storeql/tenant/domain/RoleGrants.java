package com.storeql.tenant.domain;

import com.storeql.web.ApiException;
import com.storeql.web.Permissions;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who may hand out what (flow catalogue: stf-staff-assignment-and-roles gap 1). Holding {@code
 * staff.manage} lets a person assign staff and define roles, so without a limit it is also the way
 * to raise oneself: assign OWNER to a friend, or define a role holding more than one holds. The
 * rules, in one place and pure:
 *
 * <ul>
 *   <li>The OWNER tier is granted by an OWNER and nobody else.
 *   <li>A role, built in or custom, is granted only if every permission it holds is held by the
 *       granter — nobody hands out more than they have. An owner holds everything.
 *   <li>{@code staff.manage} enters a custom role only by an OWNER's hand, so the power to manage
 *       staff cannot be copied downward by the people who hold it.
 * </ul>
 */
public final class RoleGrants {

  private RoleGrants() {}

  /**
   * Refuses an assignment the caller may not make.
   *
   * @param callerIsOwner whether the caller carries the OWNER role
   * @param callerUnrestricted whether the caller is never narrowed (an owner or the platform)
   * @param callerPermissions what the caller holds
   * @param baseTier the tier the assignment binds
   * @param granted the permissions the assigned role holds
   * @throws ApiException 403 {@code STAFF_OWNER_TIER_OWNER_ONLY} or {@code ROLE_EXCEEDS_CALLER}
   */
  public static void requireMayAssign(
      boolean callerIsOwner,
      boolean callerUnrestricted,
      Set<String> callerPermissions,
      String baseTier,
      Set<String> granted) {
    if ("OWNER".equals(baseTier) && !callerIsOwner) {
      throw ApiException.forbidden(
          "STAFF_OWNER_TIER_OWNER_ONLY", "Only an owner of the business may make another owner");
    }
    requireHeld(callerUnrestricted, callerPermissions, granted);
  }

  /**
   * Refuses a role definition or change the caller may not make.
   *
   * @param callerIsOwner whether the caller carries the OWNER role
   * @param callerUnrestricted whether the caller is never narrowed
   * @param callerPermissions what the caller holds
   * @param requested the permissions the role is to hold
   * @param alreadyHeld what the role held before (empty for a new role); only what is added is
   *     judged, so a narrowed caller can still rename a role or take permissions off it
   * @throws ApiException 403 {@code ROLE_STAFF_MANAGE_OWNER_ONLY} or {@code ROLE_EXCEEDS_CALLER}
   */
  public static void requireMayDefine(
      boolean callerIsOwner,
      boolean callerUnrestricted,
      Set<String> callerPermissions,
      Set<String> requested,
      Set<String> alreadyHeld) {
    Set<String> added = new TreeSet<>(requested);
    added.removeAll(alreadyHeld);
    if (added.contains(Permissions.STAFF_MANAGE) && !callerIsOwner) {
      throw ApiException.forbidden(
          "ROLE_STAFF_MANAGE_OWNER_ONLY",
          "Only an owner of the business may give a role the staff.manage permission");
    }
    requireHeld(callerUnrestricted, callerPermissions, added);
  }

  private static void requireHeld(
      boolean callerUnrestricted, Set<String> callerPermissions, Set<String> granted) {
    if (callerUnrestricted) return;
    Set<String> beyond = new TreeSet<>(granted);
    beyond.removeAll(callerPermissions);
    if (!beyond.isEmpty()) {
      throw ApiException.forbidden(
          "ROLE_EXCEEDS_CALLER",
          "That role holds permissions you do not hold yourself: " + String.join(", ", beyond));
    }
  }
}
