package com.storeql.payment.api;

import com.storeql.payment.domain.Disputes;
import com.storeql.payment.dto.DisputeDtos;
import com.storeql.payment.service.DisputeService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.HttpHeaders;
import com.storeql.web.Parsing;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The chargeback register (11.9): the disputes a business's card payments have drawn, what each is
 * waiting for and by when, the answer the business gave, and how it ended. Management's: a dispute
 * is money leaving the business and an argument with a bank.
 */
@Path("/admin/disputes")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Disputes")
public class DisputeResource {

  @Inject DisputeService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The chargeback register",
      description = "Newest first; ?status= one status, ?storeId= one store. OWNER or MANAGER.")
  @GET
  public ApiResponse<List<DisputeDtos.DisputeResponse>> list(
      @QueryParam("status") String status,
      @QueryParam("storeId") String storeId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Cursor.Page<Disputes.Dispute> page =
        svc.list(
            ctx.requireTenantId(), status, Parsing.optionalUuid(storeId, "storeId"), after, limit);
    return ApiResponse.ok(
        page.items().stream().map(DisputeResource::toDto).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "Record a chargeback the acquirer has told the business about",
      description =
          "For a card taken on a terminal the platform does not talk to. A payment provider's own"
              + " disputes arrive by webhook. Takes an Idempotency-Key. OWNER or MANAGER holding"
              + " the sales.refund permission.")
  @APIResponse(responseCode = "201", description = "Recorded")
  @APIResponse(
      responseCode = "409",
      description = "Not a card payment, or a dispute is already open")
  @POST
  public Response record(
      DisputeDtos.RecordDisputeRequest req, @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String key) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    Validations.validate(req);
    Disputes.Dispute d = svc.record(ctx.requireTenantId(), ctx.requireUserId(), req, key);
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(toDto(d))).build();
  }

  @Operation(summary = "Disputes over a period against the card payments they came out of")
  @GET
  @Path("/summary")
  public ApiResponse<DisputeDtos.SummaryResponse> summary(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Instant start = Parsing.instant(from, "from");
    Instant end = Parsing.instant(to, "to");
    Disputes.Summary s =
        svc.summary(ctx.requireTenantId(), Parsing.optionalUuid(storeId, "storeId"), start, end);
    return ApiResponse.ok(
        new DisputeDtos.SummaryResponse(
            start.toString(),
            end.toString(),
            s.opened(),
            s.needsResponse(),
            s.underReview(),
            s.won(),
            s.lost(),
            s.amountDisputed(),
            s.amountLost(),
            s.feesCharged(),
            s.cardPayments(),
            s.ratio(),
            DisputeService.aboveMonitoringThreshold(s.ratio())));
  }

  @Operation(summary = "One dispute, with its history and the business's answer")
  @GET
  @Path("/{id}")
  public ApiResponse<DisputeDtos.FileResponse> get(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(toDto(svc.file(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Answer a dispute with evidence",
      description =
          "Once, and not after its date. A provider's dispute is sent to the provider; one the"
              + " acquirer told the business about is kept here and sent by the business. OWNER or"
              + " MANAGER holding the sales.refund permission.")
  @APIResponse(responseCode = "403", description = "PERMISSION_DENIED: sales.refund narrowed out")
  @APIResponse(responseCode = "409", description = "Already answered, closed, or past its date")
  @POST
  @Path("/{id}/evidence")
  public ApiResponse<DisputeDtos.FileResponse> evidence(
      @PathParam("id") UUID id, DisputeDtos.EvidenceRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    Validations.validate(req);
    return ApiResponse.ok(
        toDto(svc.submitEvidence(ctx.requireTenantId(), ctx.requireUserId(), id, req)));
  }

  @Operation(
      summary = "Accept a dispute: do not contest it",
      description = "OWNER or MANAGER holding the sales.refund permission.")
  @APIResponse(responseCode = "409", description = "Already closed")
  @POST
  @Path("/{id}/accept")
  public ApiResponse<DisputeDtos.FileResponse> accept(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    return ApiResponse.ok(toDto(svc.accept(ctx.requireTenantId(), ctx.requireUserId(), id)));
  }

  @Operation(
      summary = "Record how a dispute the acquirer told the business about ended",
      description =
          "WON or LOST. A provider's dispute is decided by its webhook, never here. OWNER or"
              + " MANAGER holding the sales.refund permission.")
  @APIResponse(responseCode = "409", description = "A provider's dispute, or already closed")
  @POST
  @Path("/{id}/resolve")
  public ApiResponse<DisputeDtos.FileResponse> resolve(
      @PathParam("id") UUID id, DisputeDtos.ResolveRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    ctx.requirePermission(Permissions.SALES_REFUND);
    Validations.validate(req);
    return ApiResponse.ok(toDto(svc.resolve(ctx.requireTenantId(), ctx.requireUserId(), id, req)));
  }

  private static DisputeDtos.DisputeResponse toDto(Disputes.Dispute d) {
    boolean overdue =
        Disputes.NEEDS_RESPONSE.equals(d.status())
            && d.evidenceDueBy() != null
            && Instant.now().isAfter(d.evidenceDueBy());
    return new DisputeDtos.DisputeResponse(
        d.id().toString(),
        d.paymentId().toString(),
        d.orderId().toString(),
        d.storeId() == null ? null : d.storeId().toString(),
        d.provider(),
        d.providerDisputeRef(),
        d.amount(),
        d.feeAmount(),
        d.currency(),
        d.reason(),
        d.networkReasonCode(),
        d.status(),
        d.fundsWithdrawn(),
        d.evidenceDueBy() == null ? null : d.evidenceDueBy().toString(),
        overdue,
        d.openedAt().toString(),
        d.closedAt() == null ? null : d.closedAt().toString());
  }

  private static DisputeDtos.FileResponse toDto(Disputes.DisputeFile f) {
    Disputes.Evidence e = f.evidence();
    return new DisputeDtos.FileResponse(
        toDto(f.dispute()),
        f.events().stream()
            .map(
                ev ->
                    new DisputeDtos.EventResponse(
                        ev.kind(),
                        ev.detail(),
                        ev.actorId() == null ? null : ev.actorId().toString(),
                        ev.createdAt().toString()))
            .toList(),
        e == null
            ? null
            : new DisputeDtos.EvidenceResponse(
                e.productDescription(),
                e.customerName(),
                e.customerEmail(),
                e.receiptReference(),
                e.fulfilmentProof(),
                e.customerCommunication(),
                e.refundPolicy(),
                e.notes(),
                e.submittedBy().toString(),
                e.submittedAt().toString()));
  }
}
