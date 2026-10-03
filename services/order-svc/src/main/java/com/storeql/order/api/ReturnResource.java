package com.storeql.order.api;

import com.storeql.order.dto.Dtos.NoReceiptReturnRequest;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.IdempotencyKeys;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Returns that belong to no sale: goods brought back with no receipt. */
@ApplicationScoped
@Path("/returns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Returns")
public class ReturnResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Takes goods back with no receipt, for store credit or a gift card.
   *
   * @param key the caller's {@code Idempotency-Key}: a retry answers with the first return
   * @param req the store, the reason, the method, the customer and their contact, the goods
   * @return {@code 201} with the recorded return
   */
  @Operation(
      summary = "Return goods with no receipt",
      description =
          "Manager only (sales.refund), only where the business allows it (the return policy),"
              + " refunded to STORE_CREDIT (the customer must be named) or a GIFT_CARD (new, or"
              + " topped up), never cash. Each line is priced at the item's current till price at"
              + " the store, VAT included, and the total may not pass the policy's no-receipt"
              + " ceiling. customerContact is kept and never logged. There is no sale, so"
              + " order-svc announces the refund itself (NoReceiptReturnRecorded). Requires an"
              + " Idempotency-Key: a retry answers with the first return.")
  @APIResponse(responseCode = "201", description = "Return recorded (or the first, on a retry)")
  @APIResponse(
      responseCode = "400",
      description =
          "IDEMPOTENCY_KEY_REQUIRED, ORDER_NO_RECEIPT_METHOD_INVALID, ORDER_RETURN_NO_ITEMS,"
              + " ORDER_RETURN_CONDITION_REQUIRED, ORDER_RETURN_CONDITION_INVALID")
  @APIResponse(
      responseCode = "403",
      description = "ORDER_RETURN_NEEDS_MANAGER, with NO_RECEIPT in details")
  @APIResponse(
      responseCode = "409",
      description =
          "ORDER_NO_RECEIPT_RETURNS_OFF, ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER, or the card is"
              + " not usable")
  @APIResponse(responseCode = "422", description = "ORDER_NO_RECEIPT_OVER_CEILING")
  @APIResponse(responseCode = "503", description = "pricing-svc cannot be reached")
  @POST
  @Path("/no-receipt")
  public Response noReceipt(
      @HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String key,
      NoReceiptReturnRequest req) {
    ctx.requireAnyRole("CASHIER", "STOREKEEPER", "MANAGER", "OWNER");
    if (key == null || key.isBlank()) {
      throw ApiException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED", "the Idempotency-Key header is required");
    }
    Validations.validate(req);
    var ret =
        svc.noReceiptReturn(ctx.requireTenantId(), req, IdempotencyKeys.require(key.trim()), ctx);
    var items = svc.getReturnItems(ctx.tenantId(), ret.id());
    var card = svc.giftCardOf(ret).orElse(null);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(ret, items, card))).build();
  }
}
