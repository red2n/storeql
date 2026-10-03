package com.storeql.pricing.api;

import com.storeql.pricing.dto.Dtos;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.MarkdownService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Date-code markdown (05.4) and the ladder that plans it (03.9). Not under {@code /admin/}, so a
 * storekeeper can sticker a counter; every method states its own roles.
 */
@RequestScoped
@Path("/markdowns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Markdowns")
public class MarkdownResource {

  @Inject MarkdownService svc;
  @Inject TenantContext ctx;

  private static LocalDate today() {
    return Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
  }

  @Operation(
      summary = "The markdown ladder at a store",
      description =
          "How much off at how many days to expiry: the store's own steps, the business's, or the"
              + " default (3 days 25 %, 1 day 50 %, the day itself 75 %) — source says which."
              + " Warehouse and management roles.")
  @APIResponse(responseCode = "200", description = "The ladder")
  @GET
  @Path("/ladder")
  public Response ladder(@QueryParam("storeId") String storeId) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER");
    UUID store = Parsing.optionalUuid(storeId, "storeId");
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.ladder(ctx.requireTenantId(), store))))
        .build();
  }

  @Operation(
      summary = "Set the markdown ladder",
      description =
          "Replaces the steps for one store (storeId) or the business (no storeId). Management-only:"
              + " how much margin the counter may give away is a policy.")
  @APIResponse(responseCode = "200", description = "The ladder as it now stands")
  @APIResponse(responseCode = "400", description = "Two steps at the same day, or a bad percentage")
  @PUT
  @Path("/ladder")
  public Response setLadder(Dtos.SetMarkdownLadderRequest req) {
    Validations.validate(req);
    UUID store = Parsing.optionalUuid(req.storeId(), "storeId");
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.setLadder(ctx, store, req.steps()))))
        .build();
  }

  @Operation(
      summary = "What to sticker this morning",
      description =
          "Every batch at the store expiring within the horizon (default 7 days), from"
              + " inventory-svc, with its current POS price, the ladder step for its days to go,"
              + " the suggested price, and the live markdown already on it if any."
              + " inventoryReachable is false when inventory-svc could not be read. Warehouse and"
              + " management roles, assigned to the store.")
  @APIResponse(responseCode = "200", description = "The plan")
  @GET
  @Path("/plan")
  public Response plan(
      @QueryParam("storeId") String storeId,
      @QueryParam("withinDays") @DefaultValue("7") int withinDays) {
    UUID store = Parsing.uuid(storeId, "storeId");
    var plan = svc.plan(ctx, store, withinDays);
    LocalDate today = today();
    return Response.ok(
            ApiResponse.ok(
                new Dtos.MarkdownPlanResponse(
                    store,
                    withinDays,
                    plan.ladder().source(),
                    plan.inventoryReachable(),
                    plan.suggestions().stream().map(s -> Mappers.toDto(s, today)).toList())))
        .build();
  }

  @Operation(
      summary = "Sticker a batch at a lower price",
      description =
          "Records the markdown and issues the sticker's barcode (EAN-13, prefix 21, the reduced"
              + " price in the code in the currency's own minor units, five digits: up to 999.99"
              + " in pounds or euros, ¥99,999, KWD 99.999), which the till scans to sell at that"
              + " price. percentOff or markdownPrice, not both, against the current POS price; a"
              + " reduced price from a percentage is rounded to the currency's minor units, and a"
              + " typed markdownPrice finer than them is refused. Warehouse and management roles,"
              + " assigned to the store.")
  @APIResponse(responseCode = "201", description = "The markdown, with its sticker code")
  @APIResponse(
      responseCode = "400",
      description =
          "PRICING_MARKDOWN_REASON_UNKNOWN, PRICING_MARKDOWN_AMOUNT_REQUIRED,"
              + " PRICING_MARKDOWN_AMOUNT_AMBIGUOUS, PRICING_MARKDOWN_NOT_A_REDUCTION,"
              + " PRICING_MARKDOWN_EXPIRED_DATE; PRICING_MARKDOWN_LABEL_RANGE for a price past"
              + " five digits of the currency's minor units; VALIDATION_FAILED for a markdownPrice"
              + " with more decimals than the currency has")
  @APIResponse(responseCode = "404", description = "The variant has no POS price")
  @POST
  public Response create(Dtos.CreateMarkdownRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.create(ctx, req), today())))
        .build();
  }

  @Operation(
      summary = "A store's markdowns",
      description =
          "Newest first; ?status=ACTIVE|EXPIRED|CANCELLED. Warehouse and management roles.")
  @APIResponse(responseCode = "200", description = "The markdowns")
  @GET
  public Response list(@QueryParam("storeId") String storeId, @QueryParam("status") String status) {
    UUID store = Parsing.uuid(storeId, "storeId");
    LocalDate today = today();
    return Response.ok(
            ApiResponse.ok(
                svc.list(ctx, store, status).stream().map(m -> Mappers.toDto(m, today)).toList()))
        .build();
  }

  @Operation(summary = "One markdown", description = "Another tenant's is 404.")
  @APIResponse(responseCode = "200", description = "The markdown")
  @APIResponse(responseCode = "404", description = "Not this business's")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.get(ctx, id), today()))).build();
  }

  @Operation(
      summary = "Take the stickers off",
      description =
          "Cancels a live markdown with a reason: the code stops scanning; what already sold at"
              + " the price stays sold. Warehouse and management roles.")
  @APIResponse(responseCode = "200", description = "The markdown, cancelled")
  @APIResponse(responseCode = "409", description = "Not active")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id, Dtos.CancelMarkdownRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.cancel(ctx, id, req.reason()), today())))
        .build();
  }
}
