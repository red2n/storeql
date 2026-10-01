package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.CreatePickingRuleAssignmentRequest;
import com.storeql.inventory.dto.Dtos.CreatePickingRuleRequest;
import com.storeql.inventory.dto.Dtos.PickingRuleAssignmentResponse;
import com.storeql.inventory.dto.Dtos.PickingRuleResolveResponse;
import com.storeql.inventory.dto.Dtos.PickingRuleResponse;
import com.storeql.inventory.dto.Dtos.PickingRuleZonePriorityResponse;
import com.storeql.inventory.dto.Dtos.SetZonePrioritiesRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiException;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Picking rules, zone priorities, and rule assignments (Gap #38). Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Picking Rules")
public class PickingRuleResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Creates a picking rule.
   *
   * <p>Defines a named picking strategy (e.g. FIFO/FEFO) with an optional grade preference.
   *
   * @param req the request body
   * @return picking rule created ({@code 201})
   */
  @Operation(
      summary = "Create a picking rule",
      description =
          "Defines a named picking strategy (e.g. FIFO/FEFO) with an optional grade"
              + " preference.")
  @APIResponse(responseCode = "201", description = "Picking rule created")
  @POST
  @Path("/picking-rules")
  public Response createPickingRule(CreatePickingRuleRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                Mappers.toPickingRule(service.createPickingRule(ctx.requireTenantId(), req))))
        .build();
  }

  /**
   * Lists picking rules.
   *
   * @param limitParam the limit param (query parameter)
   */
  @Operation(summary = "List picking rules")
  @APIResponse(responseCode = "200", description = "List picking rules")
  @GET
  @Path("/picking-rules")
  public ApiResponse<List<PickingRuleResponse>> listPickingRules(
      @QueryParam("limit") Integer limitParam) {
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listPickingRules(ctx.requireTenantId(), limit).stream()
            .map(Mappers::toPickingRule)
            .toList());
  }

  /**
   * Gets a picking rule by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} picking rule not found
   */
  @Operation(summary = "Get a picking rule by id")
  @APIResponse(responseCode = "404", description = "Picking rule not found")
  @GET
  @Path("/picking-rules/{id}")
  public ApiResponse<PickingRuleResponse> getPickingRule(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toPickingRule(service.getPickingRule(ctx.requireTenantId(), id)));
  }

  /**
   * Deactivates a picking rule.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} picking rule not found
   */
  @Operation(summary = "Deactivate a picking rule")
  @APIResponse(responseCode = "404", description = "Picking rule not found")
  @DELETE
  @Path("/picking-rules/{id}")
  public ApiResponse<PickingRuleResponse> deactivatePickingRule(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    return ApiResponse.ok(
        Mappers.toPickingRule(service.deactivatePickingRule(ctx.requireTenantId(), id)));
  }

  /**
   * Sets a picking rule's zone priorities.
   *
   * <p>Replaces the ordered list of zone pick priorities for the rule.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 404} picking rule not found
   */
  @Operation(
      summary = "Set a picking rule's zone priorities",
      description = "Replaces the ordered list of zone pick priorities for the rule.")
  @APIResponse(responseCode = "404", description = "Picking rule not found")
  @PUT
  @Path("/picking-rules/{id}/zone-priorities")
  public ApiResponse<List<PickingRuleZonePriorityResponse>> setZonePriorities(
      @PathParam("id") UUID id, SetZonePrioritiesRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        service.setZonePriorities(ctx.requireTenantId(), id, req).stream()
            .map(Mappers::toZonePriority)
            .toList());
  }

  /**
   * Lists a picking rule's zone priorities.
   *
   * @param id the id (path parameter)
   */
  @Operation(summary = "List a picking rule's zone priorities")
  @APIResponse(responseCode = "200", description = "List a picking rule's zone priorities")
  @GET
  @Path("/picking-rules/{id}/zone-priorities")
  public ApiResponse<List<PickingRuleZonePriorityResponse>> listZonePriorities(
      @PathParam("id") UUID id) {
    return ApiResponse.ok(
        service.listZonePriorities(ctx.requireTenantId(), id).stream()
            .map(Mappers::toZonePriority)
            .toList());
  }

  /**
   * Assigns a picking rule to a scope.
   *
   * <p>Binds a picking rule to a scope (e.g. tenant/store/category) so it applies automatically to
   * matching resolves.
   *
   * @param req the request body
   * @return assignment created ({@code 201})
   */
  @Operation(
      summary = "Assign a picking rule to a scope",
      description =
          "Binds a picking rule to a scope (e.g. tenant/store/category) so it applies"
              + " automatically to matching resolves.")
  @APIResponse(responseCode = "201", description = "Assignment created")
  @POST
  @Path("/picking-rule-assignments")
  public Response createPickingRuleAssignment(CreatePickingRuleAssignmentRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    if ("STORE".equalsIgnoreCase(req.scopeType())
        && req.scopeId() != null
        && !req.scopeId().isBlank()) {
      ctx.requireStoreAccess(uuid(req.scopeId(), "scopeId"));
    }
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                Mappers.toPickingRuleAssignment(
                    service.createPickingRuleAssignment(ctx.requireTenantId(), req))))
        .build();
  }

  /**
   * Lists picking rule assignments.
   *
   * @param limitParam the limit param (query parameter)
   */
  @Operation(summary = "List picking rule assignments")
  @APIResponse(responseCode = "200", description = "List picking rule assignments")
  @GET
  @Path("/picking-rule-assignments")
  public ApiResponse<List<PickingRuleAssignmentResponse>> listPickingRuleAssignments(
      @QueryParam("limit") Integer limitParam) {
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listPickingRuleAssignments(ctx.requireTenantId(), limit).stream()
            .map(Mappers::toPickingRuleAssignment)
            .toList());
  }

  /**
   * Deletes a picking rule assignment.
   *
   * @param id the id (path parameter)
   * @return assignment deleted ({@code 204})
   * @throws com.storeql.web.ApiException {@code 404} picking rule assignment not found
   */
  @Operation(summary = "Delete a picking rule assignment")
  @APIResponse(responseCode = "204", description = "Assignment deleted")
  @APIResponse(responseCode = "404", description = "Picking rule assignment not found")
  @DELETE
  @Path("/picking-rule-assignments/{id}")
  public Response deletePickingRuleAssignment(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    var existing = service.getPickingRuleAssignment(ctx.requireTenantId(), id);
    if (existing.scopeId() != null && "STORE".equals(existing.scopeType())) {
      ctx.requireStoreAccess(existing.scopeId());
    }
    service.deletePickingRuleAssignment(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  /**
   * Resolves the applicable picking rule and pick order for a variant at a store.
   *
   * <p>Previews the batch pick order (e.g. FIFO/FEFO with grade preference) the resolved rule would
   * produce.
   *
   * @param store the store (query parameter)
   * @param variant the variant (query parameter)
   * @throws com.storeql.web.ApiException {@code 400} store and variant query params are required
   */
  @Operation(
      summary = "Resolve the applicable picking rule and pick order for a variant at a store",
      description =
          "Previews the batch pick order (e.g. FIFO/FEFO with grade preference) the"
              + " resolved rule would produce.")
  @APIResponse(responseCode = "400", description = "store and variant query params are required")
  @GET
  @Path("/picking-rules/resolve")
  public ApiResponse<PickingRuleResolveResponse> resolvePickingRule(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    if (store == null || variant == null) {
      throw new ApiException(
          400, "MISSING_PARAM", "store and variant are required", List.of(), null);
    }
    return ApiResponse.ok(
        service.resolvePickingRule(tenantId, uuid(store, "store"), uuid(variant, "variant")));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
