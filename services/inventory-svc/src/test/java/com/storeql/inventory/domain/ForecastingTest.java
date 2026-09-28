package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.inventory.domain.Forecasting.Calendar;
import com.storeql.inventory.domain.Forecasting.Forecast;
import com.storeql.inventory.domain.Forecasting.Shape;
import com.storeql.inventory.domain.Forecasting.UpliftFacts;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statistical demand forecast (06.x) as pure arithmetic over a zero-filled daily series: what
 * it says about a steady seller, an intermittent one, a weekly pattern, and how honestly it reports
 * its own accuracy on a hold-out.
 */
class ForecastingTest {

  private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 20);

  private static List<BigDecimal> series(double... values) {
    List<BigDecimal> out = new ArrayList<>();
    for (double v : values) out.add(BigDecimal.valueOf(v));
    return out;
  }

  private static List<BigDecimal> constant(int days, double value) {
    return new ArrayList<>(Collections.nCopies(days, BigDecimal.valueOf(value)));
  }

  private static double d(BigDecimal b) {
    return b.doubleValue();
  }

  @Test
  @DisplayName(
      "A steady seller forecasts itself: five a day stays five a day, and the hold-out says so")
  void steadyDemandForecastsItself() {
    Forecast f = Forecasting.forecast(constant(60, 5), SUNDAY, 28);
    assertThat(f.method(), is(Forecasting.METHOD_SES));
    assertThat(f.intermittent(), is(false));
    assertThat(f.historyDays(), is(60));
    assertThat(f.fromDay(), is(SUNDAY.plusDays(1)));
    assertThat(f.points().size(), is(28));
    assertThat(d(f.level()), closeTo(5.0, 0.01));
    for (BigDecimal p : f.points()) assertThat(d(p), closeTo(5.0, 0.01));
    assertThat(d(f.expectedOver(7)), closeTo(35.0, 0.1));
    assertThat(f.accuracy().holdoutDays(), greaterThanOrEqualTo(7));
    assertThat(d(f.accuracy().mape()), closeTo(0.0, 0.01));
    assertThat(d(f.accuracy().bias()), closeTo(0.0, 0.01));
  }

  @Test
  @DisplayName(
      "One unit every fourth day is intermittent: Croston with the SBA correction, a quarter a day less a little")
  void intermittentDemandUsesCrostonSba() {
    List<BigDecimal> daily = new ArrayList<>();
    for (int i = 0; i < 84; i++) daily.add(i % 4 == 3 ? BigDecimal.ONE : BigDecimal.ZERO);
    Forecast f = Forecasting.forecast(daily, SUNDAY, 28);
    assertThat(f.method(), is(Forecasting.METHOD_CROSTON_SBA));
    assertThat(f.intermittent(), is(true));
    assertThat(f.weekdayProfile().isEmpty(), is(true));
    // 28 days at a quarter a day is 7; the SBA correction (1 - alpha/2) takes a little off.
    assertThat(d(f.expectedOver(28)), greaterThan(5.0));
    assertThat(d(f.expectedOver(28)), lessThanOrEqualTo(7.0));
    for (BigDecimal p : f.points()) assertThat(d(p), greaterThan(0.0));
  }

  @Test
  @DisplayName(
      "Weekends that sell double show in the profile, and the week's points still add up to the week")
  void weekendsCarryMoreOfTheWeek() {
    List<BigDecimal> daily = new ArrayList<>();
    for (int i = 0; i < 56; i++) {
      DayOfWeek dow = SUNDAY.minusDays(55 - i).getDayOfWeek();
      boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
      daily.add(BigDecimal.valueOf(weekend ? 20 : 10));
    }
    Forecast f = Forecasting.forecast(daily, SUNDAY, 14);
    assertThat(f.method(), is(Forecasting.METHOD_SES));
    assertThat(f.weekdayProfile().size(), is(7));
    // Monday first, Sunday last.
    assertThat(d(f.weekdayProfile().get(0)), lessThan(0.9));
    assertThat(d(f.weekdayProfile().get(5)), greaterThan(1.3));
    assertThat(d(f.weekdayProfile().get(6)), greaterThan(1.3));
    // fromDay is a Monday: the first five points are weekdays, the sixth and seventh the weekend.
    assertThat(f.fromDay().getDayOfWeek(), is(DayOfWeek.MONDAY));
    assertThat(d(f.points().get(5)), greaterThan(d(f.points().get(0))));
    assertThat(d(f.expectedOver(7)), closeTo(90.0, 9.0));
    assertThat(d(f.accuracy().mape()), lessThan(15.0));
  }

  @Test
  @DisplayName("Fewer than fourteen days is a mean, with no claim of accuracy")
  void tooLittleHistoryIsAMeanWithNoClaimOfAccuracy() {
    Forecast f = Forecasting.forecast(series(2, 4, 6, 4, 2, 4, 6, 4, 2, 4), SUNDAY, 7);
    assertThat(f.method(), is(Forecasting.METHOD_MEAN));
    assertThat(d(f.level()), closeTo(3.8, 0.01));
    assertThat(f.accuracy().holdoutDays(), is(0));
    assertThat(f.accuracy().mape(), is(nullValue()));
    assertThat(f.accuracy().bias(), is(nullValue()));
    assertThat(f.accuracy().mase(), is(nullValue()));
    assertThat(f.points().size(), is(7));
  }

  @Test
  @DisplayName("No demand at all forecasts nothing, and says nothing about accuracy")
  void noDemandForecastsNothing() {
    Forecast f = Forecasting.forecast(constant(30, 0), SUNDAY, 7);
    assertThat(f.method(), is(Forecasting.METHOD_MEAN));
    assertThat(d(f.level()), is(0.0));
    assertThat(f.points(), everyItem(is(BigDecimal.ZERO.setScale(4))));
    assertThat(f.accuracy().mape(), is(nullValue()));
  }

  @Test
  @DisplayName(
      "A step up inside the hold-out reads as under-forecasting: a negative bias, and an error that is not zero")
  void aStepUpShowsAsNegativeBias() {
    // Four and six on alternate days, so the naive one-day-back forecast has an error to scale by;
    // then ten days at fifteen inside the hold-out.
    List<BigDecimal> daily = new ArrayList<>();
    for (int i = 0; i < 40; i++) daily.add(BigDecimal.valueOf(i % 2 == 0 ? 4 : 6));
    daily.addAll(constant(10, 15));
    Forecast f = Forecasting.forecast(daily, SUNDAY, 7);
    assertThat(d(f.accuracy().bias()), lessThan(0.0));
    assertThat(d(f.accuracy().mape()), greaterThan(0.0));
    assertThat(f.accuracy().mase(), is(org.hamcrest.Matchers.notNullValue()));
  }

  @Test
  @DisplayName("Points are never negative and always carry four decimals")
  void pointsAreNeverNegativeAndScaleIsFour() {
    Forecast f =
        Forecasting.forecast(
            series(3, 0, 0, 9, 1, 0, 2, 0, 0, 0, 5, 0, 1, 0, 0, 7, 0, 0, 2, 1), SUNDAY, 10);
    for (BigDecimal p : f.points()) {
      assertThat(p.signum(), greaterThanOrEqualTo(0));
      assertThat(p.scale(), is(4));
    }
    assertThat(f.expectedOver(3).scale(), is(4));
  }

  // ── the shape of a year, and what a promotion does ─────────────────────────

  /** Fourteen months to {@code SUNDAY}: ten a day, twenty a day through December. */
  private static List<BigDecimal> aYearWithADecember() {
    List<BigDecimal> daily = new ArrayList<>();
    for (int i = 0; i < 420; i++) {
      LocalDate day = SUNDAY.minusDays(419 - i);
      daily.add(BigDecimal.valueOf(day.getMonth() == Month.DECEMBER ? 20 : 10));
    }
    return daily;
  }

  @Test
  @DisplayName(
      "Thirteen months of history give the year its shape: December sells double, and a forecast into December says so")
  void aYearsShapeCarriesIntoTheForecast() {
    List<BigDecimal> daily = aYearWithADecember();
    List<BigDecimal> indices = Forecasting.seasonalIndices(daily, SUNDAY, new boolean[420]);
    assertThat(indices.size(), is(12));
    assertThat(d(indices.get(Month.DECEMBER.getValue() - 1)), greaterThan(1.7));
    assertThat(d(indices.get(Month.JUNE.getValue() - 1)), lessThan(1.0));

    Forecast f =
        Forecasting.forecast(
            daily, SUNDAY, 120, new Shape(indices, null, null), Calendar.none(420, 120));
    assertThat(f.seasonalIndices().size(), is(12));
    // The level is the deseasonalised day; October forecasts ten, December twenty.
    int october = (int) (LocalDate.of(2026, 10, 1).toEpochDay() - f.fromDay().toEpochDay());
    int december = (int) (LocalDate.of(2026, 12, 1).toEpochDay() - f.fromDay().toEpochDay());
    assertThat(d(f.points().get(october)), closeTo(10.0, 0.6));
    assertThat(d(f.points().get(december)), closeTo(20.0, 1.2));
    assertThat(d(f.accuracy().mape()), lessThan(10.0));
  }

  @Test
  @DisplayName(
      "A fortnight's promotion at two and a half times sells shows as the uplift, and only the days a promotion will run are lifted")
  void aPromotionLiftsTheDaysItRuns() {
    List<BigDecimal> daily = new ArrayList<>();
    boolean[] promoted = new boolean[120];
    for (int i = 0; i < 120; i++) {
      boolean promo = i >= 60 && i < 74;
      promoted[i] = promo;
      daily.add(BigDecimal.valueOf(promo ? 25 : 10));
    }
    UpliftFacts facts = Forecasting.upliftFacts(daily, SUNDAY, promoted, List.of());
    assertThat(facts.promotedDays(), is(14));
    assertThat(facts.baselineDays(), is(106));
    BigDecimal uplift = Forecasting.upliftOf(facts);
    assertThat(d(uplift), closeTo(2.5, 0.01));

    boolean[] ahead = new boolean[14];
    for (int i = 0; i < 7; i++) ahead[i] = true;
    Forecast f =
        Forecasting.forecast(
            daily,
            SUNDAY,
            14,
            new Shape(List.of(), uplift, Shape.UPLIFT_ITEM),
            new Calendar(promoted, ahead));
    assertThat(f.method(), is(Forecasting.METHOD_SES));
    assertThat(d(f.level()), closeTo(10.0, 0.3));
    assertThat(d(f.uplift()), closeTo(2.5, 0.01));
    assertThat(f.upliftSource(), is(Shape.UPLIFT_ITEM));
    assertThat(f.promotedHistoryDays(), is(14));
    assertThat(f.promotedAheadDays(), is(7));
    for (int i = 0; i < 7; i++) assertThat(d(f.points().get(i)), closeTo(25.0, 0.8));
    for (int i = 7; i < 14; i++) assertThat(d(f.points().get(i)), closeTo(10.0, 0.4));
    assertThat(d(f.expectedOver(14)), closeTo(245.0, 6.0));
  }

  @Test
  @DisplayName(
      "Under thirteen months there is no season, under a week of promotion no lift, and the forecast is the plain one")
  void tooLittleHistoryHasNoSeasonAndNoLift() {
    List<BigDecimal> daily = constant(60, 5);
    assertThat(Forecasting.seasonalIndices(daily, SUNDAY, new boolean[60]), is(empty()));
    boolean[] threeDays = new boolean[60];
    for (int i = 10; i < 13; i++) threeDays[i] = true;
    assertThat(
        Forecasting.upliftOf(Forecasting.upliftFacts(daily, SUNDAY, threeDays, List.of())),
        is(nullValue()));
    // A promotion that did not lift anything is no uplift either.
    boolean[] aFlatFortnight = new boolean[60];
    for (int i = 20; i < 34; i++) aFlatFortnight[i] = true;
    assertThat(
        Forecasting.upliftOf(Forecasting.upliftFacts(daily, SUNDAY, aFlatFortnight, List.of())),
        is(nullValue()));

    Forecast plain = Forecasting.forecast(daily, SUNDAY, 28);
    Forecast shaped = Forecasting.forecast(daily, SUNDAY, 28, Shape.FLAT, Calendar.none(60, 28));
    assertThat(shaped.points(), is(plain.points()));
    assertThat(plain.seasonalIndices(), is(empty()));
    assertThat(plain.uplift(), is(nullValue()));
    assertThat(plain.promotedAheadDays(), is(0));
  }
}
