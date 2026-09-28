package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.AddPurchaseOrderLineRequest;
import com.storeql.purchase.dto.Dtos.CancelPurchaseOrderRequest;
import com.storeql.purchase.dto.Dtos.CreatePurchaseOrderRequest;
import com.storeql.purchase.dto.Dtos.DecidePurchaseOrderRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.PurchaseService;
import com.storeql.web.ApiResponse;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Thin JAX-RS resource for purchase orders — validate, delegate to {@link PurchaseService}, wrap in
 * envelope. No logic here.
 *
 * <p>A purchase order moves DRAFT → SUBMITTED (or PENDING_APPROVAL when it exceeds the submitter's
 * spend authority) → RECEIVED, with CANCELLED and CLOSED as the two ways it can end early. Spend
 * authority is measured on net value, since VAT is recoverable and therefore not spend.
 */
@RequestScoped
@Path("/purchase-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Purchase Orders")
public class PurchaseOrderResource {

  @Inject PurchaseService svc;
  @Inject com.storeql.purchase.service.DropshipService dropship;
  @Inject TenantContext ctx;

  /**
   * Creates a DRAFT purchase order for a supplier and store.
   *
   * <p>Lines are added separately, and only while the order is still DRAFT.
   *
   * @param req the supplier, store, currency and any header-level detail
   * @return {@code 201} with the created order
   * @throws com.storeql.web.ApiException {@code 404} when the supplier does not exist
   */
  @Operation(
      summary = "Create a purchase order",
      description = "Creates a DRAFT purchase order for the given supplier and store.")
  @APIResponse(responseCode = "201", description = "Purchase order created")
  @APIResponse(responseCode = "404", description = "Supplier not found")
  @POST
  public Response create(CreatePurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPurchaseOrder(req, ctx))))
        .build();
  }

  /**
   * Lists purchase orders for the caller's tenant.
   *
   * @param limit page size; clamped to the platform default and maximum when absent or out of range
   * @return the purchase orders
   */
  @Operation(
      summary = "List purchase orders",
      description = "Lists purchase orders for the caller's tenant.")
  @APIResponse(responseCode = "200", description = "The purchase orders")
  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.storeql.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrders(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  /**
   * Reads a single purchase order.
   *
   * @param id the purchase order to read
   * @return the purchase order
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(summary = "Get a purchase order", description = "Returns a single purchase order.")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getPurchaseOrder(ctx, id)))).build();
  }

  /**
   * Submits a DRAFT order, routing it for approval when it exceeds the submitter's authority.
   *
   * <p>Either outcome is recorded in the order's append-only approval trail. Authority is
   * configured per currency <em>and</em> per role, because StoreQL does no FX and a ceiling in one
   * currency cannot be compared against an order in another. With no authority configured at all,
   * approval is off and every submission goes straight to SUBMITTED.
   *
   * @param id the purchase order to submit
   * @return the order, now SUBMITTED or PENDING_APPROVAL
   * @throws com.storeql.web.ApiException {@code 400} when the order is not DRAFT; {@code 404} when
   *     it does not exist
   */
  @Operation(
      summary = "Submit a purchase order to the supplier",
      description =
          "Transitions a DRAFT purchase order to SUBMITTED, or to PENDING_APPROVAL when its net"
              + " value is above the submitter's own spend authority. Either way the submission is"
              + " recorded in the order's append-only approval trail. Spend authority is configured"
              + " per currency AND per role; an order in a currency with no ceiling of its own is"
              + " measured in the business's home currency at the exchange rate it keeps (03.x), the"
              + " rate and translated net recorded on the order — and without a rate it fails"
              + " closed. When no authority is configured at all, approval is off and this behaves"
              + " as it did before the feature existed.")
  @APIResponse(responseCode = "200", description = "Submitted, or routed for approval")
  @APIResponse(responseCode = "400", description = "Only DRAFT orders can be submitted")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @POST
  @Path("/{id}/submit")
  public Response submit(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.submitPurchaseOrder(ctx, id)))).build();
  }

  @Operation(
      summary = "Mark a dropship order delivered to the customer",
      description =
          "The move a goods receipt makes for stock that arrives here: a SUBMITTED dropship order"
              + " becomes RECEIVED without any stock, and the cost of goods the business never held"
              + " is posted against what the supplier will invoice. Anything else is received.")
  @APIResponse(responseCode = "200", description = "Delivered")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "PURCHASE_PO_NOT_DELIVERABLE")
  @POST
  @Path("/{id}/dropship-delivered")
  public Response dropshipDelivered(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(dropship.deliver(ctx, id)))).build();
  }

  /**
   * Approves a PENDING_APPROVAL order, moving it to SUBMITTED.
   *
   * <p>The approver is held to the same ceiling that routed the order here — an approval by someone
   * who could not have submitted it themselves would defeat the control. The total is re-read from
   * the order rather than taken from the request, because a rejected order can be edited before it
   * comes back.
   *
   * @param id the purchase order to approve
   * @param req the approver's optional note for the trail
   * @return the approved, now SUBMITTED order
   * @throws com.storeql.web.ApiException {@code 403} when the total is above the approver's own
   *     authority; {@code 404} when the order does not exist; {@code 409} when it is not awaiting
   *     approval
   */
  @Operation(
      summary = "Approve a purchase order awaiting approval",
      description =
          "Moves a PENDING_APPROVAL order to SUBMITTED. The approver's own spend authority is"
              + " checked against the same figure by the same rule that routed it here — an"
              + " approval by someone who could not have submitted it themselves would defeat the"
              + " control entirely. The total is re-read from the order rather than taken from the"
              + " request, because a rejected order can be edited before it comes back.")
  @APIResponse(responseCode = "200", description = "Approved and submitted")
  @APIResponse(responseCode = "403", description = "Above the approver's own authority")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "Order is not awaiting approval")
  @POST
  @Path("/{id}/approve")
  public Response approve(@PathParam("id") UUID id, DecidePurchaseOrderRequest req) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.approvePurchaseOrder(ctx, id, req))))
        .build();
  }

  /**
   * Rejects a PENDING_APPROVAL order, returning it to DRAFT for correction.
   *
   * <p>Rejecting needs no spend authority: refusing to commit money is not itself a commitment, and
   * requiring it would strand an order too large for anyone configured.
   *
   * @param id the purchase order to reject
   * @param req the rejection reason, which is required
   * @return the rejected order, back in DRAFT
   * @throws com.storeql.web.ApiException {@code 400} when no reason is given; {@code 404} when the
   *     order does not exist; {@code 409} when it is not awaiting approval
   */
  @Operation(
      summary = "Reject a purchase order awaiting approval",
      description =
          "Returns a PENDING_APPROVAL order to DRAFT so it can be corrected and resubmitted,"
              + " recording the required reason in the approval trail. Rejecting needs no spend"
              + " authority: refusing to commit money is not itself a commitment, and requiring it"
              + " would leave an order too large for anyone configured stuck in the queue for good.")
  @APIResponse(responseCode = "200", description = "Rejected and returned to DRAFT")
  @APIResponse(responseCode = "400", description = "A rejection must state a reason")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "Order is not awaiting approval")
  @POST
  @Path("/{id}/reject")
  public Response reject(@PathParam("id") UUID id, DecidePurchaseOrderRequest req) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.rejectPurchaseOrder(ctx, id, req))))
        .build();
  }

  /**
   * The order's append-only approval trail: every submission and every decision, newest first.
   *
   * <p>One order can cycle through several decisions, since a rejection sends it back to DRAFT to
   * be resubmitted. Each row carries the figure and the authority as they stood at the time, so it
   * still answers "was that person allowed to commit that much?" after the configuration changes.
   *
   * @param id the purchase order whose trail to read
   * @return the trail entries, newest first
   * @throws com.storeql.web.ApiException {@code 404} when the order does not exist
   */
  @Operation(
      summary = "A purchase order's approval history",
      description =
          "Every submission and every decision, newest first. Append-only: a rejection sends the"
              + " order back to DRAFT to be resubmitted, so one order can cycle through several"
              + " decisions, and a trail that kept only the last one would not be a trail. Each row"
              + " carries the figure and the authority as they stood at the time, so it still"
              + " answers 'was that person allowed to commit that much?' after the configuration"
              + " has changed.")
  @APIResponse(responseCode = "200", description = "The approval trail")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}/approvals")
  public Response approvals(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.purchaseOrderApprovals(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }

  /**
   * The caller's own spend ceiling in one currency.
   *
   * <p>Lets a buyer learn their authority before building an order rather than after trying to
   * submit it. Measured on net value, since VAT is recoverable for a VAT-registered business.
   *
   * @param currency ISO-4217 code to report the ceiling in
   * @return the caller's authority, flagged as unlimited when approval is not configured
   * @throws com.storeql.web.ApiException {@code 400} when the currency is not an ISO-4217 code
   */
  @Operation(
      summary = "What the caller may commit, in one currency",
      description =
          "The caller's own spend ceiling, so a buyer learns their authority before building an"
              + " order rather than after trying to submit it. Measured on the order's NET value:"
              + " VAT is recoverable for a VAT-registered business and is therefore not spend.")
  @APIResponse(responseCode = "200", description = "The caller's authority in that currency")
  @APIResponse(responseCode = "400", description = "Not an ISO 4217 currency code")
  @GET
  @Path("/spend-authority")
  public Response spendAuthority(@QueryParam("currency") String currency) {
    var authority = svc.spendAuthority(ctx, currency);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    authority,
                    currency.trim().toUpperCase(java.util.Locale.ROOT),
                    !svc.approvalEnabled())))
        .build();
  }

  /**
   * Cancels a DRAFT or SUBMITTED order, recording the reason.
   *
   * <p>A RECEIVED order cannot be cancelled — stock is already booked against it. A partly
   * delivered one is short-closed via {@link #close} instead.
   *
   * @param id the purchase order to cancel
   * @param req the cancellation reason, which is required
   * @return the cancelled order
   * @throws com.storeql.web.ApiException {@code 400} when no reason is given; {@code 404} when the
   *     order does not exist; {@code 409} when it is already RECEIVED or CANCELLED
   */
  @Operation(
      summary = "Cancel a purchase order",
      description =
          "Transitions a DRAFT or SUBMITTED purchase order to CANCELLED, recording the reason. A"
              + " RECEIVED order cannot be cancelled -- stock is already booked against it.")
  @APIResponse(responseCode = "200", description = "Purchase order cancelled")
  @APIResponse(responseCode = "400", description = "Cancellation reason missing or blank")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(
      responseCode = "409",
      description = "Purchase order is already RECEIVED or CANCELLED")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id, CancelPurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.cancelPurchaseOrder(ctx, id, req))))
        .build();
  }

  /**
   * Ordered against received, line by line, with the balance still due.
   *
   * <p>This is what a PARTIALLY_RECEIVED status does not tell you: a buyer chasing a supplier needs
   * to know <em>what</em> is missing. Receipts are matched to order lines by variant rather than by
   * line id, because a delivery note names products, not order rows.
   *
   * @param id the purchase order to report on
   * @return one row per ordered variant
   * @throws com.storeql.web.ApiException {@code 404} when the order does not exist
   */
  @Operation(
      summary = "What is still outstanding on a purchase order",
      description =
          "Ordered against received, line by line, with the balance still due. This is what a"
              + " PARTIALLY_RECEIVED status does not tell you: a buyer chasing a supplier needs to"
              + " know *what* is missing. Receipts are matched to order lines by variant rather"
              + " than by line id, because a delivery note names products, not order rows.")
  @APIResponse(responseCode = "200", description = "One row per ordered variant")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}/progress")
  public Response progress(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.purchaseOrderProgress(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }

  /**
   * Short-closes a partly delivered order: the balance is never arriving.
   *
   * <p>CLOSED is deliberately not RECEIVED — "we got it all" and "we gave up on the rest" are
   * different facts, and a supplier scorecard that cannot tell them apart is worthless. Only a
   * PARTIALLY_RECEIVED order qualifies: one with nothing delivered is a cancellation, one fully
   * delivered is already RECEIVED.
   *
   * @param id the purchase order to short-close
   * @param req the reason, which is required
   * @return the short-closed order
   * @throws com.storeql.web.ApiException {@code 400} when no reason is given; {@code 404} when the
   *     order does not exist; {@code 409} when it is not PARTIALLY_RECEIVED
   */
  @Operation(
      summary = "Short-close a partially received purchase order",
      description =
          "Marks a partly delivered order CLOSED: the balance is never arriving and we have stopped"
              + " waiting. Requires a reason. Only a PARTIALLY_RECEIVED order can be short-closed —"
              + " one with nothing delivered is a cancellation, and one fully delivered is already"
              + " RECEIVED. CLOSED is deliberately not RECEIVED, because 'we got it all' and 'we"
              + " gave up on the rest' are different facts and a supplier scorecard that cannot"
              + " tell them apart is worthless.")
  @APIResponse(responseCode = "200", description = "Purchase order short-closed")
  @APIResponse(responseCode = "400", description = "Reason missing or blank")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "Order is not PARTIALLY_RECEIVED")
  @POST
  @Path("/{id}/close")
  public Response close(@PathParam("id") UUID id, CancelPurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.closePurchaseOrderShort(ctx, id, req))))
        .build();
  }

  /**
   * Adds a line to a DRAFT purchase order.
   *
   * @param id the purchase order to add to
   * @param req the variant, quantity and unit cost
   * @return {@code 201} with the added line
   * @throws com.storeql.web.ApiException {@code 400} when the order is not DRAFT; {@code 404} when
   *     it does not exist
   */
  @Operation(
      summary = "Add a line to a purchase order",
      description = "Lines can only be added while the purchase order is DRAFT.")
  @APIResponse(responseCode = "201", description = "Line added")
  @APIResponse(responseCode = "400", description = "Purchase order is not DRAFT")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @POST
  @Path("/{id}/lines")
  public Response addLine(@PathParam("id") UUID id, AddPurchaseOrderLineRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.addPurchaseOrderLine(ctx, id, req))))
        .build();
  }

  /**
   * Lists every line on a purchase order.
   *
   * @param id the purchase order whose lines to list
   * @return the order's lines
   * @throws com.storeql.web.ApiException {@code 404} when the order does not exist
   */
  @Operation(
      summary = "List a purchase order's lines",
      description = "Returns all lines on the given purchase order.")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}/lines")
  public Response listLines(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrderLines(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
