package com.storeql.product.api;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.dto.AssortmentDtos;
import com.storeql.product.mapper.AssortmentMappers;
import com.storeql.product.service.AssortmentService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/assortment}: which stores carry a line, decided with a date and a reason (07.18).
 *
 * <p>A separate class from {@link MerchandisingResource} on purpose, and not only for tidiness:
 * JAX-RS picks one resource class by the best match on its <em>root</em> path and then resolves the
 * method only inside that class, so two capabilities sharing a root would have one silently
 * shadowing the other.
 *
 * <p>The live range still answers where it always did — a product's stores are part of the product.
 * This is the decision log in front of it, and the sweep that moves the one into the other.
 */
@Path("/admin/assortment")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Assortment")
public class AssortmentResource {

  @Inject AssortmentService svc;
  @Inject TenantContext ctx;

  // ── clusters ────────────────────────────────────────────────────────────────

  @Operation(
      summary = "Name a group of stores to range against",
      description =
          "The complaint about per-store assortment was never that it could not express a range — it is"
              + " that expressing one costs a row per store. A cluster names the group once: city"
              + " convenience, superstore, the ten shops with a fish counter. A store may belong to"
              + " several, deliberately, because \"Scotland\" and \"has a bakery\" are both true of the"
              + " same shop.")
  @APIResponse(responseCode = "201", description = "Cluster named")
  @APIResponse(responseCode = "409", description = "Another active cluster has that code")
  @POST
  @Path("/clusters")
  public Response addCluster(AssortmentDtos.AddClusterRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    var c =
        svc.addCluster(
            ctx.requireTenantId(), req.code(), req.name(), req.note(), ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(AssortmentMappers.toDto(c))).build();
  }

  @Operation(summary = "The tenant's store clusters")
  @GET
  @Path("/clusters")
  public ApiResponse<List<AssortmentDtos.ClusterResponse>> clusters() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(AssortmentMappers.clusters(svc.clusters(ctx.requireTenantId())));
  }

  @Operation(summary = "One cluster, with its stores")
  @APIResponse(responseCode = "404", description = "No such cluster")
  @GET
  @Path("/clusters/{id}")
  public ApiResponse<AssortmentDtos.ClusterResponse> cluster(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(AssortmentMappers.toDto(svc.cluster(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Add stores to a cluster",
      description =
          "Stores already in it are left alone rather than refused, so the same list can be sent twice"
              + " without a buyer having to work out which half is new.")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN), or a store of this business the caller is not held"
              + " to (STORE_ACCESS_DENIED): none of the list is added")
  @APIResponse(
      responseCode = "404",
      description =
          "No such cluster (CLUSTER_NOT_FOUND), or a store that is not one of this business's"
              + " (ASSORTMENT_STORE_NOT_FOUND), whoever names it: none of the list is added")
  @APIResponse(responseCode = "409", description = "The cluster is retired")
  @APIResponse(
      responseCode = "503",
      description =
          "tenant-svc cannot say which stores are the business's (TENANT_STORES_UNAVAILABLE): a"
              + " store that cannot be checked is refused, not accepted")
  @POST
  @Path("/clusters/{id}/stores")
  public ApiResponse<AssortmentDtos.ClusterResponse> addStores(
      @PathParam("id") UUID id, AssortmentDtos.AddStoresRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    List<UUID> stores = req.storeIds().stream().map(s -> uuid(s, "storeId")).toList();
    return ApiResponse.ok(AssortmentMappers.toDto(svc.addMembers(ctx, id, stores)));
  }

  // ── changes ─────────────────────────────────────────────────────────────────

  @Operation(
      summary = "Record a range decision",
      description =
          "Dated, reasoned, and not yet in force: recording a change is the plan, applying it is the"
              + " act. That separation is what lets a range be set three weeks out instead of typed on"
              + " the morning it happens. The reason is required — a de-list with none is exactly what"
              + " this log exists to stop.")
  @APIResponse(responseCode = "201", description = "Decision recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "No product, action or reason, or a reason past 500 characters (VALIDATION_FAILED);"
              + " unknown action (ASSORTMENT_ACTION_UNKNOWN), neither or both targets given"
              + " (ASSORTMENT_TARGET_REQUIRED), an id that is not one (ASSORTMENT_ID_INVALID) or a"
              + " day that is not one (ASSORTMENT_DATE_INVALID)")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); a store of this business the caller is not held to"
              + " (STORE_ACCESS_DENIED); or, by a manager held to stores, a change aimed at a"
              + " cluster or to a line sold at every store (BUSINESS_WIDE_ONLY, which names who"
              + " can: an owner or a manager of the whole business). Nothing is recorded.")
  @APIResponse(
      responseCode = "409",
      description =
          "A de-list that would leave the line at no store, by anyone (ASSORTMENT_LAST_STORE): a"
              + " line with no store range is sold at every store, so the sweep could never apply"
              + " it. Judged on the line's whole plan — the range now, with every open change of"
              + " the line, whatever its day, walked as the sweep walks them — so a de-list is"
              + " also refused when it would leave one already recorded (for a later day, or"
              + " waiting) taking the line out of its last store; the refusal names that one's"
              + " day. A listing is never refused for it. Discontinue the line to stop selling it,"
              + " or list it at another store first; a manager held to stores, who may not"
              + " discontinue it, is pointed at an owner or a manager of the whole business to do"
              + " so, and at another of their own stores or at that owner or manager to list it at"
              + " one they do not keep. Nothing is recorded.")
  @APIResponse(
      responseCode = "404",
      description =
          "A line that is not this business's (PRODUCT_NOT_FOUND), no such cluster"
              + " (CLUSTER_NOT_FOUND), or a store that is not one of this business's"
              + " (ASSORTMENT_STORE_NOT_FOUND), whoever names it")
  @APIResponse(
      responseCode = "503",
      description = "tenant-svc cannot say which stores are the business's")
  @POST
  @Path("/changes")
  public Response record(AssortmentDtos.RecordChangeRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    var ch =
        svc.record(
            ctx,
            uuid(req.productId(), "productId"),
            optionalUuid(req.storeId(), "storeId"),
            optionalUuid(req.clusterId(), "clusterId"),
            req.action(),
            req.effectiveFrom() == null || req.effectiveFrom().isBlank()
                ? null
                : day(req.effectiveFrom(), "effectiveFrom"),
            req.reason());
    return Response.status(201).entity(ApiResponse.ok(AssortmentMappers.toDto(ch))).build();
  }

  @Operation(
      summary = "A line's range history",
      description =
          "Newest decision first, applied and unapplied together: the answer to \"who took this out of"
              + " the Scottish shops, when, and why\".")
  @GET
  @Path("/changes")
  public ApiResponse<List<AssortmentDtos.ChangeResponse>> changes(
      @QueryParam("product") String product) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        AssortmentMappers.changes(svc.changesOf(ctx.requireTenantId(), uuid(product, "product"))));
  }

  @Operation(
      summary = "What is due to be applied",
      description = "Everything dated on or before the day asked about and not yet in force.")
  @GET
  @Path("/changes/due")
  public ApiResponse<List<AssortmentDtos.ChangeResponse>> due(@QueryParam("asOf") String asOf) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        AssortmentMappers.changes(svc.due(ctx.requireTenantId(), optionalDay(asOf, "asOf"))));
  }

  @Operation(
      summary = "Apply what is due",
      description =
          "Oldest first, because order carries meaning: a line listed in March and de-listed in June,"
              + " applied the other way round, is on the shelf. Each change is judged on the range"
              + " it finds. A de-list of a line sold everywhere (ASSORTMENT_RANGED_EVERYWHERE), a"
              + " list decided by a manager held to stores of a line now sold everywhere"
              + " (BUSINESS_WIDE_ONLY) and a change aimed at an empty cluster (CLUSTER_EMPTY) are"
              + " reported with stillDue true and stay due — nothing has to be re-entered once the"
              + " cause is fixed. A de-list of the last stores a line is sold at"
              + " (ASSORTMENT_LAST_STORE: no stores means every store, so no range can make it"
              + " apply) is closed as refused with its reason, reported once with stillDue false,"
              + " kept on the line's history (refusedAt, refusalCode, refusal) and never due"
              + " again.")
  @APIResponse(responseCode = "200", description = "What was applied, and what was not")
  @POST
  @Path("/changes/apply")
  public ApiResponse<AssortmentDtos.SweepResponse> apply(@QueryParam("asOf") String asOf) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        AssortmentMappers.toDto(svc.applyDue(ctx.requireTenantId(), optionalDay(asOf, "asOf"))));
  }

  // ── range review ────────────────────────────────────────────────────────────

  @Operation(
      summary = "Open a range review",
      description =
          "One category over one trading period. The worth of a review is not the decision — a line"
              + " could always be de-listed — it is that the figures it was taken on are kept beside"
              + " it.")
  @APIResponse(responseCode = "201", description = "Review opened")
  @POST
  @Path("/reviews")
  public Response openReview(AssortmentDtos.OpenReviewRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    var r =
        svc.openReview(
            ctx.requireTenantId(),
            uuid(req.categoryId(), "categoryId"),
            req.name(),
            day(req.periodFrom(), "periodFrom"),
            day(req.periodTo(), "periodTo"),
            req.note(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(AssortmentMappers.toDto(r))).build();
  }

  @Operation(summary = "The tenant's range reviews")
  @GET
  @Path("/reviews")
  public ApiResponse<List<AssortmentDtos.ReviewResponse>> reviews() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(AssortmentMappers.reviews(svc.reviews(ctx.requireTenantId())));
  }

  @Operation(summary = "One review, with its lines and figures")
  @APIResponse(responseCode = "404", description = "No such review")
  @GET
  @Path("/reviews/{id}")
  public ApiResponse<AssortmentDtos.ReviewResponse> review(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(AssortmentMappers.toDto(svc.review(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Add the lines under review",
      description =
          "With the figures they are judged on. The figures are supplied, not read: sales and margin"
              + " belong to order-svc and reporting-svc, and product-svc reads neither service's"
              + " tables. A snapshot is also the better record — a report re-run next year shows"
              + " different numbers and makes an old decision look arbitrary. Sending a line twice"
              + " refreshes its figures.")
  @APIResponse(
      responseCode = "400",
      description =
          "Money without a currency, a currency without money, or a code ISO 4217 does not know"
              + " (REVIEW_LINE_CURRENCY); negative units or revenue, revenue or margin with more"
              + " decimal places than its currency has (two for GBP, none for JPY, three for KWD),"
              + " units sold with more than three decimal places, or a figure too large to keep"
              + " (REVIEW_LINE_FIGURES)")
  @APIResponse(responseCode = "409", description = "The review is already closed")
  @POST
  @Path("/reviews/{id}/lines")
  public ApiResponse<AssortmentDtos.ReviewResponse> addLines(
      @PathParam("id") UUID id, AssortmentDtos.AddLinesRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    List<Line> lines =
        req.lines().stream()
            .map(
                l ->
                    new Line(
                        null,
                        ctx.requireTenantId(),
                        id,
                        uuid(l.variantId(), "variantId"),
                        l.unitsSold(),
                        l.revenue(),
                        l.margin(),
                        l.currency() == null || l.currency().isBlank()
                            ? null
                            : l.currency().strip().toUpperCase(java.util.Locale.ROOT),
                        l.rankInCategory(),
                        null,
                        null,
                        l.ownBrand()))
            .toList();
    return ApiResponse.ok(AssortmentMappers.toDto(svc.addLines(ctx.requireTenantId(), id, lines)));
  }

  @Operation(
      summary = "Decide one line",
      description =
          "KEEP, DELIST or INTRODUCE. Dropping an own-brand line asks for a note — not a refusal, a"
              + " buyer may well be right, but the remedy for a poor own-brand line is more often a"
              + " reformulation or a price than a de-list, and the margin lost is the business's own.")
  @APIResponse(
      responseCode = "400",
      description = "Unknown decision, or an own-brand drop with no note")
  @POST
  @Path("/reviews/{id}/decisions")
  public ApiResponse<AssortmentDtos.ReviewResponse> decide(
      @PathParam("id") UUID id, AssortmentDtos.DecideRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        AssortmentMappers.toDto(
            svc.decide(
                ctx.requireTenantId(),
                id,
                uuid(req.variantId(), "variantId"),
                req.decision(),
                req.note(),
                ctx.requireUserId())));
  }

  @Operation(
      summary = "Close the review and record what it decided",
      description =
          "Every line needs a decision first: a review closed with lines unread would leave a buyer"
              + " believing a category was gone through when part of it was not. The changes carry the"
              + " review's effective date, usually the next reset, and reach the shelf when the sweep"
              + " runs. A product is de-listed only when every one of its variants was reviewed and"
              + " every one was dropped — the rest are reported as left alone, with the reason.")
  @APIResponse(
      responseCode = "409",
      description =
          "Lines still undecided (REVIEW_LINES_UNDECIDED), no lines (REVIEW_EMPTY), the review is"
              + " closed (REVIEW_NOT_OPEN), or, whoever closes it, a de-list it would produce"
              + " would leave a line at no store, which reads as every store"
              + " (ASSORTMENT_LAST_STORE, naming the line: decide KEEP and discontinue it, or list"
              + " it elsewhere first). The review stays open and nothing is recorded.")
  @APIResponse(
      responseCode = "400",
      description =
          "Neither or both targets given (ASSORTMENT_TARGET_REQUIRED). Judged first: then the"
              + " review and the store or cluster it names, each this business's (404) before the"
              + " caller's (403), and only then the review's own state (409).")
  @APIResponse(
      responseCode = "403",
      description =
          "Not an owner or manager (FORBIDDEN); a store of this business the caller is not held to"
              + " (STORE_ACCESS_DENIED); or, by a manager held to stores, a cluster, or a change"
              + " the review produces that they could not record one by one — to a line sold at"
              + " every store (BUSINESS_WIDE_ONLY). The review stays open and nothing is"
              + " recorded.")
  @APIResponse(
      responseCode = "404",
      description =
          "No such review or cluster, or a store that is not one of this business's"
              + " (ASSORTMENT_STORE_NOT_FOUND), whoever names it")
  @APIResponse(
      responseCode = "503",
      description = "tenant-svc cannot say which stores are the business's")
  @POST
  @Path("/reviews/{id}/close")
  public ApiResponse<AssortmentDtos.ReviewResultResponse> close(
      @PathParam("id") UUID id, AssortmentDtos.CloseReviewRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    if (req != null) { // the body is optional: a bare close applies to the whole business
      Validations.validate(req);
    }
    return ApiResponse.ok(
        AssortmentMappers.toDto(
            svc.close(
                ctx,
                id,
                req == null ? null : optionalUuid(req.storeId(), "storeId"),
                req == null ? null : optionalUuid(req.clusterId(), "clusterId"),
                req == null ? null : optionalDay(req.effectiveFrom(), "effectiveFrom"))));
  }

  @Operation(
      summary = "Abandon the review",
      description =
          "Kept, with its figures: an abandoned review is itself a record of a look taken.")
  @APIResponse(responseCode = "409", description = "The review is already closed")
  @POST
  @Path("/reviews/{id}/abandon")
  public ApiResponse<AssortmentDtos.ReviewResponse> abandon(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(AssortmentMappers.toDto(svc.abandon(ctx.requireTenantId(), id)));
  }

  private static UUID uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("ASSORTMENT_ID_REQUIRED", field + " is required");
    }
    try {
      return Ids.parse(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "ASSORTMENT_ID_INVALID", field + " is not an id", List.of(), e);
    }
  }

  private static UUID optionalUuid(String value, String field) {
    return value == null || value.isBlank() ? null : uuid(value, field);
  }

  private static LocalDate day(String value, String field) {
    try {
      return LocalDate.parse(value.strip());
    } catch (RuntimeException e) {
      throw new ApiException(
          400, "ASSORTMENT_DATE_INVALID", field + " is written as 2026-09-19", List.of(), e);
    }
  }

  private static LocalDate optionalDay(String value, String field) {
    return value == null || value.isBlank() ? null : day(value, field);
  }
}
