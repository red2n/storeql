package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MaskingTest {

  @Test
  void keepsTheFirstCharacterAndTheDomain() {
    assertEquals("j***@example.com", Masking.email("jane.doe@example.com"));
    assertEquals("a***@x.co.uk", Masking.email(" a@x.co.uk "));
  }

  @Test
  void anAddressWithoutAnAtSignIsHiddenWhole() {
    assertEquals("***", Masking.email("not an address"));
    assertEquals("***", Masking.email("@example.com"));
    assertEquals("***", Masking.email(""));
  }

  @Test
  void nullStaysNull() {
    assertNull(Masking.email(null));
  }

  @Test
  void aFirstCharacterOutsideTheBasicPlaneIsKeptWhole() {
    assertEquals("😀***@example.org", Masking.email("😀smile@example.org"));
  }
}
