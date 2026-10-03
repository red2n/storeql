package com.storeql.customer.api;

import com.storeql.customer.domain.Privacy.Intimation;
import com.storeql.customer.domain.Privacy.Request;
import com.storeql.customer.dto.PrivacyDtos.IntimateRequest;
import com.storeql.customer.dto.PrivacyDtos.PublishNoticeRequest;
import com.storeql.customer.dto.PrivacyDtos.ResolveRequest;
import com.storeql.customer.dto.PrivacyDtos.SetSettingsRequest;
import com.storeql.customer.mapper.PrivacyMappers;
import com.storeql.customer.service.PrivacyService;
import com.storeql.customer.service.PrivacyService.SettingsChange;
import com.storeql.web.ApiResponse;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The business's side of a person's privacy under the DPDP Act (13.12): the notice per language,
 * the grievance contact and period, the request queue, and the breach told to customers. The notice
 * itself is public: a shopper reads it before they sign up.
 */
@RequestScoped
@Path("/customers/privacy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Privacy")
public class PrivacyResource {

  static final int MAX_PAGE = 100;

  @Inject PrivacyService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The privacy notice, as a shopper reads it",
      description =
          "Public: read before signing up. In the language asked for when the business has"
              + " published one, else in English, else none yet; with the purposes consent is"
              + " asked for, the languages offered (English and the Eighth Schedule's"
              + " twenty-two), the grievance contact and the period a request is answered in, and"
              + " whether the DPDP Act binds this business today.")
  @APIResponse(responseCode = "200", description = "The notice and what surrounds it")
  @APIResponse(responseCode = "400", description = "A language not offered")
  @GET
  @Path("/notice")
  public Response notice(@QueryParam("language") String language) {
    return Response.ok(
            ApiResponse.ok(PrivacyMappers.toDto(svc.notice(ctx.requireTenantId(), language))))
        .build();
  }

  @Operation(summary = "Who takes grievances, and the published period")
  @GET
  @Path("/settings")
  public Response settings() {
    return Response.ok(ApiResponse.ok(PrivacyMappers.toDto(svc.settings(ctx.requireTenantId()))))
        .build();
  }

  @Operation(
      summary = "Set the grievance contact and the period",
      description =
          "The business contact a person puts questions and grievances to (DPDP Act s.8(9),"
              + " Rules r.9), and the days it gives itself to answer a request: at most 90"
              + " (r.14(3)). Owners and managers.")
  @APIResponse(responseCode = "200", description = "The settings as kept")
  @APIResponse(
      responseCode = "400",
      description = "A period outside 1 to 90, or an email that is not one")
  @PUT
  @Path("/settings")
  public Response setSettings(SetSettingsRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                PrivacyMappers.toDto(
                    svc.setSettings(
                        ctx.requireTenantId(),
                        new SettingsChange(
                            req.grievanceName(),
                            req.grievanceEmail(),
                            req.grievancePhone(),
                            req.grievanceAddress(),
                            req.responseDays()),
                        ctx.userId()))))
        .build();
  }

  @Operation(summary = "The current notice in each language it is published in")
  @GET
  @Path("/notices")
  public Response notices() {
    return Response.ok(
            ApiResponse.ok(
                svc.notices(ctx.requireTenantId()).stream().map(PrivacyMappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Publish the notice in a language",
      description =
          "A new version every time: a consent names the version the person read, so no version"
              + " is ever rewritten. English, or any of the Eighth Schedule's languages by ISO 639"
              + " code. Owners and managers.")
  @APIResponse(responseCode = "201", description = "The version published")
  @APIResponse(responseCode = "400", description = "A language not offered, or text out of bounds")
  @POST
  @Path("/notices")
  public Response publish(PublishNoticeRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    return Response.status(201)
        .entity(
            ApiResponse.ok(
                PrivacyMappers.toDto(
                    svc.publish(
                        ctx.requireTenantId(),
                        req.language(),
                        req.title(),
                        req.body(),
                        ctx.userId()))))
        .build();
  }

  @Operation(
      summary = "The request queue",
      description =
          "?status=OPEN is the queue, soonest due first; without it, everything newest first.")
  @APIResponse(responseCode = "400", description = "A status that does not exist")
  @GET
  @Path("/requests")
  public Response requests(
      @QueryParam("status") String status, @QueryParam("limit") @DefaultValue("50") int limit) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<Request> page =
        svc.requests(ctx.requireTenantId(), status, Math.max(1, Math.min(limit, MAX_PAGE)));
    return Response.ok(
            ApiResponse.ok(page.stream().map(r -> PrivacyMappers.toDto(r, today)).toList()))
        .build();
  }

  @Operation(
      summary = "Answer a request",
      description = "RESOLVED or REFUSED, with what was done. Needs customers.privacy.")
  @APIResponse(responseCode = "200", description = "The request as settled")
  @APIResponse(responseCode = "404", description = "No such request")
  @APIResponse(responseCode = "409", description = "Already settled")
  @POST
  @Path("/requests/{id}/resolve")
  public Response resolve(@PathParam("id") UUID id, ResolveRequest req) {
    ctx.requirePermission(Permissions.CUSTOMERS_PRIVACY);
    Validations.validate(req);
    Request r =
        svc.resolve(ctx.requireTenantId(), id, req.status(), req.resolution(), ctx.userId());
    return Response.ok(ApiResponse.ok(PrivacyMappers.toDto(r, LocalDate.now(ZoneOffset.UTC))))
        .build();
  }

  @Operation(summary = "Breaches told to customers, newest first")
  @GET
  @Path("/breach-intimations")
  public Response intimations() {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    return Response.ok(
            ApiResponse.ok(
                svc.intimations(ctx.requireTenantId()).stream()
                    .map(PrivacyMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Tell customers of a breach",
      description =
          "Each customer named — or everyone the business can reach when none is — by email where"
              + " there is one and by text where there is only a phone, in plain words (DPDP Rules"
              + " r.7(1)); what was sent and to how many is kept for the report to the Board"
              + " (r.7(2)(b)). Owners and managers.")
  @APIResponse(responseCode = "201", description = "Sent, with how many were reached")
  @APIResponse(responseCode = "400", description = "Text out of bounds, or more than 500 named")
  @APIResponse(responseCode = "409", description = "Nobody named can be reached")
  @POST
  @Path("/breach-intimations")
  public Response intimate(IntimateRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    List<UUID> only = new ArrayList<>();
    if (req.customerIds() != null) {
      for (String id : req.customerIds()) only.add(com.storeql.web.Parsing.uuid(id, "customerIds"));
    }
    Intimation i =
        svc.intimate(
            ctx.requireTenantId(),
            req.noticeId() == null || req.noticeId().isBlank()
                ? null
                : com.storeql.web.Parsing.uuid(req.noticeId(), "noticeId"),
            req.subject(),
            req.body(),
            only,
            ctx.userId());
    return Response.status(201).entity(ApiResponse.ok(PrivacyMappers.toDto(i))).build();
  }
}
