package com.storeql.pricing.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.BasketLine;
import com.storeql.pricing.domain.Domain.Promotion;
import com.storeql.pricing.domain.Domain.PromotionOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the promotion engine.
 *
 * <p>These exist in this form because the engine is a pure function. The implementation it replaces
 * was three branches inside a price-resolution method behind a database call, and consequently the
 * only assertions about promotion behaviour in the whole repository were two lines of an
 * integration test. Every case below is one a shop actually hits, and none of them needed Postgres
 * to ask about.
 */
class PromotionEngineTest {

  private final PromotionEngine engine = new PromotionEngine();

  private static final UUID SHIRT = Ids.parse("01a090ae-611e-700b-bde4-50df0324c37c");
  private static final UUID MUG = Ids.parse("01a090ae-611e-700f-b645-a14095230b77");

  // ── line-level ─────────────────────────────────────────────────────────────

  @Test
  void percentTakesItsShareOfTheLine() {
    var out = run(List.of(line(SHIRT, 2, "10.00")), List.of(percent("10% off", 10)));
    assertThat(out.totalDiscount(), is(bd("2.00"))); // 10% of 20.00
  }

  /**
   * FLAT is per unit, which is what the engine it replaces meant by subtracting from a unit price.
   */
  @Test
  void flatIsPerUnitNotPerLine() {
    var out = run(List.of(line(SHIRT, 3, "10.00")), List.of(flat("£2 off", "2.00")));
    assertThat(out.totalDiscount(), is(bd("6.00")));
  }

  /**
   * The clamp that matters. A flat discount larger than the item cannot make the line negative, and
   * — because the clamp is per line rather than over the basket — cannot reach across and eat the
   * value of another line either.
   */
  @Test
  void nothingCanDriveALineBelowZeroOrSpillIntoAnother() {
    var out =
        run(
            List.of(line(SHIRT, 1, "3.00"), line(MUG, 1, "50.00")),
            List.of(scoped(flat("£10 off shirts", "10.00"), SHIRT)));
    assertThat(out.totalDiscount(), is(bd("3.00")));
    assertThat(out.lineDiscounts(), hasSize(1));
    assertThat(out.lineDiscounts().get(0).variantId(), is(SHIRT));
  }

  /** A variant-scoped promotion leaves everything else alone. */
  @Test
  void scopeIsHonoured() {
    var out =
        run(
            List.of(line(SHIRT, 1, "20.00"), line(MUG, 1, "10.00")),
            List.of(scoped(percent("shirts only", 50), SHIRT)));
    assertThat(out.totalDiscount(), is(bd("10.00")));
  }

  // ── stacking and priority ──────────────────────────────────────────────────

  /**
   * The defect this rebuild is named for. The old engine sorted candidates {@code ORDER BY value
   * DESC LIMIT 1}, comparing a PERCENT's value (15, meaning 15%) with a FLAT's (20, meaning £20) as
   * though they shared a unit — so on a £500 basket the £20 promotion beat the 15% one worth £75.
   * Ordering is now explicit and both apply.
   */
  @Test
  void twoPromotionsBothApplyInPriorityOrder() {
    Promotion fifteenPct = withPriority(percent("15% off", 15), 10);
    Promotion twentyOff = withPriority(flat("£20 off", "20.00"), 20);

    var out = run(List.of(line(SHIRT, 1, "500.00")), List.of(twentyOff, fifteenPct));

    // 15% of 500 = 75, plus 20 flat = 95. Neither silently loses to the other.
    assertThat(out.totalDiscount(), is(bd("95.00")));
    // And they ran in priority order, not in the order the list happened to arrive in.
    assertThat(out.appliedPromotionIds(), contains(fifteenPct.id(), twentyOff.id()));
  }

  /** Line-level promotions compound against the original price, so two 10%s take 20%, not 19%. */
  @Test
  void lineLevelPercentagesCompoundOnTheOriginalPrice() {
    var out =
        run(
            List.of(line(SHIRT, 1, "100.00")),
            List.of(
                withPriority(percent("ten", 10), 1), withPriority(percent("ten again", 10), 2)));
    assertThat(out.totalDiscount(), is(bd("20.00")));
  }

  /** "Cannot be combined with any other offer", expressed. */
  @Test
  void anExclusivePromotionSuppressesEverythingAfterIt() {
    Promotion first = withPriority(percent("half price", 50), 1);
    Promotion exclusive = exclusive(withPriority(percent("30% off, on its own", 30), 2));
    Promotion never = withPriority(percent("should not run", 25), 3);

    var out = run(List.of(line(SHIRT, 1, "100.00")), List.of(first, exclusive, never));

    // 50 then 30 — both ran; the exclusive one stopped the list after itself, not before.
    assertThat(out.appliedPromotionIds(), contains(first.id(), exclusive.id()));
    assertThat(out.totalDiscount(), is(bd("80.00")));
  }

  /** An exclusive promotion that does not apply must not suppress the ones behind it. */
  @Test
  void anExclusivePromotionThatDoesNotApplySuppressesNothing() {
    Promotion exclusiveElsewhere =
        exclusive(withPriority(scoped(percent("mugs only", 50), MUG), 1));
    Promotion shirts = withPriority(percent("shirts 10%", 10), 2);

    var out = run(List.of(line(SHIRT, 1, "100.00")), List.of(exclusiveElsewhere, shirts));

    assertThat(out.appliedPromotionIds(), contains(shirts.id()));
    assertThat(out.totalDiscount(), is(bd("10.00")));
  }

  // ── basket-level ───────────────────────────────────────────────────────────

  /**
   * {@code min_order_amount} has existed since V1, been returned in every API response, and never
   * been read. "£5 off orders over £100" took £5 off a £3 basket.
   */
  @Test
  void aThresholdIsActuallyTested() {
    Promotion fiver = threshold("£5 off over £100", "5.00", "100.00");

    assertThat(run(List.of(line(SHIRT, 1, "50.00")), List.of(fiver)).totalDiscount(), is(ZERO));
    assertThat(
        run(List.of(line(SHIRT, 1, "150.00")), List.of(fiver)).totalDiscount(), is(bd("5.00")));
  }

  /**
   * The threshold is measured against what is left after the line-level discounts, not against the
   * ticket price. A basket that only clears £100 before a half-price offer has not spent £100.
   */
  @Test
  void aThresholdSeesTheBasketAfterLineDiscountsNotBefore() {
    var out =
        run(
            List.of(line(SHIRT, 1, "120.00")),
            List.of(percent("half price", 50), threshold("£5 off over £100", "5.00", "100.00")));
    // 120 → 60 after the line promotion, which no longer clears 100.
    assertThat(out.totalDiscount(), is(bd("60.00")));
    assertThat(out.basketDiscounts(), hasSize(0));
  }

  @Test
  void basketPercentAppliesToWhatRemains() {
    var out =
        run(
            List.of(line(SHIRT, 1, "100.00"), line(MUG, 1, "100.00")),
            List.of(
                withPriority(scoped(percent("shirts 50%", 50), SHIRT), 1),
                withPriority(basketPercent("10% off everything", 10), 2)));
    // 200 − 50 = 150, then 10% of 150 = 15.
    assertThat(out.totalDiscount(), is(bd("65.00")));
    assertThat(out.basketDiscounts().get(0).amount(), is(bd("15.00")));
    // A basket discount belongs to no line, and says so.
    assertThat(out.basketDiscounts().get(0).variantId(), is((UUID) null));
  }

  @Test
  void aBasketDiscountCannotExceedTheBasket() {
    var out = run(List.of(line(SHIRT, 1, "5.00")), List.of(basketFlat("£50 off", "50.00")));
    assertThat(out.totalDiscount(), is(bd("5.00")));
  }

  // ── BOGO ───────────────────────────────────────────────────────────────────

  @Test
  void bogoDiscountsOneWholeGroupAtATime() {
    // Buy 2 get 1 free, five shirts at £10: one completed group of three, so one free shirt.
    var out = run(List.of(line(SHIRT, 5, "10.00")), List.of(bogo("B2G1", 2, 1, 100)));
    assertThat(out.totalDiscount(), is(bd("10.00")));
  }

  @Test
  void bogoGivesNothingAwayUntilTheGroupIsComplete() {
    var out = run(List.of(line(SHIRT, 2, "10.00")), List.of(bogo("B2G1", 2, 1, 100)));
    assertThat(out.totalDiscount(), is(ZERO));
  }

  /**
   * The case a per-line implementation gets wrong, and the common one: three different products on
   * one buy-2-get-1. The group is counted across every line in scope, and the cheapest unit is the
   * one given away — the convention every retailer uses, and the one that stops a basket being
   * gamed by adding an expensive item.
   */
  @Test
  void bogoCountsAcrossLinesAndDiscountsTheCheapest() {
    var out =
        run(
            List.of(line(SHIRT, 2, "30.00"), line(MUG, 1, "6.00")),
            List.of(bogo("B2G1 across the range", 2, 1, 100)));
    assertThat(out.totalDiscount(), is(bd("6.00")));
    assertThat(out.lineDiscounts().get(0).variantId(), is(MUG));
  }

  @Test
  void bogoCanBeAPartialDiscountRatherThanFree() {
    // Buy 1 get 1 half price, two mugs at £10.
    var out = run(List.of(line(MUG, 2, "10.00")), List.of(bogo("B1G1 half", 1, 1, 50)));
    assertThat(out.totalDiscount(), is(bd("5.00")));
  }

  // ── coupons ────────────────────────────────────────────────────────────────

  @Test
  void aCouponPromotionDoesNothingUntilItIsPresented() {
    Promotion save = coupon(percent("10% off", 10), "SAVE10");

    assertThat(run(List.of(line(SHIRT, 1, "100.00")), List.of(save)).totalDiscount(), is(ZERO));

    var withCode =
        engine.apply(
            List.of(line(SHIRT, 1, "100.00")),
            List.of(save),
            Map.of(),
            List.of("save10"),
            Map.of(),
            2);
    // Matched case-insensitively: a customer typing lowercase gets their discount.
    assertThat(withCode.totalDiscount(), is(bd("10.00")));
    assertThat(withCode.rejectedCoupons().isEmpty(), is(true));
  }

  /**
   * A code that does nothing has to say why. "Nothing happened" is the answer that generates the
   * support call, and the three reasons below are genuinely different problems.
   */
  @Test
  void everyPresentedCodeGetsAnAnswer() {
    Promotion save = coupon(scoped(percent("mugs only", 10), MUG), "MUGS");

    var out =
        engine.apply(
            List.of(line(SHIRT, 1, "100.00")),
            List.of(save),
            Map.of(save.id(), Set.of(MUG)),
            List.of("MUGS", "TYPO"),
            Map.of(),
            2);

    // The code exists and is live, but nothing in the basket qualifies.
    assertThat(out.rejectedCoupons(), hasEntry("MUGS", "NOT_APPLICABLE"));
    // This one is not a code at all.
    assertThat(out.rejectedCoupons(), hasEntry("TYPO", "NO_SUCH_COUPON"));
  }

  @Test
  void anExhaustedCouponIsRejectedWithItsReasonRatherThanIgnored() {
    Promotion save = coupon(percent("10% off", 10), "SAVE10");

    var out =
        engine.apply(
            List.of(line(SHIRT, 1, "100.00")),
            List.of(save),
            Map.of(),
            List.of("SAVE10"),
            Map.of(save.id(), "COUPON_EXHAUSTED"),
            2);

    assertThat(out.totalDiscount(), is(ZERO));
    assertThat(out.rejectedCoupons(), hasEntry("SAVE10", "COUPON_EXHAUSTED"));
  }

  /**
   * A promotion nobody asked for and cannot have is skipped silently — only typed codes get noise.
   */
  @Test
  void anUnpresentedCouponIsNotReportedAsRejected() {
    Promotion save = coupon(percent("10% off", 10), "SAVE10");
    var out = run(List.of(line(SHIRT, 1, "100.00")), List.of(save));
    assertThat(out.rejectedCoupons(), not(hasKey("SAVE10")));
    assertThat(out.rejectedCoupons().isEmpty(), is(true));
  }

  // ── determinism ────────────────────────────────────────────────────────────

  /**
   * The same basket must price the same way every time. Two promotions at equal priority break the
   * tie on id rather than on whatever order the database returned them in — which is precisely the
   * kind of accident the old engine's behaviour rested on.
   */
  @Test
  void equalPrioritiesBreakTheTieDeterministically() {
    Promotion a = withPriority(percent("a", 10), 5);
    Promotion b = withPriority(percent("b", 20), 5);

    var forwards = run(List.of(line(SHIRT, 1, "100.00")), List.of(a, b));
    var backwards = run(List.of(line(SHIRT, 1, "100.00")), List.of(b, a));

    assertThat(forwards.appliedPromotionIds(), is(backwards.appliedPromotionIds()));
    assertThat(forwards.totalDiscount(), is(backwards.totalDiscount()));
  }

  @Test
  void anEmptyCandidateListIsNotAnError() {
    var out = run(List.of(line(SHIRT, 1, "10.00")), List.of());
    assertThat(out.totalDiscount(), is(ZERO));
    assertThat(out.appliedPromotionIds(), hasSize(0));
  }

  // ── mix and match (03.8) ───────────────────────────────────────────────────

  @Test
  void anyThreeForTenTakesTheDifferenceOffTheDearestThree() {
    // 4.00, 4.00, 4.00 and 2.00 in scope: the bundle is the three at 4.00 (12.00 → 10.00), the
    // 2.00 unit is charged in full. Putting the 2.00 in the bundle would save only 0.00.
    var promo = mixMatch("Any 3 for £10", 3, "10.00");
    SCOPES.put(promo.id(), Set.of(SHIRT, MUG));
    var out = run(List.of(line(SHIRT, 3, "4.00"), line(MUG, 1, "2.00")), List.of(promo));
    assertThat(out.totalDiscount(), is(bd("2.00")));
    assertThat(out.lineDiscounts(), hasSize(1));
    assertThat(out.lineDiscounts().get(0).variantId(), is(SHIRT));
  }

  @Test
  void wholeBundlesOnlyAndTheRestInFull() {
    // Five units on a bundle of three: one bundle, two left at full price.
    var promo = mixMatch("Any 3 for £10", 3, "10.00");
    var out = run(List.of(line(SHIRT, 5, "4.00")), List.of(promo));
    assertThat(out.totalDiscount(), is(bd("2.00")));
    // Two units: no bundle, no discount.
    assertThat(run(List.of(line(SHIRT, 2, "4.00")), List.of(promo)).totalDiscount(), is(ZERO));
    // Six: two bundles.
    assertThat(
        run(List.of(line(SHIRT, 6, "4.00")), List.of(promo)).totalDiscount(), is(bd("4.00")));
  }

  @Test
  void aBundleSplitsItsSavingAcrossTheLinesByPrice() {
    // 6.00 + 3.00 + 3.00 = 12.00 for 9.00: a saving of 3.00, split 1.50 / 1.50 by price, and
    // the two 3.00 units are on one line.
    var promo = mixMatch("Any 3 for £9", 3, "9.00");
    var out = run(List.of(line(SHIRT, 1, "6.00"), line(MUG, 2, "3.00")), List.of(promo));
    assertThat(out.totalDiscount(), is(bd("3.00")));
    BigDecimal shirt = discountOn(out, SHIRT);
    BigDecimal mug = discountOn(out, MUG);
    assertThat(shirt, is(bd("1.50")));
    assertThat(mug, is(bd("1.50")));
  }

  @Test
  void aBundlePricedAboveItsUnitsSavesNothing() {
    var promo = mixMatch("Any 2 for £20", 2, "20.00");
    var out = run(List.of(line(SHIRT, 2, "4.00")), List.of(promo));
    assertThat(out.totalDiscount(), is(ZERO));
    assertThat(out.appliedPromotionIds(), hasSize(0));
  }

  @Test
  void mixMatchCountsAcrossTheScopeOnly() {
    var promo = mixMatch("Any 2 for £5", 2, "5.00");
    SCOPES.put(promo.id(), Set.of(MUG));
    // Three shirts out of scope, one mug in scope: no bundle.
    assertThat(
        run(List.of(line(SHIRT, 3, "4.00"), line(MUG, 1, "4.00")), List.of(promo)).totalDiscount(),
        is(ZERO));
  }

  @Test
  void anEmptyScopeDiscountsNothingNotEverything() {
    // A category scope that resolved to no variant used to mean "everything".
    var promo = percent("10% off nothing", 10);
    SCOPES.put(promo.id(), Set.of());
    assertThat(run(List.of(line(SHIRT, 2, "10.00")), List.of(promo)).totalDiscount(), is(ZERO));
    var mm = mixMatch("Any 2 for £5", 2, "5.00");
    SCOPES.put(mm.id(), Set.of());
    assertThat(run(List.of(line(SHIRT, 2, "10.00")), List.of(mm)).totalDiscount(), is(ZERO));
  }

  @Test
  void aFractionalQuantityBundlesWholeUnitsOnly() {
    var promo = mixMatch("Any 2 for £5", 2, "5.00");
    // 2.5 kg on a "any 2 for" deal: two units bundle, the half is charged in full.
    assertThat(
        run(List.of(line(SHIRT, 1, "4.00"), line(MUG, 1, "4.00")), List.of(promo)).totalDiscount(),
        is(bd("3.00")));
    var out =
        engine.apply(
            List.of(new BasketLine(SHIRT, bd("2.5"), bd("4.00"))),
            List.of(promo),
            Map.of(),
            List.of(),
            Map.of(),
            2);
    assertThat(out.totalDiscount(), is(bd("3.00")));
  }

  // ── the currency's own minor units ────────────────────────────────────────

  /** A percentage off a yen line is whole yen: 10% of ¥999 is ¥100, never ¥99.90. */
  @Test
  void aYenPercentageIsWholeYen() {
    var out = run(List.of(line(SHIRT, 3, "333")), List.of(percent("10% off", 10)), 0);
    assertThat(out.totalDiscount(), is(bd("100")));
  }

  /** A percentage off a dinar line keeps its fils: 10% of KWD 1.235 is 0.124, never 0.12. */
  @Test
  void aDinarPercentageKeepsItsThirdDecimal() {
    var out = run(List.of(line(SHIRT, 1, "1.235")), List.of(percent("10% off", 10)), 3);
    assertThat(out.totalDiscount(), is(bd("0.124")));
  }

  /** A basket percentage and a half-price free unit are rounded to the yen too. */
  @Test
  void aYenBasketPercentageAndBogoAreWholeYen() {
    var basket = run(List.of(line(SHIRT, 1, "999")), List.of(basketPercent("15% off", 15)), 0);
    assertThat(basket.totalDiscount(), is(bd("150")));
    var bogo = run(List.of(line(SHIRT, 2, "125")), List.of(bogo("2nd half price", 1, 1, 50)), 0);
    assertThat(bogo.totalDiscount(), is(bd("63")));
  }

  /** A bundle price in dinars saves to the fils: two at 3.000 for 5.005 save 0.995, not 1.00. */
  @Test
  void aDinarBundleSavesToTheFils() {
    var out =
        run(
            List.of(line(SHIRT, 1, "3.000"), line(MUG, 1, "3.000")),
            List.of(mixMatch("Any 2 for 5.005", 2, "5.005")),
            3);
    assertThat(out.totalDiscount(), is(bd("0.995")));
  }

  private static BigDecimal discountOn(PromotionOutcome out, UUID variant) {
    return out.lineDiscounts().stream()
        .filter(d -> variant.equals(d.variantId()))
        .map(d -> d.amount())
        .reduce(ZERO, BigDecimal::add);
  }

  private static Promotion mixMatch(String name, int bundle, String price) {
    return withQuantities(
        base(name, Promotion.TYPE_MIX_MATCH, bd(price)), new BigDecimal(bundle), null, null);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static final BigDecimal ZERO = BigDecimal.ZERO;

  private PromotionOutcome run(List<BasketLine> lines, List<Promotion> promos) {
    return run(lines, promos, 2);
  }

  /** The same, for a currency with {@code scale} minor units (0 for yen, 3 for dinars). */
  private PromotionOutcome run(List<BasketLine> lines, List<Promotion> promos, int scale) {
    Map<UUID, Set<UUID>> scopes = new java.util.LinkedHashMap<>();
    for (Promotion p : promos) {
      Set<UUID> s = SCOPES.get(p.id());
      if (s != null) scopes.put(p.id(), s);
    }
    return engine.apply(lines, promos, scopes, List.of(), Map.of(), scale);
  }

  /** Scopes recorded by {@link #scoped}, so a test reads as one expression. */
  private static final Map<UUID, Set<UUID>> SCOPES = new java.util.concurrent.ConcurrentHashMap<>();

  private static BasketLine line(UUID variantId, int qty, String unitPrice) {
    return new BasketLine(variantId, new BigDecimal(qty), bd(unitPrice));
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static Promotion base(String name, String type, BigDecimal value) {
    return new Promotion(
        Ids.newId(),
        Ids.newId(),
        null,
        name,
        type,
        value,
        null,
        "ALL",
        true,
        Instant.now().minusSeconds(60),
        null,
        Instant.now(),
        100,
        false,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static Promotion percent(String name, int pct) {
    return base(name, Promotion.TYPE_PERCENT, new BigDecimal(pct));
  }

  private static Promotion flat(String name, String amount) {
    return base(name, Promotion.TYPE_FLAT, bd(amount));
  }

  private static Promotion basketPercent(String name, int pct) {
    return base(name, Promotion.TYPE_BASKET_PERCENT, new BigDecimal(pct));
  }

  private static Promotion basketFlat(String name, String amount) {
    return base(name, Promotion.TYPE_BASKET_FLAT, bd(amount));
  }

  private static Promotion threshold(String name, String amount, String min) {
    Promotion p = base(name, Promotion.TYPE_SPEND_THRESHOLD, bd(amount));
    return copy(p, p.priority(), p.exclusive(), p.couponCode(), bd(min));
  }

  private static Promotion bogo(String name, int buy, int get, int pct) {
    return withQuantities(
        base(name, Promotion.TYPE_BOGO, BigDecimal.ONE),
        new BigDecimal(buy),
        new BigDecimal(get),
        new BigDecimal(pct));
  }

  /** The same promotion with the BOGO / mix-and-match quantities set. */
  private static Promotion withQuantities(
      Promotion p, BigDecimal buyQty, BigDecimal getQty, BigDecimal getDiscountPct) {
    return new Promotion(
        p.id(),
        p.tenantId(),
        p.storeId(),
        p.name(),
        p.type(),
        p.value(),
        p.minOrderAmount(),
        p.channel(),
        p.active(),
        p.startsAt(),
        p.endsAt(),
        p.createdAt(),
        p.priority(),
        p.exclusive(),
        p.couponCode(),
        p.maxRedemptions(),
        p.maxPerCustomer(),
        buyQty,
        getQty,
        getDiscountPct);
  }

  private static Promotion withPriority(Promotion p, int priority) {
    return copy(p, priority, p.exclusive(), p.couponCode(), p.minOrderAmount());
  }

  private static Promotion exclusive(Promotion p) {
    return copy(p, p.priority(), true, p.couponCode(), p.minOrderAmount());
  }

  private static Promotion coupon(Promotion p, String code) {
    return copy(p, p.priority(), p.exclusive(), code, p.minOrderAmount());
  }

  /** Records a variant scope for {@link #run} and returns the promotion unchanged. */
  private static Promotion scoped(Promotion p, UUID variantId) {
    SCOPES.put(p.id(), Set.of(variantId));
    return p;
  }

  private static Promotion copy(
      Promotion p, int priority, boolean exclusive, String coupon, BigDecimal minOrder) {
    return new Promotion(
        p.id(),
        p.tenantId(),
        p.storeId(),
        p.name(),
        p.type(),
        p.value(),
        minOrder,
        p.channel(),
        p.active(),
        p.startsAt(),
        p.endsAt(),
        p.createdAt(),
        priority,
        exclusive,
        coupon,
        p.maxRedemptions(),
        p.maxPerCustomer(),
        p.buyQty(),
        p.getQty(),
        p.getDiscountPct());
  }
}
