package com.storeql.tenant.api;

import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.dto.WorkforceDtos;
import com.storeql.tenant.mapper.WorkforceMappers;
import com.storeql.tenant.service.WorkforceService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.HttpHeaders;
import com.storeql.web.IdempotencyKeys;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED or BODY_REQUIRED; WORKFORCE_ID_REQUIRED or WORKFORCE_ID_INVALID: a"
              + " store or person that is not an id; WORKFORCE_DATE_INVALID; WORKFORCE_WINDOW_INVALID:"
              + " a shift that does not end after it starts, or runs past 24 hours")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store of the business the caller"
              + " is not held to")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business")
  @APIResponse(
      responseCode = "409",
      description = "WORKFORCE_NOT_ASSIGNED: that person is not assigned to that store")
  @POST
  @Path("/shifts")
  public Response planShift(WorkforceDtos.PlanShiftRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = TimeClockResource.uuid(req.storeId(), "storeId");
    UUID userId = TimeClockResource.uuid(req.userId(), "userId");
    Instant startsAt = instant(req.startsAt(), "startsAt");
    Instant endsAt = instant(req.endsAt(), "endsAt");
    WorkforceService.requireShiftWindow(startsAt, endsAt);
    UUID tenantId = ctx.requireTenantId();
    // The request (400), then the store is the business's (404), then one the caller is held to
    // (403) — so another business's manager naming our store is told it does not exist — and only
    // then is anything rostered.
    ctx.requireStoreAccess(svc.requireStore(tenantId, storeId));
    var shift =
        svc.planShift(
            tenantId,
            storeId,
            userId,
            startsAt,
            endsAt,
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
              + " not do is stay quiet, because nobody spots eleven hours by eye. Read at the"
              + " caller's own stores: a named store must be one of theirs, and naming none reads"
              + " exactly their stores (the whole business for a caller held to none).")
  @APIResponse(responseCode = "403", description = "A store the caller is not held to")
  @GET
  @Path("/shifts")
  public ApiResponse<WorkforceDtos.RosterResponse> roster(
      @QueryParam("store") String store,
      @QueryParam("user") String user,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    // As attendance: a named store must be one of the caller's; naming none is exactly the
    // caller's stores (the whole business only for a caller held to none).
    var stores = ctx.reportStores(optionalUuid(store, "store"));
    Instant[] w = window(instant(from, "from"), instant(to, "to"));
    return ApiResponse.ok(
        WorkforceMappers.toDto(
            svc.roster(ctx.requireTenantId(), stores, optionalUuid(user, "user"), w[0], w[1])));
  }

  @Operation(
      summary = "Publish a shift",
      description =
          "What staff may see and rely on. Judged at the shift's own store: a manager held to"
              + " stores publishes only at theirs. A retryable write, so it needs an"
              + " Idempotency-Key (a UUIDv7, new for each shift): a retry under the same key"
              + " answers the published shift again rather than a conflict, and the same key sent"
              + " for another shift is refused.")
  @APIResponse(
      responseCode = "400",
      description =
          "INVALID_UUID: the path is not an id; IDEMPOTENCY_KEY_REQUIRED: no Idempotency-Key;"
              + " IDEMPOTENCY_KEY_INVALID: a key that is not a UUIDv7")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a shift at a store the caller is not"
              + " held to")
  @APIResponse(
      responseCode = "404",
      description = "WORKFORCE_SHIFT_NOT_FOUND: no such shift in this business")
  @APIResponse(
      responseCode = "409",
      description =
          "WORKFORCE_SHIFT_NOT_PLANNED: only a planned shift is published;"
              + " IDEMPOTENCY_KEY_REUSED: the key already published another shift")
  @POST
  @Path("/shifts/{id}/publish")
  public ApiResponse<WorkforceDtos.ShiftResponse> publish(
      @PathParam("id") UUID id, @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    String key = requireKey(idempotencyKey);
    UUID tenantId = ctx.requireTenantId();
    // The shift must be the business's (404), then at a store the caller is held to (403), before
    // it moves.
    ctx.requireStoreAccess(svc.storeOfShift(tenantId, id));
    return ApiResponse.ok(
        WorkforceMappers.toDto(svc.publishShift(tenantId, id, key, ctx.requireUserId())));
  }

  /**
   * The request's Idempotency-Key in canonical form; a retryable write without one is refused.
   *
   * @throws ApiException 400 {@code IDEMPOTENCY_KEY_REQUIRED} without one, {@code
   *     IDEMPOTENCY_KEY_INVALID} for one that is not a UUIDv7
   */
  static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw ApiException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED", "the Idempotency-Key header is required");
    }
    return IdempotencyKeys.require(key.strip());
  }

  @Operation(
      summary = "Call a shift off",
      description =
          "With a reason, which is required and stays on the record. Judged at the shift's own"
              + " store: a manager held to stores calls off only theirs.")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED or BODY_REQUIRED: no reason; INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a shift at a store the caller is not"
              + " held to")
  @APIResponse(
      responseCode = "404",
      description = "WORKFORCE_SHIFT_NOT_FOUND: no such shift in this business")
  @APIResponse(
      responseCode = "409",
      description =
          "WORKFORCE_SHIFT_CANCELLED: already called off; WORKFORCE_SHIFT_CHANGED: it changed as it"
              + " was read")
  @POST
  @Path("/shifts/{id}/cancel")
  public ApiResponse<WorkforceDtos.ShiftResponse> cancel(
      @PathParam("id") UUID id, WorkforceDtos.CancelShiftRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(svc.storeOfShift(tenantId, id));
    return ApiResponse.ok(
        WorkforceMappers.toDto(svc.cancelShift(tenantId, id, req.reason(), ctx.requireUserId())));
  }

  // ── the hours ───────────────────────────────────────────────────────────────

  @Operation(
      summary = "The hours worked in a window",
      description =
          "Corrections included as the entries that stand; the ones they replaced are left out, because"
              + " counting both would double a day. An entry still open has no hours yet and says so."
              + " Read at the caller's own stores, as the roster is.")
  @APIResponse(responseCode = "403", description = "A store the caller is not held to")
  @GET
  @Path("/time-entries")
  public ApiResponse<List<WorkforceDtos.EntryResponse>> entries(
      @QueryParam("store") String store,
      @QueryParam("user") String user,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    var stores = ctx.reportStores(optionalUuid(store, "store"));
    Instant[] w = window(instant(from, "from"), instant(to, "to"));
    return ApiResponse.ok(
        WorkforceMappers.entries(
            svc.entries(ctx.requireTenantId(), stores, optionalUuid(user, "user"), w[0], w[1])));
  }

  @Operation(
      summary = "Correct somebody's hours",
      description =
          "The forgotten clock-out, usually. A correction is a **new entry that supersedes** the one it"
              + " replaces, with the reason, and both stay on the record: hours that can be quietly"
              + " rewritten are hours nobody can be held to. The breaks come with it, or the"
              + " correction would pay for the lunch hour. Judged at the entry's own store, and"
              + " never one's own hours. A retryable write, so it needs an Idempotency-Key (a"
              + " UUIDv7, new for each correction): the same request sent again under the key is"
              + " answered with the correction it made rather than a conflict, and the key sent"
              + " for another entry, other times or another reason is refused.")
  @APIResponse(
      responseCode = "400",
      description =
          "IDEMPOTENCY_KEY_REQUIRED: no Idempotency-Key; IDEMPOTENCY_KEY_INVALID: a key that is not"
              + " a UUIDv7; VALIDATION_FAILED or BODY_REQUIRED: no reason; INVALID_UUID: the path is"
              + " not an id; WORKFORCE_DATE_INVALID; WORKFORCE_WINDOW_INVALID: hours that end before"
              + " they start or run past 24 hours")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for an entry at a store the caller is"
              + " not held to; WORKFORCE_SELF_ADJUST_REFUSED for the caller's own hours")
  @APIResponse(
      responseCode = "404",
      description = "WORKFORCE_ENTRY_NOT_FOUND: no such time entry in this business")
  @APIResponse(
      responseCode = "409",
      description =
          "WORKFORCE_ENTRY_NOT_STANDING: that entry has already been corrected;"
              + " IDEMPOTENCY_KEY_REUSED: the key already made another correction")
  @POST
  @Path("/time-entries/{id}/adjust")
  public ApiResponse<WorkforceDtos.EntryResponse> adjust(
      @PathParam("id") UUID id,
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      WorkforceDtos.AdjustRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    String key = requireKey(idempotencyKey);
    Validations.validate(req);
    Instant in = optionalInstant(req.clockedInAt(), "clockedInAt");
    Instant out = optionalInstant(req.clockedOutAt(), "clockedOutAt");
    UUID tenantId = ctx.requireTenantId();
    // The entry must be the business's (404), then at a store the caller is held to (403); only
    // then is it their own hours (403 WORKFORCE_SELF_ADJUST_REFUSED, judged in the service).
    ctx.requireStoreAccess(svc.storeOfEntry(tenantId, id));
    return ApiResponse.ok(
        WorkforceMappers.toDto(
            svc.adjust(tenantId, id, in, out, req.reason(), key, ctx.requireUserId())));
  }

  @Operation(
      summary = "Clock somebody in by hand",
      description =
          "For the terminal that was down, or the person who forgot. Recorded as MANAGER rather than"
              + " CLOCK, so an audit of hours can tell who pressed what.")
  @APIResponse(responseCode = "201", description = "On the clock")
  @APIResponse(
      responseCode = "400",
      description =
          "WORKFORCE_ID_REQUIRED or WORKFORCE_ID_INVALID; WORKFORCE_SHIFT_NOT_THEIRS: the shift is"
              + " rostered for somebody else; WORKFORCE_SHIFT_AT_ANOTHER_STORE: the shift is at"
              + " another store than the one clocked in at")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store the caller is not held to;"
              + " WORKFORCE_SELF_ADJUST_REFUSED for the caller's own hours")
  @APIResponse(
      responseCode = "404",
      description =
          "STORE_NOT_FOUND: no such store in this business; WORKFORCE_SHIFT_NOT_FOUND: no such"
              + " shift in it")
  @APIResponse(
      responseCode = "409",
      description =
          "WORKFORCE_ALREADY_CLOCKED_IN, WORKFORCE_NOT_ASSIGNED, WORKFORCE_SHIFT_CANCELLED")
  @POST
  @Path("/time-entries")
  public Response clockInFor(@QueryParam("user") String user, WorkforceDtos.ClockInRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID person = TimeClockResource.uuid(user, "user");
    UUID storeId = TimeClockResource.uuid(req.storeId(), "storeId");
    UUID shiftId = TimeClockResource.optionalUuid(req.shiftId(), "shiftId");
    UUID tenantId = ctx.requireTenantId();
    // The store must be the business's (404), then one the caller is held to (403).
    ctx.requireStoreAccess(svc.requireStore(tenantId, storeId));
    var entry =
        svc.clockIn(
            tenantId, person, storeId, shiftId, Workforce.SOURCE_MANAGER, ctx.requireUserId());
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
              + " trial, a proprietor drawing no wage); less than zero is not. The rate is kept to"
              + " four decimal places in any currency, as payroll quotes it; what it costs is"
              + " rounded to the currency's own minor units.")
  @APIResponse(responseCode = "201", description = "Recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED or BODY_REQUIRED; WORKFORCE_ID_REQUIRED or WORKFORCE_ID_INVALID;"
              + " WORKFORCE_RATE_INVALID: not an amount written out (digits and one point; an"
              + " exponent such as 1E+3 is refused), below zero, or more than 8 digits before the"
              + " point or 4 after it; WORKFORCE_DATE_INVALID; CURRENCY_INVALID")
  @APIResponse(
      responseCode = "403",
      description = "FORBIDDEN below management; BUSINESS_WIDE_ONLY for a caller held to stores")
  @APIResponse(
      responseCode = "409",
      description = "A rate already starts on that day for that person")
  @POST
  @Path("/pay-rates")
  public Response addRate(WorkforceDtos.AddPayRateRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    java.math.BigDecimal rate = WorkforceService.readRate(req.hourlyRate());
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
      description =
          "Newest first, which is the order the costing rule reads them in. A rate belongs to a"
              + " person, not a store: a manager held to stores reads it only for somebody assigned"
              + " at one of them — narrower than the staff list, which also shows them the"
              + " business-wide people, because a branch manager does not read the pay of the"
              + " managers above them; a caller held to none reads anybody's.")
  @APIResponse(
      responseCode = "400",
      description = "WORKFORCE_ID_REQUIRED or WORKFORCE_ID_INVALID: user is missing or not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a caller held to stores asking about"
              + " somebody who works at none of them")
  @GET
  @Path("/pay-rates")
  public ApiResponse<List<WorkforceDtos.PayRateResponse>> rates(@QueryParam("user") String user) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    UUID person = TimeClockResource.uuid(user, "user");
    svc.requirePersonAtStores(tenantId, person, ctx.reportStores(null));
    return ApiResponse.ok(WorkforceMappers.rates(svc.rates(tenantId, person)));
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
   * A window that runs forwards and is held to the longest span a read may cover.
   *
   * @throws ApiException 400 {@code WORKFORCE_WINDOW_INVALID} for one that ends before it begins or
   *     is longer than the limit
   */
  private Instant[] window(Instant from, Instant to) {
    return window(from, to, maxWindowDays);
  }

  /**
   * The same window for every read of the roster, the caller's own included.
   *
   * @param maxDays the longest span a read may cover
   */
  static Instant[] window(Instant from, Instant to, int maxDays) {
    if (from.isAfter(to)) throw backwards();
    if (java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(maxDays)) > 0) {
      throw tooLong(maxDays);
    }
    return new Instant[] {from, to};
  }

  private LocalDate[] dayWindow(LocalDate from, LocalDate to) {
    if (from.isAfter(to)) throw backwards();
    if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > maxWindowDays) {
      throw tooLong(maxWindowDays);
    }
    return new LocalDate[] {from, to};
  }

  private static ApiException backwards() {
    // An empty answer to a window that cannot hold anything reads as "nobody worked".
    return ApiException.badRequest("WORKFORCE_WINDOW_INVALID", "from must not be after to");
  }

  private static ApiException tooLong(int maxDays) {
    return ApiException.badRequest(
        "WORKFORCE_WINDOW_INVALID", "read at most " + maxDays + " days at a time");
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
