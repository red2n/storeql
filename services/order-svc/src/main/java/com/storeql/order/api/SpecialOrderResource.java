package com.storeql.order.api;

import com.storeql.order.dto.Dtos.CreateSpecialOrderRequest;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.HttpHeaders;
import com.storeql.web.IdempotencyKeys;
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
 * Gap #42 — Special orders: customer orders placed at a store for future delivery. Distinct from
 * regular POS orders — no immediate inventory deduction, tracks customer contact and delivery date.
 */
@RequestScoped
@Path("/admin/special-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Special Orders")
public class SpecialOrderResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Places a customer order for goods the store does not stock.
   *
   * <p>No inventory is deducted or reserved: the goods do not exist in stock yet, which is the
   * whole point of a special order.
   *
   * @param idempotencyKey the {@code Idempotency-Key} header (a UUIDv7), or {@code null} to use the
   *     body's
   * @param req the store, customer, items and optional currency
   * @return {@code 201} with the special order and its lines, which on a retry under the same key
   *     are the first order's
   * @throws com.storeql.web.ApiException {@code 400} when no items are supplied or the key is not a
   *     UUIDv7; {@code 409 IDEMPOTENCY_KEY_REUSED} when the key made a different special order
   */
  @Operation(
      summary = "Create a special order",
      description =
          "Places a customer order for future delivery at a store, without immediate inventory"
              + " deduction. A retry with the same Idempotency-Key and the same request answers the"
              + " first order, with the same status and body, and writes nothing.")
  @APIResponse(
      responseCode = "201",
      description = "Special order created, or the first one made under this Idempotency-Key")
  @APIResponse(
      responseCode = "400",
      description =
          "No items in the special order, or an Idempotency-Key that is not a UUIDv7;"
              + " VALIDATION_FAILED for a unitPrice with more decimals than the business's"
              + " currency has")
  @APIResponse(
      responseCode = "409",
      description =
          "IDEMPOTENCY_KEY_REUSED: this Idempotency-Key made a different special order, which"
              + " stands")
  @POST
  public Response create(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      CreateSpecialOrderRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    String key = IdempotencyKeys.effective(idempotencyKey, req.idempotencyKey());
    var so = svc.createSpecialOrder(tenantId, req, key, ctx);
    var items = svc.getSpecialOrderItems(tenantId, so.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(so, items))).build();
  }

  /**
   * Cursor-paginated list of the tenant's special orders, each with its lines.
   *
   * @param storeId restrict to one store, or {@code null}
   * @param customerId restrict to one customer, or {@code null}
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of special orders, with the next cursor in {@code meta}
   */
  @Operation(
      summary = "List special orders",
      description =
          "Special orders for the tenant, optionally filtered by store or customer."
              + " Cursor-paginated.")
  @APIResponse(responseCode = "200", description = "Page of special orders")
  @GET
  public Response list(
      @QueryParam("storeId") String storeId,
      @QueryParam("customerId") String customerId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    int clamped = Cursor.clampLimit(limit);
    var page = svc.listSpecialOrders(tenantId, storeId, customerId, after, clamped);
    var list =
        page.orders().stream()
            .map(so -> Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, so.id())))
            .toList();
    return Response.ok(
            ApiResponse.ok(list, new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  /**
   * Reads one special order with its lines.
   *
   * @param id the special order to read
   * @return the special order and its lines
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(
      summary = "Get a special order by id",
      description = "Special order detail with items.")
  @APIResponse(responseCode = "200", description = "Special order found")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.getSpecialOrder(tenantId, id);
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  /**
   * Confirms a PENDING special order once the goods are on their way.
   *
   * @param id the special order to confirm
   * @return the confirmed special order with its lines
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist; {@code 403
   *     STORE_ACCESS_DENIED} for staff not assigned to its store; a conflict when its status does
   *     not allow confirmation
   */
  @Operation(
      summary = "Confirm a special order",
      description =
          "Transitions a PENDING special order to CONFIRMED, by staff who may act at its store.")
  @APIResponse(responseCode = "200", description = "Special order confirmed")
  @APIResponse(responseCode = "403", description = "The caller is not assigned to its store")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @POST
  @Path("/{id}/confirm")
  public Response confirm(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.confirmSpecialOrder(tenantId, id, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  /**
   * Marks a CONFIRMED special order handed over to the customer.
   *
   * @param id the special order to fulfil
   * @return the fulfilled special order with its lines
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist; {@code 403
   *     STORE_ACCESS_DENIED} for staff not assigned to its store; a conflict when its status does
   *     not allow fulfilment
   */
  @Operation(
      summary = "Fulfil a special order",
      description =
          "Transitions a CONFIRMED special order to FULFILLED, by staff who may act at its store.")
  @APIResponse(responseCode = "200", description = "Special order fulfilled")
  @APIResponse(responseCode = "403", description = "The caller is not assigned to its store")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @POST
  @Path("/{id}/fulfil")
  public Response fulfil(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.fulfilSpecialOrder(tenantId, id, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  /**
   * Cancels a special order that has not yet been handed over.
   *
   * @param id the special order to cancel
   * @return the cancelled special order with its lines
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist; {@code 403
   *     STORE_ACCESS_DENIED} for staff not assigned to its store; {@code 409} when it has already
   *     been fulfilled
   */
  @Operation(
      summary = "Cancel a special order",
      description =
          "Cancels a special order, by staff who may act at its store. A fulfilled special order"
              + " cannot be cancelled.")
  @APIResponse(responseCode = "200", description = "Special order cancelled")
  @APIResponse(responseCode = "403", description = "The caller is not assigned to its store")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @APIResponse(responseCode = "409", description = "Special order is already fulfilled")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.cancelSpecialOrder(tenantId, id, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }
}
