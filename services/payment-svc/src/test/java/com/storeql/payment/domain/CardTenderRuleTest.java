package com.storeql.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.payment.domain.CardTenderRule.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The till's card rule: when a typed CARD tender is allowed, and what it must carry. */
class CardTenderRuleTest {

  @Test
  @DisplayName(
      "a tender that names the machine's approval is recorded from it, whatever else is true")
  void terminalApproval() {
    Decision d = CardTenderRule.decide(true, true, false, null);
    assertTrue(d.allowed());
    assertEquals("TERMINAL", d.entryMode());
  }

  @Test
  @DisplayName(
      "a store with a registered machine refuses a typed card unless a standalone one is allowed")
  void needsTerminal() {
    Decision d = CardTenderRule.decide(false, true, false, "AUTH 1");
    assertFalse(d.allowed());
    assertEquals("PAYMENT_CARD_NEEDS_TERMINAL", d.refusalCode());
    assertEquals(409, d.refusalStatus());
  }

  @Test
  @DisplayName(
      "where a typed card is allowed the machine's reference is required, trimmed, and bounded")
  void referenceRequired() {
    for (boolean machine : new boolean[] {false, true}) {
      boolean allowedHere =
          machine; // with a machine, only because the owner allowed a standalone one
      for (String blank : new String[] {null, "", "   ", "\t"}) {
        Decision d = CardTenderRule.decide(false, machine, allowedHere, blank);
        assertEquals("PAYMENT_CARD_REFERENCE_REQUIRED", d.refusalCode());
        assertEquals(400, d.refusalStatus());
      }
      Decision ok = CardTenderRule.decide(false, machine, allowedHere, "  AUTH 4821  ");
      assertTrue(ok.allowed());
      assertEquals("STANDALONE", ok.entryMode());
      assertEquals("AUTH 4821", ok.reference());
    }
    Decision tooLong = CardTenderRule.decide(false, false, false, "x".repeat(65));
    assertEquals("PAYMENT_CARD_REFERENCE_INVALID", tooLong.refusalCode());
    assertTrue(CardTenderRule.decide(false, false, false, "x".repeat(64)).allowed());
  }

  @Test
  @DisplayName("a store with no machine at all needs only the reference")
  void noMachine() {
    Decision d = CardTenderRule.decide(false, false, false, "RRN 000123");
    assertTrue(d.allowed());
    assertEquals("STANDALONE", d.entryMode());
    assertNull(d.refusalCode());
  }

  @Test
  @DisplayName("an allowed standalone machine does not matter where the store has none")
  void allowedWithoutMachine() {
    assertTrue(CardTenderRule.decide(false, false, true, "A1").allowed());
  }
}
