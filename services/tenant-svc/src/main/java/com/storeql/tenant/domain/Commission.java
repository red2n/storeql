package com.storeql.tenant.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * What a sale earns the person who made it: the arrangement, not the money.
 *
 * <p>The hours are recorded and what they cost is known ({@link Workforce}); on a counter that pays
 * commission, neither answers what somebody is owed. This holds the scheme and who is on it. The
 * earning itself is worked out where the sales are, because a period's every sale and return must
 * not cross a service boundary to be counted.
 *
 * <p>Two decisions shape everything here. A scheme is <b>superseded, never edited</b>, so
 * commission earned at 2% cannot become 3% because somebody corrected the scheme in April. And its
 * bands are <b>marginal</b>: only the part of a period's sales inside a band earns that band's
 * rate, because the alternative — re-rating the whole period once a threshold is crossed — makes a
 * figure that changes after the fact, and nobody can be paid on one of those.
 */
public final class Commission {

  private Commission() {}

  /** A percentage of the net sold: VAT out, discounts off. */
  public static final String PERCENT_OF_NET = "PERCENT_OF_NET";

  /** A fixed amount for each unit sold. */
  public static final String PER_UNIT = "PER_UNIT";

  public static final List<String> BASES = List.of(PERCENT_OF_NET, PER_UNIT);

  public static final String ACTIVE = "ACTIVE";
  public static final String WITHDRAWN = "WITHDRAWN";

  /**
   * Units keep the scale every quantity in this platform keeps.
   *
   * <p>A band's figures are shown on a statement beside each other, and a band that answered {@code
   * 10000} where the next answered {@code 5000.00} would be the same mixed-scale drift that has
   * cost this codebase two reports already — so what a band holds is scaled here, once.
   */
  private static final int UNIT_SCALE = 3;

  /**
   * A per-unit band starts at a whole number of units ("the first hundred"), shown at {@link
   * #UNIT_SCALE} like any count.
   *
   * <p>Whole, not a quantity's three places, because the threshold travels: order-svc keeps each
   * band's threshold on a commission statement line at the statement currency's minor units, and
   * the fewest ISO 4217 gives is none (yen, won, pesos). A fractional count would be rounded there
   * (2.125 to 2.13, 0.5 to 1) and the statement would show a band nobody set. Unit tiers are
   * counted in whole units in practice, weighed goods included (the first 100 kg), so nothing a
   * shop needs is refused.
   */
  private static final int UNIT_THRESHOLD_SCALE = 0;

  /**
   * One rate band.
   *
   * @param thresholdFrom the period-to-date net sales at which this band starts; the first is zero
   * @param rate a percentage for {@link #PERCENT_OF_NET} (2.5 means 2.5%), an amount per unit for
   *     {@link #PER_UNIT}
   */
  public record Band(UUID id, UUID schemeId, BigDecimal thresholdFrom, BigDecimal rate) {}

  /**
   * A commission arrangement.
   *
   * @param currency the money a {@link #PER_UNIT} amount is in; null for a percentage
   * @param supersedes the version this one replaces, when it was corrected
   */
  public record Scheme(
      UUID id,
      UUID tenantId,
      String name,
      String basis,
      String currency,
      String status,
      String note,
      UUID supersedes,
      UUID supersededBy,
      Instant createdAt,
      UUID createdBy,
      List<Band> bands) {

    public Scheme {
      bands = bands == null ? List.of() : bands.stream().sorted(BY_THRESHOLD).toList();
    }

    public boolean withdrawn() {
      return WITHDRAWN.equals(status);
    }

    public boolean perUnit() {
      return PER_UNIT.equals(basis);
    }

    /** The version in force: a scheme that something else replaced is not one to earn under. */
    public boolean current() {
      return supersededBy == null && !withdrawn();
    }
  }

  /**
   * A scheme with each band's threshold read back at its own scale: a percentage band's at the
   * minor units of the business's currency (1000.00 pounds, 1000 yen, 250.125 dinars), a per-unit
   * band's at a quantity's. Never fewer places than the figure has, so nothing kept is rounded away
   * on the way out.
   *
   * @param salesMinorUnits the minor units of the business's currency
   */
  public static Scheme atScale(Scheme scheme, int salesMinorUnits) {
    if (scheme == null) return null;
    int places = scheme.perUnit() ? UNIT_SCALE : salesMinorUnits;
    List<Band> bands =
        scheme.bands().stream()
            .map(
                b ->
                    new Band(
                        b.id(),
                        b.schemeId(),
                        b.thresholdFrom() == null
                            ? null
                            : b.thresholdFrom()
                                .setScale(
                                    Math.max(
                                        places, b.thresholdFrom().stripTrailingZeros().scale()),
                                    RoundingMode.UNNECESSARY),
                        b.rate()))
            .toList();
    return new Scheme(
        scheme.id(),
        scheme.tenantId(),
        scheme.name(),
        scheme.basis(),
        scheme.currency(),
        scheme.status(),
        scheme.note(),
        scheme.supersedes(),
        scheme.supersededBy(),
        scheme.createdAt(),
        scheme.createdBy(),
        bands);
  }

  private static final Comparator<Band> BY_THRESHOLD =
      Comparator.comparing(Band::thresholdFrom, Comparator.naturalOrder());

  /**
   * Who is on which scheme, from which day.
   *
   * @param schemeId null ends the arrangement from that day — an arrangement that stopped is not
   *     the absence of one, and a shop must be able to say when it stopped
   */
  public record Assignment(
      UUID id,
      UUID tenantId,
      UUID userId,
      UUID schemeId,
      LocalDate effectiveFrom,
      String note,
      Instant createdAt,
      UUID createdBy) {}

  /**
   * The scheme in force for a day: the latest assignment effective on or before it.
   *
   * <p>The day, not today — exactly as a pay rate is read ({@link Workforce#cost}), so a scheme
   * somebody moved onto in April does not re-earn January.
   *
   * @param assignments the person's assignments, in any order
   * @return the scheme id in force, or null when the person was on none that day
   */
  public static UUID schemeOn(List<Assignment> assignments, LocalDate day) {
    Assignment best = null;
    for (Assignment a : assignments) {
      if (a.effectiveFrom().isAfter(day)) continue;
      if (best == null || a.effectiveFrom().isAfter(best.effectiveFrom())) best = a;
    }
    return best == null ? null : best.schemeId();
  }

  /**
   * What a band earned.
   *
   * @param amountInBand the net sales (or units) that fell inside this band
   */
  public record Earned(
      BigDecimal thresholdFrom, BigDecimal rate, BigDecimal amountInBand, BigDecimal commission) {}

  /**
   * What a period's sales earn under a scheme, band by band.
   *
   * <p>Marginal, and stated band by band rather than as one figure, because a person paid
   * commission is entitled to see which part of what they sold earned which rate. A scheme with a
   * single band at zero — most of them — answers one line.
   *
   * <p>{@code PER_UNIT} thresholds are read as units rather than money, which is the only reading
   * that makes sense of "the first hundred cost a pound each": the bands count the same thing the
   * rate is charged on.
   *
   * <p>Commission is money, so it is rounded like money, once, at the end — to the minor units of
   * the currency it is paid in (ISO 4217: whole yen, a dinar's third decimal), never an assumed
   * two. A percentage of net sales is in the sales' currency, whose minor units the net sales are
   * held to as well; a per-unit amount is in the scheme's own currency, and its units keep the
   * platform's quantity scale.
   *
   * @param amount the period's net sales for {@link #PERCENT_OF_NET}, its units for {@link
   *     #PER_UNIT}; negative or zero earns nothing
   * @param moneyScale the minor units of the currency the commission is paid in
   */
  public static List<Earned> earn(Scheme scheme, BigDecimal amount, int moneyScale) {
    if (scheme == null || amount == null || amount.signum() <= 0 || scheme.bands().isEmpty()) {
      return List.of();
    }
    List<Band> bands = scheme.bands();
    List<Earned> earned = new ArrayList<>();
    for (int i = 0; i < bands.size(); i++) {
      Band band = bands.get(i);
      if (amount.compareTo(band.thresholdFrom()) <= 0) break;
      BigDecimal ceiling = i + 1 < bands.size() ? bands.get(i + 1).thresholdFrom() : null;
      BigDecimal top = ceiling == null || amount.compareTo(ceiling) < 0 ? amount : ceiling;
      BigDecimal inBand = top.subtract(band.thresholdFrom());
      if (inBand.signum() <= 0) continue;
      BigDecimal commission =
          scheme.perUnit()
              ? band.rate().multiply(inBand)
              : inBand
                  .multiply(band.rate())
                  .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
      int scale = scheme.perUnit() ? UNIT_SCALE : moneyScale;
      earned.add(
          new Earned(
              band.thresholdFrom().setScale(scale, RoundingMode.HALF_UP),
              band.rate(),
              inBand.setScale(scale, RoundingMode.HALF_UP),
              commission.setScale(moneyScale, RoundingMode.HALF_UP)));
    }
    return List.copyOf(earned);
  }

  /**
   * The total of what the bands earned, at money's own scale.
   *
   * @param moneyScale the minor units of the currency the commission is paid in
   */
  public static BigDecimal total(List<Earned> earned, int moneyScale) {
    BigDecimal total = BigDecimal.ZERO.setScale(moneyScale);
    for (Earned e : earned) total = total.add(e.commission());
    return total;
  }

  /**
   * The minor units commission under a scheme is paid in: a per-unit scheme's own currency, else
   * the sales' (a percentage of them, or no scheme at all).
   */
  private static int moneyScale(
      Scheme scheme, String salesCurrency, ToIntFunction<String> minorUnits) {
    return minorUnits.applyAsInt(
        scheme != null && scheme.perUnit() ? scheme.currency() : salesCurrency);
  }

  /**
   * A day's attributed sales for one person, as the service that holds the sales reports them.
   *
   * @param net what the shop took on that day, net: VAT out, discounts off
   * @param units how many units were sold, for a per-unit arrangement
   */
  public record Day(LocalDate day, BigDecimal net, BigDecimal units) {}

  /**
   * A stretch of days under one arrangement, and what it earned.
   *
   * @param schemeId null when the person was on no arrangement for those days — reported rather
   *     than dropped, because sales that earned nothing are what a manager needs to see
   */
  public record Segment(
      UUID schemeId,
      LocalDate from,
      LocalDate to,
      BigDecimal amount,
      List<Earned> earned,
      BigDecimal commission) {

    public Segment {
      earned = earned == null ? List.of() : List.copyOf(earned);
    }
  }

  /**
   * What a person's days earn, arrangement by arrangement.
   *
   * <p>Days are walked in order and cut into segments wherever the arrangement changes, because
   * <b>a new arrangement starts its own band progression</b>: somebody moved onto a tiered scheme
   * mid-month has not already sold their way up its first band. Rating the period as one lump would
   * either credit or rob them of that, depending on which scheme happened to be read.
   *
   * <p>A day under no arrangement is still a segment, with no scheme and no commission. Dropping it
   * would make a statement whose sales do not add up to the period's takings, which is the first
   * thing anybody checks.
   *
   * @param schemes the schemes the assignments name, by id; one missing is treated as no
   *     arrangement
   * @param salesCurrency the currency the net sales are in: the business's own
   * @param minorUnits the minor units of a currency, as {@code Fx.minorUnits} gives them
   */
  public static List<Segment> rate(
      List<Day> days,
      List<Assignment> assignments,
      Map<UUID, Scheme> schemes,
      String salesCurrency,
      ToIntFunction<String> minorUnits) {
    if (days == null || days.isEmpty()) return List.of();
    List<Day> ordered = days.stream().sorted(Comparator.comparing(Day::day)).toList();
    List<Segment> segments = new ArrayList<>();
    UUID current = null;
    LocalDate from = null;
    LocalDate to = null;
    BigDecimal amount = BigDecimal.ZERO;
    boolean open = false;
    for (Day d : ordered) {
      UUID schemeId = schemeOn(assignments, d.day());
      Scheme scheme = schemeId == null ? null : schemes.get(schemeId);
      UUID effective = scheme == null ? null : schemeId;
      if (open && !java.util.Objects.equals(effective, current)) {
        segments.add(close(current, from, to, amount, schemes, salesCurrency, minorUnits));
        open = false;
      }
      if (!open) {
        current = effective;
        from = d.day();
        amount = BigDecimal.ZERO;
        open = true;
      }
      to = d.day();
      BigDecimal counted = counted(scheme, d);
      if (counted != null) amount = amount.add(counted);
    }
    if (open) {
      segments.add(close(current, from, to, amount, schemes, salesCurrency, minorUnits));
    }
    return List.copyOf(segments);
  }

  /** What a day contributes: its units under a per-unit scheme, its net under a percentage. */
  private static BigDecimal counted(Scheme scheme, Day day) {
    if (scheme != null && scheme.perUnit()) return day.units();
    return day.net();
  }

  private static Segment close(
      UUID schemeId,
      LocalDate from,
      LocalDate to,
      BigDecimal amount,
      Map<UUID, Scheme> schemes,
      String salesCurrency,
      ToIntFunction<String> minorUnits) {
    Scheme scheme = schemeId == null ? null : schemes.get(schemeId);
    int moneyScale = moneyScale(scheme, salesCurrency, minorUnits);
    int scale = scheme != null && scheme.perUnit() ? UNIT_SCALE : moneyScale;
    BigDecimal counted =
        (amount == null ? BigDecimal.ZERO : amount).setScale(scale, RoundingMode.HALF_UP);
    List<Earned> earned = earn(scheme, counted, moneyScale);
    return new Segment(schemeId, from, to, counted, earned, total(earned, moneyScale));
  }

  /**
   * What is wrong with a scheme as somebody asked for it, or null when nothing is.
   *
   * <p>The bands are the part worth refusing: a scheme whose first band does not start at zero
   * leaves the first sales of every period earning nothing, silently, and a shop would find out
   * from a statement somebody disputes.
   *
   * <p>A band starts where a sale can reach: at an amount of the business's currency, held to that
   * currency's minor units (a band at 1000.5 yen is one no takings can equal), or at a whole number
   * of units ({@link #UNIT_THRESHOLD_SCALE}: what every statement currency can hold unchanged).
   *
   * @param salesMinorUnits the minor units of the business's currency, as {@code Fx.minorUnits}
   *     gives them; what a {@link #PERCENT_OF_NET} band's threshold is held to
   */
  public static String problem(
      String basis, String currency, List<BigDecimal> thresholds, int salesMinorUnits) {
    if (basis == null || !BASES.contains(basis)) {
      return "a commission basis is PERCENT_OF_NET or PER_UNIT";
    }
    boolean perUnit = PER_UNIT.equals(basis);
    if (perUnit && (currency == null || currency.isBlank())) {
      return "a per-unit scheme pays an amount, so it needs the currency that amount is in";
    }
    if (!perUnit && currency != null && !currency.isBlank()) {
      return "a percentage is a ratio, not an amount: leave the currency out";
    }
    if (thresholds == null || thresholds.isEmpty()) return "a scheme needs at least one rate band";
    int places = perUnit ? UNIT_THRESHOLD_SCALE : salesMinorUnits;
    for (BigDecimal t : thresholds) {
      if (t != null && t.stripTrailingZeros().scale() > places) {
        return perUnit
            ? "a per-unit band starts at a whole number of units: "
                + t.toPlainString()
                + " is not one"
            : "a band starts at an amount of the business's currency, which has "
                + places
                + " decimal places: "
                + t.toPlainString()
                + " is not one";
      }
    }
    List<BigDecimal> sorted = thresholds.stream().sorted().toList();
    if (sorted.get(0).signum() != 0) {
      return "the first band starts at zero, or the first sales of every period earn nothing";
    }
    for (int i = 1; i < sorted.size(); i++) {
      if (sorted.get(i).compareTo(sorted.get(i - 1)) == 0) {
        return "two bands starting at " + sorted.get(i) + " leave the rate undecidable";
      }
    }
    return null;
  }
}
