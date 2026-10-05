package com.storeql.payment.api;

import com.storeql.ids.Ids;
import com.storeql.payment.dto.TerminalDtos;
import com.storeql.payment.mapper.TerminalMappers;
import com.storeql.payment.service.TerminalService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.HttpHeaders;
import com.storeql.web.IdempotencyKeys;
import com.storeql.web.Permissions;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Taking a card on a terminal, from the till (07.16).
 *
 * <p><b>Its own class, rooted at {@code /payments/terminal}, and that is not cosmetic.</b> JAX-RS
 * chooses ONE resource class by the best match on its root path and then looks for the method only
 * inside that class. These routes first lived on a class rooted at {@code "/"} with absolute paths
 * on each method, which made every one of them unreachable: {@code PaymentResource} is rooted at
 * {@code /payments}, that beat {@code "/"} for a URI beginning {@code /payments/}, and the request
 * 404'd without ever being offered to this code. A root of {@code /payments/terminal} has more
 * literal characters than {@code /payments}, so it wins the URIs that are actually ours.
 *
 * <p>It was found by running the service and asking it, not by a test: the integration test calls
 * the service object directly, so it never exercised a route. The k6 flow does, and would have
 * caught it on its first run.
 *
 * <p><b>No route here accepts a card number.</b> The terminal reads the card; this platform sends
 * an amount and receives a verdict.
 *
 * <p><b>Every write is at the terminal's own store:</b> a caller held to other stores is refused
 * {@code 403 STORE_ACCESS_DENIED}, after another business's terminal or payment is {@code 404}.
 */
@Path("/payments/terminal")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Card terminals")
public class TerminalPaymentResource {

  @Inject TerminalService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Take a card on a terminal",
      description =
          "Sends the amount to the device and returns what it said. The attempt is recorded BEFORE the"
              + " terminal is asked, with the Idempotency-Key, so a retried press finds the first"
              + " attempt instead of starting a second EMV transaction on a real card — which is the"
              + " canonical double-charge. A TIMED_OUT attempt is never retried automatically: the"
              + " card may have been charged. While the terminal has a card payment that is not"
              + " settled (still at the machine; approved and neither recorded on its order with"
              + " POST /payments naming it nor put back; or timed out with nobody's word on it), a"
              + " new key is refused 409 TERMINAL_UNSETTLED_APPROVAL, one detail per unsettled"
              + " payment (attemptId=…;orderId=…;amount=…;currency=…;onCard=…;state=…;standing=…);"
              + " a replay under a key already claimed is answered as before.")
  @APIResponse(responseCode = "201", description = "The terminal answered — read `state`")
  @APIResponse(
      responseCode = "400",
      description =
          "TERMINAL_AMOUNT_INVALID: not positive, or finer than the currency's own minor unit (ISO"
              + " 4217: none for JPY, two for GBP, three for KWD) — refused, never rounded;"
              + " CURRENCY_INVALID: not an ISO 4217 code; TERMINAL_CARD_DATA_NOT_ACCEPTED;"
              + " TERMINAL_ID_INVALID; VALIDATION_FAILED")
  @APIResponse(
      responseCode = "403",
      description = "Not a cashier, manager or owner; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "TERMINAL_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description =
          "TERMINAL_UNSETTLED_APPROVAL (the terminal holds a card payment that is not settled),"
              + " PAYMENT_ORDER_GIVEN_UP (the sale was cancelled or voided: no card is taken for"
              + " it), TERMINAL_CURRENCY_MISMATCH (not the business's own currency, which details"
              + " name as currency=…; not checked while it cannot be read), TERMINAL_RETIRED,"
              + " TERMINAL_VENDOR_UNAVAILABLE, or TERMINAL_REQUEST_IN_FLIGHT (two identical presses"
              + " racing — ask again for the outcome)")
  @POST
  public Response sale(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      TerminalDtos.SaleRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID terminalId = uuid(req.terminalId(), "terminalId");
    UUID orderId = uuid(req.orderId(), "orderId");
    // The business's own terminal (404 otherwise), then at a store the caller may act at.
    ctx.requireStoreAccess(svc.get(tenantId, terminalId).storeId());
    var attempt =
        svc.sale(
            tenantId,
            terminalId,
            orderId,
            req.amount(),
            req.currency().toUpperCase(java.util.Locale.ROOT),
            ctx.requireUserId(),
            IdempotencyKeys.effective(idempotencyKey, null));
    return Response.status(201)
        .entity(ApiResponse.ok(TerminalMappers.toDto(svc.facts(attempt))))
        .build();
  }

  @Operation(
      summary = "Put money back on the card that paid",
      description =
          "Linked to the original attempt rather than taking a card again: an unlinked refund is how"
              + " card fraud is done, and most acquirers refuse them outright. The amount is in the"
              + " currency the card paid in, no finer than its minor unit, and never more than is"
              + " still on that card. A reason is required (at most 500 characters) and is kept,"
              + " with who asked and when. On a sale recorded as a tender, the books' refund (and"
              + " its PaymentRefunded) is written once the machine has put it back — also when the"
              + " machine says so after a person said it was not made, and also when the sale was"
              + " recorded only after the refund was asked: once, and counted against what is left"
              + " on the card. Putting all of an unrecorded approval back settles the"
              + " terminal for its next sale.")
  @APIResponse(responseCode = "201", description = "The terminal answered — read `state`")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED: no reason, or one longer than 500 characters, or no amount;"
              + " TERMINAL_AMOUNT_INVALID: not positive, or finer than the paying currency's minor"
              + " unit")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a manager or owner; PERMISSION_DENIED: the caller's role does not hold"
              + " sales.refund; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "TERMINAL_ATTEMPT_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description =
          "TERMINAL_NOT_A_SALE, TERMINAL_NOT_APPROVED, TERMINAL_REFUND_TOO_LARGE,"
              + " TERMINAL_VENDOR_UNAVAILABLE, TERMINAL_REQUEST_IN_FLIGHT, or — one refund of a card"
              + " at a time — TERMINAL_REFUND_IN_FLIGHT (another refund of it is at the machine) or"
              + " TERMINAL_REFUND_UNDECIDED (another timed out with nobody's word on it), details"
              + " attemptId=…;state=…;amount=…;currency=…; TERMINAL_RETIRED only when the machine"
              + " that took it is retired and its store has no other of its vendor in service"
              + " (with one, the refund goes through that)")
  @POST
  @Path("/{id}/refunds")
  public Response refund(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      @PathParam("id") UUID id,
      TerminalDtos.RefundRequest req) {
    // Management's, like every other refund on this platform: the money goes out, not in. And the
    // same permission as the books' refund (20.10), since this one is the books' refund too.
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(attempt(tenantId, id).storeId());
    var attempt =
        svc.refund(
            tenantId,
            id,
            req.amount(),
            ctx.requireUserId(),
            IdempotencyKeys.effective(idempotencyKey, null),
            req.reason());
    return Response.status(201)
        .entity(ApiResponse.ok(TerminalMappers.toDto(svc.facts(attempt))))
        .build();
  }

  @Operation(
      summary = "Say what a card machine that did not answer shows",
      description =
          "For a payment the machine did not answer — one that TIMED_OUT, or one still REQUESTED"
              + " after the machine has had its time to answer (storeql.terminal"
              + ".answer-within-seconds, 180 by default: the call that asked it is gone) — a manager"
              + " or owner at the terminal's store records what the machine shows: APPROVED (it is"
              + " then an approval to record on its sale with POST /payments naming it, or to put"
              + " back; on a sale cancelled or voided it is put back at once) or NOT_TAKEN, with a"
              + " reason (at most 500 characters) and an Idempotency-Key. Kept once, append-only,"
              + " with who and when; a replay under the same key answers with the first. A request"
              + " left at the machine is written as TIMED_OUT, since the machine said nothing; if it"
              + " answers after all, an approval is kept over the person's word. A NOT_TAKEN sale"
              + " no longer holds its terminal. Needs sales.refund: what a person says decides"
              + " whether money goes back.")
  @APIResponse(responseCode = "200", description = "Recorded — read `standing` and `decision`")
  @APIResponse(
      responseCode = "400",
      description =
          "IDEMPOTENCY_KEY_REQUIRED; VALIDATION_FAILED (no outcome, no reason, or a reason longer"
              + " than 500 characters); TERMINAL_OUTCOME_INVALID")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a manager or owner; PERMISSION_DENIED: the caller's role does not hold"
              + " sales.refund; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "TERMINAL_ATTEMPT_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description =
          "TERMINAL_REQUEST_IN_FLIGHT (still at the machine and it may yet answer; details"
              + " decidableFrom=), TERMINAL_NOT_TIMED_OUT (the machine answered it),"
              + " TERMINAL_ATTEMPT_ALREADY_DECIDED, IDEMPOTENCY_KEY_REUSED")
  @POST
  @Path("/{id}/settle")
  public ApiResponse<TerminalDtos.AttemptResponse> settle(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      @PathParam("id") UUID id,
      TerminalDtos.DecisionRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    String key = requireKey(idempotencyKey);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(attempt(tenantId, id).storeId());
    return ApiResponse.ok(
        TerminalMappers.toDto(
            svc.decide(tenantId, id, req.outcome(), req.reason(), ctx.requireUserId(), key)));
  }

  @Operation(
      summary = "What holds a terminal",
      description =
          "The card payments on a terminal that are not settled, oldest first — what a new sale on"
              + " it is refused for. Empty when it may take the next one.")
  @APIResponse(responseCode = "200", description = "The unsettled payments, maybe none")
  @APIResponse(responseCode = "400", description = "TERMINAL_ID_INVALID")
  @APIResponse(
      responseCode = "403",
      description = "Not a cashier, manager or owner; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "TERMINAL_NOT_FOUND")
  @GET
  @Path("/unsettled")
  public ApiResponse<List<TerminalDtos.AttemptResponse>> unsettled(
      @QueryParam("terminalId") String terminalId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    UUID id = uuid(terminalId, "terminalId");
    ctx.requireStoreAccess(svc.get(tenantId, id).storeId());
    return ApiResponse.ok(TerminalMappers.facts(svc.unsettled(tenantId, id)));
  }

  @Operation(
      summary = "Money owed back to cards",
      description =
          "What an order cancelled, voided or returned to its card owes back to a card a terminal"
              + " took, and what a manager's refund of a recorded card payment is putting back —"
              + " until the machine has put it back. ?state= OWED, NEEDS_ATTENTION (a manager acts:"
              + " the machine could not be reached, refused, or did not answer), REFUNDED,"
              + " NOT_REFUNDED or REFUNDED_ANOTHER_WAY (no machine could put it back, and a"
              + " manager recorded how it was given back); none for what is still owed. ?storeId= one store; none for the"
              + " caller's stores (the whole business for one held to none). Paged by ?after= and"
              + " ?limit=.")
  @APIResponse(
      responseCode = "400",
      description = "CARD_REFUND_DUE_STATE_INVALID, INVALID_CURSOR, TERMINAL_ID_INVALID")
  @APIResponse(
      responseCode = "403",
      description = "Not a manager or owner; STORE_ACCESS_DENIED for a store not the caller's")
  @GET
  @Path("/refund-dues")
  public ApiResponse<List<TerminalDtos.RefundDueResponse>> dues(
      @QueryParam("storeId") String storeId,
      @QueryParam("state") String state,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    var stores = ctx.reportStores(storeId == null ? null : uuid(storeId, "storeId"));
    var page = svc.dues(tenantId, stores, state, after, limit);
    return ApiResponse.ok(
        TerminalMappers.dues(page.items()),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(summary = "One sum owed back to a card")
  @APIResponse(
      responseCode = "403",
      description = "Not a manager or owner; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "CARD_REFUND_DUE_NOT_FOUND")
  @GET
  @Path("/refund-dues/{id}")
  public ApiResponse<TerminalDtos.RefundDueResponse> due(@PathParam("id") UUID id) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    var due = svc.due(tenantId, id);
    ctx.requireStoreAccess(due.storeId());
    return ApiResponse.ok(TerminalMappers.toDto(due, svc.closureOf(tenantId, id).orElse(null)));
  }

  @Operation(
      summary = "Ask the card machine again to put money back",
      description =
          "For money owed back to a card that waits for a person: asks the sale's own terminal again"
              + " — or, once that is retired, another of its vendor in service at its store (a"
              + " refund is linked to the sale by the vendor's reference, so it needs the vendor,"
              + " not the device) — under the caller's Idempotency-Key (a replay answers as the"
              + " first). A refund of it that went through but was never finished is finished"
              + " instead; one still at the machine, or one that timed out with nobody's word on"
              + " it, is refused until somebody has looked (POST"
              + " /payments/terminal/{refundAttemptId}/settle).")
  @APIResponse(responseCode = "200", description = "The due as it now stands — read `state`")
  @APIResponse(responseCode = "400", description = "IDEMPOTENCY_KEY_REQUIRED")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a manager or owner; PERMISSION_DENIED: the caller's role does not hold"
              + " sales.refund; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "CARD_REFUND_DUE_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description =
          "CARD_REFUND_DUE_SETTLED, TERMINAL_REQUEST_IN_FLIGHT, TERMINAL_REFUND_UNDECIDED,"
              + " TERMINAL_REFUND_TOO_LARGE, IDEMPOTENCY_KEY_REUSED; TERMINAL_RETIRED: the sale's"
              + " terminal is retired and its store has no other of its vendor in service (details"
              + " terminalId=…;vendor=…;storeId=…) — register one, or record how it was given back"
              + " another way (POST /payments/terminal/refund-dues/{id}/another-way)")
  @POST
  @Path("/refund-dues/{id}/retry")
  public ApiResponse<TerminalDtos.RefundDueResponse> retry(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey, @PathParam("id") UUID id) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    String key = requireKey(idempotencyKey);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(svc.due(tenantId, id).storeId());
    return ApiResponse.ok(
        TerminalMappers.toDto(svc.retryDue(tenantId, id, ctx.requireUserId(), key)));
  }

  @Operation(
      summary = "Record how money owed back to a card was given back another way",
      description =
          "For money owed back to a card that no card machine can put back: its terminal is retired"
              + " with none of its vendor left at the store, or the machine keeps refusing (a card"
              + " closed, a machine dead). A manager or owner at the store says how it was given"
              + " back instead — method CASH, CARD (the acquirer's own refund of the card, outside"
              + " any machine here: its reference is required), UPI or WALLET — with a reason (at"
              + " most 500 characters) and an Idempotency-Key. Kept once, append-only, with who and"
              + " when; a replay under the same key answers with the first. The machine first:"
              + " only a due in NEEDS_ATTENTION (a machine was asked and did not put it back, or"
              + " could not be asked) is closed this way, and never while a refund of it is at the"
              + " machine or timed out with nobody's word on it. When the due is drawn against a"
              + " recorded tender, the books' refund in that method and its PaymentRefunded are"
              + " written with it, and the due no longer holds the tender; an approval never"
              + " recorded on a sale has nothing in the books to reverse, so it is closed by the"
              + " acquirer's refund only (CARD). The due ends REFUNDED_ANOTHER_WAY. Needs"
              + " sales.refund.")
  @APIResponse(responseCode = "200", description = "Recorded — read `state` and `anotherWay`")
  @APIResponse(
      responseCode = "400",
      description =
          "IDEMPOTENCY_KEY_REQUIRED; VALIDATION_FAILED (no method, no reason, a reason longer than"
              + " 500 characters or a reference longer than 255); CARD_REFUND_METHOD_INVALID;"
              + " CARD_REFUND_REFERENCE_REQUIRED (CARD with no reference);"
              + " TERMINAL_CARD_DATA_NOT_ACCEPTED (a reference that is a card number)")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a manager or owner; PERMISSION_DENIED: the caller's role does not hold"
              + " sales.refund; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "CARD_REFUND_DUE_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description =
          "CARD_REFUND_DUE_SETTLED (nothing is owed on it any more), CARD_REFUND_DUE_NOT_TRIED (the"
              + " machine has not been asked: POST …/retry first), TERMINAL_REQUEST_IN_FLIGHT or"
              + " TERMINAL_REFUND_UNDECIDED (a refund of it is not accounted for),"
              + " CARD_REFUND_DUE_NOT_IN_BOOKS (an approval never recorded goes back on its card"
              + " only: method CARD), IDEMPOTENCY_KEY_REUSED; details state=…")
  @POST
  @Path("/refund-dues/{id}/another-way")
  public ApiResponse<TerminalDtos.RefundDueResponse> anotherWay(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      @PathParam("id") UUID id,
      TerminalDtos.AnotherWayRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    String key = requireKey(idempotencyKey);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    // The business's own due (404 otherwise), then at a store the caller may act at.
    ctx.requireStoreAccess(svc.due(tenantId, id).storeId());
    var due =
        svc.refundedAnotherWay(
            tenantId, id, req.method(), req.reference(), req.reason(), ctx.requireUserId(), key);
    return ApiResponse.ok(TerminalMappers.toDto(due, svc.closureOf(tenantId, id).orElse(null)));
  }

  @Operation(
      summary = "Ask the terminal to stop asking for a card",
      description =
          "For a sale the cashier abandoned. A question for the machine, not its answer: it settles"
              + " nothing. The cardholder may have finished a moment before, so the attempt stays"
              + " REQUESTED (standing AT_MACHINE, holding the terminal) until the machine answers"
              + " the sale itself — CANCELLED if the cancel took, APPROVED if the card was taken"
              + " first, and then it is the card taken. Read it again (GET"
              + " /payments/terminal/{id}) until it is settled; one the machine never answers is a"
              + " manager's to settle (POST /payments/terminal/{id}/settle). A settled attempt"
              + " comes back as it is.")
  @APIResponse(
      responseCode = "200",
      description = "The attempt as it now stands — usually still REQUESTED: read `state`")
  @APIResponse(
      responseCode = "403",
      description = "Not a cashier, manager or owner; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "TERMINAL_ATTEMPT_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description =
          "TERMINAL_NOT_A_SALE: a refund is not taken at the pinpad, so it is not stopped there")
  @POST
  @Path("/{id}/cancel")
  public ApiResponse<TerminalDtos.AttemptResponse> cancel(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(attempt(tenantId, id).storeId());
    return ApiResponse.ok(TerminalMappers.toDto(svc.facts(svc.cancel(tenantId, id))));
  }

  @Operation(
      summary = "Every terminal attempt against an order",
      description =
          "Declines included, oldest first: a declined card followed by a cash tender reads in the"
              + " order it happened, which is what a cashier and an auditor both need. Only the"
              + " attempts at stores the caller may act at.")
  @GET
  @Path("/by-order/{orderId}")
  public ApiResponse<List<TerminalDtos.AttemptResponse>> byOrder(
      @PathParam("orderId") UUID orderId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        svc.attemptsOf(tenantId, orderId).stream()
            .filter(a -> ctx.hasStoreAccess(a.storeId()))
            .map(a -> TerminalMappers.toDto(svc.facts(a)))
            .toList());
  }

  @Operation(summary = "One terminal attempt")
  @APIResponse(
      responseCode = "403",
      description = "Not a cashier, manager or owner; STORE_ACCESS_DENIED: held to other stores")
  @APIResponse(responseCode = "404", description = "TERMINAL_ATTEMPT_NOT_FOUND")
  @GET
  @Path("/{id}")
  public ApiResponse<TerminalDtos.AttemptResponse> one(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    var attempt = attempt(ctx.requireTenantId(), id);
    ctx.requireStoreAccess(attempt.storeId());
    return ApiResponse.ok(TerminalMappers.toDto(svc.facts(attempt)));
  }

  private com.storeql.payment.domain.Terminals.Attempt attempt(UUID tenantId, UUID id) {
    return svc.attempt(tenantId, id)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"));
  }

  private static String requireKey(String idempotencyKey) {
    String key = IdempotencyKeys.effective(idempotencyKey, null);
    if (key == null) {
      throw ApiException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED", "the Idempotency-Key header is required");
    }
    return key;
  }

  private static UUID uuid(String value, String field) {
    try {
      return Ids.parse(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "TERMINAL_ID_INVALID", field + " is not an id", List.of(), e);
    }
  }
}
