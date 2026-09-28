package com.storeql.customer.repo;

import com.storeql.customer.domain.Privacy;
import com.storeql.customer.domain.Privacy.ConsentEntry;
import com.storeql.customer.domain.Privacy.GuardianConsent;
import com.storeql.customer.domain.Privacy.Intimation;
import com.storeql.customer.domain.Privacy.Notice;
import com.storeql.customer.domain.Privacy.PurposeConsent;
import com.storeql.customer.domain.Privacy.Request;
import com.storeql.customer.domain.Privacy.Settings;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The privacy tables (13.12): every query tenant-scoped first, the consent log append-only. */
@ApplicationScoped
public class PrivacyRepository extends BaseJdbcRepository {

  private static final String SETTINGS_COLUMNS =
      "tenant_id, grievance_name, grievance_email, grievance_phone, grievance_address,"
          + " response_days, updated_at, updated_by";

  private static final String NOTICE_COLUMNS =
      "id, tenant_id, language, version, title, body, published_at, published_by";

  private static final String CONSENT_COLUMNS =
      "tenant_id, customer_id, purpose, granted, notice_language, notice_version, updated_at";

  private static final String LOG_COLUMNS =
      "id, tenant_id, customer_id, purpose, granted, source, notice_language, notice_version,"
          + " actor_id, recorded_at";

  private static final String GUARDIAN_COLUMNS =
      "tenant_id, customer_id, guardian_name, verification, reference, given_at, recorded_by,"
          + " withdrawn_at, withdrawn_by";

  private static final String REQUEST_COLUMNS =
      "id, tenant_id, customer_id, kind, detail, nominee_name, nominee_contact, opened_at, due_on,"
          + " status, resolution, resolved_at, resolved_by";

  private static final String INTIMATION_COLUMNS =
      "id, tenant_id, notice_id, subject, body, sent_at, sent_by, recipients, failures";

  // ── settings ──────────────────────────────────────────────────────────────

  public Optional<Settings> findSettings(UUID tenantId) {
    return query(
            "SELECT " + SETTINGS_COLUMNS + " FROM privacy_settings WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            PrivacyRepository::mapSettings,
            "privacy settings")
        .stream()
        .findFirst();
  }

  public void upsertSettings(Settings s) {
    exec(
        "INSERT INTO privacy_settings ("
            + SETTINGS_COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id) DO UPDATE SET grievance_name = EXCLUDED.grievance_name,"
            + " grievance_email = EXCLUDED.grievance_email,"
            + " grievance_phone = EXCLUDED.grievance_phone,"
            + " grievance_address = EXCLUDED.grievance_address,"
            + " response_days = EXCLUDED.response_days, updated_at = EXCLUDED.updated_at,"
            + " updated_by = EXCLUDED.updated_by",
        ps -> {
          ps.setObject(1, s.tenantId());
          ps.setString(2, s.grievanceName());
          ps.setString(3, s.grievanceEmail());
          ps.setString(4, s.grievancePhone());
          ps.setString(5, s.grievanceAddress());
          ps.setInt(6, s.responseDays());
          ps.setObject(7, odt(s.updatedAt()));
          ps.setObject(8, s.updatedBy());
        },
        "save privacy settings");
  }

  // ── notices ───────────────────────────────────────────────────────────────

  /** Publishes the next version in a language, under the tenant's unique version row. */
  public Notice publish(
      UUID id, UUID tenantId, String language, String title, String body, Instant at, UUID by) {
    return inTx(
        c -> {
          // The newest version is locked so two publishes in the same language queue up; a race
          // that slips past is caught by the unique version and answered as busy.
          int version = 1;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT version FROM privacy_notices WHERE tenant_id = ? AND language = ?"
                      + " ORDER BY version DESC LIMIT 1 FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setString(2, language);
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next()) version = rs.getInt(1) + 1;
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO privacy_notices ("
                      + NOTICE_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, language);
            ps.setInt(4, version);
            ps.setString(5, title);
            ps.setString(6, body);
            ps.setObject(7, odt(at));
            ps.setObject(8, by);
            ps.executeUpdate();
          }
          return new Notice(id, tenantId, language, version, title, body, at, by);
        },
        "publish privacy notice");
  }

  /** The current version in each language, newest publication first. */
  public List<Notice> currentNotices(UUID tenantId) {
    return query(
        "SELECT DISTINCT ON (language) "
            + NOTICE_COLUMNS
            + " FROM privacy_notices WHERE tenant_id = ? ORDER BY language, version DESC",
        ps -> ps.setObject(1, tenantId),
        PrivacyRepository::mapNotice,
        "current privacy notices");
  }

  public Optional<Notice> currentNotice(UUID tenantId, String language) {
    return query(
            "SELECT "
                + NOTICE_COLUMNS
                + " FROM privacy_notices WHERE tenant_id = ? AND language = ?"
                + " ORDER BY version DESC LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, language);
            },
            PrivacyRepository::mapNotice,
            "current privacy notice")
        .stream()
        .findFirst();
  }

  // ── purpose consents ──────────────────────────────────────────────────────

  public List<PurposeConsent> consents(UUID tenantId, UUID customerId) {
    return query(
        "SELECT "
            + CONSENT_COLUMNS
            + " FROM purpose_consents WHERE tenant_id = ? AND customer_id = ? ORDER BY purpose",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
        },
        PrivacyRepository::mapConsent,
        "purpose consents");
  }

  /**
   * Records grants and withdrawals: the current state and the evidence, in one transaction — and,
   * for MARKETING withdrawn, the marketing-channel cascade on the same transaction: the purpose
   * withdrawal and every channel it switches off commit or roll back together.
   */
  public void record(List<ConsentEntry> entries) {
    inTx(
        c -> {
          for (ConsentEntry e : entries) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO purpose_consents ("
                        + CONSENT_COLUMNS
                        + ") VALUES (?,?,?,?,?,?,?)"
                        + " ON CONFLICT (tenant_id, customer_id, purpose) DO UPDATE SET"
                        + " granted = EXCLUDED.granted, notice_language = EXCLUDED.notice_language,"
                        + " notice_version = EXCLUDED.notice_version,"
                        + " updated_at = EXCLUDED.updated_at")) {
              ps.setObject(1, e.tenantId());
              ps.setObject(2, e.customerId());
              ps.setString(3, e.purpose());
              ps.setBoolean(4, e.granted());
              ps.setString(5, e.noticeLanguage());
              ps.setObject(6, e.noticeVersion());
              ps.setObject(7, odt(e.recordedAt()));
              ps.executeUpdate();
            }
            insertLog(c, e, e.granted());
          }
          cascadeMarketingWithdrawal(c, entries);
          return null;
        },
        "record purpose consents");
  }

  public List<ConsentEntry> consentLog(UUID tenantId, UUID customerId, int limit) {
    return query(
        "SELECT "
            + LOG_COLUMNS
            + " FROM purpose_consent_log WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY recorded_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setInt(3, limit);
        },
        PrivacyRepository::mapEntry,
        "purpose consent log");
  }

  // ── guardian consents ─────────────────────────────────────────────────────

  public Optional<GuardianConsent> guardian(UUID tenantId, UUID customerId) {
    return query(
            "SELECT "
                + GUARDIAN_COLUMNS
                + " FROM guardian_consents WHERE tenant_id = ? AND customer_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            PrivacyRepository::mapGuardian,
            "guardian consent")
        .stream()
        .findFirst();
  }

  /** Records a guardian's consent, replacing one withdrawn earlier. */
  public void saveGuardian(GuardianConsent g) {
    exec(
        "INSERT INTO guardian_consents ("
            + GUARDIAN_COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id, customer_id) DO UPDATE SET"
            + " guardian_name = EXCLUDED.guardian_name, verification = EXCLUDED.verification,"
            + " reference = EXCLUDED.reference, given_at = EXCLUDED.given_at,"
            + " recorded_by = EXCLUDED.recorded_by, withdrawn_at = NULL, withdrawn_by = NULL",
        ps -> {
          ps.setObject(1, g.tenantId());
          ps.setObject(2, g.customerId());
          ps.setString(3, g.guardianName());
          ps.setString(4, g.verification());
          ps.setString(5, g.reference());
          ps.setObject(6, odt(g.givenAt()));
          ps.setObject(7, g.recordedBy());
          ps.setObject(8, odt(g.withdrawnAt()));
          ps.setObject(9, g.withdrawnBy());
        },
        "save guardian consent");
  }

  /**
   * Withdraws a guardian's consent and, with it, every tracking consent the child had, recording
   * each withdrawal as evidence — MARKETING among them cascades to every marketing channel on the
   * same transaction, exactly as a direct withdrawal of it does.
   *
   * @return whether a standing consent was withdrawn
   */
  public boolean withdrawGuardian(
      UUID tenantId, UUID customerId, UUID by, Instant at, List<ConsentEntry> fallingConsents) {
    return inTx(
        c -> {
          int changed;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE guardian_consents SET withdrawn_at = ?, withdrawn_by = ?"
                      + " WHERE tenant_id = ? AND customer_id = ? AND withdrawn_at IS NULL")) {
            ps.setObject(1, odt(at));
            ps.setObject(2, by);
            ps.setObject(3, tenantId);
            ps.setObject(4, customerId);
            changed = ps.executeUpdate();
          }
          if (changed == 0) return false;
          for (ConsentEntry e : fallingConsents) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE purpose_consents SET granted = FALSE, updated_at = ?"
                        + " WHERE tenant_id = ? AND customer_id = ? AND purpose = ?")) {
              ps.setObject(1, odt(e.recordedAt()));
              ps.setObject(2, e.tenantId());
              ps.setObject(3, e.customerId());
              ps.setString(4, e.purpose());
              ps.executeUpdate();
            }
            insertLog(c, e, false);
          }
          cascadeMarketingWithdrawal(c, fallingConsents);
          return true;
        },
        "withdraw guardian consent");
  }

  // ── requests ──────────────────────────────────────────────────────────────

  public Request open(Request r) {
    exec(
        "INSERT INTO privacy_requests (" + REQUEST_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, r.id());
          ps.setObject(2, r.tenantId());
          ps.setObject(3, r.customerId());
          ps.setString(4, r.kind());
          ps.setString(5, r.detail());
          ps.setString(6, r.nomineeName());
          ps.setString(7, r.nomineeContact());
          ps.setObject(8, odt(r.openedAt()));
          ps.setObject(9, r.dueOn());
          ps.setString(10, r.status());
          ps.setString(11, r.resolution());
          ps.setObject(12, odt(r.resolvedAt()));
          ps.setObject(13, r.resolvedBy());
        },
        "open privacy request");
    return r;
  }

  public Optional<Request> findRequest(UUID tenantId, UUID id) {
    return query(
            "SELECT " + REQUEST_COLUMNS + " FROM privacy_requests WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PrivacyRepository::mapRequest,
            "find privacy request")
        .stream()
        .findFirst();
  }

  /** The queue: open requests soonest due first, else every request newest first. */
  public List<Request> requests(UUID tenantId, String status, int limit) {
    if (status == null) {
      return query(
          "SELECT "
              + REQUEST_COLUMNS
              + " FROM privacy_requests WHERE tenant_id = ? ORDER BY opened_at DESC, id DESC"
              + " LIMIT ?",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setInt(2, limit);
          },
          PrivacyRepository::mapRequest,
          "privacy requests");
    }
    return query(
        "SELECT "
            + REQUEST_COLUMNS
            + " FROM privacy_requests WHERE tenant_id = ? AND status = ?"
            + " ORDER BY due_on, opened_at, id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, status);
          ps.setInt(3, limit);
        },
        PrivacyRepository::mapRequest,
        "privacy requests by status");
  }

  public List<Request> requestsOf(UUID tenantId, UUID customerId) {
    return query(
        "SELECT "
            + REQUEST_COLUMNS
            + " FROM privacy_requests WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY opened_at DESC, id DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
        },
        PrivacyRepository::mapRequest,
        "a customer's privacy requests");
  }

  /**
   * Resolves an open request.
   *
   * @return whether it was open
   */
  public boolean resolve(
      UUID tenantId, UUID id, String status, String resolution, UUID by, Instant at) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE privacy_requests SET status = ?, resolution = ?, resolved_at = ?,"
                      + " resolved_by = ? WHERE tenant_id = ? AND id = ? AND status = 'OPEN'")) {
            ps.setString(1, status);
            ps.setString(2, resolution);
            ps.setObject(3, odt(at));
            ps.setObject(4, by);
            ps.setObject(5, tenantId);
            ps.setObject(6, id);
            return ps.executeUpdate() == 1;
          }
        },
        "resolve privacy request");
  }

  // ── breach intimations ────────────────────────────────────────────────────

  public Intimation recordIntimation(Intimation i) {
    exec(
        "INSERT INTO breach_intimations (" + INTIMATION_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, i.id());
          ps.setObject(2, i.tenantId());
          ps.setObject(3, i.noticeId());
          ps.setString(4, i.subject());
          ps.setString(5, i.body());
          ps.setObject(6, odt(i.sentAt()));
          ps.setObject(7, i.sentBy());
          ps.setInt(8, i.recipients());
          ps.setInt(9, i.failures());
        },
        "record breach intimation");
    return i;
  }

  public List<Intimation> intimations(UUID tenantId, int limit) {
    return query(
        "SELECT "
            + INTIMATION_COLUMNS
            + " FROM breach_intimations WHERE tenant_id = ? ORDER BY sent_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        PrivacyRepository::mapIntimation,
        "breach intimations");
  }

  /** Everyone the business can reach: active customers with an email or a phone. */
  public List<Reachable> reachable(UUID tenantId, List<UUID> only, int limit) {
    if (only == null || only.isEmpty()) {
      return query(
          "SELECT id, email, phone, phone_e164 FROM customers WHERE tenant_id = ?"
              + " AND status = 'ACTIVE' AND (email IS NOT NULL OR phone IS NOT NULL)"
              + " ORDER BY created_at, id LIMIT ?",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setInt(2, limit);
          },
          PrivacyRepository::mapReachable,
          "reachable customers");
    }
    return query(
        "SELECT id, email, phone, phone_e164 FROM customers WHERE tenant_id = ?"
            + " AND status = 'ACTIVE' AND (email IS NOT NULL OR phone IS NOT NULL)"
            + " AND id = ANY (?) ORDER BY created_at, id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", only.toArray()));
          ps.setInt(3, limit);
        },
        PrivacyRepository::mapReachable,
        "named reachable customers");
  }

  /**
   * A customer as a breach intimation reaches them.
   *
   * @param phoneE164 {@code phone} normalised, preferred for a text — the SMS channel only accepts
   *     E.164; {@code null} when {@code phone} does not normalise, so a caller falls back to it as
   *     typed
   */
  public record Reachable(UUID id, String email, String phone, String phoneE164) {}

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if ("23505".equals(e.getSQLState())) {
      return ApiException.conflict(
          "PRIVACY_NOTICE_BUSY", "the notice is being published by someone else; try again");
    }
    return super.handleTxSqlException(what, e);
  }

  /**
   * The marketing-consent cascade: a MARKETING entry that withdraws switches off every marketing
   * channel that is on, on the same connection's transaction. Shared by a direct withdrawal ({@link
   * #record}) and a guardian's withdrawal falling through to the child's consents ({@link
   * #withdrawGuardian}) — both hand this the same shape of entry.
   */
  private static void cascadeMarketingWithdrawal(Connection c, List<ConsentEntry> entries)
      throws SQLException {
    for (ConsentEntry e : entries) {
      if (Privacy.PURPOSE_MARKETING.equals(e.purpose()) && !e.granted()) {
        CustomerRepository.cascadeWithdrawMarketingInTx(
            c, e.tenantId(), e.customerId(), e.actorId(), e.recordedAt());
      }
    }
  }

  /** One line of evidence: a grant or a withdrawal as it happened. */
  private static void insertLog(Connection c, ConsentEntry e, boolean granted) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO purpose_consent_log (" + LOG_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.customerId());
      ps.setString(4, e.purpose());
      ps.setBoolean(5, granted);
      ps.setString(6, e.source());
      ps.setString(7, e.noticeLanguage());
      ps.setObject(8, e.noticeVersion());
      ps.setObject(9, e.actorId());
      ps.setObject(10, odt(e.recordedAt()));
      ps.executeUpdate();
    }
  }

  // ── row mappers ───────────────────────────────────────────────────────────

  private static Settings mapSettings(ResultSet rs) throws SQLException {
    return new Settings(
        rs.getObject("tenant_id", UUID.class),
        rs.getString("grievance_name"),
        rs.getString("grievance_email"),
        rs.getString("grievance_phone"),
        rs.getString("grievance_address"),
        rs.getInt("response_days"),
        instant(rs, "updated_at"),
        rs.getObject("updated_by", UUID.class));
  }

  private static Notice mapNotice(ResultSet rs) throws SQLException {
    return new Notice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("language"),
        rs.getInt("version"),
        rs.getString("title"),
        rs.getString("body"),
        instant(rs, "published_at"),
        rs.getObject("published_by", UUID.class));
  }

  private static PurposeConsent mapConsent(ResultSet rs) throws SQLException {
    return new PurposeConsent(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("purpose"),
        rs.getBoolean("granted"),
        rs.getString("notice_language"),
        rs.getObject("notice_version", Integer.class),
        instant(rs, "updated_at"));
  }

  private static ConsentEntry mapEntry(ResultSet rs) throws SQLException {
    return new ConsentEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("purpose"),
        rs.getBoolean("granted"),
        rs.getString("source"),
        rs.getString("notice_language"),
        rs.getObject("notice_version", Integer.class),
        rs.getObject("actor_id", UUID.class),
        instant(rs, "recorded_at"));
  }

  private static GuardianConsent mapGuardian(ResultSet rs) throws SQLException {
    return new GuardianConsent(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("guardian_name"),
        rs.getString("verification"),
        rs.getString("reference"),
        instant(rs, "given_at"),
        rs.getObject("recorded_by", UUID.class),
        instant(rs, "withdrawn_at"),
        rs.getObject("withdrawn_by", UUID.class));
  }

  private static Request mapRequest(ResultSet rs) throws SQLException {
    return new Request(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("kind"),
        rs.getString("detail"),
        rs.getString("nominee_name"),
        rs.getString("nominee_contact"),
        instant(rs, "opened_at"),
        rs.getObject("due_on", LocalDate.class),
        rs.getString("status"),
        rs.getString("resolution"),
        instant(rs, "resolved_at"),
        rs.getObject("resolved_by", UUID.class));
  }

  private static Intimation mapIntimation(ResultSet rs) throws SQLException {
    return new Intimation(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("notice_id", UUID.class),
        rs.getString("subject"),
        rs.getString("body"),
        instant(rs, "sent_at"),
        rs.getObject("sent_by", UUID.class),
        rs.getInt("recipients"),
        rs.getInt("failures"));
  }

  private static Reachable mapReachable(ResultSet rs) throws SQLException {
    return new Reachable(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("phone_e164"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime odt(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
