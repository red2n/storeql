package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.GoodsReceipt;
import com.storeql.purchase.domain.Domain.GoodsReceiptLine;
import com.storeql.purchase.domain.Domain.IntercompanyInvoice;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.Domain.PurchaseOrderLineProgress;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.ThreeWayMatch;
import com.storeql.purchase.domain.Totals;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
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

/** JDBC persistence for purchase-svc. Every tenant query filters by tenant_id first. */
@ApplicationScoped
public class PurchaseRepository extends BaseOutboxRepository {

  // ── Suppliers ─────────────────────────────────────────────────────────────────

  /**
   * Inserts a supplier.
   *
   * @param s the supplier to persist; its {@code id} must already be a UUIDv7
   * @return the supplier as stored
   */
  public Supplier createSupplier(Supplier s) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO suppliers"
                      + " (id,tenant_id,name,vat_number,vat_registered,country_code,currency,payment_terms_days,"
                      + "  remittance_email,bank_account_name,bank_sort_code,bank_account_number,bank_iban,"
                      + "  bank_bic,bank_details_changed_at,bank_details_changed_by,einvoice_scheme,"
                      + "  einvoice_id,lead_time_days)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setString(3, s.name());
            ps.setString(4, s.vatNumber());
            ps.setBoolean(5, s.vatRegistered());
            ps.setString(6, s.countryCode());
            ps.setString(7, s.currency());
            ps.setInt(8, s.paymentTermsDays());
            bindPaymentFields(ps, 9, s);
            ps.setString(17, s.einvoiceScheme());
            ps.setString(18, s.einvoiceId());
            bindLeadTime(ps, 19, s);
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            throw supplierConflict(sqle);
          }
          return s;
        },
        "create supplier");
  }

  /**
   * What a failed supplier write means: the name or the e-invoicing address belongs to another
   * supplier, or anything else as it was.
   */
  private static java.sql.SQLException supplierConflict(java.sql.SQLException sqle) {
    if (!UNIQUE_VIOLATION.equals(sqle.getSQLState())) return sqle;
    if (String.valueOf(sqle.getMessage()).contains("uq_suppliers_einvoice_address"))
      throw new ApiException(
          409,
          "PURCHASE_SUPPLIER_EINVOICE_ADDRESS_TAKEN",
          "another supplier already sends e-invoices from that electronic address",
          List.of(),
          sqle);
    throw new ApiException(
        409,
        "PURCHASE_SUPPLIER_DUPLICATE",
        "Supplier with that name already exists for this tenant",
        List.of(),
        sqle);
  }

  /**
   * Lists a tenant's suppliers.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param limit maximum rows
   * @return the suppliers
   */
  public List<Supplier> findSuppliers(UUID tenantId, int limit) {
    return query(
        "SELECT " + SUPPLIER_COLUMNS + " FROM suppliers WHERE tenant_id=? ORDER BY name LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        PurchaseRepository::mapSupplier,
        "find suppliers");
  }

  /**
   * Looks a supplier up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the supplier to fetch
   * @return the supplier, or empty when it does not exist in this tenant
   */
  public Optional<Supplier> findSupplier(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT " + SUPPLIER_COLUMNS + " FROM suppliers WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PurchaseRepository::mapSupplier,
            "find supplier");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Replaces a supplier's master data in place. The id, tenant and creation time never change.
   *
   * @param s the supplier as it should now read
   * @return true when a row was updated; false when no such supplier exists in the tenant
   * @throws ApiException {@code PURCHASE_SUPPLIER_DUPLICATE} (409) when the new name is taken
   */
  public boolean updateSupplier(Supplier s) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE suppliers SET name=?, vat_number=?, vat_registered=?, country_code=?,"
                      + " currency=?, payment_terms_days=?, remittance_email=?, bank_account_name=?,"
                      + " bank_sort_code=?, bank_account_number=?, bank_iban=?, bank_bic=?,"
                      + " bank_details_changed_at=?, bank_details_changed_by=?, einvoice_scheme=?,"
                      + " einvoice_id=?, lead_time_days=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=?")) {
            ps.setString(1, s.name());
            ps.setString(2, s.vatNumber());
            ps.setBoolean(3, s.vatRegistered());
            ps.setString(4, s.countryCode());
            ps.setString(5, s.currency());
            ps.setInt(6, s.paymentTermsDays());
            bindPaymentFields(ps, 7, s);
            ps.setString(15, s.einvoiceScheme());
            ps.setString(16, s.einvoiceId());
            bindLeadTime(ps, 17, s);
            ps.setObject(18, s.tenantId());
            ps.setObject(19, s.id());
            return ps.executeUpdate() > 0;
          } catch (java.sql.SQLException sqle) {
            throw supplierConflict(sqle);
          }
        },
        "update supplier");
  }

  /**
   * How many purchase orders against a supplier are still open — drafted, awaiting approval,
   * submitted or part-received. Each of them is denominated in the supplier's currency.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param supplierId the supplier
   * @return the count of open orders
   */
  public int countOpenPurchaseOrders(UUID tenantId, UUID supplierId) {
    var rows =
        query(
            "SELECT COUNT(*) AS n FROM purchase_orders WHERE tenant_id=? AND supplier_id=?"
                + " AND status IN ('DRAFT','PENDING_APPROVAL','SUBMITTED','PARTIALLY_RECEIVED')",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, supplierId);
            },
            rs -> rs.getInt("n"),
            "count open purchase orders");
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  static final String SUPPLIER_COLUMNS =
      "id,tenant_id,name,vat_number,vat_registered,country_code,currency,payment_terms_days,"
          + "created_at,updated_at,remittance_email,bank_account_name,bank_sort_code,"
          + "bank_account_number,bank_iban,bank_bic,bank_details_changed_at,bank_details_changed_by,"
          + "einvoice_scheme,einvoice_id,lead_time_days";

  /**
   * Binds the eight payment fields — remittance email, bank details, the change stamp — from {@code
   * at}.
   */
  private static void bindPaymentFields(java.sql.PreparedStatement ps, int at, Supplier s)
      throws SQLException {
    ps.setString(at, s.remittanceEmail());
    ps.setString(at + 1, s.bankAccountName());
    ps.setString(at + 2, s.bankSortCode());
    ps.setString(at + 3, s.bankAccountNumber());
    ps.setString(at + 4, s.bankIban());
    ps.setString(at + 5, s.bankBic());
    ps.setObject(at + 6, toOdt(s.bankDetailsChangedAt()));
    ps.setObject(at + 7, s.bankDetailsChangedBy());
  }

  private static void bindLeadTime(java.sql.PreparedStatement ps, int at, Supplier s)
      throws SQLException {
    if (s.leadTimeDays() == null) ps.setNull(at, java.sql.Types.INTEGER);
    else ps.setInt(at, s.leadTimeDays());
  }

  static Supplier mapSupplier(ResultSet rs) throws SQLException {
    OffsetDateTime changed = rs.getObject("bank_details_changed_at", OffsetDateTime.class);
    // wasNull speaks of the last column read: asked right after the one that may be null.
    int quoted = rs.getInt("lead_time_days");
    Integer leadTimeDays = rs.wasNull() ? null : quoted;
    return new Supplier(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("vat_number"),
        rs.getBoolean("vat_registered"),
        rs.getString("country_code"),
        rs.getString("currency"),
        rs.getInt("payment_terms_days"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getString("remittance_email"),
        rs.getString("bank_account_name"),
        rs.getString("bank_sort_code"),
        rs.getString("bank_account_number"),
        rs.getString("bank_iban"),
        rs.getString("bank_bic"),
        changed == null ? null : changed.toInstant(),
        rs.getObject("bank_details_changed_by", UUID.class),
        rs.getString("einvoice_scheme"),
        rs.getString("einvoice_id"),
        leadTimeDays);
  }

  // ── Purchase Orders ───────────────────────────────────────────────────────────

  /**
   * Inserts a DRAFT purchase order and its outbox event in one transaction.
   *
   * @param po the purchase order to persist; its {@code id} must already be a UUIDv7
   * @param event the outbox row to commit alongside the insert
   * @return the purchase order as stored
   */
  public PurchaseOrder createPurchaseOrder(PurchaseOrder po, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO purchase_orders"
                      + " (id,tenant_id,supplier_id,store_id,status,currency,"
                      + "  total_net,total_vat,total_gross,expected_delivery,created_by,source,"
                      + "  ownership,sales_order_id,ship_to,duty_status)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, po.id());
            ps.setObject(2, po.tenantId());
            ps.setObject(3, po.supplierId());
            ps.setObject(4, po.storeId());
            ps.setString(5, po.status());
            ps.setString(6, po.currency());
            ps.setBigDecimal(7, po.totalNet());
            ps.setBigDecimal(8, po.totalVat());
            ps.setBigDecimal(9, po.totalGross());
            ps.setObject(10, po.expectedDelivery());
            ps.setObject(11, po.createdBy());
            ps.setString(12, po.source() == null ? Domain.PO_SOURCE_MANUAL : po.source());
            ps.setString(13, po.ownership() == null ? Domain.PO_OWNERSHIP_OWNED : po.ownership());
            ps.setObject(14, po.salesOrderId());
            ps.setString(15, po.shipTo());
            ps.setString(16, po.dutyStatus() == null ? Domain.PO_DUTY_PAID : po.dutyStatus());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return po;
        },
        "create purchase order");
  }

  /**
   * Lists a tenant's purchase orders.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param limit maximum rows
   * @return the purchase orders
   */
  public List<PurchaseOrder> findPurchaseOrders(UUID tenantId, int limit) {
    return query(
        "SELECT id,tenant_id,supplier_id,store_id,status,currency,"
            + "total_net,total_vat,total_gross,expected_delivery,created_at,updated_at,cancelled_at,"
            + "cancelled_reason,closed_at,closed_reason,created_by,approved_by,approved_at,source,"
            + "fx_rate,total_net_home,home_currency,ownership,sales_order_id,ship_to,duty_status"
            + " FROM purchase_orders WHERE tenant_id=? ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        this::mapPurchaseOrder,
        "find purchase orders");
  }

  /**
   * Looks a purchase order up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the purchase order to fetch
   * @return the purchase order, or empty when it does not exist in this tenant
   */
  public Optional<PurchaseOrder> findPurchaseOrder(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id,tenant_id,supplier_id,store_id,status,currency,"
                + "total_net,total_vat,total_gross,expected_delivery,created_at,updated_at,cancelled_at,"
                + "cancelled_reason,closed_at,closed_reason,created_by,approved_by,approved_at,source,"
                + "fx_rate,total_net_home,home_currency,ownership,sales_order_id,ship_to,duty_status"
                + " FROM purchase_orders WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapPurchaseOrder,
            "find purchase order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Moves a DRAFT order to {@code SUBMITTED} or {@code PENDING_APPROVAL} and records the submission
   * in the approval trail, atomically.
   *
   * <p>The guard is in the {@code WHERE} clause rather than in a preceding read, on the same
   * reasoning as {@link #cancelPurchaseOrder}: two concurrent submits, or a submit racing a cancel,
   * must not both win. Whichever commits first moves the row out of DRAFT and the other sees zero
   * rows.
   *
   * @param tenantId the owning tenant
   * @param id the order to submit
   * @param status the state this submission lands in
   * @param trail the {@code REQUESTED} row recording what was submitted and under whose authority
   * @return {@code true} if this call submitted the order; {@code false} if it was not DRAFT
   */
  public boolean submitPurchaseOrder(
      UUID tenantId, UUID id, String status, Domain.PurchaseOrderApproval trail) {
    return submitPurchaseOrder(tenantId, id, status, trail, NO_ANNOUNCEMENT);
  }

  /** What a status change says about the order's cross-dock allocations, given them. */
  public static final java.util.function.Function<List<Domain.LineAllocation>, Optional<OutboxRow>>
      NO_ANNOUNCEMENT = a -> Optional.empty();

  /**
   * Writes what the announcement makes of the order's cross-dock allocations, on the caller's
   * transaction: a submitted order's snapshot, or an empty one when the order stops being on its
   * way. Nothing when the order has no allocations.
   */
  private void announceAllocationsTx(
      Connection c,
      UUID tenantId,
      UUID poId,
      java.util.function.Function<List<Domain.LineAllocation>, Optional<OutboxRow>> announce)
      throws SQLException {
    List<Domain.LineAllocation> allocations = CrossDockRepository.allocationsTx(c, tenantId, poId);
    if (allocations.isEmpty()) return;
    Optional<OutboxRow> row = announce.apply(allocations);
    if (row.isPresent()) insertOutbox(c, row.get());
  }

  /** As above, announcing the order's cross-dock allocations on the same transaction. */
  public boolean submitPurchaseOrder(
      UUID tenantId,
      UUID id,
      String status,
      Domain.PurchaseOrderApproval trail,
      java.util.function.Function<List<Domain.LineAllocation>, Optional<OutboxRow>> announce) {
    return inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  // The moment the order went to the supplier, kept for the lead time it is
                  // measured by; an order that waits for approval is stamped when approved.
                  "UPDATE purchase_orders SET status=?, updated_at=now(),"
                      + " submitted_at = CASE WHEN ?='SUBMITTED' THEN now() ELSE submitted_at END"
                      + " WHERE tenant_id=? AND id=? AND status='DRAFT'")) {
            ps.setString(1, status);
            ps.setString(2, status);
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            rows = ps.executeUpdate();
          }
          if (rows == 0) return false;
          insertApproval(c, trail);
          announceAllocationsTx(c, tenantId, id, announce);
          return true;
        },
        "submit purchase order");
  }

  /**
   * Records an approval decision and moves the order accordingly, atomically.
   *
   * <p>An approval takes the order to {@code SUBMITTED}; a rejection returns it to {@code DRAFT} so
   * it can be corrected and resubmitted, and clears nothing else — the trail keeps the rejection,
   * which is the point of it being append-only.
   *
   * @param tenantId the owning tenant
   * @param id the order being decided on
   * @param approve true to approve, false to reject
   * @param decision the trail row, already carrying the decider, their authority and the figure
   * @return {@code true} if this call decided the order; {@code false} if it was not awaiting one
   */
  public boolean decidePurchaseOrder(
      UUID tenantId, UUID id, boolean approve, Domain.PurchaseOrderApproval decision) {
    return decidePurchaseOrder(tenantId, id, approve, decision, NO_ANNOUNCEMENT);
  }

  /** As above, announcing the order's cross-dock allocations on the same transaction. */
  public boolean decidePurchaseOrder(
      UUID tenantId,
      UUID id,
      boolean approve,
      Domain.PurchaseOrderApproval decision,
      java.util.function.Function<List<Domain.LineAllocation>, Optional<OutboxRow>> announce) {
    return inTx(
        c -> {
          int rows;
          String sql =
              approve
                  ? "UPDATE purchase_orders SET status='SUBMITTED', approved_by=?, approved_at=now(),"
                      + " submitted_at=now(),"
                      + " updated_at=now() WHERE tenant_id=? AND id=? AND status='PENDING_APPROVAL'"
                  : "UPDATE purchase_orders SET status='DRAFT', approved_by=NULL, approved_at=NULL,"
                      + " updated_at=now() WHERE tenant_id=? AND id=? AND status='PENDING_APPROVAL'";
          try (var ps = c.prepareStatement(sql)) {
            int i = 1;
            if (approve) ps.setObject(i++, decision.decidedBy());
            ps.setObject(i++, tenantId);
            ps.setObject(i, id);
            rows = ps.executeUpdate();
          }
          if (rows == 0) return false;
          insertApproval(c, decision);
          announceAllocationsTx(c, tenantId, id, announce);
          return true;
        },
        approve ? "approve purchase order" : "reject purchase order");
  }

  private void insertApproval(Connection c, Domain.PurchaseOrderApproval a) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO purchase_order_approvals"
                + " (id,tenant_id,po_id,decision,total_net,currency,authority,decided_by,"
                + "  decided_role,reason)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, a.id());
      ps.setObject(2, a.tenantId());
      ps.setObject(3, a.poId());
      ps.setString(4, a.decision());
      ps.setBigDecimal(5, a.totalNet());
      ps.setString(6, a.currency());
      ps.setBigDecimal(7, a.authority());
      ps.setObject(8, a.decidedBy());
      ps.setString(9, a.decidedRole());
      ps.setString(10, a.reason());
      ps.executeUpdate();
    }
  }

  /**
   * The order's approval history, newest first. Append-only, so this is the complete record of
   * every submission and decision the order has been through.
   *
   * @param tenantId the owning tenant — first condition (golden rule #3)
   * @param poId the order
   * @return every trail row for that order
   */
  public List<Domain.PurchaseOrderApproval> findApprovals(UUID tenantId, UUID poId) {
    return query(
        "SELECT id,tenant_id,po_id,decision,total_net,currency,authority,decided_by,decided_role,"
            + "reason,decided_at"
            + " FROM purchase_order_approvals WHERE tenant_id=? AND po_id=?"
            + " ORDER BY decided_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        rs ->
            new Domain.PurchaseOrderApproval(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("po_id", UUID.class),
                rs.getString("decision"),
                rs.getBigDecimal("total_net"),
                rs.getString("currency"),
                rs.getBigDecimal("authority"),
                rs.getObject("decided_by", UUID.class),
                rs.getString("decided_role"),
                rs.getString("reason"),
                rs.getObject("decided_at", OffsetDateTime.class).toInstant()),
        "find purchase order approvals");
  }

  /**
   * Sets a purchase order's status unconditionally.
   *
   * <p>Applies no state-machine check of its own — callers that need one (submit, cancel, close)
   * use the guarded methods that fold the check into the same statement.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the purchase order to update
   * @param status the status to set
   */
  public void updatePurchaseOrderStatus(UUID tenantId, UUID id, String status) {
    exec(
        "UPDATE purchase_orders SET status=?, updated_at=now() WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "update po status");
  }

  /**
   * Cancels a purchase order and publishes the event in one transaction (golden rule #6).
   *
   * <p>The guard lives in the {@code WHERE} clause rather than in a preceding read, so two
   * concurrent cancels -- or a cancel racing a goods receipt -- cannot both win: whichever commits
   * first moves the row out of the cancellable set and the other sees zero rows updated. A
   * check-then-act in the service layer would leave exactly that window open.
   *
   * @param tenantId the owning tenant
   * @param id the purchase order to cancel
   * @param reason caller-supplied cancellation reason, already validated as non-blank
   * @param event the {@code PurchaseOrderCancelled} outbox row, written in the same transaction
   * @return {@code true} if this call cancelled the order; {@code false} if it was already RECEIVED
   *     or CANCELLED and therefore not cancellable
   */
  public boolean cancelPurchaseOrder(UUID tenantId, UUID id, String reason, OutboxRow event) {
    return cancelPurchaseOrder(tenantId, id, reason, event, NO_ANNOUNCEMENT);
  }

  /** As above, announcing the order's cross-dock allocations on the same transaction. */
  public boolean cancelPurchaseOrder(
      UUID tenantId,
      UUID id,
      String reason,
      OutboxRow event,
      java.util.function.Function<List<Domain.LineAllocation>, Optional<OutboxRow>> announce) {
    return inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE purchase_orders SET status='CANCELLED', cancelled_at=now(),"
                      + " cancelled_reason=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status IN ('DRAFT','SUBMITTED')")) {
            ps.setString(1, reason);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            rows = ps.executeUpdate();
          }
          if (rows == 0) return false;
          insertOutbox(c, event);
          announceAllocationsTx(c, tenantId, id, announce);
          return true;
        },
        "cancel purchase order");
  }

  private PurchaseOrder mapPurchaseOrder(ResultSet rs) throws SQLException {
    return new PurchaseOrder(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getString("currency"),
        rs.getBigDecimal("total_net"),
        rs.getBigDecimal("total_vat"),
        rs.getBigDecimal("total_gross"),
        rs.getObject("expected_delivery", LocalDate.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getObject("cancelled_at", OffsetDateTime.class) == null
            ? null
            : rs.getObject("cancelled_at", OffsetDateTime.class).toInstant(),
        rs.getString("cancelled_reason"),
        rs.getObject("closed_at", OffsetDateTime.class) == null
            ? null
            : rs.getObject("closed_at", OffsetDateTime.class).toInstant(),
        rs.getString("closed_reason"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("approved_by", UUID.class),
        rs.getObject("approved_at", OffsetDateTime.class) == null
            ? null
            : rs.getObject("approved_at", OffsetDateTime.class).toInstant(),
        rs.getString("source"),
        // Kept at the column's ten decimals; read back as it was set (0.79, not 0.7900000000).
        rs.getBigDecimal("fx_rate") == null
            ? null
            : rs.getBigDecimal("fx_rate").stripTrailingZeros(),
        rs.getBigDecimal("total_net_home"),
        rs.getString("home_currency"),
        rs.getString("ownership"),
        rs.getObject("sales_order_id", UUID.class),
        rs.getString("ship_to"),
        rs.getString("duty_status"));
  }

  /**
   * Keeps the translation a spend decision was made against (03.x): the rate and the net in the
   * home currency. A rate moves; the record of what was decided must not.
   */
  public void recordTranslation(
      UUID tenantId, UUID id, BigDecimal fxRate, BigDecimal totalNetHome, String homeCurrency) {
    exec(
        "UPDATE purchase_orders SET fx_rate=?, total_net_home=?, home_currency=?"
            + " WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setBigDecimal(1, fxRate);
          ps.setBigDecimal(2, totalNetHome);
          ps.setString(3, homeCurrency);
          ps.setObject(4, tenantId);
          ps.setObject(5, id);
        },
        "record purchase order translation");
  }

  // ── PO Lines ──────────────────────────────────────────────────────────────────

  /**
   * Appends a line and restates the order's totals from every line it now has, atomically (SJ-D22).
   *
   * <p>The two halves must not be separable. A committed line whose order still shows the old total
   * is a purchase order that understates what it commits — and once spend authority is enforced
   * against that figure, an order could be approved against a total that its own lines contradict.
   *
   * <p>Recomputed from all lines rather than incremented by this one, so the stored figure is a
   * function of the rows rather than of the sequence of calls that produced them. An increment that
   * is missed, applied twice or applied against a since-changed rate drifts silently and for good;
   * a recompute cannot.
   *
   * <p>The arithmetic itself is not done here — it is {@link Totals#of}, a pure function this
   * method calls. Keeping money arithmetic out of the repository is what lets every rounding and
   * VAT case be a unit test rather than a Testcontainers one.
   *
   * @param line the line to append
   * @param currency the order's currency, which fixes the rounding scale
   * @param vatRates VAT code to rate, resolved from pricing-svc by the caller
   * @return the line as stored
   */
  public PurchaseOrderLine addPurchaseOrderLine(
      PurchaseOrderLine line, String currency, java.util.Map<String, BigDecimal> vatRates) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO purchase_order_lines"
                      + " (id,tenant_id,po_id,variant_id,qty,unit_price,vat_code,proposal_reason)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, line.id());
            ps.setObject(2, line.tenantId());
            ps.setObject(3, line.poId());
            ps.setObject(4, line.variantId());
            ps.setBigDecimal(5, line.qty());
            ps.setBigDecimal(6, line.unitPrice());
            ps.setString(7, line.vatCode());
            ps.setString(8, line.proposalReason());
            ps.executeUpdate();
          }
          restateTotals(c, line.tenantId(), line.poId(), currency, vatRates);
          return line;
        },
        "add po line");
  }

  /**
   * Reads every line of the order and writes the three totals back onto it. Runs on the caller's
   * connection so it joins their transaction.
   *
   * @param c the open connection, inside the caller's transaction
   * @param tenantId the owning tenant — first condition of every query (golden rule #3)
   * @param poId the order to restate
   * @param currency the order's currency
   * @param vatRates VAT code to rate
   * @throws SQLException if either statement fails, aborting the caller's transaction
   */
  private void restateTotals(
      Connection c,
      UUID tenantId,
      UUID poId,
      String currency,
      java.util.Map<String, BigDecimal> vatRates)
      throws SQLException {
    List<PurchaseOrderLine> lines = new ArrayList<>();
    try (var ps =
        c.prepareStatement(
            "SELECT id,tenant_id,po_id,variant_id,qty,unit_price,vat_code,created_at,proposal_reason"
                + " FROM purchase_order_lines WHERE tenant_id=? AND po_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, poId);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          lines.add(
              new PurchaseOrderLine(
                  rs.getObject("id", UUID.class),
                  rs.getObject("tenant_id", UUID.class),
                  rs.getObject("po_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getBigDecimal("qty"),
                  rs.getBigDecimal("unit_price"),
                  rs.getString("vat_code"),
                  rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                  rs.getString("proposal_reason")));
        }
      }
    }
    Totals totals = Totals.of(lines, currency, vatRates);
    try (var ps =
        c.prepareStatement(
            "UPDATE purchase_orders SET total_net=?, total_vat=?, total_gross=?, updated_at=now()"
                + " WHERE tenant_id=? AND id=?")) {
      ps.setBigDecimal(1, totals.net());
      ps.setBigDecimal(2, totals.vat());
      ps.setBigDecimal(3, totals.gross());
      ps.setObject(4, tenantId);
      ps.setObject(5, poId);
      ps.executeUpdate();
    }
  }

  /**
   * Lists a purchase order's lines.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param poId the purchase order whose lines to list
   * @return the order's lines
   */
  public List<PurchaseOrderLine> findPurchaseOrderLines(UUID tenantId, UUID poId) {
    return query(
        "SELECT id,tenant_id,po_id,variant_id,qty,unit_price,vat_code,created_at,proposal_reason"
            + " FROM purchase_order_lines WHERE tenant_id=? AND po_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        rs ->
            new PurchaseOrderLine(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("po_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("unit_price"),
                rs.getString("vat_code"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("proposal_reason")),
        "find po lines");
  }

  // ── Goods Receipts ────────────────────────────────────────────────────────────

  /**
   * Record a goods receipt and move the purchase order to whichever state the quantities imply.
   *
   * <p><b>The quantities are now read.</b> This used to end {@code SET status='RECEIVED' WHERE
   * status='SUBMITTED'} with no reference to what had actually turned up, so a delivery of 6
   * against an order of 10 closed the order — and the second delivery of the remaining 4 was then
   * refused, because the order was no longer SUBMITTED. A split delivery stranded its own balance.
   *
   * <p>The comparison happens inside this transaction, against a {@code FOR UPDATE} lock on the
   * order, so two lorries arriving at once cannot both read "4 outstanding" and both book it.
   *
   * <p><b>Over-receipt is refused rather than absorbed.</b> Accepting more than was ordered would
   * book stock nobody asked for against a purchase order that cannot account for it, and a mistyped
   * 60 for 6 would do it silently. Whether a tolerance band should be allowed is a procurement
   * policy question — a real one, with a real answer per tenant — and inventing one here would be
   * guessing.
   *
   * <p>If the same Idempotency-Key was already stored for this tenant, the original receipt is
   * returned unchanged (replay), before any quantity is counted.
   */
  public GoodsReceipt createGoodsReceipt(
      GoodsReceipt gr,
      List<GoodsReceiptLine> lines,
      OutboxRow event,
      List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          if (gr.idempotencyKey() != null) {
            GoodsReceipt existing = findGoodsReceiptByKeyTx(c, gr.tenantId(), gr.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO goods_receipts (id,tenant_id,po_id,store_id,received_at,"
                      + " idempotency_key) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, gr.id());
            ps.setObject(2, gr.tenantId());
            ps.setObject(3, gr.poId());
            ps.setObject(4, gr.storeId());
            ps.setObject(5, toOdt(gr.receivedAt()));
            ps.setString(6, gr.idempotencyKey());
            ps.executeUpdate();
          }
          for (GoodsReceiptLine l : lines) {
            try (var ps =
                c.prepareStatement(
                    "INSERT INTO goods_receipt_lines (id,tenant_id,gr_id,variant_id,qty_received)"
                        + " VALUES (?,?,?,?,?)")) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, l.grId());
              ps.setObject(4, l.variantId());
              ps.setBigDecimal(5, l.qtyReceived());
              ps.executeUpdate();
            }
          }
          // Lock the order first: the status decision below reads every receipt against it, and
          // two deliveries arriving together must not both see the same outstanding quantity.
          String status = lockPurchaseOrderStatusTx(c, gr.tenantId(), gr.poId());
          if (status == null) {
            throw ApiException.notFound("PURCHASE_PO_NOT_FOUND", "No such purchase order");
          }
          if (!Domain.PO_SUBMITTED.equals(status) && !Domain.PO_PARTIALLY_RECEIVED.equals(status)) {
            throw ApiException.conflict(
                "PURCHASE_PO_NOT_RECEIVABLE",
                "a purchase order can only be received while SUBMITTED or PARTIALLY_RECEIVED —"
                    + " this one is "
                    + status);
          }

          // Ordered against received, this receipt included. Both sides are already in the
          // schema; nothing read them until now.
          List<PurchaseOrderLineProgress> progress = lineProgressTx(c, gr.tenantId(), gr.poId());
          if (progress.isEmpty()) {
            throw ApiException.unprocessable(
                "PURCHASE_PO_HAS_NO_LINES",
                "a purchase order with no lines has nothing to receive against");
          }
          for (PurchaseOrderLineProgress p : progress) {
            if (p.qtyReceived().compareTo(p.qtyOrdered()) > 0) {
              throw ApiException.unprocessable(
                  "PURCHASE_OVER_RECEIPT",
                  "variant "
                      + p.variantId()
                      + ": received "
                      + p.qtyReceived()
                      + " against an order of "
                      + p.qtyOrdered()
                      + " — amend the purchase order if the extra was genuinely ordered");
            }
          }
          boolean complete = progress.stream().allMatch(p -> p.qtyOutstanding().signum() == 0);
          setPurchaseOrderStatusTx(
              c,
              gr.tenantId(),
              gr.poId(),
              complete ? Domain.PO_RECEIVED : Domain.PO_PARTIALLY_RECEIVED);
          // The delivery as measured against the order's promise: the fact the supplier's
          // scorecard is made from, kept with the receipt it belongs to.
          SupplierPerformanceRepository.recordDeliveryTx(c, gr, lines, complete);

          // The asset and the accrual commit with the receipt, or neither does. An idempotent
          // replay returned above, before any of this, so a retried delivery posts once.
          insertNominalEntries(c, posting);
          insertOutbox(c, event);
          return gr;
        },
        "create goods receipt");
  }

  /** {@code SELECT ... FOR UPDATE}, so the outstanding-quantity read below is serialised. */
  private static String lockPurchaseOrderStatusTx(java.sql.Connection c, UUID tenantId, UUID poId)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT status FROM purchase_orders WHERE tenant_id=? AND id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, poId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("status") : null;
      }
    }
  }

  private static void setPurchaseOrderStatusTx(
      java.sql.Connection c, UUID tenantId, UUID poId, String status) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "UPDATE purchase_orders SET status=?, updated_at=now() WHERE tenant_id=? AND id=?")) {
      ps.setString(1, status);
      ps.setObject(2, tenantId);
      ps.setObject(3, poId);
      ps.executeUpdate();
    }
  }

  /**
   * Ordered against received per variant, for one purchase order.
   *
   * <p>A LEFT JOIN from the order's own lines, so a variant ordered but never delivered still
   * appears with its full quantity outstanding — an INNER JOIN would have made "nothing arrived"
   * indistinguishable from "nothing was ordered", and the whole point of this query is to notice
   * what is missing.
   */
  private static List<PurchaseOrderLineProgress> lineProgressTx(
      java.sql.Connection c, UUID tenantId, UUID poId) throws SQLException {
    List<PurchaseOrderLineProgress> out = new java.util.ArrayList<>();
    try (var ps =
        c.prepareStatement(
            "SELECT l.variant_id,"
                + "       SUM(l.qty)::numeric(14,3) AS qty_ordered,"
                + "       COALESCE((SELECT SUM(grl.qty_received) FROM goods_receipt_lines grl"
                + "                   JOIN goods_receipts gr ON gr.id = grl.gr_id"
                + "                  WHERE gr.tenant_id = l.tenant_id AND gr.po_id = ?"
                + "                    AND grl.variant_id = l.variant_id), 0)::numeric(14,3)"
                + "         AS qty_received,"
                + "       COALESCE((SELECT SUM(vrl.qty) FROM vendor_return_lines vrl"
                + "                   JOIN vendor_returns vr ON vr.id = vrl.return_id"
                + "                  WHERE vr.tenant_id = l.tenant_id AND vr.po_id = ?"
                + "                    AND vrl.variant_id = l.variant_id), 0)::numeric(14,3)"
                + "         AS qty_returned"
                + "  FROM purchase_order_lines l"
                + " WHERE l.tenant_id = ? AND l.po_id = ?"
                + " GROUP BY l.tenant_id, l.variant_id")) {
      ps.setObject(1, poId);
      ps.setObject(2, poId);
      ps.setObject(3, tenantId);
      ps.setObject(4, poId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal ordered = rs.getBigDecimal("qty_ordered");
          BigDecimal received = rs.getBigDecimal("qty_received");
          out.add(
              new PurchaseOrderLineProgress(
                  rs.getObject("variant_id", UUID.class),
                  ordered,
                  received,
                  ordered.subtract(received).max(BigDecimal.ZERO),
                  rs.getBigDecimal("qty_returned")));
        }
      }
    }
    return out;
  }

  /**
   * The same progress view, for a caller asking what is still outstanding on an order.
   *
   * <p>Receipts are matched to order lines by variant rather than by line id, because a delivery
   * note names products, not order rows.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param poId the purchase order to report on
   * @return one row per ordered variant, with ordered, received and the balance due
   */
  public List<PurchaseOrderLineProgress> findLineProgress(UUID tenantId, UUID poId) {
    return inTx(c -> lineProgressTx(c, tenantId, poId), "read purchase order progress");
  }

  /**
   * Short-closes a partially received order: the balance is never coming and we have stopped
   * waiting.
   *
   * <p>Only from PARTIALLY_RECEIVED. A SUBMITTED order with nothing delivered is a cancellation
   * (SJ-D3), and a RECEIVED one has nothing outstanding to close. Guarded in the {@code WHERE} so a
   * close racing a final delivery cannot both win — whichever commits second finds no row.
   */
  public boolean closePurchaseOrderShort(UUID tenantId, UUID poId, String reason) {
    return closePurchaseOrderShort(tenantId, poId, reason, NO_ANNOUNCEMENT);
  }

  /** As above, announcing the order's cross-dock allocations on the same transaction. */
  public boolean closePurchaseOrderShort(
      UUID tenantId,
      UUID poId,
      String reason,
      java.util.function.Function<List<Domain.LineAllocation>, Optional<OutboxRow>> announce) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE purchase_orders SET status='CLOSED', closed_at=now(),"
                      + " closed_reason=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status='PARTIALLY_RECEIVED'")) {
            ps.setString(1, reason);
            ps.setObject(2, tenantId);
            ps.setObject(3, poId);
            if (ps.executeUpdate() == 0) return false;
          }
          announceAllocationsTx(c, tenantId, poId, announce);
          return true;
        },
        "close purchase order short");
  }

  /**
   * Captures a supplier invoice and its already-matched lines in one transaction.
   *
   * <p>The match is computed by the caller from {@link #findMatchPositions} and passed in, because
   * the arithmetic belongs in {@link com.storeql.purchase.domain.ThreeWayMatch} where it can be a
   * unit test. What must be atomic is the invoice, its lines, and the outcome each line was
   * captured with — a line whose stored variance does not match the invoice it sits on is worse
   * than no variance at all.
   *
   * @param invoice the invoice header, status already decided
   * @param lines its lines, each carrying its own variance string
   * @return the invoice as stored
   * @throws ApiException 409 if this supplier's invoice number has already been captured
   */
  public Domain.SupplierInvoice captureSupplierInvoice(
      Domain.SupplierInvoice invoice,
      List<Domain.SupplierInvoiceLine> lines,
      OutboxRow event,
      List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO supplier_invoices"
                      + " (id,tenant_id,po_id,supplier_id,invoice_number,invoice_date,currency,"
                      + "  net_amount,vat_amount,gross_amount,status,created_by,due_date,"
                      + "  stated_gross,header_variances,posted_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, invoice.id());
            ps.setObject(2, invoice.tenantId());
            ps.setObject(3, invoice.poId());
            ps.setObject(4, invoice.supplierId());
            ps.setString(5, invoice.invoiceNumber());
            ps.setObject(6, invoice.invoiceDate());
            ps.setString(7, invoice.currency());
            ps.setBigDecimal(8, invoice.netAmount());
            ps.setBigDecimal(9, invoice.vatAmount());
            ps.setBigDecimal(10, invoice.grossAmount());
            ps.setString(11, invoice.status());
            ps.setObject(12, invoice.createdBy());
            ps.setObject(13, invoice.dueDate());
            ps.setBigDecimal(14, invoice.statedGross());
            ps.setString(15, invoice.headerVariances());
            ps.setObject(16, toOdt(invoice.postedAt()));
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "PURCHASE_INVOICE_DUPLICATE",
                  "invoice "
                      + invoice.invoiceNumber()
                      + " has already been captured for this supplier",
                  List.of(),
                  sqle);
            throw sqle;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO supplier_invoice_lines"
                      + " (id,tenant_id,invoice_id,variant_id,qty_invoiced,unit_price,vat_code,"
                      + "  variances)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            for (Domain.SupplierInvoiceLine l : lines) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, l.invoiceId());
              ps.setObject(4, l.variantId());
              ps.setBigDecimal(5, l.qtyInvoiced());
              ps.setBigDecimal(6, l.unitPrice());
              ps.setString(7, l.vatCode());
              ps.setString(8, l.variances());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          // The AP posting and the event commit with the invoice (golden rule 6): pricing-svc
          // reads box 4 from the event, and the ledger's creditor is the same figure.
          insertNominalEntries(c, posting);
          insertOutbox(c, event);
          return invoice;
        },
        "capture supplier invoice");
  }

  /**
   * Decides a flagged invoice, under its row: exactly one of two concurrent decisions wins.
   *
   * <p>The status condition is in the UPDATE itself rather than checked first, so twenty managers
   * clicking together produce one decision and nineteen conflicts, never two decisions. A reversal
   * posting and a rejection event, when the decision is to reject, commit with it.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the invoice
   * @param status {@code APPROVED} or {@code REJECTED}
   * @param decidedBy who
   * @param reason why
   * @param posting the reversal lines, empty for an approval
   * @param event the rejection event, or null for an approval
   * @return {@code true} when this call made the decision; {@code false} when the invoice was not
   *     FLAGGED, because it never was or because someone else decided first
   */
  public boolean resolveSupplierInvoice(
      UUID tenantId,
      UUID id,
      String status,
      UUID decidedBy,
      String reason,
      List<NominalLedgerEntry> posting,
      OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE supplier_invoices SET status=?, resolved_at=now(), resolved_by=?,"
                      + " resolution_reason=? WHERE tenant_id=? AND id=? AND status=?")) {
            ps.setString(1, status);
            ps.setObject(2, decidedBy);
            ps.setString(3, reason);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.setString(6, Domain.INVOICE_FLAGGED);
            if (ps.executeUpdate() == 0) return false;
          }
          insertNominalEntries(c, posting);
          if (event != null) insertOutbox(c, event);
          return true;
        },
        "resolve supplier invoice");
  }

  /**
   * What the order and its receipts say about each variant, plus what earlier invoices already
   * billed — the two documents a new invoice is matched against.
   *
   * <p>Invoiced quantity is summed across every earlier invoice on the order, not just the last
   * one. A supplier delivering in two lorries invoices twice, and matching each against the whole
   * order in isolation would flag the second as over-invoiced every time — the mistake the
   * goods-receipt path made before partial receipt fixed it.
   *
   * @param tenantId the owning tenant — first condition of every query (golden rule #3)
   * @param poId the order being invoiced against
   * @return one position per ordered variant
   */
  public List<ThreeWayMatch.OrderPosition> findMatchPositions(UUID tenantId, UUID poId) {
    return query(
        "SELECT l.variant_id,"
            + "       SUM(l.qty)::numeric(14,3)                       AS qty_ordered,"
            + "       MAX(l.unit_price)                               AS ordered_unit_price,"
            + "       COALESCE((SELECT SUM(grl.qty_received) FROM goods_receipt_lines grl"
            + "                   JOIN goods_receipts gr ON gr.id = grl.gr_id"
            + "                  WHERE gr.tenant_id = l.tenant_id AND gr.po_id = ?"
            + "                    AND grl.variant_id = l.variant_id), 0)::numeric(14,3)"
            + "                                                       AS qty_received,"
            + "       COALESCE((SELECT SUM(sil.qty_invoiced) FROM supplier_invoice_lines sil"
            + "                   JOIN supplier_invoices si ON si.id = sil.invoice_id"
            + "                  WHERE si.tenant_id = l.tenant_id AND si.po_id = ?"
            + "                    AND si.status <> 'REJECTED'"
            + "                    AND sil.variant_id = l.variant_id), 0)::numeric(14,3)"
            + "                                                       AS qty_invoiced"
            + "  FROM purchase_order_lines l"
            + " WHERE l.tenant_id = ? AND l.po_id = ?"
            + " GROUP BY l.variant_id, l.tenant_id",
        ps -> {
          ps.setObject(1, poId);
          ps.setObject(2, poId);
          ps.setObject(3, tenantId);
          ps.setObject(4, poId);
        },
        rs ->
            new ThreeWayMatch.OrderPosition(
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty_ordered"),
                rs.getBigDecimal("qty_received"),
                rs.getBigDecimal("qty_invoiced"),
                rs.getBigDecimal("ordered_unit_price")),
        "read three-way match positions");
  }

  /**
   * Lists supplier invoices, newest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param poId restrict to one purchase order, or {@code null} for the whole tenant
   * @param limit maximum rows
   * @return the supplier invoices, newest first
   */
  public List<Domain.SupplierInvoice> findSupplierInvoices(
      UUID tenantId, UUID poId, String status, int limit) {
    String sql =
        "SELECT "
            + SI_COLUMNS
            + " FROM supplier_invoices WHERE tenant_id=?"
            + (poId != null ? " AND po_id=?" : "")
            + (status != null ? " AND status=?" : "")
            + " ORDER BY created_at DESC LIMIT ?";
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (poId != null) ps.setObject(i++, poId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        PurchaseRepository::mapSupplierInvoice,
        "find supplier invoices");
  }

  /**
   * Looks a supplier invoice up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the invoice to fetch
   * @return the invoice, or empty when it does not exist in this tenant
   */
  public Optional<Domain.SupplierInvoice> findSupplierInvoice(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT " + SI_COLUMNS + " FROM supplier_invoices WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PurchaseRepository::mapSupplierInvoice,
            "find supplier invoice");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Lists a supplier invoice's lines with their stored match variances.
   *
   * <p>The variances are read back as recorded at match time, not recomputed — re-matching on read
   * would silently erase the disagreement the invoice was flagged for.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param invoiceId the invoice whose lines to list
   * @return the invoice lines
   */
  public List<Domain.SupplierInvoiceLine> findSupplierInvoiceLines(UUID tenantId, UUID invoiceId) {
    return query(
        "SELECT id,tenant_id,invoice_id,variant_id,qty_invoiced,unit_price,vat_code,variances,"
            + "created_at"
            + " FROM supplier_invoice_lines WHERE tenant_id=? AND invoice_id=? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, invoiceId);
        },
        rs ->
            new Domain.SupplierInvoiceLine(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty_invoiced"),
                rs.getBigDecimal("unit_price"),
                rs.getString("vat_code"),
                rs.getString("variances"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        "find supplier invoice lines");
  }

  private static final String SI_COLUMNS =
      "id,tenant_id,po_id,supplier_id,invoice_number,invoice_date,currency,net_amount,vat_amount,"
          + "gross_amount,status,matched_at,created_by,created_at,due_date,stated_gross,"
          + "header_variances,posted_at,resolved_at,resolved_by,resolution_reason,paid_at,"
          + "payment_run_id";

  private static Domain.SupplierInvoice mapSupplierInvoice(ResultSet rs) throws SQLException {
    OffsetDateTime posted = rs.getObject("posted_at", OffsetDateTime.class);
    OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
    OffsetDateTime paid = rs.getObject("paid_at", OffsetDateTime.class);
    return new Domain.SupplierInvoice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("po_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getString("invoice_number"),
        rs.getObject("invoice_date", LocalDate.class),
        rs.getString("currency"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getString("status"),
        rs.getObject("matched_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("due_date", LocalDate.class),
        rs.getBigDecimal("stated_gross"),
        rs.getString("header_variances"),
        posted == null ? null : posted.toInstant(),
        resolved == null ? null : resolved.toInstant(),
        rs.getObject("resolved_by", UUID.class),
        rs.getString("resolution_reason"),
        paid == null ? null : paid.toInstant(),
        rs.getObject("payment_run_id", UUID.class));
  }

  private GoodsReceipt findGoodsReceiptByKeyTx(
      java.sql.Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id,tenant_id,po_id,store_id,received_at,created_at,idempotency_key"
                + " FROM goods_receipts WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapGoodsReceipt(rs) : null;
      }
    }
  }

  private static GoodsReceipt mapGoodsReceipt(ResultSet rs) throws SQLException {
    return new GoodsReceipt(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("po_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getString("idempotency_key"));
  }

  /**
   * Lists the deliveries booked against a purchase order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param poId the purchase order whose receipts to list
   * @return the goods receipts
   */
  public List<GoodsReceipt> findGoodsReceiptsByPo(UUID tenantId, UUID poId) {
    return query(
        "SELECT id,tenant_id,po_id,store_id,received_at,created_at,idempotency_key"
            + " FROM goods_receipts WHERE tenant_id=? AND po_id=? ORDER BY received_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        PurchaseRepository::mapGoodsReceipt,
        "find grns by po");
  }

  /**
   * Lists the lines of one goods receipt.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param grId the goods receipt whose lines to list
   * @return the receipt's lines
   */
  public List<GoodsReceiptLine> findGoodsReceiptLines(UUID tenantId, UUID grId) {
    return query(
        "SELECT id,tenant_id,gr_id,variant_id,qty_received,created_at"
            + " FROM goods_receipt_lines WHERE tenant_id=? AND gr_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, grId);
        },
        rs ->
            new GoodsReceiptLine(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("gr_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty_received"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        "find grn lines");
  }

  // ── Intercompany Invoices ─────────────────────────────────────────────────────

  /**
   * Inserts a single intercompany invoice.
   *
   * <p>For one side only — a normal intercompany raise uses {@link #createIntercompanyInvoicePair},
   * which keeps the AR and AP sides in step.
   *
   * @param inv the invoice to persist; its {@code id} must already be a UUIDv7
   * @param entries the nominal-ledger entries to post alongside it
   * @return the invoice as stored
   */
  public IntercompanyInvoice createIntercompanyInvoice(
      IntercompanyInvoice inv, List<NominalLedgerEntry> ledgerEntries, OutboxRow event) {
    return inTx(
        c -> {
          insertIntercompanyInvoice(c, inv);
          for (NominalLedgerEntry e : ledgerEntries) {
            insertNominalEntry(c, e);
          }
          insertOutbox(c, event);
          return inv;
        },
        "create intercompany invoice");
  }

  /**
   * Creates an AR invoice and its matching AP invoice atomically (double-entry integrity).
   *
   * <p>Both sides commit together or neither does: a half-written pair would leave the two books
   * permanently disagreeing.
   *
   * @param ar the receivable side
   * @param ap the payable side
   * @param entries the nominal-ledger entries to post alongside both
   * @return the two invoices as stored
   */
  public List<IntercompanyInvoice> createIntercompanyInvoicePair(
      IntercompanyInvoice ar,
      List<NominalLedgerEntry> arEntries,
      OutboxRow arEvent,
      IntercompanyInvoice ap,
      List<NominalLedgerEntry> apEntries,
      OutboxRow apEvent) {
    return inTx(
        c -> {
          for (IntercompanyInvoice inv : new IntercompanyInvoice[] {ar, ap}) {
            insertIntercompanyInvoice(c, inv);
          }
          for (NominalLedgerEntry e : arEntries) insertNominalEntry(c, e);
          for (NominalLedgerEntry e : apEntries) insertNominalEntry(c, e);
          insertOutbox(c, arEvent);
          insertOutbox(c, apEvent);
          return List.of(ar, ap);
        },
        "create intercompany invoice pair");
  }

  /**
   * Marks an intercompany invoice settled and posts its settlement entries in one transaction.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the invoice to settle
   * @param entries the nominal-ledger entries the settlement posts
   */
  public void settleIntercompanyInvoice(
      UUID tenantId, UUID id, List<NominalLedgerEntry> settlementEntries) {
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE intercompany_invoices SET status='SETTLED'"
                      + " WHERE tenant_id=? AND id=? AND status='RAISED'"
                      + " RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            var rs = ps.executeQuery();
            if (!rs.next())
              throw new ApiException(
                  409,
                  "PURCHASE_INVOICE_NOT_RAISEABLE",
                  "Invoice already settled or not found",
                  List.of());
          }
          for (NominalLedgerEntry e : settlementEntries) {
            insertNominalEntry(c, e);
          }
          return null;
        },
        "settle intercompany invoice");
  }

  private void insertIntercompanyInvoice(java.sql.Connection c, IntercompanyInvoice inv)
      throws java.sql.SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO intercompany_invoices"
                + " (id,tenant_id,invoice_type,from_store_id,to_store_id,transfer_ref,"
                + "  net_amount,vat_amount,gross_amount,vat_code,vat_disregarded,"
                + "  status,invoice_date,payment_due_date,currency)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, inv.id());
      ps.setObject(2, inv.tenantId());
      ps.setString(3, inv.invoiceType());
      ps.setObject(4, inv.fromStoreId());
      ps.setObject(5, inv.toStoreId());
      ps.setObject(6, inv.transferRef());
      ps.setBigDecimal(7, inv.netAmount());
      ps.setBigDecimal(8, inv.vatAmount());
      ps.setBigDecimal(9, inv.grossAmount());
      ps.setString(10, inv.vatCode());
      ps.setBoolean(11, inv.vatDisregarded());
      ps.setString(12, inv.status());
      ps.setObject(13, inv.invoiceDate());
      ps.setObject(14, inv.paymentDueDate());
      ps.setString(15, inv.currency());
      ps.executeUpdate();
    }
  }

  private void insertNominalEntry(java.sql.Connection c, NominalLedgerEntry e)
      throws java.sql.SQLException {
    LedgerWriter.insert(c, List.of(e));
  }

  private void insertNominalEntries(java.sql.Connection c, List<NominalLedgerEntry> entries)
      throws SQLException {
    LedgerWriter.insert(c, entries);
  }

  /**
   * Writes one balanced journal on its own. The lines were built by {@code LedgerPosting}, which is
   * where the balance was checked; this method only stores them together.
   *
   * @param entries the journal's lines, sharing a journal id
   */
  public void postJournal(List<NominalLedgerEntry> entries) {
    inTx(
        c -> {
          insertNominalEntries(c, entries);
          return null;
        },
        "post journal");
  }

  /**
   * Reads one journal's lines, in the order they were written.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param journalId the journal to read
   * @return its lines, empty when no such journal exists in this tenant
   */
  public List<NominalLedgerEntry> findJournal(UUID tenantId, UUID journalId) {
    return query(
        "SELECT "
            + NLE_COLUMNS
            + " FROM nominal_ledger_entries"
            + " WHERE tenant_id=? AND journal_id=? ORDER BY created_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, journalId);
        },
        this::mapNominalEntry,
        "find journal");
  }

  /**
   * The journal a document produced, by source: the posting to reverse when the document is
   * rejected.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param sourceType the posting's source type
   * @param sourceRef the document
   * @return its lines, empty when the document was never posted
   */
  public List<NominalLedgerEntry> findPostingFor(UUID tenantId, String sourceType, UUID sourceRef) {
    return query(
        "SELECT "
            + NLE_COLUMNS
            + " FROM nominal_ledger_entries"
            + " WHERE tenant_id=? AND source_type=? AND source_ref=? ORDER BY created_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, sourceType);
          ps.setObject(3, sourceRef);
        },
        this::mapNominalEntry,
        "find posting for document");
  }

  /**
   * Movement per nominal code over a range: the trial balance.
   *
   * <p>A code's name is whichever was written first for it; a manual journal may name a code
   * differently from a posting, and the balance is per code, not per name.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param from first entry date included, or null for the beginning
   * @param to last entry date included, or null for the end
   * @param storeId restrict to one store's postings, or null for the whole tenant
   * @return one row per code, in code order
   */
  public List<Domain.TrialBalanceRow> findTrialBalance(
      UUID tenantId, LocalDate from, LocalDate to, UUID storeId) {
    return query(
        "SELECT nominal_code, MIN(nominal_name) AS nominal_name,"
            + " COALESCE(SUM(debit),0) AS debit, COALESCE(SUM(credit),0) AS credit"
            + " FROM nominal_ledger_entries WHERE tenant_id=?"
            + (from != null ? " AND entry_date >= ?" : "")
            + (to != null ? " AND entry_date <= ?" : "")
            + (storeId != null ? " AND store_id = ?" : "")
            + " GROUP BY nominal_code ORDER BY nominal_code",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (from != null) ps.setObject(i++, from);
          if (to != null) ps.setObject(i++, to);
          if (storeId != null) ps.setObject(i, storeId);
        },
        rs ->
            new Domain.TrialBalanceRow(
                rs.getString("nominal_code"),
                rs.getString("nominal_name"),
                rs.getBigDecimal("debit"),
                rs.getBigDecimal("credit")),
        "find trial balance");
  }

  /**
   * Lists a tenant's intercompany invoices, both AR and AP sides.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param limit maximum rows
   * @return the invoices
   */
  public List<IntercompanyInvoice> findIntercompanyInvoices(UUID tenantId, int limit) {
    return query(
        "SELECT id,tenant_id,invoice_type,from_store_id,to_store_id,transfer_ref,"
            + "net_amount,vat_amount,gross_amount,vat_code,vat_disregarded,"
            + "status,invoice_date,payment_due_date,currency,created_at"
            + " FROM intercompany_invoices WHERE tenant_id=? ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        this::mapInvoice,
        "find intercompany invoices");
  }

  /**
   * Looks an intercompany invoice up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the invoice to fetch
   * @return the invoice, or empty when it does not exist in this tenant
   */
  public Optional<IntercompanyInvoice> findIntercompanyInvoice(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id,tenant_id,invoice_type,from_store_id,to_store_id,transfer_ref,"
                + "net_amount,vat_amount,gross_amount,vat_code,vat_disregarded,"
                + "status,invoice_date,payment_due_date,currency,created_at"
                + " FROM intercompany_invoices WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapInvoice,
            "find intercompany invoice");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private IntercompanyInvoice mapInvoice(ResultSet rs) throws SQLException {
    UUID transferRef = rs.getObject("transfer_ref", UUID.class);
    return new IntercompanyInvoice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("invoice_type"),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        transferRef,
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getString("vat_code"),
        rs.getBoolean("vat_disregarded"),
        rs.getString("status"),
        rs.getObject("invoice_date", LocalDate.class),
        rs.getObject("payment_due_date", LocalDate.class),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Nominal Ledger ────────────────────────────────────────────────────────────

  /**
   * Keyset page of the nominal ledger, ordered by {@code (entry_date, created_at, id)} ascending
   * (chronological journal order). {@code from}/{@code to} were previously the only bound, and both
   * are optional — a caller omitting them (a legitimate "since inception" trial-balance query) got
   * a fully unbounded scan of the ledger. Now paginated instead of relying on the date filter
   * alone.
   */
  public List<NominalLedgerEntry> findNominalLedger(
      UUID tenantId,
      String nominalCode,
      LocalDate from,
      LocalDate to,
      LocalDate afterEntryDate,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    boolean hasCursor = afterEntryDate != null && afterCreatedAt != null && afterId != null;
    return query(
        "SELECT "
            + NLE_COLUMNS
            + " FROM nominal_ledger_entries"
            + " WHERE tenant_id=?"
            + (nominalCode != null ? " AND nominal_code=?" : "")
            + (from != null ? " AND entry_date >= ?" : "")
            + (to != null ? " AND entry_date <= ?" : "")
            + (hasCursor ? " AND (entry_date, created_at, id) > (?, ?, ?)" : "")
            + " ORDER BY entry_date, created_at, id LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (nominalCode != null) ps.setString(i++, nominalCode);
          if (from != null) ps.setObject(i++, from);
          if (to != null) ps.setObject(i++, to);
          if (hasCursor) {
            ps.setObject(i++, afterEntryDate);
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        this::mapNominalEntry,
        "find nominal ledger");
  }

  private static final String NLE_COLUMNS =
      "id,tenant_id,entry_date,nominal_code,nominal_name,debit,credit,description,source_ref,"
          + "created_at,journal_id,source_type,store_id";

  private NominalLedgerEntry mapNominalEntry(ResultSet rs) throws SQLException {
    UUID sourceRef = rs.getObject("source_ref", UUID.class);
    return new NominalLedgerEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("entry_date", LocalDate.class),
        rs.getString("nominal_code"),
        rs.getString("nominal_name"),
        rs.getBigDecimal("debit"),
        rs.getBigDecimal("credit"),
        rs.getString("description"),
        sourceRef,
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("journal_id", UUID.class),
        rs.getString("source_type"),
        rs.getObject("store_id", UUID.class));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private static OffsetDateTime toOdt(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  // ── Return to vendor and debit note (07.8) ────────────────────────────────────

  private static final String VR_COLUMNS =
      "id, tenant_id, po_id, supplier_id, store_id, status, reason, notes, currency, net_amount,"
          + " vat_amount, gross_amount, debit_note_number, raised_at, raised_by,"
          + " credit_note_number, credit_note_date, credit_amount, credited_at, credited_by,"
          + " idempotency_key";

  /**
   * Raises a return to vendor: the goods going back, and the debit note for their value.
   *
   * <p>Inside one transaction, under the purchase order's row lock: the order must have had
   * something received against it; no variant may go back in a greater quantity than was received
   * less what has already gone back; the debit note takes the next number in the tenant's series
   * ({@code UPDATE … RETURNING} under the series row's lock, like a receipt number, so a rollback
   * gives the number back); and the event that moves the stock is written with it.
   *
   * <p>Idempotent on the caller's key: a retried raise returns the return already recorded rather
   * than sending the goods back twice.
   *
   * @param ret the return, its amounts already computed; the debit note number is taken here
   * @param lines its lines
   * @param event {@code ReturnedToVendor}, written in the same transaction
   * @return the return as stored, numbered
   */
  public Domain.VendorReturn createVendorReturn(
      Domain.VendorReturn ret, List<Domain.VendorReturnLine> lines, OutboxRow event) {
    return inTx(
        c -> {
          if (ret.idempotencyKey() != null) {
            Domain.VendorReturn existing =
                findVendorReturnByKeyTx(c, ret.tenantId(), ret.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }
          String status = lockPurchaseOrderStatusTx(c, ret.tenantId(), ret.poId());
          if (status == null) {
            throw ApiException.notFound("PURCHASE_PO_NOT_FOUND", "No such purchase order");
          }
          if (!Domain.PO_PARTIALLY_RECEIVED.equals(status)
              && !Domain.PO_RECEIVED.equals(status)
              && !Domain.PO_CLOSED.equals(status)) {
            throw ApiException.conflict(
                "PURCHASE_RTV_NOTHING_RECEIVED",
                "goods can only go back against an order something was received on — this one"
                    + " is "
                    + status);
          }
          // Received less already returned, per variant: the ceiling on what can go back.
          java.util.Map<UUID, BigDecimal> returnable = new java.util.HashMap<>();
          for (PurchaseOrderLineProgress p : lineProgressTx(c, ret.tenantId(), ret.poId())) {
            returnable.put(p.variantId(), p.qtyReceived().subtract(p.qtyReturned()));
          }
          for (Domain.VendorReturnLine l : lines) {
            BigDecimal ceiling = returnable.get(l.variantId());
            if (ceiling == null) {
              throw ApiException.unprocessable(
                  "PURCHASE_RTV_NOT_ON_ORDER",
                  "variant " + l.variantId() + " is not on this purchase order");
            }
            if (l.qty().compareTo(ceiling) > 0) {
              throw ApiException.unprocessable(
                  "PURCHASE_RTV_OVER_RETURN",
                  "variant "
                      + l.variantId()
                      + ": "
                      + l.qty().toPlainString()
                      + " to return against "
                      + ceiling.toPlainString()
                      + " received and not yet returned");
            }
          }
          String number = nextDebitNoteNumberTx(c, ret.tenantId());
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO vendor_returns (id, tenant_id, po_id, supplier_id, store_id,"
                      + " status, reason, notes, currency, net_amount, vat_amount, gross_amount,"
                      + " debit_note_number, raised_at, raised_by, idempotency_key)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, ret.id());
            ps.setObject(2, ret.tenantId());
            ps.setObject(3, ret.poId());
            ps.setObject(4, ret.supplierId());
            ps.setObject(5, ret.storeId());
            ps.setString(6, Domain.RETURN_RAISED);
            ps.setString(7, ret.reason());
            ps.setString(8, ret.notes());
            ps.setString(9, ret.currency());
            ps.setBigDecimal(10, ret.netAmount());
            ps.setBigDecimal(11, ret.vatAmount());
            ps.setBigDecimal(12, ret.grossAmount());
            ps.setString(13, number);
            ps.setObject(14, toOdt(ret.raisedAt()));
            ps.setObject(15, ret.raisedBy());
            ps.setString(16, ret.idempotencyKey());
            ps.executeUpdate();
          }
          for (Domain.VendorReturnLine l : lines) {
            try (var ps =
                c.prepareStatement(
                    "INSERT INTO vendor_return_lines (id, tenant_id, return_id, variant_id, qty,"
                        + " unit_price, vat_code, line_net) VALUES (?,?,?,?,?,?,?,?)")) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, l.returnId());
              ps.setObject(4, l.variantId());
              ps.setBigDecimal(5, l.qty());
              ps.setBigDecimal(6, l.unitPrice());
              ps.setString(7, l.vatCode());
              ps.setBigDecimal(8, l.lineNet());
              ps.executeUpdate();
            }
          }
          insertOutbox(c, event);
          Domain.VendorReturn stored = findVendorReturnTx(c, ret.tenantId(), ret.id());
          if (stored == null) {
            throw new SQLException("vendor return vanished after insert");
          }
          return stored;
        },
        "raise vendor return");
  }

  /** The next debit note number for the tenant, opening the series on first use. */
  private static String nextDebitNoteNumberTx(java.sql.Connection c, UUID tenantId)
      throws SQLException {
    try (var open =
        c.prepareStatement(
            "INSERT INTO debit_note_series (tenant_id, next_number) VALUES (?, 1)"
                + " ON CONFLICT DO NOTHING")) {
      open.setObject(1, tenantId);
      open.executeUpdate();
    }
    try (var take =
        c.prepareStatement(
            "UPDATE debit_note_series SET next_number = next_number + 1 WHERE tenant_id = ?"
                + " RETURNING next_number - 1")) {
      take.setObject(1, tenantId);
      try (ResultSet rs = take.executeQuery()) {
        if (!rs.next()) {
          throw new SQLException("debit note series vanished between open and take");
        }
        return String.format("DN-%06d", rs.getLong(1));
      }
    }
  }

  private static Domain.VendorReturn findVendorReturnByKeyTx(
      java.sql.Connection c, UUID tenantId, String key) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT "
                + VR_COLUMNS
                + " FROM vendor_returns WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapVendorReturn(rs) : null;
      }
    }
  }

  private static Domain.VendorReturn findVendorReturnTx(
      java.sql.Connection c, UUID tenantId, UUID id) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT " + VR_COLUMNS + " FROM vendor_returns WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapVendorReturn(rs) : null;
      }
    }
  }

  /**
   * @param tenantId owning tenant; the first condition of the query
   * @param id the return
   * @return the return, or empty when it is not this tenant's
   */
  public Optional<Domain.VendorReturn> findVendorReturn(UUID tenantId, UUID id) {
    List<Domain.VendorReturn> rows =
        query(
            "SELECT " + VR_COLUMNS + " FROM vendor_returns WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PurchaseRepository::mapVendorReturn,
            "find vendor return");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * @param tenantId owning tenant; the first condition
   * @param poId the order, or null for every return in the tenant
   * @return the returns, newest first
   */
  public List<Domain.VendorReturn> findVendorReturns(UUID tenantId, UUID poId) {
    if (poId == null) {
      return query(
          "SELECT "
              + VR_COLUMNS
              + " FROM vendor_returns WHERE tenant_id = ?"
              + " ORDER BY raised_at DESC LIMIT 200",
          ps -> ps.setObject(1, tenantId),
          PurchaseRepository::mapVendorReturn,
          "list vendor returns");
    }
    return query(
        "SELECT "
            + VR_COLUMNS
            + " FROM vendor_returns WHERE tenant_id = ? AND po_id = ?"
            + " ORDER BY raised_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        PurchaseRepository::mapVendorReturn,
        "list vendor returns by po");
  }

  /** The lines of one return, in the order they were raised. */
  public List<Domain.VendorReturnLine> findVendorReturnLines(UUID tenantId, UUID returnId) {
    return query(
        "SELECT id, tenant_id, return_id, variant_id, qty, unit_price, vat_code, line_net,"
            + " created_at FROM vendor_return_lines WHERE tenant_id = ? AND return_id = ?"
            + " ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, returnId);
        },
        rs ->
            new Domain.VendorReturnLine(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("return_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("unit_price"),
                rs.getString("vat_code"),
                rs.getBigDecimal("line_net"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        "list vendor return lines");
  }

  /**
   * Records the supplier's credit note against a return, closing it. The state guard is in the
   * UPDATE's WHERE clause: a return credited twice keeps the first credit note, and the second
   * caller is told so.
   *
   * @return whether the return was RAISED and is now CREDITED
   */
  public boolean creditVendorReturn(
      UUID tenantId,
      UUID id,
      String creditNoteNumber,
      LocalDate creditNoteDate,
      BigDecimal amount,
      UUID creditedBy,
      List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE vendor_returns SET status = ?, credit_note_number = ?,"
                      + " credit_note_date = ?, credit_amount = ?, credited_at = now(),"
                      + " credited_by = ? WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, Domain.RETURN_CREDITED);
            ps.setString(2, creditNoteNumber);
            ps.setObject(3, creditNoteDate);
            ps.setBigDecimal(4, amount);
            ps.setObject(5, creditedBy);
            ps.setObject(6, tenantId);
            ps.setObject(7, id);
            ps.setString(8, Domain.RETURN_RAISED);
            if (ps.executeUpdate() == 0) return false;
          }
          // The reversal of the creditor commits with the credit note: a return that was credited
          // on paper and never in the ledger is the reconciliation break this exists to prevent.
          insertNominalEntries(c, posting);
          return true;
        },
        "credit vendor return");
  }

  private static Domain.VendorReturn mapVendorReturn(ResultSet rs) throws SQLException {
    OffsetDateTime raised = rs.getObject("raised_at", OffsetDateTime.class);
    OffsetDateTime credited = rs.getObject("credited_at", OffsetDateTime.class);
    LocalDate creditDate = rs.getObject("credit_note_date", LocalDate.class);
    return new Domain.VendorReturn(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("po_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getString("reason"),
        rs.getString("notes"),
        rs.getString("currency"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getString("debit_note_number"),
        raised == null ? null : raised.toInstant(),
        rs.getObject("raised_by", UUID.class),
        rs.getString("credit_note_number"),
        creditDate,
        rs.getBigDecimal("credit_amount"),
        credited == null ? null : credited.toInstant(),
        rs.getObject("credited_by", UUID.class),
        rs.getString("idempotency_key"));
  }
}
