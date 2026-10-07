package com.storeql.purchase.api;

import com.storeql.purchase.domain.EInvoiceInbox.Fetch;
import com.storeql.purchase.service.BusinessWide;
import com.storeql.purchase.service.EInvoiceFetchService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/e-invoices/inbox}: fetching a Polish buyer's invoices (07.13).
 *
 * <p>Separate from {@code /e-invoices} because it is a different act. That resource is where
 * documents <em>arrive</em> — uploaded by a person, delivered by a network. This one is where the
 * platform <b>goes and asks</b>, which is the only way a Polish buyer receives anything: KSeF holds
 * a business's invoices until the business fetches them.
 */
@RequestScoped
@Path("/admin/e-invoices/inbox")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "E-Invoice Inbox")
public class EInvoiceInboxResource {

  @Inject EInvoiceFetchService svc;
  @Inject TenantContext ctx;

  @Schema(name = "EInvoiceInboxSettings")
  public record SettingsResponse(
      @Schema(description = "NONE or KSEF.") String network,
      @Schema(description = "NONE, SIMULATED or KSEF.") String provider,
      @Schema(description = "The business at the network: its NIP for KSeF.")
          String providerAccount,
      @Schema(description = "Whether a credential is held. It is never shown again.")
          boolean hasSecret,
      @Schema(description = "The day the last fetch reached; the next asks from here.")
          String fetchedTo,
      String lastFetchAt,
      String lastFetchNote,
      @Schema(description = "Whether this deployment can reach KSeF at all.")
          boolean deploymentConfigured,
      @Schema(description = "What the deployment needs for the real network.") String requires) {}

  @Schema(name = "SetEInvoiceInboxRequest")
  public record SetSettingsRequest(
      @Schema(description = "NONE or KSEF.") @NotBlank @Size(max = 20) String network,
      @Schema(description = "SIMULATED or KSEF; required unless the network is NONE.")
          @Size(max = 20)
          String provider,
      @Schema(description = "The business's NIP.") @Size(max = 40) String providerAccount,
      @Schema(
              description =
                  "The business's KSeF token. Sealed and never shown again; leave out to keep the"
                      + " one stored, blank to remove it.")
          @Size(max = 400)
          String secret) {}

  @Schema(name = "EInvoiceFetchResult")
  public record FetchResponse(
      String from,
      String to,
      @Schema(description = "How many the network was holding.") int waiting,
      @Schema(description = "How many reached the inbox.") int received,
      @Schema(description = "How many the inbox already had — the ordinary case on a repeat.")
          int alreadyHeld,
      @Schema(description = "How many the inbox would not take, with the reasons in notes.")
          int refused,
      List<String> notes) {

    /** Its own copy: a record that hands back the caller's list is a record that can change. */
    public FetchResponse {
      notes = notes == null ? List.of() : List.copyOf(notes);
    }
  }

  @Operation(
      summary = "Where this business fetches its invoices from",
      description =
          "KSeF is the one network that is asked rather than delivered from: a Polish buyer's invoices"
              + " sit in the ministry's system until the buyer fetches them. The credential is never"
              + " returned — only whether one is held.")
  @APIResponse(
      responseCode = "403",
      description = "Not management, or BUSINESS_WIDE_ONLY: a caller held to stores")
  @GET
  @Path("/settings")
  public ApiResponse<SettingsResponse> settings() {
    businessWideManagement();
    return ApiResponse.ok(toDto(svc.settings(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Choose where this business fetches from",
      description =
          "KSeF signs a business in as itself, by NIP and with its own token, so both are required for"
              + " the real network. SIMULATED is the platform standing in: nothing leaves it, so"
              + " nothing arrives, and a fetch says so rather than answering as an empty success.")
  @APIResponse(
      responseCode = "400",
      description = "An unknown network or provider, or an invalid NIP")
  @APIResponse(
      responseCode = "409",
      description = "The deployment cannot reach KSeF, or no token is held")
  @APIResponse(
      responseCode = "403",
      description = "Not management, or BUSINESS_WIDE_ONLY: a caller held to stores")
  @PUT
  @Path("/settings")
  public ApiResponse<SettingsResponse> setSettings(SetSettingsRequest req) {
    businessWideManagement();
    Validations.validate(req);
    return ApiResponse.ok(
        toDto(
            svc.setSettings(
                ctx.requireTenantId(),
                req.network(),
                req.provider(),
                req.providerAccount(),
                req.secret(),
                ctx.requireUserId())));
  }

  @Schema(name = "EInvoiceInboxReadiness")
  public record ReadinessResponse(
      String network,
      String provider,
      @Schema(description = "True when everything holds and the ministry signed the business in.")
          boolean ready,
      @Schema(description = "What is still missing, in the order worth doing it.")
          List<String> outstanding,
      @Schema(
              description =
                  "READY, UNREACHABLE or REFUSED; absent while something is still missing.")
          String networkState,
      String networkDetail) {

    public ReadinessResponse {
      outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
    }
  }

  @Operation(
      summary = "What stands between this business and its first fetched invoice",
      description =
          "The network **asked**, not assumed: the ministry unreachable, its keys unreadable and a"
              + " token refused are three different problems with three different remedies. Nothing is"
              + " fetched. For the day a KSeF token is issued, so a shop knows whether tomorrow's"
              + " invoices will arrive rather than finding out from an inbox that stays empty.")
  @APIResponse(
      responseCode = "403",
      description = "Not management, or BUSINESS_WIDE_ONLY: a caller held to stores")
  @GET
  @Path("/readiness")
  public ApiResponse<ReadinessResponse> readiness() {
    businessWideManagement();
    var r = svc.readiness(ctx.requireTenantId());
    return ApiResponse.ok(
        new ReadinessResponse(
            r.network(), r.provider(), r.ready(), r.outstanding(), r.state(), r.detail()));
  }

  @Operation(
      summary = "Fetch what the network is holding",
      description =
          "Asks for a window of days and takes everything in it, each document going through the same"
              + " intake an uploaded one does — read, checked, matched, captured or left waiting with"
              + " the reason. A Polish invoice arrives as FA(3), which is read into the same model as"
              + " any other, so nothing downstream knows the difference. The window carries on from"
              + " the last fetch unless one is given; a document already held is counted and not taken"
              + " twice; and one the inbox will not take is named while the rest are still taken.")
  @APIResponse(responseCode = "200", description = "What the fetch came to")
  @APIResponse(responseCode = "409", description = "The business fetches from nowhere")
  @APIResponse(responseCode = "422", description = "The network could not be reached, or refused")
  @APIResponse(
      responseCode = "403",
      description = "Not management, or BUSINESS_WIDE_ONLY: a caller held to stores")
  @POST
  @Path("/fetch")
  public Response fetch(@QueryParam("from") String from, @QueryParam("to") String to) {
    businessWideManagement();
    Fetch f = svc.fetch(ctx, day(from, "from"), day(to, "to"));
    return Response.ok(
            ApiResponse.ok(
                new FetchResponse(
                    f.from().toString(),
                    f.to().toString(),
                    f.waiting(),
                    f.received(),
                    f.alreadyHeld(),
                    f.refused(),
                    f.notes())))
        .build();
  }

  /**
   * Management, held to no store. The inbox is the business's — one connection, one credential, the
   * business's identity at the network, and a fetch that takes every store's invoices and moves the
   * business's own cursor — so a manager held to stores is refused, as for the business's other
   * settings.
   *
   * @throws ApiException 403 for anyone but management; 403 {@code BUSINESS_WIDE_ONLY} for a caller
   *     held to stores
   */
  private void businessWideManagement() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    BusinessWide.require(
        ctx,
        "The e-invoice inbox's connection and its fetch are the whole business's; they need a"
            + " caller who is not held to stores");
  }

  private static SettingsResponse toDto(EInvoiceFetchService.SettingsView v) {
    var s = v.settings();
    return new SettingsResponse(
        s.network(),
        s.provider(),
        s.providerAccount(),
        v.hasSecret(),
        s.fetchedTo() == null ? null : s.fetchedTo().toString(),
        s.lastFetchAt() == null ? null : s.lastFetchAt().toString(),
        s.lastFetchNote(),
        v.deploymentConfigured(),
        v.requires());
  }

  private static LocalDate day(String value, String field) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.strip());
    } catch (RuntimeException e) {
      throw new ApiException(
          400, "PURCHASE_INBOX_DATE_INVALID", field + " is written as 2026-09-01", List.of(), e);
    }
  }
}
