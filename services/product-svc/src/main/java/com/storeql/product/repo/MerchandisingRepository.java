package com.storeql.product.repo;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Merchandising;
import com.storeql.product.domain.Merchandising.Fixture;
import com.storeql.product.domain.Merchandising.Planogram;
import com.storeql.product.domain.Merchandising.Position;
import com.storeql.product.domain.Merchandising.Reset;
import com.storeql.product.domain.Merchandising.SpacePlan;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixtures, planograms, space plans and resets (07.17).
 *
 * <p>Two operations here are the ones worth reading, and both are one transaction on purpose.
 *
 * <p><b>Publishing a planogram</b> marks the version it replaces, publishes itself, and writes the
 * capacity event in the same transaction. If the event were written afterwards, a crash between the
 * two would leave a shelf whose layout says one thing and whose replenishment target says another,
 * with nothing to tell anybody which is right. The predecessor is marked first, because until it
 * is, the partial unique index still holds "in force" for that fixture — the same ordering the
 * statutory filings needed, and the same reason {@code superseded_by} is deferrable.
 *
 * <p><b>Replacing a draft's positions</b> deletes and re-inserts inside one transaction, because a
 * layout is a whole: half a shelf saved is not a smaller layout, it is a wrong one.
 */
@ApplicationScoped
public class MerchandisingRepository extends BaseOutboxRepository {

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static final String FIXTURE_COLUMNS =
      "SELECT id, tenant_id, store_id, zone_id, code, name, kind, shelf_count, shelf_width_mm,"
          + " status, created_at, updated_at FROM merch_fixtures";

  private static final String INSERT_FIXTURE =
      "INSERT INTO merch_fixtures (id, tenant_id, store_id, zone_id, code, name, kind, shelf_count,"
          + " shelf_width_mm, status, created_at, created_by, updated_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String RETIRE_FIXTURE =
      "UPDATE merch_fixtures SET status = 'RETIRED', updated_at = ?"
          + " WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'";

  /**
   * Adds a fixture.
   *
   * <p>Through {@code inTx} rather than {@code exec} on purpose: {@code exec} turns a unique
   * violation into a generic {@code DUPLICATE}, and {@link #handleTxSqlException} is only consulted
   * for a transaction — so a duplicated fixture code would tell a buyer "something already exists"
   * and not which shelf.
   */
  public Fixture addFixture(Fixture f, UUID actorId) {
    return inTxInsert(
        INSERT_FIXTURE,
        f,
        ps -> {
          ps.setObject(1, f.id());
          ps.setObject(2, f.tenantId());
          ps.setObject(3, f.storeId());
          ps.setObject(4, f.zoneId());
          ps.setString(5, f.code());
          ps.setString(6, f.name());
          ps.setString(7, f.kind());
          ps.setInt(8, f.shelfCount());
          ps.setInt(9, f.shelfWidthMm());
          ps.setString(10, Merchandising.ACTIVE);
          ps.setObject(11, f.createdAt().atOffset(ZoneOffset.UTC));
          ps.setObject(12, actorId);
          ps.setObject(13, f.createdAt().atOffset(ZoneOffset.UTC));
        },
        "add a fixture");
  }

  /** An insert inside a transaction, so {@link #handleTxSqlException} names the conflict. */
  private <T> T inTxInsert(String sql, T result, Binder binder, String what) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
          }
          return result;
        },
        what);
  }

  /** A store's fixtures, retired ones included so a past planogram still reads. */
  public List<Fixture> fixturesOf(UUID tenantId, UUID storeId) {
    return query(
        FIXTURE_COLUMNS + " WHERE tenant_id = ? AND store_id = ? ORDER BY code",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        MerchandisingRepository::readFixture,
        "fixtures of a store");
  }

  public Optional<Fixture> fixture(UUID tenantId, UUID id) {
    return query(
            FIXTURE_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            MerchandisingRepository::readFixture,
            "a fixture")
        .stream()
        .findFirst();
  }

  public boolean retireFixture(UUID tenantId, UUID id, OutboxRow outbox) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(RETIRE_FIXTURE)) {
            ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            if (ps.executeUpdate() != 1) return false;
          }
          // In the same transaction as the retire: a shelf that is gone and a projection that still
          // holds its capacity would have replenishment filling furniture nobody can see.
          insertOutbox(c, outbox);
          return true;
        },
        "retire a fixture");
  }

  /**
   * Every millimetre of active shelf a store has, which is what a category's share is a share of.
   */
  public long storeShelfWidthMm(UUID tenantId, UUID storeId) {
    return query(
            "SELECT COALESCE(SUM(shelf_count::BIGINT * shelf_width_mm), 0) AS mm"
                + " FROM merch_fixtures WHERE tenant_id = ? AND store_id = ? AND status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs -> rs.getLong("mm"),
            "a store's shelf width")
        .stream()
        .findFirst()
        .orElse(0L);
  }

  // ── planograms ──────────────────────────────────────────────────────────────

  private static final String PLANOGRAM_COLUMNS =
      "SELECT id, tenant_id, fixture_id, version, status, effective_from, note, supersedes,"
          + " superseded_by, created_at, published_at FROM planograms";

  private static final String INSERT_PLANOGRAM =
      "INSERT INTO planograms (id, tenant_id, fixture_id, version, status, effective_from, note,"
          + " supersedes, created_at, created_by) VALUES (?,?,?,?,'DRAFT',?,?,?,?,?)";

  private static final String NEXT_VERSION =
      "SELECT COALESCE(MAX(version), 0) + 1 AS v FROM planograms"
          + " WHERE tenant_id = ? AND fixture_id = ?";

  private static final String MARK_SUPERSEDED =
      "UPDATE planograms SET superseded_by = ?, status = 'SUPERSEDED'"
          + " WHERE tenant_id = ? AND id = ? AND superseded_by IS NULL";

  private static final String PUBLISH =
      "UPDATE planograms SET status = 'PUBLISHED', published_at = ?, published_by = ?,"
          + " supersedes = ? WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'";

  private static final String IN_FORCE =
      PLANOGRAM_COLUMNS
          + " WHERE tenant_id = ? AND fixture_id = ? AND status = 'PUBLISHED'"
          + " AND superseded_by IS NULL";

  private static final String INSERT_POSITION =
      "INSERT INTO planogram_positions (id, tenant_id, planogram_id, variant_id, shelf, sequence,"
          + " facings, depth, min_presentation) VALUES (?,?,?,?,?,?,?,?,?)";

  private static final String POSITION_COLUMNS =
      "SELECT id, tenant_id, planogram_id, variant_id, shelf, sequence, facings, depth, capacity,"
          + " min_presentation FROM planogram_positions";

  /**
   * Starts a draft for a fixture at the next version.
   *
   * @throws ApiException 409 {@code PLANOGRAM_DRAFT_EXISTS} — one draft per fixture, because two
   *     people drawing the same shelf at once is a merge nobody wins
   */
  public Planogram startDraft(UUID tenantId, UUID fixtureId, LocalDate from, String note, UUID by) {
    return inTx(
        c -> {
          int version = nextVersion(c, tenantId, fixtureId);
          UUID id = Ids.newId();
          Instant now = Instant.now();
          try (PreparedStatement ps = c.prepareStatement(INSERT_PLANOGRAM)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, fixtureId);
            ps.setInt(4, version);
            ps.setObject(5, from);
            ps.setString(6, note);
            ps.setObject(7, null);
            ps.setObject(8, now.atOffset(ZoneOffset.UTC));
            ps.setObject(9, by);
            ps.executeUpdate();
          }
          return new Planogram(
              id,
              tenantId,
              fixtureId,
              version,
              Merchandising.DRAFT,
              from,
              note,
              null,
              null,
              now,
              null,
              List.of());
        },
        "start a planogram draft");
  }

  private static int nextVersion(Connection c, UUID tenantId, UUID fixtureId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(NEXT_VERSION)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, fixtureId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt("v") : 1;
      }
    }
  }

  /**
   * Replaces a draft's positions wholesale.
   *
   * <p>Delete then insert, in one transaction: a layout is a whole, and half a shelf saved is not a
   * smaller layout but a wrong one.
   */
  public void replacePositions(UUID tenantId, UUID planogramId, List<Position> positions) {
    inTx(
        c -> {
          try (PreparedStatement del =
              c.prepareStatement(
                  "DELETE FROM planogram_positions WHERE tenant_id = ? AND planogram_id = ?")) {
            del.setObject(1, tenantId);
            del.setObject(2, planogramId);
            del.executeUpdate();
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_POSITION)) {
            for (Position p : positions) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, planogramId);
              ps.setObject(4, p.variantId());
              ps.setInt(5, p.shelf());
              ps.setInt(6, p.sequence());
              ps.setInt(7, p.facings());
              ps.setInt(8, p.depth());
              ps.setInt(9, p.minPresentation());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "replace a planogram's positions");
  }

  /**
   * Publishes a draft, supersedes whatever was in force, and writes the capacity event — one
   * transaction.
   *
   * <p>The order matters and is not arbitrary: the predecessor is marked first, because until it is
   * the partial unique index still holds "in force" for that fixture and the publish would be
   * refused. That is also why {@code superseded_by} is {@code DEFERRABLE} — the mark names a row
   * whose publish has not happened yet, so the key is checked at commit.
   *
   * <p>The event goes in the same transaction as the publish. Written afterwards, a crash between
   * the two would leave a shelf whose layout says one thing and whose replenishment target says
   * another, with nothing to say which is right.
   *
   * @return true when this call published it; false when it was not a draft any more
   */
  public boolean publish(UUID tenantId, UUID planogramId, UUID actorId, OutboxRow event) {
    return inTx(
        c -> {
          Planogram draft = byIdTx(c, tenantId, planogramId);
          if (draft == null || !draft.draft()) return false;
          UUID previous = inForceIdTx(c, tenantId, draft.fixtureId());
          if (previous != null) {
            try (PreparedStatement ps = c.prepareStatement(MARK_SUPERSEDED)) {
              ps.setObject(1, planogramId);
              ps.setObject(2, tenantId);
              ps.setObject(3, previous);
              ps.executeUpdate();
            }
          }
          try (PreparedStatement ps = c.prepareStatement(PUBLISH)) {
            ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(2, actorId);
            ps.setObject(3, previous);
            ps.setObject(4, tenantId);
            ps.setObject(5, planogramId);
            if (ps.executeUpdate() != 1) return false;
          }
          if (event != null) insertOutbox(c, event);
          return true;
        },
        "publish a planogram");
  }

  private Planogram byIdTx(Connection c, UUID tenantId, UUID id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(PLANOGRAM_COLUMNS + " WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readPlanogram(rs) : null;
      }
    }
  }

  private UUID inForceIdTx(Connection c, UUID tenantId, UUID fixtureId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(IN_FORCE)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, fixtureId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject("id", UUID.class) : null;
      }
    }
  }

  public Optional<Planogram> planogram(UUID tenantId, UUID id) {
    return query(
            PLANOGRAM_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            MerchandisingRepository::readPlanogram,
            "a planogram")
        .stream()
        .findFirst()
        .map(p -> withPositions(tenantId, p));
  }

  public Optional<Planogram> inForce(UUID tenantId, UUID fixtureId) {
    return query(
            IN_FORCE,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, fixtureId);
            },
            MerchandisingRepository::readPlanogram,
            "the planogram in force")
        .stream()
        .findFirst()
        .map(p -> withPositions(tenantId, p));
  }

  /** Every version for a fixture, newest first, so the history of a shelf reads. */
  public List<Planogram> versionsOf(UUID tenantId, UUID fixtureId) {
    return query(
        PLANOGRAM_COLUMNS + " WHERE tenant_id = ? AND fixture_id = ? ORDER BY version DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, fixtureId);
        },
        MerchandisingRepository::readPlanogram,
        "a fixture's planogram versions");
  }

  private Planogram withPositions(UUID tenantId, Planogram p) {
    // tenant_id first, then the planogram. PMD found this method's tenant argument unused and it
    // was
    // pointing at something real: the query keyed on planogram_id alone. An id is unguessable,
    // which is
    // not the same as isolated — golden rule #3 says every query on tenant-owned data filters by
    // tenant
    // first, so that it holds by construction rather than by the width of a UUID.
    List<Position> positions =
        query(
            POSITION_COLUMNS + " WHERE tenant_id = ? AND planogram_id = ? ORDER BY shelf, sequence",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, p.id());
            },
            MerchandisingRepository::readPosition,
            "a planogram's positions");
    return new Planogram(
        p.id(),
        p.tenantId(),
        p.fixtureId(),
        p.version(),
        p.status(),
        p.effectiveFrom(),
        p.note(),
        p.supersedes(),
        p.supersededBy(),
        p.createdAt(),
        p.publishedAt(),
        positions);
  }

  /**
   * The facing widths for a set of variants, so a layout can be width-checked.
   *
   * @return width by variant id; a variant with no recorded width is absent from the map rather
   *     than present with a zero, because "unknown" and "takes no space" are different facts
   */
  public Map<UUID, Integer> facingWidths(UUID tenantId, List<UUID> variantIds) {
    if (variantIds.isEmpty()) return Map.of();
    Map<UUID, Integer> out = new java.util.HashMap<>();
    query(
        "SELECT id, facing_width_mm FROM product_variants"
            + " WHERE tenant_id = ? AND id = ANY(?) AND facing_width_mm IS NOT NULL",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", variantIds.toArray()));
        },
        rs -> out.put(rs.getObject("id", UUID.class), rs.getInt("facing_width_mm")),
        "facing widths");
    return out;
  }

  /**
   * Records how wide one facing of a variant is, or clears it.
   *
   * @return false when the variant is not this tenant's, so the caller can answer 404 rather than
   *     report a silent success
   */
  public boolean setFacingWidth(UUID tenantId, UUID variantId, Integer facingWidthMm) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE product_variants SET facing_width_mm = ?, updated_at = ?"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, facingWidthMm, java.sql.Types.INTEGER);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, variantId);
            return ps.executeUpdate() == 1;
          }
        },
        "set a facing width");
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Fixture readFixture(ResultSet rs) throws SQLException {
    return new Fixture(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("kind"),
        rs.getInt("shelf_count"),
        rs.getInt("shelf_width_mm"),
        rs.getString("status"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Planogram readPlanogram(ResultSet rs) throws SQLException {
    return new Planogram(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("fixture_id", UUID.class),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("note"),
        rs.getObject("supersedes", UUID.class),
        rs.getObject("superseded_by", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "published_at"),
        List.of());
  }

  private static Position readPosition(ResultSet rs) throws SQLException {
    return new Position(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("planogram_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getInt("shelf"),
        rs.getInt("sequence"),
        rs.getInt("facings"),
        rs.getInt("depth"),
        rs.getInt("capacity"),
        rs.getInt("min_presentation"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  // ── space plans ─────────────────────────────────────────────────────────────

  private static final String SPACE_COLUMNS =
      "SELECT id, tenant_id, store_id, category_id, target_share, review_on, note, created_at,"
          + " updated_at FROM category_space_plans";

  /** Sets or replaces a category's target share for a store. One plan per pair, by unique index. */
  public SpacePlan upsertSpacePlan(SpacePlan plan, UUID actorId) {
    exec(
        "INSERT INTO category_space_plans (id, tenant_id, store_id, category_id, target_share,"
            + " review_on, note, created_at, created_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id, store_id, category_id) DO UPDATE SET"
            + " target_share = EXCLUDED.target_share, review_on = EXCLUDED.review_on,"
            + " note = EXCLUDED.note, updated_at = EXCLUDED.updated_at",
        ps -> {
          ps.setObject(1, plan.id());
          ps.setObject(2, plan.tenantId());
          ps.setObject(3, plan.storeId());
          ps.setObject(4, plan.categoryId());
          ps.setBigDecimal(5, plan.targetShare());
          ps.setObject(6, plan.reviewOn());
          ps.setString(7, plan.note());
          ps.setObject(8, plan.createdAt().atOffset(ZoneOffset.UTC));
          ps.setObject(9, actorId);
          ps.setObject(10, plan.createdAt().atOffset(ZoneOffset.UTC));
        },
        "set a category space plan");
    return plan;
  }

  public List<SpacePlan> spacePlansOf(UUID tenantId, UUID storeId) {
    return query(
        SPACE_COLUMNS + " WHERE tenant_id = ? AND store_id = ? ORDER BY target_share DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        MerchandisingRepository::readSpacePlan,
        "a store's space plans");
  }

  /**
   * The millimetres of facing each category actually holds in a store, from the planograms in
   * force.
   *
   * <p>One statement rather than a read-then-sum in Java: the join is planogram positions to
   * variants to products to categories, and pulling every position across a store to add it up
   * outside the database would be the same arithmetic done slower and in more places.
   *
   * <p>A position whose variant has no recorded facing width contributes nothing, which matches the
   * domain: unknown is not zero, and a category part-measured is reported as what could be
   * measured.
   */
  public Map<UUID, Long> facingMmByCategory(UUID tenantId, UUID storeId) {
    Map<UUID, Long> out = new java.util.HashMap<>();
    query(
        "SELECT p.category_id AS category_id,"
            + " SUM(pos.facings::BIGINT * v.facing_width_mm) AS mm"
            + " FROM planogram_positions pos"
            + " JOIN planograms g ON g.id = pos.planogram_id AND g.tenant_id = pos.tenant_id"
            + " JOIN merch_fixtures f ON f.id = g.fixture_id AND f.tenant_id = g.tenant_id"
            + " JOIN product_variants v ON v.id = pos.variant_id AND v.tenant_id = pos.tenant_id"
            + " JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id"
            + " WHERE pos.tenant_id = ? AND f.store_id = ? AND g.status = 'PUBLISHED'"
            + " AND g.superseded_by IS NULL AND v.facing_width_mm IS NOT NULL"
            + " AND p.category_id IS NOT NULL"
            + " GROUP BY p.category_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        rs -> out.put(rs.getObject("category_id", UUID.class), rs.getLong("mm")),
        "facing width by category");
    return out;
  }

  private static SpacePlan readSpacePlan(ResultSet rs) throws SQLException {
    return new SpacePlan(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getBigDecimal("target_share"),
        rs.getObject("review_on", LocalDate.class),
        rs.getString("note"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  // ── resets ──────────────────────────────────────────────────────────────────

  private static final String RESET_COLUMNS =
      "SELECT id, tenant_id, category_id, name, scheduled_for, status, cancelled_reason,"
          + " created_at, completed_at FROM category_resets";

  public Reset addReset(Reset r, UUID actorId) {
    exec(
        "INSERT INTO category_resets (id, tenant_id, category_id, name, scheduled_for, status,"
            + " created_at, created_by) VALUES (?,?,?,?,?,'PLANNED',?,?)",
        ps -> {
          ps.setObject(1, r.id());
          ps.setObject(2, r.tenantId());
          ps.setObject(3, r.categoryId());
          ps.setString(4, r.name());
          ps.setObject(5, r.scheduledFor());
          ps.setObject(6, r.createdAt().atOffset(ZoneOffset.UTC));
          ps.setObject(7, actorId);
        },
        "plan a category reset");
    return r;
  }

  /**
   * Attaches a planogram to a reset.
   *
   * @throws ApiException 409 {@code RESET_PLANOGRAM_CLAIMED} when another reset already moves that
   *     shelf — two resets claiming one shelf on different days is the contradiction the unique
   *     index stops being recordable — or {@code RESET_PLANOGRAM_ATTACHED} when this reset already
   *     has that layout
   */
  public void attach(UUID tenantId, UUID resetId, UUID planogramId) {
    inTxInsert(
        "INSERT INTO category_reset_planograms (reset_id, planogram_id, tenant_id) VALUES (?,?,?)",
        null,
        ps -> {
          ps.setObject(1, resetId);
          ps.setObject(2, planogramId);
          ps.setObject(3, tenantId);
        },
        "attach a planogram to a reset");
  }

  public boolean moveReset(UUID tenantId, UUID id, String from, String to, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE category_resets SET status = ?, cancelled_reason = ?,"
                      + " completed_at = ? WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, to);
            ps.setString(2, Merchandising.CANCELLED.equals(to) ? reason : null);
            ps.setObject(
                3,
                Merchandising.COMPLETED.equals(to) ? Instant.now().atOffset(ZoneOffset.UTC) : null);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.setString(6, from);
            return ps.executeUpdate() == 1;
          }
        },
        "move a category reset");
  }

  public Optional<Reset> reset(UUID tenantId, UUID id) {
    return query(
            RESET_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            MerchandisingRepository::readReset,
            "a category reset")
        .stream()
        .findFirst()
        .map(r -> withPlanograms(r));
  }

  public List<Reset> resetsOf(UUID tenantId) {
    return query(
        RESET_COLUMNS + " WHERE tenant_id = ? ORDER BY scheduled_for DESC",
        ps -> ps.setObject(1, tenantId),
        MerchandisingRepository::readReset,
        "category resets");
  }

  /**
   * The stores each reset touches: the stores of the fixtures its attached layouts are drawn for. A
   * reset with no layout attached is absent from the map: it touches no store yet.
   *
   * @param tenantId the business; the first condition
   * @return store ids by reset id
   */
  public Map<UUID, java.util.Set<UUID>> resetStores(UUID tenantId) {
    Map<UUID, java.util.Set<UUID>> out = new java.util.HashMap<>();
    query(
        "SELECT rp.reset_id, f.store_id FROM category_reset_planograms rp"
            + " JOIN planograms p ON p.tenant_id = rp.tenant_id AND p.id = rp.planogram_id"
            + " JOIN merch_fixtures f ON f.tenant_id = p.tenant_id AND f.id = p.fixture_id"
            + " WHERE rp.tenant_id = ?",
        ps -> ps.setObject(1, tenantId),
        rs ->
            out.computeIfAbsent(
                    rs.getObject("reset_id", UUID.class), k -> new java.util.HashSet<>())
                .add(rs.getObject("store_id", UUID.class)),
        "the stores of the resets");
    return out;
  }

  private Reset withPlanograms(Reset r) {
    // tenant_id first here too, for the same reason.
    List<UUID> ids =
        query(
            "SELECT planogram_id FROM category_reset_planograms"
                + " WHERE tenant_id = ? AND reset_id = ?",
            ps -> {
              ps.setObject(1, r.tenantId());
              ps.setObject(2, r.id());
            },
            rs -> rs.getObject("planogram_id", UUID.class),
            "a reset's planograms");
    return new Reset(
        r.id(),
        r.tenantId(),
        r.categoryId(),
        r.name(),
        r.scheduledFor(),
        r.status(),
        r.cancelledReason(),
        r.createdAt(),
        r.completedAt(),
        ids);
  }

  private static Reset readReset(ResultSet rs) throws SQLException {
    return new Reset(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getString("name"),
        rs.getObject("scheduled_for", LocalDate.class),
        rs.getString("status"),
        rs.getString("cancelled_reason"),
        instant(rs, "created_at"),
        instant(rs, "completed_at"),
        List.of());
  }

  // ── own brand ───────────────────────────────────────────────────────────────

  public boolean setOwnBrand(UUID tenantId, UUID brandId, boolean own) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE brands SET own_brand = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setBoolean(1, own);
            ps.setObject(2, tenantId);
            ps.setObject(3, brandId);
            return ps.executeUpdate() == 1;
          }
        },
        "mark a brand as own-brand");
  }

  /**
   * A collision on a unique index here is a business conflict, not a server fault.
   *
   * <p>Three of them are reachable: a second draft for a fixture, a fixture code already in use in
   * the store, and a planogram another reset already moves. They are named rather than left as a
   * generic 409, because "something already exists" tells a buyer nothing about which shelf.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      String detail = e.getMessage() == null ? "" : e.getMessage();
      if (detail.contains("uq_planogram_one_draft")) {
        return ApiException.conflict(
            "PLANOGRAM_DRAFT_EXISTS",
            "That fixture already has a draft; finish or discard it before starting another");
      }
      if (detail.contains("uq_merch_fixtures_code")) {
        return ApiException.conflict(
            "FIXTURE_CODE_TAKEN", "Another active fixture in this store already has that code");
      }
      // Two indexes cover one insert, and Postgres names whichever it hits first: the primary key
      // when the same layout is attached to the same reset twice, the unique index when a second
      // reset claims a shelf the first one already moves. Different mistakes, so different answers.
      if (detail.contains("pk_reset_planograms")) {
        return ApiException.conflict(
            "RESET_PLANOGRAM_ATTACHED", "That layout is already part of this reset");
      }
      if (detail.contains("uq_reset_planogram_once")) {
        return ApiException.conflict(
            "RESET_PLANOGRAM_CLAIMED", "Another reset already moves that shelf");
      }
    }
    return super.handleTxSqlException(what, e);
  }

  /** Kept for the service: the widths a layout needs, in the order its positions were given. */
  static List<Integer> widthsFor(List<Position> positions, Map<UUID, Integer> byVariant) {
    List<Integer> out = new ArrayList<>(positions.size());
    for (Position p : positions) out.add(byVariant.get(p.variantId()));
    return out;
  }
}
