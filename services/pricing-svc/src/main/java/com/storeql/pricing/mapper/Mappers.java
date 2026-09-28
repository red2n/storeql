package com.storeql.pricing.mapper;

import com.storeql.pricing.domain.Domain;
import com.storeql.pricing.domain.Domain.CustomerVatStatus;
import com.storeql.pricing.domain.Domain.PriceList;
import com.storeql.pricing.domain.Domain.PriceListItem;
import com.storeql.pricing.domain.Domain.PriceOverride;
import com.storeql.pricing.domain.Domain.ProductVatCategory;
import com.storeql.pricing.domain.Domain.Promotion;
import com.storeql.pricing.domain.Domain.PromotionItem;
import com.storeql.pricing.domain.Domain.PromotionWindow;
import com.storeql.pricing.domain.Domain.ResolvedPrice;
import com.storeql.pricing.domain.Domain.TaxSummary;
import com.storeql.pricing.domain.Domain.TaxSummaryRow;
import com.storeql.pricing.domain.Domain.TaxTransaction;
import com.storeql.pricing.domain.Domain.VatRate;
import com.storeql.pricing.domain.Domain.VatReturn;
import com.storeql.pricing.dto.Dtos;
import com.storeql.pricing.dto.Dtos.CustomerVatStatusResponse;
import com.storeql.pricing.dto.Dtos.DisplayPriceResponse;
import com.storeql.pricing.dto.Dtos.PriceListItemResponse;
import com.storeql.pricing.dto.Dtos.PriceListResponse;
import com.storeql.pricing.dto.Dtos.PriceOverrideResponse;
import com.storeql.pricing.dto.Dtos.ProductVatCategoryResponse;
import com.storeql.pricing.dto.Dtos.PromotionItemResponse;
import com.storeql.pricing.dto.Dtos.PromotionResponse;
import com.storeql.pricing.dto.Dtos.PromotionWindowResponse;
import com.storeql.pricing.dto.Dtos.ResolvedPriceResponse;
import com.storeql.pricing.dto.Dtos.TaxSummaryResponse;
import com.storeql.pricing.dto.Dtos.TaxSummaryRowResponse;
import com.storeql.pricing.dto.Dtos.TaxSummaryTotalsResponse;
import com.storeql.pricing.dto.Dtos.TaxTransactionResponse;
import com.storeql.pricing.dto.Dtos.VatRateResponse;
import com.storeql.pricing.dto.Dtos.VatReturnResponse;

/** Domain → DTO conversions. No HTTP or persistence types. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a VAT rate to its wire form.
   *
   * @param r the VAT rate to convert
   * @return its API representation
   */
  public static VatRateResponse toDto(VatRate r) {
    return new VatRateResponse(
        r.id(),
        r.tenantId(),
        r.code(),
        r.name(),
        r.rate(),
        r.exempt(),
        r.description(),
        r.effectiveFrom() != null ? r.effectiveFrom().toString() : null,
        r.effectiveTo() != null ? r.effectiveTo().toString() : null,
        r.createdAt() != null ? r.createdAt().toString() : null);
  }

  /**
   * Converts a variant's VAT assignment to its wire form.
   *
   * @param pvc the variant's VAT assignment to convert
   * @return its API representation
   */
  public static ProductVatCategoryResponse toDto(ProductVatCategory pvc) {
    return new ProductVatCategoryResponse(
        pvc.id(),
        pvc.tenantId(),
        pvc.variantId(),
        pvc.vatCode(),
        pvc.effectiveFrom() != null ? pvc.effectiveFrom().toString() : null,
        pvc.effectiveTo() != null ? pvc.effectiveTo().toString() : null,
        pvc.createdAt() != null ? pvc.createdAt().toString() : null);
  }

  /**
   * Converts a customer's VAT status to its wire form.
   *
   * @param cvs the customer's VAT status to convert
   * @return its API representation
   */
  public static CustomerVatStatusResponse toDto(CustomerVatStatus cvs) {
    return new CustomerVatStatusResponse(
        cvs.id(),
        cvs.tenantId(),
        cvs.customerId(),
        cvs.vatNumber(),
        cvs.vatRegistered(),
        cvs.reverseChargeEligible(),
        cvs.countryCode(),
        cvs.legalName(),
        cvs.einvoiceScheme(),
        cvs.einvoiceId(),
        cvs.createdAt() != null ? cvs.createdAt().toString() : null,
        cvs.updatedAt() != null ? cvs.updatedAt().toString() : null);
  }

  /**
   * Converts a price list to its wire form.
   *
   * @param pl the price list to convert
   * @return its API representation
   */
  public static PriceListResponse toDto(PriceList pl) {
    return new PriceListResponse(
        pl.id(),
        pl.tenantId(),
        pl.name(),
        pl.channel(),
        pl.currency(),
        pl.effectiveFrom() != null ? pl.effectiveFrom().toString() : null,
        pl.effectiveTo() != null ? pl.effectiveTo().toString() : null,
        pl.active(),
        pl.createdAt() != null ? pl.createdAt().toString() : null,
        pl.zoneId());
  }

  /**
   * Converts one priced variant on a price list to its wire form.
   *
   * @param item one priced variant on a price list to convert
   * @return its API representation
   */
  public static PriceListItemResponse toDto(PriceListItem item) {
    return new PriceListItemResponse(
        item.id(),
        item.tenantId(),
        item.priceListId(),
        item.variantId(),
        item.price(),
        item.minQty(),
        item.createdAt() != null ? item.createdAt().toString() : null,
        item.updatedAt() != null ? item.updatedAt().toString() : null);
  }

  /**
   * Converts a resolved price to its wire form.
   *
   * @param rp the resolved price to convert
   * @return its API representation
   */
  public static ResolvedPriceResponse toDto(ResolvedPrice rp) {
    return new ResolvedPriceResponse(
        rp.variantId(),
        rp.unitPrice(),
        rp.vatCode(),
        rp.vatRate(),
        rp.vatAmount(),
        rp.totalWithVat(),
        rp.currency(),
        rp.priceListId(),
        rp.promotionApplied(),
        toUnitPrice(rp.unitPricing()),
        rp.unitPriceRequired(),
        rp.priorPrice() == null ? null : rp.priorPrice().priorPrice(),
        rp.priorPrice() == null ? null : rp.priorPrice().status(),
        rp.priorPrice() == null || rp.priorPrice().reductionStartedAt() == null
            ? null
            : rp.priorPrice().reductionStartedAt().toString(),
        rp.priorPriceRequired(),
        announceable(
            rp.promotionApplied() != null,
            rp.priorPriceRequired(),
            rp.priorPrice() == null ? null : rp.priorPrice().status()),
        rp.display() == null
            ? null
            : new DisplayPriceResponse(
                rp.display().currency(),
                rp.display().rate(),
                rp.display().unitPrice(),
                rp.display().totalWithVat()));
  }

  /**
   * Whether a price may be announced as a reduction: a promotion applies and, where art.6a binds,
   * its prior price is known and above today's (03.12).
   */
  static boolean announceable(boolean promoted, boolean required, String status) {
    return com.storeql.pricing.service.PriorPrices.announceable(promoted, required, status);
  }

  public static com.storeql.pricing.dto.Dtos.AppliedPriceResponse toAppliedPrice(
      com.storeql.pricing.domain.Domain.AppliedPrice a) {
    return new com.storeql.pricing.dto.Dtos.AppliedPriceResponse(
        a.id(),
        a.channel(),
        a.storeId(),
        a.priced(),
        a.price(),
        a.netPrice(),
        a.regularPrice(),
        a.promotionName(),
        a.currency(),
        a.appliedFrom().toString(),
        a.uncertainSince() == null ? null : a.uncertainSince().toString(),
        a.recordedAt() == null ? null : a.recordedAt().toString(),
        a.cause());
  }

  /** A reduction on offer in its wire form. */
  public static com.storeql.pricing.dto.Dtos.ReductionResponse toReduction(
      com.storeql.pricing.domain.Domain.Reduction r) {
    var prior = r.prior();
    return new com.storeql.pricing.dto.Dtos.ReductionResponse(
        r.variantId(),
        r.channel(),
        r.storeId(),
        r.price(),
        r.regularPrice(),
        r.promotionName(),
        r.currency(),
        prior.priorPrice(),
        prior.status(),
        prior.reductionStartedAt() == null ? null : prior.reductionStartedAt().toString(),
        r.required(),
        announceable(true, r.required(), prior.status()));
  }

  /** A unit price in its wire form, or null. */
  public static com.storeql.pricing.dto.Dtos.UnitPriceResponse toUnitPrice(
      com.storeql.pricing.domain.Domain.UnitPrice u) {
    return u == null
        ? null
        : new com.storeql.pricing.dto.Dtos.UnitPriceResponse(
            u.amount(), u.unit(), u.quantity(), u.label());
  }

  public static com.storeql.pricing.dto.Dtos.ShelfLabelResponse toShelfLabel(
      com.storeql.pricing.domain.Domain.ShelfLabel l) {
    return new com.storeql.pricing.dto.Dtos.ShelfLabelResponse(
        l.variantId(),
        l.priced(),
        l.currency(),
        l.regularPrice(),
        toUnitPrice(l.regularUnitPrice()),
        l.promotionalPrice(),
        toUnitPrice(l.promotionalUnitPrice()),
        l.promotionName(),
        l.measureDeclared(),
        l.unitPriceRequired(),
        l.priorPrice(),
        l.priorPriceStatus(),
        l.priorPriceRequired(),
        announceable(l.promotionalPrice() != null, l.priorPriceRequired(), l.priorPriceStatus()));
  }

  public static com.storeql.pricing.dto.Dtos.UnitPriceGapsResponse toUnitPriceGaps(
      com.storeql.pricing.domain.Domain.UnitPriceGaps g) {
    return new com.storeql.pricing.dto.Dtos.UnitPriceGapsResponse(
        g.required(),
        g.gaps().stream()
            .map(
                x ->
                    new com.storeql.pricing.dto.Dtos.UnitPriceGapResponse(
                        x.variantId(), x.productId(), x.catalogued()))
            .toList());
  }

  /**
   * Converts a promotion to its wire form.
   *
   * @param p the promotion to convert
   * @return its API representation
   */
  public static PromotionResponse toDto(Promotion p) {
    return toDto(p, null);
  }

  /** A promotion window for the forecast (06.x); the variant ids in a stable order. */
  public static PromotionWindowResponse toDto(PromotionWindow w) {
    return new PromotionWindowResponse(
        w.promotionId(),
        w.storeId(),
        w.name(),
        w.type(),
        w.value(),
        w.channel(),
        w.active(),
        w.startsAt().toString(),
        w.endsAt() == null ? null : w.endsAt().toString(),
        w.variantIds().stream().sorted().toList(),
        w.allVariants());
  }

  /**
   * As {@link #toDto(Promotion)}, saying whether the storefront may advertise it as a reduction
   * (03.12).
   */
  public static PromotionResponse toDto(Promotion p, Boolean reductionAnnounceable) {
    return new PromotionResponse(
        p.id(),
        p.tenantId(),
        p.storeId(),
        p.name(),
        p.type(),
        p.value(),
        p.minOrderAmount(),
        p.channel(),
        p.active(),
        p.startsAt() != null ? p.startsAt().toString() : null,
        p.endsAt() != null ? p.endsAt().toString() : null,
        p.createdAt() != null ? p.createdAt().toString() : null,
        p.priority(),
        p.exclusive(),
        p.couponCode(),
        p.maxRedemptions(),
        p.maxPerCustomer(),
        p.buyQty(),
        p.getQty(),
        p.getDiscountPct(),
        reductionAnnounceable);
  }

  /**
   * Converts a promotion's scope row to its wire form.
   *
   * @param pi the promotion's scope row to convert
   * @return its API representation
   */
  public static PromotionItemResponse toDto(PromotionItem pi) {
    return new PromotionItemResponse(
        pi.id(),
        pi.tenantId(),
        pi.promotionId(),
        pi.scopeType(),
        pi.scopeId(),
        pi.createdAt() != null ? pi.createdAt().toString() : null);
  }

  /**
   * Converts one recorded tax line to its wire form.
   *
   * @param tt one recorded tax line to convert
   * @return its API representation
   */
  public static TaxTransactionResponse toDto(TaxTransaction tt) {
    return new TaxTransactionResponse(
        tt.id(),
        tt.tenantId(),
        tt.orderId(),
        tt.orderLineId(),
        tt.variantId(),
        tt.storeId(),
        tt.vatCode(),
        tt.vatRate(),
        tt.netAmount(),
        tt.vatAmount(),
        tt.grossAmount(),
        tt.exempt(),
        tt.taxPointDate() != null ? tt.taxPointDate().toString() : null,
        tt.invoiceRef(),
        tt.createdAt() != null ? tt.createdAt().toString() : null);
  }

  /**
   * Converts the nine VAT return boxes to its wire form.
   *
   * @param vr the nine VAT return boxes to convert
   * @return its API representation
   */
  public static VatReturnResponse toDto(VatReturn vr) {
    return new VatReturnResponse(
        vr.box1(),
        vr.box2(),
        vr.box3(),
        vr.box4(),
        vr.box5(),
        vr.box6(),
        vr.box7(),
        vr.box8(),
        vr.box9(),
        vr.periodFrom(),
        vr.periodTo(),
        VAT_BOXES_COMPUTED,
        VAT_BOXES_NOT_COMPUTED,
        true,
        VAT_RETURN_CAVEAT);
  }

  /**
   * Boxes 1, 3, 5, 6 from tax_transactions; 4, 7 from input_tax_transactions; 2, 8, 9 not modelled.
   */
  static final java.util.List<Integer> VAT_BOXES_COMPUTED = java.util.List.of(1, 3, 4, 5, 6, 7);

  static final java.util.List<Integer> VAT_BOXES_NOT_COMPUTED = java.util.List.of(2, 8, 9);

  /**
   * SJ-D39 closed: box 4 (input VAT reclaimed) and box 7 (net purchases) are projected from the
   * supplier invoices purchase-svc captures, by invoice date. Boxes 2, 8 and 9 concern Northern
   * Ireland protocol acquisitions and supplies, which nothing here models; for a business without
   * them they are genuinely zero, and the caveat says so rather than leaving a reader to guess.
   */
  static final String VAT_RETURN_CAVEAT =
      "Boxes 4 and 7 come from the supplier invoices purchasing captured in the period, by invoice"
          + " date. Boxes 2, 8 and 9 are zero because no Northern Ireland protocol acquisitions or"
          + " supplies are modelled — check that applies to you before filing.";

  /**
   * Converts a manual price override to its wire form.
   *
   * @param p the manual price override to convert
   * @return its API representation
   */
  public static PriceOverrideResponse toDto(PriceOverride p) {
    return new PriceOverrideResponse(
        p.id() != null ? p.id().toString() : null,
        p.orderId() != null ? p.orderId().toString() : null,
        p.variantId() != null ? p.variantId().toString() : null,
        p.storeId() != null ? p.storeId().toString() : null,
        p.originalPrice(),
        p.overridePrice(),
        p.overrideReason(),
        p.overriddenBy() != null ? p.overriddenBy().toString() : null,
        p.createdAt() != null ? p.createdAt().toString() : null);
  }

  /**
   * Converts the tax summary report to its wire form.
   *
   * @param ts the tax summary report to convert
   * @return its API representation
   */
  public static TaxSummaryResponse toDto(TaxSummary ts) {
    return new TaxSummaryResponse(
        ts.rows().stream().map(Mappers::toDto).toList(),
        new TaxSummaryTotalsResponse(
            ts.totals().netAmount(),
            ts.totals().vatAmount(),
            ts.totals().outputVat(),
            ts.totals().grossAmount(),
            ts.totals().transactions()),
        ts.periodFrom(),
        ts.periodTo());
  }

  /**
   * Converts one tax summary group to its wire form.
   *
   * @param r one tax summary group to convert
   * @return its API representation
   */
  public static TaxSummaryRowResponse toDto(TaxSummaryRow r) {
    return new TaxSummaryRowResponse(
        r.groupKey(), r.exempt(), r.netAmount(), r.vatAmount(), r.grossAmount(), r.transactions());
  }

  /**
   * Converts one on/off status change to its wire form.
   *
   * @param c one on/off status change to convert
   * @return its API representation
   */
  public static Dtos.StatusChangeResponse toDto(Domain.StatusChange c) {
    return new Dtos.StatusChangeResponse(
        c.id(),
        c.subjectType(),
        c.subjectId(),
        c.active(),
        c.reason(),
        c.changedBy(),
        c.changedAt());
  }

  // ── Making Tax Digital (18.5) ─────────────────────────────────────────────

  private static String iso(java.time.Instant i) {
    return i == null ? null : i.toString();
  }

  /**
   * @param v a registration and what the deployment offers
   * @return its API representation; the tokens never cross the boundary
   */
  public static Dtos.VatRegistrationResponse toDto(
      com.storeql.pricing.service.MtdService.RegistrationView v) {
    var r = v.registration();
    if (r == null) {
      return new Dtos.VatRegistrationResponse(
          false, null, null, false, null, null, v.providers(), v.hmrcConfigured());
    }
    return new Dtos.VatRegistrationResponse(
        true,
        r.vrn(),
        r.provider(),
        r.connected(),
        iso(r.connectedAt()),
        iso(r.updatedAt()),
        v.providers(),
        v.hmrcConfigured());
  }

  /**
   * @param o an obligation
   * @return its API representation
   */
  public static Dtos.VatObligationResponse toDto(
      com.storeql.pricing.domain.Domain.VatObligation o) {
    return new Dtos.VatObligationResponse(
        o.periodKey(), iso(o.start()), iso(o.end()), iso(o.due()), o.status(), iso(o.received()));
  }

  /**
   * @param s a filing
   * @return its API representation
   */
  public static Dtos.VatReturnSubmissionResponse toDto(
      com.storeql.pricing.domain.Domain.VatReturnSubmission s) {
    var b = s.boxes();
    return new Dtos.VatReturnSubmissionResponse(
        s.id().toString(),
        s.vrn(),
        s.periodKey(),
        iso(s.periodFrom()),
        iso(s.periodTo()),
        b.box1(),
        b.box2(),
        b.box3(),
        b.box4(),
        b.box5(),
        b.box6(),
        b.box7(),
        b.box8(),
        b.box9(),
        s.finalised(),
        s.provider(),
        s.status(),
        iso(s.submittedAt()),
        s.submittedBy() == null ? null : s.submittedBy().toString(),
        iso(s.processingDate()),
        s.formBundleNumber(),
        s.paymentIndicator(),
        s.chargeRefNumber(),
        s.receiptId(),
        iso(s.receiptTimestamp()),
        s.errorCode(),
        s.errorMessage());
  }

  // ── Date-code markdown (05.4, 03.9) ──────────────────────────────────────

  /**
   * @param m a markdown
   * @param today the date its effective status is judged on
   * @return its API representation
   */
  public static Dtos.MarkdownResponse toDto(
      com.storeql.pricing.domain.Domain.Markdown m, java.time.LocalDate today) {
    return new Dtos.MarkdownResponse(
        m.id(),
        m.storeId(),
        m.variantId(),
        m.batchId(),
        m.batchNo(),
        m.expiryDate().toString(),
        m.qty(),
        m.redeemedQty(),
        m.remainingQty(),
        m.currency(),
        m.originalPrice(),
        m.markdownPrice(),
        m.percentOff(),
        m.reason(),
        m.labelCode(),
        m.effectiveStatus(today),
        m.appliedBy(),
        m.createdAt(),
        m.cancelledAt(),
        m.cancelReason());
  }

  /**
   * @param l a ladder
   * @return its API representation
   */
  public static Dtos.MarkdownLadderResponse toDto(
      com.storeql.pricing.domain.Domain.MarkdownLadder l) {
    return new Dtos.MarkdownLadderResponse(
        l.storeId() == null ? null : l.storeId().toString(),
        l.source(),
        l.steps().stream()
            .map(s -> new Dtos.MarkdownStepRequest(s.daysToExpiry(), s.percentOff()))
            .toList());
  }

  /**
   * @param s one suggestion of the plan
   * @param today the date
   * @return its API representation
   */
  public static Dtos.MarkdownSuggestionResponse toDto(
      com.storeql.pricing.domain.Domain.MarkdownSuggestion s, java.time.LocalDate today) {
    return new Dtos.MarkdownSuggestionResponse(
        s.batchId(),
        s.variantId(),
        s.batchNo(),
        s.expiryDate() == null ? null : s.expiryDate().toString(),
        s.daysToExpiry(),
        s.remainingQty(),
        s.currentPrice(),
        s.currency(),
        s.step() == null ? null : s.step().daysToExpiry(),
        s.step() == null ? null : s.step().percentOff(),
        s.suggestedPrice(),
        s.existing() == null ? null : toDto(s.existing(), today));
  }

  /**
   * @param m a live markdown a sticker named
   * @return what the till needs
   */
  public static Dtos.MarkdownLabelResponse toLabelDto(
      com.storeql.pricing.domain.Domain.Markdown m,
      com.storeql.pricing.domain.Domain.MarkdownReduction r) {
    return new Dtos.MarkdownLabelResponse(
        m.id(),
        m.variantId(),
        m.storeId(),
        m.labelCode(),
        m.markdownPrice(),
        m.originalPrice(),
        m.currency(),
        m.expiryDate().toString(),
        m.remainingQty(),
        r.wasPrice(),
        r.wasPrice() != null,
        r.priorPrice(),
        r.priorPriceStatus(),
        r.priorPriceRequired(),
        r.perishableExempt());
  }

  // ── Price zones and competitor-driven repricing (03.x) ─────────────────────

  public static Dtos.PriceZoneResponse toDto(Domain.PriceZone z) {
    return new Dtos.PriceZoneResponse(
        z.id(), z.name(), z.description(), z.storeIds(), text(z.createdAt()));
  }

  public static Dtos.CompetitorPriceResponse toDto(Domain.CompetitorPrice c) {
    return new Dtos.CompetitorPriceResponse(
        c.id(),
        c.variantId(),
        c.competitor(),
        c.price(),
        c.currency(),
        c.zoneId(),
        text(c.observedOn()),
        c.source(),
        text(c.recordedAt()));
  }

  public static Dtos.RepricingRuleResponse toDto(Domain.RepricingRule r) {
    return new Dtos.RepricingRuleResponse(
        r.id(),
        r.name(),
        r.priceListId(),
        r.zoneId(),
        r.rule().strategy().name(),
        r.rule().value(),
        r.rule().floorPercent(),
        r.rule().rounding().name(),
        r.rule().maxAgeDays(),
        r.active(),
        text(r.createdAt()));
  }

  public static Dtos.RepricingProposalResponse toDto(Domain.RepricingProposal p) {
    return new Dtos.RepricingProposalResponse(
        p.id(),
        p.ruleId(),
        p.priceListId(),
        p.zoneId(),
        p.variantId(),
        p.currentPrice(),
        p.competitor(),
        p.competitorPrice(),
        text(p.observedOn()),
        p.proposedPrice(),
        p.currency(),
        p.status(),
        text(p.proposedAt()),
        text(p.decidedAt()));
  }

  public static Dtos.RepricingRunResponse toDto(Domain.RepricingRun run) {
    return new Dtos.RepricingRunResponse(
        run.ruleId(),
        run.examined(),
        run.proposed(),
        run.proposals().stream().map(Mappers::toDto).toList());
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
