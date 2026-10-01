package com.storeql.events.contract;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The five members every event carries. {@code tenantId} is empty only for a platform-scope event.
 */
public record Envelope(
    UUID eventId, String eventType, Optional<UUID> tenantId, UUID aggregateId, Instant occurredAt) {

  /** The business, for an event whose contract says it always names one. */
  public UUID requireTenant() {
    return tenantId.orElseThrow(() -> new IllegalArgumentException(eventType + " has no tenantId"));
  }
}
