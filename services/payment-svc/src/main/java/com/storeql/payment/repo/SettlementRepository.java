package com.storeql.payment.repo;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Settlements;
import com.storeql.payment.domain.Settlements.Batch;
import com.storeql.payment.domain.Settlements.Line;
import com.storeql.payment.domain.Settlements.ParsedLine;
import com.storeql.payment.domain.Settlements.StoreTotals;
import com.storeql.payment.domain.Settlements.Unsettled;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Settlement batches and their lines (11.10). A file is imported, matched and — when nothing in it
 * is open — reconciled in one transaction, so a batch is never seen half matched. Matching is done
 * by the database over the whole batch at once, not a query per line: a day's file is thousands of
 * lines. Tenders, refunds and disputes are only read: that one has settled is the line's fact.
 */
@ApplicationScoped
public class SettlementRepository extends BaseOutboxRepository {

  private static final int INSERT_CHUNK = 500;

  /** Two lines with one reference can want the same tender; each pass gives the loser another. */
  private static final int MATCH_PASSES = 8;

  private static final String BATCH_COLUMNS =
      "SELECT b.id, b.tenant_id, b.store_id, b.provider, b.reference, b.format, b.currency,"
          + " b.payout_date, b.declared_net, b.sales_amount, b.refund_amount, b.chargeback_amount,"
          + " b.fee_amount, b.net_amount, b.line_count,"
          + " (SELECT count(*) FROM settlement_lines l WHERE l.tenant_id = b.tenant_id"
          + " AND l.batch_id = b.id AND l.resolution IS NULL"
          + " AND l.match_status IN ('UNMATCHED', 'AMOUNT_MISMATCH', 'DUPLICATE')),"
          + " b.status, b.idempotency_key, b.imported_by, b.imported_at, b.reconciled_by,"
          + " b.reconciled_at FROM settlement_batches b";

  private static final String LINE_COLUMNS =
      "SELECT id, batch_id, line_no, type, reference, original_reference, gross_amount, fee_amount,"
          + " net_amount, occurred_at, match_status, matched_tender_id, matched_refund_id,"
          + " matched_dispute_id, store_id, expected_amount, resolution, resolved_by, resolved_at,"
          + " note FROM settlement_lines";

  private static final String INSERT_BATCH =
      "INSERT INTO settlement_batches (id, tenant_id, store_id, provider, reference, format,"
          + " currency, payout_date, declared_net, sales_amount, refund_amount, chargeback_amount,"
          + " fee_amount, net_amount, line_count, status, idempotency_key, imported_by,"
          + " imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String INSERT_LINE =
      "INSERT INTO settlement_lines (id, tenant_id, batch_id, line_no, type, reference,"
          + " original_reference, gross_amount, fee_amount, net_amount, occurred_at, match_status)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

  /**
   * A sale line takes the captured card tender the acquirer's reference names: of several with one
   * reference (an authorisation code is six characters, and repeats) the one for the same sum, then
   * the nearest in time; never one another line has. Where two lines want the same tender the
   * earlier line has it and the next pass finds the other another.
   */
  private static final String MATCH_SALES =
      "WITH candidate AS ("
          + " SELECT l.id AS line_id, l.line_no, t.id AS tender_id, t.store_id, t.amount,"
          + " row_number() OVER (PARTITION BY l.id ORDER BY (t.amount = l.gross_amount) DESC,"
          + " abs(extract(epoch FROM (t.created_at - COALESCE(l.occurred_at, t.created_at)))),"
          + " t.id) AS pick"
          + " FROM settlement_lines l JOIN payment_tenders t ON t.tenant_id = l.tenant_id"
          + " AND t.reference IN (l.reference, l.original_reference)"
          + " WHERE l.tenant_id = ? AND l.batch_id = ? AND l.type = 'SALE'"
          + " AND l.match_status = 'UNMATCHED' AND t.status = 'CAPTURED'"
          + " AND t.method IN ('CARD', 'UPI', 'WALLET')"
          + " AND NOT EXISTS (SELECT 1 FROM settlement_lines s WHERE s.tenant_id = t.tenant_id"
          + " AND s.matched_tender_id = t.id AND s.type = 'SALE')),"
          + " chosen AS (SELECT line_id, tender_id, store_id, amount, row_number() OVER"
          + " (PARTITION BY tender_id ORDER BY line_no) AS claim FROM candidate WHERE pick = 1)"
          + " UPDATE settlement_lines l SET matched_tender_id = c.tender_id, store_id = c.store_id,"
          + " match_status = CASE WHEN c.amount = l.gross_amount THEN 'MATCHED'"
          + " ELSE 'AMOUNT_MISMATCH' END,"
          + " expected_amount = CASE WHEN c.amount = l.gross_amount THEN NULL ELSE c.amount END"
          + " FROM chosen c WHERE l.tenant_id = ? AND l.id = c.line_id AND c.claim = 1";

  /**
   * What is left of the sales and names a tender some line already has: paid twice, or sent twice.
   */
  private static final String FLAG_DUPLICATE_SALES =
      "UPDATE settlement_lines l SET match_status = 'DUPLICATE', expected_amount = t.amount"
          + " FROM payment_tenders t WHERE l.tenant_id = ? AND l.batch_id = ? AND l.type = 'SALE'"
          + " AND l.match_status = 'UNMATCHED' AND t.tenant_id = l.tenant_id"
          + " AND t.reference IN (l.reference, l.original_reference) AND t.status = 'CAPTURED'"
          + " AND t.method IN ('CARD', 'UPI', 'WALLET')"
          + " AND EXISTS (SELECT 1 FROM settlement_lines s WHERE s.tenant_id = t.tenant_id"
          + " AND s.matched_tender_id = t.id AND s.type = 'SALE')";

  /** A refund line by the refund's own reference. */
  private static final String MATCH_REFUNDS_BY_REFERENCE =
      "WITH candidate AS ("
          + " SELECT l.id AS line_id, l.line_no, r.id AS refund_id, r.amount, r.store_id,"
          + " row_number() OVER (PARTITION BY l.id ORDER BY (r.amount = -l.gross_amount) DESC,"
          + " r.created_at, r.id) AS pick"
          + " FROM settlement_lines l JOIN refund_tenders r ON r.tenant_id = l.tenant_id"
          + " AND r.reference = l.reference"
          + " WHERE l.tenant_id = ? AND l.batch_id = ? AND l.type = 'REFUND'"
          + " AND l.match_status = 'UNMATCHED'"
          + " AND NOT EXISTS (SELECT 1 FROM settlement_lines s WHERE s.tenant_id = r.tenant_id"
          + " AND s.matched_refund_id = r.id AND s.type = 'REFUND')),"
          + " chosen AS (SELECT line_id, refund_id, store_id, amount, row_number() OVER"
          + " (PARTITION BY refund_id ORDER BY line_no) AS claim FROM candidate WHERE pick = 1)"
          + " UPDATE settlement_lines l SET matched_refund_id = c.refund_id, store_id = c.store_id,"
          + " match_status = CASE WHEN c.amount = -l.gross_amount THEN 'MATCHED'"
          + " ELSE 'AMOUNT_MISMATCH' END,"
          + " expected_amount = CASE WHEN c.amount = -l.gross_amount THEN NULL ELSE -c.amount END"
          + " FROM chosen c WHERE l.tenant_id = ? AND l.id = c.line_id AND c.claim = 1";

  /**
   * A refund line that names only the payment it gave back: a refund of that payment for that sum.
   * The sum is part of the key here — a payment may be refunded in parts — so a refund of a
   * different sum is left unmatched for a person rather than guessed at.
   */
  private static final String MATCH_REFUNDS_BY_PAYMENT =
      "WITH candidate AS ("
          + " SELECT l.id AS line_id, l.line_no, r.id AS refund_id, r.store_id,"
          + " row_number() OVER (PARTITION BY l.id ORDER BY r.created_at, r.id) AS pick"
          + " FROM settlement_lines l JOIN payment_tenders t ON t.tenant_id = l.tenant_id"
          + " AND t.reference IN (l.reference, l.original_reference)"
          + " JOIN refund_tenders r ON r.tenant_id = t.tenant_id AND r.payment_id = t.id"
          + " AND r.amount = -l.gross_amount"
          + " WHERE l.tenant_id = ? AND l.batch_id = ? AND l.type = 'REFUND'"
          + " AND l.match_status = 'UNMATCHED'"
          + " AND NOT EXISTS (SELECT 1 FROM settlement_lines s WHERE s.tenant_id = r.tenant_id"
          + " AND s.matched_refund_id = r.id AND s.type = 'REFUND')),"
          + " chosen AS (SELECT line_id, refund_id, store_id, row_number() OVER"
          + " (PARTITION BY refund_id ORDER BY line_no) AS claim FROM candidate WHERE pick = 1)"
          + " UPDATE settlement_lines l SET matched_refund_id = c.refund_id, store_id = c.store_id,"
          + " match_status = 'MATCHED', expected_amount = NULL"
          + " FROM chosen c WHERE l.tenant_id = ? AND l.id = c.line_id AND c.claim = 1";

  /**
   * A chargeback line against the dispute on file, by the dispute's own reference or by the payment
   * it is about. What this service holds for it is what the ledger has already moved: the amount
   * and the fee once the acquirer took them (nothing before), and the amount alone coming back on a
   * win (nothing unless it was won).
   */
  private static final String MATCH_CHARGEBACKS =
      "WITH candidate AS ("
          + " SELECT l.id AS line_id, l.line_no, l.type, d.id AS dispute_id, d.store_id,"
          + " CASE WHEN l.type = 'CHARGEBACK' THEN"
          + " CASE WHEN d.funds_withdrawn THEN -(d.amount + d.fee_amount) ELSE 0 END"
          + " ELSE CASE WHEN d.status = 'WON' THEN d.amount ELSE 0 END END AS held,"
          + " row_number() OVER (PARTITION BY l.id ORDER BY"
          + " (d.provider_dispute_ref = l.reference) DESC, d.opened_at DESC, d.id) AS pick"
          + " FROM settlement_lines l JOIN disputes d ON d.tenant_id = l.tenant_id"
          + " AND (d.provider_dispute_ref = l.reference OR d.payment_id IN"
          + " (SELECT t.id FROM payment_tenders t WHERE t.tenant_id = l.tenant_id"
          + " AND t.reference IN (l.reference, l.original_reference)))"
          + " WHERE l.tenant_id = ? AND l.batch_id = ?"
          + " AND l.type IN ('CHARGEBACK', 'CHARGEBACK_REVERSAL') AND l.match_status = 'UNMATCHED'"
          + " AND NOT EXISTS (SELECT 1 FROM settlement_lines s WHERE s.tenant_id = d.tenant_id"
          + " AND s.matched_dispute_id = d.id AND s.type = l.type)),"
          + " chosen AS (SELECT line_id, dispute_id, store_id, held, row_number() OVER"
          + " (PARTITION BY dispute_id, type ORDER BY line_no) AS claim"
          + " FROM candidate WHERE pick = 1)"
          + " UPDATE settlement_lines l SET matched_dispute_id = c.dispute_id,"
          + " store_id = c.store_id,"
          + " match_status = CASE WHEN c.held = l.net_amount THEN 'MATCHED'"
          + " ELSE 'AMOUNT_MISMATCH' END,"
          + " expected_amount = CASE WHEN c.held = l.net_amount THEN NULL ELSE c.held END"
          + " FROM chosen c WHERE l.tenant_id = ? AND l.id = c.line_id AND c.claim = 1";

  private static final String COUNT_OPEN =
      "SELECT count(*) FROM settlement_lines WHERE tenant_id = ? AND batch_id = ?"
          + " AND resolution IS NULL AND match_status IN ('UNMATCHED', 'AMOUNT_MISMATCH',"
          + " 'DUPLICATE')";

  /**
   * What a batch moves, by store. A chargeback's fee is not a fee here — the ledger booked it when
   * the dispute took the money (11.9) — unless the line went to unallocated, where nothing had.
   * Clearing gives up what this service holds for the line, which for an accepted difference is not
   * what the acquirer paid; whatever that leaves over is unallocated, so every store's four figures
   * balance by construction.
   */
  private static final String STORE_TOTALS =
      "SELECT COALESCE(l.store_id, b.store_id) AS store, SUM(l.net_amount),"
          + " SUM(CASE WHEN l.type IN ('CHARGEBACK', 'CHARGEBACK_REVERSAL')"
          + " AND l.resolution IS DISTINCT FROM 'UNALLOCATED' THEN 0 ELSE l.fee_amount END),"
          + " SUM(CASE WHEN l.resolution = 'UNALLOCATED' OR l.type IN ('FEE', 'ADJUSTMENT') THEN 0"
          + " WHEN l.type IN ('CHARGEBACK', 'CHARGEBACK_REVERSAL')"
          + " THEN COALESCE(l.expected_amount, l.net_amount)"
          + " ELSE COALESCE(l.expected_amount, l.gross_amount) END)"
          + " FROM settlement_lines l JOIN settlement_batches b ON b.tenant_id = l.tenant_id"
          + " AND b.id = l.batch_id WHERE l.tenant_id = ? AND l.batch_id = ?"
          + " GROUP BY COALESCE(l.store_id, b.store_id) ORDER BY store NULLS LAST";

  /** What importing a file came to. */
  public enum Imported {
    CREATED,
    /** The same request again: the stored batch is returned. */
    REPLAYED,
    /** This payout has been imported before, by another request. */
    ALREADY_IMPORTED
  }

  /** The batch, and how it came to be returned. */
  public record ImportResult(Imported outcome, Batch batch) {}

  /** What deciding a line came to. */
  public enum Resolved {
    DONE,
    BATCH_CLOSED,
    NOT_AN_EXCEPTION,
    /** Asked to accept a difference on a line that is linked to nothing. */
    NOTHING_LINKED
  }

  /**
   * What a line is pointed at by hand.
   *
   * @param held what this service holds for it, signed as the line is
   */
  public record Target(String type, UUID id, UUID storeId, BigDecimal held) {}

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      if (message.contains("uq_settlement_batches")) {
        return new ApiException(
            409,
            "SETTLEMENT_ALREADY_IMPORTED",
            "This payout is being imported by another request",
            List.of(),
            e);
      }
      return new ApiException(
          409,
          "SETTLEMENT_ALREADY_SETTLED",
          "Another settlement line has just taken the same payment: try again",
          List.of(),
          e);
    }
    return super.handleTxSqlException(what, e);
  }

  // ── import ──────────────────────────────────────────────────────────────────

  /**
   * Stores a batch with its lines, matches them, and reconciles it there and then when nothing is
   * left open.
   *
   * @param reconciled builds the event for a batch that reconciles at once, from the batch as
   *     stored and what it moves by store
   */
  public ImportResult importBatch(
      Batch b, List<ParsedLine> lines, BiFunction<Batch, List<StoreTotals>, OutboxRow> reconciled) {
    return inTx(
        c -> {
          Optional<Batch> replay = replayOf(c, b);
          if (replay.isPresent()) return new ImportResult(Imported.REPLAYED, replay.get());
          Optional<Batch> before = importedBefore(c, b);
          if (before.isPresent()) return new ImportResult(Imported.ALREADY_IMPORTED, before.get());
          insertBatch(c, b);
          insertLines(c, b, lines);
          match(c, b.tenantId(), b.id());
          if (countOpen(c, b.tenantId(), b.id()) == 0) {
            close(c, b.tenantId(), b.id(), Settlements.EXCEPTIONS, null, b.importedAt());
            Batch stored = load(c, b.tenantId(), b.id());
            insertOutbox(c, reconciled.apply(stored, storeTotals(c, b.tenantId(), b.id())));
            return new ImportResult(Imported.CREATED, stored);
          }
          return new ImportResult(Imported.CREATED, load(c, b.tenantId(), b.id()));
        },
        "import settlement");
  }

  private static Optional<Batch> replayOf(Connection c, Batch b) throws SQLException {
    if (b.idempotencyKey() == null) return Optional.empty();
    try (PreparedStatement ps =
        c.prepareStatement(BATCH_COLUMNS + " WHERE b.tenant_id = ? AND b.idempotency_key = ?")) {
      ps.setObject(1, b.tenantId());
      ps.setString(2, b.idempotencyKey());
      return one(ps);
    }
  }

  private static Optional<Batch> importedBefore(Connection c, Batch b) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            BATCH_COLUMNS + " WHERE b.tenant_id = ? AND b.provider = ? AND b.reference = ?")) {
      ps.setObject(1, b.tenantId());
      ps.setString(2, b.provider());
      ps.setString(3, b.reference());
      return one(ps);
    }
  }

  private static void insertBatch(Connection c, Batch b) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_BATCH)) {
      ps.setObject(1, b.id());
      ps.setObject(2, b.tenantId());
      ps.setObject(3, b.storeId());
      ps.setString(4, b.provider());
      ps.setString(5, b.reference());
      ps.setString(6, b.format());
      ps.setString(7, b.currency());
      ps.setObject(8, b.payoutDate());
      ps.setBigDecimal(9, b.declaredNet());
      ps.setBigDecimal(10, b.salesAmount());
      ps.setBigDecimal(11, b.refundAmount());
      ps.setBigDecimal(12, b.chargebackAmount());
      ps.setBigDecimal(13, b.feeAmount());
      ps.setBigDecimal(14, b.netAmount());
      ps.setInt(15, b.lineCount());
      ps.setString(16, Settlements.EXCEPTIONS);
      ps.setString(17, b.idempotencyKey());
      ps.setObject(18, b.importedBy());
      ps.setObject(19, b.importedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private static void insertLines(Connection c, Batch b, List<ParsedLine> lines)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_LINE)) {
      int pending = 0;
      int lineNo = 0;
      for (ParsedLine l : lines) {
        lineNo++;
        ps.setObject(1, Ids.newId());
        ps.setObject(2, b.tenantId());
        ps.setObject(3, b.id());
        ps.setInt(4, lineNo);
        ps.setString(5, l.type());
        ps.setString(6, l.reference());
        ps.setString(7, l.originalReference());
        ps.setBigDecimal(8, l.gross());
        ps.setBigDecimal(9, l.fee());
        ps.setBigDecimal(10, l.net());
        ps.setObject(11, l.occurredAt() == null ? null : l.occurredAt().atOffset(ZoneOffset.UTC));
        ps.setString(
            12,
            Settlements.FEE.equals(l.type()) ? Settlements.NOT_APPLICABLE : Settlements.UNMATCHED);
        ps.addBatch();
        pending++;
        if (pending == INSERT_CHUNK) {
          ps.executeBatch();
          pending = 0;
        }
      }
      if (pending > 0) ps.executeBatch();
    }
  }

  private static void match(Connection c, UUID tenantId, UUID batchId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(MATCH_SALES)) {
      untilNothingMoves(ps, tenantId, batchId);
    }
    try (PreparedStatement ps = c.prepareStatement(FLAG_DUPLICATE_SALES)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, batchId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps = c.prepareStatement(MATCH_REFUNDS_BY_REFERENCE)) {
      untilNothingMoves(ps, tenantId, batchId);
    }
    try (PreparedStatement ps = c.prepareStatement(MATCH_REFUNDS_BY_PAYMENT)) {
      untilNothingMoves(ps, tenantId, batchId);
    }
    try (PreparedStatement ps = c.prepareStatement(MATCH_CHARGEBACKS)) {
      untilNothingMoves(ps, tenantId, batchId);
    }
  }

  /** Runs a matching statement again while it still finds something, up to a bound. */
  private static void untilNothingMoves(PreparedStatement ps, UUID tenantId, UUID batchId)
      throws SQLException {
    ps.setObject(1, tenantId);
    ps.setObject(2, batchId);
    ps.setObject(3, tenantId);
    for (int pass = 0; pass < MATCH_PASSES; pass++) {
      if (ps.executeUpdate() == 0) return;
    }
  }

  private static int countOpen(Connection c, UUID tenantId, UUID batchId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(COUNT_OPEN)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, batchId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  /** Moves a batch to RECONCILED from the one status it may be closed from. */
  private static boolean close(
      Connection c, UUID tenantId, UUID batchId, String from, UUID actorId, Instant when)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE settlement_batches SET status = 'RECONCILED', reconciled_by = ?,"
                + " reconciled_at = ? WHERE tenant_id = ? AND id = ? AND status = ?")) {
      ps.setObject(1, actorId);
      ps.setObject(2, when.atOffset(ZoneOffset.UTC));
      ps.setObject(3, tenantId);
      ps.setObject(4, batchId);
      ps.setString(5, from);
      return ps.executeUpdate() == 1;
    }
  }

  private static List<StoreTotals> storeTotals(Connection c, UUID tenantId, UUID batchId)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(STORE_TOTALS)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, batchId);
      try (ResultSet rs = ps.executeQuery()) {
        List<StoreTotals> out = new ArrayList<>();
        while (rs.next()) {
          BigDecimal bank = rs.getBigDecimal(2);
          BigDecimal fees = rs.getBigDecimal(3);
          BigDecimal clearing = rs.getBigDecimal(4);
          out.add(
              new StoreTotals(
                  rs.getObject(1, UUID.class),
                  bank,
                  fees,
                  clearing,
                  bank.add(fees).subtract(clearing)));
        }
        return out;
      }
    }
  }

  // ── reads ───────────────────────────────────────────────────────────────────

  public Optional<Batch> find(UUID tenantId, UUID id) {
    return query(
            BATCH_COLUMNS + " WHERE b.tenant_id = ? AND b.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            SettlementRepository::mapBatch,
            "load settlement")
        .stream()
        .findFirst();
  }

  /** Payouts, the newest import first, optionally in one status. */
  public List<Batch> list(UUID tenantId, String status, Cursor.CreatedAtId after, int limit) {
    return query(
        BATCH_COLUMNS
            + " WHERE b.tenant_id = ?"
            + " AND (CAST(? AS VARCHAR) IS NULL OR b.status = ?)"
            + " AND (CAST(? AS TIMESTAMPTZ) IS NULL OR (b.imported_at, b.id) < (?, ?))"
            + " ORDER BY b.imported_at DESC, b.id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, status);
          ps.setString(3, status);
          OffsetDateTime at = after == null ? null : after.createdAt().atOffset(ZoneOffset.UTC);
          ps.setObject(4, at);
          ps.setObject(5, at);
          ps.setObject(6, after == null ? null : after.id());
          ps.setInt(7, limit);
        },
        SettlementRepository::mapBatch,
        "list settlements");
  }

  /**
   * A batch's lines in file order, after a line number; {@code onlyOpen} keeps to the ones still
   * waiting for a decision.
   */
  public List<Line> lines(
      UUID tenantId, UUID batchId, boolean onlyOpen, int afterLineNo, int limit) {
    return query(
        LINE_COLUMNS
            + " WHERE tenant_id = ? AND batch_id = ? AND line_no > ?"
            + " AND (? = false OR (resolution IS NULL"
            + " AND match_status IN ('UNMATCHED', 'AMOUNT_MISMATCH', 'DUPLICATE')))"
            + " ORDER BY line_no LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
          ps.setInt(3, afterLineNo);
          ps.setBoolean(4, onlyOpen);
          ps.setInt(5, limit);
        },
        SettlementRepository::mapLine,
        "list settlement lines");
  }

  public Optional<Line> line(UUID tenantId, UUID batchId, UUID lineId) {
    return query(
            LINE_COLUMNS + " WHERE tenant_id = ? AND batch_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, batchId);
              ps.setObject(3, lineId);
            },
            SettlementRepository::mapLine,
            "load settlement line")
        .stream()
        .findFirst();
  }

  private static final String TENDER_TARGET =
      "SELECT id, store_id, amount FROM payment_tenders WHERE tenant_id = ? AND id = ?"
          + " AND status = 'CAPTURED' AND method IN ('CARD', 'UPI', 'WALLET')";

  /** A refund is its own store's (every path writes it). */
  private static final String REFUND_TARGET =
      "SELECT id, store_id, -amount FROM refund_tenders WHERE tenant_id = ? AND id = ?";

  private static final String CHARGEBACK_TARGET =
      "SELECT id, store_id, CASE WHEN funds_withdrawn THEN -(amount + fee_amount) ELSE 0 END"
          + " FROM disputes WHERE tenant_id = ? AND id = ?";

  private static final String REVERSAL_TARGET =
      "SELECT id, store_id, CASE WHEN status = 'WON' THEN amount ELSE 0 END"
          + " FROM disputes WHERE tenant_id = ? AND id = ?";

  /**
   * What a line of this type may be pointed at by hand — a captured card payment, a refund, a
   * dispute — with what this service holds for it, signed as the line is.
   */
  public Optional<Target> target(UUID tenantId, String lineType, UUID id) {
    String statement =
        switch (lineType) {
          case Settlements.SALE -> TENDER_TARGET;
          case Settlements.REFUND -> REFUND_TARGET;
          case Settlements.CHARGEBACK -> CHARGEBACK_TARGET;
          case Settlements.CHARGEBACK_REVERSAL -> REVERSAL_TARGET;
          default -> null;
        };
    if (statement == null) return Optional.empty();
    return query(
            statement,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            rs ->
                new Target(
                    lineType,
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getBigDecimal(3)),
            "load settlement target")
        .stream()
        .findFirst();
  }

  /** Whether a settlement line other than this one already has the payment, refund or dispute. */
  public boolean settledElsewhere(UUID tenantId, UUID lineId, Target target) {
    return !query(
            "SELECT 1 FROM settlement_lines WHERE tenant_id = ? AND id <> ? AND type = ?"
                + " AND (matched_tender_id = ? OR matched_refund_id = ? OR matched_dispute_id = ?)"
                + " LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, lineId);
              ps.setString(3, target.type());
              ps.setObject(4, target.id());
              ps.setObject(5, target.id());
              ps.setObject(6, target.id());
            },
            rs -> rs.getInt(1),
            "check settled elsewhere")
        .isEmpty();
  }

  /**
   * Card payments captured before {@code before} that no settlement line has: money the acquirer
   * has yet to pay. The oldest first, because the oldest is the one to ask about.
   */
  public List<Unsettled> unsettled(
      UUID tenantId, UUID storeId, Instant before, Cursor.CreatedAtId after, int limit) {
    return query(
        "SELECT t.id, t.order_id, t.store_id, t.method, t.reference, t.amount, t.created_at"
            + " FROM payment_tenders t WHERE t.tenant_id = ? AND t.status = 'CAPTURED'"
            + " AND t.method IN ('CARD', 'UPI', 'WALLET') AND t.created_at < ?"
            + " AND (CAST(? AS UUID) IS NULL OR t.store_id = ?)"
            + " AND (CAST(? AS TIMESTAMPTZ) IS NULL OR (t.created_at, t.id) > (?, ?))"
            + " AND NOT EXISTS (SELECT 1 FROM settlement_lines s WHERE s.tenant_id = t.tenant_id"
            + " AND s.matched_tender_id = t.id AND s.type = 'SALE')"
            + " ORDER BY t.created_at, t.id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, before.atOffset(ZoneOffset.UTC));
          ps.setObject(3, storeId);
          ps.setObject(4, storeId);
          OffsetDateTime at = after == null ? null : after.createdAt().atOffset(ZoneOffset.UTC);
          ps.setObject(5, at);
          ps.setObject(6, at);
          ps.setObject(7, after == null ? null : after.id());
          ps.setInt(8, limit);
        },
        rs ->
            new Unsettled(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getString(5),
                rs.getBigDecimal(6),
                rs.getObject(7, OffsetDateTime.class).toInstant()),
        "list unsettled card payments");
  }

  // ── decisions ───────────────────────────────────────────────────────────────

  /**
   * Records a manager's decision on a line and brings the batch's status up to date, while the
   * batch is still open. The batch row is locked first, so a decision and a sign-off cannot pass
   * each other.
   *
   * @param target what the line is pointed at; null to keep what it has (accepting a difference) or
   *     to let go of it (unallocated)
   */
  public Resolved resolve(
      UUID tenantId,
      UUID batchId,
      UUID lineId,
      String resolution,
      Target target,
      String note,
      UUID actorId,
      Instant when) {
    return inTx(
        c -> {
          String status = lockStatus(c, tenantId, batchId);
          if (status == null || Settlements.RECONCILED.equals(status)) return Resolved.BATCH_CLOSED;
          int changed;
          if (Settlements.UNALLOCATED.equals(resolution)) {
            changed = letGo(c, tenantId, batchId, lineId, note, actorId, when);
          } else if (target != null) {
            changed = point(c, tenantId, batchId, lineId, resolution, target, note, actorId, when);
          } else {
            changed = acceptDifference(c, tenantId, batchId, lineId, note, actorId, when);
            if (changed == 0) return Resolved.NOTHING_LINKED;
          }
          if (changed == 0) return Resolved.NOT_AN_EXCEPTION;
          setStatus(
              c,
              tenantId,
              batchId,
              countOpen(c, tenantId, batchId) == 0 ? Settlements.READY : Settlements.EXCEPTIONS);
          return Resolved.DONE;
        },
        "resolve settlement line");
  }

  private static String lockStatus(Connection c, UUID tenantId, UUID batchId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT status FROM settlement_batches WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, batchId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static int letGo(
      Connection c, UUID tenantId, UUID batchId, UUID lineId, String note, UUID actorId, Instant at)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE settlement_lines SET resolution = 'UNALLOCATED', matched_tender_id = NULL,"
                + " matched_refund_id = NULL, matched_dispute_id = NULL, store_id = NULL,"
                + " expected_amount = NULL, resolved_by = ?, resolved_at = ?, note = ?"
                + " WHERE tenant_id = ? AND batch_id = ? AND id = ?"
                + " AND match_status IN ('UNMATCHED', 'AMOUNT_MISMATCH', 'DUPLICATE')")) {
      ps.setObject(1, actorId);
      ps.setObject(2, at.atOffset(ZoneOffset.UTC));
      ps.setString(3, note);
      ps.setObject(4, tenantId);
      ps.setObject(5, batchId);
      ps.setObject(6, lineId);
      return ps.executeUpdate();
    }
  }

  private static int point(
      Connection c,
      UUID tenantId,
      UUID batchId,
      UUID lineId,
      String resolution,
      Target target,
      String note,
      UUID actorId,
      Instant at)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE settlement_lines SET resolution = ?,"
                + " matched_tender_id = CASE WHEN type = 'SALE' THEN CAST(? AS UUID) END,"
                + " matched_refund_id = CASE WHEN type = 'REFUND' THEN CAST(? AS UUID) END,"
                + " matched_dispute_id = CASE WHEN type IN ('CHARGEBACK', 'CHARGEBACK_REVERSAL')"
                + " THEN CAST(? AS UUID) END,"
                + " store_id = ?, expected_amount = ?, resolved_by = ?, resolved_at = ?, note = ?"
                + " WHERE tenant_id = ? AND batch_id = ? AND id = ? AND type = ?"
                + " AND match_status IN ('UNMATCHED', 'AMOUNT_MISMATCH', 'DUPLICATE')")) {
      ps.setString(1, resolution);
      ps.setObject(2, target.id());
      ps.setObject(3, target.id());
      ps.setObject(4, target.id());
      ps.setObject(5, target.storeId());
      ps.setBigDecimal(
          6, Settlements.DIFFERENCE_ACCEPTED.equals(resolution) ? target.held() : null);
      ps.setObject(7, actorId);
      ps.setObject(8, at.atOffset(ZoneOffset.UTC));
      ps.setString(9, note);
      ps.setObject(10, tenantId);
      ps.setObject(11, batchId);
      ps.setObject(12, lineId);
      ps.setString(13, target.type());
      return ps.executeUpdate();
    }
  }

  private static int acceptDifference(
      Connection c, UUID tenantId, UUID batchId, UUID lineId, String note, UUID actorId, Instant at)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE settlement_lines SET resolution = 'DIFFERENCE_ACCEPTED', resolved_by = ?,"
                + " resolved_at = ?, note = ? WHERE tenant_id = ? AND batch_id = ? AND id = ?"
                + " AND match_status = 'AMOUNT_MISMATCH' AND expected_amount IS NOT NULL"
                + " AND COALESCE(matched_tender_id, matched_refund_id, matched_dispute_id)"
                + " IS NOT NULL")) {
      ps.setObject(1, actorId);
      ps.setObject(2, at.atOffset(ZoneOffset.UTC));
      ps.setString(3, note);
      ps.setObject(4, tenantId);
      ps.setObject(5, batchId);
      ps.setObject(6, lineId);
      return ps.executeUpdate();
    }
  }

  private static void setStatus(Connection c, UUID tenantId, UUID batchId, String status)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE settlement_batches SET status = ? WHERE tenant_id = ? AND id = ?"
                + " AND status <> 'RECONCILED'")) {
      ps.setString(1, status);
      ps.setObject(2, tenantId);
      ps.setObject(3, batchId);
      ps.executeUpdate();
    }
  }

  /**
   * Signs a batch off, if every exception in it has been decided, and tells the ledger.
   *
   * @return the batch as it now stands, or empty when it was not waiting for a sign-off
   */
  public Optional<Batch> reconcile(
      UUID tenantId,
      UUID batchId,
      UUID actorId,
      Instant when,
      BiFunction<Batch, List<StoreTotals>, OutboxRow> reconciled) {
    return inTx(
        c -> {
          if (!close(c, tenantId, batchId, Settlements.READY, actorId, when)) {
            return Optional.empty();
          }
          Batch stored = load(c, tenantId, batchId);
          insertOutbox(c, reconciled.apply(stored, storeTotals(c, tenantId, batchId)));
          return Optional.of(stored);
        },
        "reconcile settlement");
  }

  // ── mapping ─────────────────────────────────────────────────────────────────

  private static Batch load(Connection c, UUID tenantId, UUID batchId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(BATCH_COLUMNS + " WHERE b.tenant_id = ? AND b.id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, batchId);
      return one(ps)
          .orElseThrow(() -> new SQLException("settlement batch vanished inside its transaction"));
    }
  }

  private static Optional<Batch> one(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      return rs.next() ? Optional.of(mapBatch(rs)) : Optional.empty();
    }
  }

  private static Batch mapBatch(ResultSet rs) throws SQLException {
    OffsetDateTime reconciledAt = rs.getObject(22, OffsetDateTime.class);
    return new Batch(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getObject(3, UUID.class),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getObject(8, LocalDate.class),
        rs.getBigDecimal(9),
        rs.getBigDecimal(10),
        rs.getBigDecimal(11),
        rs.getBigDecimal(12),
        rs.getBigDecimal(13),
        rs.getBigDecimal(14),
        rs.getInt(15),
        rs.getInt(16),
        rs.getString(17),
        rs.getString(18),
        rs.getObject(19, UUID.class),
        rs.getObject(20, OffsetDateTime.class).toInstant(),
        rs.getObject(21, UUID.class),
        reconciledAt == null ? null : reconciledAt.toInstant());
  }

  private static Line mapLine(ResultSet rs) throws SQLException {
    OffsetDateTime occurred = rs.getObject(10, OffsetDateTime.class);
    OffsetDateTime resolved = rs.getObject(19, OffsetDateTime.class);
    return new Line(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getInt(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getBigDecimal(7),
        rs.getBigDecimal(8),
        rs.getBigDecimal(9),
        occurred == null ? null : occurred.toInstant(),
        rs.getString(11),
        rs.getObject(12, UUID.class),
        rs.getObject(13, UUID.class),
        rs.getObject(14, UUID.class),
        rs.getObject(15, UUID.class),
        rs.getBigDecimal(16),
        rs.getString(17),
        rs.getObject(18, UUID.class),
        resolved == null ? null : resolved.toInstant(),
        rs.getString(20));
  }
}
