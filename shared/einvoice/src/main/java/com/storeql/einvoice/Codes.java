package com.storeql.einvoice;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The code lists EN 16931 and Peppol BIS Billing 3.0 validate against, exactly as the CEN and
 * OpenPEPPOL schematron releases list them (extracted from {@code EN16931-UBL-codes.sch} and {@code
 * PEPPOL-EN16931-UBL.sch}), so a code this accepts is one a receiving access point accepts. They
 * are kept together in {@code code-lists.properties}, one key per list.
 */
public final class Codes {
  private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

  /** One published list; the constant's name is its key in {@code code-lists.properties}. */
  public enum CodeList {
    /** UNTDID 1001, restricted to invoices and credit notes (BR-CL-01). */
    DOCUMENT_TYPES,
    /** ISO 4217 alpha-3 (BR-CL-03, BR-CL-04, BR-CL-05). */
    CURRENCIES,
    /** UNTDID 2005, restricted to VAT point date codes (BR-CL-06). */
    VAT_POINT_DATE_CODES,
    /** UNTDID 1153, invoiced object scheme identifiers (BR-CL-07). */
    OBJECT_SCHEMES,
    /** ISO 6523 ICD, identifier schemes (BR-CL-10, BR-CL-11, BR-CL-21, BR-CL-26). */
    ICD,
    /** UNTDID 7143, item classification schemes (BR-CL-13). */
    ITEM_CLASSIFICATION,
    /** ISO 3166-1 alpha-2 with the EN 16931 additions 1A and XI (BR-CL-14, BR-CL-15). */
    COUNTRIES,
    /** UNTDID 4461 (BR-CL-16). */
    PAYMENT_MEANS,
    /** UNTDID 5305, restricted to VAT (BR-CL-17, BR-CL-18). */
    VAT_CATEGORIES,
    /** UNTDID 5189, restricted to allowance reasons (BR-CL-19). */
    ALLOWANCE_REASONS,
    /** UNTDID 7161 (BR-CL-20). */
    CHARGE_REASONS,
    /** CEF VATEX exemption reasons (BR-CL-22). */
    VATEX,
    /** UN/ECE Recommendation 20 with the Recommendation 21 extension (BR-CL-23). */
    UNITS,
    /** CEF Electronic Address Scheme (BR-CL-25). */
    EAS,
    /** The EAS codes Peppol routes on (PEPPOL-EN16931-CL008). */
    PEPPOL_EAS,
    /** The attachment MIME types Peppol accepts (PEPPOL-EN16931-CL001). */
    PEPPOL_MIME
  }

  private static final String RESOURCE = "code-lists.properties";

  private static final Map<CodeList, Set<String>> LOADED = new ConcurrentHashMap<>();

  private Codes() {}

  /**
   * Whether a code is on a list. Codes compare without case, as the schematron's {@code
   * upper-case(normalize-space())} does, except MIME types, which the list spells in lower case.
   */
  public static boolean contains(CodeList list, String code) {
    if (code == null || code.isBlank()) return false;
    return of(list).contains(normalise(list, code));
  }

  /** Every code on a list. */
  public static Set<String> of(CodeList list) {
    return LOADED.computeIfAbsent(list, Codes::load);
  }

  private static String normalise(CodeList list, String code) {
    String trimmed = code.strip();
    return list == CodeList.PEPPOL_MIME
        ? trimmed.toLowerCase(Locale.ROOT)
        : trimmed.toUpperCase(Locale.ROOT);
  }

  private static Set<String> load(CodeList list) {
    Properties lists = new Properties();
    try (InputStream in = Codes.class.getResourceAsStream(RESOURCE);
        Reader reader = in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8)) {
      if (reader == null) throw new IllegalStateException(RESOURCE + " is not on the classpath");
      lists.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException(RESOURCE + " could not be read", e);
    }
    String codes = lists.getProperty(list.name());
    if (codes == null || codes.isBlank()) {
      throw new IllegalStateException(RESOURCE + " has no list " + list.name());
    }
    return Arrays.stream(WHITESPACE.split(codes.strip()))
        .map(code -> normalise(list, code))
        .collect(Collectors.toUnmodifiableSet());
  }
}
