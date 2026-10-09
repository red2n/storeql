package com.storeql.product.domain.imports;

import java.util.Optional;

/**
 * Barcodes as the importer reads them (intent/catalogue-import.md), pure.
 *
 * <p>A barcode in an export is text typed by a till system or mangled by a spreadsheet, so it is
 * judged before it is trusted: digits only, a length a GTIN has (8, 12, 13 or 14), a check digit
 * that agrees, and not a scale's own label. Two codes for one product written differently — an
 * EAN-13 and the same code as a GTIN-14, a UPC-A and its EAN-13 — are the same code, so they are
 * compared as GTIN-14.
 */
public final class Gtin {

  private Gtin() {}

  /** Why a barcode cell was refused. */
  public enum Problem {
    /** Written in exponent form ({@code 5.01E+12}): a spreadsheet's damage, the digits are gone. */
    EXPONENT_FORM,
    /** Not only digits. */
    NOT_DIGITS,
    /** Not a length a GTIN has: a leading zero lost, or not a barcode. */
    BAD_LENGTH,
    /** The check digit does not agree with the others. */
    BAD_CHECK_DIGIT,
    /** A scale's in-store label (price or weight embedded), which names no product on its own. */
    LABEL_STYLE
  }

  /** A barcode read: its GTIN-14, or why it was refused. */
  public record Read(String gtin14, Problem problem) {
    public boolean ok() {
      return problem == null;
    }
  }

  /**
   * Reads a barcode cell.
   *
   * @param raw the cell
   * @return the GTIN-14 it stands for, or what is wrong with it; an empty cell is {@link
   *     Optional#empty()}
   */
  public static Optional<Read> read(String raw) {
    if (raw == null) return Optional.empty();
    String s = raw.trim();
    if (s.isEmpty()) return Optional.empty();
    if (s.matches("(?i)^[0-9]+([.,][0-9]+)?e[+-]?[0-9]+$")) {
      return Optional.of(new Read(null, Problem.EXPONENT_FORM));
    }
    if (!s.matches("[0-9]+")) {
      return Optional.of(new Read(null, Problem.NOT_DIGITS));
    }
    int n = s.length();
    if (n != 8 && n != 12 && n != 13 && n != 14) {
      return Optional.of(new Read(null, Problem.BAD_LENGTH));
    }
    // A 13-digit code starting 2 is a scale's in-store label (GS1 restricted circulation): it
    // carries a price or a weight, so two sales of one product make two different codes. Whether
    // its check digit agrees does not change what it is.
    if (n == 13 && s.charAt(0) == '2') {
      return Optional.of(new Read(null, Problem.LABEL_STYLE));
    }
    if (!checkDigitAgrees(s)) {
      return Optional.of(new Read(null, Problem.BAD_CHECK_DIGIT));
    }
    return Optional.of(new Read("0".repeat(14 - n) + s, null));
  }

  /** GS1 check digit: weights 3, 1, 3, 1 … from the digit next to the check digit. */
  static boolean checkDigitAgrees(String digits) {
    int sum = 0;
    int weight = 3;
    for (int i = digits.length() - 2; i >= 0; i--) {
      sum += (digits.charAt(i) - '0') * weight;
      weight = 4 - weight;
    }
    int check = (10 - sum % 10) % 10;
    return check == digits.charAt(digits.length() - 1) - '0';
  }
}
