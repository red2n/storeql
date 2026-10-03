package com.storeql.order.api;

import com.storeql.ids.Ids;
import com.storeql.order.domain.SalesAttribution.SellerChange;
import com.storeql.order.domain.SalesAttribution.Statement;
import com.storeql.order.domain.SalesAttribution.StatementLine;
import com.storeql.order.dto.CommissionDtos;
import com.storeql.order.service.CommissionStatementService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/commission}: what a period of attributed sales earned, and who a sale is credited
 * to.
 *
 * <p>The arrangement is tenant-svc's and the sales are this service's, so a statement is produced
 * here and rated there: figures go over, money comes back, and no order or return leaves the
 * service that owns it.
 *
 * <p>Under {@code /admin/} so the authorisation filter gates the whole prefix to management — what
 * anybody is owed is nobody else's business, and the by-path form is what stops a method added here
 * later from shipping open (SJ-D10, SJ-D11).
 */
@RequestScoped
@Path("/admin/commission")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reports")
public class CommissionResource {

  @Inject CommissionStatementService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Produce a commission statement for a finished period",
      description =
          "A draft, with a line per person per stretch under one arrangement per rate band. The"
              + " period must have finished: a statement for days still trading would be approved,"
              + " paid, and then contradicted. Refused when a statement already stands for that"
              + " period and scope unless it is named as the one being replaced, and refused"
              + " outright when the arrangements cannot be read — a statement of zeros would be"
              + " signed off and paid.")
  @APIResponse(responseCode = "201", description = "The draft, with its lines")
  @APIResponse(responseCode = "400", description = "COMMISSION_PERIOD_INVALID")
  @APIResponse(
      responseCode = "409",
      description =
          "COMMISSION_STATEMENT_STANDS or COMMISSION_STATEMENT_NOT_STANDING;"
              + " COMMISSION_FX_RATE_MISSING when a per-unit arrangement pays in a currency other"
              + " than the statement's and the business keeps no rate to translate it (the"
              + " seller in details) — translated at the business's own rate otherwise, never"
              + " counted as if it were the statement's")
  @APIResponse(
      responseCode = "503",
      description =
          "COMMISSION_RATES_UNAVAILABLE (the arrangements could not be read);"
              + " COMMISSION_FX_UNAVAILABLE (a translation was needed and the business's exchange"
              + " rates could not be read)")
  @POST
  @Path("/statements")
  public Response draft(CommissionDtos.StatementRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = optionalUuid(req.storeId(), "storeId");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    Statement statement =
        svc.draft(
            ctx.requireTenantId(),
            storeId,
            date(req.from(), "from"),
            date(req.to(), "to"),
            req.currency(),
            req.note(),
            optionalUuid(req.supersedes(), "supersedes"),
            ctx,
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(toDto(statement, true))).build();
  }

  @Operation(
      summary = "The statements of this business",
      description = "Newest period first. Lines are left out; read one statement for those.")
  @GET
  @Path("/statements")
  public ApiResponse<List<CommissionDtos.StatementResponse>> statements(
      @QueryParam("storeId") String storeId,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc
            .statements(ctx.requireTenantId(), optionalUuid(storeId, "storeId"), status, limit)
            .stream()
            .map(s -> toDto(s, false))
            .toList());
  }

  @Operation(summary = "One statement, with every line that explains it")
  @APIResponse(responseCode = "404", description = "COMMISSION_STATEMENT_NOT_FOUND")
  @GET
  @Path("/statements/{id}")
  public ApiResponse<CommissionDtos.StatementResponse> statement(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(toDto(svc.statement(ctx.requireTenantId(), id), true));
  }

  @Operation(
      summary = "Approve a statement, which freezes it",
      description =
          "After this the figures do not move: a later refund, a corrected arrangement or a"
              + " re-credited sale changes nothing, because somebody is paid on these numbers. A"
              + " period that has to change is restated by a new statement naming this one.")
  @APIResponse(responseCode = "409", description = "COMMISSION_STATEMENT_NOT_DRAFT")
  @POST
  @Path("/statements/{id}/approval")
  public ApiResponse<CommissionDtos.StatementResponse> approve(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(toDto(svc.approve(ctx.requireTenantId(), id, ctx.requireUserId()), true));
  }

  @Operation(
      summary = "Throw away a draft",
      description =
          "A draft is a working document. An approved statement is a record and is restated, never"
              + " deleted.")
  @APIResponse(responseCode = "409", description = "COMMISSION_STATEMENT_NOT_DRAFT")
  @DELETE
  @Path("/statements/{id}")
  public Response discard(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.discard(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  @Operation(
      summary = "Credit a sale to somebody else",
      description =
          "Who rang a sale up is the POS journal's business; who sold it is this. The change is kept"
              + " with its reason, because commission follows it. A statement already approved does"
              + " not move — it is frozen — so a period that was paid on the old attribution has to"
              + " be restated for this to reach anybody's pay.")
  @APIResponse(responseCode = "400", description = "COMMISSION_REASON_REQUIRED")
  @APIResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @PUT
  @Path("/sales/{orderId}/seller")
  public ApiResponse<CommissionDtos.SellerChangeResponse> credit(
      @PathParam("orderId") UUID orderId, CommissionDtos.SellerRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        toDto(
            svc.credit(
                ctx.requireTenantId(),
                orderId,
                optionalUuid(req.sellerUserId(), "sellerUserId"),
                req.reason(),
                ctx.requireUserId())));
  }

  @Operation(
      summary = "How a sale's attribution has changed",
      description = "Newest first, with who changed it and why.")
  @APIResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @GET
  @Path("/sales/{orderId}/seller")
  public ApiResponse<List<CommissionDtos.SellerChangeResponse>> changes(
      @PathParam("orderId") UUID orderId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc.changes(ctx.requireTenantId(), orderId).stream()
            .map(CommissionResource::toDto)
            .toList());
  }

  private static CommissionDtos.StatementResponse toDto(Statement s, boolean withLines) {
    return new CommissionDtos.StatementResponse(
        s.id().toString(),
        text(s.storeId()),
        s.periodStart().toString(),
        s.periodEnd().toString(),
        s.currency(),
        s.status(),
        s.netSales().toPlainString(),
        s.commission().toPlainString(),
        s.note(),
        text(s.supersedes()),
        text(s.supersededBy()),
        s.createdAt().toString(),
        text(s.createdBy()),
        s.approvedAt() == null ? null : s.approvedAt().toString(),
        text(s.approvedBy()),
        withLines ? s.lines().stream().map(CommissionResource::toDto).toList() : null);
  }

  private static CommissionDtos.LineResponse toDto(StatementLine l) {
    return new CommissionDtos.LineResponse(
        l.sellerUserId().toString(),
        text(l.schemeId()),
        l.schemeName(),
        l.segmentFrom().toString(),
        l.segmentTo().toString(),
        plain(l.thresholdFrom()),
        plain(l.rate()),
        plain(l.amount()),
        plain(l.commission()),
        l.rateCurrency() == null ? null : l.rateCurrency().strip(),
        plain(l.ratedCommission()));
  }

  private static CommissionDtos.SellerChangeResponse toDto(SellerChange c) {
    return new CommissionDtos.SellerChangeResponse(
        c.id().toString(),
        c.orderId().toString(),
        text(c.fromUserId()),
        text(c.toUserId()),
        c.reason(),
        c.changedAt().toString(),
        text(c.changedBy()));
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }

  private static String plain(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static UUID optionalUuid(String value, String field) {
    if (value == null || value.isBlank()) return null;
    try {
      return Ids.parse(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "COMMISSION_ID_INVALID", field + " is not an id: " + value, List.of(), e);
    }
  }

  private static LocalDate date(String value, String field) {
    // A missing date is asked about rather than caught: validation rejects a blank one first, and
    // reading a null by catching the failure it causes hides the reason from whoever sent it.
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest(
          "COMMISSION_DATE_INVALID", field + " is a date as YYYY-MM-DD, and this one is missing");
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400,
          "COMMISSION_DATE_INVALID",
          field + " is a date as YYYY-MM-DD: " + value,
          List.of(),
          e);
    }
  }
}
