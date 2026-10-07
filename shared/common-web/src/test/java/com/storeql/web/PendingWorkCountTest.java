package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The cap on a waiting-work count, and when a count is said to have reached it. */
class PendingWorkCountTest {

  @Test
  @DisplayName("The cap is a thousand, and only a count that reached it is 'or more'")
  void onlyACountAtTheCapIsOrMore() {
    assertEquals(1000, PendingWorkCount.CAP);
    assertFalse(PendingWorkCount.atCap(0));
    assertFalse(PendingWorkCount.atCap(999));
    assertTrue(PendingWorkCount.atCap(1000));
    assertTrue(PendingWorkCount.atCap(1001));
  }
}
