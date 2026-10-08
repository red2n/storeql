package com.storeql.gateway.flow;

import jakarta.json.bind.annotation.JsonbNillable;
import java.time.Instant;
import java.util.List;

/**
 * What {@code GET /api/v1/system-health/…} answers. Nulls are written out, not left off: a null
 * failure rate means "nothing was asked", which the screen must tell from "no field".
 */
public final class SystemHealthDtos {

  private SystemHealthDtos() {}

  /**
   * The summary screen's numbers.
   *
   * @param generatedAt when the figures were read
   * @param available false when the counters could not be read: every number is then empty, and the
   *     screen says so rather than showing zeros
   * @param droppedSinceStart records this gateway has given up (full queue, Redis away) since it
   *     started
   * @param windows the headline counts over three spans
   * @param byGroup the last hour by route group, biggest first
   * @param perMinute the last sixty minutes, oldest first, a point for each
   * @param perHour the last twenty-four hours, oldest first, a point for each
   */
  @JsonbNillable
  public record Summary(
      Instant generatedAt,
      boolean available,
      long droppedSinceStart,
      Windows windows,
      List<GroupCount> byGroup,
      List<Point> perMinute,
      List<Point> perHour) {
    public Summary {
      byGroup = List.copyOf(byGroup);
      perMinute = List.copyOf(perMinute);
      perHour = List.copyOf(perHour);
    }
  }

  /** Three spans, each ending now. Whole buckets: the minute or hour in progress counts. */
  @JsonbNillable
  public record Windows(Counts last5Minutes, Counts lastHour, Counts last24Hours) {}

  /**
   * Requests over a span.
   *
   * @param total every request
   * @param succeeded those that went well (below 400)
   * @param failed 5xx and the refusals 401, 403, 413 and 429
   * @param clientErrors the other 4xx: a normal business answer of no
   * @param failureRate {@code failed / total}, 0 to 1; null when nothing was asked
   */
  @JsonbNillable
  public record Counts(
      long total, long succeeded, long failed, long clientErrors, Double failureRate) {}

  /**
   * @param group the route group, e.g. {@code order-svc}
   * @param total requests in it
   * @param failed those that failed
   */
  @JsonbNillable
  public record GroupCount(String group, long total, long failed) {}

  /**
   * @param at the start of the minute or hour (UTC)
   * @param total requests in it
   * @param failed those that failed
   */
  @JsonbNillable
  public record Point(Instant at, long total, long failed) {}

  /**
   * One failure.
   *
   * @param at when the answer was given (UTC)
   * @param requestId the id to quote
   * @param method the HTTP method
   * @param routePattern the route with every id replaced by {@code {id}}
   * @param group the route group
   * @param status the HTTP status
   * @param code the answer's stable code, or null
   * @param userId who sent it, or null for an anonymous caller
   * @param ms milliseconds taken
   */
  @JsonbNillable
  public record FailureItem(
      Instant at,
      String requestId,
      String method,
      String routePattern,
      String group,
      int status,
      String code,
      String userId,
      long ms) {}

  /**
   * A page of failures, newest first.
   *
   * @param items the page
   * @param nextCursor the cursor for the next, older page, or null on the last
   * @param available false when the list could not be read: {@code items} is then empty and means
   *     nothing
   */
  @JsonbNillable
  public record FailurePage(List<FailureItem> items, String nextCursor, boolean available) {
    public FailurePage {
      items = List.copyOf(items);
    }
  }
}
