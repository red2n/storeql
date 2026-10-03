package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Merchandising;
import com.storeql.product.domain.Merchandising.Fixture;
import com.storeql.product.domain.Merchandising.Planogram;
import com.storeql.product.domain.Merchandising.Reset;
import com.storeql.product.repo.MerchandisingRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A fixture belongs to a store, so a manager held to stores acts on a fixture, a layout drawn for
 * it, or a reset touching it only where they hold the store (3 Oct 2026): the record is the
 * business's first (404), then the caller's store (403 {@code STORE_ACCESS_DENIED}).
 */
class MerchandisingStoreScopeTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();

  private final Map<UUID, Fixture> fixtures = new HashMap<>();
  private final Map<UUID, Planogram> planograms = new HashMap<>();
  private final Map<UUID, Reset> resets = new HashMap<>();
  private final Map<UUID, Set<UUID>> resetStores = new HashMap<>();

  private final MerchandisingService svc = new MerchandisingService();

  MerchandisingStoreScopeTest() {
    svc.repo =
        new MerchandisingRepository() {
          @Override
          public Optional<Fixture> fixture(UUID tenantId, UUID id) {
            return Optional.ofNullable(fixtures.get(id)).filter(f -> f.tenantId().equals(tenantId));
          }

          @Override
          public Optional<Planogram> planogram(UUID tenantId, UUID id) {
            return Optional.ofNullable(planograms.get(id))
                .filter(p -> p.tenantId().equals(tenantId));
          }

          @Override
          public Optional<Reset> reset(UUID tenantId, UUID id) {
            return Optional.ofNullable(resets.get(id)).filter(r -> r.tenantId().equals(tenantId));
          }

          @Override
          public List<Reset> resetsOf(UUID tenantId) {
            return resets.values().stream().filter(r -> r.tenantId().equals(tenantId)).toList();
          }

          @Override
          public Map<UUID, Set<UUID>> resetStores(UUID tenantId) {
            return resetStores;
          }
        };
  }

  private UUID fixtureAt(UUID tenant, UUID store) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    fixtures.put(
        id,
        new Fixture(
            id,
            tenant,
            store,
            null,
            "F-" + id,
            "Gondola",
            "GONDOLA",
            3,
            1000,
            Merchandising.ACTIVE,
            now,
            now));
    return id;
  }

  private UUID planogramOf(UUID tenant, UUID fixture) {
    UUID id = Ids.newId();
    planograms.put(
        id,
        new Planogram(
            id,
            tenant,
            fixture,
            1,
            Merchandising.DRAFT,
            LocalDate.now(),
            null,
            null,
            null,
            Instant.now(),
            null,
            List.of()));
    return id;
  }

  private UUID resetTouching(UUID tenant, UUID... stores) {
    UUID id = Ids.newId();
    resets.put(
        id,
        new Reset(
            id,
            tenant,
            Ids.newId(),
            "Reset",
            LocalDate.now(),
            Merchandising.PLANNED,
            null,
            Instant.now(),
            null,
            List.of()));
    if (stores.length > 0) resetStores.put(id, new HashSet<>(List.of(stores)));
    return id;
  }

  private static TenantContext caller(UUID tenant, String role, UUID... held) {
    return CatalogueStoresTest.caller(tenant, role, Set.of(held));
  }

  private static void assertDenied(Runnable call) {
    ApiException e = assertThrows(ApiException.class, call::run);
    assertThat(e.status(), is(403));
    assertThat(e.code(), is("STORE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName("A fixture is the business's first (404), then the caller's store (403)")
  void fixtureScope() {
    UUID atB = fixtureAt(TENANT, B);
    UUID atA = fixtureAt(TENANT, A);
    assertDenied(() -> svc.requireFixtureHeld(caller(TENANT, "MANAGER", A), atB));
    assertThat(svc.requireFixtureHeld(caller(TENANT, "MANAGER", A), atA).id(), is(atA));
    assertDoesNotThrow(() -> svc.requireFixtureHeld(caller(TENANT, "MANAGER", A, B), atB));
    for (String role : List.of("OWNER", "MANAGER")) {
      assertDoesNotThrow(() -> svc.requireFixtureHeld(caller(TENANT, role), atB));
    }
    // Another business's fixture, or one nobody made: 404 whoever asks, even naming our store.
    for (UUID id : List.of(atB, Ids.newId())) {
      ApiException e =
          assertThrows(
              ApiException.class, () -> svc.requireFixtureHeld(caller(RIVAL, "OWNER", B), id));
      assertThat(e.status(), is(404));
      assertThat(e.code(), is("FIXTURE_NOT_FOUND"));
    }
  }

  private static void assertStoreNotFound(Runnable call) {
    ApiException e = assertThrows(ApiException.class, call::run);
    assertThat(e.status(), is(404));
    assertThat(e.code(), is("MERCH_STORE_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "A store a fixture or space plan names is the business's first (404), then the caller's")
  void theNamedStoreIsTheBusinesssFirst() {
    svc.profiles =
        new TenantProfiles() {
          @Override
          public Stores stores(UUID tenantId, UUID including) {
            return new Stores(TENANT.equals(tenantId) ? Set.of(A, B) : Set.of(), Map.of());
          }
        };
    UUID nobodys = Ids.newId();
    // Not the business's: not found whoever names it — an owner was never asked before, being
    // held to no store — and another business naming our store finds nothing either.
    assertStoreNotFound(() -> svc.requireStore(caller(TENANT, "OWNER"), nobodys));
    assertStoreNotFound(() -> svc.requireStore(caller(TENANT, "MANAGER"), nobodys));
    assertStoreNotFound(() -> svc.requireStore(caller(TENANT, "MANAGER", A), nobodys));
    assertStoreNotFound(() -> svc.requireStore(caller(RIVAL, "OWNER"), A));
    assertStoreNotFound(() -> svc.requireStore(caller(RIVAL, "MANAGER", A), A));
    // The business's, and not the caller's.
    assertDenied(() -> svc.requireStore(caller(TENANT, "MANAGER", A), B));
    // Theirs, or a caller held to none.
    assertDoesNotThrow(() -> svc.requireStore(caller(TENANT, "MANAGER", A), A));
    assertDoesNotThrow(() -> svc.requireStore(caller(TENANT, "OWNER"), B));
  }

  @Test
  @DisplayName("A layout belongs to its fixture's store")
  void planogramScope() {
    UUID atB = planogramOf(TENANT, fixtureAt(TENANT, B));
    UUID atA = planogramOf(TENANT, fixtureAt(TENANT, A));
    assertDenied(() -> svc.requirePlanogramHeld(caller(TENANT, "MANAGER", A), atB));
    assertThat(svc.requirePlanogramHeld(caller(TENANT, "MANAGER", A), atA).id(), is(atA));
    assertDoesNotThrow(() -> svc.requirePlanogramHeld(caller(TENANT, "MANAGER"), atB));
    assertDoesNotThrow(() -> svc.requirePlanogramHeld(caller(TENANT, "OWNER"), atB));
    ApiException other =
        assertThrows(
            ApiException.class, () -> svc.requirePlanogramHeld(caller(RIVAL, "MANAGER", B), atB));
    assertThat(other.code(), is("PLANOGRAM_NOT_FOUND"));
  }

  @Test
  @DisplayName("A reset is held only by a caller who holds every store it touches")
  void resetScope() {
    UUID atB = resetTouching(TENANT, B);
    UUID both = resetTouching(TENANT, A, B);
    UUID atA = resetTouching(TENANT, A);
    UUID none = resetTouching(TENANT);
    TenantContext heldA = caller(TENANT, "MANAGER", A);
    assertDenied(() -> svc.requireResetHeld(heldA, atB));
    assertDenied(() -> svc.requireResetHeld(heldA, both));
    assertDoesNotThrow(() -> svc.requireResetHeld(heldA, atA));
    assertDoesNotThrow(() -> svc.requireResetHeld(heldA, none));
    assertDoesNotThrow(() -> svc.requireResetHeld(caller(TENANT, "MANAGER", A, B), both));
    assertDoesNotThrow(() -> svc.requireResetHeld(caller(TENANT, "OWNER"), both));
    assertDoesNotThrow(() -> svc.requireResetHeld(caller(TENANT, "MANAGER"), atB));
    ApiException other =
        assertThrows(
            ApiException.class, () -> svc.requireResetHeld(caller(RIVAL, "OWNER", A), atA));
    assertThat(other.code(), is("RESET_NOT_FOUND"));
  }

  @Test
  @DisplayName("The reset list shows a held manager only resets touching their stores, or none yet")
  void resetList() {
    UUID atB = resetTouching(TENANT, B);
    UUID both = resetTouching(TENANT, A, B);
    UUID atA = resetTouching(TENANT, A);
    UUID none = resetTouching(TENANT);
    resetTouching(RIVAL, A);
    List<UUID> held =
        svc.resetsFor(caller(TENANT, "MANAGER", A)).stream().map(Reset::id).sorted().toList();
    assertThat(held, contains(sorted(both, atA, none)));
    assertThat(svc.resetsFor(caller(TENANT, "OWNER")).size(), is(4));
    assertThat(svc.resetsFor(caller(TENANT, "MANAGER")).size(), is(4));
    assertThat("a reset touching only B is not listed", held.contains(atB), is(false));
  }

  private static UUID[] sorted(UUID... ids) {
    return java.util.Arrays.stream(ids).sorted().toArray(UUID[]::new);
  }
}
