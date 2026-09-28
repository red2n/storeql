package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The catalogue, the tier defaults, and how a context judges a caller by them. */
class PermissionsTest {

  private static TenantContext ctx(Set<String> roles, Set<String> permissions) {
    TenantContext c = new TenantContext();
    c.set(Ids.newId(), Ids.newId(), roles, Set.of(), permissions, "r");
    return c;
  }

  @Test
  @DisplayName("Every code in the catalogue has a sentence, and the sets agree")
  void catalogueIsComplete() {
    assertEquals(13, Permissions.ALL.size());
    for (String code : Permissions.ALL) {
      assertTrue(Permissions.catalogue().containsKey(code));
      assertTrue(Permissions.isKnown(code));
    }
    assertFalse(Permissions.isKnown("orders.everything"));
    assertFalse(Permissions.isKnown(null));
  }

  @Test
  @DisplayName("A manager holds everything by default; a storekeeper stock; a cashier the drawer")
  void tierDefaults() {
    assertEquals(Permissions.ALL, Permissions.defaultsFor("MANAGER"));
    assertEquals(Permissions.ALL, Permissions.defaultsFor("OWNER"));
    assertEquals(
        Set.of(
            Permissions.STOCK_ADJUST, Permissions.STOCK_TRANSFER, Permissions.PURCHASING_APPROVE),
        Permissions.defaultsFor("STOREKEEPER"));
    assertEquals(
        Set.of(Permissions.TILL_NO_SALE, Permissions.PURCHASING_APPROVE),
        Permissions.defaultsFor("CASHIER"));
    assertTrue(Permissions.defaultsFor("CUSTOMER").isEmpty());
    assertTrue(Permissions.defaultsFor("SHIFT_LEAD").isEmpty());
    assertTrue(Permissions.defaultsFor(null).isEmpty());
  }

  @Test
  @DisplayName("Several roles hold the union of their defaults")
  void effectiveIsAUnion() {
    assertEquals(
        Set.of(
            Permissions.STOCK_ADJUST,
            Permissions.STOCK_TRANSFER,
            Permissions.TILL_NO_SALE,
            Permissions.PURCHASING_APPROVE),
        Permissions.effective(Set.of("STOREKEEPER", "CASHIER")));
    assertTrue(Permissions.effective(Set.of()).isEmpty());
    assertTrue(Permissions.effective(null).isEmpty());
  }

  @Test
  @DisplayName("A token with no claim is judged by its tier: a manager may, a cashier may not")
  void noClaimMeansTierDefaults() {
    assertTrue(ctx(Set.of("MANAGER"), null).hasPermission(Permissions.SALES_VOID));
    assertFalse(ctx(Set.of("CASHIER"), null).hasPermission(Permissions.SALES_VOID));
    assertTrue(ctx(Set.of("CASHIER"), null).hasPermission(Permissions.TILL_NO_SALE));
    assertTrue(ctx(Set.of("STOREKEEPER"), null).hasPermission(Permissions.STOCK_ADJUST));
  }

  @Test
  @DisplayName("A token with a claim is judged by the claim, and only by the claim")
  void claimNarrows() {
    TenantContext shiftLead = ctx(Set.of("MANAGER"), Set.of(Permissions.PURCHASING_APPROVE));
    assertTrue(shiftLead.hasPermission(Permissions.PURCHASING_APPROVE));
    assertFalse(shiftLead.hasPermission(Permissions.SALES_VOID));
    assertEquals(Set.of(Permissions.PURCHASING_APPROVE), shiftLead.permissions());
    // A claim naming nothing narrows all the way down: a trainee cashier may not open the drawer.
    assertFalse(ctx(Set.of("CASHIER"), Set.of()).hasPermission(Permissions.TILL_NO_SALE));
  }

  @Test
  @DisplayName("An owner or the platform is never narrowed, whatever the claim says")
  void ownersAreNotNarrowed() {
    assertTrue(ctx(Set.of("OWNER"), Set.of()).hasPermission(Permissions.SALES_VOID));
    assertEquals(Permissions.ALL, ctx(Set.of("PLATFORM_ADMIN"), Set.of()).permissions());
  }

  @Test
  @DisplayName("requirePermission refuses with the permission named")
  void requireRefusesByName() {
    TenantContext trainee = ctx(Set.of("CASHIER"), Set.of());
    ApiException e =
        assertThrows(ApiException.class, () -> trainee.requirePermission(Permissions.TILL_NO_SALE));
    assertEquals(403, e.status());
    assertEquals("PERMISSION_DENIED", e.code());
    assertTrue(e.getMessage().contains(Permissions.TILL_NO_SALE));
    ctx(Set.of("CASHIER"), null).requirePermission(Permissions.TILL_NO_SALE);
  }

  @Test
  @DisplayName("A permission that is not in the catalogue is held by nobody but an owner")
  void unknownCodeIsDenied() {
    assertFalse(ctx(Set.of("MANAGER"), null).hasPermission("orders.everything"));
    assertTrue(
        ctx(Set.of("MANAGER"), Set.of("orders.everything")).hasPermission("orders.everything"));
  }
}
