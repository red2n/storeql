package com.storeql.customer.domain;

import com.storeql.customer.domain.Domain.LoyaltyAccount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A business's loyalty programme (13.x): its tiers — each a name, the qualifying points that reach
 * it and the earn multiplier it gives — how many months a point lives, and how many months of
 * earning count towards a tier (null for a lifetime). Pure: nothing here knows a database.
 *
 * <p>A business that has never set one has {@link #defaults}: the four tiers the platform shipped
 * with at a single multiplier, no expiry, and lifetime qualification — exactly what it had before.
 *
 * @param tenantId owning tenant
 * @param expiryMonths months a point lives from the day it is earned; null for never
 * @param qualifyingMonths months of earning that count towards a tier; null for a lifetime
 * @param tiers the tiers, lowest threshold first, the first at zero
 * @param reason why it was last set
 * @param setBy who set it; null for the default
 * @param setAt when
 */
public record LoyaltyProgramme(
    UUID tenantId,
    Integer expiryMonths,
    Integer qualifyingMonths,
    List<Tier> tiers,
    String reason,
    UUID setBy,
    Instant setAt) {

  /** One rung of the ladder. */
  public record Tier(String name, BigDecimal threshold, BigDecimal multiplier) {}

  public static final int MAX_TIERS = 6;
  public static final int MAX_EXPIRY_MONTHS = 120;
  public static final int MAX_QUALIFYING_MONTHS = 36;
  public static final BigDecimal MAX_MULTIPLIER = BigDecimal.TEN;

  /** A rule that changes never takes a point sooner than this many days from the change. */
  public static final int NOTICE_DAYS = 30;

  private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,29}");

  private static final List<Tier> DEFAULT_TIERS =
      List.of(
          new Tier(LoyaltyAccount.TIER_BRONZE, BigDecimal.ZERO, BigDecimal.ONE),
          new Tier(LoyaltyAccount.TIER_SILVER, new BigDecimal("1000"), BigDecimal.ONE),
          new Tier(LoyaltyAccount.TIER_GOLD, new BigDecimal("5000"), BigDecimal.ONE),
          new Tier(LoyaltyAccount.TIER_PLATINUM, new BigDecimal("20000"), BigDecimal.ONE));

  public LoyaltyProgramme {
    tiers = List.copyOf(tiers);
  }

  /** What a business has before it sets anything: today's behaviour, unchanged. */
  public static LoyaltyProgramme defaults(UUID tenantId) {
    return new LoyaltyProgramme(tenantId, null, null, DEFAULT_TIERS, null, null, null);
  }

  /** True for the platform's default rather than something the business chose. */
  public boolean isDefault() {
    return setAt == null;
  }

  public boolean expires() {
    return expiryMonths != null;
  }

  /** The highest tier whose threshold the points reach. */
  public Tier tierFor(BigDecimal qualifyingPoints) {
    Tier best = tiers.get(0);
    for (Tier t : tiers) {
      if (qualifyingPoints.compareTo(t.threshold()) >= 0) {
        best = t;
      }
    }
    return best;
  }

  /** The first tier the points have not reached, if any. */
  public Optional<Tier> nextTier(BigDecimal qualifyingPoints) {
    for (Tier t : tiers) {
      if (qualifyingPoints.compareTo(t.threshold()) < 0) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** How many more qualifying points reach the next tier; null at the top. */
  public BigDecimal pointsToNextTier(BigDecimal qualifyingPoints) {
    return nextTier(qualifyingPoints)
        .map(t -> t.threshold().subtract(qualifyingPoints))
        .orElse(null);
  }

  /** The earn multiplier of a tier by name; one for a name the ladder does not have. */
  public BigDecimal multiplierFor(String tierName) {
    for (Tier t : tiers) {
      if (t.name().equals(tierName)) {
        return t.multiplier();
      }
    }
    return BigDecimal.ONE;
  }

  /** When a point earned at {@code earnedAt} dies; null when points never do. */
  public Instant expiryFor(Instant earnedAt) {
    return expiryMonths == null ? null : plusMonths(earnedAt, expiryMonths);
  }

  /**
   * An open lot's expiry once a rule is set or changed: the rule's own date, but never sooner than
   * {@link #NOTICE_DAYS} from now — a rule cannot take points overnight.
   */
  public static Instant reexpiry(Instant earnedAt, int expiryMonths, Instant now) {
    Instant byRule = plusMonths(earnedAt, expiryMonths);
    Instant notice = now.plus(NOTICE_DAYS, ChronoUnit.DAYS);
    return byRule.isBefore(notice) ? notice : byRule;
  }

  /**
   * Why a programme cannot be honoured, or null when it can: months out of range, no tiers or more
   * than six, a first tier not at zero, thresholds not ascending, a repeated or ill-formed name, a
   * multiplier below one or above ten.
   */
  public static String validate(Integer expiryMonths, Integer qualifyingMonths, List<Tier> tiers) {
    if (expiryMonths != null && (expiryMonths < 1 || expiryMonths > MAX_EXPIRY_MONTHS)) {
      return "expiryMonths must be between 1 and " + MAX_EXPIRY_MONTHS + ", or absent for never";
    }
    if (qualifyingMonths != null
        && (qualifyingMonths < 1 || qualifyingMonths > MAX_QUALIFYING_MONTHS)) {
      return "qualifyingMonths must be between 1 and "
          + MAX_QUALIFYING_MONTHS
          + ", or absent for a lifetime";
    }
    if (tiers == null || tiers.isEmpty()) {
      return "at least one tier is required";
    }
    if (tiers.size() > MAX_TIERS) {
      return "at most six tiers";
    }
    if (tiers.get(0).threshold() == null || tiers.get(0).threshold().signum() != 0) {
      return "the first tier starts at zero";
    }
    Set<String> names = new HashSet<>();
    BigDecimal last = null;
    for (Tier t : tiers) {
      if (t.name() == null || !NAME.matcher(t.name()).matches()) {
        return "a tier name is 2 to 30 characters: capitals, digits and underscores, starting with a letter";
      }
      if (!names.add(t.name())) {
        return "each tier name once";
      }
      if (t.threshold() == null || t.threshold().signum() < 0) {
        return "a threshold is zero or more";
      }
      if (last != null && t.threshold().compareTo(last) <= 0) {
        return "thresholds must be ascending";
      }
      last = t.threshold();
      if (t.multiplier() == null
          || t.multiplier().compareTo(BigDecimal.ONE) < 0
          || t.multiplier().compareTo(MAX_MULTIPLIER) > 0
          || t.multiplier().stripTrailingZeros().scale() > 3) {
        return "a multiplier is between 1 and 10, to three decimal places";
      }
    }
    return null;
  }

  private static Instant plusMonths(Instant at, int months) {
    return at.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
  }
}
