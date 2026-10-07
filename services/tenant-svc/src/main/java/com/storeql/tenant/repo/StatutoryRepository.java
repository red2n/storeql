package com.storeql.tenant.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.StatutoryReturns.Filing;
import com.storeql.tenant.domain.StatutoryReturns.Return;
import jakarta.enterprise.context.ApplicationScoped;
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

/**
 * Which statutory returns a jurisdiction requires, and what a business filed.
 *
 * <p><b>Nothing here returns a due date or a state.</b> Both are worked out from the return's own
 * frequency and offset against the period, in the domain, on every read — a stored deadline is a
 * deadline that goes stale, which is why the incident register (V7) derives its clocks too.
 *
 * <p>The returns are reference data and carry no tenant: the law is the same for every business in
 * a country. The filings are that business's own.
 */
@ApplicationScoped
public class StatutoryRepository extends BaseJdbcRepository {

  private static final String RETURN_COLUMNS =
      "SELECT code, scope_kind, scope, name, frequency, due_after, export_service, export_path,"
          + " citation, effective_from, effective_to FROM statutory_returns";

  /**
   * Every return that could reach a country: its own, and each regime's.
   *
   * <p>The membership question is left to the caller, because it is asked of the <em>period's</em>
   * dates and not of today — a business that left a regime mid-year owes the periods it was a
   * member for and not the ones after, and a query that filtered on "now" would quietly drop them.
   */
  private static final String FOR_COUNTRY =
      RETURN_COLUMNS
          + " WHERE (scope_kind = 'COUNTRY' AND scope = upper(?))"
          + "    OR scope_kind = 'REGIME'"
          + " ORDER BY code, effective_from";

  public List<Return> candidatesFor(String country) {
    return query(
        FOR_COUNTRY,
        ps -> ps.setString(1, country),
        StatutoryRepository::readReturn,
        "statutory returns for a country");
  }

  public Optional<Return> byCode(String code, LocalDate onDate) {
    return query(
            RETURN_COLUMNS
                + " WHERE code = ? AND effective_from <= ?"
                + " AND (effective_to IS NULL OR effective_to >= ?)"
                + " ORDER BY effective_from DESC LIMIT 1",
            ps -> {
              ps.setString(1, code);
              ps.setObject(2, onDate);
              ps.setObject(3, onDate);
            },
            StatutoryRepository::readReturn,
            "statutory return by code")
        .stream()
        .findFirst();
  }

  // ── the filings ─────────────────────────────────────────────────────────────

  private static final String FILING_COLUMNS =
      "SELECT id, tenant_id, return_code, period_start, period_end, filed_at, filed_by, reference,"
          + " provider, payload_digest, supersedes, superseded_by, note FROM statutory_filings";

  /** Everything a business has filed, newest period first, superseded rows included. */
  private static final String FILINGS_OF =
      FILING_COLUMNS + " WHERE tenant_id = ? ORDER BY period_start DESC, filed_at DESC";

  /**
   * The filing that stands for one period, if there is one.
   *
   * <p>Keyed on {@code superseded_by IS NULL}: the filing that stands is the one nothing has
   * corrected. The unique index says the same thing, so there is at most one.
   */
  private static final String STANDING =
      FILING_COLUMNS
          + " WHERE tenant_id = ? AND return_code = ? AND period_start = ?"
          + " AND superseded_by IS NULL";

  private static final String INSERT_FILING =
      "INSERT INTO statutory_filings (id, tenant_id, return_code, period_start, period_end,"
          + " filed_at, filed_by, reference, provider, payload_digest, supersedes, superseded_by,"
          + " note, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)";

  /** Points the corrected filing at its correction, which is what makes "stands" an index. */
  private static final String MARK_SUPERSEDED =
      "UPDATE statutory_filings SET superseded_by = ? WHERE id = ? AND superseded_by IS NULL";

  public List<Filing> filingsOf(UUID tenantId) {
    return query(
        FILINGS_OF, ps -> ps.setObject(1, tenantId), StatutoryRepository::readFiling, "filings");
  }

  public Optional<Filing> standingFor(UUID tenantId, String code, LocalDate periodStart) {
    return query(
            STANDING,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, code);
              ps.setObject(3, periodStart);
            },
            StatutoryRepository::readFiling,
            "the filing that stands")
        .stream()
        .findFirst();
  }

  /**
   * Records a filing, and points its predecessor at it when it corrects one.
   *
   * <p>One transaction, because the two halves are one fact: a correction that landed without its
   * predecessor being marked would leave two filings standing for the same period, and the unique
   * index would refuse the second — so the write would fail rather than mislead, but it would fail
   * for a reason nobody could read.
   *
   * @param supersedes the filing this one corrects, or null
   * @return the filing as written
   */
  public Filing file(Filing filing, UUID supersedes) {
    return inTx(
        c -> {
          if (supersedes != null) {
            try (PreparedStatement ps = c.prepareStatement(MARK_SUPERSEDED)) {
              ps.setObject(1, filing.id());
              ps.setObject(2, supersedes);
              if (ps.executeUpdate() != 1) {
                throw com.storeql.web.ApiException.conflict(
                    "STATUTORY_FILING_ALREADY_CORRECTED",
                    "That filing has already been corrected by a later one");
              }
            }
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_FILING)) {
            ps.setObject(1, filing.id());
            ps.setObject(2, filing.tenantId());
            ps.setString(3, filing.returnCode());
            ps.setObject(4, filing.periodStart());
            ps.setObject(5, filing.periodEnd());
            ps.setObject(6, filing.filedAt().atOffset(ZoneOffset.UTC));
            ps.setObject(7, filing.filedBy());
            ps.setString(8, filing.reference());
            ps.setString(9, filing.provider());
            ps.setString(10, filing.payloadDigest());
            ps.setObject(11, supersedes);
            ps.setString(12, filing.note());
            ps.setObject(13, Instant.now().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return filing;
        },
        "record a statutory filing");
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Return readReturn(ResultSet rs) throws SQLException {
    return new Return(
        rs.getString("code"),
        rs.getString("scope_kind"),
        rs.getString("scope"),
        rs.getString("name"),
        rs.getString("frequency"),
        rs.getString("due_after"),
        rs.getString("export_service"),
        rs.getString("export_path"),
        rs.getString("citation"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("effective_to", LocalDate.class));
  }

  private static Filing readFiling(ResultSet rs) throws SQLException {
    OffsetDateTime filedAt = rs.getObject("filed_at", OffsetDateTime.class);
    return new Filing(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("return_code"),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        filedAt == null ? null : filedAt.toInstant(),
        rs.getObject("filed_by", UUID.class),
        rs.getString("reference"),
        rs.getString("provider"),
        rs.getString("payload_digest"),
        rs.getObject("supersedes", UUID.class),
        rs.getObject("superseded_by", UUID.class),
        rs.getString("note"));
  }
}
