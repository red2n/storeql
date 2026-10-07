package com.storeql.product.api;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Merchandising.Position;
import com.storeql.product.dto.MerchandisingDtos;
import com.storeql.product.mapper.MerchandisingMappers;
import com.storeql.product.service.MerchandisingService;
import com.storeql.web.ApiException;
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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/merchandising}: what gets shelf space, how much, and where it sits (07.17).
 *
 * <p>Management's. Space is a buying decision — how much of a store a category gets, and which
 * lines stand at eye level — and it moves money between suppliers.
 *
 * <p>One class, one root path, every method relative to it. Not cosmetic: JAX-RS picks a single
 * resource class by the best match on its <em>root</em> and then resolves the method only inside
 * that class, so a class rooted at {@code "/"} with absolute method paths has its routes silently
 * taken by whichever class owns the prefix. That cost a morning on 07.16 — every terminal route
 * answered 404 while the build stayed green.
 */
@Path("/admin/merchandising")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Merchandising")
public class MerchandisingResource {

  @Inject MerchandisingService svc;
  @Inject com.storeql.product.service.ProductService catalogue;
  @Inject TenantContext ctx;

  // ── fixtures ────────────────────────────────────────────────────────────────

  @Operation(
      summary = "Record a piece of shelving",
      description =
          "A fixture is the thing a planogram is drawn for: a gondola, an end cap, a chiller. Its shelf"
              + " count and width are what make a layout checkable — facings times a variant's width"
              + " either fits or does not. `zoneId` is optional, because a buyer plans a fixture before"
              + " anybody decides which aisle it stands in.")
  @APIResponse(responseCode = "201", description = "Fixture recorded")
  @APIResponse(responseCode = "409", description = "Another active fixture has that code")
  @APIResponse(
      responseCode = "404",
      description = "The store is not one of this business's (MERCH_STORE_NOT_FOUND)")
  @APIResponse(
      responseCode = "503",
      description = "tenant-svc cannot say whose the store is (TENANT_STORES_UNAVAILABLE)")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the store is not one of theirs (STORE_ACCESS_DENIED)")
  @POST
  @Path("/fixtures")
  public Response addFixture(MerchandisingDtos.AddFixtureRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = uuid(req.storeId(), "storeId");
    svc.requireStore(ctx, storeId);
    var f =
        svc.addFixture(
            ctx.requireTenantId(),
            storeId,
            req.zoneId() == null || req.zoneId().isBlank() ? null : uuid(req.zoneId(), "zoneId"),
            req.code(),
            req.name(),
            req.kind(),
            req.shelfCount(),
            req.shelfWidthMm(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(MerchandisingMappers.toDto(f))).build();
  }

  @Operation(
      summary = "A store's fixtures",
      description =
          "Retired ones included, so a past layout still reads against the furniture it was drawn for.")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the store named is not one of theirs (STORE_ACCESS_DENIED)")
  @GET
  @Path("/fixtures")
  public ApiResponse<List<MerchandisingDtos.FixtureResponse>> fixtures(
      @QueryParam("store") String store) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID storeId = uuid(store, "store");
    ctx.reportStores(storeId); // a named store must be one of the caller's: 403 STORE_ACCESS_DENIED
    return ApiResponse.ok(
        MerchandisingMappers.fixtures(svc.fixtures(ctx.requireTenantId(), storeId)));
  }

  @Operation(
      summary = "Retire a fixture",
      description =
          "Never deleted: planograms point at it, and a past layout has to stay readable.")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the fixture is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such fixture in this business")
  @POST
  @Path("/fixtures/{id}/retire")
  public ApiResponse<MerchandisingDtos.FixtureResponse> retireFixture(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requireFixtureHeld(ctx, id);
    return ApiResponse.ok(MerchandisingMappers.toDto(svc.retireFixture(ctx.requireTenantId(), id)));
  }

  // ── planograms ──────────────────────────────────────────────────────────────

  @Operation(
      summary = "Start a new layout for a fixture",
      description =
          "A draft at the next version. One draft per fixture: two people drawing the same shelf at"
              + " once is a merge nobody wins.")
  @APIResponse(responseCode = "201", description = "Draft started")
  @APIResponse(
      responseCode = "409",
      description = "That fixture already has a draft, or is retired")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the fixture is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such fixture in this business")
  @POST
  @Path("/fixtures/{id}/planograms")
  public Response startDraft(
      @PathParam("id") UUID fixtureId, MerchandisingDtos.StartPlanogramRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requireFixtureHeld(ctx, fixtureId);
    Validations.validate(req);
    var p =
        svc.startDraft(
            ctx.requireTenantId(),
            fixtureId,
            day(req.effectiveFrom(), "effectiveFrom"),
            req.note(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(MerchandisingMappers.toDto(p))).build();
  }

  @Operation(
      summary = "Every version drawn for a fixture",
      description =
          "Newest first, so the history of a shelf reads: what it was, and what replaced it.")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the fixture is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such fixture in this business")
  @GET
  @Path("/fixtures/{id}/planograms")
  public ApiResponse<List<MerchandisingDtos.PlanogramResponse>> versions(
      @PathParam("id") UUID fixtureId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requireFixtureHeld(ctx, fixtureId);
    return ApiResponse.ok(
        MerchandisingMappers.planograms(svc.versions(ctx.requireTenantId(), fixtureId)));
  }

  @Operation(
      summary = "The layout in force for a fixture",
      description = "Published and not replaced — what the shelf is supposed to look like now.")
  @APIResponse(responseCode = "404", description = "Nothing published for that fixture yet")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the fixture is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such fixture in this business")
  @GET
  @Path("/fixtures/{id}/planogram")
  public ApiResponse<MerchandisingDtos.PlanogramResponse> inForce(@PathParam("id") UUID fixtureId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requireFixtureHeld(ctx, fixtureId);
    return ApiResponse.ok(
        MerchandisingMappers.toDto(
            svc.inForce(ctx.requireTenantId(), fixtureId)
                .orElseThrow(
                    () ->
                        ApiException.notFound(
                            "PLANOGRAM_NOT_FOUND", "Nothing is published for that fixture yet"))));
  }

  @Operation(
      summary = "Replace a draft's positions",
      description =
          "The whole layout at once, because half a shelf saved is not a smaller layout but a wrong"
              + " one. Each shelf is checked as it is saved rather than at publication: a layout found"
              + " not to fit after a reset has been scheduled around it is found out too late. A"
              + " position whose variant has no recorded facing width is placed and NOT checked — the"
              + " answer says how many it could not account for, because refusing a whole layout for"
              + " want of one measurement would be worse than a partial check that says so.")
  @APIResponse(responseCode = "200", description = "Saved — read the per-shelf fit")
  @APIResponse(
      responseCode = "409",
      description = "A shelf overflows, or the planogram is not a draft")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the planogram is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such planogram in this business")
  @PUT
  @Path("/planograms/{id}/positions")
  public ApiResponse<List<MerchandisingDtos.ShelfFitResponse>> setPositions(
      @PathParam("id") UUID planogramId, MerchandisingDtos.SetPositionsRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requirePlanogramHeld(ctx, planogramId);
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    List<Position> positions =
        req.positions().stream()
            .map(
                p ->
                    new Position(
                        null,
                        tenantId,
                        planogramId,
                        uuid(p.variantId(), "variantId"),
                        p.shelf(),
                        p.sequence(),
                        p.facings(),
                        p.depth(),
                        // Capacity is the database's to compute; nothing a caller sends is trusted
                        // for
                        // it, and the value here is never read on the way in.
                        p.facings() * p.depth(),
                        p.minPresentation()))
            .toList();
    return ApiResponse.ok(
        MerchandisingMappers.fits(svc.setPositions(tenantId, planogramId, positions)));
  }

  @Operation(
      summary = "Publish a layout",
      description =
          "It takes effect, the version it replaces is superseded — never edited — and the capacity it"
              + " implies goes to inventory-svc in the same transaction, so a shelf's layout and its"
              + " replenishment target cannot disagree. An empty layout is refused: published over a"
              + " full one it would quietly tell replenishment the shelf holds nothing.")
  @APIResponse(responseCode = "409", description = "Not a draft, or empty")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the planogram is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such planogram in this business")
  @POST
  @Path("/planograms/{id}/publish")
  public ApiResponse<MerchandisingDtos.PlanogramResponse> publish(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requirePlanogramHeld(ctx, id);
    return ApiResponse.ok(
        MerchandisingMappers.toDto(svc.publish(ctx.requireTenantId(), id, ctx.requireUserId())));
  }

  @Operation(summary = "One layout, with its positions")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the planogram is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such planogram in this business")
  @GET
  @Path("/planograms/{id}")
  public ApiResponse<MerchandisingDtos.PlanogramResponse> planogram(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requirePlanogramHeld(ctx, id);
    return ApiResponse.ok(
        MerchandisingMappers.toDto(
            svc.planogram(ctx.requireTenantId(), id)
                .orElseThrow(
                    () -> ApiException.notFound("PLANOGRAM_NOT_FOUND", "No such planogram"))));
  }

  // ── space planning ──────────────────────────────────────────────────────────

  @Operation(
      summary = "Set a category's target share of a store",
      description =
          "The plan the drawn layouts are judged against. One plan per store and category; setting it"
              + " again replaces it.")
  @APIResponse(
      responseCode = "404",
      description = "The store is not one of this business's (MERCH_STORE_NOT_FOUND)")
  @APIResponse(
      responseCode = "503",
      description = "tenant-svc cannot say whose the store is (TENANT_STORES_UNAVAILABLE)")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the store is not one of theirs (STORE_ACCESS_DENIED)")
  @PUT
  @Path("/space-plans")
  public ApiResponse<MerchandisingDtos.SpaceLineResponse> setSpacePlan(
      MerchandisingDtos.SpacePlanRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = uuid(req.storeId(), "storeId");
    svc.requireStore(ctx, storeId);
    UUID categoryId = uuid(req.categoryId(), "categoryId");
    svc.setSpacePlan(
        ctx.requireTenantId(),
        storeId,
        categoryId,
        req.targetShare(),
        req.reviewOn() == null || req.reviewOn().isBlank() ? null : day(req.reviewOn(), "reviewOn"),
        req.note(),
        ctx.requireUserId());
    // The line back, not the plan: a target with no actual beside it is the number somebody already
    // had.
    return ApiResponse.ok(
        MerchandisingMappers.spaceLines(svc.spaceReport(ctx.requireTenantId(), storeId)).stream()
            .filter(l -> l.categoryId().equals(categoryId.toString()))
            .findFirst()
            .orElseThrow(
                () -> ApiException.notFound("SPACE_PLAN_NOT_FOUND", "The plan was not saved")));
  }

  @Operation(
      summary = "What each planned category was promised, and what it has",
      description =
          "The actual share is measured from the layouts in force, so it moves when a shelf is re-laid"
              + " and not when somebody remembers to update a number. The variance is signed:"
              + " over-spaced and under-spaced are different problems with different remedies. Only"
              + " categories with a plan appear — a category nobody promised anything is not a"
              + " variance, and listing it would bury the lines a buyer can act on.")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the store named is not one of theirs (STORE_ACCESS_DENIED)")
  @GET
  @Path("/space")
  public ApiResponse<List<MerchandisingDtos.SpaceLineResponse>> space(
      @QueryParam("store") String store) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID storeId = uuid(store, "store");
    ctx.reportStores(storeId); // a named store must be one of the caller's: 403 STORE_ACCESS_DENIED
    return ApiResponse.ok(
        MerchandisingMappers.spaceLines(svc.spaceReport(ctx.requireTenantId(), storeId)));
  }

  // ── resets ──────────────────────────────────────────────────────────────────

  @Operation(
      summary = "Plan a category reset",
      description =
          "The day a set of published layouts goes on the shelf together, because a category is re-laid"
              + " all at once and half a reset is a mess in an aisle.")
  @APIResponse(responseCode = "201", description = "Reset planned")
  @POST
  @Path("/resets")
  public Response planReset(MerchandisingDtos.PlanResetRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    var r =
        svc.planReset(
            ctx.requireTenantId(),
            uuid(req.categoryId(), "categoryId"),
            req.name(),
            day(req.scheduledFor(), "scheduledFor"),
            ctx.requireUserId());
    return Response.status(201)
        .entity(ApiResponse.ok(MerchandisingMappers.toDto(r, LocalDate.now())))
        .build();
  }

  @Operation(
      summary = "The resets this business has planned",
      description =
          "Newest first. `overdue` is derived on every read — a stored flag is wrong the day nobody runs the job.")
  @GET
  @Path("/resets")
  public ApiResponse<List<MerchandisingDtos.ResetResponse>> resets() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(MerchandisingMappers.resets(svc.resetsFor(ctx), LocalDate.now()));
  }

  @Operation(
      summary = "Add a published layout to a reset",
      description =
          "Published only: a draft can still change under the reset. A layout belongs to one reset —"
              + " two resets claiming the same shelf on different days is a contradiction that is"
              + " better refused than recorded.")
  @APIResponse(responseCode = "409", description = "Not open, not published, or already claimed")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the reset is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such reset in this business")
  @POST
  @Path("/resets/{id}/planograms")
  public ApiResponse<MerchandisingDtos.ResetResponse> attach(
      @PathParam("id") UUID id, MerchandisingDtos.AttachPlanogramRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requireResetHeld(ctx, id);
    Validations.validate(req);
    UUID planogramId = uuid(req.planogramId(), "planogramId");
    svc.requirePlanogramHeld(ctx, planogramId);
    return ApiResponse.ok(
        MerchandisingMappers.toDto(
            svc.attach(ctx.requireTenantId(), id, planogramId), LocalDate.now()));
  }

  @Operation(summary = "The reset happened")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the reset is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such reset in this business")
  @POST
  @Path("/resets/{id}/complete")
  public ApiResponse<MerchandisingDtos.ResetResponse> complete(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requireResetHeld(ctx, id);
    return ApiResponse.ok(
        MerchandisingMappers.toDto(svc.complete(ctx.requireTenantId(), id), LocalDate.now()));
  }

  @Operation(
      summary = "The reset was called off",
      description =
          "With a reason, which is required: an abandoned reset with none is what somebody asks about in six months.")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the reset is at a store beyond theirs (STORE_ACCESS_DENIED). Nothing changes.")
  @APIResponse(responseCode = "404", description = "No such reset in this business")
  @POST
  @Path("/resets/{id}/cancel")
  public ApiResponse<MerchandisingDtos.ResetResponse> cancel(
      @PathParam("id") UUID id, MerchandisingDtos.CancelResetRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    svc.requireResetHeld(ctx, id);
    Validations.validate(req);
    return ApiResponse.ok(
        MerchandisingMappers.toDto(
            svc.cancel(ctx.requireTenantId(), id, req.reason()), LocalDate.now()));
  }

  @Operation(
      summary = "Record how wide one facing of a line is",
      description =
          "The measurement every fit check is made of, and the one most catalogues are missing. Kept on"
              + " the variant rather than in a layout: a bottle is the width it is on every shelf it"
              + " stands on, and a width per position would let two layouts disagree about the same"
              + " bottle. Sending null says the width is not known after all — the line is still"
              + " placed, and the fit says how much it could not account for.")
  @APIResponse(responseCode = "404", description = "No such variant")
  @APIResponse(
      responseCode = "403",
      description =
          "A manager held to stores, and the variant's line is sold at every store or at a store"
              + " beyond theirs (BUSINESS_WIDE_ONLY): the width is the variant's at every store."
              + " Nothing changes.")
  @PUT
  @Path("/variants/{id}/facing-width")
  public ApiResponse<MerchandisingDtos.FacingWidthRequest> setFacingWidth(
      @PathParam("id") UUID id, MerchandisingDtos.FacingWidthRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    catalogue.requireVariantLineHeld(ctx, id);
    Validations.validate(req);
    svc.setFacingWidth(ctx.requireTenantId(), id, req == null ? null : req.facingWidthMm());
    return ApiResponse.ok(
        new MerchandisingDtos.FacingWidthRequest(req == null ? null : req.facingWidthMm()));
  }

  // ── own brand ───────────────────────────────────────────────────────────────

  @Operation(
      summary = "Mark a brand as the business's own",
      description =
          "Own-brand changes how a line is treated at nearly every step: the margin is the business's"
              + " own rather than a supplier's, a range review protects it against the brands beside"
              + " it, and a recall is the business's own responsibility.")
  @APIResponse(responseCode = "404", description = "No such brand")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); or a manager held to stores (BUSINESS_WIDE_ONLY):"
              + " the mark is the whole business's. Nothing changes.")
  @PUT
  @Path("/brands/{id}/own-brand")
  public ApiResponse<MerchandisingDtos.OwnBrandRequest> setOwnBrand(
      @PathParam("id") UUID id, MerchandisingDtos.OwnBrandRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    catalogue.requireBusinessWideCatalogue(ctx, "Marking a brand as the business's own");
    Validations.validate(req);
    svc.setOwnBrand(ctx.requireTenantId(), id, req != null && req.ownBrand());
    return ApiResponse.ok(new MerchandisingDtos.OwnBrandRequest(req != null && req.ownBrand()));
  }

  private static UUID uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("MERCH_ID_REQUIRED", field + " is required");
    }
    try {
      return Ids.parse(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "MERCH_ID_INVALID", field + " is not an id", List.of(), e);
    }
  }

  private static LocalDate day(String value, String field) {
    try {
      return LocalDate.parse(value.strip());
    } catch (RuntimeException e) {
      throw new ApiException(
          400, "MERCH_DATE_INVALID", field + " is written as 2026-09-19", List.of(), e);
    }
  }
}
