package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceRequest;
import com.storeql.purchase.dto.Dtos.ResolveSupplierInvoiceRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.PurchaseService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * Supplier invoices and the three-way match: ordered against received against invoiced.
 *
 * <p>The control that stops a business paying for goods it did not order, did not get, or was
 * charged the wrong price for.
 */
@RequestScoped
@Path("/supplier-invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Supplier Invoices")
public class SupplierInvoiceResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  /**
   * Records a supplier's invoice against a purchase order and matches it three ways.
   *
   * <p>Quantity is matched against what was <em>received</em>, because you pay for what turned up;
   * price against what was <em>ordered</em>, because a delivery note does not renegotiate the
   * price. Variances flag the invoice rather than block it, and the flags are stored as found.
   *
   * @param req the purchase order, invoice number, date, currency and lines
   * @return {@code 201} with the captured invoice, status MATCHED or FLAGGED
   * @throws com.storeql.web.ApiException {@code 400} when there are no lines or the currency
   *     differs from the order's; {@code 404} when the order does not exist; {@code 409} when this
   *     supplier's invoice number was already captured
   */
  @Operation(
      summary = "Capture a supplier invoice and match it three ways",
      description =
          "Records the supplier's invoice against a purchase order and compares it with what was"
              + " ordered and what was received. Quantity is matched against RECEIVED, not ordered,"
              + " because you pay for what turned up — an order for 100 that delivered 60 and"
              + " invoiced 60 is correct. Price is matched against the ORDER, because a goods"
              + " receipt records quantity only. Invoiced quantity is compared cumulatively across"
              + " every invoice on the order, so a supplier who delivers and bills in two parts is"
              + " not flagged as over-invoicing on the second.\\n\\n"
              + "The invoice is stored whether or not it matches: flagging never blocks capture. An"
              + " invoice that arrived is a fact, and refusing to record one that disagrees with the"
              + " order destroys the evidence of the disagreement. Tolerance bands are configured"
              + " per deployment (storeql.purchase.match.tolerance.*) and default to zero, which"
              + " surfaces every difference; percentage and absolute limits, with separate"
              + " upper and lower bands on price, the stricter governing.\n\n"
              + "The invoice is POSTED at capture whether or not it matched — Dr GR/IR, Dr VAT"
              + " input, Cr Creditors, dated the invoice date — because the liability exists the"
              + " moment the supplier has invoiced; what a variance blocks is payment. dueDate is"
              + " the invoice date plus the supplier's terms. statedGross, when keyed, is checked"
              + " against the lines plus VAT and a difference flags TOTAL_MISMATCH.")
  @APIResponse(responseCode = "201", description = "Invoice captured; status MATCHED or FLAGGED")
  @APIResponse(
      responseCode = "400",
      description =
          "No lines, a currency the order was not in, or PURCHASE_AMOUNT_TOO_PRECISE: a vatAmount"
              + " or statedGross finer than the currency's minor units (refused, never rounded)")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(
      responseCode = "409",
      description =
          "This supplier's invoice number has already been captured, or the accounting period"
              + " covering the invoice date is closed (PURCHASE_PERIOD_CLOSED)")
  @APIResponse(
      responseCode = "403",
      description =
          "Not owner, manager or storekeeper, or STORE_ACCESS_DENIED: the caller is held to stores"
              + " that are not the order's")
  @POST
  public Response capture(CaptureSupplierInvoiceRequest req) {
    // Capturing posts to the ledger: back-office work, not the till's.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER");
    Validations.validate(req);
    var invoice = svc.captureSupplierInvoice(ctx, req);
    return Response.status(201)
        .entity(
            ApiResponse.ok(
                Mappers.toDto(
                    invoice,
                    svc.supplierInvoiceLines(ctx, invoice.id()),
                    svc.matchPositions(ctx, invoice.poId()))))
        .build();
  }

  /**
   * Lists supplier invoices, newest first.
   *
   * @param poId restrict to one purchase order, or {@code null} for the whole tenant
   * @param limit page size, defaulting to 20
   * @return the invoices, newest first
   */
  @Operation(
      summary = "List supplier invoices",
      description =
          "Optionally filtered to one purchase order (?poId=) and to one status (?status=MATCHED,"
              + " FLAGGED, APPROVED or REJECTED) — ?status=FLAGGED is the queue awaiting a decision.")
  @APIResponse(responseCode = "200", description = "Invoices, newest first")
  @APIResponse(responseCode = "400", description = "A status that is not one of the four")
  @GET
  public Response list(
      @QueryParam("poId") String poId,
      @QueryParam("status") String status,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID po = Parsing.optionalUuid(poId, "poId");
    return Response.ok(
            ApiResponse.ok(
                svc
                    .listSupplierInvoices(ctx, po, status, Math.min(Math.max(limit, 1), 100))
                    .stream()
                    .map(
                        inv ->
                            Mappers.toDto(
                                inv,
                                svc.supplierInvoiceLines(ctx, inv.id()),
                                svc.matchPositions(ctx, inv.poId())))
                    .toList()))
        .build();
  }

  /**
   * One supplier invoice with the order, the receipts and the invoice side by side.
   *
   * <p>Each line carries what was ordered, what was received, what earlier invoices billed, what
   * this one bills, and every variance found. The variances are those stored at capture, not
   * recomputed — the order can be amended afterwards, and re-matching on read would erase the
   * disagreement the invoice was flagged for.
   *
   * @param id the invoice to read
   * @return the invoice and its match
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(
      summary = "One supplier invoice, with all three documents side by side",
      description =
          "Each line carries what was ordered, what was received, what earlier invoices billed, what"
              + " this one bills, and every variance found. The variances are the ones stored at"
              + " capture rather than recomputed: a purchase order can be amended after an invoice"
              + " is flagged, and re-matching on read would silently erase the disagreement it was"
              + " flagged for.")
  @APIResponse(responseCode = "200", description = "The invoice and its match")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: the invoice's order is another store's than the caller's")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    var invoice = svc.getSupplierInvoice(ctx, id);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    invoice,
                    svc.supplierInvoiceLines(ctx, id),
                    svc.matchPositions(ctx, invoice.poId()))))
        .build();
  }

  @Operation(
      summary = "Decide a flagged invoice: approve it for payment or reject it",
      description =
          "Management only, with a required reason. APPROVE releases the invoice for payment and"
              + " leaves its posting as it is. REJECT reverses its posting line for line on a new"
              + " journal dated today, takes it out of the VAT return, and frees the quantities it"
              + " billed so the supplier's corrected invoice matches cleanly. Only a FLAGGED"
              + " invoice can be decided, and only once: two managers deciding together produce"
              + " one decision and one 409.")
  @APIResponse(responseCode = "200", description = "The invoice, decided")
  @APIResponse(responseCode = "400", description = "An action that is neither, or no reason")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role or without purchasing.invoices.decide, or STORE_ACCESS_DENIED:"
              + " the invoice's order is another store's than the caller's")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @APIResponse(
      responseCode = "409",
      description =
          "Not FLAGGED (PURCHASE_INVOICE_NOT_FLAGGED), already decided"
              + " (PURCHASE_INVOICE_ALREADY_RESOLVED), or the period is closed"
              + " (PURCHASE_PERIOD_CLOSED)")
  @POST
  @Path("/{id}/resolve")
  public Response resolve(@PathParam("id") UUID id, ResolveSupplierInvoiceRequest req) {
    Validations.validate(req);
    var invoice = svc.resolveSupplierInvoice(ctx, id, req);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    invoice,
                    svc.supplierInvoiceLines(ctx, id),
                    svc.matchPositions(ctx, invoice.poId()))))
        .build();
  }
}
