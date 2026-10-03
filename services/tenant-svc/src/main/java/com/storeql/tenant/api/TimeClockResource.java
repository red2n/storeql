package com.storeql.tenant.api;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.dto.WorkforceDtos;
import com.storeql.tenant.mapper.WorkforceMappers;
import com.storeql.tenant.service.WorkforceService;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /workforce/clock}: a member of staff clocking themselves in and out.
 *
 * <p><b>Outside {@code /admin/} deliberately.</b> That prefix is gated to management for the whole
 * platform, and the people who clock in are cashiers and storekeepers — the staff a shop is made
 * of. A clock only management could press would be a clock nobody used.
 *
 * <p>Every route here acts on <b>the caller's own hours</b>: the user id comes from the token and
 * is never taken from the request, for the same reason a tenant id never is. A manager writing
 * hours for somebody else is a different act, under {@code /admin/workforce}, recorded as such and
 * with a reason.
 */
@Path("/workforce/clock")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Time Clock")
public class TimeClockResource {

  @Inject WorkforceService svc;
  @Inject TenantContext ctx;

  /** The longest window a roster read may span, the same for staff and management. */
  @Inject
  @ConfigProperty(name = "storeql.workforce.window.max-days", defaultValue = "62")
  int maxWindowDays;

  @Operation(
      summary = "Clock in",
      description =
          "The caller's own hours begin. A second tap is answered with a conflict rather than a second"
              + " entry — the database decides that, so two taps on a slow terminal cannot become two"
              + " afternoons' pay. Naming a rostered shift ties the hours to the plan, which is what"
              + " lets an attendance report say who was late rather than only who was in.")
  @APIResponse(responseCode = "201", description = "On the clock")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED or BODY_REQUIRED; WORKFORCE_ID_REQUIRED or WORKFORCE_ID_INVALID;"
              + " WORKFORCE_SHIFT_NOT_THEIRS: the shift is rostered for somebody else;"
              + " WORKFORCE_SHIFT_AT_ANOTHER_STORE: the shift is at another store than the one"
              + " clocked in at")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN for a caller who is not staff; STORE_ACCESS_DENIED for a store of the"
              + " business the caller is not held to")
  @APIResponse(
      responseCode = "404",
      description =
          "STORE_NOT_FOUND: no such store in this business; WORKFORCE_SHIFT_NOT_FOUND: no such"
              + " shift in it")
  @APIResponse(
      responseCode = "409",
      description =
          "WORKFORCE_ALREADY_CLOCKED_IN; WORKFORCE_NOT_ASSIGNED: not assigned to that store;"
              + " WORKFORCE_SHIFT_CANCELLED: the shift was called off")
  @POST
  @Path("/in")
  public Response clockIn(WorkforceDtos.ClockInRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    Validations.validate(req);
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID shiftId = optionalUuid(req.shiftId(), "shiftId");
    UUID tenantId = ctx.requireTenantId();
    // The store must be the business's (404) — another business's staff naming our store, even
    // with it among their own store ids, are told it does not exist — then one the caller is held
    // to (403), before anything is written; as the manual clock-in judges it.
    ctx.requireStoreAccess(svc.requireStore(tenantId, storeId));
    var entry =
        svc.clockIn(
            tenantId,
            ctx.requireUserId(),
            storeId,
            shiftId,
            Workforce.SOURCE_CLOCK,
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(WorkforceMappers.toDto(entry))).build();
  }

  @Operation(
      summary = "Clock out",
      description =
          "The caller's hours end, and a break they forgot to end ends with them, at the same instant."
              + " Nobody is kept at a terminal arguing with a validation message at the end of a"
              + " shift.")
  @APIResponse(responseCode = "409", description = "Not on the clock")
  @POST
  @Path("/out")
  public ApiResponse<WorkforceDtos.EntryResponse> clockOut() {
    ctx.requireAnyRole("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    return ApiResponse.ok(
        WorkforceMappers.toDto(svc.clockOut(ctx.requireTenantId(), ctx.requireUserId())));
  }

  @Operation(
      summary = "Am I on the clock?",
      description =
          "The open entry with its breaks, or nothing. The hours are absent while it is open rather"
              + " than zero: a zero looks like a day nobody worked.")
  @GET
  @Path("/open")
  public ApiResponse<WorkforceDtos.EntryResponse> open() {
    ctx.requireAnyRole("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    return ApiResponse.ok(
        svc.onTheClock(ctx.requireTenantId(), ctx.requireUserId())
            .map(WorkforceMappers::toDto)
            .orElse(null));
  }

  @Operation(
      summary = "Start a break",
      description =
          "On the shift the caller is working. Whether it is paid is the employer's arrangement, kept"
              + " here rather than decided: unpaid breaks come off the hours and paid ones do not. One"
              + " break at a time, because a second would make the day's arithmetic undecidable.")
  @APIResponse(
      responseCode = "409",
      description = "Not on the clock, or a break is already running")
  @POST
  @Path("/breaks/start")
  public ApiResponse<WorkforceDtos.EntryResponse> startBreak(WorkforceDtos.StartBreakRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    // The body is optional (a REST break, unpaid); one that is sent is held to its constraints.
    if (req != null) Validations.validate(req);
    return ApiResponse.ok(
        WorkforceMappers.toDto(
            svc.startBreak(
                ctx.requireTenantId(),
                ctx.requireUserId(),
                req == null ? null : req.kind(),
                req != null && req.paid())));
  }

  @Operation(summary = "End the break")
  @APIResponse(responseCode = "409", description = "No break is running")
  @POST
  @Path("/breaks/end")
  public ApiResponse<WorkforceDtos.EntryResponse> endBreak() {
    ctx.requireAnyRole("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    return ApiResponse.ok(
        WorkforceMappers.toDto(svc.endBreak(ctx.requireTenantId(), ctx.requireUserId())));
  }

  @Operation(
      summary = "The shifts I am rostered for",
      description =
          "The caller's own roster over a window, with nobody else's. A week either side of now when"
              + " from and to are left out; a window that ends before it begins, or runs longer than"
              + " 62 days, is refused.")
  @APIResponse(
      responseCode = "400",
      description = "WORKFORCE_DATE_INVALID, WORKFORCE_WINDOW_INVALID")
  @GET
  @Path("/shifts")
  public ApiResponse<WorkforceDtos.RosterResponse> myShifts(
      @jakarta.ws.rs.QueryParam("from") String from, @jakarta.ws.rs.QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    // The same window every other roster read is held to, however small the roster is.
    Instant[] w =
        WorkforceResource.window(
            WorkforceResource.instant(from, "from"),
            WorkforceResource.instant(to, "to"),
            maxWindowDays);
    return ApiResponse.ok(
        WorkforceMappers.toDto(
            svc.roster(ctx.requireTenantId(), null, ctx.requireUserId(), w[0], w[1])));
  }

  /** An id that may be left out: null when it is, else held to the same rule as {@link #uuid}. */
  static UUID optionalUuid(String value, String field) {
    return value == null || value.isBlank() ? null : uuid(value, field);
  }

  static UUID uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("WORKFORCE_ID_REQUIRED", field + " is required");
    }
    try {
      return Ids.parse(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "WORKFORCE_ID_INVALID", field + " is not an id", List.of(), e);
    }
  }
}
