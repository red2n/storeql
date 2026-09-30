package com.storeql.tenant.api;

import com.storeql.tenant.dto.BillingDtos;
import com.storeql.tenant.mapper.BillingMappers;
import com.storeql.tenant.service.BillingService;
import com.storeql.tenant.service.SubscriptionService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What a business sees of what it pays the platform (21.9): the subscription it is on, its own
 * invoices, and the few things it may change itself.
 *
 * <p>Management's — what a business is charged is an owner's business and not a cashier's, and the
 * moves here commit money. The price list itself, the billing run and everybody's receivables are
 * the platform's: {@link PlatformBillingResource}.
 *
 * <p>Note what a business may <em>not</em> do here. It cannot record its own payment, void its own
 * invoice, or move itself onto a plan it has not been sold — those would be a business writing its
 * own accounts.
 */
@Path("/admin/tenant/billing")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Billing")
public class TenantBillingResource {

  @Inject SubscriptionService subscriptions;
  @Inject BillingService billing;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The subscription this business is on, and everything that has happened to it",
      description =
          "What it pays, for which period, what happens at the end of it, and whether its VAT number"
              + " has been checked — which decides whether it is charged VAT at all.")
  @GET
  public ApiResponse<BillingDtos.SubscriptionFileResponse> mine() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(BillingMappers.toDto(subscriptions.file(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Sets where the business is, for billing",
      description =
          "Where it is established, which is not the same question as where its shops are: it"
              + " decides the VAT treatment and prints on every invoice from here on. Invoices"
              + " already issued keep the address they were issued with.")
  @PUT
  @Path("/details")
  public ApiResponse<BillingDtos.SubscriptionFileResponse> details(
      @Valid BillingDtos.BuyerRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        BillingMappers.toDto(
            subscriptions.setDetails(ctx.requireTenantId(), req, ctx.requireUserId())));
  }

  @Operation(
      summary = "The invoices this business has been sent",
      description = "Its own and nobody else's, newest first.")
  @GET
  @Path("/invoices")
  public ApiResponse<List<BillingDtos.InvoiceResponse>> invoices(
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        BillingMappers.invoices(billing.invoicesOf(ctx.requireTenantId(), limit)));
  }

  @Operation(
      summary = "One of its own invoices, with everything printed on it",
      description =
          "Another business's invoice is not found here: the read is keyed on the business the token"
              + " names, so an id from elsewhere answers 404 rather than 403, which tells a guesser"
              + " nothing about whether it exists.")
  @GET
  @Path("/invoices/{invoiceId}")
  public ApiResponse<BillingDtos.InvoiceFileResponse> invoice(
      @PathParam("invoiceId") UUID invoiceId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        BillingMappers.toDto(billing.ownInvoiceFile(ctx.requireTenantId(), invoiceId)));
  }

  @Operation(
      summary = "Moves the business to another plan",
      description =
          "Up now, down at the end of the period. An upgrade bills the days left on the new price"
              + " against what was billed for them on the old one — two lines, a credit and a"
              + " charge, so the arithmetic can be checked. A downgrade waits for the period"
              + " already paid for, and is refused if what the business is using would not fit it.")
  @POST
  @Path("/plan")
  public ApiResponse<BillingDtos.SubscriptionFileResponse> changePlan(
      @Valid BillingDtos.PlanChangeRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        BillingMappers.toDto(
            subscriptions.changePlan(ctx.requireTenantId(), req, ctx.requireUserId())));
  }

  @Operation(
      summary = "Ends the subscription when the period it has paid for runs out",
      description =
          "Not now: it has paid to the end of the period, and taking the platform away early would"
              + " be keeping money for a service withdrawn.")
  @POST
  @Path("/cancel")
  public ApiResponse<BillingDtos.SubscriptionFileResponse> cancel(
      @Valid BillingDtos.CancelRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        BillingMappers.toDto(
            subscriptions.cancelAtPeriodEnd(
                ctx.requireTenantId(), req == null ? null : req.reason(), ctx.requireUserId())));
  }

  @Operation(summary = "Takes back a cancellation, while the period it would have ended in runs")
  @POST
  @Path("/resume")
  public ApiResponse<BillingDtos.SubscriptionFileResponse> resume() {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        BillingMappers.toDto(subscriptions.keepGoing(ctx.requireTenantId(), ctx.requireUserId())));
  }

  @Operation(summary = "Drops a plan change that was waiting for the end of the period")
  @POST
  @Path("/plan/cancel-pending")
  public ApiResponse<BillingDtos.SubscriptionFileResponse> dropPending() {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        BillingMappers.toDto(
            subscriptions.dropPendingChange(ctx.requireTenantId(), ctx.requireUserId())));
  }
}
