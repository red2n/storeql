package com.storeql.reporting.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * What reporting-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): the
 * reporting projections built from other services' events, each exported as derived output data.
 * Its outbox, processed_events and migration history are left out as {@link
 * TenantDataSpec#INFRASTRUCTURE} says; this class names no other exclusion.
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
        "labour_facts",
        "sales_facts",
        "sales_line_facts",
        "sales_voids",
        "catalogue_products",
        "catalogue_variants");
  }
}
