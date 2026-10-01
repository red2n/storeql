package com.storeql.tenant.api;

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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/workforce}: the roster, the hours and who was in (store operations & workforce).
 *
 * <p>Management's half. The staff half — clocking in and out — is {@link TimeClockResource} outside
 * {@code /admin/}, because that prefix is gated to management platform-wide and a clock only a
 * manager could press would be a clock nobody used.
 *
 * <p>A shop's biggest controllable cost is its hours, and until now the platform kept none of them.
 */
@Path("/admin/workforce")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Workforce")
public class WorkforceResource {

  @Inject WorkforceService svc;
  @Inject TenantContext ctx;

  /** The longest window a roster, hours or attendance read may span. */
  @Inject
  @ConfigProperty(name = "storeql.workforce.window.max-days", defaultValue = "62")
  int maxWindowDays;

  // ── the roster ──────────────────────────────────────────────────────────────

  @Operation(
      summary = "Roster a shift",
      description =
          "Planned first and published when the rota is settled, so a half-written week is not"
              + " something staff can rely on. A shift for somebody who does not work at that store is"
              + " refused here rather than discovered on the morning.")
  @APIResponse(responseCode = "201", description = "Rostered")
  @APIResponse(responseCode = "409", description = "That person is not assigned to that store")
  @POST
  @Path("/shifts")
  public Response planShift(WorkforceDtos.PlanShiftRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = TimeClockResource.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    var shift =
        svc.planShift(
            ctx.requireTenantId(),
            storeId,
            TimeClockResource.uuid(req.userId(), "userId"),
            instant(req.startsAt(), "startsAt"),
            instant(req.endsAt(), "endsAt"),
            req.duty(),
            req.note(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(WorkforceMappers.toDto(shift))).build();
  }

  @Operation(
      summary = "The roster of a window",
      description =
          "With what is worth saying about each person's own shifts: eleven hours' daily rest, a break"
              + " expected after six, and shifts that overlap. **Flagged, never refused** — the Working"
              + " Time Directive is implemented member state by member state with its own derogations,"
              + " and an employer who has one is entitled to roster against it. What the platform must"
              + " not do is stay quiet, because nobody spots eleven hours by eye.")
  @GET
  @Path("/shifts")
  public ApiResponse<WorkforceDtos.RosterResponse> roster(
      @QueryParam("store") String store,
      @QueryParam("user") String user,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Instant[] w = window(instant(from, "from"), instant(to, "to"));
    return ApiResponse.ok(
        WorkforceMappers.toDto(
            svc.roster(
                ctx.requireTenantId(),
                optionalUuid(store, "store"),
                optionalUuid(user, "user"),
                w[0],
                w[1])));
  }

  @Operation(summary = "Publish a shift", description = "What staff may see and rely on.")
  @APIResponse(responseCode = "409", description = "Only a planned shift is published")
  @POST
  @Path("/shifts/{id}/publish")
  public ApiResponse<WorkforceDtos.ShiftResponse> publish(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        WorkforceMappers.toDto(svc.publishShift(ctx.requireTenantId(), id, ctx.requireUserId())));
  }

  @Operation(
      summary = "Call a shift off",
      description = "With a reason, which is required and stays on the record.")
  @APIResponse(responseCode = "409", description = "Already called off")
  @POST
  @Path("/shifts/{id}/cancel")
  public ApiResponse<WorkforceDtos.ShiftResponse> cancel(
      @PathParam("id") UUID id, WorkforceDtos.CancelShiftRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        WorkforceMappers.toDto(
            svc.cancelShift(ctx.requireTenantId(), id, req.reason(), ctx.requireUserId())));
  }

  // ── the hours ───────────────────────────────────────────────────────────────

  @Operation(
      summary = "The hours worked in a window",
      description =
          "Corrections included as the entries that stand; the ones they replaced are left out, because"
              + " counting both would double a day. An entry still open has no hours yet and says so.")
  @GET
  @Path("/time-entries")
  public ApiResponse<List<WorkforceDtos.EntryResponse>> entries(
      @QueryParam("store") String store,
      @QueryParam("user") String user,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Instant[] w = window(instant(from, "from"), instant(to, "to"));
    return ApiResponse.ok(
        WorkforceMappers.entries(
            svc.entries(
                ctx.requireTenantId(),
                optionalUuid(store, "store"),
                optionalUuid(user, "user"),
                w[0],
                w[1])));
  }

  @Operation(
      summary = "Correct somebody's hours",
      description =
          "The forgotten clock-out, usually. A correction is a **new entry that supersedes** the one it"
              + " replaces, with the reason, and both stay on the record: hours that can be quietly"
              + " rewritten are hours nobody can be held to. The breaks come with it, or the"
              + " correction would pay for the lunch hour.")
  @APIResponse(responseCode = "400", description = "No reason, or a window that is not one")
  @APIResponse(
      responseCode = "403",
      description = "Another store than the caller is held to, or the caller's own hours")
  @APIResponse(responseCode = "409", description = "That entry has already been corrected")
  @POST
  @Path("/time-entries/{id}/adjust")
  public ApiResponse<WorkforceDtos.EntryResponse> adjust(
      @PathParam("id") UUID id, WorkforceDtos.AdjustRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    // The entry must be the business's (404), then at a store the caller is held to (403); only
    // then is it their own hours (403 WORKFORCE_SELF_ADJUST_REFUSED, judged in the service).
    ctx.requireStoreAccess(svc.storeOfEntry(ctx.requireTenantId(), id));
    return ApiResponse.ok(
        WorkforceMappers.toDto(
            svc.adjust(
                ctx.requireTenantId(),
                id,
                optionalInstant(req.clockedInAt(), "clockedInAt"),
                optionalInstant(req.clockedOutAt(), "clockedOutAt"),
                req.reason(),
                ctx.requireUserId())));
  }

  @Operation(
      summary = "Clock somebody in by hand",
      description =
          "For the terminal that was down, or the person who forgot. Recorded as MANAGER rather than"
              + " CLOCK, so an audit of hours can tell who pressed what.")
  @APIResponse(responseCode = "201", description = "On the clock")
  @APIResponse(responseCode = "409", description = "Already on the clock, or not assigned there")
  @POST
  @Path("/time-entries")
  public Response clockInFor(@QueryParam("user") String user, WorkforceDtos.ClockInRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId =
        svc.requireStore(ctx.requireTenantId(), TimeClockResource.uuid(req.storeId(), "storeId"));
    ctx.requireStoreAccess(storeId);
    var entry =
        svc.clockIn(
            ctx.requireTenantId(),
            TimeClockResource.uuid(user, "user"),
            storeId,
            req.shiftId() == null || req.shiftId().isBlank()
                ? null
                : TimeClockResource.uuid(req.shiftId(), "shiftId"),
            Workforce.SOURCE_MANAGER,
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(WorkforceMappers.toDto(entry))).build();
  }

  // ── what an hour costs ──────────────────────────────────────────────────────

  @Operation(
      summary = "Record what an hour of somebody's time costs",
      description =
          "Dated, and append-only: a rise must not re-cost the past, or last quarter's labour figure"
              + " would disagree with itself the day somebody got a pay rise. This is a rate and"
              + " nothing else — no salary, no deductions, no payroll — because the question a shop"
              + " asks is what a Saturday cost against what it took. Zero is meaningful (an unpaid"
              + " trial, a proprietor drawing no wage); less than zero is not.")
  @APIResponse(responseCode = "201", description = "Recorded")
  @APIResponse(
      responseCode = "409",
      description = "A rate already starts on that day for that person")
  @POST
  @Path("/pay-rates")
  public Response addRate(WorkforceDtos.AddPayRateRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    java.math.BigDecimal rate;
    try {
      rate = new java.math.BigDecimal(req.hourlyRate().strip());
    } catch (NumberFormatException e) {
      throw new ApiException(
          400, "WORKFORCE_RATE_INVALID", "hourlyRate is an amount such as 12.50", List.of(), e);
    }
    var saved =
        svc.addRate(
            ctx.requireTenantId(),
            TimeClockResource.uuid(req.userId(), "userId"),
            req.effectiveFrom() == null || req.effectiveFrom().isBlank()
                ? null
                : day(req.effectiveFrom(), "effectiveFrom"),
            rate,
            req.currency(),
            req.note(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(WorkforceMappers.toDto(saved))).build();
  }

  @Operation(
      summary = "What somebody's hours have cost, rate by rate",
      description = "Newest first, which is the order the costing rule reads them in.")
  @GET
  @Path("/pay-rates")
  public ApiResponse<List<WorkforceDtos.PayRateResponse>> rates(@QueryParam("user") String user) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        WorkforceMappers.rates(
            svc.rates(ctx.requireTenantId(), TimeClockResource.uuid(user, "user"))));
  }

  // ── attendance ──────────────────────────────────────────────────────────────

  @Operation(
      summary = "Who was in, against who was meant to be",
      description =
          "Day by day and person by person, built from the same objects payroll would read so the two"
              + " cannot disagree about a day. A day appears when either side has something on it:"
              + " rostered and nothing clocked is an **absence**, worked with nothing rostered is as"
              + " much a management fact as an absence, and somebody still on the clock is neither."
              + " `lateByMinutes` compares the first clock-in with the rostered start, and is negative"
              + " for early.")
  @GET
  @Path("/attendance")
  public ApiResponse<List<WorkforceDtos.AttendanceResponse>> attendance(
      @QueryParam("store") String store,
      @QueryParam("user") String user,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    // A named store must be one of the caller's; naming none is exactly the caller's stores (the
    // whole business only for a caller held to none).
    var stores = ctx.reportStores(optionalUuid(store, "store"));
    LocalDate[] d = dayWindow(day(from, "from"), day(to, "to"));
    return ApiResponse.ok(
        WorkforceMappers.attendance(
            svc.attendance(ctx.requireTenantId(), stores, optionalUuid(user, "user"), d[0], d[1])));
  }

  // ── parsing ─────────────────────────────────────────────────────────────────

  /**
   * A window held to the longest span a read may cover.
   *
   * @throws ApiException 400 {@code WORKFORCE_WINDOW_INVALID} for one longer than the limit
   */
  private Instant[] window(Instant from, Instant to) {
    if (java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(maxWindowDays))
        > 0) {
      throw tooLong();
    }
    return new Instant[] {from, to};
  }

  private LocalDate[] dayWindow(LocalDate from, LocalDate to) {
    if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > maxWindowDays) throw tooLong();
    return new LocalDate[] {from, to};
  }

  private ApiException tooLong() {
    return ApiException.badRequest(
        "WORKFORCE_WINDOW_INVALID", "read at most " + maxWindowDays + " days at a time");
  }

  /** An instant, or the window's default: a rota is read a week at a time. */
  static Instant instant(String value, String field) {
    if (value == null || value.isBlank()) {
      return "from".equals(field)
          ? Instant.now().minus(java.time.Duration.ofDays(7))
          : Instant.now().plus(java.time.Duration.ofDays(7));
    }
    try {
      return value.length() <= 10
          ? LocalDate.parse(value.strip()).atStartOfDay().toInstant(ZoneOffset.UTC)
          : Instant.parse(value.strip());
    } catch (RuntimeException e) {
      throw new ApiException(
          400,
          "WORKFORCE_DATE_INVALID",
          field + " is written as 2026-09-14 or 2026-09-14T08:00:00Z",
          List.of(),
          e);
    }
  }

  private static Instant optionalInstant(String value, String field) {
    return value == null || value.isBlank() ? null : instant(value, field);
  }

  private static LocalDate day(String value, String field) {
    if (value == null || value.isBlank()) {
      return "from".equals(field)
          ? LocalDate.now(ZoneOffset.UTC).minusDays(7)
          : LocalDate.now(ZoneOffset.UTC).plusDays(1);
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (RuntimeException e) {
      throw new ApiException(
          400, "WORKFORCE_DATE_INVALID", field + " is written as 2026-09-14", List.of(), e);
    }
  }

  private static UUID optionalUuid(String value, String field) {
    return value == null || value.isBlank() ? null : TimeClockResource.uuid(value, field);
  }
}
