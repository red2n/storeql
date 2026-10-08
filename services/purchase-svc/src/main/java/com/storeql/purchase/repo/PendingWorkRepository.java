package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.PaymentRuns;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.PendingWorkCount;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * How many things wait for a person, one count per queue. Each is a single statement led by {@code
 * tenant_id} that an index answers: the partial {@code idx_po_pending}, {@code
 * idx_supplier_invoices_status}, the partial {@code idx_payment_runs_tenant_proposed} and the
 * partial {@code idx_accounting_syncs_tenant_status}.
 *
 * <p>Each count reads at most {@link PendingWorkCount#CAP} rows of its queue and stops: the system
 * health screen polls these every few seconds, so a queue that keeps growing must not make every
 * poll dearer. An answer equal to the cap means "this many or more". The cap is bound as the limit
 * of the inner select; the counted rows are the inner select's, so the database stops at the cap
 * instead of counting every index entry.
 *
 * <p>The status is written into each statement as a literal rather than bound, because a partial
 * index is used only when the planner can see its predicate in the statement itself. The statements
 * are public so a test can ask the database how it would run them; each takes the business as its
 * first parameter and the cap as its second.
 */
@ApplicationScoped
public class PendingWorkRepository extends BaseJdbcRepository {

  private static final String COUNT_UP_TO_CAP_OF = "SELECT count(*) AS n FROM (SELECT 1 FROM ";
  private static final String UP_TO_CAP = " LIMIT ?) AS waiting";

  /** Orders held above their submitter's spend authority; the business, then the cap. */
  public static final String PURCHASE_ORDERS_PENDING_APPROVAL =
      COUNT_UP_TO_CAP_OF
          + "purchase_orders WHERE tenant_id = ? AND status = '"
          + Domain.PO_PENDING_APPROVAL
          + "'"
          + UP_TO_CAP;

  /** Runs proposed and waiting for a second person; the business, then the cap. */
  public static final String PAYMENT_RUNS_PROPOSED =
      COUNT_UP_TO_CAP_OF
          + "payment_runs WHERE tenant_id = ? AND status = '"
          + PaymentRuns.PROPOSED
          + "'"
          + UP_TO_CAP;

  /** Invoices that did not match, waiting for a decision; the business, then the cap. */
  public static final String SUPPLIER_INVOICES_FLAGGED =
      COUNT_UP_TO_CAP_OF
          + "supplier_invoices WHERE tenant_id = ? AND status = '"
          + Domain.INVOICE_FLAGGED
          + "'"
          + UP_TO_CAP;

  /** Pushes whose outcome is unknown, waiting for a person; the business, then the cap. */
  public static final String ACCOUNTING_SYNCS_UNCERTAIN =
      COUNT_UP_TO_CAP_OF
          + "accounting_syncs WHERE tenant_id = ? AND status = '"
          + Accounting.UNCERTAIN
          + "'"
          + UP_TO_CAP;

  public long purchaseOrdersPendingApproval(UUID tenantId) {
    return count(PURCHASE_ORDERS_PENDING_APPROVAL, tenantId, "count purchase orders pending");
  }

  public long paymentRunsProposed(UUID tenantId) {
    return count(PAYMENT_RUNS_PROPOSED, tenantId, "count payment runs proposed");
  }

  public long supplierInvoicesFlagged(UUID tenantId) {
    return count(SUPPLIER_INVOICES_FLAGGED, tenantId, "count supplier invoices flagged");
  }

  public long accountingSyncsUncertain(UUID tenantId) {
    return count(ACCOUNTING_SYNCS_UNCERTAIN, tenantId, "count accounting syncs uncertain");
  }

  private long count(String sql, UUID tenantId, String what) {
    return query(
            sql,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setInt(2, PendingWorkCount.CAP);
            },
            rs -> rs.getLong("n"),
            what)
        .get(0);
  }
}
