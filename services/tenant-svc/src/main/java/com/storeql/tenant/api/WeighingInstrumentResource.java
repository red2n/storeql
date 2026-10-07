package com.storeql.tenant.api;

import com.storeql.tenant.dto.Dtos.CreateWeighingInstrumentRequest;
import com.storeql.tenant.dto.Dtos.InstrumentVerificationResponse;
import com.storeql.tenant.dto.Dtos.PatchStatusRequest;
import com.storeql.tenant.dto.Dtos.RecordVerificationRequest;
import com.storeql.tenant.dto.Dtos.UpdateWeighingInstrumentRequest;
import com.storeql.tenant.dto.Dtos.WeighingInstrumentResponse;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.service.WeighingInstrumentService;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The weighing-instrument register (Weights and Measures Act 1985). Reads are open to any staff
 * role, because the till has to know which scale it may sell from; writes are management-only.
 */
@RequestScoped
@Path("/admin/stores/{storeId}/weighing-instruments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Weighing instruments")
public class WeighingInstrumentResource {

  @Inject WeighingInstrumentService service;
  @Inject TenantContext ctx;
  @Inject com.storeql.tenant.service.TenantService tenants;

  /**
   * The store's register.
   *
   * @param storeId the store
   * @param certified when true, only instruments that may be used for trade today
   * @return the instruments with their standing
   */
  @Operation(
      summary = "List a store's weighing instruments",
      description =
          "Every instrument with its standing, derived from its history: certified means in"
              + " service, latest entry a pass, and not yet due again. ?certified=true is what the"
              + " till asks before it sells by weight.")
  @APIResponse(responseCode = "200", description = "The instruments")
  @GET
  public ApiResponse<List<WeighingInstrumentResponse>> list(
      @PathParam("storeId") UUID storeId, @QueryParam("certified") Boolean certified) {
    ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(
        service.list(ctx.requireTenantId(), storeId, Boolean.TRUE.equals(certified)).stream()
            .map(Mappers::toInstrument)
            .toList(),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Registers an instrument.
   *
   * @param storeId the store it stands in
   * @param req its description
   * @return {@code 201} with the instrument, NEVER_VERIFIED
   */
  @Operation(
      summary = "Register a weighing instrument",
      description =
          "Adds it to the store's register, never verified: nothing is certified by being"
              + " written down. Management-only.")
  @APIResponse(responseCode = "201", description = "Registered")
  @APIResponse(
      responseCode = "409",
      description = "Identifier taken at the store, or serial in the tenant")
  @POST
  public Response create(@PathParam("storeId") UUID storeId, CreateWeighingInstrumentRequest req) {
    requireManagement();
    Validations.validate(req);
    requireStoreHeld(storeId);
    var w = service.create(ctx.requireTenantId(), storeId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toInstrument(w), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * One instrument.
   *
   * @param storeId the store
   * @param id the instrument
   * @return the instrument with its standing
   */
  @Operation(summary = "Get a weighing instrument")
  @APIResponse(responseCode = "200", description = "The instrument")
  @APIResponse(responseCode = "404", description = "No such instrument at this store")
  @GET
  @Path("/{id}")
  public ApiResponse<WeighingInstrumentResponse> get(
      @PathParam("storeId") UUID storeId, @PathParam("id") UUID id) {
    ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(
        Mappers.toInstrument(service.get(ctx.requireTenantId(), storeId, id)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Rewrites an instrument's description.
   *
   * @param storeId the store
   * @param id the instrument
   * @param req the new description
   * @return the instrument
   */
  @Operation(summary = "Update a weighing instrument", description = "Management-only.")
  @APIResponse(responseCode = "200", description = "Updated")
  @PUT
  @Path("/{id}")
  public ApiResponse<WeighingInstrumentResponse> update(
      @PathParam("storeId") UUID storeId,
      @PathParam("id") UUID id,
      UpdateWeighingInstrumentRequest req) {
    requireManagement();
    Validations.validate(req);
    requireStoreHeld(storeId);
    return ApiResponse.ok(
        Mappers.toInstrument(service.update(ctx.requireTenantId(), storeId, id, req)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Takes an instrument out of service, back, or retires it.
   *
   * @param storeId the store
   * @param id the instrument
   * @param req the status
   * @return the instrument
   */
  @Operation(
      summary = "Set an instrument's status",
      description = "IN_SERVICE, OUT_OF_SERVICE or RETIRED. Retirement is final. Management-only.")
  @APIResponse(responseCode = "200", description = "Updated")
  @jakarta.ws.rs.PATCH
  @Path("/{id}/status")
  public ApiResponse<WeighingInstrumentResponse> status(
      @PathParam("storeId") UUID storeId, @PathParam("id") UUID id, PatchStatusRequest req) {
    requireManagement();
    Validations.validate(req);
    requireStoreHeld(storeId);
    return ApiResponse.ok(
        Mappers.toInstrument(service.setStatus(ctx.requireTenantId(), storeId, id, req.status())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * The instrument's history, newest first.
   *
   * @param storeId the store
   * @param id the instrument
   * @return the entries
   */
  @Operation(summary = "An instrument's verification history")
  @APIResponse(responseCode = "200", description = "The entries, newest first")
  @GET
  @Path("/{id}/verifications")
  public ApiResponse<List<InstrumentVerificationResponse>> history(
      @PathParam("storeId") UUID storeId, @PathParam("id") UUID id) {
    ctx.requireStoreAccess(storeId);
    return ApiResponse.ok(
        service.history(ctx.requireTenantId(), storeId, id).stream()
            .map(Mappers::toVerification)
            .toList(),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Appends a verification, inspection or repair.
   *
   * @param storeId the store
   * @param id the instrument
   * @param req the entry
   * @return {@code 201} with the entry
   */
  @Operation(
      summary = "Record a verification, inspection or repair",
      description =
          "Append-only. A repair is never a pass and takes the instrument out of trade until it is"
              + " verified again. Management-only.")
  @APIResponse(responseCode = "201", description = "Recorded")
  @POST
  @Path("/{id}/verifications")
  public Response record(
      @PathParam("storeId") UUID storeId, @PathParam("id") UUID id, RecordVerificationRequest req) {
    requireManagement();
    Validations.validate(req);
    requireStoreHeld(storeId);
    var v = service.recordVerification(ctx.requireTenantId(), storeId, id, req, ctx.userId());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toVerification(v), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  private void requireManagement() {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
  }

  private void requireStoreHeld(UUID storeId) {
    tenants.getStore(ctx.requireTenantId(), storeId);
    ctx.requireStoreAccess(storeId);
  }
}
