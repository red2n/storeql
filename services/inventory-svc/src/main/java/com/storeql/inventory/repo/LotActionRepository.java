package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.LotAction;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lot split/merge audit trail (Tier-1 Gap #23). Extracted from {@code InventoryRepository}: this
 * records that a split or merge happened and lists what was recorded. The stock itself moves in
 * {@code InventoryRepository.splitBatch} and {@code mergeBatches}, which write the action on the
 * transaction that moves it, through {@link #insertLotActionTx}.
 */
@ApplicationScoped
public class LotActionRepository extends BaseJdbcRepository {

  /**
   * Inserts a lot action on the caller's own transaction connection, under an id the caller has
   * already named: a split or merge cites that id from the two movements it writes before the
   * action itself.
   *
   * @param idempotencyKey the key the request was sent under, or null; unique within the tenant, so
   *     a second action under it fails with a unique violation
   */
  static LotAction insertLotActionTx(
      Connection c,
      UUID id,
      UUID tenantId,
      String actionType,
      UUID sourceBatchId,
      UUID resultBatchId,
      BigDecimal qty,
      String notes,
      String idempotencyKey)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO lot_actions"
                + " (id,tenant_id,action_type,source_batch_id,result_batch_id,qty,notes,"
                + "idempotency_key)"
                + " VALUES (?,?,?,?,?,?,?,?)"
                + " RETURNING id,tenant_id,action_type,source_batch_id,"
                + "result_batch_id,qty,notes,created_at")) {
      ps.setObject(1, id);
      ps.setObject(2, tenantId);
      ps.setString(3, actionType);
      ps.setObject(4, sourceBatchId);
      ps.setObject(5, resultBatchId);
      ps.setBigDecimal(6, qty);
      ps.setString(7, notes);
      ps.setString(8, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return mapLotAction(rs);
      }
    }
  }

  /**
   * The lot action an Idempotency-Key made, on the caller's own transaction connection.
   *
   * @return the action, or null when the tenant has none under the key
   */
  static LotAction findByKeyTx(Connection c, UUID tenantId, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id,tenant_id,action_type,source_batch_id,result_batch_id,qty,notes,created_at"
                + " FROM lot_actions WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapLotAction(rs) : null;
      }
    }
  }

  /** Whether the tenant has a lot action made under this Idempotency-Key. */
  public boolean keyUsed(UUID tenantId, String idempotencyKey) {
    return !query(
            "SELECT 1 FROM lot_actions WHERE tenant_id = ? AND idempotency_key = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            rs -> 1,
            "find lot action by key")
        .isEmpty();
  }

  /**
   * Lists the tenant's lot actions.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param batchId the batch id
   * @return the matching rows
   */
  public List<LotAction> listLotActions(UUID tenantId, UUID batchId) {
    return query(
        "SELECT id,tenant_id,action_type,source_batch_id,result_batch_id,qty,notes,created_at"
            + " FROM lot_actions WHERE tenant_id=?"
            + " AND (source_batch_id=? OR result_batch_id=?)"
            + " ORDER BY created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
          ps.setObject(3, batchId);
        },
        LotActionRepository::mapLotAction,
        "list lot actions");
  }

  private static LotAction mapLotAction(ResultSet rs) throws SQLException {
    return new LotAction(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("action_type"),
        rs.getObject("source_batch_id", UUID.class),
        rs.getObject("result_batch_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
