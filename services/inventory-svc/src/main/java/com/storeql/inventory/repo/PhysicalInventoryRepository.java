package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.MoveType;
import com.storeql.inventory.domain.Domain.MovementAttribution;
import com.storeql.inventory.domain.Domain.PhysicalInventory;
import com.storeql.inventory.domain.Domain.PhysicalInventoryTag;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Physical inventory counts (Gap #16). Extracted from {@code InventoryRepository}.
 *
 * <p>Completion posts through the core repo's batch internals, as a cycle count does: a gain is a
 * new batch where it was found and a loss is drawn from the batches, each an ADJUST movement
 * against the count and the person completing it. It once wrote {@code stock_movements} rows by SQL
 * alone, which no level reads — the ledger said the stock moved and every screen said it had not.
 */
@ApplicationScoped
public class PhysicalInventoryRepository extends BaseOutboxRepository {

  private static final String REF_TYPE = "PHYSICAL_INVENTORY";

  private static final String TAG_COLUMNS =
      "id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty, counted_qty,"
          + " adjustment_qty, status, counted_at";

  @Inject InventoryRepository inventory;

  /**
   * Inserts a physical inventory.
   *
   * @param pi the physical to persist
   * @param event the outbox row to commit alongside the write
   * @return the physical inventory as stored
   */
  public PhysicalInventory createPhysicalInventory(PhysicalInventory pi, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO physical_inventories (id, tenant_id, store_id, status, notes)"
                  + " VALUES (?,?,?,?,?)"
                  + " RETURNING id, tenant_id, store_id, status, notes, started_at, completed_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, pi.id());
            ps.setObject(2, pi.tenantId());
            ps.setObject(3, pi.storeId());
            ps.setString(4, pi.status());
            ps.setString(5, pi.notes());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw dbError("create physical inventory", new SQLException());
              PhysicalInventory saved = mapPhysicalInventory(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create physical inventory");
  }

  /**
   * Looks a physical inventory up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the physical inventory to act on
   * @return the physical inventory, or empty when it does not exist in this tenant
   */
  public Optional<PhysicalInventory> findPhysicalInventory(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id, tenant_id, store_id, status, notes, started_at, completed_at"
                + " FROM physical_inventories WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PhysicalInventoryRepository::mapPhysicalInventory,
            "find physical inventory");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Lists the tenant's physical inventories.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @return the matching rows
   */
  public List<PhysicalInventory> listPhysicalInventories(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, status, notes, started_at, completed_at"
            + " FROM physical_inventories WHERE tenant_id=?"
            + (storeId != null ? " AND store_id=?" : "")
            + " ORDER BY started_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        PhysicalInventoryRepository::mapPhysicalInventory,
        "list physical inventories");
  }

  /**
   * Lists the tenant's tags.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param physicalInventoryId the physical inventory id
   * @return the matching rows
   */
  public List<PhysicalInventoryTag> listTags(UUID tenantId, UUID physicalInventoryId) {
    return query(
        "SELECT id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty,"
            + " counted_qty, adjustment_qty, status, counted_at"
            + " FROM physical_inventory_tags WHERE tenant_id=? AND physical_inventory_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, physicalInventoryId);
        },
        PhysicalInventoryRepository::mapTag,
        "list physical inventory tags");
  }

  /**
   * Adds a count tag, recording what the books hold for it now as the quantity to count against:
   * AVAILABLE stock at the count's store, in the zone when one is named — what a loss on completion
   * may draw. A variant is tagged once per count, for the whole store or per zone, never both, so
   * no unit is counted twice.
   *
   * @param id the new tag's id, a UUIDv7
   * @param tenantId owning tenant; the first condition of every query
   * @param physicalInventoryId the count
   * @param variantId the variant to count
   * @param zoneId the zone it is counted in, or {@code null} for the whole store
   * @return the tag as stored, or empty when the count is already completed
   * @throws ApiException 404 {@code PI_NOT_FOUND}; 409 {@code PI_TAG_EXISTS}
   */
  public Optional<PhysicalInventoryTag> addTag(
      UUID id, UUID tenantId, UUID physicalInventoryId, UUID variantId, UUID zoneId) {
    return inTx(
        c -> {
          UUID storeId = lockOpen(c, tenantId, physicalInventoryId);
          if (storeId == null) return Optional.<PhysicalInventoryTag>empty();
          refuseOverlap(c, tenantId, physicalInventoryId, variantId, zoneId);
          BigDecimal onHand = onHand(c, tenantId, storeId, variantId, zoneId);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO physical_inventory_tags"
                      + " (id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty)"
                      + " VALUES (?,?,?,?,?,?) RETURNING "
                      + TAG_COLUMNS)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, physicalInventoryId);
            ps.setObject(4, variantId);
            ps.setObject(5, zoneId);
            ps.setBigDecimal(6, onHand);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw dbError("add pi tag", new SQLException());
              return Optional.of(mapTag(rs));
            }
          }
        },
        "add physical inventory tag");
  }

  /** Locks the count's header; its store, or {@code null} once it is completed. */
  private static UUID lockOpen(Connection c, UUID tenantId, UUID piId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT store_id, status FROM physical_inventories"
                + " WHERE tenant_id=? AND id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, piId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw ApiException.notFound("PI_NOT_FOUND", "Physical inventory not found");
        return PhysicalInventory.COMPLETED.equals(rs.getString("status"))
            ? null
            : rs.getObject("store_id", UUID.class);
      }
    }
  }

  private static void refuseOverlap(
      Connection c, UUID tenantId, UUID piId, UUID variantId, UUID zoneId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM physical_inventory_tags"
                + " WHERE tenant_id=? AND physical_inventory_id=? AND variant_id=?"
                + " AND (zone_id IS NULL OR CAST(? AS uuid) IS NULL OR zone_id = CAST(? AS uuid))"
                + " LIMIT 1")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, piId);
      ps.setObject(3, variantId);
      ps.setObject(4, zoneId);
      ps.setObject(5, zoneId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next())
          throw ApiException.conflict(
              "PI_TAG_EXISTS",
              "This variant is already tagged in this count, for the whole store or this zone");
      }
    }
  }

  /** AVAILABLE stock of a variant at a store, in one zone when one is named. */
  private static BigDecimal onHand(
      Connection c, UUID tenantId, UUID storeId, UUID variantId, UUID zoneId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(remaining_qty), 0) AS q FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=?"
                + " AND material_status='AVAILABLE'"
                + " AND (CAST(? AS uuid) IS NULL OR zone_id = CAST(? AS uuid))")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      ps.setObject(4, zoneId);
      ps.setObject(5, zoneId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal("q") : BigDecimal.ZERO;
      }
    }
  }

  /**
   * Records the counted quantity on one tag and marks it COUNTED.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param physicalInventoryId the count the tag belongs to, also matched
   * @param tagId the tag being counted
   * @param countedQty the quantity actually found
   * @return the tag with its recorded count
   */
  public PhysicalInventoryTag countTag(
      UUID tenantId, UUID physicalInventoryId, UUID tagId, BigDecimal countedQty) {
    return inTx(
        c -> {
          // A completed count's tags are posted; counting one again would reopen it on paper.
          if (lockOpen(c, tenantId, physicalInventoryId) == null)
            throw ApiException.conflict(
                "PI_ALREADY_COMPLETED", "Physical inventory already completed");
          String sql =
              "UPDATE physical_inventory_tags SET counted_qty=?, status='COUNTED', counted_at=now()"
                  + " WHERE tenant_id=? AND physical_inventory_id=? AND id=? RETURNING "
                  + TAG_COLUMNS;
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, countedQty);
            ps.setObject(2, tenantId);
            ps.setObject(3, physicalInventoryId);
            ps.setObject(4, tagId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("TAG_NOT_FOUND", "Physical inventory tag not found");
              return mapTag(rs);
            }
          }
        },
        "count pi tag");
  }

  /**
   * Completes a physical inventory, posting each counted tag's variance and writing the event —
   * atomically. A gain is a new AVAILABLE batch where it was found; a loss is drawn from the
   * batches (the tag's zone only, when it has one); both are ADJUST movements against the count,
   * attributed to who completed it, so the levels move with the ledger.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param piId the physical inventory to complete
   * @param event the outbox row to commit alongside
   * @param actorId who completed it
   * @return the completed physical inventory
   * @throws ApiException 404 {@code PI_NOT_FOUND}; 409 {@code PI_ALREADY_COMPLETED}; 422 {@code
   *     INSUFFICIENT_STOCK} when stock left since a tag was added and there is less to take than
   *     the count found missing — counted again, it posts
   */
  public PhysicalInventory completePhysicalInventory(
      UUID tenantId, UUID piId, OutboxRow event, UUID actorId) {
    MovementAttribution attribution =
        MovementAttribution.by(actorId, MovementAttribution.PHYSICAL_INVENTORY_VARIANCE);
    return inTx(
        c -> {
          UUID storeId = lockOpen(c, tenantId, piId);
          if (storeId == null)
            throw ApiException.conflict(
                "PI_ALREADY_COMPLETED", "Physical inventory already completed");
          for (PhysicalInventoryTag tag : variances(c, tenantId, piId)) {
            post(c, tenantId, storeId, piId, tag, attribution);
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE physical_inventory_tags SET status='ADJUSTED'"
                      + " WHERE tenant_id=? AND physical_inventory_id=? AND status='COUNTED'")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE physical_inventories SET status='COMPLETED', completed_at=now()"
                      + " WHERE tenant_id=? AND id=?"
                      + " RETURNING id, tenant_id, store_id, status, notes, started_at,"
                      + " completed_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw dbError("complete physical inventory", new SQLException());
              PhysicalInventory done = mapPhysicalInventory(rs);
              insertOutbox(c, event);
              return done;
            }
          }
        },
        "complete physical inventory");
  }

  private static List<PhysicalInventoryTag> variances(Connection c, UUID tenantId, UUID piId)
      throws SQLException {
    List<PhysicalInventoryTag> tags = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + TAG_COLUMNS
                + " FROM physical_inventory_tags"
                + " WHERE tenant_id=? AND physical_inventory_id=?"
                + " AND status='COUNTED' AND counted_qty IS NOT NULL"
                + " AND counted_qty <> system_qty")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, piId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) tags.add(mapTag(rs));
      }
    }
    return tags;
  }

  private void post(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID piId,
      PhysicalInventoryTag tag,
      MovementAttribution attribution)
      throws SQLException {
    BigDecimal adj = tag.adjustmentQty();
    if (adj.signum() > 0) {
      Batch found =
          new Batch(
              Ids.newId(),
              tenantId,
              storeId,
              tag.variantId(),
              "PI-" + Ids.shortRef(piId),
              adj,
              adj,
              null,
              null,
              Instant.now(),
              Batch.STATUS_ACTIVE,
              Batch.MATERIAL_AVAILABLE,
              null,
              null,
              tag.zoneId());
      inventory.insertBatch(c, found);
      InventoryRepository.insertMovement(
          c,
          tenantId,
          storeId,
          tag.variantId(),
          found.id(),
          MoveType.ADJUST,
          adj,
          REF_TYPE,
          piId,
          attribution);
      return;
    }
    BigDecimal loss = adj.negate();
    if (tag.zoneId() != null) {
      // The draw orders by zone but does not stop at it: a zone's loss is never taken elsewhere.
      BigDecimal inZone = onHand(c, tenantId, storeId, tag.variantId(), tag.zoneId());
      if (inZone.compareTo(loss) < 0)
        throw ApiException.unprocessable(
            "INSUFFICIENT_STOCK",
            "Short by " + loss.subtract(inZone).toPlainString() + " in the zone; count it again");
    }
    inventory.deductBatches(
        c,
        tenantId,
        storeId,
        tag.variantId(),
        loss,
        MoveType.ADJUST,
        REF_TYPE,
        piId,
        null,
        null,
        tag.zoneId() == null ? null : List.of(tag.zoneId()),
        attribution);
  }

  private static PhysicalInventory mapPhysicalInventory(ResultSet rs) throws SQLException {
    OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
    return new PhysicalInventory(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
        completed == null ? null : completed.toInstant());
  }

  private static PhysicalInventoryTag mapTag(ResultSet rs) throws SQLException {
    OffsetDateTime countedAt = rs.getObject("counted_at", OffsetDateTime.class);
    return new PhysicalInventoryTag(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("physical_inventory_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getBigDecimal("system_qty"),
        rs.getBigDecimal("counted_qty"),
        rs.getBigDecimal("adjustment_qty"),
        rs.getString("status"),
        countedAt == null ? null : countedAt.toInstant());
  }
}
