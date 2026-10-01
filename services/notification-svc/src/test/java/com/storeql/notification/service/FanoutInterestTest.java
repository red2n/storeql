package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The fan-out's "nobody listens" memory: short, bounded, and dropped on a change. */
class FanoutInterestTest {

  private static FanoutInterest interest(long ttl, int max) {
    FanoutInterest i = new FanoutInterest();
    i.ttlSeconds = ttl;
    i.max = max;
    return i;
  }

  @Test
  void anEmptyAnswerIsRememberedUntilTheBusinessChangesAnEndpoint() {
    FanoutInterest i = interest(60, 100);
    UUID tenant = Ids.newId();
    assertFalse(i.knownEmpty(tenant, "OrderPlaced"));
    i.noteEmpty(tenant, "OrderPlaced");
    assertTrue(i.knownEmpty(tenant, "OrderPlaced"));
    assertFalse(i.knownEmpty(tenant, "OrderCancelled"), "another type is asked afresh");
    assertFalse(i.knownEmpty(Ids.newId(), "OrderPlaced"), "another business is asked afresh");
    i.invalidate(tenant);
    assertFalse(i.knownEmpty(tenant, "OrderPlaced"));
  }

  @Test
  void theMemoryIsBounded() {
    FanoutInterest i = interest(60, 10);
    for (int n = 0; n < 100; n++) {
      i.noteEmpty(Ids.newId(), "OrderPlaced");
    }
    assertTrue(i.remembered() <= 10, "remembered " + i.remembered());
  }

  @Test
  void aZeroTimeToLiveSwitchesItOff() {
    FanoutInterest i = interest(0, 10);
    UUID tenant = Ids.newId();
    i.noteEmpty(tenant, "OrderPlaced");
    assertFalse(i.knownEmpty(tenant, "OrderPlaced"));
    assertEquals(0, i.remembered());
  }
}
