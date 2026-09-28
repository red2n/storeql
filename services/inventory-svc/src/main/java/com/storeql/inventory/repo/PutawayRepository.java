package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.PutawayRule;
import com.storeql.inventory.domain.Domain.PutawayTask;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Directed putaway: the store's rules for where a product goes when it arrives with no zone, and
 * the list of batches no rule could place. Every statement filters by {@code tenant_id} first; a
 * zone is tenant-svc's id, referenced never joined.
 */
@ApplicationScoped
public class PutawayRepository extends BaseJdbcRepository {

  // ── The hook: on every batch that arrives with no zone ─────────────────────

  /**
   * Places the batch by the store's rule for its product, else the store default; with neither,
   * raises a task for a person. Called inside the arrival's transaction.
   */
  static void directTx(Connection c, Batch b) throws SQLException {
    UUID zone = null;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT zone_id FROM putaway_rules WHERE tenant_id = ? AND store_id = ?"
                + " AND (variant_id = ? OR variant_id IS NULL) ORDER BY variant_id NULLS LAST"
                + " LIMIT 1")) {
      ps.setObject(1, b.tenantId());
      ps.setObject(2, b.storeId());
      ps.setObject(3, b.variantId());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) zone = rs.getObject("zone_id", UUID.class);
      }
    }
    if (zone != null) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE inventory_batches SET zone_id = ? WHERE tenant_id = ? AND id = ?")) {
        ps.setObject(1, zone);
        ps.setObject(2, b.tenantId());
        ps.setObject(3, b.id());
        ps.executeUpdate();
      }
      return;
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO putaway_tasks (id, tenant_id, store_id, batch_id, variant_id, qty, status)"
                + " VALUES (?,?,?,?,?,?,'OPEN') ON CONFLICT (batch_id) DO NOTHING")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, b.tenantId());
      ps.setObject(3, b.storeId());
      ps.setObject(4, b.id());
      ps.setObject(5, b.variantId());
      ps.setBigDecimal(6, b.remainingQty());
      ps.executeUpdate();
    }
  }

  // ── Rules ──────────────────────────────────────────────────────────────────

  /** Sets the rule, replacing the zone of an earlier one for the same product (or default). */
  public PutawayRule upsertRule(PutawayRule r) {
    return inTx(
        c -> {
          String sql =
              r.variantId() == null
                  ? "INSERT INTO putaway_rules (id, tenant_id, store_id, variant_id, zone_id, created_by,"
                      + " created_at) VALUES (?,?,?,NULL,?,?,?) ON CONFLICT (tenant_id, store_id) WHERE"
                      + " variant_id IS NULL DO UPDATE SET zone_id = EXCLUDED.zone_id, created_by ="
                      + " EXCLUDED.created_by, created_at = EXCLUDED.created_at RETURNING id"
                  : "INSERT INTO putaway_rules (id, tenant_id, store_id, variant_id, zone_id, created_by,"
                      + " created_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT (tenant_id, store_id, variant_id)"
                      + " WHERE variant_id IS NOT NULL DO UPDATE SET zone_id = EXCLUDED.zone_id, created_by"
                      + " = EXCLUDED.created_by, created_at = EXCLUDED.created_at RETURNING id";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, r.id());
            ps.setObject(i++, r.tenantId());
            ps.setObject(i++, r.storeId());
            if (r.variantId() != null) ps.setObject(i++, r.variantId());
            ps.setObject(i++, r.zoneId());
            ps.setObject(i++, r.createdBy());
            ps.setObject(i, r.createdAt().atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              UUID id = rs.getObject("id", UUID.class);
              return new PutawayRule(
                  id,
                  r.tenantId(),
                  r.storeId(),
                  r.variantId(),
                  r.zoneId(),
                  r.createdBy(),
                  r.createdAt());
            }
          }
        },
        "set putaway rule");
  }

  private static final String RULE_COLUMNS =
      "id, tenant_id, store_id, variant_id, zone_id, created_by, created_at";

  public List<PutawayRule> rules(UUID tenantId, UUID storeId) {
    return query(
        "SELECT "
            + RULE_COLUMNS
            + " FROM putaway_rules WHERE tenant_id = ? AND store_id = ? ORDER BY variant_id NULLS"
            + " FIRST, created_at LIMIT 1000",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        PutawayRepository::mapRule,
        "list putaway rules");
  }

  public Optional<PutawayRule> findRule(UUID tenantId, UUID id) {
    return query(
            "SELECT " + RULE_COLUMNS + " FROM putaway_rules WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PutawayRepository::mapRule,
            "find putaway rule")
        .stream()
        .findFirst();
  }

  private static PutawayRule mapRule(ResultSet rs) throws SQLException {
    return new PutawayRule(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  /** Removes a rule; false when the business has none by that id. */
  public boolean deleteRule(UUID tenantId, UUID id) {
    int[] rows = new int[1];
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM putaway_rules WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            rows[0] = ps.executeUpdate();
          }
          return null;
        },
        "delete putaway rule");
    return rows[0] > 0;
  }

  // ── Tasks ──────────────────────────────────────────────────────────────────

  /**
   * A task with its batch's number, read in the one statement; the batch is this service's own, and
   * the join is pinned to the task's tenant. Callers continue with {@code WHERE t.tenant_id = ?}.
   */
  private static final String TASK_SELECT =
      "SELECT t.id, t.tenant_id, t.store_id, t.batch_id, b.batch_no, t.variant_id, t.qty,"
          + " t.suggested_zone_id, t.status, t.placed_zone_id, t.placed_by, t.placed_at,"
          + " t.created_at FROM putaway_tasks t LEFT JOIN inventory_batches b"
          + " ON b.tenant_id = t.tenant_id AND b.id = t.batch_id";

  public List<PutawayTask> openTasks(UUID tenantId, UUID storeId) {
    return query(
        TASK_SELECT
            + " WHERE t.tenant_id = ? AND t.status = 'OPEN'"
            + (storeId == null ? "" : " AND t.store_id = ?")
            + " ORDER BY t.created_at, t.id LIMIT 500",
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        PutawayRepository::mapTask,
        "list putaway tasks");
  }

  public Optional<PutawayTask> findTask(UUID tenantId, UUID id) {
    List<PutawayTask> rows =
        query(
            TASK_SELECT + " WHERE t.tenant_id = ? AND t.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PutawayRepository::mapTask,
            "find putaway task");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Places the batch in the zone and closes the task, on one transaction.
   *
   * @throws ApiException 404 {@code INVENTORY_PUTAWAY_TASK_NOT_FOUND}; 409 {@code
   *     INVENTORY_PUTAWAY_TASK_PLACED} when it was placed already
   */
  public PutawayTask place(UUID tenantId, UUID taskId, UUID zoneId, UUID by) {
    return inTx(
        c -> {
          PutawayTask t;
          try (PreparedStatement ps =
              c.prepareStatement(
                  TASK_SELECT + " WHERE t.tenant_id = ? AND t.id = ? FOR UPDATE OF t")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound(
                    "INVENTORY_PUTAWAY_TASK_NOT_FOUND", "no putaway task " + taskId);
              }
              t = mapTask(rs);
            }
          }
          if (!PutawayTask.OPEN.equals(t.status())) {
            throw ApiException.conflict(
                "INVENTORY_PUTAWAY_TASK_PLACED", "batch " + t.batchId() + " was placed already");
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE inventory_batches SET zone_id = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, zoneId);
            ps.setObject(2, tenantId);
            ps.setObject(3, t.batchId());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE putaway_tasks SET status = 'PLACED', placed_zone_id = ?, placed_by = ?,"
                      + " placed_at = now() WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, zoneId);
            ps.setObject(2, by);
            ps.setObject(3, tenantId);
            ps.setObject(4, taskId);
            ps.executeUpdate();
          }
          return new PutawayTask(
              t.id(),
              t.tenantId(),
              t.storeId(),
              t.batchId(),
              t.batchNo(),
              t.variantId(),
              t.qty(),
              t.suggestedZoneId(),
              PutawayTask.PLACED,
              zoneId,
              by,
              java.time.Instant.now(),
              t.createdAt());
        },
        "place putaway task");
  }

  private static PutawayTask mapTask(ResultSet rs) throws SQLException {
    OffsetDateTime placedAt = rs.getObject("placed_at", OffsetDateTime.class);
    return new PutawayTask(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("batch_no"),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getObject("suggested_zone_id", UUID.class),
        rs.getString("status"),
        rs.getObject("placed_zone_id", UUID.class),
        rs.getObject("placed_by", UUID.class),
        placedAt == null ? null : placedAt.toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
