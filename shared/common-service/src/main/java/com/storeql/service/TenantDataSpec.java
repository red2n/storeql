package com.storeql.service;

import java.util.Map;
import java.util.Set;

/**
 * What one service holds for a tenant, and how it leaves (21.14, EU Data Act (EU) 2023/2854
 * arts.25–26): the service's schema, the tables and columns kept out of a tenant's export with the
 * reason the register gives, how a table with no {@code tenant_id} column is tied to its tenant,
 * and the tables an import does not load.
 *
 * <p>Everything else in the schema is exported. The catalog is read from the database, not written
 * out here, so a table or column is carried the day it is migrated; one that cannot be tied to a
 * tenant, or an exclusion that names something the schema no longer has, fails the catalog rather
 * than leaving data out quietly (art.25(2)(e): an exhaustive specification of what can be ported).
 *
 * <p>A service subclasses this as an {@code @ApplicationScoped} bean. The strings are code
 * constants; the tenant predicates are the only SQL here, and each takes the tenant as its one
 * parameter.
 */
public abstract class TenantDataSpec {

  /** Left out for every service: they record how events and migrations moved, not the tenant. */
  public static final Map<String, String> INFRASTRUCTURE =
      Map.of(
          "flyway_schema_history", "the service's migration history, not the tenant's data",
          "outbox", "events waiting to be published: delivery machinery, not data",
          "processed_events", "which events a consumer has handled: delivery machinery, not data");

  /**
   * The service's own schema.
   *
   * @return the schema name, as its role's search path sets it
   */
  public abstract String schema();

  /**
   * Tables not exported, beyond {@link #INFRASTRUCTURE}.
   *
   * @return table name to the reason
   */
  public Map<String, String> excludedTables() {
    return Map.of();
  }

  /**
   * Columns not exported: credentials, and secrets that would let an export act as the tenant.
   *
   * @return {@code table.column} to the reason
   */
  public Map<String, String> excludedColumns() {
    return Map.of();
  }

  /**
   * How a table with no {@code tenant_id} column is tied to its tenant.
   *
   * @return table name to a predicate over the table's own columns with one {@code ?} for the
   *     tenant, e.g. {@code user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = ?)}
   */
  public Map<String, String> tenantPredicates() {
    return Map.of();
  }

  /**
   * How a table with no {@code tenant_id} column is tied to its tenant for erasure: login tokens
   * hanging off a staff user, say, left out of the export. A left-out table with a {@code
   * tenant_id} is erased by it without being named here; platform reference data, with neither, is
   * not the tenant's and is not erased. An exported table named here is erased by this predicate
   * rather than its export one — iam-svc's sandbox pair is the live business's to export and goes
   * with either business when erased; named for a table with a {@code tenant_id}, it is a problem.
   *
   * @return table name to a predicate with one {@code ?} for the tenant
   */
  public Map<String, String> erasurePredicates() {
    return Map.of();
  }

  /**
   * Tables exported but not loaded by an import.
   *
   * @return table name to the reason
   */
  public Map<String, String> importSkipped() {
    return Map.of();
  }

  /**
   * Tables exported but kept when the business's data is erased: the record that the business
   * existed, gave notice and was erased, which the platform keeps as the other party to the
   * contract. Their rows are named here with the reason, so the erasure evidence says what stayed.
   *
   * @return table name to the reason
   */
  public Map<String, String> keptAtErasure() {
    return Map.of();
  }

  /**
   * Projections rebuilt from other services' events: exported as output data and marked derived.
   *
   * @return table names
   */
  public Set<String> derivedTables() {
    return Set.of();
  }
}
