package com.storeql.notification.api;

import com.storeql.notification.dto.TemplateDtos;
import com.storeql.notification.service.MessageTemplateService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A business putting its messages in its own words (13.x): owners and managers. The tenant is the
 * token's; there is no way to name another business's templates.
 */
@Path("/admin/notifications")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Message templates")
public class MessageTemplateResource {

  private static final String ONE = "/templates/{type}/{form}/{language}";

  @Inject MessageTemplateService templates;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Every message the business can put in its own words",
      description =
          "Each message, the forms it goes out in, the values a template can use, the parts it"
              + " must keep, and the languages the business has written it in.")
  @GET
  @Path("/templates")
  public ApiResponse<List<TemplateDtos.MessageView>> catalogue() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(templates.catalogue(ctx.requireTenantId()));
  }

  @Operation(
      summary = "The words a message goes out in now",
      description =
          "The words a message in that language goes out in, and that language's own history. The"
              + " business's live version (source BUSINESS); or, when it has none in that language"
              + " (never written, or retired) or its live version there cannot be used"
              + " (storedWordsUnusable: its words do not parse), its live version in its default"
              + " language (source DEFAULT_LANGUAGE, wordsLanguage saying which); or the platform's"
              + " words (source DEFAULT).")
  @APIResponse(responseCode = "404", description = "No such message, or not sent in that form")
  @GET
  @Path(ONE)
  public ApiResponse<TemplateDtos.TemplateView> get(
      @PathParam("type") String type,
      @PathParam("form") String form,
      @PathParam("language") String language) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(templates.get(ctx.requireTenantId(), type, form, language));
  }

  @Operation(
      summary = "Save the business's words for a message, form and language",
      description =
          "Writes the next version; the one before is kept, retired. Refused when it does not"
              + " parse, uses a value the message does not have, leaves out a part the message"
              + " must keep (422 TEMPLATE_PART_REQUIRED), or does not fit the form.")
  @APIResponse(responseCode = "400", description = "Does not parse, an unknown value, too long")
  @APIResponse(responseCode = "422", description = "A part the message must keep is missing")
  @PUT
  @Path(ONE)
  public ApiResponse<TemplateDtos.TemplateView> put(
      @PathParam("type") String type,
      @PathParam("form") String form,
      @PathParam("language") String language,
      TemplateDtos.TemplateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        templates.put(ctx.requireTenantId(), ctx.requireUserId(), type, form, language, req));
  }

  @Operation(
      summary = "Go back to the platform's words",
      description = "Retires the live version; it stays in the history.")
  @APIResponse(responseCode = "404", description = "The business has written nothing here")
  @DELETE
  @Path(ONE)
  public ApiResponse<String> retire(
      @PathParam("type") String type,
      @PathParam("form") String form,
      @PathParam("language") String language) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    templates.retire(ctx.requireTenantId(), ctx.requireUserId(), type, form, language);
    return ApiResponse.ok("retired");
  }

  @Operation(
      summary = "Write out a draft with sample values",
      description =
          "Nothing is saved. Answers the message as a reader would see it, how many texts an SMS"
              + " takes, and what would stop it being saved.")
  @POST
  @Path(ONE + "/preview")
  public ApiResponse<TemplateDtos.Preview> preview(
      @PathParam("type") String type,
      @PathParam("form") String form,
      @PathParam("language") String language,
      TemplateDtos.TemplateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(templates.preview(ctx.requireTenantId(), type, form, language, req));
  }

  @Operation(summary = "How the business's messages are signed, and their language")
  @GET
  @Path("/template-settings")
  public ApiResponse<TemplateDtos.Settings> settings() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(templates.settings(ctx.requireTenantId()));
  }

  @Operation(
      summary = "Set how the business's messages are signed, and their language",
      description =
          "defaultLanguage is what messages go out in when the reader's is not known; signOff"
              + " replaces the business's own name at the foot of a customer's email.")
  @PUT
  @Path("/template-settings")
  public ApiResponse<TemplateDtos.Settings> putSettings(TemplateDtos.Settings req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(templates.putSettings(ctx.requireTenantId(), ctx.requireUserId(), req));
  }
}
