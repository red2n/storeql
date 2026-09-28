package com.storeql.customer.domain;

/**
 * The staff customer search's query: what counts as one, how long it may be, and the LIKE pattern
 * it is matched with. Pure; the repository binds the pattern, the service refuses the over-long.
 */
public final class CustomerSearch {

  /** The most characters a query may hold once trimmed. */
  public static final int MAX_LENGTH = 100;

  /**
   * The fewest digits a phone-shaped query must hold before the phone is searched by its digits.
   */
  public static final int MIN_PHONE_DIGITS = 4;

  /** What a person types between the digits of a phone number, beyond whitespace. */
  private static final String PHONE_PUNCTUATION = "+-()./";

  private CustomerSearch() {}

  /**
   * The query as it is searched for.
   *
   * @param q the {@code q} parameter as it arrived, or {@code null}
   * @return the trimmed text, or {@code null} when there is nothing to search for — a blank query
   *     lists exactly as no query does
   */
  public static String term(String q) {
    if (q == null) return null;
    String trimmed = q.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Whether a trimmed query is over {@link #MAX_LENGTH}. Counted in code points, so the cap is the
   * characters a person typed rather than UTF-16 halves of them.
   */
  public static boolean tooLong(String term) {
    return term != null && term.codePointCount(0, term.length()) > MAX_LENGTH;
  }

  /**
   * The substring pattern for {@code ILIKE ... ESCAPE '\'}. A {@code %} or {@code _} the person
   * typed must match itself: left as a wildcard, {@code q=%} would list the whole customer base
   * through a search box, and a trailing backslash would be a malformed pattern.
   */
  public static String pattern(String term) {
    String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  /**
   * The digits-only substring pattern a phone is also matched with, so that {@code 07700 900111},
   * {@code 07700900111} and {@code 7700 900111} find a phone stored as {@code 07700900111}, and the
   * other way about: the repository reduces the stored phone to its ASCII digits and matches this
   * against it with {@code LIKE}.
   *
   * <p>Only a phone-shaped query is searched this way — ASCII digits with nothing between them but
   * whitespace and {@code + - ( ) . /} — and only one holding at least {@link #MIN_PHONE_DIGITS}
   * digits. A query with a letter in it is a name or an email, and its digits alone are not a phone
   * number: {@code john1985@mail.example} must not bring in every customer whose phone holds 1985.
   * No leading {@code +44} or {@code 0} is rewritten, because nothing in this service normalises a
   * phone number to a country: the digits are matched as they are.
   *
   * @param term the trimmed query, or {@code null}
   * @return {@code %digits%}, which holds no wildcard but its ends, or {@code null} when the query
   *     is not a phone number to search by its digits
   */
  public static String phonePattern(String term) {
    if (term == null) return null;
    StringBuilder digits = new StringBuilder(term.length());
    for (int i = 0; i < term.length(); i++) {
      char c = term.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      } else if (!Character.isWhitespace(c)
          && !Character.isSpaceChar(c)
          && PHONE_PUNCTUATION.indexOf(c) < 0) {
        return null;
      }
    }
    return digits.length() >= MIN_PHONE_DIGITS ? "%" + digits + "%" : null;
  }
}
