package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.BillingTax;
import com.storeql.tenant.domain.BillingTax.Treatment;
import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.domain.Plans;
import com.storeql.tenant.domain.Plans.Plan;
import com.storeql.tenant.domain.Proration;
import com.storeql.tenant.domain.Proration.Prorated;
import com.storeql.tenant.domain.Subscriptions;
import com.storeql.tenant.domain.Subscriptions.BillingProfile;
import com.storeql.tenant.domain.Subscriptions.Buyer;
import com.storeql.tenant.domain.Subscriptions.Invoice;
import com.storeql.tenant.domain.Subscriptions.InvoiceLine;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.repo.BillingRepository;
import com.storeql.tenant.repo.ObligationRepository;
import com.storeql.tenant.repo.PlanRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The billing run: what a business owes for the period it is entering, and the invoice that says so
 * (21.9).
 *
 * <p><b>Billed in advance, on the subscription's own anniversary.</b> Not a calendar batch on the
 * first of the month — that would make every first period a stub and every proration a special
 * case. Each period is the same shape as the next, which is what makes the arithmetic here short.
 *
 * <p><b>The price is the one the business was sold</b>, locked on the subscription. A change to the
 * price list reaches new subscribers; an existing one keeps what it agreed until the platform
 * migrates it deliberately. Reading the price back off the plan at billing time would quietly undo
 * that, which is why nothing here looks at {@code plan_prices} except when a subscription is first
 * written.
 *
 * <p><b>A period is invoiced once</b>, by the unique index on (subscription, period start). The run
 * can be run twice, or by two replicas at the same instant, and the second one loses on the index
 * rather than on a check somebody remembered.
 *
 * <p><b>An invoice with no seller is not an invoice.</b> Nothing is billed until the platform has
 * filled in its own identity, and nothing is billed in a country whose rate the platform has not
 * set — a guessed rate is VAT charged wrongly in somebody else's jurisdiction, and refusing is the
 * cheaper mistake.
 */
@ApplicationScoped
public class BillingService {

  private static final System.Logger LOG = System.getLogger(BillingService.class.getName());

  /** Two places: an invoice does not print 5 or 5.0. */
  private static final int MONEY_SCALE = 2;

  /**
   * How many periods one run will catch up on. Two years of months: enough that a platform which
   * has not billed for a long while is brought fully up to date, and few enough that a date typed
   * wrongly cannot raise a lifetime of invoices in one call.
   */
  private static final int CATCH_UP_PASSES = 24;

  /** The regime whose reverse charge and destination rules {@link BillingTax} decides. */
  private static final String EU = "EU";

  @Inject BillingRepository repo;
  @Inject PlanRepository plans;
  @Inject UsageService usageOf;

  /** What a business is told about its trial (21.13). */
  @Inject TrialNoticeService trialNotices;

  /**
   * Membership is asked of a date, not of a list: the platform already records which countries
   * belong to which regime and when — the United Kingdom was a member until 31 January 2020 — so
   * whether a country was in the EU is a question about the invoice's date.
   */
  @Inject ObligationRepository jurisdictions;

  /**
   * What the platform charges as, or a refusal.
   *
   * @throws ApiException 409 {@code BILLING_PROFILE_NOT_SET}
   */
  public BillingProfile profile() {
    return repo.profile()
        .orElseThrow(
            () ->
                ApiException.conflict(
                    "BILLING_PROFILE_NOT_SET",
                    "The platform has not set its own billing details; an invoice with no seller is"
                        + " not an invoice anywhere it trades"));
  }

  /** Sets what the platform bills as. */
  public BillingProfile saveProfile(
      com.storeql.tenant.dto.BillingDtos.ProfileRequest req, UUID actorId) {
    BillingProfile p =
        new BillingProfile(
            req.legalName().strip(),
            req.addressLine1(),
            req.addressLine2(),
            req.city(),
            req.postcode(),
            req.country().strip().toUpperCase(java.util.Locale.ROOT),
            blankToNull(req.vatNumber()),
            blankToNull(req.companyNumber()),
            req.invoicePrefix().strip().toUpperCase(java.util.Locale.ROOT),
            req.paymentTermsDays(),
            req.taxRate(),
            req.bankDetails(),
            actorId,
            Instant.now());
    repo.saveProfile(p);
    return p;
  }

  /** Every rate the platform has set. */
  public List<Subscriptions.VatRate> rates() {
    return repo.rates();
  }

  /**
   * Sets a country's rate from a date.
   *
   * @throws ApiException 400 {@code BILLING_DATE_INVALID}
   */
  public List<Subscriptions.VatRate> saveRate(
      com.storeql.tenant.dto.BillingDtos.RateRequest req, UUID actorId) {
    repo.saveRate(
        req.country().strip().toUpperCase(java.util.Locale.ROOT),
        date(req.effectiveFrom()),
        req.rate(),
        req.note(),
        actorId);
    return repo.rates();
  }

  /** The invoices still owed, most overdue first. */
  public List<Invoice> receivables(Integer limit) {
    return repo.receivables(pageSize(limit));
  }

  /** One invoice with everything printed on it, for the platform. */
  public Subscriptions.InvoiceFile invoiceFile(UUID invoiceId) {
    Invoice invoice =
        repo.invoice(invoiceId)
            .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice"));
    return withLines(invoice);
  }

  /**
   * One of a business's own invoices.
   *
   * @throws ApiException 404 {@code INVOICE_NOT_FOUND} for another business's invoice too — a 403
   *     would confirm to a guesser that the id is real
   */
  public Subscriptions.InvoiceFile ownInvoiceFile(UUID tenantId, UUID invoiceId) {
    Invoice invoice =
        repo.ownInvoice(tenantId, invoiceId)
            .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice"));
    return withLines(invoice);
  }

  /** A business's own invoices, newest first. */
  public List<Invoice> invoicesOf(UUID tenantId, Integer limit) {
    return repo.invoicesOf(tenantId, pageSize(limit));
  }

  /**
   * Records money received against an invoice and settles it when that reaches the total.
   *
   * @throws ApiException 409 {@code INVOICE_NOT_OPEN} when it has been withdrawn, given up on, or
   *     is already paid — a payment against any of those is a bookkeeping mistake, not a payment
   */
  public Subscriptions.InvoiceFile recordPayment(
      UUID invoiceId, com.storeql.tenant.dto.BillingDtos.RecordPaymentRequest req, UUID actorId) {
    Invoice invoice =
        repo.invoice(invoiceId)
            .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice"));
    if (!Subscriptions.BANK_TRANSFER.equals(req.method())
        && !Subscriptions.CARD.equals(req.method())) {
      throw ApiException.badRequest(
          "PAYMENT_METHOD_UNKNOWN",
          "Money arrives by " + Subscriptions.BANK_TRANSFER + " or " + Subscriptions.CARD);
    }
    Subscriptions.Payment payment =
        new Subscriptions.Payment(
            Ids.newId(),
            invoiceId,
            money(req.amount()),
            invoice.currency(),
            req.method(),
            req.provider(),
            req.providerRef(),
            req.receivedOn() == null || req.receivedOn().isBlank()
                ? LocalDate.now()
                : date(req.receivedOn()),
            actorId,
            Instant.now());
    // The provider's reference says which payment this is, so it is what makes a second report of
    // it the same payment — as a key derived from it, a UUIDv7 like every key the platform stores.
    String key =
        req.providerRef() == null || req.providerRef().isBlank()
            ? null
            : Ids.derived(invoice.tenantId(), "billing-payment:" + req.providerRef()).toString();
    if (!repo.pay(payment, invoice.tenantId(), key)) {
      throw ApiException.conflict(
          "INVOICE_NOT_OPEN",
          "This invoice is " + invoice.status() + ", so no payment can be applied to it");
    }
    settleUp(invoice.tenantId());
    return invoiceFile(invoiceId);
  }

  /**
   * Pays an invoice in full, for the pay link a dunning notice carries (21.12).
   *
   * <p>The same path as {@link #recordPayment}, reached without a DTO because the caller is a link
   * and not a form. It reuses {@code repo.pay} and {@code settleUp} rather than repeating them: two
   * ways to apply money to an invoice is one edit away from only one of them settling it.
   *
   * @throws ApiException 409 {@code INVOICE_NOT_OPEN}
   */
  public Invoice payInFull(UUID invoiceId, BigDecimal amount, UUID recordedBy) {
    Invoice invoice =
        repo.invoice(invoiceId)
            .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice"));
    Subscriptions.Payment payment =
        new Subscriptions.Payment(
            Ids.newId(),
            invoiceId,
            money(amount),
            invoice.currency(),
            Subscriptions.CARD,
            "pay-link",
            null,
            LocalDate.now(),
            recordedBy,
            Instant.now());
    if (!repo.pay(payment, invoice.tenantId(), null)) {
      throw ApiException.conflict(
          "INVOICE_NOT_OPEN",
          "This invoice is " + invoice.status() + ", so no payment can be applied to it");
    }
    settleUp(invoice.tenantId());
    return repo.invoice(invoiceId).orElseThrow();
  }

  /**
   * Moves a due date out — a promise to pay (21.12).
   *
   * @throws ApiException 400 {@code DUE_DATE_NOT_LATER} when nothing moved, which is a date that is
   *     not later or an invoice that is no longer open; the same answer either way, because both
   *     mean "this is not a date this invoice can be given"
   */
  public Invoice extendDueDate(UUID invoiceId, LocalDate to) {
    if (!repo.extendDueDate(invoiceId, to)) {
      throw ApiException.badRequest(
          "DUE_DATE_NOT_LATER",
          "A due date only ever moves outwards, and only on an invoice that is still owed");
    }
    return repo.invoice(invoiceId).orElseThrow();
  }

  /** Gives up on a debt (21.12). */
  public boolean writeOff(UUID invoiceId) {
    return repo.writeOff(invoiceId);
  }

  /**
   * Withdraws an unpaid invoice, keeping its number.
   *
   * @throws ApiException 409 {@code INVOICE_NOT_VOIDABLE}
   */
  public Subscriptions.InvoiceFile voidInvoice(UUID invoiceId, String reason) {
    invoiceFile(invoiceId);
    if (!repo.voidInvoice(invoiceId, reason)) {
      throw ApiException.conflict(
          "INVOICE_NOT_VOIDABLE",
          "Only an open invoice that has taken no money is withdrawn; one that has been paid against"
              + " is corrected with a credit note, so the money and the paperwork still agree");
    }
    return invoiceFile(invoiceId);
  }

  /**
   * Bills a subscription's very first period, the day it starts.
   *
   * <p>Called by the lifecycle when a business signs up with no trial: the first period is owed in
   * advance like every period after it, so there is one rule and not two.
   */
  public Invoice billFirstPeriod(Subscription s, LocalDate on) {
    BillingProfile seller = profile();
    return issue(
        s,
        seller,
        s.periodStart(),
        s.periodEnd(),
        on,
        List.of(planLine(s, s.periodStart(), s.periodEnd())),
        Subscriptions.PERIOD);
  }

  /**
   * Brings a subscription back to ACTIVE once it owes nothing.
   *
   * <p>Only from PAST_DUE. A subscription suspended by an administrator is not un-suspended by a
   * payment (21.12) — that is a decision somebody took, not a debt.
   */
  private void settleUp(UUID tenantId) {
    repo.ofTenant(tenantId)
        // Behind **or** suspended. Only PAST_DUE was lifted here, so a business that let dunning
        // take
        // the platform away and then paid in full got its tenant reactivated and its subscription
        // left
        // SUSPENDED — which is not billable, so it was never invoiced again and quietly stopped
        // being a
        // customer while still using the platform. k6 dunning-flow found it on its first live run.
        .filter(
            s ->
                Subscriptions.PAST_DUE.equals(s.status())
                    || Subscriptions.SUSPENDED.equals(s.status()))
        .filter(s -> repo.invoicesOf(tenantId, 100).stream().noneMatch(Invoice::open))
        .ifPresent(
            s -> {
              // Named as the status it moves from, so two callers racing cannot both claim it.
              if (repo.moveStatus(s.id(), s.status(), Subscriptions.ACTIVE)) {
                repo.record(
                    Ids.newId(),
                    tenantId,
                    s.id(),
                    Subscriptions.PAID_UP,
                    "everything owed has been paid; it was " + s.status(),
                    null);
              }
            });
  }

  private Subscriptions.InvoiceFile withLines(Invoice invoice) {
    return new Subscriptions.InvoiceFile(
        invoice, repo.linesOf(invoice.id()), repo.paymentsOf(invoice.id()));
  }

  private static int pageSize(Integer limit) {
    if (limit == null) return 20;
    return Math.max(1, Math.min(100, limit));
  }

  private static LocalDate date(String day) {
    try {
      return LocalDate.parse(day.strip());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400, "BILLING_DATE_INVALID", "A date is written as 2026-09-18", List.of(), e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /**
   * Bills everything whose period has run out.
   *
   * @param asOf the day the run is for
   * @return the invoices raised, in the order they were raised
   */
  /**
   * What one pass of the run did.
   *
   * @param skipped the subscriptions it could not bill, and why — one business the platform has no
   *     rate for must not stop every other business being billed
   */
  public record Run(List<Invoice> raised, List<Skipped> skipped) {
    public Run {
      raised = List.copyOf(raised);
      skipped = List.copyOf(skipped);
    }
  }

  /** A subscription the run passed over, named so somebody can act on it. */
  public record Skipped(UUID tenantId, String code, String reason) {}

  public Run run(LocalDate asOf) {
    BillingProfile seller = profile();
    List<Invoice> raised = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    Set<UUID> passedOver = new HashSet<>();
    // Trials about to end are told so before anything is billed (21.13): the word is owed whether
    // or not any invoice is raised today.
    trialNotices.endingSoon(asOf, seller);
    for (int pass = 0; pass < CATCH_UP_PASSES; pass++) {
      List<Subscription> due =
          repo.due(asOf).stream().filter(s -> !passedOver.contains(s.id())).toList();
      if (due.isEmpty()) return new Run(raised, skipped);
      for (Subscription s : due) {
        try {
          renew(s, seller, asOf).ifPresent(raised::add);
        } catch (ApiException e) {
          // A refusal about this one subscription: no rate for its country, no price in its
          // currency. It is passed over and named, and the rest of the platform is still billed —
          // one business the platform has no tax position for must not stop everybody's invoices.
          // It is excluded from later passes too, or the catch-up loop would retry it 24 times.
          passedOver.add(s.id());
          skipped.add(new Skipped(s.tenantId(), e.code(), e.getMessage()));
          LOG.log(
              System.Logger.Level.WARNING,
              "the billing run passed over {0}: {1} — {2}",
              s.tenantId(),
              e.code(),
              e.getMessage());
        }
      }
    }
    LOG.log(
        System.Logger.Level.WARNING,
        "the billing run for {0} still had subscriptions due after {1} passes and stopped; check the"
            + " date it was given",
        asOf,
        CATCH_UP_PASSES);
    return new Run(raised, skipped);
  }

  /**
   * Bills one subscription for the period it is entering and moves it into that period.
   *
   * @return the invoice, or empty when there was nothing to do — a subscription cancelled at the
   *     period end, or one another replica has already renewed
   */
  private Optional<Invoice> renew(Subscription s, BillingProfile seller, LocalDate asOf) {
    if (s.cancelAtPeriodEnd()) {
      // Its last period's usage is owed like any other's, and billed before it ends: a failure here
      // leaves it due, so the next run tries again rather than letting the usage go unbilled.
      closeLastPeriod(s, seller, asOf);
      end(s, "the business asked for it to end when the period it had paid for ran out");
      return Optional.empty();
    }
    // The usage of the period that is ending, on the plan it was lived on (21.10) — read before a
    // waiting downgrade moves the subscription, and billed in arrears on the same invoice as the
    // next period in advance.
    UsageService.Closing usage = usageOf.close(s, s.periodStart(), s.periodEnd(), 2);
    boolean trialEnding = Subscriptions.TRIALING.equals(s.status());
    // A downgrade waits for the period already paid for; this is that moment.
    Subscription moved = applyPending(s);
    LocalDate start = moved.periodEnd();
    LocalDate end = advance(start, moved.billingInterval());
    List<InvoiceLine> lines = new ArrayList<>();
    lines.add(planLine(moved, start, end));
    lines.addAll(usage.lines());
    Invoice invoice =
        issue(moved, seller, start, end, asOf, lines, Subscriptions.PERIOD, usage.periods());
    repo.save(
        new Builder(moved).status(Subscriptions.ACTIVE).period(start, end).trialEnd(null).build());
    repo.record(
        Ids.newId(),
        moved.tenantId(),
        moved.id(),
        Subscriptions.RENEWED,
        "billed " + start + " to " + end + " on invoice " + invoice.number(),
        null);
    if (trialEnding) {
      // The trial is over on the file, and the business is told with the invoice and the way to
      // pay it (21.13) — once, however often the run runs.
      repo.record(
          Ids.newId(),
          moved.tenantId(),
          moved.id(),
          Subscriptions.TRIAL_ENDED,
          "the trial ended on " + start + "; the first invoice is " + invoice.number(),
          null);
      trialNotices.ended(
          new Builder(moved).status(Subscriptions.ACTIVE).period(start, end).trialEnd(null).build(),
          invoice,
          seller);
    }
    return Optional.of(invoice);
  }

  /**
   * Writes one invoice: its tax, its total, its number and its lines.
   *
   * @throws ApiException 409 {@code BILLING_RATE_NOT_SET} when the buyer's country needs a rate the
   *     platform has not set — refused rather than guessed at
   */
  private Invoice issue(
      Subscription s,
      BillingProfile seller,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate issued,
      List<InvoiceLine> lines,
      String kind) {
    return issue(s, seller, periodStart, periodEnd, issued, lines, kind, List.of());
  }

  /**
   * Writes one invoice with what each meter billed on it, in one transaction (21.10).
   *
   * @throws ApiException 409 {@code BILLING_RATE_NOT_SET} as above
   */
  private Invoice issue(
      Subscription s,
      BillingProfile seller,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate issued,
      List<InvoiceLine> lines,
      String kind,
      List<Meters.UsagePeriod> usage) {
    Buyer buyer = s.buyer();
    String buyerCountry = buyer.country();
    Treatment treatment =
        BillingTax.decide(
            seller.country(),
            jurisdictions.memberOn(EU, seller.country(), issued),
            buyerCountry,
            jurisdictions.memberOn(EU, buyerCountry, issued),
            buyer.vatNumber(),
            buyer.vatChecked());

    BigDecimal net =
        lines.stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal rate = rateFor(treatment, seller, buyerCountry, issued);
    BigDecimal tax = money(net.multiply(rate));
    Instant now = Instant.now();

    Invoice invoice =
        new Invoice(
            Ids.newId(),
            s.tenantId(),
            s.id(),
            "",
            kind,
            Subscriptions.OPEN,
            issued,
            issued.plusDays(seller.paymentTermsDays()),
            periodStart,
            periodEnd,
            s.currency(),
            money(net),
            treatment.code(),
            rate,
            tax,
            money(net).add(tax),
            BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY),
            seller.snapshot(),
            buyer.snapshot(),
            buyer.vatNumber(),
            null,
            now,
            now);
    return repo.issue(invoice, lines, seller.invoicePrefix(), usage);
  }

  /** The rate a treatment charges, refusing where the platform has set none. */
  private BigDecimal rateFor(
      Treatment treatment, BillingProfile seller, String buyerCountry, LocalDate on) {
    if (!treatment.needsRate()) return BigDecimal.ZERO.setScale(4, RoundingMode.UNNECESSARY);
    String country = BillingTax.rateCountry(treatment, seller.country(), buyerCountry);
    if (country != null && country.equals(upper(seller.country()))) return seller.taxRate();
    return repo.rateOn(country, on)
        .orElseThrow(
            () ->
                ApiException.conflict(
                    "BILLING_RATE_NOT_SET",
                    "This business is in "
                        + country
                        + " and gave no checked VAT number, so its own country's rate applies and"
                        + " the platform has not set one; set it under Billing → VAT rates. A"
                        + " guessed rate is VAT charged wrongly in somebody else's country."));
  }

  // ── a plan change mid-period ────────────────────────────────────────────────

  /**
   * Moves a business to a bigger plan now, billing the days it has left on the new price against
   * what it was billed for them on the old one.
   *
   * <p>Two lines, never one net figure: a credit for the unused remainder of what was billed, and a
   * debit for the same days at the new price. An invoice showing only the difference cannot be
   * checked by the person paying it.
   *
   * @throws ApiException 409 {@code PLAN_NOT_SOLD}, {@code BILLING_NOT_ACTIVE}
   */
  public Invoice upgradeNow(UUID tenantId, UUID planId, LocalDate on, UUID actorId) {
    Subscription s = require(tenantId);
    BillingProfile seller = profile();
    Plan plan = planOnSale(planId);
    BigDecimal newPrice = priceOf(plan, s.currency(), on);

    // Rule 3 of the research: a business that has not paid for the period does not get credited for
    // the part of it it is giving back. The change takes effect; the credit does not.
    boolean credits = !Subscriptions.PAST_DUE.equals(s.status());
    Prorated p =
        Proration.onChange(
            credits ? s.priceAmount() : BigDecimal.ZERO,
            newPrice,
            s.periodStart(),
            s.periodEnd(),
            on);
    if (!p.any()) {
      throw ApiException.conflict(
          "BILLING_PERIOD_ENDING",
          "There are no days left in this period to charge for; the change belongs to the next one");
    }

    List<InvoiceLine> lines = new ArrayList<>();
    int line = 1;
    lines.add(
        prorationLine(
            line++,
            Subscriptions.LINE_CREDIT,
            "Unused "
                + p.daysRemaining()
                + " of "
                + p.daysInPeriod()
                + " days on the previous plan, at what was billed",
            p.credit().negate()));
    lines.add(
        prorationLine(
            line,
            Subscriptions.LINE_PRORATION,
            plan.name()
                + ", "
                + p.daysRemaining()
                + " of "
                + p.daysInPeriod()
                + " days to "
                + s.periodEnd(),
            p.debit()));

    // An adjustment, not a period: a proration can happen more than once inside one period, and
    // uq_invoices_period constrains periods alone. Raising this as a PERIOD collided with the
    // period's own invoice on the day a business signed up, which its first live run found.
    Invoice invoice = issue(s, seller, on, s.periodEnd(), on, lines, Subscriptions.ADJUSTMENT);
    repo.save(new Builder(s).planId(planId).price(newPrice).pendingPlanId(null).build());
    repo.record(
        Ids.newId(),
        tenantId,
        s.id(),
        Subscriptions.UPGRADED,
        "moved to "
            + plan.code()
            + " on "
            + on
            + "; invoice "
            + invoice.number()
            + (credits ? "" : " with no credit, because the current period is unpaid"),
        actorId);
    return invoice;
  }

  /**
   * Schedules a smaller plan for the end of the period the business has already paid for.
   *
   * @throws ApiException 409 {@code PLAN_NOT_SOLD}
   */
  public Subscription downgradeAtPeriodEnd(UUID tenantId, UUID planId, UUID actorId) {
    Subscription s = require(tenantId);
    Plan plan = planOnSale(planId);
    Subscription pending = new Builder(s).pendingPlanId(planId).build();
    repo.save(pending);
    repo.record(
        Ids.newId(),
        tenantId,
        s.id(),
        Subscriptions.DOWNGRADE_SCHEDULED,
        "will move to " + plan.code() + " on " + s.periodEnd() + ", the day this period ends",
        actorId);
    return pending;
  }

  private Subscription applyPending(Subscription s) {
    if (s.pendingPlanId() == null) return s;
    Plan plan = plans.find(s.pendingPlanId()).orElse(null);
    if (plan == null) return new Builder(s).pendingPlanId(null).build();
    BigDecimal price = priceOrZero(plan, s.currency(), s.periodEnd());
    Subscription moved = new Builder(s).planId(plan.id()).price(price).pendingPlanId(null).build();
    repo.save(moved);
    repo.record(
        Ids.newId(),
        s.tenantId(),
        s.id(),
        Subscriptions.DOWNGRADED,
        "moved to " + plan.code() + " as this period began",
        null);
    return moved;
  }

  /**
   * Bills the usage of a subscription's last period, as an adjustment of its own: there is no next
   * period for it to ride on. Nothing over the allowance is still written down, with no invoice.
   */
  private void closeLastPeriod(Subscription s, BillingProfile seller, LocalDate asOf) {
    UsageService.Closing usage = usageOf.close(s, s.periodStart(), s.periodEnd(), 1);
    if (usage.lines().isEmpty()) {
      usageOf.closeWithoutInvoice(usage);
      return;
    }
    issue(
        s,
        seller,
        s.periodStart(),
        s.periodEnd(),
        asOf,
        usage.lines(),
        Subscriptions.ADJUSTMENT,
        usage.periods());
  }

  private void end(Subscription s, String why) {
    if (!repo.moveStatus(s.id(), s.status(), Subscriptions.CANCELLED)) return;
    repo.save(new Builder(s).status(Subscriptions.CANCELLED).cancelledAt(Instant.now()).build());
    repo.record(Ids.newId(), s.tenantId(), s.id(), Subscriptions.ENDED, why, null);
  }

  // ── small things ────────────────────────────────────────────────────────────

  private InvoiceLine planLine(Subscription s, LocalDate from, LocalDate to) {
    return new InvoiceLine(
        Ids.newId(),
        1,
        Subscriptions.LINE_PLAN,
        planName(s.planId()) + ", " + from + " to " + to,
        BigDecimal.ONE,
        s.priceAmount(),
        money(s.priceAmount()));
  }

  private static InvoiceLine prorationLine(
      int no, String kind, String description, BigDecimal amount) {
    return new InvoiceLine(
        Ids.newId(), no, kind, description, BigDecimal.ONE, amount, money(amount));
  }

  private String planName(UUID planId) {
    return plans.find(planId).map(Plan::name).orElse("Plan");
  }

  private Subscription require(UUID tenantId) {
    Subscription s =
        repo.ofTenant(tenantId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "SUBSCRIPTION_NOT_FOUND", "This business has no subscription"));
    if (!s.billable()) {
      throw ApiException.conflict(
          "BILLING_NOT_ACTIVE", "This subscription is " + s.status() + " and is not being billed");
    }
    return s;
  }

  private Plan planOnSale(UUID planId) {
    Plan plan =
        plans
            .find(planId)
            .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "No such plan"));
    if (!plan.sold()) {
      throw ApiException.conflict("PLAN_NOT_SOLD", "This plan is " + plan.status());
    }
    return plan;
  }

  private BigDecimal priceOf(Plan plan, String currency, LocalDate on) {
    return plans
        .priceOn(plan.id(), currency, on)
        .orElseThrow(
            () ->
                ApiException.conflict(
                    "PLAN_PRICE_MISSING",
                    "This plan has no price in " + currency + " in force on " + on));
  }

  private BigDecimal priceOrZero(Plan plan, String currency, LocalDate on) {
    return plans.priceOn(plan.id(), currency, on).orElse(BigDecimal.ZERO);
  }

  /** The next period's boundary. A month keeps the day of the month; a year keeps the date. */
  private static LocalDate advance(LocalDate from, String interval) {
    return Plans.YEAR.equals(interval) ? from.plusYears(1) : from.plusMonths(1);
  }

  private static BigDecimal money(BigDecimal amount) {
    return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String upper(String s) {
    return s == null ? "" : s.strip().toUpperCase(java.util.Locale.ROOT);
  }

  /** Changes one thing about a subscription and leaves the rest alone. */
  private static final class Builder {
    private final Subscription from;
    private UUID planId;
    private String status;
    private BigDecimal price;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate trialEnd;
    private boolean trialEndSet;
    private UUID pendingPlanId;
    private boolean pendingSet;
    private Buyer buyer;
    private Instant cancelledAt;

    Builder(Subscription from) {
      this.from = from;
    }

    Builder planId(UUID id) {
      planId = id;
      return this;
    }

    Builder status(String s) {
      status = s;
      return this;
    }

    Builder price(BigDecimal p) {
      price = p;
      return this;
    }

    Builder period(LocalDate start, LocalDate end) {
      periodStart = start;
      periodEnd = end;
      return this;
    }

    Builder trialEnd(LocalDate end) {
      trialEnd = end;
      trialEndSet = true;
      return this;
    }

    Builder pendingPlanId(UUID id) {
      pendingPlanId = id;
      pendingSet = true;
      return this;
    }

    Builder buyer(Buyer b) {
      buyer = b;
      return this;
    }

    Builder cancelledAt(Instant at) {
      cancelledAt = at;
      return this;
    }

    Subscription build() {
      return new Subscription(
          from.id(),
          from.tenantId(),
          planId == null ? from.planId() : planId,
          status == null ? from.status() : status,
          price == null ? from.priceAmount() : price,
          from.currency(),
          from.billingInterval(),
          periodStart == null ? from.periodStart() : periodStart,
          periodEnd == null ? from.periodEnd() : periodEnd,
          trialEndSet ? trialEnd : from.trialEnd(),
          pendingSet ? pendingPlanId : from.pendingPlanId(),
          from.cancelAtPeriodEnd(),
          buyer == null ? from.buyer() : buyer,
          from.billingEmail(),
          from.startedAt(),
          cancelledAt == null ? from.cancelledAt() : cancelledAt,
          from.createdAt(),
          Instant.now());
    }
  }
}
