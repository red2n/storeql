package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.service.WorkforceService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The management roster and hours are read at the caller's own stores, the way attendance and every
 * report are: a named store must be one of theirs, naming none is exactly their stores together,
 * and the whole business only for a caller held to none.
 *
 * <p>The real {@link TenantContext} decides; the service is a stub that records what it was asked
 * for, so a refusal is seen to stop the read before the service is reached.
 */
class WorkforceResourceScopeTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();
  private static final String FROM = "2026-09-01";
  private static final String TO = "2026-09-08";

  /** What the service was asked for: the stores of each read, in order. */
  private static final class Recording extends WorkforceService {
    final List<Set<UUID>> asked = new ArrayList<>();

    @Override
    public Roster roster(UUID tenantId, Set<UUID> stores, UUID userId, Instant from, Instant to) {
      assertEquals(TENANT, tenantId);
      asked.add(stores);
      return new Roster(List.of(), Map.of());
    }

    @Override
    public List<Entry> entries(
        UUID tenantId, Set<UUID> stores, UUID userId, Instant from, Instant to) {
      assertEquals(TENANT, tenantId);
      asked.add(stores);
      return List.of();
    }
  }

  /**
   * A context as the filter would populate it from the gateway's headers. {@code set} is the
   * filter's own, package-private by design; a test reaches it the way the filter does not need to.
   */
  private static TenantContext caller(String role, Set<UUID> heldTo) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, TENANT, Ids.newId(), Set.of(role), heldTo, "req");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static WorkforceResource resource(TenantContext ctx, Recording svc) {
    WorkforceResource r = new WorkforceResource();
    r.ctx = ctx;
    r.svc = svc;
    r.maxWindowDays = 62;
    return r;
  }

  @Test
  @DisplayName("A manager held to a store reads that store's roster and hours, and no other")
  void aStoreHeldManagerReadsOnlyTheirStores() {
    Recording svc = new Recording();
    WorkforceResource r = resource(caller("MANAGER", Set.of(A)), svc);

    r.roster(null, null, FROM, TO);
    r.entries(null, null, FROM, TO);
    assertEquals(
        List.of(Set.of(A), Set.of(A)), svc.asked, "naming none is exactly the caller's stores");

    svc.asked.clear();
    r.roster(A.toString(), null, FROM, TO);
    r.entries(A.toString(), null, FROM, TO);
    assertEquals(List.of(Set.of(A), Set.of(A)), svc.asked, "their own store, named");

    svc.asked.clear();
    for (ApiException refused :
        List.of(
            assertThrows(ApiException.class, () -> r.roster(B.toString(), null, FROM, TO)),
            assertThrows(ApiException.class, () -> r.entries(B.toString(), null, FROM, TO)))) {
      assertEquals(403, refused.status());
      assertEquals("STORE_ACCESS_DENIED", refused.code());
    }
    assertTrue(svc.asked.isEmpty(), "a refused store is never read");
  }

  @Test
  @DisplayName("A manager of two branches reads both together, and never a third")
  void aManagerOfTwoBranchesReadsBoth() {
    Recording svc = new Recording();
    WorkforceResource r = resource(caller("MANAGER", Set.of(A, B)), svc);
    r.roster(null, null, FROM, TO);
    r.entries(null, null, FROM, TO);
    assertEquals(List.of(Set.of(A, B), Set.of(A, B)), svc.asked);

    UUID third = Ids.newId();
    ApiException refused =
        assertThrows(ApiException.class, () -> r.entries(third.toString(), null, FROM, TO));
    assertEquals("STORE_ACCESS_DENIED", refused.code());
  }

  @Test
  @DisplayName("A caller held to no store reads the whole business, or the store they name")
  void aCallerHeldToNoneReadsTheWholeBusiness() {
    for (String role : List.of("OWNER", "MANAGER")) {
      Recording svc = new Recording();
      WorkforceResource r = resource(caller(role, Set.of()), svc);
      r.roster(null, null, FROM, TO);
      r.entries(null, null, FROM, TO);
      assertNull(svc.asked.get(0), role + ": the whole business");
      assertNull(svc.asked.get(1), role + ": the whole business");

      svc.asked.clear();
      r.roster(B.toString(), null, FROM, TO);
      r.entries(B.toString(), null, FROM, TO);
      assertEquals(List.of(Set.of(B), Set.of(B)), svc.asked, role + ": the store named");
    }
  }

  @Test
  @DisplayName("Staff below management and a shopper read neither")
  void onlyManagementReads() {
    for (String role : List.of("CASHIER", "STOREKEEPER", "CUSTOMER")) {
      Recording svc = new Recording();
      WorkforceResource r = resource(caller(role, Set.of()), svc);
      assertEquals(
          403, assertThrows(ApiException.class, () -> r.roster(null, null, FROM, TO)).status());
      assertEquals(
          403, assertThrows(ApiException.class, () -> r.entries(null, null, FROM, TO)).status());
      assertTrue(svc.asked.isEmpty(), role + " reaches no read");
    }
  }
}
