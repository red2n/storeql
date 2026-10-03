package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.PaymentProposal;
import com.storeql.purchase.domain.PaymentRuns;
import com.storeql.purchase.domain.PaymentRuns.Item;
import com.storeql.purchase.domain.PaymentRuns.PaymentRun;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for supplier payment runs (17.10). Every query filters by tenant_id first.
 *
 * <p>The controls that stop a supplier being paid twice are here, in the database, rather than in
 * the service: a document sits in one open run at a time (a partial unique index), a run moves
 * between states only by a conditional UPDATE, and paying a run settles exactly the documents it
 * holds or nothing at all.
 */
@ApplicationScoped
public class PaymentRunRepository extends BaseOutboxRepository {

  private static final String RUN_COLUMNS =
      "id,tenant_id,reference,status,pay_up_to,payment_date,currency,total,proposed_by,"
          + "proposed_at,approved_by,approved_at,paid_by,paid_at,cancelled_by,cancelled_at,"
          + "cancel_reason,created_at";

  private static final String ITEM_COLUMNS =
      "id,tenant_id,run_id,supplier_id,store_id,item_type,document_id,reference,document_date,"
          + "due_date,amount";

  /**
   * The invoices a run may pay: matched or approved, posted, unpaid, in the run's currency, due by
   * the date, and not already held by an open run.
   */
  public List<PaymentProposal.Document> findInvoicesDue(
      UUID tenantId, String currency, LocalDate payUpTo, int limit) {
    return query(
        "SELECT si.id, si.supplier_id, po.store_id, si.invoice_number, si.invoice_date,"
            + "       si.due_date, si.gross_amount"
            + "  FROM supplier_invoices si"
            + "  JOIN purchase_orders po ON po.tenant_id = si.tenant_id AND po.id = si.po_id"
            + " WHERE si.tenant_id = ? AND si.status IN ('MATCHED','APPROVED')"
            + "   AND si.posted_at IS NOT NULL AND si.paid_at IS NULL"
            + "   AND si.currency = ? AND si.due_date <= ? AND si.gross_amount > 0"
            + "   AND NOT EXISTS (SELECT 1 FROM payment_run_items i"
            + "                    WHERE i.tenant_id = si.tenant_id AND i.item_type = 'INVOICE'"
            + "                      AND i.document_id = si.id AND i.open)"
            + " ORDER BY si.due_date, si.id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, currency);
          ps.setObject(3, payUpTo);
          ps.setInt(4, limit);
        },
        rs ->
            new PaymentProposal.Document(
                PaymentProposal.INVOICE,
                rs.getObject("id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("invoice_number"),
                rs.getObject("invoice_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class),
                rs.getBigDecimal("gross_amount")),
        "find invoices due");
  }

  /** Supplier credit notes recorded against returns and not yet offset against a payment. */
  public List<PaymentProposal.Document> findUnallocatedCredits(
      UUID tenantId, String currency, int limit) {
    return query(
        "SELECT vr.id, vr.supplier_id, vr.store_id, vr.credit_note_number, vr.credit_note_date,"
            + "       vr.credit_amount"
            + "  FROM vendor_returns vr"
            + " WHERE vr.tenant_id = ? AND vr.status = 'CREDITED' AND vr.allocated_at IS NULL"
            + "   AND vr.currency = ? AND vr.credit_amount > 0"
            + "   AND NOT EXISTS (SELECT 1 FROM payment_run_items i"
            + "                    WHERE i.tenant_id = vr.tenant_id AND i.item_type = 'CREDIT_NOTE'"
            + "                      AND i.document_id = vr.id AND i.open)"
            + " ORDER BY vr.credit_note_date, vr.id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, currency);
          ps.setInt(3, limit);
        },
        rs ->
            new PaymentProposal.Document(
                PaymentProposal.CREDIT_NOTE,
                rs.getObject("id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("credit_note_number"),
                rs.getObject("credit_note_date", LocalDate.class),
                null,
                rs.getBigDecimal("credit_amount")),
        "find unallocated credit notes");
  }

  /** The suppliers with these ids, in this tenant. */
  public List<Supplier> findSuppliers(UUID tenantId, Collection<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return query(
        "SELECT "
            + PurchaseRepository.SUPPLIER_COLUMNS
            + " FROM suppliers WHERE tenant_id = ? AND id = ANY(?)",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
        },
        PurchaseRepository::mapSupplier,
        "find suppliers for a payment run");
  }

  /**
   * Stores a proposed run and the documents it holds, together.
   *
   * @throws ApiException 409 {@code PURCHASE_PAYMENT_RUN_CONFLICT} when another run took one of the
   *     documents between the read and this write
   */
  public void insertRun(PaymentRun run, List<Item> items) {
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO payment_runs (id,tenant_id,reference,status,pay_up_to,payment_date,"
                      + " currency,total,proposed_by,proposed_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, run.id());
            ps.setObject(2, run.tenantId());
            ps.setString(3, run.reference());
            ps.setString(4, run.status());
            ps.setObject(5, run.payUpTo());
            ps.setObject(6, run.paymentDate());
            ps.setString(7, run.currency());
            ps.setBigDecimal(8, run.total());
            ps.setObject(9, run.proposedBy());
            ps.setObject(10, run.proposedAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO payment_run_items ("
                      + ITEM_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Item i : items) {
              ps.setObject(1, i.id());
              ps.setObject(2, i.tenantId());
              ps.setObject(3, i.runId());
              ps.setObject(4, i.supplierId());
              ps.setObject(5, i.storeId());
              ps.setString(6, i.itemType());
              ps.setObject(7, i.documentId());
              ps.setString(8, i.reference());
              ps.setObject(9, i.documentDate());
              ps.setObject(10, i.dueDate());
              ps.setBigDecimal(11, i.amount());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "propose payment run");
  }

  public Optional<PaymentRun> findRun(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT " + RUN_COLUMNS + " FROM payment_runs WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PaymentRunRepository::mapRun,
            "find payment run");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Runs newest first, optionally in one status. */
  public List<PaymentRun> findRuns(UUID tenantId, String status, int limit) {
    return query(
        "SELECT "
            + RUN_COLUMNS
            + " FROM payment_runs WHERE tenant_id = ?"
            + (status == null ? "" : " AND status = ?")
            // Newest first by id, not by created_at. The id is UUIDv7, minted once when the run is
            // created and never rewritten, so it cannot wind backwards; created_at is filled by a
            // column DEFAULT of now(), which is Postgres's *transaction start* time, so two
            // concurrent transactions can stamp rows in an order that does not match the order they
            // were created in. A live run turned up a row whose cancelled_at preceded its own
            // created_at by two seconds and sorted above a newer run because of it. Ordering by the
            // id uses the very property UUIDv7 was chosen for.
            + " ORDER BY id DESC LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        PaymentRunRepository::mapRun,
        "list payment runs");
  }

  /** The documents of these runs, in one read. */
  public List<Item> findItems(UUID tenantId, Collection<UUID> runIds) {
    if (runIds.isEmpty()) return List.of();
    return query(
        "SELECT "
            + ITEM_COLUMNS
            + " FROM payment_run_items WHERE tenant_id = ? AND run_id = ANY(?)"
            + " ORDER BY run_id, supplier_id, item_type DESC, due_date, reference",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", runIds.toArray()));
        },
        rs ->
            new Item(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("item_type"),
                rs.getObject("document_id", UUID.class),
                rs.getString("reference"),
                rs.getObject("document_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class),
                rs.getBigDecimal("amount")),
        "list payment run items");
  }

  /**
   * PROPOSED to APPROVED, keeping each payee's bank-details version on the same transaction: the
   * run is approved for the accounts as they stand now, and {@link #payeesChangedSinceApproval}
   * finds any that move on later. {@code false} when the run was not PROPOSED.
   */
  public boolean approve(UUID tenantId, UUID id, UUID approvedBy) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE payment_runs SET status = ?, approved_by = ?, approved_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, PaymentRuns.APPROVED);
            ps.setObject(2, approvedBy);
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            ps.setString(5, PaymentRuns.PROPOSED);
            if (ps.executeUpdate() != 1) return false;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO payment_run_payees"
                      + " (tenant_id, run_id, supplier_id, bank_details_version)"
                      + " SELECT DISTINCT s.tenant_id, i.run_id, s.id, s.bank_details_version"
                      + " FROM payment_run_items i"
                      + " JOIN suppliers s ON s.tenant_id = i.tenant_id AND s.id = i.supplier_id"
                      + " WHERE i.tenant_id = ? AND i.run_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            ps.executeUpdate();
          }
          return true;
        },
        "approve payment run");
  }

  /**
   * The payees of an approved run whose bank details changed after it was approved: their version
   * has moved on from the one the approval kept. Judged by counting changes, never by comparing the
   * service's clock with the database's.
   */
  public java.util.Set<UUID> payeesChangedSinceApproval(UUID tenantId, UUID runId) {
    return new java.util.HashSet<>(
        query(
            "SELECT p.supplier_id FROM payment_run_payees p"
                + " JOIN suppliers s ON s.tenant_id = p.tenant_id AND s.id = p.supplier_id"
                + " WHERE p.tenant_id = ? AND p.run_id = ?"
                + " AND s.bank_details_version <> p.bank_details_version",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, runId);
            },
            rs -> rs.getObject("supplier_id", UUID.class),
            "payees changed since approval"));
  }

  /**
   * PROPOSED or APPROVED to CANCELLED, releasing the documents for the next run.
   *
   * @return {@code false} when the run was already paid or cancelled
   */
  public boolean cancel(UUID tenantId, UUID id, UUID cancelledBy, String reason) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE payment_runs SET status = ?, cancelled_by = ?, cancelled_at = now(),"
                      + " cancel_reason = ? WHERE tenant_id = ? AND id = ? AND status IN (?,?)")) {
            ps.setString(1, PaymentRuns.CANCELLED);
            ps.setObject(2, cancelledBy);
            ps.setString(3, reason);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.setString(6, PaymentRuns.PROPOSED);
            ps.setString(7, PaymentRuns.APPROVED);
            if (ps.executeUpdate() == 0) return false;
          }
          closeItems(c, tenantId, id);
          return true;
        },
        "cancel payment run");
  }

  /**
   * Pays an approved run: APPROVED to PAID, every invoice it holds settled, every credit note it
   * holds allocated, the ledger posted and the remittance advices queued — all in one transaction.
   *
   * @param invoices how many invoices the run holds; the settlement must touch exactly these
   * @param credits how many credit notes it holds
   * @return {@code false} when the run was not APPROVED, because another call paid it first
   * @throws ApiException 409 {@code PURCHASE_PAYMENT_RUN_STALE} when a document changed under the
   *     run, and nothing is written
   */
  public boolean pay(
      UUID tenantId,
      UUID id,
      UUID paidBy,
      int invoices,
      int credits,
      List<NominalLedgerEntry> posting,
      List<OutboxRow> events) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE payment_runs SET status = ?, paid_by = ?, paid_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, PaymentRuns.PAID);
            ps.setObject(2, paidBy);
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            ps.setString(5, PaymentRuns.APPROVED);
            if (ps.executeUpdate() == 0) return false;
          }
          int settled =
              settle(
                  c,
                  "UPDATE supplier_invoices SET paid_at = now(), payment_run_id = ?"
                      + " WHERE tenant_id = ? AND paid_at IS NULL AND status IN ('MATCHED','APPROVED')"
                      + "   AND id IN (SELECT document_id FROM payment_run_items"
                      + "               WHERE tenant_id = ? AND run_id = ? AND item_type = 'INVOICE')",
                  tenantId,
                  id);
          int allocated =
              settle(
                  c,
                  "UPDATE vendor_returns SET allocated_at = now(), allocated_run_id = ?"
                      + " WHERE tenant_id = ? AND allocated_at IS NULL AND status = 'CREDITED'"
                      + "   AND id IN (SELECT document_id FROM payment_run_items"
                      + "               WHERE tenant_id = ? AND run_id = ? AND item_type = 'CREDIT_NOTE')",
                  tenantId,
                  id);
          if (settled != invoices || allocated != credits) {
            throw ApiException.conflict(
                "PURCHASE_PAYMENT_RUN_STALE",
                "a document in this run changed since it was proposed; cancel it and propose again");
          }
          closeItems(c, tenantId, id);
          LedgerWriter.insert(c, posting);
          for (OutboxRow e : events) insertOutbox(c, e);
          return true;
        },
        "pay payment run");
  }

  private static int settle(Connection c, String sql, UUID tenantId, UUID runId)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, runId);
      ps.setObject(2, tenantId);
      ps.setObject(3, tenantId);
      ps.setObject(4, runId);
      return ps.executeUpdate();
    }
  }

  private static void closeItems(Connection c, UUID tenantId, UUID runId) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "UPDATE payment_run_items SET open = false WHERE tenant_id = ? AND run_id = ? AND open")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, runId);
      ps.executeUpdate();
    }
  }

  /** Two proposals racing for one document: the second is told to propose again. */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    for (SQLException s = e; s != null; s = s.getNextException()) {
      if (UNIQUE_VIOLATION.equals(s.getSQLState())) {
        return new ApiException(
            409,
            "PURCHASE_PAYMENT_RUN_CONFLICT",
            "another payment run took some of these documents first; propose again",
            List.of(),
            e);
      }
    }
    return super.handleTxSqlException(what, e);
  }

  private static PaymentRun mapRun(ResultSet rs) throws SQLException {
    return new PaymentRun(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("reference"),
        rs.getString("status"),
        rs.getObject("pay_up_to", LocalDate.class),
        rs.getObject("payment_date", LocalDate.class),
        rs.getString("currency"),
        rs.getBigDecimal("total"),
        rs.getObject("proposed_by", UUID.class),
        instant(rs, "proposed_at"),
        rs.getObject("approved_by", UUID.class),
        instant(rs, "approved_at"),
        rs.getObject("paid_by", UUID.class),
        instant(rs, "paid_at"),
        rs.getObject("cancelled_by", UUID.class),
        instant(rs, "cancelled_at"),
        rs.getString("cancel_reason"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
