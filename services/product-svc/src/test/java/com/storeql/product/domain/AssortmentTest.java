package com.storeql.product.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment.Change;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.domain.Assortment.Review;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Range decisions: what is due, what is still intent, and what a review will act on.
 *
 * <p>The cases worth writing are the boundaries. A change dated today is due today, not tomorrow —
 * a range that goes live a day late is a promotion with nothing on the shelf. A change already
 * applied is never due again, because applying twice would re-list a line somebody has since
 * dropped by hand.
 */
class AssortmentTest {

  private static Change change(String action, LocalDate from, Instant appliedAt) {
    return new Change(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        null,
        action,
        from,
        "because",
        Ids.newId(),
        Instant.now(),
        appliedAt,
        null,
        false);
  }

  private static Line line(String decision, boolean ownBrand, Integer rank) {
    return line(Ids.newId(), decision, ownBrand, rank);
  }

  private static Line line(UUID variantId, String decision, boolean ownBrand, Integer rank) {
    return new Line(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        variantId,
        new BigDecimal("120.000"),
        new BigDecimal("340.00"),
        new BigDecimal("70.00"),
        "GBP",
        rank,
        decision,
        null,
        ownBrand);
  }

  @Test
  @DisplayName("A change dated today is due today, not tomorrow")
  void dueOnItsDay() {
    Change c = change(Assortment.LIST, LocalDate.of(2026, 10, 1), null);
    assertFalse(c.due(LocalDate.of(2026, 9, 30)), "not yet");
    assertTrue(
        c.due(LocalDate.of(2026, 10, 1)), "a range that goes live a day late is an empty shelf");
    assertTrue(
        c.due(LocalDate.of(2026, 10, 9)), "and a missed day does not make it stop being due");
  }

  @Test
  @DisplayName("An applied change is never due again")
  void appliedIsDone() {
    // Applying twice would re-list a line somebody has since dropped by hand, which is the quiet
    // way a
    // de-list gets undone.
    Change c = change(Assortment.DELIST, LocalDate.of(2026, 10, 1), Instant.now());
    assertTrue(c.applied());
    assertFalse(c.due(LocalDate.of(2026, 12, 1)));
  }

  @Test
  @DisplayName("A review knows what is left to decide, and what it will act on")
  void reviewProgress() {
    Review r =
        new Review(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Soft drinks H2",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 7, 1),
            Assortment.OPEN,
            null,
            Instant.now(),
            null,
            List.of(
                line(Assortment.KEEP, false, 1),
                line(Assortment.DELIST, false, 40),
                line(Assortment.INTRODUCE, false, null),
                line(null, false, 12)));

    assertEquals(1, r.undecided(), "one line still has no decision");
    // KEEP is a decision and not an action: the line is already ranged, so closing the review does
    // nothing to it. Counting it would produce a no-op change for every line a buyer left alone.
    assertEquals(2, r.actionable().size());
    assertTrue(r.open());
  }

  @Test
  @DisplayName("Dropping an own-brand line asks for a note; dropping anything else does not")
  void ownBrandNeedsJustification() {
    // The remedy for a poor own-brand line is more often a reformulation or a price than a de-list,
    // and
    // the margin lost is the business's own. Not a refusal — a buyer may be right — but not a
    // reflex.
    assertTrue(Assortment.needsJustification(line(Assortment.DELIST, true, 40)));
    assertFalse(Assortment.needsJustification(line(Assortment.DELIST, false, 40)));
    assertFalse(Assortment.needsJustification(line(Assortment.KEEP, true, 2)));
    assertFalse(Assortment.needsJustification(line(null, true, 2)));
  }

  @Test
  @DisplayName("A review's lines are its own copy, so a caller cannot change them afterwards")
  void linesAreCopied() {
    List<Line> mutable = new java.util.ArrayList<>(List.of(line(Assortment.KEEP, false, 1)));
    Review r =
        new Review(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "x",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 7, 1),
            Assortment.OPEN,
            null,
            Instant.now(),
            null,
            mutable);
    mutable.add(line(Assortment.DELIST, false, 9));
    assertEquals(1, r.lines().size());
  }

  @Test
  @DisplayName("A product is de-listed only when every one of its variants was dropped")
  void delistNeedsEveryVariant() {
    // The two levels do not line up: a review reads variants, because that is what sells and what
    // gets ranked, while a range is held per product. De-listing on a partial reading takes lines
    // off the shelf that nobody looked at.
    UUID product = Ids.newId();
    UUID v1 = Ids.newId();
    UUID v2 = Ids.newId();
    Map<UUID, UUID> byVariant = Map.of(v1, product, v2, product);

    var both =
        Assortment.rangeActions(
            List.of(line(v1, Assortment.DELIST, false, 40), line(v2, Assortment.DELIST, false, 41)),
            byVariant,
            Map.of(product, 2));
    assertEquals(1, both.actions().size());
    assertEquals(Assortment.DELIST, both.actions().get(0).action());
    assertEquals(product, both.actions().get(0).productId());

    // One of the two reviewed, and dropped: the other variant is still on sale and unread.
    var partial =
        Assortment.rangeActions(
            List.of(line(v1, Assortment.DELIST, false, 40)),
            Map.of(v1, product),
            Map.of(product, 2));
    assertTrue(partial.actions().isEmpty(), "the unreviewed variant is still sold");
    assertEquals(1, partial.leftAlone().size());
    assertEquals(Assortment.UNREVIEWED_SIBLING, partial.leftAlone().get(0).reason());
  }

  @Test
  @DisplayName("Bringing a variant in beats dropping its sibling")
  void introduceWins() {
    // A new flavour replacing an old one: both decisions land on the same product, and de-listing
    // it
    // on the way in would take the replacement off the shelf with the line it replaces.
    UUID product = Ids.newId();
    UUID out = Ids.newId();
    UUID in = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(
                line(out, Assortment.DELIST, false, 39),
                line(in, Assortment.INTRODUCE, false, null)),
            Map.of(out, product, in, product),
            Map.of(product, 2));
    assertEquals(1, outcome.actions().size());
    assertEquals(Assortment.LIST, outcome.actions().get(0).action());
    assertTrue(outcome.leftAlone().isEmpty());
  }

  @Test
  @DisplayName("A kept sibling keeps the product ranged, and says so")
  void keptSiblingHoldsTheRange() {
    UUID product = Ids.newId();
    UUID keep = Ids.newId();
    UUID drop = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(
                line(keep, Assortment.KEEP, false, 3), line(drop, Assortment.DELIST, false, 44)),
            Map.of(keep, product, drop, product),
            Map.of(product, 2));
    assertTrue(outcome.actions().isEmpty());
    assertEquals(Assortment.KEPT_SIBLING, outcome.leftAlone().get(0).reason());
  }

  @Test
  @DisplayName("Lines left at KEEP produce nothing at all")
  void keepProducesNothing() {
    // Not even a note: a change per untouched line would fill the log with no-ops and bury the
    // decisions that matter.
    UUID product = Ids.newId();
    UUID v = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(line(v, Assortment.KEEP, false, 1)), Map.of(v, product), Map.of(product, 1));
    assertTrue(outcome.actions().isEmpty());
    assertTrue(outcome.leftAlone().isEmpty());
  }

  @Test
  @DisplayName("An undecided line is not an action, and a foreign variant is ignored")
  void undecidedAndUnknownAreSkipped() {
    UUID product = Ids.newId();
    UUID mine = Ids.newId();
    UUID foreign = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(line(mine, null, false, 2), line(foreign, Assortment.DELIST, false, 50)),
            Map.of(mine, product),
            Map.of(product, 1));
    assertTrue(outcome.actions().isEmpty(), "a variant the tenant does not own decides nothing");
    assertTrue(outcome.leftAlone().isEmpty());
  }

  // ── what a due change may do to the range as it stands (2 Oct 2026) ─────────────

  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();

  private static Change due(String action, boolean heldToStores) {
    return new Change(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        A,
        null,
        action,
        LocalDate.of(2026, 10, 1),
        "because",
        Ids.newId(),
        Instant.now(),
        null,
        null,
        heldToStores);
  }

  private static String refusal(Change ch, List<UUID> stores, List<UUID> current) {
    return Assortment.refusalToApply(ch, stores, current)
        .map(Assortment.Refusal::code)
        .orElse("applies");
  }

  @Test
  @DisplayName("A de-list never turns a line sold somewhere into one sold everywhere")
  void aDelistNeverLeavesNoStore() {
    // Sold everywhere: no store to take it out of without naming every other (as before).
    assertEquals(
        "ASSORTMENT_RANGED_EVERYWHERE",
        refusal(due(Assortment.DELIST, false), List.of(A), List.of()));
    // Sold at A alone, or at A and B with both de-listed: no rows left would mean every store,
    // the opposite of a de-list, whoever decided it.
    assertEquals(
        "ASSORTMENT_LAST_STORE", refusal(due(Assortment.DELIST, false), List.of(A), List.of(A)));
    assertEquals(
        "ASSORTMENT_LAST_STORE",
        refusal(due(Assortment.DELIST, true), List.of(A, B), List.of(B, A)));
    // A store left keeps a range to de-list from.
    assertEquals("applies", refusal(due(Assortment.DELIST, false), List.of(A), List.of(A, B)));
    assertEquals("applies", refusal(due(Assortment.DELIST, true), List.of(A), List.of(B)));
  }

  @Test
  @DisplayName("A held manager's list never narrows a line that is now sold everywhere")
  void aHeldManagersListNeverNarrowsEverywhere() {
    // Recorded when the line was ranged; by the day it applies it is sold at every store again.
    assertEquals("BUSINESS_WIDE_ONLY", refusal(due(Assortment.LIST, true), List.of(A), List.of()));
    // Beside the stores it has, it is theirs to add.
    assertEquals("applies", refusal(due(Assortment.LIST, true), List.of(A), List.of(B)));
    // An owner's or a business-wide manager's list ranges a line sold everywhere, as it always has.
    assertEquals("applies", refusal(due(Assortment.LIST, false), List.of(A), List.of()));
  }

  @Test
  @DisplayName("The range after a change: added to, or taken from, as it stands")
  void theRangeAfterAChange() {
    assertEquals(List.of(B, A), Assortment.rangeAfter(Assortment.LIST, List.of(B), List.of(A)));
    assertEquals(List.of(A), Assortment.rangeAfter(Assortment.LIST, List.of(A), List.of(A)));
    assertEquals(List.of(B), Assortment.rangeAfter(Assortment.DELIST, List.of(A, B), List.of(A)));
    assertEquals(List.of(), Assortment.rangeAfter(Assortment.DELIST, List.of(A), List.of(A)));
  }

  @Test
  @DisplayName(
      "Leaving a line at no store is a de-list of every store it is sold at, and only that")
  void leavingALineAtNoStore() {
    // The one rule the door (when a change is recorded) and the sweep (when it is applied) share.
    assertTrue(Assortment.leavesNoStore(Assortment.DELIST, List.of(A), List.of(A)));
    assertTrue(Assortment.leavesNoStore(Assortment.DELIST, List.of(A, B), List.of(B, A)));
    // A store left, a line sold everywhere (its own refusal), a listing, or no store aimed at.
    assertFalse(Assortment.leavesNoStore(Assortment.DELIST, List.of(A, B), List.of(A)));
    assertFalse(Assortment.leavesNoStore(Assortment.DELIST, List.of(), List.of(A)));
    assertFalse(Assortment.leavesNoStore(Assortment.LIST, List.of(A), List.of(A)));
    assertFalse(Assortment.leavesNoStore(Assortment.DELIST, List.of(A), List.of()));
  }

  // ── a de-list nobody can apply is closed, never due for ever (2 Oct 2026) ─────────

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final Instant DECIDED = Instant.parse("2026-09-01T09:00:00Z");

  /** A change of one line, recorded {@code order} seconds into the morning, for a day. */
  private static Change open(String action, UUID store, int day, int order, boolean held) {
    return new Change(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        store,
        null,
        action,
        DAY.plusDays(day),
        "because",
        Ids.newId(),
        DECIDED.plusSeconds(order),
        null,
        null,
        held);
  }

  private static Change open(String action, UUID store, int day, int order) {
    return open(action, store, day, order, false);
  }

  private static Assortment.Step step(Change ch) {
    return new Assortment.Step(ch, List.of(ch.storeId()));
  }

  @Test
  @DisplayName("Only a de-list of a line's last stores is refused for good; every other one waits")
  void onlyTheLastStoreRefusalIsFinal() {
    // Nothing can make it apply as it reads (no rows would be every store), so it is closed.
    assertFalse(
        Assortment.refusalToApply(due(Assortment.DELIST, false), List.of(A), List.of(A))
            .orElseThrow()
            .staysDue());
    // Each of these the range can still allow: a range named, the line narrowed again, a store
    // added to the cluster.
    assertTrue(
        Assortment.refusalToApply(due(Assortment.DELIST, false), List.of(A), List.of())
            .orElseThrow()
            .staysDue());
    assertTrue(
        Assortment.refusalToApply(due(Assortment.LIST, true), List.of(A), List.of())
            .orElseThrow()
            .staysDue());
    assertTrue(Assortment.clusterEmpty().staysDue());
    assertEquals("CLUSTER_EMPTY", Assortment.clusterEmpty().code());
  }

  @Test
  @DisplayName("A change that is closed or applied is never due again")
  void aClosedChangeIsNotDue() {
    Change waiting = change(Assortment.DELIST, DAY, null);
    assertTrue(waiting.due(DAY));
    Change closed =
        new Change(
            waiting.id(),
            waiting.tenantId(),
            waiting.productId(),
            waiting.storeId(),
            null,
            Assortment.DELIST,
            DAY,
            "because",
            waiting.decidedBy(),
            waiting.createdAt(),
            null,
            null,
            false,
            Instant.now(),
            Assortment.LAST_STORE,
            "the last store");
    assertTrue(closed.refused());
    assertFalse(closed.due(DAY.plusDays(30)), "closed for good: the sweep never tries it again");
  }

  @Test
  @DisplayName("The plan tries a waiting change again, as the hourly sweep does")
  void thePlanTriesAWaitingChangeAgain() {
    // Sold everywhere: a de-list of A waits for a range. A listing at A gives it one — and then
    // the de-list would take the line out of its last store, so the sweep closes it.
    Change delist = open(Assortment.DELIST, A, 1, 0);
    Change list = open(Assortment.LIST, A, 5, 1);
    Map<UUID, Assortment.Fate> fates =
        Assortment.plan(List.of(), List.of(step(delist), step(list)));
    assertEquals(Assortment.Outcome.APPLIED, fates.get(list.id()).outcome());
    assertEquals(Assortment.Outcome.CLOSED, fates.get(delist.id()).outcome());
    assertEquals(Assortment.LAST_STORE, fates.get(delist.id()).refusal().code());
    assertEquals(List.of(), fates.get(delist.id()).rangeMet(), "on its own day: every store");
    // Without the listing the de-list only waits, never closed.
    assertEquals(
        Assortment.Outcome.WAITING,
        Assortment.plan(List.of(), List.of(step(delist))).get(delist.id()).outcome());
  }

  @Test
  @DisplayName("The plan walks changes by their day, whatever order they were recorded in")
  void thePlanWalksByDay() {
    // Ranged at A and B. The de-list of A for day 10 was recorded first, the de-list of B for day
    // 5 after it: on day 5 the line is left at A, so on day 10 the first would leave no store.
    Change first = open(Assortment.DELIST, A, 10, 0);
    Change second = open(Assortment.DELIST, B, 5, 1);
    for (List<Assortment.Step> steps :
        List.of(List.of(step(first), step(second)), List.of(step(second), step(first)))) {
      Map<UUID, Assortment.Fate> fates = Assortment.plan(List.of(A, B), steps);
      assertEquals(Assortment.Outcome.APPLIED, fates.get(second.id()).outcome());
      assertEquals(Assortment.Outcome.CLOSED, fates.get(first.id()).outcome());
      assertEquals(List.of(A), fates.get(first.id()).rangeMet());
    }
    assertEquals(
        Assortment.Outcome.APPLIED,
        Assortment.plan(List.of(A, B), List.of(step(first))).get(first.id()).outcome());
  }

  @Test
  @DisplayName("A planned swap applies both; a refused change moves nothing")
  void aSwapAppliesAndARefusalMovesNothing() {
    Change list = open(Assortment.LIST, B, 9, 0);
    Change delist = open(Assortment.DELIST, A, 10, 1);
    Map<UUID, Assortment.Fate> swap =
        Assortment.plan(List.of(A), List.of(step(list), step(delist)));
    assertEquals(Assortment.Outcome.APPLIED, swap.get(list.id()).outcome());
    assertEquals(Assortment.Outcome.APPLIED, swap.get(delist.id()).outcome());
    assertEquals(List.of(A, B), swap.get(delist.id()).rangeMet());

    // A held manager's listing of a line now sold everywhere waits and moves nothing, so the
    // de-list after it meets every store and waits too.
    Change held = open(Assortment.LIST, B, 1, 0, true);
    Change after = open(Assortment.DELIST, B, 2, 1);
    Map<UUID, Assortment.Fate> waiting =
        Assortment.plan(List.of(), List.of(step(held), step(after)));
    assertEquals(Assortment.Outcome.WAITING, waiting.get(held.id()).outcome());
    assertEquals("BUSINESS_WIDE_ONLY", waiting.get(held.id()).refusal().code());
    assertEquals(Assortment.Outcome.WAITING, waiting.get(after.id()).outcome());
    assertEquals("ASSORTMENT_RANGED_EVERYWHERE", waiting.get(after.id()).refusal().code());

    // Aimed at a cluster with no stores yet: it waits, as the sweep leaves it.
    Change empty = open(Assortment.LIST, A, 1, 0);
    Map<UUID, Assortment.Fate> none =
        Assortment.plan(List.of(B), List.of(new Assortment.Step(empty, List.of())));
    assertEquals(Assortment.Outcome.WAITING, none.get(empty.id()).outcome());
    assertEquals("CLUSTER_EMPTY", none.get(empty.id()).refusal().code());
  }
}
