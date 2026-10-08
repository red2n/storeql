package com.storeql.purchase.domain;

/**
 * What waits for a person in purchasing, for one business as a whole (the system-health screen).
 * Each count is read up to {@link com.storeql.web.PendingWorkCount#CAP} and no further, so a count
 * equal to it means "that many or more".
 *
 * @param purchaseOrdersPendingApproval orders over their submitter's spend authority
 * @param paymentRunsProposed supplier payment runs proposed and not yet approved or cancelled
 * @param supplierInvoicesFlagged invoices that disagree with their order or receipt, awaiting a
 *     decision
 * @param accountingSyncsUncertain journal pushes whose outcome is unknown, awaiting a person's word
 * @param approvalsRouted whether this deployment has purchase approval limits configured (a setting
 *     of the deployment, read once at startup, not of the business); when false no order is held
 *     for approval for any business, so a zero purchase-order count is not "nothing waits"
 */
public record PendingWork(
    long purchaseOrdersPendingApproval,
    long paymentRunsProposed,
    long supplierInvoicesFlagged,
    long accountingSyncsUncertain,
    boolean approvalsRouted) {}
