package com.storeql.order.api;

import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A delivery checkout split across shops (order orchestration): one checkout, one payment, one
 * order per shop. Each part is read, fulfilled and refunded as an order under {@code /orders}.
 */
@ApplicationScoped
@Path("/order-groups")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Orders")
public class OrderGroupResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "A split checkout",
      description =
          "Its total and its parts — each an order at its own shop, the delivery-area store's"
              + " first. For the shopper who placed it, or the business's staff.")
  @APIResponse(responseCode = "200", description = "The checkout")
  @APIResponse(responseCode = "404", description = "ORDER_GROUP_NOT_FOUND")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getGroup(Parsing.uuid(id, "id"), ctx))))
        .build();
  }
}
