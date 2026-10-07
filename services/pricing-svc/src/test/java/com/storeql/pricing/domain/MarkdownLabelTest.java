package com.storeql.pricing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The sticker's barcode is what the till reads; a digit wrong is a wrong price at the till. */
class MarkdownLabelTest {

  @Test
  void aStickerIsPrefixItemPriceAndCheck() {
    String code = MarkdownLabel.encode(42, new BigDecimal("7.00"), 2);
    assertEquals(13, code.length());
    assertTrue(code.startsWith("2100042"));
    assertEquals("00700", code.substring(7, 12));
    assertTrue(MarkdownLabel.isLabel(code));
    assertEquals(new BigDecimal("7.00"), MarkdownLabel.priceOf(code, 2));
  }

  @Test
  void theCheckDigitIsEan13s() {
    // 4006381333931 is a well-known valid EAN-13.
    assertEquals('1', MarkdownLabel.checkDigit("400638133393"));
    String code = MarkdownLabel.encode(1, new BigDecimal("0.99"), 2);
    String tampered = code.substring(0, 12) + (code.charAt(12) == '0' ? '1' : '0');
    assertFalse(MarkdownLabel.isLabel(tampered));
    assertNull(MarkdownLabel.priceOf(tampered, 2));
  }

  @Test
  void itemNumbersWrapAtFiveDigitsAndPricesMustFit() {
    assertTrue(MarkdownLabel.encode(100001, BigDecimal.ONE, 2).startsWith("2100001"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MarkdownLabel.encode(1, new BigDecimal("1000.00"), 2));
    assertThrows(
        IllegalArgumentException.class, () -> MarkdownLabel.encode(1, new BigDecimal("-1"), 2));
    assertEquals(
        new BigDecimal("999.99"),
        MarkdownLabel.priceOf(MarkdownLabel.encode(7, new BigDecimal("999.99"), 2), 2));
  }

  @Test
  void anOrdinaryBarcodeOrAScaleLabelIsNotASticker() {
    assertFalse(MarkdownLabel.isLabel("5012345678900"));
    assertFalse(MarkdownLabel.isLabel("2012345678901"));
    assertFalse(MarkdownLabel.isLabel("21ABC"));
    assertFalse(MarkdownLabel.isLabel(null));
  }

  /**
   * The five price digits are the currency's own minor units, as GS1 price-embedded store codes
   * carry them: whole yen up to ¥99,999, fils up to KWD 99.999 — never a two-decimal reading.
   */
  @Test
  void thePriceDigitsAreTheCurrencysOwnMinorUnits() {
    String yen = MarkdownLabel.encode(5, new BigDecimal("1980"), 0);
    assertEquals("01980", yen.substring(7, 12));
    assertEquals(new BigDecimal("1980"), MarkdownLabel.priceOf(yen, 0));
    assertEquals(new BigDecimal("99999"), MarkdownLabel.maxPrice(0));
    assertThrows(
        IllegalArgumentException.class, () -> MarkdownLabel.encode(1, new BigDecimal("100000"), 0));

    String dinar = MarkdownLabel.encode(5, new BigDecimal("1.235"), 3);
    assertEquals("01235", dinar.substring(7, 12));
    assertEquals(new BigDecimal("1.235"), MarkdownLabel.priceOf(dinar, 3));
    assertEquals(new BigDecimal("99.999"), MarkdownLabel.maxPrice(3));
    assertEquals(new BigDecimal("999.99"), MarkdownLabel.maxPrice(2));
  }
}
