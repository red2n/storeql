package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.CancelPaymentRunRequest;
import com.storeql.purchase.dto.Dtos.ProposePaymentRunRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.BusinessWide;
import com.storeql.purchase.service.PaymentRunService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Permissions;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Supplier payment runs and remittance (17.10). Every route needs a management role and {@code
 * finance.payments} ({@code 403 FORBIDDEN} / {@code PERMISSION_DENIED}, checked first), and a
 * caller held to no store: a run gathers every store's invoices and pays them from the business's
 * own accounts, so it is the whole business's ({@code 403 BUSINESS_WIDE_ONLY} for a caller held to
 * stores, before anything is read or written).
 */
@RequestScoped
@Path("/payment-runs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Payment Runs")
public class PaymentRunResource {

  @Inject PaymentRunService svc;
  @Inject com.storeql.purchase.service.BankFileService files;
  @Inject com.storeql.web.TenantContext ctx;

  @Operation(
      summary = "Propose a payment run",
      description =
          "Collects every invoice that may be paid — MATCHED, or FLAGGED and then APPROVED; posted;"
              + " unpaid; in the run's currency; due on or before payUpTo; not held by another open"
              + " run — and each supplier's unallocated credit notes, which are offset against"
              + " what it is paid. A supplier with no bank details, or whose credits cover its"
              + " invoices, is left out and listed under excluded with the reason. A supplier whose"
              + " bank details changed in the last 14 days is paid but carries the warning"
              + " BANK_DETAILS_CHANGED_RECENTLY. The documents are reserved to the run: a second"
              + " proposal will not pick them up, and two racing proposals produce one run and"
              + " one 409.")
  @APIResponse(responseCode = "201", description = "The proposed run")
  @APIResponse(responseCode = "400", description = "A bad date or currency")
  @APIResponse(
      responseCode = "409",
      description =
          "Nothing payable is due (PURCHASE_PAYMENT_RUN_NOTHING_DUE, with each supplier left out"
              + " and why), or a concurrent proposal took a document (PURCHASE_PAYMENT_RUN_CONFLICT)")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @POST
  public Response propose(ProposePaymentRunRequest req) {
    wholeBusiness();
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.propose(ctx, req))))
        .build();
  }

  @Operation(summary = "List payment runs", description = "Newest first; ?status= filters.")
  @APIResponse(responseCode = "200", description = "Runs, newest first")
  @APIResponse(responseCode = "400", description = "A status that is not one of the four")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @GET
  public Response list(
      @QueryParam("status") String status, @QueryParam("limit") @DefaultValue("20") int limit) {
    wholeBusiness();
    return Response.ok(
            ApiResponse.ok(
                svc.list(ctx, status, Math.min(Math.max(limit, 1), 100)).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(summary = "One payment run, with what it pays each supplier")
  @APIResponse(responseCode = "200", description = "The run")
  @APIResponse(responseCode = "404", description = "Not found in this tenant")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    wholeBusiness();
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.get(ctx, id)))).build();
  }

  @Operation(
      summary = "Approve a proposed run",
      description =
          "By someone other than its proposer, unless the approver is the owner. Refused when a"
              + " supplier in the run can no longer be paid.")
  @APIResponse(responseCode = "200", description = "The run, APPROVED")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(responseCode = "409", description = "Not PROPOSED, or PURCHASE_PAYMENT_RUN_STALE")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's;"
              + " PURCHASE_PAYMENT_RUN_SELF_APPROVAL: the proposer approving their own run")
  @POST
  @Path("/{id}/approve")
  public Response approve(@PathParam("id") UUID id) {
    wholeBusiness();
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.approve(ctx, id)))).build();
  }

  @Operation(
      summary = "Pay an approved run",
      description =
          "Settles every invoice and allocates every credit note in the run, posts Dr 2100"
              + " Creditors / Cr 1200 Bank per supplier and store dated the payment date, and"
              + " emails each supplier with a remittance address its advice. Once only: twenty"
              + " concurrent calls are one payment and nineteen 409s. Refused when any supplier's"
              + " bank details changed after approval.")
  @APIResponse(responseCode = "200", description = "The run, PAID")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(
      responseCode = "409",
      description =
          "Not APPROVED, already paid, cancelled, stale, bank details changed after approval, or"
              + " the period is closed")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @POST
  @Path("/{id}/pay")
  public Response pay(@PathParam("id") UUID id) {
    wholeBusiness();
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.pay(ctx, id)))).build();
  }

  @Operation(
      summary = "Cancel a run that is not yet paid",
      description = "With a reason. Its documents are free for the next run.")
  @APIResponse(responseCode = "200", description = "The run, CANCELLED")
  @APIResponse(responseCode = "400", description = "No reason")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(responseCode = "409", description = "Already paid or already cancelled")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id, CancelPaymentRunRequest req) {
    wholeBusiness();
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.cancel(ctx, id, req)))).build();
  }

  @Operation(
      summary = "The bank file for an approved or paid run",
      description =
          "?format=CSV (the default): one payment per supplier, payee name, sort code and account"
              + " number or IBAN and BIC, amount, currency and the run reference, cells guarded"
              + " against formula injection. ?format=PAIN001 (17.12): an ISO 20022 pain.001.001.09"
              + " SEPA credit transfer initiation for a euro run, from the euro paying account, one"
              + " transfer per supplier to its IBAN, the run reference as message id and remittance."
              + " ?format=BACS18: a Bacs Standard 18 direct credit file for a sterling run, from the"
              + " sterling paying account and its service user number, with the processing day the"
              + " banking day before the payment date. Never cached. Refused when any supplier's"
              + " bank details, or the paying account, changed after the run was approved.")
  @APIResponse(responseCode = "200", description = "The file")
  @APIResponse(responseCode = "400", description = "PURCHASE_BANK_FILE_FORMAT_UNKNOWN")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(
      responseCode = "409",
      description =
          "Not approved, bank details or the paying account changed, a format the run's currency"
              + " does not take, no paying account, a supplier the format cannot pay, or a Bacs"
              + " payment date too soon")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @GET
  @Path("/{id}/bank-file")
  @Produces({"text/csv", "application/xml", "text/plain", MediaType.APPLICATION_JSON})
  public Response bankFile(@PathParam("id") UUID id, @QueryParam("format") String format) {
    wholeBusiness();
    var file = files.bankFile(ctx, id, format);
    return Response.ok(file.body())
        .type(file.contentType())
        .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
        .header("Cache-Control", "no-store")
        .build();
  }

  @Operation(
      summary = "The accounts supplier payments are made from",
      description = "One per currency, the latest set; account numbers and IBANs masked (17.12).")
  @APIResponse(responseCode = "200", description = "The paying accounts")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @GET
  @Path("/paying-accounts")
  public Response payingAccounts() {
    wholeBusiness();
    return Response.ok(
            ApiResponse.ok(files.payingAccounts(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Set the account a currency's payments are made from",
      description =
          "The account holder's name, and a UK sort code and account number or an IBAN (with an"
              + " optional BIC); a euro account needs its IBAN; a Bacs service user number goes"
              + " with a UK account. Kept as history: a change made after a run was approved stops"
              + " that run's bank file.")
  @APIResponse(responseCode = "200", description = "The paying account in force")
  @APIResponse(
      responseCode = "400",
      description = "PURCHASE_PAYING_ACCOUNT_INVALID naming what is wrong, or a currency code")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @PUT
  @Path("/paying-accounts/{currency}")
  public Response setPayingAccount(
      @PathParam("currency") String currency,
      com.storeql.purchase.dto.Dtos.PayingAccountRequest req) {
    wholeBusiness();
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(files.setPayingAccount(ctx, currency, req))))
        .build();
  }

  @Operation(
      summary = "Read the bank's status report for a run's file",
      description =
          "An ISO 20022 pain.002 customer payment status report, as XML. Each payment is matched"
              + " by the end-to-end id the file gave it; a rejected payment, or a payee the bank"
              + " could not match (NMTC) or matched only closely (CMTC), is held, and the run cannot"
              + " be paid while it is. A report is recorded once by its message id. Only for an"
              + " approved run not yet paid.")
  @APIResponse(responseCode = "200", description = "The run, with each supplier's bank check")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_STATUS_REPORT_INVALID: not a well-formed pain.002, a DTD, an unknown status")
  @APIResponse(
      responseCode = "409",
      description =
          "The run is not approved or already paid, the report answers another file"
              + " (PURCHASE_STATUS_REPORT_NOT_FOR_RUN), or names a payment the run does not make"
              + " (PURCHASE_STATUS_REPORT_UNKNOWN_PAYMENT)")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @POST
  @Path("/{id}/status-report")
  @Consumes({"application/xml", "text/xml"})
  public Response statusReport(@PathParam("id") UUID id, String xml) {
    wholeBusiness();
    return Response.ok(ApiResponse.ok(Mappers.toDto(files.recordStatusReport(ctx, id, xml))))
        .build();
  }

  @Operation(
      summary = "Release a held close match",
      description =
          "With a reason saying what was checked. Only a close match the bank did not reject can"
              + " be released; a payee the bank could not match, or a rejected payment, cannot be"
              + " paid by this run. Once.")
  @APIResponse(responseCode = "200", description = "The run")
  @APIResponse(responseCode = "400", description = "No reason")
  @APIResponse(
      responseCode = "409",
      description =
          "PURCHASE_PAYEE_NOT_HELD, PURCHASE_PAYEE_NOT_RELEASABLE, PURCHASE_PAYEE_ALREADY_RELEASED,"
              + " or the run is not approved")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.payments; BUSINESS_WIDE_ONLY:"
              + " a caller held to stores, a payment run being the whole business's")
  @POST
  @Path("/{id}/payments/{supplierId}/release")
  public Response release(
      @PathParam("id") UUID id,
      @PathParam("supplierId") UUID supplierId,
      com.storeql.purchase.dto.Dtos.ReleasePayeeRequest req) {
    wholeBusiness();
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(files.release(ctx, id, supplierId, req))))
        .build();
  }

  /**
   * The role first, then the scope: a storekeeper or a cashier is refused for the role ({@code 403
   * FORBIDDEN}, or {@code PERMISSION_DENIED} without {@code finance.payments}) exactly as the
   * service would refuse them, and only management with the permission is told a run is the whole
   * business's ({@code 403 BUSINESS_WIDE_ONLY} for a caller held to stores). The service checks the
   * role again; this keeps the refusal a caller sees the same whichever check they meet first.
   */
  private void wholeBusiness() {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    ctx.requirePermission(Permissions.FINANCE_PAYMENTS);
    BusinessWide.require(
        ctx,
        "A payment run gathers every store's invoices and pays them from the business's own"
            + " accounts; it needs a caller who is not held to stores");
  }
}
