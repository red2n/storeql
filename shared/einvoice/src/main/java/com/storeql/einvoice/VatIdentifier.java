package com.storeql.einvoice;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A VAT identifier as EN 16931 carries it: prefixed with the country that issued it (BR-CO-09) —
 * {@code GB123456789}, {@code DE123456789}, {@code EL094259216} for Greece.
 */
public final class VatIdentifier {
  private static final java.util.regex.Pattern SEPARATORS =
      java.util.regex.Pattern.compile("[\\s.\\-]");

  private static final Pattern SHAPE = Pattern.compile("[A-Z0-9]{2}[A-Z0-9+*]{2,15}");

  private VatIdentifier() {}

  /**
   * The identifier as it is compared: upper case, without the spaces, dots and hyphens people type
   * inside one. {@code null} for nothing.
   */
  public static String normalise(String vatId) {
    if (vatId == null) return null;
    String s = SEPARATORS.matcher(vatId).replaceAll("").toUpperCase(Locale.ROOT);
    return s.isEmpty() ? null : s;
  }

  /**
   * An identifier from what a person entered, normalised, or {@code null} when nothing was.
   *
   * @throws IllegalArgumentException when it does not start with the code of a country that issues
   *     VAT identifiers, or is not 4 to 17 letters and digits
   */
  public static String parse(String vatId) {
    String s = normalise(vatId);
    if (s == null) return null;
    String prefix = s.length() < 2 ? s : s.substring(0, 2);
    boolean country = "EL".equals(prefix) || Codes.contains(Codes.CodeList.COUNTRIES, prefix);
    if (!country || !SHAPE.matcher(s).matches()) {
      throw new IllegalArgumentException(
          "a VAT identifier starts with the code of the country that issued it, e.g. GB123456789");
    }
    return s;
  }

  /** Whether two identifiers are the same once normalised; never for a missing one. */
  public static boolean same(String a, String b) {
    String x = normalise(a);
    return x != null && x.equals(normalise(b));
  }
}
