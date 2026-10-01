package com.storeql.order.api;

import com.storeql.order.dto.Dtos.GenerateReceiptRequest;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gap #44 — Receipt / e-journal printing. Append-only log of receipt generation events (print and
 * email). Does not render the receipt itself — the frontend renders from order data.
 */
@RequestScoped
@Path("/admin/orders/{orderId}/receipts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Receipts")
public class ReceiptResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Produces a print or email receipt for a sale and records that it happened.
   *
   * <p>An email receipt is delivered before the audit row is written, so a delivery failure
   * surfaces as 503 rather than telling the cashier it emailed when it did not.
   *
   * @param orderId the sale being receipted
   * @param req the receipt type and, for {@code EMAIL}, the destination address
   * @return {@code 201} with the recorded receipt event
   * @throws com.storeql.web.ApiException {@code 400} when {@code emailedTo} is missing for an EMAIL
   *     receipt; {@code 404} when the order does not exist; {@code 503} when notification-svc is
   *     unavailable and the email was not sent
   */
  @Operation(
      summary = "Generate a receipt record",
      description =
          "Records a print or email receipt-generation event for the order. For EMAIL, builds a"
              + " plain-text receipt and delivers it via notification-svc (SMTP when configured)"
              + " before writing the audit row. emailedTo is required for EMAIL receipts.")
  @APIResponse(responseCode = "201", description = "Receipt record created")
  @APIResponse(responseCode = "400", description = "emailedTo missing for an EMAIL receipt type")
  @APIResponse(responseCode = "404", description = "Order not found")
  @APIResponse(responseCode = "503", description = "notification-svc unavailable — email not sent")
  @POST
  public Response generate(@PathParam("orderId") UUID orderId, GenerateReceiptRequest req) {
    Validations.validate(req);
    var receipt = svc.generateReceipt(ctx.requireTenantId(), orderId, req, ctx);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(receipt, svc.mayReadReceiptAddresses(ctx))))
        .build();
  }

  /**
   * Every print/email receipt produced for one sale.
   *
   * <p>Distinct from the fiscal receipt: this is the log of times a copy was produced, not the
   * numbered tax document.
   *
   * @param orderId the sale whose receipt records to read
   * @return the receipt records, empty when no copy was ever produced; the address a copy was
   *     emailed to is masked ({@code j***@example.com}) for everyone but management (OWNER,
   *     MANAGER) at the sale's store
   * @throws com.storeql.web.ApiException {@code 404} when the order does not exist; {@code 403}
   *     {@code STORE_ACCESS_DENIED} for staff not assigned to the sale's store
   */
  @Operation(
      summary = "List receipt records for an order",
      description = "All receipt-generation records (print/email) for the order.")
  @APIResponse(responseCode = "200", description = "List of receipt records")
  @APIResponse(responseCode = "404", description = "Order not found")
  @GET
  public Response list(@PathParam("orderId") UUID orderId) {
    var receipts =
        svc.listReceipts(ctx.requireTenantId(), orderId, ctx).stream()
            .map(r -> Mappers.toDto(r, svc.mayReadReceiptAddresses(ctx)))
            .toList();
    return Response.ok(ApiResponse.ok(receipts)).build();
  }
}
