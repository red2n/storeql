package com.storeql.events.contract;

import com.storeql.ids.Ids;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue of events whose payload is defined once here. Adding an event class means adding
 * its {@code CONTRACT} to {@link #ALL}; {@code EventContractsTest} fails when a class with a {@code
 * TYPE} is missing from it or has no round-trip sample.
 */
public final class EventContracts {

  private EventContracts() {}

  public static final List<EventContract> ALL =
      List.of(
          ExceptionAlertRaised.CONTRACT,
          ExceptionRuleChanged.CONTRACT,
          ApprovalRequested.CONTRACT,
          ApprovalDecided.CONTRACT,
          ApprovalExpired.CONTRACT,
          AuthorityRuleChanged.CONTRACT,
          SensitiveReportRead.CONTRACT,
          SubjectErasureCompleted.CONTRACT,
          LedgerPeriodClosed.CONTRACT,
          LedgerPeriodReopened.CONTRACT,
          LedgerPeriodLocked.CONTRACT,
          VariantHandlingSet.CONTRACT,
          ZoneStatusChanged.CONTRACT,
          CustomersMerged.CONTRACT,
          PlatformActionRecorded.CONTRACT,
          GiftCardLoadReversed.CONTRACT);

  public static Optional<EventContract> byType(String type) {
    return ALL.stream().filter(c -> c.type().equals(type)).findFirst();
  }

  /**
   * The payload's {@code eventId} when it is a version-7 UUID, else empty (not JSON, no member, or
   * another version). For tests and for a consumer that must drop an event that has none.
   */
  public static Optional<UUID> eventIdOf(String json) {
    try (var r = EventReader.JSON.createReader(new StringReader(json))) {
      JsonObject o = r.readObject();
      if (!o.containsKey("eventId") || o.isNull("eventId")) {
        return Optional.empty();
      }
      return Optional.of(Ids.parse(o.getString("eventId")));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
