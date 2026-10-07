package com.storeql.purchase.dto;

import com.storeql.web.PendingWorkCount;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** What waits for a person in purchasing, on the wire. */
public final class PendingWorkDtos {
  private PendingWorkDtos() {}

  /** What every count says of its limit, built from the one constant so it cannot drift. */
  private static final String CAPPED =
      " Counted up to "
          + PendingWorkCount.CAP
          + ": a count of "
          + PendingWorkCount.CAP
          + " means "
          + PendingWorkCount.CAP
          + " or more.";

  @Schema(
      name = "PurchasePendingWork",
      description =
          "How many things wait for a person in purchasing, for the whole business. Each count"
              + " stops at "
              + PendingWorkCount.CAP
              + ": a count of "
              + PendingWorkCount.CAP
              + " means "
              + PendingWorkCount.CAP
              + " or more.")
  public record PendingWorkResponse(
      @Schema(description = "Purchase orders in PENDING_APPROVAL." + CAPPED)
          long purchaseOrdersPendingApproval,
      @Schema(description = "Supplier payment runs in PROPOSED." + CAPPED) long paymentRunsProposed,
      @Schema(description = "Supplier invoices in FLAGGED." + CAPPED) long supplierInvoicesFlagged,
      @Schema(description = "Accounting pushes in UNCERTAIN." + CAPPED)
          long accountingSyncsUncertain,
      @Schema(
              description =
                  "False when this deployment has no purchase approval limits configured: no order is"
                      + " then held for approval for any business, so a zero purchase-order count"
                      + " above is not \"nothing waits\".")
          boolean approvalsRouted) {}
}
