package com.storeql.customer.api;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Privacy;
import com.storeql.customer.dto.PrivacyDtos.ChoiceRequest;
import com.storeql.customer.dto.PrivacyDtos.ChooseRequest;
import com.storeql.customer.dto.PrivacyDtos.OpenRequestRequest;
import com.storeql.customer.mapper.PrivacyMappers;
import com.storeql.customer.service.CustomerService;
import com.storeql.customer.service.PrivacyService;
import com.storeql.customer.service.PrivacyService.Choice;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A signed-in shopper's own privacy (13.12), keyed on the token's login like {@code /customers/me}:
 * what they consented to, purpose by purpose; withdrawal in one step; what they asked for.
 */
@RequestScoped
@Path("/customers/me/privacy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Privacy")
public class MyPrivacyResource {

  @Inject PrivacyService svc;
  @Inject CustomerService customers;
  @Inject TenantContext ctx;

  @Operation(
      summary = "My consents",
      description =
          "Every purpose, granted or not, against the notice version read; whether I am a child by"
              + " the date of birth held, and whether a guardian's consent stands.")
  @APIResponse(responseCode = "404", description = "This shop holds no record for the caller")
  @GET
  public Response mine() {
    return Response.ok(
            ApiResponse.ok(PrivacyMappers.toDto(svc.consents(ctx.requireTenantId(), me().id()))))
        .build();
  }

  @Operation(
      summary = "Give or withdraw consent, purpose by purpose",
      description =
          "Free, specific, informed, unconditional and unambiguous (DPDP Act s.6): each purpose on"
              + " its own, against the notice in the language read. A child's tracking purposes"
              + " are refused without a guardian's consent (s.9).")
  @APIResponse(responseCode = "200", description = "My consents as they now stand")
  @APIResponse(responseCode = "400", description = "A purpose that does not exist, or none")
  @APIResponse(
      responseCode = "409",
      description = "A child without a guardian's consent; or, where the Act binds, no notice yet")
  @PUT
  @Path("/consents")
  public Response choose(ChooseRequest req) {
    Validations.validate(req);
    List<Choice> choices =
        req.choices().stream()
            .map((ChoiceRequest c) -> new Choice(c.purpose(), c.granted()))
            .toList();
    return Response.ok(ApiResponse.ok(PrivacyMappers.toDto(view(choices, req.language())))).build();
  }

  @Operation(
      summary = "Withdraw every consent, in one step",
      description = "As easy as giving it (s.6(4)): one call, no confirmation, nothing to fill in.")
  @APIResponse(responseCode = "200", description = "My consents, every one withdrawn")
  @DELETE
  @Path("/consents")
  public Response withdrawAll() {
    Customer c = me();
    svc.withdrawAll(ctx.requireTenantId(), c.id(), null);
    return Response.ok(
            ApiResponse.ok(PrivacyMappers.toDto(svc.consents(ctx.requireTenantId(), c.id()))))
        .build();
  }

  @Operation(summary = "What I asked for, newest first")
  @GET
  @Path("/requests")
  public Response requests() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    return Response.ok(
            ApiResponse.ok(
                svc.requestsOf(ctx.requireTenantId(), me().id()).stream()
                    .map(r -> PrivacyMappers.toDto(r, today))
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Ask for my rights",
      description =
          "ACCESS to what is held, CORRECTION, ERASURE, a NOMINATION of who may act for me, or a"
              + " GRIEVANCE (ss.11–14). Due within the period the business publishes.")
  @APIResponse(responseCode = "201", description = "The request, with the day it is due by")
  @APIResponse(
      responseCode = "400",
      description = "A kind that does not exist, or a nomination naming nobody")
  @APIResponse(responseCode = "409", description = "Twenty requests already open")
  @POST
  @Path("/requests")
  public Response open(OpenRequestRequest req) {
    Validations.validate(req);
    var r =
        svc.openRequest(
            ctx.requireTenantId(),
            me().id(),
            req.kind(),
            req.detail(),
            req.nomineeName(),
            req.nomineeContact());
    return Response.status(201)
        .entity(ApiResponse.ok(PrivacyMappers.toDto(r, LocalDate.now(ZoneOffset.UTC))))
        .build();
  }

  private PrivacyService.ConsentsView view(List<Choice> choices, String language) {
    Customer c = me();
    svc.choose(
        ctx.requireTenantId(), c.id(), choices, language, Privacy.SOURCE_PREFERENCE_CENTRE, null);
    return svc.consents(ctx.requireTenantId(), c.id());
  }

  private Customer me() {
    return customers.getByLogin(ctx.requireTenantId(), ctx.requireUserId());
  }
}
