package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The minute and hour a request falls in, and the keys they are kept under. */
class FlowBucketsTest {

  private static final Instant T = Instant.parse("2026-10-07T10:15:42.987Z");

  @Test
  @DisplayName("A minute is the whole minutes since the epoch, an hour the whole hours")
  void bucketNumbers() {
    assertEquals(T.getEpochSecond() / 60, FlowBuckets.minuteOf(T));
    assertEquals(T.getEpochSecond() / 3600, FlowBuckets.hourOf(T));
    assertEquals(
        FlowBuckets.minuteOf(T), FlowBuckets.minuteOf(Instant.parse("2026-10-07T10:15:00Z")));
    assertEquals(
        FlowBuckets.minuteOf(T) + 1, FlowBuckets.minuteOf(Instant.parse("2026-10-07T10:16:00Z")));
    assertEquals(
        FlowBuckets.minuteOf(T), FlowBuckets.minuteOf(Instant.parse("2026-10-07T10:15:59.999Z")));
    assertEquals(0, FlowBuckets.minuteOf(Instant.EPOCH));
    assertEquals(-1, FlowBuckets.minuteOf(Instant.EPOCH.minusMillis(1)), "floor, not truncation");
  }

  @Test
  @DisplayName("A bucket starts on its boundary")
  void bucketStart() {
    assertEquals(
        Instant.parse("2026-10-07T10:15:00Z"), FlowBuckets.startOfMinute(FlowBuckets.minuteOf(T)));
    assertEquals(
        Instant.parse("2026-10-07T10:00:00Z"), FlowBuckets.startOfHour(FlowBuckets.hourOf(T)));
  }

  @Test
  @DisplayName("The last sixty minutes, oldest first, end with the minute now")
  void lastMinutes() {
    List<Long> minutes = FlowBuckets.lastMinutes(T);
    assertEquals(60, minutes.size());
    assertEquals(FlowBuckets.minuteOf(T), minutes.get(59));
    assertEquals(FlowBuckets.minuteOf(T) - 59, minutes.get(0));
    for (int i = 1; i < minutes.size(); i++) {
      assertEquals(minutes.get(i - 1) + 1, minutes.get(i));
    }
  }

  @Test
  @DisplayName("The last twenty-four hours, oldest first, end with the hour now")
  void lastHours() {
    List<Long> hours = FlowBuckets.lastHours(T);
    assertEquals(24, hours.size());
    assertEquals(FlowBuckets.hourOf(T), hours.get(23));
    assertEquals(FlowBuckets.hourOf(T) - 23, hours.get(0));
  }

  @Test
  @DisplayName(
      "Counters live in a hash per business and bucket; the failures in one set per business")
  void keyLayout() {
    String tenant = Ids.newId().toString();
    assertEquals("flow:m:" + tenant + ":29683515", FlowBuckets.minuteKey(tenant, 29_683_515L));
    assertEquals("flow:h:" + tenant + ":494725", FlowBuckets.hourKey(tenant, 494_725L));
    assertEquals("flow:f:" + tenant, FlowBuckets.failuresKey(tenant));
  }

  @Test
  @DisplayName("Unattributed traffic has a key a tenant id can never be")
  void unattributedCannotCollideWithATenant() {
    assertEquals("flow:m:-:5", FlowBuckets.minuteKey(FlowBuckets.UNATTRIBUTED, 5));
    assertTrue(FlowBuckets.UNATTRIBUTED.length() < 36, "a tenant id is a 36-character UUIDv7");
  }

  @Test
  @DisplayName("A field is the group and the outcome, in that order, bar-separated")
  void fieldNames() {
    assertEquals("order-svc|ok", FlowBuckets.field("order-svc", FlowOutcome.OK));
    assertEquals("order-svc|client", FlowBuckets.field("order-svc", FlowOutcome.CLIENT));
    assertEquals("payment-svc|failed", FlowBuckets.field("payment-svc", FlowOutcome.FAILED));
  }

  @Test
  @DisplayName("Retention: minutes a day, hours a week, failures a day")
  void retention() {
    assertEquals(24 * 3600, FlowBuckets.MINUTE_TTL_SECONDS);
    assertEquals(7 * 24 * 3600, FlowBuckets.HOUR_TTL_SECONDS);
    assertEquals(24 * 3600, FlowBuckets.FAILURE_TTL_SECONDS);
  }
}
