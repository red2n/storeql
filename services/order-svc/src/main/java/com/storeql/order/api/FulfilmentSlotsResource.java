package com.storeql.order.api;

import com.storeql.order.dto.Dtos.FulfilmentSlotsResponse;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.FulfilmentWindowService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The storefront's read of a store's delivery and collection windows
 * (intent/delivery-and-collection-slots.md): public, no staff role — the tenant comes from {@code
 * X-Tenant-Id}, which the gateway resolves from {@code X-Storefront-Tenant} or a signed-in
 * shopper's token before this ever runs. Nested under {@code /storefront}, which {@code
 * AdminAuthorizationFilter.isOpenRead} already admits.
 */
@ApplicationScoped
@Path("/storefront/fulfilment-slots")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Storefront")
public class FulfilmentSlotsResource {

  @Inject FulfilmentWindowService svc;
  @Inject TenantContext ctx;

  /**
   * The next seven days of one store's windows of one fulfilment type, in the store's own time,
   * with what each occurrence has left.
   *
   * @param store the store to read; required
   * @param type {@code DELIVERY} or {@code PICKUP}; required
   * @throws com.storeql.web.ApiException 400 {@code ORDER_SLOT_STORE_REQUIRED}, {@code
   *     ORDER_SLOT_TYPE_INVALID}; 404 {@code ORDER_SLOT_STORE_NOT_FOUND} (not this business's store
   *     — another business's store id included)
   */
  @Operation(
      summary = "A store's next seven days of delivery or collection windows",
      description =
          "Today and the next six days, in the store's own zone, each occurrence with what it has"
              + " left; a full one says so, one past its cut-off is left out. offered is false when"
              + " the store has no active window of this type.")
  @APIResponse(responseCode = "200", description = "Seven days of occurrences")
  @APIResponse(
      responseCode = "400",
      description = "ORDER_SLOT_STORE_REQUIRED, ORDER_SLOT_TYPE_INVALID")
  @APIResponse(responseCode = "404", description = "ORDER_SLOT_STORE_NOT_FOUND")
  @GET
  public ApiResponse<FulfilmentSlotsResponse> slots(
      @QueryParam("store") String store, @QueryParam("type") String type) {
    var view = svc.slotsFor(ctx.requireTenantId(), store, type);
    return ApiResponse.ok(Mappers.toDto(view));
  }
}
