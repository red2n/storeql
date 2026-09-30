package com.storeql.product.service;

import com.storeql.gs1.Gtin;
import com.storeql.web.ApiException;

/**
 * What a barcode typed onto a variant must satisfy before it is stored.
 *
 * <p>Only a barcode that <em>claims</em> to be a GTIN is held to GS1's check digit: all digits and
 * 8, 12, 13 or 14 of them. Everything else (a shop's own codes, PLUs, letters, any other length)
 * has always been accepted and stays so. Reuses {@link Gtin}, the check the scan path applies.
 */
public final class VariantBarcodes {

  private VariantBarcodes() {}

  /**
   * Whether a barcode is the shape of a GTIN: only digits, of a length GS1 defines.
   *
   * @param barcode the barcode as typed
   * @return {@code true} for 8, 12, 13 or 14 digits
   */
  public static boolean looksLikeGtin(String barcode) {
    if (barcode == null) return false;
    int n = barcode.length();
    if (n != 8 && n != 12 && n != 13 && n != 14) return false;
    for (int i = 0; i < n; i++) {
      char ch = barcode.charAt(i);
      if (ch < '0' || ch > '9') return false;
    }
    return true;
  }

  /**
   * Refuses a barcode that looks like a GTIN but fails its check digit.
   *
   * @param barcode the barcode as typed, or {@code null} for a variant with none
   * @throws ApiException {@code 400 PRODUCT_BARCODE_INVALID}
   */
  public static void requireValid(String barcode) {
    if (looksLikeGtin(barcode) && !Gtin.valid(barcode)) {
      throw ApiException.badRequest(
          "PRODUCT_BARCODE_INVALID",
          "The barcode looks like a GTIN but its check digit is wrong; check the digits");
    }
  }
}
