package com.storeql.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An event of a kind payment-svc began acting on only lately (a void) is history when it was
 * announced before then: its id, a UUIDv7, says when it was made.
 */
class EventCutoffTest {

  /** A UUIDv7 minted at {@code at}, as an event announced then would carry. */
  static UUID mintedAt(Instant at) {
    String millis = String.format("%012x", at.toEpochMilli());
    return Ids.parse(
        millis.substring(0, 8)
            + "-"
            + millis.substring(8)
            + "-"
            + Ids.newId().toString().substring(14));
  }

  private static final Instant SINCE = Instant.parse("2026-10-02T09:15:30.123456Z");

  @Test
  @DisplayName("A time-ordered id says when it was made, to the millisecond")
  void whenAnIdWasMade() {
    Instant at = Instant.parse("2026-09-30T23:59:59.987Z");
    assertEquals(at, EventCutoff.mintedAt(mintedAt(at)));
    Instant before = Instant.now();
    Instant made = EventCutoff.mintedAt(Ids.newId());
    assertFalse(made.isBefore(before.minusMillis(1)), made + " is before " + before);
  }

  @Test
  @DisplayName(
      "An event made before payment-svc began acting on its kind is history; one made in the same"
          + " millisecond or after is not")
  void historyIsWhatCameBefore() {
    assertTrue(EventCutoff.predates(mintedAt(SINCE.minus(Duration.ofDays(3))), SINCE));
    assertTrue(EventCutoff.predates(mintedAt(SINCE.minusMillis(1)), SINCE));
    assertFalse(
        EventCutoff.predates(mintedAt(SINCE), SINCE),
        "an id keeps only the millisecond: the same millisecond is not before");
    assertFalse(EventCutoff.predates(mintedAt(SINCE.plusMillis(1)), SINCE));
    assertFalse(EventCutoff.predates(Ids.newId(), Instant.now().minusSeconds(60)));
  }

  @Test
  @DisplayName("An event with no id cannot say when it was made, so it is history")
  void noIdIsHistory() {
    assertTrue(EventCutoff.predates(null, SINCE));
  }
}
