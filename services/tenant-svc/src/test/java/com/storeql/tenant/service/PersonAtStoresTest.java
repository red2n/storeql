package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.repo.WorkforceRepository;
import com.storeql.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a caller held to stores may read about one person: only somebody assigned at one of those
 * stores. The repository is a stub holding who is assigned where, so the rule is seen alone.
 */
class PersonAtStoresTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();
  private static final UUID AT_A = Ids.newId();
  private static final UUID AT_B = Ids.newId();
  private static final UUID BUSINESS_WIDE = Ids.newId();

  /** Store assignments only; a business-wide one has no store, so it is in no set of stores. */
  private static final class Assignments extends WorkforceRepository {
    final Map<UUID, Set<UUID>> at =
        Map.of(AT_A, Set.of(A), AT_B, Set.of(B), BUSINESS_WIDE, Set.of());
    final List<Set<UUID>> asked = new ArrayList<>();

    @Override
    public Set<UUID> workingAt(UUID tenantId, Set<UUID> people, Set<UUID> stores) {
      assertEquals(TENANT, tenantId);
      asked.add(stores);
      Set<UUID> found = new java.util.HashSet<>();
      for (UUID p : people) {
        if (at.getOrDefault(p, Set.of()).stream().anyMatch(stores::contains)) found.add(p);
      }
      return found;
    }
  }

  private static WorkforceService service(Assignments repo) {
    WorkforceService s = new WorkforceService();
    s.repo = repo;
    return s;
  }

  @Test
  @DisplayName("Held to a store: somebody assigned there is read, anybody else refused alike")
  void heldToAStore() {
    Assignments repo = new Assignments();
    WorkforceService s = service(repo);
    assertDoesNotThrow(() -> s.requirePersonAtStores(TENANT, AT_A, Set.of(A)));
    for (UUID other : List.of(AT_B, BUSINESS_WIDE, Ids.newId())) {
      ApiException refused =
          assertThrows(ApiException.class, () -> s.requirePersonAtStores(TENANT, other, Set.of(A)));
      assertEquals(403, refused.status());
      assertEquals(
          "STORE_ACCESS_DENIED",
          refused.code(),
          "the same answer for another store's person, a business-wide one and a stranger");
    }
  }

  @Test
  @DisplayName("Held to two stores: somebody at either is read")
  void heldToTwo() {
    WorkforceService s = service(new Assignments());
    assertDoesNotThrow(() -> s.requirePersonAtStores(TENANT, AT_A, Set.of(A, B)));
    assertDoesNotThrow(() -> s.requirePersonAtStores(TENANT, AT_B, Set.of(A, B)));
  }

  @Test
  @DisplayName("Held to no store: anybody is read, and nothing is looked up")
  void heldToNone() {
    Assignments repo = new Assignments();
    WorkforceService s = service(repo);
    for (UUID anyone : List.of(AT_A, AT_B, BUSINESS_WIDE, Ids.newId())) {
      assertDoesNotThrow(() -> s.requirePersonAtStores(TENANT, anyone, null));
    }
    assertTrue(repo.asked.isEmpty());
  }

  @Test
  @DisplayName(
      "Several people at once: read together, in one look-up, refused when any one is elsewhere")
  void severalPeopleAtOnce() {
    Assignments repo = new Assignments();
    WorkforceService s = service(repo);
    assertDoesNotThrow(() -> s.requirePeopleAtStores(TENANT, List.of(AT_A, AT_A), Set.of(A)));
    assertDoesNotThrow(() -> s.requirePeopleAtStores(TENANT, List.of(AT_A, AT_B), Set.of(A, B)));
    assertEquals(2, repo.asked.size(), "one look-up per call, however many people");

    UUID stranger = Ids.newId();
    ApiException refused =
        assertThrows(
            ApiException.class,
            () ->
                s.requirePeopleAtStores(
                    TENANT, List.of(AT_A, AT_B, BUSINESS_WIDE, stranger), Set.of(A)));
    assertEquals(403, refused.status());
    assertEquals("STORE_ACCESS_DENIED", refused.code());
    assertEquals(
        Set.of(AT_B.toString(), BUSINESS_WIDE.toString(), stranger.toString()),
        Set.copyOf(refused.details()),
        "names exactly the ids the caller sent that are not at their stores, nothing more");
  }

  @Test
  @DisplayName("Nobody to judge, or a caller held to no store: nothing is looked up")
  void nothingToJudge() {
    Assignments repo = new Assignments();
    WorkforceService s = service(repo);
    assertDoesNotThrow(() -> s.requirePeopleAtStores(TENANT, List.of(), Set.of(A)));
    assertDoesNotThrow(() -> s.requirePeopleAtStores(TENANT, List.of(AT_B, Ids.newId()), null));
    assertTrue(repo.asked.isEmpty());
  }
}
