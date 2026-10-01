package com.storeql.events.contract;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Ledger month: a month was closed (a re-close raises the version). Consumers: inventory-svc closes
 * each store's valuation period; reporting-svc snapshots the month's sales days. Producer:
 * purchase-svc (intent/accounting-periods.md). {@code aggregateId} is the ledger period's id.
 * Fields are those of {@link LedgerPeriodEvent.Read}.
 */
public final class LedgerPeriodClosed {

  public static final String TYPE = "LedgerPeriodClosed";
  public static final String TOPIC = "storeql.purchase.ledger-period-closed";

  public static final String MONTH = LedgerPeriodEvent.MONTH;
  public static final String ZONE = LedgerPeriodEvent.ZONE;
  public static final String VERSION = LedgerPeriodEvent.VERSION;
  public static final String BY = LedgerPeriodEvent.BY;
  public static final String APPROVAL_ID = LedgerPeriodEvent.APPROVAL_ID;

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, LedgerPeriodClosed::read);

  private LedgerPeriodClosed() {}

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
