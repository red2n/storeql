package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.Fx;
import com.storeql.tenant.domain.Commission.Assignment;
import com.storeql.tenant.domain.Commission.Band;
import com.storeql.tenant.domain.Commission.Earned;
import com.storeql.tenant.domain.Commission.Scheme;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The commission arithmetic, which is the part a person paid on it will check: the bands are
 * marginal, a threshold belongs to the band above it, and nothing earns on a period that sold
 * nothing.
 */
class CommissionTest {

  private static Scheme scheme(String basis, String currency, String... thresholdsAndRates) {
    UUID id = Ids.newId();
    List<Band> bands = new java.util.ArrayList<>();
    for (int i = 0; i < thresholdsAndRates.length; i += 2) {
      bands.add(
          new Band(
              Ids.newId(),
              id,
              new BigDecimal(thresholdsAndRates[i]),
              new BigDecimal(thresholdsAndRates[i + 1])));
    }
    return new Scheme(
        id,
        Ids.newId(),
        "counter",
        basis,
        currency,
        Commission.ACTIVE,
        null,
        null,
        null,
        Instant.now(),
        Ids.newId(),
        bands);
  }

  @Test
  @DisplayName("A flat scheme earns its percentage of the net, rounded like money")
  void flat() {
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    List<Earned> earned = Commission.earn(flat, new BigDecimal("1000.00"), 2);
    assertEquals(1, earned.size());
    assertEquals(new BigDecimal("20.00"), earned.get(0).commission());
    assertEquals(new BigDecimal("20.00"), Commission.total(earned, 2));
    // A third of a penny is rounded once, at the end, and only then.
    assertEquals(
        new BigDecimal("0.34"),
        Commission.total(Commission.earn(flat, new BigDecimal("16.75"), 2), 2),
        "2% of 16.75 is 0.335");
  }

  @Test
  @DisplayName("Bands are marginal: only the part inside a band earns that band's rate")
  void marginal() {
    Scheme tiered = scheme(Commission.PERCENT_OF_NET, null, "0", "2", "10000", "3");
    List<Earned> earned = Commission.earn(tiered, new BigDecimal("15000.00"), 2);
    assertEquals(2, earned.size());
    assertEquals(new BigDecimal("200.00"), earned.get(0).commission());
    // Both bands' figures come back at money's own scale, whatever scale the band was written at:
    // a statement line reading 10000 beside one reading 5000.00 is the drift that has cost two
    // reports already.
    assertEquals(new BigDecimal("10000.00"), earned.get(0).amountInBand());
    assertEquals(new BigDecimal("0.00"), earned.get(0).thresholdFrom());
    assertEquals(new BigDecimal("150.00"), earned.get(1).commission());
    assertEquals(new BigDecimal("5000.00"), earned.get(1).amountInBand());
    assertEquals(new BigDecimal("350.00"), Commission.total(earned, 2));

    // Exactly at the threshold earns the lower band only: the higher one starts *above* it, so
    // crossing it never re-rates what came before.
    List<Earned> atThreshold = Commission.earn(tiered, new BigDecimal("10000.00"), 2);
    assertEquals(1, atThreshold.size());
    assertEquals(new BigDecimal("200.00"), Commission.total(atThreshold, 2));
    // A penny over pays a penny's worth of the higher rate, not a pound's.
    assertEquals(
        new BigDecimal("200.00"),
        Commission.total(Commission.earn(tiered, new BigDecimal("10000.01"), 2), 2),
        "3% of a penny rounds to nothing, and the lower band is unchanged");
  }

  @Test
  @DisplayName("A per-unit scheme counts units, and its bands count units too")
  void perUnit() {
    Scheme counter = scheme(Commission.PER_UNIT, "GBP", "0", "1.00", "100", "1.50");
    List<Earned> earned = Commission.earn(counter, new BigDecimal("150"), 2);
    assertEquals(new BigDecimal("175.00"), Commission.total(earned, 2));
    assertEquals(new BigDecimal("100.00"), earned.get(0).commission());
    assertEquals(new BigDecimal("75.00"), earned.get(1).commission());
    // Units keep a quantity's scale, not money's: 100 units, not 100.00 of anything.
    assertEquals(new BigDecimal("100.000"), earned.get(0).amountInBand());
    assertEquals(new BigDecimal("50.000"), earned.get(1).amountInBand());
  }

  @Test
  @DisplayName("A period that sold nothing earns nothing, and neither does a scheme with no bands")
  void nothing() {
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    assertTrue(Commission.earn(flat, BigDecimal.ZERO, 2).isEmpty());
    assertTrue(Commission.earn(flat, new BigDecimal("-500.00"), 2).isEmpty(), "a net refund");
    assertTrue(Commission.earn(flat, null, 2).isEmpty());
    assertTrue(Commission.earn(null, new BigDecimal("100"), 2).isEmpty());
    assertTrue(
        Commission.earn(scheme(Commission.PERCENT_OF_NET, null), new BigDecimal("100"), 2)
            .isEmpty());
    // And the total of nothing is money's zero, not an int's: a statement prints 0.00.
    assertEquals(new BigDecimal("0.00"), Commission.total(List.of(), 2));
  }

  @Test
  @DisplayName("The scheme in force is the latest one effective on or before the day sold")
  void inForce() {
    UUID person = Ids.newId();
    UUID first = Ids.newId();
    UUID second = Ids.newId();
    List<Assignment> assignments =
        List.of(
            assignment(person, first, LocalDate.of(2026, 1, 1)),
            assignment(person, second, LocalDate.of(2026, 4, 1)),
            // Taken off commission in July: the arrangement ended, which is not the absence of one.
            assignment(person, null, LocalDate.of(2026, 7, 1)));
    assertEquals(first, Commission.schemeOn(assignments, LocalDate.of(2026, 3, 31)));
    assertEquals(second, Commission.schemeOn(assignments, LocalDate.of(2026, 4, 1)));
    assertEquals(second, Commission.schemeOn(assignments, LocalDate.of(2026, 6, 30)));
    assertNull(Commission.schemeOn(assignments, LocalDate.of(2026, 7, 1)), "off commission");
    assertNull(
        Commission.schemeOn(assignments, LocalDate.of(2025, 12, 31)),
        "a day before any arrangement earns nothing, rather than the first one ever made");
    assertNull(Commission.schemeOn(List.of(), LocalDate.of(2026, 4, 1)));
  }

  @Test
  @DisplayName("A scheme that could not be paid on is refused, and the reason says what to do")
  void refusals() {
    assertTrue(
        Commission.problem("MARGIN", null, List.of(BigDecimal.ZERO), 2).contains("PERCENT_OF_NET"));
    assertTrue(
        Commission.problem(null, null, List.of(BigDecimal.ZERO), 2).contains("PERCENT_OF_NET"));
    assertTrue(
        Commission.problem(Commission.PER_UNIT, null, List.of(BigDecimal.ZERO), 2)
            .contains("currency"));
    assertTrue(
        Commission.problem(Commission.PERCENT_OF_NET, "GBP", List.of(BigDecimal.ZERO), 2)
            .contains("leave the currency out"));
    assertTrue(
        Commission.problem(Commission.PERCENT_OF_NET, null, List.of(), 2)
            .contains("one rate band"));
    assertTrue(
        Commission.problem(Commission.PERCENT_OF_NET, null, List.of(new BigDecimal("500")), 2)
            .contains("starts at zero"),
        "the first sales of every period would earn nothing, silently");
    assertTrue(
        Commission.problem(
                Commission.PERCENT_OF_NET,
                null,
                List.of(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("100")),
                2)
            .contains("undecidable"));
    assertNull(
        Commission.problem(
            Commission.PERCENT_OF_NET, null, List.of(BigDecimal.ZERO, new BigDecimal("10000")), 2));
    assertNull(Commission.problem(Commission.PER_UNIT, "GBP", List.of(BigDecimal.ZERO), 2));
  }

  @Test
  @DisplayName("A person's days are cut into segments wherever the arrangement changes")
  void segments() {
    UUID person = Ids.newId();
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    Scheme tiered = scheme(Commission.PERCENT_OF_NET, null, "0", "1", "1000", "5");
    Map<UUID, Scheme> schemes = Map.of(flat.id(), flat, tiered.id(), tiered);
    List<Assignment> assignments =
        List.of(
            assignment(person, flat.id(), LocalDate.of(2026, 9, 1)),
            assignment(person, tiered.id(), LocalDate.of(2026, 9, 16)));
    List<Commission.Day> days =
        List.of(
            day("2026-09-10", "500.00"),
            day("2026-09-15", "500.00"),
            day("2026-09-16", "900.00"),
            day("2026-09-20", "600.00"));

    List<Commission.Segment> segments =
        Commission.rate(days, assignments, schemes, "GBP", Fx::minorUnits);
    assertEquals(2, segments.size());
    // The old arrangement rates its own days only.
    assertEquals(flat.id(), segments.get(0).schemeId());
    assertEquals(new BigDecimal("1000.00"), segments.get(0).amount());
    assertEquals(new BigDecimal("20.00"), segments.get(0).commission());
    // And the new one starts its band progression from zero: 1000 at 1% then 500 at 5%, never
    // 1500 as though the first scheme's sales had already climbed this scheme's first band.
    assertEquals(tiered.id(), segments.get(1).schemeId());
    assertEquals(new BigDecimal("1500.00"), segments.get(1).amount());
    assertEquals(new BigDecimal("35.00"), segments.get(1).commission());
    assertEquals(LocalDate.of(2026, 9, 16), segments.get(1).from());
    assertEquals(LocalDate.of(2026, 9, 20), segments.get(1).to());
  }

  @Test
  @DisplayName("Days under no arrangement are reported as sales that earned nothing")
  void unearned() {
    UUID person = Ids.newId();
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    List<Assignment> assignments =
        List.of(assignment(person, flat.id(), LocalDate.of(2026, 9, 16)));
    List<Commission.Segment> segments =
        Commission.rate(
            List.of(day("2026-09-10", "400.00"), day("2026-09-20", "100.00")),
            assignments,
            Map.of(flat.id(), flat),
            "GBP",
            Fx::minorUnits);
    assertEquals(2, segments.size());
    assertNull(segments.get(0).schemeId(), "before the arrangement began");
    assertEquals(new BigDecimal("400.00"), segments.get(0).amount(), "the sales are still counted");
    assertEquals(new BigDecimal("0.00"), segments.get(0).commission());
    assertEquals(flat.id(), segments.get(1).schemeId());
    assertEquals(new BigDecimal("2.00"), segments.get(1).commission());
    // A scheme the assignment names but nothing holds is the same as no arrangement, never a crash.
    List<Commission.Segment> dangling =
        Commission.rate(
            List.of(day("2026-09-20", "100.00")), assignments, Map.of(), "GBP", Fx::minorUnits);
    assertNull(dangling.get(0).schemeId());
    assertEquals(new BigDecimal("0.00"), dangling.get(0).commission());
    assertTrue(Commission.rate(List.of(), assignments, Map.of(), "GBP", Fx::minorUnits).isEmpty());
  }

  @Test
  @DisplayName("A per-unit arrangement counts the units of its days, not their money")
  void perUnitDays() {
    UUID person = Ids.newId();
    Scheme counter = scheme(Commission.PER_UNIT, "GBP", "0", "0.50");
    List<Commission.Segment> segments =
        Commission.rate(
            List.of(
                new Commission.Day(
                    LocalDate.of(2026, 9, 1), new BigDecimal("900.00"), new BigDecimal("30")),
                new Commission.Day(
                    LocalDate.of(2026, 9, 2), new BigDecimal("300.00"), new BigDecimal("10"))),
            List.of(assignment(person, counter.id(), LocalDate.of(2026, 1, 1))),
            Map.of(counter.id(), counter),
            "GBP",
            Fx::minorUnits);
    assertEquals(1, segments.size());
    assertEquals(
        new BigDecimal("40.000"), segments.get(0).amount(), "units, at a quantity's scale");
    assertEquals(new BigDecimal("20.00"), segments.get(0).commission());
  }

  private static Commission.Day day(String on, String net) {
    return new Commission.Day(LocalDate.parse(on), new BigDecimal(net), BigDecimal.ZERO);
  }

  private static Assignment assignment(UUID person, UUID schemeId, LocalDate from) {
    return new Assignment(
        Ids.newId(), Ids.newId(), person, schemeId, from, null, Instant.now(), Ids.newId());
  }

  @Test
  @DisplayName(
      "Commission is rounded to the currency it is paid in: whole yen, a dinar's third decimal")
  void roundedToTheCurrencysOwnMinorUnits() {
    UUID person = Ids.newId();
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2.5");
    List<Assignment> on = List.of(assignment(person, flat.id(), LocalDate.of(2026, 1, 1)));

    // 2.5% of 12 345 yen is 308.625 yen: paid as 309, and the sales are whole yen too.
    List<Commission.Segment> yen =
        Commission.rate(
            List.of(day("2026-09-01", "12345")),
            on,
            Map.of(flat.id(), flat),
            "JPY",
            Fx::minorUnits);
    assertEquals(new BigDecimal("12345"), yen.get(0).amount());
    assertEquals(new BigDecimal("309"), yen.get(0).commission());

    // 2.5% of 1 234.567 dinars is 30.864175: a dinar keeps three decimals, never two.
    List<Commission.Segment> dinar =
        Commission.rate(
            List.of(day("2026-09-01", "1234.567")),
            on,
            Map.of(flat.id(), flat),
            "KWD",
            Fx::minorUnits);
    assertEquals(new BigDecimal("1234.567"), dinar.get(0).amount());
    assertEquals(new BigDecimal("30.864"), dinar.get(0).commission());

    // No arrangement: the sales still come back, at the sales' own minor units, earning a zero
    // of the same scale.
    List<Commission.Segment> none =
        Commission.rate(
            List.of(day("2026-09-01", "500")), List.of(), Map.of(), "JPY", Fx::minorUnits);
    assertEquals(new BigDecimal("0"), none.get(0).commission());
    assertEquals(new BigDecimal("500"), none.get(0).amount());

    // A per-unit scheme pays in its own currency, whatever the sales are in: 12.5 yen a unit for
    // three units is 37.5 yen, paid as whole yen, while the units keep a quantity's three decimals.
    Scheme perUnitYen = scheme(Commission.PER_UNIT, "JPY", "0", "12.5");
    List<Commission.Segment> units =
        Commission.rate(
            List.of(
                new Commission.Day(
                    LocalDate.of(2026, 9, 1), new BigDecimal("100.00"), new BigDecimal("3"))),
            List.of(assignment(person, perUnitYen.id(), LocalDate.of(2026, 1, 1))),
            Map.of(perUnitYen.id(), perUnitYen),
            "GBP",
            Fx::minorUnits);
    assertEquals(new BigDecimal("3.000"), units.get(0).amount());
    assertEquals(new BigDecimal("38"), units.get(0).commission(), "37.5 yen, as whole yen");
  }

  @Test
  @DisplayName("A band starts at an amount the business's currency can hold, or a unit count")
  void aBandStartsAtAnAmountTheCurrencyCanHold() {
    // Whole yen: a band starting at 1000.5 yen starts nowhere a sale can reach exactly.
    assertTrue(
        Commission.problem(
                Commission.PERCENT_OF_NET,
                null,
                List.of(BigDecimal.ZERO, new BigDecimal("1000.5")),
                Fx.minorUnits("JPY"))
            .contains("decimal"));
    assertNull(
        Commission.problem(
            Commission.PERCENT_OF_NET,
            null,
            List.of(BigDecimal.ZERO, new BigDecimal("1000.000")),
            Fx.minorUnits("JPY")),
        "trailing zeros are the same amount");
    // A dinar's third decimal is an amount; a fourth is not.
    assertNull(
        Commission.problem(
            Commission.PERCENT_OF_NET,
            null,
            List.of(BigDecimal.ZERO, new BigDecimal("250.125")),
            Fx.minorUnits("KWD")));
    assertTrue(
        Commission.problem(
                Commission.PERCENT_OF_NET,
                null,
                List.of(BigDecimal.ZERO, new BigDecimal("250.1255")),
                Fx.minorUnits("KWD"))
            .contains("decimal"));
    // A per-unit band starts at a whole number of units, whatever the currency: "the first
    // hundred", never "the first 10.125".
    for (String currency : List.of("GBP", "JPY", "KWD")) {
      assertNull(
          Commission.problem(
              Commission.PER_UNIT,
              currency,
              List.of(BigDecimal.ZERO, new BigDecimal("100.000")),
              Fx.minorUnits(currency)),
          "trailing zeros are the same count");
      for (String fractional : List.of("10.125", "10.5", "0.001")) {
        String problem =
            Commission.problem(
                Commission.PER_UNIT,
                currency,
                List.of(BigDecimal.ZERO, new BigDecimal(fractional)),
                Fx.minorUnits(currency));
        assertTrue(
            problem != null && problem.contains("whole number of units"),
            fractional + " units in " + currency + ": " + problem);
      }
    }
  }

  @Test
  @DisplayName(
      "Every per-unit threshold the rating answers survives the statement's rounding in any"
          + " currency")
  void aPerUnitThresholdSurvivesAnyCurrencysMinorUnits() {
    // order-svc keeps each band's threshold on a statement line at the statement currency's minor
    // units — none for yen. A per-unit threshold is a count of units, so anything a scheme can be
    // made with must come through that rounding unchanged in value, for every currency.
    List<BigDecimal> thresholds = List.of(BigDecimal.ZERO, new BigDecimal("100"));
    assertNull(Commission.problem(Commission.PER_UNIT, "GBP", thresholds, 2));
    Scheme counter = scheme(Commission.PER_UNIT, "GBP", "0", "0.50", "100", "0.75");
    List<Earned> earned = Commission.earn(counter, new BigDecimal("120.500"), 2);
    assertEquals(2, earned.size());
    for (String statementCurrency : List.of("GBP", "JPY", "KWD", "CLP", "BHD")) {
      int places = Fx.minorUnits(statementCurrency);
      for (Earned e : earned) {
        BigDecimal kept = e.thresholdFrom().setScale(places, java.math.RoundingMode.HALF_UP);
        assertEquals(
            0,
            kept.compareTo(e.thresholdFrom()),
            e.thresholdFrom() + " units became " + kept + " on a " + statementCurrency + " line");
      }
    }
  }
}
