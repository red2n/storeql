package com.storeql.reporting.repo;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.Domain.InventoryProjection;
import com.storeql.reporting.domain.Domain.LabourDayStat;
import com.storeql.reporting.domain.Domain.MovementStat;
import com.storeql.reporting.domain.Domain.OpenSupplyLine;
import com.storeql.reporting.domain.Domain.SaleLine;
import com.storeql.reporting.domain.Domain.SalesCategoryStat;
import com.storeql.reporting.domain.Domain.SalesDayStat;
import com.storeql.reporting.domain.Domain.SalesSummary;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
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
import java.util.Set;
import java.util.UUID;

/**
 * JDBC access to reporting-svc's own projection tables.
 *
 * <p>Write methods are called from Kafka handlers and are idempotent. The {@code *Once} variants
 * take a processed_events mark in the same transaction as the projection write, so a redelivered
 * event cannot double-count. The exception is {@code recordSaleOnce}, whose primary key is the
 * dedupe, checked in the same statement. Read methods back the report endpoints.
 */
@ApplicationScoped
public class ReportingRepository extends BaseJdbcRepository {

  // ── Inventory projection upserts ─────────────────────────────────────────

  /**
   * Apply one signed stock delta to the projection + movement stats, deduped on eventId. The
   * processed_events mark and both (non-idempotent, additive) writes commit in ONE transaction so a
   * redelivered event is skipped and a crashed write is retried — never applied twice and never
   * lost. Returns false if the event was already processed.
   */
  public boolean applyStockDeltaOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String eventType) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO inventory_projection"
                      + " (tenant_id, store_id, variant_id, on_hand, updated_at)"
                      + " VALUES (?,?,?,?,now())"
                      + " ON CONFLICT (tenant_id, store_id, variant_id)"
                      + " DO UPDATE SET on_hand = inventory_projection.on_hand + EXCLUDED.on_hand,"
                      + "               updated_at = now()")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, variantId);
            ps.setBigDecimal(4, delta);
            ps.executeUpdate();
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO movement_events"
                      + " (id, tenant_id, store_id, variant_id, event_type, qty_change)"
                      + " VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, variantId);
            ps.setString(5, eventType);
            ps.setBigDecimal(6, delta);
            ps.executeUpdate();
          }
          return true;
        },
        "apply stock delta");
  }

  // ── Open supply lines (intransit transfers) ───────────────────────────────

  /**
   * Records one in-transit transfer line.
   *
   * <p>{@code ON CONFLICT (id) DO NOTHING} is only a guard: each line's id is minted fresh per
   * call, so it never matches a row an earlier delivery wrote. The processed_events mark, taken in
   * the same transaction, refuses a redelivered event (see {@link #applyTransferShippedOnce}).
   *
   * @param line the supply line to open, tagged with the shipping event's id
   */
  private static void insertSupplyLineTx(Connection c, OpenSupplyLine line) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO open_supply_lines"
                + " (id, tenant_id, transfer_order_id, from_store_id, to_store_id, variant_id,"
                + "  qty, event_id)"
                + " VALUES (?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (id) DO NOTHING")) {
      ps.setObject(1, line.id());
      ps.setObject(2, line.tenantId());
      ps.setObject(3, line.transferOrderId());
      ps.setObject(4, line.fromStoreId());
      ps.setObject(5, line.toStoreId());
      ps.setObject(6, line.variantId());
      ps.setBigDecimal(7, line.qty());
      ps.setObject(8, line.eventId());
      ps.executeUpdate();
    }
  }

  /**
   * Opens the in-transit supply lines of one shipment, deduped on the shipping event for this
   * consumer. The mark and every line commit in ONE transaction: a line that cannot be written
   * takes the mark with it, so the redelivered event is applied rather than swallowed. Returns
   * false if this consumer already processed the event.
   *
   * <p>A transfer that has already landed opens nothing: the receipt and the shipment travel on two
   * topics, so the receipt can be read first, and lines opened after it would never be retired. The
   * receipt leaves a mark for exactly that (see {@link #retireSupplyLines}); both sides take the
   * transfer's lock before they read or write its lines, so a shipment and its receipt handled at
   * the same moment by two consumers cannot each miss the other.
   *
   * @param tenantId owning tenant
   * @param transferOrderId the transfer order, which the receipt names too and retires its lines by
   * @param eventId the {@code TransferOrderShipped} event id, the dedupe key
   * @param consumerName this consumer's dedupe name
   * @param fromStoreId the shipping store
   * @param toStoreId the receiving store
   * @param variantIds one entry per supply line
   * @param qtys the quantity of each line, same order as {@code variantIds}
   * @return {@code true} when applied; {@code false} when the event was already processed
   */
  public boolean applyTransferShippedOnce(
      UUID tenantId,
      UUID transferOrderId,
      UUID eventId,
      String consumerName,
      UUID fromStoreId,
      UUID toStoreId,
      List<UUID> variantIds,
      List<BigDecimal> qtys) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          lockTransfer(c, tenantId, transferOrderId);
          if (hasLanded(c, tenantId, transferOrderId)) {
            return true; // received before it was read as shipped: nothing is in transit
          }
          for (int i = 0; i < variantIds.size(); i++) {
            insertSupplyLineTx(
                c,
                new OpenSupplyLine(
                    Ids.newId(),
                    tenantId,
                    transferOrderId,
                    fromStoreId,
                    toStoreId,
                    variantIds.get(i),
                    qtys.get(i),
                    eventId));
          }
          return true;
        },
        "apply transfer shipped");
  }

  /**
   * Retires every in-transit line of one transfer, and notes that it has landed.
   *
   * <p>Found by the business and the transfer order: a {@code TransferOrderReceived} has an event
   * id of its own, so it cannot name the shipment's. Another business's receipt naming the same
   * transfer order id retires nothing, and its note never stops this business's shipment opening.
   *
   * <p>The note is a {@code processed_events} mark under a name of its own, keyed by an id derived
   * from the business and the transfer, so the scheduled purge of old marks removes it with the
   * others. Retiring twice finds nothing the second time.
   *
   * @param tenantId owning tenant, the first condition of the delete
   * @param transferOrderId the transfer order that landed
   */
  public void retireSupplyLines(UUID tenantId, UUID transferOrderId) {
    inTx(
        c -> {
          lockTransfer(c, tenantId, transferOrderId);
          markProcessedIfNewTx(c, landedKey(tenantId, transferOrderId), TRANSFER_LANDED);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM open_supply_lines WHERE tenant_id = ? AND transfer_order_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, transferOrderId);
            ps.executeUpdate();
          }
          return null;
        },
        "retire supply lines");
  }

  /** The consumer name a landed transfer's note is kept under, apart from every event consumer. */
  private static final String TRANSFER_LANDED = "reporting-svc/transfer-landed";

  /** The note's key: the same for the same business and transfer, and for no other. */
  private static UUID landedKey(UUID tenantId, UUID transferOrderId) {
    return Ids.derived(tenantId, "transfer-landed:" + transferOrderId);
  }

  private static boolean hasLanded(Connection c, UUID tenantId, UUID transferOrderId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT 1 FROM processed_events WHERE event_id = ? AND consumer = ?")) {
      ps.setObject(1, landedKey(tenantId, transferOrderId));
      ps.setString(2, TRANSFER_LANDED);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Orders a transfer's shipment and its receipt. Without it, the two handled at the same moment by
   * two consumers could each miss the other's uncommitted row, and the lines would stay open for a
   * transfer that had landed.
   */
  private static void lockTransfer(Connection c, UUID tenantId, UUID transferOrderId)
      throws SQLException {
    try (var ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      ps.setString(1, "transfer|" + tenantId + "|" + transferOrderId);
      ps.execute();
    }
  }

  // ── Queries ───────────────────────────────────────────────────────────────

  /**
   * DB-load safety valve for the tenant-wide aggregate reads below: none of them are
   * client-paginated (callers want the whole result to aggregate/display in one shot), but nothing
   * upstream caps how large a tenant's catalog or event history can grow. This bounds the worst
   * case instead of leaving the query truly unbounded.
   */
  private static final int REPORTING_SAFETY_CAP = 20_000;

  /**
   * Binds a non-null store scope as a {@code uuid[]} array parameter, paired in the SQL with {@code
   * store_id = ANY(?)}. Callers append that clause — and call this — only when {@code stores} is
   * non-null; an unrestricted (whole-tenant) read must build a query with no store clause at all,
   * never one bound with a null array (the Postgres driver cannot determine the parameter's type in
   * that shape and errors before it ever finds no rows).
   */
  private static void bindStores(PreparedStatement ps, int index, Set<UUID> stores)
      throws SQLException {
    ps.setArray(index, ps.getConnection().createArrayOf("uuid", stores.toArray(new UUID[0])));
  }

  /**
   * Gap #47: cross-store on-hand. Optionally filtered to a store scope (SJ-D74: {@code null} for
   * every store in the tenant, else the caller's own stores added together) and/or variantId.
   */
  public List<InventoryProjection> queryOnHand(UUID tenantId, Set<UUID> stores, UUID variantId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT tenant_id, store_id, variant_id, on_hand, updated_at"
                + " FROM inventory_projection WHERE tenant_id = ?");
    if (stores != null) sb.append(" AND store_id = ANY(?)");
    if (variantId != null) sb.append(" AND variant_id = ?");
    sb.append(" ORDER BY store_id, variant_id LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (stores != null) {
            bindStores(ps, i++, stores);
          }
          if (variantId != null) ps.setObject(i++, variantId);
          ps.setInt(i, REPORTING_SAFETY_CAP);
        },
        ReportingRepository::mapProjection,
        "query on-hand");
  }

  /**
   * Gap #48: open supply in transit, optionally filtered to a store scope (destination store; see
   * {@link #queryOnHand}) and/or variantId.
   */
  public List<OpenSupplyLine> querySupplyLines(UUID tenantId, Set<UUID> stores, UUID variantId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, transfer_order_id, from_store_id, to_store_id, variant_id, qty,"
                + " event_id FROM open_supply_lines WHERE tenant_id = ?");
    if (stores != null) sb.append(" AND to_store_id = ANY(?)");
    if (variantId != null) sb.append(" AND variant_id = ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (stores != null) {
            bindStores(ps, i++, stores);
          }
          if (variantId != null) ps.setObject(i, variantId);
        },
        ReportingRepository::mapSupplyLine,
        "query supply lines");
  }

  /**
   * Gap #49: movement stats aggregated by (store, variant, date-bucket). bucketDays controls the
   * truncation unit: 1=day, 7=week, 30=month (approximate, uses date_trunc). Store scope as in
   * {@link #queryOnHand}. Only movements from {@code from} (inclusive) and before {@code to}
   * (exclusive, {@code null} for no upper bound) are read.
   */
  public List<MovementStat> queryMovementStats(
      UUID tenantId,
      Set<UUID> stores,
      UUID variantId,
      int bucketDays,
      java.time.Instant from,
      java.time.Instant to) {
    String trunc = bucketDays <= 1 ? "day" : bucketDays <= 7 ? "week" : "month";
    StringBuilder sb =
        new StringBuilder(
            "SELECT store_id, variant_id,"
                + "       date_trunc('"
                + trunc
                + "', occurred_at) AS bucket,"
                + "       COALESCE(SUM(CASE WHEN qty_change > 0 THEN qty_change ELSE 0 END),0) AS total_in,"
                + "       COALESCE(SUM(CASE WHEN qty_change < 0 THEN ABS(qty_change) ELSE 0 END),0) AS total_out"
                + " FROM movement_events WHERE tenant_id = ?");
    // The window is always bounded below, so idx_mvt_tenant_store can range-scan.
    sb.append(" AND occurred_at >= ?");
    if (to != null) sb.append(" AND occurred_at < ?");
    if (stores != null) sb.append(" AND store_id = ANY(?)");
    if (variantId != null) sb.append(" AND variant_id = ?");
    sb.append(
        " GROUP BY store_id, variant_id, bucket ORDER BY bucket DESC, store_id, variant_id"
            + " LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
          if (to != null) ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
          if (stores != null) {
            bindStores(ps, i++, stores);
          }
          if (variantId != null) ps.setObject(i++, variantId);
          ps.setInt(i, REPORTING_SAFETY_CAP);
        },
        ReportingRepository::mapMovementStat,
        "query movement stats");
  }

  // ── Sales projection (from order/payment events) ──────────────────────────

  /**
   * Record a confirmed order as a sales fact. Naturally idempotent: {@code (tenant_id, order_id)}
   * is the primary key and OrderConfirmed is emitted once per order, so a redelivered event is a
   * no-op via {@code ON CONFLICT DO NOTHING}. Returns true if a row was inserted.
   *
   * <p>A void already heard for the order (it can arrive first: the consumer reads its topics in no
   * particular order) marks the fact as it lands, so the sale is never counted.
   */
  public boolean recordSaleOnce(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal gross,
      String currency,
      List<SaleLine> lines) {
    return inTx(
        c -> {
          lockSale(c, tenantId, orderId);
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO sales_facts"
                      + " (tenant_id, order_id, store_id, channel, customer_id, gross_amount,"
                      + "  currency, voided_at)"
                      + " VALUES (?,?,?,?,?,?,?,"
                      + "  (SELECT v.voided_at FROM sales_voids v"
                      + "   WHERE v.tenant_id = ? AND v.order_id = ?))"
                      + " ON CONFLICT (tenant_id, order_id) DO NOTHING")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            ps.setObject(3, storeId);
            ps.setString(4, channel);
            ps.setObject(5, customerId);
            ps.setBigDecimal(6, gross);
            ps.setString(7, currency);
            ps.setObject(8, tenantId);
            ps.setObject(9, orderId);
            if (ps.executeUpdate() == 0) {
              return false; // seen before: its lines are already here
            }
          }
          // The lines land with the sale, in the same transaction and at the same instant (now()
          // is the transaction's), so a report by day and a report by category agree.
          if (!lines.isEmpty()) {
            try (var ps =
                c.prepareStatement(
                    "INSERT INTO sales_line_facts"
                        + " (tenant_id, order_id, line_no, variant_id, store_id, channel, qty,"
                        + "  unit_price, line_total, currency, confirmed_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,now())")) {
              int lineNo = 0;
              for (SaleLine line : lines) {
                ps.setObject(1, tenantId);
                ps.setObject(2, orderId);
                ps.setInt(3, ++lineNo);
                ps.setObject(4, line.variantId());
                ps.setObject(5, storeId);
                ps.setString(6, channel);
                ps.setBigDecimal(7, line.qty());
                ps.setBigDecimal(8, line.unitPrice());
                ps.setBigDecimal(9, line.lineTotal());
                ps.setString(10, currency);
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return true;
        },
        "record sale");
  }

  /**
   * Void a sale, deduped on the {@code OrderVoided} event's id: the fact is marked with the moment
   * the void was heard and left out of every sales report from then on — never deleted, so the row
   * and its lines stay. The first void heard for an order stands; a later one changes nothing. A
   * void for an order not yet projected is kept, and marks the sale when its {@code OrderConfirmed}
   * lands. The mark and both writes commit in one transaction.
   *
   * <p>Scoped by the tenant first, so another business's void naming the same order id touches only
   * its own rows.
   *
   * @return false when the event was already processed
   */
  public boolean voidSaleOnce(UUID eventId, String consumer, UUID tenantId, UUID orderId) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return false;
          }
          lockSale(c, tenantId, orderId);
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO sales_voids (tenant_id, order_id, event_id, voided_at)"
                      + " VALUES (?,?,?,now())"
                      + " ON CONFLICT (tenant_id, order_id) DO NOTHING")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            ps.setObject(3, eventId);
            ps.executeUpdate();
          }
          try (var ps =
              c.prepareStatement(
                  "UPDATE sales_facts SET voided_at ="
                      + " (SELECT v.voided_at FROM sales_voids v"
                      + "  WHERE v.tenant_id = ? AND v.order_id = ?)"
                      + " WHERE tenant_id = ? AND order_id = ? AND voided_at IS NULL")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            ps.setObject(3, tenantId);
            ps.setObject(4, orderId);
            ps.executeUpdate();
          }
          return true;
        },
        "void sale");
  }

  /**
   * Orders a sale's projection and its void. Without it, a void and its sale handled at the same
   * moment by two consumers could each miss the other's uncommitted row, and the sale would land
   * unmarked; with it, whichever commits second sees the first.
   */
  private static void lockSale(Connection c, UUID tenantId, UUID orderId) throws SQLException {
    try (var ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      ps.setString(1, "sale|" + tenantId + "|" + orderId);
      ps.execute();
    }
  }

  // ── The catalogue projection: where each variant sits ─────────────────────

  /**
   * The catalogue's word on a product: its category path (leaf first, root last) and the variants
   * it named. A later word replaces an earlier one; an earlier word redelivered late changes
   * nothing, so the order events arrive in cannot move a product back.
   */
  public void upsertProductCategory(
      UUID tenantId,
      UUID productId,
      List<UUID> categoryPath,
      List<UUID> variantIds,
      Instant announcedAt) {
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO catalogue_products"
                      + " (tenant_id, product_id, category_path, announced_at)"
                      + " VALUES (?,?,?,?)"
                      + " ON CONFLICT (tenant_id, product_id) DO UPDATE SET"
                      + " category_path = EXCLUDED.category_path,"
                      + " announced_at = EXCLUDED.announced_at"
                      + " WHERE catalogue_products.announced_at <= EXCLUDED.announced_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, productId);
            ps.setArray(3, c.createArrayOf("uuid", categoryPath.toArray(new UUID[0])));
            ps.setObject(4, OffsetDateTime.ofInstant(announcedAt, ZoneOffset.UTC));
            ps.executeUpdate();
          }
          upsertVariants(c, tenantId, productId, variantIds);
          return null;
        },
        "project product category");
  }

  /** A variant created after its product was announced: tied to the product it belongs to. */
  public void upsertVariantProduct(UUID tenantId, UUID variantId, UUID productId) {
    inTx(
        c -> {
          upsertVariants(c, tenantId, productId, List.of(variantId));
          return null;
        },
        "project variant");
  }

  private static void upsertVariants(
      Connection c, UUID tenantId, UUID productId, List<UUID> variantIds) throws SQLException {
    if (variantIds.isEmpty()) {
      return;
    }
    try (var ps =
        c.prepareStatement(
            "INSERT INTO catalogue_variants (tenant_id, variant_id, product_id) VALUES (?,?,?)"
                + " ON CONFLICT (tenant_id, variant_id) DO UPDATE SET product_id = EXCLUDED.product_id")) {
      for (UUID variantId : variantIds) {
        ps.setObject(1, tenantId);
        ps.setObject(2, variantId);
        ps.setObject(3, productId);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /**
   * What each category took, from the sale lines and the catalogue projection: the leaf category,
   * or its top-level ancestor when {@code top}. Lines whose variant is unknown to the projection,
   * or whose product has no category, group under a null category rather than vanish — takings the
   * report cannot place are still takings. The lines of a voided sale are left out.
   *
   * @param stores the store scope (SJ-D74): {@code null} for every store in the tenant, else the
   *     caller's own stores added together
   */
  public List<SalesCategoryStat> salesByCategory(
      UUID tenantId, Instant from, Instant to, Set<UUID> stores, String channel, boolean top) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT CASE WHEN ? THEN cp.category_path[array_length(cp.category_path, 1)]"
                + " ELSE cp.category_path[1] END AS category_id,"
                + " l.currency, COUNT(DISTINCT l.order_id) AS orders,"
                + " COALESCE(SUM(l.qty), 0) AS units, COALESCE(SUM(l.line_total), 0) AS gross"
                + " FROM sales_line_facts l"
                + " LEFT JOIN catalogue_variants cv"
                + " ON cv.tenant_id = l.tenant_id AND cv.variant_id = l.variant_id"
                + " LEFT JOIN catalogue_products cp"
                + " ON cp.tenant_id = cv.tenant_id AND cp.product_id = cv.product_id"
                + " WHERE l.tenant_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM sales_facts f"
                + "  WHERE f.tenant_id = l.tenant_id AND f.order_id = l.order_id"
                + "  AND f.voided_at IS NOT NULL)");
    List<Object> params = new ArrayList<>();
    params.add(top);
    params.add(tenantId);
    if (from != null) {
      sb.append(" AND l.confirmed_at >= ?");
      params.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
    }
    if (to != null) {
      sb.append(" AND l.confirmed_at < ?");
      params.add(OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    }
    if (stores != null) {
      sb.append(" AND l.store_id = ANY(?)");
      params.add(stores.toArray(new UUID[0]));
    }
    if (channel != null) {
      sb.append(" AND l.channel = ?");
      params.add(channel);
    }
    sb.append(" GROUP BY 1, l.currency ORDER BY gross DESC, l.currency, category_id LIMIT ?");
    params.add(REPORTING_SAFETY_CAP);
    return query(
        sb.toString(),
        ps -> {
          for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof UUID[] ids) {
              ps.setArray(i + 1, ps.getConnection().createArrayOf("uuid", ids));
            } else {
              ps.setObject(i + 1, p);
            }
          }
        },
        ReportingRepository::mapSalesCategory,
        "sales by category");
  }

  private static SalesCategoryStat mapSalesCategory(ResultSet rs) throws SQLException {
    return new SalesCategoryStat(
        rs.getObject("category_id", UUID.class),
        rs.getString("currency"),
        rs.getLong("orders"),
        rs.getBigDecimal("units"),
        rs.getBigDecimal("gross"));
  }

  /**
   * Add a refund to a sale, deduped on the payment event's {@code eventId} (refunds accumulate, so
   * a redelivered event must not double-count). The mark and the update commit in one transaction.
   * A refund for an order not yet projected updates nothing (the mark still stands).
   */
  public void applySalesRefundOnce(
      UUID eventId, String consumer, UUID tenantId, UUID orderId, BigDecimal amount) {
    inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return null;
          }
          try (var ps =
              c.prepareStatement(
                  "UPDATE sales_facts SET refunded_amount = refunded_amount + ?"
                      + " WHERE tenant_id = ? AND order_id = ?")) {
            ps.setBigDecimal(1, amount);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.executeUpdate();
          }
          return null;
        },
        "apply sales refund");
  }

  /**
   * Record a return made without a receipt as a refund on its day at its store, once per event
   * ({@code NoReceiptReturnRecorded}). It is a {@code sales_facts} row keyed by the return, gross
   * zero and the refund in {@code refunded_amount}, flagged so it is never counted as an order. The
   * mark and the row commit in one transaction; the key also makes a second event for the same
   * return a no-op.
   *
   * @return false when the event (or the return) was already recorded
   */
  public boolean recordNoReceiptRefundOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID returnId,
      UUID storeId,
      BigDecimal amount,
      String currency) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return false;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO sales_facts"
                      + " (tenant_id, order_id, store_id, gross_amount, refunded_amount,"
                      + "  currency, no_receipt)"
                      + " VALUES (?,?,?,0,?,?,true)"
                      + " ON CONFLICT (tenant_id, order_id) DO NOTHING")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, returnId);
            ps.setObject(3, storeId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, currency);
            return ps.executeUpdate() > 0;
          }
        },
        "record no-receipt refund");
  }

  /** Sales totals grouped by currency over the window/filters; a voided sale is left out. */
  public List<SalesSummary> salesSummary(
      UUID tenantId, Instant from, Instant to, Set<UUID> stores, String channel) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT currency, COUNT(*) FILTER (WHERE NOT no_receipt) AS orders,"
                + " COALESCE(SUM(gross_amount),0) AS gross,"
                + " COALESCE(SUM(refunded_amount),0) AS refunded"
                + " FROM sales_facts WHERE tenant_id = ? AND voided_at IS NULL");
    appendSalesFilters(sb, from, to, stores, channel);
    sb.append(" GROUP BY currency ORDER BY currency");
    return query(
        sb.toString(),
        ps -> bindSalesFilters(ps, tenantId, from, to, stores, channel),
        ReportingRepository::mapSalesSummary,
        "sales summary");
  }

  /**
   * Sales totals bucketed by day (and currency) over the window/filters, newest first; a voided
   * sale is left out.
   *
   * @param stores the store scope (SJ-D74): {@code null} for every store in the tenant, else the
   *     caller's own stores added together
   */
  public List<SalesDayStat> salesByDay(
      UUID tenantId, Instant from, Instant to, Set<UUID> stores, String channel) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT date_trunc('day', confirmed_at) AS day, currency,"
                + " COUNT(*) FILTER (WHERE NOT no_receipt) AS orders,"
                + " COALESCE(SUM(gross_amount),0) AS gross,"
                + " COALESCE(SUM(refunded_amount),0) AS refunded"
                + " FROM sales_facts WHERE tenant_id = ? AND voided_at IS NULL");
    appendSalesFilters(sb, from, to, stores, channel);
    sb.append(" GROUP BY day, currency ORDER BY day DESC, currency LIMIT ?");
    return query(
        sb.toString(),
        ps ->
            ps.setInt(
                bindSalesFilters(ps, tenantId, from, to, stores, channel), REPORTING_SAFETY_CAP),
        ReportingRepository::mapSalesDay,
        "sales by day");
  }

  private static void appendSalesFilters(
      StringBuilder sb, Instant from, Instant to, Set<UUID> stores, String channel) {
    if (from != null) sb.append(" AND confirmed_at >= ?");
    if (to != null) sb.append(" AND confirmed_at < ?");
    if (stores != null) sb.append(" AND store_id = ANY(?)");
    if (channel != null) sb.append(" AND channel = ?");
  }

  /** Binds the shared filters and returns the next free parameter index for the caller to use. */
  private static int bindSalesFilters(
      PreparedStatement ps,
      UUID tenantId,
      Instant from,
      Instant to,
      Set<UUID> stores,
      String channel)
      throws SQLException {
    ps.setObject(1, tenantId);
    int i = 2;
    if (from != null) ps.setObject(i++, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
    if (to != null) ps.setObject(i++, OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    if (stores != null) bindStores(ps, i++, stores);
    if (channel != null) ps.setObject(i++, channel);
    return i;
  }

  private static SalesSummary mapSalesSummary(ResultSet rs) throws SQLException {
    return new SalesSummary(
        rs.getString("currency"),
        rs.getLong("orders"),
        rs.getBigDecimal("gross"),
        rs.getBigDecimal("refunded"));
  }

  private static SalesDayStat mapSalesDay(ResultSet rs) throws SQLException {
    return new SalesDayStat(
        rs.getObject("day", OffsetDateTime.class).toLocalDate().toString(),
        rs.getString("currency"),
        rs.getLong("orders"),
        rs.getBigDecimal("gross"),
        rs.getBigDecimal("refunded"));
  }

  // ── Mappers ───────────────────────────────────────────────────────────────

  private static InventoryProjection mapProjection(ResultSet rs) throws SQLException {
    return new InventoryProjection(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("on_hand"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OpenSupplyLine mapSupplyLine(ResultSet rs) throws SQLException {
    return new OpenSupplyLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("transfer_order_id", UUID.class),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getObject("event_id", UUID.class));
  }

  private static MovementStat mapMovementStat(ResultSet rs) throws SQLException {
    return new MovementStat(
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("bucket", OffsetDateTime.class).toLocalDate().toString(),
        rs.getBigDecimal("total_in"),
        rs.getBigDecimal("total_out"));
  }

  // ── Labour projection and the report that reads it ───────────────────────

  /**
   * Records what one time entry cost, and takes back out the entry it corrects.
   *
   * <p>One transaction and one statement each way. Keyed on the entry, so the same event twice is
   * one row — at-least-once delivery is the rule, not the exception — and the correction's own row
   * replaces the figure rather than adding to it, which is what stops a corrected day being counted
   * twice.
   */
  public void recordLabour(
      UUID tenantId,
      UUID entryId,
      UUID supersedes,
      UUID storeId,
      LocalDate day,
      long minutes,
      BigDecimal cost,
      String currency) {
    inTx(
        c -> {
          if (supersedes != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "DELETE FROM labour_facts WHERE tenant_id = ? AND entry_id = ?")) {
              ps.setObject(1, tenantId);
              ps.setObject(2, supersedes);
              ps.executeUpdate();
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO labour_facts (tenant_id, entry_id, store_id, day, minutes, cost,"
                      + " currency, recorded_at) VALUES (?,?,?,?,?,?,?,now())"
                      + " ON CONFLICT (tenant_id, entry_id) DO UPDATE SET"
                      + " store_id = EXCLUDED.store_id, day = EXCLUDED.day,"
                      + " minutes = EXCLUDED.minutes, cost = EXCLUDED.cost,"
                      + " currency = EXCLUDED.currency, recorded_at = now()")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, entryId);
            ps.setObject(3, storeId);
            ps.setObject(4, day);
            ps.setLong(5, Math.max(0, minutes));
            ps.setBigDecimal(6, cost);
            ps.setString(7, cost == null ? null : currency);
            ps.executeUpdate();
          }
          return null;
        },
        "record labour");
  }

  /**
   * Takings and the cost of the hours that earned them, day by day.
   *
   * <p>A full outer join in spirit: a day with takings and no hours recorded is as real as a day
   * with hours and no sales, and both are worth seeing. The currency comes from the sales, because
   * that is what the shop took; labour in another currency is summed apart and its minutes still
   * counted, so the hours are never lost even where the money cannot be added up. A voided sale is
   * not takings.
   *
   * @param stores the store scope (SJ-D74): {@code null} for every store in the business, else the
   *     caller's own stores added together
   */
  public List<LabourDayStat> labourByDay(
      UUID tenantId, Instant from, Instant to, Set<UUID> stores) {
    // The store filter is appended only when there is one to apply — an unconditional
    // "store_id = ANY(?)" bound with a null array makes the Postgres driver fail to determine the
    // parameter's type (it never reaches the point of finding no rows), so an unrestricted caller
    // must get a query with no store clause at all, exactly as the single-store filters elsewhere
    // in this class do.
    String storeFilter = stores != null ? " AND store_id = ANY(?)" : "";
    String sql =
        "WITH sales AS ("
            + "    SELECT date_trunc('day', confirmed_at)::date AS day, currency,"
            + "           COALESCE(SUM(gross_amount),0) AS gross,"
            + "           COALESCE(SUM(refunded_amount),0) AS refunded"
            + "    FROM sales_facts"
            + "    WHERE tenant_id = ? AND confirmed_at >= ? AND confirmed_at < ?"
            + "      AND voided_at IS NULL"
            + storeFilter
            + "    GROUP BY 1, 2"
            + "),"
            + "labour AS ("
            + "    SELECT day, SUM(minutes)::bigint AS minutes,"
            + "           SUM(CASE WHEN cost IS NULL THEN minutes ELSE 0 END)::bigint AS uncosted,"
            + "           SUM(cost) AS cost,"
            + "           MAX(currency) AS currency"
            + "    FROM labour_facts"
            + "    WHERE tenant_id = ? AND day >= ?::date AND day < ?::date"
            + storeFilter
            + "    GROUP BY 1"
            + ")"
            + "SELECT COALESCE(s.day, l.day) AS day,"
            + "       COALESCE(s.currency, l.currency) AS currency,"
            // Not cast to a scale here: a cast to two places rounded a dinar's third. The report
            // writes every figure at its own currency's minor units (Mappers.money), so a day with
            // hours and no sales still answers 0.00 pounds, or 0 yen, beside a trading day.
            + "       COALESCE(s.gross, 0) AS gross,"
            + "       COALESCE(s.refunded, 0) AS refunded,"
            + "       COALESCE(l.minutes, 0) AS minutes,"
            + "       COALESCE(l.uncosted, 0) AS uncosted,"
            + "       l.cost AS cost"
            + " FROM sales s FULL OUTER JOIN labour l ON l.day = s.day"
            + " ORDER BY 1 DESC LIMIT ?";
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
          ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
          if (stores != null) bindStores(ps, i++, stores);
          ps.setObject(i++, tenantId);
          ps.setObject(i++, from.atOffset(ZoneOffset.UTC).toLocalDate());
          ps.setObject(i++, to.atOffset(ZoneOffset.UTC).toLocalDate());
          if (stores != null) bindStores(ps, i++, stores);
          ps.setInt(i, REPORTING_SAFETY_CAP);
        },
        ReportingRepository::mapLabourDay,
        "labour by day");
  }

  private static LabourDayStat mapLabourDay(ResultSet rs) throws SQLException {
    return new LabourDayStat(
        rs.getObject("day", LocalDate.class).toString(),
        rs.getString("currency"),
        rs.getBigDecimal("gross"),
        rs.getBigDecimal("refunded"),
        rs.getLong("minutes"),
        rs.getLong("uncosted"),
        rs.getBigDecimal("cost"));
  }
}
