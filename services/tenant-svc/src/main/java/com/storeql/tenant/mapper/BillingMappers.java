package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.BillingTax;
import com.storeql.tenant.domain.Subscriptions;
import com.storeql.tenant.domain.Subscriptions.BillingProfile;
import com.storeql.tenant.domain.Subscriptions.Buyer;
import com.storeql.tenant.domain.Subscriptions.Invoice;
import com.storeql.tenant.domain.Subscriptions.InvoiceFile;
import com.storeql.tenant.domain.Subscriptions.InvoiceLine;
import com.storeql.tenant.domain.Subscriptions.Payment;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.domain.Subscriptions.SubscriptionEvent;
import com.storeql.tenant.domain.Subscriptions.SubscriptionFile;
import com.storeql.tenant.dto.BillingDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Subscriptions and invoices as the wire carries them (21.9). */
public final class BillingMappers {

  private BillingMappers() {}

  public static BillingDtos.ProfileResponse toDto(BillingProfile p) {
    return new BillingDtos.ProfileResponse(
        p.legalName(),
        p.addressLine1(),
        p.addressLine2(),
        p.city(),
        p.postcode(),
        p.country(),
        p.vatNumber(),
        p.companyNumber(),
        p.invoicePrefix(),
        p.paymentTermsDays(),
        p.taxRate(),
        p.bankDetails(),
        text(p.updatedAt()));
  }

  public static BillingDtos.SubscriptionFileResponse toDto(SubscriptionFile f) {
    return new BillingDtos.SubscriptionFileResponse(
        toDto(f.subscription(), f.planCode(), f.planName()), events(f.events()));
  }

  public static BillingDtos.SubscriptionResponse toDto(
      Subscription s, String planCode, String planName) {
    return new BillingDtos.SubscriptionResponse(
        s.id().toString(),
        text(s.planId()),
        planCode,
        planName,
        s.status(),
        s.priceAmount(),
        s.currency(),
        s.billingInterval(),
        text(s.periodStart()),
        text(s.periodEnd()),
        text(s.trialEnd()),
        text(s.pendingPlanId()),
        s.cancelAtPeriodEnd(),
        toDto(s.buyer()),
        s.billingEmail(),
        text(s.startedAt()),
        text(s.cancelledAt()));
  }

  public static BillingDtos.BuyerResponse toDto(Buyer b) {
    return new BillingDtos.BuyerResponse(
        b.name(),
        b.line1(),
        b.line2(),
        b.city(),
        b.postcode(),
        b.country(),
        b.vatNumber(),
        text(b.vatCheckedAt()),
        b.vatCheckSource(),
        b.vatChecked());
  }

  public static BillingDtos.RateResponse toDto(Subscriptions.VatRate r) {
    return new BillingDtos.RateResponse(r.country(), text(r.effectiveFrom()), r.rate(), r.note());
  }

  public static List<BillingDtos.RateResponse> rates(List<Subscriptions.VatRate> rates) {
    return rates.stream().map(BillingMappers::toDto).toList();
  }

  public static List<BillingDtos.InvoiceResponse> invoices(List<Invoice> invoices) {
    return invoices.stream().map(BillingMappers::toDto).toList();
  }

  /** What a billing run did, as the platform console shows it. */
  public static BillingDtos.RunResponse run(
      java.time.LocalDate asOf, com.storeql.tenant.service.BillingService.Run result) {
    return new BillingDtos.RunResponse(
        asOf.toString(),
        result.raised().size(),
        invoices(result.raised()),
        result.skipped().stream()
            .map(
                k -> new BillingDtos.SkippedResponse(k.tenantId().toString(), k.code(), k.reason()))
            .toList());
  }

  /**
   * An invoice on the wire.
   *
   * <p>Money is scaled to two places. The columns are {@code NUMERIC(18,4)} so nothing is lost
   * inside, but an invoice is a document and prints to the penny, and a client that pays back
   * exactly the figure it was shown must not be refused for sending {@code 11.3000} where two
   * places were expected. Scaling here is lossless: every amount written is already a two-place
   * value. The rate keeps its four, because a rate genuinely has them (0.1900).
   */
  public static BillingDtos.InvoiceResponse toDto(Invoice i) {
    return new BillingDtos.InvoiceResponse(
        i.id().toString(),
        i.tenantId().toString(),
        i.number(),
        i.kind(),
        i.status(),
        text(i.issueDate()),
        text(i.dueDate()),
        text(i.periodStart()),
        text(i.periodEnd()),
        i.currency(),
        money(i.netAmount()),
        i.taxTreatment(),
        i.taxRate(),
        money(i.taxAmount()),
        money(i.totalAmount()),
        money(i.amountPaid()),
        money(i.outstanding()),
        i.voidedReason());
  }

  /** To the penny, as a document prints it. */
  private static java.math.BigDecimal money(java.math.BigDecimal amount) {
    return amount == null ? null : amount.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  /**
   * An invoice with everything printed on it.
   *
   * <p>The reverse-charge wording is attached here rather than stored on the row: it is the same
   * sentence on every such invoice, required by Directive 2006/112/EC art. 226(11a), and a copy per
   * invoice would be one more thing that could drift out of step with the law.
   */
  public static BillingDtos.InvoiceFileResponse toDto(InvoiceFile f) {
    Invoice i = f.invoice();
    return new BillingDtos.InvoiceFileResponse(
        toDto(i),
        f.lines().stream().map(BillingMappers::toDto).toList(),
        f.payments().stream().map(BillingMappers::toDto).toList(),
        i.sellerSnapshot(),
        i.buyerSnapshot(),
        i.buyerVatNumber(),
        BillingTax.REVERSE_CHARGE.equals(i.taxTreatment())
            ? BillingTax.REVERSE_CHARGE_WORDING
            : null);
  }

  public static BillingDtos.LineResponse toDto(InvoiceLine l) {
    return new BillingDtos.LineResponse(
        l.lineNo(),
        l.kind(),
        l.description(),
        l.quantity(),
        exact(l.unitAmount()),
        exact(l.amount()));
  }

  /**
   * A line's price as it was set, at least to pence: 10.00 stays 10.00, and a text part at 0.0350
   * reads 0.035 rather than a 0.04 nobody charged (21.10). The invoice's total is what is rounded.
   */
  private static BigDecimal exact(BigDecimal amount) {
    if (amount == null) return null;
    BigDecimal stripped = amount.stripTrailingZeros();
    return stripped.scale() < 2 ? amount.setScale(2, RoundingMode.UNNECESSARY) : stripped;
  }

  public static BillingDtos.PaymentResponse toDto(Payment p) {
    return new BillingDtos.PaymentResponse(
        p.id().toString(),
        money(p.amount()),
        p.currency(),
        p.method(),
        p.provider(),
        p.providerRef(),
        text(p.receivedOn()));
  }

  public static List<BillingDtos.EventResponse> events(List<SubscriptionEvent> events) {
    return events.stream()
        .map(
            e ->
                new BillingDtos.EventResponse(
                    e.id().toString(), e.kind(), e.detail(), text(e.createdAt())))
        .toList();
  }

  /**
   * The buyer a request asks for.
   *
   * <p><b>A changed VAT number carries no check.</b> The reverse charge rests on the number being
   * real, and a check is evidence about <em>that</em> number — so editing the number has to discard
   * it, or a business could be checked on one number, quietly substitute another, and keep the
   * treatment. Until somebody checks the new one it is charged its own country's rate, which is the
   * safe side of the mistake.
   */
  public static Buyer merge(Buyer current, BillingDtos.BuyerRequest req) {
    String wanted = req.vatNumber() == null ? null : req.vatNumber().strip().toUpperCase(LOCALE);
    String had =
        current.vatNumber() == null ? null : current.vatNumber().strip().toUpperCase(LOCALE);
    boolean sameNumber = java.util.Objects.equals(wanted, had);
    return new Buyer(
        req.name() == null || req.name().isBlank() ? current.name() : req.name().strip(),
        req.line1(),
        req.line2(),
        req.city(),
        req.postcode(),
        req.country().strip().toUpperCase(LOCALE),
        wanted,
        sameNumber ? current.vatCheckedAt() : null,
        sameNumber ? current.vatCheckedBy() : null,
        sameNumber ? current.vatCheckSource() : null);
  }

  private static final java.util.Locale LOCALE = java.util.Locale.ROOT;

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }

  private static String text(LocalDate day) {
    return day == null ? null : day.toString();
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }

  public static BillingDtos.DunningPolicyResponse toDto(
      com.storeql.tenant.domain.Dunning.Policy p) {
    return new BillingDtos.DunningPolicyResponse(
        p.enabled(),
        p.reminderDays(),
        p.suspendAfterDays(),
        p.uncollectibleAfterDays(),
        p.set(),
        text(p.updatedAt()));
  }

  /** A policy as a request asks for it; the days are sorted and de-duplicated by the record. */
  public static com.storeql.tenant.domain.Dunning.Policy policy(
      BillingDtos.DunningPolicyRequest req) {
    return new com.storeql.tenant.domain.Dunning.Policy(
        Boolean.TRUE.equals(req.enabled()),
        req.reminderDays().stream().distinct().sorted().toList(),
        req.suspendAfterDays(),
        req.uncollectibleAfterDays(),
        null,
        null);
  }

  public static List<BillingDtos.DunningEventResponse> dunningEvents(
      List<com.storeql.tenant.domain.Dunning.Event> events) {
    return events.stream()
        .map(
            e ->
                new BillingDtos.DunningEventResponse(
                    e.id().toString(), e.step(), e.detail(), text(e.createdAt())))
        .toList();
  }

  public static List<BillingDtos.OverdueResponse> overdue(
      List<com.storeql.tenant.domain.Dunning.Overdue> overdue) {
    return overdue.stream()
        .map(
            o ->
                new BillingDtos.OverdueResponse(
                    o.invoiceId().toString(),
                    o.tenantId().toString(),
                    o.number(),
                    text(o.dueDate()),
                    o.daysOverdue(),
                    o.stage(),
                    o.nextStep()))
        .toList();
  }

  /** What a dunning run did, as the platform console shows it. */
  public static BillingDtos.DunningRunResponse dunningRun(
      java.time.LocalDate asOf, com.storeql.tenant.service.DunningService.Run result) {
    return new BillingDtos.DunningRunResponse(
        asOf.toString(),
        result.taken().size(),
        result.taken().stream()
            .map(
                t ->
                    new BillingDtos.DunningStepResponse(
                        t.tenantId().toString(), t.invoiceNumber(), t.step()))
            .toList(),
        result.skipped().stream()
            .map(
                k ->
                    new BillingDtos.SkippedResponse(
                        k.tenantId().toString(), "DUNNING_SKIPPED", k.reason()))
            .toList());
  }
}
