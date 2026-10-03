package com.storeql.pricing.api;

import com.storeql.pricing.dto.Dtos.RecordTaxTransactionRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** POSLog-compatible tax transaction journal per HMRC VAT Notice 700. */
@RequestScoped
@Path("/tax-transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Tax Transactions")
public class TaxTransactionResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Records one line's tax position for the VAT return and tax summary.
   *
   * <p>Append-only: figures are stamped as at the tax point, so a later rate change does not
   * rewrite what was charged.
   *
   * @param req the order, line, variant, store, VAT code and rate, amounts, exempt flag, tax point
   *     and invoice reference
   * @return the recorded transaction
   */
  @Operation(
      summary = "Record a tax transaction",
      description =
          "Appends a POSLog-compatible tax transaction journal entry (net/VAT/gross per order"
              + " line) per HMRC VAT Notice 700.")
  @APIResponse(responseCode = "201", description = "Tax transaction recorded")
  @APIResponse(
      responseCode = "503",
      description =
          "TENANT_PROFILE_UNAVAILABLE: the amounts are kept to the business currency's minor"
              + " units, and the currency could not be read")
  @POST
  public Response record(RecordTaxTransactionRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.recordTaxTransaction(req, ctx))))
        .build();
  }

  /**
   * The tax lines recorded against one order.
   *
   * @param orderId the order whose tax lines to read
   * @return the transactions, empty when none were recorded
   */
  @Operation(
      summary = "List tax transactions for an order",
      description = "All tax transaction journal entries recorded for the given order.")
  @APIResponse(responseCode = "200", description = "List of tax transactions")
  @APIResponse(responseCode = "400", description = "orderId query param missing")
  @APIResponse(responseCode = "403", description = "Caller has no staff role")
  @GET
  public Response listByOrder(@QueryParam("orderId") UUID orderId) {
    // Any order's tax lines, addressable by id, and this path is not under /admin/ — so nothing
    // gated it. Staff rather than management: a cashier querying a receipt is legitimate, a
    // signed-in customer reading another customer's order is not.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    if (orderId == null)
      throw ApiException.badRequest("PRICING_MISSING_ORDER_ID", "orderId query param required");
    return Response.ok(
            ApiResponse.ok(
                svc.listTaxTransactionsByOrder(ctx, orderId).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
