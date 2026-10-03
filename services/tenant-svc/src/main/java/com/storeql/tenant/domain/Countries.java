package com.storeql.tenant.domain;

import com.storeql.web.ApiException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The country a business, a store or a billing address names: an ISO 3166-1 alpha-2 code, however
 * it arrives. The length alone is no test — two ideographic spaces are two characters, and so is
 * {@code UK}, which is not a country (the United Kingdom is {@code GB}).
 */
public final class Countries {

  private static final Set<String> ISO = Set.of(Locale.getISOCountries());

  /** ASCII only, so that a letter that upper-cases to another (a dotless i) is no code. */
  private static final Pattern LETTERS = Pattern.compile("[A-Za-z]{2}");

  private Countries() {}

  /**
   * The code, upper-cased.
   *
   * @param raw as typed; surrounding space is ignored
   * @throws ApiException 400 {@code COUNTRY_INVALID} for anything that is not an ISO 3166-1 alpha-2
   *     code, a blank included
   */
  public static String require(String raw) {
    String typed = raw == null ? "" : raw.strip();
    String code = LETTERS.matcher(typed).matches() ? typed.toUpperCase(Locale.ROOT) : "";
    if (!ISO.contains(code)) {
      throw ApiException.badRequest(
          "COUNTRY_INVALID", "country must be an ISO 3166-1 alpha-2 code such as DE or JP");
    }
    return code;
  }

  /**
   * A country that may be left out: nothing named stays nothing, what is named must be one.
   *
   * @throws ApiException 400 {@code COUNTRY_INVALID} for a non-blank value that is not a code
   */
  public static String optional(String raw) {
    return raw == null || raw.isBlank() ? null : require(raw);
  }
}
