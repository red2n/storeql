package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.Entitlements;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.domain.Meters.Meter;
import com.storeql.tenant.domain.Meters.MeterPrice;
import com.storeql.tenant.domain.Meters.PlanMeter;
import com.storeql.tenant.domain.Plans;
import com.storeql.tenant.domain.Plans.Entitlement;
import com.storeql.tenant.domain.Plans.Grant;
import com.storeql.tenant.domain.Plans.Plan;
import com.storeql.tenant.domain.Plans.PlanFile;
import com.storeql.tenant.domain.Plans.Price;
import com.storeql.tenant.domain.Plans.Usage;
import com.storeql.tenant.dto.PlanDtos;
import com.storeql.tenant.repo.PlanRepository;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Plans and packaging (21.8): the platform's price list, and which plan a business is on.
 *
 * <p>Two rules run through all of it. A plan that anybody is on is never deleted, only retired —
 * the business that bought it keeps what it bought. And a business is never put on a plan it
 * already does not fit: the refusal names what is over, because silently letting a business exceed
 * a limit it is now told it has is worse than saying no.
 */
@ApplicationScoped
public class PlanService {

  private static final Logger LOG = System.getLogger(PlanService.class.getName());

  /** A code is what a person types and an API sends: short, and nothing that needs escaping. */
  private static final int MAX_CODE = 40;

  @Inject PlanRepository repo;
  @Inject TenantRepository tenants;

  // ── the price list ──────────────────────────────────────────────────────────

  /** Every plan, for the platform's own console. */
  public List<PlanFile> all() {
    return repo.list(false, false).stream().map(this::file).toList();
  }

  /** The plans a visitor may read: sold, and meant to be seen. */
  public List<PlanFile> published() {
    return repo.list(true, true).stream().map(this::file).toList();
  }

  public PlanFile get(UUID id) {
    return file(require(id));
  }

  private PlanFile file(Plan p) {
    return new PlanFile(
        p, repo.prices(p.id()), repo.grants(p.id()), repo.meters(p.id()), repo.meterPrices(p.id()));
  }

  /**
   * Writes a new plan, in draft: a plan is sold only once somebody activates it, so a half-written
   * price list is never on offer.
   *
   * @throws ApiException 400 {@code PLAN_CODE_INVALID}, {@code PLAN_INTERVAL_UNKNOWN}; 409 {@code
   *     PLAN_CODE_TAKEN}
   */
  public PlanFile create(UUID actorId, PlanDtos.PlanRequest req) {
    String code = code(req.code());
    if (repo.findByCode(code).isPresent()) {
      throw ApiException.conflict("PLAN_CODE_TAKEN", "A plan already goes by " + code);
    }
    Instant now = Instant.now();
    Plan plan =
        new Plan(
            Ids.newId(),
            code,
            req.name().strip(),
            blankToNull(req.description()),
            Plans.DRAFT,
            interval(req.billingInterval()),
            req.trialDays() == null ? 0 : req.trialDays(),
            false,
            req.isPublic() == null || req.isPublic(),
            req.sortOrder() == null ? 0 : req.sortOrder(),
            actorId,
            now,
            now);
    return file(repo.create(plan));
  }

  /**
   * Changes a plan's name, description, interval, trial, visibility or order. A plan somebody is on
   * keeps its billing interval: changing it would change what they are charged for without anybody
   * agreeing to it.
   *
   * @throws ApiException 404 {@code PLAN_NOT_FOUND}; 409 {@code PLAN_INTERVAL_IN_USE}
   */
  public PlanFile update(UUID id, PlanDtos.PlanRequest req) {
    Plan plan = require(id);
    String wanted = interval(req.billingInterval());
    if (!wanted.equals(plan.billingInterval()) && repo.subscribers(id) > 0) {
      throw ApiException.conflict(
          "PLAN_INTERVAL_IN_USE",
          "Businesses are on this plan: how often they are billed cannot change under them");
    }
    repo.update(
        new Plan(
            id,
            plan.code(),
            req.name().strip(),
            blankToNull(req.description()),
            plan.status(),
            wanted,
            req.trialDays() == null ? plan.trialDays() : req.trialDays(),
            plan.isDefault(),
            req.isPublic() == null ? plan.isPublic() : req.isPublic(),
            req.sortOrder() == null ? plan.sortOrder() : req.sortOrder(),
            plan.createdBy(),
            plan.createdAt(),
            Instant.now()));
    return get(id);
  }

  /**
   * Puts a plan on sale. It must have a price in at least one currency first: a plan on sale with
   * no price is a promise with no number.
   *
   * @throws ApiException 409 {@code PLAN_ALREADY_SOLD}, {@code PLAN_HAS_NO_PRICE}
   */
  public PlanFile activate(UUID id) {
    Plan plan = require(id);
    if (plan.sold()) {
      throw ApiException.conflict("PLAN_ALREADY_SOLD", "This plan is already on sale");
    }
    if (repo.prices(id).isEmpty()) {
      throw ApiException.conflict("PLAN_HAS_NO_PRICE", "Give the plan a price before selling it");
    }
    if (!repo.moveStatus(id, plan.status(), Plans.ACTIVE)) {
      throw ApiException.conflict(
          "PLAN_CHANGED_MEANWHILE", "This plan was moved by somebody else just now");
    }
    return get(id);
  }

  /**
   * Takes a plan off sale. The businesses on it stay on it — retiring a plan is not taking it away
   * from those who bought it — and a retired plan that was the default leaves the platform with
   * none until another is named.
   *
   * @throws ApiException 409 {@code PLAN_NOT_SOLD}
   */
  public PlanFile retire(UUID id) {
    Plan plan = require(id);
    // Only a plan on sale can be taken off it. Moving from whatever it happens to be would make
    // retiring a retired plan succeed, which reads as "done" and means nothing happened.
    if (!plan.sold()) {
      throw ApiException.conflict("PLAN_NOT_SOLD", "This plan is " + plan.status());
    }
    if (!repo.moveStatus(id, Plans.ACTIVE, Plans.RETIRED)) {
      throw ApiException.conflict(
          "PLAN_CHANGED_MEANWHILE", "This plan was moved by somebody else just now");
    }
    return get(id);
  }

  /**
   * Names the plan a business that signs up is put on.
   *
   * @throws ApiException 409 {@code PLAN_NOT_SOLD} for a plan that is not on sale
   */
  public PlanFile makeDefault(UUID id) {
    Plan plan = require(id);
    if (Plans.SANDBOX_CODE.equalsIgnoreCase(plan.code())) {
      throw ApiException.conflict(
          "PLAN_SANDBOX_ONLY", "The SANDBOX plan is for sandboxes; no business starts on it");
    }
    if (!repo.makeDefault(id)) {
      throw ApiException.conflict(
          "PLAN_NOT_SOLD", "Only a plan on sale can be the one a new business starts on");
    }
    return get(id);
  }

  /**
   * Sets a plan's price in one currency from a date. Prices are never edited in place: an invoice
   * raised under the old price must still be explicable next year.
   *
   * @throws ApiException 400 {@code CURRENCY_INVALID}, {@code PLAN_PRICE_DATE_INVALID}
   */
  public PlanFile setPrice(UUID id, UUID actorId, PlanDtos.PriceRequest req) {
    require(id);
    String currency = currency(req.currency());
    LocalDate from = day(req.effectiveFrom());
    repo.setPrice(new Price(Ids.newId(), id, currency, req.amount(), from, actorId, Instant.now()));
    return get(id);
  }

  /**
   * Sets what a plan includes, whole: a key left out is one the plan no longer names, and a key the
   * platform does not enforce is refused rather than promised.
   *
   * @throws ApiException 400 {@code PLAN_ENTITLEMENT_UNKNOWN}, {@code PLAN_ENTITLEMENT_SHAPE}
   */
  public PlanFile setGrants(UUID id, List<PlanDtos.GrantRequest> wanted) {
    require(id);
    List<Grant> grants = new ArrayList<>(wanted.size());
    for (PlanDtos.GrantRequest g : wanted) {
      Entitlement e =
          Plans.entitlement(g.key())
              .orElseThrow(
                  () ->
                      ApiException.badRequest(
                          "PLAN_ENTITLEMENT_UNKNOWN",
                          g.key()
                              + " is not something the platform enforces: "
                              + Plans.CATALOGUE.stream().map(Entitlement::key).sorted().toList()));
      if (e.limit() && g.enabled() != null) {
        throw ApiException.badRequest(
            "PLAN_ENTITLEMENT_SHAPE", e.key() + " is a number, not a yes or no");
      }
      if (!e.limit() && g.limitValue() != null) {
        throw ApiException.badRequest(
            "PLAN_ENTITLEMENT_SHAPE", e.key() + " is a yes or no, not a number");
      }
      grants.add(
          new Grant(
              e.key(),
              e.limit() ? g.limitValue() : null,
              e.limit() ? null : g.enabled() != null && g.enabled()));
    }
    repo.setGrants(id, grants);
    return get(id);
  }

  // ── metered usage (21.10) ───────────────────────────────────────────────────

  /**
   * Sets what a plan includes of each meter, whole: a meter left out is one the plan does not name,
   * which leaves it unlimited and uncharged.
   *
   * @throws ApiException 400 {@code PLAN_METER_UNKNOWN} for something the platform does not count,
   *     {@code PLAN_METER_NOT_REFUSABLE} for a hard ceiling on a meter that must never be refused,
   *     {@code PLAN_METER_HARD_UNLIMITED} for a hard ceiling with nothing included, {@code
   *     PLAN_METER_TWICE} for a meter named twice
   */
  public PlanFile setMeters(UUID id, List<PlanDtos.PlanMeterRequest> wanted) {
    require(id);
    List<PlanMeter> meters = new ArrayList<>(wanted.size());
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (PlanDtos.PlanMeterRequest m : wanted) {
      Meter meter =
          Meters.meter(m.meter())
              .orElseThrow(
                  () ->
                      ApiException.badRequest(
                          "PLAN_METER_UNKNOWN",
                          m.meter()
                              + " is not something the platform counts: "
                              + Meters.CATALOGUE.stream().map(Meter::key).toList()));
      if (!seen.add(meter.key())) {
        throw ApiException.badRequest("PLAN_METER_TWICE", meter.key() + " is named twice");
      }
      boolean hard = Boolean.TRUE.equals(m.hard());
      if (hard && !meter.refusable()) {
        // An order refused because the month's allowance ran out is a sale lost at the till and a
        // customer turned away. Beyond what is included, an order is charged, never refused.
        throw ApiException.badRequest(
            "PLAN_METER_NOT_REFUSABLE",
            meter.label() + " are never refused: price what is used beyond the allowance instead");
      }
      if (m.included() != null && m.included() < 0) {
        throw ApiException.badRequest(
            "PLAN_METER_INCLUDED_INVALID", "What a plan includes is a whole number, 0 or more");
      }
      if (hard && m.included() == null) {
        throw ApiException.badRequest(
            "PLAN_METER_HARD_UNLIMITED", "A hard ceiling needs a number to stop at");
      }
      meters.add(new PlanMeter(meter.key(), m.included(), hard));
    }
    repo.setMeters(id, meters);
    return get(id);
  }

  /**
   * Sets what one unit beyond a plan's allowance costs, in one currency, from a date. Never edited
   * in place, as a plan's price is not: a period is charged at the price in force the day it began.
   *
   * @throws ApiException 400 {@code PLAN_METER_UNKNOWN}, {@code CURRENCY_INVALID}, {@code
   *     PLAN_PRICE_DATE_INVALID}
   */
  public PlanFile setMeterPrice(UUID id, UUID actorId, PlanDtos.MeterPriceRequest req) {
    require(id);
    Meter meter =
        Meters.meter(req.meter())
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "PLAN_METER_UNKNOWN",
                        req.meter() + " is not something the platform counts"));
    repo.setMeterPrice(
        new MeterPrice(
            Ids.newId(),
            id,
            meter.key(),
            currency(req.currency()),
            req.unitAmount(),
            day(req.effectiveFrom()),
            actorId,
            Instant.now()));
    return get(id);
  }

  // ── which plan a business is on ─────────────────────────────────────────────

  /**
   * Puts a business on a plan, refusing one whose limits it already exceeds — and saying by how
   * much, so the answer is actionable rather than a door closed.
   *
   * @throws ApiException 404 {@code TENANT_NOT_FOUND}, {@code PLAN_NOT_FOUND}; 409 {@code
   *     PLAN_NOT_SOLD}, {@code PLAN_LIMIT_EXCEEDED_NOW}
   */
  public Plans.TenantPlan putOnPlan(UUID tenantId, UUID actorId, UUID planId, String reason) {
    Tenant tenant = requireTenant(tenantId);
    Plan plan = require(planId);
    if (!plan.sold() && !planId.equals(tenant.planId())) {
      throw ApiException.conflict("PLAN_NOT_SOLD", "This plan is " + plan.status());
    }
    // The sandbox plan is for sandboxes (22.8): a live business on it would trade unbilled on a
    // sandbox's allowances, and a sandbox on anything else would be billed for nothing real.
    if (Plans.SANDBOX_CODE.equalsIgnoreCase(plan.code()) != tenant.isSandbox()) {
      throw ApiException.conflict(
          "PLAN_SANDBOX_ONLY",
          tenant.isSandbox()
              ? "A sandbox stays on the SANDBOX plan"
              : "The SANDBOX plan is for a business's sandbox, never for the business itself");
    }
    List<Usage> over = usage(tenantId, planId).stream().filter(Usage::over).toList();
    if (!over.isEmpty()) {
      throw ApiException.conflict(
          "PLAN_LIMIT_EXCEEDED_NOW",
          "This business is already past what that plan allows: "
              + over.stream()
                  .map(u -> u.label() + " " + u.used() + " of " + u.limitValue())
                  .toList());
    }
    if (!repo.changeTenantPlan(
        tenantId, Optional.ofNullable(tenant.planId()), planId, actorId, blankToNull(reason))) {
      throw ApiException.conflict(
          "PLAN_CHANGED_MEANWHILE", "This business's plan changed while this was being decided");
    }
    return planOf(tenantId);
  }

  /** The plan a business is on, with each limit against what it is using. */
  public Plans.TenantPlan planOf(UUID tenantId) {
    Tenant tenant = requireTenant(tenantId);
    if (tenant.planId() == null) {
      // Every business that predates plans, and every business while the platform has no default.
      return new Plans.TenantPlan(null, List.of(), "This business is on no plan");
    }
    PlanFile file = get(tenant.planId());
    return new Plans.TenantPlan(file, usage(tenantId, tenant.planId()), null);
  }

  /**
   * Each limit of a plan against what the business is using. A count the owning service cannot give
   * is left unknown rather than guessed at — a limit enforced on a guess is worse than none.
   */
  public List<Usage> usage(UUID tenantId, UUID planId) {
    List<Grant> grants = repo.grants(planId);
    List<Usage> out = new ArrayList<>();
    for (Entitlement e : Plans.CATALOGUE) {
      if (!e.limit()) continue;
      Optional<Grant> grant = grants.stream().filter(g -> g.key().equals(e.key())).findFirst();
      if (grant.isEmpty()) continue;
      out.add(new Usage(e.key(), e.label(), grant.get().limitValue(), used(tenantId, e.key())));
    }
    return out;
  }

  /**
   * What this service can count itself; the rest is the owning service's to answer, and is left
   * unknown here rather than guessed at.
   */
  private Long used(UUID tenantId, String key) {
    return switch (key) {
      case Plans.STORES_MAX -> repo.storeCount(tenantId);
      case Plans.STAFF_MAX -> repo.staffCount(tenantId);
      default -> null;
    };
  }

  /**
   * The plan a business signing up is put on, when the platform has named one. Onboarding calls
   * this; a platform with no default leaves the business on none, which is unrestricted.
   */
  public Optional<UUID> defaultPlanId() {
    return repo.defaultPlan().map(Plan::id);
  }

  /**
   * Puts a business that has just signed up on the default plan, if there is one.
   *
   * <p>Best effort by design: the business exists either way, and one on no plan is unrestricted,
   * so failing to place it is safe where failing to create it is not. It is logged rather than
   * thrown so a platform with no default — or a moment when plans cannot be read — does not turn a
   * successful sign-up into an error for somebody who did nothing wrong.
   *
   * @return the plan it was put on, or empty
   */
  /**
   * Puts a business that is signing up on the plan it chose (21.13): one on sale, to the public.
   *
   * @throws ApiException 404 {@code PLAN_NOT_FOUND}; 409 {@code PLAN_NOT_SOLD} for a draft or a
   *     retired plan; 409 {@code PLAN_NOT_PUBLIC} for one the platform sells only by hand
   */
  public void putOnPlan(UUID tenantId, UUID planId) {
    Plan plan = requireChoosable(planId);
    repo.changeTenantPlan(tenantId, Optional.empty(), planId, null, "signed up on " + plan.code());
  }

  /**
   * The plan a sandbox sits on (22.8), seeded by migration and kept for sandboxes alone.
   *
   * @throws ApiException {@code 503 SANDBOX_PLAN_MISSING} when a deployment has lost it
   */
  public UUID requireSandboxPlanId() {
    return repo.findByCode(Plans.SANDBOX_CODE)
        .map(Plan::id)
        .orElseThrow(
            () ->
                new ApiException(
                    503,
                    "SANDBOX_PLAN_MISSING",
                    "This deployment has no SANDBOX plan; a sandbox cannot be made until it is restored",
                    List.of()));
  }

  /** The plan a business may choose at signup: on sale, and to the public. */
  public Plan requireChoosable(UUID planId) {
    Plan plan =
        repo.find(planId)
            .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "No such plan"));
    if (!plan.sold()) {
      throw ApiException.conflict("PLAN_NOT_SOLD", "This plan is " + plan.status());
    }
    if (!plan.isPublic()) {
      throw ApiException.conflict(
          "PLAN_NOT_PUBLIC", "This plan is sold by the platform directly, not chosen at signup");
    }
    return plan;
  }

  public Optional<UUID> putOnDefaultPlan(UUID tenantId) {
    try {
      Optional<UUID> planId = defaultPlanId();
      if (planId.isEmpty()) return Optional.empty();
      boolean placed =
          repo.changeTenantPlan(
              tenantId, Optional.empty(), planId.get(), null, "signed up on the default plan");
      return placed ? planId : Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "a new business was not put on a plan: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Refuses one more of something when the business's plan does not stretch to it, naming the
   * figures so the answer is actionable rather than a door closed.
   *
   * <p>These are the limits tenant-svc can answer on its own, because it owns the stores and the
   * staff assignments. A limit over something another service owns is that service's to enforce,
   * through {@code Entitlements}, for the same reason.
   *
   * @param what what is counted, in the plural, as a person would say it
   * @param used asked for only when there is a ceiling, so the count is paid for only when it
   *     matters
   * @throws ApiException 409 {@code PLAN_LIMIT_REACHED}
   */
  private void requireRoom(UUID tenantId, String key, String what, LongSupplier used) {
    Optional<UUID> planId = tenants.findTenant(tenantId).map(Tenant::planId);
    if (planId.isEmpty()) return;
    Optional<Grant> grant =
        repo.grants(planId.get()).stream().filter(g -> key.equals(g.key())).findFirst();
    if (grant.isEmpty() || grant.get().limitValue() == null) return;
    long limit = grant.get().limitValue();
    long have = used.getAsLong();
    if (have < limit) return;
    throw Entitlements.limitReached(limit, what, have);
  }

  /** Refuses another store the plan does not allow. */
  public void requireRoomForAnotherStore(UUID tenantId) {
    requireRoom(tenantId, Plans.STORES_MAX, "stores", () -> repo.storeCount(tenantId));
  }

  /**
   * Refuses another member of staff the plan does not allow.
   *
   * <p>Giving somebody who already works here a role at a second store is not a second person, so
   * it is never refused: the limit counts people, not assignments.
   */
  public void requireRoomForAnotherStaffMember(UUID tenantId, UUID userId) {
    if (repo.alreadyStaff(tenantId, userId)) return;
    requireRoom(tenantId, Plans.STAFF_MAX, "staff", () -> repo.staffCount(tenantId));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Plan require(UUID id) {
    return repo.find(id).orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "No such plan"));
  }

  private Tenant requireTenant(UUID tenantId) {
    return tenants
        .findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No such business"));
  }

  private static String code(String raw) {
    String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z0-9_-]{2," + MAX_CODE + "}")) {
      throw ApiException.badRequest(
          "PLAN_CODE_INVALID", "A plan's code is 2 to " + MAX_CODE + " of A-Z, 0-9, - and _");
    }
    return code;
  }

  private static String interval(String raw) {
    String interval = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (!Plans.INTERVALS.contains(interval)) {
      throw ApiException.badRequest("PLAN_INTERVAL_UNKNOWN", "A plan is billed by MONTH or YEAR");
    }
    return interval;
  }

  private static String currency(String raw) {
    String currency = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (!currency.matches("[A-Z]{3}")) {
      throw ApiException.badRequest("CURRENCY_INVALID", "A currency is a three-letter ISO code");
    }
    return currency;
  }

  private static LocalDate day(String raw) {
    if (raw == null || raw.isBlank()) return LocalDate.now(ZoneOffset.UTC);
    try {
      return LocalDate.parse(raw.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "PLAN_PRICE_DATE_INVALID", "effectiveFrom is an ISO date", List.of(), e);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
