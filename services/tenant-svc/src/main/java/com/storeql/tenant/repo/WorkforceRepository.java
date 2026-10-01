package com.storeql.tenant.repo;

import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.domain.Workforce.PayRate;
import com.storeql.tenant.domain.Workforce.Rest;
import com.storeql.tenant.domain.Workforce.Shift;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The roster and the clock (store operations & workforce).
 *
 * <p>Two things here are the database's to enforce rather than the service's, because two requests
 * at once is exactly when they matter: <b>one open entry per person</b> and <b>one open break per
 * entry</b>. Clocking in twice is how somebody gets paid twice for one afternoon, and a second open
 * break makes the arithmetic of a day undecidable.
 *
 * <p>The hours themselves are <em>not</em> computed here. They are the domain's, computed from the
 * entry and its breaks, so payroll and the attendance report cannot disagree about a day — which
 * they would the moment the same rule existed in SQL as well.
 */
@ApplicationScoped
public class WorkforceRepository extends BaseOutboxRepository {

  private static final String SHIFT_COLUMNS =
      "id, tenant_id, store_id, user_id, starts_at, ends_at, duty, status, note,"
          + " cancelled_reason, created_at, created_by, updated_at";

  private static final String ENTRY_COLUMNS =
      "id, tenant_id, store_id, user_id, shift_id, clocked_in_at, clocked_out_at, source, note,"
          + " adjusted_reason, supersedes, superseded_by, created_at, created_by";

  private static final String BREAK_COLUMNS =
      "id, tenant_id, time_entry_id, started_at, ended_at, kind, paid";

  /**
   * The parameters a window read binds, in the order both queries below ask for them.
   *
   * <p>One binder, because the two reads must mean the same window: a roster and the hours worked
   * against it that disagreed about which days they covered would make every comparison wrong.
   */
  private static Binder window(UUID tenantId, UUID storeId, UUID userId, Instant from, Instant to) {
    return ps -> {
      int i = 1;
      ps.setObject(i++, tenantId);
      ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
      ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
      if (storeId != null) ps.setObject(i++, storeId);
      if (userId != null) ps.setObject(i, userId);
    };
  }

  // ── the roster ──────────────────────────────────────────────────────────────

  public Shift planShift(Shift s) {
    exec(
        "INSERT INTO work_shifts (id, tenant_id, store_id, user_id, starts_at, ends_at, duty,"
            + " status, note, created_at, created_by, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, s.id());
          ps.setObject(2, s.tenantId());
          ps.setObject(3, s.storeId());
          ps.setObject(4, s.userId());
          ps.setObject(5, s.startsAt().atOffset(ZoneOffset.UTC));
          ps.setObject(6, s.endsAt().atOffset(ZoneOffset.UTC));
          ps.setString(7, s.duty());
          ps.setString(8, s.status());
          ps.setString(9, s.note());
          ps.setObject(10, s.createdAt().atOffset(ZoneOffset.UTC));
          ps.setObject(11, s.createdBy());
          ps.setObject(12, s.updatedAt().atOffset(ZoneOffset.UTC));
        },
        "plan a shift");
    return s;
  }

  public Optional<Shift> shift(UUID tenantId, UUID id) {
    return query(
            "SELECT " + SHIFT_COLUMNS + " FROM work_shifts WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            WorkforceRepository::readShift,
            "a shift")
        .stream()
        .findFirst();
  }

  /**
   * The shifts of a window, in order of start.
   *
   * <p>Ordered by person and then start, because the concerns a rota raises are about one person's
   * shifts in sequence: the gap between two of somebody's own shifts, not between two people's.
   */
  public List<Shift> shifts(UUID tenantId, UUID storeId, UUID userId, Instant from, Instant to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + SHIFT_COLUMNS
                + " FROM work_shifts WHERE tenant_id = ? AND starts_at < ?"
                + " AND ends_at > ?");
    if (storeId != null) sql.append(" AND store_id = ?");
    if (userId != null) sql.append(" AND user_id = ?");
    sql.append(" ORDER BY user_id, starts_at");
    return query(
        sql.toString(),
        window(tenantId, storeId, userId, from, to),
        WorkforceRepository::readShift,
        "shifts of a window");
  }

  /** Moves a shift's status, and returns whether it was in the status it had to be in. */
  public boolean moveShift(UUID tenantId, UUID id, String from, String to, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE work_shifts SET status = ?, cancelled_reason = ?, updated_at = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, to);
            ps.setString(2, Workforce.CANCELLED.equals(to) ? reason : null);
            ps.setObject(3, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.setString(6, from);
            return ps.executeUpdate() == 1;
          }
        },
        "move a shift");
  }

  /** Whether the person works at the store at all: tenant-svc's own staff assignments. */
  public boolean worksAt(UUID tenantId, UUID userId, UUID storeId) {
    return !query(
            "SELECT 1 AS present FROM staff_assignments"
                + " WHERE tenant_id = ? AND user_id = ? AND store_id = ? LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
              ps.setObject(3, storeId);
            },
            rs -> rs.getInt("present"),
            "a staff assignment")
        .isEmpty();
  }

  /** A store's country and zone as roster rules read them. */
  public record StoreBasis(String country, String timezone) {}

  /**
   * Where a store is, as far as working-time law is concerned: its own country, else the
   * business's; and its own zone. Empty when the store is not this tenant's.
   */
  public java.util.Optional<StoreBasis> storeBasis(UUID tenantId, UUID storeId) {
    return query(
            "SELECT COALESCE(NULLIF(btrim(s.country), ''), t.country) AS country, s.timezone"
                + " FROM stores s JOIN tenants t ON t.id = s.tenant_id"
                + " WHERE s.tenant_id = ? AND s.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs -> new StoreBasis(rs.getString("country"), rs.getString("timezone")),
            "a store's country and zone")
        .stream()
        .findFirst();
  }

  // ── what an hour costs ──────────────────────────────────────────────────────

  private static final String RATE_COLUMNS =
      "id, tenant_id, user_id, effective_from, hourly_rate, currency, note, created_at, created_by";

  /**
   * Records a rate from a date.
   *
   * @throws ApiException 409 {@code WORKFORCE_RATE_EXISTS} when one already starts that day: two
   *     rates effective the same morning is an undecidable cost
   */
  public PayRate addRate(PayRate r) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO staff_pay_rates (id, tenant_id, user_id, effective_from,"
                      + " hourly_rate, currency, note, created_at, created_by)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.userId());
            ps.setObject(4, r.effectiveFrom());
            ps.setBigDecimal(5, r.hourlyRate());
            ps.setString(6, r.currency());
            ps.setString(7, r.note());
            ps.setObject(8, r.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(9, r.createdBy());
            ps.executeUpdate();
          }
          return r;
        },
        "record a pay rate");
  }

  /** A person's rates, newest first — which is the order the costing rule reads them in. */
  public List<PayRate> rates(UUID tenantId, UUID userId) {
    return query(
        "SELECT "
            + RATE_COLUMNS
            + " FROM staff_pay_rates WHERE tenant_id = ? AND user_id = ?"
            + " ORDER BY effective_from DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, userId);
        },
        WorkforceRepository::readRate,
        "a person's pay rates");
  }

  // ── the clock ───────────────────────────────────────────────────────────────

  /**
   * Opens an entry.
   *
   * @throws ApiException 409 {@code WORKFORCE_ALREADY_CLOCKED_IN} from the partial unique index,
   *     which is what makes two taps on a slow terminal one entry rather than two
   */
  public Entry clockIn(Entry e) {
    return inTx(
        c -> {
          insertEntry(c, e);
          return e;
        },
        "clock in");
  }

  private static void insertEntry(java.sql.Connection c, Entry e) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO time_entries (id, tenant_id, store_id, user_id, shift_id,"
                + " clocked_in_at, clocked_out_at, source, note, adjusted_reason, supersedes,"
                + " created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.storeId());
      ps.setObject(4, e.userId());
      ps.setObject(5, e.shiftId());
      ps.setObject(6, e.clockedInAt().atOffset(ZoneOffset.UTC));
      ps.setObject(7, e.clockedOutAt() == null ? null : e.clockedOutAt().atOffset(ZoneOffset.UTC));
      ps.setString(8, e.source());
      ps.setString(9, e.note());
      ps.setString(10, e.adjustedReason());
      ps.setObject(11, e.supersedes());
      ps.setObject(12, e.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(13, e.createdBy());
      ps.executeUpdate();
    }
  }

  /** The entry a person is on the clock for, breaks and all. */
  public Optional<Entry> openEntry(UUID tenantId, UUID userId) {
    return query(
            "SELECT "
                + ENTRY_COLUMNS
                + " FROM time_entries WHERE tenant_id = ? AND user_id = ?"
                + " AND clocked_out_at IS NULL AND superseded_by IS NULL",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
            },
            WorkforceRepository::readEntry,
            "the open entry")
        .stream()
        .findFirst()
        .map(e -> withBreaks(tenantId, e));
  }

  public Optional<Entry> entry(UUID tenantId, UUID id) {
    return query(
            "SELECT " + ENTRY_COLUMNS + " FROM time_entries WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            WorkforceRepository::readEntry,
            "a time entry")
        .stream()
        .findFirst()
        .map(e -> withBreaks(tenantId, e));
  }

  /**
   * Closes an entry, and any break still open with it.
   *
   * <p>One transaction, and the break is closed at the same instant: somebody who forgot to end a
   * break must not be stopped from going home, and a break left open would make the day's hours
   * undecidable for ever.
   *
   * @return false when the entry was not open, so the caller can answer 409 rather than report
   *     success
   */
  public boolean clockOut(UUID tenantId, UUID entryId, Instant at, OutboxRow labour) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE time_entry_breaks SET ended_at = ?"
                      + " WHERE tenant_id = ? AND time_entry_id = ? AND ended_at IS NULL")) {
            ps.setObject(1, at.atOffset(ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, entryId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE time_entries SET clocked_out_at = ? WHERE tenant_id = ? AND id = ?"
                      + " AND clocked_out_at IS NULL AND superseded_by IS NULL")) {
            ps.setObject(1, at.atOffset(ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, entryId);
            if (ps.executeUpdate() != 1) return false;
          }
          // In the same transaction as the hours: a cost announced for hours that were rolled back
          // would make a labour report disagree with the clock, and nothing would say which was
          // right. Null when the day has no rate in force, which is reported as unknown, not zero.
          if (labour != null) insertOutbox(c, labour);
          return true;
        },
        "clock out");
  }

  /**
   * Records a correction: the predecessor is marked and the new entry inserted, one transaction.
   *
   * <p>The predecessor must be marked first or the partial unique index refuses the insert, and the
   * deferred foreign key is what makes that order legal. The same shape as an invoice, a statutory
   * filing and a planogram — and for the same reason: a record that can be edited is a record
   * nobody can be held to.
   */
  public Entry adjust(Entry correction, List<Rest> breaks, OutboxRow labour) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE time_entries SET superseded_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND superseded_by IS NULL")) {
            ps.setObject(1, correction.id());
            ps.setObject(2, correction.tenantId());
            ps.setObject(3, correction.supersedes());
            if (ps.executeUpdate() != 1) {
              throw ApiException.conflict(
                  "WORKFORCE_ENTRY_NOT_STANDING",
                  "that entry has already been corrected by another");
            }
          }
          insertEntry(c, correction);
          // The breaks come with it: a correction that lost them would pay for the lunch hour.
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO time_entry_breaks (id, tenant_id, time_entry_id, started_at,"
                      + " ended_at, kind, paid) VALUES (?,?,?,?,?,?,?)")) {
            for (Rest r : breaks) {
              ps.setObject(1, com.storeql.ids.Ids.newId());
              ps.setObject(2, correction.tenantId());
              ps.setObject(3, correction.id());
              ps.setObject(4, r.startedAt().atOffset(ZoneOffset.UTC));
              ps.setObject(5, r.endedAt() == null ? null : r.endedAt().atOffset(ZoneOffset.UTC));
              ps.setString(6, r.kind());
              ps.setBoolean(7, r.paid());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          // The correction's cost, carrying the entry it replaces so a reader can take the old
          // figure back out: a report that counted both would double the day.
          if (labour != null) insertOutbox(c, labour);
          return correction;
        },
        "correct a time entry");
  }

  /**
   * Starts a break. The index refuses a second open one.
   *
   * <p>Through {@code inTx} rather than {@code exec} on purpose: only the transactional path
   * consults {@link #handleTxSqlException}, and a generic {@code DUPLICATE} would tell a shop
   * nothing about why its break button did nothing.
   */
  public Rest startBreak(Rest r) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO time_entry_breaks (id, tenant_id, time_entry_id, started_at, kind,"
                      + " paid) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.timeEntryId());
            ps.setObject(4, r.startedAt().atOffset(ZoneOffset.UTC));
            ps.setString(5, r.kind());
            ps.setBoolean(6, r.paid());
            ps.executeUpdate();
          }
          return r;
        },
        "start a break");
  }

  /** Ends the open break of an entry; false when there was none. */
  public boolean endBreak(UUID tenantId, UUID entryId, Instant at) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE time_entry_breaks SET ended_at = ? WHERE tenant_id = ?"
                      + " AND time_entry_id = ? AND ended_at IS NULL")) {
            ps.setObject(1, at.atOffset(ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, entryId);
            return ps.executeUpdate() == 1;
          }
        },
        "end a break");
  }

  /**
   * The entries of a window that stand, with their breaks.
   *
   * <p>Superseded entries are left out: they are the record of what was corrected, not hours
   * anybody worked, and counting them would double a day.
   */
  public List<Entry> entries(UUID tenantId, UUID storeId, UUID userId, Instant from, Instant to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + ENTRY_COLUMNS
                + " FROM time_entries WHERE tenant_id = ?"
                + " AND superseded_by IS NULL AND clocked_in_at < ?"
                + " AND (clocked_out_at IS NULL OR clocked_out_at > ?)");
    if (storeId != null) sql.append(" AND store_id = ?");
    if (userId != null) sql.append(" AND user_id = ?");
    sql.append(" ORDER BY user_id, clocked_in_at");
    List<Entry> found =
        query(
            sql.toString(),
            window(tenantId, storeId, userId, from, to),
            WorkforceRepository::readEntry,
            "entries of a window");
    if (found.isEmpty()) return found;
    Map<UUID, List<Rest>> byEntry = breaksOf(tenantId, found.stream().map(Entry::id).toList());
    List<Entry> out = new ArrayList<>(found.size());
    for (Entry e : found) out.add(withBreaks(e, byEntry.getOrDefault(e.id(), List.of())));
    return out;
  }

  /**
   * The breaks of many entries in one read: a query per entry would be a query per row of a report.
   */
  private Map<UUID, List<Rest>> breaksOf(UUID tenantId, List<UUID> entryIds) {
    Map<UUID, List<Rest>> out = new LinkedHashMap<>();
    for (Rest r :
        query(
            "SELECT "
                + BREAK_COLUMNS
                + " FROM time_entry_breaks WHERE tenant_id = ?"
                + " AND time_entry_id = ANY(?) ORDER BY started_at",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("uuid", entryIds.toArray()));
            },
            WorkforceRepository::readBreak,
            "breaks of entries")) {
      out.computeIfAbsent(r.timeEntryId(), k -> new ArrayList<>()).add(r);
    }
    return out;
  }

  private Entry withBreaks(UUID tenantId, Entry e) {
    return withBreaks(e, breaksOf(tenantId, List.of(e.id())).getOrDefault(e.id(), List.of()));
  }

  private static Entry withBreaks(Entry e, List<Rest> breaks) {
    return new Entry(
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
        e.supersededBy(),
        e.createdAt(),
        e.createdBy(),
        breaks);
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Shift readShift(ResultSet rs) throws SQLException {
    return new Shift(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        instant(rs, "starts_at"),
        instant(rs, "ends_at"),
        rs.getString("duty"),
        rs.getString("status"),
        rs.getString("note"),
        rs.getString("cancelled_reason"),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "updated_at"));
  }

  private static Entry readEntry(ResultSet rs) throws SQLException {
    return new Entry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("shift_id", UUID.class),
        instant(rs, "clocked_in_at"),
        instant(rs, "clocked_out_at"),
        rs.getString("source"),
        rs.getString("note"),
        rs.getString("adjusted_reason"),
        rs.getObject("supersedes", UUID.class),
        rs.getObject("superseded_by", UUID.class),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        List.of());
  }

  private static Rest readBreak(ResultSet rs) throws SQLException {
    return new Rest(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("time_entry_id", UUID.class),
        instant(rs, "started_at"),
        instant(rs, "ended_at"),
        rs.getString("kind"),
        rs.getBoolean("paid"));
  }

  private static PayRate readRate(ResultSet rs) throws SQLException {
    return new PayRate(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("effective_from", java.time.LocalDate.class),
        rs.getBigDecimal("hourly_rate"),
        rs.getString("currency"),
        rs.getString("note"),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  /** The two races the database decides, named so a caller can answer them properly. */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()) && e.getMessage() != null) {
      if (e.getMessage().contains("uq_time_entry_open")) {
        return ApiException.conflict(
            "WORKFORCE_ALREADY_CLOCKED_IN", "that person is already on the clock");
      }
      if (e.getMessage().contains("uq_pay_rate_day")) {
        return ApiException.conflict(
            "WORKFORCE_RATE_EXISTS", "a rate already starts on that day for that person");
      }
      if (e.getMessage().contains("uq_break_open")) {
        return ApiException.conflict(
            "WORKFORCE_BREAK_OPEN", "that entry already has a break running");
      }
    }
    return super.handleTxSqlException(what, e);
  }
}
