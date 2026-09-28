package com.storeql.purchase.domain;

import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Deferred revenue for loyalty points and gift cards (17.11): the pure part, so every rule can be
 * tested without a database.
 *
 * <p>FRS 102 section 23, as revised by the 2024 periodic review for periods from 1 January 2026,
 * takes IFRS 15's five-step model. A point earned with a purchase is a separate promise, so part of
 * the sale's revenue pays for it and is deferred until the point is spent or lapses (B39–B43): the
 * sale's net revenue is split between the goods and the points in proportion to their standalone
 * selling prices, the points valued at what they are worth to the shopper less the share the
 * business expects never to be spent. A gift card sold is not revenue but a liability, and the
 * value the business expects nobody to claim is recognised in proportion as the rest is claimed
 * (B44–B47).
 *
 * <p>Points are pooled for the tenant rather than tracked one by one. A point spent releases its
 * share of the income deferred against the points expected to be spent, so a pool that is spent as
 * expected is empty when its last expected point goes; when no points remain outstanding, whatever
 * is left is breakage. Events for points arrive on three topics and may be read out of order: a
 * redemption of points the pool has not yet seen earned is held as unmatched and settled against
 * the earning when it arrives, released in the same journal.
 */
public final class DeferredRevenue {

  /** The most a point can be said to be worth. Anything above is a typo for a currency amount. */
  public static final BigDecimal MAX_POINT_VALUE = new BigDecimal("1000");

  /** The highest breakage estimate accepted: a scheme expected to be 96% unspent is not one. */
  public static final BigDecimal MAX_BREAKAGE_PCT = new BigDecimal("95");

  public static final String CODE_POINT_VALUE_INVALID = "PURCHASE_POINT_VALUE_INVALID";
  public static final String CODE_BREAKAGE_OUT_OF_RANGE = "PURCHASE_BREAKAGE_OUT_OF_RANGE";

  /** A gift card given away rather than sold: a marketing cost, not a tender. */
  public static final String PAID_BY_PROMOTIONAL = "PROMOTIONAL";

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int WORKING_SCALE = 10;

  private DeferredRevenue() {}

  /** The tenant accountant's estimates. Percentages are 0 to {@link #MAX_BREAKAGE_PCT}. */
  public record Settings(
      BigDecimal pointValue, BigDecimal pointsBreakagePct, BigDecimal giftCardBreakagePct) {

    /** The share of points the business expects to be spent. Never zero. */
    BigDecimal pointsSpentShare() {
      return BigDecimal.ONE.subtract(share(pointsBreakagePct));
    }

    BigDecimal giftCardBreakageShare() {
      return share(giftCardBreakagePct);
    }

    private static BigDecimal share(BigDecimal pct) {
      return pct.divide(HUNDRED, WORKING_SCALE, RoundingMode.HALF_UP);
    }
  }

  /**
   * The tenant's points: those outstanding, the income deferred against them, and points spent
   * before the ledger saw them earned.
   */
  public record PointsPool(BigDecimal outstanding, BigDecimal deferred, BigDecimal unmatched) {
    public static final PointsPool EMPTY =
        new PointsPool(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** The tenant's gift cards since the ledger began to follow them. */
  public record GiftCardPool(BigDecimal loaded, BigDecimal redeemed, BigDecimal breakage) {
    public static final GiftCardPool EMPTY =
        new GiftCardPool(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    /** What is still owed to cardholders on the ledger's view: loaded, less spent and breakage. */
    public BigDecimal liability() {
      return loaded.subtract(redeemed).subtract(breakage);
    }
  }

  /** Where a posting belongs: the tenant, the document or event behind it, a store, a date. */
  public record Source(UUID tenantId, UUID ref, UUID storeId, LocalDate date) {}

  public record PointsOutcome(PointsPool pool, List<NominalLedgerEntry> posting) {}

  public record GiftCardOutcome(GiftCardPool pool, List<NominalLedgerEntry> posting) {}

  /**
   * Why a set of estimates would be refused, or {@code null} when an accountant could mean them.
   *
   * @return {@link #CODE_POINT_VALUE_INVALID} or {@link #CODE_BREAKAGE_OUT_OF_RANGE}, or null
   */
  public static String refusal(
      BigDecimal pointValue, BigDecimal pointsBreakagePct, BigDecimal giftCardBreakagePct) {
    if (pointValue == null
        || pointValue.signum() <= 0
        || pointValue.compareTo(MAX_POINT_VALUE) > 0
        || pointValue.stripTrailingZeros().scale() > 4) {
      return CODE_POINT_VALUE_INVALID;
    }
    if (!percentage(pointsBreakagePct) || !percentage(giftCardBreakagePct)) {
      return CODE_BREAKAGE_OUT_OF_RANGE;
    }
    return null;
  }

  private static boolean percentage(BigDecimal pct) {
    return pct != null
        && pct.signum() >= 0
        && pct.compareTo(MAX_BREAKAGE_PCT) <= 0
        && pct.stripTrailingZeros().scale() <= 2;
  }

  /**
   * Points earned. With a sale ({@code orderTotal} given) the income for them comes out of the
   * sale's net revenue, Dr sales; without one they are a gift, Dr loyalty points awarded. Points
   * already spent before this earning reached the ledger release their share at once.
   *
   * @param orderTotal the sale's total including VAT, or null for points given away
   * @param orderTax the VAT inside it
   */
  public static PointsOutcome earned(
      Source src,
      Settings s,
      PointsPool pool,
      BigDecimal points,
      BigDecimal orderTotal,
      BigDecimal orderTax) {
    if (points == null || points.signum() <= 0) return new PointsOutcome(pool, List.of());
    BigDecimal standalone = points.multiply(s.pointValue()).multiply(s.pointsSpentShare());
    boolean fromSale = orderTotal != null;
    BigDecimal deferral;
    if (fromSale) {
      BigDecimal tax = orderTax == null ? BigDecimal.ZERO : orderTax.max(BigDecimal.ZERO);
      BigDecimal net = orderTotal.subtract(tax).max(BigDecimal.ZERO);
      deferral =
          net.signum() == 0 || standalone.signum() == 0
              ? BigDecimal.ZERO
              : money(
                  net.multiply(standalone)
                      .divide(net.add(standalone), WORKING_SCALE, RoundingMode.HALF_UP));
    } else {
      deferral = money(standalone);
    }
    BigDecimal matched = points.min(pool.unmatched());
    BigDecimal releasedNow =
        matched.signum() == 0
            ? BigDecimal.ZERO
            : money(deferral.multiply(matched).divide(points, WORKING_SCALE, RoundingMode.HALF_UP));
    PointsPool next =
        new PointsPool(
            pool.outstanding().add(points).subtract(matched),
            pool.deferred().add(deferral).subtract(releasedNow),
            pool.unmatched().subtract(matched));
    if (deferral.signum() == 0) return new PointsOutcome(next, List.of());
    LedgerPosting p =
        LedgerPosting.of(
            src.tenantId(),
            src.date(),
            (fromSale ? "Loyalty points earned on sale " : "Loyalty points awarded, ")
                + Handle.of(src.ref()),
            Domain.SOURCE_LOYALTY_DEFERRAL,
            src.ref(),
            src.storeId());
    if (fromSale) {
      p.debit(Domain.CODE_SALES, Domain.NAME_SALES, deferral);
    } else {
      p.debit(Domain.CODE_LOYALTY_AWARDED, Domain.NAME_LOYALTY_AWARDED, deferral);
    }
    return new PointsOutcome(
        next,
        p.credit(
                Domain.CODE_DEFERRED_LOYALTY,
                Domain.NAME_DEFERRED_LOYALTY,
                deferral.subtract(releasedNow))
            .credit(Domain.CODE_LOYALTY_REDEEMED, Domain.NAME_LOYALTY_REDEEMED, releasedNow)
            .build());
  }

  /**
   * Points spent: Dr deferred income, Cr redeemed revenue with their share of what is deferred
   * against the points expected to be spent, and Cr breakage with whatever is left once no points
   * remain. Points beyond those outstanding are held as unmatched.
   */
  public static PointsOutcome redeemed(Source src, Settings s, PointsPool pool, BigDecimal points) {
    if (points == null || points.signum() <= 0) return new PointsOutcome(pool, List.of());
    BigDecimal matched = points.min(pool.outstanding());
    BigDecimal release = BigDecimal.ZERO;
    if (matched.signum() > 0 && pool.deferred().signum() > 0) {
      BigDecimal expected = pool.outstanding().multiply(s.pointsSpentShare());
      release =
          money(
                  pool.deferred()
                      .multiply(matched)
                      .divide(expected, WORKING_SCALE, RoundingMode.HALF_UP))
              .min(pool.deferred());
    }
    return close(
        src,
        new PointsPool(
            pool.outstanding().subtract(matched),
            pool.deferred().subtract(release),
            pool.unmatched().add(points.subtract(matched))),
        release,
        "Loyalty points redeemed, ");
  }

  /**
   * A manual correction: points added are points given away; points removed are points lapsed,
   * which release nothing until none remain, the expected lapses being priced out already.
   */
  public static PointsOutcome adjusted(Source src, Settings s, PointsPool pool, BigDecimal points) {
    if (points == null || points.signum() == 0) return new PointsOutcome(pool, List.of());
    if (points.signum() > 0) return earned(src, s, pool, points, null, null);
    BigDecimal matched = points.negate().min(pool.outstanding());
    return close(
        src,
        new PointsPool(pool.outstanding().subtract(matched), pool.deferred(), pool.unmatched()),
        BigDecimal.ZERO,
        "Loyalty points lapsed, ");
  }

  /** Posts a release, and sweeps what is left to breakage when no points remain outstanding. */
  /**
   * Points that died under the programme's expiry rule (13.x): they leave the pool as a lapse, and
   * once nothing is outstanding whatever deferred income is left is breakage — the point the
   * estimate was made for.
   */
  public static PointsOutcome expired(Source src, Settings s, PointsPool pool, BigDecimal points) {
    if (points == null || points.signum() <= 0) return new PointsOutcome(pool, List.of());
    BigDecimal matched = points.min(pool.outstanding());
    return close(
        src,
        new PointsPool(pool.outstanding().subtract(matched), pool.deferred(), pool.unmatched()),
        BigDecimal.ZERO,
        "Loyalty points expired, ");
  }

  private static PointsOutcome close(
      Source src, PointsPool after, BigDecimal release, String description) {
    BigDecimal breakage = after.outstanding().signum() == 0 ? after.deferred() : BigDecimal.ZERO;
    PointsPool next =
        new PointsPool(after.outstanding(), after.deferred().subtract(breakage), after.unmatched());
    BigDecimal total = release.add(breakage);
    if (total.signum() == 0) return new PointsOutcome(next, List.of());
    return new PointsOutcome(
        next,
        LedgerPosting.of(
                src.tenantId(),
                src.date(),
                description + Handle.of(src.ref()),
                Domain.SOURCE_LOYALTY_RELEASE,
                src.ref(),
                src.storeId())
            .debit(Domain.CODE_DEFERRED_LOYALTY, Domain.NAME_DEFERRED_LOYALTY, total)
            .credit(Domain.CODE_LOYALTY_REDEEMED, Domain.NAME_LOYALTY_REDEEMED, release)
            .credit(Domain.CODE_LOYALTY_BREAKAGE, Domain.NAME_LOYALTY_BREAKAGE, breakage)
            .build());
  }

  /**
   * A gift card sold or reloaded: Dr the account the money went to — or gift cards given away — and
   * Cr the gift card liability. Not revenue, and no VAT: that falls due when the card is spent.
   *
   * @param kind ISSUE or RELOAD
   * @param paidBy the tender taken, or {@link #PAID_BY_PROMOTIONAL}
   */
  public static List<NominalLedgerEntry> giftCardLoaded(
      Source src, String kind, String paidBy, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) return List.of();
    String how = paidBy == null ? "" : paidBy.trim().toUpperCase(Locale.ROOT);
    boolean given = PAID_BY_PROMOTIONAL.equals(how);
    LedgerPosting p =
        LedgerPosting.of(
            src.tenantId(),
            src.date(),
            ("RELOAD".equals(kind) ? "Gift card reloaded" : "Gift card issued")
                + (given
                    ? " free of charge"
                    : " paid by " + (how.isEmpty() ? "an unrecorded method" : how)),
            Domain.SOURCE_GIFT_CARD_LOAD,
            src.ref(),
            src.storeId());
    if (given) {
      p.debit(Domain.CODE_GIFT_CARDS_GIVEN, Domain.NAME_GIFT_CARDS_GIVEN, amount);
    } else {
      SalesPosting.Control control = SalesPosting.controlFor(how);
      p.debit(control.code(), control.name(), amount);
    }
    return p.credit(Domain.CODE_GIFT_CARD_LIABILITY, Domain.NAME_GIFT_CARD_LIABILITY, amount)
        .build();
  }

  /** The pool after a load. */
  public static GiftCardPool loaded(GiftCardPool pool, BigDecimal amount) {
    return new GiftCardPool(pool.loaded().add(amount), pool.redeemed(), pool.breakage());
  }

  /**
   * A gift card spent as tender. The tender's own posting takes the liability down (17.7); this
   * recognises the breakage that goes with it: {@code amount × b / (1 − b)}, never more in all than
   * the estimate of what was loaded, nor than the liability left. When spending overtakes what the
   * estimate left owing, the breakage recognised was too much and is reversed as far as it goes.
   * Without estimates, nothing is recognised.
   *
   * @param s the estimates, or null when none are set
   */
  public static GiftCardOutcome giftCardRedeemed(
      Source src, Settings s, GiftCardPool pool, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) return new GiftCardOutcome(pool, List.of());
    GiftCardPool spent =
        new GiftCardPool(pool.loaded(), pool.redeemed().add(amount), pool.breakage());
    BigDecimal overdrawn = spent.liability().negate();
    if (overdrawn.signum() > 0) {
      BigDecimal reversal = overdrawn.min(spent.breakage());
      return new GiftCardOutcome(
          new GiftCardPool(spent.loaded(), spent.redeemed(), spent.breakage().subtract(reversal)),
          breakage(src, reversal, true));
    }
    if (s == null || s.giftCardBreakagePct().signum() == 0) {
      return new GiftCardOutcome(spent, List.of());
    }
    BigDecimal b = s.giftCardBreakageShare();
    BigDecimal due =
        money(
            amount
                .multiply(b)
                .divide(BigDecimal.ONE.subtract(b), WORKING_SCALE, RoundingMode.HALF_UP));
    BigDecimal ceiling = money(pool.loaded().multiply(b)).subtract(pool.breakage());
    BigDecimal recognised = due.min(ceiling).min(spent.liability()).max(BigDecimal.ZERO);
    return new GiftCardOutcome(
        new GiftCardPool(spent.loaded(), spent.redeemed(), spent.breakage().add(recognised)),
        breakage(src, recognised, false));
  }

  /** Breakage recognised (Dr liability, Cr breakage) or reversed (the other way round). */
  private static List<NominalLedgerEntry> breakage(
      Source src, BigDecimal amount, boolean reversed) {
    if (amount.signum() == 0) return List.of();
    LedgerPosting p =
        LedgerPosting.of(
            src.tenantId(),
            src.date(),
            (reversed ? "Gift card breakage reversed on sale " : "Gift card breakage on sale ")
                + Handle.of(src.ref()),
            Domain.SOURCE_GIFT_CARD_BREAKAGE,
            src.ref(),
            src.storeId());
    if (reversed) {
      p.debit(Domain.CODE_GIFT_CARD_BREAKAGE, Domain.NAME_GIFT_CARD_BREAKAGE, amount)
          .credit(Domain.CODE_GIFT_CARD_LIABILITY, Domain.NAME_GIFT_CARD_LIABILITY, amount);
    } else {
      p.debit(Domain.CODE_GIFT_CARD_LIABILITY, Domain.NAME_GIFT_CARD_LIABILITY, amount)
          .credit(Domain.CODE_GIFT_CARD_BREAKAGE, Domain.NAME_GIFT_CARD_BREAKAGE, amount);
    }
    return p.build();
  }

  private static BigDecimal money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP);
  }
}
