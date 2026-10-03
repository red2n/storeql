package com.storeql.tenant.api;

import com.storeql.tenant.dto.BroadcastDtos;
import com.storeql.tenant.mapper.BroadcastMappers;
import com.storeql.tenant.service.BroadcastService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
 * {@code /workforce/broadcasts}: the notices current for whoever is on shift, and their
 * acknowledgement (store operations & workforce).
 *
 * <p><b>Outside {@code /admin/} deliberately</b>, as the clock and the task list are: the people a
 * notice is for are cashiers and storekeepers, and a notice only management could read reaches
 * nobody. Who acknowledged what comes from the token, never from the request.
 *
 * <p>Both are judged at the store named, in this order, as the task list is: the store must be the
 * business's ({@code 404 STORE_NOT_FOUND}), one the caller is held to ({@code 403
 * STORE_ACCESS_DENIED}), and the caller assigned there ({@code 409 WORKFORCE_NOT_ASSIGNED}).
 * Management held to no store (an owner, a business-wide manager) reads and acknowledges a store's
 * notices without being assigned there, as they work its task list: a notice reaches them there
 * when it is to everybody, or to a role they hold there or to their own tier.
 */
@Path("/workforce/broadcasts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Time Clock")
public class BroadcastReadResource {

  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  /** The tiers that act at any store when held to none. */
  private static final List<String> MANAGEMENT = List.of("OWNER", "MANAGER");

  @Inject BroadcastService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The notices current for me at a store",
      description =
          "Newest first, each with whether I have acknowledged it. Withdrawn and expired notices are"
              + " not here. An owner or a manager held to no store reads any store's notices"
              + " without being assigned there.")
  @APIResponse(
      responseCode = "400",
      description = "TASK_ID_INVALID: storeId is missing or not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN for a caller who is not staff; STORE_ACCESS_DENIED for a store the caller is"
              + " not held to")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business")
  @APIResponse(
      responseCode = "409",
      description = "WORKFORCE_NOT_ASSIGNED: not assigned at that store")
  @GET
  public ApiResponse<List<BroadcastDtos.BroadcastResponse>> current(
      @QueryParam("storeId") String storeId) {
    ctx.requireAnyRole(STAFF);
    UUID store = StoreTaskResource.uuid(storeId, "storeId");
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(svc.requireStore(tenantId, store));
    return ApiResponse.ok(
        svc.current(tenantId, store, ctx.requireUserId(), anywhereAs()).stream()
            .map(s -> BroadcastMappers.toDto(s.broadcast(), s.acknowledgedAt()))
            .toList());
  }

  @Operation(
      summary = "Acknowledge a notice",
      description =
          "Once. A second tap is a conflict, not a second reading — the constraint decides. An"
              + " owner or a manager held to no store acknowledges at any store without being"
              + " assigned there.")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED or BODY_REQUIRED; TASK_ID_INVALID: storeId is not an id; INVALID_UUID:"
              + " the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN for a caller who is not staff; STORE_ACCESS_DENIED for a store the caller is"
              + " not held to")
  @APIResponse(
      responseCode = "404",
      description =
          "STORE_NOT_FOUND: no such store in this business; BROADCAST_NOT_FOUND: no such notice in"
              + " it")
  @APIResponse(
      responseCode = "409",
      description =
          "WORKFORCE_NOT_ASSIGNED: not assigned at that store; BROADCAST_ALREADY_ACKNOWLEDGED,"
              + " BROADCAST_NOT_CURRENT, BROADCAST_NOT_ADDRESSED")
  @POST
  @Path("/{id}/acknowledgement")
  public Response acknowledge(@PathParam("id") UUID id, BroadcastDtos.AckRequest req) {
    ctx.requireAnyRole(STAFF);
    Validations.validate(req);
    UUID store = StoreTaskResource.uuid(req.storeId(), "storeId");
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(svc.requireStore(tenantId, store));
    var ack = svc.acknowledge(tenantId, id, store, ctx.requireUserId(), anywhereAs());
    return Response.status(201).entity(ApiResponse.ok(ack.ackedAt().toString())).build();
  }

  /**
   * The management tiers a caller acts as at any store of the business: an owner or a manager held
   * to no store. Empty for everybody else — staff below management are held to where they are
   * assigned, even should their token name no store.
   */
  private List<String> anywhereAs() {
    if (!ctx.storeIds().isEmpty()) return List.of();
    return MANAGEMENT.stream().filter(ctx::hasRole).toList();
  }
}
