package com.storeql.tenant.api;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Commission.Band;
import com.storeql.tenant.domain.Commission.Day;
import com.storeql.tenant.domain.Commission.Scheme;
import com.storeql.tenant.dto.CommissionDtos;
import com.storeql.tenant.mapper.CommissionMappers;
import com.storeql.tenant.service.CommissionService;
import com.storeql.tenant.service.CommissionService.Rated;
import com.storeql.tenant.service.CommissionService.SellerDays;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/workforce/commission}: what a sale earns the person who made it.
 *
 * <p>The arrangement lives here, beside the hours and what they cost, because it is a term of
 * employment. The money is worked out here too — {@code POST /rate} — and called by the service
 * that holds the sales: it sends figures, never rows, so a period's sales never leave the service
 * that owns them and the commission rule exists in exactly one place.
 */
@Path("/admin/workforce/commission")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Workforce")
public class CommissionResource {

  @Inject CommissionService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The commission arrangements this business offers",
      description =
          "Newest first, each with its marginal rate bands. Pass all=true to include withdrawn and"
              + " superseded versions, which a statement needs to explain what it paid.")
  @GET
  @Path("/schemes")
  public ApiResponse<List<CommissionDtos.SchemeResponse>> schemes(@QueryParam("all") Boolean all) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc.schemes(ctx.requireTenantId(), !Boolean.TRUE.equals(all)).stream()
            .map(CommissionMappers::toDto)
            .toList());
  }

  @Operation(summary = "One arrangement, with its bands")
  @APIResponse(responseCode = "404", description = "No such scheme")
  @GET
  @Path("/schemes/{id}")
  public ApiResponse<CommissionDtos.SchemeResponse> scheme(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(CommissionMappers.toDto(svc.scheme(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Record a commission arrangement",
      description =
          "Bands are marginal: only the part of a period's sales inside a band earns that band's"
              + " rate. The first band starts at 0, because otherwise the first sales of every"
              + " period earn nothing and nobody notices until a statement is disputed.")
  @APIResponse(responseCode = "201", description = "The arrangement, with its bands")
  @APIResponse(
      responseCode = "400",
      description =
          "COMMISSION_SCHEME_INVALID: an unknown basis, a per-unit scheme without a currency, a"
              + " percentage with one, no bands, a first band above zero, or two bands at one figure")
  @POST
  @Path("/schemes")
  public Response create(CommissionDtos.SchemeRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    Scheme scheme =
        svc.create(
            ctx.requireTenantId(),
            req.name(),
            req.basis(),
            req.currency(),
            bands(req),
            req.note(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(CommissionMappers.toDto(scheme))).build();
  }

  @Operation(
      summary = "Correct an arrangement",
      description =
          "Writes a new version and moves whoever is on the old one across from effectiveFrom. The"
              + " old version is left exactly as it was, because commission already earned under it"
              + " was earned under its rates — a rate that could be edited is one nobody can be paid"
              + " on. Somebody who already has an arrangement written for that very day keeps it.")
  @APIResponse(responseCode = "409", description = "COMMISSION_SCHEME_SUPERSEDED")
  @POST
  @Path("/schemes/{id}/corrections")
  public Response correct(@PathParam("id") UUID id, CommissionDtos.SchemeRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    Scheme scheme =
        svc.correct(
            ctx.requireTenantId(),
            id,
            req.name(),
            req.basis(),
            req.currency(),
            bands(req),
            req.note(),
            date(req.effectiveFrom(), "effectiveFrom"),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(CommissionMappers.toDto(scheme))).build();
  }

  @Operation(
      summary = "Withdraw an arrangement",
      description =
          "Nobody new goes on it, and what was earned under it stands. Refused while anybody is"
              + " still on it: a shop that believed commission had stopped and found it had not is"
              + " why.")
  @APIResponse(responseCode = "409", description = "COMMISSION_SCHEME_IN_USE")
  @DELETE
  @Path("/schemes/{id}")
  public ApiResponse<CommissionDtos.SchemeResponse> withdraw(@PathParam("id") UUID id) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(CommissionMappers.toDto(svc.withdraw(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "A person's arrangements over time",
      description = "Newest first. A row with no scheme is the day their commission stopped.")
  @GET
  @Path("/staff/{userId}")
  public ApiResponse<List<CommissionDtos.AssignmentResponse>> assignments(
      @PathParam("userId") UUID userId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc.assignments(ctx.requireTenantId(), userId).stream()
            .map(CommissionMappers::toDto)
            .toList());
  }

  @Operation(
      summary = "Put somebody on an arrangement, or take them off it",
      description =
          "Dated exactly as a pay rate is: the arrangement in force for a sale is the latest one"
              + " effective on or before the day it was sold, so moving somebody in April does not"
              + " re-earn January. Leave schemeId out to end the arrangement from that day.")
  @APIResponse(
      responseCode = "409",
      description =
          "COMMISSION_NOT_STAFF, COMMISSION_SCHEME_NOT_CURRENT, or COMMISSION_ARRANGEMENT_EXISTS"
              + " when one already starts that day")
  @PUT
  @Path("/staff/{userId}")
  public Response assign(@PathParam("userId") UUID userId, CommissionDtos.AssignmentRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    var assignment =
        svc.assign(
            ctx.requireTenantId(),
            userId,
            req.schemeId() == null || req.schemeId().isBlank() ? null : uuid(req.schemeId()),
            date(req.effectiveFrom(), "effectiveFrom"),
            req.note(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(CommissionMappers.toDto(assignment))).build();
  }

  @Operation(
      summary = "What these sales earn",
      description =
          "For the service that holds the sales: it sends what each person sold day by day, and gets"
              + " back what that earns, segment by segment. Figures only — no sale ever leaves the"
              + " service that owns it — and the rule lives here, where the arrangement does, so"
              + " nobody is ever paid on a second implementation of it. A person on no arrangement"
              + " comes back with their sales and no commission rather than being left out, because"
              + " sales that earned nothing are what a manager needs to see.")
  @APIResponse(
      responseCode = "400",
      description = "COMMISSION_PERIOD_INVALID or COMMISSION_PERIOD_TOO_LARGE")
  @POST
  @Path("/rate")
  public ApiResponse<List<CommissionDtos.RatedResponse>> rate(CommissionDtos.RateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    List<SellerDays> sellers =
        req.sellers().stream()
            .map(
                s ->
                    new SellerDays(
                        uuid(s.userId()),
                        s.days().stream()
                            .map(
                                d ->
                                    new Day(
                                        date(d.day(), "day"),
                                        d.net() == null ? BigDecimal.ZERO : d.net(),
                                        d.units() == null ? BigDecimal.ZERO : d.units()))
                            .toList()))
            .toList();
    UUID tenantId = ctx.requireTenantId();
    List<Rated> rated = svc.rate(tenantId, date(req.from(), "from"), date(req.to(), "to"), sellers);
    Map<String, String> names = new LinkedHashMap<>();
    for (Scheme s : svc.schemes(tenantId, false)) names.put(s.id().toString(), s.name());
    return ApiResponse.ok(rated.stream().map(r -> CommissionMappers.toDto(r, names)).toList());
  }

  private static List<Band> bands(CommissionDtos.SchemeRequest req) {
    return req.bands().stream()
        .map(b -> new Band(null, null, b.thresholdFrom(), b.rate()))
        .toList();
  }

  private static UUID uuid(String value) {
    try {
      return Ids.parse(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "COMMISSION_ID_INVALID", "that is not an id: " + value, List.of(), e);
    }
  }

  private static LocalDate date(String value, String field) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400,
          "COMMISSION_DATE_INVALID",
          field + " is a date as YYYY-MM-DD: " + value,
          List.of(),
          e);
    }
  }
}
