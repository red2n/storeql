package com.storeql.payment.domain;

import com.storeql.ids.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Whether an event is history to payment-svc: announced before payment-svc began acting on its kind
 * (V18, {@code events_handled_since}). Pure.
 *
 * <p>payment-svc began giving back what a voided sale took long after order-svc began announcing
 * voids. Its consumer group had never read that topic, so on its first deployment it starts at the
 * earliest void Kafka still holds; each has an {@code eventId} new to {@code processed_events}, so
 * the dedupe does not stop it. Those voids were settled by hand when they happened, and acting on
 * them would refund them again today: cash out of a drawer nobody opened, a refund the ledger posts
 * today, a card put back that a person may already have put back. An event's id is a UUIDv7, which
 * carries when it was made, so a void made before payment-svc began acting on voids is history.
 */
public final class EventCutoff {

  private EventCutoff() {}

  /**
   * When a time-ordered id was made, to the millisecond (RFC 9562: the first 48 bits).
   *
   * @throws IllegalArgumentException for an id that is not a UUIDv7, which says no time
   */
  public static Instant mintedAt(UUID id) {
    if (!Ids.isV7(id)) {
      throw new IllegalArgumentException("not a time-ordered (v7) id: " + id);
    }
    return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
  }

  /**
   * Whether an event was made before {@code since}: strictly before its millisecond, because an id
   * keeps no finer time, so an event made in the same millisecond is never taken for history. An
   * event with no time-ordered id cannot say when it was made, and is history.
   */
  public static boolean predates(UUID eventId, Instant since) {
    if (!Ids.isV7(eventId)) return true;
    return mintedAt(eventId).isBefore(since.truncatedTo(ChronoUnit.MILLIS));
  }
}
