package com.storeql.einvoice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Polish NIP check digit: weights 6 5 7 2 3 4 5 6 7, sum mod 11, a remainder of 10 invalid. */
class Fa3NipTest {

  @Test
  void numbersWithTheirRealCheckDigitAreValid() {
    for (String nip :
        new String[] {"1234563218", "5260250274", "7740001454", "5250007738", "0000000000"}) {
      assertTrue(Fa3.validNip(nip), nip);
    }
  }

  @Test
  void aWrongCheckDigitIsInvalid() {
    assertFalse(Fa3.validNip("1234563219"));
    assertFalse(Fa3.validNip("5260250275"));
    // Valid under the old weights (6 7 8 9 2 3 4 5 6), not under the real ones.
    assertFalse(Fa3.validNip("5260250991"));
  }

  @Test
  void aRemainderOfTenIsNeverValid() {
    // 1*6 + 2*5 + 3*7 + 4*2 + 5*3 + 6*4 + 7*5 + 8*6 + 9*7 = 230, and 230 mod 11 = 10.
    for (char last = '0'; last <= '9'; last++) {
      assertFalse(Fa3.validNip("123456789" + last), "last digit " + last);
    }
  }

  @Test
  void notTenDigitsIsInvalid() {
    assertFalse(Fa3.validNip(null));
    assertFalse(Fa3.validNip(""));
    assertFalse(Fa3.validNip("526025027"));
    assertFalse(Fa3.validNip("52602502744"));
    assertFalse(Fa3.validNip("526-025-02-74"));
    assertFalse(Fa3.validNip("PL5260250274"));
  }
}
