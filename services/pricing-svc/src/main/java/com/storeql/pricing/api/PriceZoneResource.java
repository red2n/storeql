package com.storeql.pricing.api;

import com.storeql.ids.Ids;
import com.storeql.pricing.dto.Dtos.AssignZoneStoresRequest;
import com.storeql.pricing.dto.Dtos.CreatePriceZoneRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.RepricingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Permissions;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Price zones (03.x): the groups of stores that price alike. Management only. */
@RequestScoped
@Path("/admin/price-zones")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Zones")
public class PriceZoneResource {

  @Inject RepricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a price zone",
      description =
          "A group of stores that price alike. A price list bound to the zone (zoneId on POST"
              + " /admin/price-lists) is what its stores charge; every other store falls back to"
              + " the tenant-wide list. Management only.")
  @APIResponse(responseCode = "201", description = "Price zone created")
  @APIResponse(responseCode = "409", description = "PRICING_ZONE_NAME_EXISTS")
  @POST
  public Response create(CreatePriceZoneRequest req) {
    Validations.validate(req);
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createZone(ctx, req))))
        .build();
  }

  @Operation(summary = "List price zones", description = "Every zone with the stores in it.")
  @APIResponse(responseCode = "200", description = "The zones")
  @GET
  public Response list() {
    return Response.ok(ApiResponse.ok(svc.listZones(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Set a zone's stores",
      description =
          "Replaces the zone's membership. A store is in one zone at most, so naming it here moves"
              + " it out of any other; a store that is not the business's is refused"
              + " (PRICING_ZONE_STORE_UNKNOWN).")
  @APIResponse(responseCode = "200", description = "The zone with its stores")
  @APIResponse(responseCode = "404", description = "PRICING_ZONE_NOT_FOUND")
  @PUT
  @Path("/{id}/stores")
  public Response assignStores(@PathParam("id") String id, AssignZoneStoresRequest req) {
    Validations.validate(req);
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.assignStores(ctx, Ids.parse(id), req))))
        .build();
  }
}
