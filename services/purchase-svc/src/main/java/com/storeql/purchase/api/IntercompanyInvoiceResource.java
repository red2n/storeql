package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.IntercompanyInvoicePairResponse;
import com.storeql.purchase.dto.Dtos.RaiseIntercompanyInvoiceRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.PurchaseService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Intercompany AR/AP invoicing for inter-org inventory transfers (Gap #20, Oracle Inventory Ch.
 * 19).
 */
@RequestScoped
@Path("/intercompany-invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Intercompany Invoices")
public class IntercompanyInvoiceResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  /**
   * Raises the AR and AP sides of an intercompany transfer atomically.
   *
   * <p>Both sides commit together, with their FRS 102 / UK GAAP double-entry nominal ledger
   * postings. Payment due date is invoice date + 30 days (BACS terms).
   *
   * @param req the sending and receiving stores, the amount and the invoice date
   * @return {@code 201} with both sides of the pair
   * @throws com.storeql.web.ApiException {@code 400} when the body is wrong — the two stores the
   *     same, an amount finer than its currency, a gross that is not net plus VAT, VAT on a
   *     VAT-group supply — whoever sends it; then {@code 403} when the caller is held to stores
   *     that do not include both ends
   */
  @Operation(
      summary = "Raise an intercompany invoice pair",
      description =
          "Raises an AR invoice for the sending store and an AP invoice for the receiving store"
              + " atomically, posting the corresponding FRS 102 / UK GAAP double-entry nominal"
              + " ledger entries. Payment due date is invoice date + 30 days (BACS terms).")
  @APIResponse(responseCode = "201", description = "Invoice pair raised")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_IC_SAME_STORE: from and to store must be different; PURCHASE_AMOUNT_TOO_PRECISE:"
              + " an amount finer than the currency's minor units; PURCHASE_IC_GROSS_MISMATCH:"
              + " grossAmount is not netAmount plus vatAmount; PURCHASE_IC_VAT_DISREGARDED: a"
              + " vatAmount on a supply inside one VAT group (vatDisregarded)")
  @APIResponse(
      responseCode = "403",
      description =
          "STORE_ACCESS_DENIED: the caller is held to stores that do not include both ends")
  @POST
  public Response raise(RaiseIntercompanyInvoiceRequest req) {
    Validations.validate(req);
    var pair = svc.raiseIntercompanyInvoices(req, ctx);
    return Response.status(201)
        .entity(
            ApiResponse.ok(
                new IntercompanyInvoicePairResponse(
                    Mappers.toDto(pair.get(0)), Mappers.toDto(pair.get(1)))))
        .build();
  }

  /**
   * Lists the tenant's intercompany invoices, both AR and AP sides.
   *
   * @param limit page size; clamped to the platform default and maximum when absent or out of range
   * @return the invoices
   */
  @Operation(
      summary = "List intercompany invoices",
      description = "Lists intercompany invoices for the caller's tenant.")
  @APIResponse(responseCode = "200", description = "The invoices")
  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.storeql.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(
                svc.listIntercompanyInvoices(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  /**
   * Reads a single AR or AP intercompany invoice.
   *
   * @param id the invoice to read
   * @return the invoice
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(
      summary = "Get an intercompany invoice",
      description = "Returns a single AR or AP intercompany invoice.")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: the caller is held to neither end of the transfer")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getIntercompanyInvoice(ctx, id)))).build();
  }

  /**
   * Settles an intercompany invoice, posting its nominal-ledger entries.
   *
   * <p>Which entries depends on the side: bank/debtors for AR, creditors/bank for AP.
   *
   * @param id the invoice to settle
   * @return {@code 204} with no body
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(
      summary = "Settle an intercompany invoice",
      description =
          "Posts the settlement nominal ledger entries (bank/debtors or creditors/bank) for the"
              + " given invoice.")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @APIResponse(
      responseCode = "403",
      description =
          "STORE_ACCESS_DENIED: the caller is not held to the side's store (the sender's for"
              + " AR, the receiver's for AP)")
  @POST
  @Path("/{id}/settle")
  public Response settle(@PathParam("id") UUID id) {
    svc.settleIntercompanyInvoice(ctx, id);
    return Response.ok(ApiResponse.ok("settled")).build();
  }
}
