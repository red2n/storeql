package com.storeql.order.repo;

import com.storeql.order.domain.SalesAttribution;
import com.storeql.order.domain.SalesAttribution.SellerChange;
import com.storeql.order.domain.SalesAttribution.SellerDay;
import com.storeql.order.domain.SalesAttribution.Statement;
import com.storeql.order.domain.SalesAttribution.StatementLine;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Attributed sales, and the statements made of them (store operations & workforce).
 *
 * <p>A sale counts on the day it was <b>supplied</b> — its first move to FULFILLED in the
 * append-only status history — and a refund subtracts on the day it was refunded, which are the
 * same definitions the tax reporting uses. Anything else would have two reports over the same trade
 * disagreeing about which month it happened in.
 *
 * <p>A refund reduces the period it was <em>refunded</em> in, not the one the sale was made in: a
 * month already stated and paid is never re-rated, which is the whole reason a statement is frozen.
 */
@ApplicationScoped
public class CommissionRepository extends BaseJdbcRepository {

  /**
   * Net sales and units per seller per day, sales and refunds together.
   *
   * <p>Both halves use the whole-order net from {@code order_items}, because that is what a
   * percentage of "what the shop took" means: VAT out, line discounts already in the line totals.
   */
  private static final String SELLER_DAYS =
      """
      WITH supplied AS (
          SELECT o.id, o.seller_user_id, MIN(h.changed_at) AS at
          FROM orders o
          JOIN order_status_history h
            ON h.tenant_id = o.tenant_id AND h.order_id = o.id AND h.to_status = 'FULFILLED'
          WHERE o.tenant_id = ? AND o.currency = ? AND o.seller_user_id IS NOT NULL
            AND (CAST(? AS uuid) IS NULL OR o.store_id = CAST(? AS uuid))
          GROUP BY o.id, o.seller_user_id
      ),
      sold AS (
          SELECT s.seller_user_id,
                 (s.at AT TIME ZONE 'UTC')::date AS day,
                 SUM(oi.line_total) AS net,
                 SUM(oi.qty) AS units
          FROM supplied s
          JOIN order_items oi ON oi.tenant_id = ? AND oi.order_id = s.id
          WHERE s.at >= ? AND s.at < ?
          GROUP BY 1, 2
      ),
      refunded AS (
          SELECT o.seller_user_id,
                 -- A till refund is completed as it is recorded and leaves completed_at null, so the
                 -- column alone would drop every counter refund and pay commission on goods that
                 -- came back.
                 (COALESCE(r.completed_at, r.created_at) AT TIME ZONE 'UTC')::date AS day,
                 -SUM(ri.refund_amount) AS net,
                 -SUM(ri.qty) AS units
          FROM returns r
          JOIN orders o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
          JOIN return_items ri ON ri.tenant_id = r.tenant_id AND ri.return_id = r.id
          WHERE r.tenant_id = ? AND o.currency = ? AND o.seller_user_id IS NOT NULL
            AND (CAST(? AS uuid) IS NULL OR o.store_id = CAST(? AS uuid))
            AND r.status = 'COMPLETED'
            AND COALESCE(r.completed_at, r.created_at) >= ?
            AND COALESCE(r.completed_at, r.created_at) < ?
          GROUP BY 1, 2
      )
      SELECT seller_user_id, day,
             SUM(net) AS net,
             SUM(units)::numeric(18,3) AS units
      FROM (SELECT * FROM sold UNION ALL SELECT * FROM refunded) both_ways
      GROUP BY 1, 2
      ORDER BY 1, 2
      """;

  private static final String STATEMENT_COLUMNS =
      "id, tenant_id, store_id, period_start, period_end, currency, status, net_sales, commission,"
          + " note, supersedes, superseded_by, created_at, created_by, approved_at, approved_by";

  private static final String LINE_COLUMNS =
      "id, tenant_id, statement_id, seller_user_id, scheme_id, scheme_name, segment_from,"
          + " segment_to, threshold_from, rate, amount, commission, rate_currency, rated_commission";

  /**
   * What each seller sold, day by day, over a period.
   *
   * @param storeId one store, or null for every store the business has
   * @param from the first day counted
   * @param to the last day counted, inclusive
   * @param currency the statement's currency; a day's net is kept to its own minor units
   */
  public List<SellerDay> sellerDays(
      UUID tenantId, UUID storeId, String currency, LocalDate from, LocalDate to) {
    int scale = com.storeql.service.Fx.minorUnits(currency);
    OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    return query(
        SELLER_DAYS,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setString(i++, currency);
          ps.setObject(i++, storeId);
          ps.setObject(i++, storeId);
          ps.setObject(i++, tenantId);
          ps.setObject(i++, start);
          ps.setObject(i++, end);
          ps.setObject(i++, tenantId);
          ps.setString(i++, currency);
          ps.setObject(i++, storeId);
          ps.setObject(i++, storeId);
          ps.setObject(i++, start);
          ps.setObject(i, end);
        },
        rs ->
            new SellerDay(
                rs.getObject("seller_user_id", UUID.class),
                rs.getObject("day", LocalDate.class),
                rs.getBigDecimal("net").setScale(scale, java.math.RoundingMode.HALF_UP),
                rs.getBigDecimal("units")),
        "read attributed sales by seller and day");
  }

  /**
   * Writes a statement with its lines.
   *
   * <p>A statement it replaces is <b>not</b> closed here: a draft must not knock out the statement
   * that stands, or a month would have nothing standing for it while somebody worked on a
   * correction. The old one closes when the new one is approved.
   */
  public Statement record(Statement s) {
    // Money at the statement currency's own minor units, half up, as NUMERIC(18,2) kept it for the
    // pound: whole yen, three-decimal dinars.
    int scale = com.storeql.service.Fx.minorUnits(s.currency());
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO commission_statements ("
                      + STATEMENT_COLUMNS
                      + ")"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setObject(3, s.storeId());
            ps.setObject(4, s.periodStart());
            ps.setObject(5, s.periodEnd());
            ps.setString(6, s.currency());
            ps.setString(7, s.status());
            ps.setBigDecimal(8, OrderRepository.money(s.netSales(), scale));
            ps.setBigDecimal(9, OrderRepository.money(s.commission(), scale));
            ps.setString(10, s.note());
            ps.setObject(11, s.supersedes());
            ps.setObject(12, s.supersededBy());
            ps.setObject(13, s.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(14, s.createdBy());
            ps.setObject(
                15, s.approvedAt() == null ? null : s.approvedAt().atOffset(ZoneOffset.UTC));
            ps.setObject(16, s.approvedBy());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO commission_statement_lines ("
                      + LINE_COLUMNS
                      + ")"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (StatementLine l : s.lines()) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, s.id());
              ps.setObject(4, l.sellerUserId());
              ps.setObject(5, l.schemeId());
              ps.setString(6, l.schemeName());
              ps.setObject(7, l.segmentFrom());
              ps.setObject(8, l.segmentTo());
              // Where the band starts, as the arrangement rated it: a count of units under a
              // per-unit arrangement (2.125 stays 2.125 on a yen statement, 100 stays 100 on a
              // dinar one), an amount of the sales under a percentage — never rounded as the
              // statement's money, which would show a band nobody set.
              ps.setBigDecimal(9, l.thresholdFrom());
              ps.setBigDecimal(10, l.rate());
              ps.setBigDecimal(11, l.amount());
              ps.setBigDecimal(12, OrderRepository.money(l.commission(), scale));
              ps.setString(13, l.rateCurrency());
              ps.setBigDecimal(14, l.ratedCommission());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return s;
        },
        "record a commission statement");
  }

  /**
   * Approves a draft, and closes the statement it replaces in the same transaction.
   *
   * <p>One transaction, because between the two writes a period would either have two statements
   * standing for it or none — and the unique index would refuse the first of those, leaving the
   * approval half done.
   *
   * @param supersededId the statement this one replaces, or null
   * @return false when it was not a draft any more
   */
  public boolean approve(UUID tenantId, UUID id, UUID approvedBy, UUID supersededId) {
    return inTx(
        c -> {
          if (supersededId != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE commission_statements SET superseded_by = ?, status = ?"
                        + " WHERE tenant_id = ? AND id = ? AND superseded_by IS NULL")) {
              ps.setObject(1, id);
              ps.setString(2, SalesAttribution.SUPERSEDED);
              ps.setObject(3, tenantId);
              ps.setObject(4, supersededId);
              ps.executeUpdate();
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE commission_statements SET status = ?, approved_at = ?, approved_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, SalesAttribution.APPROVED);
            ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
            ps.setObject(3, approvedBy);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.setString(6, SalesAttribution.DRAFT);
            return ps.executeUpdate() > 0;
          }
        },
        "approve a commission statement");
  }

  /** The one race the database decides here, named so a manager is told what happened. */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains("uq_statement_approved_period")) {
      return com.storeql.web.ApiException.conflict(
          "COMMISSION_STATEMENT_STANDS",
          "a statement for that period and scope is already approved; restate it instead of"
              + " approving a second one");
    }
    return super.handleTxSqlException(what, e);
  }

  /** Deletes a draft nobody approved: a draft is a working document, not a record. */
  public boolean discard(UUID tenantId, UUID id) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM commission_statement_lines WHERE tenant_id = ? AND statement_id ="
                      + " (SELECT id FROM commission_statements WHERE tenant_id = ? AND id = ?"
                      + " AND status = ?)")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            ps.setString(4, SalesAttribution.DRAFT);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM commission_statements WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            ps.setString(3, SalesAttribution.DRAFT);
            return ps.executeUpdate() > 0;
          }
        },
        "discard a commission statement draft");
  }

  /** One statement with its lines. */
  public Optional<Statement> statement(UUID tenantId, UUID id) {
    List<Statement> found =
        query(
            "SELECT "
                + STATEMENT_COLUMNS
                + " FROM commission_statements"
                + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            CommissionRepository::mapStatement,
            "read a commission statement");
    if (found.isEmpty()) return Optional.empty();
    return Optional.of(withLines(tenantId, found).get(0));
  }

  /** The statements of a period or a store, newest period first; lines left out. */
  public List<Statement> statements(UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + STATEMENT_COLUMNS + " FROM commission_statements WHERE tenant_id = ?");
    if (storeId != null) sql.append(" AND store_id = ?");
    if (status != null) sql.append(" AND status = ?");
    sql.append(" ORDER BY period_start DESC, created_at DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        CommissionRepository::mapStatement,
        "list commission statements");
  }

  /**
   * The statement that stands for a scope and period, when there is one.
   *
   * <p>Asked before a draft is made, so a period already signed off is not quietly restated.
   */
  public Optional<Statement> standing(
      UUID tenantId, UUID storeId, LocalDate from, LocalDate to, String currency) {
    return query(
            "SELECT "
                + STATEMENT_COLUMNS
                + " FROM commission_statements"
                + " WHERE tenant_id = ?"
                // A statement for the whole business is the one with no store, not any store's.
                + " AND ((CAST(? AS uuid) IS NULL AND store_id IS NULL)"
                + "      OR store_id = CAST(? AS uuid))"
                + " AND period_start = ? AND period_end = ? AND currency = ?"
                + " AND status = ? AND superseded_by IS NULL",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, storeId);
              ps.setObject(4, from);
              ps.setObject(5, to);
              ps.setString(6, currency);
              ps.setString(7, SalesAttribution.APPROVED);
            },
            CommissionRepository::mapStatement,
            "read the standing commission statement")
        .stream()
        .findFirst();
  }

  // ── who a sale is credited to ───────────────────────────────────────────────

  /**
   * Who a sale is credited to.
   *
   * @return empty when the order is not this tenant's; present with a null seller when the sale is
   *     credited to nobody, which is the ordinary answer online rather than an absence
   */
  public Optional<Credited> sellerOf(UUID tenantId, UUID orderId) {
    return query(
            "SELECT seller_user_id FROM orders WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, orderId);
            },
            rs -> new Credited(orderId, rs.getObject("seller_user_id", UUID.class)),
            "read a sale's seller")
        .stream()
        .findFirst();
  }

  /** A sale and whoever is credited with it, if anybody. */
  public record Credited(UUID orderId, UUID sellerUserId) {}

  /** Credits a sale to somebody, keeping what it was and who changed it. */
  public void credit(UUID tenantId, UUID orderId, UUID sellerUserId, SellerChange change) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE orders SET seller_user_id = ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, sellerUserId);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO order_seller_changes (id, tenant_id, order_id, from_user_id,"
                      + " to_user_id, reason, changed_at, changed_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, change.id());
            ps.setObject(2, change.tenantId());
            ps.setObject(3, change.orderId());
            ps.setObject(4, change.fromUserId());
            ps.setObject(5, change.toUserId());
            ps.setString(6, change.reason());
            ps.setObject(7, change.changedAt().atOffset(ZoneOffset.UTC));
            ps.setObject(8, change.changedBy());
            ps.executeUpdate();
          }
          return null;
        },
        "credit a sale to a seller");
  }

  /** How a sale's attribution has changed, newest first. */
  public List<SellerChange> changes(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, from_user_id, to_user_id, reason, changed_at, changed_by"
            + " FROM order_seller_changes WHERE tenant_id = ? AND order_id = ?"
            + " ORDER BY changed_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        rs ->
            new SellerChange(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("from_user_id", UUID.class),
                rs.getObject("to_user_id", UUID.class),
                rs.getString("reason"),
                rs.getObject("changed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("changed_by", UUID.class)),
        "read a sale's attribution history");
  }

  private List<Statement> withLines(UUID tenantId, List<Statement> statements) {
    Map<UUID, List<StatementLine>> lines = new LinkedHashMap<>();
    for (Statement s : statements) {
      lines.put(
          s.id(),
          query(
              "SELECT "
                  + LINE_COLUMNS
                  + " FROM commission_statement_lines"
                  + " WHERE tenant_id = ? AND statement_id = ?"
                  + " ORDER BY seller_user_id, segment_from, threshold_from NULLS FIRST",
              ps -> {
                ps.setObject(1, tenantId);
                ps.setObject(2, s.id());
              },
              CommissionRepository::mapLine,
              "read commission statement lines"));
    }
    List<Statement> out = new ArrayList<>(statements.size());
    for (Statement s : statements) {
      out.add(
          new Statement(
              s.id(),
              s.tenantId(),
              s.storeId(),
              s.periodStart(),
              s.periodEnd(),
              s.currency(),
              s.status(),
              s.netSales(),
              s.commission(),
              s.note(),
              s.supersedes(),
              s.supersededBy(),
              s.createdAt(),
              s.createdBy(),
              s.approvedAt(),
              s.approvedBy(),
              lines.getOrDefault(s.id(), List.of())));
    }
    return List.copyOf(out);
  }

  private static Statement mapStatement(ResultSet rs) throws SQLException {
    OffsetDateTime approvedAt = rs.getObject("approved_at", OffsetDateTime.class);
    return new Statement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getBigDecimal("net_sales"),
        rs.getBigDecimal("commission"),
        rs.getString("note"),
        rs.getObject("supersedes", UUID.class),
        rs.getObject("superseded_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_by", UUID.class),
        approvedAt == null ? null : approvedAt.toInstant(),
        rs.getObject("approved_by", UUID.class),
        List.of());
  }

  private static StatementLine mapLine(ResultSet rs) throws SQLException {
    return new StatementLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("statement_id", UUID.class),
        rs.getObject("seller_user_id", UUID.class),
        rs.getObject("scheme_id", UUID.class),
        rs.getString("scheme_name"),
        rs.getObject("segment_from", LocalDate.class),
        rs.getObject("segment_to", LocalDate.class),
        rs.getBigDecimal("threshold_from"),
        rs.getBigDecimal("rate"),
        rs.getBigDecimal("amount"),
        rs.getBigDecimal("commission"),
        rs.getString("rate_currency"),
        rs.getBigDecimal("rated_commission"));
  }
}
