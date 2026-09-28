package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.dto.WaveDtos.BuildWaveRequest;
import com.storeql.inventory.dto.WaveDtos.RecordPicksRequest;
import com.storeql.inventory.mapper.WaveMappers;
import com.storeql.inventory.service.WaveService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Wave picking: the orders waiting at a store, the waves built from them, the picks and the
 * completion that fulfils the orders. Warehouse work: {@code stock.transfer}.
 */
@RequestScoped
@Path("/admin/inventory/waves")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Wave Picking")
public class WaveResource {

  @Inject WaveService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The orders waiting to be picked",
      description =
          "Confirmed online orders for pickup or delivery at the store, earliest first, with what each still needs.")
  @APIResponse(responseCode = "200", description = "The orders")
  @GET
  @Path("/awaiting")
  public Response awaiting(@QueryParam("storeId") String storeId) {
    return Response.ok(
            ApiResponse.ok(
                svc
                    .awaiting(ctx, storeId == null || storeId.isBlank() ? null : Ids.parse(storeId))
                    .stream()
                    .map(WaveMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Build a wave",
      description =
          "Gathers the orders waiting at the store (all, or the ones named) into one pick list: one"
              + " line per batch the picking rule directs to, in walk order through the zones, each"
              + " naming the orders it serves. Idempotent on the Idempotency-Key.")
  @APIResponse(responseCode = "201", description = "Built")
  @APIResponse(
      responseCode = "409",
      description = "INVENTORY_WAVE_NOTHING_TO_PICK, INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE")
  @POST
  public Response build(
      BuildWaveRequest req, @HeaderParam("Idempotency-Key") String idempotencyKey) {
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                WaveMappers.toDto(
                    svc.build(
                        ctx,
                        Ids.parse(req.storeId()),
                        req.orderIds(),
                        idempotencyKey == null || idempotencyKey.isBlank()
                            ? null
                            : idempotencyKey.toLowerCase(java.util.Locale.ROOT)))))
        .build();
  }

  @Operation(
      summary = "List waves",
      description = "Newest first, at one store or all, at one status or all.")
  @APIResponse(responseCode = "200", description = "The waves")
  @GET
  public Response list(@QueryParam("storeId") String storeId, @QueryParam("status") String status) {
    return Response.ok(
            ApiResponse.ok(
                svc
                    .list(
                        ctx,
                        storeId == null || storeId.isBlank() ? null : Ids.parse(storeId),
                        status)
                    .stream()
                    .map(WaveMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(summary = "Read a wave", description = "With its lines and the orders each serves.")
  @APIResponse(responseCode = "200", description = "The wave")
  @APIResponse(responseCode = "404", description = "INVENTORY_WAVE_NOT_FOUND")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    return Response.ok(ApiResponse.ok(WaveMappers.toDto(svc.get(ctx, Ids.parse(id))))).build();
  }

  @Operation(
      summary = "Record what was picked",
      description = "Per line, at most what was directed; a line left out is not yet picked.")
  @APIResponse(responseCode = "200", description = "Recorded")
  @APIResponse(
      responseCode = "400",
      description = "INVENTORY_WAVE_LINE_UNKNOWN, INVENTORY_WAVE_PICK_EXCEEDS_LINE")
  @APIResponse(responseCode = "409", description = "INVENTORY_WAVE_NOT_OPEN")
  @POST
  @Path("/{id}/picks")
  public Response picks(@PathParam("id") String id, RecordPicksRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(WaveMappers.toDto(svc.picks(ctx, Ids.parse(id), req))))
        .build();
  }

  @Operation(
      summary = "Complete the wave",
      description =
          "Deducts exactly what was picked from the batches it was picked from, consumes the orders'"
              + " holds by as much, and tells order-svc to fulfil the orders for the picked quantities;"
              + " what was picked short waits for the next wave.")
  @APIResponse(responseCode = "200", description = "Completed")
  @APIResponse(responseCode = "409", description = "INVENTORY_WAVE_NOT_OPEN")
  @APIResponse(responseCode = "422", description = "INVENTORY_WAVE_STOCK_GONE")
  @POST
  @Path("/{id}/complete")
  public Response complete(@PathParam("id") String id) {
    return Response.ok(ApiResponse.ok(WaveMappers.toDto(svc.complete(ctx, Ids.parse(id))))).build();
  }

  @Operation(
      summary = "Cancel the wave",
      description = "Nothing moved; the orders wait for the next one.")
  @APIResponse(responseCode = "200", description = "Cancelled")
  @APIResponse(responseCode = "409", description = "INVENTORY_WAVE_NOT_OPEN")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id) {
    UUID waveId = Ids.parse(id);
    return Response.ok(ApiResponse.ok(WaveMappers.toDto(svc.cancel(ctx, waveId)))).build();
  }
}
