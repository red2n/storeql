package com.storeql.order.api;

import com.storeql.order.dto.Dtos.NoSaleRequest;
import com.storeql.order.dto.Dtos.ParkSaleRequest;
import com.storeql.order.dto.Dtos.ParkedSaleResponse;
import com.storeql.order.service.ParkedSaleService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
 * Parked (suspended) sales — allows a cashier to hold an in-progress sale and serve the next
 * customer, then resume. Also handles no-sale / open-drawer logging.
 */
@Path("/pos")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "POS Operations")
public class ParkedSaleResource {

  @Inject ParkedSaleService svc;
  @Inject TenantContext ctx;

  /**
   * Holds an in-progress cashier sale so the next customer can be served.
   *
   * @param req the store and the basket as rung so far
   * @return {@code 201} with the parked sale
   * @throws com.storeql.web.ApiException {@code 403} when the caller holds no POS-eligible role
   */
  @Operation(
      summary = "Park a sale",
      description =
          "Holds an in-progress cashier sale so the next customer can be served. Requires CASHIER,"
              + " MANAGER, or OWNER.")
  @APIResponse(responseCode = "201", description = "Sale parked")
  @APIResponse(
      responseCode = "400",
      description =
          "PARK_EMPTY; VALIDATION_FAILED for a unitPrice or discountAmount with more decimals"
              + " than the business's currency has")
  @APIResponse(responseCode = "403", description = "Caller lacks a POS-eligible role")
  @APIResponse(
      responseCode = "503",
      description =
          "TENANT_PROFILE_UNAVAILABLE: the business's currency, whose minor units the money is"
              + " kept to, is neither projected here nor readable from tenant-svc")
  @POST
  @Path("/parked-sales")
  public Response park(ParkSaleRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID cashierId = ctx.userId();
    var sale = svc.park(tenantId, cashierId, req, ctx);
    return Response.status(201)
        .entity(ApiResponse.ok(sale, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * The tenant's active parked sales, for a cashier picking one back up.
   *
   * @param storeId restrict to one store, or {@code null} for every store the caller keeps
   * @return the parked sales
   * @throws com.storeql.web.ApiException {@code 403} when the caller holds no POS-eligible role
   */
  @Operation(
      summary = "List parked sales",
      description = "Active parked sales for the tenant, optionally filtered by store.")
  @APIResponse(responseCode = "200", description = "List of parked sales")
  @APIResponse(responseCode = "403", description = "Caller lacks a POS-eligible role")
  @GET
  @Path("/parked-sales")
  public ApiResponse<List<ParkedSaleResponse>> listParked(@QueryParam("storeId") String storeId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    UUID sid = storeId == null ? null : Parsing.uuid(storeId, "storeId");
    var sales = svc.list(tenantId, sid, ctx);
    return ApiResponse.ok(sales, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Reads one parked sale, to resume it at the till.
   *
   * @param id the parked sale to read
   * @return the parked sale with its basket
   * @throws com.storeql.web.ApiException {@code 403} when the caller holds no POS-eligible role;
   *     {@code 404} when no such parked sale exists in the tenant
   */
  @Operation(summary = "Get a parked sale by id", description = "Retrieves a single parked sale.")
  @APIResponse(responseCode = "200", description = "Parked sale found")
  @APIResponse(responseCode = "403", description = "Caller lacks a POS-eligible role")
  @APIResponse(responseCode = "404", description = "Parked sale not found")
  @GET
  @Path("/parked-sales/{id}")
  public ApiResponse<ParkedSaleResponse> getParked(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(svc.get(tenantId, id, ctx), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Picks a parked sale back up, recording who did it.
   *
   * <p>The sale leaves the open list; the till then rings its basket up as an ordinary sale. The
   * cashier who resumes it need not be the one who parked it, and the record says which is which.
   *
   * @param id the parked sale to resume
   * @return the parked sale with its basket, who parked it and who resumed it
   * @throws com.storeql.web.ApiException {@code 403} when the caller holds no POS-eligible role or
   *     keeps none of the sale's stores; {@code 404} when no such open parked sale exists in the
   *     tenant; {@code 409 PARKED_SALE_NOT_OPEN} when another till resumed it first
   */
  @Operation(
      summary = "Resume a parked sale",
      description =
          "Takes a parked sale off the open list and records who picked it up and when. The"
              + " basket is returned so the till can ring it up.")
  @APIResponse(responseCode = "200", description = "Parked sale resumed")
  @APIResponse(responseCode = "403", description = "Not a POS-eligible role, or another store")
  @APIResponse(responseCode = "404", description = "Parked sale not found")
  @APIResponse(responseCode = "409", description = "PARKED_SALE_NOT_OPEN: resumed already")
  @POST
  @Path("/parked-sales/{id}/resume")
  public ApiResponse<ParkedSaleResponse> resume(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(svc.resume(tenantId, id, ctx), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Discards a parked sale without resuming it.
   *
   * <p>Nothing was sold and no stock was committed, so there is nothing to reverse.
   *
   * @param id the parked sale to discard; the row is kept, with who discarded it and when
   * @return {@code 204} with no body
   * @throws com.storeql.web.ApiException {@code 403} when the caller holds no POS-eligible role;
   *     {@code 404} when no such parked sale exists in the tenant
   */
  @Operation(
      summary = "Cancel/discard a parked sale",
      description = "Removes a parked sale without resuming it.")
  @APIResponse(responseCode = "204", description = "Parked sale removed")
  @APIResponse(responseCode = "403", description = "Caller lacks a POS-eligible role")
  @APIResponse(responseCode = "404", description = "Parked sale not found")
  @DELETE
  @Path("/parked-sales/{id}")
  public Response cancel(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    svc.cancel(tenantId, id, ctx);
    return Response.noContent().build();
  }

  /**
   * Records a cash-drawer open with no accompanying sale.
   *
   * <p>Audit exists precisely because an unexplained drawer open is how cash leaves a till without
   * a transaction to show for it.
   *
   * @param req the store and the stated reason for opening the drawer
   * @return {@code 201} with the logged entry
   * @throws com.storeql.web.ApiException {@code 403} when the caller holds no POS-eligible role
   */
  @Operation(
      summary = "Log a no-sale / open-drawer event",
      description = "Records a cash-drawer open with no accompanying sale, for audit purposes.")
  @APIResponse(responseCode = "201", description = "No-sale logged")
  @APIResponse(responseCode = "403", description = "Caller lacks a POS-eligible role")
  @POST
  @Path("/no-sale")
  public Response logNoSale(NoSaleRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    // A trainee is a cashier who cannot open the drawer without a sale (20.10).
    ctx.requirePermission(com.storeql.web.Permissions.TILL_NO_SALE);
    UUID tenantId = ctx.requireTenantId();
    UUID cashierId = ctx.userId();
    var entry = svc.logNoSale(tenantId, cashierId, req);
    return Response.status(201)
        .entity(ApiResponse.ok(entry, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
