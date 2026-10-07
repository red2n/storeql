package com.storeql.gateway.flow;

import com.storeql.gateway.flow.SystemHealthDtos.Counts;
import com.storeql.gateway.flow.SystemHealthDtos.GroupCount;
import com.storeql.gateway.flow.SystemHealthDtos.Point;
import com.storeql.gateway.flow.SystemHealthDtos.Summary;
import com.storeql.gateway.flow.SystemHealthDtos.Windows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The summary's arithmetic, apart from Redis: counters in, the numbers the screen draws out. A span
 * is whole buckets ending with the one now, so "the last hour" is the last sixty minute buckets
 * (between 59 and 60 minutes) and "the last 24 hours" the last twenty-four hour buckets; the three
 * spans, the by-group list and both series are sums of the same buckets and always agree.
 */
public final class HealthSummary {

  private static final int FIVE_MINUTES = 5;

  private HealthSummary() {}

  /** What a bucket, or a span of them, adds up to. */
  private static final class Tally {
    long ok;
    long client;
    long failed;

    long total() {
      return ok + client + failed;
    }

    void inc(FlowOutcome outcome, long count) {
      switch (outcome) {
        case OK -> ok += count;
        case CLIENT -> client += count;
        case FAILED -> failed += count;
      }
    }

    /**
     * Adds a bucket's counters; a field that is not {@code group|outcome} or not a count is left
     * out.
     */
    void add(Map<String, Long> bucket, Map<String, Tally> groups) {
      for (Map.Entry<String, Long> field : bucket.entrySet()) {
        String name = field.getKey();
        int bar = name.lastIndexOf('|');
        long count = field.getValue() == null ? 0 : field.getValue();
        FlowOutcome outcome =
            bar <= 0 ? null : FlowOutcome.ofLabel(name.substring(bar + 1)).orElse(null);
        if (outcome == null || count <= 0) continue;
        inc(outcome, count);
        if (groups != null) {
          groups.computeIfAbsent(name.substring(0, bar), k -> new Tally()).inc(outcome, count);
        }
      }
    }

    Counts counts() {
      long total = total();
      return new Counts(total, ok, failed, client, total == 0 ? null : (double) failed / total);
    }
  }

  /**
   * @param now when the figures were read
   * @param counters the buckets read back
   * @param dropped records the gateway has given up since it started
   * @return the summary
   */
  public static Summary build(Instant now, FlowCounters counters, long dropped) {
    List<Long> minuteNumbers = FlowBuckets.lastMinutes(now);
    List<Long> hourNumbers = FlowBuckets.lastHours(now);

    Tally lastFive = new Tally();
    Tally lastHour = new Tally();
    Map<String, Tally> groups = new HashMap<>();
    List<Point> perMinute = new ArrayList<>(minuteNumbers.size());
    for (int i = 0; i < minuteNumbers.size(); i++) {
      Tally bucket = new Tally();
      bucket.add(at(counters.minutes(), i), null);
      lastHour.add(at(counters.minutes(), i), groups);
      if (i >= minuteNumbers.size() - FIVE_MINUTES) lastFive.add(at(counters.minutes(), i), null);
      perMinute.add(
          new Point(
              FlowBuckets.startOfMinute(minuteNumbers.get(i)), bucket.total(), bucket.failed));
    }

    Tally lastDay = new Tally();
    List<Point> perHour = new ArrayList<>(hourNumbers.size());
    for (int i = 0; i < hourNumbers.size(); i++) {
      Tally bucket = new Tally();
      bucket.add(at(counters.hours(), i), null);
      lastDay.add(at(counters.hours(), i), null);
      perHour.add(
          new Point(FlowBuckets.startOfHour(hourNumbers.get(i)), bucket.total(), bucket.failed));
    }

    List<GroupCount> byGroup =
        groups.entrySet().stream()
            .map(e -> new GroupCount(e.getKey(), e.getValue().total(), e.getValue().failed))
            .sorted(
                Comparator.comparingLong(GroupCount::total)
                    .reversed()
                    .thenComparing(GroupCount::group))
            .toList();

    return new Summary(
        now,
        true,
        dropped,
        new Windows(lastFive.counts(), lastHour.counts(), lastDay.counts()),
        byGroup,
        List.copyOf(perMinute),
        List.copyOf(perHour));
  }

  /**
   * The answer when the counters could not be read: no numbers, and {@code available} false so the
   * screen says the figures are unavailable instead of drawing a quiet system.
   *
   * @param now when the read was tried
   * @param dropped records the gateway has given up since it started
   * @return an empty summary
   */
  public static Summary unavailable(Instant now, long dropped) {
    Counts none = new Tally().counts();
    return new Summary(
        now, false, dropped, new Windows(none, none, none), List.of(), List.of(), List.of());
  }

  private static Map<String, Long> at(List<Map<String, Long>> buckets, int index) {
    return index < buckets.size() ? buckets.get(index) : Map.of();
  }
}
