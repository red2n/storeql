package com.storeql.tenant.api;

import com.storeql.ids.Ids;
import com.storeql.tenant.dto.StoreTaskDtos;
import com.storeql.tenant.mapper.StoreTaskMappers;
import com.storeql.tenant.service.StoreTaskService;
import com.storeql.tenant.service.StoreTaskService.Line;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/workforce/tasks}: what a shop does and when, and how each day came to (store
 * operations & workforce).
 *
 * <p>Management's half — writing the list, raising a job by hand, reading the day. The staff half,
 * working the list, is {@link StoreTaskWorkResource} outside {@code /admin/}, because a checklist
 * only a manager could tick is a checklist nobody keeps.
 */
@Path("/admin/workforce/tasks")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Workforce")
public class StoreTaskResource {

  @Inject StoreTaskService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Write a list",
      description =
          "A piece of work the shop does on a schedule. With lines it is a checklist, finished when"
              + " every required line is ticked; without them a single task. It falls due at dueTime on"
              + " the STORE's own clock, so an opening list at 08:00 falls due at 08:00 in Mumbai and"
              + " 08:00 in London, not at the same instant. Omit storeId for every store, which is"
              + " the whole business's to write: a manager held to stores writes lists for one of"
              + " theirs.")
  @APIResponse(responseCode = "201", description = "The list, with its lines")
  @APIResponse(
      responseCode = "400",
      description =
          "TASK_LIST_INVALID, TASK_TITLE_REQUIRED, TASK_LINE_BLANK, TASK_TIME_INVALID,"
              + " TASK_ID_INVALID (storeId), VALIDATION_FAILED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store the caller is not held to;"
              + " BUSINESS_WIDE_ONLY for a list for every store from a caller held to stores")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business")
  @POST
  @Path("/lists")
  public Response create(StoreTaskDtos.TemplateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank() ? null : uuid(req.storeId(), "storeId");
    LocalTime dueTime = time(req.dueTime());
    List<Line> lines =
        req.lines() == null
            ? List.of()
            : req.lines().stream()
                .map(l -> new Line(l.text(), l.required() == null || l.required()))
                .toList();
    // The request (400), then the store is the business's (404), then one the caller may change
    // (403) — so another business's manager naming our store is told it does not exist.
    StoreTaskService.requireWorkable(
        req.title(), req.kind(), req.daysOfWeek(), dueTime, req.graceMinutes(), lines);
    UUID tenantId = ctx.requireTenantId();
    requireMayChange(tenantId, storeId);
    var list =
        svc.create(
            tenantId,
            storeId,
            req.title(),
            req.instructions(),
            req.kind(),
            req.daysOfWeek(),
            dueTime,
            req.graceMinutes(),
            req.role(),
            req.required() == null || req.required(),
            lines,
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(StoreTaskMappers.toDto(list))).build();
  }

  @Operation(
      summary = "The lists this business keeps",
      description =
          "Active ones unless all=true. Withdrawn lists stay, to explain days already worked. A"
              + " manager held to stores reads their stores' lists and those for every store; a"
              + " caller held to none reads them all.")
  @APIResponse(responseCode = "403", description = "FORBIDDEN below management")
  @GET
  @Path("/lists")
  public ApiResponse<List<StoreTaskDtos.TemplateResponse>> lists(@QueryParam("all") Boolean all) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc
            .templates(ctx.requireTenantId(), !Boolean.TRUE.equals(all), ctx.reportStores(null))
            .stream()
            .map(StoreTaskMappers::toDto)
            .toList());
  }

  @Operation(
      summary = "One list, with its lines",
      description = "A list for one store is read at that store; one for every store by anybody.")
  @APIResponse(responseCode = "400", description = "INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a list of a store the caller is not"
              + " held to")
  @APIResponse(responseCode = "404", description = "TASK_LIST_NOT_FOUND")
  @GET
  @Path("/lists/{id}")
  public ApiResponse<StoreTaskDtos.TemplateResponse> list(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    var template = svc.template(ctx.requireTenantId(), id);
    if (template.storeId() != null) ctx.requireStoreAccess(template.storeId());
    return ApiResponse.ok(StoreTaskMappers.toDto(template));
  }

  @Operation(
      summary = "Withdraw a list",
      description =
          "No new days are generated for it. Days already generated stand: what was done was done."
              + " A list for one store is withdrawn at that store; one for every store needs a"
              + " caller held to none, as writing it did.")
  @APIResponse(responseCode = "400", description = "INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a list of a store the caller is not"
              + " held to; BUSINESS_WIDE_ONLY for a list for every store from a caller held to"
              + " stores")
  @APIResponse(responseCode = "404", description = "TASK_LIST_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "TASK_LIST_WITHDRAWN")
  @DELETE
  @Path("/lists/{id}")
  public ApiResponse<StoreTaskDtos.TemplateResponse> withdraw(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    // The list must be the business's (404), then the caller's to change (403), before it moves.
    requireMayChange(tenantId, svc.template(tenantId, id).storeId());
    return ApiResponse.ok(StoreTaskMappers.toDto(svc.withdraw(tenantId, id, ctx.requireUserId())));
  }

  @Operation(
      summary = "Generate a store's day now",
      description =
          "Every active list that falls due on that date, once — the sweeper does this hourly, and a"
              + " manager who has just written the opening list need not wait for it. Idempotent: the"
              + " unique constraint decides, so a day is never generated twice however many ask.")
  @APIResponse(responseCode = "200", description = "How many occurrences this call created")
  @APIResponse(
      responseCode = "400",
      description = "TASK_ID_INVALID (storeId), TASK_DATE_INVALID, VALIDATION_FAILED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store of the business the caller"
              + " is not held to")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business")
  @POST
  @Path("/days")
  public ApiResponse<Integer> generate(StoreTaskDtos.GenerateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = uuid(req.storeId(), "storeId");
    LocalDate named =
        req.businessDate() == null || req.businessDate().isBlank()
            ? null
            : date(req.businessDate(), "businessDate");
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(svc.requireStore(tenantId, storeId));
    LocalDate day = named == null ? svc.today(tenantId, storeId) : named;
    return ApiResponse.ok(svc.generate(tenantId, storeId, day));
  }

  @Operation(
      summary = "Raise a job by hand",
      description =
          "A list put on a store's day once: a delivery to put away, a spill. Today on the store's clock when no date is given.")
  @APIResponse(responseCode = "201", description = "The task, open")
  @APIResponse(
      responseCode = "400",
      description = "TASK_ID_INVALID (storeId, listId), TASK_DATE_INVALID, VALIDATION_FAILED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store of the business the caller"
              + " is not held to")
  @APIResponse(
      responseCode = "404",
      description =
          "STORE_NOT_FOUND: no such store in this business; TASK_LIST_NOT_FOUND: no such list in"
              + " it")
  @APIResponse(
      responseCode = "409",
      description = "TASK_ALREADY_RAISED, TASK_LIST_WITHDRAWN, TASK_LIST_OTHER_STORE")
  @POST
  @Path("/raise")
  public Response raise(StoreTaskDtos.RaiseRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID listId = uuid(req.listId(), "listId");
    LocalDate day =
        req.businessDate() == null || req.businessDate().isBlank()
            ? null
            : date(req.businessDate(), "businessDate");
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(svc.requireStore(tenantId, storeId));
    var task = svc.raise(tenantId, listId, storeId, day);
    return Response.status(201).entity(ApiResponse.ok(StoreTaskMappers.toDto(task))).build();
  }

  @Operation(
      summary = "A store's work over a range of days",
      description = "Every occurrence, with its lines, in the order it fell due. At most 62 days.")
  @APIResponse(
      responseCode = "400",
      description = "TASK_ID_INVALID (storeId), TASK_DATE_INVALID, TASK_RANGE_INVALID")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store of the business the caller"
              + " is not held to")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business")
  @GET
  @Path("/days")
  public ApiResponse<List<StoreTaskDtos.InstanceResponse>> days(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Range range = range(storeId, from, to);
    return ApiResponse.ok(
        svc.day(ctx.requireTenantId(), range.store(), range.from(), range.to()).stream()
            .map(StoreTaskMappers::toDto)
            .toList());
  }

  @Operation(
      summary = "What each day came to",
      description =
          "One summary per business date: done, late, skipped, missed, open. Late is done after it fell"
              + " due, which is not missed; a day is settled when nothing required is open or missed.")
  @APIResponse(
      responseCode = "400",
      description = "TASK_ID_INVALID (storeId), TASK_DATE_INVALID, TASK_RANGE_INVALID")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a store of the business the caller"
              + " is not held to")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business")
  @GET
  @Path("/summary")
  public ApiResponse<List<StoreTaskDtos.DayResponse>> summary(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Range range = range(storeId, from, to);
    return ApiResponse.ok(
        svc.summary(ctx.requireTenantId(), range.store(), range.from(), range.to()).stream()
            .map(StoreTaskMappers::toDto)
            .toList());
  }

  /** A store and the days of it a read covers. */
  private record Range(UUID store, LocalDate from, LocalDate to) {}

  /**
   * A read of a store's days, judged as a write is: the request (400), the store is the business's
   * (404), then one the caller is held to (403).
   */
  private Range range(String storeId, String from, String to) {
    UUID store = uuid(storeId, "storeId");
    LocalDate first = date(from, "from");
    LocalDate last = date(to, "to");
    StoreTaskService.requireRange(first, last);
    ctx.requireStoreAccess(svc.requireStore(ctx.requireTenantId(), store));
    return new Range(store, first, last);
  }

  @Operation(summary = "One task, with its lines", description = "Read at the task's own store.")
  @APIResponse(responseCode = "400", description = "INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN below management; STORE_ACCESS_DENIED for a task at a store the caller is not"
              + " held to")
  @APIResponse(responseCode = "404", description = "TASK_NOT_FOUND")
  @GET
  @Path("/{id}")
  public ApiResponse<StoreTaskDtos.InstanceResponse> task(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    var task = svc.instance(ctx.requireTenantId(), id);
    ctx.requireStoreAccess(task.storeId());
    return ApiResponse.ok(StoreTaskMappers.toDto(task));
  }

  /**
   * A list for one store is changed at that store — which must be the business's (404) before it is
   * judged the caller's (403); one for every store is the whole business's, because it falls due at
   * stores a manager held to some cannot see.
   *
   * @param storeId the list's store, or null for every store
   */
  private void requireMayChange(UUID tenantId, UUID storeId) {
    if (storeId == null) BusinessWide.require(ctx);
    else ctx.requireStoreAccess(svc.requireStore(tenantId, storeId));
  }

  static UUID uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("TASK_ID_INVALID", field + " is required");
    }
    try {
      return Ids.parse(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "TASK_ID_INVALID", field + " is not an id: " + value, List.of(), e);
    }
  }

  static LocalDate date(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("TASK_DATE_INVALID", field + " is a date as YYYY-MM-DD");
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "TASK_DATE_INVALID", field + " is a date as YYYY-MM-DD: " + value, List.of(), e);
    }
  }

  private static LocalTime time(String value) {
    try {
      return LocalTime.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "TASK_TIME_INVALID", "dueTime is a time as HH:mm: " + value, List.of(), e);
    }
  }
}
