package com.storeql.order.api;

import com.storeql.order.dto.Dtos.AddDepositRequest;
import com.storeql.order.dto.Dtos.CreateLayawayRequest;
import com.storeql.order.dto.Dtos.VoidRequest;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Layaway management — Gap #14 POS feature. */
@Path("/layaways")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Layaways")
public class LayawayResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Opens a layaway: goods set aside against a deposit, collected once paid off.
   *
   * <p>The balance is the total less the opening deposit.
   *
   * @param req the store, customer, items and initial deposit
   * @return {@code 201} with the layaway, its items and its deposits
   * @throws com.storeql.web.ApiException {@code 400} when no items are supplied; {@code 409} when
   *     the initial deposit exceeds the total
   */
  @Operation(
      summary = "Create a layaway",
      description =
          "Opens a layaway with an initial deposit; the balance is the total minus the deposit.")
  @APIResponse(responseCode = "201", description = "Layaway created")
  @APIResponse(
      responseCode = "400",
      description =
          "No items in the layaway; VALIDATION_FAILED for a unitPrice or initialDeposit with"
              + " more decimals than the business's currency has")
  @APIResponse(responseCode = "409", description = "Initial deposit exceeds the total amount")
  @APIResponse(
      responseCode = "503",
      description =
          "TENANT_PROFILE_UNAVAILABLE: the business's currency, whose minor units the money is"
              + " kept to, is neither projected here nor readable from tenant-svc")
  @POST
  public Response create(CreateLayawayRequest req) {
    Validations.validate(req);
    var layaway = svc.createLayaway(req, ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(layaway, items, deposits)))
        .build();
  }

  /**
   * Reads one layaway with its goods and payments.
   *
   * @param id the layaway to read
   * @return the layaway, its items and its deposits
   * @throws com.storeql.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(
      summary = "Get a layaway by id",
      description = "Layaway detail with items and deposits.")
  @APIResponse(responseCode = "200", description = "Layaway found")
  @APIResponse(responseCode = "404", description = "Layaway not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    var layaway = svc.getLayaway(ctx.tenantId(), Parsing.uuid(id, "id"));
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }

  /**
   * Takes a further payment against a layaway, reducing its balance.
   *
   * @param id the layaway being paid down
   * @param req the amount, payment method and reference
   * @return the layaway with its new balance, items and deposits
   * @throws com.storeql.web.ApiException {@code 404} when the layaway does not exist
   */
  @Operation(
      summary = "Add a deposit to a layaway",
      description = "Records an additional deposit payment toward the layaway's balance.")
  @APIResponse(responseCode = "200", description = "Deposit recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED for an amount with more decimals than the business's currency has")
  @APIResponse(responseCode = "404", description = "Layaway not found")
  @APIResponse(
      responseCode = "503",
      description =
          "TENANT_PROFILE_UNAVAILABLE: the business's currency, whose minor units the money is"
              + " kept to, is neither projected here nor readable from tenant-svc")
  @POST
  @Path("/{id}/deposits")
  public Response addDeposit(@PathParam("id") String id, AddDepositRequest req) {
    Validations.validate(req);
    var layaway = svc.addDeposit(ctx.tenantId(), Parsing.uuid(id, "id"), req, ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }

  /**
   * Closes a fully paid layaway and hands the goods over, emitting {@code LayawayCompleted}.
   *
   * @param id the layaway to complete
   * @return the completed layaway with its items and deposits
   * @throws com.storeql.web.ApiException {@code 404} when the layaway does not exist; a conflict
   *     when a balance is still owed
   */
  @Operation(
      summary = "Complete a layaway",
      description = "Marks a fully-paid layaway as completed and emits LayawayCompleted.")
  @APIResponse(responseCode = "200", description = "Layaway completed")
  @APIResponse(responseCode = "404", description = "Layaway not found")
  @POST
  @Path("/{id}/complete")
  public Response complete(@PathParam("id") String id) {
    var layaway = svc.completeLayaway(ctx.tenantId(), Parsing.uuid(id, "id"), ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }

  /**
   * Cancels an active layaway, releasing the goods and emitting {@code LayawayCancelled}.
   *
   * <p>Refunding deposits already taken is a separate decision, handled through payment-svc. A
   * cancel with no body is allowed; a body that <em>is</em> sent must carry a reason.
   *
   * @param id the layaway to cancel
   * @param req the reason, or {@code null} to cancel without one
   * @return the cancelled layaway with its items and deposits
   * @throws com.storeql.web.ApiException {@code 404} when the layaway does not exist
   */
  @Operation(
      summary = "Cancel a layaway",
      description =
          "Cancels an active layaway and emits LayawayCancelled. An optional reason may be given;"
              + " if a body is sent it must include one.")
  @APIResponse(responseCode = "200", description = "Layaway cancelled")
  @APIResponse(responseCode = "404", description = "Layaway not found")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id, VoidRequest req) {
    // A cancel with no body at all is allowed (no reason given); a body that IS sent must satisfy
    // VoidRequest's @NotBlank reason rather than silently passing an empty one through.
    if (req != null) {
      Validations.validate(req);
    }
    var layaway =
        svc.cancelLayaway(
            ctx.tenantId(), Parsing.uuid(id, "id"), req != null ? req.reason() : null, ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }
}
