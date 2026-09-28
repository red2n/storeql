package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ledger's one invariant, checked where lines are made: every journal balances, every line is a
 * debit or a credit, nothing is negative.
 */
class LedgerPostingTest {

  private static final UUID T = Ids.newId();
  private static final LocalDate D = LocalDate.of(2026, 9, 13);

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static LedgerPosting posting() {
    return LedgerPosting.of(T, D, "Test posting", Domain.SOURCE_JOURNAL, null, null);
  }

  @Test
  @DisplayName(
      "A description longer than the ledger's column is cut to fit, ending with an ellipsis, so a"
          + " long reason never fails the posting")
  void aLongDescriptionIsCutToTheColumn() {
    String reason = "r".repeat(500);
    var lines =
        LedgerPosting.of(
                T,
                D,
                "Freight on receipt #1a2b3c4d reversed: " + reason,
                Domain.SOURCE_JOURNAL,
                null,
                null)
            .debit("2109", "GR/IR", d("1.00"))
            .credit("1001", "Stock", d("1.00"))
            .build();
    String said = lines.get(0).description();
    assertThat(said.length(), is(LedgerPosting.DESCRIPTION_MAX));
    assertThat(said.startsWith("Freight on receipt #1a2b3c4d reversed: rrr"), is(true));
    assertThat(said.endsWith("…"), is(true));
    String exact = "x".repeat(LedgerPosting.DESCRIPTION_MAX);
    assertThat(
        LedgerPosting.of(T, D, exact, Domain.SOURCE_JOURNAL, null, null)
            .debit("1", "a", d("1"))
            .credit("2", "b", d("1"))
            .build()
            .get(0)
            .description(),
        is(exact));
  }

  @Test
  @DisplayName("Two sides that agree build, share one journal id and carry the header")
  void balancedBuilds() {
    UUID store = Ids.newId();
    UUID doc = Ids.newId();
    var lines =
        LedgerPosting.of(T, D, "  Goods received  ", Domain.SOURCE_GOODS_RECEIPT, doc, store)
            .debit("1001", "Stock", d("150.00"))
            .credit("2109", "GR/IR", d("150.00"))
            .build();
    assertThat(lines, hasSize(2));
    assertThat(lines.get(0).journalId(), notNullValue());
    assertThat(lines.get(1).journalId(), is(lines.get(0).journalId()));
    assertThat(lines.get(0).description(), is("Goods received"));
    assertThat(lines.get(0).sourceType(), is(Domain.SOURCE_GOODS_RECEIPT));
    assertThat(lines.get(0).sourceRef(), is(doc));
    assertThat(lines.get(0).storeId(), is(store));
    assertThat(lines.get(0).tenantId(), is(T));
    assertThat(lines.get(0).entryDate(), is(D));
    assertThat(lines.get(0).debit(), comparesEqualTo(d("150.00")));
    assertThat(lines.get(0).credit(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(lines.get(1).credit(), comparesEqualTo(d("150.00")));
  }

  @Test
  @DisplayName("Three lines that balance build; the totals are the sums")
  void threeLines() {
    var p =
        posting()
            .debit("2109", "GR/IR", d("150.00"))
            .debit("2201", "VAT input", d("30.00"))
            .credit("2100", "Creditors", d("180.00"));
    assertThat(p.balanced(), is(true));
    assertThat(p.totalDebit(), comparesEqualTo(d("180.00")));
    assertThat(p.totalCredit(), comparesEqualTo(d("180.00")));
    assertThat(p.build(), hasSize(3));
  }

  @Test
  @DisplayName("Debits and credits that disagree do not build — by a penny or by a lot")
  void unbalancedRefused() {
    var penny = posting().debit("1001", "Stock", d("100.00")).credit("2109", "GR/IR", d("99.99"));
    assertThat(penny.balanced(), is(false));
    assertThrows(IllegalStateException.class, penny::build);
    var lot = posting().debit("1001", "Stock", d("100.00")).credit("2109", "GR/IR", d("1.00"));
    assertThrows(IllegalStateException.class, lot::build);
  }

  @Test
  @DisplayName("Fewer than two lines is not a journal")
  void tooFewLines() {
    assertThrows(IllegalStateException.class, () -> posting().build());
    // A zero line adds nothing, so one real line plus a zero line is still one line.
    assertThrows(
        IllegalStateException.class,
        () -> posting().debit("1001", "Stock", d("0")).credit("2109", "x", d("0")).build());
  }

  @Test
  @DisplayName("A negative amount, or a line that is both a debit and a credit, is refused at once")
  void badLinesRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> posting().debit("1001", "Stock", d("-1.00")));
    assertThrows(
        IllegalArgumentException.class, () -> posting().credit("1001", "Stock", d("-0.01")));
    assertThrows(
        IllegalArgumentException.class,
        () -> posting().line("1001", "Stock", d("10.00"), d("10.00")));
  }

  @Test
  @DisplayName("A nominal code is one to ten letters or digits; anything else is refused")
  void codeShape() {
    assertThrows(IllegalArgumentException.class, () -> posting().debit("", "x", d("1")));
    assertThrows(IllegalArgumentException.class, () -> posting().debit(null, "x", d("1")));
    assertThrows(IllegalArgumentException.class, () -> posting().debit("12345678901", "x", d("1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> posting().debit("1001; DROP TABLE nominal_ledger_entries", "x", d("1")));
    assertThrows(IllegalArgumentException.class, () -> posting().debit("10 01", "x", d("1")));
    // Letters are fine: some charts use them.
    posting().debit("STOCK", "x", d("1"));
  }

  @Test
  @DisplayName("A name that is missing falls back to the code; a name is trimmed")
  void nameFallback() {
    var lines = posting().debit("1001", null, d("1")).credit("2109", "  GR/IR  ", d("1")).build();
    assertThat(lines.get(0).nominalName(), is("1001"));
    assertThat(lines.get(1).nominalName(), is("GR/IR"));
  }

  @Test
  @DisplayName("Fifty lines is the most; the fifty-first is refused")
  void lineCap() {
    var p = posting();
    for (int i = 0; i < 25; i++) {
      p.debit("1001", "x", d("1")).credit("2109", "y", d("1"));
    }
    assertThat(p.size(), is(50));
    assertThrows(IllegalArgumentException.class, () -> p.debit("1001", "x", d("1")));
  }

  @Test
  @DisplayName("A posting needs a tenant, a date, a source and a description")
  void headerRequired() {
    assertThrows(
        IllegalArgumentException.class,
        () -> LedgerPosting.of(null, D, "x", Domain.SOURCE_JOURNAL, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> LedgerPosting.of(T, null, "x", Domain.SOURCE_JOURNAL, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> LedgerPosting.of(T, D, "x", null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> LedgerPosting.of(T, D, "   ", Domain.SOURCE_JOURNAL, null, null));
  }

  @Test
  @DisplayName("A reversal mirrors every line on a new journal and keeps the document and store")
  void reversal() {
    UUID store = Ids.newId();
    UUID doc = Ids.newId();
    var original =
        LedgerPosting.of(T, D, "Supplier invoice INV-1", Domain.SOURCE_SUPPLIER_INVOICE, doc, store)
            .debit("2109", "GR/IR", d("150.00"))
            .debit("2201", "VAT input", d("30.00"))
            .credit("2100", "Creditors", d("180.00"))
            .build();
    var reversed =
        LedgerPosting.reversalOf(
                original, D.plusDays(3), "Rejected INV-1", Domain.SOURCE_INVOICE_REVERSAL)
            .build();
    assertThat(reversed, hasSize(3));
    assertThat(reversed.get(0).journalId(), is(not(original.get(0).journalId())));
    assertThat(reversed.get(0).nominalCode(), is("2109"));
    assertThat(reversed.get(0).credit(), comparesEqualTo(d("150.00")));
    assertThat(reversed.get(0).debit(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(reversed.get(2).nominalCode(), is("2100"));
    assertThat(reversed.get(2).debit(), comparesEqualTo(d("180.00")));
    assertThat(reversed.get(0).sourceRef(), is(doc));
    assertThat(reversed.get(0).storeId(), is(store));
    assertThat(reversed.get(0).sourceType(), is(Domain.SOURCE_INVOICE_REVERSAL));
    assertThat(reversed.get(0).entryDate(), is(D.plusDays(3)));
    assertThrows(
        IllegalArgumentException.class,
        () -> LedgerPosting.reversalOf(List.of(), D, "x", Domain.SOURCE_INVOICE_REVERSAL));
  }

  private static <T> org.hamcrest.Matcher<T> not(T value) {
    return org.hamcrest.Matchers.not(value);
  }
}
