package com.storeql.customer.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.customer.domain.PointLots.PointLot;
import com.storeql.customer.domain.PointLots.Take;
import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Points are spent from the lot that dies first, then the oldest (13.x). */
class PointLotsTest {

  private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
  private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");

  private static PointLot lot(UUID id, String remaining, Instant earned, Instant expires) {
    return new PointLot(id, new BigDecimal(remaining), earned, expires);
  }

  @Test
  @DisplayName(
      "The lot expiring soonest is spent first, then the oldest; a never-expiring lot last")
  void soonestToDieFirst() {
    UUID never = Ids.newId();
    UUID dyingInJune = Ids.newId();
    UUID dyingInMarch = Ids.newId();
    List<PointLot> lots =
        List.of(
            lot(never, "100", JAN, null),
            lot(dyingInJune, "40", JAN, JUN),
            lot(dyingInMarch, "30", MAR, MAR.plusSeconds(86400 * 60)));
    List<Take> takes = PointLots.consume(lots, new BigDecimal("60"));
    assertThat(takes.size(), is(2));
    assertThat(takes.get(0).lotId(), is(dyingInMarch));
    assertThat(takes.get(0).points(), comparesEqualTo(new BigDecimal("30")));
    assertThat(takes.get(1).lotId(), is(dyingInJune));
    assertThat(takes.get(1).points(), comparesEqualTo(new BigDecimal("30")));
  }

  @Test
  @DisplayName(
      "Among lots that never expire, the oldest is spent first, and a lot is never over-spent")
  void oldestAmongTheImmortal() {
    UUID old = Ids.newId();
    UUID newer = Ids.newId();
    List<PointLot> lots = List.of(lot(newer, "50", MAR, null), lot(old, "20", JAN, null));
    List<Take> takes = PointLots.consume(lots, new BigDecimal("70"));
    assertThat(takes.get(0).lotId(), is(old));
    assertThat(takes.get(0).points(), comparesEqualTo(new BigDecimal("20")));
    assertThat(takes.get(1).lotId(), is(newer));
    assertThat(takes.get(1).points(), comparesEqualTo(new BigDecimal("50")));
    // Asking for more than the lots hold takes what there is; the balance check is the caller's.
    assertThat(
        PointLots.consume(lots, new BigDecimal("500")).stream()
            .map(Take::points)
            .reduce(BigDecimal.ZERO, BigDecimal::add),
        comparesEqualTo(new BigDecimal("70")));
    assertThat(PointLots.consume(lots, BigDecimal.ZERO).isEmpty(), is(true));
  }
}
