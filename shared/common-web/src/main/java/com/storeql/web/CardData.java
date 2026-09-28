package com.storeql.web;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds payment card numbers (PANs) in text, so that none is ever accepted or logged.
 *
 * <p>StoreQL's PCI DSS scope rests on one fact: no card detail reaches any StoreQL service — the
 * customer completes against the payment provider, so the platform is SAQ-A. A design decision only
 * holds while nothing accepts a card number, and a free-text field accepts anything. This is the
 * control that turns the decision into a fact: the gateway refuses any request carrying a PAN, and
 * the log scrubber masks one before it is written, so a staff member pasting a customer's card
 * number into a note is stopped at the door, and a bug that logs a request body cannot leak one.
 *
 * <p>A PAN here is a maximal run of digits — single spaces or hyphens allowed between them, as
 * people type card numbers — of 15, 16 or 19 digits that passes the Luhn check and starts with an
 * issuer range that is actually issued at that length: Visa 4 (16, 19); Mastercard 51–55 and
 * 2221–2720 (16); American Express 34, 37 (15); Discover 6011, 644–649, 65 (16, 19); JCB 3528–3589
 * (16, 19); UnionPay 62 (16, 19). Thirteen- and fourteen-digit runs are deliberately not cards:
 * they collide with EAN-13 barcodes and GTIN-14 codes, which retail data is full of and a tenth of
 * which pass Luhn by chance, and no issuer has used those lengths for years. Nothing longer than a
 * card is a card either, so a UUID or an all-digit identifier is never cut into pieces to find one.
 * And a run that sits inside a UUID's 8-4-4-4-12 shape is an identifier whatever its digits add up
 * to: one v7 id in fifty thousand has all-digit groups that line up to a Luhn-valid number in an
 * issuer's range, and a request carrying two ids and a role was refused as a card (SJ-D67).
 *
 * <p>This keeps honest integrations honest; it is not a defence against someone determined to
 * smuggle a card number past the gateway in an encoding of their own, which would not make it a
 * card number to anything upstream either.
 */
public final class CardData {

  /** Digit counts that issuers actually use; 13 and 14 collide with retail barcodes. */
  private static final List<Integer> CARD_LENGTHS = List.of(15, 16, 19);

  /** A run of this many digits or more is an identifier, not a card, and is skipped whole. */
  private static final int LONGEST_CARD = 19;

  /** A UUID's shape, hex in either case, not touching more hex on either side. */
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "(?<![0-9A-Fa-f])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"
              + "-[0-9A-Fa-f]{12}(?![0-9A-Fa-f])");

  private CardData() {}

  /** Where a card number sits in the text, by character offsets. */
  public record Match(int start, int end, String digits) {}

  /**
   * The first card number in the text.
   *
   * @param text the text to scan; null is empty
   * @return the match, or empty when there is none
   */
  public static Optional<Match> find(CharSequence text) {
    if (text == null) {
      return Optional.empty();
    }
    int n = text.length();
    BitSet identifiers = uuidSpans(text);
    int i = 0;
    while (i < n) {
      if (!cardDigit(text, identifiers, i)) {
        i++;
        continue;
      }
      // A run: digit groups separated by exactly one space or hyphen. A card may start and end on
      // any group boundary — "ref 7 4111 1111 1111 1111" carries one — but a single unbroken
      // group is taken whole, so a long identifier is never cut into pieces to find one.
      List<int[]> groups = new ArrayList<>();
      int end = i;
      while (end < n && cardDigit(text, identifiers, end)) {
        int gs = end;
        while (end < n && cardDigit(text, identifiers, end)) {
          end++;
        }
        groups.add(new int[] {gs, end});
        if (end + 1 < n
            && (text.charAt(end) == ' ' || text.charAt(end) == '-')
            && cardDigit(text, identifiers, end + 1)) {
          end++;
        }
      }
      for (int a = 0; a < groups.size(); a++) {
        StringBuilder digits = new StringBuilder();
        for (int b = a; b < groups.size(); b++) {
          digits.append(text, groups.get(b)[0], groups.get(b)[1]);
          if (digits.length() > LONGEST_CARD) {
            break;
          }
          if (isCard(digits)) {
            return Optional.of(new Match(groups.get(a)[0], groups.get(b)[1], digits.toString()));
          }
        }
      }
      i = end;
    }
    return Optional.empty();
  }

  /**
   * Whether the text carries a card number anywhere.
   *
   * @param text the text to scan
   * @return true when {@link #find} would match
   */
  public static boolean containsPan(CharSequence text) {
    return find(text).isPresent();
  }

  /**
   * The text with every card number masked to its last four digits, separators kept, so a log line
   * still shows that a card number was there and which card, but not the number.
   *
   * @param text the text to mask; null is returned as null
   * @return the masked text
   */
  public static String mask(String text) {
    if (text == null) {
      return null;
    }
    String out = text;
    int from = 0;
    while (true) {
      Optional<Match> m = find(out.substring(from));
      if (m.isEmpty()) {
        return out;
      }
      int start = from + m.get().start();
      int end = from + m.get().end();
      StringBuilder masked = new StringBuilder();
      int keepFrom = m.get().digits().length() - 4;
      int seen = 0;
      for (int k = start; k < end; k++) {
        char c = out.charAt(k);
        if (Character.isDigit(c)) {
          masked.append(seen < keepFrom ? '*' : c);
          seen++;
        } else {
          masked.append(c);
        }
      }
      out = out.substring(0, start) + masked + out.substring(end);
      from = start + masked.length();
    }
  }

  /** A digit that may be part of a card: not one inside a UUID's shape. */
  private static boolean cardDigit(CharSequence text, BitSet identifiers, int at) {
    return Character.isDigit(text.charAt(at)) && !identifiers.get(at);
  }

  /** The character positions that sit inside a UUID's 8-4-4-4-12 shape. */
  private static BitSet uuidSpans(CharSequence text) {
    BitSet spans = new BitSet();
    Matcher m = UUID_SHAPE.matcher(text);
    while (m.find()) {
      spans.set(m.start(), m.end());
    }
    return spans;
  }

  private static boolean isCard(CharSequence digits) {
    int len = digits.length();
    if (!CARD_LENGTHS.contains(len) || !luhn(digits)) {
      return false;
    }
    int p2 = prefix(digits, 2);
    int p3 = prefix(digits, 3);
    int p4 = prefix(digits, 4);
    int p6 = prefix(digits, 6);
    return switch (len) {
      case 15 -> p2 == 34 || p2 == 37;
      case 16 ->
          digits.charAt(0) == '4'
              || (p2 >= 51 && p2 <= 55)
              || (p4 >= 2221 && p4 <= 2720)
              || isDiscover(p2, p3, p4, p6)
              || (p4 >= 3528 && p4 <= 3589)
              || p2 == 62;
      case 19 ->
          digits.charAt(0) == '4'
              || isDiscover(p2, p3, p4, p6)
              || (p4 >= 3528 && p4 <= 3589)
              || p2 == 62;
      default -> false;
    };
  }

  private static boolean isDiscover(int p2, int p3, int p4, int p6) {
    return p4 == 6011 || (p3 >= 644 && p3 <= 649) || p2 == 65 || (p6 >= 622126 && p6 <= 622925);
  }

  private static int prefix(CharSequence digits, int count) {
    return Integer.parseInt(digits.subSequence(0, count).toString());
  }

  /** The Luhn check every card number passes and nine in ten random numbers fail. */
  static boolean luhn(CharSequence digits) {
    int sum = 0;
    boolean doubleIt = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubleIt) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      doubleIt = !doubleIt;
    }
    return sum % 10 == 0;
  }
}
