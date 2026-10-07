package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.web.ApiException;
import com.storeql.web.Permissions;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Nobody hands out more than they hold, and the top tier is an owner's to give. */
class RoleGrantsTest {

  private static final Set<String> ALL = Permissions.ALL;
  private static final Set<String> MANAGER = Permissions.defaultsFor("MANAGER");
  private static final Set<String> LEAD = Set.of("purchasing.approve", "staff.manage");

  private static ApiException refused(Runnable r) {
    return assertThrows(ApiException.class, r::run);
  }

  @Test
  @DisplayName("Only an owner makes an owner; a manager, however wide, does not")
  void onlyAnOwnerMakesAnOwner() {
    ApiException e =
        refused(() -> RoleGrants.requireMayAssign(false, false, MANAGER, "OWNER", ALL));
    assertEquals(403, e.status());
    assertEquals("STAFF_OWNER_TIER_OWNER_ONLY", e.code());
    assertDoesNotThrow(() -> RoleGrants.requireMayAssign(true, true, ALL, "OWNER", ALL));
  }

  @Test
  @DisplayName("The platform administrator is not an owner: the owner tier stays the owner's")
  void thePlatformIsNotAnOwner() {
    assertEquals(
        "STAFF_OWNER_TIER_OWNER_ONLY",
        refused(() -> RoleGrants.requireMayAssign(false, true, ALL, "OWNER", ALL)).code());
  }

  @Test
  @DisplayName("A manager may assign the tiers below and beside them")
  void aManagerAssignsTheTiersBelow() {
    assertDoesNotThrow(
        () ->
            RoleGrants.requireMayAssign(
                false, false, MANAGER, "MANAGER", Permissions.defaultsFor("MANAGER")));
    assertDoesNotThrow(
        () ->
            RoleGrants.requireMayAssign(
                false, false, MANAGER, "CASHIER", Permissions.defaultsFor("CASHIER")));
  }

  @Test
  @DisplayName("A narrowed manager cannot raise anyone, themselves included, past what they hold")
  void aNarrowedManagerCannotRaise() {
    ApiException e =
        refused(
            () ->
                RoleGrants.requireMayAssign(
                    false, false, LEAD, "MANAGER", Permissions.defaultsFor("MANAGER")));
    assertEquals("ROLE_EXCEEDS_CALLER", e.code());
    assertTrue(e.getMessage().contains("finance.journal"));
    // ...but a role no wider than theirs is theirs to give.
    assertDoesNotThrow(
        () -> RoleGrants.requireMayAssign(false, false, LEAD, "MANAGER", Set.of("staff.manage")));
    assertDoesNotThrow(() -> RoleGrants.requireMayAssign(false, false, LEAD, "CASHIER", Set.of()));
  }

  @Test
  @DisplayName("A role never holds more than its definer holds")
  void aRoleNeverHoldsMoreThanItsDefiner() {
    ApiException e =
        refused(
            () ->
                RoleGrants.requireMayDefine(
                    false, false, Set.of("purchasing.approve"), Set.of("sales.void"), Set.of()));
    assertEquals(403, e.status());
    assertEquals("ROLE_EXCEEDS_CALLER", e.code());
    assertDoesNotThrow(
        () ->
            RoleGrants.requireMayDefine(
                false, false, MANAGER, Set.of("sales.void", "finance.journal"), Set.of()));
  }

  @Test
  @DisplayName(
      "staff.manage goes into a role by an owner's hand only, even from a manager who holds it")
  void staffManageIsTheOwnersToGive() {
    ApiException e =
        refused(
            () ->
                RoleGrants.requireMayDefine(
                    false, false, MANAGER, Set.of("staff.manage"), Set.of()));
    assertEquals("ROLE_STAFF_MANAGE_OWNER_ONLY", e.code());
    assertDoesNotThrow(
        () -> RoleGrants.requireMayDefine(true, true, ALL, Set.of("staff.manage"), Set.of()));
  }

  @Test
  @DisplayName("Only what a change adds is judged: a manager may rename, or take permissions away")
  void onlyWhatIsAddedIsJudged() {
    Set<String> held = Set.of("staff.manage", "sales.void");
    // Same set, a rename: nothing added.
    assertDoesNotThrow(
        () -> RoleGrants.requireMayDefine(false, false, Set.of("purchasing.approve"), held, held));
    // Narrowed: nothing added.
    assertDoesNotThrow(
        () ->
            RoleGrants.requireMayDefine(
                false, false, Set.of("purchasing.approve"), Set.of("sales.void"), held));
    // Widened by staff.manage, by a manager: refused.
    assertEquals(
        "ROLE_STAFF_MANAGE_OWNER_ONLY",
        refused(
                () ->
                    RoleGrants.requireMayDefine(
                        false,
                        false,
                        MANAGER,
                        Set.of("staff.manage", "sales.void"),
                        Set.of("sales.void")))
            .code());
  }
}
