package com.storeql.notification.api;

import com.storeql.notification.domain.Webhooks;
import com.storeql.notification.dto.WebhookDtos;
import com.storeql.notification.mapper.WebhookMappers;
import com.storeql.notification.service.WebhookService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
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
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A business's webhooks (22.6): the owner registers, changes, rotates and removes endpoints; an
 * owner or a manager reads them, reads the delivery log, pings an endpoint and sends a delivery
 * again. The secret is in the answer to the registering and to a rotation, and nowhere else.
 */
@Path("/admin/webhooks")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks")
public class WebhookResource {

  @Inject WebhookService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The event types a business may subscribe to",
      description = "OWNER or MANAGER. Each with a line about what it means.")
  @GET
  @Path("/events")
  public ApiResponse<List<WebhookDtos.EventTypeResponse>> events() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(Webhooks.CATALOGUE.stream().map(WebhookMappers::toDto).toList());
  }

  @Operation(
      summary = "Register an endpoint",
      description =
          "OWNER only. HTTPS on a public address, a description, and the event types wanted. The"
              + " answer carries the signing secret, once.")
  @APIResponse(responseCode = "201", description = "The endpoint, with its secret")
  @APIResponse(
      responseCode = "400",
      description =
          "WEBHOOK_URL_INVALID, WEBHOOK_DESCRIPTION_INVALID, WEBHOOK_EVENTS_EMPTY, WEBHOOK_EVENT_UNKNOWN")
  @POST
  @Path("/endpoints")
  public Response register(WebhookDtos.CreateRequest req) {
    ctx.requireAnyRole("OWNER");
    WebhookService.Made made = service.register(ctx.requireTenantId(), ctx.requireUserId(), req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                WebhookDtos.CreatedResponse.of(
                    WebhookMappers.toDto(made.endpoint()), made.secret())))
        .build();
  }

  @Operation(
      summary = "The business's endpoints",
      description = "OWNER or MANAGER. Never the secret.")
  @GET
  @Path("/endpoints")
  public ApiResponse<List<WebhookDtos.EndpointResponse>> list() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        service.list(ctx.requireTenantId()).stream().map(WebhookMappers::toDto).toList());
  }

  @GET
  @Path("/endpoints/{id}")
  public ApiResponse<WebhookDtos.EndpointResponse> get(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(WebhookMappers.toDto(service.get(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Change an endpoint",
      description =
          "OWNER only. Any of url, description, events, enabled; the rest stay. enabled: true also"
              + " clears a switch-off and its run of failures.")
  @PUT
  @Path("/endpoints/{id}")
  public ApiResponse<WebhookDtos.EndpointResponse> update(
      @PathParam("id") UUID id, WebhookDtos.UpdateRequest req) {
    ctx.requireAnyRole("OWNER");
    return ApiResponse.ok(WebhookMappers.toDto(service.update(ctx.requireTenantId(), id, req)));
  }

  @Operation(
      summary = "Remove an endpoint",
      description = "OWNER only. Its delivery log goes with it.")
  @DELETE
  @Path("/endpoints/{id}")
  public ApiResponse<String> delete(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER");
    service.delete(ctx.requireTenantId(), id);
    return ApiResponse.ok("removed");
  }

  @Operation(
      summary = "Rotate the signing secret",
      description = "OWNER only. The new secret, shown once; the old one signs nothing more.")
  @POST
  @Path("/endpoints/{id}/secret")
  public ApiResponse<WebhookDtos.SecretResponse> rotate(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER");
    return ApiResponse.ok(
        new WebhookDtos.SecretResponse(service.rotateSecret(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Send a test delivery",
      description = "OWNER or MANAGER. A Ping, signed like any delivery, queued now.")
  @APIResponse(responseCode = "202", description = "Queued; the delivery to watch")
  @POST
  @Path("/endpoints/{id}/ping")
  public Response ping(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID delivery = service.ping(ctx.requireTenantId(), id);
    return Response.accepted()
        .entity(ApiResponse.ok(new WebhookDtos.PingResponse(delivery.toString())))
        .build();
  }

  @Operation(
      summary = "The delivery log",
      description =
          "OWNER or MANAGER. Newest first; narrowed to an endpoint and to PENDING, DELIVERED or"
              + " DEAD when asked. Cursor-paginated on the delivery's id.")
  @GET
  @Path("/deliveries")
  public ApiResponse<WebhookDtos.DeliveryPage> deliveries(
      @QueryParam("endpointId") String endpointId,
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    WebhookService.Page page =
        service.deliveries(
            ctx.requireTenantId(),
            Parsing.optionalUuid(endpointId, "endpointId"),
            status == null || status.isBlank() ? null : status.trim(),
            Parsing.optionalUuid(after, "after"),
            limit);
    return ApiResponse.ok(
        new WebhookDtos.DeliveryPage(
            page.items().stream().map(WebhookMappers::toDto).toList(), page.nextCursor()));
  }

  @Operation(summary = "A delivery, with what was sent and every try")
  @GET
  @Path("/deliveries/{id}")
  public ApiResponse<WebhookDtos.DeliveryDetail> delivery(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    WebhookService.Detail detail = service.delivery(ctx.requireTenantId(), id);
    return ApiResponse.ok(WebhookMappers.toDto(detail.delivery(), detail.attempts()));
  }

  @Operation(
      summary = "Send a delivery again",
      description =
          "OWNER or MANAGER. Queued now, whatever state it was in; the tries so far stay.")
  @POST
  @Path("/deliveries/{id}/redeliver")
  public ApiResponse<WebhookDtos.DeliveryResponse> redeliver(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(WebhookMappers.toDto(service.redeliver(ctx.requireTenantId(), id)));
  }
}
