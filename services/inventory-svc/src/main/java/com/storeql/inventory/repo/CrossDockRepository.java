package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.CrossDock;
import com.storeql.inventory.domain.CrossDock.DockLine;
import com.storeql.inventory.domain.CrossDock.Expected;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.MoveType;
import com.storeql.inventory.domain.Domain.MovementAttribution;
import com.storeql.inventory.domain.Domain.TransferOrder;
import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-docking at the warehouse (intent/cross-docking.md): what each shop is owed of each product
 * on a purchase order, and the receipt that sends it straight across the dock. Every query filters
 * by {@code tenant_id} first.
 */
@ApplicationScoped
public class CrossDockRepository extends BaseOutboxRepository {

  @Inject InventoryRepository inventory;

  /**
   * Replaces what the order owes the shops with purchase-svc's latest snapshot, once per event.
   *
   * @return false when the event was already applied
   */
  public boolean setExpectedOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID poId,
      UUID warehouseId,
      List<Expected> rows) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM crossdock_expected WHERE tenant_id = ? AND purchase_order_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, poId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO crossdock_expected (id, tenant_id, purchase_order_id, warehouse_id,"
                      + " store_id, variant_id, qty) VALUES (?,?,?,?,?,?,?) ON CONFLICT (tenant_id,"
                      + " purchase_order_id, store_id, variant_id) DO UPDATE SET qty ="
                      + " crossdock_expected.qty + EXCLUDED.qty")) {
            for (Expected e : rows) {
              if (e.qty() == null || e.qty().signum() <= 0) continue;
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, poId);
              ps.setObject(4, warehouseId);
              ps.setObject(5, e.storeId());
              ps.setObject(6, e.variantId());
              ps.setBigDecimal(7, e.qty());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "set cross-dock expectations");
  }

  /** What the order still owes the shops, wherever it is delivered. */
  public List<CrossDock.Owed> owed(UUID tenantId, UUID poId) {
    return query(
        "SELECT warehouse_id, store_id, variant_id, qty FROM crossdock_expected WHERE tenant_id = ?"
            + " AND purchase_order_id = ? ORDER BY store_id, variant_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        rs ->
            new CrossDock.Owed(
                rs.getObject("warehouse_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty")),
        "list what an order owes the shops");
  }

  /** What the order still owes the shops, at the warehouse given. */
  public List<Expected> expected(UUID tenantId, UUID poId, UUID warehouseId) {
    return query(
        "SELECT store_id, variant_id, qty FROM crossdock_expected WHERE tenant_id = ? AND"
            + " purchase_order_id = ? AND warehouse_id = ? ORDER BY variant_id, store_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
          ps.setObject(3, warehouseId);
        },
        rs ->
            new Expected(
                rs.getObject("store_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty")),
        "list cross-dock expectations");
  }

  /**
   * Receives the allocated lines of a delivery at the warehouse and sends them straight across the
   * dock, on one transaction, once per event: per line, a batch for what crosses (never put away)
   * and one for the rest (put away as any delivery); per shop, one PENDING transfer with a line per
   * product shipping from that batch; what the order owes the shops drawn down by what they got.
   *
   * @param batchEvent the StockReceived row for each batch made
   * @return the transfers raised; empty when the event was already applied
   */
  public List<UUID> receiveOnce(
      UUID dedupeId,
      String consumer,
      UUID tenantId,
      UUID warehouseId,
      UUID poId,
      UUID receiptId,
      List<DockLine> lines,
      java.util.function.Function<Batch, OutboxRow> batchEvent) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumer)) return List.<UUID>of();
          Instant now = Instant.now();
          Map<UUID, List<TransferOrderLine>> byShop = new LinkedHashMap<>();
          for (DockLine line : lines) {
            BigDecimal crossing =
                line.shares().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal rest = line.received().subtract(crossing);
            UUID crossBatch = null;
            if (crossing.signum() > 0) {
              Batch b = batch(tenantId, warehouseId, line, crossing, now);
              InventoryRepository.insertCrossDockBatchTx(inventory, c, b);
              received(c, b, receiptId);
              insertOutbox(c, batchEvent.apply(b));
              crossBatch = b.id();
            }
            if (rest.signum() > 0) {
              Batch b = batch(tenantId, warehouseId, line, rest, now);
              inventory.insertBatch(c, b);
              received(c, b, receiptId);
              insertOutbox(c, batchEvent.apply(b));
            }
            for (Map.Entry<UUID, BigDecimal> share : line.shares().entrySet()) {
              if (share.getValue().signum() <= 0) continue;
              byShop
                  .computeIfAbsent(share.getKey(), k -> new ArrayList<>())
                  .add(
                      new TransferOrderLine(
                          Ids.newId(),
                          tenantId,
                          null,
                          line.variantId(),
                          share.getValue(),
                          null,
                          null,
                          line.reasons().get(share.getKey()),
                          crossBatch));
              drawDownTx(c, tenantId, poId, share.getKey(), line.variantId(), share.getValue());
            }
          }
          List<UUID> transfers = new ArrayList<>();
          for (Map.Entry<UUID, List<TransferOrderLine>> e : byShop.entrySet()) {
            UUID id = Ids.newId();
            List<TransferOrderLine> withOrder = new ArrayList<>();
            for (TransferOrderLine l : e.getValue()) {
              withOrder.add(
                  new TransferOrderLine(
                      l.id(),
                      tenantId,
                      id,
                      l.variantId(),
                      l.requestedQty(),
                      null,
                      null,
                      l.reason(),
                      l.sourceBatchId()));
            }
            InventoryRepository.createTransferOrderTx(
                c,
                new TransferOrder(
                    id,
                    tenantId,
                    warehouseId,
                    e.getKey(),
                    TransferOrder.TYPE_INTRANSIT,
                    TransferOrder.PENDING,
                    "cross-docked from purchase order " + Ids.shortRef(poId),
                    now,
                    null,
                    null,
                    TransferOrder.SOURCE_CROSSDOCK,
                    null,
                    poId,
                    receiptId),
                withOrder);
            transfers.add(id);
          }
          return transfers;
        },
        "cross-dock a delivery");
  }

  private static Batch batch(
      UUID tenantId, UUID warehouseId, DockLine line, BigDecimal qty, Instant now) {
    return new Batch(
        Ids.newId(),
        tenantId,
        warehouseId,
        line.variantId(),
        line.batchNo(),
        qty,
        qty,
        line.costPrice(),
        line.expiry(),
        now,
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        null,
        null,
        Batch.OWNERSHIP_OWNED,
        null,
        Batch.DUTY_PAID);
  }

  private static void received(Connection c, Batch b, UUID receiptId) throws SQLException {
    InventoryRepository.insertMovement(
        c,
        b.tenantId(),
        b.storeId(),
        b.variantId(),
        b.id(),
        MoveType.RECEIVE,
        b.receivedQty(),
        "GRN",
        receiptId,
        MovementAttribution.system());
  }

  private static void drawDownTx(
      Connection c, UUID tenantId, UUID poId, UUID storeId, UUID variantId, BigDecimal qty)
      throws SQLException {
    // A claim met in full is gone; one met in part keeps what is still owed.
    try (PreparedStatement ps =
        c.prepareStatement(
            "DELETE FROM crossdock_expected WHERE tenant_id = ? AND purchase_order_id = ? AND"
                + " store_id = ? AND variant_id = ? AND qty <= ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, poId);
      ps.setObject(3, storeId);
      ps.setObject(4, variantId);
      ps.setBigDecimal(5, qty);
      if (ps.executeUpdate() > 0) return;
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE crossdock_expected SET qty = qty - ?, updated_at = now() WHERE tenant_id = ? AND"
                + " purchase_order_id = ? AND store_id = ? AND variant_id = ?")) {
      ps.setBigDecimal(1, qty);
      ps.setObject(2, tenantId);
      ps.setObject(3, poId);
      ps.setObject(4, storeId);
      ps.setObject(5, variantId);
      ps.executeUpdate();
    }
  }
}
