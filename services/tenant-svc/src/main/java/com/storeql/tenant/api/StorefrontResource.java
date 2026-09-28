package com.storeql.tenant.api;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.Store;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.dto.Dtos.StorefrontConfigResponse;
import com.storeql.tenant.service.TenantService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public storefront config. The guest online shop needs to know per-store display rules (e.g.
 * whether to show prices). Tenant comes from {@code X-Tenant-Id}, which the gateway resolves from
 * the storefront's domain (here, the {@code X-Storefront-Tenant} dev seam). Read-only, no identity
 * required — reachable via the gateway's storefront whitelist.
 */
@Path("/storefront")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Storefront")
public class StorefrontResource {

  @Inject TenantService service;
  @Inject com.storeql.tenant.service.ObligationService obligations;
  @Inject TenantContext ctx;

  /**
   * Display rules for the guest online shop: price visibility, enabled tenders, address.
   *
   * <p>Needs no identity — the tenant comes from the storefront domain the gateway resolved, so a
   * guest browsing the shop can read it.
   *
   * @param store the store to read config for, as {@code ?store=<storeId>}; required
   * @return the store's public storefront configuration
   * @throws ApiException {@code STORE_REQUIRED} or {@code INVALID_STORE} (400) when the parameter
   *     is missing or not a UUID; {@code 404} when no such store exists in the tenant
   */
  @Operation(
      summary = "Get a store's public storefront config",
      description =
          "Display rules for the guest online shop (price visibility, enabled payment methods,"
              + " address). No identity required.")
  @APIResponse(responseCode = "400", description = "store query parameter missing or not a UUID")
  @APIResponse(responseCode = "404", description = "No such store")
  @GET
  @Path("/config")
  public ApiResponse<StorefrontConfigResponse> config(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    if (store == null || store.isBlank()) {
      throw ApiException.badRequest("STORE_REQUIRED", "store query parameter is required");
    }
    UUID storeId;
    try {
      storeId = Ids.parse(store.trim());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_STORE", "store must be a UUID", java.util.List.of(), e);
    }
    Store s = service.getStore(tenantId, storeId);
    return ApiResponse.ok(toStorefrontConfig(s, service.getTenant(tenantId)));
  }

  /**
   * Whether this tenant may currently transact — the gateway calls this (cached) to gate storefront
   * browsing and checkout, so a deactivated business's online shop stops serving. Tenant comes from
   * {@code X-Tenant-Id} (gateway sets it from the storefront domain/header).
   *
   * @return a single flag: whether the tenant's status is ACTIVE
   * @throws ApiException {@code TENANT_NOT_FOUND} (404) when the tenant does not exist
   */
  @Operation(
      summary = "Check whether the tenant may currently transact",
      description =
          "Gateway calls this (cached) to gate storefront browsing and checkout, so a deactivated"
              + " business's online shop stops serving.")
  @APIResponse(
      responseCode = "200",
      description = "A single flag: whether the tenant's status is ACTIVE")
  @GET
  @Path("/active")
  public ApiResponse<TenantActiveResponse> active() {
    UUID tenantId = ctx.requireTenantId();
    var t = service.getTenant(tenantId);
    return ApiResponse.ok(new TenantActiveResponse("ACTIVE".equalsIgnoreCase(t.status())));
  }

  /**
   * Minimal active-flag projection for the gateway's storefront suspension gate.
   *
   * @param active true if the tenant's status is ACTIVE
   */
  @Schema(
      name = "TenantActiveResponse",
      description = "Minimal active-flag projection for the gateway's storefront suspension gate.")
  public record TenantActiveResponse(
      @Schema(description = "True if the tenant's status is ACTIVE.") boolean active) {}

  /**
   * The tenant's ACTIVE stores, for the storefront's store switcher.
   *
   * <p>Closed and suspended stores are filtered out, so a shopper is never offered one that cannot
   * take an order.
   *
   * @return the active stores' public storefront configurations
   */
  @Operation(
      summary = "List active stores for the tenant",
      description = "Powers the storefront's store switcher.")
  @APIResponse(
      responseCode = "200",
      description = "The active stores' public storefront configurations")
  @GET
  @Path("/stores")
  public ApiResponse<List<StorefrontConfigResponse>> stores() {
    UUID tenantId = ctx.requireTenantId();
    Tenant tenant = service.getTenant(tenantId);
    List<StorefrontConfigResponse> items =
        service.listStores(tenantId).stream()
            .filter(s -> "ACTIVE".equalsIgnoreCase(s.status()))
            .map(s -> toStorefrontConfig(s, tenant))
            .toList();
    return ApiResponse.ok(items);
  }

  /**
   * A store's public configuration, with the deposit return scheme in force where it trades, in the
   * business's currency (09.16): what a shopper is told a drink's deposit will be. Named for the
   * business it belongs to — the tenant whose storefront is asked, which the caller passes.
   */
  private StorefrontConfigResponse toStorefrontConfig(Store s, Tenant tenant) {
    String currency = tenant.currency();
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
    var scheme =
        s.country() == null
            ? java.util.Optional.<com.storeql.tenant.domain.Domain.DepositScheme>empty()
            : obligations.depositScheme(s.country(), currency, today);
    return new StorefrontConfigResponse(
        s.id().toString(),
        s.name(),
        s.status(),
        s.showPrices(),
        com.storeql.tenant.mapper.Mappers.paymentMethodsList(s.enabledPaymentMethods()),
        s.line1(),
        s.city(),
        s.country(),
        s.pincode(),
        null,
        scheme.map(d -> com.storeql.tenant.mapper.Mappers.toDepositScheme(d, today)).orElse(null),
        s.type(),
        !Store.TYPE_DARK_STORE.equals(s.type()),
        tenant.businessName(),
        s.tillPhone());
  }
}
