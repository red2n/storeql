package com.storeql.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Range: which stores carry a line, and the review that decides (07.18).
 *
 * <p>Both halves of this had something already. {@code product_stores} has recorded which stores
 * carry a product (V13__product_store_assortment.sql), and the item lifecycle (V1__init.sql) has
 * launched, discontinued and reinstated lines against a date. Neither is replaced here.
 *
 * <p>What was missing was the same thing twice: <b>the decision, with a date and a reason and the
 * comparison it was made against.</b> A line was ranged or dropped store by store, on somebody's
 * judgement, and nothing recorded why or what it was weighed against — so nobody could answer "who
 * took this out of the Scottish shops, and on what evidence" six months later.
 *
 * <p>Two consequences run through the design. A change is recorded <em>before</em> it takes effect,
 * so a range is planned rather than typed on the morning it happens — which makes {@code
 * product_stores} the state and this the intent. And a review's figures are a <b>snapshot</b>:
 * sales belong to order-svc and reporting-svc, product-svc reads neither, and a snapshot is the
 * better record anyway, because a report re-run next year shows different numbers and makes an old
 * decision look arbitrary.
 */
public final class Assortment {

  private Assortment() {}

  public static final String ACTIVE = "ACTIVE";
  public static final String RETIRED = "RETIRED";

  /**
   * A named group of stores to range against.
   *
   * <p>The practical complaint about per-store assortment was never that it could not express a
   * range — it is that expressing one costs a row per store. A chain ranges by type: city
   * convenience, superstore, the ten shops with a fish counter. A store may belong to several
   * clusters, deliberately: "Scotland" and "has a bakery" are both true of the same shop, and one
   * grouping would make one of them unsayable.
   */
  public record Cluster(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      String note,
      String status,
      Instant createdAt,
      Instant updatedAt,
      List<UUID> storeIds) {

    public Cluster {
      storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }

    public boolean active() {
      return ACTIVE.equals(status);
    }
  }

  public static final String LIST = "LIST";
  public static final String DELIST = "DELIST";
  public static final Set<String> ACTIONS = Set.of(LIST, DELIST);

  /**
   * A dated, reasoned decision about where a line is ranged.
   *
   * @param storeId set when the change is aimed at one store; null when it is aimed at a cluster
   * @param clusterId set when it is aimed at a group; exactly one of the two
   * @param appliedAt when the change was actually pushed into {@code product_stores}. Null means it
   *     is still intent — which is the whole point of having a date
   * @param reviewId the review that produced it, when it came from one rather than a single
   *     decision
   * @param heldToStores whether whoever decided it was held to stores (a manager of some branches,
   *     not of the whole business). Kept because the range can change between the decision and the
   *     day it applies, and such a manager's change may never move a line to or from "every store"
   *     on that day either ({@link #refusalToApply})
   * @param refusedAt when the sweep closed it as refused for good ({@link Refusal#staysDue} false).
   *     Null while it is open or once it is applied; a change is applied or refused, never both
   * @param refusalCode the code it was closed with, kept beside it; null unless refused
   * @param refusalDetail the sentence it was closed with; null unless refused
   */
  public record Change(
      UUID id,
      UUID tenantId,
      UUID productId,
      UUID storeId,
      UUID clusterId,
      String action,
      LocalDate effectiveFrom,
      String reason,
      UUID decidedBy,
      Instant createdAt,
      Instant appliedAt,
      UUID reviewId,
      boolean heldToStores,
      Instant refusedAt,
      String refusalCode,
      String refusalDetail) {

    /** A change that has not been closed as refused: as it is recorded, or applied. */
    public Change(
        UUID id,
        UUID tenantId,
        UUID productId,
        UUID storeId,
        UUID clusterId,
        String action,
        LocalDate effectiveFrom,
        String reason,
        UUID decidedBy,
        Instant createdAt,
        Instant appliedAt,
        UUID reviewId,
        boolean heldToStores) {
      this(
          id,
          tenantId,
          productId,
          storeId,
          clusterId,
          action,
          effectiveFrom,
          reason,
          decidedBy,
          createdAt,
          appliedAt,
          reviewId,
          heldToStores,
          null,
          null,
          null);
    }

    public boolean applied() {
      return appliedAt != null;
    }

    /** Whether the sweep closed it as refused for good: it will never be applied. */
    public boolean refused() {
      return refusedAt != null;
    }

    /**
     * Whether this change is due: dated on or before the day asked about, and neither applied nor
     * closed as refused.
     */
    public boolean due(LocalDate asOf) {
      return !applied() && !refused() && !effectiveFrom.isAfter(asOf);
    }
  }

  /**
   * Why a due change is not applied to the range as it stands: a stable code and a sentence.
   *
   * @param staysDue true when the change waits, due, for the range (or a cluster's membership) to
   *     allow it, and is tried again by every sweep; false when no range can make it apply as it
   *     reads, so the sweep closes it as refused rather than meet it again on every run for ever
   */
  public record Refusal(String code, String detail, boolean staysDue) {}

  /**
   * The refusal of a change aimed at a cluster with no stores yet: applied, it would be a no-op
   * marked done and never come back. It waits for the cluster to be given a store.
   */
  public static Refusal clusterEmpty() {
    return new Refusal(
        "CLUSTER_EMPTY", "The cluster this change aims at has no stores in it yet", true);
  }

  /** The refusal of a de-list that would leave a line at no store, which reads as every store. */
  public static final String LAST_STORE = "ASSORTMENT_LAST_STORE";

  /**
   * Whether a change would take a line out of every store it is sold at: a DELIST of all of a
   * ranged line's stores. Its range would then have no rows, which {@code product_stores} reads as
   * every store, the opposite of what was decided — so nobody may make it, whoever they are.
   * Discontinuing the line is how it stops being sold anywhere.
   *
   * @param action LIST or DELIST
   * @param current the stores it is sold at (empty for every store, which is not this case)
   * @param stores the stores the change is aimed at
   * @return true when it would leave the line at no store
   */
  public static boolean leavesNoStore(String action, List<UUID> current, Collection<UUID> stores) {
    return DELIST.equals(action)
        && !current.isEmpty()
        && rangeAfter(DELIST, current, stores).isEmpty();
  }

  /**
   * The stores a product is sold at after a change, from the stores it is sold at now: the change's
   * stores added for a LIST, taken away for a DELIST, the order of the range kept. Read literally:
   * an empty answer is "no store", which {@code product_stores} would read as every store, and that
   * is the reason {@link #refusalToApply} exists.
   *
   * @param action LIST or DELIST
   * @param current the stores it is sold at now (empty for every store)
   * @param stores the stores the change is aimed at
   * @return the range it would leave
   */
  public static List<UUID> rangeAfter(String action, List<UUID> current, Collection<UUID> stores) {
    var after = new LinkedHashSet<>(current);
    if (LIST.equals(action)) {
      after.addAll(stores);
    } else {
      after.removeAll(stores);
    }
    return List.copyOf(after);
  }

  /**
   * Whether a due change can be applied to the range as it stands, judged when it is applied, on
   * the range read there.
   *
   * <p>{@code product_stores} says "every store" with no rows, so two changes cannot be written as
   * they read, and one may not be written by whoever decided it:
   *
   * <ul>
   *   <li>A DELIST of a line sold at every store ({@code ASSORTMENT_RANGED_EVERYWHERE}): taking it
   *       out of one store means naming every other, which is tenant-svc's to know, not this
   *       service's to guess.
   *   <li>A DELIST that would leave it at no store ({@code ASSORTMENT_LAST_STORE}, {@link
   *       #leavesNoStore}): no rows would put it on every shelf, the opposite of a de-list, whoever
   *       decided it. Discontinuing the line is how it stops being sold anywhere. Such a change is
   *       refused when it is recorded, judged on the line's whole plan ({@link #plan}); the sweep
   *       finds one only when the range, or a cluster's membership, has changed since.
   *   <li>A LIST, decided by a manager held to stores, of a line that is now sold at every store
   *       ({@code BUSINESS_WIDE_ONLY}): it would take the line off every shelf but theirs, which
   *       such a manager may not do when the change is recorded and may not do on the day either.
   *       An owner's or a business-wide manager's LIST of such a line ranges it, as it always has.
   * </ul>
   *
   * The first and third leave the change due, so fixing the range is enough and nothing is
   * re-entered. The second is final ({@link Refusal#staysDue} false): judged on the range the sweep
   * finds, the line is sold only at the stores it names, and no range can make "take it out of all
   * of them" apply as it reads, so tried again it would be refused on every run for ever. The sweep
   * closes it as refused, with its reason, once.
   *
   * @param ch the change
   * @param stores the stores it is aimed at, resolved for the day
   * @param current the stores the product is sold at now (empty for every store)
   * @return why it is not applied, or empty when it may be
   */
  public static Optional<Refusal> refusalToApply(Change ch, List<UUID> stores, List<UUID> current) {
    if (DELIST.equals(ch.action())) {
      if (current.isEmpty()) {
        return Optional.of(
            new Refusal(
                "ASSORTMENT_RANGED_EVERYWHERE",
                "This line has no store range at all, which means every store sells it. List it to"
                    + " the stores that keep it before taking it out of one.",
                true));
      }
      if (leavesNoStore(DELIST, current, stores)) {
        return Optional.of(
            new Refusal(
                LAST_STORE,
                "These are the last stores this line is sold at, and a line with no store range"
                    + " is sold at every store, so this de-list can never be applied and is closed"
                    + " as refused. To stop selling the line, discontinue it; to move it, list it"
                    + " at another store and record the de-list again.",
                false));
      }
      return Optional.empty();
    }
    if (ch.heldToStores() && current.isEmpty()) {
      return Optional.of(
          new Refusal(
              "BUSINESS_WIDE_ONLY",
              "This line is now sold at every store, and the change was decided by a manager held"
                  + " to stores: listing it at theirs would take it off every other shelf, which"
                  + " only an owner or a manager of the whole business can do.",
              true));
    }
    return Optional.empty();
  }

  // ── a line's plan: what the sweep would do with its open changes ────────────

  /**
   * A change as a plan walks it.
   *
   * @param stores the stores it is aimed at: its one store, or a cluster's members as they are
   *     today (empty for a cluster with none yet)
   */
  public record Step(Change change, List<UUID> stores) {

    public Step {
      stores = stores == null ? List.of() : List.copyOf(stores);
    }
  }

  /** How a change ends in a plan. */
  public enum Outcome {
    /** Put into the range on its day, or on a later sweep once the range allowed it. */
    APPLIED,
    /** Refused with a refusal that stays due, and still refused when the plan runs out. */
    WAITING,
    /** Refused for good ({@link Refusal#staysDue} false): the sweep closes it. */
    CLOSED
  }

  /**
   * A change's end in a plan.
   *
   * @param refusal the last refusal it met; null when it is applied
   * @param rangeMet the stores the line was sold at when the change was first judged on its day
   *     (empty for every store): the range it is decided against
   */
  public record Fate(Outcome outcome, Refusal refusal, List<UUID> rangeMet) {

    public Fate {
      rangeMet = rangeMet == null ? List.of() : List.copyOf(rangeMet);
    }

    public boolean closed() {
      return outcome == Outcome.CLOSED;
    }
  }

  /**
   * What the sweep would do with a line's open changes: each one's end, and the range it meets.
   *
   * <p>Walked as the sweep walks them — by day, and within a day in the order they were recorded —
   * and, because the sweep runs every hour and tries a waiting change again, each day is swept
   * until nothing more moves. So a de-list waiting for a line sold everywhere to be given a range
   * is tried again after a listing gives it one, as it would be on the day. A refused change moves
   * nothing; a closed one is not tried again; each is judged by {@link #refusalToApply}, the
   * sweep's own rule, on the range the plan has reached.
   *
   * <p>A plan, not a promise: a cluster's members are read as they are today, and the range can be
   * replaced before the day. The sweep judges every change again on the range it finds, and closes
   * any that has become impossible.
   *
   * @param range the stores the line is sold at now (empty for every store)
   * @param steps the line's open changes, in any order
   * @return each change's end, by change id
   */
  public static Map<UUID, Fate> plan(List<UUID> range, List<Step> steps) {
    List<Step> open = new ArrayList<>(steps);
    open.sort(
        java.util.Comparator.comparing((Step st) -> st.change().effectiveFrom())
            .thenComparing(st -> st.change().createdAt()));
    Map<UUID, Fate> fates = new LinkedHashMap<>();
    Map<UUID, List<UUID>> met = new LinkedHashMap<>();
    List<UUID> state = List.copyOf(range);
    for (LocalDate day :
        open.stream().map(st -> st.change().effectiveFrom()).distinct().sorted().toList()) {
      boolean moved;
      do {
        moved = false;
        for (var it = open.iterator(); it.hasNext(); ) {
          Step st = it.next();
          Change ch = st.change();
          if (ch.effectiveFrom().isAfter(day)) break;
          met.putIfAbsent(ch.id(), state);
          Optional<Refusal> refusal =
              st.stores().isEmpty()
                  ? Optional.of(clusterEmpty())
                  : refusalToApply(ch, st.stores(), state);
          if (refusal.isEmpty()) {
            state = rangeAfter(ch.action(), state, st.stores());
            fates.put(ch.id(), new Fate(Outcome.APPLIED, null, met.get(ch.id())));
            it.remove();
            moved = true;
          } else if (!refusal.get().staysDue()) {
            fates.put(ch.id(), new Fate(Outcome.CLOSED, refusal.get(), met.get(ch.id())));
            it.remove();
          } else {
            fates.put(ch.id(), new Fate(Outcome.WAITING, refusal.get(), met.get(ch.id())));
          }
        }
      } while (moved);
    }
    return fates;
  }

  // ── range review ────────────────────────────────────────────────────────────

  public static final String OPEN = "OPEN";
  public static final String DECIDED = "DECIDED";
  public static final String ABANDONED = "ABANDONED";

  public static final String KEEP = "KEEP";
  public static final String INTRODUCE = "INTRODUCE";
  public static final Set<String> DECISIONS = Set.of(KEEP, DELIST, INTRODUCE);

  /** A category looked at over a trading period. */
  public record Review(
      UUID id,
      UUID tenantId,
      UUID categoryId,
      String name,
      LocalDate periodFrom,
      LocalDate periodTo,
      String status,
      String note,
      Instant createdAt,
      Instant decidedAt,
      List<Line> lines) {

    public Review {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean open() {
      return OPEN.equals(status);
    }

    /** How many lines still have no decision — what stops a review being closed. */
    public long undecided() {
      return lines.stream().filter(l -> l.decision() == null).count();
    }

    /** The lines a closing review will act on: the ones marked to drop or to bring in. */
    public List<Line> actionable() {
      return lines.stream()
          .filter(l -> l.decision() != null && !KEEP.equals(l.decision()))
          .toList();
    }
  }

  /**
   * One line under review, with the figures it was judged on.
   *
   * @param ownBrand recorded on the row rather than looked up later, because own-brand status
   *     changes and the review should show the reason as it applied on the day
   * @param rankInCategory where it came on whatever the buyer ranked by; 1 is best
   */
  public record Line(
      UUID id,
      UUID tenantId,
      UUID reviewId,
      UUID variantId,
      BigDecimal unitsSold,
      BigDecimal revenue,
      BigDecimal margin,
      String currency,
      Integer rankInCategory,
      String decision,
      String decisionNote,
      boolean ownBrand) {}

  /**
   * Whether dropping this line needs somebody to say more than "it sold badly".
   *
   * <p>An own-brand line is the business's own margin and its own shelf presence, so a review that
   * drops one on rank alone is usually a mistake — the remedy for a poor own-brand line is more
   * often a reformulation or a price than a de-list. Not a refusal: a buyer may well be right. It
   * asks for a note, which is the difference between a decision and a reflex.
   */
  public static boolean needsJustification(Line line) {
    return DELIST.equals(line.decision()) && line.ownBrand();
  }

  // ── from variant decisions to a product's range ─────────────────────────────

  /** A range action derived for one product from the decisions taken on its variants. */
  public record ProductAction(UUID productId, String action) {}

  /** A product a closing review deliberately did not touch, and why. */
  public record LeftAlone(UUID productId, String reason) {}

  /** What closing a review comes to: the changes it produces, and what it left alone. */
  public record RangeOutcome(List<ProductAction> actions, List<LeftAlone> leftAlone) {

    public RangeOutcome {
      actions = actions == null ? List.of() : List.copyOf(actions);
      leftAlone = leftAlone == null ? List.of() : List.copyOf(leftAlone);
    }
  }

  static final String KEPT_SIBLING =
      "Another variant of this product is kept, so the product stays ranged";
  static final String UNREVIEWED_SIBLING =
      "The review did not cover every variant of this product, so de-listing it would drop a line"
          + " nobody looked at";

  /**
   * Turns variant-level review decisions into product-level range actions.
   *
   * <p>The two levels do not line up, and pretending they do is the bug worth avoiding: a review
   * reads <em>variants</em>, because that is what sells and what gets ranked, while {@code
   * product_stores} ranges a <em>product</em>. Three rules bridge them, and each one exists because
   * the naive reading loses a line somebody is still selling.
   *
   * <ol>
   *   <li><b>Bringing one in wins.</b> If any variant is marked INTRODUCE the product is listed,
   *       even where a sibling is being dropped — a new flavour replacing an old one must not
   *       de-list the product on its way in.
   *   <li><b>Dropping needs every variant.</b> A product is de-listed only when every one of its
   *       variants was reviewed and every one was marked DELIST. Otherwise the drop would take
   *       lines the buyer never looked at off the shelf with it.
   *   <li><b>KEEP does nothing.</b> The line is already ranged; a change per untouched line would
   *       fill the log with no-ops and hide the decisions that matter.
   * </ol>
   *
   * @param productByVariant which product each reviewed variant belongs to
   * @param variantsPerProduct how many live variants each product has <em>now</em> — the count that
   *     decides whether the review covered all of them
   */
  public static RangeOutcome rangeActions(
      List<Line> lines, Map<UUID, UUID> productByVariant, Map<UUID, Integer> variantsPerProduct) {
    Map<UUID, List<String>> byProduct = new LinkedHashMap<>();
    for (Line l : lines) {
      if (l.decision() == null) continue;
      UUID productId = productByVariant.get(l.variantId());
      if (productId == null) continue;
      byProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(l.decision());
    }

    List<ProductAction> actions = new ArrayList<>();
    List<LeftAlone> leftAlone = new ArrayList<>();
    for (Map.Entry<UUID, List<String>> e : byProduct.entrySet()) {
      List<String> decisions = e.getValue();
      if (decisions.contains(INTRODUCE)) {
        actions.add(new ProductAction(e.getKey(), LIST));
      } else if (decisions.stream().allMatch(DELIST::equals)) {
        int live = variantsPerProduct.getOrDefault(e.getKey(), decisions.size());
        if (decisions.size() >= live) {
          actions.add(new ProductAction(e.getKey(), DELIST));
        } else {
          leftAlone.add(new LeftAlone(e.getKey(), UNREVIEWED_SIBLING));
        }
      } else if (decisions.contains(DELIST)) {
        leftAlone.add(new LeftAlone(e.getKey(), KEPT_SIBLING));
      }
    }
    return new RangeOutcome(actions, leftAlone);
  }
}
