package com.storeql.gateway.flow;

import java.util.Optional;

/**
 * How a request ended, as the health screen counts it. The one place that decides what a failure
 * is, so a counter, a list of failures and a failure rate cannot disagree.
 *
 * <ul>
 *   <li>{@link #FAILED}: the system did not do what was asked, or refused to — any 5xx, and the
 *       refusals 401 (no valid sign-in), 403 (not allowed), 413 (too big) and 429 (too many).
 *   <li>{@link #CLIENT}: a normal business answer that happens to be a 4xx — not found, a conflict,
 *       a rule not met, a bad value. The system worked; the answer was no.
 *   <li>{@link #OK}: everything below 400, redirects included.
 * </ul>
 */
public enum FlowOutcome {
  OK("ok"),
  CLIENT("client"),
  FAILED("failed");

  private final String label;

  FlowOutcome(String label) {
    this.label = label;
  }

  /**
   * @param status the HTTP status the caller got
   * @return how that status counts
   */
  public static FlowOutcome of(int status) {
    if (status >= 500 || status == 401 || status == 403 || status == 413 || status == 429) {
      return FAILED;
    }
    return status >= 400 ? CLIENT : OK;
  }

  /**
   * @return the word this outcome is stored and shown under: the second half of a counter's field
   *     name
   */
  public String label() {
    return label;
  }

  /**
   * @param label a stored label
   * @return the outcome it names, or empty for anything else
   */
  public static Optional<FlowOutcome> ofLabel(String label) {
    for (FlowOutcome o : values()) {
      if (o.label.equals(label)) return Optional.of(o);
    }
    return Optional.empty();
  }
}
