package com.storeql.notification.api;

import com.storeql.ids.Ids;
import com.storeql.notification.dto.Dtos.ChannelStatusResponse;
import com.storeql.notification.mapper.Mappers;
import com.storeql.notification.service.NotificationService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Thin JAX-RS resource for the back-office alert and notification feeds — validate, delegate to
 * {@link NotificationService}, wrap in envelope. No logic here.
 */
@Path("/admin/notifications")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Notifications")
public class AdminResource {

  @Inject NotificationService service;
  @Inject TenantContext ctx;
  @Inject com.storeql.notification.channel.Channels channelRouter;

  /**
   * List shortage alerts for the tenant, newest first.
   *
   * <p>A caller held to stores reads those stores' alerts and no others: a store they name must be
   * one of theirs, and with none named (or a variant named) they read exactly their own stores
   * together, never the whole business's.
   *
   * @param storeId restrict to one store, or {@code null} for every store the caller reads; ignored
   *     when {@code variantId} is given
   * @param variantId restrict to one variant across the stores the caller reads, or {@code null}
   * @param limit page size; values outside 1..100 fall back to 20 rather than being rejected
   * @return the matching alerts as DTOs
   * @throws com.storeql.web.ApiException {@code 403 STORE_ACCESS_DENIED} for a store the caller is
   *     not held to
   */
  @Operation(
      summary = "List shortage alerts",
      description =
          "Paginated shortage alerts for the caller's tenant, optionally filtered by store or"
              + " variant. Recorded from consumed StockBelowThreshold events, newest first. A"
              + " manager held to stores reads only those stores' alerts.")
  @APIResponse(responseCode = "200", description = "Shortage alerts")
  @APIResponse(responseCode = "400", description = "storeId or variantId is not a valid UUID")
  @APIResponse(responseCode = "403", description = "STORE_ACCESS_DENIED: a store not the caller's")
  @GET
  @Path("/shortage-alerts")
  public ApiResponse<Object> listShortageAlerts(
      @QueryParam("storeId") String storeId,
      @QueryParam("variantId") String variantId,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID tenantId = ctx.requireTenantId();
    int effectiveLimit = (limit < 1 || limit > 100) ? 20 : limit;
    UUID named = storeId != null ? Ids.parse(storeId) : null;
    // A named store must be one the caller keeps; with none named, a caller held to stores reads
    // exactly those together and one held to none reads every store (null).
    Set<UUID> stores = ctx.reportStores(variantId != null ? null : named);

    var alerts =
        variantId != null
            ? service.listAlertsByVariant(tenantId, Ids.parse(variantId), stores, effectiveLimit)
            : service.listAlerts(tenantId, stores, effectiveLimit);

    var dtos = alerts.stream().map(Mappers::toDto).toList();
    return ApiResponse.ok(dtos);
  }

  /**
   * In-app notifications feed (welcome / order-confirmation / …) for the tenant, newest first.
   *
   * @param recipient restrict to one recipient, or {@code null} for the whole tenant feed
   * @param limit page size; values outside 1..100 fall back to 20 rather than being rejected
   * @return the matching notifications as DTOs
   */
  @Operation(
      summary = "List in-app notifications",
      description =
          "In-app notifications feed (welcome / order-confirmation / shortage alert / …) for the"
              + " caller's tenant, newest first, optionally filtered by recipient.")
  @APIResponse(responseCode = "200", description = "Notifications")
  @GET
  public ApiResponse<Object> listNotifications(
      @QueryParam("recipient") String recipient,
      @QueryParam("channel") String channel,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID tenantId = ctx.requireTenantId();
    int effectiveLimit = (limit < 1 || limit > 100) ? 20 : limit;
    var notifications =
        service.listNotifications(
            tenantId,
            recipient != null && !recipient.isBlank() ? recipient : null,
            channel != null && !channel.isBlank()
                ? channel.trim().toUpperCase(java.util.Locale.ROOT)
                : null,
            effectiveLimit);
    return ApiResponse.ok(notifications.stream().map(Mappers::toDto).toList());
  }

  @Operation(
      summary = "The channels this deployment can send on",
      description =
          "EMAIL (the configured default channel), SMS and PUSH, each with the provider behind it"
              + " and whether that provider is configured (13.7). Management-only by path.")
  @APIResponse(responseCode = "200", description = "One row per channel")
  @GET
  @Path("/channels")
  public ApiResponse<Object> channels() {
    ctx.requireTenantId();
    var sms = channelRouter.sms().provider();
    var push = channelRouter.push().provider();
    return ApiResponse.ok(
        java.util.List.of(
            new ChannelStatusResponse("EMAIL", channelRouter.configured().name(), true),
            new ChannelStatusResponse(
                "SMS", sms == null ? "NONE" : sms.name(), sms != null && sms.isConfigured()),
            new ChannelStatusResponse(
                "PUSH", push == null ? "NONE" : push.name(), push != null && push.isConfigured())));
  }
}
