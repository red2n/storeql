package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.StoreTasks;
import com.storeql.tenant.domain.StoreTasks.Day;
import com.storeql.tenant.domain.StoreTasks.Instance;
import com.storeql.tenant.domain.StoreTasks.InstanceItem;
import com.storeql.tenant.domain.StoreTasks.Template;
import com.storeql.tenant.domain.StoreTasks.TemplateItem;
import com.storeql.tenant.repo.StoreTaskRepository;
import com.storeql.tenant.repo.StoreTaskRepository.StoreClock;
import com.storeql.tenant.repo.WorkforceRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The work a shop does every day, and the record that it was done (store operations & workforce).
 *
 * <p>Two acts here belong to different people. <b>Writing the list</b> is management's — what the
 * shop does and when — under {@code /admin/}. <b>Working it</b> is the staff's own: today's list at
 * their store, a line ticked, a job finished or explained, outside that prefix, because a checklist
 * only a manager could tick is a checklist nobody keeps.
 *
 * <p>The day is <b>generated ahead of being worked</b>, by the sweeper every hour and by a manager
 * on demand, because a task nobody did has to exist in order to be missed. Generation is idempotent
 * under the unique constraint, so both can run and the day appears once.
 */
@ApplicationScoped
public class StoreTaskService {

  private static final Logger LOG = System.getLogger(StoreTaskService.class.getName());

  /** How far back a read of a store's list may reach at once. */
  static final int MAX_DAYS = 62;

  @Inject StoreTaskRepository repo;
  @Inject WorkforceRepository workforce;

  /** One line of a list, as a manager writes it. */
  public record Line(String text, boolean required) {}

  // ── writing the list ────────────────────────────────────────────────────────

  /**
   * Records a list.
   *
   * @param storeId null for every store the business has
   * @param lines the checklist's lines in order, or empty for a single task
   * @throws ApiException 400 when the list could not be worked: an unknown kind, no time, a day
   *     outside the week, a weekly list with no day, or a blank line
   */
  public Template create(
      UUID tenantId,
      UUID storeId,
      String title,
      String instructions,
      String kind,
      Set<Integer> daysOfWeek,
      LocalTime dueTime,
      Integer graceMinutes,
      String role,
      boolean required,
      List<Line> lines,
      UUID actorId) {
    requireWorkable(title, kind, daysOfWeek, dueTime, graceMinutes, lines);
    String name = require(title, "TASK_TITLE_REQUIRED", "a list needs a title");
    String upperKind = kind.strip().toUpperCase(Locale.ROOT);
    int grace = graceMinutes == null ? 60 : graceMinutes;
    if (storeId != null) requireStore(tenantId, storeId);
    UUID id = Ids.newId();
    List<TemplateItem> items = new ArrayList<>();
    int position = 1;
    for (Line line : lines == null ? List.<Line>of() : lines) {
      items.add(
          new TemplateItem(Ids.newId(), id, position++, line.text().strip(), line.required()));
    }
    Template template =
        new Template(
            id,
            tenantId,
            storeId,
            name,
            blankToNull(instructions),
            upperKind,
            daysOfWeek == null ? Set.of() : daysOfWeek,
            dueTime,
            grace,
            blankToNull(role) == null ? null : role.strip().toUpperCase(Locale.ROOT),
            required,
            StoreTasks.ACTIVE,
            Instant.now(),
            actorId,
            null,
            null,
            items);
    repo.create(template);
    return repo.template(tenantId, id).orElse(template);
  }

  /**
   * A list somebody could work, judged before anything else is — whoever writes it and for
   * whichever store — so a request that could never be a list is refused as one.
   *
   * @throws ApiException 400 {@code TASK_TITLE_REQUIRED}; {@code TASK_LIST_INVALID} for an unknown
   *     kind, no time, a day outside the week or a weekly list with no day; {@code TASK_LINE_BLANK}
   */
  public static void requireWorkable(
      String title,
      String kind,
      Set<Integer> daysOfWeek,
      LocalTime dueTime,
      Integer graceMinutes,
      List<Line> lines) {
    require(title, "TASK_TITLE_REQUIRED", "a list needs a title");
    String upperKind = kind == null ? null : kind.strip().toUpperCase(Locale.ROOT);
    int grace = graceMinutes == null ? 60 : graceMinutes;
    String problem = StoreTasks.problem(upperKind, daysOfWeek, dueTime, grace);
    if (problem != null) throw ApiException.badRequest("TASK_LIST_INVALID", problem);
    int position = 1;
    for (Line line : lines == null ? List.<Line>of() : lines) {
      if (blankToNull(line.text()) == null) {
        throw ApiException.badRequest(
            "TASK_LINE_BLANK", "line " + position + " of the list is blank");
      }
      position++;
    }
  }

  /**
   * The store a list is written for, a day generated or read at, or a job raised at, so the caller
   * can be judged against it before anything else: it must be the business's — another business's
   * manager naming our store, even among their own store ids, is told it does not exist — and the
   * caller then holds it or not (the resource asks).
   *
   * @throws ApiException 404 {@code STORE_NOT_FOUND}
   */
  public UUID requireStore(UUID tenantId, UUID storeId) {
    if (repo.storeClock(tenantId, storeId).isEmpty()) {
      throw ApiException.notFound("STORE_NOT_FOUND", "no such store");
    }
    return storeId;
  }

  /**
   * Withdraws a list: no new days are generated for it, and the days already generated stand.
   *
   * @throws ApiException 404; 409 {@code TASK_LIST_WITHDRAWN} when it already was
   */
  public Template withdraw(UUID tenantId, UUID templateId, UUID actorId) {
    Template template = requireTemplate(tenantId, templateId);
    if (!repo.withdraw(tenantId, templateId, actorId)) {
      throw ApiException.conflict("TASK_LIST_WITHDRAWN", "that list was already withdrawn");
    }
    return repo.template(tenantId, templateId).orElse(template);
  }

  /**
   * The business's lists.
   *
   * @param stores the caller's stores ({@code TenantContext.reportStores(null)}): their stores' own
   *     lists and those for every store; null for a caller held to none, who reads them all
   */
  public List<Template> templates(UUID tenantId, boolean activeOnly, Set<UUID> stores) {
    return repo.templates(tenantId, activeOnly, stores);
  }

  public Template template(UUID tenantId, UUID id) {
    return requireTemplate(tenantId, id);
  }

  // ── the day ─────────────────────────────────────────────────────────────────

  /**
   * Generates a store's day: every active list that falls due on that date, once.
   *
   * @return how many occurrences this call created
   * @throws ApiException 404 when the store is not this business's
   */
  public int generate(UUID tenantId, UUID storeId, LocalDate businessDate) {
    StoreClock clock =
        repo.storeClock(tenantId, storeId)
            .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "no such store"));
    return generate(tenantId, clock, businessDate);
  }

  private int generate(UUID tenantId, StoreClock clock, LocalDate businessDate) {
    int created = 0;
    for (Template t : repo.templates(tenantId, true)) {
      if (t.storeId() != null && !t.storeId().equals(clock.storeId())) continue;
      if (!t.fallsDueOn(businessDate)) continue;
      if (repo.generate(occurrence(t, clock, businessDate))) created++;
    }
    return created;
  }

  /**
   * Raises a task by hand for a store today: a delivery that has to be put away, a spill.
   *
   * @throws ApiException 404 when the list or the store is not this business's
   */
  public Instance raise(UUID tenantId, UUID templateId, UUID storeId, LocalDate businessDate) {
    Template t = requireTemplate(tenantId, templateId);
    if (!t.active()) throw ApiException.conflict("TASK_LIST_WITHDRAWN", "that list was withdrawn");
    StoreClock clock =
        repo.storeClock(tenantId, storeId)
            .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "no such store"));
    if (t.storeId() != null && !t.storeId().equals(storeId)) {
      throw ApiException.conflict("TASK_LIST_OTHER_STORE", "that list belongs to another store");
    }
    LocalDate day =
        businessDate == null
            ? StoreTasks.businessDate(Instant.now(), clock.timezone())
            : businessDate;
    Instance occurrence = occurrence(t, clock, day);
    if (!repo.generate(occurrence)) {
      throw ApiException.conflict(
          "TASK_ALREADY_RAISED", "that list is already on the day's work for that store");
    }
    return repo.instance(tenantId, occurrence.id()).orElse(occurrence);
  }

  private static Instance occurrence(Template t, StoreClock clock, LocalDate businessDate) {
    UUID id = Ids.newId();
    List<InstanceItem> items = new ArrayList<>();
    for (TemplateItem line : t.items()) {
      items.add(
          new InstanceItem(
              Ids.newId(), id, line.position(), line.text(), line.required(), null, null));
    }
    return new Instance(
        id,
        t.tenantId(),
        clock.storeId(),
        t.id(),
        businessDate,
        StoreTasks.dueAt(businessDate, t.dueTime(), clock.timezone()),
        StoreTasks.OPEN,
        t.title(),
        t.kind(),
        t.role(),
        t.required(),
        null,
        null,
        null,
        null,
        Instant.now(),
        items);
  }

  /**
   * A store's work over a range of days.
   *
   * @throws ApiException 400 on a range that is not one or is too long
   */
  public List<Instance> day(UUID tenantId, UUID storeId, LocalDate from, LocalDate to) {
    requireRange(from, to);
    return repo.day(tenantId, storeId, from, to);
  }

  /**
   * A range of days a store's work may be read over: forwards, and no longer than the limit.
   *
   * @throws ApiException 400 {@code TASK_RANGE_INVALID}
   */
  public static void requireRange(LocalDate from, LocalDate to) {
    if (from == null || to == null || to.isBefore(from)) {
      throw ApiException.badRequest("TASK_RANGE_INVALID", "a range ends on or after it starts");
    }
    if (from.plusDays(MAX_DAYS).isBefore(to)) {
      throw ApiException.badRequest(
          "TASK_RANGE_INVALID", "a range covers at most " + MAX_DAYS + " days");
    }
  }

  /** The store's own today, for a member of staff asking what there is to do. */
  public LocalDate today(UUID tenantId, UUID storeId) {
    StoreClock clock =
        repo.storeClock(tenantId, storeId)
            .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "no such store"));
    return StoreTasks.businessDate(Instant.now(), clock.timezone());
  }

  /** What a store's day came to, one summary per business date in the range. */
  public List<Day> summary(UUID tenantId, UUID storeId, LocalDate from, LocalDate to) {
    List<Instance> all = day(tenantId, storeId, from, to);
    List<Day> out = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      LocalDate on = d;
      out.add(
          StoreTasks.summarise(
              on, storeId, all.stream().filter(i -> i.businessDate().equals(on)).toList()));
    }
    return List.copyOf(out);
  }

  public Instance instance(UUID tenantId, UUID id) {
    return requireInstance(tenantId, id);
  }

  // ── working the list ────────────────────────────────────────────────────────

  /**
   * Ticks one line of a checklist.
   *
   * @throws ApiException 404; 409 {@code TASK_NOT_OPEN} when the task is settled, {@code
   *     TASK_LINE_TICKED} when the line already was, {@code WORKFORCE_NOT_ASSIGNED} for somebody
   *     who does not work at that store
   */
  public Instance tick(UUID tenantId, UUID instanceId, int position, UUID userId) {
    return tick(tenantId, instanceId, position, userId, false);
  }

  /**
   * {@link #tick(UUID, UUID, int, UUID)}, for a caller who may act at any of the business's stores.
   *
   * @param anyStore true for management held to no store (an owner, a business-wide manager), who
   *     works a list at any store without being assigned there; false holds the caller to the
   *     stores they are assigned at ({@code 409 WORKFORCE_NOT_ASSIGNED} elsewhere)
   */
  public Instance tick(
      UUID tenantId, UUID instanceId, int position, UUID userId, boolean anyStore) {
    Instance task = requireInstance(tenantId, instanceId);
    requireWorksAt(tenantId, userId, task.storeId(), anyStore);
    if (!task.open()) {
      throw ApiException.conflict(
          "TASK_NOT_OPEN", "that task is " + task.status().toLowerCase(Locale.ROOT));
    }
    if (task.items().stream().noneMatch(i -> i.position() == position)) {
      throw ApiException.notFound("TASK_LINE_NOT_FOUND", "no line " + position + " on that list");
    }
    if (!repo.tick(tenantId, instanceId, position, userId, Instant.now())) {
      throw ApiException.conflict("TASK_LINE_TICKED", "line " + position + " was already ticked");
    }
    return requireInstance(tenantId, instanceId);
  }

  /**
   * Finishes a task.
   *
   * <p>A checklist is finished when every required line is ticked, and refused until it is: a
   * closing list signed off with the safe still open is the case the required flag exists for.
   *
   * @throws ApiException 409 {@code TASK_LINES_OUTSTANDING}, {@code TASK_NOT_OPEN}
   */
  public Instance complete(UUID tenantId, UUID instanceId, String note, UUID userId) {
    return complete(tenantId, instanceId, note, userId, false);
  }

  /**
   * {@link #complete(UUID, UUID, String, UUID)}, for a caller who may act at any store.
   *
   * @param anyStore as {@link #tick(UUID, UUID, int, UUID, boolean)}
   */
  public Instance complete(
      UUID tenantId, UUID instanceId, String note, UUID userId, boolean anyStore) {
    Instance task = requireInstance(tenantId, instanceId);
    requireWorksAt(tenantId, userId, task.storeId(), anyStore);
    if (!task.open()) {
      throw ApiException.conflict(
          "TASK_NOT_OPEN", "that task is " + task.status().toLowerCase(Locale.ROOT));
    }
    long outstanding = task.outstanding();
    if (outstanding > 0) {
      throw ApiException.conflict(
          "TASK_LINES_OUTSTANDING",
          outstanding
              + " required line(s) of that list are not ticked; tick them or skip the list with a reason");
    }
    if (!repo.settle(
        tenantId, instanceId, StoreTasks.DONE, userId, Instant.now(), null, blankToNull(note))) {
      throw ApiException.conflict("TASK_NOT_OPEN", "that task was settled by somebody else");
    }
    return requireInstance(tenantId, instanceId);
  }

  /**
   * Explains a task away rather than doing it.
   *
   * @throws ApiException 400 without a reason — a skipped closing check with no reason is what an
   *     auditor asks about; 409 {@code TASK_NOT_OPEN}
   */
  public Instance skip(UUID tenantId, UUID instanceId, String reason, UUID userId) {
    return skip(tenantId, instanceId, reason, userId, false);
  }

  /**
   * {@link #skip(UUID, UUID, String, UUID)}, for a caller who may act at any store.
   *
   * @param anyStore as {@link #tick(UUID, UUID, int, UUID, boolean)}
   */
  public Instance skip(
      UUID tenantId, UUID instanceId, String reason, UUID userId, boolean anyStore) {
    String why = require(reason, "TASK_REASON_REQUIRED", "say why the task is being skipped");
    Instance task = requireInstance(tenantId, instanceId);
    requireWorksAt(tenantId, userId, task.storeId(), anyStore);
    if (!task.open()) {
      throw ApiException.conflict(
          "TASK_NOT_OPEN", "that task is " + task.status().toLowerCase(Locale.ROOT));
    }
    if (!repo.settle(tenantId, instanceId, StoreTasks.SKIPPED, userId, Instant.now(), why, null)) {
      throw ApiException.conflict("TASK_NOT_OPEN", "that task was settled by somebody else");
    }
    return requireInstance(tenantId, instanceId);
  }

  // ── the sweep ───────────────────────────────────────────────────────────────

  /**
   * Generates every store's today and marks what fell due and was never done.
   *
   * <p>Today is each store's own: a sweep at 23:30 in London generates tomorrow's list for a shop
   * in Sydney, which is already on tomorrow. Nothing here throws — one business with a broken store
   * must not stop the sweep for every other — and what went wrong is logged by name.
   *
   * @return occurrences generated plus occurrences marked missed
   */
  public int sweep(Instant now) {
    int touched = 0;
    for (UUID tenantId : repo.tenantsWithLists()) {
      try {
        for (StoreClock clock : repo.storeClocks(tenantId)) {
          touched += generate(tenantId, clock, StoreTasks.businessDate(now, clock.timezone()));
        }
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "task sweep for {0} failed: {1}", tenantId, e.getMessage());
      }
    }
    try {
      List<Instance> missed = repo.markMissed(now, StoreTaskService::missed);
      touched += missed.size();
      if (!missed.isEmpty()) LOG.log(Level.INFO, "{0} task(s) marked missed", missed.size());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "marking missed tasks failed: {0}", e.getMessage());
    }
    return touched;
  }

  /** The sweep, never throwing: for the scheduler thread. */
  public int sweepQuietly() {
    try {
      return sweep(Instant.now());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "task sweep failed: {0}", e.getMessage());
      return 0;
    }
  }

  private static OutboxRow missed(Instance i) {
    return new OutboxRow(
        "StoreTaskMissed",
        "storeql.tenant.store-task-missed",
        i.tenantId(),
        i.id(),
        Events.storeTaskMissed(
            i.tenantId(),
            i.storeId(),
            i.id(),
            i.title(),
            i.kind(),
            i.businessDate(),
            i.dueAt(),
            i.required()));
  }

  // ── plumbing ────────────────────────────────────────────────────────────────

  private void requireWorksAt(UUID tenantId, UUID userId, UUID storeId, boolean anyStore) {
    if (anyStore) return;
    if (!workforce.worksAt(tenantId, userId, storeId)) {
      throw ApiException.conflict(
          "WORKFORCE_NOT_ASSIGNED", "that person is not assigned to that store");
    }
  }

  private Template requireTemplate(UUID tenantId, UUID id) {
    return repo.template(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TASK_LIST_NOT_FOUND", "no such list"));
  }

  private Instance requireInstance(UUID tenantId, UUID id) {
    return repo.instance(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "no such task"));
  }

  private static String require(String value, String code, String message) {
    String v = blankToNull(value);
    if (v == null) throw ApiException.badRequest(code, message);
    return v;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  /** For the resource: a store's clock, or empty when the store is not this business's. */
  public Optional<StoreClock> clock(UUID tenantId, UUID storeId) {
    return repo.storeClock(tenantId, storeId);
  }
}
