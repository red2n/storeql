package com.storeql.order.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * What order-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): orders,
 * returns, gift cards, receipts, the fiscal record and age checks. Every table and column of the
 * order schema is exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "order";
  }

  @Override
  public Map<String, String> excludedColumns() {
    return Map.of(
        "tse_devices.private_key",
        "a fiscal device's signing key: a credential, provisioned again at the destination");
  }

  @Override
  public Map<String, String> importSkipped() {
    return Map.of(
        "tenant_status", "the destination business's own status is kept, set when it signs up");
  }

  @Override
  public Set<String> derivedTables() {
    return Set.of("store_status", "tenant_status");
  }
}
