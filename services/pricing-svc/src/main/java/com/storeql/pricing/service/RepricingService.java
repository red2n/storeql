package com.storeql.pricing.service;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.CompetitorPrice;
import com.storeql.pricing.domain.Domain.PriceList;
import com.storeql.pricing.domain.Domain.PriceListItem;
import com.storeql.pricing.domain.Domain.PriceZone;
import com.storeql.pricing.domain.Domain.RepricingProposal;
import com.storeql.pricing.domain.Domain.RepricingRule;
import com.storeql.pricing.domain.Domain.RepricingRun;
import com.storeql.pricing.domain.Repricing;
import com.storeql.pricing.domain.Repricing.Observation;
import com.storeql.pricing.dto.Dtos.AssignZoneStoresRequest;
import com.storeql.pricing.dto.Dtos.CreatePriceZoneRequest;
import com.storeql.pricing.dto.Dtos.CreateRepricingRuleRequest;
import com.storeql.pricing.dto.Dtos.RecordCompetitorPriceRequest;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.pricing.repo.RepricingRepository;
import com.storeql.service.Fx;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Price zones and competitor-driven repricing (03.x).
 *
 * <p>A zone groups the stores that price alike; a price list bound to it is what those stores
 * charge, and {@link PricingRepository#resolveBasePrice} prefers it. A rival's price is recorded as
 * seen, like for like in the business's own currency. A rule on a list turns the freshest rival
 * price into a proposal through {@link Repricing}; management applies it into the list or dismisses
 * it. Nothing here changes a price on its own: a run proposes, a person decides.
 */
@ApplicationScoped
public class RepricingService {

  private static final LocalDate EARLIEST_OBSERVATION = LocalDate.of(2000, 1, 1);
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  @Inject RepricingRepository repo;
  @Inject PricingRepository pricing;
  @Inject TenantProfiles profiles;
  @Inject AppliedPriceService appliedPrices;

  // ── Zones ──────────────────────────────────────────────────────────────────

  public PriceZone createZone(TenantContext ctx, CreatePriceZoneRequest req) {
    return repo.createZone(
        new PriceZone(
            Ids.newId(),
            ctx.tenantId(),
            req.name().trim(),
            req.description() == null || req.description().isBlank()
                ? null
                : req.description().trim(),
            List.of(),
            Instant.now()));
  }

  public List<PriceZone> listZones(TenantContext ctx) {
    return repo.findZones(ctx.tenantId());
  }

  public PriceZone getZone(TenantContext ctx, UUID id) {
    return repo.findZone(ctx.tenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound("PRICING_ZONE_NOT_FOUND", "price zone " + id + " not found"));
  }

  /** Replaces a zone's stores; each must be one of the business's own (tenant-svc says which). */
  public PriceZone assignStores(TenantContext ctx, UUID zoneId, AssignZoneStoresRequest req) {
    getZone(ctx, zoneId);
    Set<UUID> stores = new LinkedHashSet<>();
    for (String s : req.storeIds()) stores.add(Parsing.uuid(s, "storeIds"));
    if (!stores.isEmpty()) {
      TenantProfiles.Stores own = profiles.stores(ctx.tenantId(), null);
      for (UUID s : stores) {
        if (!own.has(s)) {
          throw ApiException.badRequest(
              "PRICING_ZONE_STORE_UNKNOWN", "store " + s + " is not one of this business's");
        }
      }
    }
    repo.assignStores(ctx.tenantId(), zoneId, stores);
    appliedPrices.catchUp(ctx.tenantId());
    return getZone(ctx, zoneId);
  }

  // ── Competitor prices ──────────────────────────────────────────────────────

  public CompetitorPrice record(TenantContext ctx, RecordCompetitorPriceRequest req) {
    return repo.recordCompetitorPrices(
            List.of(observation(ctx, req, CompetitorPrice.SOURCE_MANUAL)))
        .get(0);
  }

  public int recordBatch(TenantContext ctx, List<RecordCompetitorPriceRequest> rows) {
    List<CompetitorPrice> seen = new ArrayList<>(rows.size());
    for (RecordCompetitorPriceRequest r : rows) {
      seen.add(observation(ctx, r, CompetitorPrice.SOURCE_IMPORT));
    }
    return repo.recordCompetitorPrices(seen).size();
  }

  private CompetitorPrice observation(
      TenantContext ctx, RecordCompetitorPriceRequest req, String source) {
    String home = profiles.requireCurrency(ctx.tenantId());
    String currency =
        req.currency() == null || req.currency().isBlank()
            ? home
            : req.currency().trim().toUpperCase(Locale.ROOT);
    if (!home.equals(currency)) {
      throw ApiException.badRequest(
          "PRICING_COMPETITOR_CURRENCY_MISMATCH",
          "a competitor's price is recorded in "
              + home
              + ", the business's own currency, so rivals are compared like for like; got "
              + currency);
    }
    UUID zoneId = Parsing.optionalUuid(req.zoneId(), "zoneId");
    if (zoneId != null && repo.findZone(ctx.tenantId(), zoneId).isEmpty()) {
      throw ApiException.badRequest(
          "PRICING_ZONE_UNKNOWN", "price zone " + req.zoneId() + " is not one of this business's");
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate observedOn =
        req.observedOn() == null || req.observedOn().isBlank()
            ? today
            : Parsing.date(req.observedOn(), "observedOn");
    if (observedOn.isAfter(today) || observedOn.isBefore(EARLIEST_OBSERVATION)) {
      throw ApiException.badRequest(
          "PRICING_COMPETITOR_DATE_INVALID",
          "observedOn must be a day between 2000-01-01 and today; got " + observedOn);
    }
    return new CompetitorPrice(
        Ids.newId(),
        ctx.tenantId(),
        Parsing.uuid(req.variantId(), "variantId"),
        req.competitor().trim(),
        req.price(),
        currency,
        zoneId,
        observedOn,
        source,
        ctx.userId(),
        Instant.now());
  }

  public List<CompetitorPrice> listObservations(
      TenantContext ctx, String variantId, String zoneId, int limit) {
    return repo.findCompetitorPrices(
        ctx.tenantId(),
        Parsing.optionalUuid(variantId, "variantId"),
        Parsing.optionalUuid(zoneId, "zoneId"),
        limit);
  }

  // ── Rules ──────────────────────────────────────────────────────────────────

  public RepricingRule createRule(TenantContext ctx, CreateRepricingRuleRequest req) {
    UUID priceListId = Parsing.uuid(req.priceListId(), "priceListId");
    PriceList list =
        pricing
            .findPriceList(ctx.tenantId(), priceListId)
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "PRICING_LIST_UNKNOWN",
                        "price list " + req.priceListId() + " is not one of this business's"));
    Repricing.Strategy strategy = strategy(req.strategy());
    BigDecimal value = req.value() == null ? BigDecimal.ZERO : req.value();
    if (strategy == Repricing.Strategy.UNDERCUT_PERCENT && value.compareTo(HUNDRED) >= 0) {
      throw ApiException.badRequest(
          "REPRICING_VALUE_INVALID", "an undercut percentage must be below 100; got " + value);
    }
    Repricing.Rounding rounding = rounding(req.rounding());
    int maxAge = req.maxAgeDays() == null ? 14 : req.maxAgeDays();
    if (maxAge < 1 || maxAge > 365) {
      throw ApiException.badRequest(
          "REPRICING_MAX_AGE_INVALID", "maxAgeDays must be between 1 and 365; got " + maxAge);
    }
    return repo.createRule(
        new RepricingRule(
            Ids.newId(),
            ctx.tenantId(),
            req.name().trim(),
            list.id(),
            list.zoneId(),
            new Repricing.Rule(strategy, value, req.floorPercent(), rounding, maxAge),
            true,
            Instant.now()));
  }

  private static Repricing.Strategy strategy(String text) {
    try {
      return Repricing.Strategy.valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "REPRICING_STRATEGY_INVALID",
          "strategy must be MATCH_LOWEST, UNDERCUT_PERCENT or UNDERCUT_AMOUNT; got " + text,
          List.of(),
          e);
    }
  }

  private static Repricing.Rounding rounding(String text) {
    if (text == null || text.isBlank()) return Repricing.Rounding.NONE;
    try {
      return Repricing.Rounding.valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "REPRICING_ROUNDING_INVALID",
          "rounding must be NONE or ENDING_99; got " + text,
          List.of(),
          e);
    }
  }

  public List<RepricingRule> listRules(TenantContext ctx) {
    return repo.findRules(ctx.tenantId());
  }

  /**
   * Runs a rule: for every variant priced on its list that a rival has been seen for recently, the
   * lowest fresh rival price through the rule's arithmetic. A change opens (or refreshes) a
   * proposal; a variant with nothing to propose any more has its open proposal withdrawn.
   */
  public RepricingRun run(TenantContext ctx, UUID ruleId) {
    RepricingRule rule =
        repo.findRule(ctx.tenantId(), ruleId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "REPRICING_RULE_NOT_FOUND", "repricing rule " + ruleId + " not found"));
    PriceList list =
        pricing
            .findPriceList(ctx.tenantId(), rule.priceListId())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "REPRICING_NO_PRICE_LIST", "the rule's price list no longer exists"));
    int minorUnits = Fx.minorUnits(list.currency());
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    int maxAge = rule.rule().maxAgeDays();
    Map<UUID, BigDecimal> current = repo.currentPrices(ctx.tenantId(), list.id());
    Map<UUID, List<Observation>> seen =
        repo.observationsFor(ctx.tenantId(), list.zoneId(), today.minusDays(maxAge));
    List<RepricingProposal> proposals = new ArrayList<>();
    List<UUID> proposedVariants = new ArrayList<>();
    int examined = 0;
    for (Map.Entry<UUID, BigDecimal> e : current.entrySet()) {
      List<Observation> rivals = seen.get(e.getKey());
      if (rivals == null || rivals.isEmpty()) continue;
      examined++;
      Optional<Observation> lowest = Repricing.lowestFresh(rivals, today, maxAge);
      if (lowest.isEmpty()) continue;
      Optional<BigDecimal> proposed =
          Repricing.propose(rule.rule(), e.getValue(), lowest.get().price(), minorUnits);
      if (proposed.isEmpty()) continue;
      proposals.add(
          repo.upsertOpenProposal(
              new RepricingProposal(
                  Ids.newId(),
                  ctx.tenantId(),
                  rule.id(),
                  list.id(),
                  list.zoneId(),
                  e.getKey(),
                  e.getValue(),
                  lowest.get().competitor(),
                  lowest.get().price(),
                  lowest.get().observedOn(),
                  proposed.get(),
                  list.currency(),
                  RepricingProposal.PROPOSED,
                  Instant.now(),
                  null,
                  null)));
      proposedVariants.add(e.getKey());
    }
    repo.withdrawOpenProposalsExcept(ctx.tenantId(), rule.id(), proposedVariants);
    return new RepricingRun(rule.id(), examined, proposals.size(), proposals);
  }

  // ── Proposals ──────────────────────────────────────────────────────────────

  public List<RepricingProposal> listProposals(TenantContext ctx, String status, int limit) {
    String s =
        status == null || status.isBlank()
            ? RepricingProposal.PROPOSED
            : status.trim().toUpperCase(Locale.ROOT);
    if (!Set.of(RepricingProposal.PROPOSED, RepricingProposal.APPLIED, RepricingProposal.DISMISSED)
        .contains(s)) {
      throw ApiException.badRequest(
          "REPRICING_STATUS_INVALID",
          "status must be PROPOSED, APPLIED or DISMISSED; got " + status);
    }
    return repo.findProposals(ctx.tenantId(), s, limit);
  }

  private RepricingProposal getProposal(TenantContext ctx, UUID id) {
    return repo.findProposal(ctx.tenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "REPRICING_PROPOSAL_NOT_FOUND", "repricing proposal " + id + " not found"));
  }

  /**
   * Applies a proposal: the proposed price becomes the list's single-unit price for the variant.
   */
  public RepricingProposal apply(TenantContext ctx, UUID id) {
    RepricingProposal p = getProposal(ctx, id);
    if (!RepricingProposal.PROPOSED.equals(p.status())) {
      throw ApiException.conflict(
          "REPRICING_PROPOSAL_DECIDED",
          "proposal " + id + " was already " + p.status().toLowerCase(Locale.ROOT));
    }
    PriceListItem item =
        new PriceListItem(
            Ids.newId(),
            ctx.tenantId(),
            p.priceListId(),
            p.variantId(),
            p.proposedPrice(),
            BigDecimal.ONE,
            Instant.now(),
            Instant.now());
    RepricingProposal decided =
        repo.decide(
            p,
            RepricingProposal.APPLIED,
            ctx.userId(),
            item,
            Events.priceChanged(ctx.tenantId(), p.priceListId()));
    appliedPrices.catchUp(ctx.tenantId());
    return decided;
  }

  public RepricingProposal dismiss(TenantContext ctx, UUID id) {
    RepricingProposal p = getProposal(ctx, id);
    if (!RepricingProposal.PROPOSED.equals(p.status())) {
      throw ApiException.conflict(
          "REPRICING_PROPOSAL_DECIDED",
          "proposal " + id + " was already " + p.status().toLowerCase(Locale.ROOT));
    }
    return repo.decide(p, RepricingProposal.DISMISSED, ctx.userId(), null, null);
  }
}
