package com.storeql.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The confirmations ride a consumer group of their own that starts at the latest offset: a
 * projection of live confirmations must not replay every retained one on its first deployment, and
 * the deductions' group keeps its own offsets.
 */
class AwaitingOrdersConsumerTest {

  @Test
  void theConfirmationsHaveTheirOwnGroupStartingAtTheLatestOffset() {
    var c = new AwaitingOrdersConsumer();
    c.confirmedTopic = "storeql.order.order-confirmed";
    assertEquals(List.of("storeql.order.order-confirmed"), c.topics());
    assertEquals("latest", c.offsetReset());
    assertEquals("inventory-svc-awaiting-orders", c.groupId());
    // The deductions keep their own group, and with it their own offsets.
    assertNotEquals(new OrderEventConsumer().groupId(), c.groupId());
  }
}
