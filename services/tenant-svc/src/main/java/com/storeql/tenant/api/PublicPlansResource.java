package com.storeql.tenant.api;

import com.storeql.tenant.dto.PlanDtos;
import com.storeql.tenant.mapper.PlanMappers;
import com.storeql.tenant.service.PlanService;
import com.storeql.web.ApiResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The price list a prospect reads before signing up (21.13): the plans on sale to the public, what
 * they cost, what they include and how long their trial is.
 *
 * <p>No identity is asked for, deliberately: a prospect has none yet. Three things have to agree
 * for this to be reachable — this route, the gateway's public-path list, and the fact that nothing
 * here is under {@code /admin}. What it answers is what the platform already publishes on its plan
 * cards; a plan sold by hand ({@code isPublic} false) and a draft are not on it.
 */
@Path("/plans")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Plans")
public class PublicPlansResource {

  @Inject PlanService plans;

  @Operation(
      summary = "The plans on sale, for a prospect",
      description =
          "Unauthenticated. Every plan the platform sells to the public, with its prices, what it"
              + " includes and its trial in days; the default plan is the one a business lands on"
              + " when it chooses none at signup.")
  @APIResponse(responseCode = "200", description = "The plans on sale")
  @GET
  public ApiResponse<List<PlanDtos.PlanResponse>> onSale() {
    return ApiResponse.ok(plans.published().stream().map(PlanMappers::toDto).toList());
  }
}
