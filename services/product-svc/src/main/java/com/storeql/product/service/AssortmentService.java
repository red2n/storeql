package com.storeql.product.service;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment;
import com.storeql.product.domain.Assortment.Change;
import com.storeql.product.domain.Assortment.Cluster;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.domain.Assortment.RangeOutcome;
import com.storeql.product.domain.Assortment.Review;
import com.storeql.product.repo.AssortmentRepository;
import com.storeql.product.repo.AssortmentRepository.VariantProduct;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Range: which stores carry a line, decided with a date and a reason (07.18).
 *
 * <p>Three judgements shape this class.
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
 * fill in on the buyer's behalf. That change stays due and says so.
 */
@ApplicationScoped
public class AssortmentService {

  @Inject AssortmentRepository repo;

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

  /** Adds stores to a cluster. Already-present stores are left alone rather than refused. */
  public Cluster addMembers(UUID tenantId, UUID clusterId, List<UUID> storeIds) {
    Cluster c = cluster(tenantId, clusterId);
    if (!c.active()) {
      throw ApiException.conflict("CLUSTER_RETIRED", "A retired cluster takes no more stores");
    }
    if (storeIds.isEmpty()) {
      throw ApiException.badRequest("CLUSTER_STORES_REQUIRED", "Name at least one store");
    }
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
   * @param storeId one store, or null when the change is aimed at a cluster
   * @param clusterId a group of stores, or null when it is aimed at one store
   * @throws ApiException 400 when the action is unknown, when neither or both targets are given, or
   *     when there is no reason
   */
  public Change record(
      UUID tenantId,
      UUID productId,
      UUID storeId,
      UUID clusterId,
      String action,
      LocalDate effectiveFrom,
      String reason,
      UUID actorId) {
    String a = action == null ? "" : action.strip().toUpperCase(Locale.ROOT);
    if (!Assortment.ACTIONS.contains(a)) {
      throw ApiException.badRequest(
          "ASSORTMENT_ACTION_UNKNOWN", "A range change is LIST or DELIST");
    }
    if ((storeId == null) == (clusterId == null)) {
      throw ApiException.badRequest(
          "ASSORTMENT_TARGET_REQUIRED", "Aim the change at one store or at one cluster, not both");
    }
    if (clusterId != null) cluster(tenantId, clusterId);
    return repo.record(
        new Change(
            Ids.newId(),
            tenantId,
            productId,
            storeId,
            clusterId,
            a,
            effectiveFrom == null ? LocalDate.now() : effectiveFrom,
            require(reason, "ASSORTMENT_REASON_REQUIRED", "Say why the range is changing"),
            actorId,
            Instant.now(),
            null,
            null));
  }

  public List<Change> changesOf(UUID tenantId, UUID productId) {
    return repo.changesOf(tenantId, productId);
  }

  public List<Change> due(UUID tenantId, LocalDate asOf) {
    return repo.due(tenantId, asOf == null ? LocalDate.now() : asOf);
  }

  /**
   * One change that could not be applied, and why. The change stays due, so fixing the cause is
   * enough — nothing has to be re-entered.
   */
  public record NotApplied(UUID changeId, UUID productId, String code, String detail) {}

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
   * <p>Two refusals leave the change due rather than swallowing it. A cluster with no stores yet
   * would apply as a no-op and then never come back. A DELIST against a product that is ranged
   * everywhere cannot be expressed at all under {@code product_stores}' convention that no rows
   * means every store, and inventing the store list is product-svc's to guess and tenant-svc's to
   * know.
   */
  public SweepResult applyDue(UUID tenantId, LocalDate asOf) {
    List<NotApplied> refused = new ArrayList<>();
    int applied = 0;
    // Cluster membership is read once per sweep, not once per change aimed at it.
    java.util.Map<UUID, List<UUID>> clusterStores = new java.util.HashMap<>();
    for (Change ch : repo.due(tenantId, asOf == null ? LocalDate.now() : asOf)) {
      List<UUID> stores =
          ch.storeId() != null
              ? List.of(ch.storeId())
              : clusterStores.computeIfAbsent(
                  ch.clusterId(), id -> cluster(tenantId, id).storeIds());
      if (stores.isEmpty()) {
        refused.add(
            new NotApplied(
                ch.id(),
                ch.productId(),
                "CLUSTER_EMPTY",
                "The cluster this change aims at has no stores in it yet"));
        continue;
      }
      if (Assortment.DELIST.equals(ch.action())
          && repo.rangedStoreCount(tenantId, ch.productId()) == 0) {
        refused.add(
            new NotApplied(
                ch.id(),
                ch.productId(),
                "ASSORTMENT_RANGED_EVERYWHERE",
                "This line has no store range at all, which means every store sells it. List it to"
                    + " the stores that keep it before taking it out of one."));
        continue;
      }
      if (repo.apply(tenantId, ch, stores)) applied++;
    }
    return new SweepResult(applied, refused);
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
   * when money is given — a revenue with no currency is a number nobody can add up later.
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
      if (negative(l.unitsSold()) || negative(l.revenue())) {
        throw ApiException.badRequest(
            "REVIEW_LINE_FIGURES", "Units sold and revenue are never negative");
      }
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
   */
  public ReviewResult close(
      UUID tenantId,
      UUID reviewId,
      UUID storeId,
      UUID clusterId,
      LocalDate effectiveFrom,
      UUID actorId) {
    Review r = openOnly(tenantId, reviewId);
    if (r.lines().isEmpty()) {
      throw ApiException.conflict("REVIEW_EMPTY", "A review with no lines decides nothing");
    }
    long undecided = r.undecided();
    if (undecided > 0) {
      throw ApiException.conflict(
          "REVIEW_LINES_UNDECIDED", undecided + " line(s) in this review still have no decision");
    }
    if ((storeId == null) == (clusterId == null)) {
      throw ApiException.badRequest(
          "ASSORTMENT_TARGET_REQUIRED", "Aim the review's changes at one store or at one cluster");
    }
    if (clusterId != null) cluster(tenantId, clusterId);

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
              r.id()));
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
