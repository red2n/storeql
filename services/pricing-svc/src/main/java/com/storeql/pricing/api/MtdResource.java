package com.storeql.pricing.api;

import com.storeql.pricing.dto.Dtos;
import com.storeql.pricing.mapper.Mappers;
import com.storeql.pricing.service.MtdService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
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
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Making Tax Digital for VAT (18.5): the registration, HMRC's grant, the obligations and the
 * filings. Not under {@code /admin/}, so every method requires a management role itself — the
 * default-deny filter lets any staff role mutate, and a cashier must not file a VAT return.
 */
@RequestScoped
@Path("/vat-return/mtd")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "VAT Return")
public class MtdResource {

  @Inject MtdService svc;
  @Inject TenantContext ctx;

  private void management() {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
  }

  @Operation(
      summary = "The VAT number this business files under, and how",
      description =
          "The registration, whether HMRC's grant is held, and what the deployment offers: the"
              + " providers available (SIMULATED always; HMRC when the application's credentials"
              + " and a token key are configured). registered is false until a number is"
              + " registered — the offer is still named. Management-only.")
  @APIResponse(responseCode = "200", description = "The registration, or the offer")
  @GET
  @Path("/registration")
  public Response registration() {
    management();
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.registration(ctx.requireTenantId()))))
        .build();
  }

  @Operation(
      summary = "Register the VAT number and the path to HMRC",
      description =
          "vrn is nine digits with HMRC's check digit (a GB prefix and spaces are allowed);"
              + " provider is SIMULATED or HMRC. Changing the number drops HMRC's grant, which is"
              + " for one number. The VAT return is HMRC's, in pounds: only a business whose own"
              + " profile says its home country is GB and its currency GBP may register for it."
              + " Management-only.")
  @APIResponse(responseCode = "200", description = "The registration as it now stands")
  @APIResponse(
      responseCode = "400",
      description = "A number that fails the check, or an unknown provider")
  @APIResponse(
      responseCode = "409",
      description =
          "VAT_RETURN_NOT_AVAILABLE: the business is not a UK one (home country GB, currency GBP);"
              + " or MTD_PROVIDER_NOT_CONFIGURED: the provider is not configured on this deployment")
  @APIResponse(
      responseCode = "503",
      description =
          "TENANT_PROFILE_UNAVAILABLE: the business's country and currency could not be read, and"
              + " nothing is assumed")
  @PUT
  @Path("/registration")
  public Response register(Dtos.RegisterVatRequest req) {
    management();
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    svc.register(ctx.requireTenantId(), req.vrn(), req.provider(), ctx.userId()))))
        .build();
  }

  @Operation(
      summary = "Where to send the manager to grant HMRC access",
      description =
          "HMRC's OAuth authorisation URL for this application, with the VAT scopes, returning to"
              + " redirectUri with a code. Management-only; HMRC registrations only.")
  @APIResponse(responseCode = "200", description = "The URL")
  @APIResponse(responseCode = "409", description = "The business files through a simulator")
  @GET
  @Path("/hmrc/authorize-url")
  public Response authorizeUrl(@QueryParam("redirectUri") String redirectUri) {
    management();
    return Response.ok(
            ApiResponse.ok(Map.of("url", svc.authorizeUrl(ctx.requireTenantId(), redirectUri))))
        .build();
  }

  @Operation(
      summary = "Exchange HMRC's code for the grant",
      description =
          "Completes the OAuth flow: the code HMRC returned is exchanged for the taxpayer's tokens,"
              + " which are held encrypted. Management-only.")
  @APIResponse(responseCode = "200", description = "The registration, now connected")
  @APIResponse(responseCode = "400", description = "HMRC refused the code")
  @POST
  @Path("/hmrc/connect")
  public Response connect(Dtos.HmrcConnectRequest req) {
    management();
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    svc.connect(
                        ctx.requireTenantId(), req.code(), req.redirectUri(), ctx.userId()))))
        .build();
  }

  @Operation(
      summary = "The periods this business must file for",
      description =
          "HMRC's obligations between two dates: each period's key, its start and end, when it is"
              + " due, and whether it is open or fulfilled. Management-only.")
  @APIResponse(responseCode = "200", description = "The obligations, oldest first")
  @APIResponse(responseCode = "404", description = "Not registered")
  @GET
  @Path("/obligations")
  public Response obligations(@QueryParam("from") String from, @QueryParam("to") String to) {
    management();
    if (from == null || from.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_FROM", "from query param required (ISO-8601)");
    if (to == null || to.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_TO", "to query param required (ISO-8601)");
    return Response.ok(
            ApiResponse.ok(
                svc
                    .obligations(
                        ctx.requireTenantId(),
                        Parsing.instant(from, "from"),
                        Parsing.instant(to, "to"))
                    .stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "File the return for a period",
      description =
          "Computes the nine boxes for the period from this business's own records — the same"
              + " figures GET /vat-return shows — rounds boxes 6 to 9 to whole pounds as the API"
              + " requires, and files them under the registered number with the taxpayer's"
              + " declaration that they are final. What HMRC answers is recorded, accepted or"
              + " refused, where nothing edits it. client carries what the browser collected for"
              + " HMRC's fraud-prevention headers. A business that is not a UK one (home country GB,"
              + " currency GBP, by its own profile) files nothing, whatever is on file."
              + " Management-only.")
  @APIResponse(responseCode = "201", description = "Accepted: the filing with HMRC's receipt")
  @APIResponse(
      responseCode = "400",
      description = "Not declared final, a bad period key, or a period that ends before it starts")
  @APIResponse(
      responseCode = "409",
      description =
          "MTD_DUPLICATE_SUBMISSION: this period is already filed and accepted;"
              + " VAT_RETURN_NOT_AVAILABLE: the business is not a UK one")
  @APIResponse(responseCode = "422", description = "HMRC refused the return; the code is HMRC's")
  @APIResponse(
      responseCode = "503",
      description =
          "HMRC could not be reached (the filing is recorded as refused), or"
              + " TENANT_PROFILE_UNAVAILABLE: the business's country and currency could not be read")
  @POST
  @Path("/submissions")
  public Response submit(Dtos.SubmitVatReturnRequest req) {
    management();
    Validations.validate(req);
    var filed =
        svc.submit(
            ctx.requireTenantId(),
            req.periodKey(),
            Parsing.instant(req.from(), "from"),
            Parsing.instant(req.to(), "to"),
            Boolean.TRUE.equals(req.finalised()),
            new MtdService.ClientFingerprint(req.client()),
            ctx.userId());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(filed))).build();
  }

  @Operation(
      summary = "Every return filed",
      description = "Newest first, accepted and refused alike. Management-only.")
  @APIResponse(responseCode = "200", description = "The filings")
  @GET
  @Path("/submissions")
  public Response submissions() {
    management();
    return Response.ok(
            ApiResponse.ok(
                svc.submissions(ctx.requireTenantId()).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(summary = "One filing", description = "Management-only; another tenant's is 404.")
  @APIResponse(responseCode = "200", description = "The filing")
  @APIResponse(responseCode = "404", description = "Not this business's")
  @GET
  @Path("/submissions/{id}")
  public Response submission(@PathParam("id") String id) {
    management();
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.submission(ctx.requireTenantId(), Parsing.uuid(id, "id")))))
        .build();
  }
}
