package com.storeql.tenant.api;

import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.domain.Plans;
import com.storeql.tenant.dto.PlanDtos;
import com.storeql.tenant.mapper.PlanMappers;
import com.storeql.tenant.service.PlanService;
import com.storeql.web.ApiResponse;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The platform's price list (21.8): what a business can be sold and what it includes. Writing it is
 * the platform's own, so every route here is {@code PLATFORM_ADMIN}; what a business sees of its
 * own plan is {@link TenantPlanResource}.
 */
@Path("/platform/plans")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Plans")
public class PlanResource {

  @Inject PlanService svc;
  @Inject TenantContext ctx;

  @Operation(summary = "Every plan, including drafts and retired ones")
  @GET
  public ApiResponse<List<PlanDtos.PlanResponse>> list() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(svc.all().stream().map(PlanMappers::toDto).toList());
  }

  @Operation(
      summary = "Write a plan",
      description = "It is created as a draft: nothing is sold until it is activated.")
  @APIResponse(responseCode = "201", description = "Written")
  @APIResponse(responseCode = "409", description = "A plan already goes by that code")
  @POST
  public Response create(PlanDtos.PlanRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(PlanMappers.toDto(svc.create(ctx.requireUserId(), req))))
        .build();
  }

  @Operation(summary = "One plan with its prices and what it includes")
  @GET
  @Path("/{id}")
  public ApiResponse<PlanDtos.PlanResponse> get(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(PlanMappers.toDto(svc.get(id)));
  }

  @Operation(
      summary = "Change a plan",
      description =
          "Its code, status and default-ness move on their own routes. How often it is billed"
              + " cannot change while businesses are on it.")
  @APIResponse(responseCode = "409", description = "Businesses are on it (PLAN_INTERVAL_IN_USE)")
  @PUT
  @Path("/{id}")
  public ApiResponse<PlanDtos.PlanResponse> update(
      @PathParam("id") UUID id, PlanDtos.PlanRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(PlanMappers.toDto(svc.update(id, req)));
  }

  @Operation(
      summary = "Put a plan on sale",
      description = "It must have a price in at least one currency first.")
  @APIResponse(responseCode = "409", description = "PLAN_HAS_NO_PRICE or PLAN_ALREADY_SOLD")
  @POST
  @Path("/{id}/activate")
  public ApiResponse<PlanDtos.PlanResponse> activate(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(PlanMappers.toDto(svc.activate(id)));
  }

  @Operation(
      summary = "Take a plan off sale",
      description = "The businesses on it keep it; it is offered to nobody new.")
  @POST
  @Path("/{id}/retire")
  public ApiResponse<PlanDtos.PlanResponse> retire(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(PlanMappers.toDto(svc.retire(id)));
  }

  @Operation(summary = "Name the plan a business that signs up starts on")
  @APIResponse(responseCode = "409", description = "Only a plan on sale can be the default")
  @POST
  @Path("/{id}/default")
  public ApiResponse<PlanDtos.PlanResponse> makeDefault(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(PlanMappers.toDto(svc.makeDefault(id)));
  }

  @Operation(
      summary = "Set a plan's price in one currency",
      description =
          "From a date. An earlier price is never edited: an invoice raised under it must still"
              + " be explicable next year.")
  @POST
  @Path("/{id}/prices")
  public ApiResponse<PlanDtos.PlanResponse> setPrice(
      @PathParam("id") UUID id, PlanDtos.PriceRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(PlanMappers.toDto(svc.setPrice(id, ctx.requireUserId(), req)));
  }

  @Operation(
      summary = "Set what a plan includes",
      description =
          "Replaced whole: a key left out is one the plan no longer names. A key the platform does"
              + " not enforce is refused (PLAN_ENTITLEMENT_UNKNOWN).")
  @PUT
  @Path("/{id}/includes")
  public ApiResponse<PlanDtos.PlanResponse> setGrants(
      @PathParam("id") UUID id, PlanDtos.GrantsRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(PlanMappers.toDto(svc.setGrants(id, req.grants())));
  }

  @Operation(
      summary = "Set what a plan includes of each meter (21.10)",
      description =
          "Replaced whole: a meter left out is one the plan does not name, which leaves it"
              + " unlimited and uncharged. `included` is per billing period; `hard` refuses use"
              + " beyond it rather than charging for it, and only a refusable meter may be hard"
              + " (PLAN_METER_NOT_REFUSABLE): an order is never refused.")
  @PUT
  @Path("/{id}/meters")
  public ApiResponse<PlanDtos.PlanResponse> setMeters(
      @PathParam("id") UUID id, PlanDtos.PlanMetersRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(PlanMappers.toDto(svc.setMeters(id, req.meters())));
  }

  @Operation(
      summary = "Set what one unit beyond a plan's allowance costs",
      description =
          "Per meter and currency, from a date. Never edited in place: a period is charged at the"
              + " price in force the day it began.")
  @POST
  @Path("/{id}/meter-prices")
  public ApiResponse<PlanDtos.PlanResponse> setMeterPrice(
      @PathParam("id") UUID id, PlanDtos.MeterPriceRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(PlanMappers.toDto(svc.setMeterPrice(id, ctx.requireUserId(), req)));
  }

  /** What the platform counts, and which of it may ever be refused. */
  @Operation(summary = "The meters a plan may include, who counts each, and which may be refused")
  @GET
  @Path("/meter-keys")
  public ApiResponse<PlanDtos.MeterCatalogueResponse> meters() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        new PlanDtos.MeterCatalogueResponse(
            Meters.CATALOGUE.stream()
                .map(
                    m ->
                        new PlanDtos.MeterCatalogueEntry(
                            m.key(), m.label(), m.unit(), m.refusable(), m.countedBy()))
                .toList()));
  }

  /**
   * What the platform actually enforces. A console offers these keys rather than inventing one,
   * because a key nobody enforces would be a promise nobody keeps.
   */
  @Operation(summary = "The entitlement keys a plan may carry, and who enforces each")
  @GET
  @Path("/entitlement-keys")
  public ApiResponse<PlanDtos.CatalogueResponse> catalogue() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        new PlanDtos.CatalogueResponse(
            Plans.enforced().stream()
                .map(
                    e -> new PlanDtos.CatalogueEntry(e.key(), e.label(), e.limit(), e.enforcedBy()))
                .toList()));
  }
}
