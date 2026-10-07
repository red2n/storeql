package com.storeql.tenant.api;

import com.storeql.tenant.dto.Dtos.TenantInventoryConfigResponse;
import com.storeql.tenant.dto.Dtos.UpsertInventoryConfigRequest;
import com.storeql.tenant.service.TenantService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Gap #53: per-tenant inventory control parameters (lot, serial, grade, costing, UOM). */
@Path("/admin/inventory-config")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Inventory Config")
public class InventoryConfigResource {

  @Inject TenantService svc;
  @Inject TenantContext ctx;

  /**
   * Merges the given fields into the tenant's inventory-control parameters.
   *
   * <p>Unset fields keep their current (or default) value, so a partial body is safe. A {@code
   * null} body is allowed and upserts the defaults.
   *
   * @param req the fields to change, or {@code null} to upsert defaults
   * @return the merged configuration
   */
  @Operation(
      summary = "Upsert inventory-control config",
      description =
          "Merges the given fields into the tenant's inventory-control parameters; unset fields"
              + " keep their current (or default) value. A null body upserts defaults.")
  @APIResponse(responseCode = "200", description = "The merged configuration")
  @PUT
  public Response upsert(UpsertInventoryConfigRequest req) {
    BusinessWide.require(ctx);
    // Body is optional (PUT with no body = upsert defaults); validate only when one is supplied.
    if (req != null) {
      Validations.validate(req);
    }
    TenantInventoryConfigResponse body =
        svc.upsertInventoryConfig(
            ctx.requireTenantId(),
            req != null
                ? req
                : new UpsertInventoryConfigRequest(null, null, null, null, null, null, null, null));
    return Response.ok(ApiResponse.ok(body)).build();
  }

  /**
   * Returns the tenant's current inventory-control parameters.
   *
   * @return the stored configuration
   * @throws com.storeql.web.ApiException {@code 404} when the tenant has never set one
   */
  @Operation(
      summary = "Get inventory-control config",
      description = "Returns the tenant's current inventory-control parameters.")
  @APIResponse(responseCode = "404", description = "No inventory config found")
  @GET
  public Response get() {
    TenantInventoryConfigResponse body = svc.getInventoryConfig(ctx.requireTenantId());
    return Response.ok(ApiResponse.ok(body)).build();
  }
}
