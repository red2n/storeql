package com.storeql.tenant.api;

import com.storeql.tenant.domain.Broadcasts.Broadcast;
import com.storeql.tenant.dto.BroadcastDtos;
import com.storeql.tenant.mapper.BroadcastMappers;
import com.storeql.tenant.service.BroadcastService;
import com.storeql.web.ApiException;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/workforce/broadcasts}: what management tells the shop floor, and how far it reached
 * (store operations & workforce). The staff half — reading and acknowledging — is {@link
 * BroadcastReadResource}, outside {@code /admin/}.
 */
@Path("/admin/workforce/broadcasts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Workforce")
public class BroadcastResource {

  @Inject BroadcastService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Publish a notice",
      description =
          "To one store or every open store, to everybody there or one role. Announced to each"
              + " store's devices in the same transaction; only an URGENT notice wakes them. Never"
              + " edited: withdraw and publish again, so what was acknowledged is what was seen. A"
              + " manager held to stores publishes to one of theirs; a notice to every store is the"
              + " whole business's, and needs a caller held to none.")
  @APIResponse(responseCode = "201", description = "The notice")
  @APIResponse(
      responseCode = "400",
      description =
          "BROADCAST_INVALID, BROADCAST_EXPIRY_INVALID, TASK_ID_INVALID (storeId), VALIDATION_FAILED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store of the business the caller"
              + " is not held to; BUSINESS_WIDE_ONLY for a notice to every store from a caller held"
              + " to stores")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business, or not open")
  @POST
  public Response publish(BroadcastDtos.PublishRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank()
            ? null
            : StoreTaskResource.uuid(req.storeId(), "storeId");
    Instant expiresAt = instant(req.expiresAt());
    BroadcastService.requirePublishable(
        req.title(), req.body(), req.priority(), expiresAt, Instant.now());
    UUID tenantId = ctx.requireTenantId();
    // The request (400), then the store is the business's (404), then one the caller is held to
    // (403) — so another business's manager naming our store is told it does not exist. Every
    // store is the whole business's, which reaches stores a manager held to some cannot see.
    if (storeId == null) BusinessWide.require(ctx);
    else ctx.requireStoreAccess(svc.requireStore(tenantId, storeId));
    var notice =
        svc.publish(
            tenantId,
            req.title(),
            req.body(),
            req.priority(),
            storeId,
            req.role(),
            Boolean.TRUE.equals(req.requiresAck()),
            expiresAt,
            ctx.requireUserId());
    return Response.status(201)
        .entity(ApiResponse.ok(BroadcastMappers.toDto(notice, null)))
        .build();
  }

  @Operation(
      summary = "The business's notices, newest first",
      description =
          "Published ones unless all=true. A manager held to stores reads their stores' notices"
              + " and those to every store; a caller held to none reads them all.")
  @APIResponse(responseCode = "403", description = "FORBIDDEN below management")
  @GET
  public ApiResponse<List<BroadcastDtos.BroadcastResponse>> list(
      @QueryParam("all") Boolean all, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc
            .broadcasts(
                ctx.requireTenantId(), !Boolean.TRUE.equals(all), limit, ctx.reportStores(null))
            .stream()
            .map(b -> BroadcastMappers.toDto(b, null))
            .toList());
  }

  @Operation(
      summary = "One notice",
      description = "A notice to one store is read at that store; one to every store by anybody.")
  @APIResponse(responseCode = "400", description = "INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a notice to a store the caller is not"
              + " held to")
  @APIResponse(responseCode = "404", description = "BROADCAST_NOT_FOUND")
  @GET
  @Path("/{id}")
  public ApiResponse<BroadcastDtos.BroadcastResponse> one(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(BroadcastMappers.toDto(readable(id), null));
  }

  @Operation(
      summary = "How far a notice reached",
      description =
          "Per store: how many it is addressed to, how many acknowledged, and who has not — named."
              + " A manager held to stores reads only their stores' rows, so a notice to every"
              + " store names nobody at a store they cannot see.")
  @APIResponse(responseCode = "400", description = "INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a notice to a store the caller is not"
              + " held to")
  @APIResponse(responseCode = "404", description = "BROADCAST_NOT_FOUND")
  @GET
  @Path("/{id}/reach")
  public ApiResponse<List<BroadcastDtos.ReachResponse>> reach(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    readable(id);
    return ApiResponse.ok(
        svc.reach(ctx.requireTenantId(), id, ctx.reportStores(null)).stream()
            .map(BroadcastMappers::toDto)
            .toList());
  }

  @Operation(
      summary = "Withdraw a notice, with the reason",
      description =
          "Its acknowledgements stay: they were made against the text that stood. A notice to one"
              + " store is withdrawn at that store; one to every store needs a caller held to none,"
              + " as publishing it did.")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED or BODY_REQUIRED: no reason; INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a notice to a store the caller is not"
              + " held to; BUSINESS_WIDE_ONLY for a notice to every store from a caller held to"
              + " stores")
  @APIResponse(responseCode = "404", description = "BROADCAST_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "BROADCAST_WITHDRAWN")
  @POST
  @Path("/{id}/withdrawal")
  public ApiResponse<BroadcastDtos.BroadcastResponse> withdraw(
      @PathParam("id") UUID id, BroadcastDtos.WithdrawRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    // The notice must be the business's (404), then the caller's to change (403), before it moves.
    UUID storeId = svc.broadcast(tenantId, id).storeId();
    if (storeId == null) BusinessWide.require(ctx);
    else ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(
        BroadcastMappers.toDto(
            svc.withdraw(tenantId, id, req.reason(), ctx.requireUserId()), null));
  }

  /**
   * A notice the caller may read: the business's (404), and, when it is to one store, one of the
   * caller's (403). A notice to every store reaches every caller's stores, so anybody may read it.
   */
  private Broadcast readable(UUID id) {
    Broadcast b = svc.broadcast(ctx.requireTenantId(), id);
    if (b.storeId() != null) ctx.requireStoreAccess(b.storeId());
    return b;
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400,
          "BROADCAST_EXPIRY_INVALID",
          "expiresAt is an ISO-8601 instant: " + value,
          List.of(),
          e);
    }
  }
}
