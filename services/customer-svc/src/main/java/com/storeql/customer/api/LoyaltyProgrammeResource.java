package com.storeql.customer.api;

import com.storeql.customer.dto.Dtos.SetLoyaltyProgrammeRequest;
import com.storeql.customer.mapper.Mappers;
import com.storeql.customer.service.LoyaltyProgrammeService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The business's loyalty programme (13.x): its tiers and their benefits, how long a point lives,
 * how many months of earning count towards a tier. Management only, under {@code /admin}.
 */
@Path("/admin/loyalty")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Loyalty")
public class LoyaltyProgrammeResource {

  @Inject LoyaltyProgrammeService service;
  @Inject TenantContext ctx;

  /**
   * The programme in force: the business's own, or the platform's default when it never set one.
   *
   * @return the programme
   */
  @Operation(
      summary = "The loyalty programme",
      description =
          "Tiers (name, qualifying points threshold, earn multiplier), months a point lives"
              + " (absent: never), months of earning that count towards a tier (absent: a"
              + " lifetime). `isDefault` when the business never set one. Management only.")
  @APIResponse(responseCode = "200", description = "The programme")
  @GET
  @Path("/programme")
  public ApiResponse<?> programme() {
    return ApiResponse.ok(
        Mappers.toProgramme(service.programme(ctx)), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Sets the programme. Open lots take the new expiry with a month's notice; every account is
   * re-tiered from what qualifies now.
   *
   * @param req the tiers, months and reason
   * @return the programme as saved
   * @throws com.storeql.web.ApiException {@code 400 LOYALTY_TIERS_INVALID}, {@code 400
   *     LOYALTY_EXPIRY_INVALID}
   */
  @Operation(
      summary = "Set the loyalty programme",
      description =
          "One to six tiers, the first at zero, ascending thresholds, each a distinct name and a"
              + " multiplier from 1 to 10; expiryMonths 1–120 or absent for never; qualifyingMonths"
              + " 1–36 or absent for a lifetime; a reason. Points already held take the new expiry,"
              + " never sooner than thirty days from now. Management only.")
  @APIResponse(responseCode = "200", description = "Programme saved")
  @APIResponse(responseCode = "400", description = "A shape that cannot be honoured, by name")
  @PUT
  @Path("/programme")
  public ApiResponse<?> set(SetLoyaltyProgrammeRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toProgramme(service.set(ctx, req)), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Runs the expiry and re-tiering sweep for this business now, rather than waiting for the hour.
   *
   * @return what it did
   */
  @Operation(
      summary = "Run the loyalty sweep now",
      description =
          "Writes off every lot that has died (one EXPIRE entry per customer, announced as"
              + " LoyaltyExpired) and re-tiers every account from what qualifies today. The sweeper"
              + " does this hourly; this is for the business that wants it now. Management only.")
  @APIResponse(
      responseCode = "200",
      description = "Customers and points expired, accounts re-tiered")
  @POST
  @Path("/expiry/run")
  public ApiResponse<?> runExpiry() {
    return ApiResponse.ok(
        Mappers.toExpiryRun(service.sweep(ctx)), ApiResponse.Meta.of(ctx.requestId()));
  }
}
