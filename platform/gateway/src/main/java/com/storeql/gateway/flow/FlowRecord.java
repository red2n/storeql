package com.storeql.gateway.flow;

import java.time.Instant;

/**
 * One request as the health screen remembers it. Everything here is safe to keep: no body, no query
 * string, no raw path — only the shape of the route, how it ended, who sent it and how long it
 * took.
 *
 * @param at when the answer was given (UTC)
 * @param requestId the id the caller can quote, also sent to every service
 * @param method the HTTP method, or {@code OTHER} for one the gateway does not know
 * @param routePattern the templated path ({@link RoutePattern})
 * @param group the route group ({@link RoutePattern#group()})
 * @param status the HTTP status
 * @param code the answer's stable error code, or null when it carried none or the gateway cannot
 *     know it (a relayed answer is never read)
 * @param tenantId the verified business, or null when the request belongs to none
 * @param userId the verified caller, or null when anonymous
 * @param ms milliseconds from arrival to answer
 */
public record FlowRecord(
    Instant at,
    String requestId,
    String method,
    String routePattern,
    String group,
    int status,
    String code,
    String tenantId,
    String userId,
    long ms) {

  /**
   * @return how this request counts
   */
  public FlowOutcome outcome() {
    return FlowOutcome.of(status);
  }

  /**
   * @return whether this is the health screen's own polling ({@link RoutePattern#isScreenRead})
   */
  public boolean isScreenRead() {
    return RoutePattern.isScreenRead(routePattern, group);
  }
}
