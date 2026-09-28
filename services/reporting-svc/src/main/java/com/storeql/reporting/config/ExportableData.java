package com.storeql.reporting.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * What reporting-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): the
 * reporting projections built from other services' events. Every table and column of the reporting
 * schema is exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "reporting";
  }

  @Override
  public Set<String> derivedTables() {
    return Set.of(
        "inventory_projection",
        "movement_events",
        "open_supply_lines",
        "sales_facts",
        "sales_line_facts",
        "sales_voids",
        "catalogue_products",
        "catalogue_variants");
  }
}
