package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment;
import com.storeql.product.domain.Assortment.Change;
import com.storeql.product.domain.Assortment.Cluster;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.domain.Assortment.Review;
import com.storeql.product.repo.AssortmentRepository;
import com.storeql.product.repo.AssortmentRepository.ApplyResult;
import com.storeql.product.repo.AssortmentRepository.VariantProduct;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a range does when tenant-svc, which owns stores, cannot say which stores are the business's:
 * it refuses with 503 and writes nothing, rather than accept an id it cannot check. And what a
 * manager held to stores may record and have applied (2 Oct 2026): their own stores only, the
 * business's checked before theirs, never a cluster, and never a line moved to or from every store
 * — judged when recorded, and again on the range the sweep finds. The repository is a stand-in that
 * notes every write, which is the claim: for a refusal, none.
 */
class AssortmentStoresTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID CLUSTER = Ids.newId();
  private static final UUID REVIEW = Ids.newId();
  private static final UUID STORE = Ids.newId();

  /** The business's second store, which a manager held to {@link #STORE} does not keep. */
  private static final UUID OTHER_STORE = Ids.newId();

  private static final UUID RIVAL_STORE = Ids.newId();

  /** A product of the business's, and the one variant the review reads of it. */
  private static final UUID PRODUCT = Ids.newId();

  private static final UUID VARIANT = Ids.newId();

  private static final TenantContext OWNER = CatalogueStoresTest.caller(TENANT, "OWNER", Set.of());

  /** A manager held to {@link #STORE} alone. */
  private static final TenantContext HELD =
      CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of(STORE));

  /**
   * Holds one live cluster and one open, fully decided review; notes every write made. A product's
   * range is what {@link #ranges} says: absent, not the business's; empty, sold at every store.
   */
  private static final class Repo extends AssortmentRepository {

    final List<String> writes = new ArrayList<>();
    final List<Change> recorded = new ArrayList<>();
    final Map<UUID, List<UUID>> ranges = new HashMap<>(Map.of(PRODUCT, List.of()));
    List<UUID> clusterStores = List.of(STORE);
    String decision = Assortment.KEEP;

    /** The review's status, whether it has its line, and whether it is the business's at all. */
    String reviewStatus = Assortment.OPEN;

    boolean reviewHasLines = true;
    boolean reviewExists = true;

    /** The business's changes not yet applied, oldest first; a recorded one is not added. */
    List<Change> dueChanges = List.of();

    /** Changes applied or closed for good: never due again, as the repository's columns say. */
    final Set<UUID> settled = new java.util.HashSet<>();

    @Override
    public Optional<Cluster> cluster(UUID tenantId, UUID id) {
      if (!TENANT.equals(tenantId) || !CLUSTER.equals(id)) return Optional.empty();
      return Optional.of(
          new Cluster(
              CLUSTER,
              TENANT,
              "NORTH",
              "Northern shops",
              null,
              Assortment.ACTIVE,
              Instant.now(),
              Instant.now(),
              clusterStores));
    }

    @Override
    public Map<UUID, List<UUID>> rangesOf(UUID tenantId, Collection<UUID> productIds) {
      Map<UUID, List<UUID>> found = new LinkedHashMap<>();
      if (!TENANT.equals(tenantId)) return found;
      for (UUID id : productIds) {
        if (ranges.containsKey(id)) found.put(id, ranges.get(id));
      }
      return found;
    }

    @Override
    public List<VariantProduct> productsOfVariants(UUID tenantId, List<UUID> variantIds) {
      return List.of(new VariantProduct(VARIANT, PRODUCT));
    }

    @Override
    public Map<UUID, Integer> variantCounts(UUID tenantId, List<UUID> productIds) {
      return Map.of(PRODUCT, 1);
    }

    @Override
    public List<Change> due(UUID tenantId, LocalDate asOf) {
      // As the repository answers: this business's changes not yet applied and dated on or before
      // the day, oldest first (the list is kept in that order).
      return dueChanges.stream()
          .filter(c -> TENANT.equals(tenantId) && c.due(asOf) && !settled.contains(c.id()))
          .toList();
    }

    @Override
    public List<Change> pending(UUID tenantId, Collection<UUID> productIds) {
      // Every open change of these lines, whatever its day: the plan a new one joins.
      return dueChanges.stream()
          .filter(
              c ->
                  TENANT.equals(tenantId)
                      && productIds.contains(c.productId())
                      && !settled.contains(c.id()))
          .toList();
    }

    @Override
    public ApplyResult apply(
        UUID tenantId,
        Change ch,
        List<UUID> stores,
        Function<List<UUID>, Optional<Assortment.Refusal>> judge) {
      // As the repository does: judged on the range read inside the transaction, with the product
      // row locked; a refusal that waits writes nothing, one that is final closes the change.
      if (settled.contains(ch.id())) return ApplyResult.SETTLED;
      List<UUID> current = ranges.get(ch.productId());
      Optional<Assortment.Refusal> refusal = judge.apply(current);
      if (refusal.isPresent()) {
        if (!refusal.get().staysDue()) {
          settled.add(ch.id());
          writes.add("refuse:" + refusal.get().code());
        }
        return ApplyResult.refused(refusal.get());
      }
      ranges.put(ch.productId(), Assortment.rangeAfter(ch.action(), current, stores));
      settled.add(ch.id());
      writes.add("apply:" + ch.action());
      return ApplyResult.APPLIED;
    }

    @Override
    public void addMembers(UUID tenantId, UUID clusterId, List<UUID> storeIds) {
      writes.add("addMembers");
    }

    @Override
    public Change record(Change ch) {
      writes.add("record");
      recorded.add(ch);
      return ch;
    }

    @Override
    public Optional<Review> review(UUID tenantId, UUID id) {
      if (!reviewExists || !TENANT.equals(tenantId) || !REVIEW.equals(id)) return Optional.empty();
      Line kept =
          new Line(
              Ids.newId(),
              TENANT,
              REVIEW,
              VARIANT,
              null,
              null,
              null,
              null,
              1,
              decision,
              null,
              false);
      return Optional.of(
          new Review(
              REVIEW,
              TENANT,
              Ids.newId(),
              "H2 review",
              LocalDate.of(2026, 1, 1),
              LocalDate.of(2026, 7, 1),
              reviewStatus,
              null,
              Instant.now(),
              null,
              reviewHasLines ? List.of(kept) : List.of()));
    }

    @Override
    public boolean close(UUID tenantId, UUID reviewId, UUID actorId, List<Change> produced) {
      writes.add("close");
      recorded.addAll(produced);
      return true;
    }

    @Override
    public void addLines(UUID tenantId, UUID reviewId, List<Line> lines) {
      writes.add("addLines");
    }
  }

  private final Repo repo = new Repo();
  private final AssortmentService svc = new AssortmentService();

  AssortmentStoresTest() {
    svc.repo = repo;
    // tenant-svc cannot be read: no profile and no stores, which is what an outage looks like.
    svc.profiles = TenantProfiles.forTest(tenant -> Optional.empty(), Clock.systemUTC());
  }

  private static void assertUnverifiable(ApiException refused) {
    assertThat(refused.status(), is(503));
    assertThat(refused.code(), is("TENANT_STORES_UNAVAILABLE"));
  }

  @Test
  @DisplayName("A cluster takes no store while tenant-svc cannot say which are the business's")
  void aClusterTakesNoStoreItCannotCheck() {
    ApiException refused =
        assertThrows(ApiException.class, () -> svc.addMembers(OWNER, CLUSTER, List.of(STORE)));
    assertUnverifiable(refused);
    assertThat("nothing was added", repo.writes, is(empty()));
  }

  @Test
  @DisplayName("A change aimed at a store is not recorded while the store cannot be checked")
  void aChangeAimedAtAStoreItCannotCheckIsNotRecorded() {
    ApiException refused =
        assertThrows(
            ApiException.class,
            () ->
                svc.record(
                    OWNER,
                    PRODUCT,
                    STORE,
                    null,
                    "LIST",
                    LocalDate.of(2026, 10, 1),
                    "Range review"));
    assertUnverifiable(refused);
    assertThat("nothing was recorded", repo.writes, is(empty()));
  }

  @Test
  @DisplayName("A review is not closed against a store that cannot be checked, and stays open")
  void aReviewIsNotClosedAgainstAStoreItCannotCheck() {
    ApiException refused =
        assertThrows(ApiException.class, () -> svc.close(OWNER, REVIEW, STORE, null, null));
    assertUnverifiable(refused);
    assertThat("nothing was closed", repo.writes, is(empty()));
  }

  @Test
  @DisplayName("A change aimed at a cluster asks tenant-svc nothing")
  void aChangeAimedAtAClusterNeedsNoStoreCheck() {
    // The stores of a cluster were checked when they were added; naming the cluster is not naming a
    // store, so an outage of tenant-svc does not stop a change from being planned against one.
    svc.record(OWNER, PRODUCT, null, CLUSTER, "LIST", LocalDate.of(2026, 10, 1), "Range review");
    assertThat(repo.writes, is(List.of("record")));
  }

  @Test
  @DisplayName("A cluster is not given an empty list of stores")
  void aClusterIsNotGivenNoStores() {
    // The request's own validation says so first over HTTP; the service holds to it as well, and
    // asks tenant-svc nothing for a list with nothing in it.
    ApiException refused =
        assertThrows(ApiException.class, () -> svc.addMembers(OWNER, CLUSTER, List.of()));
    assertThat(refused.status(), is(400));
    assertThat(refused.code(), is("CLUSTER_STORES_REQUIRED"));
    assertThat("nothing was added", repo.writes, is(empty()));
  }

  @Test
  @DisplayName("A review is not given an empty list of lines")
  void aReviewIsNotGivenNoLines() {
    ApiException refused =
        assertThrows(ApiException.class, () -> svc.addLines(TENANT, REVIEW, List.of()));
    assertThat(refused.status(), is(400));
    assertThat(refused.code(), is("REVIEW_LINES_REQUIRED"));
    assertThat("nothing was added", repo.writes, is(empty()));
  }

  // ── one store check, the range's own refusal (2 Oct 2026) ───────────────────────

  /** tenant-svc as it answers when it can be read: this business has two stores, the rival one. */
  private void tenantSvcKnowsTheStore() {
    svc.profiles =
        new TenantProfiles() {
          @Override
          public Stores stores(UUID tenantId, UUID including) {
            return new Stores(
                TENANT.equals(tenantId)
                    ? Set.of(STORE, OTHER_STORE)
                    : RIVAL.equals(tenantId) ? Set.of(RIVAL_STORE) : Set.of(),
                Map.of());
          }
        };
  }

  @Test
  @DisplayName("A store that is not the business's is refused in the range's own words")
  void aStoreThatIsNotTheBusinesssKeepsTheRangesRefusal() {
    tenantSvcKnowsTheStore();
    UUID notOurs = Ids.newId();

    ApiException members =
        assertThrows(
            ApiException.class, () -> svc.addMembers(OWNER, CLUSTER, List.of(STORE, notOurs)));
    ApiException change =
        assertThrows(
            ApiException.class,
            () ->
                svc.record(
                    OWNER,
                    PRODUCT,
                    notOurs,
                    null,
                    "LIST",
                    LocalDate.of(2026, 10, 1),
                    "Range review"));
    for (ApiException refused : List.of(members, change)) {
      assertThat(refused.status(), is(404));
      assertThat(
          "the code on the wire is the range's, as it was",
          refused.code(),
          is("ASSORTMENT_STORE_NOT_FOUND"));
    }
    assertThat("nothing was written", repo.writes, is(empty()));

    svc.addMembers(OWNER, CLUSTER, List.of(STORE));
    assertThat(repo.writes, is(List.of("addMembers")));
  }

  // ── a review line's money is in its currency's minor units (2 Oct 2026) ─────────

  private static Line line(String revenue, String margin, String currency) {
    return new Line(
        null,
        TENANT,
        REVIEW,
        Ids.newId(),
        null,
        revenue == null ? null : new java.math.BigDecimal(revenue),
        margin == null ? null : new java.math.BigDecimal(margin),
        currency,
        1,
        null,
        null,
        false);
  }

  private ApiException refusedLine(Line l) {
    return assertThrows(ApiException.class, () -> svc.addLines(TENANT, REVIEW, List.of(l)));
  }

  @Test
  @DisplayName("Revenue and margin carry no more decimal places than their currency has")
  void aLinesMoneyIsHeldToItsCurrencysMinorUnits() {
    record Case(String revenue, String margin, String currency, String says) {}
    for (Case c :
        List.of(
            new Case("12.345", null, "GBP", "revenue has more decimal places than GBP has (2)"),
            new Case("1200.5", null, "JPY", "revenue has more decimal places than JPY has (0)"),
            new Case("12.3456", null, "KWD", "revenue has more decimal places than KWD has (3)"),
            new Case(null, "-0.001", "EUR", "margin has more decimal places than EUR has (2)"),
            new Case("100000000000000", null, "EUR", "revenue is too large to keep"))) {
      ApiException refused = refusedLine(line(c.revenue(), c.margin(), c.currency()));
      assertThat(c.toString(), refused.status(), is(400));
      assertThat(c.toString(), refused.code(), is("REVIEW_LINE_FIGURES"));
      assertThat(c.toString(), refused.getMessage(), is(c.says()));
    }
    assertThat("nothing was added", repo.writes, is(empty()));

    // Each at its own currency's minor units goes in: yen whole, dinar to the fils, a loss too.
    svc.addLines(
        TENANT,
        REVIEW,
        List.of(
            line("1200", "-300", "JPY"),
            line("12.345", "1.5", "KWD"),
            line("12.50", "-0.01", "GBP"),
            line("12.5000", null, "EUR")));
    assertThat(repo.writes, is(List.of("addLines")));
  }

  @Test
  @DisplayName("A currency ISO 4217 does not know is refused, not kept as three letters")
  void anUnknownCurrencyIsRefused() {
    ApiException refused = refusedLine(line("12.00", null, "ZZZ"));
    assertThat(refused.status(), is(400));
    assertThat(refused.code(), is("REVIEW_LINE_CURRENCY"));
    assertThat("nothing was added", repo.writes, is(empty()));
  }

  // ── a manager held to stores changes the range only at theirs (2 Oct 2026) ──────

  private static void assertRefused(ApiException e, int status, String code) {
    assertThat(e.getMessage(), e.status(), is(status));
    assertThat(e.getMessage(), e.code(), is(code));
  }

  private ApiException refusedChange(
      TenantContext ctx, UUID product, UUID store, UUID cluster, String action) {
    return assertThrows(
        ApiException.class,
        () -> svc.record(ctx, product, store, cluster, action, LocalDate.of(2026, 10, 1), "why"));
  }

  @Test
  @DisplayName("A held manager's change is the business's store first (404), then theirs (403)")
  void aHeldManagersStoreIsTheBusinesssThenTheirs() {
    tenantSvcKnowsTheStore();
    repo.ranges.put(PRODUCT, List.of(OTHER_STORE));

    // Another business's store is not found, whoever asks: the same answer an owner gets.
    assertRefused(
        refusedChange(HELD, PRODUCT, RIVAL_STORE, null, "LIST"), 404, "ASSORTMENT_STORE_NOT_FOUND");
    // The business's store they do not keep.
    assertRefused(
        refusedChange(HELD, PRODUCT, OTHER_STORE, null, "LIST"), 403, "STORE_ACCESS_DENIED");
    assertRefused(
        assertThrows(
            ApiException.class, () -> svc.addMembers(HELD, CLUSTER, List.of(STORE, RIVAL_STORE))),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertRefused(
        assertThrows(
            ApiException.class, () -> svc.addMembers(HELD, CLUSTER, List.of(STORE, OTHER_STORE))),
        403,
        "STORE_ACCESS_DENIED");
    assertThat("nothing was written", repo.writes, is(empty()));

    // Their own store, beside the store it is already sold at: recorded, and marked as theirs.
    svc.record(HELD, PRODUCT, STORE, null, "LIST", LocalDate.of(2026, 10, 1), "why");
    assertThat(repo.writes, is(List.of("record")));
    assertThat(
        "recorded by a manager held to stores", repo.recorded.get(0).heldToStores(), is(true));
  }

  @Test
  @DisplayName("A held manager does not aim a change at a cluster, even one of only their stores")
  void aHeldManagerDoesNotAimAtACluster() {
    // The cluster holds their store alone today; it is read again on the day the change applies,
    // by which time it may hold stores they do not keep.
    repo.clusterStores = List.of(STORE);
    for (String action : new String[] {"LIST", "DELIST"}) {
      assertRefused(refusedChange(HELD, PRODUCT, null, CLUSTER, action), 403, "BUSINESS_WIDE_ONLY");
    }
    repo.clusterStores = List.of(STORE, OTHER_STORE);
    assertRefused(refusedChange(HELD, PRODUCT, null, CLUSTER, "LIST"), 403, "BUSINESS_WIDE_ONLY");
    assertThat("nothing was recorded", repo.writes, is(empty()));

    // An owner aims at it, and the change is not marked as a held manager's.
    svc.record(OWNER, PRODUCT, null, CLUSTER, "LIST", LocalDate.of(2026, 10, 1), "why");
    assertThat(repo.recorded.get(0).heldToStores(), is(false));
  }

  @Test
  @DisplayName("A held manager does not list or de-list a line sold at every store")
  void aHeldManagerDoesNotNarrowALineSoldEverywhere() {
    tenantSvcKnowsTheStore();
    // Sold everywhere: listing it at their store would leave it sold there alone; de-listing it
    // there cannot be said without naming every other store.
    repo.ranges.put(PRODUCT, List.of());
    for (String action : new String[] {"LIST", "DELIST"}) {
      assertRefused(refusedChange(HELD, PRODUCT, STORE, null, action), 403, "BUSINESS_WIDE_ONLY");
    }
    // Who can do it is named: an owner or a manager of the whole business.
    assertThat(
        refusedChange(HELD, PRODUCT, STORE, null, "LIST").getMessage(),
        containsString("an owner or a manager of the whole business"));
    assertThat(
        refusedChange(HELD, PRODUCT, STORE, null, "DELIST").getMessage(),
        containsString("an owner or a manager of the whole business"));
    // Sold at their store alone: de-listing it there would leave no store, which means every store.
    // That is nobody's to do, an owner's no more than theirs, so they are not sent to one: they
    // are told what does stop it being sold (2 Oct 2026; it was 403 BUSINESS_WIDE_ONLY).
    repo.ranges.put(PRODUCT, List.of(STORE));
    ApiException last = refusedChange(HELD, PRODUCT, STORE, null, "DELIST");
    assertRefused(last, 409, "ASSORTMENT_LAST_STORE");
    assertThat(last.getMessage(), containsString("discontinue the line"));
    assertThat("nothing was recorded", repo.writes, is(empty()));

    // Sold at their store and another: de-listing it at theirs is theirs to do.
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    svc.record(HELD, PRODUCT, STORE, null, "DELIST", LocalDate.of(2026, 10, 1), "why");
    // And the owner may plan either against a line sold everywhere, as before.
    repo.ranges.put(PRODUCT, List.of());
    svc.record(OWNER, PRODUCT, STORE, null, "LIST", LocalDate.of(2026, 10, 1), "why");
    assertThat(repo.writes, is(List.of("record", "record")));
  }

  @Test
  @DisplayName("A change to another business's product is not found, and records nothing")
  void aChangeToAnotherBusinesssProductIsNotFound() {
    tenantSvcKnowsTheStore();
    UUID neverMade = Ids.newId();
    assertRefused(refusedChange(OWNER, neverMade, STORE, null, "LIST"), 404, "PRODUCT_NOT_FOUND");
    // The rival's staff, every tier, naming our product, and our store or theirs.
    for (TenantContext theirs :
        List.of(
            CatalogueStoresTest.caller(RIVAL, "OWNER", Set.of()),
            CatalogueStoresTest.caller(RIVAL, "MANAGER", Set.of()),
            CatalogueStoresTest.caller(RIVAL, "MANAGER", Set.of(STORE)),
            CatalogueStoresTest.caller(RIVAL, "MANAGER", Set.of(RIVAL_STORE)))) {
      for (UUID store : List.of(STORE, RIVAL_STORE)) {
        assertRefused(
            refusedChange(theirs, PRODUCT, store, null, "LIST"), 404, "PRODUCT_NOT_FOUND");
      }
    }
    assertThat("nothing was recorded", repo.writes, is(empty()));
  }

  @Test
  @DisplayName(
      "A held manager's review does not close on a change they may not make, nor a cluster")
  void aHeldManagersReviewClosesOnlyOnChangesTheyMayMake() {
    tenantSvcKnowsTheStore();
    // The line is introduced: a LIST of a product sold at every store, at their store.
    repo.decision = Assortment.INTRODUCE;
    repo.ranges.put(PRODUCT, List.of());
    assertRefused(
        assertThrows(ApiException.class, () -> svc.close(HELD, REVIEW, STORE, null, null)),
        403,
        "BUSINESS_WIDE_ONLY");
    // Aimed at a cluster.
    assertRefused(
        assertThrows(ApiException.class, () -> svc.close(HELD, REVIEW, null, CLUSTER, null)),
        403,
        "BUSINESS_WIDE_ONLY");
    // A store of the business they do not keep, and another business's.
    assertRefused(
        assertThrows(ApiException.class, () -> svc.close(HELD, REVIEW, OTHER_STORE, null, null)),
        403,
        "STORE_ACCESS_DENIED");
    assertRefused(
        assertThrows(ApiException.class, () -> svc.close(HELD, REVIEW, RIVAL_STORE, null, null)),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    assertThat("the review stays open, nothing recorded", repo.writes, is(empty()));

    // Ranged elsewhere: introducing it at their store too is theirs to do.
    repo.ranges.put(PRODUCT, List.of(OTHER_STORE));
    svc.close(HELD, REVIEW, STORE, null, null);
    assertThat(repo.writes, is(List.of("close")));
    assertThat(repo.recorded.get(0).action(), is(Assortment.LIST));
    assertThat(repo.recorded.get(0).heldToStores(), is(true));
  }

  private static Change dueChange(String action, UUID store, boolean held) {
    return new Change(
        Ids.newId(),
        TENANT,
        PRODUCT,
        store,
        null,
        action,
        LocalDate.of(2026, 10, 1),
        "why",
        Ids.newId(),
        Instant.now(),
        null,
        null,
        held);
  }

  @Test
  @DisplayName(
      "The sweep judges each change on the range it finds: a last-store de-list is closed, the"
          + " rest wait")
  void theSweepJudgesTheRangeItFinds() {
    // Recorded by a held manager while the line was ranged elsewhere; since then it has been made
    // sold at every store. Listing it at theirs now would take it off every other shelf.
    repo.ranges.put(PRODUCT, List.of());
    repo.dueChanges = List.of(dueChange("LIST", STORE, true));
    AssortmentService.SweepResult held = svc.applyDue(TENANT, LocalDate.of(2026, 10, 1));
    assertThat(held.applied(), is(0));
    assertThat(held.notApplied().get(0).code(), is("BUSINESS_WIDE_ONLY"));
    assertThat(
        "it waits: the line may be narrowed again", held.notApplied().get(0).stillDue(), is(true));
    assertThat("still sold everywhere", repo.ranges.get(PRODUCT), is(empty()));
    assertThat("nothing was written", repo.writes, is(empty()));

    // De-listing the last store it is sold at, whoever decided it: no rows would be every store.
    // Nothing can make it apply as it reads, so it is closed as refused rather than left due for
    // ever (2 Oct 2026), and the next sweep does not meet it again.
    repo.ranges.put(PRODUCT, List.of(STORE));
    repo.dueChanges = List.of(dueChange("DELIST", STORE, false));
    AssortmentService.SweepResult last = svc.applyDue(TENANT, LocalDate.of(2026, 10, 1));
    assertThat(last.applied(), is(0));
    assertThat(last.notApplied().get(0).code(), is("ASSORTMENT_LAST_STORE"));
    assertThat("closed, not due", last.notApplied().get(0).stillDue(), is(false));
    assertThat(repo.ranges.get(PRODUCT), is(List.of(STORE)));
    assertThat(
        "the range is untouched; the change is closed",
        repo.writes,
        is(List.of("refuse:ASSORTMENT_LAST_STORE")));
    AssortmentService.SweepResult again = svc.applyDue(TENANT, LocalDate.of(2026, 12, 1));
    assertThat(again.applied(), is(0));
    assertThat("never reported again", again.notApplied(), is(empty()));
    repo.writes.clear();

    // An owner's list of a line sold everywhere ranges it, as it always has.
    repo.ranges.put(PRODUCT, List.of());
    repo.dueChanges = List.of(dueChange("LIST", STORE, false));
    assertThat(svc.applyDue(TENANT, LocalDate.of(2026, 10, 1)).applied(), is(1));
    assertThat(repo.ranges.get(PRODUCT), is(List.of(STORE)));
  }

  // ── a de-list nobody can apply is refused when it is recorded (2 Oct 2026) ──────

  private static Change planned(String action, UUID store, LocalDate day) {
    return new Change(
        Ids.newId(),
        TENANT,
        PRODUCT,
        store,
        null,
        action,
        day,
        "planned",
        Ids.newId(),
        Instant.now(),
        null,
        null,
        false);
  }

  @Test
  @DisplayName("An owner's de-list of a line's last store is refused when recorded, not left due")
  void anOwnersDelistOfTheLastStoreIsRefusedWhenRecorded() {
    tenantSvcKnowsTheStore();
    // Sold at one store: taking it out of that one would leave no rows, which reads as every
    // store. The sweep would refuse it on every run, and it would stay due for ever.
    repo.ranges.put(PRODUCT, List.of(STORE));
    ApiException refused = refusedChange(OWNER, PRODUCT, STORE, null, "DELIST");
    assertRefused(refused, 409, "ASSORTMENT_LAST_STORE");
    assertThat(refused.getMessage(), containsString("discontinue the line"));
    // Aimed at a cluster that holds every store it is sold at, and one more.
    repo.clusterStores = List.of(STORE, OTHER_STORE);
    assertRefused(
        refusedChange(OWNER, PRODUCT, null, CLUSTER, "DELIST"), 409, "ASSORTMENT_LAST_STORE");
    assertThat("nothing was recorded", repo.writes, is(empty()));

    // Sold at two: taking it out of one leaves the other, and is recorded.
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    svc.record(OWNER, PRODUCT, STORE, null, "DELIST", LocalDate.of(2026, 10, 1), "why");
    // Sold everywhere: the de-list waits, due, for the line to be given a range, as it always has.
    repo.ranges.put(PRODUCT, List.of());
    svc.record(OWNER, PRODUCT, STORE, null, "DELIST", LocalDate.of(2026, 10, 1), "why");
    assertThat(repo.writes, is(List.of("record", "record")));
  }

  @Test
  @DisplayName("A de-list is judged on the range as planned for its day, not only as it stands")
  void aDelistIsJudgedOnTheRangeAsPlannedForItsDay() {
    tenantSvcKnowsTheStore();
    repo.ranges.put(PRODUCT, List.of(STORE));
    LocalDate day = LocalDate.of(2026, 10, 1);

    // A listing at the other store is already planned for the day before: the swap is recorded.
    repo.dueChanges = List.of(planned("LIST", OTHER_STORE, day.minusDays(1)));
    svc.record(OWNER, PRODUCT, STORE, null, "DELIST", day, "swap");
    assertThat(repo.writes, is(List.of("record")));

    // Planned for after it: on its own day the line would still be at this store alone.
    repo.dueChanges = List.of(planned("LIST", OTHER_STORE, day.plusDays(1)));
    assertRefused(
        assertThrows(
            ApiException.class,
            () -> svc.record(OWNER, PRODUCT, STORE, null, "DELIST", day, "swap")),
        409,
        "ASSORTMENT_LAST_STORE");

    // Sold at two, with the other already planned out first: this one would be the last.
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    repo.dueChanges = List.of(planned("DELIST", OTHER_STORE, day.minusDays(1)));
    assertRefused(
        assertThrows(
            ApiException.class,
            () -> svc.record(OWNER, PRODUCT, STORE, null, "DELIST", day, "why")),
        409,
        "ASSORTMENT_LAST_STORE");

    // A planned change the sweep would refuse moves nothing. A held manager's listing at the other
    // store of a line now sold everywhere will not apply, so on the day the line is still sold
    // everywhere; read as applied, it would have made this de-list look like the last store's.
    repo.ranges.put(PRODUCT, List.of());
    Change heldListing =
        new Change(
            Ids.newId(),
            TENANT,
            PRODUCT,
            OTHER_STORE,
            null,
            "LIST",
            day.minusDays(1),
            "planned",
            Ids.newId(),
            Instant.now(),
            null,
            null,
            true);
    repo.dueChanges = List.of(heldListing);
    svc.record(OWNER, PRODUCT, OTHER_STORE, null, "DELIST", day, "why");
    assertThat(repo.writes, is(List.of("record", "record")));
  }

  @Test
  @DisplayName(
      "A review that would de-list a line from its last store does not close, and stays open")
  void aReviewThatWouldLeaveALineNowhereDoesNotClose() {
    tenantSvcKnowsTheStore();
    repo.decision = Assortment.DELIST;
    repo.ranges.put(PRODUCT, List.of(STORE));
    for (TenantContext who : List.of(OWNER, HELD)) {
      ApiException refused =
          assertThrows(ApiException.class, () -> svc.close(who, REVIEW, STORE, null, null));
      assertRefused(refused, 409, "ASSORTMENT_LAST_STORE");
      assertThat(refused.getMessage(), containsString(PRODUCT.toString()));
    }
    repo.clusterStores = List.of(STORE);
    assertRefused(
        assertThrows(ApiException.class, () -> svc.close(OWNER, REVIEW, null, CLUSTER, null)),
        409,
        "ASSORTMENT_LAST_STORE");
    assertThat("the review stays open, nothing recorded", repo.writes, is(empty()));

    // Sold at another store too: it closes, and the de-list is recorded.
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    svc.close(OWNER, REVIEW, STORE, null, null);
    assertThat(repo.writes, is(List.of("close")));
    assertThat(repo.recorded.get(0).action(), is(Assortment.DELIST));
  }

  // ── no order of recording leaves a de-list due for ever (2 Oct 2026) ─────────────

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  @Test
  @DisplayName(
      "A de-list that would make one already recorded for a later day the last store's is refused")
  void aDelistThatWouldStrandOneRecordedForLaterIsRefused() {
    tenantSvcKnowsTheStore();
    // Ranged at both. The de-list of STORE for day 10 is recorded first; a de-list of OTHER_STORE
    // for day 5 would leave STORE alone, so on day 10 the first would leave the line nowhere.
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    Change recordedFirst = planned("DELIST", STORE, DAY.plusDays(10));
    repo.dueChanges = List.of(recordedFirst);
    ApiException refused =
        assertThrows(
            ApiException.class,
            () -> svc.record(OWNER, PRODUCT, OTHER_STORE, null, "DELIST", DAY.plusDays(5), "why"));
    assertRefused(refused, 409, "ASSORTMENT_LAST_STORE");
    assertThat(refused.getMessage(), containsString(DAY.plusDays(10).toString()));
    assertThat(refused.getMessage(), containsString("discontinue the line"));
    // The held manager of STORE, de-listing theirs early while OTHER_STORE's is recorded for later.
    repo.dueChanges = List.of(planned("DELIST", OTHER_STORE, DAY.plusDays(10)));
    assertRefused(
        assertThrows(
            ApiException.class,
            () -> svc.record(HELD, PRODUCT, STORE, null, "DELIST", DAY.plusDays(5), "why")),
        409,
        "ASSORTMENT_LAST_STORE");
    assertThat("nothing was recorded", repo.writes, is(empty()));

    // With a listing at a third place planned before both, the two de-lists leave it there.
    UUID third = Ids.newId();
    repo.dueChanges = List.of(recordedFirst, planned("LIST", third, DAY.plusDays(1)));
    svc.record(OWNER, PRODUCT, OTHER_STORE, null, "DELIST", DAY.plusDays(5), "why");
    assertThat(repo.writes, is(List.of("record")));
  }

  @Test
  @DisplayName(
      "A listing that gives a waiting de-list its last store is recorded; the sweep closes the"
          + " de-list")
  void aListingIsRecordedAndTheDelistItStrandsIsClosed() {
    tenantSvcKnowsTheStore();
    // Sold everywhere: the de-list of STORE waits, due, for the line to be given a range.
    repo.ranges.put(PRODUCT, List.of());
    svc.record(OWNER, PRODUCT, STORE, null, "DELIST", DAY, "why");
    repo.dueChanges = new ArrayList<>(repo.recorded);
    // A later listing at STORE alone gives it one. The listing is the later decision and stands;
    // it is not refused, because no recorded change can be withdrawn and listing is the remedy.
    svc.record(OWNER, PRODUCT, STORE, null, "LIST", DAY.plusDays(4), "why");
    repo.dueChanges = new ArrayList<>(repo.recorded);
    assertThat(repo.writes, is(List.of("record", "record")));
    repo.writes.clear();

    // The day's first sweep: the de-list still meets every store and waits; the listing applies.
    AssortmentService.SweepResult first = svc.applyDue(TENANT, DAY.plusDays(4));
    assertThat(first.applied(), is(1));
    assertThat(first.notApplied().get(0).code(), is("ASSORTMENT_RANGED_EVERYWHERE"));
    assertThat(first.notApplied().get(0).stillDue(), is(true));
    // The next: the de-list would now leave no store, and is closed — not left due for ever.
    AssortmentService.SweepResult second = svc.applyDue(TENANT, DAY.plusDays(4));
    assertThat(second.applied(), is(0));
    assertThat(second.notApplied().get(0).code(), is("ASSORTMENT_LAST_STORE"));
    assertThat(second.notApplied().get(0).stillDue(), is(false));
    assertThat(repo.ranges.get(PRODUCT), is(List.of(STORE)));
    assertThat(svc.applyDue(TENANT, DAY.plusDays(40)).notApplied(), is(empty()));
    assertThat(repo.due(TENANT, DAY.plusDays(40)), is(empty()));
  }

  @Test
  @DisplayName("A cluster that grows to hold every store does not keep its de-list due for ever")
  void aClusterThatGrowsDoesNotKeepItsDelistDue() {
    tenantSvcKnowsTheStore();
    // Ranged at both; the cluster holds STORE alone today, so de-listing it leaves OTHER_STORE.
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    repo.clusterStores = List.of(STORE);
    svc.record(OWNER, PRODUCT, null, CLUSTER, "DELIST", DAY, "why");
    repo.dueChanges = new ArrayList<>(repo.recorded);
    // Before the day, OTHER_STORE joins: membership is read on the day, and now holds every store.
    repo.clusterStores = List.of(STORE, OTHER_STORE);
    repo.writes.clear();

    AssortmentService.SweepResult swept = svc.applyDue(TENANT, DAY);
    assertThat(swept.notApplied().get(0).code(), is("ASSORTMENT_LAST_STORE"));
    assertThat(swept.notApplied().get(0).stillDue(), is(false));
    assertThat(repo.writes, is(List.of("refuse:ASSORTMENT_LAST_STORE")));
    assertThat("still sold at both", repo.ranges.get(PRODUCT), is(List.of(STORE, OTHER_STORE)));
    assertThat(svc.applyDue(TENANT, DAY.plusDays(1)).notApplied(), is(empty()));
  }

  @Test
  @DisplayName("A review whose de-list would strand one already recorded does not close")
  void aReviewThatWouldStrandARecordedDelistDoesNotClose() {
    tenantSvcKnowsTheStore();
    repo.decision = Assortment.DELIST;
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    // OTHER_STORE's de-list is recorded for a day after the review's (today).
    repo.dueChanges = List.of(planned("DELIST", OTHER_STORE, LocalDate.now().plusDays(10)));
    assertRefused(
        assertThrows(ApiException.class, () -> svc.close(OWNER, REVIEW, STORE, null, null)),
        409,
        "ASSORTMENT_LAST_STORE");
    assertThat("the review stays open, nothing recorded", repo.writes, is(empty()));
  }

  // ── a closing review is judged in the order every door keeps (2 Oct 2026) ───────

  private ApiException refusedClose(TenantContext who, UUID store, UUID cluster) {
    return assertThrows(ApiException.class, () -> svc.close(who, REVIEW, store, cluster, null));
  }

  @Test
  @DisplayName("A closing review is judged 400, then 404, then 403, and only then its state (409)")
  void aClosingReviewIsJudgedRequestThenWhatItNamesThenItsState() {
    // tenant-svc cannot be read, as the class starts: a request with no target is still told so,
    // and tenant-svc is not asked about a closed review's store before the request is judged.
    repo.reviewStatus = Assortment.DECIDED;
    assertRefused(refusedClose(OWNER, null, null), 400, "ASSORTMENT_TARGET_REQUIRED");

    tenantSvcKnowsTheStore();
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    UUID noCluster = Ids.newId();
    record State(String code, java.util.function.Consumer<Repo> set) {}
    for (State state :
        List.of(
            new State("REVIEW_NOT_OPEN", r -> r.reviewStatus = Assortment.DECIDED),
            new State("REVIEW_EMPTY", r -> r.reviewHasLines = false),
            new State("REVIEW_LINES_UNDECIDED", r -> r.decision = null))) {
      repo.reviewStatus = Assortment.OPEN;
      repo.reviewHasLines = true;
      repo.decision = Assortment.KEEP;
      state.set().accept(repo);

      // 400: neither target, or both — before anything is looked up.
      assertRefused(refusedClose(OWNER, null, null), 400, "ASSORTMENT_TARGET_REQUIRED");
      assertRefused(refusedClose(HELD, STORE, CLUSTER), 400, "ASSORTMENT_TARGET_REQUIRED");
      // 404: a store or a cluster that is not the business's, whoever asks.
      assertRefused(refusedClose(OWNER, RIVAL_STORE, null), 404, "ASSORTMENT_STORE_NOT_FOUND");
      assertRefused(refusedClose(HELD, RIVAL_STORE, null), 404, "ASSORTMENT_STORE_NOT_FOUND");
      assertRefused(refusedClose(OWNER, null, noCluster), 404, "CLUSTER_NOT_FOUND");
      // 403: the business's store they do not keep; any cluster, for a manager held to stores.
      assertRefused(refusedClose(HELD, OTHER_STORE, null), 403, "STORE_ACCESS_DENIED");
      assertRefused(refusedClose(HELD, null, CLUSTER), 403, "BUSINESS_WIDE_ONLY");
      // 409: only then the review's own state, to a caller who may close it there.
      assertRefused(refusedClose(OWNER, STORE, null), 409, state.code());
      assertRefused(refusedClose(OWNER, null, CLUSTER), 409, state.code());
      assertRefused(refusedClose(HELD, STORE, null), 409, state.code());
    }

    // A review that is not the business's is not found before the store it names is asked about.
    repo.reviewStatus = Assortment.OPEN;
    repo.reviewHasLines = true;
    repo.decision = Assortment.KEEP;
    repo.reviewExists = false;
    assertRefused(refusedClose(OWNER, null, null), 400, "ASSORTMENT_TARGET_REQUIRED");
    assertRefused(refusedClose(OWNER, RIVAL_STORE, null), 404, "REVIEW_NOT_FOUND");
    assertRefused(refusedClose(HELD, OTHER_STORE, null), 404, "REVIEW_NOT_FOUND");
    assertRefused(refusedClose(HELD, null, CLUSTER), 404, "REVIEW_NOT_FOUND");
    assertThat("nothing was closed or recorded", repo.writes, is(empty()));

    // The same review, open and decided, closes at the store the caller keeps.
    repo.reviewExists = true;
    svc.close(HELD, REVIEW, STORE, null, null);
    assertThat(repo.writes, is(List.of("close")));
  }

  // ── a held manager is told what they can do about a last store (2 Oct 2026) ─────

  @Test
  @DisplayName(
      "A last-store refusal points a held manager at what they can do; an owner's is unchanged")
  void aLastStoreRefusalPointsAHeldManagerAtWhatTheyCanDo() {
    tenantSvcKnowsTheStore();
    repo.ranges.put(PRODUCT, List.of(STORE));

    // Held to STORE alone: no store of theirs would be left selling it, so they are sent to who can
    // list it elsewhere, never told to list it at a store they cannot range.
    ApiException alone = refusedChange(HELD, PRODUCT, STORE, null, "DELIST");
    assertRefused(alone, 409, "ASSORTMENT_LAST_STORE");
    assertThat(alone.getMessage(), not(containsString("list it at another store first")));
    assertThat(alone.getMessage(), not(containsString("another of your stores")));
    assertThat(
        alone.getMessage(), containsString("ask an owner or a manager of the whole business"));
    // Discontinuing is the whole business's too (403 BUSINESS_WIDE_ONLY at the lifecycle door, 2
    // Oct 2026), so they are told who can stop it being sold, never to discontinue it themselves.
    assertThat(
        alone.getMessage(),
        containsString(
            "to stop selling it, ask an owner or a manager of the whole business to discontinue"
                + " the line"));
    assertThat(alone.getMessage(), not(containsString("to stop selling it, discontinue")));

    // Held to STORE and a second shop: pointed at theirs first, and at who can list it elsewhere.
    TenantContext heldToTwo =
        CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of(STORE, Ids.newId()));
    ApiException two = refusedChange(heldToTwo, PRODUCT, STORE, null, "DELIST");
    assertRefused(two, 409, "ASSORTMENT_LAST_STORE");
    assertThat(two.getMessage(), containsString("list it at another of your stores first"));
    assertThat(two.getMessage(), containsString("ask an owner or a manager of the whole business"));
    assertThat(
        two.getMessage(),
        containsString("ask an owner or a manager of the whole business to discontinue the line"));

    // The owner's wording is as it was: every store is theirs to list it at, and the line theirs
    // to discontinue. So is a manager's of the whole business, who may do both.
    TenantContext businessWide = CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of());
    for (TenantContext whole : List.of(OWNER, businessWide)) {
      ApiException owner = refusedChange(whole, PRODUCT, STORE, null, "DELIST");
      assertThat(
          owner.getMessage(),
          containsString(
              "to stop selling it, discontinue the line; to move it, list it at another store"
                  + " first"));
      assertThat(owner.getMessage(), not(containsString("an owner or a manager")));
    }

    // A de-list that would strand one recorded for later: the held manager is told the same.
    repo.ranges.put(PRODUCT, List.of(STORE, OTHER_STORE));
    repo.dueChanges = List.of(planned("DELIST", OTHER_STORE, DAY.plusDays(10)));
    ApiException stranding =
        assertThrows(
            ApiException.class,
            () -> svc.record(HELD, PRODUCT, STORE, null, "DELIST", DAY.plusDays(5), "why"));
    assertRefused(stranding, 409, "ASSORTMENT_LAST_STORE");
    assertThat(stranding.getMessage(), containsString(DAY.plusDays(10).toString()));
    assertThat(stranding.getMessage(), not(containsString("list it at another store first")));
    assertThat(
        stranding.getMessage(), containsString("ask an owner or a manager of the whole business"));
    ApiException strandingByOwner =
        assertThrows(
            ApiException.class,
            () -> svc.record(OWNER, PRODUCT, STORE, null, "DELIST", DAY.plusDays(5), "why"));
    assertThat(
        strandingByOwner.getMessage(),
        containsString("to move it, list it at another store first"));

    // A review the held manager closes at their store says the same.
    repo.dueChanges = List.of();
    repo.ranges.put(PRODUCT, List.of(STORE));
    repo.decision = Assortment.DELIST;
    ApiException review =
        assertThrows(ApiException.class, () -> svc.close(HELD, REVIEW, STORE, null, null));
    assertRefused(review, 409, "ASSORTMENT_LAST_STORE");
    assertThat(review.getMessage(), not(containsString("list it at another store first")));
    assertThat(
        review.getMessage(), containsString("ask an owner or a manager of the whole business"));
    assertThat(
        review.getMessage(),
        containsString("ask an owner or a manager of the whole business to discontinue the line"));
    assertThat("nothing was recorded", repo.writes, is(empty()));
  }
}
