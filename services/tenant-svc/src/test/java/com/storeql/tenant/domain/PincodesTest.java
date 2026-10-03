package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A postal code is kept as given, save for the space around and inside it. */
class PincodesTest {

  @Test
  @DisplayName("Surrounding space is dropped and inner runs of white space become one space")
  void spaceIsTidied() {
    assertEquals("SW1A 1AA", Pincodes.normalise("  SW1A \t 1AA "));
    assertEquals("560001", Pincodes.normalise(" 560001"));
  }

  @Test
  @DisplayName("Case does not tell two codes apart")
  void caseDoesNotMatter() {
    assertEquals(Pincodes.key("sw1a 1aa"), Pincodes.key("SW1A  1AA"));
  }

  @Test
  @DisplayName("A space is never removed: it can be what separates two codes")
  void aSpaceIsNeverRemoved() {
    assertNotEquals(Pincodes.key("SW1A 1AA"), Pincodes.key("SW1A1AA"));
  }

  @Test
  @DisplayName("Any script survives, and null stays null")
  void anyScriptSurvives() {
    assertEquals("〒100-0001", Pincodes.normalise("〒100-0001"));
    assertNull(Pincodes.normalise(null));
    assertNull(Pincodes.key(null));
  }
}
