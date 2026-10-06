package com.storeql.cart.api;

import com.storeql.cart.dto.Dtos.AddItemRequest;
import com.storeql.cart.dto.Dtos.CartItemResponse;
import com.storeql.cart.dto.Dtos.CartResponse;
import com.storeql.cart.dto.Dtos.CartViewResponse;
import com.storeql.cart.dto.Dtos.CreateCartRequest;
import com.storeql.cart.dto.Dtos.MergeCartRequest;
import com.storeql.cart.dto.Dtos.UpdateItemQtyRequest;
import com.storeql.cart.service.CartService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Thin JAX-RS resource — validate, delegate to CartService, wrap in envelope. No logic here. */
@Path("/cart")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Cart")
public class CartResource {

  @Inject CartService service;
  @Inject TenantContext ctx;

  /** Create or get the caller's active cart. */
  @Operation(
      summary = "Create or get the active cart",
      description =
          "Returns the caller's existing ACTIVE cart, or creates a new one. Guest carts are"
              + " identified by sessionId (server-minted, never client-chosen); authenticated"
              + " carts by customerId from the JWT.")
  @APIResponse(responseCode = "200", description = "Existing or newly created active cart")
  @APIResponse(
      responseCode = "409",
      description = "The business is switched off, or the store is closed or suspended")
  @POST
  public ApiResponse<CartResponse> createOrGet(CreateCartRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.createOrGetCart(ctx, req));
  }

  /**
   * View the cart with its items. Identify the cart by cartId query param, session query param, or
   * the authenticated customer identity from the JWT.
   */
  @Operation(
      summary = "View a cart and its items",
      description =
          "Identifies the cart by cartId query param, session query param, or the authenticated"
              + " customer identity from the JWT.")
  @APIResponse(responseCode = "200", description = "Cart with its items")
  @APIResponse(
      responseCode = "400",
      description = "None of cartId, session, or an authenticated identity was supplied")
  @APIResponse(
      responseCode = "404",
      description = "Cart not found, inactive, or not owned by caller")
  @GET
  public ApiResponse<CartViewResponse> view(
      @QueryParam("cartId") String cartId, @QueryParam("session") String session) {
    return ApiResponse.ok(service.viewCart(ctx, cartId, session));
  }

  /** Add an item (or increment qty if the variant is already present). */
  @Operation(
      summary = "Add an item to the cart",
      description = "Adds an item, or increments its qty if the variant is already present.")
  @APIResponse(responseCode = "200", description = "Item added or qty incremented")
  @APIResponse(responseCode = "404", description = "Cart not found or not owned by caller")
  @APIResponse(
      responseCode = "409",
      description =
          "Cart is not active, the business is switched off, or the store is closed or suspended")
  @POST
  @Path("/items")
  public ApiResponse<CartItemResponse> addItem(AddItemRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.addItem(ctx, req));
  }

  /** Change the qty of an existing cart item. */
  @Operation(
      summary = "Update a cart item's quantity",
      description = "Sets the qty of an existing cart item.")
  @APIResponse(responseCode = "200", description = "Quantity updated")
  @APIResponse(
      responseCode = "404",
      description = "Cart not found, item not found, or item not in this cart")
  @PUT
  @Path("/items/{itemId}")
  public ApiResponse<CartItemResponse> updateQty(
      @PathParam("itemId") UUID itemId, UpdateItemQtyRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.updateItemQty(ctx, itemId, req));
  }

  /** Remove an item from the cart. */
  @Operation(summary = "Remove an item from the cart", description = "Deletes a cart item by id.")
  @APIResponse(responseCode = "204", description = "Item removed")
  @APIResponse(
      responseCode = "404",
      description = "Cart not found, item not found, or item not in this cart")
  @DELETE
  @Path("/items/{itemId}")
  public Response removeItem(
      @PathParam("itemId") UUID itemId,
      @QueryParam("cartId") String cartId,
      @QueryParam("session") String session) {
    service.removeItem(ctx, itemId, cartId, session);
    return Response.noContent().build();
  }

  /** Merge a guest cart (by sessionId) into the authenticated customer's cart. */
  @Operation(
      summary = "Merge a guest cart into the customer's cart",
      description =
          "Merges a guest cart (by sessionId) into the authenticated customer's cart, creating the"
              + " customer cart if it does not exist. The guest cart is abandoned after the merge.")
  @APIResponse(responseCode = "200", description = "Merged cart")
  @APIResponse(responseCode = "400", description = "Merge requires an authenticated user")
  @APIResponse(responseCode = "404", description = "Guest cart not found or inactive")
  @POST
  @Path("/merge")
  public ApiResponse<CartResponse> merge(MergeCartRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.mergeCart(ctx, req));
  }
}
