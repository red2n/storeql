package com.storeql.iam.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.iam.service.SecurityEventService;
import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who reads the security trail. It covers every login of the business, so a manager held to stores
 * is refused because the trail is business-wide ({@code 403 BUSINESS_WIDE_ONLY}, the code every
 * other business-wide refusal answers), never told that a store is not theirs ({@code
 * STORE_ACCESS_DENIED} is for a store the caller does not keep, and no store is named here). The
 * business read is always the token's, and a refused caller reads nothing.
 */
class SecurityEventResourceTest {

  /** Records each read instead of reaching the database. */
  private static final class Recording extends SecurityEventService {
    final List<UUID> tenantsRead = new ArrayList<>();
    int reads;

    @Override
    public Page list(
        UUID tenantId,
        String type,
        UUID userId,
        Instant from,
        Instant to,
        UUID after,
        Integer limit) {
      reads++;
      tenantsRead.add(tenantId);
      return new Page(List.of(), null);
    }
  }

  private static TenantContext caller(UUID tenant, Set<String> roles, Set<UUID> stores) {
    return new TenantContext() {
      @Override
      public UUID tenantId() {
        return tenant;
      }

      @Override
      public UUID requireTenantId() {
        if (tenant == null) throw ApiException.unauthorized("NO_TENANT", "No tenant");
        return tenant;
      }

      @Override
      public Set<String> roles() {
        return roles;
      }

      @Override
      public boolean hasRole(String role) {
        return roles.contains(role);
      }

      @Override
      public void requireAnyRole(String... required) {
        for (String r : required) {
          if (roles.contains(r)) return;
        }
        throw ApiException.forbidden("FORBIDDEN", "Insufficient role for this operation");
      }

      @Override
      public Set<UUID> storeIds() {
        return stores;
      }
    };
  }

  private static SecurityEventResource resource(TenantContext ctx, Recording service) {
    SecurityEventResource r = new SecurityEventResource();
    r.ctx = ctx;
    r.service = service;
    return r;
  }

  private static void read(SecurityEventResource r) {
    r.list(null, null, null, null, null, 20);
  }

  @Test
  void aManagerHeldToStoresIsToldTheTrailIsBusinessWideAndReadsNothing() {
    UUID tenant = Ids.newId();
    for (Set<UUID> stores : List.of(Set.of(Ids.newId()), Set.of(Ids.newId(), Ids.newId()))) {
      Recording service = new Recording();
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> read(resource(caller(tenant, Set.of("MANAGER"), stores), service)));
      assertEquals(403, e.status());
      assertEquals("BUSINESS_WIDE_ONLY", e.code(), "stores: " + stores.size());
      assertNotEquals("STORE_ACCESS_DENIED", e.code(), "no store is named on this read");
      assertTrue(e.getMessage().contains("business-wide"), e.getMessage());
      assertEquals(0, service.reads, "a refused caller reads nothing");
    }
  }

  @Test
  void anotherBusinesssManagerHeldToStoresIsRefusedTheSameWay() {
    // Held to a store of a business of its own: refused for being held, before any business is
    // read, so it neither reaches ours nor learns whether ours has events.
    Recording service = new Recording();
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                read(
                    resource(
                        caller(Ids.newId(), Set.of("MANAGER"), Set.of(Ids.newId())), service)));
    assertEquals("BUSINESS_WIDE_ONLY", e.code());
    assertEquals(0, service.reads);
  }

  @Test
  void staffBelowManagementAreRefusedForTheirRoleNotTheirStores() {
    UUID tenant = Ids.newId();
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      for (Set<UUID> stores : List.of(Set.<UUID>of(), Set.of(Ids.newId()))) {
        Recording service = new Recording();
        ApiException e =
            assertThrows(
                ApiException.class,
                () -> read(resource(caller(tenant, Set.of(role), stores), service)));
        assertEquals(403, e.status(), role);
        assertEquals("FORBIDDEN", e.code(), role + " held to " + stores.size());
        assertEquals(0, service.reads, role);
      }
    }
  }

  @Test
  void anOwnerOrAManagerHeldToNoStoreReadsTheirOwnBusinessOnly() {
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      UUID tenant = Ids.newId();
      Recording service = new Recording();
      read(resource(caller(tenant, Set.of(role), Set.of()), service));
      assertEquals(List.of(tenant), service.tenantsRead, role);
    }
  }

  @Test
  void thePlatformAdministratorReadsEveryBusiness() {
    Recording service = new Recording();
    read(resource(caller(null, Set.of("PLATFORM_ADMIN"), Set.of()), service));
    assertEquals(1, service.reads);
    assertNull(service.tenantsRead.get(0));
  }
}
