package com.storeql.tenant.api;

import com.storeql.tenant.dto.BillingDtos;
import com.storeql.tenant.dto.UsageDtos;
import com.storeql.tenant.mapper.BillingMappers;
import com.storeql.tenant.mapper.UsageMappers;
import com.storeql.tenant.service.BillingService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The platform's own billing (21.9): who it invoices as, the rates it has to charge, the run that
 * raises the invoices, and the money that comes back. Every route is {@code PLATFORM_ADMIN} — this
 * is the platform's own books. What a business sees of its own subscription is {@link
 * TenantBillingResource}.
 */
@Path("/platform/billing")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Billing")
public class PlatformBillingResource {

  @Inject BillingService svc;
  @Inject com.storeql.tenant.service.SubscriptionService subscriptions;
  @Inject com.storeql.tenant.service.UsageService usage;
  @Inject TenantContext ctx;

  /**
   * Whether a caller may name the day the billing run is for.
   *
   * <p>Off in production, on in compose. A year of periods has to be drivable in seconds for a live
   * flow to exist at all — Stripe ships a test clock for the same reason — but a platform
   * administrator being able to bill next March is not a feature, it is a way to issue an invoice
   * nobody owes yet. So the ability is configuration, not a role.
   */
  @Inject
  @ConfigProperty(name = "storeql.billing.test-clock.enabled", defaultValue = "false")
  boolean testClock;

  // ── who the platform invoices as ────────────────────────────────────────────

  @Operation(
      summary = "What the platform bills as",
      description = "Empty until it is set, and nothing is invoiced until then.")
  @GET
  @Path("/profile")
  public ApiResponse<BillingDtos.ProfileResponse> profile() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.toDto(svc.profile()));
  }

  @Operation(summary = "Sets what the platform bills as")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED: paymentTermsDays outside 0 to 180, a taxRate not below one or finer"
              + " than four places, a required field missing; BODY_REQUIRED")
  @APIResponse(responseCode = "403", description = "FORBIDDEN: not the platform administrator")
  @PUT
  @Path("/profile")
  public ApiResponse<BillingDtos.ProfileResponse> saveProfile(BillingDtos.ProfileRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(BillingMappers.toDto(svc.saveProfile(req, ctx.requireUserId())));
  }

  // ── the rates it has to charge where it has no choice ───────────────────────

  @Operation(
      summary = "The VAT rates the platform has set",
      description =
          "A business in another member state with no checked VAT number is charged its own"
              + " country's rate. A country with no rate here refuses the invoice rather than"
              + " guessing at one.")
  @GET
  @Path("/vat-rates")
  public ApiResponse<List<BillingDtos.RateResponse>> rates() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.rates(svc.rates()));
  }

  @Operation(summary = "Sets a country's rate from a date")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED: a rate not below one, below nothing or finer than four places;"
              + " BODY_REQUIRED; BILLING_DATE_INVALID")
  @APIResponse(responseCode = "403", description = "FORBIDDEN: not the platform administrator")
  @PUT
  @Path("/vat-rates")
  public ApiResponse<List<BillingDtos.RateResponse>> saveRate(BillingDtos.RateRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(BillingMappers.rates(svc.saveRate(req, ctx.requireUserId())));
  }

  // ── the evidence behind a reverse charge ────────────────────────────────────

  @Operation(
      summary = "Records that a business's VAT number has been checked",
      description =
          "The reverse charge rests on the number being real, so who checked it and when is kept —"
              + " an audit asks for the date, not for an assurance. Calling VIES is a seam, like the"
              + " e-invoicing networks: SIMULATED is the honest answer until there is a contract,"
              + " and it says on the record that nothing left the building. A business supplies its"
              + " own number; checking it is the platform's, and changing the number afterwards"
              + " discards the check.")
  @POST
  @Path("/tenants/{tenantId}/vat-check")
  public ApiResponse<BillingDtos.SubscriptionFileResponse> recordVatCheck(
      @PathParam("tenantId") UUID tenantId, BillingDtos.VatCheckRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(
        BillingMappers.toDto(
            subscriptions.recordVatCheck(
                tenantId, req.vatNumber(), req.source(), ctx.requireUserId())));
  }

  // ── the run ─────────────────────────────────────────────────────────────────

  @Operation(
      summary = "Raises the invoices that are due",
      description =
          "Every subscription whose period has run out is billed for the period it is entering. Safe"
              + " to run twice: a period is invoiced once, by a unique index, not by a check.")
  @POST
  @Path("/run")
  public ApiResponse<BillingDtos.RunResponse> run(@QueryParam("asOf") String asOf) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    LocalDate on = day(asOf);
    return ApiResponse.ok(BillingMappers.run(on, svc.run(on)));
  }

  // ── what is owed, and what has come in ──────────────────────────────────────

  // ── metered usage (21.10) ───────────────────────────────────────────────────

  @Operation(
      summary = "What one business has used this period, and before",
      description = "The same reading the business has, for the platform answering its questions.")
  @GET
  @Path("/tenants/{tenantId}/usage")
  public ApiResponse<UsageDtos.UsageResponse> usageOf(@PathParam("tenantId") UUID tenantId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(UsageMappers.toDto(usage.summary(tenantId)));
  }

  @Operation(
      summary = "The businesses nearing or past what their plan includes, newest first",
      description =
          "Every threshold reached — 80%, then 100% of a meter's allowance, once per period — across"
              + " businesses: who is about to outgrow a plan, before the invoice says so.")
  @GET
  @Path("/usage-alerts")
  public ApiResponse<List<UsageDtos.TenantAlertView>> usageAlerts(
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        usage.recentAlerts(limit).stream().map(UsageMappers::acrossBusinesses).toList());
  }

  @Operation(
      summary = "The invoices that are still owed, oldest first",
      description = "The platform's receivables: open invoices, with the overdue ones first.")
  @GET
  @Path("/receivables")
  public ApiResponse<List<BillingDtos.InvoiceResponse>> receivables(
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.invoices(svc.receivables(limit)));
  }

  @Operation(summary = "One invoice, with its lines, its payments and both snapshots")
  @GET
  @Path("/invoices/{invoiceId}")
  public ApiResponse<BillingDtos.InvoiceFileResponse> invoice(
      @PathParam("invoiceId") UUID invoiceId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.toDto(svc.invoiceFile(invoiceId)));
  }

  @Operation(
      summary = "Records money received against an invoice",
      description =
          "Append-only, and it settles the invoice when what has been paid reaches the total. An"
              + " invoice already withdrawn takes no payment. The amount is in the invoice's"
              + " currency, to no more places than its minor units.")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED or BODY_REQUIRED; PAYMENT_METHOD_UNKNOWN; BILLING_AMOUNT_INVALID: finer"
              + " than the invoice currency's minor units (100.50 on a JPY invoice)")
  @APIResponse(responseCode = "403", description = "FORBIDDEN: not the platform administrator")
  @APIResponse(responseCode = "404", description = "INVOICE_NOT_FOUND")
  @APIResponse(
      responseCode = "409",
      description = "INVOICE_NOT_OPEN: withdrawn, given up on, or already paid")
  @POST
  @Path("/invoices/{invoiceId}/payments")
  public ApiResponse<BillingDtos.InvoiceFileResponse> recordPayment(
      @PathParam("invoiceId") UUID invoiceId, BillingDtos.RecordPaymentRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(
        BillingMappers.toDto(svc.recordPayment(invoiceId, req, ctx.requireUserId())));
  }

  @Operation(
      summary = "Withdraws an unpaid invoice",
      description =
          "It keeps its number and its place in the sequence — a gap is what an auditor asks about."
              + " An invoice that has taken money is not withdrawn; it is credited.")
  @POST
  @Path("/invoices/{invoiceId}/void")
  public ApiResponse<BillingDtos.InvoiceFileResponse> voidInvoice(
      @PathParam("invoiceId") UUID invoiceId, BillingDtos.VoidRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(BillingMappers.toDto(svc.voidInvoice(invoiceId, req.reason())));
  }

  /**
   * The day a run is for.
   *
   * @throws ApiException 403 {@code BILLING_TEST_CLOCK_DISABLED} when a caller names a day and this
   *     deployment does not allow it; 400 when the day is not a date
   */
  private LocalDate day(String asOf) {
    if (asOf == null || asOf.isBlank()) return LocalDate.now();
    if (!testClock) {
      throw ApiException.forbidden(
          "BILLING_TEST_CLOCK_DISABLED",
          "This deployment bills for today only; naming a day is a test facility and is switched"
              + " off here");
    }
    try {
      return LocalDate.parse(asOf.strip());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400, "BILLING_DATE_INVALID", "asOf is a date, as 2026-09-18", List.of(), e);
    }
  }

  @Inject com.storeql.tenant.service.DunningService dunning;

  @Operation(
      summary = "How hard the platform chases what it is owed",
      description =
          "A platform that has set no policy still chases, on the published defaults: reminders on"
              + " days 1, 3, 5 and 7 after the due date, the service interrupted at 14, the debt"
              + " given up on at 30. It says so rather than inventing somebody who set them.")
  @GET
  @Path("/dunning/policy")
  public ApiResponse<BillingDtos.DunningPolicyResponse> dunningPolicy() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.toDto(dunning.policy()));
  }

  @Operation(
      summary = "Sets how hard the platform chases",
      description =
          "The order of the stages is part of the policy and is refused if it is wrong: the service"
              + " cannot be interrupted before the last reminder has gone, and a debt cannot be given"
              + " up on before the service was interrupted.")
  @PUT
  @Path("/dunning/policy")
  public ApiResponse<BillingDtos.DunningPolicyResponse> setDunningPolicy(
      BillingDtos.DunningPolicyRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(
        BillingMappers.toDto(dunning.setPolicy(BillingMappers.policy(req), ctx.requireUserId())));
  }

  @Operation(
      summary = "Chases everything that is overdue",
      description =
          "Every notice a business missed is sent, once — a run that has not run for a week owes the"
              + " business each reminder it did not get, because suspending a business the platform"
              + " never finished telling was late is the opposite of what dunning is for. Safe to run"
              + " twice: a step happens once by a unique index. A business it cannot act on is named"
              + " in `skipped` rather than stopping the rest.")
  @POST
  @Path("/dunning/run")
  public ApiResponse<BillingDtos.DunningRunResponse> runDunning(@QueryParam("asOf") String asOf) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    LocalDate on = day(asOf);
    return ApiResponse.ok(BillingMappers.dunningRun(on, dunning.run(on)));
  }

  @Operation(
      summary = "What is overdue, most overdue first",
      description = "With the stage each invoice has reached and what it earns next.")
  @GET
  @Path("/dunning/overdue")
  public ApiResponse<List<BillingDtos.OverdueResponse>> overdue(
      @QueryParam("asOf") String asOf, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        BillingMappers.overdue(dunning.overdue(day(asOf), limit == null ? 50 : limit)));
  }

  @Operation(summary = "What has been done about one overdue invoice, oldest first")
  @GET
  @Path("/dunning/invoices/{invoiceId}/events")
  public ApiResponse<List<BillingDtos.DunningEventResponse>> dunningEvents(
      @PathParam("invoiceId") UUID invoiceId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.dunningEvents(dunning.eventsOf(invoiceId)));
  }

  @Operation(
      summary = "The link that pays one invoice without a sign-in",
      description =
          "What a notice carries. A suspended business cannot sign in, so telling it to pay while"
              + " denying it the means would be a dead end. A fresh token each time this is asked,"
              + " and only its hash is kept.")
  @GET
  @Path("/dunning/invoices/{invoiceId}/pay-link")
  public ApiResponse<BillingDtos.PayLinkResponse> payLink(@PathParam("invoiceId") UUID invoiceId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(new BillingDtos.PayLinkResponse(dunning.issuePayToken(invoiceId)));
  }

  @Operation(
      summary = "Moves an invoice's due date out",
      description =
          "A promise to pay, which pauses the chase without forgiving the debt. Outwards only, and"
              + " only on an invoice still owed: a date that could move inwards would shorten the"
              + " time a business has to pay after the fact. Recorded, so an extension is on the file"
              + " rather than a due date that quietly moved.")
  @PUT
  @Path("/invoices/{invoiceId}/due-date")
  public ApiResponse<BillingDtos.InvoiceResponse> extendDueDate(
      @PathParam("invoiceId") UUID invoiceId, BillingDtos.ExtendDueDateRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(
        BillingMappers.toDto(
            dunning.extendDueDate(
                invoiceId, date(req.dueDate(), "dueDate"), req.reason(), ctx.requireUserId())));
  }

  /**
   * A date the request names as data, such as an invoice's new due date: read as written, whatever
   * the test clock says. The test clock governs only the day a run is for ({@link #day}); a due
   * date read through it was refused in every deployment that bills for today only.
   *
   * @throws ApiException 400 {@code BILLING_DATE_INVALID} when it is not a date
   */
  private static LocalDate date(String value, String field) {
    try {
      return LocalDate.parse(value.strip());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400, "BILLING_DATE_INVALID", field + " is a date, as 2026-09-18", List.of(), e);
    }
  }
}
