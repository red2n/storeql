package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A refund's kind rides the {@code PaymentRefunded} event when there is one (substitutions for
 * out-of-stock online lines), and an event with none looks as it always did.
 */
class EventsRefundKindTest {

  @Test
  void anAdjustmentRefundNamesItsKindAndAPlainOneDoesNot() {
    var tenant = Ids.newId();
    var order = Ids.newId();
    String adjusted =
        Events.paymentRefunded(
                tenant, Ids.newId(), order, new BigDecimal("2.00"), List.of(), "ORDER_ADJUSTMENT")
            .payload();
    assertTrue(adjusted.contains("\"kind\":\"ORDER_ADJUSTMENT\""), adjusted);
    assertTrue(adjusted.contains("\"amount\":2.00"), adjusted);
    String plain =
        Events.paymentRefunded(tenant, Ids.newId(), order, new BigDecimal("2.00"), List.of())
            .payload();
    assertFalse(plain.contains("kind"), plain);
    assertTrue(plain.endsWith("\"tenders\":[]}"), plain);
  }
}
