package com.storeql.order.repo;

import com.storeql.order.domain.EReporting.CrossBorderLine;
import com.storeql.order.domain.EReporting.Day;
import com.storeql.order.domain.EReporting.RateLine;
import com.storeql.order.domain.EReporting.Submission;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
 * What a period's e-reporting contains, read from this service's own sales (18.9, second limb).
 *
 * <p>Three queries and one table. The queries are where the design lives, and each one answers a
 * question the law asks in a particular shape.
 *
 * <p><b>A sale is in scope because no e-invoice carries it.</b> The exclusion is {@code NOT EXISTS}
 * on {@code sales_invoices}, not a judgement about the buyer: the invoice is the evidence that the
 * other limb already reports the sale, and anything else would double-report or miss.
 *
 * <p><b>The day a sale is reported in is the day it was supplied</b> — the first transition to
 * {@code FULFILLED}, taken from the append-only status history. Not {@code updated_at}, which a
 * later edit would move, silently re-dating a sale into a period that was already reported.
 *
 * <p><b>A refund is a negative operation on the day it happened</b>, split by the rate of the line
 * it returns. A period that reported gross sales and ignored refunds would overstate the VAT owed.
 */
@ApplicationScoped
public class EReportingRepository extends BaseJdbcRepository {

  /**
   * The sales in scope: supplied in the window, and carried by no e-invoice.
   *
   * <p>One definition used by both queries below — the counts and the money must be about exactly
   * the same sales, and two copies of a scope rule drift the day one of them is corrected. Binds,
   * in order: tenant, currency, window start, window end, tenant.
   */
  private static final String IN_SCOPE =
      """
      WITH supplied AS (
          SELECT o.id, o.currency, MIN(h.changed_at) AS at
          FROM orders o
          JOIN order_status_history h
            ON h.tenant_id = o.tenant_id AND h.order_id = o.id AND h.to_status = 'FULFILLED'
          WHERE o.tenant_id = ? AND o.currency = ?
          GROUP BY o.id, o.currency
      ),
      inscope AS (
          SELECT s.id, s.at FROM supplied s
          WHERE s.at >= ? AND s.at < ?
            AND NOT EXISTS (
                SELECT 1 FROM sales_invoices si
                WHERE si.tenant_id = ? AND si.order_id = s.id AND si.type_code = '380')
      ),
      """;

  /**
   * Sales supplied in the window that no invoice carries, per day and currency, with how many.
   *
   * <p>Counted per day rather than per rate: a basket with a zero-rated loaf and a standard-rated
   * bottle is one transaction, and counting per rate would report it as two.
   */
  private static final String DAY_COUNTS =
      IN_SCOPE
          + """
      refunded AS (
          -- COALESCE, because a till refund is completed as it is recorded and leaves
          -- completed_at null: taking the column alone would drop every counter refund from the
          -- report, which is the quiet kind of error a tax filing must not have.
          SELECT r.id, COALESCE(r.completed_at, r.created_at) AS at
          FROM returns r
          JOIN orders o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
          WHERE r.tenant_id = ? AND o.currency = ? AND r.status = 'COMPLETED'
            AND COALESCE(r.completed_at, r.created_at) >= ?
            AND COALESCE(r.completed_at, r.created_at) < ?
            AND NOT EXISTS (
                SELECT 1 FROM sales_invoices si
                WHERE si.tenant_id = ? AND si.return_id = r.id)
      )
      SELECT day, SUM(operations)::int AS operations FROM (
          SELECT (at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS operations
          FROM inscope GROUP BY 1
          UNION ALL
          SELECT (at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS operations
          FROM refunded GROUP BY 1
      ) counted GROUP BY day ORDER BY day""";

  /** The money of those same sales and refunds, per day and rate. Refunds come back negative. */
  private static final String DAY_RATES =
      IN_SCOPE
          + """
      sold AS (
          SELECT (i.at AT TIME ZONE 'UTC')::date AS day,
                 COALESCE(oi.vat_code, '') AS vat_code,
                 COALESCE(oi.vat_rate, 0) AS vat_rate,
                 SUM(oi.line_total) AS net,
                 SUM(COALESCE(oi.vat_amount, 0)) AS vat
          FROM inscope i
          JOIN order_items oi ON oi.tenant_id = ? AND oi.order_id = i.id
          GROUP BY 1, 2, 3
      ),
      returned AS (
          SELECT (COALESCE(r.completed_at, r.created_at) AT TIME ZONE 'UTC')::date AS day,
                 COALESCE(oi.vat_code, '') AS vat_code,
                 COALESCE(oi.vat_rate, 0) AS vat_rate,
                 -- A return item's refund_amount is the line's NET unit price times the quantity
                 -- returned (OrderService takes it from the order, not from what the caller asked
                 -- for), so the VAT is computed from the rate rather than divided out of a gross.
                 -- Dividing would have understated the refund by the VAT on the VAT. A sale at
                 -- shelf prices kept the VAT inside what it refunded (tax_amount): that figure is
                 -- reported as it was refunded, not worked out again from a net.
                 -SUM(ri.refund_amount) AS net,
                 -SUM(COALESCE(ri.tax_amount,
                               ROUND(ri.refund_amount * COALESCE(oi.vat_rate, 0), 2))) AS vat
          FROM returns r
          JOIN return_items ri ON ri.tenant_id = r.tenant_id AND ri.return_id = r.id
          JOIN orders o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
          JOIN order_items oi
            ON oi.tenant_id = r.tenant_id AND oi.order_id = r.order_id
           AND oi.variant_id = ri.variant_id
          WHERE r.tenant_id = ? AND o.currency = ? AND r.status = 'COMPLETED'
            AND COALESCE(r.completed_at, r.created_at) >= ?
            AND COALESCE(r.completed_at, r.created_at) < ?
            AND NOT EXISTS (
                SELECT 1 FROM sales_invoices si
                WHERE si.tenant_id = ? AND si.return_id = r.id)
          GROUP BY 1, 2, 3
      )
      SELECT day, vat_code, vat_rate,
             SUM(net)::numeric(18,2) AS net, SUM(vat)::numeric(18,2) AS vat
      FROM (SELECT * FROM sold UNION ALL SELECT * FROM returned) movements
      GROUP BY day, vat_code, vat_rate
      ORDER BY day, vat_rate DESC""";

  /**
   * Invoices issued in the window to a buyer whose VAT identifier is another country's.
   *
   * <p>Cross-border sales are reported one by one: the other administration matches them against
   * its own records, and an aggregate matches nothing. The country is the first two characters of
   * the buyer's VAT identifier — the identifier is what is matched on, so it is also what the
   * country is taken from, rather than an address field somebody typed.
   */
  private static final String CROSS_BORDER =
      """
      SELECT full_number, issue_date, buyer_vat_id, currency, net_amount, vat_amount, type_code
      FROM sales_invoices
      WHERE tenant_id = ? AND currency = ?
        AND issue_date >= ? AND issue_date < ?
        AND buyer_vat_id IS NOT NULL
        AND upper(substring(buyer_vat_id, 1, 2)) <> ?
      ORDER BY issue_date, full_number""";

  /** Every currency the period took money in, so nothing is silently left out of a report. */
  private static final String CURRENCIES =
      """
      SELECT DISTINCT o.currency
      FROM orders o
      JOIN order_status_history h
        ON h.tenant_id = o.tenant_id AND h.order_id = o.id AND h.to_status = 'FULFILLED'
      WHERE o.tenant_id = ? AND h.changed_at >= ? AND h.changed_at < ?
      ORDER BY 1""";

  private static final String COLUMNS =
      "id, tenant_id, return_code, period_start, period_end, currency, transaction_count,"
          + " net_total, vat_total, payload, payload_digest, network, provider, status, detail,"
          + " provider_ref, attempts, created_at, created_by, transmitted_at, supersedes,"
          + " superseded_by";

  private static final String INSERT =
      "INSERT INTO ereporting_submissions (id, tenant_id, return_code, period_start, period_end,"
          + " currency, transaction_count, net_total, vat_total, payload, payload_digest, network,"
          + " provider, status, attempts, created_at, created_by, supersedes)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  /** The days of a period, money and counts joined into the shape the report is written in. */
  public List<Day> days(UUID tenantId, String currency, LocalDate from, LocalDate to) {
    OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime end = to.atStartOfDay().atOffset(ZoneOffset.UTC);

    Map<LocalDate, Integer> counts = new LinkedHashMap<>();
    for (Object[] row :
        query(
            DAY_COUNTS,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, currency);
              ps.setObject(3, start);
              ps.setObject(4, end);
              ps.setObject(5, tenantId);
              ps.setObject(6, tenantId);
              ps.setString(7, currency);
              ps.setObject(8, start);
              ps.setObject(9, end);
              ps.setObject(10, tenantId);
            },
            rs -> new Object[] {rs.getObject("day", LocalDate.class), rs.getInt("operations")},
            "e-reporting day counts")) {
      counts.put((LocalDate) row[0], (Integer) row[1]);
    }

    Map<LocalDate, List<RateLine>> rates = new LinkedHashMap<>();
    for (Object[] row :
        query(
            DAY_RATES,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, currency);
              ps.setObject(3, start);
              ps.setObject(4, end);
              ps.setObject(5, tenantId);
              ps.setObject(6, tenantId);
              ps.setObject(7, tenantId);
              ps.setString(8, currency);
              ps.setObject(9, start);
              ps.setObject(10, end);
              ps.setObject(11, tenantId);
            },
            rs ->
                new Object[] {
                  rs.getObject("day", LocalDate.class),
                  new RateLine(
                      rs.getString("vat_code"),
                      rs.getBigDecimal("vat_rate"),
                      rs.getBigDecimal("net"),
                      rs.getBigDecimal("vat"))
                },
            "e-reporting day rates")) {
      rates.computeIfAbsent((LocalDate) row[0], k -> new ArrayList<>()).add((RateLine) row[1]);
    }

    List<Day> out = new ArrayList<>();
    for (Map.Entry<LocalDate, Integer> e : counts.entrySet()) {
      out.add(new Day(e.getKey(), e.getValue(), rates.getOrDefault(e.getKey(), List.of())));
    }
    return out;
  }

  /** The cross-border invoices of a period, at invoice level. */
  public List<CrossBorderLine> crossBorder(
      UUID tenantId, String currency, LocalDate from, LocalDate to, String sellerCountry) {
    return query(
        CROSS_BORDER,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, currency);
          ps.setObject(3, from);
          ps.setObject(4, to);
          ps.setString(
              5, sellerCountry == null ? "" : sellerCountry.toUpperCase(java.util.Locale.ROOT));
        },
        rs -> {
          boolean creditNote = "381".equals(rs.getString("type_code"));
          BigDecimal net = rs.getBigDecimal("net_amount");
          BigDecimal vat = rs.getBigDecimal("vat_amount");
          String buyerVatId = rs.getString("buyer_vat_id");
          return new CrossBorderLine(
              rs.getString("full_number"),
              rs.getObject("issue_date", LocalDate.class),
              buyerVatId.substring(0, 2).toUpperCase(java.util.Locale.ROOT),
              buyerVatId,
              rs.getString("currency"),
              creditNote ? net.negate() : net,
              creditNote ? vat.negate() : vat);
        },
        "e-reporting cross-border operations");
  }

  /** The currencies a period took money in. */
  public List<String> currencies(UUID tenantId, LocalDate from, LocalDate to) {
    return query(
        CURRENCIES,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, from.atStartOfDay().atOffset(ZoneOffset.UTC));
          ps.setObject(3, to.atStartOfDay().atOffset(ZoneOffset.UTC));
        },
        rs -> rs.getString(1),
        "e-reporting currencies");
  }

  /**
   * Records a submission, marking the one it corrects in the same transaction.
   *
   * <p>One statement order works: the predecessor is marked first, then the successor inserted, or
   * the partial unique index refuses it. The deferred foreign key is what makes that order legal.
   */
  public Submission record(Submission s) {
    return inTx(
        c -> {
          if (s.supersedes() != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE ereporting_submissions SET superseded_by = ?"
                        + " WHERE tenant_id = ? AND id = ? AND superseded_by IS NULL")) {
              ps.setObject(1, s.id());
              ps.setObject(2, s.tenantId());
              ps.setObject(3, s.supersedes());
              if (ps.executeUpdate() != 1) {
                throw ApiException.conflict(
                    "EREPORTING_NOT_STANDING",
                    "That submission has already been corrected by another");
              }
            }
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setString(3, s.returnCode());
            ps.setObject(4, s.periodStart());
            ps.setObject(5, s.periodEnd());
            ps.setString(6, s.currency());
            ps.setInt(7, s.transactionCount());
            ps.setBigDecimal(8, s.netTotal());
            ps.setBigDecimal(9, s.vatTotal());
            ps.setString(10, s.payload());
            ps.setString(11, s.payloadDigest());
            ps.setString(12, s.network());
            ps.setString(13, s.provider());
            ps.setString(14, s.status());
            ps.setInt(15, s.attempts());
            ps.setObject(16, s.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(17, s.createdBy());
            ps.setObject(18, s.supersedes());
            ps.executeUpdate();
          }
          return s;
        },
        "record an e-reporting submission");
  }

  /** Settles a submission with what the network said. */
  public void settle(
      UUID tenantId, UUID id, String status, String detail, String providerRef, Instant at) {
    exec(
        "UPDATE ereporting_submissions SET status = ?, detail = ?,"
            + " provider_ref = COALESCE(?, provider_ref), attempts = attempts + 1,"
            + " transmitted_at = COALESCE(transmitted_at, ?) WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setString(2, detail);
          ps.setString(3, providerRef);
          ps.setObject(4, at.atOffset(ZoneOffset.UTC));
          ps.setObject(5, tenantId);
          ps.setObject(6, id);
        },
        "settle an e-reporting submission");
  }

  public Optional<Submission> find(UUID tenantId, UUID id) {
    return query(
            "SELECT " + COLUMNS + " FROM ereporting_submissions WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            EReportingRepository::read,
            "an e-reporting submission")
        .stream()
        .findFirst();
  }

  /** The submission that stands for a period and currency, if any. */
  public Optional<Submission> standing(
      UUID tenantId, String returnCode, LocalDate periodStart, String currency) {
    return query(
            "SELECT "
                + COLUMNS
                + " FROM ereporting_submissions WHERE tenant_id = ? AND return_code = ?"
                + " AND period_start = ? AND currency = ? AND superseded_by IS NULL",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, returnCode);
              ps.setObject(3, periodStart);
              ps.setString(4, currency);
            },
            EReportingRepository::read,
            "the standing e-reporting submission")
        .stream()
        .findFirst();
  }

  /** A business's submissions, newest period first. */
  public List<Submission> list(UUID tenantId, int limit) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM ereporting_submissions WHERE tenant_id = ?"
            + " ORDER BY period_start DESC, return_code, created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        EReportingRepository::read,
        "e-reporting submissions");
  }

  private static Submission read(ResultSet rs) throws SQLException {
    return new Submission(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("return_code"),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getString("currency"),
        rs.getInt("transaction_count"),
        rs.getBigDecimal("net_total"),
        rs.getBigDecimal("vat_total"),
        rs.getString("payload"),
        rs.getString("payload_digest"),
        rs.getString("network"),
        rs.getString("provider"),
        rs.getString("status"),
        rs.getString("detail"),
        rs.getString("provider_ref"),
        rs.getInt("attempts"),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "transmitted_at"),
        rs.getObject("supersedes", UUID.class),
        rs.getObject("superseded_by", UUID.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  /** A second standing submission for one period is a business conflict, not a server fault. */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains("uq_ereporting_period")) {
      return ApiException.conflict(
          "EREPORTING_ALREADY_SUBMITTED",
          "That period has already been reported; correct the submission instead");
    }
    return super.handleTxSqlException(what, e);
  }
}
