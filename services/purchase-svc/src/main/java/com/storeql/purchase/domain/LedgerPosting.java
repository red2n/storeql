package com.storeql.purchase.domain;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One double-entry posting: a journal of two or more lines that debit and credit the same total.
 *
 * <p>The ledger's invariant is that every journal balances, and this is the only way lines are
 * built, so the invariant is checked once, here, rather than trusted to each caller. A builder
 * whose debits and credits disagree refuses to build; a line with a negative amount, or with both a
 * debit and a credit, is refused at the point it is added. The lines share a journal id, so the
 * posting can be read back whole and reversed line for line.
 */
public final class LedgerPosting {

  /** The most lines one journal may carry. A manual journal longer than this is a data load. */
  public static final int MAX_LINES = 50;

  private final UUID tenantId;
  private final UUID journalId;
  private final LocalDate entryDate;
  private final String description;
  private final String sourceType;
  private final UUID sourceRef;
  private final UUID storeId;
  private final List<Line> lines = new ArrayList<>();

  private record Line(String code, String name, BigDecimal debit, BigDecimal credit) {}

  /**
   * The longest description the ledger keeps ({@code nominal_ledger_entries.description
   * VARCHAR(500)}). A longer one — a reversal's reason can be 500 characters on its own — is cut to
   * fit and ends with an ellipsis, so the posting never fails on its words.
   */
  public static final int DESCRIPTION_MAX = 500;

  private static String fit(String description) {
    return description.length() <= DESCRIPTION_MAX
        ? description
        : description.substring(0, DESCRIPTION_MAX - 1) + "\u2026";
  }

  private LedgerPosting(
      UUID tenantId,
      LocalDate entryDate,
      String description,
      String sourceType,
      UUID sourceRef,
      UUID storeId) {
    if (tenantId == null || entryDate == null || sourceType == null) {
      throw new IllegalArgumentException("a posting needs a tenant, a date and a source");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("a posting needs a description");
    }
    this.tenantId = tenantId;
    this.journalId = Ids.newId();
    this.entryDate = entryDate;
    this.description = fit(description.trim());
    this.sourceType = sourceType;
    this.sourceRef = sourceRef;
    this.storeId = storeId;
  }

  /**
   * Starts a posting.
   *
   * @param tenantId the owning tenant
   * @param entryDate the date the posting belongs to
   * @param description what it records, shown on every line
   * @param sourceType one of the {@code Domain.SOURCE_*} constants
   * @param sourceRef the document that produced it, or null for a manual journal
   * @param storeId the store it belongs to, or null for a tenant-level journal
   * @return an empty posting to add lines to
   */
  public static LedgerPosting of(
      UUID tenantId,
      LocalDate entryDate,
      String description,
      String sourceType,
      UUID sourceRef,
      UUID storeId) {
    return new LedgerPosting(tenantId, entryDate, description, sourceType, sourceRef, storeId);
  }

  /** Adds a debit line. A zero amount adds nothing, so callers need not special-case it. */
  public LedgerPosting debit(String code, String name, BigDecimal amount) {
    return line(code, name, amount, BigDecimal.ZERO);
  }

  /** Adds a credit line. A zero amount adds nothing. */
  public LedgerPosting credit(String code, String name, BigDecimal amount) {
    return line(code, name, BigDecimal.ZERO, amount);
  }

  /**
   * Adds a line carrying either a debit or a credit — the shape a manual journal arrives in.
   *
   * @throws IllegalArgumentException when both sides are non-zero, either is negative, or the code
   *     is not a nominal code
   */
  public LedgerPosting line(String code, String name, BigDecimal debit, BigDecimal credit) {
    BigDecimal dr = debit == null ? BigDecimal.ZERO : debit;
    BigDecimal cr = credit == null ? BigDecimal.ZERO : credit;
    if (dr.signum() < 0 || cr.signum() < 0) {
      throw new IllegalArgumentException("a ledger line cannot carry a negative amount");
    }
    if (dr.signum() > 0 && cr.signum() > 0) {
      throw new IllegalArgumentException("a ledger line is a debit or a credit, not both");
    }
    if (dr.signum() == 0 && cr.signum() == 0) {
      return this;
    }
    if (code == null || !code.matches("[A-Za-z0-9]{1,10}")) {
      throw new IllegalArgumentException("nominal code must be 1-10 letters or digits");
    }
    if (lines.size() >= MAX_LINES) {
      throw new IllegalArgumentException("a journal carries at most " + MAX_LINES + " lines");
    }
    lines.add(new Line(code, name == null || name.isBlank() ? code : name.trim(), dr, cr));
    return this;
  }

  public UUID journalId() {
    return journalId;
  }

  public BigDecimal totalDebit() {
    return lines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public BigDecimal totalCredit() {
    return lines.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Whether the debits and credits agree. A posting with no lines is trivially balanced. */
  public boolean balanced() {
    return totalDebit().compareTo(totalCredit()) == 0;
  }

  public int size() {
    return lines.size();
  }

  /**
   * The journal's lines, ready to insert.
   *
   * @throws IllegalStateException when the posting has fewer than two lines or does not balance
   */
  public List<NominalLedgerEntry> build() {
    if (lines.size() < 2) {
      throw new IllegalStateException("a journal needs at least two lines");
    }
    if (!balanced()) {
      throw new IllegalStateException(
          "journal does not balance: debits " + totalDebit() + ", credits " + totalCredit());
    }
    Instant now = Instant.now();
    List<NominalLedgerEntry> out = new ArrayList<>(lines.size());
    for (Line l : lines) {
      out.add(
          new NominalLedgerEntry(
              Ids.newId(),
              tenantId,
              entryDate,
              l.code(),
              l.name(),
              l.debit(),
              l.credit(),
              description,
              sourceRef,
              now,
              journalId,
              sourceType,
              storeId));
    }
    return out;
  }

  /**
   * The mirror image of an existing journal: every debit becomes a credit and every credit a debit,
   * on a new journal id, so the original nets to nothing without a row being touched.
   *
   * @param original the lines to reverse
   * @param entryDate the date of the reversal
   * @param description what the reversal records
   * @param sourceType the reversal's source type
   * @return the reversing posting, ready to build
   */
  public static LedgerPosting reversalOf(
      List<NominalLedgerEntry> original,
      LocalDate entryDate,
      String description,
      String sourceType) {
    if (original.isEmpty()) {
      throw new IllegalArgumentException("nothing to reverse");
    }
    NominalLedgerEntry first = original.get(0);
    LedgerPosting p =
        new LedgerPosting(
            first.tenantId(),
            entryDate,
            description,
            sourceType,
            first.sourceRef(),
            first.storeId());
    for (NominalLedgerEntry e : original) {
      p.line(e.nominalCode(), e.nominalName(), e.credit(), e.debit());
    }
    return p;
  }
}
