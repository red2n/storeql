package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.domain.Plans;
import com.storeql.tenant.domain.Plans.Plan;
import com.storeql.tenant.domain.Subscriptions;
import com.storeql.tenant.domain.Subscriptions.Buyer;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.domain.Subscriptions.SubscriptionFile;
import com.storeql.tenant.repo.BillingRepository;
import com.storeql.tenant.repo.PlanRepository;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where a business stands with the platform (21.9): signed up, in a trial, paid up, behind, or
 * gone.
 *
 * <p>The lifecycle only. What a period is <em>worth</em>, and the invoice that says so, is {@link
 * BillingService}; this calls it at the two moments a lifecycle change owes money — a sign-up with
 * no trial, which is billed in advance the day it starts, and a plan change mid-period.
 *
 * <p><b>A trial ends in the first invoice, not in a charge at sign-up.</b> A trialing
 * subscription's period runs to the day the trial ends, and the billing run treats that day like
 * any other period end: it bills the first real period then. One code path, not two.
 */
@ApplicationScoped
public class SubscriptionService {

  @Inject BillingRepository repo;
  @Inject BillingService billing;
  @Inject PlanRepository plans;
  @Inject TenantRepository tenants;

  /**
   * Signs a business up to the plan it is on.
   *
   * <p>Its billing details are seeded from what the platform already knows: the legal name it
   * registered, and its country. Its VAT number is seeded too but <b>not</b> marked as checked —
   * the number it gave for e-invoicing is not evidence that it is real, and the reverse charge
   * rests on that evidence. Until somebody checks it the business is charged its own country's
   * rate, which is the safe side of the mistake.
   *
   * @param on the day it starts
   * @param billingEmail where notices about what it owes go — the owner's address at sign-up — or
   *     null when none is known, in which case dunning names the business rather than chasing it
   * @throws ApiException 409 {@code SUBSCRIPTION_EXISTS}, {@code TENANT_HAS_NO_PLAN}, {@code
   *     PLAN_PRICE_MISSING}
   */
  public SubscriptionFile start(UUID tenantId, LocalDate on, UUID actorId, String billingEmail) {
    if (repo.ofTenant(tenantId).isPresent()) {
      throw ApiException.conflict(
          "SUBSCRIPTION_EXISTS", "This business is already signed up; change its plan instead");
    }
    Tenant tenant =
        tenants
            .findTenant(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No such business"));
    if (tenant.planId() == null) {
      throw ApiException.conflict(
          "TENANT_HAS_NO_PLAN",
          "This business is on no plan, so there is nothing to bill it for; put it on one first");
    }
    Plan plan =
        plans
            .find(tenant.planId())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "TENANT_HAS_NO_PLAN", "The plan this business is on no longer exists"));
    BigDecimal price =
        plans
            .priceOn(plan.id(), tenant.currency(), on)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "PLAN_PRICE_MISSING",
                        "This plan has no price in "
                            + tenant.currency()
                            + " in force on "
                            + on
                            + ", so what the business would owe is undefined"));

    boolean trialing = plan.trialDays() > 0;
    LocalDate trialEnd = trialing ? on.plusDays(plan.trialDays()) : null;
    LocalDate periodEnd = trialing ? trialEnd : advance(on, plan.billingInterval());
    Instant now = Instant.now();

    Subscription started =
        new Subscription(
            Ids.newId(),
            tenantId,
            plan.id(),
            trialing ? Subscriptions.TRIALING : Subscriptions.ACTIVE,
            price,
            tenant.currency(),
            plan.billingInterval(),
            on,
            periodEnd,
            trialEnd,
            null,
            false,
            new Buyer(
                tenant.legalName() == null ? tenant.name() : tenant.legalName(),
                null,
                null,
                null,
                null,
                tenant.country(),
                tenant.vatNumber(),
                null,
                null,
                null),
            blankToNull(billingEmail),
            now,
            null,
            now,
            now);
    repo.create(started);
    repo.record(
        Ids.newId(),
        tenantId,
        started.id(),
        Subscriptions.STARTED,
        trialing
            ? "on " + plan.code() + " with a trial to " + trialEnd
            : "on " + plan.code() + ", billed " + on + " to " + periodEnd,
        actorId);

    // No trial means the first period is owed now: billed in advance, like every period after it.
    if (!trialing) {
      billing.billFirstPeriod(started, on);
    }
    return file(tenantId);
  }

  /**
   * Ends a subscription when the period the business has paid for runs out.
   *
   * <p>Not now: it has paid to the end of the period, and taking the platform away early would be
   * keeping money for a service withdrawn. The billing run does the ending.
   *
   * @throws ApiException 409 {@code BILLING_NOT_ACTIVE}, {@code SUBSCRIPTION_ALREADY_ENDING}
   */
  public SubscriptionFile cancelAtPeriodEnd(UUID tenantId, String reason, UUID actorId) {
    Subscription s = billable(tenantId);
    if (s.cancelAtPeriodEnd()) {
      throw ApiException.conflict(
          "SUBSCRIPTION_ALREADY_ENDING", "This subscription already ends on " + s.periodEnd());
    }
    repo.save(with(s, true, s.pendingPlanId()));
    repo.record(
        Ids.newId(),
        tenantId,
        s.id(),
        Subscriptions.CANCEL_SCHEDULED,
        "ends on " + s.periodEnd() + (reason == null || reason.isBlank() ? "" : ": " + reason),
        actorId);
    return file(tenantId);
  }

  /**
   * Takes back a cancellation while the period it would have ended in is still running.
   *
   * @throws ApiException 409 {@code SUBSCRIPTION_NOT_ENDING}
   */
  public SubscriptionFile keepGoing(UUID tenantId, UUID actorId) {
    Subscription s = billable(tenantId);
    if (!s.cancelAtPeriodEnd()) {
      throw ApiException.conflict(
          "SUBSCRIPTION_NOT_ENDING",
          "This subscription is not set to end, so there is nothing to undo");
    }
    repo.save(with(s, false, s.pendingPlanId()));
    repo.record(
        Ids.newId(),
        tenantId,
        s.id(),
        Subscriptions.RENEWED,
        "the cancellation was withdrawn; it renews on " + s.periodEnd(),
        actorId);
    return file(tenantId);
  }

  /**
   * Drops a scheduled downgrade, leaving the business on what it has.
   *
   * @throws ApiException 409 {@code NO_PLAN_CHANGE_PENDING}
   */
  public SubscriptionFile dropPendingChange(UUID tenantId, UUID actorId) {
    Subscription s = billable(tenantId);
    if (s.pendingPlanId() == null) {
      throw ApiException.conflict("NO_PLAN_CHANGE_PENDING", "No plan change is waiting");
    }
    repo.save(with(s, s.cancelAtPeriodEnd(), null));
    repo.record(
        Ids.newId(),
        tenantId,
        s.id(),
        Subscriptions.RENEWED,
        "the scheduled plan change was dropped",
        actorId);
    return file(tenantId);
  }

  /**
   * Sets where the business is, for billing, and the name its invoices carry.
   *
   * <p>Invoices already issued keep the address they were issued with: they snapshot both sides on
   * the day, which is the whole reason an invoice still explains itself years later.
   *
   * @throws ApiException 409 {@code BILLING_NOT_ACTIVE}
   */
  public SubscriptionFile setDetails(
      UUID tenantId, com.storeql.tenant.dto.BillingDtos.BuyerRequest req, UUID actorId) {
    Subscription s = require(tenantId);
    Buyer before = s.buyer();
    Buyer after = com.storeql.tenant.mapper.BillingMappers.merge(before, req);
    // Where notices go (SJ-D72): the request's address when it gives one, else unchanged. The
    // owner's address from sign-up stands until the business names another, never cleared by an
    // update that did not mention it — a business with no address cannot be told it is late.
    String email =
        blankToNull(req.billingEmail()) == null ? s.billingEmail() : req.billingEmail().strip();
    repo.save(rebuild(s, after, email, s.cancelAtPeriodEnd(), s.pendingPlanId()));

    String detail = "billing details set for " + after.country();
    if (!java.util.Objects.equals(email, s.billingEmail())) {
      detail += "; notices go to " + email;
    }
    if (before.vatChecked() && !after.vatChecked()) {
      // Worth its own sentence in the history: the treatment on the next invoice changes because of
      // it, and somebody will ask why.
      detail += "; the VAT number changed, so the check against the old one no longer counts";
    }
    repo.record(Ids.newId(), tenantId, s.id(), Subscriptions.DETAILS_CHANGED, detail, actorId);
    return file(tenantId);
  }

  /**
   * Moves the business to another plan, now or at the end of the period.
   *
   * <p>Up now and down at the end is the standard split, and it is not arbitrary: an upgrade is had
   * immediately, so it is billed immediately; a downgrade gives back something already paid for, so
   * it waits for the period to run out rather than crediting time the business has used.
   *
   * @throws ApiException 400 {@code PLAN_CHANGE_WHEN_UNKNOWN}
   */
  public SubscriptionFile changePlan(
      UUID tenantId, com.storeql.tenant.dto.BillingDtos.PlanChangeRequest req, UUID actorId) {
    billable(tenantId);
    switch (req.when() == null ? "" : req.when().strip().toUpperCase(java.util.Locale.ROOT)) {
      case "NOW" -> billing.upgradeNow(tenantId, req.planId(), LocalDate.now(), actorId);
      case "PERIOD_END" -> billing.downgradeAtPeriodEnd(tenantId, req.planId(), actorId);
      default ->
          throw ApiException.badRequest(
              "PLAN_CHANGE_WHEN_UNKNOWN", "A plan change happens NOW or at PERIOD_END");
    }
    return file(tenantId);
  }

  /**
   * Records that the buyer's VAT number has been checked, and by what.
   *
   * <p>The check is the evidence the reverse charge rests on, so <b>who checked it and when</b> is
   * recorded, not merely that it is valid. An audit asks for the date. Calling VIES itself is a
   * seam, like the e-invoicing networks: until there is a contract, {@code SIMULATED} stands for a
   * check that has not really left the building and says so.
   *
   * @param vatNumber the number as it will print on the invoice
   * @param source VIES, MANUAL or SIMULATED
   * @throws ApiException 400 {@code VAT_CHECK_SOURCE_UNKNOWN}
   */
  public SubscriptionFile recordVatCheck(
      UUID tenantId, String vatNumber, String source, UUID actorId) {
    Subscription s = require(tenantId);
    if (!Subscriptions.VAT_CHECK_SOURCES.contains(source)) {
      throw ApiException.badRequest(
          "VAT_CHECK_SOURCE_UNKNOWN",
          "A VAT check comes from " + String.join(", ", Subscriptions.VAT_CHECK_SOURCES));
    }
    if (vatNumber == null || vatNumber.isBlank()) {
      throw ApiException.badRequest(
          "VAT_NUMBER_REQUIRED", "There is no number to record a check against");
    }
    Buyer b = s.buyer();
    Buyer checked =
        new Buyer(
            b.name(),
            b.line1(),
            b.line2(),
            b.city(),
            b.postcode(),
            b.country(),
            vatNumber.strip().toUpperCase(java.util.Locale.ROOT),
            Instant.now(),
            actorId,
            source);
    repo.save(replaceBuyer(s, checked));
    repo.record(
        Ids.newId(),
        tenantId,
        s.id(),
        Subscriptions.VAT_CHECKED,
        "VAT number " + checked.vatNumber() + " checked against " + source,
        actorId);
    return file(tenantId);
  }

  /** Where a business stands, with its plan's name and everything that has happened to it. */
  public SubscriptionFile file(UUID tenantId) {
    Subscription s = require(tenantId);
    Plan plan = plans.find(s.planId()).orElse(null);
    return new SubscriptionFile(
        s,
        plan == null ? null : plan.code(),
        plan == null ? null : plan.name(),
        repo.events(tenantId, s.id()));
  }

  // ── small things ────────────────────────────────────────────────────────────

  private Subscription require(UUID tenantId) {
    return repo.ofTenant(tenantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "SUBSCRIPTION_NOT_FOUND", "This business is not signed up to anything"));
  }

  private Subscription billable(UUID tenantId) {
    Subscription s = require(tenantId);
    if (!s.billable()) {
      throw ApiException.conflict(
          "BILLING_NOT_ACTIVE", "This subscription is " + s.status() + " and is not being billed");
    }
    return s;
  }

  private static Subscription with(Subscription s, boolean cancelAtPeriodEnd, UUID pendingPlanId) {
    return rebuild(s, s.buyer(), s.billingEmail(), cancelAtPeriodEnd, pendingPlanId);
  }

  private static Subscription replaceBuyer(Subscription s, Buyer buyer) {
    return rebuild(s, buyer, s.billingEmail(), s.cancelAtPeriodEnd(), s.pendingPlanId());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static Subscription rebuild(
      Subscription s,
      Buyer buyer,
      String billingEmail,
      boolean cancelAtPeriodEnd,
      UUID pendingPlanId) {
    return new Subscription(
        s.id(),
        s.tenantId(),
        s.planId(),
        s.status(),
        s.priceAmount(),
        s.currency(),
        s.billingInterval(),
        s.periodStart(),
        s.periodEnd(),
        s.trialEnd(),
        pendingPlanId,
        cancelAtPeriodEnd,
        buyer,
        billingEmail,
        s.startedAt(),
        s.cancelledAt(),
        s.createdAt(),
        Instant.now());
  }

  private static LocalDate advance(LocalDate from, String interval) {
    return Plans.YEAR.equals(interval) ? from.plusYears(1) : from.plusMonths(1);
  }
}
