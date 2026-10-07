package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.CreateGoodsReceiptRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.PurchaseService;
import com.storeql.web.ApiException;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@RequestScoped
/**
 * Thin JAX-RS resource for goods receipts — validate, delegate to {@link PurchaseService}, wrap in
 * envelope. No logic here.
 *
 * <p>Booking a receipt is what moves stock into inventory-svc, via the {@code GoodsReceived} event.
 */
@Path("/goods-receipts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Goods Receipts")
public class GoodsReceiptResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  /**
   * Books a delivery against a purchase order and publishes {@code GoodsReceived}.
   *
   * @param idempotencyKey the {@code Idempotency-Key} header, so a retried delivery is not booked
   *     twice
   * @param req the purchase order and the quantities received per variant
   * @return {@code 201} with the recorded receipt
   * @throws com.storeql.web.ApiException {@code 400} when the order is not receivable or the
   *     receipt has no lines; {@code 404} when the order does not exist
   */
  @Operation(
      summary = "Record a goods receipt",
      description =
          "Records goods received against a SUBMITTED purchase order and publishes GoodsReceived."
              + " Supports Idempotency-Key to make retried receipts safe.")
  @APIResponse(responseCode = "201", description = "Goods receipt recorded")
  @APIResponse(
      responseCode = "400",
      description = "Purchase order is not SUBMITTED, or the receipt has no lines")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(
      responseCode = "403",
      description =
          "STORE_ACCESS_DENIED: the order, or the store received into, is not the caller's")
  @POST
  public Response receive(
      @HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      CreateGoodsReceiptRequest req) {
    Validations.validate(req);
    var gr = svc.receiveGoods(req, ctx, idempotencyKey);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(gr, List.of()))).build();
  }

  /**
   * Lists the deliveries booked against one purchase order.
   *
   * @param poId the purchase order whose receipts to list; required
   * @return the goods receipts, headers only
   * @throws com.storeql.web.ApiException {@code 400} when {@code poId} is missing; {@code 404} when
   *     the order does not exist
   */
  @Operation(
      summary = "List goods receipts for a purchase order",
      description = "Requires ?poId=<purchase order id>.")
  @APIResponse(responseCode = "400", description = "poId query param required")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: the caller is held to stores that are not the order's")
  @GET
  public Response listByPo(@QueryParam("poId") UUID poId) {
    if (poId == null)
      throw ApiException.badRequest("PURCHASE_MISSING_PO_ID", "poId query param required");
    return Response.ok(
            ApiResponse.ok(
                svc.listGoodsReceipts(ctx, poId).stream()
                    .map(gr -> Mappers.toDto(gr, List.of()))
                    .toList()))
        .build();
  }
}
