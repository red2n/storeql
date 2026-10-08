package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.gateway.flow.SystemHealthDtos.Counts;
import com.storeql.gateway.flow.SystemHealthDtos.Summary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The numbers the screen draws, worked out from the counters read back: pure, no Redis. */
class HealthSummaryTest {

  private static final Instant NOW = Instant.parse("2026-10-07T10:15:42Z");

  /** Sixty empty minutes and twenty-four empty hours, to put counts into. */
  private static final class Counters {
    final List<Map<String, Long>> minutes = new ArrayList<>();
    final List<Map<String, Long>> hours = new ArrayList<>();

    Counters() {
      for (int i = 0; i < FlowBuckets.MINUTES_SHOWN; i++) minutes.add(Map.of());
      for (int i = 0; i < FlowBuckets.HOURS_SHOWN; i++) hours.add(Map.of());
    }

    /** The minute {@code ago} minutes before now (0 is the minute now). */
    Counters minute(int ago, Map<String, Long> fields) {
      minutes.set(FlowBuckets.MINUTES_SHOWN - 1 - ago, fields);
      return this;
    }

    Counters hour(int ago, Map<String, Long> fields) {
      hours.set(FlowBuckets.HOURS_SHOWN - 1 - ago, fields);
      return this;
    }

    FlowCounters get() {
      return new FlowCounters(minutes, hours);
    }
  }

  @Test
  @DisplayName(
      "No traffic: every number is zero, the failure rate is null (not zero), and the series are zero-filled")
  void quietBusiness() {
    Summary s = HealthSummary.build(NOW, new Counters().get(), 0);
    assertTrue(s.available());
    assertEquals(NOW, s.generatedAt());
    for (Counts c :
        new Counts[] {
          s.windows().last5Minutes(), s.windows().lastHour(), s.windows().last24Hours()
        }) {
      assertEquals(0, c.total());
      assertEquals(0, c.succeeded());
      assertEquals(0, c.failed());
      assertEquals(0, c.clientErrors());
      assertNull(c.failureRate(), "nothing was asked, so there is no rate to show");
    }
    assertTrue(s.byGroup().isEmpty());
    assertEquals(60, s.perMinute().size());
    assertEquals(24, s.perHour().size());
    assertTrue(s.perMinute().stream().allMatch(p -> p.total() == 0 && p.failed() == 0));
  }

  @Test
  @DisplayName("A window adds ok, client errors and failures; the total is all three")
  void windowsAddTheClasses() {
    FlowCounters c =
        new Counters()
            .minute(0, Map.of("order-svc|ok", 7L, "order-svc|client", 2L, "order-svc|failed", 1L))
            .minute(1, Map.of("payment-svc|ok", 3L, "payment-svc|failed", 1L))
            .get();
    Counts w = HealthSummary.build(NOW, c, 0).windows().last5Minutes();
    assertEquals(14, w.total());
    assertEquals(10, w.succeeded());
    assertEquals(2, w.clientErrors());
    assertEquals(2, w.failed());
    assertEquals(2.0 / 14.0, w.failureRate(), 1e-12);
  }

  @Test
  @DisplayName(
      "The last five minutes are the five newest buckets, the last hour all sixty, the day the twenty-four hours")
  void windowBoundaries() {
    FlowCounters c =
        new Counters()
            .minute(4, Map.of("a|ok", 1L)) // inside five minutes
            .minute(5, Map.of("a|ok", 10L)) // just outside five, inside the hour
            .minute(59, Map.of("a|ok", 100L)) // the oldest minute of the hour
            .hour(0, Map.of("a|ok", 1000L))
            .hour(23, Map.of("a|failed", 5L))
            .get();
    Summary s = HealthSummary.build(NOW, c, 0);
    assertEquals(1, s.windows().last5Minutes().total());
    assertEquals(111, s.windows().lastHour().total());
    assertEquals(1005, s.windows().last24Hours().total());
    assertEquals(5, s.windows().last24Hours().failed());
  }

  @Test
  @DisplayName(
      "The sparkline series are oldest first, each point stamped with its bucket's start, failures counted apart")
  void seriesAreOldestFirst() {
    FlowCounters c =
        new Counters()
            .minute(0, Map.of("a|ok", 4L, "a|failed", 2L))
            .minute(59, Map.of("a|client", 1L))
            .hour(0, Map.of("a|ok", 9L))
            .get();
    Summary s = HealthSummary.build(NOW, c, 0);
    assertEquals(Instant.parse("2026-10-07T09:16:00Z"), s.perMinute().get(0).at());
    assertEquals(1, s.perMinute().get(0).total());
    assertEquals(0, s.perMinute().get(0).failed());
    assertEquals(Instant.parse("2026-10-07T10:15:00Z"), s.perMinute().get(59).at());
    assertEquals(6, s.perMinute().get(59).total());
    assertEquals(2, s.perMinute().get(59).failed());
    assertEquals(Instant.parse("2026-10-06T11:00:00Z"), s.perHour().get(0).at());
    assertEquals(Instant.parse("2026-10-07T10:00:00Z"), s.perHour().get(23).at());
    assertEquals(9, s.perHour().get(23).total());
    for (int i = 1; i < 60; i++) {
      assertTrue(s.perMinute().get(i).at().isAfter(s.perMinute().get(i - 1).at()));
    }
  }

  @Test
  @DisplayName("By group, over the last hour: biggest first, ties by name, failures counted apart")
  void groupsOfTheLastHour() {
    FlowCounters c =
        new Counters()
            .minute(0, Map.of("order-svc|ok", 5L, "order-svc|failed", 1L, "payment-svc|ok", 6L))
            .minute(30, Map.of("zeta-svc|ok", 2L, "alpha-svc|ok", 2L, "payment-svc|failed", 2L))
            .hour(0, Map.of("ignored-svc|ok", 99L)) // an hour counter is not the last hour's groups
            .get();
    List<SystemHealthDtos.GroupCount> g = HealthSummary.build(NOW, c, 0).byGroup();
    assertEquals(
        List.of("payment-svc", "order-svc", "alpha-svc", "zeta-svc"),
        g.stream().map(SystemHealthDtos.GroupCount::group).toList());
    assertEquals(8, g.get(0).total());
    assertEquals(2, g.get(0).failed());
    assertEquals(6, g.get(1).total());
    assertEquals(1, g.get(1).failed());
  }

  @Test
  @DisplayName(
      "A field the screen does not know is ignored, not counted, so a bad write cannot skew a number")
  void unknownFieldsAreIgnored() {
    FlowCounters c =
        new Counters()
            .minute(0, Map.of("a|ok", 3L, "a|weird", 50L, "no-separator", 9L, "|ok", 4L))
            .get();
    assertEquals(3, HealthSummary.build(NOW, c, 0).windows().last5Minutes().total());
  }

  @Test
  @DisplayName("The count of records the gateway has dropped since it started rides along")
  void droppedRidesAlong() {
    assertEquals(42, HealthSummary.build(NOW, new Counters().get(), 42).droppedSinceStart());
  }

  @Test
  @DisplayName("When the counters cannot be read the answer says so and carries no numbers")
  void unavailable() {
    Summary s = HealthSummary.unavailable(NOW, 7);
    assertFalse(s.available());
    assertEquals(7, s.droppedSinceStart());
    assertEquals(0, s.windows().lastHour().total());
    assertNull(s.windows().lastHour().failureRate());
    assertTrue(s.byGroup().isEmpty());
    assertTrue(s.perMinute().isEmpty());
    assertTrue(s.perHour().isEmpty());
  }
}
