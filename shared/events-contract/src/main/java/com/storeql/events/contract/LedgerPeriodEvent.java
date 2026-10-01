package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** The one definition of the three ledger-month events (intent/accounting-periods.md). */
public final class LedgerPeriodEvent {

  static final String MONTH = "month";
  static final String ZONE = "zone";
  static final String VERSION = "version";
  static final String BY = "by";
  static final String APPROVAL_ID = "approvalId";

  private LedgerPeriodEvent() {}

  /**
   * What a ledger-month event says.
   *
   * @param month the first day of the month, in the business's zone
   * @param zone the zone the month was judged in
   * @param version the close's version; a reopen carries the version it reopens
   * @param by the user who did it; empty when the platform did (a VAT filing locks)
   * @param approvalId the approval that allowed a reopen
   */
  public record Read(
      Envelope envelope,
      LocalDate month,
      String zone,
      long version,
      Optional<UUID> by,
      Optional<UUID> approvalId) {}

  static String payload(
      String type,
      UUID tenantId,
      UUID periodId,
      LocalDate month,
      String zone,
      long version,
      UUID by,
      UUID approvalId) {
    return new JsonFields(EventPayload.base(type, tenantId, periodId))
        .date(MONTH, month)
        .str(ZONE, zone)
        .integer(VERSION, version)
        .optUuid(BY, by)
        .optUuid(APPROVAL_ID, approvalId)
        .close();
  }

  static Read read(String json, String type) {
    EventReader r = EventReader.open(json, type);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.date(MONTH),
        r.string(ZONE),
        r.integer(VERSION),
        r.optUuid(BY),
        r.optUuid(APPROVAL_ID));
  }
}
