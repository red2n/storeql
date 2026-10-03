package com.storeql.product.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.web.ApiException;
import org.junit.jupiter.api.Test;

class VariantBarcodesTest {

  @Test
  void validGtinsOfEveryLengthPass() {
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("5012345678900")); // EAN-13
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("4006381333931")); // EAN-13
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("036000291452")); // UPC-A
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("96385074")); // EAN-8
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("05012345678900")); // GTIN-14
  }

  @Test
  void aGtinShapedBarcodeWithAWrongCheckDigitIsRefused() {
    for (String bad :
        new String[] {"5012345678901", "036000291453", "96385075", "05012345678901"}) {
      ApiException e = assertThrows(ApiException.class, () -> VariantBarcodes.requireValid(bad));
      assertEquals(400, e.status());
      assertEquals("PRODUCT_BARCODE_INVALID", e.code());
    }
  }

  @Test
  void whatIsNotShapedLikeAGtinIsNotChecked() {
    assertDoesNotThrow(() -> VariantBarcodes.requireValid(null));
    assertDoesNotThrow(() -> VariantBarcodes.requireValid(""));
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("SHELF-0042"));
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("12345")); // other length
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("1234567890123456")); // 16 digits
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("501234567890A")); // a letter
    assertDoesNotThrow(() -> VariantBarcodes.requireValid("5012345678 01")); // a space
  }

  @Test
  void theShapeIsAllDigitsOfALengthGs1Defines() {
    assertTrue(VariantBarcodes.looksLikeGtin("12345678"));
    assertTrue(VariantBarcodes.looksLikeGtin("123456789012"));
    assertTrue(VariantBarcodes.looksLikeGtin("1234567890123"));
    assertTrue(VariantBarcodes.looksLikeGtin("12345678901234"));
    assertFalse(VariantBarcodes.looksLikeGtin("1234567"));
    assertFalse(VariantBarcodes.looksLikeGtin("1234567890"));
    assertFalse(VariantBarcodes.looksLikeGtin("123456789012345"));
    assertFalse(VariantBarcodes.looksLikeGtin(null));
  }
}
