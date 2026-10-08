package com.storeql.gateway.flow;

import java.util.List;
import java.util.Map;

/**
 * The counters of one business as read back: a map of {@code <group>|<outcome>} to a count for each
 * of the last sixty minutes and the last twenty-four hours, both oldest first and always that long
 * (a bucket nobody wrote to is an empty map).
 *
 * @param minutes sixty minute buckets, the last being the minute now
 * @param hours twenty-four hour buckets, the last being the hour now
 */
public record FlowCounters(List<Map<String, Long>> minutes, List<Map<String, Long>> hours) {

  public FlowCounters {
    minutes = List.copyOf(minutes);
    hours = List.copyOf(hours);
  }
}
