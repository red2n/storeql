package com.storeql.pricing.api;

import com.storeql.ids.Ids;
import com.storeql.pricing.dto.Dtos.CreateRepricingRuleRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.RepricingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.Permissions;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Competitor-driven repricing (03.x): rules on a price list, the proposals a run makes, and the
 * decisions on them. A run proposes; a person applies. Management only.
 */
@RequestScoped
@Path("/admin/repricing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Repricing")
public class RepricingResource {

  @Inject RepricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a repricing rule",
      description =
          "How one price list answers its rivals: match the lowest fresh rival price, or undercut"
              + " it by a percentage or an amount; rounded to a .99 or not; never below a floor"
              + " that is a share of the current price — pricing-svc holds no cost, so the floor"
              + " protects the price, not a margin. The rule's zone is its list's.")
  @APIResponse(responseCode = "201", description = "Rule created")
  @APIResponse(
      responseCode = "400",
      description =
          "PRICING_LIST_UNKNOWN, REPRICING_STRATEGY_INVALID, REPRICING_VALUE_INVALID,"
              + " REPRICING_ROUNDING_INVALID, REPRICING_MAX_AGE_INVALID")
  @APIResponse(responseCode = "409", description = "REPRICING_RULE_NAME_EXISTS")
  @POST
  @Path("/rules")
  public Response createRule(CreateRepricingRuleRequest req) {
    Validations.validate(req);
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createRule(ctx, req))))
        .build();
  }

  @Operation(summary = "List repricing rules")
  @APIResponse(responseCode = "200", description = "The rules")
  @GET
  @Path("/rules")
  public Response listRules() {
    return Response.ok(ApiResponse.ok(svc.listRules(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Run a repricing rule",
      description =
          "For every variant priced on the rule's list that a rival has been seen for within the"
              + " rule's age limit: the lowest fresh rival price through the rule's arithmetic. A"
              + " change opens a proposal (or refreshes the open one); nothing is charged until a"
              + " proposal is applied.")
  @APIResponse(responseCode = "200", description = "What the run examined and proposed")
  @APIResponse(responseCode = "404", description = "REPRICING_RULE_NOT_FOUND")
  @POST
  @Path("/rules/{id}/run")
  public Response run(@PathParam("id") String id) {
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.run(ctx, Ids.parse(id))))).build();
  }

  @Operation(
      summary = "List repricing proposals",
      description = "Open proposals by default; ?status=APPLIED or DISMISSED for the decided ones.")
  @APIResponse(responseCode = "200", description = "The proposals, newest first")
  @GET
  @Path("/proposals")
  public Response listProposals(
      @QueryParam("status") String status, @QueryParam("limit") Integer limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.listProposals(ctx, status, Cursor.clampLimit(limit)).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Apply a proposal",
      description =
          "The proposed price becomes the list's single-unit price for the variant, with the"
              + " price-changed event every price write raises. A proposal is decided once.")
  @APIResponse(responseCode = "200", description = "The proposal, applied")
  @APIResponse(responseCode = "404", description = "REPRICING_PROPOSAL_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "REPRICING_PROPOSAL_DECIDED")
  @POST
  @Path("/proposals/{id}/apply")
  public Response apply(@PathParam("id") String id) {
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.apply(ctx, Ids.parse(id))))).build();
  }

  @Operation(summary = "Dismiss a proposal", description = "Leaves the price as it is.")
  @APIResponse(responseCode = "200", description = "The proposal, dismissed")
  @APIResponse(responseCode = "404", description = "REPRICING_PROPOSAL_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "REPRICING_PROPOSAL_DECIDED")
  @POST
  @Path("/proposals/{id}/dismiss")
  public Response dismiss(@PathParam("id") String id) {
    ctx.requirePermission(Permissions.PRICING_WRITE);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.dismiss(ctx, Ids.parse(id))))).build();
  }
}
