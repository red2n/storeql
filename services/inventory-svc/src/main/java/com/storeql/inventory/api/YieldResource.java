package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.YieldRun;
import com.storeql.inventory.dto.Dtos.YieldRunRequest;
import com.storeql.inventory.dto.Dtos.YieldRunsResponse;
import com.storeql.inventory.dto.Dtos.YieldTemplateRequest;
import com.storeql.inventory.mapper.Mappers;
import com.storeql.inventory.service.YieldService;
import com.storeql.web.ApiResponse;
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
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Fresh yield, preparation and butchery loss: templates are management's; a breakdown is counter
 * work, at a store the caller may act at.
 */
@RequestScoped
@Path("/admin/inventory/yield")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Yield")
public class YieldResource {

  private static final String[] MANAGEMENT = {"PLATFORM_ADMIN", "OWNER", "MANAGER"};

  @Inject YieldService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a yield template",
      description =
          "What a primal should break into: each cut's expected share of the input, its share of"
              + " the cost (by weight unless said otherwise) and its own shelf life. What the"
              + " shares leave is the expected loss. Management only.")
  @APIResponse(responseCode = "201", description = "Created")
  @APIResponse(
      responseCode = "400",
      description =
          "INVENTORY_YIELD_OUTPUTS_REQUIRED, INVENTORY_YIELD_OUTPUT_IS_INPUT,"
              + " INVENTORY_YIELD_OUTPUT_DUPLICATE, INVENTORY_YIELD_SHARES_INVALID")
  @POST
  @Path("/templates")
  public Response create(YieldTemplateRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole(MANAGEMENT);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toDto(svc.create(ctx, req))))
        .build();
  }

  @Operation(summary = "List yield templates", description = "Live ones first.")
  @APIResponse(responseCode = "200", description = "The templates")
  @GET
  @Path("/templates")
  public Response templates() {
    return Response.ok(ApiResponse.ok(svc.templates(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(summary = "Read a yield template")
  @APIResponse(responseCode = "200", description = "The template")
  @APIResponse(responseCode = "404", description = "INVENTORY_YIELD_TEMPLATE_NOT_FOUND")
  @GET
  @Path("/templates/{id}")
  public Response template(@PathParam("id") String id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.template(ctx, Ids.parse(id))))).build();
  }

  @Operation(summary = "End a yield template", description = "It breaks nothing more.")
  @APIResponse(responseCode = "200", description = "Ended")
  @APIResponse(responseCode = "404", description = "INVENTORY_YIELD_TEMPLATE_NOT_FOUND")
  @POST
  @Path("/templates/{id}/end")
  public Response end(@PathParam("id") String id) {
    ctx.requireAnyRole(MANAGEMENT);
    svc.end(ctx, Ids.parse(id));
    return Response.ok(ApiResponse.ok("ended")).build();
  }

  @Operation(
      summary = "Record a breakdown",
      description =
          "Consumes the primal at the store, makes each cut a batch of its own under the primal's"
              + " lot with the primal's cost apportioned by share, and records the loss against"
              + " what the template expected. Any staff with access to the store.")
  @APIResponse(responseCode = "201", description = "Recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "INVENTORY_YIELD_OUTPUT_UNKNOWN, INVENTORY_YIELD_OUTPUT_DUPLICATE,"
              + " INVENTORY_YIELD_OUTPUT_EXCEEDS_INPUT")
  @APIResponse(responseCode = "404", description = "INVENTORY_YIELD_TEMPLATE_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description = "INVENTORY_YIELD_TEMPLATE_ENDED, INVENTORY_YIELD_INPUT_NOT_OWNED")
  @APIResponse(responseCode = "422", description = "INVENTORY_YIELD_INSUFFICIENT_INPUT")
  @POST
  @Path("/runs")
  public Response record(YieldRunRequest req) {
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toDto(svc.record(ctx, req))))
        .build();
  }

  @Operation(
      summary = "The breakdowns of a period",
      description =
          "Newest first, at one store or all of them, with the period added up: what went in, what"
              + " came out, what was lost against what was expected, and the loss at the primal's"
              + " cost. The butchery-loss report.")
  @APIResponse(responseCode = "200", description = "The runs and their totals")
  @APIResponse(responseCode = "400", description = "INVENTORY_PERIOD_INVALID")
  @GET
  @Path("/runs")
  public Response runs(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    List<YieldRun> runs =
        svc.runs(ctx, storeId == null || storeId.isBlank() ? null : Ids.parse(storeId), from, to);
    return Response.ok(
            ApiResponse.ok(
                new YieldRunsResponse(
                    runs.stream().map(Mappers::toDto).toList(),
                    Mappers.toDto(YieldService.totals(runs)))))
        .build();
  }
}
