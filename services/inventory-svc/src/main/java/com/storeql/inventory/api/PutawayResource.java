package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.dto.WaveDtos.PlaceRequest;
import com.storeql.inventory.dto.WaveDtos.PutawayRuleRequest;
import com.storeql.inventory.mapper.WaveMappers;
import com.storeql.inventory.service.PutawayService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Directed putaway: the rules that place stock arriving with no zone (management's), and the list
 * of batches no rule placed, for a storekeeper to place ({@code stock.transfer}).
 */
@RequestScoped
@Path("/admin/inventory/putaway")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Directed Putaway")
public class PutawayResource {

  @Inject PutawayService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Set a putaway rule",
      description =
          "Where a product goes when it arrives at the store with no zone; without a variant, the"
              + " store's default for anything no rule names. Replaces an earlier rule for the same"
              + " product. Management only.")
  @APIResponse(responseCode = "200", description = "The rule")
  @PUT
  @Path("/rules")
  public Response setRule(PutawayRuleRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(WaveMappers.toDto(svc.setRule(ctx, req)))).build();
  }

  @Operation(summary = "List a store's putaway rules", description = "The default first.")
  @APIResponse(responseCode = "200", description = "The rules")
  @GET
  @Path("/rules")
  public Response rules(@QueryParam("storeId") String storeId) {
    return Response.ok(
            ApiResponse.ok(
                svc.rules(ctx, Ids.parse(storeId)).stream().map(WaveMappers::toDto).toList()))
        .build();
  }

  @Operation(summary = "Remove a putaway rule")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "404", description = "INVENTORY_PUTAWAY_RULE_NOT_FOUND")
  @DELETE
  @Path("/rules/{id}")
  public Response deleteRule(@PathParam("id") String id) {
    svc.deleteRule(ctx, Ids.parse(id));
    return Response.noContent().build();
  }

  @Operation(
      summary = "The batches waiting to be placed",
      description = "Stock that arrived with no zone and no rule to place it, oldest first.")
  @APIResponse(responseCode = "200", description = "The tasks")
  @GET
  @Path("/tasks")
  public Response tasks(@QueryParam("storeId") String storeId) {
    return Response.ok(
            ApiResponse.ok(
                svc
                    .openTasks(
                        ctx, storeId == null || storeId.isBlank() ? null : Ids.parse(storeId))
                    .stream()
                    .map(WaveMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Place a batch",
      description = "In the zone named, or the one suggested; placed once.")
  @APIResponse(responseCode = "200", description = "Placed")
  @APIResponse(responseCode = "400", description = "INVENTORY_PUTAWAY_ZONE_REQUIRED")
  @APIResponse(responseCode = "404", description = "INVENTORY_PUTAWAY_TASK_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "INVENTORY_PUTAWAY_TASK_PLACED")
  @POST
  @Path("/tasks/{id}/place")
  public Response place(@PathParam("id") String id, PlaceRequest req) {
    return Response.ok(
            ApiResponse.ok(
                WaveMappers.toDto(
                    svc.place(ctx, Ids.parse(id), req == null ? null : req.zoneId()))))
        .build();
  }
}
