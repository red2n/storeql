package com.storeql.inventory.repo;

import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * What each shelf holds when full, projected from product-svc's published planograms (07.17).
 *
 * <p>Replenishment has been driven from a stock number — on hand against a reorder level — which
 * answers the warehouse's question. The shop floor's question is whether the bay looks full, and
 * the two differ by exactly the shelf: 40 units on hand is plenty for a bay holding 12 and a gap in
 * one holding 60.
 */
@ApplicationScoped
public class ShelfTargetRepository extends BaseJdbcRepository {

  @Inject ExpiryDay expiryDay;

  /** One fixture's worth of targets, as the event delivered them. */
  public record Target(UUID variantId, int capacity, int minPresentation) {}

  /**
   * A shelf against what is actually in the store.
   *
   * @param capacity every unit the fixtures in this store hold for the line — summed, because a
   *     line sited twice has two shelves to fill
   * @param onHand available stock: on hand less what is held for somebody's order, matching the
   *     levels list rather than inventing a second definition
   * @param gap capacity less available, floored at zero — what it would take to fill the shelves
   * @param belowMinimum true when the shelf would look picked over: available under the summed
   *     merchandising minimum
   */
  public record ShelfGap(
      UUID storeId,
      UUID variantId,
      int capacity,
      int minPresentation,
      BigDecimal onHand,
      BigDecimal gap,
      boolean belowMinimum) {}

  private static final String CURRENT_VERSION =
      "SELECT planogram_version FROM shelf_target_fixtures WHERE tenant_id = ? AND fixture_id = ?";

  private static final String DELETE_FIXTURE =
      "DELETE FROM shelf_targets WHERE tenant_id = ? AND fixture_id = ?";

  private static final String INSERT_TARGET =
      "INSERT INTO shelf_targets (tenant_id, store_id, fixture_id, variant_id, capacity,"
          + " min_presentation, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

  private static final String UPSERT_FIXTURE =
      "INSERT INTO shelf_target_fixtures (tenant_id, fixture_id, store_id, planogram_id,"
          + " planogram_version, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT (tenant_id, fixture_id) DO UPDATE SET"
          + " store_id = EXCLUDED.store_id, planogram_id = EXCLUDED.planogram_id,"
          + " planogram_version = EXCLUDED.planogram_version, updated_at = EXCLUDED.updated_at";

  /**
   * Sets a fixture's targets to a published layout — one transaction, and a no-op when the layout
   * is one already seen.
   *
   * <p>The version check is what makes this idempotent in the way that matters. Kafka re-delivers,
   * and nothing orders records across partitions, so the same event can arrive twice and an older
   * one can arrive after a newer. Replacing the fixture's rows wholesale handles the first;
   * refusing to go backwards handles the second. Without it a bay would eventually hold a version
   * nobody built.
   *
   * @return true when this call moved the projection forward; false when the event was old or
   *     already applied
   */
  public boolean project(
      UUID tenantId,
      UUID storeId,
      UUID fixtureId,
      UUID planogramId,
      int version,
      List<Target> targets,
      Instant at) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(CURRENT_VERSION)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, fixtureId);
            try (var rs = ps.executeQuery()) {
              if (rs.next() && rs.getInt("planogram_version") >= version) return false;
            }
          }
          try (PreparedStatement ps = c.prepareStatement(DELETE_FIXTURE)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, fixtureId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_TARGET)) {
            for (Target t : targets) {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, fixtureId);
              ps.setObject(4, t.variantId());
              ps.setInt(5, t.capacity());
              ps.setInt(6, Math.min(t.minPresentation(), t.capacity()));
              ps.setObject(7, at.atOffset(ZoneOffset.UTC));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          try (PreparedStatement ps = c.prepareStatement(UPSERT_FIXTURE)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, fixtureId);
            ps.setObject(3, storeId);
            ps.setObject(4, planogramId);
            ps.setInt(5, version);
            ps.setObject(6, at.atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return true;
        },
        "project shelf capacity");
  }

  /**
   * Clears a fixture's targets — the bay is gone.
   *
   * <p>The high-water row goes with them: a fixture code is retired for good, and a later planogram
   * cannot be published against it, so keeping the version would only preserve a guard for an event
   * that can no longer be produced.
   */
  public void clearFixture(UUID tenantId, UUID fixtureId) {
    inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(DELETE_FIXTURE)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, fixtureId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM shelf_target_fixtures WHERE tenant_id = ? AND fixture_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, fixtureId);
            ps.executeUpdate();
          }
          return null;
        },
        "clear shelf targets");
  }

  /** The version a fixture's targets were last set from, or 0 when it has none. */
  public int versionOf(UUID tenantId, UUID fixtureId) {
    return query(
            CURRENT_VERSION,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, fixtureId);
            },
            rs -> rs.getInt("planogram_version"),
            "shelf target version")
        .stream()
        .findFirst()
        .orElse(0);
  }

  /**
   * What each line's shelves hold in a store, against what is available to fill them.
   *
   * <p>Targets are summed across fixtures, because a line sited on a gondola and an end cap has two
   * bays to fill. Availability is on hand less held reservations — the same definition the levels
   * list uses, so two screens never disagree about the same number. A line with a shelf and no
   * stock at all still appears: an empty bay is the case the report exists for, and it has no batch
   * rows.
   */
  private static final String GAP_REPORT_TEMPLATE =
      """
      SELECT t.store_id, t.variant_id,
             SUM(t.capacity)::int AS capacity,
             SUM(t.min_presentation)::int AS min_presentation,
             COALESCE(MAX(lv.available), 0)::numeric(18,3) AS on_hand
      FROM shelf_targets t
      LEFT JOIN (
          SELECT b.store_id, b.variant_id,
                 COALESCE(SUM(b.remaining_qty) FILTER (WHERE {SELLABLE}),0)
                   - COALESCE(MAX(res.reserved),0) AS available
          FROM inventory_batches b
          LEFT JOIN (
              SELECT store_id, variant_id, SUM(qty) AS reserved
              FROM reservations WHERE tenant_id = ? AND status = 'HELD'
              GROUP BY store_id, variant_id
          ) res ON res.store_id = b.store_id AND res.variant_id = b.variant_id
          WHERE b.tenant_id = ? AND b.material_status = 'AVAILABLE'
          GROUP BY b.store_id, b.variant_id
      ) lv ON lv.store_id = t.store_id AND lv.variant_id = t.variant_id
      WHERE t.tenant_id = ? AND t.store_id = ?
      GROUP BY t.store_id, t.variant_id
      ORDER BY (SUM(t.capacity) - COALESCE(MAX(lv.available), 0)) DESC, t.variant_id
      LIMIT ?""";

  /** The shelf-gap report for one store, deepest gap first. */
  public List<ShelfGap> gaps(UUID tenantId, UUID storeId, int limit) {
    return query(
        GAP_REPORT_TEMPLATE.replace("{SELLABLE}", expiryDay.of(tenantId).sellableSql("b")),
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, tenantId);
          ps.setObject(3, tenantId);
          ps.setObject(4, storeId);
          ps.setInt(5, limit);
        },
        rs -> {
          int capacity = rs.getInt("capacity");
          int minimum = rs.getInt("min_presentation");
          BigDecimal onHand = rs.getBigDecimal("on_hand");
          // Floored at zero, but keeping the scale: max(ZERO) would answer a bare "0" for a full
          // shelf and "40.000" for an empty one, and a report column of mixed scales reads as
          // broken.
          BigDecimal remaining = BigDecimal.valueOf(capacity).subtract(onHand);
          BigDecimal gap =
              remaining.signum() > 0 ? remaining : BigDecimal.ZERO.setScale(remaining.scale());
          return new ShelfGap(
              rs.getObject("store_id", UUID.class),
              rs.getObject("variant_id", UUID.class),
              capacity,
              minimum,
              onHand,
              gap,
              onHand.compareTo(BigDecimal.valueOf(minimum)) < 0);
        },
        "shelf gaps");
  }
}
