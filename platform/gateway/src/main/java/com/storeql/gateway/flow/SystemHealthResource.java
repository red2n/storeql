package com.storeql.gateway.flow;

import com.storeql.gateway.flow.SystemHealthDtos.FailurePage;
import com.storeql.gateway.flow.SystemHealthDtos.Summary;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.System.Logger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * How healthy the system is, for the people who run a business's system: the requests the gateway
 * has seen for that business, counted, and the last day's failures. Served by the gateway itself
 * because it is the one door every request passes; nothing here is proxied.
 *
 * <p>The business is the one {@code JwtAuthFilter} verified, never a parameter, a header the client
 * wrote or a path segment (golden rule 3). The caller needs the {@code system.health} permission
 * and must be held to no store (common-web's {@code SystemHealthAccess}, in that order). Only the
 * {@code /api/v1} form is served: the unversioned alias is deprecated and this route is new.
 *
 * <p>Redis away is an answer, not an error: the summary comes back {@code 200} with {@code
 * available: false} and no numbers, so the screen can say the figures are unavailable instead of
 * drawing a quiet system.
 */
@Path("/api/v1/system-health")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "System health")
public class SystemHealthResource {

  private static final Logger LOG = System.getLogger(SystemHealthResource.class.getName());

  @Inject FlowStore store;
  @Inject FlowRecorder recorder;

  Clock clock = Clock.systemUTC();

  private final WarnOnce warn = new WarnOnce(System::nanoTime);

  @Operation(
      summary = "Requests, failures and failure rate for the business",
      description =
          "Counts of every request the gateway saw for the caller's business, by route group and"
              + " outcome: the last 5 minutes, the last hour and the last 24 hours, the last hour"
              + " by group, and a point for each of the last 60 minutes and 24 hours. Needs the"
              + " system.health permission and a caller held to no store. When the counters cannot"
              + " be read the answer is still 200, with available false and no numbers.")
  @APIResponse(responseCode = "200", description = "The figures, or available: false")
  @APIResponse(responseCode = "401", description = "NO_TENANT: the token names no business")
  @APIResponse(
      responseCode = "403",
      description =
          "SYSTEM_HEALTH_NOT_PERMITTED: no system.health permission; BUSINESS_WIDE_ONLY: a caller"
              + " held to stores")
  @GET
  @Path("/summary")
  public Response summary(@Context ContainerRequestContext ctx) {
    String tenant;
    try {
      tenant = authorize(ctx);
    } catch (ApiException e) {
      return refuse(e);
    }
    Instant now = clock.instant();
    Summary summary;
    try {
      summary = HealthSummary.build(now, store.counters(tenant, now), recorder.droppedSinceStart());
    } catch (RuntimeException e) {
      warn.warn(LOG, "System-health figures cannot be read", e);
      summary = HealthSummary.unavailable(now, recorder.droppedSinceStart());
    }
    return answer(ApiResponse.ok(summary));
  }

  @Operation(
      summary = "The business's failures from the last 24 hours, newest first",
      description =
          "Each failure with when, the request id to quote, the route pattern (ids replaced by"
              + " {id}), the status, the stable error code, who sent it and the milliseconds taken."
              + " Cursor paging: ?limit= (default 20, at most 100) and ?after= from nextCursor."
              + " Nothing older than 24 hours is returned. A failure is a 5xx, or a 401, 403, 413 or"
              + " 429. Same access as the summary.")
  @APIResponse(responseCode = "200", description = "A page, or available: false")
  @APIResponse(responseCode = "400", description = "INVALID_CURSOR or INVALID_LIMIT")
  @APIResponse(
      responseCode = "403",
      description = "SYSTEM_HEALTH_NOT_PERMITTED or BUSINESS_WIDE_ONLY")
  @GET
  @Path("/failures")
  public Response failures(
      @QueryParam("limit") String limit,
      @QueryParam("after") String after,
      @Context ContainerRequestContext ctx) {
    String tenant;
    int size;
    Long before;
    try {
      tenant = authorize(ctx);
      size = limit(limit);
      before = before(after);
    } catch (ApiException e) {
      return refuse(e);
    }
    FailurePage page;
    try {
      FlowStore.FailureSlice slice = store.failures(tenant, clock.instant(), size, before);
      page =
          new FailurePage(
              slice.items(),
              slice.nextBefore() == null ? null : Cursor.encode(slice.nextBefore().toString()),
              true);
    } catch (RuntimeException e) {
      warn.warn(LOG, "System-health failures cannot be read", e);
      page = new FailurePage(List.of(), null, false);
    }
    return answer(ApiResponse.ok(page));
  }

  /** Who is asking, judged: the permission, then business-wide, then that there is a business. */
  private static String authorize(ContainerRequestContext ctx) {
    SystemHealthCaller caller = SystemHealthCaller.of(ctx);
    caller.requireAccess();
    if (caller.tenantId() == null) {
      throw ApiException.unauthorized("NO_TENANT", "No tenant in request context");
    }
    return caller.tenantId();
  }

  private static int limit(String raw) {
    if (raw == null || raw.isBlank()) return Cursor.DEFAULT_LIMIT;
    try {
      return Cursor.clampLimit(Integer.parseInt(raw.trim()));
    } catch (NumberFormatException e) {
      throw new ApiException(400, "INVALID_LIMIT", "limit must be a whole number", List.of(), e);
    }
  }

  private static Long before(String after) {
    String decoded = Cursor.decode(after);
    if (decoded == null) return null;
    long micros;
    try {
      micros = Long.parseLong(decoded);
    } catch (NumberFormatException e) {
      throw new ApiException(
          400, "INVALID_CURSOR", "The cursor is not one this list gave", List.of(), e);
    }
    if (micros < 0) {
      throw ApiException.badRequest("INVALID_CURSOR", "The cursor is not one this list gave");
    }
    return micros;
  }

  private static Response answer(ApiResponse<?> body) {
    // Per business and live: nothing between the gateway and the screen may keep a copy.
    return Response.ok(body).header("Cache-Control", "no-store").build();
  }

  private static Response refuse(ApiException e) {
    return Response.status(e.status())
        .type(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .entity(ApiResponse.error(e.toErrorBody()))
        .build();
  }
}
