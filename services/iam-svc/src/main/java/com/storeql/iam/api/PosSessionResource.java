package com.storeql.iam.api;

import com.storeql.iam.domain.PosSession;
import com.storeql.iam.dto.Dtos.IdleSweepResult;
import com.storeql.iam.dto.Dtos.PosSessionResponse;
import com.storeql.iam.dto.Dtos.StartPosSessionRequest;
import com.storeql.iam.service.PosSessionService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gap #45 — POS session idle timeout. Creates and manages cashier POS sessions; a background sweep
 * (POST /sweep) force-expires sessions idle past their configured timeout and revokes their tokens.
 */
@RequestScoped
@Path("/auth/pos/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "POS Sessions")
public class PosSessionResource {

  @Inject PosSessionService svc;
  @Inject TenantContext ctx;

  /**
   * Opens a POS session for the calling cashier at a store.
   *
   * @param req the store and an optional idle timeout, defaulting to 900s
   * @return {@code 201} with the newly opened session
   * @throws com.storeql.web.ApiException {@code 400} when the timeout falls outside 60..86400;
   *     {@code 409} when the tenant or store is not trading
   */
  @Operation(
      summary = "Start a POS cashier session",
      description = "Opens a session for the given store; tenant is taken from the caller's JWT.")
  @APIResponse(responseCode = "201", description = "Session started")
  @APIResponse(responseCode = "400", description = "Invalid store or timeout value")
  @APIResponse(responseCode = "409", description = "Cashier already has an active session")
  @POST
  public Response start(StartPosSessionRequest req) {
    Validations.validate(req);
    var session = svc.start(ctx, req);
    return Response.status(201).entity(ApiResponse.ok(toDto(session))).build();
  }

  /**
   * Heartbeat that resets a session's idle timer, keeping it clear of the sweeper.
   *
   * @param id the session to keep alive
   * @return {@code 204} with no body
   * @throws com.storeql.web.ApiException {@code 404} when the session is not in the caller's
   *     tenant; {@code 409} when it has already ended or expired
   */
  @Operation(
      summary = "Record session activity",
      description = "Heartbeat that resets the session's idle timer.")
  @APIResponse(responseCode = "204", description = "Activity recorded")
  @APIResponse(responseCode = "404", description = "Session not found")
  @APIResponse(responseCode = "409", description = "Session is not active")
  @PUT
  @Path("/{id}/activity")
  public Response touch(@PathParam("id") UUID id) {
    svc.touch(ctx, id);
    return Response.noContent().build();
  }

  /**
   * Closes a POS session at sign-off.
   *
   * <p>Ending an already-closed session is not an error, so a repeated sign-off is safe.
   *
   * <p>The session's own cashier ends it freely; anyone else must be OWNER or MANAGER with access
   * to the session's store and give a {@code reason}, which is recorded with who did it.
   *
   * @param id the session to close
   * @param reason why, required when ending a colleague's session
   * @return {@code 204} with no body
   * @throws com.storeql.web.ApiException {@code 404} not in the caller's tenant; {@code 403
   *     POS_SESSION_NOT_YOURS} for a non-manager naming another's; {@code 403 STORE_ACCESS_DENIED};
   *     {@code 400 POS_SESSION_REASON_REQUIRED}
   */
  @Operation(summary = "End a POS session", description = "Explicitly closes an active session.")
  @APIResponse(responseCode = "204", description = "Session ended")
  @APIResponse(responseCode = "404", description = "Session not found")
  @DELETE
  @Path("/{id}")
  public Response end(@PathParam("id") UUID id, @QueryParam("reason") String reason) {
    svc.end(ctx, id, reason);
    return Response.noContent().build();
  }

  /**
   * Lists the tenant's open POS sessions across every store.
   *
   * @param storeId optionally one store the caller keeps
   * @return the active sessions, most recently started first; management only, store scoped
   */
  @Operation(
      summary = "List active POS sessions",
      description = "Active cashier sessions for the caller's tenant.")
  @APIResponse(responseCode = "200", description = "Active sessions")
  @GET
  public Response listActive(@QueryParam("storeId") UUID storeId) {
    List<PosSessionResponse> list = svc.listActive(ctx, storeId).stream().map(this::toDto).toList();
    return Response.ok(ApiResponse.ok(list)).build();
  }

  /**
   * The caller's own open sessions (any role): what a till asks when it re-attaches.
   *
   * @return the caller's active sessions
   */
  @Operation(summary = "My open POS sessions")
  @GET
  @Path("/mine")
  public Response mine() {
    List<PosSessionResponse> list = svc.listMine(ctx).stream().map(this::toDto).toList();
    return Response.ok(ApiResponse.ok(list)).build();
  }

  /**
   * Admin: expire all sessions idle past their timeout and revoke their refresh tokens. This is a
   * platform-wide maintenance operation (it ignores tenant scope), so it is restricted to platform
   * administrators — without this guard any authenticated caller could revoke POS sessions across
   * every tenant.
   *
   * @return the number of sessions expired by this sweep
   * @throws com.storeql.web.ApiException {@code 403} when the caller is not a {@code
   *     PLATFORM_ADMIN}
   */
  @Operation(
      summary = "Sweep idle POS sessions",
      description =
          "Platform-wide maintenance operation: force-expires sessions idle past their timeout and"
              + " revokes their refresh tokens. Ignores tenant scope. Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "200", description = "Sweep completed")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @POST
  @Path("/sweep")
  public Response sweep() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    int expired = svc.sweepIdle();
    return Response.ok(ApiResponse.ok(new IdleSweepResult(expired))).build();
  }

  private PosSessionResponse toDto(PosSession s) {
    return new PosSessionResponse(
        s.id() != null ? s.id().toString() : null,
        s.tenantId() != null ? s.tenantId().toString() : null,
        s.userId() != null ? s.userId().toString() : null,
        s.storeId() != null ? s.storeId().toString() : null,
        s.startedAt() != null ? s.startedAt().toString() : null,
        s.lastActivityAt() != null ? s.lastActivityAt().toString() : null,
        s.endedAt() != null ? s.endedAt().toString() : null,
        s.idleTimeoutSeconds(),
        s.status());
  }
}
