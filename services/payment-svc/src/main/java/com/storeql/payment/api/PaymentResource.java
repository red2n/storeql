package com.storeql.payment.api;

import com.storeql.payment.dto.Dtos.RecordRefundRequest;
import com.storeql.payment.dto.Dtos.RecordTenderRequest;
import com.storeql.payment.mapper.Mappers;
import com.storeql.payment.service.PaymentService;
import com.storeql.web.ApiResponse;
import com.storeql.web.IdempotencyKeys;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Payment tenders and refunds. */
@RequestScoped
@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Payments")
public class PaymentResource {

  @Inject PaymentService svc;
  @Inject TenantContext ctx;

  /**
   * Records a staff-taken tender against an order (POS or back-office).
   *
   * <p>The caller's role is the trust boundary here — unlike {@link #payOnline}, the claim is not
   * re-verified against order-svc.
   *
   * @param idempotencyKey the {@code Idempotency-Key} header, so a retry does not take payment
   *     twice; falls back to the key on the body when absent
   * @param req the order, amount, method and optional reference/notes
   * @return {@code 201} with the captured tender
   * @throws com.storeql.web.ApiException {@code 400} for an unknown method, a store-credit tender
   *     with no customer, or an amount that comes to nothing at the currency's minor unit; {@code
   *     403} without a cashier/manager/owner role; {@code 422} when the store owner has switched
   *     that method off
   */
  @Operation(
      summary = "Record a payment tender",
      description =
          "Staff-recorded tender (POS/back-office) for an order. Requires CASHIER, MANAGER, or"
              + " OWNER. The amount is recorded at the currency's own minor unit (whole yen, a"
              + " dinar's three places), rounded half up as the order's lines are: 3 x 1.10 sent"
              + " as 3.3000000000000003 is a tender of 3.30. A CARD taken on a card machine names"
              + " the machine's approved payment (terminalPaymentId): it is recorded once, on its"
              + " own order, at exactly what the machine took, and that settles the machine for"
              + " its next sale. A CARD naming none records the order's approval at that amount"
              + " that nothing records yet, if there is one. On a sale cancelled or voided, a CARD"
              + " a card machine may have taken for it is refused, named or not: what the machine"
              + " took goes back to the card.")
  @APIResponse(responseCode = "201", description = "Tender captured")
  @APIResponse(
      responseCode = "404",
      description = "TERMINAL_ATTEMPT_NOT_FOUND: no such card machine payment for this business")
  @APIResponse(
      responseCode = "409",
      description =
          "PAYMENT_ORDER_GIVEN_UP (a CARD on a sale cancelled or voided that a card machine may"
              + " have taken for; details orderId=). Naming a card machine's payment:"
              + " TERMINAL_ATTEMPT_OTHER_ORDER, TERMINAL_NOT_APPROVED,"
              + " TERMINAL_ATTEMPT_ALREADY_RECORDED, TERMINAL_ATTEMPT_REFUNDED,"
              + " TERMINAL_AMOUNT_MISMATCH, TERMINAL_WRONG_STORE, TERMINAL_NOT_A_SALE")
  @APIResponse(
      responseCode = "400",
      description =
          "PAYMENT_INVALID_METHOD, PAYMENT_CUSTOMER_REQUIRED, PAYMENT_GIFT_CARD_VIA_REDEEM,"
              + " PAYMENT_AMOUNT_INVALID (the amount comes to nothing at the currency's minor"
              + " unit; any finer figure is taken at that unit, rounded half up, as a till sums in"
              + " binary floating point), CURRENCY_INVALID, or VALIDATION_FAILED (no amount, not"
              + " positive, more than ten whole digits or twenty decimal places)")
  @APIResponse(
      responseCode = "403",
      description =
          "Caller lacks a cashier/manager/owner role; STORE_ACCESS_DENIED for a store (or a card"
              + " machine's store) the caller is not held to")
  @APIResponse(responseCode = "422", description = "Payment method disabled for this store")
  @POST
  public Response record(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      RecordTenderRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    Validations.validate(req);
    if (req.orderId() == null || req.orderId().isBlank()) {
      throw com.storeql.web.ApiException.badRequest("VALIDATION_FAILED", "orderId: is required");
    }
    var tender = svc.recordTender(req, ctx, effectiveKey(idempotencyKey, req.idempotencyKey()));
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(tender))).build();
  }

  /**
   * Online customer payment for the guest storefront. No staff role required — reachable via the
   * gateway's storefront whitelist (tenant from {@code X-Storefront-Tenant}) or by an authenticated
   * customer. Cashless only; cash tenders are POS-staff territory via {@link #record}. The claim is
   * verified against order-svc (exists, is an ONLINE order, belongs to the caller when
   * authenticated, amount matches the order total) before it's captured and {@code PaymentCaptured}
   * is emitted so order-svc confirms the order. (Hardening TODO: integrate a real payment provider
   * — this still self-attests that money actually moved.)
   *
   * @param idempotencyKey the {@code Idempotency-Key} header, so a retry does not take payment
   *     twice; falls back to the key on the body when absent
   * @param req the order, amount and cashless method
   * @return {@code 201} with the captured tender
   * @throws com.storeql.web.ApiException {@code 400} when cash is tendered online or the amount
   *     does not match the order total; {@code 404} when the order is not found, is not {@code
   *     ONLINE}, or is not the caller's; {@code 409} when the order is not awaiting payment
   */
  @Operation(
      summary = "Capture an online customer payment",
      description =
          "Cashless-only payment for a guest/customer storefront order. Verifies the order exists,"
              + " is ONLINE, is PENDING, belongs to the caller when authenticated, and the amount"
              + " matches the order total before capturing.")
  @APIResponse(
      responseCode = "201",
      description =
          "Tender captured; for a split checkout (groupId), a GroupPaymentResponse with a tender"
              + " per part")
  @APIResponse(
      responseCode = "400",
      description =
          "Cash tendered online, amount mismatch, PAYMENT_GROUP_AMOUNT_MISMATCH for a checkout,"
              + " or VALIDATION_FAILED (no amount, not positive, more than ten whole digits or"
              + " twenty decimal places)")
  @APIResponse(
      responseCode = "404",
      description = "Order or checkout not found, not ONLINE, or not the caller's")
  @APIResponse(
      responseCode = "409",
      description =
          "Order is not awaiting payment, or PAYMENT_ORDER_IN_GROUP: a part of a split checkout"
              + " is paid with its checkout")
  @POST
  @Path("/online")
  public Response payOnline(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      RecordTenderRequest req) {
    Validations.validate(req);
    if (req.method() != null && "CASH".equalsIgnoreCase(req.method())) {
      throw com.storeql.web.ApiException.badRequest(
          "PAYMENT_ONLINE_CASHLESS",
          "Online payments must be cashless (CARD, UPI or WALLET); cash is settled in person"
              + " at pickup/delivery");
    }
    boolean order = req.orderId() != null && !req.orderId().isBlank();
    boolean checkout = req.groupId() != null && !req.groupId().isBlank();
    if (order == checkout) {
      throw com.storeql.web.ApiException.badRequest(
          "VALIDATION_FAILED",
          "name the order (orderId) or the split checkout (groupId), not both");
    }
    String key = effectiveKey(idempotencyKey, req.idempotencyKey());
    if (checkout) {
      var paid = svc.recordOnlineGroupPayment(req, ctx, key);
      return Response.status(201)
          .entity(
              ApiResponse.ok(
                  new com.storeql.payment.dto.Dtos.GroupPaymentResponse(
                      paid.groupId(),
                      paid.total(),
                      paid.tenders().stream().map(Mappers::toDto).toList())))
          .build();
    }
    var tender = svc.recordOnlinePayment(req, ctx, key);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(tender))).build();
  }

  /**
   * Reads one payment tender.
   *
   * @param id the tender to read
   * @return the tender
   * @throws com.storeql.web.ApiException {@code 404} when no such tender exists in the tenant or
   *     the caller may not read it — a denial is a 404 so ids cannot be probed for existence
   */
  @Operation(
      summary = "Get a payment tender by id",
      description =
          "Staff may read any tender in their tenant; a customer may only read a tender on their"
              + " own order.")
  @APIResponse(responseCode = "200", description = "Tender found")
  @APIResponse(responseCode = "404", description = "Tender not found, or not owned by the caller")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    var tender = svc.getTender(ctx.requireTenantId(), id, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(tender))).build();
  }

  /**
   * Lists all payment tenders recorded for a given order.
   *
   * <p>A split-tender sale returns one row per tender.
   *
   * @param orderId the order whose tenders to list
   * @return the captured tenders, empty when nothing has been paid
   * @throws com.storeql.web.ApiException {@code 404} when the caller may not read this order
   */
  @Operation(
      summary = "List payment tenders for an order",
      description = "All tenders recorded against the given order.")
  @APIResponse(responseCode = "200", description = "Tenders for the order")
  @APIResponse(responseCode = "404", description = "Order not owned by the caller")
  @GET
  @Path("/by-order/{orderId}")
  public Response listByOrder(@PathParam("orderId") UUID orderId) {
    var tenders = svc.listTendersByOrder(ctx.requireTenantId(), orderId, ctx);
    return Response.ok(ApiResponse.ok(tenders.stream().map(Mappers::toDto).toList())).build();
  }

  /**
   * Records a refund against a previously captured tender. MANAGER or above only, holding {@code
   * sales.refund}, and able to act at the store the tender was taken at.
   *
   * @param idempotencyKey the {@code Idempotency-Key} header, so a retry does not refund twice;
   *     falls back to the key on the body when absent
   * @param orderId the order being refunded
   * @param req the payment being refunded against, the amount, method and reason
   * @return {@code 201} with the recorded refund
   * @throws com.storeql.web.ApiException {@code 400} for an unknown method; {@code 403} without a
   *     manager/owner role or the permission; {@code 404} for a tender the business does not have,
   *     then {@code 403 STORE_ACCESS_DENIED} for one taken at a store the caller is not held to; a
   *     conflict when the refund would exceed what was captured
   */
  @Operation(
      summary = "Record a refund",
      description =
          "Refund against a previously captured tender for the order. Existence, the caller's"
              + " store, order-match, and the cumulative refund cap are enforced with the payment"
              + " row locked. Requires MANAGER or OWNER holding sales.refund. A refund is the"
              + " store's where its tender was taken (it lowers that store's expected cash and"
              + " reports), so a manager held to stores refunds only a tender taken at one of"
              + " them; one held to none, any tender of the business.")
  @APIResponse(responseCode = "201", description = "Refund recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "PAYMENT_INVALID_METHOD, PAYMENT_AMOUNT_INVALID (finer than the business's currency's"
              + " minor unit), or VALIDATION_FAILED")
  @APIResponse(
      responseCode = "403",
      description =
          "Caller lacks a manager/owner role; PERMISSION_DENIED without sales.refund;"
              + " STORE_ACCESS_DENIED: the tender was taken at a store the caller is not held to"
              + " (or at no store, which only a caller held to none refunds) — nothing is read"
              + " back or written")
  @APIResponse(
      responseCode = "404",
      description = "PAYMENT_NOT_FOUND: no such tender for this business (another's included)")
  @APIResponse(
      responseCode = "409",
      description =
          "REFUND_EXCEEDS_PAYMENT (what is refunded and what is owed back to a card count);"
              + " PAYMENT_REFUND_VIA_TERMINAL: a CARD refund of a tender a card machine took goes"
              + " back on that machine (POST /payments/terminal/{attemptId}/refunds, named in the"
              + " details), never in the books alone; PAYMENT_ORDER_MISMATCH;"
              + " IDEMPOTENCY_KEY_REUSED: the key already made a refund of another tender")
  @POST
  @Path("/by-order/{orderId}/refunds")
  public Response recordRefund(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      @PathParam("orderId") UUID orderId,
      RecordRefundRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(com.storeql.web.Permissions.SALES_REFUND);
    Validations.validate(req);
    var refund =
        svc.recordRefund(ctx, orderId, req, effectiveKey(idempotencyKey, req.idempotencyKey()));
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(refund))).build();
  }

  /** The standard Idempotency-Key header is authoritative; the body field is a legacy fallback. */
  private static String effectiveKey(String header, String bodyField) {
    return IdempotencyKeys.effective(header, bodyField);
  }

  /**
   * Lists all refunds recorded for a given order.
   *
   * @param orderId the order whose refunds to list
   * @return the refunds, empty when nothing has been refunded
   * @throws com.storeql.web.ApiException {@code 404} when the caller may not read this order
   */
  @Operation(
      summary = "List refunds for an order",
      description = "All refunds recorded against the given order.")
  @APIResponse(responseCode = "200", description = "Refunds for the order")
  @APIResponse(responseCode = "404", description = "Order not owned by the caller")
  @GET
  @Path("/by-order/{orderId}/refunds")
  public Response listRefunds(@PathParam("orderId") UUID orderId) {
    var refunds = svc.listRefundsByOrder(ctx.requireTenantId(), orderId, ctx);
    return Response.ok(ApiResponse.ok(refunds.stream().map(Mappers::toDto).toList())).build();
  }
}
