package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A Bacs Standard 18 direct credit file (17.12): the file a sterling payment run hands a UK bank.
 *
 * <p>Written to the Standard 18 layout as a bank publishes it for customers (HSBC's message
 * implementation guide): the labels VOL1, HDR1, HDR2 and UHL1 at 80 characters; one standard record
 * of 100 characters per supplier with transaction code 99, a direct credit; one contra record, code
 * 17, debiting the business's own account with the total; then EOF1, EOF2 and UTL1 with the totals
 * and counts. A single processing day file. Bacs carries upper-case letters, digits, full stop,
 * ampersand, slash, hyphen and space, so names and references are reduced to those and cut to their
 * 18 characters.
 *
 * <p>Bacs runs a three-day cycle: a file submitted on day one is processed on day two and the money
 * is in the supplier's account on day three. The processing day written into the file is the
 * banking day before the value date. Weekends are skipped here; UK bank holidays are the bank's to
 * refuse, and it does.
 */
public final class Bacs18 {

  /** The most one Bacs credit carries: £20,000,000.00. */
  public static final long MAX_PENCE = 2_000_000_000L;

  /** The most the contra's eleven digits carry. */
  static final long MAX_TOTAL_PENCE = 99_999_999_999L;

  private static final Pattern NOT_BACS = Pattern.compile("[^A-Z0-9.&/\\- ]");
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
  private static final Pattern THREE_DIGITS = Pattern.compile("\\d{3}");
  private static final Pattern SERIAL = Pattern.compile("[A-Z0-9]{6}");

  private Bacs18() {}

  /**
   * The business sending the file.
   *
   * @param serviceUserNumber the six-digit SUN Bacs assigned the business
   * @param name the name of the account debited, as the bank holds it
   */
  public record Originator(
      String serviceUserNumber, String sortCode, String accountNumber, String name) {}

  /** One credit to a supplier. */
  public record Credit(
      String sortCode,
      String accountNumber,
      BigDecimal amount,
      String reference,
      String payeeName) {}

  /**
   * The file.
   *
   * @param serialNumber six characters; Bacs refuses a serial it has seen in the last three months
   * @param fileNumber three digits, unique for the day
   * @param processingDay the banking day before the value date
   * @param contraNarrative what the business's own statement shows for the debit
   */
  public record Submission(
      String serialNumber,
      String fileNumber,
      LocalDate creationDate,
      LocalDate processingDay,
      Originator originator,
      List<Credit> credits,
      String contraNarrative) {}

  /**
   * Writes the file.
   *
   * @return the records, CRLF-terminated
   * @throws IllegalArgumentException naming what Bacs would refuse
   */
  public static String write(Submission s) {
    Originator o = originator(s.originator());
    String serial = s.serialNumber() == null ? "" : s.serialNumber().toUpperCase(Locale.ROOT);
    if (!SERIAL.matcher(serial).matches() || "000000".equals(serial)) {
      throw new IllegalArgumentException("a Bacs serial number is six letters or digits");
    }
    if (s.fileNumber() == null || !THREE_DIGITS.matcher(s.fileNumber()).matches()) {
      throw new IllegalArgumentException("a Bacs file number is three digits");
    }
    if (s.creationDate() == null || s.processingDay() == null) {
      throw new IllegalArgumentException("a Bacs file needs its creation date and processing day");
    }
    if (!s.processingDay().isAfter(s.creationDate())) {
      throw new IllegalArgumentException("the Bacs processing day must be after the file is made");
    }
    if (weekend(s.processingDay())) {
      throw new IllegalArgumentException("the Bacs processing day must be a banking day");
    }
    List<Credit> credits = s.credits() == null ? List.of() : s.credits();
    if (credits.isEmpty()) {
      throw new IllegalArgumentException("a Bacs file needs at least one credit");
    }

    List<String> records = new ArrayList<>();
    String sun = o.serviceUserNumber();
    String hdr1 =
        "HDR1A"
            + sun
            + "S"
            + blanks(3)
            + sun
            + serial
            + "0001"
            + "0001"
            + blanks(6)
            + julian(s.creationDate())
            + julian(s.processingDay().plusDays(1))
            + " "
            + "000000"
            + blanks(20);
    String hdr2 = "HDR2F0200000100" + blanks(35) + "00" + blanks(28);
    records.add("VOL1" + serial + " " + blanks(30) + sun + blanks(32) + "1");
    records.add(hdr1);
    records.add(hdr2);
    records.add(
        "UHL1"
            + julian(s.processingDay())
            + "999999"
            + blanks(4)
            + "00"
            + "000000"
            + "1 DAILY  "
            + s.fileNumber()
            + blanks(40));

    long total = 0;
    for (Credit c : credits) {
      String who = "payee " + c.payeeName();
      String sort;
      String account;
      try {
        sort = BankAccount.sortCode(c.sortCode());
        account = BankAccount.accountNumber(c.accountNumber());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(who + ": " + e.getMessage(), e);
      }
      long pence = pence(c.amount(), who);
      total += pence;
      if (total > MAX_TOTAL_PENCE) {
        throw new IllegalArgumentException("a Bacs file totals at most £999,999,999.99");
      }
      records.add(
          sort
              + account
              + "0"
              + "99"
              + o.sortCode()
              + o.accountNumber()
              + blanks(4)
              + zeros(pence, 11)
              + text(o.name())
              + text(c.reference())
              + text(c.payeeName()));
    }
    records.add(
        o.sortCode()
            + o.accountNumber()
            + "0"
            + "17"
            + o.sortCode()
            + o.accountNumber()
            + blanks(4)
            + zeros(total, 11)
            + text(s.contraNarrative())
            + text("CONTRA")
            + text(o.name()));
    records.add("EOF1" + hdr1.substring(4, 54) + blanks(6) + hdr1.substring(60));
    records.add("EOF2" + hdr2.substring(4));
    records.add(
        "UTL1"
            + zeros(total, 13)
            + zeros(total, 13)
            + zeros(1, 7)
            + zeros(credits.size(), 7)
            + blanks(36));

    for (String r : records) {
      // Labels start with their name; standard and contra records with a sort code.
      int expected = Character.isDigit(r.charAt(0)) ? 100 : 80;
      if (r.length() != expected) {
        throw new IllegalStateException("a Bacs record came out " + r.length() + " long");
      }
    }
    return String.join("\r\n", records) + "\r\n";
  }

  /** The processing day for money to arrive on a value date: the banking day before it. */
  public static LocalDate processingDayFor(LocalDate valueDate) {
    return previousBankingDay(valueDate);
  }

  /**
   * The earliest value date for a file submitted today: processed the next banking day, in the
   * supplier's account the banking day after that.
   */
  public static LocalDate earliestValueDate(LocalDate today) {
    return nextBankingDay(nextBankingDay(today));
  }

  /** {@code bYYDDD}: a blank, the year's last two digits and the day of the year. */
  static String julian(LocalDate d) {
    return String.format(Locale.ROOT, " %02d%03d", d.getYear() % 100, d.getDayOfYear());
  }

  /** Eighteen characters of what Bacs carries: upper case, anything else a space, padded. */
  static String text(String s) {
    String t = s == null ? "" : NOT_BACS.matcher(s.toUpperCase(Locale.ROOT)).replaceAll(" ");
    t = t.length() > 18 ? t.substring(0, 18) : t;
    return t + blanks(18 - t.length());
  }

  private static Originator originator(Originator o) {
    if (o == null) throw new IllegalArgumentException("a Bacs file needs the paying account");
    if (o.serviceUserNumber() == null || !SIX_DIGITS.matcher(o.serviceUserNumber()).matches()) {
      throw new IllegalArgumentException("a Bacs service user number is six digits");
    }
    if (o.name() == null || text(o.name()).isBlank()) {
      throw new IllegalArgumentException("the paying account needs its name as the bank holds it");
    }
    try {
      return new Originator(
          o.serviceUserNumber(),
          BankAccount.sortCode(o.sortCode()),
          BankAccount.accountNumber(o.accountNumber()),
          o.name());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("the paying account: " + e.getMessage(), e);
    }
  }

  private static long pence(BigDecimal amount, String who) {
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException(who + " is paid nothing");
    }
    // Two places on purpose, not as an assumption about money: Bacs moves sterling only (the
    // service writes this file for a GBP run alone), and the scheme counts it in whole pence.
    if (amount.stripTrailingZeros().scale() > 2) {
      throw new IllegalArgumentException(who + " is paid in fractions of a penny");
    }
    long pence = amount.movePointRight(2).longValueExact();
    if (pence > MAX_PENCE) {
      throw new IllegalArgumentException(who + " is paid more than one Bacs credit carries");
    }
    return pence;
  }

  private static boolean weekend(LocalDate d) {
    return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
  }

  private static LocalDate nextBankingDay(LocalDate d) {
    LocalDate next = d.plusDays(1);
    while (weekend(next)) next = next.plusDays(1);
    return next;
  }

  private static LocalDate previousBankingDay(LocalDate d) {
    LocalDate prev = d.minusDays(1);
    while (weekend(prev)) prev = prev.minusDays(1);
    return prev;
  }

  private static String zeros(long value, int width) {
    String v = Long.toString(value);
    return "0".repeat(Math.max(0, width - v.length())) + v;
  }

  private static String blanks(int n) {
    return " ".repeat(n);
  }
}
