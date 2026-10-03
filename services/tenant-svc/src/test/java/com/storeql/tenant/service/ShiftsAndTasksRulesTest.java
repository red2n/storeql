package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Broadcasts;
import com.storeql.tenant.domain.Broadcasts.Ack;
import com.storeql.tenant.domain.Broadcasts.Broadcast;
import com.storeql.tenant.domain.StoreTasks;
import com.storeql.tenant.domain.StoreTasks.Instance;
import com.storeql.tenant.domain.StoreTasks.InstanceItem;
import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.domain.Workforce.Shift;
import com.storeql.tenant.repo.BroadcastRepository;
import com.storeql.tenant.repo.StoreTaskRepository;
import com.storeql.tenant.repo.WorkforceRepository;
import com.storeql.tenant.repo.WorkforceRepository.Adjustment;
import com.storeql.tenant.repo.WorkforceRepository.Publication;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules the services hold whoever calls them: hours tied to a shift are hours at its store; a
 * publish under a key answers the same the second time; a list is worked where one is assigned,
 * unless management held to no store is working it; an hourly rate is kept to the places its column
 * holds. The repositories are stubs holding just enough, so each rule is seen alone.
 */
class ShiftsAndTasksRulesTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();
  private static final UUID PERSON = Ids.newId();
  private static final UUID MANAGER = Ids.newId();

  private static Shift shift(UUID id, UUID storeId, UUID userId, String status) {
    Instant now = Instant.now();
    return new Shift(
        id,
        TENANT,
        storeId,
        userId,
        now,
        now.plusSeconds(8 * 3600),
        null,
        status,
        null,
        Workforce.CANCELLED.equals(status) ? "closed" : null,
        now,
        MANAGER,
        now);
  }

  /** Who works where, the shifts there are, and the entries written. */
  private static final class Roster extends WorkforceRepository {
    final Map<UUID, Shift> shifts = new HashMap<>();
    final List<Entry> written = new ArrayList<>();
    Publication publication = Publication.PUBLISHED;
    final List<String> keys = new ArrayList<>();
    final Map<UUID, Entry> entries = new HashMap<>();
    final Map<String, Adjustment> adjustments = new HashMap<>();
    final List<Entry> corrected = new ArrayList<>();

    /**
     * What a racing attempt under the same key committed while this one waited, or null: {@link
     * #adjust} answers it instead of writing.
     */
    Adjustment raced;

    @Override
    public Optional<Entry> entry(UUID tenantId, UUID id) {
      return TENANT.equals(tenantId) ? Optional.ofNullable(entries.get(id)) : Optional.empty();
    }

    @Override
    public List<Workforce.PayRate> rates(UUID tenantId, UUID userId) {
      return List.of();
    }

    @Override
    public Optional<Adjustment> adjustmentUnder(UUID tenantId, String key) {
      keys.add(key);
      return TENANT.equals(tenantId) ? Optional.ofNullable(adjustments.get(key)) : Optional.empty();
    }

    @Override
    public Adjustment adjust(
        Entry correction, List<Workforce.Rest> breaks, OutboxRow labour, String key) {
      if (raced != null) return raced;
      corrected.add(correction);
      entries.put(correction.id(), correction);
      Adjustment made = new Adjustment(correction.supersedes(), correction.id());
      adjustments.put(key, made);
      return made;
    }

    @Override
    public boolean worksAt(UUID tenantId, UUID userId, UUID storeId) {
      return TENANT.equals(tenantId)
          && PERSON.equals(userId)
          && (A.equals(storeId) || B.equals(storeId));
    }

    @Override
    public Optional<Shift> shift(UUID tenantId, UUID id) {
      return TENANT.equals(tenantId) ? Optional.ofNullable(shifts.get(id)) : Optional.empty();
    }

    @Override
    public Entry clockIn(Entry e) {
      written.add(e);
      return e;
    }

    @Override
    public Publication publishShift(UUID tenantId, UUID shiftId, String key, UUID actorId) {
      keys.add(key);
      return publication;
    }
  }

  private static WorkforceService workforce(Roster repo) {
    WorkforceService s = new WorkforceService();
    s.repo = repo;
    return s;
  }

  @Test
  @DisplayName("A clock-in naming a shift at another store is refused, and nothing is written")
  void aShiftAtAnotherStoreIsRefused() {
    Roster repo = new Roster();
    UUID atB = Ids.newId();
    repo.shifts.put(atB, shift(atB, B, PERSON, Workforce.PUBLISHED));
    WorkforceService s = workforce(repo);

    for (String source : List.of(Workforce.SOURCE_CLOCK, Workforce.SOURCE_MANAGER)) {
      UUID actor = Workforce.SOURCE_CLOCK.equals(source) ? PERSON : MANAGER;
      ApiException refused =
          assertThrows(ApiException.class, () -> s.clockIn(TENANT, PERSON, A, atB, source, actor));
      assertEquals(400, refused.status());
      assertEquals("WORKFORCE_SHIFT_AT_ANOTHER_STORE", refused.code(), source);
    }
    assertTrue(repo.written.isEmpty(), "no hours at A answer a shift at B");

    // At the shift's own store it is clocked on to.
    Entry in = s.clockIn(TENANT, PERSON, B, atB, Workforce.SOURCE_CLOCK, PERSON);
    assertEquals(atB, in.shiftId());
    assertEquals(B, in.storeId());
  }

  @Test
  @DisplayName(
      "Somebody else's shift is theirs to refuse first, wherever it is; a called-off one after")
  void whoseShiftComesBeforeWhere() {
    Roster repo = new Roster();
    UUID theirsAtB = Ids.newId();
    repo.shifts.put(theirsAtB, shift(theirsAtB, B, Ids.newId(), Workforce.PUBLISHED));
    UUID offAtA = Ids.newId();
    repo.shifts.put(offAtA, shift(offAtA, A, PERSON, Workforce.CANCELLED));
    WorkforceService s = workforce(repo);

    ApiException notTheirs =
        assertThrows(
            ApiException.class,
            () -> s.clockIn(TENANT, PERSON, A, theirsAtB, Workforce.SOURCE_CLOCK, PERSON));
    assertEquals("WORKFORCE_SHIFT_NOT_THEIRS", notTheirs.code());
    ApiException cancelled =
        assertThrows(
            ApiException.class,
            () -> s.clockIn(TENANT, PERSON, A, offAtA, Workforce.SOURCE_CLOCK, PERSON));
    assertEquals("WORKFORCE_SHIFT_CANCELLED", cancelled.code());
    // Another business's shift id is no shift at all.
    ApiException unknown =
        assertThrows(
            ApiException.class,
            () -> s.clockIn(TENANT, PERSON, A, Ids.newId(), Workforce.SOURCE_CLOCK, PERSON));
    assertEquals(404, unknown.status());
    assertTrue(repo.written.isEmpty());
  }

  @Test
  @DisplayName(
      "A publish under a key answers the shift; a replay answers it again; a reused key is refused")
  void publishOutcomes() {
    Roster repo = new Roster();
    UUID planned = Ids.newId();
    repo.shifts.put(planned, shift(planned, A, PERSON, Workforce.PUBLISHED));
    WorkforceService s = workforce(repo);
    String key = Ids.newId().toString();

    assertEquals(planned, s.publishShift(TENANT, planned, key, MANAGER).id());
    repo.publication = Publication.REPLAYED;
    assertEquals(
        Workforce.PUBLISHED,
        s.publishShift(TENANT, planned, key, MANAGER).status(),
        "a retry is answered with the published shift, not a conflict");
    assertEquals(List.of(key, key), repo.keys);

    repo.publication = Publication.KEY_REUSED;
    ApiException reused =
        assertThrows(ApiException.class, () -> s.publishShift(TENANT, planned, key, MANAGER));
    assertEquals(409, reused.status());
    assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());

    repo.publication = Publication.NOT_PLANNED;
    ApiException notPlanned =
        assertThrows(ApiException.class, () -> s.publishShift(TENANT, planned, key, MANAGER));
    assertEquals("WORKFORCE_SHIFT_NOT_PLANNED", notPlanned.code());
    ApiException missing =
        assertThrows(ApiException.class, () -> s.publishShift(TENANT, Ids.newId(), key, MANAGER));
    assertEquals(404, missing.status());
    assertEquals("WORKFORCE_SHIFT_NOT_FOUND", missing.code());
  }

  // ── correcting hours under a key ───────────────────────────────────────────

  /** A closed entry of PERSON's at A, clocked by them. */
  private static Entry clocked(Roster repo) {
    Instant in = Instant.parse("2026-09-14T08:00:00Z");
    Entry e =
        new Entry(
            Ids.newId(),
            TENANT,
            A,
            PERSON,
            null,
            in,
            in.plusSeconds(8 * 3600),
            Workforce.SOURCE_CLOCK,
            null,
            null,
            null,
            null,
            in,
            PERSON,
            List.of());
    repo.entries.put(e.id(), e);
    return e;
  }

  /** The entry as it stands once a correction has replaced it. */
  private static void superseded(Roster repo, Entry e, UUID by) {
    repo.entries.put(
        e.id(),
        new Entry(
            e.id(),
            e.tenantId(),
            e.storeId(),
            e.userId(),
            e.shiftId(),
            e.clockedInAt(),
            e.clockedOutAt(),
            e.source(),
            e.note(),
            e.adjustedReason(),
            e.supersedes(),
            by,
            e.createdAt(),
            e.createdBy(),
            e.breaks()));
  }

  private static final Instant NINE = Instant.parse("2026-09-14T09:00:00Z");
  private static final Instant FIVE = Instant.parse("2026-09-14T17:00:00Z");

  @Test
  @DisplayName(
      "A correction under a key is made once: a retry answers it again, and the key used for"
          + " another correction is refused with nothing written")
  void aCorrectionIsARetryableWrite() {
    Roster repo = new Roster();
    Entry original = clocked(repo);
    Entry other = clocked(repo);
    WorkforceService s = workforce(repo);
    String key = Ids.newId().toString();

    Entry made = s.adjust(TENANT, original.id(), NINE, FIVE, " terminal down ", key, MANAGER);
    assertEquals(original.id(), made.supersedes());
    assertEquals(1, repo.corrected.size());
    superseded(repo, original, made.id());

    // The answer was lost and the same request is sent again: the same correction, not a 409.
    Entry again = s.adjust(TENANT, original.id(), NINE, FIVE, "terminal down", key, MANAGER);
    assertEquals(made.id(), again.id());
    assertEquals(1, repo.corrected.size(), "nothing more is written");

    // The same key with other hours, another reason, or for another entry: another request.
    for (Runnable reuse :
        List.<Runnable>of(
            () ->
                s.adjust(
                    TENANT,
                    original.id(),
                    NINE,
                    FIVE.plusSeconds(60),
                    "terminal down",
                    key,
                    MANAGER),
            () -> s.adjust(TENANT, original.id(), NINE, FIVE, "forgot to clock out", key, MANAGER),
            () -> s.adjust(TENANT, other.id(), NINE, FIVE, "terminal down", key, MANAGER))) {
      ApiException reused = assertThrows(ApiException.class, reuse::run);
      assertEquals(409, reused.status());
      assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
    }
    assertEquals(1, repo.corrected.size());

    // A new key on the entry already corrected is a second correction: refused as before.
    ApiException twice =
        assertThrows(
            ApiException.class,
            () ->
                s.adjust(
                    TENANT, original.id(), NINE, FIVE, "again", Ids.newId().toString(), MANAGER));
    assertEquals("WORKFORCE_ENTRY_NOT_STANDING", twice.code());
    assertEquals(1, repo.corrected.size());
  }

  @Test
  @DisplayName("A retry that raced its first attempt is answered with what the first attempt made")
  void aRacingRetryIsAnsweredWithTheFirstCorrection() {
    Roster repo = new Roster();
    Entry original = clocked(repo);
    WorkforceService s = workforce(repo);
    String key = Ids.newId().toString();
    // The first attempt committed while this one was being judged.
    Entry first = s.adjust(TENANT, original.id(), NINE, FIVE, "terminal down", key, MANAGER);
    repo.adjustments.clear();
    repo.raced = new Adjustment(original.id(), first.id());

    Entry answered = s.adjust(TENANT, original.id(), NINE, FIVE, "terminal down", key, MANAGER);
    assertEquals(first.id(), answered.id());
    // The same race under the key but for other hours is still another request.
    ApiException reused =
        assertThrows(
            ApiException.class,
            () ->
                s.adjust(
                    TENANT,
                    original.id(),
                    NINE,
                    FIVE.plusSeconds(1),
                    "terminal down",
                    key,
                    MANAGER));
    assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
  }

  @Test
  @DisplayName(
      "Whose hours they are is judged before the key: one's own hours are refused under any key")
  void ownHoursAreRefusedBeforeTheKeyIsRead() {
    Roster repo = new Roster();
    Entry original = clocked(repo);
    WorkforceService s = workforce(repo);
    ApiException own =
        assertThrows(
            ApiException.class,
            () ->
                s.adjust(
                    TENANT, original.id(), NINE, FIVE, "mine", Ids.newId().toString(), PERSON));
    assertEquals("WORKFORCE_SELF_ADJUST_REFUSED", own.code());
    ApiException backwards =
        assertThrows(
            ApiException.class,
            () ->
                s.adjust(
                    TENANT, original.id(), FIVE, NINE, "fix", Ids.newId().toString(), MANAGER));
    assertEquals("WORKFORCE_WINDOW_INVALID", backwards.code());
    assertTrue(repo.keys.isEmpty(), "no key was looked up for a refused request");
    assertTrue(repo.corrected.isEmpty());
  }

  @Test
  @DisplayName("An hourly rate keeps four places and eight whole digits; finer is refused, not cut")
  void anHourlyRateIsKeptToItsColumn() {
    WorkforceService s = workforce(new Roster());
    for (String rate : List.of("12.34567", "123456789", "0.00001")) {
      ApiException refused =
          assertThrows(
              ApiException.class,
              () ->
                  s.addRate(
                      TENANT, PERSON, LocalDate.now(), new BigDecimal(rate), "GBP", null, MANAGER));
      assertEquals(400, refused.status(), rate);
      assertEquals("WORKFORCE_RATE_INVALID", refused.code(), rate);
    }
  }

  @Test
  @DisplayName(
      "A rate whose whole digits wrap an int round is refused, not written: 1E+2147483647 passed"
          + " the column check and broke the insert as a 500")
  void aRateBeyondAnyColumnIsRefusedBeforeItIsWritten() {
    WorkforceService s = workforce(new Roster());
    for (BigDecimal rate :
        List.of(
            new BigDecimal("1E+2147483647"),
            new BigDecimal("0E+2147483647"),
            new BigDecimal(java.math.BigInteger.ONE, Integer.MIN_VALUE),
            new BigDecimal(java.math.BigInteger.TEN.pow(400), 400))) {
      ApiException refused =
          assertThrows(
              ApiException.class,
              () -> s.addRate(TENANT, PERSON, LocalDate.now(), rate, "GBP", null, MANAGER),
              rate::toString);
      assertEquals(400, refused.status(), rate::toString);
      assertEquals("WORKFORCE_RATE_INVALID", refused.code(), rate::toString);
    }
  }

  @Test
  @DisplayName(
      "An hourly rate sent as text is read written out or refused by name, before any figure is"
          + " built from it")
  void anHourlyRateIsReadWrittenOut() {
    for (String text : List.of("1E+999999999", "1E+2147483647", "12.5e1", "NaN", "twelve", "")) {
      ApiException refused =
          assertThrows(ApiException.class, () -> WorkforceService.readRate(text), text);
      assertEquals(400, refused.status(), text);
      assertEquals("WORKFORCE_RATE_INVALID", refused.code(), text);
    }
    assertEquals(new BigDecimal("12.50"), WorkforceService.readRate(" 12.50 "));
  }

  // ── store tasks ────────────────────────────────────────────────────────────

  private static Instance task(UUID id, UUID storeId) {
    Instant now = Instant.now();
    return new Instance(
        id,
        TENANT,
        storeId,
        Ids.newId(),
        LocalDate.now(),
        now,
        StoreTasks.OPEN,
        "Lock up",
        StoreTasks.CLOSING,
        null,
        true,
        null,
        null,
        null,
        null,
        now,
        List.of(new InstanceItem(Ids.newId(), id, 1, "Safe", false, null, null)));
  }

  private static final class Lists extends StoreTaskRepository {
    final Map<UUID, Instance> tasks = new HashMap<>();
    final List<String> written = new ArrayList<>();

    @Override
    public Optional<Instance> instance(UUID tenantId, UUID id) {
      return TENANT.equals(tenantId) ? Optional.ofNullable(tasks.get(id)) : Optional.empty();
    }

    @Override
    public boolean tick(UUID tenantId, UUID instanceId, int position, UUID userId, Instant at) {
      written.add("tick");
      return true;
    }

    @Override
    public boolean settle(
        UUID tenantId,
        UUID instanceId,
        String status,
        UUID userId,
        Instant at,
        String skippedReason,
        String note) {
      written.add(status);
      return true;
    }
  }

  private static StoreTaskService tasks(Lists repo) {
    StoreTaskService s = new StoreTaskService();
    s.repo = repo;
    s.workforce = new Roster();
    return s;
  }

  @Test
  @DisplayName(
      "A list is worked where one is assigned; management held to no store works any store's")
  void aListIsWorkedWhereOneIsAssignedOrAnywhereForTheBusiness() {
    Lists repo = new Lists();
    UUID atB = Ids.newId();
    repo.tasks.put(atB, task(atB, B));
    StoreTaskService s = tasks(repo);
    UUID owner = Ids.newId();

    for (ApiException refused :
        List.of(
            assertThrows(ApiException.class, () -> s.tick(TENANT, atB, 1, owner, false)),
            assertThrows(ApiException.class, () -> s.complete(TENANT, atB, null, owner, false)),
            assertThrows(ApiException.class, () -> s.skip(TENANT, atB, "flood", owner, false)))) {
      assertEquals(409, refused.status());
      assertEquals("WORKFORCE_NOT_ASSIGNED", refused.code());
    }
    assertTrue(repo.written.isEmpty());

    s.tick(TENANT, atB, 1, owner, true);
    s.complete(TENANT, atB, null, owner, true);
    assertEquals(List.of("tick", StoreTasks.DONE), repo.written);

    // The four-argument forms are the assigned-only ones, as before.
    UUID other = Ids.newId();
    repo.tasks.put(other, task(other, A));
    assertEquals(
        "WORKFORCE_NOT_ASSIGNED",
        assertThrows(ApiException.class, () -> s.skip(TENANT, other, "flood", owner)).code());
    s.skip(TENANT, other, "flood", PERSON);
    assertEquals(StoreTasks.SKIPPED, repo.written.get(2));
  }

  // ── notices ────────────────────────────────────────────────────────────────

  private static Broadcast notice(UUID storeId, String role) {
    return new Broadcast(
        Ids.newId(),
        TENANT,
        "Recall",
        "Batch 42 off the shelf.",
        Broadcasts.URGENT,
        storeId,
        role,
        true,
        Instant.now().minusSeconds(60),
        null,
        Broadcasts.PUBLISHED,
        MANAGER,
        null,
        null,
        null);
  }

  /** The business's notices; PERSON is a cashier at A and nowhere else; the acks written. */
  private static final class Notices extends BroadcastRepository {
    final Map<UUID, Broadcast> byId = new HashMap<>();
    final List<String> acked = new ArrayList<>();

    UUID add(Broadcast b) {
      byId.put(b.id(), b);
      return b.id();
    }

    @Override
    public Optional<Broadcast> broadcast(UUID tenantId, UUID id) {
      return TENANT.equals(tenantId) ? Optional.ofNullable(byId.get(id)) : Optional.empty();
    }

    @Override
    public List<String> rolesAt(UUID tenantId, UUID userId, UUID storeId) {
      return TENANT.equals(tenantId) && PERSON.equals(userId) && A.equals(storeId)
          ? List.of("CASHIER")
          : List.of();
    }

    @Override
    public List<Broadcast> publishedFor(UUID tenantId, UUID storeId) {
      return byId.values().stream()
          .filter(b -> b.storeId() == null || b.storeId().equals(storeId))
          .toList();
    }

    @Override
    public Map<UUID, Instant> ackTimesBy(UUID tenantId, UUID userId) {
      return Map.of();
    }

    @Override
    public Ack acknowledge(Ack a) {
      acked.add(a.broadcastId() + " by " + a.userId() + " at " + a.storeId());
      return a;
    }
  }

  private static BroadcastService broadcasts(Notices repo) {
    BroadcastService s = new BroadcastService();
    s.repo = repo;
    return s;
  }

  @Test
  @DisplayName(
      "A notice is read and acknowledged where one is assigned; management held to no store does"
          + " so at any store, addressed by its tier")
  void aNoticeIsAcknowledgedWhereOneIsAssignedOrAnywhereForTheBusiness() {
    Notices repo = new Notices();
    UUID toAllAtB = repo.add(notice(B, null));
    UUID toEveryStore = repo.add(notice(null, null));
    UUID toCashiersAtB = repo.add(notice(B, "CASHIER"));
    UUID toManagersAtB = repo.add(notice(B, "MANAGER"));
    BroadcastService s = broadcasts(repo);
    UUID owner = Ids.newId();

    // Held to where one is assigned: not assigned at B, refused whatever the notice, nothing kept.
    for (UUID id : List.of(toAllAtB, toEveryStore)) {
      ApiException refused =
          assertThrows(ApiException.class, () -> s.acknowledge(TENANT, id, B, owner, List.of()));
      assertEquals(409, refused.status());
      assertEquals("WORKFORCE_NOT_ASSIGNED", refused.code());
    }
    assertEquals(
        "WORKFORCE_NOT_ASSIGNED",
        assertThrows(ApiException.class, () -> s.current(TENANT, B, PERSON, List.of())).code());
    assertTrue(repo.acked.isEmpty());

    // An owner held to no store, at B where nobody assigned them: the notices to everybody there.
    s.acknowledge(TENANT, toAllAtB, B, owner, List.of("OWNER"));
    s.acknowledge(TENANT, toEveryStore, B, owner, List.of("OWNER"));
    assertEquals(
        List.of(toAllAtB + " by " + owner + " at " + B, toEveryStore + " by " + owner + " at " + B),
        repo.acked);
    // ...but not one to the cashiers, nor one to the managers: addressed by tier, not above it.
    for (UUID id : List.of(toCashiersAtB, toManagersAtB)) {
      assertEquals(
          "BROADCAST_NOT_ADDRESSED",
          assertThrows(
                  ApiException.class, () -> s.acknowledge(TENANT, id, B, owner, List.of("OWNER")))
              .code());
    }
    assertEquals(
        java.util.Set.of(toAllAtB, toEveryStore),
        s.current(TENANT, B, owner, List.of("OWNER")).stream()
            .map(seen -> seen.broadcast().id())
            .collect(java.util.stream.Collectors.toSet()));

    // A business-wide manager is addressed by the managers' notice too.
    UUID head = Ids.newId();
    s.acknowledge(TENANT, toManagersAtB, B, head, List.of("MANAGER"));
    assertEquals(
        java.util.Set.of(toAllAtB, toEveryStore, toManagersAtB),
        s.current(TENANT, B, head, List.of("MANAGER")).stream()
            .map(seen -> seen.broadcast().id())
            .collect(java.util.stream.Collectors.toSet()));

    // The four-argument forms are the assigned-only ones, as before: the cashier at A reads the
    // every-store notice there, and is not on B's staff.
    assertEquals(1, s.current(TENANT, A, PERSON).size());
    assertEquals(
        "WORKFORCE_NOT_ASSIGNED",
        assertThrows(ApiException.class, () -> s.acknowledge(TENANT, toAllAtB, B, PERSON)).code());
  }
}
