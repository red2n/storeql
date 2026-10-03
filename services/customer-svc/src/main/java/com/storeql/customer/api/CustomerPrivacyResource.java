package com.storeql.customer.api;

import com.storeql.customer.domain.Privacy;
import com.storeql.customer.dto.PrivacyDtos.ChoiceRequest;
import com.storeql.customer.dto.PrivacyDtos.ChooseRequest;
import com.storeql.customer.dto.PrivacyDtos.RecordGuardianRequest;
import com.storeql.customer.mapper.PrivacyMappers;
import com.storeql.customer.service.PrivacyService;
import com.storeql.customer.service.PrivacyService.Choice;
import com.storeql.web.ApiResponse;
import com.storeql.web.Permissions;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A customer's privacy as staff see and act on it (13.12): consents taken over the counter, the
 * evidence behind them, and a child's guardian recorded with how the parent was verified.
 */
@RequestScoped
@Path("/customers/{customerId}/privacy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Privacy")
public class CustomerPrivacyResource {

  @Inject PrivacyService svc;
  @Inject TenantContext ctx;

  @Operation(summary = "A customer's consents, and whether a guardian's consent stands")
  @APIResponse(responseCode = "404", description = "No such customer")
  @GET
  public Response consents(@PathParam("customerId") UUID customerId) {
    return Response.ok(
            ApiResponse.ok(PrivacyMappers.toDto(svc.consents(ctx.requireTenantId(), customerId))))
        .build();
  }

  @Operation(
      summary = "The evidence: every grant and withdrawal, newest first",
      description = "Needs customers.privacy.")
  @GET
  @Path("/log")
  public Response log(@PathParam("customerId") UUID customerId) {
    ctx.requirePermission(Permissions.CUSTOMERS_PRIVACY);
    return Response.ok(
            ApiResponse.ok(
                svc.consentLog(ctx.requireTenantId(), customerId).stream()
                    .map(PrivacyMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Consent taken over the counter",
      description = "Recorded as the staff member's act, against the notice in the language read.")
  @APIResponse(responseCode = "400", description = "A purpose that does not exist, or none")
  @APIResponse(responseCode = "404", description = "No such customer")
  @APIResponse(
      responseCode = "409",
      description = "A child without a guardian's consent; no notice where the Act binds")
  @PUT
  @Path("/consents")
  public Response choose(@PathParam("customerId") UUID customerId, ChooseRequest req) {
    Validations.validate(req);
    List<Choice> choices =
        req.choices().stream()
            .map((ChoiceRequest c) -> new Choice(c.purpose(), c.granted()))
            .toList();
    svc.choose(
        ctx.requireTenantId(),
        customerId,
        choices,
        req.language(),
        Privacy.SOURCE_STAFF,
        ctx.requireUserId());
    return consents(customerId);
  }

  @Operation(
      summary = "Record a parent's or guardian's consent for a child",
      description =
          "With how the parent's identity and age were verified: details the business already"
              + " holds, a document seen over the counter, or a Digital Locker token (DPDP Rules"
              + " r.10). The reference notes what was seen, never a document's number. Owners and"
              + " managers.")
  @APIResponse(responseCode = "200", description = "The consent as recorded")
  @APIResponse(
      responseCode = "400",
      description = "No name, an unknown verification, or a number as the reference")
  @APIResponse(responseCode = "404", description = "No such customer")
  @APIResponse(responseCode = "409", description = "The person is of age")
  @POST
  @Path("/guardian")
  public Response recordGuardian(
      @PathParam("customerId") UUID customerId, RecordGuardianRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                PrivacyMappers.toDto(
                    svc.recordGuardian(
                        ctx.requireTenantId(),
                        customerId,
                        req.guardianName(),
                        req.verification(),
                        req.reference(),
                        ctx.requireUserId()))))
        .build();
  }

  @Operation(
      summary = "Withdraw a guardian's consent",
      description =
          "The child's tracking consents fall with it, each withdrawal recorded. Owners and managers.")
  @APIResponse(responseCode = "200", description = "The consent as withdrawn")
  @APIResponse(responseCode = "404", description = "No guardian's consent stands")
  @DELETE
  @Path("/guardian")
  public Response withdrawGuardian(@PathParam("customerId") UUID customerId) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    return Response.ok(
            ApiResponse.ok(
                PrivacyMappers.toDto(
                    svc.withdrawGuardian(ctx.requireTenantId(), customerId, ctx.requireUserId()))))
        .build();
  }
}
