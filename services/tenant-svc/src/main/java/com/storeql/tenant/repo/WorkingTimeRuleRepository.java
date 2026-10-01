package com.storeql.tenant.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.WorkingTime.Rule;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.List;

/**
 * The working-time rules (V41): platform reference data, so nothing here filters by tenant — no row
 * belongs to one. Found the way legal obligations are: a rule made for a regime reaches a country
 * while it is a member, with the window narrowed to the membership.
 */
@ApplicationScoped
public class WorkingTimeRuleRepository extends BaseJdbcRepository {

  /**
   * Every rule that reaches {@code country} at some time. A window that closes before it opens (a
   * membership that ended first) is dropped here.
   */
  public List<Rule> forCountry(String country) {
    return query(
            "SELECT r.code, r.rule_value, r.unit, r.severity, r.scope, r.citation,"
                + " GREATEST(r.effective_from, COALESCE(m.member_from, r.effective_from)) AS eff_from,"
                + " CASE WHEN m.member_to IS NULL THEN r.effective_to"
                + "      WHEN r.effective_to IS NULL THEN m.member_to"
                + "      ELSE LEAST(r.effective_to, m.member_to) END AS eff_to"
                + " FROM working_time_rules r"
                + " LEFT JOIN jurisdiction_members m"
                + "   ON r.scope_kind = 'REGIME' AND m.regime_code = r.scope AND m.country = ?"
                + " WHERE r.applies_to = 'ALL'"
                + "   AND ((r.scope_kind = 'COUNTRY' AND r.scope = ?)"
                + "     OR (r.scope_kind = 'REGIME' AND m.country IS NOT NULL))"
                + " ORDER BY eff_from, r.code",
            ps -> {
              ps.setString(1, country);
              ps.setString(2, country);
            },
            rs ->
                new Rule(
                    rs.getString("code"),
                    rs.getBigDecimal("rule_value"),
                    rs.getString("unit"),
                    rs.getString("severity"),
                    rs.getString("scope"),
                    rs.getObject("eff_from", LocalDate.class),
                    rs.getObject("eff_to", LocalDate.class),
                    rs.getString("citation")),
            "working-time rules for a country")
        .stream()
        .filter(r -> r.effectiveTo() == null || !r.effectiveTo().isBefore(r.effectiveFrom()))
        .toList();
  }
}
