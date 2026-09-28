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
import java.util.UUID;

/**
 * JDBC access to reporting-svc's own projection tables.
 *
 * <p>Write methods are called from Kafka handlers and are idempotent — the {@code *Once} variants
 * fold the dedupe check into the same statement as the projection write, so a redelivered event
 * cannot double-count. Read methods back the report endpoints.
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
   * <p>{@code ON CONFLICT (id) DO NOTHING} makes a redelivered {@code TransferShipped} a no-op: the
   * caller derives each line's id from the event, so the retry reuses the same primary key.
   *
   * @param line the supply line to open, keyed by the shipping event's id
   */
  public void insertSupplyLine(OpenSupplyLine line) {
    exec(
        "INSERT INTO open_supply_lines"
            + " (id, tenant_id, from_store_id, to_store_id, variant_id, qty, event_id)"
            + " VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (id) DO NOTHING",
        ps -> {
          ps.setObject(1, line.id());
          ps.setObject(2, line.tenantId());
          ps.setObject(3, line.fromStoreId());
          ps.setObject(4, line.toStoreId());
          ps.setObject(5, line.variantId());
          ps.setBigDecimal(6, line.qty());
          ps.setObject(7, line.eventId());
        },
        "insert open supply line");
  }

  /**
   * Retires every in-transit line opened by one shipment.
   *
   * <p>Not tenant-scoped, unusually for this codebase: {@code event_id} is a globally unique UUIDv7
   * that already pins the rows to the tenant that emitted the shipment.
   *
   * @param eventId the {@code TransferShipped} event id the lines were opened under
   */
  public void deleteSupplyLinesByEvent(UUID eventId) {
    exec(
        "DELETE FROM open_supply_lines WHERE event_id = ?",
        ps -> ps.setObject(1, eventId),
        "delete supply lines by event");
  }

  // ── Queries ───────────────────────────────────────────────────────────────

  /**
   * DB-load safety valve for the tenant-wide aggregate reads below: none of them are
   * client-paginated (callers want the whole result to aggregate/display in one shot), but nothing
   * upstream caps how large a tenant's catalog or event history can grow. This bounds the worst
   * case instead of leaving the query truly unbounded.
   */
  private static final int REPORTING_SAFETY_CAP = 20_000;

  /** Gap #47: cross-store on-hand. Optionally filtered by storeId or variantId. */
  public List<InventoryProjection> queryOnHand(UUID tenantId, UUID storeId, UUID variantId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT tenant_id, store_id, variant_id, on_hand, updated_at"
                + " FROM inventory_projection WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    sb.append(" ORDER BY store_id, variant_id LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          ps.setInt(i, REPORTING_SAFETY_CAP);
        },
        ReportingRepository::mapProjection,
        "query on-hand");
  }

  /** Gap #48: open supply in transit, optionally filtered by toStoreId or variantId. */
  public List<OpenSupplyLine> querySupplyLines(UUID tenantId, UUID toStoreId, UUID variantId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, from_store_id, to_store_id, variant_id, qty, event_id"
                + " FROM open_supply_lines WHERE tenant_id = ?");
    if (toStoreId != null) sb.append(" AND to_store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (toStoreId != null) ps.setObject(i++, toStoreId);
          if (variantId != null) ps.setObject(i, variantId);
        },
        ReportingRepository::mapSupplyLine,
        "query supply lines");
  }

  /**
   * Gap #49: movement stats aggregated by (store, variant, date-bucket). bucketDays controls the
   * truncation unit: 1=day, 7=week, 30=month (approximate, uses date_trunc).
   */
  public List<MovementStat> queryMovementStats(
      UUID tenantId, UUID storeId, UUID variantId, int bucketDays) {
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
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    sb.append(
        " GROUP BY store_id, variant_id, bucket ORDER BY bucket DESC, store_id, variant_id"
            + " LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (storeId != null) ps.setObject(i++, storeId);
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
   */
  public List<SalesCategoryStat> salesByCategory(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel, boolean top) {
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
    if (storeId != null) {
      sb.append(" AND l.store_id = ?");
      params.add(storeId);
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
            ps.setObject(i + 1, params.get(i));
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

  /** Sales totals grouped by currency over the window/filters; a voided sale is left out. */
  public List<SalesSummary> salesSummary(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT currency, COUNT(*) AS orders,"
                + " COALESCE(SUM(gross_amount),0) AS gross,"
                + " COALESCE(SUM(refunded_amount),0) AS refunded"
                + " FROM sales_facts WHERE tenant_id = ? AND voided_at IS NULL");
    appendSalesFilters(sb, from, to, storeId, channel);
    sb.append(" GROUP BY currency ORDER BY currency");
    return query(
        sb.toString(),
        ps -> bindSalesFilters(ps, tenantId, from, to, storeId, channel),
        ReportingRepository::mapSalesSummary,
        "sales summary");
  }

  /**
   * Sales totals bucketed by day (and currency) over the window/filters, newest first; a voided
   * sale is left out.
   */
  public List<SalesDayStat> salesByDay(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT date_trunc('day', confirmed_at) AS day, currency, COUNT(*) AS orders,"
                + " COALESCE(SUM(gross_amount),0) AS gross,"
                + " COALESCE(SUM(refunded_amount),0) AS refunded"
                + " FROM sales_facts WHERE tenant_id = ? AND voided_at IS NULL");
    appendSalesFilters(sb, from, to, storeId, channel);
    sb.append(" GROUP BY day, currency ORDER BY day DESC, currency LIMIT ?");
    return query(
        sb.toString(),
        ps ->
            ps.setInt(
                bindSalesFilters(ps, tenantId, from, to, storeId, channel), REPORTING_SAFETY_CAP),
        ReportingRepository::mapSalesDay,
        "sales by day");
  }

  private static void appendSalesFilters(
      StringBuilder sb, Instant from, Instant to, UUID storeId, String channel) {
    if (from != null) sb.append(" AND confirmed_at >= ?");
    if (to != null) sb.append(" AND confirmed_at < ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (channel != null) sb.append(" AND channel = ?");
  }

  /** Binds the shared filters and returns the next free parameter index for the caller to use. */
  private static int bindSalesFilters(
      PreparedStatement ps, UUID tenantId, Instant from, Instant to, UUID storeId, String channel)
      throws SQLException {
    ps.setObject(1, tenantId);
    int i = 2;
    if (from != null) ps.setObject(i++, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
    if (to != null) ps.setObject(i++, OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    if (storeId != null) ps.setObject(i++, storeId);
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
   */
  public List<LabourDayStat> labourByDay(UUID tenantId, Instant from, Instant to, UUID storeId) {
    String sql =
        """
        WITH sales AS (
            SELECT date_trunc('day', confirmed_at)::date AS day, currency,
                   COALESCE(SUM(gross_amount),0) AS gross,
                   COALESCE(SUM(refunded_amount),0) AS refunded
            FROM sales_facts
            WHERE tenant_id = ? AND confirmed_at >= ? AND confirmed_at < ?
              AND voided_at IS NULL
              AND (?::uuid IS NULL OR store_id = ?)
            GROUP BY 1, 2
        ),
        labour AS (
            SELECT day, SUM(minutes)::bigint AS minutes,
                   SUM(CASE WHEN cost IS NULL THEN minutes ELSE 0 END)::bigint AS uncosted,
                   SUM(cost) AS cost,
                   MAX(currency) AS currency
            FROM labour_facts
            WHERE tenant_id = ? AND day >= ?::date AND day < ?::date
              AND (?::uuid IS NULL OR store_id = ?)
            GROUP BY 1
        )
        SELECT COALESCE(s.day, l.day) AS day,
               COALESCE(s.currency, l.currency) AS currency,
               -- Scaled, not bare: a day with hours and no sales would otherwise answer 0 where a
               -- trading day answers 0.00, and a column of mixed scales reads as broken.
               COALESCE(s.gross, 0)::numeric(18,2) AS gross,
               COALESCE(s.refunded, 0)::numeric(18,2) AS refunded,
               COALESCE(l.minutes, 0) AS minutes,
               COALESCE(l.uncosted, 0) AS uncosted,
               l.cost AS cost
        FROM sales s FULL OUTER JOIN labour l ON l.day = s.day
        ORDER BY 1 DESC LIMIT ?""";
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, from.atOffset(ZoneOffset.UTC));
          ps.setObject(i++, to.atOffset(ZoneOffset.UTC));
          ps.setObject(i++, storeId);
          ps.setObject(i++, storeId);
          ps.setObject(i++, tenantId);
          ps.setObject(i++, from.atOffset(ZoneOffset.UTC).toLocalDate());
          ps.setObject(i++, to.atOffset(ZoneOffset.UTC).toLocalDate());
          ps.setObject(i++, storeId);
          ps.setObject(i++, storeId);
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
