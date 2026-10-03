package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Broadcasts;
import com.storeql.tenant.domain.Broadcasts.Ack;
import com.storeql.tenant.domain.Broadcasts.Broadcast;
import com.storeql.tenant.domain.Commission.Scheme;
import com.storeql.tenant.domain.StoreTasks;
import com.storeql.tenant.domain.StoreTasks.Instance;
import com.storeql.tenant.domain.StoreTasks.Template;
import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.domain.Workforce.Shift;
import com.storeql.tenant.dto.BroadcastDtos;
import com.storeql.tenant.dto.CommissionDtos;
import com.storeql.tenant.dto.StoreTaskDtos;
import com.storeql.tenant.dto.WorkforceDtos;
import com.storeql.tenant.service.BroadcastService;
import com.storeql.tenant.service.CommissionService;
import com.storeql.tenant.service.CommissionService.Rated;
import com.storeql.tenant.service.CommissionService.SellerDays;
import com.storeql.tenant.service.StoreTaskService;
import com.storeql.tenant.service.WorkforceService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order a write is refused in, wherever a store is named or implied: what the request is (400),
 * then whether the store is the business's (404), then whether the caller is held to it (403), and
 * only then the work. So another business's staff naming our store — even with it among their own
 * store ids — are told it does not exist, a branch manager naming a sister branch is told it is not
 * theirs, and a malformed request is told so whoever sends it.
 *
 * <p>The real {@link TenantContext} decides; the services are stubs that record what reached them,
 * so a refusal is seen to stop the act before anything is written.
 */
class RefusalOrderTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();
  private static final UUID PERSON = Ids.newId();
  private static final UUID SHIFT_AT_A = Ids.newId();
  private static final UUID ENTRY_AT_A = Ids.newId();

  private static TenantContext caller(UUID tenant, String role, Set<UUID> heldTo) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, tenant, Ids.newId(), Set.of(role), heldTo, "req");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static TenantContext ours(String role, Set<UUID> heldTo) {
    return caller(TENANT, role, heldTo);
  }

  private static void refused(Executable act, int status, String code) {
    ApiException e = assertThrows(ApiException.class, act::run);
    assertEquals(status, e.status(), e.getMessage());
    assertEquals(code, e.code());
  }

  /** {@link org.junit.jupiter.api.function.Executable} without the checked exception. */
  @FunctionalInterface
  private interface Executable {
    void run();
  }

  // ── the roster and the clock ───────────────────────────────────────────────

  /** Our business has stores A and B; nobody else's store is ours. What reached it, in order. */
  private static final class Workforces extends WorkforceService {
    final List<String> acted = new ArrayList<>();

    @Override
    public UUID requireStore(UUID tenantId, UUID storeId) {
      if (TENANT.equals(tenantId) && (A.equals(storeId) || B.equals(storeId))) return storeId;
      throw ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant");
    }

    @Override
    public Shift planShift(
        UUID tenantId,
        UUID storeId,
        UUID userId,
        Instant startsAt,
        Instant endsAt,
        String duty,
        String note,
        UUID actorId) {
      acted.add("plan " + storeId);
      return new Shift(
          Ids.newId(),
          tenantId,
          storeId,
          userId,
          startsAt,
          endsAt,
          duty,
          Workforce.PLANNED,
          note,
          null,
          startsAt,
          actorId,
          startsAt);
    }

    @Override
    public Entry clockIn(
        UUID tenantId, UUID userId, UUID storeId, UUID shiftId, String source, UUID actorId) {
      acted.add("clock " + storeId);
      Instant now = Instant.now();
      return new Entry(
          Ids.newId(),
          tenantId,
          storeId,
          userId,
          shiftId,
          now,
          null,
          source,
          null,
          null,
          null,
          null,
          now,
          actorId,
          List.of());
    }

    @Override
    public UUID storeOfShift(UUID tenantId, UUID shiftId) {
      if (TENANT.equals(tenantId) && SHIFT_AT_A.equals(shiftId)) return A;
      throw ApiException.notFound("WORKFORCE_SHIFT_NOT_FOUND", "no such shift");
    }

    @Override
    public UUID storeOfEntry(UUID tenantId, UUID entryId) {
      if (TENANT.equals(tenantId) && ENTRY_AT_A.equals(entryId)) return A;
      throw ApiException.notFound("WORKFORCE_ENTRY_NOT_FOUND", "no such time entry");
    }

    @Override
    public Entry adjust(
        UUID tenantId,
        UUID entryId,
        Instant clockedInAt,
        Instant clockedOutAt,
        String reason,
        String idempotencyKey,
        UUID actorId) {
      acted.add("adjust " + entryId + " under " + idempotencyKey);
      Instant now = Instant.now();
      return new Entry(
          Ids.newId(),
          tenantId,
          A,
          PERSON,
          null,
          now,
          null,
          Workforce.SOURCE_MANAGER,
          null,
          reason,
          entryId,
          null,
          now,
          actorId,
          List.of());
    }

    @Override
    public Shift publishShift(UUID tenantId, UUID id, String idempotencyKey, UUID actorId) {
      acted.add("publish " + id + " under " + idempotencyKey);
      Instant now = Instant.now();
      return new Shift(
          id,
          tenantId,
          A,
          PERSON,
          now,
          now.plusSeconds(3600),
          null,
          Workforce.PUBLISHED,
          null,
          null,
          now,
          actorId,
          now);
    }
  }

  private static WorkforceResource roster(TenantContext ctx, Workforces svc) {
    WorkforceResource r = new WorkforceResource();
    r.ctx = ctx;
    r.svc = svc;
    r.maxWindowDays = 62;
    return r;
  }

  private static TimeClockResource clock(TenantContext ctx, Workforces svc) {
    TimeClockResource r = new TimeClockResource();
    r.ctx = ctx;
    r.svc = svc;
    r.maxWindowDays = 62;
    return r;
  }

  private static WorkforceDtos.PlanShiftRequest shiftAt(UUID store, String from, String to) {
    return new WorkforceDtos.PlanShiftRequest(
        store.toString(), PERSON.toString(), from, to, null, null);
  }

  private static WorkforceDtos.PlanShiftRequest shiftAt(UUID store) {
    return shiftAt(store, "2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z");
  }

  private static WorkforceDtos.ClockInRequest at(UUID store) {
    return new WorkforceDtos.ClockInRequest(store.toString(), null);
  }

  @Test
  @DisplayName(
      "Rostering a shift: a store not the business's is 404 before a store not the caller's is 403")
  void planningJudgesTheStoreBeforeTheCaller() {
    Workforces svc = new Workforces();
    // A branch manager of A naming B: B is the business's, but not theirs.
    refused(
        () -> roster(ours("MANAGER", Set.of(A)), svc).planShift(shiftAt(B)),
        403,
        "STORE_ACCESS_DENIED");
    // Naming a store that is nobody's in this business — even one listed among their own ids.
    UUID elsewhere = Ids.newId();
    refused(
        () -> roster(ours("MANAGER", Set.of(A, elsewhere)), svc).planShift(shiftAt(elsewhere)),
        404,
        "STORE_NOT_FOUND");
    // Another business's management, held to our store by its own headers or to none: not found.
    for (Set<UUID> held : List.of(Set.of(A), Set.<UUID>of())) {
      for (String role : List.of("OWNER", "MANAGER")) {
        refused(
            () -> roster(caller(RIVAL, role, held), svc).planShift(shiftAt(A)),
            404,
            "STORE_NOT_FOUND");
      }
    }
    assertTrue(svc.acted.isEmpty(), "nothing was rostered");

    roster(ours("MANAGER", Set.of(A)), svc).planShift(shiftAt(A));
    roster(ours("OWNER", Set.of()), svc).planShift(shiftAt(B));
    assertEquals(List.of("plan " + A, "plan " + B), svc.acted);
  }

  @Test
  @DisplayName("Rostering a shift: a window that is not one is 400, whoever sends it and wherever")
  void aMalformedShiftIsRefusedFirst() {
    Workforces svc = new Workforces();
    UUID unknown = Ids.newId();
    for (TenantContext who :
        List.of(
            ours("MANAGER", Set.of(A)),
            caller(RIVAL, "MANAGER", Set.of(A)),
            ours("OWNER", Set.of()))) {
      for (UUID store : List.of(A, B, unknown)) {
        refused(
            () ->
                roster(who, svc)
                    .planShift(shiftAt(store, "2026-10-05T17:00:00Z", "2026-10-05T09:00:00Z")),
            400,
            "WORKFORCE_WINDOW_INVALID");
        refused(
            () ->
                roster(who, svc)
                    .planShift(shiftAt(store, "2026-10-05T09:00:00Z", "2026-10-06T10:00:00Z")),
            400,
            "WORKFORCE_WINDOW_INVALID");
      }
    }
    assertTrue(svc.acted.isEmpty());
  }

  @Test
  @DisplayName(
      "Clocking in: a store not the business's is 404 before a store not the caller's is 403")
  void clockingInJudgesTheStoreBeforeTheCaller() {
    Workforces svc = new Workforces();
    // Our cashier at A, clocking in at our B: theirs to be told no.
    refused(
        () -> clock(ours("CASHIER", Set.of(A)), svc).clockIn(at(B)), 403, "STORE_ACCESS_DENIED");
    // Another business's cashier with our store among their own ids: no such store, nothing
    // written.
    for (String role : List.of("CASHIER", "STOREKEEPER", "MANAGER")) {
      refused(
          () -> clock(caller(RIVAL, role, Set.of(A)), svc).clockIn(at(A)), 404, "STORE_NOT_FOUND");
    }
    refused(
        () -> clock(caller(RIVAL, "OWNER", Set.of()), svc).clockIn(at(A)), 404, "STORE_NOT_FOUND");
    // A shopper is no staff at all.
    refused(() -> clock(caller(RIVAL, "CUSTOMER", Set.of()), svc).clockIn(at(A)), 403, "FORBIDDEN");
    assertTrue(svc.acted.isEmpty(), "nobody was clocked in");

    clock(ours("CASHIER", Set.of(A)), svc).clockIn(at(A));
    assertEquals(List.of("clock " + A), svc.acted);
  }

  @Test
  @DisplayName("Clocking somebody in by hand keeps the same order: 400, then 404, then 403")
  void clockingSomebodyInByHandKeepsTheOrder() {
    Workforces svc = new Workforces();
    refused(
        () -> roster(ours("MANAGER", Set.of(A)), svc).clockInFor(PERSON.toString(), at(B)),
        403,
        "STORE_ACCESS_DENIED");
    refused(
        () -> roster(caller(RIVAL, "MANAGER", Set.of(A)), svc).clockInFor(PERSON.toString(), at(A)),
        404,
        "STORE_NOT_FOUND");
    refused(
        () -> roster(caller(RIVAL, "MANAGER", Set.of(A)), svc).clockInFor("not-an-id", at(A)),
        400,
        "WORKFORCE_ID_INVALID");
    assertTrue(svc.acted.isEmpty());
  }

  // ── publishing a shift ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Publishing a shift needs an Idempotency-Key, refused before anything is read")
  void publishingNeedsAnIdempotencyKey() {
    Workforces svc = new Workforces();
    WorkforceResource r = roster(ours("MANAGER", Set.of(A)), svc);
    refused(() -> r.publish(SHIFT_AT_A, null), 400, "IDEMPOTENCY_KEY_REQUIRED");
    refused(() -> r.publish(SHIFT_AT_A, " "), 400, "IDEMPOTENCY_KEY_REQUIRED");
    refused(() -> r.publish(SHIFT_AT_A, "pos-1712345678-order"), 400, "IDEMPOTENCY_KEY_INVALID");
    // Below management is told so first, key or none.
    refused(
        () -> roster(ours("CASHIER", Set.of(A)), svc).publish(SHIFT_AT_A, null), 403, "FORBIDDEN");
    // Another business: no key is still a bad request; with one, no such shift.
    String key = Ids.newId().toString();
    refused(
        () -> roster(caller(RIVAL, "MANAGER", Set.of(A)), svc).publish(SHIFT_AT_A, key),
        404,
        "WORKFORCE_SHIFT_NOT_FOUND");
    assertTrue(svc.acted.isEmpty());

    // The key reaches the service in its canonical form.
    String upper = Ids.newId().toString().toUpperCase(java.util.Locale.ROOT);
    r.publish(SHIFT_AT_A, upper);
    assertEquals(
        List.of("publish " + SHIFT_AT_A + " under " + upper.toLowerCase(java.util.Locale.ROOT)),
        svc.acted);
  }

  // ── correcting hours ───────────────────────────────────────────────────────

  private static final WorkforceDtos.AdjustRequest FIX =
      new WorkforceDtos.AdjustRequest(null, "2026-10-05T17:00:00Z", "terminal was down");

  @Test
  @DisplayName(
      "Correcting hours needs an Idempotency-Key, refused before the body or the entry is read")
  void correctingHoursNeedsAnIdempotencyKey() {
    Workforces svc = new Workforces();
    WorkforceResource r = roster(ours("MANAGER", Set.of(A)), svc);
    refused(() -> r.adjust(ENTRY_AT_A, null, FIX), 400, "IDEMPOTENCY_KEY_REQUIRED");
    refused(() -> r.adjust(ENTRY_AT_A, " ", FIX), 400, "IDEMPOTENCY_KEY_REQUIRED");
    refused(
        () -> r.adjust(ENTRY_AT_A, "adjust-" + ENTRY_AT_A, FIX), 400, "IDEMPOTENCY_KEY_INVALID");
    // A malformed body, or one for nobody's entry, is still told about the key first.
    refused(() -> r.adjust(Ids.newId(), null, null), 400, "IDEMPOTENCY_KEY_REQUIRED");
    // Below management is told so first, key or none.
    refused(
        () -> roster(ours("CASHIER", Set.of(A)), svc).adjust(ENTRY_AT_A, null, FIX),
        403,
        "FORBIDDEN");
    String key = Ids.newId().toString();
    // With a key, a body that is not one is the next refusal, before the entry is looked up.
    refused(() -> r.adjust(Ids.newId(), key, null), 400, "BODY_REQUIRED");
    refused(
        () ->
            r.adjust(
                ENTRY_AT_A,
                key,
                new WorkforceDtos.AdjustRequest("yesterday", null, "terminal was down")),
        400,
        "WORKFORCE_DATE_INVALID");
    // Then the entry: another business's management, held to our store or to none, with its own
    // key or with ours, finds no such entry; a branch manager of B is told A is not theirs.
    for (Set<UUID> held : List.of(Set.of(A), Set.<UUID>of())) {
      for (String role : List.of("OWNER", "MANAGER")) {
        refused(
            () -> roster(caller(RIVAL, role, held), svc).adjust(ENTRY_AT_A, key, FIX),
            404,
            "WORKFORCE_ENTRY_NOT_FOUND");
      }
    }
    refused(
        () -> roster(ours("MANAGER", Set.of(B)), svc).adjust(ENTRY_AT_A, key, FIX),
        403,
        "STORE_ACCESS_DENIED");
    assertTrue(svc.acted.isEmpty(), "nothing was corrected");

    // The key reaches the service in its canonical form.
    String upper = Ids.newId().toString().toUpperCase(java.util.Locale.ROOT);
    r.adjust(ENTRY_AT_A, upper, FIX);
    assertEquals(
        List.of("adjust " + ENTRY_AT_A + " under " + upper.toLowerCase(java.util.Locale.ROOT)),
        svc.acted);
  }

  // ── commission ─────────────────────────────────────────────────────────────

  private static final class Arrangements extends CommissionService {
    final List<List<UUID>> rated = new ArrayList<>();

    @Override
    public List<Rated> rate(UUID tenantId, LocalDate from, LocalDate to, List<SellerDays> sellers) {
      rated.add(sellers.stream().map(SellerDays::userId).toList());
      return List.of();
    }

    @Override
    public List<Scheme> schemes(UUID tenantId, boolean activeOnly) {
      return List.of();
    }
  }

  /** Nobody works at the caller's stores: every seller named is refused once the period is good. */
  private static final class NobodyHere extends WorkforceService {
    @Override
    public void requirePeopleAtStores(
        UUID tenantId, java.util.Collection<UUID> people, Set<UUID> heldTo) {
      if (heldTo != null) throw ApiException.forbidden("STORE_ACCESS_DENIED", "not at your stores");
    }
  }

  /**
   * What some sellers sold, {@code rows} day-rows in all, at most 400 to a seller as the body
   * holds.
   */
  private static CommissionDtos.RateRequest sales(String from, String to, int rows) {
    List<CommissionDtos.SellerRequest> sellers = new ArrayList<>();
    for (int left = rows; left > 0; left -= 400) {
      List<CommissionDtos.DayRequest> days = new ArrayList<>();
      for (int i = 0; i < Math.min(400, left); i++) {
        days.add(new CommissionDtos.DayRequest("2026-09-02", new BigDecimal("100"), null));
      }
      sellers.add(new CommissionDtos.SellerRequest(Ids.newId().toString(), days));
    }
    return new CommissionDtos.RateRequest(from, to, sellers);
  }

  private static CommissionResource commission(TenantContext ctx, Arrangements svc) {
    CommissionResource r = new CommissionResource();
    r.ctx = ctx;
    r.svc = svc;
    r.workforce = new NobodyHere();
    return r;
  }

  @Test
  @DisplayName("Rating sales: a period that is not one is 400 before a seller elsewhere is 403")
  void aPeriodIsJudgedBeforeTheSellers() {
    Arrangements svc = new Arrangements();
    CommissionResource held = commission(ours("MANAGER", Set.of(A)), svc);
    refused(
        () -> held.rate(sales("2026-09-30", "2026-09-01", 1)), 400, "COMMISSION_PERIOD_INVALID");
    refused(() -> held.rate(sales(null, "2026-09-30", 1)), 400, "VALIDATION_FAILED");
    refused(
        () -> held.rate(sales("2026-09-01", "2026-09-30", 10_001)),
        400,
        "COMMISSION_PERIOD_TOO_LARGE");
    refused(() -> held.rate(sales("2026-09-31", "2026-09-30", 1)), 400, "COMMISSION_DATE_INVALID");
    assertTrue(svc.rated.isEmpty());
    // A good period: now the seller elsewhere is the refusal.
    refused(() -> held.rate(sales("2026-09-01", "2026-09-30", 1)), 403, "STORE_ACCESS_DENIED");
    assertTrue(svc.rated.isEmpty(), "nobody's commission is worked out");
  }

  // ── store tasks ────────────────────────────────────────────────────────────

  private static Instance task(UUID id, UUID storeId) {
    return new Instance(
        id,
        TENANT,
        storeId,
        Ids.newId(),
        LocalDate.now(),
        Instant.now(),
        StoreTasks.OPEN,
        "Open up",
        StoreTasks.OPENING,
        null,
        true,
        null,
        null,
        null,
        null,
        Instant.now(),
        List.of());
  }

  /**
   * Our business's tasks by id, and what reached the service with whether it could act anywhere.
   */
  private static final class Tasks extends StoreTaskService {
    final Map<UUID, Instance> tasks = new java.util.HashMap<>();
    final List<String> acted = new ArrayList<>();

    UUID at(UUID storeId) {
      UUID id = Ids.newId();
      tasks.put(id, task(id, storeId));
      return id;
    }

    @Override
    public Instance instance(UUID tenantId, UUID id) {
      Instance i = TENANT.equals(tenantId) ? tasks.get(id) : null;
      if (i == null) throw ApiException.notFound("TASK_NOT_FOUND", "no such task");
      return i;
    }

    @Override
    public Instance tick(
        UUID tenantId, UUID instanceId, int position, UUID userId, boolean anyStore) {
      acted.add("tick " + instanceId + (anyStore ? " anywhere" : " assigned"));
      return instance(tenantId, instanceId);
    }

    @Override
    public Instance complete(
        UUID tenantId, UUID instanceId, String note, UUID userId, boolean anyStore) {
      acted.add("complete " + instanceId + (anyStore ? " anywhere" : " assigned"));
      return instance(tenantId, instanceId);
    }

    @Override
    public Instance skip(
        UUID tenantId, UUID instanceId, String reason, UUID userId, boolean anyStore) {
      acted.add("skip " + instanceId + (anyStore ? " anywhere" : " assigned"));
      return instance(tenantId, instanceId);
    }

    @Override
    public UUID requireStore(UUID tenantId, UUID storeId) {
      if (TENANT.equals(tenantId) && (A.equals(storeId) || B.equals(storeId))) return storeId;
      throw ApiException.notFound("STORE_NOT_FOUND", "no such store");
    }

    @Override
    public Template create(
        UUID tenantId,
        UUID storeId,
        String title,
        String instructions,
        String kind,
        Set<Integer> daysOfWeek,
        java.time.LocalTime dueTime,
        Integer graceMinutes,
        String role,
        boolean required,
        List<Line> lines,
        UUID actorId) {
      acted.add("write a list at " + storeId);
      return new Template(
          Ids.newId(),
          tenantId,
          storeId,
          title,
          null,
          StoreTasks.OPENING,
          Set.of(),
          dueTime,
          60,
          null,
          true,
          StoreTasks.ACTIVE,
          Instant.now(),
          actorId,
          null,
          null,
          List.of());
    }

    @Override
    public int generate(UUID tenantId, UUID storeId, LocalDate businessDate) {
      acted.add("generate " + storeId);
      return 0;
    }

    @Override
    public Instance raise(UUID tenantId, UUID templateId, UUID storeId, LocalDate businessDate) {
      acted.add("raise at " + storeId);
      return task(Ids.newId(), storeId);
    }

    @Override
    public List<Instance> day(UUID tenantId, UUID storeId, LocalDate from, LocalDate to) {
      acted.add("read the days of " + storeId);
      return List.of();
    }

    @Override
    public List<StoreTasks.Day> summary(UUID tenantId, UUID storeId, LocalDate from, LocalDate to) {
      acted.add("sum up " + storeId);
      return List.of();
    }

    @Override
    public LocalDate today(UUID tenantId, UUID storeId) {
      return LocalDate.now();
    }
  }

  private static StoreTaskResource lists(TenantContext ctx, Tasks svc) {
    StoreTaskResource r = new StoreTaskResource();
    r.ctx = ctx;
    r.svc = svc;
    return r;
  }

  private static StoreTaskDtos.TemplateRequest openingAt(UUID store, String kind) {
    return new StoreTaskDtos.TemplateRequest(
        "Open up",
        null,
        kind,
        store.toString(),
        null,
        "08:00",
        null,
        null,
        null,
        List.of(new StoreTaskDtos.LineRequest("Unlock", true)));
  }

  /** Every write and read of task lists that names a store, as one caller, at one store. */
  private static List<Executable> everyListActAt(TenantContext who, Tasks svc, UUID store) {
    StoreTaskResource admin = lists(who, svc);
    String s = store.toString();
    return List.of(
        () -> admin.create(openingAt(store, "OPENING")),
        () -> admin.generate(new StoreTaskDtos.GenerateRequest(s, null)),
        () -> admin.raise(new StoreTaskDtos.RaiseRequest(Ids.newId().toString(), s, null)),
        () -> admin.days(s, "2026-10-01", "2026-10-02"),
        () -> admin.summary(s, "2026-10-01", "2026-10-02"),
        () -> work(who, svc).today(s, null));
  }

  @Test
  @DisplayName(
      "Task lists: a store not the business's is 404 before a store not the caller's is 403")
  void taskListsJudgeTheStoreBeforeTheCaller() {
    Tasks svc = new Tasks();
    UUID unknown = Ids.newId();
    // A branch manager of A naming B: B is the business's, but not theirs.
    for (Executable act : everyListActAt(ours("MANAGER", Set.of(A)), svc, B)) {
      refused(act, 403, "STORE_ACCESS_DENIED");
    }
    // A branch manager of A naming a store that is nobody's in this business: not found, whether
    // or not it is among their own ids; and so for a caller held to none.
    for (TenantContext who :
        List.of(
            ours("MANAGER", Set.of(A)),
            ours("MANAGER", Set.of(A, unknown)),
            ours("OWNER", Set.<UUID>of()))) {
      for (Executable act : everyListActAt(who, svc, unknown)) {
        refused(act, 404, "STORE_NOT_FOUND");
      }
    }
    // Another business's management, held to our store by its own headers, to its own store, or
    // to none: our store does not exist for it.
    for (Set<UUID> held : List.of(Set.of(A), Set.of(Ids.newId()), Set.<UUID>of())) {
      for (String role : List.of("OWNER", "MANAGER")) {
        for (Executable act : everyListActAt(caller(RIVAL, role, held), svc, A)) {
          refused(act, 404, "STORE_NOT_FOUND");
        }
      }
    }
    // Another business's cashier reading our store's list: no such store either.
    for (Set<UUID> held : List.of(Set.of(A), Set.of(Ids.newId()))) {
      refused(
          () -> work(caller(RIVAL, "CASHIER", held), svc).today(A.toString(), null),
          404,
          "STORE_NOT_FOUND");
    }
    assertTrue(svc.acted.isEmpty(), "nothing was written or read");

    for (Executable act : everyListActAt(ours("MANAGER", Set.of(A)), svc, A)) act.run();
    assertEquals(
        List.of(
            "write a list at " + A,
            "generate " + A,
            "raise at " + A,
            "read the days of " + A,
            "sum up " + A,
            "read the days of " + A),
        svc.acted);
  }

  @Test
  @DisplayName("Task lists: a request that is not one is 400, whoever sends it and wherever")
  void aMalformedListActIsRefusedFirst() {
    Tasks svc = new Tasks();
    UUID unknown = Ids.newId();
    for (TenantContext who :
        List.of(
            ours("MANAGER", Set.of(A)),
            caller(RIVAL, "MANAGER", Set.of(A)),
            caller(RIVAL, "OWNER", Set.<UUID>of()))) {
      StoreTaskResource r = lists(who, svc);
      for (UUID store : List.of(A, B, unknown)) {
        String s = store.toString();
        refused(() -> r.create(openingAt(store, "SOMETIMES")), 400, "TASK_LIST_INVALID");
        refused(
            () -> r.generate(new StoreTaskDtos.GenerateRequest(s, "soon")),
            400,
            "TASK_DATE_INVALID");
        refused(
            () -> r.raise(new StoreTaskDtos.RaiseRequest("not-an-id", s, null)),
            400,
            "TASK_ID_INVALID");
        refused(() -> r.days(s, "2026-10-02", "2026-10-01"), 400, "TASK_RANGE_INVALID");
        refused(() -> r.summary(s, "2026-01-01", "2026-12-31"), 400, "TASK_RANGE_INVALID");
        refused(() -> work(who, svc).today(s, "today"), 400, "TASK_DATE_INVALID");
      }
    }
    assertTrue(svc.acted.isEmpty());
  }

  // ── notices ────────────────────────────────────────────────────────────────

  /** Our business's notices, and what reached the service with the tiers it acted as. */
  private static final class Notices extends BroadcastService {
    final List<String> acted = new ArrayList<>();

    @Override
    public UUID requireStore(UUID tenantId, UUID storeId) {
      if (TENANT.equals(tenantId) && (A.equals(storeId) || B.equals(storeId))) return storeId;
      throw ApiException.notFound("STORE_NOT_FOUND", "no such store");
    }

    @Override
    public Broadcast publish(
        UUID tenantId,
        String title,
        String body,
        String priority,
        UUID storeId,
        String role,
        boolean requiresAck,
        Instant expiresAt,
        UUID actorId) {
      acted.add("publish to " + storeId);
      return new Broadcast(
          Ids.newId(),
          tenantId,
          title,
          body,
          priority,
          storeId,
          role,
          requiresAck,
          Instant.now(),
          expiresAt,
          Broadcasts.PUBLISHED,
          actorId,
          null,
          null,
          null);
    }

    @Override
    public List<Seen> current(UUID tenantId, UUID storeId, UUID userId, List<String> anywhereAs) {
      acted.add("read at " + storeId + " as " + anywhereAs);
      return List.of();
    }

    @Override
    public Ack acknowledge(
        UUID tenantId, UUID id, UUID storeId, UUID userId, List<String> anywhereAs) {
      acted.add("acknowledge at " + storeId + " as " + anywhereAs);
      return new Ack(Ids.newId(), tenantId, id, userId, storeId, Instant.now());
    }
  }

  private static BroadcastResource notices(TenantContext ctx, Notices svc) {
    BroadcastResource r = new BroadcastResource();
    r.ctx = ctx;
    r.svc = svc;
    return r;
  }

  private static BroadcastReadResource reading(TenantContext ctx, Notices svc) {
    BroadcastReadResource r = new BroadcastReadResource();
    r.ctx = ctx;
    r.svc = svc;
    return r;
  }

  private static BroadcastDtos.PublishRequest noticeTo(UUID store, String priority, String expiry) {
    return new BroadcastDtos.PublishRequest(
        "Recall", "Batch 42 off the shelf.", priority, store.toString(), null, true, expiry);
  }

  private static final UUID NOTICE = Ids.newId();

  /** Publishing to, reading at and acknowledging at one store, as one caller. */
  private static List<Executable> everyNoticeActAt(TenantContext who, Notices svc, UUID store) {
    return List.of(
        () -> notices(who, svc).publish(noticeTo(store, "URGENT", null)),
        () -> reading(who, svc).current(store.toString()),
        () ->
            reading(who, svc).acknowledge(NOTICE, new BroadcastDtos.AckRequest(store.toString())));
  }

  @Test
  @DisplayName("Notices: a store not the business's is 404 before a store not the caller's is 403")
  void noticesJudgeTheStoreBeforeTheCaller() {
    Notices svc = new Notices();
    UUID unknown = Ids.newId();
    // Our branch manager, and our cashier, at A naming B: the business's, but not theirs.
    for (String role : List.of("MANAGER", "CASHIER")) {
      List<Executable> acts = everyNoticeActAt(ours(role, Set.of(A)), svc, B);
      for (Executable act : "MANAGER".equals(role) ? acts : acts.subList(1, 3)) {
        refused(act, 403, "STORE_ACCESS_DENIED");
      }
    }
    // A store that is nobody's in this business, whoever names it.
    for (TenantContext who :
        List.of(
            ours("MANAGER", Set.of(A)),
            ours("MANAGER", Set.of(A, unknown)),
            ours("OWNER", Set.<UUID>of()))) {
      for (Executable act : everyNoticeActAt(who, svc, unknown)) {
        refused(act, 404, "STORE_NOT_FOUND");
      }
    }
    // Another business's management, held to our store, its own or none: not found.
    for (Set<UUID> held : List.of(Set.of(A), Set.of(Ids.newId()), Set.<UUID>of())) {
      for (String role : List.of("OWNER", "MANAGER")) {
        for (Executable act : everyNoticeActAt(caller(RIVAL, role, held), svc, A)) {
          refused(act, 404, "STORE_NOT_FOUND");
        }
      }
    }
    // Another business's staff reading or acknowledging at our store: not found either.
    for (String role : List.of("CASHIER", "STOREKEEPER")) {
      for (Set<UUID> held : List.of(Set.of(A), Set.of(Ids.newId()), Set.<UUID>of())) {
        for (Executable act : everyNoticeActAt(caller(RIVAL, role, held), svc, A).subList(1, 3)) {
          refused(act, 404, "STORE_NOT_FOUND");
        }
      }
    }
    // A shopper is no staff at all.
    for (Executable act : everyNoticeActAt(caller(RIVAL, "CUSTOMER", Set.of()), svc, A)) {
      refused(act, 403, "FORBIDDEN");
    }
    assertTrue(svc.acted.isEmpty(), "nothing was published, read or acknowledged");
  }

  @Test
  @DisplayName("Notices: a notice that tells nobody anything is 400, whoever sends it and wherever")
  void aMalformedNoticeIsRefusedFirst() {
    Notices svc = new Notices();
    for (TenantContext who :
        List.of(
            ours("MANAGER", Set.of(A)),
            caller(RIVAL, "MANAGER", Set.of(A)),
            caller(RIVAL, "OWNER", Set.<UUID>of()))) {
      for (UUID store : List.of(A, B, Ids.newId())) {
        refused(
            () -> notices(who, svc).publish(noticeTo(store, "SHOUT", null)),
            400,
            "BROADCAST_INVALID");
        refused(
            () -> notices(who, svc).publish(noticeTo(store, "INFO", "tomorrow")),
            400,
            "BROADCAST_EXPIRY_INVALID");
        refused(
            () -> notices(who, svc).publish(noticeTo(store, "INFO", "2020-01-01T00:00:00Z")),
            400,
            "BROADCAST_INVALID");
      }
    }
    assertTrue(svc.acted.isEmpty());
  }

  @Test
  @DisplayName(
      "Notices: management held to no store reads and acknowledges at any store as its tier;"
          + " everybody else only where assigned")
  void businessWideManagementReadsAndAcknowledgesAnywhere() {
    Notices svc = new Notices();
    for (String role : List.of("OWNER", "MANAGER")) {
      for (Executable act : everyNoticeActAt(ours(role, Set.of()), svc, B).subList(1, 3)) {
        act.run();
      }
    }
    assertEquals(
        List.of(
            "read at " + B + " as [OWNER]",
            "acknowledge at " + B + " as [OWNER]",
            "read at " + B + " as [MANAGER]",
            "acknowledge at " + B + " as [MANAGER]"),
        svc.acted);

    // A branch manager at their store, a cashier there, and a cashier whose token names no store:
    // each held to where they are assigned (the service's 409 when they are not).
    svc.acted.clear();
    everyNoticeActAt(ours("MANAGER", Set.of(A)), svc, A).get(2).run();
    everyNoticeActAt(ours("CASHIER", Set.of(A)), svc, A).get(2).run();
    everyNoticeActAt(ours("CASHIER", Set.of()), svc, A).get(1).run();
    assertEquals(
        List.of(
            "acknowledge at " + A + " as []",
            "acknowledge at " + A + " as []",
            "read at " + A + " as []"),
        svc.acted);
  }

  private static StoreTaskWorkResource work(TenantContext ctx, Tasks svc) {
    StoreTaskWorkResource r = new StoreTaskWorkResource();
    r.ctx = ctx;
    r.svc = svc;
    return r;
  }

  private static final StoreTaskDtos.SkipRequest WHY = new StoreTaskDtos.SkipRequest("flooded");

  @Test
  @DisplayName(
      "Working a task at another store: 403 STORE_ACCESS_DENIED for a store-held caller, not"
          + " 409 unassigned")
  void aStoreHeldCallerElsewhereIsDeniedTheStore() {
    Tasks svc = new Tasks();
    UUID atB = svc.at(B);
    for (String role : List.of("CASHIER", "STOREKEEPER", "MANAGER")) {
      StoreTaskWorkResource r = work(ours(role, Set.of(A)), svc);
      refused(() -> r.tick(atB, 1), 403, "STORE_ACCESS_DENIED");
      refused(() -> r.complete(atB, null), 403, "STORE_ACCESS_DENIED");
      refused(() -> r.skip(atB, WHY), 403, "STORE_ACCESS_DENIED");
    }
    assertTrue(svc.acted.isEmpty(), "another store's task is never worked");
  }

  @Test
  @DisplayName("Working another business's task is 404, even naming our store among its own")
  void anotherBusinessFindsNoTask() {
    Tasks svc = new Tasks();
    UUID atA = svc.at(A);
    for (String role : List.of("CASHIER", "STOREKEEPER", "MANAGER", "OWNER")) {
      for (Set<UUID> held : List.of(Set.of(A), Set.<UUID>of())) {
        StoreTaskWorkResource r = work(caller(RIVAL, role, held), svc);
        refused(() -> r.tick(atA, 1), 404, "TASK_NOT_FOUND");
        refused(() -> r.complete(atA, null), 404, "TASK_NOT_FOUND");
        refused(() -> r.skip(atA, WHY), 404, "TASK_NOT_FOUND");
      }
    }
    refused(() -> work(caller(RIVAL, "CUSTOMER", Set.of()), svc).tick(atA, 1), 403, "FORBIDDEN");
    assertTrue(svc.acted.isEmpty());
  }

  @Test
  @DisplayName(
      "Management held to no store works any store's task; everybody else only where assigned")
  void businessWideManagementWorksAnyStore() {
    Tasks svc = new Tasks();
    UUID atB = svc.at(B);
    UUID atA = svc.at(A);
    for (String role : List.of("OWNER", "MANAGER")) {
      StoreTaskWorkResource r = work(ours(role, Set.of()), svc);
      r.tick(atB, 1);
      r.complete(atB, null);
      r.skip(atB, WHY);
    }
    assertEquals(
        List.of(
            "tick " + atB + " anywhere",
            "complete " + atB + " anywhere",
            "skip " + atB + " anywhere",
            "tick " + atB + " anywhere",
            "complete " + atB + " anywhere",
            "skip " + atB + " anywhere"),
        svc.acted);

    // A branch manager at their own store, a cashier there, and a cashier whose token names no
    // store: each held to where they are assigned (the service's 409 when they are not).
    svc.acted.clear();
    work(ours("MANAGER", Set.of(A)), svc).tick(atA, 1);
    work(ours("CASHIER", Set.of(A)), svc).complete(atA, null);
    work(ours("CASHIER", Set.of()), svc).skip(atA, WHY);
    assertEquals(
        List.of(
            "tick " + atA + " assigned",
            "complete " + atA + " assigned",
            "skip " + atA + " assigned"),
        svc.acted);
  }
}
