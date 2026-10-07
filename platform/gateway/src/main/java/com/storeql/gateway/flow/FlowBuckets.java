package com.storeql.gateway.flow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The time buckets counters are kept in, and the Redis keys that name them. The whole key layout is
 * here so it can be read, tested and changed in one place:
 *
 * <ul>
 *   <li>{@code flow:m:<tenant>:<minute>} — a hash of {@code <group>|<outcome>} to a count, for one
 *       minute (whole minutes since the epoch); kept a day.
 *   <li>{@code flow:h:<tenant>:<hour>} — the same for one hour; kept a week.
 *   <li>{@code flow:f:<tenant>} — a sorted set of the last day's failures, score the moment in
 *       epoch microseconds, member the failure as JSON; capped, kept a day.
 * </ul>
 *
 * <p>Traffic that belongs to no business is counted under {@link #UNATTRIBUTED} in the first two,
 * for the operator; no read ever asks for it.
 */
public final class FlowBuckets {

  private FlowBuckets() {}

  /** The tenant part of a key for traffic that belongs to no business. Never a UUID. */
  public static final String UNATTRIBUTED = "-";

  /** Minutes the screen draws, and the most the per-minute counters are read back over. */
  public static final int MINUTES_SHOWN = 60;

  /** Hours the screen draws. */
  public static final int HOURS_SHOWN = 24;

  /** A minute's counters live a day after their last write. */
  public static final long MINUTE_TTL_SECONDS = 24L * 3600;

  /** An hour's counters live a week after their last write. */
  public static final long HOUR_TTL_SECONDS = 7L * 24 * 3600;

  /** A failure is shown for a day, and the set it is in lives a day after its last write. */
  public static final long FAILURE_TTL_SECONDS = 24L * 3600;

  /**
   * @param at a moment
   * @return the whole minutes since the epoch it falls in
   */
  public static long minuteOf(Instant at) {
    return Math.floorDiv(at.getEpochSecond(), 60L);
  }

  /**
   * @param at a moment
   * @return the whole hours since the epoch it falls in
   */
  public static long hourOf(Instant at) {
    return Math.floorDiv(at.getEpochSecond(), 3600L);
  }

  /**
   * @param minute a minute number
   * @return the moment it starts
   */
  public static Instant startOfMinute(long minute) {
    return Instant.ofEpochSecond(minute * 60L);
  }

  /**
   * @param hour an hour number
   * @return the moment it starts
   */
  public static Instant startOfHour(long hour) {
    return Instant.ofEpochSecond(hour * 3600L);
  }

  /**
   * @param now the moment the screen is drawn for
   * @return the last {@link #MINUTES_SHOWN} minute numbers, oldest first, the last being now's
   */
  public static List<Long> lastMinutes(Instant now) {
    return last(minuteOf(now), MINUTES_SHOWN);
  }

  /**
   * @param now the moment the screen is drawn for
   * @return the last {@link #HOURS_SHOWN} hour numbers, oldest first, the last being now's
   */
  public static List<Long> lastHours(Instant now) {
    return last(hourOf(now), HOURS_SHOWN);
  }

  private static List<Long> last(long current, int count) {
    List<Long> out = new ArrayList<>(count);
    for (int i = count - 1; i >= 0; i--) out.add(current - i);
    return out;
  }

  /**
   * @param tenant a business id, or {@link #UNATTRIBUTED}
   * @param minute a minute number
   * @return the key of that minute's counters
   */
  public static String minuteKey(String tenant, long minute) {
    return "flow:m:" + tenant + ":" + minute;
  }

  /**
   * @param tenant a business id, or {@link #UNATTRIBUTED}
   * @param hour an hour number
   * @return the key of that hour's counters
   */
  public static String hourKey(String tenant, long hour) {
    return "flow:h:" + tenant + ":" + hour;
  }

  /**
   * @param tenant a business id
   * @return the key of that business's recent failures
   */
  public static String failuresKey(String tenant) {
    return "flow:f:" + tenant;
  }

  /**
   * @param group a route group
   * @param outcome how the request ended
   * @return the counter's field name
   */
  public static String field(String group, FlowOutcome outcome) {
    return group + "|" + outcome.label();
  }
}
