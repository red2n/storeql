package com.storeql.pricing.service;

import com.storeql.pricing.domain.Domain.AppliedPrice;
import com.storeql.pricing.domain.Domain.PriceEvaluation;
import com.storeql.pricing.domain.Domain.PriorPrice;
import com.storeql.pricing.domain.Domain.Reduction;
import com.storeql.pricing.domain.Domain.ResolvedPrice;
import com.storeql.pricing.dto.Dtos.ResolvePriceRequest;
import com.storeql.pricing.repo.AppliedPriceRepository;
import com.storeql.pricing.repo.AppliedPriceRepository.Outcome;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The applied-price ledger (03.12): what each variant was offered at, recorded as the price engine
 * computes it, and the prior price of a reduction read back from it.
 */
@ApplicationScoped
public class AppliedPriceService {

  private static final Logger LOG = System.getLogger(AppliedPriceService.class.getName());

  /** The channels a shopper is offered a price on; a list for ALL reaches both. */
  static final List<String> CHANNELS = List.of("ONLINE", "POS");

  /** How long a claimed evaluation is held before another worker may take it again. */
  static final Duration LEASE = Duration.ofMinutes(2);

  /**
   * How far back the ledger is read: a progressive reduction may have begun long before its window.
   */
  static final Duration LOOKBACK = Duration.ofDays(400);

  /** How many evaluations one claim takes; a full batch means more are waiting. */
  public static final int BATCH = 50;

  /** How many of its own variant-level evaluations a write works through before it answers. */
  static final int CATCH_UP = 20;

  /** The most reductions listed, or weighed for the storefront's banner. */
  static final int REDUCTIONS_LIMIT = 2000;

  @Inject AppliedPriceRepository repo;
  @Inject PricingService pricing;

  /** Whether a tenant's storefront may advertise item reductions, as of its ledger and its law. */
  private record Verdict(Instant ledgerAt, PriorPrices.Rules rules, boolean advertisable) {}

  private final Map<UUID, Verdict> verdicts = new ConcurrentHashMap<>();

  /**
   * Works the due evaluations until none are left or the batch is spent.
   *
   * @return how many were completed
   */
  public int processDue() {
    return work(repo.claimDue(BATCH, LEASE));
  }

  /**
   * Records a tenant's variant-level changes straight after they commit, so a reduction does not
   * wait on the sweeper to stop being pending. Never fails the write that asked: whatever is left,
   * or refused, the sweeper takes.
   */
  public void catchUp(UUID tenantId) {
    try {
      // Claimed again while work is done: each variant's next change is claimable once the one
      // before it is recorded.
      int left = CATCH_UP;
      int done;
      do {
        done = work(repo.claimDueFor(tenantId, left, LEASE));
        left -= done;
      } while (done > 0 && left > 0);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "applied-price catch-up left to the sweeper: " + e.getMessage());
    }
  }

  private int work(List<PriceEvaluation> due) {
    int done = 0;
    for (PriceEvaluation e : due) {
      evaluate(e);
      repo.complete(e.id());
      done++;
    }
    return done;
  }

  /**
   * Records what a queued evaluation finds: for each variant it covers, on each channel,
   * business-wide and at each store a promotion is scoped to.
   *
   * @return how many ledger rows were appended
   */
  int evaluate(PriceEvaluation e) {
    List<UUID> variants =
        e.variantId() != null ? List.of(e.variantId()) : repo.pricedVariants(e.tenantId());
    List<UUID> stores = new ArrayList<>();
    stores.add(null);
    stores.addAll(repo.promotionStores(e.tenantId()));
    int appended = 0;
    for (UUID variant : variants) {
      List<AppliedPrice> offers = new ArrayList<>();
      for (String channel : CHANNELS) {
        for (UUID store : stores) {
          offers.add(offer(e, variant, channel, store));
        }
      }
      // Asked after the offers were computed: a change that landed meanwhile has queued by now.
      boolean overwritten = repo.overwrittenSince(e.tenantId(), variant, e.asOf());
      boolean again = false;
      for (AppliedPrice offer : offers) {
        Outcome outcome =
            repo.recordIfChanged(overwritten ? offer.withUncertainSince(e.asOf()) : offer);
        if (outcome != Outcome.UNCHANGED) appended++;
        again |= outcome == Outcome.OUT_OF_ORDER;
      }
      // Recorded out of order: evaluate the variant again, so the uncertain span it opened closes.
      if (again) repo.enqueue(e.tenantId(), variant, null, "RECHECK");
    }
    return appended;
  }

  private AppliedPrice offer(PriceEvaluation e, UUID variant, String channel, UUID store) {
    var req =
        new ResolvePriceRequest(
            variant.toString(),
            store == null ? null : store.toString(),
            channel,
            BigDecimal.ONE,
            null,
            null);
    try {
      ResolvedPrice offered = pricing.resolveAsRecorded(e.tenantId(), req, true, e.asOf());
      ResolvedPrice regular = pricing.resolveAsRecorded(e.tenantId(), req, false, e.asOf());
      return new AppliedPrice(
          null,
          e.tenantId(),
          variant,
          channel,
          store,
          true,
          offered.totalWithVat(),
          offered.unitPrice(),
          regular.totalWithVat(),
          offered.promotionApplied(),
          offered.currency(),
          e.asOf(),
          null,
          null,
          e.cause());
    } catch (ApiException x) {
      // No price in force, or no VAT rate to charge it with (SJ-D56): nothing is offered.
      if (x.status() != 404 && !PricingService.VAT_RATE_NOT_CONFIGURED.equals(x.code())) throw x;
      return new AppliedPrice(
          null,
          e.tenantId(),
          variant,
          channel,
          store,
          false,
          null,
          null,
          null,
          null,
          null,
          e.asOf(),
          null,
          null,
          e.cause());
    }
  }

  /**
   * The prior price of what is offered now, from the ledger of the same variant and channel — at
   * the store, where a store-scoped promotion has given it a ledger of its own. Pending while an
   * evaluation of the variant is due.
   *
   * @param channel ONLINE or POS; any other reads as ONLINE
   * @param progressive whether art.6a(5) applies to this offer
   */
  public PriorPrice priorPrice(
      UUID tenantId,
      UUID variantId,
      String channel,
      UUID storeId,
      BigDecimal price,
      BigDecimal regular,
      Instant at,
      boolean progressive) {
    if (repo.pendingFor(tenantId, variantId)) {
      return new PriorPrice(PriorPrices.PENDING, null, null, null, false);
    }
    String key = CHANNELS.contains(channel) ? channel : "ONLINE";
    return PriorPrices.of(
        ledgerAt(tenantId, variantId, key, storeId, at.minus(LOOKBACK)),
        price,
        regular,
        at,
        progressive);
  }

  /**
   * A store's ledger is kept only from the first promotion scoped to it. Before that its offer was
   * the business-wide one, so the business-wide rows stand for the time before its own began.
   */
  List<AppliedPrice> ledgerAt(
      UUID tenantId, UUID variantId, String channel, UUID storeId, Instant since) {
    List<AppliedPrice> business = repo.ledger(tenantId, variantId, channel, null, since);
    if (storeId == null) return business;
    List<AppliedPrice> store = repo.ledger(tenantId, variantId, channel, storeId, since);
    if (store.isEmpty()) return business;
    Instant opened = store.get(0).appliedFrom();
    List<AppliedPrice> merged = new ArrayList<>();
    for (AppliedPrice row : business) {
      if (row.appliedFrom().isBefore(opened)) merged.add(row);
    }
    merged.addAll(store);
    return merged;
  }

  /**
   * The reductions on offer on a channel as the ledger records them, each with its prior price.
   *
   * @param rulesAt art.6a's rules for an offer at a store, or with no store
   */
  public List<Reduction> reductions(
      UUID tenantId, String channel, Function<UUID, PriorPrices.Rules> rulesAt, int limit) {
    String key = CHANNELS.contains(channel) ? channel : "ONLINE";
    Instant now = Instant.now();
    AppliedPriceRepository.Pending pending = repo.pendingVariants(tenantId);
    Map<UUID, PriorPrices.Rules> rules = new HashMap<>();
    List<Reduction> out = new ArrayList<>();
    for (AppliedPrice row : repo.currentReductions(tenantId, key, limit)) {
      PriorPrices.Rules r = rules.computeIfAbsent(row.storeId(), rulesAt);
      PriorPrice prior =
          pending.covers(row.variantId())
              ? new PriorPrice(PriorPrices.PENDING, null, null, null, false)
              : PriorPrices.of(
                  ledgerAt(tenantId, row.variantId(), key, row.storeId(), now.minus(LOOKBACK)),
                  row.price(),
                  row.regularPrice(),
                  now,
                  r.progressive());
      out.add(
          new Reduction(
              row.variantId(),
              key,
              row.storeId(),
              row.price(),
              row.regularPrice(),
              row.promotionName(),
              row.currency(),
              prior,
              r.required()));
    }
    return out;
  }

  /**
   * Whether every item reduction on the storefront can be announced: a banner saying "20% off" may
   * not stand over a product whose own page cannot call its price reduced. Refused while anything
   * is pending, and when there are too many reductions to weigh. Kept until the ledger or the law
   * changes.
   */
  public boolean reductionsAnnounceable(UUID tenantId, Function<UUID, PriorPrices.Rules> rulesAt) {
    if (repo.pendingVariants(tenantId).any()) return false;
    Instant ledgerAt = repo.lastRecorded(tenantId);
    PriorPrices.Rules rules = rulesAt.apply(null);
    Verdict hit = verdicts.get(tenantId);
    if (hit != null && Objects.equals(hit.ledgerAt(), ledgerAt) && hit.rules().equals(rules)) {
      return hit.advertisable();
    }
    List<Reduction> all = reductions(tenantId, "ONLINE", rulesAt, REDUCTIONS_LIMIT + 1);
    boolean advertisable =
        all.size() <= REDUCTIONS_LIMIT
            && all.stream()
                .allMatch(r -> PriorPrices.announceable(true, r.required(), r.prior().status()));
    verdicts.put(tenantId, new Verdict(ledgerAt, rules, advertisable));
    return advertisable;
  }

  /**
   * Opens the ledger for every tenant with prices and no history, so its prior prices start today.
   *
   * @return how many tenants were opened
   */
  public int openLedgers() {
    List<UUID> tenants = repo.tenantsWithoutLedger();
    for (UUID t : tenants) repo.enqueue(t, null, null, "LEDGER_OPENED");
    return tenants.size();
  }

  /** The latest ledger rows for a variant, newest first. */
  public List<AppliedPrice> history(UUID tenantId, UUID variantId, int limit) {
    return repo.history(tenantId, variantId, Math.max(1, Math.min(500, limit)));
  }

  /** Evaluations due and not yet done for the tenant. */
  public int pending(UUID tenantId) {
    return repo.pending(tenantId);
  }
}
