package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Comparing quotes, pure: the lowest price per line in the business's own money, each bid's total
 * and rank, and what cannot be ranked. Written before the code.
 */
class RfqTest {

  private static final UUID RFQ = Ids.newId();
  private static final UUID LINE_A = Ids.newId();
  private static final UUID LINE_B = Ids.newId();
  private static final UUID S1 = Ids.newId();
  private static final UUID S2 = Ids.newId();
  private static final UUID S3 = Ids.newId();

  /** Pounds at home; euros at 0.85; nothing else translatable. */
  private static final Rfq.Translator FX =
      (currency, amount) ->
          switch (currency) {
            case "GBP" -> Optional.of(amount);
            case "EUR" ->
                Optional.of(
                    amount.multiply(new BigDecimal("0.85")).setScale(2, RoundingMode.HALF_UP));
            default -> Optional.empty();
          };

  private static Rfq.Line line(UUID id, int qty, int order) {
    return new Rfq.Line(id, Ids.newId(), RFQ, Ids.newId(), new BigDecimal(qty), order, null);
  }

  private static Rfq.Bid bid(
      UUID supplier, String status, String currency, Map<UUID, BigDecimal> prices) {
    return new Rfq.Bid(
        Ids.newId(),
        Ids.newId(),
        RFQ,
        supplier,
        "S",
        status,
        currency,
        3,
        null,
        null,
        Instant.now(),
        prices);
  }

  @Test
  void theLowestPricePerLineIsFoundInTheBusinessesOwnMoney() {
    List<Rfq.Line> lines = List.of(line(LINE_A, 10, 0), line(LINE_B, 5, 1));
    List<Rfq.Bid> bids =
        List.of(
            bid(
                S1,
                Rfq.QUOTED,
                "GBP",
                Map.of(LINE_A, new BigDecimal("10.00"), LINE_B, new BigDecimal("4.00"))),
            bid(
                S2,
                Rfq.QUOTED,
                "EUR",
                Map.of(LINE_A, new BigDecimal("11.00"), LINE_B, new BigDecimal("5.00"))),
            bid(S3, Rfq.DECLINED, null, Map.of()));
    Rfq.Comparison c = Rfq.compare("GBP", lines, bids, FX);
    assertThat(c.homeCurrency(), is("GBP"));
    Rfq.LineComparison a = c.lines().get(0);
    assertThat(a.prices().size(), is(2));
    Rfq.Price s2a = a.prices().stream().filter(p -> p.supplierId().equals(S2)).findFirst().get();
    assertThat(s2a.homeUnitPrice(), comparesEqualTo(new BigDecimal("9.35")));
    assertThat(s2a.lineTotal(), comparesEqualTo(new BigDecimal("110.00")));
    assertThat(s2a.homeLineTotal(), comparesEqualTo(new BigDecimal("93.50")));
    assertThat(s2a.lowest(), is(true));
    Rfq.Price s1a = a.prices().stream().filter(p -> p.supplierId().equals(S1)).findFirst().get();
    assertThat(s1a.lowest(), is(false));
    // The second line goes the other way: four pounds beats four and a quarter.
    Rfq.LineComparison b = c.lines().get(1);
    assertThat(
        b.prices().stream().filter(Rfq.Price::lowest).findFirst().get().supplierId(), is(S1));
    // Totals in each bid's own money and at home; ranked cheapest first; the decliner unranked.
    Rfq.BidSummary first =
        c.bids().stream().filter(x -> x.supplierId().equals(S2)).findFirst().get();
    assertThat(first.total(), comparesEqualTo(new BigDecimal("135.00")));
    assertThat(first.currency(), is("EUR"));
    assertThat(first.homeTotal(), comparesEqualTo(new BigDecimal("114.75")));
    assertThat(first.rank(), is(1));
    assertThat(first.complete(), is(true));
    Rfq.BidSummary second =
        c.bids().stream().filter(x -> x.supplierId().equals(S1)).findFirst().get();
    assertThat(second.homeTotal(), comparesEqualTo(new BigDecimal("120.00")));
    assertThat(second.rank(), is(2));
    Rfq.BidSummary none =
        c.bids().stream().filter(x -> x.supplierId().equals(S3)).findFirst().get();
    assertThat(none.rank(), is(nullValue()));
    assertThat(none.total(), is(nullValue()));
  }

  @Test
  void aPartialBidAndAnUntranslatableOneAreShownButNotRanked() {
    List<Rfq.Line> lines = List.of(line(LINE_A, 10, 0), line(LINE_B, 5, 1));
    List<Rfq.Bid> bids =
        List.of(
            bid(S1, Rfq.QUOTED, "GBP", Map.of(LINE_A, new BigDecimal("10.00"))),
            bid(
                S2,
                Rfq.QUOTED,
                "JPY",
                Map.of(LINE_A, new BigDecimal("1500"), LINE_B, new BigDecimal("700"))),
            bid(
                S3,
                Rfq.QUOTED,
                "GBP",
                Map.of(LINE_A, new BigDecimal("12.00"), LINE_B, new BigDecimal("3.00"))));
    Rfq.Comparison c = Rfq.compare("GBP", lines, bids, FX);
    Rfq.BidSummary partial =
        c.bids().stream().filter(x -> x.supplierId().equals(S1)).findFirst().get();
    assertThat(partial.complete(), is(false));
    assertThat(partial.total(), comparesEqualTo(new BigDecimal("100.00")));
    assertThat(partial.rank(), is(nullValue()));
    Rfq.BidSummary yen = c.bids().stream().filter(x -> x.supplierId().equals(S2)).findFirst().get();
    assertThat(yen.complete(), is(true));
    assertThat(yen.total(), comparesEqualTo(new BigDecimal("18500")));
    assertThat(yen.homeTotal(), is(nullValue()));
    assertThat(yen.rank(), is(nullValue()));
    Rfq.BidSummary whole =
        c.bids().stream().filter(x -> x.supplierId().equals(S3)).findFirst().get();
    assertThat(whole.rank(), is(1));
    // The yen price is on the line but cannot be the lowest: nobody knows what it is in pounds.
    Rfq.Price yenA =
        c.lines().get(0).prices().stream().filter(p -> p.supplierId().equals(S2)).findFirst().get();
    assertThat(yenA.homeUnitPrice(), is(nullValue()));
    assertThat(yenA.lowest(), is(false));
    assertThat(
        c.lines().get(0).prices().stream().filter(Rfq.Price::lowest).findFirst().get().supplierId(),
        is(S1));
  }

  @Test
  void referencesCountUpInTheSeriesAndNothingIsComparedWithoutQuotes() {
    assertThat(Rfq.reference(1), is("RFQ-000001"));
    assertThat(Rfq.reference(1234), is("RFQ-001234"));
    Rfq.Comparison c =
        Rfq.compare(
            "GBP", List.of(line(LINE_A, 10, 0)), List.of(bid(S1, Rfq.INVITED, null, Map.of())), FX);
    assertThat(c.lines().get(0).prices().isEmpty(), is(true));
    assertThat(c.bids().get(0).rank(), is(nullValue()));
  }
}
