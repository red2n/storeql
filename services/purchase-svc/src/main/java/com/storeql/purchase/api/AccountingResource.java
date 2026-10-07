package com.storeql.purchase.api;

import com.storeql.purchase.dto.AccountingDtos;
import com.storeql.purchase.mapper.AccountingMappers;
import com.storeql.purchase.service.AccountingService;
import com.storeql.purchase.service.BusinessWide;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting connectors (17.9), under {@code /accounting}: the package a business keeps its books
 * in, the mapping of its nominal codes onto the package's accounts, and every journal's push. Read
 * by management; the connection itself is the owner's to make and remove.
 *
 * <p>All of it is the business's books as a whole — one connection, one mapping, every store's
 * journals pushed through it — so every route but the catalogue of packages needs a caller held to
 * no store ({@code 403 BUSINESS_WIDE_ONLY}), as the business's other settings do.
 */
@Path("/accounting")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting connectors")
public class AccountingResource {

  @Inject AccountingService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The packages that can be connected",
      description = "Management. Each with the settings it needs and where its tokens come from.")
  @GET
  @Path("/providers")
  public ApiResponse<List<AccountingDtos.ProviderResponse>> providers() {
    management();
    return ApiResponse.ok(service.providers().stream().map(AccountingMappers::toProvider).toList());
  }

  @Operation(
      summary = "The business's connection",
      description =
          "Management. Never the tokens; with what has been pushed, is waiting, failed or is uncertain.")
  @APIResponse(responseCode = "404", description = "ACCOUNTING_NOT_CONNECTED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; BUSINESS_WIDE_ONLY: a caller held to stores, the books being"
              + " the whole business's")
  @GET
  @Path("/connection")
  public ApiResponse<AccountingDtos.ConnectionResponse> connection() {
    books();
    return ApiResponse.ok(AccountingMappers.toConnection(service.view(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Connect the business's package",
      description =
          "OWNER only. Replaces whatever was connected, with its mapping and its log. The tokens are"
              + " sealed at rest and never shown again. Journals dated from syncFrom are pushed.")
  @APIResponse(
      responseCode = "400",
      description =
          "ACCOUNTING_PROVIDER_UNKNOWN, ACCOUNTING_SETTINGS_INVALID, ACCOUNTING_CREDENTIALS_MISSING, ACCOUNTING_CREDENTIALS_INVALID, ACCOUNTING_SYNC_FROM_INVALID")
  @APIResponse(
      responseCode = "503",
      description = "ACCOUNTING_NOT_CONFIGURED: no sealing key in this deployment")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not the owner; BUSINESS_WIDE_ONLY: an owner held to stores, the books being"
              + " the whole business's")
  @PUT
  @Path("/connection")
  public ApiResponse<AccountingDtos.ConnectionResponse> connect(AccountingDtos.ConnectRequest req) {
    owner();
    return ApiResponse.ok(
        AccountingMappers.toConnection(
            service.connect(ctx.requireTenantId(), ctx.requireUserId(), req)));
  }

  @Operation(
      summary = "Disconnect the package",
      description = "OWNER only. The tokens, the mapping and the log go with it.")
  @APIResponse(responseCode = "404", description = "ACCOUNTING_NOT_CONNECTED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not the owner; BUSINESS_WIDE_ONLY: an owner held to stores, the books being"
              + " the whole business's")
  @DELETE
  @Path("/connection")
  public ApiResponse<Void> disconnect() {
    owner();
    service.disconnect(ctx.requireTenantId());
    return ApiResponse.ok(null);
  }

  @Operation(
      summary = "Switch the connection off",
      description = "OWNER only. Nothing is queued or pushed until it is switched on.")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not the owner; BUSINESS_WIDE_ONLY: an owner held to stores, the books being"
              + " the whole business's")
  @POST
  @Path("/connection/disable")
  public ApiResponse<AccountingDtos.ConnectionResponse> disable() {
    owner();
    return ApiResponse.ok(
        AccountingMappers.toConnection(
            service.setEnabled(ctx.requireTenantId(), false, ctx.requireUserId())));
  }

  @Operation(
      summary = "Switch the connection on",
      description = "OWNER only. What the ledger posted meanwhile is pushed on the next pass.")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not the owner; BUSINESS_WIDE_ONLY: an owner held to stores, the books being"
              + " the whole business's")
  @POST
  @Path("/connection/enable")
  public ApiResponse<AccountingDtos.ConnectionResponse> enable() {
    owner();
    return ApiResponse.ok(
        AccountingMappers.toConnection(
            service.setEnabled(ctx.requireTenantId(), true, ctx.requireUserId())));
  }

  @Operation(
      summary = "The package's chart of accounts",
      description =
          "Management. Read from the package now — which also proves the connection works.")
  @APIResponse(
      responseCode = "502",
      description = "ACCOUNTING_PROVIDER_REFUSED: the package said no, with its words")
  @APIResponse(responseCode = "503", description = "ACCOUNTING_PROVIDER_UNREACHABLE")
  @APIResponse(
      responseCode = "409",
      description =
          "ACCOUNTING_SETTINGS_INVALID: the connection was kept with a setting the package"
              + " cannot be reached by; nothing is sent")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; BUSINESS_WIDE_ONLY: a caller held to stores, the books being"
              + " the whole business's")
  @GET
  @Path("/connection/accounts")
  public ApiResponse<List<AccountingDtos.ExternalAccountResponse>> accounts() {
    books();
    return ApiResponse.ok(
        service.accounts(ctx.requireTenantId()).stream()
            .map(AccountingMappers::toAccount)
            .toList());
  }

  @Operation(
      summary = "The mapping of nominal codes onto the package's accounts",
      description = "Management. A code not mapped is sent as itself.")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; BUSINESS_WIDE_ONLY: a caller held to stores, the books being"
              + " the whole business's")
  @GET
  @Path("/connection/mappings")
  public ApiResponse<List<AccountingDtos.MappingResponse>> mappings() {
    books();
    return ApiResponse.ok(
        service.mappings(ctx.requireTenantId()).stream()
            .map(AccountingMappers::toMapping)
            .toList());
  }

  @Operation(
      summary = "Replace the mapping",
      description =
          "Management with finance.journal. The whole mapping; what is left out is removed.")
  @APIResponse(responseCode = "400", description = "ACCOUNTING_MAPPING_INVALID")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.journal; BUSINESS_WIDE_ONLY: a"
              + " caller held to stores, the books being the whole business's")
  @PUT
  @Path("/connection/mappings")
  public ApiResponse<List<AccountingDtos.MappingResponse>> replaceMappings(
      AccountingDtos.MappingsRequest req) {
    finance();
    return ApiResponse.ok(
        service
            .replaceMappings(ctx.requireTenantId(), req == null ? List.of() : req.mappings())
            .stream()
            .map(AccountingMappers::toMapping)
            .toList());
  }

  @Operation(
      summary = "Push now",
      description =
          "Management with finance.journal. One pass: every journal posted since the day chosen is"
              + " queued, and everything due is tried. The clock does the same every half minute.")
  @APIResponse(responseCode = "409", description = "ACCOUNTING_DISABLED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.journal; BUSINESS_WIDE_ONLY: a"
              + " caller held to stores, the books being the whole business's")
  @POST
  @Path("/connection/sync")
  public ApiResponse<AccountingDtos.RunResponse> sync() {
    finance();
    return ApiResponse.ok(AccountingMappers.toRun(service.syncNow(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Every journal's push, newest first",
      description =
          "Management. Cursor on the id; status one of PENDING, DELIVERED, FAILED, UNCERTAIN, SKIPPED.")
  @APIResponse(responseCode = "400", description = "ACCOUNTING_STATUS_INVALID")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; BUSINESS_WIDE_ONLY: a caller held to stores, the books being"
              + " the whole business's")
  @GET
  @Path("/syncs")
  public ApiResponse<AccountingDtos.SyncPage> syncs(
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    books();
    AccountingService.Page page =
        service.syncs(
            ctx.requireTenantId(),
            status == null || status.isBlank()
                ? null
                : status.trim().toUpperCase(java.util.Locale.ROOT),
            after == null || after.isBlank() ? null : Parsing.uuid(after, "after"),
            limit);
    return ApiResponse.ok(
        new AccountingDtos.SyncPage(
            page.items().stream().map(AccountingMappers::toSync).toList(), page.nextCursor()));
  }

  @Operation(
      summary = "One journal's push, whole",
      description = "Management. With the journal's lines and every try.")
  @APIResponse(responseCode = "404", description = "ACCOUNTING_SYNC_NOT_FOUND")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; BUSINESS_WIDE_ONLY: a caller held to stores, the books being"
              + " the whole business's")
  @GET
  @Path("/syncs/{id}")
  public ApiResponse<AccountingDtos.SyncResponse> sync(@PathParam("id") UUID id) {
    books();
    return ApiResponse.ok(AccountingMappers.toSync(service.sync(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Try a push again, now",
      description = "Management with finance.journal. Whatever it was waiting for.")
  @APIResponse(
      responseCode = "409",
      description = "ACCOUNTING_SYNC_DELIVERED: already in the package")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.journal; BUSINESS_WIDE_ONLY: a"
              + " caller held to stores, the books being the whole business's")
  @POST
  @Path("/syncs/{id}/retry")
  public ApiResponse<AccountingDtos.SyncResponse> retry(@PathParam("id") UUID id) {
    finance();
    return ApiResponse.ok(
        AccountingMappers.toSync(service.retry(ctx.requireTenantId(), id, ctx.userId())));
  }

  @Operation(
      summary = "Say whether an uncertain push landed",
      description =
          "Management with finance.journal. For a push whose outcome was unknown: outcome LANDED"
              + " with the package's own reference records it delivered and it is never pushed"
              + " again; NOT_LANDED queues it to be tried again. Once, and kept on the row with who"
              + " decided and when.")
  @APIResponse(
      responseCode = "400",
      description =
          "ACCOUNTING_OUTCOME_INVALID, ACCOUNTING_EXTERNAL_ID_REQUIRED, ACCOUNTING_NOTE_TOO_LONG")
  @APIResponse(responseCode = "404", description = "ACCOUNTING_SYNC_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "ACCOUNTING_SYNC_NOT_UNCERTAIN")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.journal; BUSINESS_WIDE_ONLY: a"
              + " caller held to stores, the books being the whole business's")
  @POST
  @Path("/syncs/{id}/resolve")
  public ApiResponse<AccountingDtos.SyncResponse> resolve(
      @PathParam("id") UUID id, AccountingDtos.ResolveRequest req) {
    finance();
    // The request carries no Bean Validation: the service reads every field and names each
    // refusal (ACCOUNTING_OUTCOME_INVALID, ACCOUNTING_EXTERNAL_ID_REQUIRED,
    // ACCOUNTING_NOTE_TOO_LONG) before anything is written, and a missing body is an outcome
    // nobody gave.
    AccountingDtos.ResolveRequest r =
        req == null ? new AccountingDtos.ResolveRequest(null, null, null) : req;
    return ApiResponse.ok(
        AccountingMappers.toSync(
            service.resolve(
                ctx.requireTenantId(), id, ctx.userId(), r.outcome(), r.externalId(), r.note())));
  }

  @Operation(
      summary = "Leave a journal out of the package",
      description =
          "Management with finance.journal, with a reason — entered by hand, or not wanted there.")
  @APIResponse(responseCode = "400", description = "ACCOUNTING_REASON_REQUIRED")
  @APIResponse(responseCode = "409", description = "ACCOUNTING_SYNC_DELIVERED")
  @APIResponse(
      responseCode = "403",
      description =
          "FORBIDDEN: not management; PERMISSION_DENIED: no finance.journal; BUSINESS_WIDE_ONLY: a"
              + " caller held to stores, the books being the whole business's")
  @POST
  @Path("/syncs/{id}/skip")
  public ApiResponse<AccountingDtos.SyncResponse> skip(
      @PathParam("id") UUID id, AccountingDtos.SkipRequest req) {
    finance();
    // As for resolve: the service checks the reason (ACCOUNTING_REASON_REQUIRED) before it writes.
    return ApiResponse.ok(
        AccountingMappers.toSync(
            service.skip(ctx.requireTenantId(), id, req == null ? null : req.reason())));
  }

  private void management() {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
  }

  /**
   * Management, held to no store: the connection, its mapping and every push are the business's.
   */
  private void books() {
    management();
    wholeBusiness();
  }

  /**
   * Management with {@code finance.journal}, held to no store. The role first ({@code FORBIDDEN}),
   * then the permission ({@code PERMISSION_DENIED}), then the scope ({@code BUSINESS_WIDE_ONLY}): a
   * caller is told what they may not do at all before they are told where they may not do it, as on
   * every {@code /payment-runs} route.
   */
  private void finance() {
    management();
    ctx.requirePermission(Permissions.FINANCE_JOURNAL);
    wholeBusiness();
  }

  private void owner() {
    ctx.requireAnyRole("OWNER");
    wholeBusiness();
  }

  private void wholeBusiness() {
    BusinessWide.require(
        ctx,
        "The accounting connection and every journal's push are the whole business's books; they"
            + " need a caller who is not held to stores");
  }
}
