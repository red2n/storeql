package com.storeql.pricing.api;

import com.storeql.pricing.domain.Domain;
import com.storeql.pricing.dto.Dtos.BatchUpsertPriceListItemsRequest;
import com.storeql.pricing.dto.Dtos.BatchUpsertResult;
import com.storeql.pricing.dto.Dtos.CreatePriceListRequest;
import com.storeql.pricing.dto.Dtos.SetActiveRequest;
import com.storeql.pricing.dto.Dtos.UpsertPriceListItemRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Setting and switching prices — the other half of the money-moving surface, alongside {@link
 * AdminPromotionResource}.
 *
 * <p><b>Why this class exists at all.</b> SJ-D33 and SJ-D36 were reported against promotions: a
 * promotion could be created by any staff role and then never switched off. Price lists had both
 * defects in identical form and neither was reported:
 *
 * <ul>
 *   <li><b>Anyone could write prices.</b> {@code POST /price-lists} and {@code
 *       /price-lists/{id}/items} sit outside {@code /admin/}, so {@code AdminAuthorizationFilter}
 *       asked only for <em>some</em> staff role. Proved against the running stack: a CASHIER token
 *       created a price list and got 201 back.
 *   <li><b>Nothing could switch one off.</b> {@code price_lists.active} has been {@code NOT NULL
 *       DEFAULT TRUE} since V1 and the resolve query filters on {@code pl.active = TRUE}, so it
 *       decides what customers are charged — and no route, service method or SQL statement ever
 *       wrote it.
 * </ul>
 *
 * <p>A price list is the more dangerous of the two: a promotion discounts a price, a price list
 * <em>is</em> the price. Fixing only what was reported is the mistake this branch has recorded four
 * times over (SJ-D10 → SJ-D11, SJ-D12 → SJ-D16, SJ-D2 → SJ-D23), so both are fixed here.
 *
 * <p>Gated by path, not by a role check inside each method, for the reason SJ-D10 established: a
 * method added to this class next year cannot be left open by someone forgetting a line. Reads stay
 * on {@code /price-lists} — the POS and storefront legitimately need them.
 */
@RequestScoped
@Path("/admin/price-lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Lists")
public class AdminPriceListResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Creates a price list, active from creation.
   *
   * @param req the name, channel (defaulting to ALL), currency and effective window
   * @return {@code 201} with the created price list
   */
  @Operation(
      summary = "Create a price list",
      description =
          "Creates a new price list scoped to a channel, currency, and effective period. Management"
              + " only: this sets what customers are charged, and it used to be reachable by any"
              + " staff role including a cashier.")
  @APIResponse(responseCode = "201", description = "Price list created")
  @APIResponse(responseCode = "403", description = "Caller is not management")
  @POST
  public Response create(CreatePriceListRequest req) {
    ctx.requirePermission(com.storeql.web.Permissions.PRICING_WRITE);
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPriceList(req, ctx))))
        .build();
  }

  /**
   * Sets one variant's price on a price list, publishing {@code PriceChanged}.
   *
   * @param id the price list to write to
   * @param req the variant, price and optional minimum quantity for a quantity break
   * @return the stored item
   * @throws com.storeql.web.ApiException {@code 404} when the price list does not exist in the
   *     caller's tenant
   */
  @Operation(
      summary = "Upsert a price list item",
      description =
          "Sets or updates the price for a single variant on this price list. The price is in the"
              + " list's currency and no finer than it: whole yen, at most three decimals for a"
              + " dinar, two for a pound; it is kept at the currency's own scale.")
  @APIResponse(responseCode = "200", description = "Price list item upserted")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED: a missing or non-positive price, or one with more decimals than"
              + " the list's currency has")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @POST
  @Path("/{id}/items")
  public Response upsertItem(@PathParam("id") UUID id, UpsertPriceListItemRequest req) {
    ctx.requirePermission(com.storeql.web.Permissions.PRICING_WRITE);
    Validations.validate(req);
    return Response.status(200)
        .entity(ApiResponse.ok(Mappers.toDto(svc.upsertPriceListItem(ctx, id, req))))
        .build();
  }

  /**
   * Sets many prices on one price list in a single call.
   *
   * <p>Not atomic: a bad row is collected as an error rather than rolling back the rest, so one
   * malformed line in a bulk upload does not discard the whole file. A partial success still
   * returns success — the caller must read {@code errors}.
   *
   * <p>The request as a whole is checked first: there must be a list of rows, not empty and at most
   * 500. Past that nothing is written, because a body that large is a different kind of upload —
   * send it in pieces.
   *
   * @param id the price list to write to
   * @param req the items to upsert
   * @return how many succeeded, and one message per failure
   * @throws com.storeql.web.ApiException {@code 400 VALIDATION_FAILED} when the rows are missing,
   *     empty or more than 500 (nothing written); {@code 404} when the price list does not exist in
   *     the caller's tenant
   */
  @Operation(
      summary = "Batch upsert price list items",
      description =
          "Upserts prices for multiple variants at once, at most 500 rows a call. Never returns 4xx"
              + " on a partial failure — per-item errors are reported in the response body"
              + " alongside the upserted count, a price with more decimals than the list's"
              + " currency has among them. A body with no rows, or with more than 500, is"
              + " refused as a whole and nothing is written.")
  @APIResponse(
      responseCode = "200",
      description = "Batch result with upserted count and any errors")
  @APIResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: items missing, empty or more than 500; nothing written")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @POST
  @Path("/{id}/items/batch")
  public Response batchUpsertItems(@PathParam("id") UUID id, BatchUpsertPriceListItemsRequest req) {
    ctx.requirePermission(com.storeql.web.Permissions.PRICING_WRITE);
    Validations.validate(req);
    BatchUpsertResult result = svc.batchUpsertPriceListItems(ctx, id, req);
    return Response.ok(ApiResponse.ok(result)).build();
  }

  /**
   * Stops a price list, recording why.
   *
   * @param id the price list to stop
   * @param req the reason, which is required
   * @return the recorded status change
   * @throws com.storeql.web.ApiException {@code 400} when no reason is given; {@code 404} when the
   *     price list does not exist; {@code 409} when it is already stopped
   */
  @Operation(
      summary = "Stop a price list",
      description =
          "Switches the price list off with immediate effect, recording who did it and why. The"
              + " resolve query filters on active, so from the next request its prices stop being"
              + " offered. Orders already placed keep the price they were charged — that figure is"
              + " recorded on the order, not looked up again.")
  @APIResponse(responseCode = "200", description = "Stopped")
  @APIResponse(responseCode = "400", description = "No reason given")
  @APIResponse(responseCode = "404", description = "No such price list for this tenant")
  @APIResponse(responseCode = "409", description = "Already stopped")
  @POST
  @Path("/{id}/deactivate")
  public Response deactivate(@PathParam("id") UUID id, SetActiveRequest req) {
    ctx.requirePermission(com.storeql.web.Permissions.PRICING_WRITE);
    // The reason is checked in the service, which names the refusal (PRICING_REASON_REQUIRED, 400)
    // before anything is read or written: Validations.validate here would answer VALIDATION_FAILED
    // and lose the code the screens know.
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PRICE_LIST, id, false, req))))
        .build();
  }

  /**
   * Starts a stopped price list again, recording why.
   *
   * <p>A reason is required in this direction too: restarting is the change more likely to be
   * questioned later.
   *
   * @param id the price list to start
   * @param req the reason, which is required
   * @return the recorded status change
   * @throws com.storeql.web.ApiException {@code 400} when no reason is given; {@code 404} when the
   *     price list does not exist; {@code 409} when it is already active
   */
  @Operation(summary = "Start a stopped price list again", description = "Requires a reason too.")
  @APIResponse(responseCode = "200", description = "Started")
  @APIResponse(responseCode = "409", description = "Already running")
  @POST
  @Path("/{id}/activate")
  public Response activate(@PathParam("id") UUID id, SetActiveRequest req) {
    ctx.requirePermission(com.storeql.web.Permissions.PRICING_WRITE);
    // As for deactivate: the service checks the reason and names the refusal.
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PRICE_LIST, id, true, req))))
        .build();
  }

  /**
   * The append-only on/off history for one price list.
   *
   * @param id the price list whose history to read
   * @return the recorded status changes, newest first
   */
  @Operation(
      summary = "A price list's on/off history",
      description =
          "Append-only, newest first. Shares one trail with promotions rather than two that would"
              + " drift apart — the row says which subject it belongs to.")
  @APIResponse(responseCode = "200", description = "The history")
  @GET
  @Path("/{id}/status-history")
  public Response history(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.statusChanges(ctx, Domain.StatusChange.PRICE_LIST, id).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }
}
