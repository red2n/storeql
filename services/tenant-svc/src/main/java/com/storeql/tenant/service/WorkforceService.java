package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantProfiles;
import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.domain.Workforce.AttendanceDay;
import com.storeql.tenant.domain.Workforce.Concern;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.domain.Workforce.PayRate;
import com.storeql.tenant.domain.Workforce.Rest;
import com.storeql.tenant.domain.Workforce.Shift;
import com.storeql.tenant.domain.WorkingTime;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.tenant.repo.WorkforceRepository;
import com.storeql.tenant.repo.WorkingTimeRuleRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The roster and the clock (store operations & workforce).
 *
 * <p>Four judgements shape this service.
 *
 * <p><b>A person is rostered where they work.</b> tenant-svc owns the staff assignments, so a shift
 * for somebody who does not work at that store is refused here rather than discovered on the
 * morning. The same for clocking in: a clock-in at a store you are not assigned to is a mistake,
 * and a shop with two branches makes it weekly.
 *
 * <p><b>Clocking is the person's own act.</b> A member of staff clocks themselves in and out; a
 * manager writing hours for somebody else is a different act with a different name, recorded as
 * {@code MANAGER} and always with a reason. Anything else makes an audit of hours worthless.
 *
 * <p><b>Going home is never blocked.</b> Clocking out closes a break somebody forgot to end, at the
 * same instant, and says so. The alternative is a person standing at a terminal at the end of a
 * shift arguing with a validation message.
 *
 * <p><b>The hours are the domain's.</b> Nothing here recomputes them in SQL, so payroll and the
 * attendance report cannot come to disagree about a day.
 */
@ApplicationScoped
public class WorkforceService {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(WorkforceService.class.getName());

  @Inject WorkforceRepository repo;
  @Inject WorkingTimeRuleRepository rules;
  @Inject TenantRepository tenants;
  @Inject TenantProfiles profiles;

  /** A roster, with what is worth saying about it. */
  public record Roster(List<Shift> shifts, Map<UUID, List<Concern>> concerns) {

    public Roster {
      shifts = shifts == null ? List.of() : List.copyOf(shifts);
      concerns = concerns == null ? Map.of() : Map.copyOf(concerns);
    }
  }

  // ── the roster ──────────────────────────────────────────────────────────────

  /**
   * Rosters a shift.
   *
   * @throws ApiException 400 on a window that is not one; 409 {@code WORKFORCE_NOT_ASSIGNED} when
   *     the person does not work at that store
   */
  public Shift planShift(
      UUID tenantId,
      UUID storeId,
      UUID userId,
      Instant startsAt,
      Instant endsAt,
      String duty,
      String note,
      UUID actorId) {
    if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
      throw ApiException.badRequest("WORKFORCE_WINDOW_INVALID", "a shift ends after it starts");
    }
    if (Duration.between(startsAt, endsAt).compareTo(Duration.ofHours(24)) > 0) {
      throw ApiException.badRequest(
          "WORKFORCE_WINDOW_INVALID", "a shift longer than 24 hours is a typo, not a shift");
    }
    requireWorksAt(tenantId, userId, storeId);
    Instant now = Instant.now();
    return repo.planShift(
        new Shift(
            Ids.newId(),
            tenantId,
            storeId,
            userId,
            startsAt,
            endsAt,
            blankToNull(duty),
            Workforce.PLANNED,
            blankToNull(note),
            null,
            now,
            actorId,
            now));
  }

  /**
   * The roster of a window, with the concerns each person's own shifts raise.
   *
   * <p>Concerns are per person and not per store, because the rest between two shifts is a fact
   * about a person: two people's shifts back to back is a shop staying open, not somebody going
   * without sleep.
   *
   * <p>What the law says is found through the store the shift is at (its country, else the
   * business's) and the day is the store's own, in its zone. A store whose country has no rule gets
   * only the concern that holds anywhere: overlapping shifts.
   */
  public Roster roster(UUID tenantId, UUID storeId, UUID userId, Instant from, Instant to) {
    List<Shift> shifts = repo.shifts(tenantId, storeId, userId, from, to);
    Map<UUID, List<Shift>> byPerson = new LinkedHashMap<>();
    for (Shift s : shifts) byPerson.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(s);
    Map<UUID, Judging> stores = new HashMap<>();
    Map<String, List<WorkingTime.Rule>> byCountry = new HashMap<>();
    Map<UUID, List<Concern>> concerns = new LinkedHashMap<>();
    for (Map.Entry<UUID, List<Shift>> e : byPerson.entrySet()) {
      List<Shift> mine = e.getValue();
      // Shifts at stores that are judged alike are judged together, so the rest between two of them
      // counts; stores in another country or zone are judged by their own.
      Map<String, List<Shift>> groups = new LinkedHashMap<>();
      Map<String, Judging> basis = new LinkedHashMap<>();
      for (Shift s : mine) {
        Judging j = stores.computeIfAbsent(s.storeId(), id -> judging(tenantId, id, byCountry));
        groups.computeIfAbsent(j.key(), k -> new ArrayList<>()).add(s);
        basis.putIfAbsent(j.key(), j);
      }
      List<Concern> found =
          new ArrayList<>(WorkingTime.overlaps(mine, basis.values().iterator().next().zone()));
      for (Map.Entry<String, List<Shift>> g : groups.entrySet()) {
        Judging j = basis.get(g.getKey());
        found.addAll(WorkingTime.judge(g.getValue(), j.rules(), j.zone()));
      }
      if (!found.isEmpty()) concerns.put(e.getKey(), found);
    }
    return new Roster(shifts, concerns);
  }

  /**
   * What judges a store's roster: the rules that reach its country and the zone its days are in.
   */
  private record Judging(String key, List<WorkingTime.Rule> rules, ZoneId zone) {}

  private Judging judging(
      UUID tenantId, UUID storeId, Map<String, List<WorkingTime.Rule>> byCountry) {
    var basis = repo.storeBasis(tenantId, storeId);
    String country =
        basis
            .map(WorkforceRepository.StoreBasis::country)
            .map(c -> c.strip().toUpperCase(Locale.ROOT))
            .orElse("");
    ZoneId zone = zoneOf(basis.map(WorkforceRepository.StoreBasis::timezone).orElse(null));
    List<WorkingTime.Rule> found =
        country.isEmpty() ? List.of() : byCountry.computeIfAbsent(country, rules::forCountry);
    return new Judging(country + "|" + zone.getId(), found, zone);
  }

  /** The store's zone; UTC only when it cannot be read, which the stores' own checks prevent. */
  private static ZoneId zoneOf(String timezone) {
    try {
      return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone.strip());
    } catch (java.time.DateTimeException e) {
      LOG.log(java.util.logging.Level.WARNING, "unreadable store zone {0}", timezone);
      return ZoneOffset.UTC;
    }
  }

  /** Publishes a shift: what staff may see and rely on. */
  public Shift publishShift(UUID tenantId, UUID id, UUID actorId) {
    if (!repo.moveShift(tenantId, id, Workforce.PLANNED, Workforce.PUBLISHED, null)) {
      requireShift(tenantId, id);
      throw ApiException.conflict(
          "WORKFORCE_SHIFT_NOT_PLANNED", "only a planned shift is published");
    }
    return requireShift(tenantId, id);
  }

  /**
   * Calls a shift off, with a reason.
   *
   * <p>The reason is required and stays: a rota that changed and nobody can say why is what a
   * week's dispute is made of.
   */
  public Shift cancelShift(UUID tenantId, UUID id, String reason, UUID actorId) {
    String why = require(reason, "WORKFORCE_REASON_REQUIRED", "say why the shift was called off");
    Shift shift = requireShift(tenantId, id);
    if (Workforce.CANCELLED.equals(shift.status())) {
      throw ApiException.conflict(
          "WORKFORCE_SHIFT_CANCELLED", "that shift has already been called off");
    }
    if (!repo.moveShift(tenantId, id, shift.status(), Workforce.CANCELLED, why)) {
      throw ApiException.conflict("WORKFORCE_SHIFT_CHANGED", "that shift changed as it was read");
    }
    return requireShift(tenantId, id);
  }

  // ── the clock ───────────────────────────────────────────────────────────────

  /**
   * Clocks somebody in.
   *
   * @param userId whose hours these are; the resource makes a member of staff their own
   * @param source {@link Workforce#SOURCE_CLOCK} when the person did it, {@code MANAGER} otherwise
   * @throws ApiException 409 {@code WORKFORCE_ALREADY_CLOCKED_IN} (the index decides, so two taps
   *     on a slow terminal are one entry), {@code WORKFORCE_NOT_ASSIGNED}; 403 {@code
   *     WORKFORCE_SELF_ADJUST_REFUSED} when a manager writes their own hours
   */
  public Entry clockIn(
      UUID tenantId, UUID userId, UUID storeId, UUID shiftId, String source, UUID actorId) {
    // Nobody writes their own hours by hand: the clock is the person's, a manager's entry is for
    // somebody else (workforce-rules slice 6 (a)). A fixed control, not a setting.
    if (Workforce.SOURCE_MANAGER.equals(source) && userId.equals(actorId)) {
      throw selfAdjust();
    }
    requireWorksAt(tenantId, userId, storeId);
    if (shiftId != null) {
      Shift shift = requireShift(tenantId, shiftId);
      if (!shift.userId().equals(userId)) {
        throw ApiException.badRequest(
            "WORKFORCE_SHIFT_NOT_THEIRS", "that shift is rostered for somebody else");
      }
      if (!shift.live()) {
        throw ApiException.conflict("WORKFORCE_SHIFT_CANCELLED", "that shift was called off");
      }
    }
    Instant now = Instant.now();
    return repo.clockIn(
        new Entry(
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
            List.of()));
  }

  /** The entry somebody is on the clock for, or empty. */
  public java.util.Optional<Entry> onTheClock(UUID tenantId, UUID userId) {
    return repo.openEntry(tenantId, userId);
  }

  /**
   * Clocks somebody out, closing a break they forgot to end.
   *
   * @throws ApiException 409 {@code WORKFORCE_NOT_CLOCKED_IN} when there is nothing open
   */
  public Entry clockOut(UUID tenantId, UUID userId) {
    Entry open =
        repo.openEntry(tenantId, userId)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "WORKFORCE_NOT_CLOCKED_IN", "that person is not on the clock"));
    Instant at = Instant.now();
    // The cost is worked out from the entry as it will stand — the breaks it already has, closed
    // with
    // it — so the announcement and the hours are written in one transaction.
    Entry closed = closedCopy(open, at);
    if (!repo.clockOut(tenantId, open.id(), at, labour(closed, null))) {
      throw ApiException.conflict("WORKFORCE_NOT_CLOCKED_IN", "that entry closed as it was read");
    }
    return repo.entry(tenantId, open.id()).orElse(open);
  }

  /** Starts a break on the open entry. */
  public Entry startBreak(UUID tenantId, UUID userId, String kind, boolean paid) {
    Entry open =
        repo.openEntry(tenantId, userId)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "WORKFORCE_NOT_CLOCKED_IN",
                        "a break belongs to a shift somebody is working"));
    String k = kind == null ? Workforce.BREAK_REST : kind.strip().toUpperCase(Locale.ROOT);
    if (!Workforce.BREAK_KINDS.contains(k)) {
      throw ApiException.badRequest("WORKFORCE_BREAK_KIND_UNKNOWN", "a break is REST or MEAL");
    }
    repo.startBreak(new Rest(Ids.newId(), tenantId, open.id(), Instant.now(), null, k, paid));
    return repo.entry(tenantId, open.id()).orElse(open);
  }

  /** Ends the break on the open entry. */
  public Entry endBreak(UUID tenantId, UUID userId) {
    Entry open =
        repo.openEntry(tenantId, userId)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "WORKFORCE_NOT_CLOCKED_IN", "that person is not on the clock"));
    if (!repo.endBreak(tenantId, open.id(), Instant.now())) {
      throw ApiException.conflict("WORKFORCE_NO_BREAK", "no break is running on that shift");
    }
    return repo.entry(tenantId, open.id()).orElse(open);
  }

  /**
   * Corrects an entry: a new one supersedes it, with the reason, and both stay.
   *
   * <p>The most common use is the forgotten clock-out, where the alternative — a manager editing
   * the time — leaves a record nobody can be held to.
   *
   * @throws ApiException 400 without a reason or on a window that is not one; 409 when the entry
   *     has already been corrected; 403 {@code WORKFORCE_SELF_ADJUST_REFUSED} for one's own hours
   */
  public Entry adjust(
      UUID tenantId,
      UUID entryId,
      Instant clockedInAt,
      Instant clockedOutAt,
      String reason,
      UUID actorId) {
    String why =
        require(reason, "WORKFORCE_REASON_REQUIRED", "say why the hours are being changed");
    Entry original =
        repo.entry(tenantId, entryId)
            .orElseThrow(
                () -> ApiException.notFound("WORKFORCE_ENTRY_NOT_FOUND", "no such time entry"));
    if (original.userId().equals(actorId)) {
      throw selfAdjust();
    }
    if (!original.stands()) {
      throw ApiException.conflict(
          "WORKFORCE_ENTRY_NOT_STANDING", "that entry has already been corrected");
    }
    Instant in = clockedInAt == null ? original.clockedInAt() : clockedInAt;
    Instant out = clockedOutAt == null ? original.clockedOutAt() : clockedOutAt;
    if (out != null && !out.isAfter(in)) {
      throw ApiException.badRequest("WORKFORCE_WINDOW_INVALID", "hours end after they start");
    }
    if (out != null && Duration.between(in, out).compareTo(Duration.ofHours(24)) > 0) {
      throw ApiException.badRequest(
          "WORKFORCE_WINDOW_INVALID", "an entry longer than 24 hours is a typo");
    }
    Instant now = Instant.now();
    Entry correction =
        new Entry(
            Ids.newId(),
            tenantId,
            original.storeId(),
            original.userId(),
            original.shiftId(),
            in,
            out,
            Workforce.SOURCE_MANAGER,
            original.note(),
            why,
            original.id(),
            null,
            now,
            actorId,
            original.breaks());
    requireSecondPersonWhenRaised(original, correction);
    repo.adjust(correction, original.breaks(), labour(correction, original.id()));
    return repo.entry(tenantId, correction.id()).orElse(correction);
  }

  /**
   * The store an entry belongs to, so the caller can be judged against it before anything is
   * written: another business's entry is a 404, and a manager held to stores acts only at theirs.
   *
   * @throws ApiException 404 {@code WORKFORCE_ENTRY_NOT_FOUND}
   */
  public UUID storeOfEntry(UUID tenantId, UUID entryId) {
    return repo.entry(tenantId, entryId)
        .map(Entry::storeId)
        .orElseThrow(
            () -> ApiException.notFound("WORKFORCE_ENTRY_NOT_FOUND", "no such time entry"));
  }

  /**
   * The store a manual clock-in is judged at: it must be the business's (404), and the caller then
   * holds it or not (the resource asks).
   *
   * @throws ApiException 404 {@code STORE_NOT_FOUND}
   */
  public UUID requireStore(UUID tenantId, UUID storeId) {
    return tenants
        .findStore(tenantId, storeId)
        .map(s -> storeId)
        .orElseThrow(
            () -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
  }

  private static ApiException selfAdjust() {
    return ApiException.forbidden(
        "WORKFORCE_SELF_ADJUST_REFUSED",
        "nobody corrects or clocks in their own hours; ask somebody else to do it");
  }

  /**
   * Where the approval of a correction that raises paid hours plugs in (workforce-rules slice 6
   * (c)). A correction that would raise the person's paid minutes is to become a pending
   * correction, the approvals action {@code staff.time-correction-up}, applied and announced only
   * when a second person with {@code staff.manage} approves — so that labour cost never moves on an
   * unapproved figure. That needs the approvals mechanism (stage 0.5) and the business's switch,
   * which is off until set; until they exist a raise applies at once, as it always has, and this
   * says so where it is decided. The switch reads here, and returns the pending answer instead of
   * falling through to {@code repo.adjust}.
   */
  private void requireSecondPersonWhenRaised(Entry original, Entry correction) {
    if (Workforce.raisesPaidMinutes(original, correction)) {
      LOG.log(
          java.util.logging.Level.FINE,
          "correction {0} raises paid minutes; approval is not switched on for this business",
          correction.id());
    }
  }

  public List<Entry> entries(UUID tenantId, UUID storeId, UUID userId, Instant from, Instant to) {
    return repo.entries(tenantId, storeId, userId, from, to);
  }

  /**
   * What an entry cost, as an event — or null when it cannot be costed.
   *
   * <p>Null in two cases, and both are honest: the entry is still open, so there are no hours yet;
   * or no rate was in force on the day it was worked, which a reader must report as unknown rather
   * than as zero, since zero is a real rate somebody may be on. The event carries the store and the
   * money and <b>not the person</b>: a labour figure is a fact about a shop's Saturday, and who
   * earned what stays here.
   */
  private OutboxRow labour(Entry entry, UUID supersedes) {
    java.time.Duration worked = entry.worked();
    if (worked == null) return null;
    List<PayRate> rates = repo.rates(entry.tenantId(), entry.userId());
    java.math.BigDecimal cost = Workforce.cost(entry, rates);
    String currency =
        rates.stream()
            .filter(r -> !r.effectiveFrom().isAfter(entry.day()))
            .map(PayRate::currency)
            .findFirst()
            .orElse(null);
    return new OutboxRow(
        "LabourRecorded",
        "storeql.tenant.labour-recorded",
        entry.tenantId(),
        entry.id(),
        Events.labourRecorded(
            entry.tenantId(),
            entry.storeId(),
            entry.id(),
            supersedes,
            entry.day(),
            worked.toMinutes(),
            cost,
            currency));
  }

  /** The entry as it will stand once closed, for costing before the write. */
  private static Entry closedCopy(Entry open, Instant at) {
    List<Rest> breaks = new ArrayList<>();
    for (Rest r : open.breaks()) {
      breaks.add(
          r.endedAt() == null
              ? new Rest(
                  r.id(), r.tenantId(), r.timeEntryId(), r.startedAt(), at, r.kind(), r.paid())
              : r);
    }
    return new Entry(
        open.id(),
        open.tenantId(),
        open.storeId(),
        open.userId(),
        open.shiftId(),
        open.clockedInAt(),
        at,
        open.source(),
        open.note(),
        open.adjustedReason(),
        open.supersedes(),
        open.supersededBy(),
        open.createdAt(),
        open.createdBy(),
        breaks);
  }

  // ── what an hour costs ──────────────────────────────────────────────────────

  /**
   * Records what an hour of somebody's time costs, from a date.
   *
   * <p>Dated because a rise must not re-cost the past: a labour figure that moved when somebody got
   * a pay rise would make last quarter's report disagree with itself.
   *
   * @throws ApiException 400 on a negative rate or an unknown currency; 409 when a rate already
   *     starts that day
   */
  public PayRate addRate(
      UUID tenantId,
      UUID userId,
      java.time.LocalDate from,
      java.math.BigDecimal hourlyRate,
      String currency,
      String note,
      UUID actorId) {
    if (hourlyRate == null || hourlyRate.signum() < 0) {
      throw ApiException.badRequest(
          "WORKFORCE_RATE_INVALID", "an hour costs nothing or something, never less than nothing");
    }
    return repo.addRate(
        new PayRate(
            Ids.newId(),
            tenantId,
            userId,
            from == null ? java.time.LocalDate.now(ZoneOffset.UTC) : from,
            hourlyRate,
            profiles.currencyOr(tenantId, currency),
            blankToNull(note),
            Instant.now(),
            actorId));
  }

  public List<PayRate> rates(UUID tenantId, UUID userId) {
    return repo.rates(tenantId, userId);
  }

  // ── attendance ──────────────────────────────────────────────────────────────

  /**
   * Planned against worked, day by day and person by person.
   *
   * <p>Built from the same objects payroll would read, so the two cannot disagree. A day appears
   * when either side has something on it: rostered and not worked is an absence, worked and not
   * rostered is as much a management fact as an absence, and a person still on the clock is
   * neither.
   */
  public List<AttendanceDay> attendance(
      UUID tenantId, UUID storeId, UUID userId, LocalDate from, LocalDate to) {
    Instant start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant end = to.atStartOfDay().toInstant(ZoneOffset.UTC);
    // [planned minutes, worked minutes, entries] per (day, person, store).
    Map<String, long[]> minutes = new LinkedHashMap<>();
    Map<String, boolean[]> open = new HashMap<>();
    Map<String, Instant[]> firsts = new HashMap<>();

    for (Shift s : repo.shifts(tenantId, storeId, userId, start, end)) {
      if (!s.live()) continue;
      String key = key(s.day(), s.userId(), s.storeId());
      minutes.computeIfAbsent(key, k -> new long[3])[0] += s.length().toMinutes();
      Instant[] first = firsts.computeIfAbsent(key, k -> new Instant[2]);
      if (first[0] == null || s.startsAt().isBefore(first[0])) first[0] = s.startsAt();
    }
    for (Entry e : repo.entries(tenantId, storeId, userId, start, end)) {
      String key = key(e.day(), e.userId(), e.storeId());
      long[] both = minutes.computeIfAbsent(key, k -> new long[3]);
      Duration worked = e.worked();
      if (worked != null) both[1] += worked.toMinutes();
      both[2]++;
      if (e.open()) open.computeIfAbsent(key, k -> new boolean[1])[0] = true;
      Instant[] first = firsts.computeIfAbsent(key, k -> new Instant[2]);
      if (first[1] == null || e.clockedInAt().isBefore(first[1])) first[1] = e.clockedInAt();
    }

    List<AttendanceDay> out = new ArrayList<>();
    for (Map.Entry<String, long[]> e : minutes.entrySet()) {
      String[] parts = e.getKey().split("@");
      Instant[] first = firsts.getOrDefault(e.getKey(), new Instant[2]);
      Long lateBy =
          first[0] == null || first[1] == null
              ? null
              : Duration.between(first[0], first[1]).toMinutes();
      out.add(
          new AttendanceDay(
              LocalDate.parse(parts[0]),
              Ids.parse(parts[1]),
              Ids.parse(parts[2]),
              e.getValue()[0],
              e.getValue()[1],
              (int) e.getValue()[2],
              open.containsKey(e.getKey()),
              lateBy));
    }
    out.sort(
        java.util.Comparator.comparing(AttendanceDay::day)
            .thenComparing(a -> a.userId().toString()));
    return out;
  }

  private static String key(LocalDate day, UUID userId, UUID storeId) {
    return day + "@" + userId + "@" + storeId;
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private void requireWorksAt(UUID tenantId, UUID userId, UUID storeId) {
    if (!repo.worksAt(tenantId, userId, storeId)) {
      throw ApiException.conflict(
          "WORKFORCE_NOT_ASSIGNED",
          "that person is not assigned to that store; assign them before rostering or clocking");
    }
  }

  private Shift requireShift(UUID tenantId, UUID id) {
    return repo.shift(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("WORKFORCE_SHIFT_NOT_FOUND", "no such shift"));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static String require(String value, String code, String message) {
    String v = blankToNull(value);
    if (v == null) throw ApiException.badRequest(code, message);
    return v;
  }
}
