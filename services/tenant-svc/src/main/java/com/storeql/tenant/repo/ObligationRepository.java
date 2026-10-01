package com.storeql.tenant.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Domain.CashLimit;
import com.storeql.tenant.domain.Domain.DepositScheme;
import com.storeql.tenant.domain.Domain.LegalObligation;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.List;

/**
 * The jurisdiction reference data (V9): which obligations reach a country, directly or through a
 * regime it belongs to. Platform-wide, so nothing here filters by tenant — no row belongs to one.
 */
@ApplicationScoped
public class ObligationRepository extends BaseJdbcRepository {

  /**
   * Every obligation that reaches {@code country} at some time: its own, and each regime's with the
   * window narrowed to the membership. A window that closes before it opens is returned as such,
   * for the caller to drop.
   */
  /** The deposit return schemes that reach a country, its own and its regimes' (09.16). */
  public List<DepositScheme> depositSchemesFor(String country) {
    return query(
        "SELECT d.scope_kind, d.scope, d.currency, d.deposit_each, d.materials, d.min_volume_ml,"
            + " d.max_volume_ml, d.vat_treatment, d.citation, d.summary,"
            + " GREATEST(d.effective_from, COALESCE(m.member_from, d.effective_from)) AS eff_from,"
            + " CASE WHEN m.member_to IS NULL THEN d.effective_to"
            + "      WHEN d.effective_to IS NULL THEN m.member_to"
            + "      ELSE LEAST(d.effective_to, m.member_to) END AS eff_to"
            + " FROM deposit_schemes d"
            + " LEFT JOIN jurisdiction_members m"
            + "   ON d.scope_kind = 'REGIME' AND m.regime_code = d.scope AND m.country = ?"
            + " WHERE (d.scope_kind = 'COUNTRY' AND d.scope = ?)"
            + "    OR (d.scope_kind = 'REGIME' AND m.country IS NOT NULL)"
            + " ORDER BY eff_from",
        ps -> {
          ps.setString(1, country);
          ps.setString(2, country);
        },
        rs ->
            new DepositScheme(
                rs.getString("scope_kind"),
                rs.getString("scope"),
                rs.getString("currency"),
                rs.getBigDecimal("deposit_each"),
                List.of(rs.getString("materials").split(",")),
                rs.getInt("min_volume_ml"),
                rs.getInt("max_volume_ml"),
                rs.getString("vat_treatment"),
                rs.getObject("eff_from", java.time.LocalDate.class),
                rs.getObject("eff_to", java.time.LocalDate.class),
                rs.getString("citation"),
                rs.getString("summary")),
        "deposit schemes for a country");
  }

  /** The cash limits that reach a country, its own and its regimes' (09.17). */
  public List<CashLimit> cashLimitsFor(String country) {
    return query(
        "SELECT l.scope_kind, l.scope, l.currency, l.from_amount, l.citation, l.summary,"
            + " GREATEST(l.effective_from, COALESCE(m.member_from, l.effective_from)) AS eff_from,"
            + " CASE WHEN m.member_to IS NULL THEN l.effective_to"
            + "      WHEN l.effective_to IS NULL THEN m.member_to"
            + "      ELSE LEAST(l.effective_to, m.member_to) END AS eff_to"
            + " FROM cash_limits l"
            + " LEFT JOIN jurisdiction_members m"
            + "   ON l.scope_kind = 'REGIME' AND m.regime_code = l.scope AND m.country = ?"
            + " WHERE (l.scope_kind = 'COUNTRY' AND l.scope = ?)"
            + "    OR (l.scope_kind = 'REGIME' AND m.country IS NOT NULL)"
            + " ORDER BY eff_from, l.from_amount",
        ps -> {
          ps.setString(1, country);
          ps.setString(2, country);
        },
        rs ->
            new CashLimit(
                rs.getString("scope_kind"),
                rs.getString("scope"),
                rs.getString("currency"),
                rs.getBigDecimal("from_amount"),
                rs.getObject("eff_from", java.time.LocalDate.class),
                rs.getObject("eff_to", java.time.LocalDate.class),
                rs.getString("citation"),
                rs.getString("summary")),
        "cash limits for a country");
  }

  public List<LegalObligation> forCountry(String country) {
    return query(
        "SELECT o.code, o.scope_kind, o.scope, o.citation, o.summary, o.limit_value, o.limit_unit,"
            + " o.qualifier,"
            + " GREATEST(o.effective_from, COALESCE(m.member_from, o.effective_from)) AS eff_from,"
            + " CASE WHEN m.member_to IS NULL THEN o.effective_to"
            + "      WHEN o.effective_to IS NULL THEN m.member_to"
            + "      ELSE LEAST(o.effective_to, m.member_to) END AS eff_to"
            + " FROM legal_obligations o"
            + " LEFT JOIN jurisdiction_members m"
            + "   ON o.scope_kind = 'REGIME' AND m.regime_code = o.scope AND m.country = ?"
            + " WHERE (o.scope_kind = 'COUNTRY' AND o.scope = ?)"
            + "    OR (o.scope_kind = 'REGIME' AND m.country IS NOT NULL)"
            + " ORDER BY eff_from, o.code",
        ps -> {
          ps.setString(1, country);
          ps.setString(2, country);
        },
        rs ->
            new LegalObligation(
                rs.getString("code"),
                rs.getString("scope_kind"),
                rs.getString("scope"),
                rs.getObject("eff_from", LocalDate.class),
                rs.getObject("eff_to", LocalDate.class),
                rs.getString("citation"),
                rs.getString("summary"),
                rs.getBigDecimal("limit_value"),
                rs.getString("limit_unit"),
                rs.getString("qualifier")),
        "legal obligations for a country");
  }

  /**
   * Whether a country belonged to a regime on a date (21.9).
   *
   * <p>The same question the three statements above ask as a join, asked directly, because the
   * platform's own invoice needs it as a yes or no: whether the business it is billing was in the
   * EU decides the VAT treatment, and membership has dates. The United Kingdom was a member until
   * 31 January 2020, so an invoice dated before that and one dated after it are taxed differently
   * for the same business — which is why this takes a day and not just a country.
   *
   * @param regime the regime code, e.g. {@code "EU"}
   * @param country ISO 3166-1 alpha-2; a null or blank country belongs to nothing
   * @param day the date the question is asked of
   * @return true when a membership window covers that day
   */
  public boolean memberOn(String regime, String country, LocalDate day) {
    if (country == null || country.isBlank()) return false;
    return !query(
            "SELECT 1 FROM jurisdiction_members WHERE regime_code = ? AND country = upper(?)"
                + " AND member_from <= ? AND (member_to IS NULL OR member_to >= ?) LIMIT 1",
            ps -> {
              ps.setString(1, regime);
              ps.setString(2, country.strip());
              ps.setObject(3, day);
              ps.setObject(4, day);
            },
            rs -> Boolean.TRUE,
            "regime membership on a day")
        .isEmpty();
  }
}
