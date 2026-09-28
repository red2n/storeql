package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.BondRelease;
import com.storeql.inventory.dto.Dtos.BondApprovalRequest;
import com.storeql.inventory.dto.Dtos.BondReleaseRequest;
import com.storeql.inventory.dto.Dtos.BondReleasesResponse;
import com.storeql.inventory.dto.Dtos.DutyRateRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.BondService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Bonded and duty-suspended stock: approvals and duty rates are management's; a release to home use
 * is warehouse work, at a store the caller may act at.
 */
@RequestScoped
@Path("/admin/inventory/bond")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Bonded Stock")
public class BondResource {

  private static final String[] MANAGEMENT = {"PLATFORM_ADMIN", "OWNER", "MANAGER"};

  @Inject BondService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Approve a store as a bonded warehouse",
      description =
          "The revenue's approval number and regime (EXCISE or CUSTOMS). Only an approved store"
              + " takes duty-suspended stock. Management only.")
  @APIResponse(responseCode = "200", description = "Approved")
  @APIResponse(responseCode = "400", description = "INVENTORY_BOND_REGIME_INVALID")
  @PUT
  @Path("/approvals/{storeId}")
  public Response approve(@PathParam("storeId") String storeId, BondApprovalRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole(MANAGEMENT);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.approve(ctx, Ids.parse(storeId), req))))
        .build();
  }

  @Operation(summary = "End a store's approval", description = "It takes no more suspended stock.")
  @APIResponse(responseCode = "200", description = "Ended")
  @APIResponse(responseCode = "404", description = "INVENTORY_BOND_APPROVAL_NOT_FOUND")
  @POST
  @Path("/approvals/{storeId}/end")
  public Response end(@PathParam("storeId") String storeId) {
    ctx.requireAnyRole(MANAGEMENT);
    svc.end(ctx, Ids.parse(storeId));
    return Response.ok(ApiResponse.ok("ended")).build();
  }

  @Operation(summary = "List bond approvals", description = "Live ones first.")
  @APIResponse(responseCode = "200", description = "The approvals")
  @GET
  @Path("/approvals")
  public Response approvals() {
    return Response.ok(ApiResponse.ok(svc.approvals(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Set the duty one unit of a variant crystallises",
      description =
          "In the home currency, with a note on how it was arrived at. The platform derives no rate"
              + " from strength or volume. Management only.")
  @APIResponse(responseCode = "200", description = "Set")
  @PUT
  @Path("/duty-rates/{variantId}")
  public Response setRate(@PathParam("variantId") String variantId, DutyRateRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole(MANAGEMENT);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.setRate(ctx, Ids.parse(variantId), req))))
        .build();
  }

  @Operation(summary = "List duty rates")
  @APIResponse(responseCode = "200", description = "The rates")
  @GET
  @Path("/duty-rates")
  public Response rates() {
    return Response.ok(ApiResponse.ok(svc.rates(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Release duty-suspended stock to home use",
      description =
          "Draws the bonded batches FIFO into duty-paid batches of their own (a BOND_RELEASE"
              + " movement), computes the duty at the variant's rate and announces DutyReleased,"
              + " which purchase-svc owes to the revenue. At a store the caller may act at.")
  @APIResponse(responseCode = "201", description = "Released")
  @APIResponse(
      responseCode = "409",
      description = "INVENTORY_STORE_NOT_BONDED, INVENTORY_DUTY_RATE_MISSING")
  @APIResponse(responseCode = "422", description = "INVENTORY_INSUFFICIENT_BONDED_STOCK")
  @POST
  @Path("/releases")
  public Response release(BondReleaseRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.release(ctx, req))))
        .build();
  }

  @Operation(
      summary = "The releases of a period and the duty they add up to",
      description = "The figure an excise return is made from; optionally for one store.")
  @APIResponse(responseCode = "200", description = "The releases and their total duty")
  @GET
  @Path("/releases")
  public Response releases(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    List<BondRelease> releases = svc.releases(ctx, storeId, from, to);
    BigDecimal total = BigDecimal.ZERO;
    for (BondRelease r : releases) total = total.add(r.dutyAmount());
    return Response.ok(
            ApiResponse.ok(
                new BondReleasesResponse(
                    releases.stream().map(Mappers::toDto).toList(), total, svc.currency(ctx))))
        .build();
  }

  @Operation(
      summary = "What sits in bond",
      description =
          "Per store and variant, with the duty it would crystallise at the variant's rate.")
  @APIResponse(responseCode = "200", description = "The stock in bond")
  @GET
  @Path("/stock")
  public Response stock(@QueryParam("storeId") String storeId) {
    return Response.ok(
            ApiResponse.ok(svc.stock(ctx, storeId).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
