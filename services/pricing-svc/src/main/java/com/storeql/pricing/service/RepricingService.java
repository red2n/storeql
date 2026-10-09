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
import com.storeql.web.ErrorCodes;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

  /** The decimal places {@code competitor_prices.price} (NUMERIC(19,4)) keeps. */
  static final int OBSERVED_PRICE_PLACES = 4;

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
        // An observation, not a price the business sets: kept as seen at the column's four places,
        // finer than the currency if that is what was seen; a fifth place is refused, not rounded.
        rivalPriceIn(req.price(), home),
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
    // The business's currency is read only for an amount: a percentage, or nothing at all
    // (MATCH_LOWEST), is no money. The rival's price the amount comes off is in that currency.
    BigDecimal value =
        ruleValueIn(
            strategy,
            req.value() == null ? BigDecimal.ZERO : req.value(),
            strategy == Repricing.Strategy.UNDERCUT_AMOUNT
                ? profiles.requireCurrency(ctx.tenantId())
                : null);
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

  /**
   * A rival's price as it is kept: an observation of what a competitor charges, not a price the
   * business sets, so it is kept as seen at the column's four places and may be finer than the
   * business's currency (forecourt fuel to a tenth of a penny; intent/repricing-automation.md). It
   * is never rounded: a price that fits within the currency's units is shown at them ({@code 8.5}
   * pounds as {@code 8.50}), a finer one as it was seen ({@code 1.4599}), and one finer than the
   * column holds is refused, because the database would round it without a word.
   *
   * @param price what the rival was seen to charge
   * @param currency the business's own currency, whose minor units a price that fits is shown at
   * @throws ApiException 400 {@code VALIDATION_FAILED} for a price of more than {@link
   *     #OBSERVED_PRICE_PLACES} decimals, or more whole digits than the column holds
   */
  static BigDecimal rivalPriceIn(BigDecimal price, String currency) {
    PricingService.requireWholeDigits(price, "price", PricingService.FOUR_PLACE_WHOLE_DIGITS);
    BigDecimal seen = price.stripTrailingZeros();
    if (seen.scale() > OBSERVED_PRICE_PLACES) {
      // The price as written, by toString: 1E-80000000 in full is eighty million digits.
      String why =
          "price "
              + price
              + " has more decimals than a rival's price is kept to (four places): it is refused,"
              + " never rounded";
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, why, List.of("price: " + why));
    }
    int units = Fx.minorUnits(currency);
    return seen.scale() < units ? seen.setScale(units, RoundingMode.UNNECESSARY) : seen;
  }

  /**
   * A rule's {@code value} as it is handed to the column. For UNDERCUT_AMOUNT it is an amount taken
   * off the rival's price, so it is returned at the business's currency's minor units, and a value
   * finer than they are is refused by name, not rounded. For UNDERCUT_PERCENT (a percentage) and
   * MATCH_LOWEST (no value) it is no money and is returned as typed. For any strategy, a value of
   * more than {@link PricingService#FOUR_PLACE_WHOLE_DIGITS} whole digits is refused.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} for an amount with more decimals than the
   *     currency has, or any value with more whole digits than the column holds
   */
  static BigDecimal ruleValueIn(Repricing.Strategy strategy, BigDecimal value, String currency) {
    if (strategy == Repricing.Strategy.UNDERCUT_AMOUNT) {
      return PricingService.amountIn(
          value, currency, "value", PricingService.FOUR_PLACE_WHOLE_DIGITS);
    }
    PricingService.requireWholeDigits(value, "value", PricingService.FOUR_PLACE_WHOLE_DIGITS);
    return value;
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
    // The rival's price was seen some days ago and the rule only trusts a price that fresh; a
    // proposal that has outlived it is not applied, whatever the rival charges now. Dismissing
    // it, or running the rule again against what is seen today, are still open.
    RepricingRule rule =
        repo.findRule(ctx.tenantId(), p.ruleId())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "REPRICING_RULE_NOT_FOUND", "the proposal's rule no longer exists"));
    if (Repricing.isStale(
        p.observedOn(), LocalDate.now(ZoneOffset.UTC), rule.rule().maxAgeDays())) {
      throw ApiException.conflict(
          "PRICING_PROPOSAL_STALE",
          "the rival price behind proposal "
              + id
              + " was seen on "
              + p.observedOn()
              + ", more than "
              + rule.rule().maxAgeDays()
              + " days ago; run the rule again or dismiss the proposal");
    }
    // The list's own currency sets the price's scale, as every other list-price write is held to
    // it (priceIn): the proposal column keeps four places, and the list item's price column is
    // unconstrained NUMERIC (V1__init.sql), so 7.9900 written as it was read would be the price the
    // till and the basket quote say back.
    PriceList list =
        pricing
            .findPriceList(ctx.tenantId(), p.priceListId())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "REPRICING_NO_PRICE_LIST", "the proposal's price list no longer exists"));
    // A shelf price's VAT is the rate of the variant's category (intent/vat-inclusive-pricing.md):
    // a proposal is no way round the refusal a person typing the same price would meet.
    if (list.taxInclusive()
        && pricing.findProductVatCategory(ctx.tenantId(), p.variantId()).isEmpty()) {
      throw ApiException.conflict(
          "PRICING_VAT_CATEGORY_REQUIRED",
          "variant "
              + p.variantId()
              + " has no VAT category; give it one before a tax-inclusive price is applied to it");
    }
    PriceListItem item =
        new PriceListItem(
            Ids.newId(),
            ctx.tenantId(),
            p.priceListId(),
            p.variantId(),
            appliedPrice(p.proposedPrice(), list.currency()),
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

  /**
   * A proposed price as the list keeps it: at the list currency's own minor units (ISO 4217,
   * through {@link Fx#minorUnits}) — {@code 7.99} pounds, {@code 1234} yen, {@code 8.990} dinars —
   * however many places the proposal column read it back with.
   *
   * @throws IllegalStateException for a price finer than the currency, which no run proposes (a run
   *     proposes at the list currency's units, and a list's currency never changes)
   */
  static BigDecimal appliedPrice(BigDecimal proposed, String currency) {
    int units = Fx.minorUnits(currency);
    if (proposed.stripTrailingZeros().scale() > units) {
      throw new IllegalStateException(
          "proposed price " + proposed.toPlainString() + " is finer than " + currency + " has");
    }
    return proposed.setScale(units, java.math.RoundingMode.UNNECESSARY);
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
