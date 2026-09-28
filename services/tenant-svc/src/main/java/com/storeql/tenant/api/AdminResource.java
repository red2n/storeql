package com.storeql.tenant.api;

import com.storeql.tenant.dto.Dtos.AssignStaffRequest;
import com.storeql.tenant.dto.Dtos.CreateStoreRequest;
import com.storeql.tenant.dto.Dtos.CreateZoneRequest;
import com.storeql.tenant.dto.Dtos.PatchStatusRequest;
import com.storeql.tenant.dto.Dtos.StaffResponse;
import com.storeql.tenant.dto.Dtos.StoreResponse;
import com.storeql.tenant.dto.Dtos.TenantResponse;
import com.storeql.tenant.dto.Dtos.UpdateStoreRequest;
import com.storeql.tenant.dto.Dtos.UpdateTenantRequest;
import com.storeql.tenant.dto.Dtos.UpdateZoneRequest;
import com.storeql.tenant.dto.Dtos.ZoneResponse;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.service.TenantService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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

/** Admin endpoints for tenant profile, stores, zones, and staff. All tenant-scoped. */
@Path("/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Admin")
public class AdminResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  // ── tenant profile ───────────────────────────────────────────────────────

  /**
   * Returns the caller's own tenant, resolved from the JWT.
   *
   * @return the tenant profile
   * @throws com.storeql.web.ApiException {@code 404} when the tenant no longer exists
   */
  @Operation(summary = "Get the tenant profile", description = "Returns the caller's tenant.")
  @APIResponse(responseCode = "404", description = "Tenant not found")
  @GET
  @Path("/tenant")
  public ApiResponse<TenantResponse> getTenant() {
    return ApiResponse.ok(Mappers.toTenant(service.getTenant(ctx.requireTenantId())));
  }

  /**
   * Renames the caller's tenant.
   *
   * <p>Country and currency are fixed at onboarding and cannot be changed here.
   *
   * @param req the new business name and optional legal name
   * @return the updated tenant profile
   * @throws com.storeql.web.ApiException {@code 404} when the tenant no longer exists
   */
  @Operation(
      summary = "Update the tenant profile",
      description = "Updates business name and legal name for the caller's tenant.")
  @APIResponse(responseCode = "404", description = "Tenant not found")
  @PUT
  @Path("/tenant")
  public ApiResponse<TenantResponse> updateTenant(UpdateTenantRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toTenant(service.updateTenant(ctx.requireTenantId(), req)));
  }

  // ── stores ───────────────────────────────────────────────────────────────

  /**
   * Cursor-paginated list of the tenant's stores.
   *
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of stores, with the next cursor in {@code meta}
   */
  @Operation(
      summary = "List the tenant's stores",
      description = "Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100.")
  @APIResponse(
      responseCode = "200",
      description = "The page of stores, with the next cursor in {@code meta}")
  @GET
  @Path("/stores")
  public ApiResponse<List<StoreResponse>> listStores(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page = service.listStores(ctx.requireTenantId(), after, Cursor.clampLimit(limit));
    var stores = page.items().stream().map(Mappers::toStore).toList();
    return ApiResponse.ok(stores, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Adds a store to the caller's tenant, together with its DEFAULT zone.
   *
   * <p>The zone comes automatically so stock always has somewhere to sit.
   *
   * @param req the store's name, code, type, address and trading settings
   * @return {@code 201} with the created store
   */
  @Operation(
      summary = "Add a store",
      description = "Adds a new store (+ its DEFAULT zone) to the caller's tenant.")
  @APIResponse(responseCode = "201", description = "Store created")
  @POST
  @Path("/stores")
  public Response addStore(CreateStoreRequest req) {
    Validations.validate(req);
    var result = service.addStore(ctx.requireTenantId(), req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toStore(result.store())))
        .build();
  }

  /**
   * Reads a single store.
   *
   * @param storeId the store to read
   * @return the store
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(summary = "Get a store", description = "Returns a single store in the tenant.")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @GET
  @Path("/stores/{storeId}")
  public ApiResponse<StoreResponse> getStore(@PathParam("storeId") UUID storeId) {
    return ApiResponse.ok(Mappers.toStore(service.getStore(ctx.requireTenantId(), storeId)));
  }

  /**
   * Updates a store's address, geo, hours, price visibility and enabled tenders.
   *
   * <p>Omitting {@code showPrices} or {@code enabledPaymentMethods} keeps the current value, so a
   * partial update cannot silently switch the shop to catalogue-only or strip its tenders.
   *
   * @param storeId the store to update
   * @param req the replacement details
   * @return the updated store
   * @throws com.storeql.web.ApiException {@code 400} when the tender list is empty or names an
   *     unknown method; {@code 404} when the store does not exist in the caller's tenant
   */
  @Operation(
      summary = "Update a store",
      description =
          "Updates store address, geo, hours, price visibility, and enabled payment methods.")
  @APIResponse(responseCode = "400", description = "Invalid enabled payment methods")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @PUT
  @Path("/stores/{storeId}")
  public ApiResponse<StoreResponse> updateStore(
      @PathParam("storeId") UUID storeId, UpdateStoreRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toStore(service.updateStore(ctx.requireTenantId(), storeId, req)));
  }

  /**
   * Changes a store's trading status and announces it.
   *
   * <p>Publishes {@code StoreStatusChanged} so iam-svc can terminate that store's POS sessions and
   * cart/order-svc stop accepting trade against it.
   *
   * @param storeId the store whose status to change
   * @param req the new status
   * @return the store with its new status
   * @throws com.storeql.web.ApiException {@code 400} when the status is not a known one; {@code
   *     404} when the store does not exist in the caller's tenant
   */
  @Operation(
      summary = "Change a store's status",
      description = "Publishes StoreStatusChanged so other services (e.g. iam-svc) can react.")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @PATCH
  @Path("/stores/{storeId}/status")
  public ApiResponse<StoreResponse> patchStoreStatus(
      @PathParam("storeId") UUID storeId, PatchStatusRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toStore(service.patchStoreStatus(ctx.requireTenantId(), storeId, req)));
  }

  // ── zones ────────────────────────────────────────────────────────────────

  /**
   * Cursor-paginated list of one store's zones.
   *
   * @param storeId the store whose zones to page through
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of zones, with the next cursor in {@code meta}
   * @throws com.storeql.web.ApiException {@code 404} when the store does not exist in the caller's
   *     tenant
   */
  @Operation(
      summary = "List a store's zones",
      description = "Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100.")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @GET
  @Path("/stores/{storeId}/zones")
  public ApiResponse<List<ZoneResponse>> listZones(
      @PathParam("storeId") UUID storeId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = service.listZones(ctx.requireTenantId(), storeId, after, Cursor.clampLimit(limit));
    var zones = page.items().stream().map(Mappers::toZone).toList();
    return ApiResponse.ok(zones, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Creates an aisle/rack/cold-room/back-store zone under a store.
   *
   * @param storeId the store to add the zone to
   * @param req the zone's name, code and type; a blank type defaults to {@code AISLE}
   * @return {@code 201} with the created zone
   * @throws com.storeql.web.ApiException {@code 404} when the store does not exist in the caller's
   *     tenant
   */
  @Operation(
      summary = "Add a zone to a store",
      description = "Creates an aisle/rack/cold-room/back-store zone under the given store.")
  @APIResponse(responseCode = "201", description = "Zone created")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @POST
  @Path("/stores/{storeId}/zones")
  public Response addZone(@PathParam("storeId") UUID storeId, CreateZoneRequest req) {
    Validations.validate(req);
    var zone = service.addZone(ctx.requireTenantId(), storeId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toZone(zone)))
        .build();
  }

  /**
   * Reads a single zone.
   *
   * @param storeId the store in the path; the lookup is tenant-scoped and does not match on it
   * @param zoneId the zone to read
   * @return the zone
   * @throws com.storeql.web.ApiException {@code 404} when no such zone exists in the caller's
   *     tenant
   */
  @Operation(summary = "Get a zone", description = "Returns a single zone in the tenant.")
  @APIResponse(responseCode = "404", description = "No such zone")
  @GET
  @Path("/stores/{storeId}/zones/{zoneId}")
  public ApiResponse<ZoneResponse> getZone(
      @PathParam("storeId") UUID storeId, @PathParam("zoneId") UUID zoneId) {
    return ApiResponse.ok(Mappers.toZone(service.getZone(ctx.requireTenantId(), zoneId)));
  }

  /**
   * Updates a zone's name, code or type.
   *
   * @param storeId the store in the path; the lookup is tenant-scoped and does not match on it
   * @param zoneId the zone to update
   * @param req the new name, code and type
   * @return the updated zone
   * @throws com.storeql.web.ApiException {@code 404} when no such zone exists in the caller's
   *     tenant
   */
  @Operation(summary = "Update a zone", description = "Updates a zone's name, code, or type.")
  @APIResponse(responseCode = "404", description = "No such zone")
  @PUT
  @Path("/stores/{storeId}/zones/{zoneId}")
  public ApiResponse<ZoneResponse> updateZone(
      @PathParam("storeId") UUID storeId, @PathParam("zoneId") UUID zoneId, UpdateZoneRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toZone(service.updateZone(ctx.requireTenantId(), zoneId, req)));
  }

  /**
   * Changes a zone's status.
   *
   * <p>Publishes nothing: no other service projects zone status.
   *
   * @param storeId the store in the path; the lookup is tenant-scoped and does not match on it
   * @param zoneId the zone whose status to change
   * @param req the new status
   * @return the zone with its new status
   * @throws com.storeql.web.ApiException {@code 404} when no such zone exists in the caller's
   *     tenant
   */
  @Operation(summary = "Change a zone's status", description = "Updates a zone's status.")
  @APIResponse(responseCode = "404", description = "No such zone")
  @PATCH
  @Path("/stores/{storeId}/zones/{zoneId}/status")
  public ApiResponse<ZoneResponse> patchZoneStatus(
      @PathParam("storeId") UUID storeId,
      @PathParam("zoneId") UUID zoneId,
      PatchStatusRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toZone(service.patchZoneStatus(ctx.requireTenantId(), zoneId, req)));
  }

  // ── staff ────────────────────────────────────────────────────────────────

  /**
   * Grants a user a role at a store.
   *
   * <p>The user must already exist in iam-svc — provision the account there first. Publishing
   * {@code StaffAssigned} is what makes iam-svc bind the store-scoped role.
   *
   * @param req the user, store and role to grant
   * @return {@code 201} with {@code assigned}
   * @throws com.storeql.web.ApiException {@code 404} when the store does not exist in the caller's
   *     tenant
   */
  @Operation(
      summary = "Assign staff to a store",
      description =
          "Grants a user a role at a store. userId must already exist (see iam-svc). The role is a"
              + " built-in one (OWNER, MANAGER, STOREKEEPER, CASHIER) or the code of one of the"
              + " tenant's own roles (20.10), whose tier and permissions the login then carries."
              + " Needs the staff.manage permission.")
  @APIResponse(responseCode = "201", description = "Staff assigned")
  @APIResponse(responseCode = "400", description = "A role that is neither built in nor defined")
  @APIResponse(responseCode = "403", description = "The caller may not manage staff")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @POST
  @Path("/staff")
  public Response assignStaff(AssignStaffRequest req) {
    Validations.validate(req);
    service.assignStaff(ctx, req);
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok("assigned")).build();
  }

  /**
   * Cursor-paginated list of the tenant's staff assignments.
   *
   * <p>A store-held caller sees only assignments at their own stores and business-wide ones; an
   * owner, a business-wide manager or the platform admin sees every assignment, as before — {@link
   * com.storeql.web.TenantContext#storeIds()} decides which, never a request parameter.
   *
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of assignments, with the next cursor in {@code meta}
   */
  @Operation(
      summary = "List staff assignments",
      description =
          "Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100. A caller held to one or more"
              + " stores sees only assignments at those stores and business-wide ones; an owner or"
              + " a business-wide manager sees every assignment.")
  @APIResponse(
      responseCode = "200",
      description = "The page of assignments, with the next cursor in {@code meta}")
  @GET
  @Path("/staff")
  public ApiResponse<List<StaffResponse>> listStaff(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page =
        service.listStaff(ctx.requireTenantId(), ctx.storeIds(), after, Cursor.clampLimit(limit));
    return ApiResponse.ok(
        page.items().stream().map(Mappers::toStaff).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Removes a user's role assignment at one store.
   *
   * <p>Scoped to a single store rather than the whole tenant, so revoking someone's access at one
   * site leaves their other assignments intact. Does not delete their iam-svc login.
   *
   * @param userId the staff member to unassign
   * @param storeParam the store to unassign them from, as {@code ?store=<storeId>}; required
   * @return {@code removed}
   * @throws com.storeql.web.ApiException {@code 400} when {@code store} is missing or not a UUID
   */
  @Operation(
      summary = "Remove a staff assignment",
      description =
          "Removes a user's role assignment at the given store (?store=<storeId>) and tells"
              + " iam-svc, which takes the role off the login (SJ-D51). Needs staff.manage.")
  @APIResponse(responseCode = "400", description = "?store=<storeId> query parameter is missing")
  @DELETE
  @Path("/staff/{userId}")
  public ApiResponse<String> removeStaff(
      @PathParam("userId") UUID userId, @QueryParam("store") String storeParam) {
    if (storeParam == null || storeParam.isBlank()) {
      throw ApiException.badRequest("MISSING_STORE", "?store=<storeId> is required");
    }
    UUID storeId = com.storeql.web.Parsing.uuid(storeParam, "store");
    service.removeStaff(ctx, userId, storeId);
    return ApiResponse.ok("removed");
  }
}
