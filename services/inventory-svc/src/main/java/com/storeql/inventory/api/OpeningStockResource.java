package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.inventory.dto.OpeningStockDtos.OpeningStockRequest;
import com.storeql.inventory.service.OpeningStockService;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Opening stock: what a business already holds when it starts, loaded from a catalogue import. */
@RequestScoped
@Path("/admin/inventory/opening-stock")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Opening Stock")
public class OpeningStockResource {

  @Inject OpeningStockService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Open a store's stock",
      description =
          "Opens up to 500 items at a store, once each, as received batches through the same door"
              + " as a delivery (a recall holds a recalled lot, directed putaway places the batch)."
              + " Management only. Asking again for the same job writes nothing more; an item"
              + " another job opened is answered ALREADY_OPENED and left alone.")
  @APIResponse(responseCode = "200", description = "One outcome per line")
  @APIResponse(responseCode = "400", description = "A line is wrong anywhere: nothing is written")
  @APIResponse(responseCode = "404", description = "INVENTORY_STORE_NOT_FOUND")
  @POST
  public Response open(OpeningStockRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(svc.load(ctx, req))).build();
  }

  @Operation(
      summary = "What a job opened",
      description = "Lines, quantity and value by store, for the import's reconciliation.")
  @APIResponse(responseCode = "200", description = "The totals; no stores when the job opened none")
  @GET
  @Path("/{jobId}")
  public Response summary(@PathParam("jobId") String jobId) {
    return Response.ok(ApiResponse.ok(svc.summary(ctx, Ids.parse(jobId)))).build();
  }
}
