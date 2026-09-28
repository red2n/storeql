package com.storeql.pricing.api;

import com.storeql.pricing.domain.Domain;
import com.storeql.pricing.dto.Dtos.AddPromotionItemRequest;
import com.storeql.pricing.dto.Dtos.CreatePromotionRequest;
import com.storeql.pricing.dto.Dtos.SetActiveRequest;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.PricingService;
import com.storeql.web.ApiException;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Creating and switching promotions. Price lists are the same surface and the same two defects —
 * see {@link AdminPriceListResource}.
 *
 * <p><b>Under {@code /admin/} deliberately, and that is the entire point of this class
 * existing.</b> These operations used to sit on {@code /promotions}, which is outside {@code
 * /admin/}, so {@code AdminAuthorizationFilter}'s mutation tier asked only for <em>some</em> staff
 * role — and a CASHIER could therefore create a 100%-off basket promotion with no end date
 * (SJ-D36). Combined with SJ-D33, which left no way to switch one off, a till operator could give
 * away the shop permanently and the only remedy was direct database access.
 *
 * <p>Gated by path rather than by a {@code requireAnyRole} call in each method, for the reason
 * SJ-D10 established and SJ-D19 then repeated: a method added to this class next year cannot be
 * left open by someone forgetting a line. The read side stays on {@code /promotions}, because the
 * storefront legitimately needs it.
 */
@RequestScoped
@Path("/admin/promotions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Promotions")
public class AdminPromotionResource {

  /**
   * How far back the windows reach when the caller names no day: two years, the forecast's read.
   */
  private static final long DEFAULT_WINDOW_DAYS = 730;

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Creates a promotion, active immediately.
   *
   * <p>The shape is validated against the type, so a half-configured BOGO or a threshold promotion
   * with no threshold is rejected rather than stored as something that silently discounts nothing.
   *
   * @param req the type, value, window, priority, coupon and any type-specific settings
   * @return {@code 201} with the created promotion
   * @throws com.storeql.web.ApiException {@code 400} when the shape does not match the type
   */
  /**
   * The promotions that touch a store, as windows in time — what inventory-svc reads for the demand
   * forecast (06.x): the store's own and the business-wide ones since {@code from}, each with its
   * scope resolved to variants, a switched-off one ending when it was switched off.
   *
   * @param store the store; required
   * @param from the first day of interest, {@code yyyy-MM-dd}; two years back when absent
   * @return {@code 200} with the windows, earliest start first
   * @throws com.storeql.web.ApiException {@code 400 STORE_REQUIRED} without a store, {@code 400
   *     PRICING_INVALID_DATE} when {@code from} is not a date
   */
  @Operation(
      summary = "Promotion windows for a store",
      description =
          "Every promotion touching the store since `from` (two years back by default) as a window"
              + " in time: its scope resolved to variant ids (or `allVariants`), and its end — the"
              + " end date, or the moment it was switched off if that came first. Read by"
              + " inventory-svc for the demand forecast's promotional uplift (06.x); staff-readable.")
  @APIResponse(responseCode = "200", description = "The windows, earliest start first")
  @APIResponse(responseCode = "400", description = "No store, or `from` is not a date")
  @GET
  @Path("/windows")
  public Response windows(@QueryParam("store") UUID store, @QueryParam("from") String from) {
    if (store == null) {
      throw ApiException.badRequest("STORE_REQUIRED", "store is required");
    }
    Instant since;
    if (from == null || from.isBlank()) {
      since = Instant.now().minus(Duration.ofDays(DEFAULT_WINDOW_DAYS));
    } else {
      try {
        since = LocalDate.parse(from).atStartOfDay().toInstant(ZoneOffset.UTC);
      } catch (DateTimeParseException e) {
        throw new ApiException(
            400, "PRICING_INVALID_DATE", "from must be a date, yyyy-MM-dd", List.of(), e);
      }
    }
    return Response.ok(
            ApiResponse.ok(
                svc.promotionWindows(ctx, store, since).stream()
                    .map(w -> Mappers.toDto(w))
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Create a promotion",
      description =
          "Creates a time-bounded discount, optionally scoped to a store and channel. Management"
              + " only: this is money leaving the business, and it used to be reachable by any"
              + " staff role including a cashier.")
  @APIResponse(responseCode = "201", description = "Promotion created")
  @APIResponse(responseCode = "403", description = "Caller is not management")
  @POST
  public Response create(CreatePromotionRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPromotion(req, ctx))))
        .build();
  }

  /**
   * Scopes a promotion to a variant, or to everything.
   *
   * <p>{@code CATEGORY} is rejected: pricing-svc has no variant→category mapping, so such a
   * promotion would be stored and never fire.
   *
   * @param id the promotion to scope
   * @param req the scope type ({@code VARIANT} or {@code ALL}) and, for VARIANT, the variant id
   * @return the stored scope row
   * @throws com.storeql.web.ApiException {@code 400} when the scope is a category, unknown, or a
   *     VARIANT scope with no variant named
   */
  @Operation(
      summary = "Add a scope item to a promotion",
      description = "Attaches the promotion to a scope (ALL, VARIANT or CATEGORY).")
  @APIResponse(responseCode = "201", description = "Promotion item added")
  @POST
  @Path("/{id}/items")
  public Response addItem(@PathParam("id") UUID id, AddPromotionItemRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.addPromotionItem(ctx, id, req))))
        .build();
  }

  /**
   * Stops a promotion, recording why.
   *
   * <p>The only way to end a promotion created with no end date.
   *
   * @param id the promotion to stop
   * @param req the reason, which is required
   * @return the recorded status change
   * @throws com.storeql.web.ApiException {@code 400} when no reason is given; {@code 404} when the
   *     promotion does not exist; {@code 409} when it is already stopped
   */
  @Operation(
      summary = "Stop a promotion",
      description =
          "Switches the promotion off with immediate effect, recording who did it and why. Before"
              + " this existed there was no route, no service method and no writer of any kind for"
              + " promotions.active — a promotion created without an end date ran forever and could"
              + " only be stopped by reaching into the database (SJ-D33).")
  @APIResponse(responseCode = "200", description = "Stopped")
  @APIResponse(responseCode = "400", description = "No reason given")
  @APIResponse(responseCode = "404", description = "No such promotion for this tenant")
  @APIResponse(responseCode = "409", description = "Already stopped")
  @POST
  @Path("/{id}/deactivate")
  public Response deactivate(@PathParam("id") UUID id, SetActiveRequest req) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PROMOTION, id, false, req))))
        .build();
  }

  /**
   * Starts a stopped promotion again, recording why.
   *
   * <p>A reason is required in this direction too: restarting a discount is the change more likely
   * to be questioned later.
   *
   * @param id the promotion to start
   * @param req the reason, which is required
   * @return the recorded status change
   * @throws com.storeql.web.ApiException {@code 400} when no reason is given; {@code 404} when the
   *     promotion does not exist; {@code 409} when it is already active
   */
  @Operation(summary = "Start a stopped promotion again", description = "Requires a reason too.")
  @APIResponse(responseCode = "200", description = "Started")
  @APIResponse(responseCode = "409", description = "Already running")
  @POST
  @Path("/{id}/activate")
  public Response activate(@PathParam("id") UUID id, SetActiveRequest req) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PROMOTION, id, true, req))))
        .build();
  }

  /**
   * The append-only on/off history for one promotion.
   *
   * @param id the promotion whose history to read
   * @return the recorded status changes, newest first
   */
  @Operation(
      summary = "A promotion's on/off history",
      description =
          "Append-only, newest first. A promotion can be stopped and started repeatedly, so this is"
              + " a table rather than a pair of columns: a record keeping only the last change"
              + " cannot answer who turned it back on.")
  @APIResponse(responseCode = "200", description = "The history")
  @GET
  @Path("/{id}/status-history")
  public Response history(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.statusChanges(ctx, Domain.StatusChange.PROMOTION, id).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }
}
