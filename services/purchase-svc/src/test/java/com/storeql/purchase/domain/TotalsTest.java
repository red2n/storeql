package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Purchase-order totals (SJ-D22), across the five markets StoreQL is expected to trade in — UK, US,
 * China, Japan and India.
 *
 * <p>Currency is the whole point of testing it this way. Four of those five have two minor units
 * and behave identically; the yen has none, and a single {@code setScale(2)} anywhere in the chain
 * would pass every test written against sterling and produce an uninvoiceable figure in Tokyo.
 */
class TotalsTest {

  private static final UUID T = Ids.newId();
  private static final UUID PO = Ids.newId();

  private static PurchaseOrderLine line(String qty, String unitPrice, String vatCode) {
    return new PurchaseOrderLine(
        Ids.newId(),
        T,
        PO,
        Ids.newId(),
        new BigDecimal(qty),
        new BigDecimal(unitPrice),
        vatCode,
        Instant.now(),
        null);
  }

  // ── the defect itself ───────────────────────────────────────────────────────

  @Test
  @DisplayName("SJ-D22: lines produce a non-zero total — the whole defect was that they did not")
  void linesProduceATotal() {
    Totals t =
        Totals.of(
            List.of(line("10", "8.00", "T1"), line("3", "2.50", "T1")),
            "GBP",
            Map.of("T1", new BigDecimal("0.20")));

    assertThat(t.net(), comparesEqualTo(new BigDecimal("87.50")));
    assertThat(t.vat(), comparesEqualTo(new BigDecimal("17.50")));
    assertThat(t.gross(), comparesEqualTo(new BigDecimal("105.00")));
  }

  @Test
  @DisplayName("An order with no lines totals zero, at the currency's own scale")
  void noLines() {
    assertThat(Totals.of(List.of(), "GBP", Map.of()).net(), is(new BigDecimal("0.00")));
    assertThat(Totals.of(List.of(), "JPY", Map.of()).net(), is(new BigDecimal("0")));
  }

  // ── multi-currency ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("JPY has no minor unit: every figure lands on a whole yen")
  void yenHasNoMinorUnit() {
    // 3 × ¥1,234 = ¥3,702, consumption tax 10% = ¥370.2, which must not survive as ¥370.20.
    Totals t = Totals.of(line3("1234", "JPY"), "JPY", Map.of("T1", new BigDecimal("0.10")));

    assertThat(t.net().scale(), is(0));
    assertThat(t.vat().scale(), is(0));
    assertThat(t.gross().scale(), is(0));
    assertThat(t.net(), comparesEqualTo(new BigDecimal("3702")));
    assertThat(t.vat(), comparesEqualTo(new BigDecimal("370")));
    assertThat(t.gross(), comparesEqualTo(new BigDecimal("4072")));
  }

  @Test
  @DisplayName("USD, GBP, INR and CNY all carry two minor units")
  void twoMinorUnitCurrencies() {
    for (String currency : List.of("USD", "GBP", "INR", "CNY")) {
      Totals t = Totals.of(line3("10.00", currency), currency, Map.of());
      assertThat("scale for " + currency, t.net().scale(), is(2));
      assertThat("net for " + currency, t.net(), comparesEqualTo(new BigDecimal("30.00")));
    }
  }

  @Test
  @DisplayName("India: GST at 18% on a rupee order rounds to the paisa")
  void indiaGst() {
    // 7 × ₹149.99 = ₹1,049.93; 18% of that is ₹188.9874, which must round to ₹188.99.
    Totals t =
        Totals.of(
            List.of(line("7", "149.99", "GST18")), "INR", Map.of("GST18", new BigDecimal("0.18")));

    assertThat(t.net(), comparesEqualTo(new BigDecimal("1049.93")));
    assertThat(t.vat(), comparesEqualTo(new BigDecimal("188.99")));
    assertThat(t.gross(), comparesEqualTo(new BigDecimal("1238.92")));
  }

  @Test
  @DisplayName("China: 13% VAT on a yuan order")
  void chinaVat() {
    Totals t =
        Totals.of(List.of(line("4", "88.50", "T1")), "CNY", Map.of("T1", new BigDecimal("0.13")));

    assertThat(t.net(), comparesEqualTo(new BigDecimal("354.00")));
    assertThat(t.vat(), comparesEqualTo(new BigDecimal("46.02")));
    assertThat(t.gross(), comparesEqualTo(new BigDecimal("400.02")));
  }

  @Test
  @DisplayName(
      "US: no VAT table configured at all, so gross equals net — sales tax is not a PO tax")
  void usNoVatTable() {
    Totals t = Totals.of(List.of(line("12", "19.99", "T1")), "USD", Map.of());

    assertThat(t.net(), comparesEqualTo(new BigDecimal("239.88")));
    assertThat(t.vat(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(t.gross(), comparesEqualTo(new BigDecimal("239.88")));
  }

  // ── rounding policy ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("Each line rounds before it is summed, so the total reconciles line by line")
  void roundsPerLineNotOnlyAtTheEnd() {
    // Three lines of 0.005 each. Rounded per line: 0.01 × 3 = 0.03. Summed then rounded once:
    // 0.015 → 0.02. A receiving clerk adding the printed lines gets 0.03, so 0.03 is the answer.
    Totals t =
        Totals.of(
            List.of(line("1", "0.005", "T0"), line("1", "0.005", "T0"), line("1", "0.005", "T0")),
            "GBP",
            Map.of());

    assertThat(t.net(), comparesEqualTo(new BigDecimal("0.03")));
  }

  @Test
  @DisplayName("A fractional quantity — 2.5 kg at £4.40 — is money, not an integer count")
  void fractionalQuantity() {
    Totals t = Totals.of(List.of(line("2.5", "4.40", "T0")), "GBP", Map.of());
    assertThat(t.net(), comparesEqualTo(new BigDecimal("11.00")));
  }

  // ── VAT code resolution ─────────────────────────────────────────────────────

  @Test
  @DisplayName("An unknown VAT code rates at zero rather than refusing the line")
  void unknownVatCodeRatesZero() {
    Totals t =
        Totals.of(
            List.of(line("1", "100.00", "NOT_A_CODE")),
            "GBP",
            Map.of("T1", new BigDecimal("0.20")));

    assertThat(t.vat(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(t.gross(), comparesEqualTo(new BigDecimal("100.00")));
  }

  @Test
  @DisplayName("VAT codes match case-insensitively — the column is free text")
  void vatCodeIsCaseInsensitive() {
    Totals t =
        Totals.of(List.of(line("1", "100.00", "t1")), "GBP", Map.of("T1", new BigDecimal("0.20")));
    assertThat(t.vat(), comparesEqualTo(new BigDecimal("20.00")));
  }

  @Test
  @DisplayName("Lines on one order can carry different VAT codes, and each is rated on its own")
  void mixedVatCodesOnOneOrder() {
    Totals t =
        Totals.of(
            List.of(line("1", "100.00", "T1"), line("1", "100.00", "T0")),
            "GBP",
            Map.of("T1", new BigDecimal("0.20"), "T0", BigDecimal.ZERO));

    assertThat(t.net(), comparesEqualTo(new BigDecimal("200.00")));
    assertThat(t.vat(), comparesEqualTo(new BigDecimal("20.00")));
  }

  private static List<PurchaseOrderLine> line3(String unitPrice, String currency) {
    return List.of(line("3", unitPrice, "T1"));
  }
}
