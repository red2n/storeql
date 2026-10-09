package com.storeql.payment.api;

import com.storeql.ids.Ids;
import com.storeql.payment.dto.CardSettingsDtos.StandaloneCardRequest;
import com.storeql.payment.service.CardSettingsService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** The till's card rule per store: may a standalone card machine be recorded here? */
@Path("/admin/payments/stores/{storeId}/standalone-card")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Card rule")
public class CardSettingsResource {

  @Inject CardSettingsService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Read whether a store may take cards on a standalone machine",
      description =
          "With the last 30 days' CARD tenders and how many were typed from a standalone machine's"
              + " receipt, and the changes to the setting (who, when).")
  @APIResponse(responseCode = "200", description = "The setting")
  @APIResponse(responseCode = "404", description = "STORE_NOT_FOUND")
  @GET
  public Response read(@PathParam("storeId") String storeId) {
    return Response.ok(ApiResponse.ok(svc.get(ctx, Ids.parse(storeId)))).build();
  }

  @Operation(
      summary = "Allow or forbid a standalone card machine at a store",
      description =
          "Owner only (a manager cannot loosen a fraud control). Where a store has a registered card"
              + " machine, a typed CARD tender is refused (PAYMENT_CARD_NEEDS_TERMINAL) unless this is on;"
              + " wherever a typed CARD tender is taken, the machine's receipt reference is required."
              + " Every change is kept with who made it and when.")
  @APIResponse(responseCode = "200", description = "The setting as it now stands")
  @APIResponse(responseCode = "403", description = "Not an owner")
  @APIResponse(responseCode = "404", description = "STORE_NOT_FOUND")
  @PUT
  public Response set(@PathParam("storeId") String storeId, StandaloneCardRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(svc.set(ctx, Ids.parse(storeId), req.allowed()))).build();
  }
}
