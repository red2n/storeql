package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Money at its own currency's minor units: none for yen, two for pounds, three for dinars. */
class AmountsTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Test
  @DisplayName("An amount fits when it is no finer than its currency's minor unit")
  void fits() {
    assertTrue(Amounts.fits(d("12.50"), "GBP"));
    assertTrue(Amounts.fits(d("12.5000"), "GBP"), "trailing zeros are not precision");
    assertFalse(Amounts.fits(d("1.005"), "GBP"));

    assertTrue(Amounts.fits(d("1.125"), "KWD"));
    assertTrue(Amounts.fits(d("1.1250"), "KWD"));
    assertFalse(Amounts.fits(d("1.1255"), "KWD"));

    assertTrue(Amounts.fits(d("1250"), "JPY"));
    assertTrue(Amounts.fits(d("1250.00"), "JPY"));
    assertFalse(Amounts.fits(d("1250.5"), "JPY"));

    assertFalse(Amounts.fits(null, "GBP"));
    assertFalse(Amounts.fits(d("123456789012345"), "GBP"), "fifteen whole digits is a typo");
  }

  @Test
  @DisplayName("Exact is the amount at its currency's units, and never rounds")
  void exact() {
    assertEquals("12.50", Amounts.exact(d("12.5"), "GBP").toPlainString());
    assertEquals("1.125", Amounts.exact(d("1.1250"), "KWD").toPlainString());
    assertEquals("1250", Amounts.exact(d("1250.00"), "JPY").toPlainString());
    assertThrows(ArithmeticException.class, () -> Amounts.exact(d("1250.5"), "JPY"));
  }

  @Test
  @DisplayName("Shown is at the currency's units, and keeps a finer figure rather than round it")
  void shown() {
    assertEquals("12.50", Amounts.shown(d("12.5000"), "GBP").toPlainString());
    assertEquals("1.125", Amounts.shown(d("1.1250"), "KWD").toPlainString());
    assertEquals("1250", Amounts.shown(d("1250.0000"), "JPY").toPlainString());
    assertEquals("12.345", Amounts.shown(d("12.3450"), "GBP").toPlainString());
  }

  @Test
  @DisplayName("Rounded is half up at the currency's units, for a figure said in words")
  void rounded() {
    assertEquals("2.35", Amounts.rounded(d("2.345"), "GBP").toPlainString());
    assertEquals("2.346", Amounts.rounded(d("2.3455"), "KWD").toPlainString());
    assertEquals("1001", Amounts.rounded(d("1000.5"), "JPY").toPlainString());
  }

  @Test
  @DisplayName(
      "Tendered is a till's binary figure at the currency's units, half up; four places with none")
  void tendered() {
    assertEquals("3.30", Amounts.tendered(d("3.3000000000000003"), "GBP").toPlainString());
    assertEquals("15.99", Amounts.tendered(d("15.989999999999998"), "GBP").toPlainString());
    assertEquals("4.87", Amounts.tendered(d("4.87125"), "GBP").toPlainString());
    assertEquals("3.300", Amounts.tendered(d("3.3000000000000003"), "KWD").toPlainString());
    assertEquals("0.487", Amounts.tendered(d("0.487125"), "KWD").toPlainString());
    assertEquals("487", Amounts.tendered(d("487.125"), "JPY").toPlainString());
    assertEquals("1250", Amounts.tendered(d("1250.00"), "JPY").toPlainString());
    assertEquals("3.3000", Amounts.tendered(d("3.3000000000000003"), null).toPlainString());
    assertEquals(null, Amounts.tendered(null, "GBP"));
  }

  @Test
  @DisplayName(
      "A tender far below its currency's smallest unit is nothing, worked out without building a"
          + " power of ten the size of its exponent")
  void aTinyTenderIsNothingAtOnce() {
    // 1E-80000000 is twelve characters on the wire; rounding it the long way took 51 s of CPU.
    BigDecimal tiny = d("1E-80000000");
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          assertEquals("0.00", Amounts.tendered(tiny, "GBP").toPlainString());
          assertEquals("0.000", Amounts.tendered(tiny, "KWD").toPlainString());
          assertEquals("0", Amounts.tendered(tiny, "JPY").toPlainString());
          assertEquals("0.0000", Amounts.tendered(tiny, null).toPlainString());
        });
    // At the edge it rounds exactly as before: half the smallest unit is one, a hair under none.
    assertEquals("0.01", Amounts.tendered(d("0.005"), "GBP").toPlainString());
    assertEquals("0.00", Amounts.tendered(d("0.0049999"), "GBP").toPlainString());
    assertEquals("0.00", Amounts.tendered(d("0.000999"), "GBP").toPlainString());
    assertEquals("0.001", Amounts.tendered(d("0.0005"), "KWD").toPlainString());
    assertEquals("0.000", Amounts.tendered(d("0.00049"), "KWD").toPlainString());
    assertEquals("1", Amounts.tendered(d("0.5"), "JPY").toPlainString());
    assertEquals("0", Amounts.tendered(d("0.09"), "JPY").toPlainString());
  }

  @Test
  @DisplayName(
      "An amount whose exponent would wrap an int never fits, and is judged at once: no path"
          + " builds a number as long as its exponent")
  void anExponentThatWrapsNeverFits() {
    // 1E+2147483647 has one digit and a scale of -2147483647: precision - scale in an int is
    // 1 + 2147483647, which wraps to a negative and passed as "no whole digits at all". Exact
    // would then build a number two thousand million digits long.
    BigDecimal huge = d("1E+2147483647");
    BigDecimal hugeZero = d("0E+2147483647");
    BigDecimal longDigits = new BigDecimal(new java.math.BigInteger("1" + "0".repeat(4000)), 4000);
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          assertFalse(Amounts.fits(huge, "GBP"));
          assertFalse(Amounts.fits(huge, "JPY"));
          assertFalse(Amounts.fits(hugeZero, "GBP"));
          assertFalse(Amounts.fits(d("1E-2147483647"), "KWD"));
          // A one written with four thousand trailing zeros: more digits than any money has,
          // refused on its length, before a single zero is stripped.
          assertFalse(Amounts.fits(longDigits, "GBP"));
        });
    // The edges stay where they were: fourteen whole digits fit, fifteen do not.
    assertTrue(Amounts.fits(d("99999999999999.99"), "GBP"));
    assertFalse(Amounts.fits(d("999999999999999"), "GBP"));
    assertTrue(Amounts.fits(d("1.2E+3"), "JPY"));
    assertTrue(Amounts.fits(d("12.500000000000000000000000000000"), "GBP"));
  }
}
