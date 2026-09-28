package com.storeql.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure domain records — no HTTP, no persistence annotations. */
public final class Domain {

  private Domain() {}

  /**
   * HMRC VAT rate. code: T1=the business's standard rate, T5=reduced, T0=zero, TX=exempt — each set
   * by the business, never assumed (SJ-D56). Per HMRC VAT Notice 700.
   */
  public record VatRate(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      BigDecimal rate,
      boolean exempt,
      String description,
      Instant effectiveFrom,
      Instant effectiveTo,
      Instant createdAt) {

    public static final String T1 = "T1";
    public static final String T5 = "T5";
    public static final String T0 = "T0";
    public static final String TX = "TX";
  }

  /** Maps a product variant to its HMRC VAT code (T1/T5/T0/TX). */
  public record ProductVatCategory(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String vatCode,
      Instant effectiveFrom,
      Instant effectiveTo,
      Instant createdAt) {}

  /**
   * B2B customer VAT registration status. VAT number format: GB + 9 digits (e.g. GB123456789).
   * reverseChargeEligible applies to cross-border B2B supplies.
   */
  public record CustomerVatStatus(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String vatNumber,
      boolean vatRegistered,
      boolean reverseChargeEligible,
      String countryCode,
      String legalName,
      String einvoiceScheme,
      String einvoiceId,
      Instant createdAt,
      Instant updatedAt) {}

  /** Named price list (Standard, Online, POS, VIP, etc.) with an effective date range in GBP. */
  public record PriceList(
      UUID id,
      UUID tenantId,
      String name,
      String channel,
      String currency,
      Instant effectiveFrom,
      Instant effectiveTo,
      boolean active,
      Instant createdAt,
      UUID zoneId) {

    /** A tenant-wide list, bound to no price zone: what every store falls back to (03.x). */
    public PriceList(
        UUID id,
        UUID tenantId,
        String name,
        String channel,
        String currency,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean active,
        Instant createdAt) {
      this(
          id,
          tenantId,
          name,
          channel,
          currency,
          effectiveFrom,
          effectiveTo,
          active,
          createdAt,
          null);
    }

    public static final String CHANNEL_ALL = "ALL";
    public static final String CHANNEL_ONLINE = "ONLINE";
    public static final String CHANNEL_POS = "POS";
  }

  /** A single price for a variant within a price list, optionally qty-break-tiered. */
  public record PriceListItem(
      UUID id,
      UUID tenantId,
      UUID priceListId,
      UUID variantId,
      BigDecimal price,
      BigDecimal minQty,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * Time-bounded promotional discount. type=PERCENT: value is percentage off (e.g. 10 = 10% off).
   * type=FLAT: value is flat amount off in the price list's currency.
   */
  public record Promotion(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      String type,
      BigDecimal value,
      BigDecimal minOrderAmount,
      String channel,
      boolean active,
      Instant startsAt,
      Instant endsAt,
      Instant createdAt,
      int priority,
      boolean exclusive,
      String couponCode,
      Integer maxRedemptions,
      Integer maxPerCustomer,
      BigDecimal buyQty,
      BigDecimal getQty,
      BigDecimal getDiscountPct) {

    /** Percentage off each matching line. {@code value} is 0-100. */
    public static final String TYPE_PERCENT = "PERCENT";

    /** Fixed amount off each matching unit. */
    public static final String TYPE_FLAT = "FLAT";

    /** Percentage off the whole basket, after line-level promotions have run. */
    public static final String TYPE_BASKET_PERCENT = "BASKET_PERCENT";

    /** Fixed amount off the whole basket. */
    public static final String TYPE_BASKET_FLAT = "BASKET_FLAT";

    /** Fixed amount off, but only once the basket clears {@code minOrderAmount}. */
    public static final String TYPE_SPEND_THRESHOLD = "SPEND_THRESHOLD";

    /** Buy {@code buyQty}, get {@code getQty} at {@code getDiscountPct} off (100 = free). */
    public static final String TYPE_BOGO = "BOGO";

    /**
     * Mix and match (03.8): any {@code buyQty} units from the scope for {@code value} — "any 3 for
     * £10". Whole bundles only; the dearest units make up the bundles; the leftover units are
     * charged in full.
     */
    public static final String TYPE_MIX_MATCH = "MIX_MATCH";

    /** True when this promotion must be presented rather than applying on its own. */
    public boolean requiresCoupon() {
      return couponCode != null && !couponCode.isBlank();
    }

    /** True for the two types that discount the basket rather than any particular line. */
    public boolean isBasketLevel() {
      return TYPE_BASKET_PERCENT.equals(type)
          || TYPE_BASKET_FLAT.equals(type)
          || TYPE_SPEND_THRESHOLD.equals(type);
    }
  }

  /**
   * One line of a basket being quoted.
   *
   * @param variantId what is being bought
   * @param qty how many
   * @param unitPrice the base price before any promotion
   */
  public record BasketLine(UUID variantId, BigDecimal qty, BigDecimal unitPrice) {}

  /**
   * What one promotion took off one line.
   *
   * @param variantId the line discounted
   * @param promotionId which promotion did it
   * @param promotionName its name, so a receipt can say why the price changed
   * @param amount the money taken off that line in total, not per unit
   */
  public record LineDiscount(
      UUID variantId, UUID promotionId, String promotionName, BigDecimal amount) {}

  /**
   * The engine's answer for one basket.
   *
   * @param lineDiscounts every line-level reduction, itemised by promotion
   * @param basketDiscounts every whole-basket reduction, itemised by promotion
   * @param appliedPromotionIds every promotion that took something off, in the order it ran — the
   *     list a redemption ledger is written from
   * @param rejectedCoupons coupon codes the caller presented that did not apply, each with the
   *     reason. Returned rather than ignored: a customer who typed a code is owed an answer, and
   *     "nothing happened" is the answer that generates a support call.
   */
  public record PromotionOutcome(
      List<LineDiscount> lineDiscounts,
      List<LineDiscount> basketDiscounts,
      List<UUID> appliedPromotionIds,
      Map<String, String> rejectedCoupons) {

    /**
     * Everything this outcome takes off the basket.
     *
     * @return the line and basket discounts summed together
     */
    public BigDecimal totalDiscount() {
      return java.util.stream.Stream.concat(lineDiscounts.stream(), basketDiscounts.stream())
          .map(LineDiscount::amount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  /** Scopes a promotion to a specific variant, category, or ALL products. */
  /** What a promotion or price list was switched to, by whom and why. Append-only (SJ-D33). */
  public record StatusChange(
      UUID id,
      UUID tenantId,
      String subjectType,
      UUID subjectId,
      boolean active,
      String reason,
      UUID changedBy,
      Instant changedAt) {

    public static final String PROMOTION = "PROMOTION";
    public static final String PRICE_LIST = "PRICE_LIST";
  }

  public record PromotionItem(
      UUID id, UUID tenantId, UUID promotionId, String scopeType, UUID scopeId, Instant createdAt) {

    public static final String SCOPE_VARIANT = "VARIANT";
    public static final String SCOPE_CATEGORY = "CATEGORY";
    public static final String SCOPE_ALL = "ALL";
  }

  /**
   * A promotion as a window in time over some items at some store, as inventory-svc reads it for
   * the demand forecast (06.x): {@code endsAt} is the promotion's end date, or the moment it was
   * switched off when that came first and it is off now; {@code variantIds} is its scope resolved
   * to variants, empty with {@code allVariants} when it applies to everything.
   */
  public record PromotionWindow(
      UUID promotionId,
      UUID storeId,
      String name,
      String type,
      BigDecimal value,
      String channel,
      boolean active,
      Instant startsAt,
      Instant endsAt,
      Set<UUID> variantIds,
      boolean allVariants) {

    public PromotionWindow {
      variantIds = Set.copyOf(variantIds);
    }
  }

  /**
   * POSLog-compatible tax capture per order line. Records the tax point date per s.6 VATA 1994
   * (basic tax point = time of supply). Feeds HMRC MTD VAT return boxes 1 and 6.
   */
  public record TaxTransaction(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID orderLineId,
      UUID variantId,
      UUID storeId,
      String vatCode,
      BigDecimal vatRate,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      boolean exempt,
      Instant taxPointDate,
      String invoiceRef,
      Instant createdAt) {}

  // ── Gap #41: POS price overrides ─────────────────────────────────────────

  /** Append-only record of a staff-approved ad-hoc POS price change. */
  public record PriceOverride(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      UUID storeId,
      BigDecimal originalPrice,
      BigDecimal overridePrice,
      String overrideReason,
      UUID overriddenBy,
      Instant createdAt) {}

  /** Result of price resolution: base price + promotion + VAT breakdown. */
  public record ResolvedPrice(
      UUID variantId,
      BigDecimal unitPrice,
      String vatCode,
      BigDecimal vatRate,
      BigDecimal vatAmount,
      BigDecimal totalWithVat,
      String currency,
      UUID priceListId,
      String promotionApplied,
      UnitPrice unitPricing,
      boolean unitPriceRequired,
      PriorPrice priorPrice,
      boolean priorPriceRequired,
      DisplayPrice display) {

    /** A price with no display currency asked for. */
    public ResolvedPrice(
        UUID variantId,
        BigDecimal unitPrice,
        String vatCode,
        BigDecimal vatRate,
        BigDecimal vatAmount,
        BigDecimal totalWithVat,
        String currency,
        UUID priceListId,
        String promotionApplied,
        UnitPrice unitPricing,
        boolean unitPriceRequired,
        PriorPrice priorPrice,
        boolean priorPriceRequired) {
      this(
          variantId,
          unitPrice,
          vatCode,
          vatRate,
          vatAmount,
          totalWithVat,
          currency,
          priceListId,
          promotionApplied,
          unitPricing,
          unitPriceRequired,
          priorPrice,
          priorPriceRequired,
          null);
    }

    public ResolvedPrice withDisplay(DisplayPrice d) {
      return new ResolvedPrice(
          variantId,
          unitPrice,
          vatCode,
          vatRate,
          vatAmount,
          totalWithVat,
          currency,
          priceListId,
          promotionApplied,
          unitPricing,
          unitPriceRequired,
          priorPrice,
          priorPriceRequired,
          d);
    }
  }

  /** The same price in another currency at the business's rate (03.x): shown, never charged. */
  public record DisplayPrice(
      String currency, BigDecimal rate, BigDecimal unitPrice, BigDecimal totalWithVat) {}

  /**
   * HMRC MTD VAT return. Boxes per VAT Notice 700 s.17: 1=output VAT, 2=EU acquisitions VAT
   * (post-Brexit=0), 3=total due, 4=input VAT reclaimed, 5=net payable, 6=total sales ex-VAT,
   * 7=total purchases ex-VAT, 8=EU goods supplied, 9=EU goods acquired.
   */
  /** How a tax summary groups its rows. An enum, so no request text ever reaches the SQL. */
  public enum TaxGrouping {
    CODE,
    STORE,
    MONTH
  }

  /**
   * One aggregated line of the tax summary.
   *
   * <p>{@code exempt} is part of the grouping key rather than folded into the totals, because the
   * VAT return treats the two differently: Box 1 counts output VAT on taxable supplies only, while
   * Box 6 counts the net of every supply including exempt ones. Collapsing them would leave neither
   * box derivable from the report that exists to show their working.
   *
   * @param groupKey the VAT code, store id or {@code YYYY-MM} month this line sums
   * @param exempt whether this line covers exempt supplies
   * @param netAmount total net (ex-VAT) consideration
   * @param vatAmount total VAT charged
   * @param grossAmount total gross consideration
   * @param transactions how many tax transactions the line covers
   */
  public record TaxSummaryRow(
      String groupKey,
      boolean exempt,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      long transactions) {}

  /**
   * Period totals, summed from the same rows the report returns so the two cannot disagree.
   *
   * @param netAmount net across every line — ties to VAT return Box 6
   * @param vatAmount VAT across every line, exempt lines included
   * @param outputVat VAT across taxable lines only — ties to VAT return Box 1. Differs from {@code
   *     vatAmount} only if a line marked exempt carries VAT, which is a data fault worth seeing
   *     rather than silently dropping the way the Box 1 query does
   * @param grossAmount gross across every line
   * @param transactions total tax transactions in the period
   */
  public record TaxSummaryTotals(
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal outputVat,
      BigDecimal grossAmount,
      long transactions) {}

  /**
   * @param rows one line per group, largest VAT first
   * @param totals the period totals, which reconcile to the VAT return
   * @param periodFrom inclusive lower bound, echoed back as supplied
   * @param periodTo exclusive upper bound, echoed back as supplied
   */
  public record TaxSummary(
      List<TaxSummaryRow> rows, TaxSummaryTotals totals, String periodFrom, String periodTo) {}

  /**
   * Input VAT projected from one supplier invoice (SJ-D39): what box 4 and box 7 are made of. The
   * event id is the invoice id; one row per event, by the unique index.
   */
  public record InputTaxTransaction(
      UUID id,
      UUID tenantId,
      UUID eventId,
      UUID invoiceId,
      UUID poId,
      UUID supplierId,
      String invoiceNumber,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      Instant taxPointDate) {}

  public record VatReturn(
      BigDecimal box1,
      BigDecimal box2,
      BigDecimal box3,
      BigDecimal box4,
      BigDecimal box5,
      BigDecimal box6,
      BigDecimal box7,
      BigDecimal box8,
      BigDecimal box9,
      String periodFrom,
      String periodTo) {}

  // ── Date-code markdown (05.4, 03.9) ──────────────────────────────────────

  /** How much off at how many days to expiry. */
  public record MarkdownStep(int daysToExpiry, BigDecimal percentOff) {}

  /** A store's ladder, or the tenant's, or the default the service names. */
  public record MarkdownLadder(UUID storeId, List<MarkdownStep> steps, String source) {
    public MarkdownLadder {
      steps = List.copyOf(steps);
    }

    public static final String SOURCE_STORE = "STORE";
    public static final String SOURCE_TENANT = "TENANT";
    public static final String SOURCE_DEFAULT = "DEFAULT";

    /**
     * What a tenant with no ladder gets: three days 25 % off, one day 50 %, the day itself 75 %.
     */
    public static final List<MarkdownStep> DEFAULT_STEPS =
        List.of(
            new MarkdownStep(3, new BigDecimal("25.00")),
            new MarkdownStep(1, new BigDecimal("50.00")),
            new MarkdownStep(0, new BigDecimal("75.00")));

    /**
     * The step that applies at a number of days to expiry: the tightest step whose threshold is at
     * or above it — two days out takes the three-day step, not the one-day one.
     */
    public MarkdownStep stepFor(long daysToExpiry) {
      MarkdownStep best = null;
      for (MarkdownStep s : steps) {
        if (s.daysToExpiry() >= daysToExpiry
            && (best == null || s.daysToExpiry() < best.daysToExpiry())) {
          best = s;
        }
      }
      return best;
    }
  }

  /**
   * A batch stickered at a lower price to sell before its date: the decision, the sticker's barcode
   * and the price the till charges when it reads it.
   */
  public record Markdown(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String batchNo,
      LocalDate expiryDate,
      BigDecimal qty,
      String currency,
      BigDecimal originalPrice,
      BigDecimal markdownPrice,
      BigDecimal percentOff,
      String reason,
      String labelCode,
      String status,
      UUID appliedBy,
      Instant createdAt,
      Instant cancelledAt,
      UUID cancelledBy,
      String cancelReason,
      /** What has sold at this price so far, across every order. */
      BigDecimal redeemedQty) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Shown, never stored: an active markdown past its date. */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** Reduced because it is near its date: the only reason art.6a(3) can reach. */
    public static final String REASON_SHORT_DATED = "SHORT_DATED";

    public static final java.util.Set<String> REASONS =
        java.util.Set.of("SHORT_DATED", "CLEARANCE", "DAMAGED_PACK", "OVERSTOCK");

    /** ACTIVE, CANCELLED, or EXPIRED when active and past its date on {@code today}. */
    public String effectiveStatus(LocalDate today) {
      if (STATUS_ACTIVE.equals(status) && expiryDate.isBefore(today)) {
        return STATUS_EXPIRED;
      }
      return status;
    }

    /** Packs still to sell at this price. */
    public BigDecimal remainingQty() {
      return qty.subtract(redeemedQty == null ? BigDecimal.ZERO : redeemedQty);
    }
  }

  /** One line of the morning's plan: an expiring batch and what the ladder says to do with it. */
  public record MarkdownSuggestion(
      UUID batchId,
      UUID variantId,
      String batchNo,
      LocalDate expiryDate,
      long daysToExpiry,
      BigDecimal remainingQty,
      BigDecimal currentPrice,
      String currency,
      MarkdownStep step,
      BigDecimal suggestedPrice,
      Markdown existing) {}

  // ── Making Tax Digital (18.5) ─────────────────────────────────────────────

  /**
   * The VAT number a tenant files under and the path the return takes to HMRC. Tokens are stored
   * encrypted and never leave this service.
   */
  public record VatRegistration(
      UUID tenantId,
      String vrn,
      String provider,
      String accessTokenCipher,
      String refreshTokenCipher,
      Instant tokenExpiresAt,
      Instant connectedAt,
      Instant updatedAt,
      UUID updatedBy) {

    public static final String PROVIDER_SIMULATED = "SIMULATED";
    public static final String PROVIDER_HMRC = "HMRC";
    public static final List<String> PROVIDERS = List.of(PROVIDER_SIMULATED, PROVIDER_HMRC);

    /** Whether HMRC's grant is held, so a return can be filed there. */
    public boolean connected() {
      return accessTokenCipher != null;
    }
  }

  /** One of HMRC's VAT obligations: a period the taxpayer must file for, open or fulfilled. */
  public record VatObligation(
      String periodKey, Instant start, Instant end, Instant due, String status, Instant received) {
    public static final String STATUS_OPEN = "O";
    public static final String STATUS_FULFILLED = "F";
  }

  /** A return as filed, and what came back. Append-only. */
  public record VatReturnSubmission(
      UUID id,
      UUID tenantId,
      String vrn,
      String periodKey,
      Instant periodFrom,
      Instant periodTo,
      VatReturn boxes,
      boolean finalised,
      String provider,
      String status,
      Instant submittedAt,
      UUID submittedBy,
      Instant processingDate,
      String formBundleNumber,
      String paymentIndicator,
      String chargeRefNumber,
      String receiptId,
      Instant receiptTimestamp,
      String errorCode,
      String errorMessage) {
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
  }

  // ── unit pricing (03.13) ───────────────────────────────────────────────────

  /** What one price buys, in a standard unit: KG, L, M, SQM, or EA for goods sold by number. */
  public record Measure(String unit, BigDecimal quantity) {}

  /**
   * A unit price: the price per one standard unit, how much one price buys, and how it reads (per
   * kg, per litre, each).
   */
  public record UnitPrice(BigDecimal amount, String unit, BigDecimal quantity, String label) {}

  /**
   * A shelf-edge label (03.13): the regular selling price and its unit price and, while a promotion
   * applies, the promotional price and its unit price beside it.
   */
  public record ShelfLabel(
      UUID variantId,
      boolean priced,
      String currency,
      BigDecimal regularPrice,
      UnitPrice regularUnitPrice,
      BigDecimal promotionalPrice,
      UnitPrice promotionalUnitPrice,
      String promotionName,
      boolean measureDeclared,
      boolean unitPriceRequired,
      BigDecimal priorPrice,
      String priorPriceStatus,
      boolean priorPriceRequired) {}

  /** A priced variant with no declared measure, so no unit price can be shown. */
  public record UnitPriceGap(UUID variantId, UUID productId, boolean catalogued) {}

  public record UnitPriceGaps(boolean required, java.util.List<UnitPriceGap> gaps) {}

  // ── prior price (03.12) ────────────────────────────────────────────────────

  /**
   * One row of the applied-price ledger: what a shopper was offered for a variant on a channel (and
   * at a store, where a store-scoped promotion applies) from {@code appliedFrom} until the next
   * row.
   */
  public record AppliedPrice(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String channel,
      UUID storeId,
      boolean priced,
      BigDecimal price,
      BigDecimal netPrice,
      BigDecimal regularPrice,
      String promotionName,
      String currency,
      Instant appliedFrom,
      /** Null when certain; else from when what was offered cannot be known, until the next row. */
      Instant uncertainSince,
      Instant recordedAt,
      String cause) {

    /** Offered below its own regular price. */
    public boolean reduced() {
      return priced && price.compareTo(regularPrice) < 0;
    }

    /** The same finding, uncertain from {@code since}. */
    public AppliedPrice withUncertainSince(Instant since) {
      return new AppliedPrice(
          id,
          tenantId,
          variantId,
          channel,
          storeId,
          priced,
          price,
          netPrice,
          regularPrice,
          promotionName,
          currency,
          appliedFrom,
          since,
          recordedAt,
          cause);
    }
  }

  /** A queued evaluation: the variant (null for all), as of when, and why. */
  public record PriceEvaluation(
      UUID id, UUID tenantId, UUID variantId, Instant asOf, String cause, Instant enqueuedAt) {}

  /**
   * A reduction's prior price as the ledger shows it: the status, the lowest price applied in the
   * window before the reduction began, when it began, and whether less than the full window of
   * history exists.
   */
  public record PriorPrice(
      String status,
      BigDecimal priorPrice,
      /** The same before VAT, for a till that shows prices net of it. */
      BigDecimal priorPriceNet,
      Instant reductionStartedAt,
      boolean shortHistory) {}

  /**
   * What a reduced-price sticker may say at the till (03.12): the price it was reduced from, only
   * where that is the prior price the law asks for, or where the law does not ask.
   *
   * @param wasPrice the price the till may strike through, before VAT; null when none may be shown
   * @param priorPrice the lowest price of the 30 days before, VAT included, where the law asks
   * @param priorPriceStatus as {@link PriorPrice#status}, where the law asks
   * @param priorPriceRequired whether art.6a binds the store's offers
   * @param perishableExempt whether it is exempt as short-dated goods, where art.6a(3) is law
   */
  public record MarkdownReduction(
      BigDecimal wasPrice,
      BigDecimal priorPrice,
      String priorPriceStatus,
      boolean priorPriceRequired,
      boolean perishableExempt) {}

  /** A reduction on offer as the ledger records it, with its prior price. */
  public record Reduction(
      UUID variantId,
      String channel,
      UUID storeId,
      BigDecimal price,
      BigDecimal regularPrice,
      String promotionName,
      String currency,
      PriorPrice prior,
      boolean required) {}

  // ── Price zones and competitor-driven repricing (03.x) ─────────────────────

  /** A group of stores that price alike; a price list bound to it beats the tenant-wide list. */
  public record PriceZone(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      List<UUID> storeIds,
      Instant createdAt) {}

  /** What a rival charged for a variant on a day, as seen; append-only. */
  public record CompetitorPrice(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String competitor,
      BigDecimal price,
      String currency,
      UUID zoneId,
      LocalDate observedOn,
      String source,
      UUID recordedBy,
      Instant recordedAt) {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_IMPORT = "IMPORT";
  }

  /** How one price list answers its rivals; the zone is the list's. */
  public record RepricingRule(
      UUID id,
      UUID tenantId,
      String name,
      UUID priceListId,
      UUID zoneId,
      Repricing.Rule rule,
      boolean active,
      Instant createdAt) {}

  /** What a run proposed for one variant, and what became of it. */
  public record RepricingProposal(
      UUID id,
      UUID tenantId,
      UUID ruleId,
      UUID priceListId,
      UUID zoneId,
      UUID variantId,
      BigDecimal currentPrice,
      String competitor,
      BigDecimal competitorPrice,
      LocalDate observedOn,
      BigDecimal proposedPrice,
      String currency,
      String status,
      Instant proposedAt,
      Instant decidedAt,
      UUID decidedBy) {

    public static final String PROPOSED = "PROPOSED";
    public static final String APPLIED = "APPLIED";
    public static final String DISMISSED = "DISMISSED";
  }

  /** One run of a rule: how many priced variants had a fresh rival price, how many moved. */
  public record RepricingRun(
      UUID ruleId, int examined, int proposed, List<RepricingProposal> proposals) {}
}
