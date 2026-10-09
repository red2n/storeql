package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.MoveType;
import com.storeql.inventory.domain.Domain.MovementAttribution;
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
import java.util.List;
import java.util.UUID;

/**
 * Opening stock: the stock a business already holds when it starts, loaded once per (store,
 * variant) through the door every arrival uses (recall gate, directed putaway, expiry), with a
 * {@code opening_stock_loads} row on the same transaction so a retry or a second file never doubles
 * it. Every query filters {@code tenant_id} first.
 */
@ApplicationScoped
public class OpeningStockRepository extends BaseOutboxRepository {

  private static final String UNIQUE_VIOLATION = "23505";

  @Inject InventoryRepository inventory;

  /** What happened to one line. */
  public enum Outcome {
    /** The stock was opened by this call. */
    LOADED,
    /** This job opened it on an earlier call: nothing was written now. */
    REPLAYED,
    /** Another job opened it first: nothing was written, and the line says so. */
    ALREADY_OPENED
  }

  /**
   * The line's outcome, the batch that holds (or held) the stock, and whether a recall holds it.
   */
  public record Loaded(Outcome outcome, UUID batchId, boolean held) {}

  /** What a job opened at one store: lines, quantity, value of the costed lines, uncosted lines. */
  public record Summary(
      UUID storeId, int lines, BigDecimal qty, BigDecimal value, int uncostedLines) {}

  /**
   * Opens one line on one transaction: the batch (through the arrival door), its receive movement,
   * the {@code StockReceived} event and the opening row, or nothing at all when it was opened
   * before.
   */
  public Loaded load(Batch batch, UUID jobId, OutboxRow event, MovementAttribution who) {
    return inTx(
        c -> {
          Loaded before = openedTx(c, batch, jobId);
          if (before != null) {
            return before;
          }
          String hold = inventory.insertBatchHeld(c, batch);
          InventoryRepository.insertMovement(
              c,
              batch.tenantId(),
              batch.storeId(),
              batch.variantId(),
              batch.id(),
              MoveType.RECEIVE,
              batch.receivedQty(),
              "OPENING_STOCK",
              jobId,
              who);
          recordTx(c, batch, jobId);
          insertOutbox(c, event);
          return new Loaded(Outcome.LOADED, batch.id(), hold != null);
        },
        "load opening stock");
  }

  private static Loaded openedTx(Connection c, Batch batch, UUID jobId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT o.job_id, o.batch_id, b.material_status FROM opening_stock_loads o"
                + " JOIN inventory_batches b ON b.id = o.batch_id AND b.tenant_id = o.tenant_id"
                + " WHERE o.tenant_id = ? AND o.store_id = ? AND o.variant_id = ?")) {
      ps.setObject(1, batch.tenantId());
      ps.setObject(2, batch.storeId());
      ps.setObject(3, batch.variantId());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        boolean sameJob = jobId.equals(rs.getObject("job_id", UUID.class));
        return new Loaded(
            sameJob ? Outcome.REPLAYED : Outcome.ALREADY_OPENED,
            rs.getObject("batch_id", UUID.class),
            Batch.MATERIAL_RECALLED.equals(rs.getString("material_status")));
      }
    }
  }

  private static void recordTx(Connection c, Batch batch, UUID jobId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO opening_stock_loads"
                + " (id, tenant_id, store_id, variant_id, job_id, batch_id, qty, unit_cost)"
                + " VALUES (?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, batch.tenantId());
      ps.setObject(3, batch.storeId());
      ps.setObject(4, batch.variantId());
      ps.setObject(5, jobId);
      ps.setObject(6, batch.id());
      ps.setBigDecimal(7, batch.receivedQty());
      ps.setBigDecimal(8, batch.costPrice());
      ps.executeUpdate();
    } catch (SQLException e) {
      if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
        // Two calls opened the same line at once; the loser writes nothing and may be retried.
        throw new ApiException(
            409,
            "INVENTORY_OPENING_ALREADY_LOADED",
            "this store's stock of the item was opened a moment ago",
            List.of(),
            e);
      }
      throw e;
    }
  }

  /** What a job opened, by store. */
  public List<Summary> summary(UUID tenantId, UUID jobId) {
    return query(
        "SELECT store_id, count(*) AS lines, sum(qty) AS qty, sum(qty * unit_cost) AS value,"
            + " count(*) FILTER (WHERE unit_cost IS NULL) AS uncosted"
            + " FROM opening_stock_loads WHERE tenant_id = ? AND job_id = ?"
            + " GROUP BY store_id ORDER BY store_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, jobId);
        },
        rs ->
            new Summary(
                rs.getObject("store_id", UUID.class),
                rs.getInt("lines"),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("value") == null ? BigDecimal.ZERO : rs.getBigDecimal("value"),
                rs.getInt("uncosted")),
        "summarise opening stock");
  }
}
