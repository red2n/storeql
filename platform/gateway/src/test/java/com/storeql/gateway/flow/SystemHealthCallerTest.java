package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The caller as the gateway reads it from the headers {@code JwtAuthFilter} stamped, and who is let
 * in. The rule itself is common-web's {@code SystemHealthAccess}; this holds the reading of the
 * headers to the way the services read them.
 */
class SystemHealthCallerTest {

  private static final String TENANT = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();

  private static SystemHealthCaller caller(String roles, String perms, String stores) {
    return SystemHealthCaller.of(TENANT, roles, perms, stores);
  }

  private static String refusedWith(SystemHealthCaller c) {
    return assertThrows(ApiException.class, c::requireAccess).code();
  }

  @Test
  @DisplayName(
      "An owner and a platform administrator are let in whatever the permission claim says")
  void ownerIsUnrestricted() {
    assertDoesNotThrow(caller("OWNER", null, null)::requireAccess);
    assertDoesNotThrow(caller("OWNER", "-", null)::requireAccess);
    assertDoesNotThrow(caller("PLATFORM_ADMIN", "stock.adjust", null)::requireAccess);
  }

  @Test
  @DisplayName(
      "A manager with no permission claim holds the tier's defaults, which include the screen")
  void managerByDefault() {
    assertDoesNotThrow(caller("MANAGER", null, null)::requireAccess);
  }

  @Test
  @DisplayName("A custom role on the manager tier holds the screen only if its claim names it")
  void customRole() {
    assertDoesNotThrow(caller("MANAGER", "system.health", null)::requireAccess);
    assertDoesNotThrow(caller("MANAGER", "stock.adjust,system.health", null)::requireAccess);
    assertEquals(
        "SYSTEM_HEALTH_NOT_PERMITTED", refusedWith(caller("MANAGER", "stock.adjust", null)));
  }

  @Test
  @DisplayName("'-' is a claim naming nothing: a role narrowed to nothing, not 'no claim'")
  void dashIsAnEmptyClaim() {
    assertEquals("SYSTEM_HEALTH_NOT_PERMITTED", refusedWith(caller("MANAGER", "-", null)));
    assertEquals(Set.of(), caller("MANAGER", "-", null).permissions());
    assertEquals(Set.of(), caller("MANAGER", "  ", null).permissions());
    assertNull(caller("MANAGER", null, null).permissions(), "absent is not empty");
  }

  @Test
  @DisplayName(
      "A storekeeper, a cashier, a shopper and a caller with no role are refused the permission")
  void everyoneElseIsRefused() {
    for (String roles :
        new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER", "", null, "SOMETHING_ELSE"}) {
      assertEquals(
          "SYSTEM_HEALTH_NOT_PERMITTED",
          refusedWith(caller(roles, null, null)),
          String.valueOf(roles));
    }
  }

  @Test
  @DisplayName("A caller held to stores is refused business-wide, after the permission is judged")
  void storeHeldCallers() {
    assertEquals("BUSINESS_WIDE_ONLY", refusedWith(caller("MANAGER", null, STORE)));
    assertEquals("BUSINESS_WIDE_ONLY", refusedWith(caller("OWNER", null, STORE)));
    assertEquals(
        "SYSTEM_HEALTH_NOT_PERMITTED",
        refusedWith(caller("CASHIER", null, STORE)),
        "permission first, so a refused person is not told about the business-wide rule");
  }

  @Test
  @DisplayName("A store list that does not parse still holds the caller to stores: it fails closed")
  void junkStoreListFailsClosed() {
    assertEquals("BUSINESS_WIDE_ONLY", refusedWith(caller("MANAGER", null, "not-an-id")));
    assertEquals("BUSINESS_WIDE_ONLY", refusedWith(caller("MANAGER", null, " , ," + STORE)));
    assertTrue(caller("MANAGER", null, " , ,").storeIds().isEmpty());
    assertTrue(caller("MANAGER", null, "").storeIds().isEmpty());
    assertTrue(caller("MANAGER", null, null).storeIds().isEmpty());
  }

  @Test
  @DisplayName(
      "Roles are comma-separated and trimmed; holding any one that carries the permission is enough")
  void rolesAreParsed() {
    assertEquals(Set.of("MANAGER", "CASHIER"), caller(" MANAGER , CASHIER,,", null, null).roles());
    assertDoesNotThrow(caller("CASHIER,MANAGER", null, null)::requireAccess);
  }

  @Test
  @DisplayName("The business is the verified one the filter set, nothing else")
  void tenantIsCarried() {
    assertEquals(TENANT, caller("OWNER", null, null).tenantId());
    assertNull(SystemHealthCaller.of(null, "OWNER", null, null).tenantId());
  }
}
