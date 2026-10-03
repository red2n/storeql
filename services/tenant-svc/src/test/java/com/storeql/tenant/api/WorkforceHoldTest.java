package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Commission.Assignment;
import com.storeql.tenant.domain.Commission.Scheme;
import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.domain.Workforce.PayRate;
import com.storeql.tenant.domain.Workforce.Shift;
import com.storeql.tenant.dto.CommissionDtos;
import com.storeql.tenant.dto.WorkforceDtos;
import com.storeql.tenant.service.CommissionService;
import com.storeql.tenant.service.CommissionService.Rated;
import com.storeql.tenant.service.CommissionService.SellerDays;
import com.storeql.tenant.service.WorkforceService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shift is published or called off at its own store, and what is kept about a person — their pay
 * rate, their commission — is read only for somebody at the caller's stores (workforce-rules: "a
 * shift or entry is judged by its own store"; CLAUDE.md, store-held managers).
 *
 * <p>The real {@link TenantContext} decides; the services are stubs that record what reached them,
 * so a refusal is seen to stop the act before anything moves.
 */
class WorkforceHoldTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();
  private static final UUID SHIFT_AT_A = Ids.newId();
  private static final UUID SHIFT_AT_B = Ids.newId();
  private static final UUID PERSON_AT_A = Ids.newId();
  private static final UUID PERSON_AT_B = Ids.newId();

  /** What reached the service, in order; the stores each person-read was held to. */
  private static final class Recording extends WorkforceService {
    final List<String> acted = new ArrayList<>();
    final List<Set<UUID>> heldTo = new ArrayList<>();

    @Override
    public UUID storeOfShift(UUID tenantId, UUID shiftId) {
      assertEquals(TENANT, tenantId);
      if (SHIFT_AT_A.equals(shiftId)) return A;
      if (SHIFT_AT_B.equals(shiftId)) return B;
      throw ApiException.notFound("WORKFORCE_SHIFT_NOT_FOUND", "no such shift");
    }

    @Override
    public Shift publishShift(UUID tenantId, UUID id, String idempotencyKey, UUID actorId) {
      acted.add("publish " + id);
      return shift(id);
    }

    @Override
    public Shift cancelShift(UUID tenantId, UUID id, String reason, UUID actorId) {
      acted.add("cancel " + id);
      return shift(id);
    }

    @Override
    public void requirePersonAtStores(UUID tenantId, UUID userId, Set<UUID> stores) {
      assertEquals(TENANT, tenantId);
      heldTo.add(stores);
      UUID worksAt = PERSON_AT_A.equals(userId) ? A : PERSON_AT_B.equals(userId) ? B : null;
      if (stores != null && (worksAt == null || !stores.contains(worksAt))) {
        throw ApiException.forbidden("STORE_ACCESS_DENIED", "not at your stores");
      }
    }

    @Override
    public void requirePeopleAtStores(
        UUID tenantId, java.util.Collection<UUID> people, Set<UUID> stores) {
      for (UUID person : people) requirePersonAtStores(tenantId, person, stores);
    }

    @Override
    public List<PayRate> rates(UUID tenantId, UUID userId) {
      acted.add("rates " + userId);
      return List.of();
    }
  }

  private static final class Arrangements extends CommissionService {
    final List<UUID> read = new ArrayList<>();
    final List<List<UUID>> rated = new ArrayList<>();

    @Override
    public List<Assignment> assignments(UUID tenantId, UUID userId) {
      read.add(userId);
      return List.of();
    }

    @Override
    public List<Rated> rate(UUID tenantId, LocalDate from, LocalDate to, List<SellerDays> sellers) {
      rated.add(sellers.stream().map(SellerDays::userId).toList());
      return sellers.stream()
          .map(s -> new Rated(s.userId(), List.of(), BigDecimal.ZERO, null))
          .toList();
    }

    @Override
    public List<Scheme> schemes(UUID tenantId, boolean activeOnly) {
      return List.of();
    }
  }

  /** What these people sold on one day, as order-svc sends it for a statement. */
  private static CommissionDtos.RateRequest sales(UUID... people) {
    return new CommissionDtos.RateRequest(
        "2026-09-01",
        "2026-09-30",
        java.util.Arrays.stream(people)
            .map(
                p ->
                    new CommissionDtos.SellerRequest(
                        p.toString(),
                        List.of(
                            new CommissionDtos.DayRequest(
                                "2026-09-02", new BigDecimal("100"), null))))
            .toList());
  }

  private static Shift shift(UUID id) {
    Instant now = Instant.now();
    return new Shift(
        id,
        TENANT,
        A,
        PERSON_AT_A,
        now,
        now.plusSeconds(3600),
        null,
        Workforce.PUBLISHED,
        null,
        null,
        now,
        Ids.newId(),
        now);
  }

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

  private static CommissionResource commission(
      TenantContext ctx, Recording workforce, Arrangements svc) {
    CommissionResource r = new CommissionResource();
    r.ctx = ctx;
    r.svc = svc;
    r.workforce = workforce;
    return r;
  }

  private static final WorkforceDtos.CancelShiftRequest WHY =
      new WorkforceDtos.CancelShiftRequest("the delivery moved");

  /** A fresh Idempotency-Key, as a client sends one for each attempt at a write. */
  private static String key() {
    return Ids.newId().toString();
  }

  private static void deniedAtTheStore(ApiException refused) {
    assertEquals(403, refused.status());
    assertEquals("STORE_ACCESS_DENIED", refused.code());
  }

  @Test
  @DisplayName(
      "A manager held to a store publishes and calls off that store's shifts, and no other")
  void aStoreHeldManagerChangesOnlyTheirStoresShifts() {
    Recording svc = new Recording();
    WorkforceResource r = resource(caller("MANAGER", Set.of(A)), svc);

    deniedAtTheStore(assertThrows(ApiException.class, () -> r.publish(SHIFT_AT_B, key())));
    deniedAtTheStore(assertThrows(ApiException.class, () -> r.cancel(SHIFT_AT_B, WHY)));
    assertTrue(svc.acted.isEmpty(), "a shift at another store is never moved");

    r.publish(SHIFT_AT_A, key());
    r.cancel(SHIFT_AT_A, WHY);
    assertEquals(List.of("publish " + SHIFT_AT_A, "cancel " + SHIFT_AT_A), svc.acted);
  }

  @Test
  @DisplayName("A manager of two branches changes either, and never a third")
  void aManagerOfTwoBranchesChangesBoth() {
    Recording svc = new Recording();
    WorkforceResource r = resource(caller("MANAGER", Set.of(A, B)), svc);
    r.publish(SHIFT_AT_A, key());
    r.cancel(SHIFT_AT_B, WHY);
    assertEquals(List.of("publish " + SHIFT_AT_A, "cancel " + SHIFT_AT_B), svc.acted);

    Recording third = new Recording();
    WorkforceResource elsewhere = resource(caller("MANAGER", Set.of(Ids.newId())), third);
    deniedAtTheStore(assertThrows(ApiException.class, () -> elsewhere.publish(SHIFT_AT_A, key())));
    assertTrue(third.acted.isEmpty());
  }

  @Test
  @DisplayName("A caller held to no store publishes and calls off any of the business's shifts")
  void aCallerHeldToNoneChangesAnyShift() {
    for (String role : List.of("OWNER", "MANAGER")) {
      Recording svc = new Recording();
      WorkforceResource r = resource(caller(role, Set.of()), svc);
      r.publish(SHIFT_AT_B, key());
      r.cancel(SHIFT_AT_B, WHY);
      assertEquals(
          List.of("publish " + SHIFT_AT_B, "cancel " + SHIFT_AT_B), svc.acted, role + " acts");
    }
  }

  @Test
  @DisplayName("A shift that is not the business's is not found, before any store is judged")
  void anUnknownShiftIsNotFound() {
    for (Set<UUID> held : List.of(Set.of(A), Set.<UUID>of())) {
      Recording svc = new Recording();
      WorkforceResource r = resource(caller("MANAGER", held), svc);
      UUID theirs = Ids.newId();
      ApiException publish = assertThrows(ApiException.class, () -> r.publish(theirs, key()));
      assertEquals(404, publish.status());
      assertEquals("WORKFORCE_SHIFT_NOT_FOUND", publish.code());
      ApiException cancel = assertThrows(ApiException.class, () -> r.cancel(theirs, WHY));
      assertEquals(404, cancel.status());
      assertTrue(svc.acted.isEmpty());
    }
  }

  @Test
  @DisplayName("Below management and a shopper reach no shift")
  void onlyManagementChangesShifts() {
    for (String role : List.of("CASHIER", "STOREKEEPER", "CUSTOMER")) {
      Recording svc = new Recording();
      WorkforceResource r = resource(caller(role, Set.of()), svc);
      assertEquals(
          403, assertThrows(ApiException.class, () -> r.publish(SHIFT_AT_A, key())).status());
      assertEquals(403, assertThrows(ApiException.class, () -> r.cancel(SHIFT_AT_A, WHY)).status());
      assertTrue(svc.acted.isEmpty(), role);
    }
  }

  @Test
  @DisplayName("A pay rate is read for somebody at the caller's stores, and nobody else")
  void payRatesAreHeldToTheCallersStores() {
    Recording svc = new Recording();
    WorkforceResource r = resource(caller("MANAGER", Set.of(A)), svc);
    deniedAtTheStore(assertThrows(ApiException.class, () -> r.rates(PERSON_AT_B.toString())));
    assertTrue(svc.acted.isEmpty(), "another store's pay is never read");

    r.rates(PERSON_AT_A.toString());
    assertEquals(List.of("rates " + PERSON_AT_A), svc.acted);
    assertEquals(Set.of(A), svc.heldTo.get(1), "held to exactly the caller's stores");

    for (String role : List.of("OWNER", "MANAGER")) {
      Recording whole = new Recording();
      WorkforceResource businessWide = resource(caller(role, Set.of()), whole);
      businessWide.rates(PERSON_AT_B.toString());
      assertNull(whole.heldTo.get(0), role + ": held to no store");
      assertEquals(List.of("rates " + PERSON_AT_B), whole.acted);
    }
  }

  @Test
  @DisplayName("A person's commission arrangements are read as their pay rate is")
  void commissionArrangementsAreHeldToTheCallersStores() {
    Recording workforce = new Recording();
    Arrangements svc = new Arrangements();
    CommissionResource r = commission(caller("MANAGER", Set.of(A)), workforce, svc);
    deniedAtTheStore(assertThrows(ApiException.class, () -> r.assignments(PERSON_AT_B)));
    assertTrue(svc.read.isEmpty(), "another store's arrangements are never read");
    r.assignments(PERSON_AT_A);
    assertEquals(List.of(PERSON_AT_A), svc.read);

    Arrangements whole = new Arrangements();
    CommissionResource owner = commission(caller("OWNER", Set.of()), new Recording(), whole);
    owner.assignments(PERSON_AT_B);
    assertEquals(List.of(PERSON_AT_B), whole.read);
  }

  @Test
  @DisplayName(
      "What somebody's sales earn is worked out for a store-held caller only for people at their"
          + " stores")
  void commissionRatingIsHeldToTheCallersStores() {
    // Held to A: one seller from B (or nowhere) refuses the whole call before anything is rated,
    // since the answer names each person's scheme and bands.
    for (UUID[] sellers :
        List.of(
            new UUID[] {PERSON_AT_B},
            new UUID[] {PERSON_AT_A, PERSON_AT_B},
            new UUID[] {PERSON_AT_A, Ids.newId()})) {
      Arrangements svc = new Arrangements();
      CommissionResource r = commission(caller("MANAGER", Set.of(A)), new Recording(), svc);
      deniedAtTheStore(assertThrows(ApiException.class, () -> r.rate(sales(sellers))));
      assertTrue(svc.rated.isEmpty(), "nobody's commission is worked out");
    }

    Arrangements own = new Arrangements();
    commission(caller("MANAGER", Set.of(A)), new Recording(), own).rate(sales(PERSON_AT_A));
    assertEquals(List.of(List.of(PERSON_AT_A)), own.rated);

    Arrangements both = new Arrangements();
    commission(caller("MANAGER", Set.of(A, B)), new Recording(), both)
        .rate(sales(PERSON_AT_A, PERSON_AT_B));
    assertEquals(List.of(List.of(PERSON_AT_A, PERSON_AT_B)), both.rated);

    // Held to none — an owner, a business-wide manager, order-svc producing a statement (it
    // forwards no stores): anybody's.
    for (String role : List.of("OWNER", "MANAGER")) {
      Arrangements whole = new Arrangements();
      UUID stranger = Ids.newId();
      commission(caller(role, Set.of()), new Recording(), whole)
          .rate(sales(PERSON_AT_A, PERSON_AT_B, stranger));
      assertEquals(List.of(List.of(PERSON_AT_A, PERSON_AT_B, stranger)), whole.rated, role);
    }

    for (String role : List.of("CASHIER", "STOREKEEPER", "CUSTOMER")) {
      Arrangements svc = new Arrangements();
      CommissionResource r = commission(caller(role, Set.of()), new Recording(), svc);
      assertEquals(
          403, assertThrows(ApiException.class, () -> r.rate(sales(PERSON_AT_A))).status());
      assertTrue(svc.rated.isEmpty(), role);
    }
  }
}
