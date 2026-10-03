package com.storeql.tenant.repo;

import com.storeql.ids.Ids;
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
import java.util.Set;
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
   *
   * <p>The stores are bound only when the read is held to some, matching the SQL that asks for
   * them: a null bound as a uuid array is refused by the driver, so "every store" is no clause.
   */
  private static Binder window(
      UUID tenantId, Set<UUID> stores, UUID userId, Instant from, Instant to) {
    return ps -> {
      int i = 1;
      ps.setObject(i++, tenantId);
      ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
      ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
      if (stores != null) {
        ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
      }
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
   *
   * @param stores the stores to read, or null for every store of the business
   */
  public List<Shift> shifts(
      UUID tenantId, Set<UUID> stores, UUID userId, Instant from, Instant to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + SHIFT_COLUMNS
                + " FROM work_shifts WHERE tenant_id = ? AND starts_at < ?"
                + " AND ends_at > ?");
    if (stores != null) sql.append(" AND store_id = ANY(?)");
    if (userId != null) sql.append(" AND user_id = ?");
    sql.append(" ORDER BY user_id, starts_at");
    return query(
        sql.toString(),
        window(tenantId, stores, userId, from, to),
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

  /** What an attempt to publish a shift under a key came to. */
  public enum Publication {
    /** This attempt published the shift. */
    PUBLISHED,
    /** An earlier attempt under the same key published this very shift: answer it again. */
    REPLAYED,
    /** The key already published another shift; nothing moved. */
    KEY_REUSED,
    /**
     * The shift was not planned (or is not the business's), and no attempt under the key moved it.
     */
    NOT_PLANNED
  }

  /**
   * Publishes a planned shift under an Idempotency-Key, on one transaction: the move of its status
   * and the {@code shift_publications} row naming the key.
   *
   * <p>Three races are the database's to settle. A retry sent while the first attempt is still
   * running waits on the shift's row, finds it no longer planned, and then reads the first
   * attempt's publication under its key: a replay, not a conflict. The same key sent for two shifts
   * at once publishes the first and refuses the second, whose move is rolled back with it ({@code
   * uq_shift_publications_key}). Two keys for one shift publish it once ({@code status =
   * 'PLANNED'}); the second is told it is not planned.
   *
   * @param key the request's Idempotency-Key, a UUIDv7 in canonical form
   */
  public Publication publishShift(UUID tenantId, UUID shiftId, String key, UUID actorId) {
    return inTx(
        c -> {
          Publication earlier = publishedUnderTx(c, tenantId, shiftId, key);
          if (earlier != null) return earlier;
          Instant now = Instant.now();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE work_shifts SET status = ?, updated_at = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, Workforce.PUBLISHED);
            ps.setObject(2, now.atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, shiftId);
            ps.setString(5, Workforce.PLANNED);
            if (ps.executeUpdate() != 1) {
              // Not planned. Perhaps because an attempt under this very key published it while
              // this one waited on the row: the statement below sees what it committed.
              Publication raced = publishedUnderTx(c, tenantId, shiftId, key);
              return raced != null ? raced : Publication.NOT_PLANNED;
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO shift_publications"
                      + " (id, tenant_id, shift_id, idempotency_key, published_by, published_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (tenant_id, idempotency_key) DO NOTHING")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, shiftId);
            ps.setString(4, key);
            ps.setObject(5, actorId);
            ps.setObject(6, now.atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() != 1) {
              // The key published another shift at the same moment: refuse, and roll this move
              // back with the refusal.
              throw ApiException.conflict(
                  "IDEMPOTENCY_KEY_REUSED",
                  "this Idempotency-Key published another shift; send a new one for each shift");
            }
          }
          return Publication.PUBLISHED;
        },
        "publish a shift");
  }

  /** What an earlier attempt under the key did, on this transaction; null when there was none. */
  private static Publication publishedUnderTx(
      java.sql.Connection c, UUID tenantId, UUID shiftId, String key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT shift_id FROM shift_publications WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        return shiftId.equals(rs.getObject("shift_id", UUID.class))
            ? Publication.REPLAYED
            : Publication.KEY_REUSED;
      }
    }
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

  /**
   * Which of these people are assigned at one of these stores: what a caller held to stores may
   * read of a person (their pay, their commission) turns on it. One read however many people are
   * asked about, because a commission rating names up to hundreds.
   *
   * <p>A business-wide assignment (no store) is not one of anybody's stores, so it does not count —
   * narrower than the staff list, which shows a branch manager the business-wide people as well:
   * their names, not their pay.
   *
   * @param people who is asked about; never empty
   * @param stores the caller's stores; never empty
   * @return those of {@code people} assigned at one of {@code stores}
   */
  public Set<UUID> workingAt(UUID tenantId, Set<UUID> people, Set<UUID> stores) {
    return Set.copyOf(
        query(
            "SELECT DISTINCT user_id FROM staff_assignments"
                + " WHERE tenant_id = ? AND user_id = ANY(?) AND store_id = ANY(?)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("uuid", people.toArray()));
              ps.setArray(3, ps.getConnection().createArrayOf("uuid", stores.toArray()));
            },
            rs -> rs.getObject("user_id", UUID.class),
            "staff assignments at the caller's stores"));
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
   * A correction made under an Idempotency-Key: the entry it corrected and the entry that now
   * stands for it.
   */
  public record Adjustment(UUID entryId, UUID correctionId) {}

  /** The correction an earlier attempt made under the key, if one did. */
  public Optional<Adjustment> adjustmentUnder(UUID tenantId, String key) {
    return query(
            "SELECT entry_id, correction_id FROM time_entry_adjustments"
                + " WHERE tenant_id = ? AND idempotency_key = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, key);
            },
            rs ->
                new Adjustment(
                    rs.getObject("entry_id", UUID.class),
                    rs.getObject("correction_id", UUID.class)),
            "a correction under a key")
        .stream()
        .findFirst();
  }

  /**
   * Records a correction under an Idempotency-Key: the predecessor is marked, the new entry
   * inserted with its breaks, the {@code time_entry_adjustments} row naming the key and the
   * correction's {@code LabourRecorded} written — one transaction.
   *
   * <p>The predecessor must be marked first or the partial unique index refuses the insert, and the
   * deferred foreign key is what makes that order legal. The same shape as an invoice, a statutory
   * filing and a planogram — and for the same reason: a record that can be edited is a record
   * nobody can be held to.
   *
   * <p>Two races are the database's to settle, as publishing a shift's are. A retry sent while the
   * first attempt is still running waits on the entry's row, finds it already corrected, and then
   * reads the first attempt's correction under its key: that is answered, not a conflict. The same
   * key sent for two entries at once corrects the first and refuses the second, whose correction is
   * rolled back with it ({@code uq_time_entry_adjustments_key}).
   *
   * @param key the request's Idempotency-Key, a UUIDv7 in canonical form
   * @return this correction, or — when an attempt under the same key got there first — the one it
   *     made, for the caller to answer if it is the same request
   * @throws ApiException 409 {@code WORKFORCE_ENTRY_NOT_STANDING} when the entry was corrected by
   *     another request; 409 {@code IDEMPOTENCY_KEY_REUSED} when the key corrected another entry at
   *     the same moment
   */
  public Adjustment adjust(Entry correction, List<Rest> breaks, OutboxRow labour, String key) {
    return inTx(
        c -> {
          Adjustment earlier = adjustmentUnderTx(c, correction.tenantId(), key);
          if (earlier != null) return earlier;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE time_entries SET superseded_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND superseded_by IS NULL")) {
            ps.setObject(1, correction.id());
            ps.setObject(2, correction.tenantId());
            ps.setObject(3, correction.supersedes());
            if (ps.executeUpdate() != 1) {
              // Already corrected. Perhaps by an attempt under this very key that committed while
              // this one waited on the row: the statement below sees what it committed.
              Adjustment raced = adjustmentUnderTx(c, correction.tenantId(), key);
              if (raced != null) return raced;
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
              ps.setObject(1, Ids.newId());
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
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO time_entry_adjustments"
                      + " (id, tenant_id, entry_id, correction_id, idempotency_key, adjusted_by,"
                      + " adjusted_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (tenant_id, idempotency_key) DO NOTHING")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, correction.tenantId());
            ps.setObject(3, correction.supersedes());
            ps.setObject(4, correction.id());
            ps.setString(5, key);
            ps.setObject(6, correction.createdBy());
            ps.setObject(7, correction.createdAt().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() != 1) {
              // The key corrected another entry at the same moment: refuse, and roll this
              // correction back with the refusal.
              throw ApiException.conflict(
                  "IDEMPOTENCY_KEY_REUSED",
                  "this Idempotency-Key corrected other hours; send a new one for each correction");
            }
          }
          // The correction's cost, carrying the entry it replaces so a reader can take the old
          // figure back out: a report that counted both would double the day.
          if (labour != null) insertOutbox(c, labour);
          return new Adjustment(correction.supersedes(), correction.id());
        },
        "correct a time entry");
  }

  /** What an earlier attempt under the key corrected, on this transaction; null when none did. */
  private static Adjustment adjustmentUnderTx(java.sql.Connection c, UUID tenantId, String key)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT entry_id, correction_id FROM time_entry_adjustments"
                + " WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        return new Adjustment(
            rs.getObject("entry_id", UUID.class), rs.getObject("correction_id", UUID.class));
      }
    }
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
   *
   * @param stores the stores to read, or null for every store of the business
   */
  public List<Entry> entries(
      UUID tenantId, Set<UUID> stores, UUID userId, Instant from, Instant to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + ENTRY_COLUMNS
                + " FROM time_entries WHERE tenant_id = ?"
                + " AND superseded_by IS NULL AND clocked_in_at < ?"
                + " AND (clocked_out_at IS NULL OR clocked_out_at > ?)");
    if (stores != null) sql.append(" AND store_id = ANY(?)");
    if (userId != null) sql.append(" AND user_id = ?");
    sql.append(" ORDER BY user_id, clocked_in_at");
    List<Entry> found =
        query(
            sql.toString(),
            window(tenantId, stores, userId, from, to),
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
