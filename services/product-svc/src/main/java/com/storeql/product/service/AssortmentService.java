package com.storeql.product.service;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment;
import com.storeql.product.domain.Assortment.Change;
import com.storeql.product.domain.Assortment.Cluster;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.domain.Assortment.RangeOutcome;
import com.storeql.product.domain.Assortment.Review;
import com.storeql.product.repo.AssortmentRepository;
import com.storeql.product.repo.AssortmentRepository.ApplyResult;
import com.storeql.product.repo.AssortmentRepository.VariantProduct;
import com.storeql.service.Fx;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Range: which stores carry a line, decided with a date and a reason (07.18).
 *
 * <p>Six judgements shape this class.
 *
 * <p><b>Intent and state are separate.</b> Recording a change does not move the shelf; {@link
 * #applyDue} does, on the day the change said. That is what makes a range plannable — a buyer sets
 * a range three weeks out and nobody has to remember to type it on the morning.
 *
 * <p><b>A cluster's membership is read on the day it is applied, not the day it was decided.</b>
 * "All the Scottish shops" is meant to keep meaning that when a shop opens; a membership frozen at
 * decision time would quietly exclude it.
 *
 * <p><b>What cannot be expressed is refused loudly rather than guessed.</b> A product with no rows
 * in {@code product_stores} is sold everywhere, so there is no way to take it out of one store
 * without first saying which stores keep it — and product-svc does not own the list of stores to
 * fill in on the buyer's behalf. That change stays due and says so, because naming the stores is
 * enough to let it apply.
 *
 * <p><b>A store id is never taken on trust.</b> Stores are tenant-svc's, so a cluster, a change or
 * a review that names one asks tenant-svc whether it is the business's own before anything is
 * written. Without that, another business's store (or one that never existed) was kept as a member
 * and later applied into the range as though it were real. When tenant-svc cannot say, the store is
 * refused with {@code 503} rather than accepted unchecked.
 *
 * <p><b>A manager held to stores ranges at their stores only, and never to or from every store.</b>
 * The store a change or a closing review names is the business's ({@code 404}) and then theirs
 * ({@code 403 STORE_ACCESS_DENIED}); a cluster is for a caller held to none ({@code 403
 * BUSINESS_WIDE_ONLY}), because its membership is read on the day a change applies and may hold
 * stores they do not keep by then; and a line sold at every store is the business's to range
 * ({@code 403 BUSINESS_WIDE_ONLY}), as {@code PUT /admin/products/{id}/stores} holds it ({@link
 * CatalogueStores#requireChangeHeldTo}). A de-list that would leave a line at no store is not
 * refused them as a held manager: it is nobody's to make (the next paragraph), so they get the same
 * {@code 409 ASSORTMENT_LAST_STORE} an owner does, worded with what they can do about it ({@link
 * #lastStoreRemedy}). The change is marked as theirs and judged again on the range the sweep finds
 * ({@link Assortment#refusalToApply}).
 *
 * <p><b>A change nobody can apply is refused when it is recorded, and closed if it becomes one.</b>
 * A de-list of every store a line is sold at would leave it with no rows, which reads as every
 * store, so no range can make it apply as it reads. It is refused at the door ({@code 409
 * ASSORTMENT_LAST_STORE}), whoever asks — an owner no more than a manager held to stores — judged
 * on the line's whole plan ({@link Assortment#plan}): the range as it stands with every open change
 * of the line, whatever its day, walked as the sweep walks them, this one among them. So it is
 * refused when it would itself take the line out of its last store, and when it would leave a
 * de-list already recorded (for a later day, or waiting) doing so. A listing is never refused for
 * that: it is the later decision, listing elsewhere is the remedy the refusal itself gives, and no
 * recorded change can be withdrawn. The door cannot see everything that moves a range later — a
 * listing, a PUT of the product's stores, a store joining a cluster — so the sweep closes such a
 * de-list as refused, once, with its reason, in the transaction that judged it ({@link
 * Assortment.Refusal#staysDue}), rather than meet it due on every run for ever. A de-list of a line
 * sold everywhere is still recorded and waits, due, for the line to be given a range, as it always
 * has: that one the range can say once its stores are named.
 */
@ApplicationScoped
public class AssortmentService {

  @Inject AssortmentRepository repo;
  @Inject TenantProfiles profiles;

  // ── clusters ────────────────────────────────────────────────────────────────

  /**
   * Names a group of stores to range against.
   *
   * @throws ApiException 409 {@code CLUSTER_CODE_TAKEN} from the unique index, named by the
   *     repository
   */
  public Cluster addCluster(UUID tenantId, String code, String name, String note, UUID actorId) {
    Instant now = Instant.now();
    return repo.addCluster(
        new Cluster(
            Ids.newId(),
            tenantId,
            require(code, "CLUSTER_CODE_REQUIRED", "A cluster needs a code")
                .toUpperCase(Locale.ROOT),
            require(name, "CLUSTER_NAME_REQUIRED", "A cluster needs a name"),
            blankToNull(note),
            Assortment.ACTIVE,
            now,
            now,
            List.of()),
        actorId);
  }

  /**
   * Adds stores to a cluster. Already-present stores are left alone rather than refused.
   *
   * <p>All or nothing: every store is checked before the first is added, so a list that names one
   * store that is not the business's adds none of them; then, for a manager held to stores, each
   * must be theirs.
   *
   * @param ctx the caller
   * @throws ApiException 404 {@code CLUSTER_NOT_FOUND}; 409 {@code CLUSTER_RETIRED}; 404 {@code
   *     ASSORTMENT_STORE_NOT_FOUND} for a store that is not one of the business's; 403 {@code
   *     STORE_ACCESS_DENIED} for one the caller is not held to; 503 {@code
   *     TENANT_STORES_UNAVAILABLE} when tenant-svc cannot say which are
   */
  public Cluster addMembers(TenantContext ctx, UUID clusterId, List<UUID> storeIds) {
    UUID tenantId = ctx.requireTenantId();
    Cluster c = cluster(tenantId, clusterId);
    if (!c.active()) {
      throw ApiException.conflict("CLUSTER_RETIRED", "A retired cluster takes no more stores");
    }
    if (storeIds.isEmpty()) {
      throw ApiException.badRequest("CLUSTER_STORES_REQUIRED", "Name at least one store");
    }
    requireStores(ctx, storeIds);
    repo.addMembers(tenantId, clusterId, storeIds);
    return cluster(tenantId, clusterId);
  }

  public List<Cluster> clusters(UUID tenantId) {
    return repo.clusters(tenantId);
  }

  public Cluster cluster(UUID tenantId, UUID id) {
    return repo.cluster(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CLUSTER_NOT_FOUND", "No such cluster"));
  }

  // ── changes ─────────────────────────────────────────────────────────────────

  /**
   * Records a dated, reasoned range decision. Nothing moves until it is applied.
   *
   * <p>Everything is checked before it is written: the line is the business's; a cluster exists
   * and, for a manager held to stores, is not theirs to aim at; a store is the business's and then
   * the caller's; and a manager held to stores may not record a change to a line sold at every
   * store (see the class's fifth judgement), judged on the range the change meets on its day in the
   * line's plan; and nobody may record a de-list that would leave a line at no store, or leave a
   * de-list already recorded doing so (the sixth), judged on the line's whole plan.
   *
   * @param ctx the caller, who decided it
   * @param storeId one store, or null when the change is aimed at a cluster
   * @param clusterId a group of stores, or null when it is aimed at one store
   * @throws ApiException 400 when the action is unknown, when neither or both targets are given, or
   *     when there is no reason; 404 {@code PRODUCT_NOT_FOUND} for a line that is not the
   *     business's; 404 {@code CLUSTER_NOT_FOUND}; 404 {@code ASSORTMENT_STORE_NOT_FOUND} for a
   *     store that is not one of the business's; 403 {@code STORE_ACCESS_DENIED} for one the caller
   *     is not held to; 403 {@code BUSINESS_WIDE_ONLY} for a cluster or a line sold at every store,
   *     by a caller held to stores; 409 {@code ASSORTMENT_LAST_STORE} for a de-list that would
   *     leave the line at no store, or leave a de-list already recorded doing so, by anyone; 503
   *     {@code TENANT_STORES_UNAVAILABLE} when tenant-svc cannot say
   */
  public Change record(
      TenantContext ctx,
      UUID productId,
      UUID storeId,
      UUID clusterId,
      String action,
      LocalDate effectiveFrom,
      String reason) {
    UUID tenantId = ctx.requireTenantId();
    String a = action == null ? "" : action.strip().toUpperCase(Locale.ROOT);
    if (!Assortment.ACTIONS.contains(a)) {
      throw ApiException.badRequest(
          "ASSORTMENT_ACTION_UNKNOWN", "A range change is LIST or DELIST");
    }
    if ((storeId == null) == (clusterId == null)) {
      throw ApiException.badRequest(
          "ASSORTMENT_TARGET_REQUIRED", "Aim the change at one store or at one cluster, not both");
    }
    String why = require(reason, "ASSORTMENT_REASON_REQUIRED", "Say why the range is changing");
    List<UUID> range = repo.rangesOf(tenantId, List.of(productId)).get(productId);
    if (range == null) {
      throw ApiException.notFound("PRODUCT_NOT_FOUND", "No such product");
    }
    requireTarget(ctx, storeId, clusterId);
    LocalDate when = effectiveFrom == null ? LocalDate.now() : effectiveFrom;
    Change candidate =
        new Change(
            Ids.newId(),
            tenantId,
            productId,
            storeId,
            clusterId,
            a,
            when,
            why,
            ctx.requireUserId(),
            Instant.now(),
            null,
            null,
            heldToStores(ctx));
    Plans plans = new Plans(tenantId, List.of(productId));
    Map<UUID, Assortment.Fate> fates = plans.with(candidate, range);
    if (storeId != null) {
      requireHeldMayChange(ctx, productId, a, fates.get(candidate.id()).rangeMet(), storeId);
    }
    plans.requireSomeStoreLeft(ctx, candidate, range, fates);
    return repo.record(candidate);
  }

  public List<Change> changesOf(UUID tenantId, UUID productId) {
    return repo.changesOf(tenantId, productId);
  }

  public List<Change> due(UUID tenantId, LocalDate asOf) {
    return repo.due(tenantId, asOf == null ? LocalDate.now() : asOf);
  }

  /**
   * One change that could not be applied, and why.
   *
   * @param stillDue true when the change stays due, so fixing the cause is enough and nothing has
   *     to be re-entered; false when it was closed as refused for good (a de-list of a line's last
   *     stores: no range can make it apply as it reads), reported this once and never due again
   */
  public record NotApplied(
      UUID changeId, UUID productId, String code, String detail, boolean stillDue) {}

  /** What a sweep did: what it applied, and what it could not. */
  public record SweepResult(int applied, List<NotApplied> notApplied) {

    public SweepResult {
      notApplied = notApplied == null ? List.of() : List.copyOf(notApplied);
    }
  }

  /**
   * Applies every change dated on or before a day, oldest first.
   *
   * <p>Oldest first because order carries meaning: a line listed in March and de-listed in June
   * applied the other way round is on the shelf. Each change is its own transaction — one that
   * cannot be applied must not hold back the rest of the morning's work.
   *
   * <p>Three refusals leave the change due rather than swallowing it, because something can still
   * let it apply. A cluster with no stores yet would apply as a no-op and then never come back. A
   * DELIST against a product that is ranged everywhere cannot be expressed at all under {@code
   * product_stores}' convention that no rows means every store, and inventing the store list is
   * product-svc's to guess and tenant-svc's to know. A held manager's LIST of a line now sold
   * everywhere waits for the line to be narrowed again.
   *
   * <p>One closes it: a DELIST of the last stores a line is sold at ({@code
   * ASSORTMENT_LAST_STORE}). No range can make it apply as it reads, so it is closed as refused,
   * with its reason, in the transaction that judged it, reported this once ({@link
   * NotApplied#stillDue} false), and never due again.
   */
  public SweepResult applyDue(UUID tenantId, LocalDate asOf) {
    List<NotApplied> refused = new ArrayList<>();
    int applied = 0;
    // Cluster membership is read once per sweep, not once per change aimed at it.
    Map<UUID, List<UUID>> clusterStores = new HashMap<>();
    for (Change ch : repo.due(tenantId, asOf == null ? LocalDate.now() : asOf)) {
      List<UUID> stores =
          ch.storeId() != null
              ? List.of(ch.storeId())
              : clusterStores.computeIfAbsent(
                  ch.clusterId(), id -> cluster(tenantId, id).storeIds());
      if (stores.isEmpty()) {
        refused.add(notApplied(ch, Assortment.clusterEmpty()));
        continue;
      }
      // Judged on the range read inside the transaction that applies it, with the product row
      // locked: a range read before it may already have been replaced.
      ApplyResult result =
          repo.apply(
              tenantId, ch, stores, current -> Assortment.refusalToApply(ch, stores, current));
      if (result.applied()) {
        applied++;
      } else if (result.refusal() != null) {
        refused.add(notApplied(ch, result.refusal()));
      }
    }
    return new SweepResult(applied, refused);
  }

  private static NotApplied notApplied(Change ch, Assortment.Refusal r) {
    return new NotApplied(ch.id(), ch.productId(), r.code(), r.detail(), r.staysDue());
  }

  // ── range review ────────────────────────────────────────────────────────────

  /** Opens a review of one category over a trading period. */
  public Review openReview(
      UUID tenantId,
      UUID categoryId,
      String name,
      LocalDate from,
      LocalDate to,
      String note,
      UUID actorId) {
    if (from == null || to == null || !to.isAfter(from)) {
      throw ApiException.badRequest(
          "REVIEW_PERIOD_INVALID", "A review covers a period that ends after it starts");
    }
    return repo.openReview(
        new Review(
            Ids.newId(),
            tenantId,
            categoryId,
            require(name, "REVIEW_NAME_REQUIRED", "A review needs a name"),
            from,
            to,
            Assortment.OPEN,
            blankToNull(note),
            Instant.now(),
            null,
            List.of()),
        actorId);
  }

  /**
   * Adds the lines under review with the figures they are judged on.
   *
   * <p>The figures come from the caller because sales and margin belong to order-svc and
   * reporting-svc, and product-svc reads neither service's tables. A currency is required exactly
   * when money is given — a revenue with no currency is a number nobody can add up later — and it
   * is an ISO 4217 code. Revenue and margin are money in that currency, so each is held to its
   * minor units ({@link Fx#minorUnits}: none for JPY, two for GBP, three for KWD) and refused with
   * more, never rounded into a figure the buyer did not give.
   *
   * @throws ApiException 400 {@code REVIEW_LINES_REQUIRED}; 400 {@code REVIEW_LINE_CURRENCY} for
   *     money without a currency, a currency without money, or a code ISO 4217 does not know; 400
   *     {@code REVIEW_LINE_FIGURES} for negative units or revenue, or money with more decimal
   *     places than its currency has or too large to keep, or units sold with more than three
   *     decimal places or 10^11 or more; 404 {@code REVIEW_NOT_FOUND}; 409 when the review is
   *     closed
   */
  public Review addLines(UUID tenantId, UUID reviewId, List<Line> lines) {
    Review r = openOnly(tenantId, reviewId);
    if (lines.isEmpty()) {
      throw ApiException.badRequest("REVIEW_LINES_REQUIRED", "Name at least one line");
    }
    for (Line l : lines) {
      boolean money = l.revenue() != null || l.margin() != null;
      if (money == (l.currency() == null)) {
        throw ApiException.badRequest(
            "REVIEW_LINE_CURRENCY",
            "Give a currency with revenue or margin, and none without them");
      }
      if (l.currency() != null && !Fx.isCurrency(l.currency())) {
        throw ApiException.badRequest(
            "REVIEW_LINE_CURRENCY", "currency must be an ISO 4217 code such as EUR or JPY");
      }
      if (negative(l.unitsSold()) || negative(l.revenue())) {
        throw ApiException.badRequest(
            "REVIEW_LINE_FIGURES", "Units sold and revenue are never negative");
      }
      requireUnits(l.unitsSold());
      requireMoney(l.revenue(), "revenue", l.currency());
      requireMoney(l.margin(), "margin", l.currency());
    }
    repo.addLines(tenantId, r.id(), lines);
    return review(tenantId, reviewId);
  }

  /**
   * Records the buyer's decision on one line.
   *
   * <p>Dropping an own-brand line asks for a note. Not a refusal — a buyer may well be right — but
   * the remedy for a poor own-brand line is more often a reformulation or a price than a de-list,
   * and the margin lost is the business's own.
   */
  public Review decide(
      UUID tenantId, UUID reviewId, UUID variantId, String decision, String note, UUID actorId) {
    Review r = openOnly(tenantId, reviewId);
    String d = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
    if (!Assortment.DECISIONS.contains(d)) {
      throw ApiException.badRequest(
          "REVIEW_DECISION_UNKNOWN", "A line is KEEP, DELIST or INTRODUCE");
    }
    Line line =
        r.lines().stream()
            .filter(l -> l.variantId().equals(variantId))
            .findFirst()
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "REVIEW_LINE_NOT_FOUND", "That line is not in this review"));
    if (Assortment.needsJustification(withDecision(line, d)) && blankToNull(note) == null) {
      throw ApiException.badRequest(
          "REVIEW_OWN_BRAND_NOTE",
          "Dropping an own-brand line needs a note: the margin is the business's own");
    }
    if (!repo.decideLine(tenantId, reviewId, variantId, d, blankToNull(note))) {
      throw ApiException.notFound("REVIEW_LINE_NOT_FOUND", "That line is not in this review");
    }
    return review(tenantId, reviewId);
  }

  /** What closing a review produced. */
  public record ReviewResult(
      Review review, List<Change> changes, List<Assortment.LeftAlone> leftAlone) {

    public ReviewResult {
      changes = changes == null ? List.of() : List.copyOf(changes);
      leftAlone = leftAlone == null ? List.of() : List.copyOf(leftAlone);
    }
  }

  /**
   * Closes a review and records the range changes its decisions produce.
   *
   * <p>Every line must have a decision first. A review closed with lines unread would leave the
   * buyer believing a category was gone through when part of it was not.
   *
   * <p>The changes are recorded, not applied: they carry the review's own effective date, which is
   * usually the next reset, and the sweep puts them on the shelf on the day.
   *
   * <p>Its changes are held to what the caller could record one by one ({@link #record}). For a
   * manager held to stores, a change the review produces that they could not record — to a line
   * sold at every store — refuses the close as a whole, naming the line, and the review stays open
   * for a manager of the whole business to close: closing it with part of what it decided quietly
   * set aside would leave a record of decisions that never happen.
   *
   * <p>For anyone, a de-list it would produce that leaves a line at no store, or leaves a de-list
   * already recorded doing so, refuses the close the same way ({@code 409 ASSORTMENT_LAST_STORE},
   * naming the line), judged on the line's whole plan as {@link #record} judges it: recorded, it
   * could never be applied. Decide KEEP on that line and discontinue it, or list it elsewhere
   * first.
   *
   * <p>Judged in the order every door keeps: the request (400), then what it names — the review,
   * then the store or cluster — each the business's (404) before the caller's (403), and only then
   * the review's own state (409); then each change it would produce, who may (403) before what
   * nobody may (409); then the write. A wrong request is told what is wrong with it, and a caller
   * who may not close at that store is told so, before either hears whether the review could be
   * closed.
   *
   * @param ctx the caller, who closes it
   * @throws ApiException 400 {@code ASSORTMENT_TARGET_REQUIRED}; 404 {@code REVIEW_NOT_FOUND},
   *     {@code CLUSTER_NOT_FOUND}, {@code ASSORTMENT_STORE_NOT_FOUND}; 403 {@code
   *     STORE_ACCESS_DENIED} for a store the caller is not held to; 403 {@code BUSINESS_WIDE_ONLY}
   *     for a cluster, by a caller held to stores; 409 {@code REVIEW_NOT_OPEN}, {@code
   *     REVIEW_EMPTY}, {@code REVIEW_LINES_UNDECIDED}; 403 {@code BUSINESS_WIDE_ONLY} for a change
   *     a caller held to stores may not make; 409 {@code ASSORTMENT_LAST_STORE} for a de-list that
   *     would leave a line at no store; 503 {@code TENANT_STORES_UNAVAILABLE}
   */
  public ReviewResult close(
      TenantContext ctx, UUID reviewId, UUID storeId, UUID clusterId, LocalDate effectiveFrom) {
    UUID tenantId = ctx.requireTenantId();
    UUID actorId = ctx.requireUserId();
    // The request first (400); then what it names, each the business's (404) before the caller's
    // (403): the review, then the store or cluster; only then the review's own state (409).
    if ((storeId == null) == (clusterId == null)) {
      throw ApiException.badRequest(
          "ASSORTMENT_TARGET_REQUIRED", "Aim the review's changes at one store or at one cluster");
    }
    Review r = review(tenantId, reviewId);
    requireTarget(ctx, storeId, clusterId);
    if (!r.open()) {
      throw ApiException.conflict("REVIEW_NOT_OPEN", "This review has already been closed");
    }
    if (r.lines().isEmpty()) {
      throw ApiException.conflict("REVIEW_EMPTY", "A review with no lines decides nothing");
    }
    long undecided = r.undecided();
    if (undecided > 0) {
      throw ApiException.conflict(
          "REVIEW_LINES_UNDECIDED", undecided + " line(s) in this review still have no decision");
    }

    Map<UUID, UUID> productByVariant =
        repo.productsOfVariants(tenantId, r.lines().stream().map(Line::variantId).toList()).stream()
            .collect(Collectors.toMap(VariantProduct::variantId, VariantProduct::productId));
    Map<UUID, Integer> counts =
        repo.variantCounts(tenantId, productByVariant.values().stream().distinct().toList());
    RangeOutcome outcome = Assortment.rangeActions(r.lines(), productByVariant, counts);

    LocalDate when = effectiveFrom == null ? LocalDate.now() : effectiveFrom;
    Map<UUID, Integer> rankByProduct = ranks(r, productByVariant);
    Instant now = Instant.now();
    List<Change> produced = new ArrayList<>();
    for (Assortment.ProductAction a : outcome.actions()) {
      produced.add(
          new Change(
              Ids.newId(),
              tenantId,
              a.productId(),
              storeId,
              clusterId,
              a.action(),
              when,
              reasonFor(r, a, rankByProduct.get(a.productId())),
              actorId,
              now,
              null,
              r.id(),
              heldToStores(ctx)));
    }
    if (!produced.isEmpty()) {
      List<UUID> productIds = produced.stream().map(Change::productId).toList();
      Map<UUID, List<UUID>> ranges = repo.rangesOf(tenantId, productIds);
      Plans plans = new Plans(tenantId, productIds);
      Map<UUID, Map<UUID, Assortment.Fate>> fates = new HashMap<>();
      for (Change ch : produced) {
        fates.put(ch.id(), plans.with(ch, ranges.getOrDefault(ch.productId(), List.of())));
      }
      // Who may first (403), for every line; then what nobody may (409).
      if (storeId != null) {
        for (Change ch : produced) {
          requireHeldMayChange(
              ctx,
              ch.productId(),
              ch.action(),
              fates.get(ch.id()).get(ch.id()).rangeMet(),
              storeId);
        }
      }
      for (Change ch : produced) {
        plans.requireSomeStoreLeft(
            ctx, ch, ranges.getOrDefault(ch.productId(), List.of()), fates.get(ch.id()));
      }
    }
    if (!repo.close(tenantId, reviewId, actorId, produced)) {
      throw ApiException.conflict("REVIEW_NOT_OPEN", "This review has already been closed");
    }
    return new ReviewResult(review(tenantId, reviewId), produced, outcome.leftAlone());
  }

  public Review abandon(UUID tenantId, UUID reviewId) {
    openOnly(tenantId, reviewId);
    if (!repo.abandon(tenantId, reviewId)) {
      throw ApiException.conflict("REVIEW_NOT_OPEN", "This review has already been closed");
    }
    return review(tenantId, reviewId);
  }

  public Review review(UUID tenantId, UUID id) {
    return repo.review(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "No such review"));
  }

  public List<Review> reviews(UUID tenantId) {
    return repo.reviews(tenantId);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /**
   * Whether the caller is held to stores: a manager of some branches, not of the whole business.
   */
  private static boolean heldToStores(TenantContext ctx) {
    return !ctx.storeIds().isEmpty();
  }

  /**
   * Each store is the business's ({@code 404 ASSORTMENT_STORE_NOT_FOUND}), all of them asked about
   * first, and then the caller's ({@code 403 STORE_ACCESS_DENIED}): the order every door a manager
   * held to stores comes to keeps, so another business's store is not found whoever names it.
   */
  private void requireStores(TenantContext ctx, List<UUID> storeIds) {
    CatalogueStores.requireOwn(
        profiles, ctx.requireTenantId(), storeIds, CatalogueStores.ASSORTMENT_NOT_FOUND);
    storeIds.forEach(ctx::requireStoreAccess);
  }

  /**
   * What a change, or a closing review's changes, is aimed at: a cluster that exists and that the
   * caller may aim at, or a store that is the business's and the caller's.
   */
  private void requireTarget(TenantContext ctx, UUID storeId, UUID clusterId) {
    if (clusterId != null) {
      cluster(ctx.requireTenantId(), clusterId);
      CatalogueStores.requireBusinessWide(
          ctx,
          "A change aimed at a cluster is for an owner or a manager of the whole business: its"
              + " stores are read on the day it applies, and may by then include stores you do not"
              + " keep. Aim it at each of your stores instead.");
    }
    if (storeId != null) requireStores(ctx, List.of(storeId));
  }

  /**
   * What a manager held to stores may record against a line's range at one of their stores: not a
   * change to a line sold at every store (listing it at theirs would take it off every other shelf,
   * and de-listing it there cannot be said without naming every other store). The same rule {@code
   * PUT /admin/products/{id}/stores} holds them to ({@link CatalogueStores#requireChangeHeldTo}),
   * and the refusal names who can: an owner or a manager of the whole business. A caller held to no
   * store may record either; the sweep then judges what can be written.
   *
   * <p>A de-list that would leave the line at no store is not judged here: it is nobody's to make,
   * an owner's no more than theirs, so sending them to one would send them to someone who cannot
   * either. {@link Plans#requireSomeStoreLeft} refuses it, for everyone, with what does stop a line
   * being sold.
   *
   * @param current the stores it is sold at when the change meets the range on its day in the
   *     line's plan (empty for every store)
   * @throws ApiException 403 {@code BUSINESS_WIDE_ONLY}; 403 {@code STORE_ACCESS_DENIED}
   */
  private static void requireHeldMayChange(
      TenantContext ctx, UUID productId, String action, List<UUID> current, UUID storeId) {
    if (!heldToStores(ctx)) return;
    if (current.isEmpty()) {
      CatalogueStores.requireBusinessWide(
          ctx,
          Assortment.LIST.equals(action)
              ? "Line "
                  + productId
                  + " is sold at every store; listing it at yours alone would take it off every"
                  + " other shelf, which only an owner or a manager of the whole business can do"
              : "Line "
                  + productId
                  + " is sold at every store; taking it out of one store means naming every store"
                  + " that keeps it, which only an owner or a manager of the whole business can"
                  + " do");
    }
    List<UUID> after = Assortment.rangeAfter(action, current, List.of(storeId));
    if (after.isEmpty()) return;
    CatalogueStores.requireChangeHeldTo(ctx, current, after);
  }

  /**
   * What the caller can do about a de-list that would leave a line at no store, as the refusal's
   * last words: discontinue the line, or list it somewhere else first.
   *
   * <p>An owner or a manager of the whole business can discontinue the line and list it at any
   * store, and is told so as always. A manager held to stores can do neither for themselves:
   * discontinuing is the whole business's ({@code POST /admin/products/{id}/discontinue}, {@code
   * 403 BUSINESS_WIDE_ONLY}), as delisting is ({@code DELETE /admin/products/{id}}), a line of
   * their own included, so they are told who can stop it being sold; and they can list it only at
   * their stores ({@link #requireHeldMayChange}), so "another store" could send them to one they
   * cannot range: they are pointed at another of their own stores when they keep one the de-list
   * does not take it out of, and in every case at who can list it at a store they do not keep. Who
   * can is an owner or a manager of the whole business, the words every held-manager refusal here
   * uses.
   *
   * @param leaving the stores the de-list leaves no listing at ({@link Plans#leaving})
   */
  private static String lastStoreRemedy(TenantContext ctx, Set<UUID> leaving) {
    if (!heldToStores(ctx)) {
      return "to stop selling it, discontinue the line; to move it, list it at another store first";
    }
    String stop =
        "to stop selling it, ask an owner or a manager of the whole business to discontinue the"
            + " line; to move it, ";
    boolean keepsAnother = ctx.storeIds().stream().anyMatch(s -> !leaving.contains(s));
    return keepsAnother
        ? stop
            + "list it at another of your stores first, or ask them to list it at a store you do"
            + " not keep"
        : stop
            + "ask them to list it first at a store you do not keep: none of yours would be left"
            + " selling it";
  }

  /**
   * The plans of the lines a change, or a closing review's changes, is judged against: each line's
   * open changes, any day, read once, and the clusters they aim at, read once, as they are today.
   */
  private final class Plans {

    private final UUID tenantId;
    private final Map<UUID, List<Change>> recorded;
    private final Map<UUID, List<UUID>> clusterStores = new HashMap<>();

    Plans(UUID tenantId, List<UUID> productIds) {
      this.tenantId = tenantId;
      this.recorded =
          repo.pending(tenantId, productIds).stream()
              .collect(Collectors.groupingBy(Change::productId));
    }

    /**
     * The line's plan with a change among its recorded ones: how each ends, and what each meets.
     *
     * @param candidate the change being judged, not yet recorded
     * @param range the stores the line is sold at now (empty for every store)
     */
    Map<UUID, Assortment.Fate> with(Change candidate, List<UUID> range) {
      List<Assortment.Step> steps = new ArrayList<>();
      UUID productId = candidate.productId();
      for (Change ch : recordedOf(productId)) steps.add(step(ch));
      steps.add(step(candidate));
      return Assortment.plan(range, steps);
    }

    /**
     * Refuses a de-list the sweep could only close ({@link Assortment#leavesNoStore}), whoever
     * asks: with no rows the line is sold at every store, so it could never be applied. Refused
     * when the plan closes the change itself, and when it closes a de-list already recorded that
     * the plan without this change would not — this change is what strands it. A listing is never
     * refused here: it is the later decision, and listing elsewhere is the refusal's own remedy;
     * the sweep closes a de-list it strands.
     *
     * <p>The refusal says what the caller can do about it ({@link #lastStoreRemedy}).
     *
     * @param ctx the caller, whom the refusal tells what they can do
     * @param fates the line's plan with the change among its recorded ones
     * @throws ApiException 409 {@code ASSORTMENT_LAST_STORE}
     */
    void requireSomeStoreLeft(
        TenantContext ctx, Change candidate, List<UUID> range, Map<UUID, Assortment.Fate> fates) {
      if (!Assortment.DELIST.equals(candidate.action())) return;
      UUID productId = candidate.productId();
      Assortment.Fate own = fates.get(candidate.id());
      if (own.closed()) {
        throw ApiException.conflict(
            Assortment.LAST_STORE,
            "Line "
                + productId
                + " is sold only at the stores this would take it out of, and a line with no store"
                + " range is sold at every store, so nobody can take it out of its last store: "
                + lastStoreRemedy(ctx, leaving(own, step(candidate))));
      }
      List<Change> stranded =
          recordedOf(productId).stream()
              .filter(ch -> fates.containsKey(ch.id()) && fates.get(ch.id()).closed())
              .toList();
      if (stranded.isEmpty()) return;
      List<Assortment.Step> steps = new ArrayList<>();
      for (Change ch : recordedOf(productId)) steps.add(step(ch));
      Map<UUID, Assortment.Fate> without = Assortment.plan(range, steps);
      for (Change ch : stranded) {
        Assortment.Fate alone = without.get(ch.id());
        if (alone != null && alone.closed()) continue;
        throw ApiException.conflict(
            Assortment.LAST_STORE,
            "Line "
                + productId
                + " would then be sold only at the stores the de-list recorded for "
                + ch.effectiveFrom()
                + " takes it out of, and a line with no store range is sold at every store, so"
                + " that de-list could never be applied: "
                + lastStoreRemedy(ctx, leaving(fates.get(ch.id()), step(candidate), step(ch))));
      }
    }

    /**
     * The stores a refused de-list leaves no listing at: the stores the line meets the closed
     * de-list sold at, and every store the de-lists concerned take it out of. Listing it at a store
     * outside them, on or before the de-list's day, is what lets the de-list apply.
     */
    private static Set<UUID> leaving(Assortment.Fate closed, Assortment.Step... delists) {
      Set<UUID> leaving = new HashSet<>(closed.rangeMet());
      for (Assortment.Step st : delists) leaving.addAll(st.stores());
      return leaving;
    }

    private List<Change> recordedOf(UUID productId) {
      return recorded.getOrDefault(productId, List.of());
    }

    /** A change and the stores it is aimed at: its store, or its cluster's members today. */
    private Assortment.Step step(Change ch) {
      return new Assortment.Step(
          ch,
          ch.storeId() != null
              ? List.of(ch.storeId())
              : clusterStores.computeIfAbsent(
                  ch.clusterId(),
                  id -> repo.cluster(tenantId, id).map(Cluster::storeIds).orElse(List.of())));
    }
  }

  /**
   * The reason written onto a produced change: which review, over what period, and where the line
   * ranked. The whole point of the log is that this sentence exists a year later.
   */
  private static String reasonFor(Review r, Assortment.ProductAction a, Integer rank) {
    String verb = Assortment.DELIST.equals(a.action()) ? "De-listed" : "Listed";
    String ranked = rank == null ? "" : ", ranked " + rank + " in category";
    return verb
        + " by range review \""
        + r.name()
        + "\" covering "
        + r.periodFrom()
        + " to "
        + r.periodTo()
        + ranked;
  }

  /**
   * The best rank any of a product's reviewed variants reached — 1 is best, so the lowest number.
   */
  private static Map<UUID, Integer> ranks(Review r, Map<UUID, UUID> productByVariant) {
    Map<UUID, Integer> best = new HashMap<>();
    for (Line l : r.lines()) {
      UUID productId = productByVariant.get(l.variantId());
      if (productId == null || l.rankInCategory() == null) continue;
      best.merge(productId, l.rankInCategory(), Math::min);
    }
    return best;
  }

  private Review openOnly(UUID tenantId, UUID reviewId) {
    Review r = review(tenantId, reviewId);
    if (!r.open()) {
      throw ApiException.conflict("REVIEW_NOT_OPEN", "This review has already been closed");
    }
    return r;
  }

  private static Line withDecision(Line l, String decision) {
    return new Line(
        l.id(),
        l.tenantId(),
        l.reviewId(),
        l.variantId(),
        l.unitsSold(),
        l.revenue(),
        l.margin(),
        l.currency(),
        l.rankInCategory(),
        decision,
        l.decisionNote(),
        l.ownBrand());
  }

  /** The largest figure a line keeps: {@code NUMERIC(18, 4)} holds fourteen whole digits. */
  private static final BigDecimal MONEY_LIMIT = BigDecimal.TEN.pow(14);

  /** The largest units a line keeps: {@code NUMERIC(14, 3)} holds eleven whole digits. */
  private static final BigDecimal UNITS_LIMIT = BigDecimal.TEN.pow(11);

  /** The decimal places a line's units keep: {@code NUMERIC(14, 3)}. */
  private static final int UNITS_SCALE = 3;

  /**
   * A line's units fit their column: at most three decimal places and below 10^11, refused with
   * more rather than rounded or left to overflow in the database.
   *
   * @throws ApiException 400 {@code REVIEW_LINE_FIGURES}
   */
  private static void requireUnits(BigDecimal units) {
    if (units == null) return;
    if (Math.max(0, units.stripTrailingZeros().scale()) > UNITS_SCALE) {
      throw ApiException.badRequest(
          "REVIEW_LINE_FIGURES",
          "unitsSold has more decimal places than a line keeps (" + UNITS_SCALE + ")");
    }
    if (units.abs().compareTo(UNITS_LIMIT) >= 0) {
      throw ApiException.badRequest("REVIEW_LINE_FIGURES", "unitsSold is too large to keep");
    }
  }

  /**
   * A line's money is in its currency's minor units, and small enough to keep.
   *
   * @throws ApiException 400 {@code REVIEW_LINE_FIGURES}
   */
  private static void requireMoney(BigDecimal amount, String field, String currency) {
    if (amount == null) return;
    int minorUnits = Fx.minorUnits(currency);
    if (Math.max(0, amount.stripTrailingZeros().scale()) > minorUnits) {
      throw ApiException.badRequest(
          "REVIEW_LINE_FIGURES",
          field + " has more decimal places than " + currency + " has (" + minorUnits + ")");
    }
    if (amount.abs().compareTo(MONEY_LIMIT) >= 0) {
      throw ApiException.badRequest("REVIEW_LINE_FIGURES", field + " is too large to keep");
    }
  }

  private static boolean negative(BigDecimal v) {
    return v != null && v.signum() < 0;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static String require(String value, String code, String message) {
    String v = blankToNull(value);
    if (v == null) throw ApiException.badRequest(code, message);
    return v;
  }
}
