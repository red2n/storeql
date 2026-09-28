package com.storeql.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Competitor-driven repricing (03.x): the arithmetic between "what a rival charges" and "what we
 * propose to charge". Pure; the service around it reads the observations and writes the proposal.
 *
 * <p>A rule reacts to being undercut and to nothing else: it never proposes raising a price toward
 * a dearer rival, it never goes below its floor, and a {@code .99} ending is reached only by
 * rounding down, so a rounded proposal is never above the price the rule aimed at. The floor is a
 * share of the current price because pricing-svc holds no cost; a rule that must protect a margin
 * is a matter for the buyer's cost in purchase-svc, and the API says so.
 */
public final class Repricing {

  private Repricing() {}

  /** How the proposal is aimed at the lowest fresh rival price. */
  public enum Strategy {
    /** Charge what the lowest rival charges. */
    MATCH_LOWEST,
    /** Charge the rival's price less {@code value} percent. */
    UNDERCUT_PERCENT,
    /** Charge the rival's price less {@code value} in money. */
    UNDERCUT_AMOUNT
  }

  /** How the aimed-at price is tidied. */
  public enum Rounding {
    /** To the currency's minor unit, half up. */
    NONE,
    /** Down to the largest price ending in .99 (whole units less one minor unit) not above it. */
    ENDING_99
  }

  /**
   * The rule's arithmetic. {@code value} is the percentage (0–100) or the amount, by strategy;
   * {@code floorPercent} is the share of the current price the proposal may not go below.
   */
  public record Rule(
      Strategy strategy,
      BigDecimal value,
      BigDecimal floorPercent,
      Rounding rounding,
      int maxAgeDays) {}

  /** One rival's price on one day. */
  public record Observation(String competitor, BigDecimal price, LocalDate observedOn) {}

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * The price a rule proposes against the lowest fresh rival price, or nothing when the rule has
   * nothing to say: the rival is not cheaper, the floor forbids any cut, or the tidied price is the
   * current one.
   *
   * @param rule the strategy, its value, the floor and the rounding
   * @param current what the list charges now
   * @param competitor the lowest fresh rival price
   * @param minorUnits the currency's decimal places (2 for GBP, 0 for JPY)
   * @return the proposed price at the currency's scale, or empty
   */
  public static Optional<BigDecimal> propose(
      Rule rule, BigDecimal current, BigDecimal competitor, int minorUnits) {
    if (competitor.compareTo(current) >= 0) return Optional.empty();
    BigDecimal target =
        switch (rule.strategy()) {
          case MATCH_LOWEST -> competitor;
          case UNDERCUT_PERCENT ->
              competitor
                  .multiply(HUNDRED.subtract(rule.value()))
                  .divide(HUNDRED, 10, RoundingMode.HALF_UP);
          case UNDERCUT_AMOUNT -> competitor.subtract(rule.value());
        };
    BigDecimal floor =
        current
            .multiply(rule.floorPercent())
            .divide(HUNDRED, minorUnits, RoundingMode.HALF_UP)
            .max(smallestUnit(minorUnits));
    BigDecimal aimed = target.max(floor);
    BigDecimal proposed = aimed.setScale(minorUnits, RoundingMode.HALF_UP);
    if (rule.rounding() == Rounding.ENDING_99) {
      // From the unrounded aim, so a half-up to the minor unit never lifts the .99 above it.
      BigDecimal tidy = endingNinetyNine(aimed, minorUnits);
      if (tidy.compareTo(floor) >= 0) proposed = tidy;
    }
    if (proposed.compareTo(current) >= 0) return Optional.empty();
    return Optional.of(proposed);
  }

  /**
   * The largest price not above {@code price} that ends in .99 — whole units less one minor unit. A
   * currency without minor units has no .99 to end in and is rounded down to whole units.
   */
  static BigDecimal endingNinetyNine(BigDecimal price, int minorUnits) {
    if (minorUnits == 0) return price.setScale(0, RoundingMode.FLOOR);
    BigDecimal unit = smallestUnit(minorUnits);
    BigDecimal whole = price.setScale(0, RoundingMode.FLOOR);
    BigDecimal candidate =
        whole.add(BigDecimal.ONE).subtract(unit).setScale(minorUnits, RoundingMode.UNNECESSARY);
    if (candidate.compareTo(price) > 0)
      candidate = whole.subtract(unit).setScale(minorUnits, RoundingMode.UNNECESSARY);
    return candidate.max(unit);
  }

  private static BigDecimal smallestUnit(int minorUnits) {
    return BigDecimal.ONE.movePointLeft(minorUnits);
  }

  /**
   * The rival price a rule answers: each competitor's freshest observation within {@code
   * maxAgeDays} of {@code today}, and the lowest of those. Older observations are superseded by
   * newer ones from the same rival even when lower; a rival not seen recently does not count.
   */
  public static Optional<Observation> lowestFresh(
      List<Observation> seen, LocalDate today, int maxAgeDays) {
    LocalDate oldest = today.minusDays(maxAgeDays);
    Map<String, Observation> latest = new HashMap<>();
    for (Observation o : seen) {
      if (o.observedOn().isBefore(oldest) || o.observedOn().isAfter(today)) continue;
      Observation have = latest.get(o.competitor());
      if (have == null || o.observedOn().isAfter(have.observedOn())) latest.put(o.competitor(), o);
    }
    return latest.values().stream().min(Comparator.comparing(Observation::price));
  }
}
