package com.storeql.product.service;

import com.storeql.web.ApiException;
import java.util.Locale;

/**
 * How a catalogue import treats a SKU the business already holds.
 *
 * <p>Read in one place and read strictly: an import that took any word it did not know for {@link
 * #ADD} would, on a typo of {@code REPLACE}, create the duplicates the caller meant to overwrite
 * and answer as though it had done what was asked.
 */
enum ImportMode {

  /** Creates new rows; a SKU already held is that row's error. The default. */
  ADD,

  /**
   * Upserts by SKU: reuses the product matched on name and category, and replaces any variant that
   * carries a SKU the sheet names.
   */
  REPLACE;

  /**
   * Reads the mode a request names.
   *
   * @param text the request's {@code mode}, in either case; null or blank for none
   * @return the mode; {@link #ADD} when none is named
   * @throws ApiException 400 {@code IMPORT_MODE_INVALID} for any other word
   */
  static ImportMode of(String text) {
    if (text == null || text.isBlank()) {
      return ADD;
    }
    return switch (text.strip().toUpperCase(Locale.ROOT)) {
      case "ADD" -> ADD;
      case "REPLACE" -> REPLACE;
      default -> throw ApiException.badRequest("IMPORT_MODE_INVALID", "mode is ADD or REPLACE");
    };
  }
}
