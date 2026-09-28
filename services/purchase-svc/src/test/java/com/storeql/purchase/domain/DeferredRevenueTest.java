package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.DeferredRevenue.GiftCardOutcome;
import com.storeql.purchase.domain.DeferredRevenue.GiftCardPool;
import com.storeql.purchase.domain.DeferredRevenue.PointsOutcome;
import com.storeql.purchase.domain.DeferredRevenue.PointsPool;
import com.storeql.purchase.domain.DeferredRevenue.Settings;
import com.storeql.purchase.domain.DeferredRevenue.Source;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deferred revenue for loyalty points and gift cards (17.11): the rules, with the figures worked by
 * hand. A point is worth 0.05, a fifth of points are expected never to be spent, and a tenth of
 * gift card value never to be claimed.
 */
class DeferredRevenueTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final Settings SETTINGS =
      new Settings(new BigDecimal("0.05"), new BigDecimal("20"), new BigDecimal("10"));
  private static final Source SRC =
      new Source(TENANT, Ids.newId(), STORE, LocalDate.of(2026, 9, 15));

  private static BigDecimal net(List<NominalLedgerEntry> lines, String code) {
    return lines.stream()
        .filter(l -> l.nominalCode().equals(code))
        .map(l -> l.debit().subtract(l.credit()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static void assertPool(
      PointsPool pool, String outstanding, String deferred, String unmatched) {
    assertThat(pool.outstanding(), comparesEqualTo(new BigDecimal(outstanding)));
    assertThat(pool.deferred(), comparesEqualTo(new BigDecimal(deferred)));
    assertThat(pool.unmatched(), comparesEqualTo(new BigDecimal(unmatched)));
  }

  private static void assertBalanced(List<NominalLedgerEntry> lines) {
    BigDecimal dr =
        lines.stream().map(NominalLedgerEntry::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cr =
        lines.stream().map(NominalLedgerEntry::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(dr, comparesEqualTo(cr));
  }

  private static PointsOutcome saleOf200Points(PointsPool pool) {
    // 120.00 with 20.00 VAT: 100.00 net. 200 points × 0.05 × 80% = 8.00 standalone.
    return DeferredRevenue.earned(
        SRC,
        SETTINGS,
        pool,
        new BigDecimal("200"),
        new BigDecimal("120.00"),
        new BigDecimal("20.00"));
  }

  @Test
  @DisplayName("Points earned with a sale take their share of its net revenue by standalone price")
  void pointsEarnedWithASaleDeferTheirShare() {
    PointsOutcome out = saleOf200Points(PointsPool.EMPTY);

    // 100.00 × 8.00 / 108.00 = 7.407… → 7.41 out of sales into deferred income.
    assertThat(net(out.posting(), Domain.CODE_SALES), comparesEqualTo(new BigDecimal("7.41")));
    assertThat(
        net(out.posting(), Domain.CODE_DEFERRED_LOYALTY), comparesEqualTo(new BigDecimal("-7.41")));
    assertThat(out.posting().get(0).sourceType(), is(Domain.SOURCE_LOYALTY_DEFERRAL));
    assertThat(out.posting().get(0).storeId(), is(STORE));
    assertBalanced(out.posting());
    assertPool(out.pool(), "200", "7.41", "0");
  }

  @Test
  @DisplayName(
      "Spending releases each point's share of the expected spend; the last lapse is breakage")
  void spendingReleasesAndTheRestIsBreakage() {
    PointsPool pool = saleOf200Points(PointsPool.EMPTY).pool();

    // 80 of the 160 expected to be spent: 7.41 × 80 / 160 = 3.705 → 3.71.
    PointsOutcome first = DeferredRevenue.redeemed(SRC, SETTINGS, pool, new BigDecimal("80"));
    assertThat(
        net(first.posting(), Domain.CODE_LOYALTY_REDEEMED),
        comparesEqualTo(new BigDecimal("-3.71")));
    assertThat(
        net(first.posting(), Domain.CODE_DEFERRED_LOYALTY),
        comparesEqualTo(new BigDecimal("3.71")));
    assertPool(first.pool(), "120", "3.70", "0");

    // 80 of the 96 now expected: 3.70 × 80 / 96 = 3.083 → 3.08.
    PointsOutcome second =
        DeferredRevenue.redeemed(SRC, SETTINGS, first.pool(), new BigDecimal("80"));
    assertThat(
        net(second.posting(), Domain.CODE_LOYALTY_REDEEMED),
        comparesEqualTo(new BigDecimal("-3.08")));
    assertPool(second.pool(), "40", "0.62", "0");

    // The last 40 lapse: nothing remains outstanding, so the 0.62 left is breakage.
    PointsOutcome lapse =
        DeferredRevenue.adjusted(SRC, SETTINGS, second.pool(), new BigDecimal("-40"));
    assertThat(
        net(lapse.posting(), Domain.CODE_LOYALTY_BREAKAGE),
        comparesEqualTo(new BigDecimal("-0.62")));
    assertThat(lapse.posting().get(0).sourceType(), is(Domain.SOURCE_LOYALTY_RELEASE));
    assertBalanced(lapse.posting());
    assertPool(lapse.pool(), "0", "0", "0");
  }

  @Test
  @DisplayName(
      "A lapse while points remain releases nothing: expected lapses are priced out already")
  void aLapseWithPointsLeftReleasesNothing() {
    PointsPool pool = saleOf200Points(PointsPool.EMPTY).pool();
    PointsOutcome out = DeferredRevenue.adjusted(SRC, SETTINGS, pool, new BigDecimal("-30"));
    assertThat(out.posting(), is(empty()));
    assertPool(out.pool(), "170", "7.41", "0");
  }

  @Test
  @DisplayName("Points spent before their earning reached the ledger are settled when it arrives")
  void aRedemptionReadBeforeItsEarningIsMatchedLater() {
    PointsOutcome early =
        DeferredRevenue.redeemed(SRC, SETTINGS, PointsPool.EMPTY, new BigDecimal("50"));
    assertThat(early.posting(), is(empty()));
    assertPool(early.pool(), "0", "0", "50");

    PointsOutcome earned = saleOf200Points(early.pool());
    // 7.41 deferred, and the 50 already spent release 7.41 × 50 / 200 = 1.85 in the same journal.
    assertThat(net(earned.posting(), Domain.CODE_SALES), comparesEqualTo(new BigDecimal("7.41")));
    assertThat(
        net(earned.posting(), Domain.CODE_DEFERRED_LOYALTY),
        comparesEqualTo(new BigDecimal("-5.56")));
    assertThat(
        net(earned.posting(), Domain.CODE_LOYALTY_REDEEMED),
        comparesEqualTo(new BigDecimal("-1.85")));
    assertBalanced(earned.posting());
    assertPool(earned.pool(), "150", "5.56", "0");
  }

  @Test
  @DisplayName("Points given away are a marketing cost at their expected value, not a cut of sales")
  void pointsGivenAwayAreACost() {
    PointsOutcome out =
        DeferredRevenue.adjusted(SRC, SETTINGS, PointsPool.EMPTY, new BigDecimal("100"));
    // 100 × 0.05 × 80% = 4.00.
    assertThat(
        net(out.posting(), Domain.CODE_LOYALTY_AWARDED), comparesEqualTo(new BigDecimal("4.00")));
    assertThat(net(out.posting(), Domain.CODE_SALES), comparesEqualTo(BigDecimal.ZERO));
    assertThat(out.posting().get(0).description(), containsString("awarded"));
    assertPool(out.pool(), "100", "4.00", "0");
  }

  @Test
  @DisplayName("Loyalty and breakage journals name the sale or event by '#' and its handle")
  void loyaltyAndBreakageJournalsNameTheirSourceByItsHandle() {
    // Read in the accounting package and on the Integrations screen; the id stays in sourceRef.
    UUID ref = Ids.parse("01a0905d-7082-7518-9ec6-aee90d72a43e");
    Source src = new Source(TENANT, ref, STORE, LocalDate.of(2026, 9, 15));
    BigDecimal hundred = new BigDecimal("100");

    PointsOutcome earned =
        DeferredRevenue.earned(
            src, SETTINGS, PointsPool.EMPTY, hundred, new BigDecimal("120"), new BigDecimal("20"));
    PointsOutcome awarded = DeferredRevenue.adjusted(src, SETTINGS, PointsPool.EMPTY, hundred);
    PointsOutcome redeemed = DeferredRevenue.redeemed(src, SETTINGS, awarded.pool(), hundred);
    PointsOutcome lapsed =
        DeferredRevenue.adjusted(src, SETTINGS, awarded.pool(), hundred.negate());
    PointsOutcome expired = DeferredRevenue.expired(src, SETTINGS, awarded.pool(), hundred);
    GiftCardPool cards = DeferredRevenue.loaded(GiftCardPool.EMPTY, new BigDecimal("100.00"));
    GiftCardOutcome breakage =
        DeferredRevenue.giftCardRedeemed(src, SETTINGS, cards, new BigDecimal("90.00"));
    GiftCardOutcome reversed =
        DeferredRevenue.giftCardRedeemed(src, SETTINGS, breakage.pool(), new BigDecimal("10.00"));

    assertThat(
        earned.posting().get(0).description(), is("Loyalty points earned on sale #0d72a43e"));
    assertThat(awarded.posting().get(0).description(), is("Loyalty points awarded, #0d72a43e"));
    assertThat(redeemed.posting().get(0).description(), is("Loyalty points redeemed, #0d72a43e"));
    assertThat(lapsed.posting().get(0).description(), is("Loyalty points lapsed, #0d72a43e"));
    assertThat(expired.posting().get(0).description(), is("Loyalty points expired, #0d72a43e"));
    assertThat(breakage.posting().get(0).description(), is("Gift card breakage on sale #0d72a43e"));
    assertThat(
        reversed.posting().get(0).description(),
        is("Gift card breakage reversed on sale #0d72a43e"));
    for (List<NominalLedgerEntry> posting :
        List.of(
            earned.posting(),
            awarded.posting(),
            redeemed.posting(),
            lapsed.posting(),
            expired.posting(),
            breakage.posting(),
            reversed.posting())) {
      for (NominalLedgerEntry l : posting) {
        assertThat(l.description(), not(containsString(ref.toString())));
        assertThat(l.sourceRef(), is(ref));
      }
    }
  }

  @Test
  @DisplayName("Nothing, nonsense and a sale with no net revenue post nothing")
  void nothingPostsNothing() {
    for (String points : new String[] {"0", "-5"}) {
      assertThat(saleOrNothing(points).posting(), is(empty()));
      assertThat(
          DeferredRevenue.redeemed(SRC, SETTINGS, PointsPool.EMPTY, new BigDecimal(points))
              .posting(),
          is(empty()));
    }
    assertThat(
        DeferredRevenue.redeemed(SRC, SETTINGS, PointsPool.EMPTY, null).pool(),
        is(PointsPool.EMPTY));
    PointsOutcome free =
        DeferredRevenue.earned(
            SRC,
            SETTINGS,
            PointsPool.EMPTY,
            new BigDecimal("10"),
            new BigDecimal("5.00"),
            new BigDecimal("5.00"));
    assertThat(free.posting(), is(empty()));
    assertPool(free.pool(), "10", "0", "0");
  }

  private static PointsOutcome saleOrNothing(String points) {
    return DeferredRevenue.earned(
        SRC,
        SETTINGS,
        PointsPool.EMPTY,
        new BigDecimal(points),
        new BigDecimal("10.00"),
        BigDecimal.ZERO);
  }

  @Test
  @DisplayName("A release never exceeds what is deferred, however high the breakage estimate")
  void aReleaseIsCappedAtTheDeferredIncome() {
    Settings mostlyUnspent =
        new Settings(new BigDecimal("0.05"), new BigDecimal("95"), BigDecimal.ZERO);
    PointsPool pool = new PointsPool(new BigDecimal("10"), new BigDecimal("1.00"), BigDecimal.ZERO);
    PointsOutcome out = DeferredRevenue.redeemed(SRC, mostlyUnspent, pool, new BigDecimal("5"));
    assertThat(
        net(out.posting(), Domain.CODE_LOYALTY_REDEEMED), comparesEqualTo(new BigDecimal("-1.00")));
    assertPool(out.pool(), "5", "0", "0");
  }

  @Test
  @DisplayName("Estimates an accountant could not mean are refused by name")
  void nonsenseEstimatesAreRefused() {
    BigDecimal pct = new BigDecimal("20");
    for (String value : new String[] {"0", "-1", "1000.0001", "0.00001"}) {
      assertThat(
          value,
          DeferredRevenue.refusal(new BigDecimal(value), pct, pct),
          is(DeferredRevenue.CODE_POINT_VALUE_INVALID));
    }
    assertThat(
        DeferredRevenue.refusal(null, pct, pct), is(DeferredRevenue.CODE_POINT_VALUE_INVALID));
    for (String bad : new String[] {"95.01", "-0.01", "12.345", "100"}) {
      assertThat(
          bad,
          DeferredRevenue.refusal(BigDecimal.ONE, new BigDecimal(bad), pct),
          is(DeferredRevenue.CODE_BREAKAGE_OUT_OF_RANGE));
      assertThat(
          bad,
          DeferredRevenue.refusal(BigDecimal.ONE, pct, new BigDecimal(bad)),
          is(DeferredRevenue.CODE_BREAKAGE_OUT_OF_RANGE));
    }
    assertThat(
        DeferredRevenue.refusal(BigDecimal.ONE, null, pct),
        is(DeferredRevenue.CODE_BREAKAGE_OUT_OF_RANGE));
    assertThat(
        DeferredRevenue.refusal(new BigDecimal("1000"), new BigDecimal("95"), BigDecimal.ZERO),
        is(nullValue()));
    assertThat(
        DeferredRevenue.refusal(new BigDecimal("0.0001"), BigDecimal.ZERO, new BigDecimal("12.50")),
        is(nullValue()));
  }

  @Test
  @DisplayName("A gift card sold is a liability against the money taken; one given away is a cost")
  void aGiftCardSoldIsALiability() {
    BigDecimal amount = new BigDecimal("25.00");
    assertThat(
        net(
            DeferredRevenue.giftCardLoaded(SRC, "ISSUE", "CARD", amount),
            Domain.CODE_CARD_CLEARING),
        comparesEqualTo(amount));
    assertThat(
        net(DeferredRevenue.giftCardLoaded(SRC, "ISSUE", "UPI", amount), Domain.CODE_CARD_CLEARING),
        comparesEqualTo(amount));
    assertThat(
        net(
            DeferredRevenue.giftCardLoaded(SRC, "RELOAD", "CASH", amount),
            Domain.CODE_CASH_IN_TILLS),
        comparesEqualTo(amount));
    List<NominalLedgerEntry> given =
        DeferredRevenue.giftCardLoaded(SRC, "ISSUE", "promotional", amount);
    assertThat(net(given, Domain.CODE_GIFT_CARDS_GIVEN), comparesEqualTo(amount));
    assertThat(net(given, Domain.CODE_GIFT_CARD_LIABILITY), comparesEqualTo(amount.negate()));
    assertThat(given.get(0).description(), containsString("free of charge"));
    assertThat(
        net(
            DeferredRevenue.giftCardLoaded(SRC, "ISSUE", "BITCOIN", amount),
            Domain.CODE_UNALLOCATED_RECEIPTS),
        comparesEqualTo(amount));
    List<NominalLedgerEntry> reload = DeferredRevenue.giftCardLoaded(SRC, "RELOAD", "CARD", amount);
    assertThat(reload.get(0).description(), containsString("reloaded"));
    assertThat(reload.get(0).sourceType(), is(Domain.SOURCE_GIFT_CARD_LOAD));
    assertThat(net(reload, Domain.CODE_SALES), comparesEqualTo(BigDecimal.ZERO));
    assertThat(DeferredRevenue.giftCardLoaded(SRC, "ISSUE", "CARD", BigDecimal.ZERO), is(empty()));
  }

  @Test
  @DisplayName(
      "Gift card breakage follows spending, stops at the estimate, and is reversed when overtaken")
  void giftCardBreakageFollowsSpending() {
    GiftCardPool pool = DeferredRevenue.loaded(GiftCardPool.EMPTY, new BigDecimal("100.00"));

    // 45 spent: 45 × 10% / 90% = 5.00 recognised.
    GiftCardOutcome first =
        DeferredRevenue.giftCardRedeemed(SRC, SETTINGS, pool, new BigDecimal("45.00"));
    assertThat(
        net(first.posting(), Domain.CODE_GIFT_CARD_BREAKAGE),
        comparesEqualTo(new BigDecimal("-5.00")));
    assertThat(
        net(first.posting(), Domain.CODE_GIFT_CARD_LIABILITY),
        comparesEqualTo(new BigDecimal("5.00")));
    assertBalanced(first.posting());

    // 45 more: another 5.00, which is the whole estimate of 10.00 and leaves nothing owed.
    GiftCardOutcome second =
        DeferredRevenue.giftCardRedeemed(SRC, SETTINGS, first.pool(), new BigDecimal("45.00"));
    assertThat(
        net(second.posting(), Domain.CODE_GIFT_CARD_BREAKAGE),
        comparesEqualTo(new BigDecimal("-5.00")));
    assertThat(second.pool().liability(), comparesEqualTo(BigDecimal.ZERO));

    // 10 spent that the estimate said never would be: the 10.00 of breakage is reversed.
    GiftCardOutcome third =
        DeferredRevenue.giftCardRedeemed(SRC, SETTINGS, second.pool(), new BigDecimal("10.00"));
    assertThat(
        net(third.posting(), Domain.CODE_GIFT_CARD_BREAKAGE),
        comparesEqualTo(new BigDecimal("10.00")));
    assertThat(third.posting().get(0).description(), containsString("reversed"));
    assertThat(third.pool().breakage(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(third.pool().liability(), comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName(
      "No estimates, no breakage; and cards loaded before the ledger followed them earn none")
  void noEstimatesNoBreakage() {
    GiftCardPool pool = DeferredRevenue.loaded(GiftCardPool.EMPTY, new BigDecimal("100.00"));
    GiftCardOutcome unset =
        DeferredRevenue.giftCardRedeemed(SRC, null, pool, new BigDecimal("30.00"));
    assertThat(unset.posting(), is(empty()));
    assertThat(unset.pool().redeemed(), comparesEqualTo(new BigDecimal("30.00")));

    GiftCardOutcome old =
        DeferredRevenue.giftCardRedeemed(
            SRC, SETTINGS, GiftCardPool.EMPTY, new BigDecimal("20.00"));
    assertThat(old.posting(), is(empty()));
    assertThat(old.pool().breakage(), comparesEqualTo(BigDecimal.ZERO));
    assertThat(
        DeferredRevenue.giftCardRedeemed(SRC, SETTINGS, pool, BigDecimal.ZERO).posting(),
        is(empty()));
  }
}
