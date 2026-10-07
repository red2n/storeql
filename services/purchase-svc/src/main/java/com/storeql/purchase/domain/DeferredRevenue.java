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

  /**
   * A card loaded by a return's refund (return controls). The refund's own posting already credits
   * the gift-card liability, so the load is counted in the pool but posts nothing of its own.
   */
  public static final String PAID_BY_RETURN = "RETURN";

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int WORKING_SCALE = 10;

  private DeferredRevenue() {}

  /**
   * The tenant accountant's estimates. Percentages are 0 to {@link #MAX_BREAKAGE_PCT}.
   *
   * @param currency the business's currency the estimates were set in (ISO 4217): what is deferred
   *     and recognised is rounded to its own minor units — whole yen, three decimals of a dinar
   */
  public record Settings(
      BigDecimal pointValue,
      BigDecimal pointsBreakagePct,
      BigDecimal giftCardBreakagePct,
      String currency) {

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
        // Four places is the precision of a rate, not of money: a point is worth a fraction of
        // the currency's minor unit (0.0125 of a pound, 0.5 yen), and what it multiplies into is
        // rounded to the currency's own minor units where it is posted.
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
        // A percentage, not money: two places (12.25%) whatever the currency.
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
                      .divide(net.add(standalone), WORKING_SCALE, RoundingMode.HALF_UP),
                  s);
    } else {
      deferral = money(standalone, s);
    }
    BigDecimal matched = points.min(pool.unmatched());
    BigDecimal releasedNow =
        matched.signum() == 0
            ? BigDecimal.ZERO
            : money(
                deferral.multiply(matched).divide(points, WORKING_SCALE, RoundingMode.HALF_UP), s);
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
                      .divide(expected, WORKING_SCALE, RoundingMode.HALF_UP),
                  s)
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

  /**
   * Points taken back because the sale that earned them was returned or voided (return controls):
   * the deferral that went with them leaves deferred income and goes back to sales — the inverse of
   * earning, valued at the pool's average as redemption values it. The refund reversed the whole
   * sale out of sales, so without this sales would be short by what earning deferred. Points beyond
   * those outstanding were already spent: the customer owes them, and they release nothing.
   */
  public static PointsOutcome reversed(Source src, Settings s, PointsPool pool, BigDecimal points) {
    if (points == null || points.signum() <= 0) return new PointsOutcome(pool, List.of());
    BigDecimal matched = points.min(pool.outstanding());
    if (matched.signum() <= 0) return new PointsOutcome(pool, List.of());
    BigDecimal back =
        pool.deferred().signum() <= 0
            ? BigDecimal.ZERO
            : money(
                    pool.deferred()
                        .multiply(matched)
                        .divide(pool.outstanding(), WORKING_SCALE, RoundingMode.HALF_UP),
                    s)
                .min(pool.deferred());
    PointsPool next =
        new PointsPool(
            pool.outstanding().subtract(matched), pool.deferred().subtract(back), pool.unmatched());
    if (back.signum() == 0) return new PointsOutcome(next, List.of());
    return new PointsOutcome(
        next,
        LedgerPosting.of(
                src.tenantId(),
                src.date(),
                "Loyalty points taken back with a returned sale " + Handle.of(src.ref()),
                Domain.SOURCE_LOYALTY_DEFERRAL,
                src.ref(),
                src.storeId())
            .debit(Domain.CODE_DEFERRED_LOYALTY, Domain.NAME_DEFERRED_LOYALTY, back)
            .credit(Domain.CODE_SALES, Domain.NAME_SALES, back)
            .build());
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

  /** The {@code source} a sale's card carries: value sold on the order's own receipt. */
  public static final String SOURCE_SALE = "SALE";

  /**
   * A gift card sold or reloaded, without saying where the value came from: as the events before
   * order-svc named a source were read. See the long form.
   */
  public static List<NominalLedgerEntry> giftCardLoaded(
      Source src, String kind, String paidBy, BigDecimal amount) {
    return giftCardLoaded(src, kind, paidBy, amount, null, null, null);
  }

  /**
   * A gift card sold or reloaded, three ways:
   *
   * <ul>
   *   <li>sold in a sale ({@code source} SALE naming the order): the tender was taken by the sale's
   *       own payments, which debit the money account and credit sales clearing for everything
   *       taken, card value included. The confirmed sale credits sales only for the goods, so the
   *       card's value is what is left on clearing: Dr sales clearing / Cr the liability, on the
   *       order, and the order nets to zero. It is never debited to a tender again, and a split
   *       tender has no single one to debit;
   *   <li>given by hand ({@code paidBy} PROMOTIONAL): value given away, not sold — Dr gift cards
   *       given away (expense) / Cr the liability, with the reason and note in the description;
   *   <li>put on by a return's refund ({@code paidBy} RETURN): nothing, the refund owes the card.
   * </ul>
   *
   * A load with no source and a tender named is read as before: Dr that tender's account.
   *
   * @param kind ISSUE or RELOAD
   * @param paidBy the tender taken, or {@link #PAID_BY_PROMOTIONAL}
   * @param source SALE, RETURN, or a hand reason (GOODWILL, PROMOTION, COMPENSATION, MIGRATION);
   *     null when the event does not say
   * @param orderId the sale a SALE card was sold in
   * @param note what the manager wrote for a hand load
   */
  public static List<NominalLedgerEntry> giftCardLoaded(
      Source src,
      String kind,
      String paidBy,
      BigDecimal amount,
      String source,
      UUID orderId,
      String note) {
    if (amount == null || amount.signum() <= 0) return List.of();
    String how = paidBy == null ? "" : paidBy.trim().toUpperCase(Locale.ROOT);
    if (PAID_BY_RETURN.equals(how)) return List.of();
    String why = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
    boolean given = PAID_BY_PROMOTIONAL.equals(how);
    boolean inSale = !given && SOURCE_SALE.equals(why) && orderId != null;
    String verb = "RELOAD".equals(kind) ? "Gift card reloaded" : "Gift card issued";
    String description;
    if (given) {
      description =
          verb
              + " free of charge"
              + (why.isEmpty() ? "" : " (" + why + ")")
              + (note == null || note.isBlank() ? "" : ": " + note.strip());
    } else if (inSale) {
      description = verb + " in sale " + Handle.of(orderId);
    } else {
      description = verb + " paid by " + (how.isEmpty() ? "an unrecorded method" : how);
    }
    LedgerPosting p =
        LedgerPosting.of(
            src.tenantId(),
            src.date(),
            description,
            Domain.SOURCE_GIFT_CARD_LOAD,
            inSale ? orderId : src.ref(),
            src.storeId());
    if (given) {
      p.debit(Domain.CODE_GIFT_CARDS_GIVEN, Domain.NAME_GIFT_CARDS_GIVEN, amount);
    } else if (inSale) {
      p.debit(Domain.CODE_SALES_CLEARING, Domain.NAME_SALES_CLEARING, amount);
    } else {
      SalesPosting.Control control = SalesPosting.controlFor(how);
      p.debit(control.code(), control.name(), amount);
    }
    return p.credit(Domain.CODE_GIFT_CARD_LIABILITY, Domain.NAME_GIFT_CARD_LIABILITY, amount)
        .build();
  }

  /**
   * The value a sale loaded on a gift card taken back because the sale was voided or cancelled: the
   * opposite of the sale-loaded posting, Dr the liability / Cr sales clearing, on the order. The
   * refund of the sale is booked from payment-svc's own event, so with it the order nets to zero.
   */
  public static List<NominalLedgerEntry> giftCardLoadReversed(
      Source src, UUID orderId, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) return List.of();
    return LedgerPosting.of(
            src.tenantId(),
            src.date(),
            "Gift card load reversed with sale " + Handle.of(orderId),
            Domain.SOURCE_GIFT_CARD_LOAD,
            orderId,
            src.storeId())
        .debit(Domain.CODE_GIFT_CARD_LIABILITY, Domain.NAME_GIFT_CARD_LIABILITY, amount)
        .credit(Domain.CODE_SALES_CLEARING, Domain.NAME_SALES_CLEARING, amount)
        .build();
  }

  /** The pool after a load is taken back; never below nothing. */
  public static GiftCardPool loadReversed(GiftCardPool pool, BigDecimal amount) {
    BigDecimal left = pool.loaded().subtract(amount);
    return new GiftCardPool(
        left.signum() < 0 ? BigDecimal.ZERO : left, pool.redeemed(), pool.breakage());
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
                .divide(BigDecimal.ONE.subtract(b), WORKING_SCALE, RoundingMode.HALF_UP),
            s);
    BigDecimal ceiling = money(pool.loaded().multiply(b), s).subtract(pool.breakage());
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

  /** An amount in the business's currency, at that currency's own minor units. */
  private static BigDecimal money(BigDecimal v, Settings s) {
    return Money.round(v, s.currency());
  }
}
