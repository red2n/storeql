package com.storeql.order.api;

import com.storeql.order.dto.Dtos.CreateFulfilmentWindowRequest;
import com.storeql.order.dto.Dtos.FulfilmentWindowResponse;
import com.storeql.order.dto.Dtos.UpdateFulfilmentWindowRequest;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.FulfilmentWindowService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A store's delivery and collection windows (intent/delivery-and-collection-slots.md).
 * Management-only (OWNER, MANAGER, PLATFORM_ADMIN — enforced by {@code AdminAuthorizationFilter}
 * for every path under {@code /admin/}); a manager held to stores may only set the windows of the
 * ones they hold ({@link TenantContext#requireStoreAccess}).
 */
@ApplicationScoped
@Path("/admin/fulfilment-windows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Fulfilment windows")
public class FulfilmentWindowAdminResource {

  @Inject FulfilmentWindowService svc;
  @Inject TenantContext ctx;

  /**
   * A store's windows, every type and weekday.
   *
   * @throws ApiException 400 {@code ORDER_SLOT_STORE_REQUIRED}; 403 {@code STORE_ACCESS_DENIED};
   *     404 {@code ORDER_SLOT_STORE_NOT_FOUND} (not the business's store — including another
   *     business's store id)
   */
  @Operation(
      summary = "List a store's fulfilment windows",
      description = "Every window of one store, delivery and collection, every weekday.")
  @APIResponse(responseCode = "200", description = "The store's windows")
  @GET
  public ApiResponse<List<FulfilmentWindowResponse>> list(@QueryParam("storeId") String storeId) {
    if (storeId == null || storeId.isBlank()) {
      throw ApiException.badRequest("ORDER_SLOT_STORE_REQUIRED", "storeId is required");
    }
    UUID tenantId = ctx.requireTenantId();
    UUID store = Parsing.uuid(storeId, "storeId");
    var rows = svc.list(tenantId, ctx, store);
    return ApiResponse.ok(rows.stream().map(Mappers::toDto).toList());
  }

  /**
   * Sets a new window.
   *
   * @throws ApiException 403 {@code STORE_ACCESS_DENIED}; 404 {@code ORDER_SLOT_STORE_NOT_FOUND};
   *     400 {@code ORDER_SLOT_WINDOW_INVALID} (bad shape, or overlapping another active window of
   *     the same store, type and weekday)
   */
  @Operation(
      summary = "Set a fulfilment window",
      description =
          "A new weekly window for one store and fulfilment type (DELIVERY or PICKUP). Refused if"
              + " it overlaps another active window of the same store, type and weekday.")
  @APIResponse(responseCode = "201", description = "The window")
  @APIResponse(responseCode = "400", description = "ORDER_SLOT_WINDOW_INVALID")
  @APIResponse(responseCode = "404", description = "ORDER_SLOT_STORE_NOT_FOUND")
  @POST
  public Response create(CreateFulfilmentWindowRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    var row =
        svc.create(
            tenantId,
            ctx,
            storeId,
            req.fulfilmentType(),
            req.weekday(),
            req.startTime(),
            req.endTime(),
            req.capacity(),
            req.cutoffMinutes(),
            req.active(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(row))).build();
  }

  /**
   * Changes a window's shape. Its store and fulfilment type never change.
   *
   * @throws ApiException 404 {@code ORDER_SLOT_WINDOW_NOT_FOUND} (unknown id, or another
   *     business's); 403 {@code STORE_ACCESS_DENIED}; 400 {@code ORDER_SLOT_WINDOW_INVALID}
   */
  @Operation(
      summary = "Change a fulfilment window",
      description = "weekday, startTime, endTime, capacity, cutoffMinutes and active. ")
  @APIResponse(responseCode = "200", description = "The window")
  @APIResponse(responseCode = "404", description = "ORDER_SLOT_WINDOW_NOT_FOUND")
  @PUT
  @Path("/{id}")
  public ApiResponse<FulfilmentWindowResponse> update(
      @PathParam("id") String id, UpdateFulfilmentWindowRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID windowId = Parsing.uuid(id, "id");
    var row =
        svc.update(
            tenantId,
            ctx,
            windowId,
            req.weekday(),
            req.startTime(),
            req.endTime(),
            req.capacity(),
            req.cutoffMinutes(),
            req.active(),
            ctx.requireUserId());
    return ApiResponse.ok(Mappers.toDto(row));
  }
}
