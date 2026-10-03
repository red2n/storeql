package com.storeql.order.api;

import com.storeql.order.dto.Dtos.DepositReportResponse;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Deposits charged and paid back over a period (09.16): what a scheme administrator asks the
 * retailer for, and, where the deposit is outside the scope of VAT, what the VAT return leaves out.
 */
@RequestScoped
@Path("/admin/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reports")
public class DepositReportResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Deposits charged on sales that stand and refunded at the till, by material.
   *
   * @param storeId a store that is named is checked against the caller's own (SJ-D74's {@code
   *     reportStores}) and refused with {@code 403 STORE_ACCESS_DENIED} otherwise; with none named,
   *     a caller held to no store reads the whole business and a caller held to some reads exactly
   *     those, added together
   * @param from inclusive start as a full ISO-8601 instant
   * @param to exclusive end as a full ISO-8601 instant
   * @return the totals and one row per material
   */
  @Operation(
      summary = "Container deposits charged and refunded",
      description =
          "Deposits a return scheme put on drinks containers sold in the period, less those paid"
              + " back at the till, by material: what the scheme holds unredeemed and, where it"
              + " taxes the deposit, the VAT inside it. Sales cancelled or voided do not count."
              + " OWNER or MANAGER.")
  @APIResponse(responseCode = "200", description = "The period's deposits")
  @APIResponse(responseCode = "400", description = "Unparseable timestamp or from not before to")
  @GET
  @Path("/deposits")
  public ApiResponse<DepositReportResponse> deposits(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(svc.depositReport(ctx, storeId, from, to));
  }
}
