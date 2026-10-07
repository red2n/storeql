package com.storeql.tenant.api;

import com.storeql.tenant.domain.Domain.NoticeDuties;
import com.storeql.tenant.domain.Domain.SecurityNotice;
import com.storeql.tenant.dto.Dtos.RecordDutyRequest;
import com.storeql.tenant.dto.Dtos.SecurityNoticeResponse;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.service.SecurityIncidentService;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Security notices a business has been sent (21.15). Under {@code /admin/}, so management only by
 * path: the owner or a manager answers for the business as controller of its customers' data.
 */
@Path("/admin/tenant/security-notices")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Security notices")
public class SecurityNoticeResource {

  @Inject SecurityIncidentService service;
  @Inject TenantContext ctx;

  @Operation(summary = "This business's security notices, newest first")
  @APIResponse(responseCode = "403", description = "Not a management role")
  @GET
  public ApiResponse<List<SecurityNoticeResponse>> list() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.notices(tenantId).stream()
            .map(n -> Mappers.toSecurityNotice(n, service.duties(tenantId, n)))
            .toList());
  }

  @Operation(
      summary = "Record a duty done on a breach notice",
      description =
          "What the business owes when told of a personal data breach, by the regime its country"
              + " puts it under (13.12): under India's DPDP Act, each affected person told without"
              + " delay, the Board told without delay and reported to within 72 hours (Rules r.7);"
              + " under the GDPR, the authority within 72 hours and the people affected where the"
              + " risk is high (arts.33–34). Once per duty, never rewritten. Owners and managers.")
  @APIResponse(responseCode = "201", description = "The notice's duties as they now stand")
  @APIResponse(responseCode = "400", description = "A duty the regime does not put on the business")
  @APIResponse(responseCode = "404", description = "This business has no such notice")
  @APIResponse(responseCode = "409", description = "That duty was recorded already")
  @POST
  @Path("/{id}/reports")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response report(@PathParam("id") UUID id, RecordDutyRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    NoticeDuties duties = service.report(tenantId, id, req, ctx.requireUserId());
    SecurityNotice notice =
        service.notices(tenantId).stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toSecurityNotice(notice, duties)))
        .build();
  }

  @Operation(
      summary = "Acknowledge a security notice",
      description =
          "Records who confirmed they read it, once; acknowledging again returns the first.")
  @APIResponse(responseCode = "404", description = "This business has no such notice")
  @POST
  @Path("/{id}/acknowledge")
  public ApiResponse<SecurityNoticeResponse> acknowledge(@PathParam("id") UUID id) {
    BusinessWide.require(ctx);
    return ApiResponse.ok(
        Mappers.toSecurityNotice(
            service.acknowledge(ctx.requireTenantId(), id, ctx.requireUserId()),
            service.duties(
                ctx.requireTenantId(),
                service.notices(ctx.requireTenantId()).stream()
                    .filter(n -> n.id().equals(id))
                    .findFirst()
                    .orElseThrow())));
  }
}
