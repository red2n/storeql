package com.storeql.tenant.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * What tenant-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): the business,
 * its stores, zones, staff assignments, roles, retention schedule and the notices it was sent.
 * Every table and column of the tenant schema is exported except what is named here, with the
 * reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "tenant";
  }

  @Override
  public Map<String, String> excludedTables() {
    return Map.ofEntries(
        Map.entry(
            "incident_reporting_stages",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "breach_duties",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "cash_limits", "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "deposit_schemes",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "plans", "the platform's price list: the same for every business, and not theirs"),
        Map.entry(
            "plan_prices",
            "the platform's price list: the same for every business, and not theirs"),
        Map.entry(
            "plan_entitlements",
            "the platform's price list: the same for every business, and not theirs"),
        Map.entry(
            "plan_meters",
            "the platform's price list: the same for every business, and not theirs"),
        Map.entry(
            "plan_meter_prices",
            "the platform's price list: the same for every business, and not theirs"),
        Map.entry(
            "platform_billing_profile",
            "the platform's own identity as a seller: its record, not the business's"),
        Map.entry(
            "platform_vat_rates",
            "the platform's reference data: the rates it charges, the same for every business"),
        Map.entry(
            "billing_invoice_numbers",
            "the platform's invoice counter: a sequence, and nobody's data"),
        Map.entry(
            "dunning_policy",
            "the platform's own tolerance for late payment: the same for every business"),
        Map.entry(
            "statutory_returns",
            "the platform's reference data: what each jurisdiction requires, the same for every"
                + " business in it"),
        Map.entry(
            "jurisdiction_members",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "jurisdiction_regimes",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "legal_obligations",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "working_time_rules",
            "the platform's reference data: the working-time law as recorded for every business"),
        Map.entry(
            "retention_classes",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "retention_floors",
            "the platform's reference data: the law as recorded for every business"),
        Map.entry(
            "security_incident_events",
            "the platform's own incident register; what the business was told is in security_notices"),
        Map.entry(
            "security_incidents",
            "the platform's own incident register; what the business was told is in security_notices"));
  }

  @Override
  public Map<String, String> tenantPredicates() {
    return Map.of("tenants", "id = ?");
  }

  @Override
  public Map<String, String> importSkipped() {
    return Map.of(
        "security_incident_tenants",
        "links to the platform's incident register, which is not ported",
        "tenant_erasure_evidence",
        "the evidence of the leaving business's erasure, never the importing one's",
        "tenant_switches",
        "the leaving business's notice; imported, it would put the importing business on notice",
        "tenants",
        "the destination business has its own record, made when it signs up");
  }

  @Override
  public Map<String, String> keptAtErasure() {
    return Map.of(
        "tenant_erasure_evidence",
        "what each service erased, kept as the platform's record of the erasure",
        "tenant_switches",
        "the notice and its dates, kept as the platform's record that the business left",
        "tenants",
        "the business's name and status, kept as the record that it was a customer and left");
  }
}
