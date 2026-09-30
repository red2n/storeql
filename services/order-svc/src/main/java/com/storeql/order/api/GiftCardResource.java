package com.storeql.order.api;

import com.storeql.order.dto.Dtos.IssueGiftCardRequest;
import com.storeql.order.dto.Dtos.RedeemGiftCardRequest;
import com.storeql.order.dto.Dtos.RedeemGiftCardResponse;
import com.storeql.order.dto.Dtos.ReloadGiftCardRequest;
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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Gift card management — Gap #14 POS feature. */
@Path("/gift-cards")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Gift Cards")
public class GiftCardResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Issues a gift card with a server-generated code and an opening balance.
   *
   * <p>The code is minted server-side, never supplied by the caller: it is bearer stored value, so
   * a guessable code would be spendable by whoever guessed it.
   *
   * @param req the store, amount, optional currency and optional expiry
   * @return {@code 201} with the issued card, including its code
   */
  @Operation(
      summary = "Issue a gift card",
      description = "Issues a new gift card for a store with an initial stored-value balance.")
  @APIResponse(responseCode = "201", description = "Gift card issued")
  @POST
  public Response issue(IssueGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.issueGiftCard(req, ctx);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  /**
   * Looks a gift card up by its code, to check the balance at the till.
   *
   * @param code the card's code
   * @return the card with its current balance
   * @throws com.storeql.web.ApiException {@code 404} when no such card exists in the tenant
   */
  @Operation(summary = "Get a gift card by code", description = "Looks up a gift card by its code.")
  @APIResponse(responseCode = "200", description = "Gift card found")
  @APIResponse(responseCode = "404", description = "Gift card not found")
  @GET
  @Path("/{code}")
  public Response get(@PathParam("code") String code) {
    var gc = svc.getGiftCard(ctx.tenantId(), code);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  /**
   * Adds stored value to an existing gift card.
   *
   * @param code the card's code
   * @param req the amount to add and a reference for the transaction log
   * @return the card with its new balance
   * @throws com.storeql.web.ApiException {@code 404} when no such card exists; a conflict when the
   *     card is not active
   */
  @Operation(
      summary = "Reload a gift card",
      description = "Adds stored value to an existing gift card's balance.")
  @APIResponse(responseCode = "200", description = "Gift card reloaded")
  @APIResponse(responseCode = "404", description = "Gift card not found")
  @POST
  @Path("/{code}/reload")
  public Response reload(@PathParam("code") String code, ReloadGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.reloadGiftCard(ctx.tenantId(), code, req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  /**
   * Charges a gift card for an order, before the payment is recorded.
   *
   * <p>The balance check happens in the same transaction as the write, so two tills cannot together
   * overspend one card. payment-svc records the GIFT_CARD tender from the {@code GiftCardRedeemed}
   * event this publishes, so a payment exists only for value the card really gave up.
   *
   * @param code the card's code
   * @param key the caller's {@code Idempotency-Key}: a retry answers with the first redemption
   * @param req the amount, the order being paid towards, and a reference
   * @return the redemption and the card's balance after it
   * @throws com.storeql.web.ApiException {@code 404} when no such card or order exists in the
   *     business; a conflict when the card is not active, has expired, holds another currency or
   *     has too little
   */
  @Operation(
      summary = "Redeem a gift card",
      description =
          "Charges stored value from a gift card against an order, as tender for a purchase."
              + " Staff only. Requires an Idempotency-Key: a retry under the same key answers with"
              + " the first redemption and debits nothing. One card pays towards one order once,"
              + " whatever the key: the same amount again answers with the first redemption, a"
              + " different amount is GIFT_CARD_ALREADY_REDEEMED_FOR_ORDER.")
  @APIResponse(responseCode = "200", description = "Gift card charged (or the first, on a retry)")
  @APIResponse(responseCode = "400", description = "IDEMPOTENCY_KEY_REQUIRED or a bad body")
  @APIResponse(responseCode = "404", description = "Gift card or order not found")
  @APIResponse(
      responseCode = "409",
      description =
          "GIFT_CARD_NOT_ACTIVE, GIFT_CARD_EXPIRED, GIFT_CARD_CURRENCY_MISMATCH,"
              + " GIFT_CARD_INSUFFICIENT_BALANCE, GIFT_CARD_ALREADY_REDEEMED_FOR_ORDER")
  @POST
  @Path("/{code}/redeem")
  public Response redeem(
      @PathParam("code") String code,
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String key,
      RedeemGiftCardRequest req) {
    ctx.requireAnyRole("CASHIER", "STOREKEEPER", "MANAGER", "OWNER");
    if (key == null || key.isBlank()) {
      throw ApiException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED", "the Idempotency-Key header is required");
    }
    Validations.validate(req);
    var tx =
        svc.redeemGiftCard(ctx.tenantId(), code, req, IdempotencyKeys.require(key.trim()), ctx);
    return Response.ok(
            ApiResponse.ok(
                new RedeemGiftCardResponse(
                    tx.id().toString(),
                    tx.giftCardId().toString(),
                    tx.amount(),
                    tx.balanceAfter())))
        .build();
  }

  /**
   * The append-only issue/reload/redeem history for one gift card.
   *
   * @param code the card's code
   * @return every transaction against the card
   * @throws com.storeql.web.ApiException {@code 404} when no such card exists in the tenant
   */
  @Operation(
      summary = "List a gift card's transactions",
      description = "Append-only issue/reload/redeem transaction history for the gift card.")
  @APIResponse(responseCode = "200", description = "List of gift card transactions")
  @APIResponse(responseCode = "404", description = "Gift card not found")
  @GET
  @Path("/{code}/transactions")
  public Response transactions(@PathParam("code") String code) {
    var txns = svc.getGiftCardTransactions(ctx.tenantId(), code);
    return Response.ok(ApiResponse.ok(txns.stream().map(Mappers::toDto).toList())).build();
  }
}
