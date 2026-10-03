package com.storeql.pricing.api;

import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The tax summary report — the fourth and last of the reports named in the reporting gap analysis.
 *
 * <p>Lives under {@code /admin/} deliberately. {@code AdminAuthorizationFilter} gates every {@code
 * /admin/} path to PLATFORM_ADMIN / OWNER / MANAGER, which is the right audience for a tenant's tax
 * position, and gating it by construction means a future method added to this class cannot be left
 * open by forgetting a role check.
 */
@RequestScoped
@Path("/admin/reports")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Tax Summary")
public class TaxSummaryResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * The working behind the VAT return's single figures, grouped.
   *
   * <p>Totals are folded from the returned rows rather than queried separately, so the summary can
   * never disagree with its own detail. That also exposes what the return hides: Box 1 counts only
   * non-exempt supplies, so VAT sitting on a row marked exempt shows up here as {@code vatAmount}
   * differing from {@code outputVat}.
   *
   * @param from inclusive ISO-8601 lower bound on the tax point
   * @param to exclusive ISO-8601 upper bound
   * @param storeId restrict to one store — only if the caller may act there, else {@code 403
   *     STORE_ACCESS_DENIED} — or {@code null} for every store the caller may see: the whole
   *     business for a caller held to no store, else exactly the caller's own stores
   * @param groupBy {@code CODE}, {@code STORE} or {@code MONTH}; defaults to {@code CODE}
   * @return the grouped rows with folded totals and the period they cover
   * @throws com.storeql.web.ApiException {@code 400} when the period is malformed or not strictly
   *     increasing, or {@code groupBy} is not one of the three groupings; {@code 403} when {@code
   *     storeId} names a store the caller is not held to
   */
  @Operation(
      summary = "VAT collected over a period, grouped",
      description =
          "The working behind the VAT return's single figures. Group by CODE to see which rate"
              + " bands the VAT is made of, by STORE to compare sites, or by MONTH to see a rate"
              + " change or a seasonal shift. Exempt supplies are reported as their own rows,"
              + " because the return counts their net in Box 6 but their VAT nowhere. Totals"
              + " reconcile: totals.netAmount is Box 6 and totals.outputVat is Box 1, over the same"
              + " rows and the same period. A caller held to particular stores sees only those —"
              + " naming another store is refused, naming none still means only theirs, added"
              + " together, never the whole business.")
  @APIResponse(responseCode = "200", description = "Grouped rows plus reconciling totals")
  @APIResponse(
      responseCode = "400",
      description =
          "from/to missing, not ISO-8601 instants, or from is not before to; unknown groupBy;"
              + " storeId not a UUID")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER, MANAGER or PLATFORM_ADMIN")
  @APIResponse(
      responseCode = "403",
      description = "storeId names a store the caller is not held to (STORE_ACCESS_DENIED)")
  @APIResponse(
      responseCode = "503",
      description =
          "TENANT_PROFILE_UNAVAILABLE: the business's currency, whose minor units the money is"
              + " kept to, could not be read")
  @GET
  @Path("/tax-summary")
  public Response taxSummary(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId,
      @QueryParam("groupBy") String groupBy) {
    ctx.requireTenantId();
    if (from == null || from.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_FROM", "from query param required (ISO-8601)");
    if (to == null || to.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_TO", "to query param required (ISO-8601)");
    UUID requested = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(requested);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.taxSummary(ctx, from, to, stores, groupBy)),
                ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
