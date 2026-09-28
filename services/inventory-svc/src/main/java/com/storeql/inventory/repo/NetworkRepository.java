package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.DirectPurchase;
import com.storeql.inventory.domain.Domain.Serving;
import com.storeql.inventory.domain.Domain.TransferOrder;
import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.inventory.domain.Domain.TransferProposalRun;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The serving network and the transfer proposals raised on it (intent/depot-dc-replenishment.md):
 * which warehouse serves which shop, what a shop buys direct, what is already on its way or
 * committed, and the runs that write DRAFT transfers. Every query filters by {@code tenant_id}
 * first.
 */
@ApplicationScoped
public class NetworkRepository extends BaseOutboxRepository {

  private static final String SERVING_COLUMNS =
      "id, tenant_id, store_id, warehouse_id, lead_time_days, created_by, created_at, updated_at";

  private static final String RUN_COLUMNS =
      "id, tenant_id, warehouse_id, run_by, run_at, cover_days, shops, transfers, lines,"
          + " short_lines";

  // ── The network ────────────────────────────────────────────────────────────

  /** Sets the shop's warehouse, replacing an earlier one; its exceptions stay. */
  public Serving upsertServing(Serving s) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO serving_relationships (id, tenant_id, store_id, warehouse_id,"
                      + " lead_time_days, created_by, created_at, updated_at) VALUES"
                      + " (?,?,?,?,?,?,now(),now()) ON CONFLICT (tenant_id, store_id) DO UPDATE SET"
                      + " warehouse_id = EXCLUDED.warehouse_id, lead_time_days ="
                      + " EXCLUDED.lead_time_days, updated_at = now() RETURNING "
                      + SERVING_COLUMNS)) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setObject(3, s.storeId());
            ps.setObject(4, s.warehouseId());
            ps.setInt(5, s.leadTimeDays());
            ps.setObject(6, s.createdBy());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapServing(rs);
            }
          }
        },
        "set serving relationship");
  }

  /** Removes the shop from the network, with its exceptions: it buys everything direct again. */
  public boolean deleteServing(UUID tenantId, UUID storeId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM serving_exceptions WHERE tenant_id = ? AND store_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM serving_relationships WHERE tenant_id = ? AND store_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            return ps.executeUpdate() > 0;
          }
        },
        "remove serving relationship");
  }

  public List<Serving> servings(UUID tenantId) {
    return query(
        "SELECT "
            + SERVING_COLUMNS
            + " FROM serving_relationships WHERE tenant_id = ? ORDER BY warehouse_id, store_id"
            + " LIMIT 1000",
        ps -> ps.setObject(1, tenantId),
        NetworkRepository::mapServing,
        "list serving relationships");
  }

  public Optional<Serving> servingOf(UUID tenantId, UUID storeId) {
    return query(
            "SELECT "
                + SERVING_COLUMNS
                + " FROM serving_relationships WHERE tenant_id = ? AND store_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            NetworkRepository::mapServing,
            "find serving relationship")
        .stream()
        .findFirst();
  }

  /** The shops a warehouse serves. */
  public List<Serving> shopsOf(UUID tenantId, UUID warehouseId) {
    return query(
        "SELECT "
            + SERVING_COLUMNS
            + " FROM serving_relationships WHERE tenant_id = ? AND warehouse_id = ? ORDER BY"
            + " store_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, warehouseId);
        },
        NetworkRepository::mapServing,
        "list shops served");
  }

  /** Marks a product the shop buys direct; marking it twice changes nothing. */
  public void addException(DirectPurchase e) {
    exec(
        "INSERT INTO serving_exceptions (id, tenant_id, store_id, variant_id, created_by,"
            + " created_at) VALUES (?,?,?,?,?,now()) ON CONFLICT (tenant_id, store_id, variant_id)"
            + " DO NOTHING",
        ps -> {
          ps.setObject(1, e.id());
          ps.setObject(2, e.tenantId());
          ps.setObject(3, e.storeId());
          ps.setObject(4, e.variantId());
          ps.setObject(5, e.createdBy());
        },
        "add serving exception");
  }

  public boolean removeException(UUID tenantId, UUID storeId, UUID variantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM serving_exceptions WHERE tenant_id = ? AND store_id = ? AND"
                      + " variant_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, variantId);
            return ps.executeUpdate() > 0;
          }
        },
        "remove serving exception");
  }

  /** Every shop's products bought direct. */
  public Map<UUID, Set<UUID>> directByShop(UUID tenantId) {
    Map<UUID, Set<UUID>> out = new HashMap<>();
    query(
        "SELECT store_id, variant_id FROM serving_exceptions WHERE tenant_id = ? LIMIT 100000",
        ps -> ps.setObject(1, tenantId),
        rs -> {
          out.computeIfAbsent(rs.getObject("store_id", UUID.class), k -> new HashSet<>())
              .add(rs.getObject("variant_id", UUID.class));
          return null;
        },
        "list serving exceptions");
    return out;
  }

  // ── What is on its way, and what is committed ──────────────────────────────

  /**
   * What is on its way to a store from its warehouse: proposed, released and shipped transfers not
   * yet received, and what a warehouse's purchase orders still owe it to cross-dock, per product.
   */
  public Map<UUID, BigDecimal> inboundByVariant(UUID tenantId, UUID storeId) {
    Map<UUID, BigDecimal> out =
        sumByVariant(
            "SELECT l.variant_id, SUM(l.requested_qty) AS qty FROM transfer_order_lines l JOIN"
                + " transfer_orders o ON o.tenant_id = l.tenant_id AND o.id = l.transfer_order_id"
                + " WHERE o.tenant_id = ? AND o.to_store_id = ? AND o.status IN"
                + " ('DRAFT','PENDING','SHIPPED') GROUP BY l.variant_id",
            tenantId,
            storeId,
            "inbound transfers");
    sumByVariant(
            "SELECT variant_id, SUM(qty) AS qty FROM crossdock_expected WHERE tenant_id = ? AND"
                + " store_id = ? GROUP BY variant_id",
            tenantId,
            storeId,
            "owed across the dock")
        .forEach((v, q) -> out.merge(v, q, BigDecimal::add));
    return out;
  }

  /**
   * What a warehouse has promised its shops and not yet shipped: DRAFT and PENDING, per product.
   */
  public Map<UUID, BigDecimal> committedByVariant(UUID tenantId, UUID warehouseId) {
    return sumByVariant(
        "SELECT l.variant_id, SUM(l.requested_qty) AS qty FROM transfer_order_lines l JOIN"
            + " transfer_orders o ON o.tenant_id = l.tenant_id AND o.id = l.transfer_order_id"
            + " WHERE o.tenant_id = ? AND o.from_store_id = ? AND o.status IN ('DRAFT','PENDING')"
            + " GROUP BY l.variant_id",
        tenantId,
        warehouseId,
        "committed transfers");
  }

  private Map<UUID, BigDecimal> sumByVariant(String sql, UUID tenantId, UUID storeId, String what) {
    Map<UUID, BigDecimal> out = new HashMap<>();
    query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        rs -> {
          out.put(rs.getObject("variant_id", UUID.class), rs.getBigDecimal("qty"));
          return null;
        },
        what);
    return out;
  }

  /** How many proposed transfers from the warehouse still wait for a person. */
  public int openDrafts(UUID tenantId, UUID warehouseId) {
    return query(
            "SELECT count(*) AS n FROM transfer_orders WHERE tenant_id = ? AND from_store_id = ?"
                + " AND status = 'DRAFT'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, warehouseId);
            },
            rs -> rs.getInt("n"),
            "count draft transfers")
        .get(0);
  }

  // ── Runs ───────────────────────────────────────────────────────────────────

  /** The run a key already made, if any: a retried run gets it back whatever the stock says now. */
  public Optional<TransferProposalRun> findRunByKey(UUID tenantId, String idempotencyKey) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + RUN_COLUMNS
                      + " FROM transfer_proposal_runs WHERE tenant_id = ? AND idempotency_key ="
                      + " ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<TransferProposalRun>empty();
              return Optional.of(mapRun(c, rs));
            }
          }
        },
        "find transfer proposal run by key");
  }

  /** The warehouse's runs, newest first. */
  public List<TransferProposalRun> runs(UUID tenantId, UUID warehouseId) {
    return inTx(
        c -> {
          List<TransferProposalRun> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + RUN_COLUMNS
                      + " FROM transfer_proposal_runs WHERE tenant_id = ? AND warehouse_id = ?"
                      + " ORDER BY run_at DESC, id DESC LIMIT 20")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) out.add(mapRun(c, rs));
            }
          }
          return out;
        },
        "list transfer proposal runs");
  }

  /**
   * Writes a run and the DRAFT transfers it raised on one transaction, once per key: a key that
   * already made a run returns that run and writes nothing.
   */
  public TransferProposalRun saveRun(
      TransferProposalRun run,
      String idempotencyKey,
      Map<TransferOrder, List<TransferOrderLine>> transfers) {
    return inTx(
        c -> {
          if (idempotencyKey != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT "
                        + RUN_COLUMNS
                        + " FROM transfer_proposal_runs WHERE tenant_id = ? AND idempotency_key"
                        + " = ?")) {
              ps.setObject(1, run.tenantId());
              ps.setString(2, idempotencyKey);
              try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRun(c, rs);
              }
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO transfer_proposal_runs (id, tenant_id, warehouse_id, run_by, run_at,"
                      + " cover_days, shops, transfers, lines, short_lines, idempotency_key) VALUES"
                      + " (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, run.id());
            ps.setObject(2, run.tenantId());
            ps.setObject(3, run.warehouseId());
            ps.setObject(4, run.runBy());
            ps.setObject(5, run.runAt().atOffset(ZoneOffset.UTC));
            ps.setInt(6, run.coverDays());
            ps.setInt(7, run.shops());
            ps.setInt(8, run.transfers());
            ps.setInt(9, run.lines());
            ps.setInt(10, run.shortLines());
            ps.setString(11, idempotencyKey);
            ps.executeUpdate();
          }
          for (Map.Entry<TransferOrder, List<TransferOrderLine>> t : transfers.entrySet()) {
            InventoryRepository.createTransferOrderTx(c, t.getKey(), t.getValue());
          }
          return run;
        },
        "save transfer proposal run");
  }

  /**
   * Releases a proposed transfer into the ordinary lifecycle: DRAFT → PENDING.
   *
   * @throws ApiException 404 {@code TRANSFER_ORDER_NOT_FOUND}; 409 {@code
   *     INVENTORY_TRANSFER_NOT_DRAFT} when it is not a draft
   */
  public void release(UUID tenantId, UUID transferId) {
    inTx(
        c -> {
          String status;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT status FROM transfer_orders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, transferId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order");
              }
              status = rs.getString("status");
            }
          }
          if (!TransferOrder.DRAFT.equals(status)) {
            throw ApiException.conflict(
                "INVENTORY_TRANSFER_NOT_DRAFT",
                "only a proposed DRAFT transfer is released; this one is " + status);
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transfer_orders SET status = 'PENDING' WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, transferId);
            ps.executeUpdate();
          }
          return null;
        },
        "release proposed transfer");
  }

  private static TransferProposalRun mapRun(Connection c, ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    UUID tenantId = rs.getObject("tenant_id", UUID.class);
    List<UUID> transferIds = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id FROM transfer_orders WHERE tenant_id = ? AND proposal_run_id = ? ORDER BY"
                + " to_store_id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet t = ps.executeQuery()) {
        while (t.next()) transferIds.add(t.getObject("id", UUID.class));
      }
    }
    return new TransferProposalRun(
        id,
        tenantId,
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("run_by", UUID.class),
        rs.getObject("run_at", OffsetDateTime.class).toInstant(),
        rs.getInt("cover_days"),
        rs.getInt("shops"),
        rs.getInt("transfers"),
        rs.getInt("lines"),
        rs.getInt("short_lines"),
        transferIds);
  }

  private static Serving mapServing(ResultSet rs) throws SQLException {
    return new Serving(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getInt("lead_time_days"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
