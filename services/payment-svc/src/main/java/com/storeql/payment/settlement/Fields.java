package com.storeql.payment.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Reads the amounts and the times of a settlement file, refusing what it cannot read exactly. */
final class Fields {

  static final String INVALID = "SETTLEMENT_FILE_INVALID";

  private static final DateTimeFormatter SPACED =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int LONGEST_REFERENCE = 255;

  /** The longest an amount's cell may be, its spaces taken out, before it is read at all. */
  static final int LONGEST_AMOUNT = 40;

  private Fields() {}

  /** Where a row sits in the file a person opens: the header is line 1. */
  static String at(int row) {
    return "Line " + (row + 2) + ": ";
  }

  /**
   * An amount in major units with a point: money is never a float, and a file that writes 1.234,56
   * or five decimal places is not guessed at.
   *
   * @return the amount to four places, or null when the cell is empty
   */
  static BigDecimal amount(String cell, int row, String column) {
    if (cell == null) return null;
    String text = cell.replace(" ", "");
    // Bounded before it is read: reading a number, and stripping its trailing zeros, cost time
    // that grows faster than its length, and a cell may be 2,000 characters in a file of 20,000
    // lines. Forty characters hold any amount a payout carries (fourteen whole digits, a sign, a
    // point and four places) with twenty zeros to spare.
    if (text.length() > LONGEST_AMOUNT) {
      throw new SettlementFileException(INVALID, at(row) + column + " is too long to be an amount");
    }
    try {
      BigDecimal value = new BigDecimal(text);
      if ((long) value.precision() - value.scale() > 14) {
        throw new SettlementFileException(INVALID, at(row) + column + " is too large an amount");
      }
      // Judged on the digits written before it is rescaled: 1E-80000000 is twelve characters, and
      // rescaling it to four places would build a power of ten eighty million digits long.
      // Trailing zeros are not precision (10.000000 is four places), and the bound on the cell
      // above bounds what stripping them costs.
      if (value.scale() > 4 && value.stripTrailingZeros().scale() > 4) {
        throw new SettlementFileException(
            INVALID, at(row) + column + " has more than four decimal places");
      }
      return value.setScale(4, java.math.RoundingMode.UNNECESSARY);
    } catch (NumberFormatException e) {
      throw new SettlementFileException(
          INVALID, at(row) + column + " is not an amount written like 1234.56", e);
    } catch (ArithmeticException e) {
      throw new SettlementFileException(
          INVALID, at(row) + column + " has more than four decimal places", e);
    }
  }

  static BigDecimal amountOrZero(String cell, int row, String column) {
    BigDecimal value = amount(cell, row, column);
    return value == null ? BigDecimal.ZERO.setScale(4) : value;
  }

  /**
   * A time as ISO-8601, as {@code 2026-09-15 10:22:31} or as a date alone; without an offset of its
   * own it is read in {@code zone}, which is UTC when the file names none this platform knows.
   *
   * @return the instant, or null when the cell is empty
   */
  static Instant time(String cell, String zone, int row, String column) {
    if (cell == null) return null;
    ZoneId in = zoneOf(zone);
    try {
      if (cell.length() == 10) return LocalDate.parse(cell).atStartOfDay(in).toInstant();
      if (cell.indexOf('T') < 0) return LocalDateTime.parse(cell, SPACED).atZone(in).toInstant();
      if (cell.endsWith("Z") || cell.lastIndexOf('+') > 10 || cell.lastIndexOf('-') > 10) {
        return java.time.OffsetDateTime.parse(cell).toInstant();
      }
      return LocalDateTime.parse(cell).atZone(in).toInstant();
    } catch (DateTimeParseException e) {
      throw new SettlementFileException(
          INVALID, at(row) + column + " is not a date or a time this layout uses", e);
    }
  }

  static LocalDate date(String cell, int row, String column) {
    Instant at = time(cell, null, row, column);
    return at == null ? null : at.atOffset(ZoneOffset.UTC).toLocalDate();
  }

  private static ZoneId zoneOf(String zone) {
    if (zone == null || zone.isBlank()) return ZoneOffset.UTC;
    try {
      return ZoneId.of(zone.strip(), ZoneId.SHORT_IDS);
    } catch (java.time.DateTimeException e) {
      // CEST, BST and their kind are not zones the JDK knows; the time only breaks ties in
      // matching.
      return ZoneOffset.UTC;
    }
  }

  /** A reference as the acquirer wrote it, or null; one longer than the column holds is refused. */
  static String reference(String cell, int row, String column) {
    if (cell == null) return null;
    if (cell.length() > LONGEST_REFERENCE) {
      throw new SettlementFileException(INVALID, at(row) + column + " is too long a reference");
    }
    return cell;
  }

  static String currency(String cell, int row) {
    if (cell == null) return null;
    String code = cell.toUpperCase(Locale.ROOT);
    if (code.length() != 3 || !code.chars().allMatch(Character::isLetter)) {
      throw new SettlementFileException(INVALID, at(row) + "currency is not a three-letter code");
    }
    return code;
  }

  /**
   * What every row of a file must agree on — its payout, its currency — or the file is two payouts,
   * and a batch is one payment into the bank.
   *
   * @return the value the file has settled on so far
   */
  static String same(String known, String here, int row, String what) {
    if (here == null || here.equals(known)) return known;
    if (known == null) return here;
    throw new SettlementFileException(
        "SETTLEMENT_FILE_MANY_PAYOUTS",
        at(row) + "the file covers more than one " + what + ": import one payout at a time");
  }
}
