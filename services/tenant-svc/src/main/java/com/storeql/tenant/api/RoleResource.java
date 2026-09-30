package com.storeql.tenant.api;

import com.storeql.tenant.domain.Domain;
import com.storeql.tenant.dto.Dtos.DefineRoleRequest;
import com.storeql.tenant.dto.Dtos.PermissionResponse;
import com.storeql.tenant.dto.Dtos.RoleResponse;
import com.storeql.tenant.dto.Dtos.UpdateRoleRequest;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.service.TenantService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The tenant's roles (20.10): the four built-in tiers beside the tenant's own, and the permission
 * catalogue they are made from. Under {@code /admin}, so the shared filter admits management only;
 * writes also need the {@code staff.manage} permission, checked in the service.
 */
@ApplicationScoped
@Path("/admin/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Roles")
public class RoleResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "List roles",
      description =
          "The built-in roles first, each holding its default permissions, then the tenant's own"
              + " roles by code, each holding the subset of its tier's permissions it was defined"
              + " with.")
  @APIResponse(responseCode = "200", description = "Built-in and custom roles")
  @GET
  public ApiResponse<List<RoleResponse>> list() {
    List<RoleResponse> out = new ArrayList<>();
    for (String tier : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER")) {
      out.add(Mappers.toBuiltInRole(tier));
    }
    service.listRoles(ctx.requireTenantId()).stream().map(Mappers::toRole).forEach(out::add);
    return ApiResponse.ok(out);
  }

  @Operation(
      summary = "The permission catalogue",
      description =
          "Every permission a role may hold, with the sentence a screen shows beside it and the"
              + " tiers that hold it by default. A custom role may hold only what its tier holds.")
  @APIResponse(responseCode = "200", description = "The catalogue")
  @GET
  @Path("/permissions")
  public ApiResponse<List<PermissionResponse>> permissions() {
    ctx.requireTenantId();
    return ApiResponse.ok(service.permissionCatalogue());
  }

  @Operation(
      summary = "Define a role",
      description =
          "A code and name of the tenant's own, the tier it stands on (MANAGER, STOREKEEPER or"
              + " CASHIER) and the permissions it holds — a subset of the tier's, possibly empty."
              + " Publishes RoleDefined. Needs staff.manage.")
  @APIResponse(responseCode = "201", description = "Role defined")
  @APIResponse(
      responseCode = "400",
      description =
          "ROLE_CODE_INVALID, ROLE_CODE_RESERVED, ROLE_TIER_INVALID, ROLE_PERMISSION_UNKNOWN or"
              + " ROLE_PERMISSION_OUTSIDE_TIER")
  @APIResponse(responseCode = "403", description = "The caller may not manage staff")
  @APIResponse(responseCode = "409", description = "The tenant already has a role by that code")
  @POST
  public Response define(DefineRoleRequest req) {
    ctx.requirePermission(com.storeql.web.Permissions.STAFF_MANAGE);
    BusinessWide.require(ctx);
    Validations.validate(req);
    Domain.TenantRole role = service.defineRole(ctx, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toRole(role)))
        .build();
  }

  @Operation(summary = "One of the tenant's own roles")
  @APIResponse(responseCode = "200", description = "The role")
  @APIResponse(responseCode = "404", description = "No such role in this tenant")
  @GET
  @Path("/{code}")
  public ApiResponse<RoleResponse> get(@PathParam("code") String code) {
    return ApiResponse.ok(Mappers.toRole(service.getRole(ctx.requireTenantId(), code)));
  }

  @Operation(
      summary = "Rename a role or change what it holds",
      description =
          "The tier cannot change. Publishes RoleDefined; every holder carries the new set from"
              + " their next login. Needs staff.manage.")
  @APIResponse(responseCode = "200", description = "The role, redefined")
  @APIResponse(responseCode = "400", description = "A permission the tier does not hold")
  @APIResponse(responseCode = "404", description = "No such role in this tenant")
  @PUT
  @Path("/{code}")
  public ApiResponse<RoleResponse> update(@PathParam("code") String code, UpdateRoleRequest req) {
    ctx.requirePermission(com.storeql.web.Permissions.STAFF_MANAGE);
    BusinessWide.require(ctx);
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toRole(service.updateRole(ctx, code, req)));
  }

  @Operation(
      summary = "Delete a role nobody holds",
      description = "Refused while any staff assignment names it. Needs staff.manage.")
  @APIResponse(responseCode = "204", description = "Deleted")
  @APIResponse(responseCode = "404", description = "No such role in this tenant")
  @APIResponse(responseCode = "409", description = "ROLE_IN_USE")
  @DELETE
  @Path("/{code}")
  public Response delete(@PathParam("code") String code) {
    ctx.requirePermission(com.storeql.web.Permissions.STAFF_MANAGE);
    BusinessWide.require(ctx);
    service.deleteRole(ctx, code);
    return Response.noContent().build();
  }
}
