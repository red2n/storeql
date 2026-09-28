package com.storeql.purchase.api;

import com.storeql.purchase.dto.AccountingDtos;
import com.storeql.purchase.mapper.AccountingMappers;
import com.storeql.purchase.service.AccountingService;
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
  @GET
  @Path("/connection")
  public ApiResponse<AccountingDtos.ConnectionResponse> connection() {
    management();
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
  @GET
  @Path("/connection/accounts")
  public ApiResponse<List<AccountingDtos.ExternalAccountResponse>> accounts() {
    management();
    return ApiResponse.ok(
        service.accounts(ctx.requireTenantId()).stream()
            .map(AccountingMappers::toAccount)
            .toList());
  }

  @Operation(
      summary = "The mapping of nominal codes onto the package's accounts",
      description = "Management. A code not mapped is sent as itself.")
  @GET
  @Path("/connection/mappings")
  public ApiResponse<List<AccountingDtos.MappingResponse>> mappings() {
    management();
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
  @GET
  @Path("/syncs")
  public ApiResponse<AccountingDtos.SyncPage> syncs(
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    management();
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
  @GET
  @Path("/syncs/{id}")
  public ApiResponse<AccountingDtos.SyncResponse> sync(@PathParam("id") UUID id) {
    management();
    return ApiResponse.ok(AccountingMappers.toSync(service.sync(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Try a push again, now",
      description = "Management with finance.journal. Whatever it was waiting for.")
  @APIResponse(
      responseCode = "409",
      description = "ACCOUNTING_SYNC_DELIVERED: already in the package")
  @POST
  @Path("/syncs/{id}/retry")
  public ApiResponse<AccountingDtos.SyncResponse> retry(@PathParam("id") UUID id) {
    finance();
    return ApiResponse.ok(AccountingMappers.toSync(service.retry(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Leave a journal out of the package",
      description =
          "Management with finance.journal, with a reason — entered by hand, or not wanted there.")
  @APIResponse(responseCode = "400", description = "ACCOUNTING_REASON_REQUIRED")
  @APIResponse(responseCode = "409", description = "ACCOUNTING_SYNC_DELIVERED")
  @POST
  @Path("/syncs/{id}/skip")
  public ApiResponse<AccountingDtos.SyncResponse> skip(
      @PathParam("id") UUID id, AccountingDtos.SkipRequest req) {
    finance();
    return ApiResponse.ok(
        AccountingMappers.toSync(
            service.skip(ctx.requireTenantId(), id, req == null ? null : req.reason())));
  }

  private void management() {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
  }

  private void finance() {
    management();
    ctx.requirePermission(Permissions.FINANCE_JOURNAL);
  }

  private void owner() {
    ctx.requireAnyRole("OWNER");
  }
}
