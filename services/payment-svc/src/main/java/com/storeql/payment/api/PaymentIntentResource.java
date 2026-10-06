package com.storeql.payment.api;

import com.storeql.payment.dto.Dtos.CreatePaymentIntentRequest;
import com.storeql.payment.mapper.Mappers;
import com.storeql.payment.service.PaymentIntentService;
import com.storeql.web.ApiResponse;
import com.storeql.web.HttpHeaders;
import com.storeql.web.IdempotencyKeys;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Payment intents: authorising money with a provider, capturing it, and receiving the provider's
 * webhooks.
 *
 * <p>Thin, as every resource here is — validation, identity from the verified JWT, and delegation.
 */
@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
@Tag(name = "Payment intents")
public class PaymentIntentResource {

  @Inject PaymentIntentService svc;
  @Inject TenantContext ctx;

  /**
   * Opens an intent with the configured provider.
   *
   * @param idempotencyKey header key; takes precedence over the body's
   * @param req the order and amount
   * @return 201 with the intent, including any SCA redirect the customer must follow
   */
  @Operation(
      summary = "Open a payment intent",
      description =
          "Authorises the order total with the configured provider. The response carries"
              + " nextActionUrl when the customer must complete SCA / 3-D Secure. Replaying the"
              + " same Idempotency-Key returns the original intent rather than placing a second"
              + " hold on the customer's card.")
  @APIResponse(responseCode = "201", description = "Intent opened")
  @APIResponse(responseCode = "400", description = "Amount mismatch, or returnUrl not allowed")
  @APIResponse(
      responseCode = "404",
      description = "Order not found, not ONLINE, or not the caller's")
  @APIResponse(responseCode = "409", description = "Order is not awaiting payment")
  @APIResponse(
      responseCode = "422",
      description =
          "PAYMENT_AMOUNT_NOT_CHARGEABLE: an amount the provider cannot charge exactly in that"
              + " currency (Stripe: a dinar's last fils not 0, a fraction of a yen, krona or"
              + " shilling), refused before anything is recorded or the provider is asked, and"
              + " never rounded; details name the nearest it can charge"
              + " (currency=KWD;chargeableBelow=1.120;chargeableAbove=1.130)")
  @APIResponse(
      responseCode = "502",
      description = "PAYMENT_PROVIDER_UNAVAILABLE: the provider refused the authorisation")
  @APIResponse(responseCode = "503", description = "The provider could not be reached")
  @POST
  @Path("/intents")
  public Response create(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      CreatePaymentIntentRequest req) {
    Validations.validate(req);
    String key = IdempotencyKeys.effective(idempotencyKey, req.idempotencyKey());
    var intent = svc.create(req, ctx, key);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(intent))).build();
  }

  /**
   * @param id intent id
   * @return 200 with the intent
   */
  @Operation(
      summary = "Get a payment intent",
      description =
          "Staff may read any intent in their tenant; a customer may read only intents against"
              + " their own order. Denials are 404 so intent ids cannot be probed.")
  @APIResponse(responseCode = "200", description = "Intent found")
  @APIResponse(responseCode = "404", description = "Not found, or not the caller's")
  @GET
  @Path("/intents/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.get(ctx.requireTenantId(), id, ctx))))
        .build();
  }

  /**
   * Captures an authorised intent. Staff-only: taking money is a deliberate act by the business,
   * not something a shopper triggers.
   *
   * @param id intent id
   * @return 200 with the tender that records the captured money
   */
  @Operation(
      summary = "Capture an authorised payment intent",
      description =
          "Takes money previously authorised, writes the tender and emits PaymentCaptured."
              + " Capturing an already-captured intent returns the existing tender rather than"
              + " failing, so the call is safe to retry.")
  @APIResponse(responseCode = "200", description = "Captured")
  @APIResponse(responseCode = "404", description = "Intent not found")
  @APIResponse(responseCode = "409", description = "Intent is not AUTHORIZED")
  @APIResponse(responseCode = "502", description = "The provider refused the capture")
  @POST
  @Path("/intents/{id}/capture")
  public Response capture(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "CASHIER");
    var tender = svc.capture(ctx.requireTenantId(), id);
    return Response.ok(ApiResponse.ok(Mappers.toDto(tender))).build();
  }

  /**
   * Receives a provider webhook.
   *
   * <p>Consumes the raw bytes rather than a parsed DTO, because the signature is computed over
   * exactly what was sent: re-serialising a parsed body changes whitespace and key order and the
   * signature then never verifies. It carries no tenant and needs none — the intent the provider
   * names is what says which tenant this concerns.
   *
   * @param provider provider name in the path
   * @param headers request headers; the provider names which one carries its signature
   * @param rawBody the exact bytes received
   * @return 200 once applied, once recognised as a redelivery, or once judged not to be about an
   *     intent of ours; 404 for an event that names an intent of ours that is not known (yet), so
   *     that the provider delivers it again
   */
  @Operation(
      summary = "Provider webhook",
      description =
          "Public endpoint called by the payment provider. The request is authenticated by the"
              + " provider's signature over the raw body, not by a JWT. Redelivery is expected and"
              + " is applied at most once.")
  @APIResponse(responseCode = "200", description = "Applied, or already applied")
  @APIResponse(responseCode = "400", description = "Signature missing or did not verify")
  @APIResponse(
      responseCode = "404",
      description =
          "No such provider is deployed (PAYMENT_PROVIDER_UNKNOWN), or the event names a payment"
              + " intent of ours that is not known here (PAYMENT_WEBHOOK_INTENT_UNKNOWN): it is not"
              + " recorded as seen, so the provider delivers it again")
  @POST
  @Path("/webhooks/{provider}")
  @Consumes(MediaType.WILDCARD)
  public Response webhook(
      @PathParam("provider") String provider,
      @Context jakarta.ws.rs.core.HttpHeaders headers,
      byte[] rawBody) {
    svc.handleWebhook(provider, rawBody == null ? new byte[0] : rawBody, headers::getHeaderString);
    return Response.ok(ApiResponse.ok("accepted")).build();
  }
}
