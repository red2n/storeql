package com.storeql.pricing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.pricing.domain.Repricing.Observation;
import com.storeql.pricing.domain.Repricing.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Competitor-driven repricing (03.x): the arithmetic that turns "what a rival charges" into "what
 * we propose to charge", written before the code that runs it.
 *
 * <p>A rule reacts to being undercut: it never proposes raising a price toward a dearer rival,
 * never goes below its floor (a percentage of the current price, because pricing-svc holds no
 * cost), and rounds to a .99 ending only downward, so a rounded price is never above the target.
 */
class RepricingTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

  private static Rule rule(String strategy, String value, String floor, String rounding) {
    return new Rule(
        Repricing.Strategy.valueOf(strategy),
        new BigDecimal(value),
        new BigDecimal(floor),
        Repricing.Rounding.valueOf(rounding),
        14);
  }

  private static BigDecimal money(String s) {
    return new BigDecimal(s);
  }

  @Test
  void matchesTheLowestRival() {
    Optional<BigDecimal> p =
        Repricing.propose(
            rule("MATCH_LOWEST", "0", "50", "NONE"), money("10.00"), money("9.40"), 2);
    assertEquals(money("9.40"), p.orElseThrow());
  }

  @Test
  void undercutsByAPercentageOrAnAmountRoundedToTheMinorUnit() {
    assertEquals(
        money("9.31"),
        Repricing.propose(
                rule("UNDERCUT_PERCENT", "1", "50", "NONE"), money("10.00"), money("9.40"), 2)
            .orElseThrow());
    assertEquals(
        money("9.15"),
        Repricing.propose(
                rule("UNDERCUT_AMOUNT", "0.25", "50", "NONE"), money("10.00"), money("9.40"), 2)
            .orElseThrow());
    // Whole yen: nothing after the point.
    assertEquals(
        money("931"),
        Repricing.propose(
                rule("UNDERCUT_PERCENT", "1", "50", "NONE"), money("1000"), money("940"), 0)
            .orElseThrow());
  }

  @Test
  void neverProposesARiseAndNothingWhenAlreadyAtTheTarget() {
    // A dearer rival is not a reason to raise the price.
    assertTrue(
        Repricing.propose(
                rule("MATCH_LOWEST", "0", "50", "NONE"), money("10.00"), money("12.00"), 2)
            .isEmpty());
    // Already matching: nothing to propose.
    assertTrue(
        Repricing.propose(rule("MATCH_LOWEST", "0", "50", "NONE"), money("9.40"), money("9.40"), 2)
            .isEmpty());
  }

  @Test
  void theFloorHolds() {
    // 80% of 10.00 is 8.00; a rival at 5.00 pulls the proposal down only to the floor.
    assertEquals(
        money("8.00"),
        Repricing.propose(rule("MATCH_LOWEST", "0", "80", "NONE"), money("10.00"), money("5.00"), 2)
            .orElseThrow());
    // A floor at 100% forbids any cut at all.
    assertTrue(
        Repricing.propose(
                rule("MATCH_LOWEST", "0", "100", "NONE"), money("10.00"), money("9.00"), 2)
            .isEmpty());
  }

  @Test
  void ninetyNineEndingsRoundDownOnlyAndNeverThroughTheFloor() {
    // 9.40 × 0.99 = 9.306 → the largest .99 not above it: 8.99.
    assertEquals(
        money("8.99"),
        Repricing.propose(
                rule("UNDERCUT_PERCENT", "1", "50", "ENDING_99"), money("10.00"), money("9.40"), 2)
            .orElseThrow());
    // Already a .99: kept.
    assertEquals(
        money("8.99"),
        Repricing.propose(
                rule("MATCH_LOWEST", "0", "50", "ENDING_99"), money("10.00"), money("8.99"), 2)
            .orElseThrow());
    // Rounding down would cross the floor (85% of 10.00 = 8.50; 8.60 → 7.99 is below it), so the
    // unrounded target stands.
    assertEquals(
        money("8.60"),
        Repricing.propose(
                rule("MATCH_LOWEST", "0", "85", "ENDING_99"), money("10.00"), money("8.60"), 2)
            .orElseThrow());
    // A currency without a minor unit has no .99 to end in: whole units, rounded down.
    assertEquals(
        money("939"),
        Repricing.propose(
                rule("MATCH_LOWEST", "0", "50", "ENDING_99"), money("1000"), money("939.5"), 0)
            .orElseThrow());
  }

  @Test
  void theLowestFreshObservationPerRivalIsTheOneThatCounts() {
    List<Observation> seen =
        List.of(
            new Observation("Rival A", money("9.40"), TODAY.minusDays(1)),
            new Observation("Rival A", money("8.00"), TODAY.minusDays(20)), // stale, and older
            new Observation("Rival B", money("9.10"), TODAY.minusDays(3)),
            new Observation("Rival B", money("9.90"), TODAY.minusDays(10)), // superseded
            new Observation("Rival C", money("1.00"), TODAY.minusDays(15))); // stale
    Optional<Observation> lowest = Repricing.lowestFresh(seen, TODAY, 14);
    assertEquals("Rival B", lowest.orElseThrow().competitor());
    assertEquals(money("9.10"), lowest.orElseThrow().price());
    assertTrue(Repricing.lowestFresh(seen, TODAY, 0).isEmpty());
  }
}
