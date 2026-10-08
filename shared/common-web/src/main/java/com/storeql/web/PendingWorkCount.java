package com.storeql.web;

/**
 * How far a count of work waiting for a person is taken: the system-health screen polls these every
 * few seconds, so each owning service counts at most {@link #CAP} rows of a queue and stops there,
 * and the cost of a count never grows with the queue. A count equal to the cap means "this many or
 * more", and the screen says so.
 */
public final class PendingWorkCount {

  /** The most rows any one queue's count reads. */
  public static final int CAP = 1000;

  private PendingWorkCount() {}

  /**
   * Whether a count stopped at the cap, so the real figure is at least this.
   *
   * @param count a count read with {@link #CAP} as its limit
   * @return {@code true} when the queue may hold more than the count says
   */
  public static boolean atCap(long count) {
    return count >= CAP;
  }
}
