package com.storeql.purchase.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * What purchase-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): suppliers,
 * purchase orders, receipts, invoices, returns, payment runs and the nominal ledger. Every table
 * and column of the purchase schema is exported except what is named here, with the reason the
 * register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "purchase";
  }

  @Override
  public Map<String, String> excludedColumns() {
    return Map.of(
        "accounting_connections.credentials_sealed",
        "the tokens the accounting package issued: a credential; the package is connected again at the destination");
  }

  @Override
  public Map<String, String> tenantPredicates() {
    // A mapping belongs to the connection, and the connection to the business (17.9).
    return Map.of(
        "accounting_account_mappings",
        "connection_id IN (SELECT c.id FROM accounting_connections c WHERE c.tenant_id = ?)");
  }

  @Override
  public Map<String, String> erasurePredicates() {
    return Map.of(
        "accounting_account_mappings",
        "connection_id IN (SELECT c.id FROM accounting_connections c WHERE c.tenant_id = ?)");
  }

  @Override
  public Map<String, String> importSkipped() {
    String reconnected =
        "the accounting package is connected again at the destination, and its journals pushed from there";
    return Map.of(
        "accounting_connections", reconnected,
        "accounting_account_mappings", reconnected,
        "accounting_syncs", reconnected,
        "accounting_sync_attempts", reconnected);
  }
}
