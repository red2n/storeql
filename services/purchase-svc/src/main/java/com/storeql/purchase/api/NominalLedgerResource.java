package com.storeql.purchase.api;

import com.storeql.purchase.dto.Dtos.PostJournalRequest;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.PurchaseService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.Parsing;
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

/** FRS 102 / UK GAAP nominal ledger — read-only view of double-entry journal. */
@RequestScoped
@Path("/nominal-ledger")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Nominal Ledger")
public class NominalLedgerResource {

  @Inject PurchaseService svc;
  @Inject com.storeql.purchase.service.SalesPostingService sales;
  @Inject TenantContext ctx;

  /**
   * Read-only double-entry journal view, cursor-paginated.
   *
   * @param code restrict to one nominal code, or {@code null} for all
   * @param from inclusive start date as {@code yyyy-MM-dd}, or {@code null} for no lower bound
   * @param to inclusive end date as {@code yyyy-MM-dd}, or {@code null} for no upper bound
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100
   * @return the page of ledger entries plus a {@code nextCursor}
   * @throws com.storeql.web.ApiException {@code 400} when the cursor is malformed or a date is not
   *     {@code yyyy-MM-dd}
   */
  @Operation(
      summary = "List nominal ledger entries",
      description =
          "The double-entry ledger, line by line, optionally filtered by nominal code and date"
              + " range (?code=&from=&to=), where from and to are yyyy-MM-dd. Cursor-paginated:"
              + " ?after=<meta.nextCursor>&limit=1-100. Written by goods receipts (Dr Stock / Cr"
              + " GR/IR), supplier invoices (Dr GR/IR, Dr VAT input / Cr Creditors), credit notes,"
              + " rejections, intercompany invoices and manual journals; every posting balances"
              + " and its lines share a journalId.")
  @APIResponse(
      responseCode = "400",
      description = "Malformed pagination cursor, or from/to not in yyyy-MM-dd form")
  @GET
  public Response list(
      @QueryParam("code") String code,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = svc.getNominalLedger(ctx, code, from, to, after, Cursor.clampLimit(limit));
    var entries = page.items().stream().map(Mappers::toDto).toList();
    return Response.ok(
            ApiResponse.ok(entries, new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  @Operation(
      summary = "Post a manual journal",
      description =
          "Management only. Two to fifty lines, each a debit or a credit and never both, that"
              + " balance exactly; a nominal code is one to ten letters or digits. A store, when"
              + " named, must be one the caller may operate in and its accounting period for the"
              + " date must be open. With none named, a caller held to one store posts at it and a"
              + " caller held to none posts the business's own journal; a caller held to several"
              + " must name one. Returns the journal with its journalId and storeId.")
  @APIResponse(responseCode = "201", description = "Journal posted")
  @APIResponse(
      responseCode = "400",
      description =
          "PURCHASE_JOURNAL_LINE_INVALID: a line that is neither a debit nor a credit, or both, or"
              + " a bad code; INVALID_DATE: an entryDate that is not yyyy-MM-dd; INVALID_UUID: a"
              + " storeId that is not a UUIDv7")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role, PERMISSION_DENIED (finance.journal), STORE_ACCESS_DENIED: a"
              + " store the caller is not held to, or BUSINESS_WIDE_ONLY: a journal naming no"
              + " store from a caller held to several")
  @APIResponse(responseCode = "409", description = "The period is closed (PURCHASE_PERIOD_CLOSED)")
  @APIResponse(
      responseCode = "422",
      description = "PURCHASE_JOURNAL_UNBALANCED: debits and credits disagree")
  @POST
  @Path("/journals")
  public Response postJournal(PostJournalRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.postJournal(ctx, req))))
        .build();
  }

  @Operation(summary = "One journal, whole", description = "Management only.")
  @APIResponse(responseCode = "200", description = "The journal and its lines")
  @APIResponse(responseCode = "404", description = "Journal not found in this tenant")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role, or STORE_ACCESS_DENIED: posted at a store the caller is not"
              + " held to, or at none")
  @GET
  @Path("/journals/{id}")
  public Response journal(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getJournal(ctx, id)))).build();
  }

  @Operation(
      summary = "Orders left open on the sales receipts clearing account",
      description =
          "Sales reach the ledger from order-svc and payment-svc (17.7): each tender debits its"
              + " control account and credits 1105 sales receipts clearing, and the confirmed sale"
              + " debits 1105 and credits sales and VAT output, so a sale paid in full nets 1105 to"
              + " zero for its order. These are the orders that did not: taken but never confirmed,"
              + " confirmed for more than was taken, or refunded against a sale the ledger never"
              + " saw. Oldest first; ?storeId= narrows to one store, ?limit= up to 200. Management"
              + " only.")
  @APIResponse(responseCode = "200", description = "Open orders, oldest first")
  @APIResponse(responseCode = "400", description = "A storeId that is not a UUID")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role, or STORE_ACCESS_DENIED: a store the caller is not held to")
  @GET
  @Path("/sales-clearing")
  public Response salesClearing(
      @QueryParam("storeId") String storeId,
      @QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {
    return Response.ok(
            ApiResponse.ok(
                sales.openClearing(ctx, storeId, limit).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "The trial balance",
      description =
          "Management only. Every nominal code's debits, credits and balance over ?from=&to="
              + " (yyyy-MM-dd, both optional), optionally for one store (?storeId=). totalDebit"
              + " equals totalCredit on a ledger where every posting balanced, which is every"
              + " posting this service writes; balanced=false is a fault to investigate, not a"
              + " figure to report.")
  @APIResponse(responseCode = "200", description = "The trial balance")
  @APIResponse(responseCode = "400", description = "A range that ends before it starts")
  @APIResponse(
      responseCode = "403",
      description =
          "Not a management role, or STORE_ACCESS_DENIED: a store the caller is not held to")
  @GET
  @Path("/trial-balance")
  public Response trialBalance(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId) {
    var rows = svc.trialBalance(ctx, from, to, storeId);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toTrialBalance(
                    rows,
                    from == null ? null : Parsing.date(from, "from"),
                    to == null ? null : Parsing.date(to, "to"),
                    Parsing.optionalUuid(storeId, "storeId"))))
        .build();
  }
}
