package com.storeql.tenant.repo;

import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Dunning;
import com.storeql.tenant.domain.Dunning.Event;
import com.storeql.tenant.domain.Dunning.Overdue;
import com.storeql.tenant.domain.Dunning.Policy;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chasing what is owed (21.12).
 *
 * <p>Two rules here. <b>A step happens once</b>, by the unique index on (invoice, step) rather than
 * by a check: the run can be run twice, or by two replicas at the same instant, and the second one
 * loses in the database. And <b>why a business was switched off is recorded</b>, because "pay your
 * bill and the platform comes back" has to be able to tell a suspension the platform imposed from
 * one an administrator did, or money would quietly overrule a decision somebody took.
 */
@ApplicationScoped
public class DunningRepository extends BaseOutboxRepository {

  // ── the policy ──────────────────────────────────────────────────────────────

  private static final String SELECT_POLICY =
      "SELECT enabled, reminder_days, suspend_after_days, uncollectible_after_days, updated_by,"
          + " updated_at FROM dunning_policy WHERE id = 1";

  private static final String UPSERT_POLICY =
      "INSERT INTO dunning_policy (id, enabled, reminder_days, suspend_after_days,"
          + " uncollectible_after_days, updated_by, updated_at) VALUES (1,?,?,?,?,?,?)"
          + " ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled,"
          + " reminder_days = EXCLUDED.reminder_days,"
          + " suspend_after_days = EXCLUDED.suspend_after_days,"
          + " uncollectible_after_days = EXCLUDED.uncollectible_after_days,"
          + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at";

  /**
   * The platform's policy, or the defaults standing in for one.
   *
   * @return never empty — an absent row means {@link Dunning#DEFAULT_POLICY}, because a platform
   *     that has not thought about dunning still wants its invoices chased
   */
  public Policy policy() {
    return query(SELECT_POLICY, ps -> {}, DunningRepository::readPolicy, "read dunning policy")
        .stream()
        .findFirst()
        .orElse(Dunning.DEFAULT_POLICY);
  }

  public void savePolicy(Policy p, UUID actorId) {
    exec(
        UPSERT_POLICY,
        ps -> {
          ps.setBoolean(1, p.enabled());
          ps.setString(2, joinDays(p.reminderDays()));
          ps.setInt(3, p.suspendAfterDays());
          ps.setInt(4, p.uncollectibleAfterDays());
          ps.setObject(5, actorId);
          ps.setObject(6, Instant.now().atOffset(ZoneOffset.UTC));
        },
        "save dunning policy");
  }

  // ── what has been done ──────────────────────────────────────────────────────

  private static final String INSERT_EVENT =
      "INSERT INTO dunning_events (id, tenant_id, invoice_id, step, detail, actor_id, created_at)"
          + " VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";

  private static final String EVENTS_OF =
      "SELECT id, invoice_id, step, detail, actor_id, created_at FROM dunning_events"
          + " WHERE invoice_id = ? ORDER BY created_at, id";

  /**
   * Records a step, once.
   *
   * @return false when this invoice has already had this step, which is how a run that runs twice
   *     chases once — the caller does the work only when this says it is the one doing it
   */
  public boolean claimStep(
      UUID id, UUID tenantId, UUID invoiceId, String step, String detail, UUID actorId) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(INSERT_EVENT)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, invoiceId);
            ps.setString(4, step);
            ps.setString(5, detail);
            ps.setObject(6, actorId);
            ps.setObject(7, Instant.now().atOffset(ZoneOffset.UTC));
            return ps.executeUpdate() == 1;
          }
        },
        "claim dunning step");
  }

  /**
   * Records a notice step, once — and with it, in the same transaction, the pay link the notice
   * carries and the event that writes it (golden rule 6).
   *
   * <p>Three writes that must agree: a step recorded with no notice behind it would suspend a
   * business the platform never told; a notice with no step would tell it twice on the next run; a
   * link nobody was sent is a link that pays nothing. One transaction, so a run that dies halfway
   * leaves all three or none.
   *
   * @param tokenHash the hash of the link's token; the newest link is the one that pays, so an
   *     older notice's stops working
   * @return false when this invoice has already had this step, so the caller does nothing
   * @throws ApiException 409 {@code INVOICE_NOT_OPEN} when the invoice can no longer be paid: a
   *     notice asking for money on it would lead nowhere, and the step is not recorded
   */
  public boolean claimNotice(
      UUID id,
      UUID tenantId,
      UUID invoiceId,
      String step,
      String detail,
      String tokenHash,
      OutboxRow notice) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(INSERT_EVENT)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, invoiceId);
            ps.setString(4, step);
            ps.setString(5, detail);
            ps.setObject(6, null);
            ps.setObject(7, Instant.now().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() != 1) return false;
          }
          try (PreparedStatement ps = c.prepareStatement(STORE_PAY_TOKEN)) {
            ps.setString(1, tokenHash);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, invoiceId);
            if (ps.executeUpdate() != 1) {
              throw ApiException.conflict(
                  "INVOICE_NOT_OPEN",
                  "This invoice cannot be paid, so a notice about it would lead nowhere");
            }
          }
          insertOutbox(c, notice);
          return true;
        },
        "claim dunning notice");
  }

  public List<Event> eventsOf(UUID invoiceId) {
    return query(
        EVENTS_OF,
        ps -> ps.setObject(1, invoiceId),
        rs ->
            new Event(
                rs.getObject("id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                rs.getString("step"),
                rs.getString("detail"),
                rs.getObject("actor_id", UUID.class),
                instant(rs, "created_at")),
        "dunning events");
  }

  // ── what is overdue ─────────────────────────────────────────────────────────

  /**
   * Every open invoice past its date on a day, oldest first, with the last step taken beside it.
   *
   * <p>The join is on this service's own tables, so it is a join and not a cross-service read. The
   * stage comes from the latest event rather than a column on the invoice, because a column would
   * be a second copy of what the append-only log already says.
   */
  private static final String OVERDUE =
      "SELECT i.id, i.tenant_id, i.number, i.due_date,"
          + " (SELECT e.step FROM dunning_events e WHERE e.invoice_id = i.id"
          + "    AND e.step <> 'DUE_DATE_EXTENDED' ORDER BY e.created_at DESC LIMIT 1) AS stage"
          + " FROM billing_invoices i"
          + " WHERE i.status = 'OPEN' AND i.due_date < ? ORDER BY i.due_date, i.number LIMIT ?";

  public List<Overdue> overdueOn(LocalDate asOf, int limit) {
    return query(
        OVERDUE,
        ps -> {
          ps.setObject(1, asOf);
          ps.setInt(2, limit);
        },
        rs -> {
          LocalDate due = rs.getObject("due_date", LocalDate.class);
          return new Overdue(
              rs.getObject("id", UUID.class),
              rs.getObject("tenant_id", UUID.class),
              rs.getString("number"),
              due,
              (int) java.time.temporal.ChronoUnit.DAYS.between(due, asOf),
              rs.getString("stage"),
              null);
        },
        "overdue invoices");
  }

  // ── the way back ────────────────────────────────────────────────────────────

  private static final String STORE_PAY_TOKEN =
      "UPDATE billing_invoices SET pay_token_hash = ?, updated_at = ? WHERE id = ? AND status = 'OPEN'";

  /**
   * The invoice a pay token opens, if it opens one.
   *
   * <p>Looked up by hash and by status together: a token for an invoice that has since been paid or
   * withdrawn opens nothing, so a link from an old notice cannot be used to pay twice.
   */
  private static final String BY_PAY_TOKEN =
      "SELECT id, tenant_id, number, currency, total_amount, amount_paid FROM billing_invoices"
          + " WHERE pay_token_hash = ? AND status = 'OPEN'";

  public boolean storePayToken(UUID invoiceId, String tokenHash) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(STORE_PAY_TOKEN)) {
            ps.setString(1, tokenHash);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, invoiceId);
            return ps.executeUpdate() == 1;
          }
        },
        "store pay token");
  }

  /** What a pay link may pay: the invoice's id and what is left on it, and nothing else. */
  public record Payable(
      UUID invoiceId,
      UUID tenantId,
      String number,
      String currency,
      java.math.BigDecimal outstanding) {}

  public Optional<Payable> payableByToken(String tokenHash) {
    return query(
            BY_PAY_TOKEN,
            ps -> ps.setString(1, tokenHash),
            rs ->
                new Payable(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("number"),
                    rs.getString("currency"),
                    rs.getBigDecimal("total_amount").subtract(rs.getBigDecimal("amount_paid"))),
            "invoice by pay token")
        .stream()
        .findFirst();
  }

  // ── why a business is off ───────────────────────────────────────────────────

  private static final String DEACTIVATE =
      "UPDATE tenants SET status = 'INACTIVE', deactivated_reason = ?, deactivated_by = ?,"
          + " deactivated_at = ? WHERE id = ? AND status <> 'INACTIVE'";

  /**
   * Switches a business off for a reason, naming the status it moves <em>from</em>.
   *
   * @return false when it was already off, so dunning does not overwrite an administrator's reason
   *     with its own and make the next payment lift a suspension nobody asked it to lift
   */
  public boolean deactivate(UUID tenantId, String reason, UUID actorId) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(DEACTIVATE)) {
            ps.setString(1, reason);
            ps.setObject(2, actorId);
            ps.setObject(3, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(4, tenantId);
            return ps.executeUpdate() == 1;
          }
        },
        "deactivate for a reason");
  }

  /**
   * Switches a business back on, but only one that was switched off for the reason given.
   *
   * <p>This is the whole point of the column. Reactivating "an inactive business" would let a
   * payment undo an administrator's decision; reactivating one whose reason is {@code NON_PAYMENT}
   * undoes only what the platform itself did for money.
   *
   * @return false when it was not off for that reason, which is the safe answer
   */
  private static final String REACTIVATE =
      "UPDATE tenants SET status = 'ACTIVE', deactivated_reason = NULL, deactivated_by = NULL,"
          + " deactivated_at = NULL WHERE id = ? AND status = 'INACTIVE' AND deactivated_reason = ?";

  public boolean reactivateIf(UUID tenantId, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(REACTIVATE)) {
            ps.setObject(1, tenantId);
            ps.setString(2, reason);
            return ps.executeUpdate() == 1;
          }
        },
        "reactivate for a reason");
  }

  private static final String DEACTIVATION_REASON =
      "SELECT deactivated_reason FROM tenants WHERE id = ?";

  public Optional<String> deactivationReason(UUID tenantId) {
    return query(
            DEACTIVATION_REASON,
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getString("deactivated_reason"),
            "deactivation reason")
        .stream()
        .findFirst();
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Policy readPolicy(ResultSet rs) throws SQLException {
    return new Policy(
        rs.getBoolean("enabled"),
        parseDays(rs.getString("reminder_days")),
        rs.getInt("suspend_after_days"),
        rs.getInt("uncollectible_after_days"),
        rs.getObject("updated_by", UUID.class),
        instant(rs, "updated_at"));
  }

  /** Ascending and without duplicates, so the policy reads the same however it was typed. */
  static List<Integer> parseDays(String csv) {
    List<Integer> days = new ArrayList<>();
    if (csv == null || csv.isBlank()) return days;
    Arrays.stream(csv.split(","))
        .map(String::strip)
        .filter(d -> !d.isEmpty())
        .map(Integer::valueOf)
        .distinct()
        .sorted()
        .forEach(days::add);
    return days;
  }

  static String joinDays(List<Integer> days) {
    return days.stream()
        .distinct()
        .sorted()
        .map(String::valueOf)
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
