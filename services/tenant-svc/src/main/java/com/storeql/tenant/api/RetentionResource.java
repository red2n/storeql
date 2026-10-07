package com.storeql.tenant.api;

import com.storeql.tenant.domain.Retention.SubjectKind;
import com.storeql.tenant.dto.RetentionDtos.ClassResponse;
import com.storeql.tenant.dto.RetentionDtos.HoldResponse;
import com.storeql.tenant.dto.RetentionDtos.PlaceHoldRequest;
import com.storeql.tenant.dto.RetentionDtos.ReleaseHoldRequest;
import com.storeql.tenant.dto.RetentionDtos.RunResponse;
import com.storeql.tenant.dto.RetentionDtos.SetPeriodRequest;
import com.storeql.tenant.dto.RetentionDtos.SheetResponse;
import com.storeql.tenant.mapper.RetentionMappers;
import com.storeql.tenant.service.RetentionService;
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
 * Retention schedules (21.16): what the law requires, what the business set, the holds, and the
 * register of purges. Under {@code /admin/tenant/}: the sheet is readable by every staff role,
 * because the services that purge read it under a staff identity; setting a period, placing and
 * releasing holds and reading the register are management work, gated by path.
 */
@Path("/admin/tenant/retention")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Retention")
public class RetentionResource {

  @Inject RetentionService service;
  @Inject TenantContext ctx;

  /** The schedule: every class, the law's floor for the countries traded in, and the period set. */
  @Operation(
      summary = "The retention schedule",
      description =
          "Every class of data with the least the law of any country the business trades in"
              + " requires, the period the business has set, and the holds in force.")
  @APIResponse(responseCode = "200", description = "The schedule")
  @GET
  public ApiResponse<SheetResponse> sheet() {
    return ApiResponse.ok(
        RetentionMappers.toSheet(service.sheet(ctx.requireTenantId())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Sets how long a class is kept.
   *
   * @throws com.storeql.web.ApiException {@code 400} unknown class, a period outside 0..36500, or
   *     under the law's floor ({@code RETENTION_BELOW_LEGAL_MINIMUM}, the floor and its instrument
   *     in the details)
   */
  @Operation(
      summary = "Set how long a class is kept",
      description =
          "Longer than the law's floor is allowed, shorter is refused by name. Every decision is"
              + " kept; the latest is in force.")
  @APIResponse(responseCode = "200", description = "The class with its period")
  @APIResponse(responseCode = "400", description = "Unknown class, bad period, or under the floor")
  @PUT
  @Path("/{dataClass}")
  public ApiResponse<ClassResponse> set(
      @PathParam("dataClass") String dataClass, SetPeriodRequest req) {
    BusinessWide.require(ctx);
    Validations.validate(req);
    return ApiResponse.ok(
        RetentionMappers.toClass(
            service.set(ctx.requireTenantId(), dataClass, req.periodDays(), ctx.requireUserId())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "List holds", description = "The holds in force, or every hold ever placed.")
  @APIResponse(responseCode = "200", description = "The holds")
  @GET
  @Path("/holds")
  public ApiResponse<List<HoldResponse>> holds(@QueryParam("active") Boolean active) {
    boolean activeOnly = active == null || active;
    return ApiResponse.ok(
        service.holds(ctx.requireTenantId(), activeOnly).stream()
            .map(RetentionMappers::toHold)
            .toList(),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Places a hold.
   *
   * @return placed ({@code 201})
   * @throws com.storeql.web.ApiException {@code 400} unknown class, or a subject that does not
   *     match the kind
   */
  @Operation(
      summary = "Place a hold",
      description =
          "Stops a purge of one customer, one order, or everything, on one class or every class,"
              + " until it is released.")
  @APIResponse(responseCode = "201", description = "Placed")
  @APIResponse(responseCode = "400", description = "Unknown class, or subject and kind disagree")
  @POST
  @Path("/holds")
  public Response place(PlaceHoldRequest req) {
    BusinessWide.require(ctx);
    Validations.validate(req);
    UUID subjectId =
        req.subjectId() == null || req.subjectId().isBlank()
            ? null
            : Parsing.uuid(req.subjectId(), "subjectId");
    var hold =
        service.place(
            ctx.requireTenantId(),
            ctx.requireUserId(),
            req.dataClass(),
            SubjectKind.valueOf(req.subjectKind()),
            subjectId,
            req.reason());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(RetentionMappers.toHold(hold), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Releases a hold.
   *
   * @throws com.storeql.web.ApiException {@code 404} no such hold; {@code 409} already released
   */
  @Operation(summary = "Release a hold", description = "Once; the reason is kept with the hold.")
  @APIResponse(responseCode = "200", description = "Released")
  @APIResponse(responseCode = "404", description = "No such hold")
  @APIResponse(responseCode = "409", description = "Already released")
  @POST
  @Path("/holds/{id}/release")
  public ApiResponse<HoldResponse> release(@PathParam("id") String id, ReleaseHoldRequest req) {
    BusinessWide.require(ctx);
    Validations.validate(req);
    return ApiResponse.ok(
        RetentionMappers.toHold(
            service.release(
                ctx.requireTenantId(), Parsing.uuid(id, "id"), ctx.requireUserId(), req.reason())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "The register of purges",
      description = "Every purge a service ran for this business, newest first, cursor-paginated.")
  @APIResponse(responseCode = "200", description = "The runs")
  @GET
  @Path("/runs")
  public ApiResponse<List<RunResponse>> runs(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page = service.runs(ctx.requireTenantId(), after, limit);
    return ApiResponse.ok(
        page.items().stream().map(RetentionMappers::toRun).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }
}
