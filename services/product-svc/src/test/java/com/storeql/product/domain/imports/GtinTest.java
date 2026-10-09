package com.storeql.product.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A barcode in an export is judged before it is trusted. */
class GtinTest {

  @Test
  @DisplayName(
      "an EAN-13 is read and padded to a GTIN-14; the same code written as a GTIN-14 is the same")
  void ean13AndGtin14() {
    assertEquals("05012345678900", Gtin.read("5012345678900").orElseThrow().gtin14());
    assertEquals("05012345678900", Gtin.read("05012345678900").orElseThrow().gtin14());
  }

  @Test
  @DisplayName("a UPC-A, an EAN-8 and a UPC-A written as an EAN-13 all compare as GTIN-14")
  void shorterCodes() {
    assertEquals("00036000291452", Gtin.read("036000291452").orElseThrow().gtin14());
    assertEquals("00036000291452", Gtin.read("0036000291452").orElseThrow().gtin14());
    assertEquals("00000096385074", Gtin.read("96385074").orElseThrow().gtin14());
  }

  @Test
  @DisplayName("a case code (a GTIN-14 with a packaging indicator) is its own code")
  void aCaseCode() {
    // 1 + the 12 digits of an EAN-13 less its check digit + a new check digit.
    assertEquals("15012345678907", Gtin.read("15012345678907").orElseThrow().gtin14());
  }

  @Test
  @DisplayName("a wrong check digit is refused")
  void aWrongCheckDigit() {
    var r = Gtin.read("5012345678901").orElseThrow();
    assertFalse(r.ok());
    assertEquals(Gtin.Problem.BAD_CHECK_DIGIT, r.problem());
  }

  @Test
  @DisplayName("exponent form, which Excel makes of a long number, is refused as such")
  void exponentForm() {
    assertEquals(Gtin.Problem.EXPONENT_FORM, Gtin.read("5.01E+12").orElseThrow().problem());
    assertEquals(Gtin.Problem.EXPONENT_FORM, Gtin.read("5,01e12").orElseThrow().problem());
    assertEquals(Gtin.Problem.EXPONENT_FORM, Gtin.read(" 5E+12 ").orElseThrow().problem());
  }

  @Test
  @DisplayName("letters, a lost leading zero (11 digits) and the wrong length are refused")
  void notABarcode() {
    assertEquals(Gtin.Problem.NOT_DIGITS, Gtin.read("50123ABC78900").orElseThrow().problem());
    assertEquals(Gtin.Problem.NOT_DIGITS, Gtin.read("5012 3456 78900").orElseThrow().problem());
    assertEquals(Gtin.Problem.BAD_LENGTH, Gtin.read("36000291452").orElseThrow().problem());
    assertEquals(Gtin.Problem.BAD_LENGTH, Gtin.read("123456789012345").orElseThrow().problem());
  }

  @Test
  @DisplayName("a scale's label code (13 digits starting with 2) names no product and is refused")
  void aLabelStyleCode() {
    // A valid EAN-13 check digit does not make it a product barcode.
    assertEquals(Gtin.Problem.LABEL_STYLE, Gtin.read("2012345001234").orElseThrow().problem());
  }

  @Test
  @DisplayName("an empty cell is no barcode, not a bad one")
  void emptyCell() {
    assertTrue(Gtin.read("").isEmpty());
    assertTrue(Gtin.read("   ").isEmpty());
    assertTrue(Gtin.read(null).isEmpty());
  }
}
