package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.RaiseVendorReturnRequest;
import com.storeql.purchase.dto.Dtos.RecordCreditNoteRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.PurchaseService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Return to vendor and debit notes (07.8): goods going back against a received purchase order, the
 * debit note raised for their value, and the supplier's credit note that closes it.
 */
@RequestScoped
@Path("/vendor-returns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Vendor Returns")
public class VendorReturnResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  /**
   * Sends goods back and raises the debit note.
   *
   * @param idempotencyKey the caller's replay guard
   * @param req what is going back and why
   * @return the return, numbered, with its lines
   */
  @Operation(
      summary = "Return goods to the supplier and raise the debit note",
      description =
          "Against a purchase order something was received on. The store is the order's; each"
              + " line is priced at the order's price for the variant with VAT at the order's VAT"
              + " code, and the quantity may not exceed what was received less what already went"
              + " back. Stock leaves the store through ReturnedToVendor, which inventory-svc"
              + " consumes; on-hand is checked first when inventory-svc can be reached. The"
              + " purchase order's status is untouched — the debit note offsets the invoice."
              + " Idempotency-Key makes a retried raise safe. Warehouse and management roles;"
              + " the caller must be assigned to the order's store.")
  @APIResponse(responseCode = "201", description = "The return and its debit note")
  @APIResponse(responseCode = "400", description = "An unknown reason, or no lines")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "Nothing was received on this order")
  @APIResponse(
      responseCode = "422",
      description = "A variant not on the order, more than was received, or not on hand")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: the caller is held to stores that are not the order's")
  @POST
  public Response raise(
      @HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      RaiseVendorReturnRequest req) {
    Validations.validate(req);
    var ret = svc.raiseVendorReturn(req, ctx, idempotencyKey);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(ret, svc.vendorReturnLines(ctx, ret.id()))))
        .build();
  }

  /**
   * @param poId an order, or nothing for every return in the business
   * @return the returns, newest first, each with its lines
   */
  @Operation(
      summary = "List returns to vendor",
      description = "Optionally ?poId=. Newest first. Any staff role.")
  @APIResponse(responseCode = "200", description = "The returns")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: the order named is another store's than the caller's")
  @GET
  public Response list(@QueryParam("poId") UUID poId) {
    var returns = svc.listVendorReturns(ctx, poId);
    var lines = svc.vendorReturnLinesOf(ctx, returns);
    List<Object> out =
        returns.stream()
            .map(r -> (Object) Mappers.toDto(r, lines.getOrDefault(r.id(), List.of())))
            .toList();
    return Response.ok(ApiResponse.ok(out)).build();
  }

  /**
   * @param id the return
   * @return the return with its lines: the debit note as a document
   */
  @Operation(
      summary = "One return to vendor — the debit note",
      description = "Another tenant's is 404. Any staff role.")
  @APIResponse(responseCode = "200", description = "The return")
  @APIResponse(responseCode = "404", description = "Not this business's")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: the return is from another store than the caller's")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    var ret = svc.getVendorReturn(ctx, id);
    return Response.ok(ApiResponse.ok(Mappers.toDto(ret, svc.vendorReturnLines(ctx, id)))).build();
  }

  /**
   * Records the supplier's credit note against a return.
   *
   * @param id the return
   * @param req the credit note's number, date and amount
   * @return the return, now CREDITED
   */
  @Operation(
      summary = "Record the supplier's credit note",
      description =
          "Closes the return: RAISED becomes CREDITED with the credit note's number, date and"
              + " amount (the debit note's gross when omitted). Once only — a second credit note is"
              + " refused with the first one named. Management-only.")
  @APIResponse(responseCode = "200", description = "The return, credited")
  @APIResponse(
      responseCode = "400",
      description =
          "A date that is not a date, or PURCHASE_AMOUNT_TOO_PRECISE: an amount finer than the"
              + " return's currency (refused, never rounded)")
  @APIResponse(responseCode = "404", description = "Not this business's")
  @APIResponse(responseCode = "409", description = "Already credited")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role, or STORE_ACCESS_DENIED: the return is from another store than"
              + " the caller's")
  @POST
  @Path("/{id}/credit")
  public Response credit(@PathParam("id") UUID id, RecordCreditNoteRequest req) {
    Validations.validate(req);
    var ret = svc.recordCreditNote(ctx, id, req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(ret, svc.vendorReturnLines(ctx, id)))).build();
  }
}
