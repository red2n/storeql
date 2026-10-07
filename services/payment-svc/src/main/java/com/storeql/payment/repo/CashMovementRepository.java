package com.storeql.payment.repo;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CashExpectation;
import com.storeql.payment.dto.Dtos.CashMovementResponse;
import com.storeql.payment.dto.Dtos.ZReportResponse;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.web.ApiException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for cash movements (pay-in/pay-out) and daily Z-reports. */
@ApplicationScoped
public class CashMovementRepository extends BaseOutboxRepository {

  /**
   * Record a pay-in/pay-out. If the same Idempotency-Key was already stored for this tenant, the
   * original movement is returned unchanged (replay) — a retried request must not double-count cash
   * in/out of the till.
   */
  public CashMovementResponse insertMovement(
      UUID tenantId,
      UUID storeId,
      UUID tillSessionId,
      String direction,
      BigDecimal amount,
      String reason,
      UUID authorisedBy,
      UUID recordedBy,
      String idempotencyKey) {
    return inTx(
        c -> {
          if (idempotencyKey != null) {
            CashMovementResponse existing = findMovementByKeyTx(c, tenantId, idempotencyKey);
            if (existing != null) {
              return existing;
            }
          }
          UUID id = Ids.newId();
          Instant now = Instant.now();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO cash_movements (id, tenant_id, store_id, till_session_id,"
                      + " direction, amount, reason, authorised_by, recorded_by, created_at,"
                      + " idempotency_key) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, tillSessionId);
            ps.setString(5, direction);
            ps.setBigDecimal(6, amount);
            ps.setString(7, reason);
            ps.setObject(8, authorisedBy);
            ps.setObject(9, recordedBy);
            ps.setObject(10, now.atOffset(ZoneOffset.UTC));
            ps.setString(11, idempotencyKey);
            ps.executeUpdate();
          }
          return new CashMovementResponse(
              id, storeId, tillSessionId, direction, amount, reason, now);
        },
        "insert cash movement");
  }

  private CashMovementResponse findMovementByKeyTx(
      Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, till_session_id, direction, amount, reason,"
                + " authorised_by, recorded_by, created_at"
                + " FROM cash_movements WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapMovement(rs) : null;
      }
    }
  }

  /**
   * Lists the pay-ins and pay-outs recorded against one till session.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param tillSessionId the session whose movements to list
   * @return the movements, empty when none were recorded
   */
  public List<CashMovementResponse> listMovements(UUID tenantId, UUID tillSessionId) {
    return query(
        "SELECT id, tenant_id, store_id, till_session_id, direction, amount, reason,"
            + " authorised_by, recorded_by, created_at"
            + " FROM cash_movements WHERE tenant_id=? AND till_session_id=? ORDER BY created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, tillSessionId);
        },
        CashMovementRepository::mapMovement,
        "list cash movements");
  }

  /** What settling a day answered: the report and whether this request wrote it. */
  public record Settled(ZReportResponse report, boolean written) {}

  private static final String Z_COLUMNS =
      "id, tenant_id, store_id, business_date, total_sales, total_refunds,"
          + " total_discounts, total_tax, net_sales, cash_sales, card_sales,"
          + " gift_card_sales, other_sales, opening_float, cash_drops, pay_ins,"
          + " pay_outs, expected_cash, counted_cash, over_short, transaction_count,"
          + " currency, generated_by, generated_at, cash_refunds, version, replaces_id,"
          + " correction_reason, time_zone, zone_assumed";

  /**
   * Settles a store's local day into a Z-report, once. The day is {@code [business date 00:00, next
   * day 00:00)} in the store's own zone (a DST day is 23 or 25 hours), and everything counted is at
   * this store: tenders and refunds by their {@code store_id}, drops and pay-ins and pay-outs
   * through the sessions at the store. The expected cash is {@link CashExpectation}, the same
   * formula as the till's X report and close, with each term stored.
   *
   * <p>A stored day is never overwritten. With no {@code correctionOf}, a day that already has a
   * report answers the stored latest one ({@code written = false}). With one, the new report is the
   * next version and names the version it replaces. A day with a session still open is refused.
   *
   * @param zone the store's zone, or UTC when it could not be read
   * @param zoneAssumed true when {@code zone} is UTC because the store's zone could not be read
   * @param correctionOf the latest stored report's id to replace, or null
   * @param reason why the day is corrected, with {@code correctionOf}
   * @throws ApiException {@code Z_REPORT_SESSIONS_OPEN} (409), {@code Z_REPORT_NOT_FOUND} (404) for
   *     a correction of a day never settled, {@code Z_REPORT_NOT_LATEST} (409)
   */
  public Settled settleDay(
      UUID tenantId,
      UUID storeId,
      LocalDate businessDate,
      java.time.ZoneId zone,
      boolean zoneAssumed,
      BigDecimal countedCash,
      String currency,
      UUID generatedBy,
      UUID correctionOf,
      String reason) {
    return inTx(
        c -> {
          ZReportResponse latest = latestTx(c, tenantId, storeId, businessDate);
          if (correctionOf == null && latest != null) {
            return new Settled(latest, false);
          }
          if (correctionOf != null) {
            if (latest == null) {
              throw ApiException.notFound(
                  "Z_REPORT_NOT_FOUND", "No Z-report found for that store and date");
            }
            if (!latest.id().equals(correctionOf)) {
              throw ApiException.conflict(
                  "Z_REPORT_NOT_LATEST", "Only the latest version of a day's report is corrected");
            }
          }
          Instant from = businessDate.atStartOfDay(zone).toInstant();
          Instant to = businessDate.plusDays(1).atStartOfDay(zone).toInstant();
          if (openSessionsTx(c, tenantId, storeId, to)) {
            throw ApiException.conflict(
                "Z_REPORT_SESSIONS_OPEN",
                "A till at this store is still open for that day; close it first");
          }
          BigDecimal totalSales = sumPayments(c, tenantId, storeId, from, to, "CAPTURED");
          BigDecimal totalRefunds = sumRefunds(c, tenantId, storeId, from, to, null);
          BigDecimal cashRefunds = sumRefunds(c, tenantId, storeId, from, to, "CASH");
          BigDecimal cashSales = sumTender(c, tenantId, storeId, from, to, "CASH");
          BigDecimal cardSales = sumTender(c, tenantId, storeId, from, to, "CARD");
          BigDecimal giftCardSales = sumTender(c, tenantId, storeId, from, to, "GIFT_CARD");
          BigDecimal otherSales =
              totalSales.subtract(cashSales).subtract(cardSales).subtract(giftCardSales);
          BigDecimal netSales = totalSales.subtract(totalRefunds);

          BigDecimal openFloat = sumTillFloats(c, tenantId, storeId, from, to);
          BigDecimal drops = sumDrops(c, tenantId, storeId, from, to);
          BigDecimal payIns = sumMovements(c, tenantId, storeId, from, to, "PAY_IN");
          BigDecimal payOuts = sumMovements(c, tenantId, storeId, from, to, "PAY_OUT");
          CashExpectation expectation =
              new CashExpectation(openFloat, cashSales, cashRefunds, payIns, payOuts, drops);
          BigDecimal expectedCash = expectation.expected();
          BigDecimal overShort = expectation.overShort(countedCash);
          int txCount = countTransactions(c, tenantId, storeId, from, to);
          int version = latest == null ? 1 : latest.version() + 1;

          UUID reportId = Ids.newId();
          Instant now = Instant.now();
          boolean inserted;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO z_reports (id, tenant_id, store_id, business_date,"
                      + " total_sales, total_refunds, total_discounts, total_tax, net_sales,"
                      + " cash_sales, card_sales, gift_card_sales, other_sales,"
                      + " opening_float, cash_drops, pay_ins, pay_outs,"
                      + " expected_cash, counted_cash, over_short, transaction_count,"
                      + " currency, generated_by, generated_at, cash_refunds, version,"
                      + " replaces_id, correction_reason, time_zone, zone_assumed)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, business_date, version) DO NOTHING"
                      + " RETURNING id")) {
            ps.setObject(1, reportId);
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, java.sql.Date.valueOf(businessDate));
            ps.setBigDecimal(5, totalSales);
            ps.setBigDecimal(6, totalRefunds);
            ps.setBigDecimal(7, BigDecimal.ZERO); // discounts from order-svc — not owned here
            ps.setBigDecimal(8, BigDecimal.ZERO); // tax from order-svc
            ps.setBigDecimal(9, netSales);
            ps.setBigDecimal(10, cashSales);
            ps.setBigDecimal(11, cardSales);
            ps.setBigDecimal(12, giftCardSales);
            ps.setBigDecimal(13, otherSales.max(BigDecimal.ZERO));
            ps.setBigDecimal(14, openFloat);
            ps.setBigDecimal(15, drops);
            ps.setBigDecimal(16, payIns);
            ps.setBigDecimal(17, payOuts);
            ps.setBigDecimal(18, expectedCash);
            ps.setBigDecimal(19, countedCash);
            ps.setBigDecimal(20, overShort);
            ps.setInt(21, txCount);
            ps.setString(22, currency);
            ps.setObject(23, generatedBy);
            ps.setObject(24, now.atOffset(ZoneOffset.UTC));
            ps.setBigDecimal(25, cashRefunds);
            ps.setInt(26, version);
            ps.setObject(27, correctionOf);
            ps.setString(28, correctionOf == null ? null : reason);
            ps.setString(29, zone.getId());
            ps.setBoolean(30, zoneAssumed);
            try (ResultSet rs = ps.executeQuery()) {
              inserted = rs.next();
            }
          }
          if (!inserted) {
            // Lost a race to another settle of the same day: that report is the day's.
            return new Settled(latestTx(c, tenantId, storeId, businessDate), false);
          }
          return new Settled(
              new ZReportResponse(
                  reportId,
                  storeId,
                  businessDate,
                  totalSales,
                  totalRefunds,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  netSales,
                  cashSales,
                  cardSales,
                  giftCardSales,
                  openFloat,
                  drops,
                  payIns,
                  payOuts,
                  expectedCash,
                  countedCash,
                  overShort,
                  txCount,
                  currency,
                  now,
                  cashRefunds,
                  version,
                  correctionOf,
                  correctionOf == null ? null : reason,
                  zone.getId(),
                  zoneAssumed,
                  true),
              true);
        },
        "settle z-report");
  }

  /**
   * Reads a settled Z-report for one store and business day: the latest version, or the one asked
   * for.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store whose report to read
   * @param businessDate the business date the report settled
   * @param version the version to read, or null for the latest
   * @return the report, or empty when that day has not been settled
   */
  public Optional<ZReportResponse> findZReport(
      UUID tenantId, UUID storeId, LocalDate businessDate, Integer version) {
    return inTx(
        c -> {
          if (version == null) {
            return Optional.ofNullable(latestTx(c, tenantId, storeId, businessDate));
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + Z_COLUMNS
                      + " FROM z_reports WHERE tenant_id=? AND store_id=? AND business_date=?"
                      + " AND version=?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, java.sql.Date.valueOf(businessDate));
            ps.setInt(4, version);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next()
                  ? Optional.of(mapZReport(rs, false))
                  : Optional.<ZReportResponse>empty();
            }
          }
        },
        "find z-report");
  }

  private static ZReportResponse latestTx(
      Connection c, UUID tenantId, UUID storeId, LocalDate businessDate) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + Z_COLUMNS
                + " FROM z_reports WHERE tenant_id=? AND store_id=? AND business_date=?"
                + " ORDER BY version DESC LIMIT 1")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, java.sql.Date.valueOf(businessDate));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapZReport(rs, false) : null;
      }
    }
  }

  /** A session at the store, opened before the day ended, that is still open. */
  private static boolean openSessionsTx(Connection c, UUID tenantId, UUID storeId, Instant dayEnd)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM till_sessions WHERE tenant_id=? AND store_id=? AND status='OPEN'"
                + " AND opened_at < ? LIMIT 1")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, dayEnd.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    return dbError(what, e);
  }

  // ─────────────────────────────────────────── aggregation helpers

  private BigDecimal sumPayments(
      Connection c, UUID tenantId, UUID storeId, Instant from, Instant to, String status)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM payment_tenders"
                + " WHERE tenant_id=? AND store_id=? AND status=?"
                + " AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, status);
      ps.setObject(4, from.atOffset(ZoneOffset.UTC));
      ps.setObject(5, to.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumRefunds(
      Connection c, UUID tenantId, UUID storeId, Instant from, Instant to, String method)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM refund_tenders"
                + " WHERE tenant_id=? AND store_id=? AND created_at >= ? AND created_at < ?"
                + (method == null ? "" : " AND method=?"))) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atOffset(ZoneOffset.UTC));
      if (method != null) ps.setString(5, method);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumTender(
      Connection c, UUID tenantId, UUID storeId, Instant from, Instant to, String method)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM payment_tenders"
                + " WHERE tenant_id=? AND store_id=? AND method=? AND status='CAPTURED'"
                + " AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, method);
      ps.setObject(4, from.atOffset(ZoneOffset.UTC));
      ps.setObject(5, to.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumTillFloats(
      Connection c, UUID tenantId, UUID storeId, Instant from, Instant to) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(float_amount),0) FROM till_sessions"
                + " WHERE tenant_id=? AND store_id=? AND opened_at >= ? AND opened_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumDrops(Connection c, UUID tenantId, UUID storeId, Instant from, Instant to)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(cd.amount),0) FROM cash_drops cd"
                + " JOIN till_sessions ts ON ts.id = cd.till_session_id AND ts.tenant_id = cd.tenant_id"
                + " WHERE cd.tenant_id=? AND ts.store_id=? AND cd.created_at >= ? AND cd.created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumMovements(
      Connection c, UUID tenantId, UUID storeId, Instant from, Instant to, String direction)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM cash_movements"
                + " WHERE tenant_id=? AND store_id=? AND direction=?"
                + " AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, direction);
      ps.setObject(4, from.atOffset(ZoneOffset.UTC));
      ps.setObject(5, to.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private int countTransactions(Connection c, UUID tenantId, UUID storeId, Instant from, Instant to)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COUNT(DISTINCT order_id) FROM payment_tenders"
                + " WHERE tenant_id=? AND store_id=? AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  // ─────────────────────────────────────────── row mappers

  private static CashMovementResponse mapMovement(ResultSet rs) throws SQLException {
    return new CashMovementResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("till_session_id", UUID.class),
        rs.getString("direction"),
        rs.getBigDecimal("amount"),
        rs.getString("reason"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ZReportResponse mapZReport(ResultSet rs, boolean written) throws SQLException {
    String zone = rs.getString("time_zone");
    return new ZReportResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getDate("business_date").toLocalDate(),
        rs.getBigDecimal("total_sales"),
        rs.getBigDecimal("total_refunds"),
        rs.getBigDecimal("total_discounts"),
        rs.getBigDecimal("total_tax"),
        rs.getBigDecimal("net_sales"),
        rs.getBigDecimal("cash_sales"),
        rs.getBigDecimal("card_sales"),
        rs.getBigDecimal("gift_card_sales"),
        rs.getBigDecimal("opening_float"),
        rs.getBigDecimal("cash_drops"),
        rs.getBigDecimal("pay_ins"),
        rs.getBigDecimal("pay_outs"),
        rs.getBigDecimal("expected_cash"),
        rs.getBigDecimal("counted_cash"),
        rs.getBigDecimal("over_short"),
        rs.getInt("transaction_count"),
        rs.getString("currency"),
        rs.getObject("generated_at", OffsetDateTime.class).toInstant(),
        rs.getBigDecimal("cash_refunds"),
        rs.getInt("version"),
        rs.getObject("replaces_id", UUID.class),
        rs.getString("correction_reason"),
        zone == null ? "UTC" : zone,
        zone == null || rs.getBoolean("zone_assumed"),
        written);
  }
}
