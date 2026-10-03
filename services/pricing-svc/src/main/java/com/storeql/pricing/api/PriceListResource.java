package com.storeql.pricing.api;

import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reading price lists and the prices on them.
 *
 * <p>Read-only. Creating a price list and writing prices onto it moved to {@link
 * AdminPriceListResource} under {@code /admin/}: this path is outside {@code /admin/}, so the
 * mutation tier of {@code AdminAuthorizationFilter} asked only for some staff role, and a CASHIER
 * could therefore set what customers are charged. The reads stay here because the POS and the
 * storefront need them and neither runs as management.
 */
@RequestScoped
@Path("/price-lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Lists")
public class PriceListResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /** List price lists. Cursor-paginated: {@code ?after=<meta.nextCursor>&limit=1-100}. */
  /**
   * Cursor-paginated list of the tenant's price lists.
   *
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of price lists, with the next cursor in {@code meta}
   */
  @Operation(
      summary = "List price lists",
      description = "Cursor-paginated list of price lists for the tenant.")
  @APIResponse(responseCode = "200", description = "Page of price lists")
  @GET
  public Response list(@QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page = svc.listPriceLists(ctx, after, Cursor.clampLimit(limit));
    return Response.ok(
            ApiResponse.ok(
                page.items().stream().map(Mappers::toDto).toList(),
                new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  /**
   * Reads a single price list.
   *
   * @param id the price list to read
   * @return the price list
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(summary = "Get a price list by id", description = "Retrieves a single price list.")
  @APIResponse(responseCode = "200", description = "Price list found")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getPriceList(ctx, id)))).build();
  }

  /**
   * The priced variants on one price list.
   *
   * <p>A variant can appear more than once — one row per quantity break.
   *
   * @param id the price list whose items to list
   * @return the items
   * @throws com.storeql.web.ApiException {@code 404} when the price list does not exist in the
   *     caller's tenant
   */
  @Operation(
      summary = "List items on a price list",
      description = "All per-variant price entries on this price list.")
  @APIResponse(responseCode = "200", description = "List of price list items")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @GET
  @Path("/{id}/items")
  public Response listItems(
      @PathParam("id") UUID id,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    int size = limit == null || limit < 1 ? defaultItemsLimit : Math.min(limit, maxItemsLimit);
    var page = svc.listPriceListItemsPage(ctx, id, after, size);
    return Response.ok(
            ApiResponse.ok(
                page.items().stream().map(Mappers::toDto).toList(),
                new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  @Inject
  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "storeql.pricing.price-list-items.default-limit",
      defaultValue = "500")
  int defaultItemsLimit;

  @Inject
  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "storeql.pricing.price-list-items.max-limit",
      defaultValue = "1000")
  int maxItemsLimit;
}
