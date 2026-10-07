package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Who may see the system-health screen: the permission first, then a business-wide caller. */
class SystemHealthAccessTest {

  private static void refusedWith(
      String code, Set<String> roles, Set<String> claim, Set<?> stores) {
    ApiException e =
        assertThrows(ApiException.class, () -> SystemHealthAccess.require(roles, claim, stores));
    assertEquals(403, e.status());
    assertEquals(code, e.code());
  }

  @Test
  @DisplayName("The permission is in the catalogue, and an owner and a manager hold it by default")
  void heldByDefaultByOwnerAndManager() {
    assertTrue(Permissions.isKnown(Permissions.SYSTEM_HEALTH));
    assertEquals("system.health", Permissions.SYSTEM_HEALTH);
    assertTrue(Permissions.defaultsFor("OWNER").contains(Permissions.SYSTEM_HEALTH));
    assertTrue(Permissions.defaultsFor("MANAGER").contains(Permissions.SYSTEM_HEALTH));
    assertFalse(Permissions.defaultsFor("STOREKEEPER").contains(Permissions.SYSTEM_HEALTH));
    assertFalse(Permissions.defaultsFor("CASHIER").contains(Permissions.SYSTEM_HEALTH));
  }

  @Test
  @DisplayName("An owner and a manager with no claim and no store restriction are let in")
  void ownerAndManagerAreLetIn() {
    assertDoesNotThrow(() -> SystemHealthAccess.require(Set.of("OWNER"), null, Set.of()));
    assertDoesNotThrow(() -> SystemHealthAccess.require(Set.of("MANAGER"), null, Set.of()));
  }

  @Test
  @DisplayName("A cashier, a storekeeper and a shopper are refused SYSTEM_HEALTH_NOT_PERMITTED")
  void otherTiersAreRefused() {
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      refusedWith("SYSTEM_HEALTH_NOT_PERMITTED", Set.of(role), null, Set.of());
    }
    refusedWith("SYSTEM_HEALTH_NOT_PERMITTED", Set.of(), null, Set.of());
  }

  @Test
  @DisplayName(
      "A manager whose role was narrowed away from it is refused; an IT role given it is let in")
  void aCustomRoleHoldsItOnlyWhenGiven() {
    refusedWith(
        "SYSTEM_HEALTH_NOT_PERMITTED",
        Set.of("MANAGER"),
        Set.of(Permissions.PURCHASING_APPROVE),
        Set.of());
    assertDoesNotThrow(
        () ->
            SystemHealthAccess.require(
                Set.of("MANAGER"), Set.of(Permissions.SYSTEM_HEALTH), Set.of()));
  }

  @Test
  @DisplayName("An owner is never narrowed away from it")
  void anOwnerIsNeverNarrowed() {
    assertDoesNotThrow(() -> SystemHealthAccess.require(Set.of("OWNER"), Set.of(), Set.of()));
  }

  @Test
  @DisplayName("A caller held to stores is refused BUSINESS_WIDE_ONLY, after the permission check")
  void aStoreHeldCallerIsRefused() {
    Set<?> stores = Set.of(Ids.newId());
    refusedWith("BUSINESS_WIDE_ONLY", Set.of("MANAGER"), null, stores);
    // not permitted wins over business-wide: a cashier is told about the permission first
    refusedWith("SYSTEM_HEALTH_NOT_PERMITTED", Set.of("CASHIER"), null, stores);
  }

  @Test
  @DisplayName("The context form reads roles, claim and stores from the request's caller")
  void theContextFormJudgesTheCaller() {
    TenantContext held = new TenantContext();
    held.set(Ids.newId(), Ids.newId(), Set.of("MANAGER"), Set.of(Ids.newId()), null, "r");
    ApiException e = assertThrows(ApiException.class, () -> SystemHealthAccess.require(held));
    assertEquals("BUSINESS_WIDE_ONLY", e.code());

    TenantContext wide = new TenantContext();
    wide.set(Ids.newId(), Ids.newId(), Set.of("MANAGER"), Set.of(), null, "r");
    assertDoesNotThrow(() -> SystemHealthAccess.require(wide));

    TenantContext till = new TenantContext();
    till.set(Ids.newId(), Ids.newId(), Set.of("CASHIER"), Set.of(), null, "r");
    assertEquals(
        "SYSTEM_HEALTH_NOT_PERMITTED",
        assertThrows(ApiException.class, () -> SystemHealthAccess.require(till)).code());
  }
}
