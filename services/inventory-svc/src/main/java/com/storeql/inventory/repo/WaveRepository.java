package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.AwaitingLine;
import com.storeql.inventory.domain.Domain.AwaitingOrder;
import com.storeql.inventory.domain.Domain.MoveType;
import com.storeql.inventory.domain.Domain.MovementAttribution;
import com.storeql.inventory.domain.Domain.PickWave;
import com.storeql.inventory.domain.Domain.PickWaveAllocation;
import com.storeql.inventory.domain.Domain.PickWaveLine;
import com.storeql.inventory.domain.Domain.PickingRule;
import com.storeql.inventory.domain.Domain.PickingRuleZonePriority;
import com.storeql.inventory.domain.Domain.Reservation;
import com.storeql.inventory.domain.Waves;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Wave picking: the orders waiting at a store (a projection of order-svc's confirmations), the
 * waves built from them, and the completion that deducts exactly what was picked. Every statement
 * filters by {@code tenant_id} first.
 */
@ApplicationScoped
public class WaveRepository extends BaseOutboxRepository {

  private static final Logger LOG = System.getLogger(WaveRepository.class.getName());

  static final String AWAIT_CONSUMER = "inventory-svc/awaiting-orders";

  @Inject InventoryRepository inventory;

  // ── The projection: confirmed online orders waiting at their store ─────────

  /** Projects a confirmed order once; a redelivered confirmation projects nothing twice. */
  public boolean awaitOnce(
      UUID eventId,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String fulfilmentType,
      Instant confirmedAt,
      Map<UUID, BigDecimal> lines) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, AWAIT_CONSUMER)) return false;
          // Cancelled or handed over in full before this confirmation arrived: done is done.
          if (doneTx(c, tenantId, orderId)) return false;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO awaiting_orders (order_id, tenant_id, store_id, fulfilment_type,"
                      + " confirmed_at) VALUES (?,?,?,?,?) ON CONFLICT (order_id) DO NOTHING")) {
            ps.setObject(1, orderId);
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setString(4, fulfilmentType);
            ps.setObject(5, confirmedAt.atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO awaiting_order_lines (id, tenant_id, order_id, variant_id,"
                      + " qty_outstanding) VALUES (?,?,?,?,?) ON CONFLICT (order_id, variant_id) DO"
                      + " NOTHING")) {
            for (Map.Entry<UUID, BigDecimal> e : lines.entrySet()) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, orderId);
              ps.setObject(4, e.getKey());
              ps.setBigDecimal(5, e.getValue());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "await confirmed order");
  }

  /**
   * The order is cancelled, or handed over in full: it waits no more, and a confirmation that
   * arrives after this cannot make it wait again.
   */
  public void forget(UUID tenantId, UUID orderId, boolean couldHaveWaited) {
    inTx(
        c -> {
          forgetTx(c, tenantId, orderId, couldHaveWaited);
          return null;
        },
        "forget awaiting order");
  }

  /**
   * Drops the order from the waiting list and, when it waited or {@code couldHaveWaited} (an online
   * pickup or delivery order whose confirmation may still be on its way), leaves the tombstone; a
   * till sale leaves nothing.
   */
  private static void forgetTx(Connection c, UUID tenantId, UUID orderId, boolean couldHaveWaited)
      throws SQLException {
    int waited;
    try (PreparedStatement ps =
        c.prepareStatement("DELETE FROM awaiting_orders WHERE tenant_id = ? AND order_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      waited = ps.executeUpdate();
    }
    if (waited == 0 && !couldHaveWaited) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO awaiting_orders_done (order_id, tenant_id) VALUES (?,?) ON CONFLICT"
                + " (order_id) DO NOTHING")) {
      ps.setObject(1, orderId);
      ps.setObject(2, tenantId);
      ps.executeUpdate();
    }
  }

  private static boolean doneTx(Connection c, UUID tenantId, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM awaiting_orders_done WHERE tenant_id = ? AND order_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * What order-svc says is still outstanding on a line after a handover, whoever made it: the line
   * never waits for more than that. Absolute, so a redelivered or reordered event states the same
   * truth instead of subtracting twice.
   */
  public void outstandingKnown(
      UUID tenantId, UUID orderId, UUID variantId, BigDecimal outstanding) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE awaiting_order_lines SET qty_outstanding = LEAST(qty_outstanding, ?)"
                      + " WHERE tenant_id = ? AND order_id = ? AND variant_id = ?")) {
            ps.setBigDecimal(1, outstanding);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.setObject(4, variantId);
            ps.executeUpdate();
          }
          pruneOrderTx(c, tenantId, orderId);
          return null;
        },
        "awaiting line outstanding known");
  }

  /** A line fulfilled outside a wave (the Fulfil button): the order needs that much less. */
  public void fulfilledByHand(UUID tenantId, UUID orderId, UUID variantId, BigDecimal qty) {
    inTx(
        c -> {
          reduceAwaitingTx(c, tenantId, orderId, variantId, qty);
          pruneOrderTx(c, tenantId, orderId);
          return null;
        },
        "awaiting line fulfilled by hand");
  }

  /**
   * The order needs {@code qty} less of a line; shared with the order-line close (same package).
   */
  static void reduceAwaitingTx(
      Connection c, UUID tenantId, UUID orderId, UUID variantId, BigDecimal qty)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE awaiting_order_lines SET qty_outstanding = GREATEST(0, qty_outstanding - ?)"
                + " WHERE tenant_id = ? AND order_id = ? AND variant_id = ?")) {
      ps.setBigDecimal(1, qty);
      ps.setObject(2, tenantId);
      ps.setObject(3, orderId);
      ps.setObject(4, variantId);
      ps.executeUpdate();
    }
  }

  /** An order with nothing outstanding waits no more, and is done. */
  static void pruneOrderTx(Connection c, UUID tenantId, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM awaiting_order_lines WHERE tenant_id = ? AND order_id = ? AND"
                + " qty_outstanding > 0 LIMIT 1")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return; // something still waits
      }
    }
    forgetTx(c, tenantId, orderId, false);
  }

  /** The orders waiting at a store, earliest confirmed first, with what each still needs. */
  public List<AwaitingOrder> awaiting(UUID tenantId, UUID storeId) {
    List<AwaitingOrder> heads =
        query(
            "SELECT order_id, tenant_id, store_id, fulfilment_type, confirmed_at, wave_id FROM"
                + " awaiting_orders WHERE tenant_id = ?"
                + (storeId == null ? "" : " AND store_id = ?")
                // A wave takes the whole list, so it is not paged; the bound is a ceiling a store
                // never reaches in a day, not a page.
                + " ORDER BY confirmed_at, order_id LIMIT 500",
            ps -> {
              ps.setObject(1, tenantId);
              if (storeId != null) ps.setObject(2, storeId);
            },
            rs ->
                new AwaitingOrder(
                    rs.getObject("order_id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("store_id", UUID.class),
                    rs.getString("fulfilment_type"),
                    rs.getObject("confirmed_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("wave_id", UUID.class),
                    List.of()),
            "list awaiting orders");
    if (heads.isEmpty()) return heads;
    Map<UUID, List<AwaitingLine>> lines = new HashMap<>();
    query(
        "SELECT l.order_id, l.variant_id, l.qty_outstanding FROM awaiting_order_lines l"
            + " JOIN awaiting_orders o ON o.tenant_id = l.tenant_id AND o.order_id = l.order_id"
            + " WHERE l.tenant_id = ? AND l.qty_outstanding > 0"
            + (storeId == null ? "" : " AND o.store_id = ?")
            + " ORDER BY l.id",
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        rs -> {
          lines
              .computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new AwaitingLine(
                      rs.getObject("variant_id", UUID.class), rs.getBigDecimal("qty_outstanding")));
          return null;
        },
        "list awaiting lines");
    List<AwaitingOrder> full = new ArrayList<>(heads.size());
    for (AwaitingOrder o : heads) {
      full.add(
          new AwaitingOrder(
              o.orderId(),
              o.tenantId(),
              o.storeId(),
              o.fulfilmentType(),
              o.confirmedAt(),
              o.waveId(),
              List.copyOf(lines.getOrDefault(o.orderId(), List.of()))));
    }
    return full;
  }

  // ── Planning inputs: the shelf in the rule's order, the zones in walk order ─

  /** The variant's batches a sale may draw, in the order the resolved picking rule draws them. */
  public List<Waves.Stock> stockInRuleOrder(UUID tenantId, UUID storeId, UUID variantId) {
    Optional<PickingRule> rule = inventory.resolvePickingRule(tenantId, storeId, variantId);
    List<UUID> zones = zonePriorities(tenantId, rule);
    String orderBy =
        InventoryRepository.pickOrderClause(
            rule.map(PickingRule::strategy).orElse(null),
            rule.map(PickingRule::gradePreference).orElse(null),
            zones);
    return query(
        "SELECT id, batch_no, remaining_qty, zone_id FROM inventory_batches WHERE tenant_id = ?"
            + " AND store_id = ? AND variant_id = ? AND remaining_qty > 0 AND material_status ="
            + " 'AVAILABLE' AND duty_status = 'DUTY_PAID' ORDER BY "
            + orderBy,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setObject(3, variantId);
        },
        rs ->
            new Waves.Stock(
                rs.getObject("id", UUID.class),
                variantId,
                rs.getObject("zone_id", UUID.class),
                rs.getString("batch_no"),
                rs.getBigDecimal("remaining_qty")),
        "stock in rule order");
  }

  /** The zone walk: the first ZONE_PRIORITY rule among the variants gives it; none means none. */
  public List<UUID> zoneWalk(UUID tenantId, UUID storeId, List<UUID> variantIds) {
    for (UUID v : variantIds) {
      Optional<PickingRule> rule = inventory.resolvePickingRule(tenantId, storeId, v);
      List<UUID> zones = zonePriorities(tenantId, rule);
      if (!zones.isEmpty()) return zones;
    }
    return List.of();
  }

  private List<UUID> zonePriorities(UUID tenantId, Optional<PickingRule> rule) {
    return rule.filter(r -> PickingRule.ZONE_PRIORITY.equals(r.strategy()))
        .map(
            r ->
                inventory.listZonePriorities(tenantId, r.id()).stream()
                    .map(PickingRuleZonePriority::zoneId)
                    .toList())
        .orElse(List.of());
  }

  // ── Waves ──────────────────────────────────────────────────────────────────

  /**
   * Creates the wave with its lines and allocations and takes the orders into it, once per
   * idempotency key.
   *
   * @throws ApiException 409 {@code INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE}
   */
  public PickWave create(PickWave w, Waves.Plan plan, List<UUID> orderIds, String idempotencyKey) {
    return inTx(
        c -> {
          if (idempotencyKey != null) {
            Optional<PickWave> built = findByKeyTx(c, w.tenantId(), idempotencyKey);
            if (built.isPresent()) return built.get();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT order_id, wave_id FROM awaiting_orders WHERE tenant_id = ? AND order_id ="
                      + " ANY(?) FOR UPDATE")) {
            ps.setObject(1, w.tenantId());
            ps.setArray(2, c.createArrayOf("uuid", orderIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                UUID inWave = rs.getObject("wave_id", UUID.class);
                if (inWave != null) {
                  throw ApiException.conflict(
                      "INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE",
                      "order "
                          + rs.getObject("order_id", UUID.class)
                          + " is already in wave "
                          + inWave);
                }
              }
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO pick_waves (id, tenant_id, store_id, status, created_by, created_at,"
                      + " idempotency_key) VALUES (?,?,?,'OPEN',?,?,?)")) {
            ps.setObject(1, w.id());
            ps.setObject(2, w.tenantId());
            ps.setObject(3, w.storeId());
            ps.setObject(4, w.createdBy());
            ps.setObject(5, w.createdAt().atOffset(ZoneOffset.UTC));
            ps.setString(6, idempotencyKey);
            ps.executeUpdate();
          }
          try (PreparedStatement lines =
                  c.prepareStatement(
                      "INSERT INTO pick_wave_lines (id, tenant_id, wave_id, walk_order, zone_id,"
                          + " batch_id, batch_no, variant_id, directed_qty) VALUES (?,?,?,?,?,?,?,?,?)");
              PreparedStatement allocs =
                  c.prepareStatement(
                      "INSERT INTO pick_wave_allocations (id, tenant_id, line_id, seq, order_id, qty)"
                          + " VALUES (?,?,?,?,?,?)")) {
            for (Waves.Line l : plan.lines()) {
              UUID lineId = Ids.newId();
              lines.setObject(1, lineId);
              lines.setObject(2, w.tenantId());
              lines.setObject(3, w.id());
              lines.setInt(4, l.walkOrder());
              lines.setObject(5, l.zoneId());
              lines.setObject(6, l.batchId());
              lines.setString(7, l.batchNo());
              lines.setObject(8, l.variantId());
              lines.setBigDecimal(9, l.directedQty());
              lines.addBatch();
              int seq = 0;
              for (Waves.Allocation a : l.allocations()) {
                allocs.setObject(1, Ids.newId());
                allocs.setObject(2, w.tenantId());
                allocs.setObject(3, lineId);
                allocs.setInt(4, seq++);
                allocs.setObject(5, a.orderId());
                allocs.setBigDecimal(6, a.qty());
                allocs.addBatch();
              }
            }
            lines.executeBatch();
            allocs.executeBatch();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE awaiting_orders SET wave_id = ? WHERE tenant_id = ? AND order_id = ANY(?)")) {
            ps.setObject(1, w.id());
            ps.setObject(2, w.tenantId());
            ps.setArray(3, c.createArrayOf("uuid", orderIds.toArray()));
            ps.executeUpdate();
          }
          return findTx(c, w.tenantId(), w.id()).get();
        },
        "create pick wave");
  }

  public Optional<PickWave> find(UUID tenantId, UUID id) {
    return inTx(c -> findTx(c, tenantId, id), "find pick wave");
  }

  /** The wave a key already built, if any: a retried build gets it back whatever waits now. */
  public Optional<PickWave> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
    return inTx(c -> findByKeyTx(c, tenantId, idempotencyKey), "find pick wave by key");
  }

  private Optional<PickWave> findByKeyTx(Connection c, UUID tenantId, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id FROM pick_waves WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return findTx(c, tenantId, rs.getObject("id", UUID.class));
      }
    }
  }

  /** The store's waves, newest first, without their lines. */
  public List<PickWave> list(UUID tenantId, UUID storeId, String status) {
    return query(
        "SELECT w.id, w.tenant_id, w.store_id, w.status, w.created_by, w.created_at, w.completed_by,"
            + " w.completed_at, w.cancelled_at, (SELECT COUNT(DISTINCT a.order_id) FROM"
            + " pick_wave_allocations a JOIN pick_wave_lines l ON l.tenant_id = a.tenant_id AND l.id"
            + " = a.line_id WHERE a.tenant_id = w.tenant_id AND l.wave_id = w.id) AS order_count FROM"
            + " pick_waves w WHERE w.tenant_id = ?"
            + (storeId == null ? "" : " AND w.store_id = ?")
            + (status == null ? "" : " AND w.status = ?")
            + " ORDER BY w.created_at DESC, w.id DESC LIMIT 100",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (status != null) ps.setString(i, status);
        },
        rs -> mapWave(rs, List.of()),
        "list pick waves");
  }

  private Optional<PickWave> findTx(Connection c, UUID tenantId, UUID id) throws SQLException {
    PickWave head;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT w.id, w.tenant_id, w.store_id, w.status, w.created_by, w.created_at,"
                + " w.completed_by, w.completed_at, w.cancelled_at, (SELECT COUNT(DISTINCT a.order_id)"
                + " FROM pick_wave_allocations a JOIN pick_wave_lines l ON l.tenant_id = a.tenant_id"
                + " AND l.id = a.line_id WHERE a.tenant_id = w.tenant_id AND l.wave_id = w.id) AS"
                + " order_count FROM pick_waves w WHERE w.tenant_id = ? AND w.id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        head = mapWave(rs, List.of());
      }
    }
    Map<UUID, List<PickWaveAllocation>> allocs = new HashMap<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT a.line_id, a.order_id, a.qty, a.picked_qty FROM pick_wave_allocations a JOIN"
                + " pick_wave_lines l ON l.tenant_id = a.tenant_id AND l.id = a.line_id WHERE"
                + " a.tenant_id = ? AND l.wave_id = ? ORDER BY a.seq")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          allocs
              .computeIfAbsent(rs.getObject("line_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new PickWaveAllocation(
                      rs.getObject("order_id", UUID.class),
                      rs.getBigDecimal("qty"),
                      rs.getBigDecimal("picked_qty")));
        }
      }
    }
    List<PickWaveLine> lines = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, wave_id, walk_order, zone_id, batch_id, batch_no, variant_id, directed_qty,"
                + " picked_qty FROM pick_wave_lines WHERE tenant_id = ? AND wave_id = ? ORDER BY"
                + " walk_order")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          UUID lineId = rs.getObject("id", UUID.class);
          lines.add(
              new PickWaveLine(
                  lineId,
                  rs.getObject("wave_id", UUID.class),
                  rs.getInt("walk_order"),
                  rs.getObject("zone_id", UUID.class),
                  rs.getObject("batch_id", UUID.class),
                  rs.getString("batch_no"),
                  rs.getObject("variant_id", UUID.class),
                  rs.getBigDecimal("directed_qty"),
                  rs.getBigDecimal("picked_qty"),
                  List.copyOf(allocs.getOrDefault(lineId, List.of()))));
        }
      }
    }
    return Optional.of(
        new PickWave(
            head.id(),
            head.tenantId(),
            head.storeId(),
            head.status(),
            head.createdBy(),
            head.createdAt(),
            head.completedBy(),
            head.completedAt(),
            head.cancelledAt(),
            head.orderCount(),
            lines));
  }

  private static PickWave mapWave(ResultSet rs, List<PickWaveLine> lines) throws SQLException {
    OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
    OffsetDateTime cancelled = rs.getObject("cancelled_at", OffsetDateTime.class);
    return new PickWave(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("completed_by", UUID.class),
        completed == null ? null : completed.toInstant(),
        cancelled == null ? null : cancelled.toInstant(),
        rs.getInt("order_count"),
        lines);
  }

  private static String lockStatusTx(Connection c, UUID tenantId, UUID waveId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT status FROM pick_waves WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, waveId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next())
          throw ApiException.notFound("INVENTORY_WAVE_NOT_FOUND", "no wave " + waveId);
        return rs.getString("status");
      }
    }
  }

  private static void requireOpen(String status, UUID waveId) {
    if (!PickWave.OPEN.equals(status)) {
      throw ApiException.conflict(
          "INVENTORY_WAVE_NOT_OPEN",
          "wave " + waveId + " is " + status + "; only an OPEN wave is picked");
    }
  }

  /**
   * Records what was picked per line, at most what was directed.
   *
   * @throws ApiException 409 {@code INVENTORY_WAVE_NOT_OPEN}; 400 {@code
   *     INVENTORY_WAVE_LINE_UNKNOWN}, {@code INVENTORY_WAVE_PICK_EXCEEDS_LINE}
   */
  public PickWave recordPicks(UUID tenantId, UUID waveId, Map<UUID, BigDecimal> picks) {
    return inTx(
        c -> {
          requireOpen(lockStatusTx(c, tenantId, waveId), waveId);
          for (Map.Entry<UUID, BigDecimal> e : picks.entrySet()) {
            BigDecimal directed;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT directed_qty FROM pick_wave_lines WHERE tenant_id = ? AND wave_id = ? AND id"
                        + " = ?")) {
              ps.setObject(1, tenantId);
              ps.setObject(2, waveId);
              ps.setObject(3, e.getKey());
              try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                  throw ApiException.badRequest(
                      "INVENTORY_WAVE_LINE_UNKNOWN",
                      "line " + e.getKey() + " is not on wave " + waveId);
                }
                directed = rs.getBigDecimal("directed_qty");
              }
            }
            if (e.getValue().compareTo(directed) > 0) {
              throw ApiException.badRequest(
                  "INVENTORY_WAVE_PICK_EXCEEDS_LINE",
                  "line "
                      + e.getKey()
                      + ": "
                      + e.getValue().toPlainString()
                      + " picked against "
                      + directed.toPlainString()
                      + " directed");
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE pick_wave_lines SET picked_qty = ? WHERE tenant_id = ? AND id = ?")) {
              ps.setBigDecimal(1, e.getValue());
              ps.setObject(2, tenantId);
              ps.setObject(3, e.getKey());
              ps.executeUpdate();
            }
          }
          return findTx(c, tenantId, waveId).get();
        },
        "record wave picks");
  }

  /**
   * Completes the wave: for every line, what was picked is shared out to its orders (earliest
   * first), drawn from exactly that batch as SALE movements against each order, the orders' holds
   * reduced by as much, what each order still needs reduced, and the picks remembered so the
   * fulfilment that follows deducts nothing twice. Announces what {@code events} builds from the
   * picked quantities per order and product.
   *
   * @throws ApiException 409 {@code INVENTORY_WAVE_NOT_OPEN}; 422 {@code INVENTORY_WAVE_STOCK_GONE}
   *     when a picked batch no longer holds what was picked
   */
  public PickWave complete(
      UUID tenantId,
      UUID waveId,
      UUID by,
      BiFunction<PickWave, Map<UUID, Map<UUID, BigDecimal>>, List<OutboxRow>> events) {
    return inTx(
        c -> {
          requireOpen(lockStatusTx(c, tenantId, waveId), waveId);
          PickWave wave = findTx(c, tenantId, waveId).get();
          Map<UUID, Map<UUID, BigDecimal>> perOrder = new LinkedHashMap<>();
          for (PickWaveLine line : wave.lines()) {
            BigDecimal picked = line.pickedQty() == null ? BigDecimal.ZERO : line.pickedQty();
            List<Waves.Allocation> allocations =
                line.orders().stream()
                    .map(a -> new Waves.Allocation(a.orderId(), a.qty()))
                    .toList();
            List<Waves.Allocation> shares = Waves.share(allocations, picked);
            for (int i = 0; i < shares.size(); i++) {
              Waves.Allocation share = shares.get(i);
              // An order that left the waiting list while the wave was open — cancelled, or handed
              // over by hand — is not drawn: what was picked for it goes back on the shelf, and the
              // share recorded against it is what actually left.
              BigDecimal drawnBefore =
                  perOrder
                      .getOrDefault(share.orderId(), Map.of())
                      .getOrDefault(line.variantId(), BigDecimal.ZERO);
              BigDecimal draw =
                  share
                      .qty()
                      .min(
                          outstandingTx(c, tenantId, share.orderId(), line.variantId())
                              .subtract(drawnBefore))
                      .max(BigDecimal.ZERO);
              if (draw.compareTo(share.qty()) < 0) {
                LOG.log(
                    Level.WARNING,
                    "wave {0}: order {1} left while the wave was open; {2} of {3} picked for it"
                        + " stays on the shelf",
                    waveId,
                    share.orderId(),
                    share.qty().subtract(draw).toPlainString(),
                    share.qty().toPlainString());
              }
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "UPDATE pick_wave_allocations SET picked_qty = ? WHERE tenant_id = ? AND line_id = ?"
                          + " AND seq = ?")) {
                ps.setBigDecimal(1, draw);
                ps.setObject(2, tenantId);
                ps.setObject(3, line.id());
                ps.setInt(4, i);
                ps.executeUpdate();
              }
              if (draw.signum() <= 0) continue;
              drawFromBatchTx(c, tenantId, wave.storeId(), line, share.orderId(), draw);
              perOrder
                  .computeIfAbsent(share.orderId(), k -> new LinkedHashMap<>())
                  .merge(line.variantId(), draw, BigDecimal::add);
            }
          }
          for (Map.Entry<UUID, Map<UUID, BigDecimal>> o : perOrder.entrySet()) {
            for (Map.Entry<UUID, BigDecimal> v : o.getValue().entrySet()) {
              reduceHoldsTx(c, tenantId, o.getKey(), v.getKey(), v.getValue());
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "INSERT INTO wave_picked_lines (id, tenant_id, wave_id, order_id, variant_id, qty)"
                          + " VALUES (?,?,?,?,?,?)")) {
                ps.setObject(1, Ids.newId());
                ps.setObject(2, tenantId);
                ps.setObject(3, waveId);
                ps.setObject(4, o.getKey());
                ps.setObject(5, v.getKey());
                ps.setBigDecimal(6, v.getValue());
                ps.executeUpdate();
              }
              reduceAwaitingTx(c, tenantId, o.getKey(), v.getKey(), v.getValue());
              inventory.checkThresholdTx(c, tenantId, wave.storeId(), v.getKey());
            }
            pruneOrderTx(c, tenantId, o.getKey());
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE awaiting_orders SET wave_id = NULL WHERE tenant_id = ? AND wave_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, waveId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE pick_waves SET status = 'COMPLETED', completed_by = ?, completed_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, by);
            ps.setObject(2, tenantId);
            ps.setObject(3, waveId);
            ps.executeUpdate();
          }
          PickWave done = findTx(c, tenantId, waveId).get();
          for (OutboxRow e : events.apply(done, perOrder)) insertOutbox(c, e);
          return done;
        },
        "complete pick wave");
  }

  /**
   * Takes {@code qty} from exactly this line's batch, as the sale the order is. The batch must
   * still be sellable here — at this store, available, duty paid — as it was when the wave directed
   * to it; a batch quarantined or moved since is {@code INVENTORY_WAVE_STOCK_GONE}. A draw from a
   * batch the supplier still owns announces {@code ConsignmentStockSold} on this transaction, as
   * the ordinary sale deduction does: the supplier is owed the moment the stock leaves.
   */
  private void drawFromBatchTx(
      Connection c, UUID tenantId, UUID storeId, PickWaveLine line, UUID orderId, BigDecimal qty)
      throws SQLException {
    String ownership;
    UUID ownerSupplierId;
    BigDecimal costPrice;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT ownership, owner_supplier_id, cost_price FROM inventory_batches WHERE tenant_id"
                + " = ? AND id = ? AND store_id = ? AND material_status = 'AVAILABLE' AND duty_status"
                + " = 'DUTY_PAID' FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, line.batchId());
      ps.setObject(3, storeId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.unprocessable(
              "INVENTORY_WAVE_STOCK_GONE",
              "batch "
                  + line.batchNo()
                  + " is no longer available to sell at this store; count it and pick again");
        }
        ownership = rs.getString("ownership");
        ownerSupplierId = rs.getObject("owner_supplier_id", UUID.class);
        costPrice = rs.getBigDecimal("cost_price");
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE inventory_batches SET remaining_qty = remaining_qty - ? WHERE tenant_id = ? AND id"
                + " = ? AND remaining_qty >= ?")) {
      ps.setBigDecimal(1, qty);
      ps.setObject(2, tenantId);
      ps.setObject(3, line.batchId());
      ps.setBigDecimal(4, qty);
      if (ps.executeUpdate() == 0) {
        throw ApiException.unprocessable(
            "INVENTORY_WAVE_STOCK_GONE",
            "batch "
                + line.batchNo()
                + " no longer holds the "
                + qty.toPlainString()
                + " picked; count it and pick again");
      }
    }
    InventoryRepository.insertMovement(
        c,
        tenantId,
        storeId,
        line.variantId(),
        line.batchId(),
        MoveType.SALE,
        qty.negate(),
        "ORDER",
        orderId,
        MovementAttribution.system());
    if ("CONSIGNMENT".equals(ownership) && ownerSupplierId != null) {
      insertOutbox(
          c,
          new OutboxRow(
              "ConsignmentStockSold",
              "storeql.inventory.consignment-stock-sold",
              tenantId,
              line.batchId(),
              com.storeql.inventory.service.Events.consignmentStockSold(
                  tenantId,
                  storeId,
                  line.variantId(),
                  line.batchId(),
                  ownerSupplierId,
                  orderId,
                  qty,
                  costPrice)));
    }
  }

  /** What an order still waits for on a product, locked for the wave that is about to draw it. */
  private static BigDecimal outstandingTx(Connection c, UUID tenantId, UUID orderId, UUID variantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT qty_outstanding FROM awaiting_order_lines WHERE tenant_id = ? AND order_id = ?"
                + " AND variant_id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal("qty_outstanding") : BigDecimal.ZERO;
      }
    }
  }

  /** The order's holds on the product give up what the wave took; a hold emptied is consumed. */
  private static void reduceHoldsTx(
      Connection c, UUID tenantId, UUID orderId, UUID variantId, BigDecimal qty)
      throws SQLException {
    List<Reservation> holds = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, qty FROM reservations WHERE tenant_id = ? AND order_id = ? AND variant_id = ?"
                + " AND status = 'HELD' ORDER BY created_at, id FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          holds.add(
              new Reservation(
                  rs.getObject("id", UUID.class),
                  tenantId,
                  null,
                  variantId,
                  rs.getBigDecimal("qty"),
                  orderId,
                  Reservation.HELD,
                  null,
                  null,
                  null));
        }
      }
    }
    BigDecimal left = qty;
    for (Reservation h : holds) {
      if (left.signum() <= 0) break;
      BigDecimal take = h.qty().min(left);
      String sql =
          take.compareTo(h.qty()) >= 0
              ? "UPDATE reservations SET status = 'CONSUMED' WHERE tenant_id = ? AND id = ?"
              : "UPDATE reservations SET qty = qty - ? WHERE tenant_id = ? AND id = ?";
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        int i = 1;
        if (take.compareTo(h.qty()) < 0) ps.setBigDecimal(i++, take);
        ps.setObject(i++, tenantId);
        ps.setObject(i, h.id());
        ps.executeUpdate();
      }
      left = left.subtract(take);
    }
  }

  /**
   * Cancels an open wave: nothing moved, the orders wait for the next one.
   *
   * @throws ApiException 409 {@code INVENTORY_WAVE_NOT_OPEN}
   */
  public PickWave cancel(UUID tenantId, UUID waveId) {
    return inTx(
        c -> {
          requireOpen(lockStatusTx(c, tenantId, waveId), waveId);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE pick_waves SET status = 'CANCELLED', cancelled_at = now() WHERE tenant_id = ?"
                      + " AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, waveId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE awaiting_orders SET wave_id = NULL WHERE tenant_id = ? AND wave_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, waveId);
            ps.executeUpdate();
          }
          return findTx(c, tenantId, waveId).get();
        },
        "cancel pick wave");
  }

  // ── The guard: a fulfilment of what a wave already picked ──────────────────

  /**
   * What a wave already drew for this order line, acknowledged on the fulfilment's own dedupe id:
   * the fulfilment then deducts only what is left, and the line's revenue is recorded here for the
   * whole, once. Zero when no wave picked the line — the ordinary path runs untouched. Redelivered,
   * the same answer: the picks this dedupe id acknowledged count again, so the remainder the caller
   * computes is the same remainder.
   */
  public BigDecimal pickedByWave(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      BigDecimal netAmount) {
    UUID ack = Ids.derived(dedupeId, "wave-pick");
    return inTx(
        c -> {
          List<UUID> pickIds = new ArrayList<>();
          UUID lastWave = null;
          BigDecimal lastQty = BigDecimal.ZERO;
          BigDecimal picked = BigDecimal.ZERO;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, wave_id, qty FROM wave_picked_lines WHERE tenant_id = ? AND order_id"
                      + " = ? AND variant_id = ? AND (acknowledged_by IS NULL OR acknowledged_by = ?)"
                      + " ORDER BY picked_at, id FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            ps.setObject(3, variantId);
            ps.setObject(4, ack);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next() && picked.compareTo(qty) < 0) {
                pickIds.add(rs.getObject("id", UUID.class));
                lastWave = rs.getObject("wave_id", UUID.class);
                lastQty = rs.getBigDecimal("qty");
                picked = picked.add(lastQty);
              }
            }
          }
          if (pickIds.isEmpty()) return BigDecimal.ZERO;
          BigDecimal covered = picked.min(qty);
          if (!markProcessedIfNewTx(c, ack, consumerName)) return covered;
          BigDecimal overhang = picked.subtract(qty);
          if (overhang.signum() > 0) {
            // The last pick covers more than this handover names: the part it does not is still a
            // credit for the next one, so it is split off, unacknowledged, rather than lost.
            UUID last = pickIds.get(pickIds.size() - 1);
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE wave_picked_lines SET qty = ? WHERE tenant_id = ? AND id = ?")) {
              ps.setBigDecimal(1, lastQty.subtract(overhang));
              ps.setObject(2, tenantId);
              ps.setObject(3, last);
              ps.executeUpdate();
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO wave_picked_lines (id, tenant_id, wave_id, order_id, variant_id,"
                        + " qty) VALUES (?,?,?,?,?,?)")) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, lastWave);
              ps.setObject(4, orderId);
              ps.setObject(5, variantId);
              ps.setBigDecimal(6, overhang);
              ps.executeUpdate();
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE wave_picked_lines SET acknowledged_by = ? WHERE tenant_id = ? AND id ="
                      + " ANY(?)")) {
            ps.setObject(1, ack);
            ps.setObject(2, tenantId);
            ps.setArray(3, c.createArrayOf("uuid", pickIds.toArray()));
            ps.executeUpdate();
          }
          if (netAmount != null) {
            InventoryRepository.insertSaleRevenueTx(
                c, tenantId, storeId, variantId, orderId, qty, netAmount, null, "SALE");
          }
          return covered;
        },
        "acknowledge wave picks on fulfilment");
  }

  /** Now, for the wave's timestamps. */
  static Instant now() {
    return Instant.now();
  }
}
