package com.storeql.purchase.api;

import com.storeql.purchase.domain.Domain.DutyRelease;
import com.storeql.purchase.dto.Dtos.DutyReleasesResponse;
import com.storeql.purchase.mapper.Mappers;
import com.storeql.purchase.service.DutyService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Excise duty owed on releases from bond. Management only. */
@RequestScoped
@Path("/admin/duty")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Excise Duty")
public class DutyResource {

  @Inject DutyService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The releases from bond of a period and the duty they owe",
      description =
          "Each release inventory-svc announced, recorded once and posted Dr Excise Duty / Cr Excise"
              + " Duty Payable the day it left bond; the total is what an excise return is made"
              + " from.")
  @APIResponse(responseCode = "200", description = "The releases and their total duty")
  @APIResponse(responseCode = "400", description = "PURCHASE_DUTY_PERIOD_INVALID")
  @GET
  @Path("/releases")
  public Response releases(@QueryParam("from") String from, @QueryParam("to") String to) {
    List<DutyRelease> releases = svc.releases(ctx, from, to);
    BigDecimal total = BigDecimal.ZERO;
    for (DutyRelease r : releases) total = total.add(r.dutyAmount());
    return Response.ok(
            ApiResponse.ok(
                new DutyReleasesResponse(
                    releases.stream().map(Mappers::toDto).toList(), total, svc.currency(ctx))))
        .build();
  }
}
