package com.storeql.tenant.api;

import com.storeql.tenant.dto.StoreTaskDtos;
import com.storeql.tenant.mapper.StoreTaskMappers;
import com.storeql.tenant.service.StoreTaskService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /workforce/tasks}: today's list, worked by the people on shift (store operations &
 * workforce).
 *
 * <p><b>Outside {@code /admin/} deliberately</b>, as the clock is: that prefix is gated to
 * management platform-wide, and the people who open up and lock the back door are cashiers and
 * storekeepers. A checklist only a manager could tick is a checklist nobody keeps.
 *
 * <p>Who ticked what comes from the token, never from the request. Every act is judged at the
 * task's own store, in this order: the task must be the business's ({@code 404}), the store one the
 * caller is held to ({@code 403 STORE_ACCESS_DENIED}), and the caller assigned there ({@code 409
 * WORKFORCE_NOT_ASSIGNED}) — so a list is worked by the shop it belongs to. Management held to no
 * store (an owner, a business-wide manager) works any store's list without being assigned there, as
 * they act at any store elsewhere.
 */
@Path("/workforce/tasks")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Time Clock")
public class StoreTaskWorkResource {

  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  @Inject StoreTaskService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Today's list at a store",
      description =
          "Every task on the store's own today, in the order it falls due, with its lines. Today is"
              + " the store's, on its own clock. Yesterday's unfinished work is not here: it is on"
              + " the day it belonged to, where the manager's report will find it missed.")
  @APIResponse(
      responseCode = "400",
      description = "TASK_ID_INVALID: storeId is missing or not an id; TASK_DATE_INVALID")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN for a caller who is not staff; STORE_ACCESS_DENIED for a store the caller is"
              + " not held to")
  @APIResponse(
      responseCode = "404",
      description = "STORE_NOT_FOUND: no such store in this business")
  @GET
  public ApiResponse<List<StoreTaskDtos.InstanceResponse>> today(
      @QueryParam("storeId") String storeId, @QueryParam("date") String date) {
    ctx.requireAnyRole(STAFF);
    UUID store = StoreTaskResource.uuid(storeId, "storeId");
    LocalDate named = date == null || date.isBlank() ? null : StoreTaskResource.date(date, "date");
    UUID tenantId = ctx.requireTenantId();
    // The request (400), then the store is the business's (404), then one the caller is held to
    // (403): another business's staff naming our store are told it does not exist.
    ctx.requireStoreAccess(svc.requireStore(tenantId, store));
    LocalDate day = named == null ? svc.today(tenantId, store) : named;
    return ApiResponse.ok(
        svc.day(tenantId, store, day, day).stream().map(StoreTaskMappers::toDto).toList());
  }

  @Operation(summary = "One task, with its lines")
  @GET
  @Path("/{id}")
  public ApiResponse<StoreTaskDtos.InstanceResponse> task(@PathParam("id") UUID id) {
    ctx.requireAnyRole(STAFF);
    var task = svc.instance(ctx.requireTenantId(), id);
    ctx.requireStoreAccess(task.storeId());
    return ApiResponse.ok(StoreTaskMappers.toDto(task));
  }

  @Operation(
      summary = "Tick a line",
      description =
          "One line of a checklist, by whoever is at the till. A line ticked twice is a conflict, not a second tick.")
  @APIResponse(responseCode = "400", description = "INVALID_UUID: the path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN for a caller who is not staff; STORE_ACCESS_DENIED for a task at a store the"
              + " caller is not held to")
  @APIResponse(
      responseCode = "404",
      description = "TASK_NOT_FOUND: no such task in this business; TASK_LINE_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description =
          "TASK_NOT_OPEN, TASK_LINE_TICKED; WORKFORCE_NOT_ASSIGNED: not assigned at the task's"
              + " store")
  @POST
  @Path("/{id}/lines/{position}/tick")
  public ApiResponse<StoreTaskDtos.InstanceResponse> tick(
      @PathParam("id") UUID id, @PathParam("position") int position) {
    ctx.requireAnyRole(STAFF);
    UUID tenantId = ctx.requireTenantId();
    heldAtTheTasksStore(tenantId, id);
    return ApiResponse.ok(
        StoreTaskMappers.toDto(
            svc.tick(tenantId, id, position, ctx.requireUserId(), actsAtAnyStore())));
  }

  @Operation(
      summary = "Finish a task",
      description =
          "Done, by the caller, now. A checklist is refused until every required line is ticked: a"
              + " closing list signed off with the safe still open is what the required flag is for.")
  @APIResponse(
      responseCode = "400",
      description = "INVALID_UUID: the path is not an id; VALIDATION_FAILED: a note too long")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN for a caller who is not staff; STORE_ACCESS_DENIED for a task at a store the"
              + " caller is not held to")
  @APIResponse(responseCode = "404", description = "TASK_NOT_FOUND: no such task in this business")
  @APIResponse(
      responseCode = "409",
      description =
          "TASK_LINES_OUTSTANDING, TASK_NOT_OPEN; WORKFORCE_NOT_ASSIGNED: not assigned at the"
              + " task's store")
  @POST
  @Path("/{id}/complete")
  public ApiResponse<StoreTaskDtos.InstanceResponse> complete(
      @PathParam("id") UUID id, StoreTaskDtos.CompleteRequest req) {
    ctx.requireAnyRole(STAFF);
    if (req != null) Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    heldAtTheTasksStore(tenantId, id);
    return ApiResponse.ok(
        StoreTaskMappers.toDto(
            svc.complete(
                tenantId,
                id,
                req == null ? null : req.note(),
                ctx.requireUserId(),
                actsAtAnyStore())));
  }

  @Operation(
      summary = "Skip a task, with the reason",
      description =
          "Explained away rather than done. The reason is required: a skipped closing check with none is what an auditor asks about.")
  @APIResponse(
      responseCode = "400",
      description =
          "TASK_REASON_REQUIRED, VALIDATION_FAILED or BODY_REQUIRED: no reason; INVALID_UUID: the"
              + " path is not an id")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN for a caller who is not staff; STORE_ACCESS_DENIED for a task at a store the"
              + " caller is not held to")
  @APIResponse(responseCode = "404", description = "TASK_NOT_FOUND: no such task in this business")
  @APIResponse(
      responseCode = "409",
      description = "TASK_NOT_OPEN; WORKFORCE_NOT_ASSIGNED: not assigned at the task's store")
  @POST
  @Path("/{id}/skip")
  public ApiResponse<StoreTaskDtos.InstanceResponse> skip(
      @PathParam("id") UUID id, StoreTaskDtos.SkipRequest req) {
    ctx.requireAnyRole(STAFF);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    heldAtTheTasksStore(tenantId, id);
    return ApiResponse.ok(
        StoreTaskMappers.toDto(
            svc.skip(tenantId, id, req.reason(), ctx.requireUserId(), actsAtAnyStore())));
  }

  /**
   * The task must be the business's ({@code 404 TASK_NOT_FOUND}), then at a store the caller is
   * held to ({@code 403 STORE_ACCESS_DENIED}) — a store-held caller at another store is told that,
   * not that they are unassigned there — before anything is written.
   */
  private void heldAtTheTasksStore(UUID tenantId, UUID id) {
    ctx.requireStoreAccess(svc.instance(tenantId, id).storeId());
  }

  /**
   * Whether the caller may work a list at any store of the business: management held to no store —
   * an owner, a business-wide manager. Staff below management are always held to where they are
   * assigned, even should their token name no store.
   */
  private boolean actsAtAnyStore() {
    return ctx.storeIds().isEmpty() && (ctx.hasRole("OWNER") || ctx.hasRole("MANAGER"));
  }
}
