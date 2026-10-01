package com.storeql.product.service;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Merchandising;
import com.storeql.product.domain.Merchandising.Fixture;
import com.storeql.product.domain.Merchandising.Planogram;
import com.storeql.product.domain.Merchandising.Position;
import com.storeql.product.domain.Merchandising.Reset;
import com.storeql.product.domain.Merchandising.SpacePlan;
import com.storeql.product.repo.MerchandisingRepository;
import com.storeql.service.OutboxRow;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Merchandising: what gets shelf space, how much, and where it sits (07.17).
 *
 * <p>Three rules decide most of what this class does.
 *
 * <p><b>A layout is checked against the furniture it is drawn for.</b> Facings times each variant's
 * width either fits the shelf or does not, and a planogram that does not fit is a drawing nobody
 * can put up. It is checked when positions are saved rather than only when published, because
 * finding out at publication — after a reset has been scheduled around it — is finding out too
 * late.
 *
 * <p><b>What cannot be measured is not refused.</b> Most catalogues have gaps, so a position whose
 * variant has no recorded facing width is placed and not checked, and the answer says how much of
 * the shelf it could actually account for. A planogram nobody can save because one line lacks a
 * measurement would be worse than one whose check is partial and honest about it.
 *
 * <p><b>Publishing is a fact, not an edit.</b> A published version is superseded, never changed,
 * and the capacity it implies goes to inventory-svc in the same transaction.
 */
@ApplicationScoped
public class MerchandisingService {

  @Inject MerchandisingRepository repo;

  // ── fixtures ────────────────────────────────────────────────────────────────

  /**
   * Records a piece of shelving.
   *
   * @throws ApiException 400 {@code FIXTURE_KIND_UNKNOWN}; 409 {@code FIXTURE_CODE_TAKEN} from the
   *     unique index, named by the repository
   */
  public Fixture addFixture(
      UUID tenantId,
      UUID storeId,
      UUID zoneId,
      String code,
      String name,
      String kind,
      int shelfCount,
      int shelfWidthMm,
      UUID actorId) {
    String k = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
    if (!Merchandising.FIXTURE_KINDS.contains(k)) {
      throw ApiException.badRequest(
          "FIXTURE_KIND_UNKNOWN",
          "A fixture is one of " + String.join(", ", Merchandising.FIXTURE_KINDS));
    }
    return repo.addFixture(
        new Fixture(
            Ids.newId(),
            tenantId,
            storeId,
            zoneId,
            require(code, "FIXTURE_CODE_REQUIRED", "A fixture needs a code"),
            require(name, "FIXTURE_NAME_REQUIRED", "A fixture needs a name"),
            k,
            shelfCount,
            shelfWidthMm,
            Merchandising.ACTIVE,
            Instant.now(),
            Instant.now()),
        actorId);
  }

  public List<Fixture> fixtures(UUID tenantId, UUID storeId) {
    return repo.fixturesOf(tenantId, storeId);
  }

  /**
   * @throws ApiException 404 {@code FIXTURE_NOT_FOUND}; 409 {@code FIXTURE_ALREADY_RETIRED}
   */
  public Fixture retireFixture(UUID tenantId, UUID id) {
    Fixture f = requireFixture(tenantId, id);
    if (!f.active()) {
      throw ApiException.conflict("FIXTURE_ALREADY_RETIRED", "That fixture is already retired");
    }
    repo.retireFixture(
        tenantId,
        id,
        new OutboxRow(
            "FixtureRetired",
            "storeql.catalog.fixture-retired",
            tenantId,
            id,
            Events.fixtureRetired(tenantId, f.storeId(), id)));
    return requireFixture(tenantId, id);
  }

  /**
   * Records how wide one facing of a variant is — the measurement every fit check is made of.
   *
   * <p>On the variant rather than in the planogram on purpose: a bottle is the width it is on every
   * shelf it stands on, and a width per position would let two layouts of the same line disagree
   * about the same bottle.
   *
   * @param facingWidthMm the width in millimetres, or null to say it is not known after all
   * @throws ApiException 400 when the width is outside 1..5000mm; 404 when the variant is not the
   *     tenant's
   */
  public void setFacingWidth(UUID tenantId, UUID variantId, Integer facingWidthMm) {
    if (facingWidthMm != null && (facingWidthMm < 1 || facingWidthMm > 5000)) {
      throw ApiException.badRequest(
          "FACING_WIDTH_INVALID", "A facing is between 1mm and 5000mm wide");
    }
    if (!repo.setFacingWidth(tenantId, variantId, facingWidthMm)) {
      throw ApiException.notFound("VARIANT_NOT_FOUND", "No such variant");
    }
  }

  // ── planograms ──────────────────────────────────────────────────────────────

  /**
   * Starts a new draft for a fixture, at the next version.
   *
   * @throws ApiException 404 {@code FIXTURE_NOT_FOUND}; 409 {@code FIXTURE_RETIRED} or {@code
   *     PLANOGRAM_DRAFT_EXISTS}
   */
  public Planogram startDraft(
      UUID tenantId, UUID fixtureId, LocalDate effectiveFrom, String note, UUID actorId) {
    Fixture f = requireFixture(tenantId, fixtureId);
    if (!f.active()) {
      throw ApiException.conflict(
          "FIXTURE_RETIRED", "That fixture is retired; nothing new is drawn for it");
    }
    if (effectiveFrom == null) {
      throw ApiException.badRequest(
          "PLANOGRAM_DATE_REQUIRED", "A planogram needs the day it takes effect");
    }
    return repo.startDraft(tenantId, fixtureId, effectiveFrom, blankToNull(note), actorId);
  }

  /**
   * What a width check found.
   *
   * @param shelf the shelf it is about, 1 being the top
   * @param usedMm the millimetres the measured positions take
   * @param availableMm the shelf's own width
   * @param unmeasured how many positions on that shelf have no recorded width — the honest caveat
   *     on the two numbers beside it
   */
  public record ShelfFit(int shelf, long usedMm, long availableMm, int unmeasured) {
    public boolean overflows() {
      return usedMm > availableMm;
    }
  }

  /**
   * Replaces a draft's positions, after checking each shelf fits.
   *
   * <p>Checked here and not only at publication: a layout found not to fit after a reset has been
   * scheduled around it is found out too late.
   *
   * @throws ApiException 404 {@code PLANOGRAM_NOT_FOUND}; 409 {@code PLANOGRAM_NOT_DRAFT} — a
   *     published version is superseded, never edited; 409 {@code PLANOGRAM_SHELF_OVERFLOWS} naming
   *     the shelf and by how much; 400 {@code PLANOGRAM_SHELF_BEYOND_FIXTURE} for a shelf the
   *     furniture has not got
   */
  public List<ShelfFit> setPositions(UUID tenantId, UUID planogramId, List<Position> wanted) {
    Planogram p = requirePlanogram(tenantId, planogramId);
    if (!p.draft()) {
      throw ApiException.conflict(
          "PLANOGRAM_NOT_DRAFT",
          "A published planogram is never edited; start a new version instead");
    }
    Fixture f = requireFixture(tenantId, p.fixtureId());
    for (Position pos : wanted) {
      if (pos.shelf() > f.shelfCount()) {
        throw ApiException.badRequest(
            "PLANOGRAM_SHELF_BEYOND_FIXTURE",
            "That fixture has " + f.shelfCount() + " shelves, so there is no shelf " + pos.shelf());
      }
    }
    List<ShelfFit> fits = fit(tenantId, f, wanted);
    for (ShelfFit s : fits) {
      if (s.overflows()) {
        throw ApiException.conflict(
            "PLANOGRAM_SHELF_OVERFLOWS",
            "Shelf " + s.shelf() + " needs " + s.usedMm() + "mm and has " + s.availableMm() + "mm");
      }
    }
    repo.replacePositions(tenantId, planogramId, wanted);
    return fits;
  }

  /** How each shelf of a layout fits, measured with whatever widths the catalogue records. */
  public List<ShelfFit> fit(UUID tenantId, Fixture f, List<Position> positions) {
    Map<UUID, Integer> widths =
        repo.facingWidths(
            tenantId, positions.stream().map(Position::variantId).distinct().toList());
    Map<Integer, List<Position>> byShelf = new HashMap<>();
    for (Position p : positions) byShelf.computeIfAbsent(p.shelf(), k -> new ArrayList<>()).add(p);

    List<ShelfFit> out = new ArrayList<>();
    for (Map.Entry<Integer, List<Position>> e : byShelf.entrySet()) {
      List<Position> shelf = e.getValue();
      List<Integer> w = new ArrayList<>(shelf.size());
      int unmeasured = 0;
      for (Position p : shelf) {
        Integer mm = widths.get(p.variantId());
        if (mm == null) unmeasured++;
        w.add(mm);
      }
      out.add(
          new ShelfFit(
              e.getKey(), Merchandising.widthUsedMm(shelf, w), f.shelfWidthMm(), unmeasured));
    }
    out.sort((a, b) -> Integer.compare(a.shelf(), b.shelf()));
    return out;
  }

  /**
   * Publishes a draft: it takes effect, the version it replaces is superseded, and the capacity it
   * implies goes to inventory-svc — all in one transaction.
   *
   * @throws ApiException 404 {@code PLANOGRAM_NOT_FOUND}; 409 {@code PLANOGRAM_NOT_DRAFT}; 409
   *     {@code PLANOGRAM_EMPTY} — an empty layout published over a full one would silently tell
   *     replenishment that the shelf holds nothing, which is the one way this can quietly stop a
   *     store selling
   */
  public Planogram publish(UUID tenantId, UUID planogramId, UUID actorId) {
    Planogram p = requirePlanogram(tenantId, planogramId);
    if (!p.draft()) {
      throw ApiException.conflict("PLANOGRAM_NOT_DRAFT", "That planogram is not a draft");
    }
    if (p.positions().isEmpty()) {
      throw ApiException.conflict(
          "PLANOGRAM_EMPTY",
          "A layout with no positions would tell replenishment the shelf holds nothing");
    }
    Fixture f = requireFixture(tenantId, p.fixtureId());
    if (!repo.publish(tenantId, planogramId, actorId, capacityEvent(f, p))) {
      throw ApiException.conflict("PLANOGRAM_NOT_DRAFT", "That planogram is no longer a draft");
    }
    return requirePlanogram(tenantId, planogramId);
  }

  private OutboxRow capacityEvent(Fixture f, Planogram p) {
    List<UUID> variantIds = new ArrayList<>();
    List<Integer> capacities = new ArrayList<>();
    List<Integer> minimums = new ArrayList<>();
    // A variant may hold two positions on one fixture — multi-siting a line is ordinary
    // merchandising —
    // so the capacities are added together rather than the second overwriting the first. A shelf
    // target
    // that saw only the last position would under-fill the bay for ever.
    Map<UUID, int[]> merged = new java.util.LinkedHashMap<>();
    for (Position pos : p.positions()) {
      int[] sums = merged.computeIfAbsent(pos.variantId(), k -> new int[2]);
      sums[0] += pos.capacity();
      sums[1] += pos.minPresentation();
    }
    for (Map.Entry<UUID, int[]> e : merged.entrySet()) {
      variantIds.add(e.getKey());
      capacities.add(e.getValue()[0]);
      minimums.add(e.getValue()[1]);
    }
    return new OutboxRow(
        "ShelfCapacityPublished",
        "storeql.catalog.shelf-capacity-published",
        p.tenantId(),
        p.id(),
        Events.shelfCapacityPublished(
            p.tenantId(),
            f.storeId(),
            p.id(),
            p.version(),
            f.id(),
            variantIds,
            capacities,
            minimums));
  }

  public Optional<Planogram> planogram(UUID tenantId, UUID id) {
    return repo.planogram(tenantId, id);
  }

  public Optional<Planogram> inForce(UUID tenantId, UUID fixtureId) {
    return repo.inForce(tenantId, fixtureId);
  }

  public List<Planogram> versions(UUID tenantId, UUID fixtureId) {
    return repo.versionsOf(tenantId, fixtureId);
  }

  // ── space planning ──────────────────────────────────────────────────────────

  /**
   * A category's promised share of a store against the share its shelves actually give it.
   *
   * @param actualShare measured from the planograms in force, so it moves when a shelf is re-laid
   *     and not when somebody remembers to update a number
   * @param variance signed: over-spaced and under-spaced are different problems with different
   *     remedies, and an absolute figure hides which one a buyer is looking at
   */
  public record SpaceLine(
      UUID categoryId,
      BigDecimal targetShare,
      BigDecimal actualShare,
      BigDecimal variance,
      long actualMm,
      LocalDate reviewOn) {}

  public SpacePlan setSpacePlan(
      UUID tenantId,
      UUID storeId,
      UUID categoryId,
      BigDecimal targetShare,
      LocalDate reviewOn,
      String note,
      UUID actorId) {
    if (targetShare == null
        || targetShare.signum() <= 0
        || targetShare.compareTo(BigDecimal.ONE) > 0) {
      throw ApiException.badRequest(
          "SPACE_SHARE_INVALID",
          "A target share is above zero and at most one (1 = the whole store)");
    }
    return repo.upsertSpacePlan(
        new SpacePlan(
            Ids.newId(),
            tenantId,
            storeId,
            categoryId,
            targetShare.setScale(4, java.math.RoundingMode.HALF_UP),
            reviewOn,
            blankToNull(note),
            Instant.now(),
            Instant.now()),
        actorId);
  }

  /**
   * The space report for a store: what each planned category was promised, and what it has.
   *
   * <p>Only categories with a plan appear. A category with shelf space and no plan is not a
   * variance — nobody promised it anything — and listing it as one would bury the lines a buyer can
   * act on.
   */
  public List<SpaceLine> spaceReport(UUID tenantId, UUID storeId) {
    long storeMm = repo.storeShelfWidthMm(tenantId, storeId);
    Map<UUID, Long> actual = repo.facingMmByCategory(tenantId, storeId);
    List<SpaceLine> out = new ArrayList<>();
    for (SpacePlan plan : repo.spacePlansOf(tenantId, storeId)) {
      long mm = actual.getOrDefault(plan.categoryId(), 0L);
      BigDecimal share = Merchandising.actualShare(mm, storeMm);
      out.add(
          new SpaceLine(
              plan.categoryId(),
              plan.targetShare(),
              share,
              Merchandising.spaceVariance(plan.targetShare(), share),
              mm,
              plan.reviewOn()));
    }
    return out;
  }

  // ── resets ──────────────────────────────────────────────────────────────────

  public Reset planReset(
      UUID tenantId, UUID categoryId, String name, LocalDate scheduledFor, UUID actorId) {
    if (scheduledFor == null) {
      throw ApiException.badRequest("RESET_DATE_REQUIRED", "A reset needs the day it happens");
    }
    return repo.addReset(
        new Reset(
            Ids.newId(),
            tenantId,
            categoryId,
            require(name, "RESET_NAME_REQUIRED", "A reset needs a name somebody will recognise"),
            scheduledFor,
            Merchandising.PLANNED,
            null,
            Instant.now(),
            null,
            List.of()),
        actorId);
  }

  /**
   * Adds a shelf to a reset.
   *
   * @throws ApiException 409 {@code RESET_NOT_OPEN}; 409 {@code PLANOGRAM_NOT_PUBLISHED} — a reset
   *     moves layouts that exist, and a draft can still change under it; 409 {@code
   *     RESET_PLANOGRAM_CLAIMED}
   */
  public Reset attach(UUID tenantId, UUID resetId, UUID planogramId) {
    Reset r = requireReset(tenantId, resetId);
    if (!r.open()) {
      throw ApiException.conflict(
          "RESET_NOT_OPEN", "That reset is " + r.status().toLowerCase(Locale.ROOT));
    }
    Planogram p = requirePlanogram(tenantId, planogramId);
    if (p.draft()) {
      throw ApiException.conflict(
          "PLANOGRAM_NOT_PUBLISHED",
          "A reset moves published layouts; a draft can still change under it");
    }
    repo.attach(tenantId, resetId, planogramId);
    return requireReset(tenantId, resetId);
  }

  /**
   * @throws ApiException 404 {@code RESET_NOT_FOUND}; 409 {@code RESET_NOT_OPEN}
   */
  public Reset complete(UUID tenantId, UUID id) {
    if (!repo.moveReset(tenantId, id, Merchandising.PLANNED, Merchandising.COMPLETED, null)) {
      requireReset(tenantId, id); // not this business's, or no such reset: 404, not "not open"
      throw ApiException.conflict("RESET_NOT_OPEN", "That reset is not open");
    }
    return requireReset(tenantId, id);
  }

  /**
   * @throws ApiException 400 {@code RESET_REASON_REQUIRED}; 404 {@code RESET_NOT_FOUND}; 409 {@code
   *     RESET_NOT_OPEN}
   */
  public Reset cancel(UUID tenantId, UUID id, String reason) {
    String why = blankToNull(reason);
    if (why == null) {
      throw ApiException.badRequest(
          "RESET_REASON_REQUIRED",
          "Say why it was called off; an abandoned reset with no reason "
              + "is the thing somebody asks about in six months");
    }
    if (!repo.moveReset(tenantId, id, Merchandising.PLANNED, Merchandising.CANCELLED, why)) {
      requireReset(tenantId, id);
      throw ApiException.conflict("RESET_NOT_OPEN", "That reset is not open");
    }
    return requireReset(tenantId, id);
  }

  public List<Reset> resets(UUID tenantId) {
    return repo.resetsOf(tenantId);
  }

  // ── own brand ───────────────────────────────────────────────────────────────

  /**
   * @throws ApiException 404 {@code BRAND_NOT_FOUND}
   */
  public void setOwnBrand(UUID tenantId, UUID brandId, boolean own) {
    if (!repo.setOwnBrand(tenantId, brandId, own)) {
      throw ApiException.notFound("BRAND_NOT_FOUND", "No such brand");
    }
  }

  // ── guards ──────────────────────────────────────────────────────────────────

  private Fixture requireFixture(UUID tenantId, UUID id) {
    return repo.fixture(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("FIXTURE_NOT_FOUND", "No such fixture"));
  }

  private Planogram requirePlanogram(UUID tenantId, UUID id) {
    return repo.planogram(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("PLANOGRAM_NOT_FOUND", "No such planogram"));
  }

  private Reset requireReset(UUID tenantId, UUID id) {
    return repo.reset(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("RESET_NOT_FOUND", "No such reset"));
  }

  private static String require(String value, String code, String message) {
    String v = blankToNull(value);
    if (v == null) throw ApiException.badRequest(code, message);
    return v;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
