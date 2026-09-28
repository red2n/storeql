package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of a supplier's performance, pure: a delivery measured against its promise, and a
 * period's figures weighed into one score. Written before the code.
 */
class SupplierScorecardTest {

  private static final Instant ORDERED = Instant.parse("2026-09-01T09:00:00Z");

  @Test
  void aDeliveryIsMeasuredAgainstTheOrdersDateOrTheSuppliersQuotedLeadTime() {
    // Five days from order to arrival; the order named no date but the supplier quotes three.
    Instant received = Instant.parse("2026-09-06T15:30:00Z");
    assertThat(SupplierScorecard.leadDays(ORDERED, received), is(5));
    LocalDate promised = SupplierScorecard.promise(null, ORDERED, 3);
    assertThat(promised, is(LocalDate.of(2026, 9, 4)));
    assertThat(SupplierScorecard.lateDays(promised, received), is(2));
    // The order's own date wins over the quote; a day early reads negative.
    LocalDate named = SupplierScorecard.promise(LocalDate.of(2026, 9, 7), ORDERED, 3);
    assertThat(named, is(LocalDate.of(2026, 9, 7)));
    assertThat(SupplierScorecard.lateDays(named, received), is(-1));
    // No date and no quote: measured, never judged late.
    assertThat(SupplierScorecard.promise(null, ORDERED, null), is(nullValue()));
    assertThat(SupplierScorecard.lateDays(null, received), is(nullValue()));
    // A clock that ran backwards is not a negative lead time.
    assertThat(SupplierScorecard.leadDays(received, ORDERED), is(0));
  }

  @Test
  void theScoreWeighsOnTimeFillQualityAndInvoicesAndRenormalisesOverWhatIsKnown() {
    // 40 % on time, 30 % fill, 20 % quality (100 less the return rate), 10 % invoice accuracy.
    BigDecimal all =
        SupplierScorecard.score(
            new BigDecimal("50"),
            new BigDecimal("80"),
            new BigDecimal("12.5"),
            new BigDecimal("100"));
    assertThat(all, comparesEqualTo(new BigDecimal("71.5")));
    assertThat(SupplierScorecard.grade(all), is("C"));
    // Nothing promised and nothing invoiced: the weights left are fill and quality alone.
    BigDecimal some = SupplierScorecard.score(null, new BigDecimal("100"), BigDecimal.ZERO, null);
    assertThat(some, comparesEqualTo(new BigDecimal("100.0")));
    assertThat(SupplierScorecard.grade(some), is("A"));
    // A return rate over the whole cannot push quality below nothing.
    BigDecimal awful = SupplierScorecard.score(null, null, new BigDecimal("140"), null);
    assertThat(awful, comparesEqualTo(BigDecimal.ZERO));
    assertThat(SupplierScorecard.grade(awful), is("D"));
    // Nothing known is no score, not a zero.
    assertThat(SupplierScorecard.score(null, null, null, null), is(nullValue()));
    assertThat(SupplierScorecard.grade(null), is(nullValue()));
    assertThat(SupplierScorecard.grade(new BigDecimal("90")), is("A"));
    assertThat(SupplierScorecard.grade(new BigDecimal("89.9")), is("B"));
    assertThat(SupplierScorecard.grade(new BigDecimal("75")), is("B"));
    assertThat(SupplierScorecard.grade(new BigDecimal("60")), is("C"));
    assertThat(SupplierScorecard.grade(new BigDecimal("59.9")), is("D"));
  }

  @Test
  void aShareOfNothingIsUnknownNotZero() {
    assertThat(
        SupplierScorecard.pct(new BigDecimal("16"), new BigDecimal("20")),
        comparesEqualTo(new BigDecimal("80.0")));
    assertThat(SupplierScorecard.pct(BigDecimal.ZERO, BigDecimal.ZERO), is(nullValue()));
    assertThat(SupplierScorecard.pct(BigDecimal.ONE, null), is(nullValue()));
    assertThat(
        SupplierScorecard.pct(new BigDecimal("1"), new BigDecimal("3")),
        comparesEqualTo(new BigDecimal("33.3")));
  }
}
