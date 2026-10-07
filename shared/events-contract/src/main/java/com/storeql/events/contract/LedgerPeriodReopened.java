package com.storeql.events.contract;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Ledger month: a closed month was reopened, with the approval that allowed it. Consumers:
 * inventory-svc re-opens its valuation periods; reporting-svc supersedes its snapshot. Producer:
 * purchase-svc (intent/accounting-periods.md). {@code aggregateId} is the ledger period's id.
 * Fields are those of {@link LedgerPeriodEvent.Read}.
 */
public final class LedgerPeriodReopened {

  public static final String TYPE = "LedgerPeriodReopened";
  public static final String TOPIC = "storeql.purchase.ledger-period-reopened";

  public static final String MONTH = LedgerPeriodEvent.MONTH;
  public static final String ZONE = LedgerPeriodEvent.ZONE;
  public static final String VERSION = LedgerPeriodEvent.VERSION;
  public static final String BY = LedgerPeriodEvent.BY;
  public static final String APPROVAL_ID = LedgerPeriodEvent.APPROVAL_ID;

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, LedgerPeriodReopened::read);

  private LedgerPeriodReopened() {}

  public static String payload(
      UUID tenantId,
      UUID periodId,
      LocalDate month,
      String zone,
      long version,
      UUID by,
      UUID approvalId) {
    return LedgerPeriodEvent.payload(
        TYPE, tenantId, periodId, month, zone, version, by, approvalId);
  }

  public static LedgerPeriodEvent.Read read(String json) {
    return LedgerPeriodEvent.read(json, TYPE);
  }
}
