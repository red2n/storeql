package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import org.junit.jupiter.api.Test;

/** The rules for value handed out by hand: a reason from the list, and no money taken. */
class GiftCardHandLoadRulesTest {

  @Test
  void aReasonFromTheListIsAcceptedInAnyCase() {
    assertEquals("GOODWILL", OrderService.handSource("goodwill"));
    assertEquals("PROMOTION", OrderService.handSource(" PROMOTION "));
    assertEquals("COMPENSATION", OrderService.handSource("Compensation"));
    assertEquals("MIGRATION", OrderService.handSource("MIGRATION"));
  }

  @Test
  void noReasonOrAnotherReasonIsRefused() {
    for (String bad : new String[] {null, "", "  ", "SALE", "BECAUSE"}) {
      var e = assertThrows(ApiException.class, () -> OrderService.handSource(bad));
      assertEquals("GIFT_CARD_REASON_REQUIRED", e.code());
      assertEquals(400, e.status());
    }
  }

  @Test
  void aTenderNamedOnAHandLoadIsASaleInDisguise() {
    assertDoesNotThrow(() -> OrderService.requireHandPaidBy(null));
    assertDoesNotThrow(() -> OrderService.requireHandPaidBy(" "));
    assertDoesNotThrow(() -> OrderService.requireHandPaidBy("promotional"));
    for (String tender : new String[] {"CASH", "CARD", "UPI", "WALLET", "VOUCHER"}) {
      var e = assertThrows(ApiException.class, () -> OrderService.requireHandPaidBy(tender));
      assertEquals("GIFT_CARD_NEEDS_SALE", e.code());
      assertEquals(409, e.status());
    }
  }
}
