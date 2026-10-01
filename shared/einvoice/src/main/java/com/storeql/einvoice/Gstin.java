package com.storeql.einvoice;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An Indian GST identification number: two digits for the state that registered it, the holder's
 * PAN, the registration's count for that PAN, a Z, and a Luhn mod 36 check character — {@code
 * 27AAPFU0939F1ZV}. What India's e-invoices name a supplier and a recipient by, in place of a VAT
 * identifier.
 */
public final class Gstin {
  private static final java.util.regex.Pattern SPACE_DASH =
      java.util.regex.Pattern.compile("[\\s\\-]");

  private static final Pattern SHAPE =
      Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
  private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

  /** GST state and union territory codes, as the GSTIN and the IRP's place of supply carry them. */
  static final Map<String, String> STATES =
      Map.ofEntries(
          Map.entry("01", "Jammu and Kashmir"),
          Map.entry("02", "Himachal Pradesh"),
          Map.entry("03", "Punjab"),
          Map.entry("04", "Chandigarh"),
          Map.entry("05", "Uttarakhand"),
          Map.entry("06", "Haryana"),
          Map.entry("07", "Delhi"),
          Map.entry("08", "Rajasthan"),
          Map.entry("09", "Uttar Pradesh"),
          Map.entry("10", "Bihar"),
          Map.entry("11", "Sikkim"),
          Map.entry("12", "Arunachal Pradesh"),
          Map.entry("13", "Nagaland"),
          Map.entry("14", "Manipur"),
          Map.entry("15", "Mizoram"),
          Map.entry("16", "Tripura"),
          Map.entry("17", "Meghalaya"),
          Map.entry("18", "Assam"),
          Map.entry("19", "West Bengal"),
          Map.entry("20", "Jharkhand"),
          Map.entry("21", "Odisha"),
          Map.entry("22", "Chhattisgarh"),
          Map.entry("23", "Madhya Pradesh"),
          Map.entry("24", "Gujarat"),
          Map.entry("25", "Daman and Diu"),
          Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"),
          Map.entry("27", "Maharashtra"),
          Map.entry("28", "Andhra Pradesh (before 2014)"),
          Map.entry("29", "Karnataka"),
          Map.entry("30", "Goa"),
          Map.entry("31", "Lakshadweep"),
          Map.entry("32", "Kerala"),
          Map.entry("33", "Tamil Nadu"),
          Map.entry("34", "Puducherry"),
          Map.entry("35", "Andaman and Nicobar Islands"),
          Map.entry("36", "Telangana"),
          Map.entry("37", "Andhra Pradesh"),
          Map.entry("38", "Ladakh"),
          Map.entry("97", "Other Territory"));

  private Gstin() {}

  /**
   * Upper case, without the spaces and hyphens people type inside one; {@code null} for nothing.
   */
  public static String normalise(String gstin) {
    if (gstin == null) return null;
    String s = SPACE_DASH.matcher(gstin).replaceAll("").toUpperCase(Locale.ROOT);
    return s.isEmpty() ? null : s;
  }

  /**
   * A GSTIN from what a person entered, normalised, or {@code null} when nothing was.
   *
   * @throws IllegalArgumentException when it is not fifteen characters of the right shape, names a
   *     state code GST does not have, or its check character is wrong
   */
  public static String parse(String gstin) {
    String s = normalise(gstin);
    if (s == null) return null;
    if (!SHAPE.matcher(s).matches()) {
      throw new IllegalArgumentException(
          "a GSTIN is 15 characters: a state code, the PAN, the registration count, Z and a check"
              + " character, e.g. 27AAPFU0939F1ZV");
    }
    if (!STATES.containsKey(s.substring(0, 2))) {
      throw new IllegalArgumentException(
          "a GSTIN starts with a GST state code; " + s.substring(0, 2) + " is not one");
    }
    if (checkCharacter(s.substring(0, 14)) != s.charAt(14)) {
      throw new IllegalArgumentException("the GSTIN's check character is wrong");
    }
    return s;
  }

  /** Whether the text is a valid GSTIN. */
  public static boolean valid(String gstin) {
    try {
      return parse(gstin) != null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** The two-digit state code of a GSTIN, which is also its holder's state for place of supply. */
  public static String stateCode(String gstin) {
    String s = normalise(gstin);
    return s == null || s.length() < 2 ? null : s.substring(0, 2);
  }

  /** Whether GST has the state code: 01 to 38, and 97. */
  public static boolean isStateCode(String code) {
    return code != null && STATES.containsKey(code);
  }

  /**
   * The Luhn mod 36 character over the first fourteen: digits and letters count 0 to 35, weighted 1
   * and 2 alternately from the left, each product reduced to its quotient and remainder by 36.
   */
  static char checkCharacter(String first14) {
    int sum = 0;
    for (int i = 0; i < first14.length(); i++) {
      int product = ALPHABET.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
      sum += product / 36 + product % 36;
    }
    return ALPHABET.charAt((36 - sum % 36) % 36);
  }
}
