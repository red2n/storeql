package com.storeql.order.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The handover of a picked online order (ship-from-store and dark-store picking): a delivery
 * dispatched to a carrier, or a pickup collected by its shopper. Recorded once per order, after the
 * order is FULFILLED — picked and packed — and never changed.
 *
 * @param kind {@link #KIND_DISPATCHED} or {@link #KIND_COLLECTED}
 * @param carrier the carrier's name, for a dispatch; null for a collection
 * @param reference the carrier's reference or tracking number, when it gave one
 * @param parcels how many parcels left, when the store counted them
 * @param collectedBy who took a collection, when staff noted it
 * @param handedBy the member of staff who recorded the handover
 */
public record Handover(
    UUID id,
    UUID tenantId,
    UUID orderId,
    UUID storeId,
    String kind,
    String carrier,
    String reference,
    Integer parcels,
    String collectedBy,
    UUID handedBy,
    Instant handedAt) {

  public static final String KIND_DISPATCHED = "DISPATCHED";
  public static final String KIND_COLLECTED = "COLLECTED";
}
