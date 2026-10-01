package com.storeql.inventory.api;

import com.storeql.inventory.dto.Dtos.BatchReserveRequest;
import com.storeql.inventory.dto.Dtos.BatchReserveResponse;
import com.storeql.inventory.dto.Dtos.ReservationResponse;
import com.storeql.inventory.dto.Dtos.ReserveRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reservation API (called by order-svc during checkout): hold, consume, release, and read.
 * Tenant-scoped via context (gateway forwards identity).
 */
@Path("/inventory/reservations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reservations")
public class ReservationResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /** The most lines one bulk reserve takes; each opens a savepoint in one transaction. */
  @Inject
  @ConfigProperty(name = "storeql.inventory.bulk.reserve-max-lines", defaultValue = "100")
  int bulkReserveMaxLines;

  /**
   * Holds stock for an order.
   *
   * <p>Places a time-bounded HELD reservation against available stock (FIFO/expiry-ordered).
   * Supports Idempotency-Key so a retried checkout does not double-reserve.
   *
   * @param idempotencyKey the idempotency key (header parameter)
   * @param req the request body
   * @return reservation held ({@code 201})
   * @throws com.storeql.web.ApiException {@code 422} not enough stock to reserve
   */
  @Operation(
      summary = "Hold stock for an order",
      description =
          "Places a time-bounded HELD reservation against available stock (FIFO/expiry-ordered)."
              + " Supports Idempotency-Key so a retried checkout does not double-reserve.")
  @APIResponse(responseCode = "201", description = "Reservation held")
  @APIResponse(responseCode = "422", description = "Not enough stock to reserve")
  @POST
  public Response reserve(
      @HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      ReserveRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID orderId =
        req.orderId() == null || req.orderId().isBlank() ? null : uuid(req.orderId(), "orderId");
    var r =
        service.reserve(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.qty(),
            orderId,
            req.ttlSeconds(),
            idempotencyKey);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toReservation(r)))
        .build();
  }

  /**
   * Lists reservations.
   *
   * <p>Filterable by store and status.
   *
   * @param store the store (query parameter)
   * @param status the status (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(summary = "List reservations", description = "Filterable by store and status.")
  @APIResponse(responseCode = "200", description = "List reservations")
  @GET
  public ApiResponse<List<ReservationResponse>> listReservations(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listReservations(tenantId, storeId, status, limit).stream()
            .map(Mappers::toReservation)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Gets a reservation by id.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 404} no such reservation
   */
  @Operation(summary = "Get a reservation by id")
  @APIResponse(responseCode = "404", description = "No such reservation")
  @GET
  @Path("/{id}")
  public ApiResponse<ReservationResponse> getReservation(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toReservation(service.getReservation(ctx.requireTenantId(), id)));
  }

  /**
   * Consumes a held reservation.
   *
   * <p>FIFO/expiry-ordered deduction of the reserved qty from the underlying batches once the order
   * is confirmed.
   *
   * @param id the id (path parameter)
   * @throws com.storeql.web.ApiException {@code 422} reservation is not in a HELD state
   */
  @Operation(
      summary = "Consume a held reservation",
      description =
          "FIFO/expiry-ordered deduction of the reserved qty from the underlying batches"
              + " once the order is confirmed.")
  @APIResponse(responseCode = "422", description = "Reservation is not in a HELD state")
  @POST
  @Path("/{id}/consume")
  public ApiResponse<String> consume(@PathParam("id") UUID id) {
    service.consume(ctx.requireTenantId(), id);
    return ApiResponse.ok("consumed");
  }

  /**
   * Releases a held reservation.
   *
   * <p>Returns the held qty to available stock; a no-op if already released/expired.
   *
   * @param id the id (path parameter)
   */
  @Operation(
      summary = "Release a held reservation",
      description = "Returns the held qty to available stock; a no-op if already released/expired.")
  @APIResponse(responseCode = "200", description = "Release a held reservation")
  @POST
  @Path("/{id}/release")
  public ApiResponse<String> release(@PathParam("id") UUID id) {
    boolean released = service.release(ctx.requireTenantId(), id);
    return ApiResponse.ok(released ? "released" : "noop");
  }

  // ── Gap #29: Bulk (batch) reservations ────────────────────────────────────

  /**
   * Reserves stock for multiple lines in one call.
   *
   * <p>Best-effort bulk reserve: each line is attempted independently and the succeeded/failed
   * counts plus per-line results are returned.
   *
   * @param req the request body
   */
  @Operation(
      summary = "Reserve stock for multiple lines in one call",
      description =
          "Best-effort bulk reserve: each line is attempted independently and the"
              + " succeeded/failed counts plus per-line results are returned.")
  @APIResponse(responseCode = "200", description = "Reserve stock for multiple lines in one call")
  @POST
  @Path("/batch")
  public ApiResponse<BatchReserveResponse> bulkReserve(BatchReserveRequest req) {
    Validations.validate(req);
    if (req.reservations() != null && req.reservations().size() > bulkReserveMaxLines) {
      throw ApiException.badRequest(
          "INVENTORY_BULK_TOO_LARGE",
          "a bulk reserve takes at most " + bulkReserveMaxLines + " lines");
    }
    UUID tenantId = ctx.requireTenantId();
    var result = service.bulkReserve(tenantId, req.reservations());
    var responses = result.results().stream().map(Mappers::toReservation).toList();
    return ApiResponse.ok(new BatchReserveResponse(result.succeeded(), result.failed(), responses));
  }

  private static UUID uuid(String s, String field) {
    return com.storeql.web.Parsing.uuid(s, field);
  }
}
